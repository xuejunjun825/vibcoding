#!/usr/bin/env bash
# One command: precheck -> Nacos -> publish seed config -> build -> services -> chain READY.
#
# Stage order is a contract (TECHNICAL.md §2), not a preference: the gateway routes to
# lb://demo-service, so it starts after the service; and the service starts after Nacos
# because spring.config.import is NOT optional (an unreachable Nacos must fail loudly
# instead of silently falling back to local defaults).
# Any failure names the stage it died in and tails ONLY that stage's logs.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STAGE="0/5 前置检查"
CHAIN_CODE=""
CHAIN_BODY=""
CONFIG_CODE=""
CONFIG_BODY=""

tail_log() {
    local f="$1"
    [[ -f "$f" ]] || return 0
    printf -- '--- tail -n 25 %s ---\n' "$f" >&2
    tail -n 25 "$f" >&2 || true
}

on_error() {
    local rc=$?
    trap - ERR
    err "dev-up 失败于阶段 [${STAGE}]（exit ${rc}）"
    case "$STAGE" in
        0/5*)
            err "前置检查未通过：没有启动任何进程，按上面的修复项处理后重试。" ;;
        1/5*)
            tail_log "$NACOS_HOME/logs/startup.log"
            tail_log "$NACOS_HOME/logs/nacos.log"
            err "修复: 端口冲突→改 NACOS_HTTP_PORT/NACOS_CONSOLE_PORT；数据目录锁→make distclean 后重试" ;;
        2/5*)
            err "修复: 确认 Nacos 已就绪（make nacos-status）且 admin 鉴权已关闭（scripts/nacos.sh ensure 会写入）" ;;
        3/5*)
            err "修复: 看上面的 Maven 输出；依赖下载失败请配置 ~/.m2/settings.xml 镜像后重试" ;;
        4/5*)
            tail_log "$LOG_DIR/demo-service.log"
            tail_log "$LOG_DIR/api-gateway.log"
            err "修复: 服务未监听说明启动即退出，看上面日志最后 25 行；端口被占先 make dev-down" ;;
        5/5*)
            err "链路 HTTP=${CHAIN_CODE:-<none>} 响应: ${CHAIN_BODY:-<empty>}"
            tail_log "$LOG_DIR/demo-service.log"
            tail_log "$LOG_DIR/api-gateway.log"
            err "修复: 503=网关还没看到实例（Nacos 推送延迟/缺 loadbalancer）；404=StripPrefix 路由；看 .run/*.pid 与 logs/*.log" ;;
    esac
    err "半启动状态先用 make dev-down 清理，再重试 make dev-up"
    exit "$rc"
}
trap on_error ERR

# ---------------------------------------------------------------------------
# Own-process detection and kill_own_jvms() live in lib.sh — dev-up and
# dev-down shared two byte-identical copies before (QA D5).
# ---------------------------------------------------------------------------

# A pid file is stale only when its process is gone AND its port is silent. Stage 0
# deliberately passes ports this project already holds (env-precheck), because dev-up
# must be re-entrant in ANY state — so the old "stage 0 proved no port listens,
# therefore every pid file is stale" premise is false. Deleting a live pid file makes
# start_app launch a SECOND JVM that dies with "Port 8080 was already in use" while
# this script goes on to print READY off the original process (measured 2026-11-08:
# pids 12944/13127 died on 8080/8081 and left dead pid files behind).
drop_stale_pids() {
    local name pid port
    for name in api-gateway demo-service nacos; do
        [[ -f "$RUN_DIR/${name}.pid" ]] || continue
        pid="$(app_pid "$name")"
        case "$name" in
            api-gateway)  port="$GATEWAY_PORT" ;;
            demo-service) port="$DEMO_PORT" ;;
            nacos)        port="$NACOS_HTTP_PORT" ;;
        esac
        if app_alive "$name" || port_listening "$port"; then
            ok "pid 文件有效，保留: ${name}.pid（pid ${pid:-?}，端口 ${port} 在监听）"
            continue
        fi
        warn "清理过期 pid 文件: ${name}.pid（记录 pid ${pid:-?}，进程与端口均不存在）"
        rm -f "$RUN_DIR/${name}.pid"
    done
}

nacos_ports_listening() {
    local p out=""
    for p in "$NACOS_HTTP_PORT" "$NACOS_GRPC_PORT" "$NACOS_GRPC_TLS_PORT"; do
        port_listening "$p" && out+=" $p"
    done
    printf '%s' "${out# }"
}

# Never start a second Nacos over a half-dead one: two JVMs on the same embedded
# Derby directory is exactly the lock error this stage exists to avoid.
converge_nacos() {
    local listening
    listening="$(nacos_ports_listening)"
    if [[ -n "$listening" ]]; then
        if nacos_healthy; then
            ok "Nacos 已在运行且健康（${listening}），直接复用"
            return 0
        fi
        warn "Nacos 端口在监听但健康检查未通过（${listening}），先停止残留实例再启动"
        "$ROOT/scripts/nacos.sh" stop || true
        return 0
    fi
    kill_own_jvms "Nacos 端口全空但进程仍存活"
}

# Stage 2 readback, with the HTTP code kept: a 403/404 from the v3 admin API is a
# different fault from "config content is wrong", and both must be visible.
read_nacos_config() {
    local out
    out="$(curl -sS --max-time 10 -w '\n%{http_code}' \
        "$(nacos_config_url)" 2>/dev/null || true)"
    CONFIG_CODE="$(printf '%s' "$out" | tail -n1)"
    CONFIG_BODY="$(printf '%s' "$out" | sed '$d')"
}

# Stage 5 smoke: chain_ok() lives in lib.sh (shared with verify's #14).

log "阶段 0/5: 前置检查（不启动任何进程）"
"$ROOT/scripts/env-precheck.sh"
drop_stale_pids

log "阶段 1/5: Nacos standalone"
converge_nacos
"$ROOT/scripts/nacos.sh" start

log "阶段 2/5: 发布 Nacos 配置"
publish_nacos_config
read_nacos_config
[[ "$CONFIG_CODE" == "200" ]] \
    || die "Nacos 配置回读失败 HTTP ${CONFIG_CODE:-<none>}（dataId=${DATA_ID}, group=${GROUP}）；修复: 确认 Nacos 就绪且 admin 鉴权关闭"
printf '%s' "$CONFIG_BODY" | grep -q 'config-source:' \
    || die "Nacos 配置回读缺少 config-source:，实际内容: $(printf '%s' "$CONFIG_BODY" | head -c 200)"
ok "Nacos 配置回读校验通过（HTTP 200 且含 config-source:）"

log "阶段 3/5: 构建"
maven_build

log "阶段 4/5: 启动 demo-service 与 api-gateway"
start_app demo-service "$DEMO_PORT" demo-service
wait_app_health demo-service "$DEMO_PORT"
start_app api-gateway "$GATEWAY_PORT" api-gateway
wait_app_health api-gateway "$GATEWAY_PORT"

log "阶段 5/5: 就绪判定"
wait_until "Nacos 服务列表存在健康实例 demo-service:${DEMO_PORT}" 60 nacos_has_instance
# The gateway's LoadBalancer caches an empty instance list for a while after the
# service registers, so the smoke test is a BOUNDED poll (<=60s), not a single
# shot: READY still only appears when all three fields are right, it just allows
# them to converge instead of failing on a cold cache.
if ! wait_until "网关链路 200 ∧ instancePort=${DEMO_PORT} ∧ configSource=nacos" 60 chain_ok; then
    err "链路冒烟失败：HTTP ${CHAIN_CODE:-<none>}，最后响应: ${CHAIN_BODY:-<empty>}"
    exit 1
fi
ok "链路冒烟通过: ${CHAIN_BODY}"

cat <<EOF

${C_GREEN}READY${C_RESET}
  链路        ${GATEWAY_URL}/api/demo/hello  ->  ${DEMO_URL}/demo/hello
              (instancePort=${DEMO_PORT}, configSource=nacos)
  Nacos API   ${NACOS_BASE}
  Nacos 控制台 http://127.0.0.1:${NACOS_CONSOLE_PORT}  (仅本机，auth=false)
  六端口      ${GATEWAY_PORT}/${DEMO_PORT}/${NACOS_HTTP_PORT}/${NACOS_CONSOLE_PORT}/${NACOS_GRPC_PORT}/${NACOS_GRPC_TLS_PORT}

  下一步:
    make verify
    make test-negative
    make dev-down
EOF
