#!/usr/bin/env bash
# Manage the local Nacos standalone distribution.
#   ensure  download + verify + extract + write ports (idempotent)
#   start   ensure, then start standalone and wait for readiness
#   stop    graceful stop, then fall back to SIGTERM/SIGKILL
#   status  print process / port / health status
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

NACOS_TGZ="$NACOS_HOME/cache/nacos-server-${NACOS_VERSION}.tar.gz"
NACOS_URL="${NACOS_DOWNLOAD_URL:-https://github.com/alibaba/nacos/releases/download/${NACOS_VERSION}/nacos-server-${NACOS_VERSION}.tar.gz}"
# Pinned checksum of the official nacos-server-3.3.0.tar.gz (verified on 2026-11-08).
if [[ -z "${NACOS_SHA256:-}" && "$NACOS_VERSION" == "3.3.0" ]]; then
    NACOS_SHA256="18d5ec3067e9e310401028ecc35e6e9c3a854c9b14d084f48b83bfea53208107"
fi
NACOS_SHA256="${NACOS_SHA256:-PENDING}"

set_conf_prop() {
    local key="$1" value="$2" conf="$NACOS_HOME/conf/application.properties" tmp
    [[ -f "$conf" ]] || die "缺少 ${conf}，Nacos 发行包不完整"
    tmp="$(mktemp)"
    awk -v k="$key" -v v="$value" '
        BEGIN { done = 0 }
        index($0, k "=") == 1 { print k "=" v; done = 1; next }
        { print }
        END { if (!done) print k "=" v }
    ' "$conf" > "$tmp"
    mv "$tmp" "$conf"
}

# Remove every line that assigns `key`. Needed for server.port: leaving the key
# out is NOT the same as writing the default value — see ensure_nacos.
del_conf_prop() {
    local key="$1" conf="$NACOS_HOME/conf/application.properties" tmp
    [[ -f "$conf" ]] || die "缺少 ${conf}，Nacos 发行包不完整"
    tmp="$(mktemp)"
    awk -v k="$key" 'index($0, k "=") == 1 { next } { print }' "$conf" > "$tmp"
    mv "$tmp" "$conf"
}

ensure_nacos() {
    if [[ -x "$NACOS_HOME/bin/startup.sh" ]]; then
        ok "Nacos 已解压: $NACOS_HOME (${NACOS_VERSION})"
    else
        mkdir -p "$NACOS_HOME/cache"
        if [[ -f "$NACOS_TGZ" ]]; then
            ok "命中 Nacos 发行包缓存: $NACOS_TGZ"
        else
            log "下载 Nacos ${NACOS_VERSION}（约 233MB）"
            curl -fL --progress-bar --retry 2 --connect-timeout 15 \
                -o "${NACOS_TGZ}.part" "$NACOS_URL" \
                || die "下载失败: ${NACOS_URL}（可手动下载后放到 ${NACOS_TGZ}）"
            mv "${NACOS_TGZ}.part" "$NACOS_TGZ"
        fi

        local actual
        actual="$(shasum -a 256 "$NACOS_TGZ" | awk '{print $1}')"
        if [[ "$NACOS_SHA256" == "PENDING" || -z "$NACOS_SHA256" ]]; then
            warn "未固定 sha256，已计算: ${actual}（可通过 NACOS_SHA256 固定校验）"
        elif [[ "$actual" != "$NACOS_SHA256" ]]; then
            rm -f "$NACOS_TGZ"
            die "sha256 校验失败：期望 ${NACOS_SHA256}，实际 ${actual}（已删除损坏包）"
        else
            ok "sha256 校验通过: $actual"
        fi

        log "解压到 $NACOS_HOME"
        tar -xzf "$NACOS_TGZ" -C "$NACOS_HOME" --strip-components=1 \
            || die "解压失败，请检查 $NACOS_TGZ"
    fi

    # Idempotent settings. Nacos 3.x console defaults to 8080, which collides with the gateway.
    set_conf_prop "nacos.console.port" "$NACOS_CONSOLE_PORT"
    set_conf_prop "nacos.core.auth.enabled" "false"
    # Admin HTTP API (v3) is what dev-up uses to seed config; console stays protected.
    set_conf_prop "nacos.core.auth.admin.enabled" "false"
    # Nacos 3.3 startup.sh prompts (and loops forever on EOF) when these are empty,
    # even with auth disabled. Seed local-only values so `make dev-up` is non-interactive.
    # 仅本机开发：可用 NACOS_TOKEN_SECRET 覆盖，切勿把默认值复制到共享/公网环境。
    local local_secret="${NACOS_TOKEN_SECRET:-teamflow-local-dev-nacos-secret-key-0123456789}"
    set_conf_prop "nacos.core.auth.plugin.nacos.token.secret.key" "$(printf '%s' "$local_secret" | base64 | tr -d '\n')"
    set_conf_prop "nacos.core.auth.server.identity.key" "teamflow"
    set_conf_prop "nacos.core.auth.server.identity.value" "teamflow-local"
    # server.port must be present ONLY for a non-default HTTP port. Measured on 3.3.0
    # (2026-11-08, .nacos/logs/nacos.log): with the key absent the console binds
    # nacos.console.port=8849 and the server binds 8848 — the required topology;
    # with server.port=8850 both contexts move to 8850 (/nacos and /, verified 200);
    # with server.port=8848 the console context dies ("Failed to start bean
    # 'webServerStartStop'") and 8849 never listens. So: omit at default, write
    # otherwise, and delete a stale value left by a run on another port.
    if [[ "$NACOS_HTTP_PORT" == "8848" ]]; then
        del_conf_prop "server.port"
    else
        set_conf_prop "server.port" "$NACOS_HTTP_PORT"
        set_conf_prop "nacos.console.port" "$NACOS_CONSOLE_PORT"
    fi
    ok "Nacos 配置: http=${NACOS_HTTP_PORT}, console=${NACOS_CONSOLE_PORT}, gRPC=${NACOS_GRPC_PORT}/${NACOS_GRPC_TLS_PORT}, auth=false（仅本机）"
}

nacos_pid() {
    port_pids "$NACOS_HTTP_PORT" | awk '{print $1}'
}

start_nacos() {
    ensure_nacos
    if nacos_healthy; then
        ok "Nacos 已在运行 (${NACOS_BASE})"
        return 0
    fi
    [[ -n "${JAVA_HOME:-}" ]] || JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    [[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME 未设置，且无法通过 /usr/libexec/java_home 推断"
    export JAVA_HOME

    log "启动 Nacos standalone"
    (
        cd "$NACOS_HOME"
        nohup bash bin/startup.sh -m standalone >/dev/null 2>&1
    ) || die "Nacos startup.sh 执行失败"

    if ! wait_until "Nacos 健康检查就绪 (${NACOS_BASE})" 120 nacos_healthy; then
        err "Nacos 启动失败，最近日志:"
        # Nacos 3.x 的 startup.sh 把控制台输出写进 logs/startup.log（2.x 才叫
        # start.out）。只 tail start.out 会在失败路径上静默跳过、丢掉最关键的日志，
        # 所以两个名字都试，再补一份服务端 nacos.log。
        local f
        for f in "$NACOS_HOME/logs/startup.log" "$NACOS_HOME/logs/start.out" "$NACOS_HOME/logs/nacos.log"; do
            [[ -f "$f" ]] || continue
            printf -- '--- tail -n 40 %s ---\n' "$f" >&2
            tail -n 40 "$f" >&2 || true
        done
        return 1
    fi
    pid="$(nacos_pid)"
    [[ -n "$pid" ]] && echo "$pid" > "$RUN_DIR/nacos.pid"
    ok "Nacos 已启动 (pid ${pid:-unknown})"
}

stop_nacos() {
    if ! port_listening "$NACOS_HTTP_PORT" && ! port_listening "$NACOS_GRPC_PORT" && ! port_listening "$NACOS_GRPC_TLS_PORT"; then
        ok "Nacos 未运行"
        rm -f "$RUN_DIR/nacos.pid"
        return 0
    fi
    log "停止 Nacos"
    if [[ -x "$NACOS_HOME/bin/shutdown.sh" ]]; then
        ( cd "$NACOS_HOME" && bash bin/shutdown.sh >/dev/null 2>&1 ) || true
    fi
    port_free_after "$NACOS_HTTP_PORT" 30 || true

    local pids p
    pids="$( { port_pids "$NACOS_HTTP_PORT"; port_pids "$NACOS_GRPC_PORT"; port_pids "$NACOS_GRPC_TLS_PORT"; } | tr ' ' '\n' | sort -u | tr '\n' ' ' )"
    if [[ -n "${pids// /}" ]]; then
        warn "Nacos 仍在监听，发送 SIGTERM: ${pids}"
        for p in $pids; do kill "$p" 2>/dev/null || true; done
        sleep 3
        pids="$( { port_pids "$NACOS_HTTP_PORT"; port_pids "$NACOS_GRPC_PORT"; port_pids "$NACOS_GRPC_TLS_PORT"; } | tr ' ' '\n' | sort -u | tr '\n' ' ' )"
        if [[ -n "${pids// /}" ]]; then
            warn "SIGTERM 未生效，发送 SIGKILL: ${pids}"
            for p in $pids; do kill -9 "$p" 2>/dev/null || true; done
        fi
    fi
    rm -f "$RUN_DIR/nacos.pid"
    # Success is "every Nacos port is free", not "a signal was delivered":
    # 9848/9849 are held by the same JVM and leak independently of 8848.
    local leftover="" p
    for p in "$NACOS_HTTP_PORT" "$NACOS_GRPC_PORT" "$NACOS_GRPC_TLS_PORT"; do
        if port_listening "$p"; then
            leftover+=" ${p}(PID $(port_pids "$p"))"
        fi
    done
    if [[ -n "$leftover" ]]; then
        err "Nacos 端口仍未释放:${leftover}"
        err "修复: kill -9 <PID> 后重试；或改用 NACOS_HTTP_PORT/NACOS_GRPC_PORT/NACOS_GRPC_TLS_PORT 覆盖端口"
        return 1
    fi
    ok "Nacos 已停止（${NACOS_HTTP_PORT}/${NACOS_GRPC_PORT}/${NACOS_GRPC_TLS_PORT} 已释放）"
}

status_nacos() {
    local rc=0
    if nacos_healthy; then
        ok "Nacos 健康: $(nacos_health_path)"
    else
        err "Nacos 不健康/未运行（修复: bash scripts/nacos.sh start 或 make dev-up）"
        rc=1
    fi
    printf '  pid: %s\n' "$(nacos_pid || true)"
    printf '  http:    %s\n' "$NACOS_BASE"
    printf '  console: http://127.0.0.1:%s\n' "$NACOS_CONSOLE_PORT"
    printf '  gRPC:    %s / %s\n' "$NACOS_GRPC_PORT" "$NACOS_GRPC_TLS_PORT"
    return "$rc"
}

case "${1:-}" in
    ensure) ensure_nacos ;;
    start)  start_nacos ;;
    stop)   stop_nacos ;;
    status) status_nacos ;;
    *) die "用法: $0 {ensure|start|stop|status}" ;;
esac
