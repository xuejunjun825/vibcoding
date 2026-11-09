#!/usr/bin/env bash
# s7-regression.sh — AC-9 回归：既有质量门禁（q4 现为 9 项）不回归、默认关闭时后台零外呼、
# 测试桩绝不进入产品路径、Makefile/README 交付面存在。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}"; exit 1; }

section "s7 · 既有质量门禁 q4 全绿（M6 六项语义 + M7 9 项结构）"
Q4_OUT="$(bash scripts/tests/quality/q4-gate-admission.sh 2>&1)"; Q4_RC=$?
Q4_LINE="$(grep -E '^#SUMMARY' <<<"$Q4_OUT" | tail -1)"
printf '%s\n' "$Q4_LINE"
if (( Q4_RC == 0 )); then
    pass "q4 exit 0（${Q4_LINE:-无 #SUMMARY}）"
else
    fail "q4 exit ${Q4_RC}" "$(grep -E '^\[FAIL\]|FAIL |err ' <<<"$Q4_OUT" | head -3 | tr '\n' ' ')"
    info "q4 尾部：$(tail -2 <<<"$Q4_OUT" | tr '\n' ' ')"
fi
Q4_TOTAL="$(sed -n 's/.*total=\([0-9]*\).*/\1/p' <<<"$Q4_LINE" | tail -1)"
if [[ "${Q4_TOTAL:-0}" =~ ^[0-9]+$ ]] && (( Q4_TOTAL >= 20 )); then pass "q4 断言数 ${Q4_TOTAL}（未被删减）"; else fail "q4 total=${Q4_TOTAL:-0}" "回归脚本不得为了变绿删断言"; fi

section "s7 · 默认关闭：集成配置 0 启用"
i_cleanup
ENABLED="$(db_count integration_config "tenant_id=${TENANT_ID} AND enabled=1")"
if [[ "$ENABLED" == "0" ]]; then pass "integration_config 无启用行"; else fail "${ENABLED} 个配置仍启用" "验收结束必须回到默认关闭"; fi

section "s7 · 默认关闭时后台任务零外呼（覆盖一个 10s 轮询周期）"
i_stub_reset
sleep 12
REQ="$(i_stub GET /__stub/requests; jq -r '(.requests // []) | length' <<<"$HTTP_BODY")"
if [[ "$REQ" == "0" ]]; then pass "12s 内桩 0 请求（轮询/刷新任务全部短路）"; else fail "桩收到 ${REQ} 个请求" "AdapterRegistry.requireEnabled 未配置时应直接返回，不发任何连接"; fi

section "s7 · 桩不进产品路径（Java/前端源码 0 引用；yml 仅保留可覆盖的白名单默认值）"
STUB_HIT="$(grep -rlE '19090|__stub|stub-webhook-secret|stub-jenkins' \
    req-service/src/main/java api-gateway/src/main/java deliver-service/src/main/java \
    platform-common/src/main/java web/src 2>/dev/null | head -5)"
if [[ -z "$STUB_HIT" ]]; then pass "产品源码无桩地址/控制面引用"; else fail "产品源码引用桩: ${STUB_HIT}" "桩只允许存在于 scripts/tests/integration/"; fi
CTRL_HIT="$(grep -rl '__stub' req-service/src api-gateway/src deliver-service/src web/src 2>/dev/null | head -5)"
if [[ -z "$CTRL_HIT" ]]; then pass "全仓产品路径无 /__stub 控制面"; else fail "产品路径出现 /__stub: ${CTRL_HIT}" "控制面只属于测试桩"; fi

section "s7 · 交付面：make integration-verify 与 README 接入章节"
if grep -qE '^integration-verify:' Makefile; then pass "Makefile 有 integration-verify 目标"; else fail "Makefile 缺 integration-verify" "与 quality-verify 同形态的薄封装"; fi
if grep -q "make integration-verify" README.md; then pass "README 提到 make integration-verify"; else fail "README 缺一键命令" "AC-9.1"; fi
if grep -qE '^## .*集成对接' README.md; then pass "README 有集成对接章节"; else fail "README 缺集成对接章节" "AC-11 接入文档"; fi
DIFF_ROWS="$(awk '/桩与真实环境|桩与真实/{f=1} f&&/^\|/{c++} f&&/^## /&&c>0{exit} END{print c+0}' README.md)"
if (( DIFF_ROWS >= 7 )); then pass "桩差异表 ${DIFF_ROWS} 行（含表头，≥6 数据行）"; else fail "桩差异表仅 ${DIFF_ROWS} 行" "AC-11 要求 ≥6 行差异"; fi

guard s7-regression
