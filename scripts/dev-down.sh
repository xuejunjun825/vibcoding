#!/usr/bin/env bash
# Reverse-order stop: api-gateway -> demo-service -> Nacos, then converge to STOPPED.
#
# The success criterion is "all six ports have no LISTEN", not "a signal was sent":
# a delivered SIGTERM proves nothing about the socket. On top of that, no JVM of
# this project may survive — a Nacos JVM alive without a listening port still holds
# the embedded Derby lock and makes the NEXT dev-up die with
#   "Another instance of Derby may have already booted the database".
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

T0=$SECONDS
PORTS=("$GATEWAY_PORT" "$DEMO_PORT" "$NACOS_HTTP_PORT" "$NACOS_CONSOLE_PORT" "$NACOS_GRPC_PORT" "$NACOS_GRPC_TLS_PORT")

# --- own-process detection: own_jvm_pids()/kill_own_jvms() live in lib.sh
# (dev-up and dev-down used to carry byte-identical copies — QA D5) -----------

# "port(PID1,PID2)" for every port still listening — the single place that decides
# whether the environment reached STOPPED.
still_busy() {
    local p pids out=""
    for p in "${PORTS[@]}"; do
        pids="$(port_pids "$p")"
        [[ -n "$pids" ]] && out+=" ${p}(${pids// /,})"
    done
    printf '%s' "${out# }"
}

# One PID per line, then joined with spaces. port_pids() emits its pids SPACE-separated
# with NO trailing newline, so a per-port loop concatenated different ports into a
# single bogus pid ("12452"+"12428" -> "1245212428") and the fallback kill targeted a
# process that never existed, leaving both JVMs running until a manual kill.
pids_for_busy_ports() {
    local p
    for p in "${PORTS[@]}"; do
        printf '%s\n' "$(port_pids "$p")"
    done | tr -s ' ' '\n' | grep -E '^[0-9]+$' | sort -u | tr '\n' ' ' || true
}

wait_ports_free() {
    local budget="$1" i
    for ((i = 0; i < budget; i++)); do
        [[ -z "$(still_busy)" ]] && return 0
        sleep 1
    done
    [[ -z "$(still_busy)" ]]
}

log "阶段 1/3: 停止 api-gateway（逆序第 1 个）"
stop_app api-gateway
log "阶段 2/3: 停止 demo-service"
stop_app demo-service
log "阶段 3/3: 停止 Nacos"
"$ROOT/scripts/nacos.sh" stop || true

log "校验 STOPPED：六端口无 LISTEN + 无残留 JVM（目标 ≤10s）"
BUSY="$(still_busy)"
if [[ -n "$BUSY" ]]; then
    LEFTOVER="$(pids_for_busy_ports)"
    warn "仍在监听: ${BUSY} → SIGTERM ${LEFTOVER:-<none>}"
    for p in $LEFTOVER; do kill "$p" 2>/dev/null || true; done
    wait_ports_free 8 || true
fi

BUSY="$(still_busy)"
if [[ -n "$BUSY" ]]; then
    LEFTOVER="$(pids_for_busy_ports)"
    warn "SIGTERM 未生效: ${BUSY} → SIGKILL ${LEFTOVER:-<none>}"
    for p in $LEFTOVER; do kill -9 "$p" 2>/dev/null || true; done
    wait_ports_free 3 || true
fi

# Ports are free but a project JVM may still be alive (Nacos holding Derby, or a
# hung app that never bound). Clear it here so the next dev-up starts clean.
kill_own_jvms "dev-down 收尾"

BUSY="$(still_busy)"
if [[ -n "$BUSY" ]]; then
    err "dev-down 未完成：端口仍被占用 ${BUSY}"
    err "修复: lsof -nP -iTCP:<port> -sTCP:LISTEN 查 PID 后 kill -9 <pid>；或改用 GATEWAY_PORT/DEMO_PORT/NACOS_* 覆盖端口"
    exit 1
fi

JVMS="$(own_jvm_pids)"
if [[ -n "${JVMS// /}" ]]; then
    err "dev-down 未完成：仍有本项目 JVM 存活 ${JVMS}（Nacos 存活会锁住 Derby 数据目录，下一次 dev-up 会失败）"
    err "修复: kill -9 ${JVMS}"
    exit 1
fi

ELAPSED=$((SECONDS - T0))
if (( ELAPSED > 10 )); then
    warn "dev-down 用时 ${ELAPSED}s（AC-2 目标 ≤10s），端口已全部释放"
else
    ok "dev-down 用时 ${ELAPSED}s（目标 ≤10s）"
fi
ok "dev-down 完成：${PORTS[*]} 均已释放，无残留 JVM（下一步可直接 make dev-up）"
