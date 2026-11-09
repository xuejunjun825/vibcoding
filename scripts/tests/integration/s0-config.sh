#!/usr/bin/env bash
# s0-config.sh — AC-1 集成配置底座：四类系统可配、凭据只写不读、空串不清空、
# AES-GCM 随机 IV（同明文两次密文不同）、非法 base_url 拒绝、连通性测试、同步日志、启用唯一。
#
# 凭据固定为 S3cr3t-IT-2026（AC-10）：任何响应 / 日志 / 数据库可读列都不允许出现它。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}；先 make integration-verify 或 node scripts/tests/integration/stub/server.mjs"; exit 1; }

section "s0 · 四类系统配置落盘（凭据写入即加密）"
JID=""; GID=""; SID=""; NID=""   # /bin/bash 3.2 无关联数组，四个系统各用一个变量
for sys in JENKINS GITLAB SONAR NEXUS; do
    cfg="$(i_config "$sys")" || true
    if [[ "$cfg" =~ ^[0-9]+$ ]]; then
        case "$sys" in
            JENKINS) JID="$cfg" ;;
            GITLAB)  GID="$cfg" ;;
            SONAR)   SID="$cfg" ;;
            NEXUS)   NID="$cfg" ;;
        esac
        pass "创建/更新 ${sys} 配置（id=${cfg}）"
    else
        fail "创建 ${sys} 配置失败" "看 req 日志：integration_config 校验、uk_cfg_active、IntegrationCrypto 密钥"
    fi
done

section "s0 · 凭据只写不读（响应无明文、只回末四位）"
api_call GET /api/req/integration/configs?system=JENKINS
expect "配置列表 200" 200
if grep -qF "$INTEG_CREDENTIAL" <<<"$HTTP_BODY"; then
    fail "列表响应泄漏凭据明文" "ConfigView 只能回 hasCredential + last4，禁止回 credential/credentialEnc"
else
    pass "列表响应不含凭据明文"
fi
expect_jq "hasCredential=true" '[.[0].hasCredential][0]' 'true' "ConfigView.hasCredential 由 credential_enc IS NOT NULL 派生"
expect_jq "只回末四位 2026" '[.[0].credentialLast4][0]' '2026' "last4 由 IntegrationCrypto.last4 计算"

section "s0 · 密文随机 IV：同一明文两次写入密文不同，且库里无明文"
JID="${JID:-}"
if [[ -n "$JID" ]]; then
    HEX1="$(db_q "SELECT HEX(credential_enc) FROM integration_config WHERE id=${JID} AND tenant_id=${TENANT_ID}")"
    api_call PATCH "/api/req/integration/configs/${JID}" "$(jq -cn --arg c "$INTEG_CREDENTIAL" '{credential:$c}')"
    expect "凭据轮换 200" 200
    HEX2="$(db_q "SELECT HEX(credential_enc) FROM integration_config WHERE id=${JID} AND tenant_id=${TENANT_ID}")"
    if [[ -n "$HEX1" && -n "$HEX2" && "$HEX1" != "$HEX2" ]]; then
        pass "同明文两次加密密文不同（AES-GCM 随机 IV / v1 前缀）"
    else
        fail "两次加密密文相同或为空" "AesGcmCodec 每次必须新 IV；密文带 v1 版本前缀"
    fi
    PLAIN_HEX="$(printf '%s' "$INTEG_CREDENTIAL" | xxd -p | tr -d '\n')"
    HEX2_LC="$(printf '%s' "$HEX2" | tr 'A-F' 'a-f')"
    PLAIN_LC="$(printf '%s' "$PLAIN_HEX" | tr 'A-F' 'a-f')"
    if [[ -n "$HEX2" && "$HEX2_LC" != *"$PLAIN_LC"* ]]; then
        pass "credential_enc 内不含明文 hex"
    else
        fail "credential_enc 命中明文 hex" "凭据必须 AES-GCM 加密后入库"
    fi
else
    fail "缺少 JENKINS 配置 id，无法验证密文" "先修 s0 第一条"
fi

section "s0 · 空串不清空凭据（PATCH credential:\"\" 是 no-op）"
if [[ -n "$JID" ]]; then
    api_call PATCH "/api/req/integration/configs/${JID}" '{"credential":""}'
    expect "空串 PATCH 200" 200
    api_call GET /api/req/integration/configs?system=JENKINS
    expect_jq "空串后仍 hasCredential" '[.[0].hasCredential][0]' 'true' "空串语义=不改，只有显式清除（带确认名）才清"
    expect_jq "空串后末四位不变" '[.[0].credentialLast4][0]' '2026'
fi

section "s0 · 非法 base_url 拒绝（400，不落库）"
api_call PATCH "/api/req/integration/configs/${JID}" '{"baseUrl":"ftp://example.com/not-http"}'
expect "ftp:// 被拒 400" 400
api_call PATCH "/api/req/integration/configs/${JID}" '{"baseUrl":"not-a-url"}'
expect "非 URL 被拒 400" 400

section "s0 · 连通性测试走同一适配器入口（testConnection）"
api_call POST "/api/req/integration/configs/${JID}/test" '{}'
expect "测试连接 200" 200
expect_jq "测试结果 ok=true" '.ok' 'true' "桩 /api/json 应返回 200；检查 base_url 与 egress 白名单"
expect_db "last_test 已回写" "SELECT last_test_ok FROM integration_config WHERE id=${JID}" eq 1

section "s0 · 每次尝试写一行 integration_sync_log（脱敏摘要）"
api_call GET "/api/req/integration/logs?system=JENKINS&page=1&size=20"
expect "日志分页 200" 200
expect_jq "至少一行同步日志" '(.items // []) | length > 0' 'true' "OutboundHttpClient 每次尝试必须落 integration_sync_log"
expect_jq "存在 SUCCESS 结果行" '[(.items // [])[] | select(.result=="SUCCESS")] | length > 0' 'true'
if grep -qF "$INTEG_CREDENTIAL" <<<"$HTTP_BODY"; then
    fail "同步日志泄漏凭据" "SensitiveMasker 必须过滤 Authorization/private-token/password/URL query token"
else
    pass "同步日志不含凭据明文"
fi

section "s0 · 日志明细端点与时间/关键字过滤（TECHNICAL §216/§217）"
api_call GET "/api/req/integration/logs?system=JENKINS&page=1&size=20"
LOG_ID="$(jget "$HTTP_BODY" '.items[0].id')"
if [[ -n "$LOG_ID" && "$LOG_ID" != "null" ]]; then
    api_call GET "/api/req/integration/logs/${LOG_ID}"
    expect "日志明细 200" 200
    expect_jq "明细 id 与列表首行一致" '.id' "$LOG_ID"
else
    fail "列表未取到日志 id，无法验证明细端点" "integration_sync_log 应至少有一行 JENKINS 记录"
fi
api_call GET "/api/req/integration/logs?keyword=TEST_CONNECTION&page=1&size=20"
expect "关键字过滤 200" 200
expect_jq "关键字命中连通性测试日志" '(.items // []) | length > 0' 'true' \
    "keyword 应对 operation/targetUrl/requestSummary/errorCode 做模糊匹配"
api_call GET "/api/req/integration/logs?keyword=no-such-operation-${RUN_TAG}"
expect "关键字不命中 200" 200
expect_jq "关键字不命中时为空" '(.total // 0)' '0' \
    "keyword 若被静默忽略，这里会返回全部日志"
api_call GET "/api/req/integration/logs?from=2000-01-01T00:00:00&to=2000-01-02T00:00:00"
expect "时间区间过滤 200" 200
expect_jq "区间外无记录" '(.total // 0)' '0' \
    "from/to 必须进 JPQL 条件，而不是被控制器静默忽略"

section "s0 · 启用唯一：同系统第二条启用 → 409"
api_call POST /api/req/integration/configs "$(i_cfg_body JENKINS "stub-jenkins-dup-${RUN_TAG}" false)"
DUP="$(jget "$HTTP_BODY" '.id // .config.id')"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]] && [[ -n "$DUP" && "$DUP" != "null" ]]; then
    pass "第二条配置可先以停用态创建（id=${DUP}）"
    api_call PATCH "/api/req/integration/configs/${DUP}" '{"enabled":true}'
    expect "第二条启用 → 409（ak_cfg_active 唯一）" 409
    api_call DELETE "/api/req/integration/configs/${DUP}"
else
    fail "第二条配置创建失败 (HTTP ${HTTP_CODE})" "唯一约束应只拦启用态，不拦停用态创建"
fi

guard s0-config
