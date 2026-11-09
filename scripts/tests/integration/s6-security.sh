#!/usr/bin/env bash
# s6-security.sh — AC-10 安全横切：固定凭据全链路 0 命中（响应/日志/审计/DB 可读列）、
# 集成配置接口必须 JWT、webhook 验签失败 401 且不落业务行、跨租户 403、匿名端点限流、
# 密钥不进源码（.run 文件被 gitignore，Java/YAML/SQL 无明文）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}"; exit 1; }
JID="$(i_config JENKINS)" || exit 1
GID="$(i_config GITLAB)" || exit 1
RD="$(i_rd "s6安全-${RUN_TAG}")" || exit 1
REL="$(i_release_with "s6版本-${RUN_TAG}" "$RD")" || exit 1
ORD="$(i_order_for "$REL" "$RD")"
[[ -n "$ORD" && "$ORD" != "null" ]] || { err "发布单创建失败"; exit 1; }
SCAN="$(mktemp -t m7-s6-scan.XXXXXX)"
trap 'rc=$?; rm -f "$SCAN"; i_cleanup || true; exit "$rc"' EXIT
: >"$SCAN"

scan() { # scan <desc> <method> <path> [body]
    local desc="$1" method="$2" path="$3" body="${4:-}"
    api_call "$method" "$path" "$body"
    if grep -qF "$INTEG_CREDENTIAL" <<<"$HTTP_BODY"; then
        fail "${desc} 泄漏凭据明文" "响应必须只回 hasCredential + 末四位"
        grep -nF "$INTEG_CREDENTIAL" <<<"$HTTP_BODY" >>"$SCAN" 2>/dev/null || true
    else
        pass "${desc} 无凭据明文"
    fi
    if grep -qiE 'credentialEnc|credential_enc|secretEnc|"credential"|"secret"' <<<"$HTTP_BODY"; then
        fail "${desc} 暴露 credential/secret 字段名" "读接口不得返回密文或字段"
    else
        pass "${desc} 无 credential/secret 字段"
    fi
}

section "s6 · 读接口全链路凭据 0 命中"
scan "配置列表" GET "/api/req/integration/configs"
scan "同步日志" GET "/api/req/integration/logs?page=1&size=50"
scan "构建列表" GET "/api/req/deploy-orders/${ORD}/builds?limit=5"
scan "需求集成卡" GET "/api/req/requirements/${RD}/integration"
scan "准入结果" POST "/api/req/deploy-orders/${ORD}/evaluate" '{}'

section "s6 · 数据库可读列（摘要/URL）0 命中"
HIT="$(db_q "SELECT COUNT(*) FROM integration_sync_log WHERE (COALESCE(request_summary,'') LIKE '%${INTEG_CREDENTIAL}%'
      OR COALESCE(response_summary,'') LIKE '%${INTEG_CREDENTIAL}%'
      OR COALESCE(target_url,'') LIKE '%${INTEG_CREDENTIAL}%')")"
if [[ "$HIT" == "0" ]]; then pass "integration_sync_log 摘要/URL 无凭据明文"; else fail "${HIT} 行日志含凭据" "SensitiveMasker 必须在写库前过滤 Authorization/token/password"; fi
TOKEN_LEAK="$(db_q "SELECT COUNT(*) FROM integration_sync_log WHERE target_url REGEXP '[?&](token|access_token|private_token)='")"
if [[ "$TOKEN_LEAK" == "0" ]]; then pass "URL query token 已被脱敏"; else fail "${TOKEN_LEAK} 行 URL 带 token" "masker 需过滤 query 中的 token/access_token/private_token"; fi

section "s6 · 集成配置接口必须 JWT（匿名 401，webhook 是唯一匿名前缀）"
http_call GET "${GATEWAY_URL}/api/req/integration/configs"
if [[ "$HTTP_CODE" == "401" ]]; then pass "匿名读配置 401"; else fail "匿名读配置返回 ${HTTP_CODE}" "JwtGatewayFilter 只放行 /api/integration/webhooks/"; fi
http_call POST "${GATEWAY_URL}/api/req/integration/configs" '{}'
if [[ "$HTTP_CODE" == "401" ]]; then pass "匿名写配置 401"; else fail "匿名写配置返回 ${HTTP_CODE}" "配置接口不可匿名"; fi
http_call POST "${GATEWAY_URL}/api/integration/webhooks/gitlab/${GID}" '{}' -H "X-Gitlab-Token: ${INTEG_SECRET}" -H "X-Gitlab-Event: Merge Request Hook"
if [[ "$HTTP_CODE" != "401" ]]; then pass "webhook 匿名可达（HTTP ${HTTP_CODE}，不要求 JWT）"; else fail "webhook 被 JWT 拦截" "匿名前缀必须放行 /api/integration/webhooks/"; fi

section "s6 · 验签失败 401 且不落业务行"
B_BEFORE="$(db_count build_record "deploy_order_id=${ORD}")"
i_jenkins_webhook_raw "$JID" "$(i_jenkins_body 999 SUCCESS)" "sha256=00" "$(date +%s)"
expect "Jenkins 验签失败 401" 401
same_count "401 不写 build_record" build_record "deploy_order_id=${ORD}" "$B_BEFORE"
M_BEFORE="$(db_count mr_sync "requirement_id=${RD}")"
i_gitlab_webhook "$GID" "demo-group/demo-project" 9 merged "Merge Request Hook" "" "wrong-token"
expect "GitLab token 错误 401" 401
same_count "401 不写 mr_sync" mr_sync "requirement_id=${RD}" "$M_BEFORE"

section "s6 · 跨租户（t1 身份 + 其他 X-Tenant-Id）"
for path in "/api/req/integration/configs" "/api/req/deploy-orders/${ORD}/builds" "/api/req/requirements/${RD}/mr"; do
    # 第三个参数是 body 占位：http_call 用 shift 3 收走 <method> <url> <body>，
    # 漏掉它就等于把第一个 -H 当成 body，curl 会把 token 当 URL 拒绝（HTTP_CODE=000）。
    http_call GET "${GATEWAY_URL}${path}" "" -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: 999999"
    if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "404" ]]; then
        pass "跨租户 ${path} 被拒（${HTTP_CODE}）"
    else
        fail "跨租户 ${path} 返回 ${HTTP_CODE}" "ScopeSql + 租户守卫必须隔离"
    fi
done

section "s6 · 无 JWT 的 webhook 有 20 req/s 限流"
i_stub_reset
# 必须是真的"突发"：顺序 curl 每次约 20ms，30 次在 0.6s 内只花掉 30 个令牌、
# 又补回约 12 个，20/s 的桶根本打不空（这不是产品的问题，是夹具不够快）。
# 10 路并发 × 60 次才能让令牌桶在同一秒内被打穿。
RL_CODES="$(seq 1 60 | xargs -P 10 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST "${GATEWAY_URL}/api/integration/webhooks/gitlab/${GID}" \
    -H 'Content-Type: application/json' -d '{"object_kind":"push"}' \
    -H "X-Gitlab-Token: ${INTEG_SECRET}" -H "X-Gitlab-Event: Push Hook")"
RL_429="$(printf '%s\n' "$RL_CODES" | grep -c '^429$')"
if (( RL_429 > 0 )); then pass "突发 60 次触发限流（429 × ${RL_429}）"; else fail "60 次突发未限流" "WebhookRateLimiter 20 req/s 令牌桶"; fi
sleep 2

section "s6 · 密钥不落源码（.run/integration.key 被 gitignore）"
if git check-ignore -q .run/integration.key 2>/dev/null; then pass ".run/integration.key 已被 gitignore"; else fail ".run/integration.key 未忽略" "密钥文件必须在 .gitignore"; fi
SRC_HIT="$(grep -rlF "$INTEG_CREDENTIAL" req-service/src api-gateway/src deliver-service/src platform-common/src web/src 2>/dev/null | head -5)"
if [[ -z "$SRC_HIT" ]]; then pass "产品源码无固定测试凭据"; else fail "源码内出现测试凭据: ${SRC_HIT}" "凭据只允许出现在测试脚本与运行时 env"; fi
KEY_HIT="$(grep -rn "INTEGRATION_CREDENTIAL_KEY" req-service/src/main/resources/application.yml | head -2)"
if [[ -n "$KEY_HIT" ]]; then pass "密钥只从环境变量注入（application.yml 有 \${INTEGRATION_CREDENTIAL_KEY}）"; else fail "application.yml 未声明密钥来源" "IntegrationCrypto 需 env 注入"; fi

guard s6-security
