#!/usr/bin/env bash
# 环境体检：14 项逐项 PASS/FAIL。任一项红 => exit 1，并汇总失败项与修复提示。
#
# 判定口径（这是"不假绿"的结构性保证，改判据前请先读 AC-5）：
#   - 端口项 #3-#8：只提供 LISTEN 证据，输出中明确标注"仅监听"，永不承担健康判定；
#   - 语义项 #9-#14：必须命中 HTTP 响应内容（Nacos OpenAPI body / actuator health 的
#     "status":"UP" / 链路 JSON 三字段），TCP 可达一律不算通过。
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

TOTAL=14
IDX=0
PASS=0
FAIL=0
FAILED_ITEMS=()
FAILED_HINTS=()

# Index auto-increments on every pass/fail call: the printed #N/TOTAL and the
# counters therefore cannot drift apart (a check that forgets to report is
# caught by the IDX != TOTAL guard at the bottom).
pass() {
    IDX=$((IDX + 1)); PASS=$((PASS + 1))
    printf '  %s[PASS]%s #%-5s %-34s %s\n' "$C_GREEN" "$C_RESET" "${IDX}/${TOTAL}" "$1" "${2:-}"
}
fail() {
    IDX=$((IDX + 1)); FAIL=$((FAIL + 1))
    FAILED_ITEMS+=("$1"); FAILED_HINTS+=("${3:-查看 logs/ 下对应日志后重试}")
    printf '  %s[FAIL]%s #%-5s %-34s %s\n' "$C_RED" "$C_RESET" "${IDX}/${TOTAL}" "$1" "${2:-}"
    printf '         %s修复:%s %s\n' "$C_YELLOW" "$C_RESET" "${3:-查看 logs/ 下对应日志后重试}"
}

log "环境体检：Spring Cloud + Nacos（JDK 25 / Boot 4 / Cloud 2025.1）"
printf '  判定：端口项 #3-#8 = 仅监听；健康/配置/实例/链路 #9-#14 = HTTP 响应内容\n\n'

# ---- #1-#2 工具链 ----
if command -v java >/dev/null 2>&1; then
    RAW="$(java -version 2>&1 | head -n1)"
    MAJOR="$(printf '%s' "$RAW" | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [[ "$MAJOR" =~ ^[0-9]+$ ]] && (( MAJOR >= 25 )); then
        pass "JDK >= 25" "openjdk $(printf '%s' "$RAW" | sed -E 's/.*version "([^"]+)".*/\1/')"
    else
        fail "JDK >= 25" "$RAW" "安装 JDK 25：brew install openjdk@25；或按 README「JDK < 25」切换指引导出 JAVA_HOME"
    fi
else
    fail "JDK >= 25" "未找到 java" "brew install openjdk@25，并 export JAVA_HOME=\$(/usr/libexec/java_home -v 25)"
fi

if command -v mvn >/dev/null 2>&1; then
    pass "Maven 可用" "$(mvn -v 2>/dev/null | awk 'NR==1{print $3}')"
else
    fail "Maven 可用" "未找到 mvn" "brew install maven（本需求锁定 3.9.x；README 版本矩阵有对照）"
fi

# ---- #3-#8 六端口（仅监听，不做健康判定）----
for spec in "${GATEWAY_PORT}:api-gateway" "${DEMO_PORT}:demo-service" \
            "${NACOS_HTTP_PORT}:nacos-http" "${NACOS_CONSOLE_PORT}:nacos-console" \
            "${NACOS_GRPC_PORT}:nacos-grpc" "${NACOS_GRPC_TLS_PORT}:nacos-grpc-tls"; do
    port="${spec%%:*}"; name="${spec#*:}"
    pids="$(port_pids "$port")"
    if [[ -n "$pids" ]]; then
        pass "端口 ${port} ${name}" "LISTEN pid ${pids}（仅监听，不代表健康）"
    else
        fail "端口 ${port} ${name}" "未监听（仅监听项，不做健康判定）" \
            "make dev-up（若端口被外部进程占用，先 make dev-down 或 lsof -nP -iTCP:${port} -sTCP:LISTEN）"
    fi
done

# ---- #9 Nacos 健康 OpenAPI（body 命中，不是 200 就算）----
HEALTH_PATH="$(nacos_health_path || true)"
if [[ -n "$HEALTH_PATH" ]]; then
    pass "Nacos 健康 OpenAPI" "${HEALTH_PATH} 命中健康 body"
else
    fail "Nacos 健康 OpenAPI" "8848 无健康响应（200 但 body 不符也算红）" \
        "make dev-up；失败时看 .nacos/logs/startup.log 与 .nacos/logs/nacos.log（控制台端口应为 ${NACOS_CONSOLE_PORT}）"
fi

# ---- #10 Nacos 配置可读（config-source=nacos 是唯一可证伪证据）----
CONFIG_BODY="$(http_body "$(nacos_config_url)")"
LOCAL_YML="$ROOT/demo-service/src/main/resources/application.yml"
if printf '%s' "$CONFIG_BODY" | grep -Eq 'config-source:[[:space:]]*"?nacos'; then
    if [[ -f "$LOCAL_YML" ]] && grep -Eq '^[[:space:]]*config-source:' "$LOCAL_YML"; then
        fail "Nacos 配置可读 ${DATA_ID}" "本地 application.yml 也定义了 config-source，nacos 证据被污染" \
            "删除 demo-service/src/main/resources/application.yml 中的 config-source 键（它只能来自 Nacos）"
    else
        pass "Nacos 配置可读 ${DATA_ID}" "config-source=nacos（本地未定义同键，可证伪）"
    fi
else
    fail "Nacos 配置可读 ${DATA_ID}" "读取为空或不含 config-source: nacos" \
        "make dev-up 会幂等发布 config/nacos/demo-service.yml；若开启鉴权请按 README 关闭后再试"
fi

# ---- #11 Nacos 健康实例 ----
if nacos_has_instance; then
    pass "Nacos 健康实例 demo-service" "实例列表含健康 port=${DEMO_PORT}"
else
    fail "Nacos 健康实例 demo-service" "实例列表无健康 port=${DEMO_PORT}" \
        "tail -n 50 logs/demo-service.log 看 Nacos 连接；修复后 make dev-up"
fi

# ---- #12-#13 服务健康（HTTP body，不是 TCP）----
if app_health_up "$DEMO_PORT"; then
    pass "demo-service /actuator/health" "status=UP"
else
    fail "demo-service 健康" "HTTP body 未命中 \"status\":\"UP\"" \
        "tail -n 50 logs/demo-service.log；确认 Nacos ${NACOS_HTTP_PORT} 可用后 make dev-up"
fi

if app_health_up "$GATEWAY_PORT"; then
    pass "api-gateway /actuator/health" "status=UP"
else
    fail "api-gateway 健康" "HTTP body 未命中 \"status\":\"UP\"" \
        "tail -n 50 logs/api-gateway.log；确认 Nacos ${NACOS_HTTP_PORT} 可用后 make dev-up"
fi

# ---- #14 端到端调用链（网关 -> Nacos 注册 -> 服务 -> Nacos 配置）----
gateway_chain
CHAIN_BODY="$(printf '%s' "$CHAIN_BODY" | tr -d '\n' | cut -c1-240)"
if [[ "$CHAIN_CODE" == "200" ]] \
    && printf '%s' "$CHAIN_BODY" | grep -Eq '"instancePort"[[:space:]]*:[[:space:]]*'"${DEMO_PORT}" \
    && printf '%s' "$CHAIN_BODY" | grep -Eq '"configSource"[[:space:]]*:[[:space:]]*"nacos"'; then
    pass "调用链 /api/demo/hello" "200 instancePort=${DEMO_PORT} configSource=nacos"
else
    fail "调用链 /api/demo/hello (demo-service)" "HTTP ${CHAIN_CODE:-000} body=${CHAIN_BODY:-<empty>}" \
        "先确认 #12/#13 是否 UP、#11 是否有健康实例；服务刚重启时网关 LoadBalancer 缓存约 35s，可等 30s 再跑一次"
fi

# ---- 汇总 ----
printf '\n结果: %d 项检查 -> %s%d PASS%s / %s%d FAIL%s\n' "$TOTAL" \
    "$C_GREEN" "$PASS" "$C_RESET" "$C_RED" "$FAIL" "$C_RESET"

if (( IDX != TOTAL )); then
    err "体检项数不一致：已报告 ${IDX} 项，契约要求 ${TOTAL} 项（脚本 bug，按失败处理）"
    exit 1
fi

if (( FAIL > 0 )); then
    printf '失败项 (%d):\n' "$FAIL"
    i=0
    while (( i < FAIL )); do
        printf '  - %s\n    修复: %s\n' "${FAILED_ITEMS[$i]}" "${FAILED_HINTS[$i]}"
        i=$((i + 1))
    done
    exit 1
fi

ok "环境全绿：14/14 项通过（端口仅监听 + HTTP 语义双重确认）"
