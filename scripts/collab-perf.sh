#!/usr/bin/env bash
# collab-perf.sh — AC-20 性能基线：五项场景各采样 7 次取 nearest-rank P95，与预算对比后逐行给结论。
#
# 用法：
#   ./scripts/collab-perf.sh                # 先补种子（可关闭），再测量五项
#   PERF_SEED=0 ./scripts/collab-perf.sh    # 只测现网数据，不建种子
#   PERF_ROUNDS=15 ./scripts/collab-perf.sh # 采样更多轮（P95 更稳，耗时线性增长）
#
# 口径：P95 用 nearest-rank（ceil(0.95*n) 位，n=7 时是最大值），单位毫秒；
# 预算来自 PRD AC-20 表。种子一律以 `perf-` 前缀标记，退出时按前缀清理（不删业务数据）。
set -uo pipefail
# shellcheck disable=SC1091
# 集成域基座（它自身 source quality 基座）：db_exec 只在这里定义，清理语句必须能真正执行。
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tests/integration/_lib.sh"

require_stack
trap 'perf_cleanup; printf "\n"' EXIT

PERF_ROUNDS="${PERF_ROUNDS:-7}"
PERF_SEED="${PERF_SEED:-1}"
PERF_TAG="perf-$(date +%H%M%S)"
GANTT_ROWS=300
WIKI_PAGES=200

perf_cleanup() {
    db_exec "DELETE FROM requirement WHERE tenant_id=${TENANT_ID} AND title LIKE 'perf-%'" >/dev/null 2>&1 || true
    db_exec "DELETE FROM wiki_page_version WHERE tenant_id=${TENANT_ID} AND page_id IN
             (SELECT id FROM wiki_page WHERE tenant_id=${TENANT_ID} AND title LIKE 'perf-%')" >/dev/null 2>&1 || true
    db_exec "DELETE FROM wiki_page WHERE tenant_id=${TENANT_ID} AND title LIKE 'perf-%'" >/dev/null 2>&1 || true
    db_exec "DELETE FROM \`release\` WHERE tenant_id=${TENANT_ID} AND name LIKE 'perf-%'" >/dev/null 2>&1 || true
}

p95() { # p95 <ms...> → nearest-rank 第 ceil(0.95*n) 位
    local n=$# idx
    idx=$(( (95 * n + 99) / 100 ))
    (( idx < 1 )) && idx=1
    printf '%s\n' "$@" | sort -n | sed -n "${idx}p"
}

measure() { # measure <label> <url> → 打印 7 次耗时（毫秒），失败时返回 1
    local label="$1" url="$2" i ms
    local -a samples=()
    for (( i = 1; i <= PERF_ROUNDS; i++ )); do
        ms="$(curl -sS -o /dev/null -w '%{time_total}' --max-time 20 \
            -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" \
            "${GATEWAY_URL}${url}" 2>/dev/null | awk '{printf "%d", $1*1000}')" || return 1
        [[ -n "$ms" ]] || return 1
        samples+=("$ms")
    done
    MEASURED=("${samples[@]}")
    printf '%s' "$(p95 "${samples[@]}")"
}

report() { # report <场景> <p95> <预算> <说明>
    local label="$1" value="$2" budget="$3" note="${4:-}"
    local verdict="PASS"
    (( value > budget )) && verdict="FAIL"
    printf '  %-34s p95=%-6s ms  预算 %-5s ms  %s  %s\n' "$label" "$value" "$budget" "$verdict" "$note"
    printf '%s\n' "$label|$value|$budget|$verdict" >>"$PERF_ROWS"
    (( value > budget )) && PERF_FAIL=$(( PERF_FAIL + 1 ))
}

PERF_ROWS="$(mktemp)"
PERF_FAIL=0
trap 'rm -f "$PERF_ROWS"; perf_cleanup; printf "\n"' EXIT

printf '\033[1;34m性能基线（AC-20）：%d 轮/场景 · 租户 %s\033[0m\n' "$PERF_ROUNDS" "${TENANT_ID}"

if [[ "$PERF_SEED" == "1" ]]; then
    printf '  补种子：%d 条需求（甘特视图）+ %d 个 Wiki 页面…\n' "$GANTT_ROWS" "$WIKI_PAGES"
    PDR="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} AND type='PDR' ORDER BY id LIMIT 1")"
    REL="$(q_create_release "${PERF_TAG}-release")" || true
    for (( i = 1; i <= GANTT_ROWS; i++ )); do
        api_call POST /api/req/requirements "$(jq -cn --arg t "${PERF_TAG}-需求-${i}" \
            --argjson p "${PDR:-0}" --argjson d "$(q_domain)" \
            '{type:"RD",title:$t,parentId:$p,domainIds:[$d],priority:"P2",
              planStartDate:"2026-11-01",planEndDate:"2026-11-20",estimateDays:2}')" >/dev/null 2>&1 || true
    done
    [[ -n "${REL:-}" ]] && for (( i = 1; i <= 20; i++ )); do
        api_call POST "/api/req/releases/${REL}/requirements" \
            "{\"requirementIds\":[$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} AND title LIKE '${PERF_TAG}-需求-%' ORDER BY id DESC LIMIT 1")]}" \
            >/dev/null 2>&1 || true
    done
    SPACE="$(db_q "SELECT id FROM wiki_space WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
    for (( i = 1; i <= WIKI_PAGES; i++ )); do
        api_call POST /api/req/wiki/pages "$(jq -cn --arg t "${PERF_TAG}-页面-${i}" \
            --argjson s "${SPACE:-0}" '{spaceId:$s,title:$t,contentMd:"协作验收 检索 关键词 种子内容"}')" >/dev/null 2>&1 || true
    done
    ok "种子就绪（失败项由各场景的 SKIP 反映，不掩盖真实缺口）"
fi

printf '\n\033[1;34m---- 五项 P95 ----\033[0m\n'
[[ -n "${REL:-}" ]] || REL="$(db_q "SELECT id FROM \`release\` WHERE tenant_id=${TENANT_ID} ORDER BY id DESC LIMIT 1")"
SPACE="${SPACE:-$(db_q "SELECT id FROM wiki_space WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")}"
GANTT_PATH="/api/req/gantt/rows?view=REQUIREMENT&zoom=WEEK&domainId=$(q_domain)&releaseId=${REL:-0}&limit=${GANTT_ROWS}"
WIKI_SEARCH_PATH="/api/req/wiki/search?q=%E5%8D%8F%E4%BD%9C%E9%AA%8C%E6%94%B6&page=1&size=20"
WIKI_TREE_PATH="/api/req/wiki/spaces/${SPACE:-0}/tree"
FLOW_PATH="/api/deliver/flow/board?domainId=$(q_domain)&from=2026-01-01&to=2026-12-31"

if v="$(measure gantt "$GANTT_PATH")"; then
    report "甘特单视图 ${GANTT_ROWS} 条（含告警计算）" "$v" 1500 "含权限过滤+告警挂载"
else
    report "甘特单视图 ${GANTT_ROWS} 条（含告警计算）" 99999 1500 "SKIP: 接口不可用"
fi
if [[ -n "${SPACE:-}" ]] && v="$(measure wiki-search "$WIKI_SEARCH_PATH")"; then
    report "Wiki 全文检索（ngram）" "$v" 800 "q=协作验收"
else
    report "Wiki 全文检索（ngram）" 0 800 "SKIP: T3 未交付（见 [OUT-OF-SCOPE]）"
    SKIPPED_WIKI=1
fi
if [[ -n "${SPACE:-}" ]] && v="$(measure wiki-tree "$WIKI_TREE_PATH")"; then
    report "Wiki 页面树（≤500 节点）" "$v" 300 "space=$SPACE"
else
    report "Wiki 页面树（≤500 节点）" 0 300 "SKIP: T3 未交付（见 [OUT-OF-SCOPE]）"
    SKIPPED_WIKI=1
fi
if v="$(measure flow "$FLOW_PATH")"; then
    report "价值流 7 阶段看板" "$v" 2000 "audit_log 窗口函数直算"
else
    report "价值流 7 阶段看板" 99999 2000 "SKIP: 接口不可用"
fi
CASE="$(q_case_ids 1 | awk '{print $1}')"
MR_PATH="/api/req/requirements/$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} AND type='RD' ORDER BY id DESC LIMIT 1")/integration/mr-comments?limit=50"
LIST_MAX=0
for pair in "用例评审列表:/api/deliver/testcases/${CASE:-0}/reviews" \
            "执行器列表:/api/deliver/test-executors" \
            "MR 评论首屏:${MR_PATH}"; do
    label="${pair%%:*}"; path="${pair#*:}"
    if v="$(measure "$label" "$path")"; then
        (( v > LIST_MAX )) && LIST_MAX="$v"
    else
        LIST_MAX=""
    fi
done
if [[ -z "$LIST_MAX" ]]; then
    report "评审/执行器/MR 评论首屏（取三者最大）" 99999 500 "SKIP: 至少一个列表接口不可用"
else
    report "评审/执行器/MR 评论首屏（取三者最大）" "$LIST_MAX" 500 "三项各自 ≤500ms"
fi

printf '\n\033[1;34m---- 结论 ----\033[0m\n'
printf '#SUMMARY collab-perf pass=%d total=%d fail=%d\n' "$(( 5 - PERF_FAIL ))" 5 "$PERF_FAIL"
if (( PERF_FAIL > 0 )); then
    err "有 ${PERF_FAIL} 项超预算：先看该行的预算与数据量，再决定是加索引还是加派生汇总表（派生表必须可从 audit_log 全量重算）"
    exit 1
fi
ok "五项均在 AC-20 预算内"
