#!/usr/bin/env bash
# platform-down.sh — 逆序停止平台层（web → deliver → req），不动 r1 的
# Nacos/网关/demo（那些由 make dev-down 负责）。可重复执行。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

log "停止平台层（web → deliver-service → req-service）"
stop_web || true
stop_app deliver-service || true
stop_app req-service || true

FAIL=0
for pair in "${WEB_PORT}:web" "${DELIVER_PORT}:deliver-service" "${REQ_PORT}:req-service"; do
    port="${pair%%:*}"
    name="${pair##*:}"
    if port_free_after "$port" 5; then
        ok "端口 ${port} 已释放 (${name})"
    else
        err "端口 ${port} 仍被占用 (${name}): $(port_pids "$port")"
        FAIL=1
    fi
done

if (( FAIL > 0 )); then
    err "修复: lsof -nP -iTCP:<port> -sTCP:LISTEN 找到占用进程后停止；不要 kill 不属于本项目的进程"
    exit 1
fi
ok "平台层已停止（r1 基线栈仍在运行；需要时 make dev-down）"
