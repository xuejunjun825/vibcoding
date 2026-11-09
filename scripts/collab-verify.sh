#!/usr/bin/env bash
# collab-verify.sh — M8 协作域一键验收：起本地 GitLab 桩（127.0.0.1:19091）→ 串跑 s0..s8 → 汇总守卫。
#
# 用法：
#   ./scripts/collab-verify.sh              # 全量（需 make platform-up + make platform-seed）
#   ./scripts/collab-verify.sh s0 s4        # 只跑指定脚本（前缀匹配）
#
# 桩只在本脚本的子进程里存在，退出即清理；产品代码不知道桩的存在。
# 端口取 19091：M7 的 integration-verify 占 19090，两套桩可同时在线而互不串话。
# 每个 s 脚本打印 `#SUMMARY sN pass=N total=N fail=N`，TOTAL==0 或任一 FAIL 都算失败；
# 全量要求 ≥60 个断言（PRD AC-20）。
set -uo pipefail
# 复用质量域基座：require_stack 登录 + 健康检查 + expect/pass/fail 断言
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tests/quality/_lib.sh"

COLLAB_DIR="$ROOT/scripts/tests/collab"
STUB="$COLLAB_DIR/stub/server.mjs"
STUB_PORT="${STUB_PORT:-19091}"
STUB_URL="http://127.0.0.1:${STUB_PORT}"
MIN_ASSERTS="${COLLAB_MIN_ASSERTS:-60}"
RUN_DIR="$ROOT/.run"
STUB_LOG="$RUN_DIR/collab-stub.log"
STUB_PID_FILE="$RUN_DIR/collab-stub.pid"
STARTED_STUB=0

ALL_SCRIPTS=(s0-schema s1-gantt s2-wiki s3-flow s4-policy s5-ccode-cr s6-case-review
             s7-test-data-executor s8-isolation)

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
    command -v node >/dev/null 2>&1 || { err "未找到 node（M8 桩需要 Node 18+）"; return 1; }
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

printf '\033[1;34m协作域验收（M8 CTeam/CCode/CTest）：%d 个脚本\033[0m\n' "${#SELECTED[@]}"
printf '  数据库 %s:%s/%s · req %s · deliver %s · 桩 %s\n' \
    "$PLATFORM_DB_HOST" "$PLATFORM_DB_PORT" "$PLATFORM_DB_NAME" "$REQ_URL" "$DELIVER_URL" "$STUB_URL"

require_stack
export STUB_PORT STUB_URL

# SSRF 放行：req-service 只允许出站到显式登记的 host:port（M7 默认登记 19090）。
# 本套桩在 19091，因此需要把 19091 加进放行列表——已启动的进程读不到新环境变量，只能重启一次。
export INTEGRATION_EGRESS_ALLOW_HOSTS="127.0.0.1:19090,127.0.0.1:${STUB_PORT}"
REQ_PID="$(cat "$RUN_DIR/req-service.pid" 2>/dev/null || true)"
STAMP="$RUN_DIR/collab-egress.stamp"
# 沙箱里 ps 被禁（/bin/ps: Operation not permitted），改为用「上次为本套桩重启出来的 pid」作标记：
# pid 与标记一致 = 当前 req-service 就是用含 19091 的白名单启动的；否则重启一次。
if [[ -n "$REQ_PID" && "$(cat "$STAMP" 2>/dev/null || true)" != "$REQ_PID" ]]; then
    info "req-service 未放行桩端口 ${STUB_PORT}（SSRF 白名单），重启平台栈一次"
    ( cd "$ROOT" && ./scripts/platform-down.sh >/dev/null 2>&1 || true
      INTEGRATION_EGRESS_ALLOW_HOSTS="$INTEGRATION_EGRESS_ALLOW_HOSTS" ./scripts/platform-up.sh >/dev/null 2>&1 ) \
        || { err "重启平台栈失败：手动 make platform-down && make platform-up 后再跑"; exit 1; }
    cat "$RUN_DIR/req-service.pid" >"$STAMP" 2>/dev/null || true
fi
start_stub || exit 1

TOTAL=0; PASS=0; FAILED=0
declare -a ROWS=()
FAILED_SCRIPTS=()

for name in "${SELECTED[@]}"; do
    script="$COLLAB_DIR/${name}.sh"
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
printf '#SUMMARY collab pass=%d total=%d fail=%d\n' "$PASS" "$TOTAL" "$FAILED"

if (( ${#FAILED_SCRIPTS[@]} > 0 )); then err "失败脚本: ${FAILED_SCRIPTS[*]}"; fi
if (( FAILED > 0 || TOTAL == 0 || ${#FAILED_SCRIPTS[@]} > 0 )); then
    err "协作域验收未通过：先 make platform-up && make platform-seed；每个 FAIL 后面都有「哪一项 + 怎么修」"
    exit 1
fi
if (( TOTAL < MIN_ASSERTS )); then
    err "断言总数 ${TOTAL} < ${MIN_ASSERTS}（PRD AC-20：九个 s 脚本各 ≥6、总计 ≥60）"
    exit 1
fi
ok "协作域验收全绿：${PASS}/${TOTAL}"
