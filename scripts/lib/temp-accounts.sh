#!/usr/bin/env bash
# temp-accounts.sh — 门禁临时账号的「白名单 / 保护名单 / 清理」单点真源（r37，AC-1..AC-4）。
# Source it, do not execute it.
#
# 背景：门禁（rbac 五段、r18 探针、r29 探针）会在库里留下 s3~s8 / t6p* / probe* 前缀的临时账号。
# Java 侧 DemoFixture 只把临时号改成 status='DISABLED'（行还在），真正的删除只能由 shell 做。
# r36 之前每个消费方各写一份「前缀 + 删除」：断言查的与清理删的各自演化，攒下 527 条残号；
# r37 把这些前缀、保护名单与清理收敛到本文件，四个消费方共用同一份正则。
#
# 依赖方向单向：lib.sh（db_q / db_up）→ 本模块 → {orphan-join-purge.sh, legacy-probe-purge.sh, gate-order-verify.sh, platform-demo-reset.sh}
# 本文件**不** source lib.sh：账号白名单是门禁语义而非通用环境层，塞进被 30+ 脚本 source 的 lib.sh
# 会污染所有调用方；调用方先 source scripts/lib.sh，再 source 本文件。
#
# 用法：
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/temp-accounts.sh"
#   temp_accounts_db_ready || exit 1
#   temp_accounts_guard    || exit 1          # 保护名单自检 + 种子齐全，失败即零删除
#   n="$(temp_purge_disabled)" || exit 1      # stdout 只给删除条数
#   w="$(temp_orphan_join_where sys_user_domain d)" || exit 1   # 查/删孤儿共用的唯一谓词
#   temp_orphan_join_left                     # → 恰好一行 orphans_role=<n> orphans_domain=<n>
#
# 约定：所有计数/列表函数只在 stdout 打一个数字（或每行一个 username），失败一律 return 1 并在 stderr 说明。
#      唯一例外是 temp_orphan_join_left()：它打一整行两个计数，下游整行比较，禁止 awk/cut 取 token。

if [[ -n "${TEMP_ACCOUNTS_LIB_LOADED:-}" ]]; then
    return 0
fi
TEMP_ACCOUNTS_LIB_LOADED=1

# ---- 白名单前缀：全仓唯一生成点，新增探针只加一行数组元素 ----
TEMP_ACCOUNT_PATTERNS=(
    's[3-8]'    # r36: scripts/tests/rbac/s3..s8 五段各建一个 s3*/s5*/s6*/s7*/s8* 临时用户
    't6p'       # r18: 11-13 T6 探针 t6pd* / t6pe* / t6probeu 的公共前缀（遗留 3 条）
    't6probe'   # r18: T6 探针显式前缀（与 t6p 有重叠也无害：正则取并集，删的是同一批行）
    'probe'     # r17/r29: probe<数字> 探针账号（11-17 遗留 probe11272）
)
_temp_alts="$(IFS='|'; printf '%s' "${TEMP_ACCOUNT_PATTERNS[*]}")"
TEMP_ACCOUNT_REGEX="^(${_temp_alts})[a-z0-9]*\$"   # 结尾 $ 必须转义：双引号内裸 $ 会被当变量展开成空
unset _temp_alts

# ---- 角色前缀：门禁自建角色（S5/S7/S8）的门禁侧口径 ----
TEMP_ROLE_PREFIXES=('S5' 'S7' 'S8')
_temp_alts="$(IFS='|'; printf '%s' "${TEMP_ROLE_PREFIXES[*]}")"
TEMP_ROLE_REGEX="^(${_temp_alts})"
unset _temp_alts

# ---- 保护名单：命中即拒（守卫）且 SQL 侧 NOT IN 再兜底，14 名种子账号零删除 ----
TEMP_PROTECTED_USERS=(admin pm tl1 po1 biz1 suite1 dev1 dev2 qa1 qa2 vendor1 vendor2 mgr1 vpm1)
_temp_sql=''
for _temp_u in "${TEMP_PROTECTED_USERS[@]}"; do
    _temp_sql+="'${_temp_u}',"
done
TEMP_PROTECTED_SQL="(${_temp_sql%,})"
unset _temp_sql _temp_u

# 测试注入缝：空格或逗号分隔的额外名（如 TEMP_PROTECT_EXTRA=probe123456），只进守卫，不参与删除
TEMP_PROTECT_EXTRA="${TEMP_PROTECT_EXTRA:-}"
# 角色计数与保护账号计数的租户口径（用户/删除是全库，见 TECHNICAL §11 Q1）
TEMP_TENANT_CODE="${PLATFORM_TENANT:-t1}"

_temp_err() { printf '[temp-accounts] %s\n' "$*" >&2; }

# 计数闸门：db_q 把 mysql 的 stderr 丢掉，DB 不可连时返回空串而不报错——空串被当 0 就是假绿。
# 唯一出口是「首行必须匹配 ^[0-9]+$」，否则 return 1（AC-2「DB 不可连必须 exit 1」靠它）。
_temp_count() { # _temp_count <sql> <label> → stdout 一个非负整数
    local sql="$1" label="$2" out
    out="$(db_q "${sql}" 2>/dev/null | head -n1)" || true
    if [[ ! "${out}" =~ ^[0-9]+$ ]]; then
        _temp_err "${label}：返回 '${out}' 不是计数（DB 不可连或查询失败：先 make platform-up，连接参数用 PLATFORM_DB_* 覆盖）"
        return 1
    fi
    printf '%s\n' "${out}"
}

# 租户 id：只有角色计数与保护账号计数限定租户（t1），用户/删除全库
_temp_tenant_id() {
    _temp_count "SELECT id FROM sys_tenant WHERE code = '${TEMP_TENANT_CODE}'" "租户 ${TEMP_TENANT_CODE} 的 id"
}

temp_accounts_db_ready() {
    if ! declare -F db_q >/dev/null 2>&1 || ! declare -F db_up >/dev/null 2>&1; then
        _temp_err '缺少 db_q / db_up：请先 source scripts/lib.sh，再 source scripts/lib/temp-accounts.sh'
        return 1
    fi
    if ! db_up; then
        _temp_err "MySQL 不可连（${PLATFORM_DB_HOST:-?}:${PLATFORM_DB_PORT:-?}/${PLATFORM_DB_NAME:-?}）：先 make platform-up；连接参数用 PLATFORM_DB_* 覆盖（如 PLATFORM_DB_PORT=3306）"
        return 1
    fi
    return 0
}

temp_accounts_guard() {
    local u hit='' extra
    # ① 保护名单不得落在白名单动词里：命中就点名报错，删前退出（可见失败；SQL 的 NOT IN 只是兜底）
    for u in "${TEMP_PROTECTED_USERS[@]}"; do
        if [[ "${u}" =~ ${TEMP_ACCOUNT_REGEX} ]]; then
            hit+="${u} "
        fi
    done
    extra="${TEMP_PROTECT_EXTRA//,/ }"
    for u in ${extra}; do
        if [[ "${u}" =~ ${TEMP_ACCOUNT_REGEX} ]]; then
            hit+="${u}(TEMP_PROTECT_EXTRA) "
        fi
    done
    if [[ -n "${hit}" ]]; then
        _temp_err "保护账号命中临时账号正则 ${TEMP_ACCOUNT_REGEX}：${hit%；}——拒绝清理（改 TEMP_ACCOUNT_PATTERNS 或保护名单，不要放宽删除）"
        return 1
    fi
    # ② 14 名种子必须仍在（缺了说明没 seed，此时删任何东西都是误删现场）
    local cnt want
    want="${#TEMP_PROTECTED_USERS[@]}"
    cnt="$(temp_protected_active_count)" || return 1
    if [[ "${cnt}" != "${want}" ]]; then
        _temp_err "保护账号不全：租户 ${TEMP_TENANT_CODE} 内 ACTIVE ${cnt}/${want}；缺失=$(_temp_missing_protected)（先 make platform-seed 补齐种子）"
        return 1
    fi
    return 0
}

# 缺失的保护账号名（空格分隔，供 guard 的错误信息点名用；查不到则输出 '查询失败'）
_temp_missing_protected() {
    local tid have
    tid="$(_temp_tenant_id)" || { printf '查询失败'; return 0; }
    have="$(db_q "SELECT username FROM sys_user WHERE tenant_id = ${tid} AND status = 'ACTIVE' AND username IN ${TEMP_PROTECTED_SQL}" 2>/dev/null)" || true
    local u out=''
    for u in "${TEMP_PROTECTED_USERS[@]}"; do
        if ! printf '%s\n' "${have}" | grep -qx -- "${u}"; then
            out+="${u} "
        fi
    done
    printf '%s' "${out%; }"
}

temp_users_left() { # 白名单前缀的用户条数：全库、任意状态
    _temp_count "SELECT COUNT(*) FROM sys_user WHERE username REGEXP '${TEMP_ACCOUNT_REGEX}'" '白名单前缀用户（任意状态）'
}

temp_disabled_left() { # 白名单前缀 + 已停用：全库（清理与断言共用的口径）
    _temp_count "SELECT COUNT(*) FROM sys_user WHERE username REGEXP '${TEMP_ACCOUNT_REGEX}' AND status = 'DISABLED'" '停用临时账号'
}

temp_disabled_usernames() { # 每行一个 username（ORDER BY id），供错误信息打印
    local out
    out="$(db_q "SELECT username FROM sys_user WHERE username REGEXP '${TEMP_ACCOUNT_REGEX}' AND status = 'DISABLED' ORDER BY id" 2>/dev/null)" || return 1
    if [[ -n "${out}" ]]; then
        printf '%s\n' "${out}"
    fi
    return 0
}

temp_protected_active_count() { # 租户内 14 名种子里 ACTIVE 的条数，期望 14
    local tid
    tid="$(_temp_tenant_id)" || return 1
    _temp_count "SELECT COUNT(*) FROM sys_user WHERE tenant_id = ${tid} AND status = 'ACTIVE' AND username IN ${TEMP_PROTECTED_SQL}" \
        "租户 ${TEMP_TENANT_CODE} 内保护账号 ACTIVE"
}

# ---- 孤儿 join 行：判定与删除共用的唯一谓词生成点（r38，AC-1..AC-3）----
# 背景：r36 残留 527 条、r37 遗留 413 条，根因都是这段谓词被复制到多处后各自演化。
# 因此凡「查孤儿」与「删孤儿」的 SQL，都必须由本函数渲染，禁止任何一处手写 LEFT JOIN。
# 返回值只含 JOIN + WHERE（FROM 由调用方用自己的表名/别名拼），**恰好一行**：
#   LEFT JOIN sys_user u ON u.id = <alias>.user_id AND u.tenant_id = <alias>.tenant_id WHERE u.id IS NULL
# 语义是「同租户无主」：比「全库无此 user_id」更强，跨租户错配的 join 行也会被判红。
temp_orphan_join_where() { # temp_orphan_join_where <join_table> <alias>
    local table="${1:-}" alias="${2:-}"
    # 表名参与校验而非渲染：本谓词只引用别名。登记制避免把谓词指到非 join 表上（那会静默查出 0）。
    if [[ ! "${table}" =~ ^(sys_user_role|sys_user_domain)$ ]]; then
        _temp_err "temp_orphan_join_where：未知的 join 表 '${table}'（仅支持 sys_user_role / sys_user_domain；新增表请先在这里登记）"
        return 1
    fi
    if [[ ! "${alias}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ || "${alias}" == 'u' ]]; then
        _temp_err "temp_orphan_join_where：非法别名 '${alias}'（须匹配 ^[A-Za-z_][A-Za-z0-9_]*\$ 且不能是 u——u 已被 sys_user 占用）"
        return 1
    fi
    printf 'LEFT JOIN sys_user u ON u.id = %s.user_id AND u.tenant_id = %s.tenant_id WHERE u.id IS NULL\n' \
        "${alias}" "${alias}"
}

temp_orphan_role_left() { # sys_user_role 孤儿条数，期望 0
    local where
    where="$(temp_orphan_join_where sys_user_role ur)" || return 1
    _temp_count "SELECT COUNT(*) FROM sys_user_role ur ${where}" 'sys_user_role 孤儿行'
}

temp_orphan_domain_left() { # sys_user_domain 孤儿条数（r38 起纳入判据：历史上攒了 413 条），期望 0
    local where
    where="$(temp_orphan_join_where sys_user_domain d)" || return 1
    _temp_count "SELECT COUNT(*) FROM sys_user_domain d ${where}" 'sys_user_domain 孤儿行'
}

temp_orphan_role_users() { # sys_user_role 孤儿行涉及的 distinct user_id 个数（dry-run 供人工核对）
    local where
    where="$(temp_orphan_join_where sys_user_role ur)" || return 1
    _temp_count "SELECT COUNT(DISTINCT ur.user_id) FROM sys_user_role ur ${where}" 'sys_user_role 孤儿的 distinct user_id'
}

temp_orphan_domain_users() { # sys_user_domain 孤儿行涉及的 distinct user_id 个数（历史上是 59）
    local where
    where="$(temp_orphan_join_where sys_user_domain d)" || return 1
    _temp_count "SELECT COUNT(DISTINCT d.user_id) FROM sys_user_domain d ${where}" 'sys_user_domain 孤儿的 distinct user_id'
}

temp_orphan_join_left() { # → stdout 恰好一行：orphans_role=<n> orphans_domain=<n>，期望 0 0
    # 整行契约：下游（legacy-probe-purge / gate-order）只做整串比较，不解析 token。
    # 两个分量都先算进变量，任一失败就 return 1——绝不输出半行（半行会被下游当合法值）。
    local role domain
    role="$(temp_orphan_role_left)" || return 1
    domain="$(temp_orphan_domain_left)" || return 1
    printf 'orphans_role=%s orphans_domain=%s\n' "${role}" "${domain}"
}

temp_roles_left() { # 租户内门禁自建角色（^(S5|S7|S8)）条数，期望 0
    local tid
    tid="$(_temp_tenant_id)" || return 1
    _temp_count "SELECT COUNT(*) FROM sys_role WHERE tenant_id = ${tid} AND code REGEXP '${TEMP_ROLE_REGEX}'" \
        "租户 ${TEMP_TENANT_CODE} 内临时角色"
}

temp_purge_disabled() { # → stdout 删除的用户条数；任一步失败 return 1（禁止把失败当 0）
    temp_accounts_guard || return 1

    # 删前抓 id：三删之后要复核「这批 id」的 join 行 0（不动 413 条历史域孤儿）。
    # 整条谓词只生成一次（status='DISABLED' + 正则 + 保护名单兜底）：抓 id、三删、复核共用它，
    # 于是「断言查的」与「清理删的」不可能各自演化。
    local before ids id_list join_left left _temp_where
    _temp_where="status = 'DISABLED' AND username REGEXP '${TEMP_ACCOUNT_REGEX}' AND username NOT IN ${TEMP_PROTECTED_SQL}"
    before="$(_temp_count "SELECT COUNT(*) FROM sys_user WHERE ${_temp_where}" '待删停用临时账号')" || return 1
    ids="$(db_q "SELECT id FROM sys_user WHERE ${_temp_where} ORDER BY id" 2>/dev/null)" || {
        _temp_err '抓不到待删用户 id：DB 不可读（先 make platform-up）'
        return 1
    }
    id_list="$(printf '%s' "${ids}" | tr '\n' ',' | sed 's/,$//')"

    # 三条 DELETE 的 WHERE 逐字相同（同一个 ${_temp_where}），顺序即依赖序：join 行 → 主表。
    db_q "DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE ${_temp_where})" >/dev/null 2>&1 || {
        _temp_err '删除 sys_user_role 关系失败（DB 拒写）：中止，未删用户'
        return 1
    }
    db_q "DELETE FROM sys_user_domain WHERE user_id IN (SELECT id FROM sys_user WHERE ${_temp_where})" >/dev/null 2>&1 || {
        _temp_err '删除 sys_user_domain 关系失败（DB 拒写）：中止，未删用户'
        return 1
    }
    db_q "DELETE FROM sys_user WHERE ${_temp_where}" >/dev/null 2>&1 || {
        _temp_err '删除 sys_user 失败（DB 拒写）'
        return 1
    }
    unset _temp_where

    # 复核①：本次删除 id 集合内不得再留 join 行
    if [[ -n "${id_list}" ]]; then
        join_left="$(_temp_count "SELECT (SELECT COUNT(*) FROM sys_user_role WHERE user_id IN (${id_list})) + (SELECT COUNT(*) FROM sys_user_domain WHERE user_id IN (${id_list}))" '本次删除 id 的 join 行')" || return 1
        if [[ "${join_left}" != '0' ]]; then
            _temp_err "本次删除的 ${before} 个用户仍留 ${join_left} 条 join 行（sys_user_role/sys_user_domain）：先排查外键与并发写"
            return 1
        fi
    fi
    # 复核②：删后同口径计数必须归零（不是「删了 N 条」就算完，可能还有并发新建）
    left="$(temp_disabled_left)" || return 1
    if [[ "${left}" != '0' ]]; then
        _temp_err "删后仍有 ${left} 条停用临时账号（可能有并发写入）：$(temp_disabled_usernames | head -5 | tr '\n' ' ')"
        return 1
    fi
    printf '%s\n' "${before}"
}
