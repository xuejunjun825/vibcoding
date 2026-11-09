#!/usr/bin/env bash
# integration-verify.sh — M7 集成对接一键验收：起本地四合一桩 → 串跑 s0..s7 → 汇总守卫。
#
# 用法：
#   ./scripts/integration-verify.sh              # 全量（需 make platform-up + platform-seed）
#   ./scripts/integration-verify.sh s0 s1        # 只跑指定脚本
#
# 桩只在本脚本的子进程里存在（127.0.0.1:19090），退出即清理；产品代码不知道桩的存在。
# 每个 s 脚本打印 `#SUMMARY sN pass=N total=N fail=N`，TOTAL==0 或任一 FAIL 都算失败。
set -uo pipefail
# 复用质量域基座：require_stack 登录 + 健康检查 + expect/pass/fail 断言
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tests/quality/_lib.sh"

INTEG_DIR="$ROOT/scripts/tests/integration"
STUB="$INTEG_DIR/stub/server.mjs"
STUB_PORT="${STUB_PORT:-19090}"
STUB_URL="http://127.0.0.1:${STUB_PORT}"
RUN_DIR="$ROOT/.run"
STUB_LOG="$RUN_DIR/integration-stub.log"
STUB_PID_FILE="$RUN_DIR/integration-stub.pid"
STARTED_STUB=0

ALL_SCRIPTS=(s0-config s1-build s2-mr s3-sonar s4-artifact s5-admission s6-security s7-regression)

SELECTED=()
if (( $# > 0 )); then
    for want in "$@"; do
        match=""
        for s in "${ALL_SCRIPTS[@]}"; do
            [[ "$s" == "$want" || "$s" == "${want}-"* ]] && match="$s"
        done
        if [[ -z "$match" ]]; then
            err "未知脚本: ${want}（可选: ${ALL_SCRIPTS[*]}）"
            exit 2
        fi
        SELECTED+=("$match")
    done
else
    SELECTED=("${ALL_SCRIPTS[@]}")
fi

stop_stub() {
    if (( STARTED_STUB == 1 )) && [[ -f "$STUB_PID_FILE" ]]; then
        local pid; pid="$(cat "$STUB_PID_FILE" 2>/dev/null || true)"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
        rm -f "$STUB_PID_FILE"
    fi
}

start_stub() {
    if curl -fsS --max-time 2 "${STUB_URL}/__stub/health" >/dev/null 2>&1; then
        info "复用已运行的桩 ${STUB_URL}"
        return 0
    fi
    command -v node >/dev/null 2>&1 || { err "未找到 node（M7 桩需要 Node 18+）"; return 1; }
    [[ -f "$STUB" ]] || { err "缺少桩文件: ${STUB}"; return 1; }
    mkdir -p "$RUN_DIR"
    STUB_PORT="$STUB_PORT" node "$STUB" >"$STUB_LOG" 2>&1 &
    local pid=$!
    printf '%s' "$pid" >"$STUB_PID_FILE"
    STARTED_STUB=1
    local i
    for i in $(seq 1 50); do
        if curl -fsS --max-time 1 "${STUB_URL}/__stub/health" >/dev/null 2>&1; then
            ok "桩已就绪 ${STUB_URL}（pid=${pid}）"
            return 0
        fi
        sleep 0.2
    done
    err "桩启动失败，日志尾部："
    tail -n 20 "$STUB_LOG" 2>/dev/null || true
    return 1
}

trap 'stop_stub; printf "\n"; exit 130' INT TERM
trap stop_stub EXIT

printf '\033[1;34m集成对接验收（M7）：%d 个脚本\033[0m\n' "${#SELECTED[@]}"
printf '  数据库 %s:%s/%s · req %s · deliver %s · 桩 %s\n' \
    "$PLATFORM_DB_HOST" "$PLATFORM_DB_PORT" "$PLATFORM_DB_NAME" "$REQ_URL" "$DELIVER_URL" "$STUB_URL"

require_stack
start_stub || exit 1

TOTAL=0; PASS=0; FAILED=0
declare -a ROWS=()
FAILED_SCRIPTS=()

for name in "${SELECTED[@]}"; do
    script="$INTEG_DIR/${name}.sh"
    if [[ ! -f "$script" ]]; then
        err "缺少脚本: ${script}"
        FAILED_SCRIPTS+=("$name(缺失)")
        continue
    fi
    printf '\n\033[1;35m══════════ %s ══════════\033[0m\n' "$name"
    start=$(date +%s)
    out="$(bash "$script" 2>&1)"; rc=$?
    secs=$(( $(date +%s) - start ))
    if (( rc != 0 )); then
        printf '%s\n' "$out"
    else
        printf '%s\n' "$out" | grep -E '^(  (FAIL|SKIP)|#SUMMARY|  INFO)' || true
    fi
    line="$(printf '%s\n' "$out" | grep -E '^#SUMMARY ' | tail -n1)"
    s_total=0; s_pass=0; s_fail=0
    if [[ -n "$line" ]]; then
        s_pass="$(sed -E 's/.*pass=([0-9]+).*/\1/' <<<"$line")"
        s_total="$(sed -E 's/.*total=([0-9]+).*/\1/' <<<"$line")"
        s_fail="$(sed -E 's/.*fail=([0-9]+).*/\1/' <<<"$line")"
    fi
    TOTAL=$(( TOTAL + s_total )); PASS=$(( PASS + s_pass )); FAILED=$(( FAILED + s_fail ))
    ROWS+=("$(printf '%-22s pass=%-4s total=%-4s fail=%-3s %ss %s' \
        "$name" "$s_pass" "$s_total" "$s_fail" "$secs" "$( (( rc == 0 )) && echo OK || echo FAILED )")")
    (( rc != 0 )) && FAILED_SCRIPTS+=("$name")
done

printf '\n\033[1;34m---- 汇总 ----\033[0m\n'
for row in "${ROWS[@]}"; do printf '  %s\n' "$row"; done
printf '\n  合计 pass=%d total=%d fail=%d 脚本失败=%d\n' "$PASS" "$TOTAL" "$FAILED" "${#FAILED_SCRIPTS[@]}"
printf '#SUMMARY integration pass=%d total=%d fail=%d\n' "$PASS" "$TOTAL" "$FAILED"

if (( ${#FAILED_SCRIPTS[@]} > 0 )); then err "失败脚本: ${FAILED_SCRIPTS[*]}"; fi
if (( FAILED > 0 || TOTAL == 0 || ${#FAILED_SCRIPTS[@]} > 0 )); then
    err "集成验收未通过：先 make platform-up && make platform-seed；失败脚本会打印首个断言的修复提示"
    exit 1
fi
if (( TOTAL < 48 )); then
    err "断言总数 ${TOTAL} < 48（M7 要求四适配器各 ≥6、总 ≥48）"
    exit 1
fi
ok "集成验收全绿：${PASS}/${TOTAL}"
