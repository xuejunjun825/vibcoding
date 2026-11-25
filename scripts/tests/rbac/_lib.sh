#!/usr/bin/env bash
# _lib.sh — s3~s8 共用的「改授权 → 打探针 → 还原」助手（**不是段脚本**，不被 rbac-verify.sh 直接执行）
#
# 用法：source 本文件之前先 source scripts/lib.sh（拿 api_call/platform_login/HTTP_CODE）。
# 探针固定为「空 body 打真实写端点」：被拦截是 403，放行则落到参数校验 400——
# 「放行」的证据不需要真的建出数据，段脚本可以反复跑而不污染库。
#
# 可覆盖（跨段复用同一探针口径）：RBAC_PROBE_METHOD / RBAC_PROBE_PATH / RBAC_PROBE_BODY
#                                    RBAC_PAGE / RBAC_ACTION
#
# r36 起本文件同时是「段级临时主体的造名与清理」的唯一实现：
#   rbac_run_tag / rbac_purge_users <prefix> / rbac_purge_roles <prefix>（见文件末尾）。
# 段脚本只保留自己那半截——还原被改的授权、删组织——不再各写一份造名与清理的副本。
# 收敛的理由是一次真实事故：五份近似 cleanup、造名靠 $RANDOM，s7 撞上历史同名 DISABLED 号后
# 建号 409 被吞、随后 login_as 以 403 USER_DISABLED die（报错点离真因隔两步），并留下 527 条残号。

RBAC_PROBE_METHOD="${RBAC_PROBE_METHOD:-POST}"
RBAC_PROBE_PATH="${RBAC_PROBE_PATH:-/api/deliver/test-plans}"
RBAC_PROBE_BODY="${RBAC_PROBE_BODY:-{\}}"
RBAC_PAGE="${RBAC_PAGE:-test.plans}"
RBAC_ACTION="${RBAC_ACTION:-test.action.plan_create}"
RBAC_ADMIN_USER="${RBAC_ADMIN_USER:-admin}"
RBAC_ADMIN_PASS="${RBAC_ADMIN_PASS:-Admin@12345}"

login_as() { # login_as <user> <pass> → 设置 API_TOKEN/API_TENANT
    platform_login t1 "$1" "$2" >/dev/null || return 1
    [[ -n "${API_TOKEN:-}" ]]
}

admin_login() { login_as "$RBAC_ADMIN_USER" "$RBAC_ADMIN_PASS"; }

probe_status() { # 以当前 API_TOKEN 打探针，回显状态码
    api_call "$RBAC_PROBE_METHOD" "$RBAC_PROBE_PATH" "$RBAC_PROBE_BODY" >/dev/null
    printf '%s' "${HTTP_CODE:-000}"
}

probe_settle() { # probe_settle <403|allow> [超时秒=70] → 轮询探针直到进入期望态，回显最终状态码
    # 为什么需要轮询：授权行由 req-service 写入，探针打的是 deliver-service —— 两个 JVM 各有
    # 一份按租户的快照（platform.rbac.cache-ttl-seconds 默认 45s），invalidate(tenantId) 只对
    # 写请求所在的进程 epoch+1。同进程的「下一次请求即生效」由 s4 的 epoch 断言覆盖；跨进程的
    # 传播上界就是 TTL，这里等它，不把「还没传播到」误判成「缓存没有失效」。
    local want="$1" limit="${2:-70}" waited=0 got
    while true; do
        got="$(probe_status)" || true
        if [[ "${want}" == "403" ]]; then
            if [[ "${got}" == "403" ]]; then
                printf '%s' "${got}"
                return 0
            fi
        else
            if [[ "${got}" != "403" && "${got}" != "000" ]]; then
                printf '%s' "${got}"
                return 0
            fi
        fi
        if [[ "${waited}" -ge "${limit}" ]]; then
            # 超时不算「脚本出错」：回显最后状态码并正常返回，由调用方的 if 判 FAIL 并给出原因。
            # 这里若 return 1，调用点 `CODE1="$(probe_settle 403)"` 在 set -e 下会**静默中止整段**
            # 脚本（没有 FAIL 行、没有 report_counts），失败原因反而丢失。
            printf '%s' "${got}"
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
}

grants_of() { # grants_of <roleId> → 扁平 [{subjectType,subjectCode,access}]（每角色 180 个唯一格子）
    # 口径必须与库里的格子一一对应：s3/s4/s7 的 trap 用这份快照还原，少一格就等于把
    # 那一格主动写成 DENY（写侧基底取库内现值，少发=保留；但这份快照就是「库内现值」的唯一来源）。
    #   1) modules[].pages[] 给 MENU 38 + ACTION 81 + 37 行页面绑定的 FUNCTION
    #      （38 页里 pdr.review 绑定码为空，故绑定行仍是 37 行）；
    #      其中 6 行是同一个功能码被多个页面共用（org:manage 5 页、PORTAL_VIEW 2 页、
    #      case:manage 2 页），unique_by 之后是 31 个不同格子 ⇒ 150 个唯一格子；
    #   2) 顶层 unboundFunctions.functions 给不属任何页面的 30 个 FUNCTION 格子；
    #   150 + 30 = 180 = sys_role_permission 每角色的行数（s2 的等式）。
    # 不 unique_by 会发出 186 行（6 个重复键）：服务端按 (type,code) 归并后结果相同，
    # 但「payload 行数 = 库行数 = 180」这个可断言的等式就丢了，探针也无法用长度判缺口。
    api_call GET "/api/req/admin/roles/$1/permissions"
    [[ "${HTTP_CODE:-}" == "200" ]] || return 1
    printf '%s' "$HTTP_BODY" | jq -c '([.modules[]? | .pages[]? as $p
        | ($p.functionCodes[]? | {subjectType:"FUNCTION", subjectCode:.code, access:.access}),
          ($p | {subjectType:"MENU", subjectCode:$p.code, access:$p.access}),
          ($p.actions[]? | {subjectType:"ACTION", subjectCode:.code, access:.access})]
        + [(.unboundFunctions?.functions[]? | {subjectType:"FUNCTION", subjectCode:.code, access:.access})])
        | unique_by([.subjectType, .subjectCode])'
}

set_cell() { # set_cell <grants> <TYPE> <code> <access>
    printf '%s' "$1" | jq -c --arg t "$2" --arg c "$3" --arg a "$4" \
        'map(if .subjectType == $t and .subjectCode == $c then .access = $a else . end)'
}

get_cell() { # get_cell <grants> <TYPE> <code> → 当前档位（缺行回显 MISSING）
    printf '%s' "$1" | jq -r --arg t "$2" --arg c "$3" \
        '[.[] | select(.subjectType == $t and .subjectCode == $c) | .access] | first // "MISSING"'
}

put_grants() { # put_grants <roleId> <grants-json> [confirmHighRisk] → 回显状态码
    local body
    body="$(jq -n --argjson g "$2" --argjson ok "${3:-true}" '{grants: $g, confirmHighRisk: $ok}')"
    api_call PUT "/api/req/admin/roles/$1/permissions" "$body" >/dev/null
    printf '%s' "${HTTP_CODE:-000}"
}

put_grants_code() { # put_grants_code <roleId> <grants-json> [confirmHighRisk] → 回显 "<状态码>|<业务码>"
    # 状态码与响应体必须从同一次请求取：调用点常写成 X="$(put_grants ...)"，
    # 子 shell 内更新的 HTTP_BODY 不会回传，父 shell 读到的是上一条请求的旧 body。
    local body
    body="$(jq -n --argjson g "$2" --argjson ok "${3:-true}" '{grants: $g, confirmHighRisk: $ok}')"
    api_call PUT "/api/req/admin/roles/$1/permissions" "$body" >/dev/null
    printf '%s|%s' "${HTTP_CODE:-000}" \
        "$(printf '%s' "${HTTP_BODY:-}" | jq -r '.code // empty' 2>/dev/null)"
}

role_id_of() { # role_id_of <code>
    api_call GET /api/req/admin/roles
    printf '%s' "$HTTP_BODY" | jq -r --arg c "$1" '.[]? | select(.code == $c) | .id' | head -n1
}

role_row_of() { # role_row_of <code> → 整行 JSON（用户数/内置/状态）
    api_call GET /api/req/admin/roles
    printf '%s' "$HTTP_BODY" | jq -c --arg c "$1" '.[]? | select(.code == $c)' | head -n1
}

user_id_of() { # user_id_of <username>
    api_call GET "/api/req/admin/users?keyword=$1&size=10"
    printf '%s' "$HTTP_BODY" | jq -r --arg u "$1" '(.items // .records // [])[]? | select(.username == $u) | .id' | head -n1
}

epoch_now() { # epoch_now → /auth/me/permissions 的 epoch（失败回显 0）
    api_call GET /api/req/auth/me/permissions
    printf '%s' "$HTTP_BODY" | jq -r '.epoch // 0' 2>/dev/null | head -n1
}

audit_count() { # audit_count <action 前缀> [biz_type]
    local where="action LIKE '${1}%'"
    [[ -n "${2:-}" ]] && where="${where} AND biz_type = '${2}'"
    db_q "SELECT COUNT(*) FROM audit_log WHERE ${where}" | head -n1
}

# ===================== r36：段级临时主体的造名与清理（s3~s8 唯一实现） =====================
# 依赖方向不变：lib.sh → _lib.sh → 段 → rbac-verify.sh → gate-order-verify.sh。
# 这里只回答两个问题：名字怎么起（跨轮不撞名）、东西怎么删干净（删不掉要红）。
# 各段仍自己负责「还原被改的授权」「删组织」——那两件事改的格子/对象各不相同，收不到这一层。

rbac_run_tag() { # rbac_run_tag → 本次运行的全数字 tag（RBAC_RUN_TAG 可覆盖，QA 用它造撞名复现）
    # 全数字不是洁癖：platform-demo-reset.sh 的停用兜底正则
    #   ^(s[0-9]+[a-z]*u|t[0-9]+[a-z]*|probe)[0-9]*$
    # 只在 tag 为纯数字时才能匹配 sNu<tag>。也就是说 trap 万一被 kill -9 跳过（EXIT 不执行），
    # 还能靠 reset 把这些号停用；带字母的 tag 则两边都救不了（历史 s7a*/s7b* 就是这么漏的）。
    printf '%s' "${RBAC_RUN_TAG:-$(date +%m%d%H%M%S)$$$RANDOM}"
}

rbac_purge_users() { # rbac_purge_users <prefix> → stdout 删除的用户行数；残留 0 条才 return 0
    # 用户没有 DELETE API（H5 明确不做），所以直连 DB —— 与 s5 第 10 条断言先断关系行、
    # platform-verify 删种子号的写法同源。按**段族前缀**（s3/s5/s6/s7/s8）而不是本轮 tag 删：
    # 否则 527 条历史残留永远清不掉，「跑完 0 条」这条收尾断言就立不住。
    local prefix="$1" tid before after
    [[ -n "${prefix}" ]] || { err "rbac_purge_users: 缺前缀"; return 1; }
    tid="$(db_q "SELECT id FROM sys_tenant WHERE code = '${PLATFORM_TENANT:-t1}'" | head -n1)"
    [[ -n "${tid}" ]] || { err "rbac_purge_users: 取不到租户 ${PLATFORM_TENANT:-t1} 的 id：先 make platform-up"; return 1; }
    before="$(db_q "SELECT COUNT(*) FROM sys_user WHERE tenant_id = ${tid} AND username REGEXP '^${prefix}[a-z0-9]*$'" | head -n1)"
    [[ -n "${before}" ]] || { err "rbac_purge_users: 数不到 ${prefix} 前缀的临时账号：DB 不可读"; return 1; }
    # 顺序是硬约束：sys_user_role / sys_user_domain 都是 (user_id, x) 主键、无外键指向 sys_user，
    # 先删主体就会留下点不到人的孤儿行（审计与角色用户数都会跟着错）。
    db_q "DELETE ur FROM sys_user_role ur JOIN sys_user u
            ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
          WHERE u.tenant_id = ${tid} AND u.username REGEXP '^${prefix}[a-z0-9]*$'" >/dev/null
    db_q "DELETE ud FROM sys_user_domain ud JOIN sys_user u
            ON u.id = ud.user_id AND u.tenant_id = ud.tenant_id
          WHERE u.tenant_id = ${tid} AND u.username REGEXP '^${prefix}[a-z0-9]*$'" >/dev/null
    db_q "DELETE FROM sys_user WHERE tenant_id = ${tid} AND username REGEXP '^${prefix}[a-z0-9]*$'" >/dev/null
    # 删完必须复核：db_q 把 mysql 的 stderr 丢了，只看返回码会把「连接断了」当成功。
    after="$(db_q "SELECT COUNT(*) FROM sys_user WHERE tenant_id = ${tid} AND username REGEXP '^${prefix}[a-z0-9]*$'" | head -n1)"
    if [[ "${after}" != "0" ]]; then
        err "rbac_purge_users: 前缀 ${prefix} 仍残留 ${after:-?} 个临时账号（DB 读不到或 DELETE 被拒）"
        printf '%s' "${before:-0}"
        return 1
    fi
    # after == 0，所以「删掉的条数」就是 before（不写算术式：前导零会被 $(( )) 当八进制）。
    printf '%s' "${before}"
}

rbac_purge_roles() { # rbac_purge_roles <prefix> → stdout 删除的角色数；残留 0 条才 return 0
    # 角色有 DELETE /admin/roles/{id}，走 API 而不是直连：删角色要级联 sys_role_permission 并写审计，
    # 只有 RoleAdminService 那一份实现保证得了。前置条件（服务端口径）只有两条：非内置 + 没有用户挂着。
    # 所以调用方必须**先 rbac_purge_users 再这里**——顺序反了就是 409 ROLE_IN_USE（本次事故的第二个根因）。
    local prefix="$1" id n=0 left
    [[ -n "${prefix}" ]] || { err "rbac_purge_roles: 缺前缀"; return 1; }
    admin_login >/dev/null || { err "rbac_purge_roles: admin 登录失败：先 make platform-up && make platform-seed"; return 1; }
    api_call GET /api/req/admin/roles >/dev/null
    if [[ "${HTTP_CODE:-000}" != "200" ]]; then
        err "rbac_purge_roles: GET /admin/roles 回 ${HTTP_CODE:-000}，枚举不到角色"
        return 1
    fi
    for id in $(printf '%s' "$HTTP_BODY" | jq -r --arg p "$prefix" '.[]? | select((.code // "") | startswith($p)) | .id' 2>/dev/null); do
        [[ -n "${id}" && "${id}" != "null" ]] || continue
        api_call DELETE "/api/req/admin/roles/${id}" >/dev/null
        case "${HTTP_CODE:-000}" in
            200|204|404) n=$((n + 1)) ;;
            *)  err "rbac_purge_roles: DELETE 角色 ${id} 回 ${HTTP_CODE:-000}（409=还有用户挂着它：先 rbac_purge_users 再删角色）"
                printf '%s' "${n}"
                return 1 ;;
        esac
    done
    left="$(db_q "SELECT COUNT(*) FROM sys_role WHERE code LIKE '${prefix}%'" | head -n1)"
    if [[ "${left}" != "0" ]]; then
        err "rbac_purge_roles: 前缀 ${prefix} 仍残留 ${left:-?} 个角色"
        printf '%s' "${n}"
        return 1
    fi
    printf '%s' "${n}"
}

# 同一进程里所有函数/段共用一把 tag（段脚本仍会显式调一次 rbac_run_tag 声明依赖，
# 也便于 gate-order 的静态断言 grep 到它）；环境变量 RBAC_RUN_TAG 优先，供 QA 固定 tag 复现撞名。
RBAC_RUN_TAG="$(rbac_run_tag)"
