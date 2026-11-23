#!/usr/bin/env bash
# orphan-join-purge.sh — 一次性清理 sys_user_role / sys_user_domain 的「无主」join 行（r38，TECH-CHANGE §3.2）。
#
# 为什么需要它：r38 起孤儿判定扩到两张 join 表，历史库攒下的 413 条 sys_user_domain 孤儿
# （59 个已不存在的 user_id）必须清一次；gate-order 第 20 条只断言不自愈，失败信息就指向本脚本。
#
# 边界：
#   - 只删 join 行，绝不删 sys_user / sys_role；因此**不调** temp_accounts_guard——那 14 名保护名单
#     是「批量停用账号」的白名单闸门，孤儿行按定义无主，套 guard 只会让残留永远清不掉。
#     只要求 temp_accounts_db_ready（DB 不可连必须 exit 1，不能把数不到的 0 当成功）。
#   - 删除不可逆，所以默认 dry-run 只报数；--apply 才写库，且删除集与断言集由同一个谓词渲染
#     （temp_orphan_join_where：LEFT JOIN sys_user ... WHERE u.id IS NULL），不允许在这里手写 SQL 片段。
#   - deleted_* 用「删前 - 删后」的同一计数器计算，不依赖 mysql CLI 的 affected rows（它会把 0 混淆成空）。
#
# 用法：
#   bash scripts/orphan-join-purge.sh            # dry-run：role/domain 及各表 distinct user 数，不写库
#   bash scripts/orphan-join-purge.sh --apply    # 实删 + 删后复核，复核不为 0 则 exit 1
# 退出码：0 = dry-run 跑通（有孤儿也算 0，它只是报数）/ apply 后两表归零；
#         1 = DB 不可连、计数失败、删除失败或删后复核非 0；
#         2 = 未知参数（用法错误，不碰 DB）。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 显式端口覆盖必须在 source lib.sh **之前**抓住：lib.sh 的 _platform_db_parse 会按 PLATFORM_DB_URL
# 重算 PLATFORM_DB_PORT，之后再单设 PLATFORM_DB_PORT=1 会被静默丢弃——「DB 不可连必须 exit 1」
# 的验收就成了假绿。同一前置技巧的真源与来龙去脉在 scripts/legacy-probe-purge.sh:19-27，别在这里省掉。
_DB_PORT_OVERRIDE="${PLATFORM_DB_PORT:-}"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib.sh"
if [[ -n "${_DB_PORT_OVERRIDE}" ]]; then
    PLATFORM_DB_PORT="${_DB_PORT_OVERRIDE}"
fi
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/temp-accounts.sh"

_ojp_usage() { # 未知参数：用法打到 stderr，返回 2（main 原样透出；不碰 DB）
    cat >&2 <<'EOF'
用法：bash scripts/orphan-join-purge.sh            # dry-run：只报数，不写库
      bash scripts/orphan-join-purge.sh --apply    # 实删孤儿 join 行并复核归零
说明：清理 sys_user_role / sys_user_domain 中「同租户 join 不到 sys_user」的行；
      只删 join 行，不动 sys_user / sys_role；删除不可逆，先跑 dry-run 核对再 --apply。
EOF
    return 2
}

main() {
    local mode='dry-run' arg
    for arg in "$@"; do
        case "${arg}" in
            --apply) mode='apply' ;;
            *) _ojp_usage || return 2 ;;
        esac
    done

    temp_accounts_db_ready || {
        echo '[orphan-join-purge] 失败：MySQL 不可连 → 先 make platform-up（连接参数用 PLATFORM_DB_* 覆盖）' >&2
        return 1
    }

    local role domain role_users domain_users
    role="$(temp_orphan_role_left)" || {
        echo '[orphan-join-purge] 失败：数不到 sys_user_role 孤儿（DB 不可读）' >&2
        return 1
    }
    domain="$(temp_orphan_domain_left)" || {
        echo '[orphan-join-purge] 失败：数不到 sys_user_domain 孤儿（DB 不可读）' >&2
        return 1
    }

    if [[ "${mode}" == 'dry-run' ]]; then
        role_users="$(temp_orphan_role_users)" || return 1
        domain_users="$(temp_orphan_domain_users)" || return 1
        echo "[orphan-join-purge] mode=dry-run role=${role} domain=${domain} distinct_role_users=${role_users} distinct_domain_users=${domain_users}"
        if [[ "${role}" == '0' && "${domain}" == '0' ]]; then
            echo '[orphan-join-purge] 无需清理：两表均无孤儿 join 行（--apply 也不会写库）'
        else
            echo '[orphan-join-purge] 下一步：核对上面的 distinct 数后执行 bash scripts/orphan-join-purge.sh --apply（删除不可逆）'
        fi
        return 0
    fi

    # --apply：唯一谓词只生成一次（role / domain 各一次），DELETE 与上面的计数共用同一个渲染结果。
    local where_role where_domain after_role after_domain
    where_role="$(temp_orphan_join_where sys_user_role ur)" || return 1
    where_domain="$(temp_orphan_join_where sys_user_domain d)" || return 1

    db_q "DELETE ur FROM sys_user_role ur ${where_role}" >/dev/null 2>&1 || {
        echo '[orphan-join-purge] 失败：删除 sys_user_role 孤儿行被 DB 拒绝（未继续删 sys_user_domain）' >&2
        return 1
    }
    db_q "DELETE d FROM sys_user_domain d ${where_domain}" >/dev/null 2>&1 || {
        echo '[orphan-join-purge] 失败：删除 sys_user_domain 孤儿行被 DB 拒绝（sys_user_role 已删，可重跑 --apply 幂等补齐）' >&2
        return 1
    }

    # 删后复核走同一对计数器：before - after 才是真正删掉的条数，查询失败返回 1（绝不当 0）。
    after_role="$(temp_orphan_role_left)" || return 1
    after_domain="$(temp_orphan_domain_left)" || return 1
    echo "[orphan-join-purge] mode=apply deleted_role=$((role - after_role)) deleted_domain=$((domain - after_domain))"
    echo "[orphan-join-purge] post role=${after_role} domain=${after_domain}"

    if [[ "${after_role}" != '0' || "${after_domain}" != '0' ]]; then
        echo "[orphan-join-purge] 失败：删后仍有孤儿（role=${after_role} domain=${after_domain}；可能有并发写或谓词退化）——重跑本命令 --apply 复核" >&2
        return 1
    fi

    echo '[orphan-join-purge] 完成：role=0 domain=0（只删了 join 行，账号与角色未动；可重复执行）'
    return 0
}

main "$@"
