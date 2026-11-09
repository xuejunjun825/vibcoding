#!/usr/bin/env bash
# s4-artifact.sh — AC-7 Nexus 制品：检索 ≤50 且字段完整、登记 sha256 三方一致、
# 绑定/晋级幂等、CHECKSUM_MISMATCH 409、SSRF 任意 URL 400 且目标 0 请求、跨租户隔离。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}"; exit 1; }
NID="$(i_config NEXUS)" || exit 1
RD="$(i_rd "s4制品-${RUN_TAG}")" || exit 1
REL="$(i_release_with "s4版本-${RUN_TAG}" "$RD")" || exit 1
ORD="$(i_order_for "$REL" "$RD")"
[[ -n "$ORD" && "$ORD" != "null" ]] || { err "发布单创建失败"; exit 1; }
SHA_REL="$(printf 'a1%.0s' $(seq 1 32))"
SHA_SNAP="$(printf 'b2%.0s' $(seq 1 32))"
# 版本号每次运行都换：artifact_record 的坐标是幂等键，固定 1.2.3 会在第二次运行时
# 正确地撞出 409 ARTIFACT_ALREADY_BOUND（制品已绑到别的发布单）——产品行为对，夹具跑不动。
VER="1.2.3-${RUN_TAG}"
VER_BAD="9.9.9-${RUN_TAG}"
info "config=${NID} order=${ORD} sha(releases)=${SHA_REL:0:12}… ver=${VER}"

section "s4 · 制品检索：字段完整、limit 上限 50"
api_call GET "/api/req/integration/artifacts/search?q=demo-app&repository=releases&limit=10"
expect "检索 200" 200
expect_jq "返回 3 条（jar/pom/旧版本）" '(.items // .artifacts // []) | length' '3'
expect_jq "字段 artifactName" '(.items // .artifacts)[0].artifactName' 'demo-app'
expect_jq "字段 sha256（64 hex）" '(.items // .artifacts)[0].sha256 | length' '64'
expect_jq "字段 downloadUrl" '(.items // .artifacts)[0].downloadUrl | type' 'string'
api_call GET "/api/req/integration/artifacts/search?q=demo-app&repository=releases&limit=500"
N="$(jq -r '(.items // .artifacts // []) | length' <<<"$HTTP_BODY")"
if [[ "$N" =~ ^[0-9]+$ ]] && (( N <= 50 )); then pass "limit 被夹到 ${N}（≤50）"; else fail "limit 未夹紧（返回 ${N}）" "NexusAdapter.search 上限 50"; fi

section "s4 · 本地登记：sha256 与 HEAD 校验和三方一致才落库"
BODY="$(jq -cn --arg sha "$SHA_REL" --arg url "${STUB_URL}/repository/releases/com/example/demo-app/${VER}/demo-app-${VER}.jar" --arg ver "$VER" '{
  source:"NEXUS", repository:"releases", groupId:"com.example", artifactName:"demo-app",
  version:$ver, packaging:"jar", sha256:$sha, downloadUrl:$url}')"
api_call POST "/api/req/deploy-orders/${ORD}/artifacts" "$BODY"
expect "登记 200/201" 200
AID="$(jget "$HTTP_BODY" '.id')"
if [[ "$AID" =~ ^[0-9]+$ ]]; then pass "制品 id=${AID}"; else fail "登记未返回 id: $(printf '%s' "$HTTP_BODY" | head -c 200)" "ArtifactService.bind 必须在事务内校验三方 sha256"; fi
expect_jq "状态 VERIFIED（校验和已核）" '.status' 'VERIFIED'
expect_jq "source=NEXUS" '.source' 'NEXUS'

section "s4 · 绑定幂等：同坐标同 sha256 再登记 → 同一行"
api_call POST "/api/req/deploy-orders/${ORD}/artifacts" "$BODY"
expect "重复登记 200" 200
expect_jq "返回同一制品 id" '.id' "$AID"
expect_db "artifact_record 只有一行" \
    "SELECT COUNT(*) FROM artifact_record WHERE tenant_id=${TENANT_ID} AND deploy_order_id=${ORD} AND sha256='${SHA_REL}'" eq 1
api_call GET "/api/req/deploy-orders/${ORD}/artifacts"
expect "当前制品读取 200" 200
expect_jq "当前制品 id 一致" '.id // (.items[0].id)' "$AID"

section "s4 · sha256 不一致：拒绝登记（400/409，不落库）"
BAD="$(jq -cn --arg sha "$SHA_SNAP" --arg url "${STUB_URL}/repository/releases/com/example/demo-app/${VER_BAD}/demo-app-${VER_BAD}.jar" --arg ver "$VER_BAD" '{
  source:"NEXUS", repository:"releases", groupId:"com.example", artifactName:"demo-app",
  version:$ver, packaging:"jar", sha256:$sha, downloadUrl:$url}')"
api_call POST "/api/req/deploy-orders/${ORD}/artifacts" "$BAD"
if [[ "$HTTP_CODE" == "409" || "$HTTP_CODE" == "400" ]]; then
    pass "不一致被拒（HTTP ${HTTP_CODE}）"
    if grep -qE "CHECKSUM|SHA256|MISMATCH" <<<"$HTTP_BODY"; then pass "错误码可定位（$(jget "$HTTP_BODY" '.errorCode // .code')）"; else info "错误码未含 CHECKSUM/SHA256"; fi
else
    fail "不一致登记返回 ${HTTP_CODE}" "提供的 sha256 必须与制品仓 HEAD 校验和一致"
fi
expect_db "不一致不落 artifact_record" \
    "SELECT COUNT(*) FROM artifact_record WHERE tenant_id=${TENANT_ID} AND version='${VER_BAD}'" eq 0

section "s4 · 晋级：目标仓校验和不同 → 409 CHECKSUM_MISMATCH；相同 → PROMOTED 且幂等"
i_stub_checksum prod "$SHA_SNAP"
api_call POST "/api/req/artifacts/${AID}/promote" '{"targetRepo":"prod"}'
expect "目标仓 sha 不同 409" 409
expect_jq "错误码 CHECKSUM_MISMATCH" '.errorCode // .code' 'CHECKSUM_MISMATCH'
i_stub_checksum prod "$SHA_REL"
api_call POST "/api/req/artifacts/${AID}/promote" '{"targetRepo":"prod"}'
expect "sha 一致晋级 200" 200
expect_jq "晋级结果 PROMOTED" '.status // .artifact.status' 'PROMOTED'
i_stub_checksum prod "$SHA_REL"
api_call POST "/api/req/artifacts/${AID}/promote" '{"targetRepo":"prod"}'
expect "重复晋级 200（幂等）" 200
expect_db "同一源制品只产生一条晋级行" \
    "SELECT COUNT(*) FROM artifact_record WHERE tenant_id=${TENANT_ID} AND promoted_from_id=${AID}" eq 1

section "s4 · SSRF：任意 URL 400 URL_NOT_ALLOWED，且目标 0 请求"
REQ_BEFORE="$(i_stub GET /__stub/requests; jq -r '(.requests // []) | length' <<<"$HTTP_BODY")"
for bad in "http://169.254.169.254/latest/meta-data/" "file:///etc/passwd" "http://user:pass@127.0.0.1:19090/repository/releases/x.jar"; do
    PAYLOAD="$(jq -cn --arg u "$bad" --arg sha "$SHA_REL" '{source:"MANUAL", repository:"releases", groupId:"com.example",
        artifactName:"ssrf-probe", version:"1.0.0", packaging:"jar", sha256:$sha, downloadUrl:$u}')"
    api_call POST "/api/req/deploy-orders/${ORD}/artifacts" "$PAYLOAD"
    if [[ "$HTTP_CODE" == "400" ]]; then
        pass "拒绝 ${bad%%\?*}（400）"
        if grep -q "URL_NOT_ALLOWED" <<<"$HTTP_BODY"; then pass "  错误码 URL_NOT_ALLOWED"; else info "  错误码=$(jget "$HTTP_BODY" '.errorCode // .code')"; fi
    else
        fail "SSRF 地址未被拒：${bad%%\?*} → ${HTTP_CODE}" "SsrfGuard 必须在建立连接前拒绝 169.254/私网/file:/userinfo"
    fi
done
REQ_AFTER="$(i_stub GET /__stub/requests; jq -r '(.requests // []) | length' <<<"$HTTP_BODY")"
if [[ "$REQ_BEFORE" == "$REQ_AFTER" ]]; then pass "被拒地址对桩 0 请求（${REQ_BEFORE}）"; else fail "桩收到 $((REQ_AFTER - REQ_BEFORE)) 个被拒地址请求" "SSRF 校验必须在连接前"; fi

section "s4 · 手工制品（MANUAL）可登记，sha256 仍校验格式"
MAN="$(jq -cn --arg sha "$SHA_SNAP" '{source:"MANUAL", repository:"manual", groupId:"com.example",
    artifactName:"manual-jar", version:"0.1.0", packaging:"jar", sha256:$sha, downloadUrl:""}')"
api_call POST "/api/req/deploy-orders/${ORD}/artifacts" "$MAN"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    pass "MANUAL 制品登记成功（status=$(jget "$HTTP_BODY" '.status')）"
else
    info "MANUAL 登记返回 ${HTTP_CODE}（设计允许，若实现要求 downloadUrl 请记录）"
fi

section "s4 · 跨租户隔离"
http_call GET "${GATEWAY_URL}/api/req/deploy-orders/${ORD}/artifacts" "" \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: 999999"
if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "404" ]]; then
    pass "跨租户读取被拒（HTTP ${HTTP_CODE}）"
else
    fail "跨租户读取返回 ${HTTP_CODE}" "ScopeSql/租户守卫必须隔离制品"
fi

guard s4-artifact
