#!/usr/bin/env bash
# platform-up.sh — 幂等拉起平台层：MySQL 库 → req-service(8082) →
# deliver-service(8083) → 网关路由 → web(5173)。语义就绪，不 sleep。
#
# r1 的 dev-up 是唯一起栈入口：这里先调用它（幂等），再叠加平台层，
# 避免两套 Nacos/网关/demo 启动逻辑。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STAGE="0 前置检查"
STARTED_REQ=0
STARTED_DELIVER=0
STARTED_WEB=0

on_err() {
    local rc=$?
    err "platform-up 失败于阶段: ${STAGE} (exit ${rc})"
    if (( STARTED_WEB == 1 )); then stop_web || true; fi
    if (( STARTED_DELIVER == 1 )); then stop_app deliver-service || true; fi
    if (( STARTED_REQ == 1 )); then stop_app req-service || true; fi
    err "已回收本次启动的进程；修复后重跑 make platform-up（幂等）"
    exit "$rc"
}
trap on_err ERR

check_port_owner() {
    local port="$1" name="$2"
    if port_listening "$port" && ! app_alive "$name"; then
        err "端口 ${port} 已被其它进程占用: $(port_pids "$port")"
        err "修复: lsof -nP -iTCP:${port} -sTCP:LISTEN 确认；本项目的进程用 make dev-down / make platform-down 停止"
        exit 1
    fi
}

STAGE="0 前置检查"
for c in java mvn curl jq mysql node pnpm; do need_cmd "$c"; done
[[ -f "$ROOT/req-service/pom.xml" ]] || die "缺少 req-service/pom.xml：请先完成 T0（工程骨架），platform-up 不会替你重建模块"
[[ -f "$ROOT/deliver-service/pom.xml" ]] || die "缺少 deliver-service/pom.xml：请先完成 T0（工程骨架）"
check_port_owner "$REQ_PORT" req-service
check_port_owner "$DELIVER_PORT" deliver-service
db_up || die "MySQL 不可连 (${PLATFORM_DB_HOST}:${PLATFORM_DB_PORT} as ${PLATFORM_DB_USER})：修复: brew services start mysql，然后重跑"
ok "MySQL 可连 (${PLATFORM_DB_HOST}:${PLATFORM_DB_PORT})"

STAGE="1 r1 基线栈（Nacos + 网关 + demo）"
log "调用 scripts/dev-up.sh（幂等；首次会下载 Nacos）"
"$ROOT/scripts/dev-up.sh"

STAGE="2 数据库"
if db_exists; then
    ok "数据库 ${PLATFORM_DB_NAME} 已存在"
else
    log "创建数据库 ${PLATFORM_DB_NAME}"
    db_admin_q "CREATE DATABASE IF NOT EXISTS \`${PLATFORM_DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci" \
        || die "创建数据库失败：检查 ${PLATFORM_DB_USER} 是否有 CREATE 权限"
    db_exists || die "数据库 ${PLATFORM_DB_NAME} 创建后仍不可见"
    ok "数据库 ${PLATFORM_DB_NAME} 已创建"
fi

STAGE="3 构建全部 Maven 模块"
detect_maven_repo
log "mvn -q -DskipTests package（含平台模块）"
( cd "$ROOT" && mvn -q ${MAVEN_REPO_ARG:-} -DskipTests package )
ok "构建完成"

STAGE="4 启动 req-service (${REQ_PORT})"
export PLATFORM_SEED_ENABLED=true
export PLATFORM_INTERNAL_TOKEN PLATFORM_JWT_SECRET PLATFORM_MASTER_KEY
if ! app_alive req-service; then STARTED_REQ=1; fi
start_app req-service "$REQ_PORT" req-service
log "等待 req-service 语义就绪（status=UP, db=up, flyway 已迁移）"
WAIT=0
while ! req_ready; do
    WAIT=$((WAIT + 1))
    if (( WAIT >= 120 )); then
        err "req-service 120s 内未就绪: ${REQ_URL}/internal/health"
        err "最近日志:"
        tail -n 30 "$LOG_DIR/req-service.log" >&2 || true
        err "修复: 迁移失败见日志中的 Flyway 错误；若报 MySQL 9.6 unsupported，按 TECHNICAL §10 R1 覆盖 flyway.version"
        exit 1
    fi
    sleep 1
done
ok "req-service 就绪: $(curl -fsS --max-time 4 "${REQ_URL}/internal/health")"

STAGE="5 启动 deliver-service (${DELIVER_PORT})"
if ! app_alive deliver-service; then STARTED_DELIVER=1; fi
start_app deliver-service "$DELIVER_PORT" deliver-service
WAIT=0
while ! deliver_ready; do
    WAIT=$((WAIT + 1))
    if (( WAIT >= 120 )); then
        err "deliver-service 120s 内未就绪: ${DELIVER_URL}/internal/health"
        tail -n 30 "$LOG_DIR/deliver-service.log" >&2 || true
        err "修复: ddl-auto=validate 失败通常意味着 V1 迁移与实体不一致，先看 req-service 是否迁移成功"
        exit 1
    fi
    sleep 1
done
ok "deliver-service 就绪"

STAGE="6 网关路由（/api/req、/api/deliver）"
route_ready() {
    api_call POST /api/req/auth/login '{"tenantCode":"__probe__","username":"__probe__","password":"__probe__"}'
    [[ "$HTTP_CODE" =~ ^(400|401|403|422)$ ]] && json_ok "$HTTP_BODY"
}
WAIT=0
while ! route_ready; do
    WAIT=$((WAIT + 1))
    if (( WAIT >= 90 )); then
        err "网关 90s 内未把 /api/req/** 路由到 req-service（最后响应 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 200)）"
        err "修复: 检查 Nacos 实例与 LoadBalancer 缓存（最长 35s）；确认 PlatformRoutes 与 JwtGatewayFilter 已随 T0 构建进网关 jar"
        exit 1
    fi
    sleep 1
done
ok "网关路由就绪（匿名登录探针返回 ${HTTP_CODE}，证明到达 req-service）"

STAGE="7 web (${WEB_PORT})"
if ! web_up; then STARTED_WEB=1; fi
"$ROOT/scripts/platform-web.sh"
web_up || die "web 就绪探针失败: ${WEB_URL}/ 未命中 <!doctype html>"

STAGE="READY"
ok "platform 已就绪"
printf '\n  gateway  %s\n  req      %s\n  deliver  %s\n  web      %s\n\n' \
    "$GATEWAY_URL" "$REQ_URL" "$DELIVER_URL" "$WEB_URL"
printf '  下一步: make platform-seed   # 幂等种子（AC-19）\n'
printf '          make platform-verify # AC-1..AC-20\n\n'
