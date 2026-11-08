#!/usr/bin/env bash
# 负向用例（AC-6）：证明 `make verify` 不是橡皮图章。
#   1) 停 demo-service -> verify 必须非 0，且失败输出指名 demo-service 并给修复提示
#   2) 重启 demo-service -> 等网关链路就绪 -> verify 必须恢复全绿
# 三条断言缺一不可；EXIT trap 无条件兜底恢复，绝不把环境留在半停状态。
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/lib.sh"

VERIFY="$ROOT/scripts/verify.sh"
START_TS="$(date +%s)"

cleanup() {
    local rc=$?
    trap - EXIT   # 防止 exit 再次触发 trap 造成递归
    if ! app_alive demo-service; then
        warn "EXIT trap: 恢复 demo-service"
        if start_app demo-service "$DEMO_PORT" demo-service >/dev/null 2>&1 \
            && wait_app_health demo-service "$DEMO_PORT" >/dev/null 2>&1; then
            ok "EXIT trap: demo-service 已恢复"
        else
            err "EXIT trap: demo-service 恢复失败 —— 请手动执行 make dev-up"
            if (( rc == 0 )); then rc=1; fi
        fi
    fi
    exit "$rc"
}
trap cleanup EXIT

# 链路就绪：复用 lib.sh 的 chain_ok（200 + instancePort + configSource=nacos）。
# 不能只等 /actuator/health：服务 UP 之后网关仍可能因 LoadBalancer 实例缓存（约 35s）返回 503。
wait_chain_ready() {
    local timeout="${1:-90}" i=0
    until chain_ok; do
        i=$((i + 1))
        if (( i >= timeout )); then
            err "网关链路在 ${timeout}s 内未就绪（LoadBalancer 缓存 TTL 约 35s，或注册未恢复）"
            return 1
        fi
        sleep 1
    done
    printf '%s' "$i"
}

# ---- 前置条件：用例只能在"已经全绿"的环境上做减法，否则红的原因不可归因 ----
log "前置检查：环境必须已由 make dev-up 拉起并全绿"
nacos_healthy || die "Nacos 未就绪：先执行 make dev-up（负向用例不负责起环境）"
app_health_up "$GATEWAY_PORT" || die "api-gateway 未 UP：先执行 make dev-up"
app_alive demo-service || die "demo-service 无 .run/demo-service.pid（非本工具启动）：先 make dev-down 再 make dev-up"
app_health_up "$DEMO_PORT" || die "demo-service 未 UP：先执行 make dev-up"
ok "前置检查通过（Nacos / 网关 / demo-service 均健康）"

# ---- 断言 1/3：停服后 verify 必红，且指名 demo-service + 含修复提示 ----
log "断言 1/3：停止 demo-service 后 verify 必须变红"
stop_app demo-service
port_free_after "$DEMO_PORT" 10 || die "demo-service 端口 ${DEMO_PORT} 在 10s 内未释放，负向用例无法继续"
ok "demo-service 已停止，端口 ${DEMO_PORT} 已释放"

DOWN_TS="$(date +%s)"
if OUT="$(NO_COLOR=1 "$VERIFY" 2>&1)"; then
    RC=0
else
    RC=$?
fi
printf '%s\n' "$OUT"
(( RC != 0 )) || die "verify 在 demo-service 停止后仍 exit 0 —— 假绿灯，AC-6 不通过"
printf '%s' "$OUT" | grep -q 'demo-service' || die "verify 失败输出未指名 demo-service（无法定位失败项）"
printf '%s' "$OUT" | grep -q '修复' || die "verify 失败输出缺少修复提示"
printf '%s' "$OUT" | grep -q '失败项' || die "verify 失败输出缺少失败项汇总"
NAMED="$(printf '%s\n' "$OUT" | grep -c '^  \[FAIL\].*demo-service' || true)"
(( NAMED >= 2 )) || die "只有 ${NAMED} 个失败项指名 demo-service（端口/实例/健康/链路应多处同时红），体检覆盖不足"
PASSED_RED="$(printf '%s\n' "$OUT" | grep -c '^  \[PASS\]' || true)"
(( PASSED_RED >= 2 )) || die "停 demo-service 后仅剩 ${PASSED_RED} 项 PASS，说明红的原因不是定点失败（工具链都红了），
    请先确认 JDK/Maven 正常再重跑负向用例"
ok "verify 正确变红：exit ${RC}，${NAMED} 个失败项指名 demo-service，${PASSED_RED} 项仍 PASS（说明是定点失败而非整体崩溃）"

# ---- 断言 2/3：重启并等待链路真正恢复 ----
log "断言 2/3：重启 demo-service 并等待网关链路就绪"
start_app demo-service "$DEMO_PORT" demo-service
wait_app_health demo-service "$DEMO_PORT"
RECOVER_SECS="$(wait_chain_ready 90)" || die "重启后网关链路未恢复（服务 UP 但 lb:// 未刷新）"
UP_TS="$(date +%s)"
ok "链路恢复：健康等待 + LoadBalancer 刷新共 $((UP_TS - DOWN_TS))s"

# ---- 断言 3/3：恢复后 verify 必须全绿 ----
log "断言 3/3：恢复后 verify 必须全绿"
if OUT2="$(NO_COLOR=1 "$VERIFY" 2>&1)"; then
    RC2=0
else
    RC2=$?
fi
printf '%s\n' "$OUT2" | tail -n 3
(( RC2 == 0 )) || die "重启 demo-service 后 verify 仍为红（exit ${RC2}）"
printf '%s' "$OUT2" | grep -q '14 项检查' || die "verify 输出缺少 14 项汇总行，无法确认覆盖完整"
ok "恢复后 verify 全绿（14/14）"

ok "负向用例通过：停服必红、指名失败项、恢复必绿（总耗时 $(( $(date +%s) - START_TS ))s）"
