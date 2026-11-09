#!/usr/bin/env bash
# test-all.sh — 全量门禁：静态 → 单测 → r1 四步 → 平台栈 AC-1..AC-20 → 负向 → 种子 ×2。
# EXIT trap 始终停掉平台层与 r1 栈，绝不把环境留在半启动状态。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STEP=0
FAILED_STEP=""
CURRENT=""

cleanup() {
    local rc=$?
    trap - EXIT
    printf '\n'
    log "teardown：停止平台层与 r1 栈（幂等）"
    "$ROOT/scripts/platform-down.sh" || true
    "$ROOT/scripts/dev-down.sh" || true
    if (( rc != 0 )); then
        err "make test 失败于步骤 [${STEP}] ${FAILED_STEP:-$CURRENT}"
        exit "$rc"
    fi
}
trap cleanup EXIT

run_step() {
    local label="$1"; shift
    STEP=$((STEP + 1))
    CURRENT="$label"
    printf '\n\033[1;34m===== [%d] %s =====\033[0m\n' "$STEP" "$label"
    if ! "$@"; then
        FAILED_STEP="$label"
        exit 1
    fi
    ok "步骤 [${STEP}] 通过: ${label}"
}

run_step "静态门禁（lint）"              bash "$ROOT/scripts/tests/lint.sh"
run_step "单元测试（platform-unit）"     bash "$ROOT/scripts/platform-unit.sh"
run_step "r1 起栈（dev-up）"             bash "$ROOT/scripts/dev-up.sh"
run_step "r1 体检（verify）"             bash "$ROOT/scripts/verify.sh"
run_step "r1 负向（verify-negative）"    bash "$ROOT/scripts/tests/verify-negative.sh"
run_step "平台起栈（platform-up）"       bash "$ROOT/scripts/platform-up.sh"
run_step "AC-1..AC-20（platform-verify）" bash "$ROOT/scripts/platform-verify.sh"
run_step "平台负向（platform-test-negative）" bash "$ROOT/scripts/platform-test-negative.sh"
run_step "M6 质量域验收（quality-verify q0..q7）" bash "$ROOT/scripts/quality-verify.sh"
run_step "种子幂等 #1（platform-seed）"  bash "$ROOT/scripts/platform-seed.sh"
run_step "种子幂等 #2（platform-seed）"  bash "$ROOT/scripts/platform-seed.sh"

printf '\n'
ok "make test 全部 ${STEP} 个步骤通过"
printf '  性能基线不在 make test 内：make platform-perf（AC-20，需要 10 万行数据）\n'
