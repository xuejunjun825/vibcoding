#!/usr/bin/env bash
# gate-order-verify.sh — 顺序无关的机器化证明（r34，AC-5/AC-6/AC-7）。
#
# 为什么需要独立门禁：顺序依赖只能由「两种顺序都跑」证伪，单序列 + 人肉推理证明不了。
#   A 序列：reset → release-flow-verify → access-verify → access-verify
#   B 序列：reset → access-verify → release-flow-verify → access-verify
# 每一步同时看两件事：该门禁自己的分母（75/75、CASE=44 PASS=44 FAIL=0、206/206），
# 以及 scripts/lib/gate-fixture.sh 的 gate_fixture_problems 为空（足迹/非夹具需求/演示不变量）。
#
# 15 条 = 1 栈前置 + 4 静态契约 + 8 序列步骤 + 2 收尾基线。
# 它要独占演示栈、单轮 2–3 分钟，所以不进 make test（每次提交门禁），只做发布前门禁：
#   make gate-order-verify
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
# shellcheck source=lib/gate-fixture.sh
source "${ROOT}/scripts/lib/gate-fixture.sh"

GATE_FIXTURE_TENANT_CODE="${PLATFORM_TENANT:-t1}"

TOTAL_N=0
PASS_N=0
FAIL_N=0

hit() {
    TOTAL_N=$((TOTAL_N + 1)); PASS_N=$((PASS_N + 1))
    printf '  \033[1;32mOK\033[0m   %s\n' "$1"
}
miss() { # label fix
    TOTAL_N=$((TOTAL_N + 1)); FAIL_N=$((FAIL_N + 1))
    printf '  \033[1;31mFAIL\033[0m %s\n' "$1"
    [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"
}
banner() { printf '\n\033[1;34m---- %s ----\033[0m\n' "$*"; }

# 子门禁必须各自从零开始：清掉本进程导出的 GATE_TAG，否则它们会共用 gate-order 的 tag，
# 「每个门禁自己复位自己的足迹」这件事就被掩盖了。
run_gate() { # script -> stdout，$? 为该脚本退出码
    env GATE_TAG= bash "${ROOT}/scripts/$1" 2>&1
}

# ---------------------------------------------------------------- 1 栈前置
banner "0) 演示栈前置（缺栈直接失败，不做条件跳过）"
if ! req_ready; then
    err "req-service 未就绪：${REQ_URL}/internal/health —— 先 make platform-up"
    exit 1
fi
if ! deliver_ready; then
    err "deliver-service 未就绪：${DELIVER_URL}/internal/health —— 先 make platform-up"
    exit 1
fi
if ! platform_login "$GATE_FIXTURE_TENANT_CODE" admin 'Admin@12345' >/dev/null 2>&1; then
    err "admin 登录失败 —— 先 make platform-seed（或 make platform-demo-reset）"
    exit 1
fi
hit "演示栈就绪（req/deliver health + ${GATE_FIXTURE_TENANT_CODE}/admin 登录）"
gate_fixture_begin || { err "gate-fixture 起跑失败（见上方 [gate] 输出）"; exit 1; }

# ---------------------------------------------------------------- 2-5 静态契约
banner "1) 静态契约（4）"

GATES=("release-flow-verify.sh" "platform-verify.sh" "platform-test-negative.sh")

bad=""
for g in "${GATES[@]}"; do
    f="${ROOT}/scripts/${g}"
    { grep -q 'lib/gate-fixture.sh' "$f" && grep -q 'gate_fixture_begin' "$f"; } || bad+="${g} "
    # D-3：不许再「按状态取第一条种子 PDR」当父（negative 旧 :97 的写法会把夹具挂到 DEMO-R-02 上，
    # 把汇总从 5.00 推高并让评估单 STALE）——父链必须自建 gate-<tag>-BUS→POOLED→PDR。
    grep -qE "type='PDR' AND status IN \('SCHEDULED','IN_DEV'\) ORDER BY id" "$f" \
        && bad+="${g}:按状态取种子PDR "
done
if [[ -z "$bad" ]]; then
    hit "三门禁接入共享钩子，且不按状态取种子 PDR（父链自建）"
else
    miss "未接入共享钩子或仍按状态取种子 PDR: ${bad}" "source scripts/lib/gate-fixture.sh + gate_fixture_begin；父链用自建 gate-<tag>-BUS→POOLED→PDR"
fi

bad=""
for g in "${GATES[@]}"; do
    f="${ROOT}/scripts/${g}"
    grep -qE "platform-demo-reset\.sh\" >/dev/null" "$f" && bad+="${g}:静默reset "
    grep -q '不影响本次断言' "$f" && bad+="${g}:WARN降级 "
done
if [[ -z "$bad" ]]; then
    hit "三门口禁都无 >/dev/null 静默 reset / WARN 降级（清理失败只能置红）"
else
    miss "仍有静默清理: ${bad}" "复位只走 gate_fixture_reset；失败必须把退出码变差，不能只打 WARN"
fi

if grep -q '^TOTAL_CHECKS=75' "${ROOT}/scripts/platform-demo-reset.sh" \
    && grep -q 'NON_FIXTURE_REQS' "${ROOT}/scripts/platform-demo-reset.sh" \
    && grep -q "NOT LIKE 'DEMO-R-%'" "${ROOT}/scripts/platform-demo-reset.sh"; then
    hit "干净基线单点定义：demo-reset TOTAL_CHECKS=75 且含「非夹具需求 0 条」"
else
    miss "demo-reset 分母/断言缺失" "TOTAL_CHECKS 必须为 75，并含 NON_FIXTURE_REQS（code NOT LIKE 'DEMO-R-%' 期望 0）"
fi

if grep -q '^gate-order-verify:' "${ROOT}/Makefile" \
    && grep -q 'gate-order-verify: ## ' "${ROOT}/Makefile" \
    && make -C "${ROOT}" help 2>/dev/null | grep -q 'gate-order-verify'; then
    hit "make gate-order-verify 可发现（目标 + ## 帮助行）"
else
    miss "Makefile 未暴露 gate-order-verify" "加进 .PHONY，目标体 @./scripts/gate-order-verify.sh，并用 ## 写一行帮助"
fi

# ---------------------------------------------------------------- 6-13 双序列
# 每一步 = 该门禁自己的分母 + gate_fixture_problems 为空；两者任何一个不成立都判该步红。
run_reset() { # label
    local label="$1" out rc m a b probs
    out="$(bash "${ROOT}/scripts/platform-demo-reset.sh" 2>&1)"; rc=$?
    m="$(printf '%s' "$out" | grep -oE 'platform-demo-reset: [0-9]+/[0-9]+' | tail -n 1)"
    a="${m##*: }"; b="${a##*/}"; a="${a%%/*}"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ -n "$m" && "$a" == "$b" && "$a" -ge 75 && -z "$probs" ]]; then
        hit "${label}：reset ${a}/${b} 且基线干净"
    else
        miss "${label}：reset 未到 ${a:-?}/${b:-?}（rc=${rc}）或基线不干净" \
            "问题: ${probs:-<无>}；末尾输出:
$(printf '%s' "$out" | tail -n 20)"
    fi
}

run_rfv() { # label
    local label="$1" out rc line probs
    out="$(run_gate release-flow-verify.sh)"; rc=$?
    line="$(printf '%s' "$out" | grep -E '^RELEASE_FLOW_TOTAL=' | tail -n 1)"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ "$line" == *"RELEASE_FLOW_TOTAL=44 CASE=44 PASS=44 FAIL=0"* && -z "$probs" ]]; then
        hit "${label}：release-flow-verify 44/44 且基线干净"
    else
        miss "${label}：release-flow-verify 未达 44/44（rc=${rc}, ${line:-无计数行}）或基线不干净" \
            "问题: ${probs:-<无>}；末尾输出:
$(printf '%s' "$out" | tail -n 20)"
    fi
}

run_access() { # label
    local label="$1" out rc m a b probs
    out="$(run_gate access-verify.sh)"; rc=$?
    m="$(printf '%s' "$out" | grep -oE '验收通过：[0-9]+/[0-9]+' | tail -n 1)"
    a="${m#*：}"; b="${a##*/}"; a="${a%%/*}"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ -n "$m" && "$a" == "$b" && "$a" -ge 206 && -z "$probs" ]]; then
        hit "${label}：access-verify ${a}/${b} 且基线干净"
    else
        miss "${label}：access-verify 未到 ${a:-?}/${b:-?}（rc=${rc}）或基线不干净" \
            "问题: ${probs:-<无>}；末尾输出:
$(printf '%s' "$out" | tail -n 20)"
    fi
}

banner "2) A 序列：reset → rfv → access → access"
run_reset "A1 起跑基线"
run_rfv "A2 rfv（首个写门禁）"
run_access "A3 access（rfv 之后）"
run_access "A4 access 重复（只读门禁幂等）"

banner "3) B 序列：reset → access → rfv → access"
run_reset "B1 起跑基线"
run_access "B2 access（rfv 之前）"
run_rfv "B3 rfv（access 之后）"
run_access "B4 access（rfv 之后）"

# ---------------------------------------------------------------- 14-15 收尾基线
banner "4) 收尾基线（2）"
TID2="$(db_q "SELECT id FROM sys_tenant WHERE code='${GATE_FIXTURE_TENANT_CODE}'" 2>/dev/null | head -n1)"
NONFIX="$(db_count requirement "tenant_id=${TID2} AND code NOT LIKE 'DEMO-R-%'")"
TAGLEFT="0"
if [[ -n "$GATE_TAG" ]]; then
    TAGLEFT="$(db_count requirement "tenant_id=${TID2} AND title LIKE '%${GATE_TAG}%'")"
fi
# r35：S7 改为自造夹具后，租户下不得再留任何 S7- 前缀的需求（种子数据里没有这个前缀）。
S7LEFT="$(db_count requirement "tenant_id=${TID2} AND title LIKE '%S7-%'")"
if [[ "$NONFIX" == "0" && "$TAGLEFT" == "0" && "$S7LEFT" == "0" ]]; then
    hit "收尾：非夹具需求 0 条、本轮 tag 残留 0 条、S7- 痕迹 0 条"
else
    miss "收尾残留：非夹具 ${NONFIX} 条 / 本 tag ${TAGLEFT} 条 / S7- ${S7LEFT} 条" \
        "两种顺序跑完必须回到同一张干净基线；残留说明某一步的 teardown 没跑或失败被吞（S7 夹具由 s7_reset 自己清）"
fi

R02="$(db_q "SELECT CONCAT(FORMAT(estimate_days,2),'|',(SELECT COUNT(*) FROM wf_instance w WHERE w.tenant_id=${TID2} AND w.biz_type='WBS_ESTIMATE' AND w.biz_id=requirement.id AND w.status='APPROVED')) FROM requirement WHERE tenant_id=${TID2} AND code='DEMO-R-02'" 2>/dev/null | head -n1)"
R04="$(db_q "SELECT COUNT(*) FROM requirement r JOIN \`release\` v ON v.id=r.release_id WHERE r.tenant_id=${TID2} AND r.code='DEMO-R-04' AND v.code='DEMO-REL-1'" 2>/dev/null | head -n1)"
if [[ "$R02" == "5.00|1" && "$R04" == "1" ]]; then
    hit "收尾：DEMO-R-02 汇总 5.00 + 评估单 APPROVED、DEMO-R-04 仍在 DEMO-REL-1"
else
    miss "收尾演示不变量漂移：R-02='${R02:-<缺失>}'（期望 5.00|1）R-04='${R04:-<缺失>}'（期望 1）" \
        "门禁夹具必须挂在自建父链上；挂到种子会把 DEMO-R-02 的汇总推高并让评估单 STALE"
fi

# ---------------------------------------------------------------- 汇总
printf '\n'
if (( FAIL_N == 0 && TOTAL_N == 15 )); then
    ok "gate-order-verify: 15/15 —— A/B 两种顺序跑完基线一致（门禁顺序无关）"
    exit 0
fi
err "gate-order-verify: ${PASS_N}/${TOTAL_N}（声明 15 条）—— 失败 ${FAIL_N} 条"
err "修复方向：先看每条 FAIL 上方的问题清单（问题: ...）；残留类问题执行 make platform-demo-reset 后再跑"
exit 1
