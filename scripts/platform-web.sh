#!/usr/bin/env bash
# platform-web.sh — build the Vue app and serve it with `vite preview`.
# Idempotent: an already-serving preview on WEB_PORT is reused only when the
# HTML probe passes, so a foreign process squatting on 5173 can never be
# mistaken for our web layer.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STAGE="web 前置检查"
on_err() {
    local rc=$?
    err "platform-web 失败于阶段: ${STAGE} (exit ${rc})"
    err "修复: 查看 ${LOG_DIR}/web.log；构建错误先看 pnpm -C web build 的输出"
    exit "$rc"
}
trap on_err ERR

STAGE="web 前置检查"
need_cmd node
need_cmd pnpm
[[ -d "$ROOT/web" ]] || die "缺少 web/ 目录：请先完成 T7（前端基座）"
[[ -f "$ROOT/web/package.json" ]] || die "缺少 web/package.json"

if web_up && port_listening "$WEB_PORT"; then
    ok "web 已在 ${WEB_URL} 提供页面"
    exit 0
fi

if port_listening "$WEB_PORT"; then
    err "端口 ${WEB_PORT} 已被占用，但不是本项目的页面:"
    err "  进程: $(port_pids "$WEB_PORT")"
    err "  修复: lsof -nP -iTCP:${WEB_PORT} -sTCP:LISTEN 确认后停止该进程，或 WEB_PORT=5174 make platform-web"
    exit 1
fi

STAGE="安装依赖"
if [[ ! -d "$ROOT/web/node_modules" ]]; then
    log "web/node_modules 缺失，安装依赖"
    if [[ -f "$ROOT/web/pnpm-lock.yaml" ]]; then
        pnpm -C "$ROOT/web" install --frozen-lockfile
    else
        pnpm -C "$ROOT/web" install
    fi
    ok "依赖安装完成"
else
    ok "依赖已存在（web/node_modules）"
fi

STAGE="构建前端"
log "构建前端 (pnpm -C web build)"
pnpm -C "$ROOT/web" build
[[ -f "$ROOT/web/dist/index.html" ]] || die "构建后缺少 web/dist/index.html：检查 vite.config.ts 的 build.outDir"
ok "前端构建完成"

STAGE="启动 vite preview"
VITE_BIN="$ROOT/web/node_modules/.bin/vite"
[[ -x "$VITE_BIN" ]] || die "缺少 ${VITE_BIN}：请执行 pnpm -C web install"
(
    cd "$ROOT/web"
    nohup "$VITE_BIN" preview --host 127.0.0.1 --port "$WEB_PORT" --strictPort \
        >> "$LOG_DIR/web.log" 2>&1 &
    echo $! > "$RUN_DIR/web.pid"
)
sleep 1
WAIT=0
while ! web_up; do
    WAIT=$((WAIT + 1))
    if (( WAIT >= 60 )); then
        err "web 60s 内未就绪 (${WEB_URL}/)"
        err "最近日志:"
        tail -n 20 "$LOG_DIR/web.log" >&2 || true
        exit 1
    fi
    sleep 1
done
ok "web 已就绪: ${WEB_URL} (pid $(cat "$RUN_DIR/web.pid" 2>/dev/null || echo '?'))"
