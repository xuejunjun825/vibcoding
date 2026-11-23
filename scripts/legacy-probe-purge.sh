#!/usr/bin/env bash
# legacy-probe-purge.sh — 清掉「白名单前缀 + 已停用」的遗留临时账号，并给出可复核的一行证据（r37，AC-1..AC-4）。
#
# 谁需要它：门禁（gate-order-verify / platform-demo-reset）失败时提示的修复命令就是本脚本；
# 它只做编排与打印，前缀/保护名单/删除逻辑全在 scripts/lib/temp-accounts.sh 一个单点里——
# 不存在「入口会删、门禁不会删」的第二套实现。
#
# 边界：只删 status='DISABLED' 的临时账号（ACTIVE 的探针与正在跑的门禁不会被动到，AC-4）；
#       14 名种子账号命中即拒；join 行先删、主表后删；不写任何清单文件，全部信息只打 stdout。
#
# 用法：bash scripts/legacy-probe-purge.sh      # 幂等：首跑有数，二跑 purged=0
# 退出码：0 全部达成；1 DB 不可连 / 守卫不成立（零删除）/ 删后仍有残留

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 显式端口覆盖必须在 source lib.sh **之前**抓住：lib.sh 的 _platform_db_parse 会按 PLATFORM_DB_URL
# 重算 PLATFORM_DB_PORT，之后再单设 PLATFORM_DB_PORT=1 会被静默丢弃——「DB 不可连必须 exit 1」
# 的验收（PLATFORM_DB_PORT=1）就成了假绿。这里把调用方显式给的端口留住。
_DB_PORT_OVERRIDE="${PLATFORM_DB_PORT:-}"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib.sh"
if [[ -n "${_DB_PORT_OVERRIDE}" ]]; then
    PLATFORM_DB_PORT="${_DB_PORT_OVERRIDE}"
fi
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/temp-accounts.sh"

main() {
    echo '[legacy-probe-purge] 清理「白名单前缀 + 已停用」的临时账号（单点真源：scripts/lib/temp-accounts.sh）'
    echo "[legacy-probe-purge] 前缀正则：${TEMP_ACCOUNT_REGEX}"
    echo "[legacy-probe-purge] 保护名单：${#TEMP_PROTECTED_USERS[@]} 名（${TEMP_PROTECTED_USERS[*]}）"
    if [[ -n "${TEMP_PROTECT_EXTRA}" ]]; then
        echo "[legacy-probe-purge] 额外保护（TEMP_PROTECT_EXTRA）：${TEMP_PROTECT_EXTRA}"
    fi

    temp_accounts_db_ready || {
        echo '[legacy-probe-purge] 失败：MySQL 不可连 → 先 make platform-up（连接参数用 PLATFORM_DB_* 覆盖）' >&2
        exit 1
    }

    temp_accounts_guard || {
        echo '[legacy-probe-purge] 失败：守卫不成立 → 本次零删除，按上面的点名修保护名单或补种子（make platform-seed）' >&2
        exit 1
    }

    local before names
    before="$(temp_disabled_left)" || {
        echo '[legacy-probe-purge] 失败：数不到停用临时账号（DB 不可读）' >&2
        exit 1
    }
    names="$(temp_disabled_usernames)" || names=''
    if [[ "${before}" == '0' ]]; then
        echo '[legacy-probe-purge] 无需清理：库内已无停用临时账号（DISABLED=0）'
    else
        echo "[legacy-probe-purge] 待删 ${before} 条：$(printf '%s' "${names}" | tr '\n' ' ')"
    fi

    local purged remaining users_left orphans
    purged="$(temp_purge_disabled)" || {
        echo '[legacy-probe-purge] 失败：清理中止（详见上面的 [temp-accounts] 报错，未完成全部删除）' >&2
        exit 1
    }
    remaining="$(temp_disabled_left)" || exit 1
    users_left="$(temp_users_left)" || exit 1
    orphans="$(temp_orphan_join_left)" || exit 1

    # AC-2 的判据行：purged=<N> remaining=0 users_left=<N> orphans_role=0
    echo "[legacy-probe-purge] purged=${purged} remaining=${remaining} users_left=${users_left} orphans_role=${orphans}"

    if [[ "${remaining}" != '0' || "${orphans}" != '0' ]]; then
        local sample
        sample="$(temp_disabled_usernames 2>/dev/null | head -5 | tr '\n' ' ')" || sample=''
        echo "[legacy-probe-purge] 失败：仍有残留（remaining=${remaining} orphans_role=${orphans}）示例：${sample}" >&2
        echo '[legacy-probe-purge] 修复：先看上面的 [temp-accounts] 首个报错；DB 写入被拒/并发重造都会让计数不归零，处理后再跑一次本命令' >&2
        exit 1
    fi

    echo '[legacy-probe-purge] 完成：remaining=0 orphans_role=0（幂等，可重复执行）'
    exit 0
}

main "$@"
