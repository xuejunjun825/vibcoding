#!/usr/bin/env bash
# s2-mr.sh — AC-4/AC-5 GitLab MR：两种 URL 形态解析、webhook 验签 401 不落库、
# 未知事件 200 IGNORED、重复幂等、唯一键、状态单调（MERGED 不被打回 OPENED）、
# 跨租户隔离、mrAutoAdvance 默认人工确认 / 开启后自动推进且留审计。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}"; exit 1; }
GID="$(i_config GITLAB)" || exit 1
RD="$(i_rd "s2MR-${RUN_TAG}")" || exit 1
REL="$(i_release_with "s2版本-${RUN_TAG}" "$RD")" || exit 1
info "config=${GID} requirement=${RD} release=${REL}"

section "s2 · MR URL 解析：两种形态（含嵌套 group）"
P1="demo-group/demo-project"
# iid 每次运行都换一批：mr_sync 的唯一键是 (tenant, project, iid)，
# 用固定的 1/7 会在第二次运行时正确地撞出 409 MR_ALREADY_LINKED —— 那是产品行为对，
# 但夹具就永远跑不动了。
IID1=$(( 1000 + RANDOM % 8000 ))
IID2=$(( IID1 + 1 ))
IID3=$(( IID1 + 2 ))
i_stub_mr "$P1" "$IID1" opened "2026-11-09T05:00:00.000Z"
api_call POST "/api/req/requirements/${RD}/mr-sync" \
    "$(jq -cn --arg u "${STUB_URL}/${P1}/-/merge_requests/${IID1}" '{mrUrl:$u}')"
expect "mr-sync 200" 200
expect_jq "project 解析正确" '.gitlabProject' "$P1"
expect_jq "iid 解析正确" '.mrIid' "$IID1"
STATE1="$(jget "$HTTP_BODY" '.state' | tr '[:lower:]' '[:upper:]')"
if [[ "$STATE1" == "OPENED" ]]; then pass "state 归一为 OPENED"; else fail "state=${STATE1}" "GitLab opened 应映射为 OPENED"; fi

P2="demo-group/sub/demo-project"
i_stub_mr "$P2" "$IID2" opened "2026-11-09T05:01:00.000Z"
api_call POST "/api/req/requirements/${RD}/mr-sync" \
    "$(jq -cn --arg u "${STUB_URL}/${P2}/merge_requests/${IID2}" '{mrUrl:$u}')"
expect "另一种 URL 形态（/merge_requests/${IID2}，嵌套 group）200" 200
expect_jq "嵌套 project" '.gitlabProject' "$P2"
expect_jq "iid=${IID2}" '.mrIid' "$IID2"

api_call POST "/api/req/requirements/${RD}/mr-sync" '{"mrUrl":"not-a-valid-url"}'
if [[ "$HTTP_CODE" == "400" ]]; then
    expect "无法解析 URL → 400" 400 MR_URL_INVALID
elif [[ "$HTTP_CODE" == "200" ]]; then
    expect_jq "兼容口径：200 但结构化字段为空" '.mrIid // 0' '0'
else
    fail "非法 URL 返回 ${HTTP_CODE}（期望 400 MR_URL_INVALID 或 200 空结构）" "PRD AC-4.1 与 TECHNICAL §4.4 口径冲突，二者其一"
fi

section "s2 · webhook 验签：错误 Secret Token → 401 且不落业务行"
MRS_BEFORE="$(db_count mr_sync "requirement_id=${RD}")"
i_gitlab_webhook "$GID" "$P1" "$IID1" merged "Merge Request Hook" "2026-11-09T06:00:00.000Z" "wrong-token"
expect "错误 token 401" 401
expect_jq "错误码 WEBHOOK_SIGNATURE_INVALID" '.errorCode // .code' 'WEBHOOK_SIGNATURE_INVALID'
same_count "401 不写 mr_sync" mr_sync "requirement_id=${RD}" "$MRS_BEFORE"

section "s2 · 未知事件 200 IGNORED（避免 GitLab 反复重投）"
http_call POST "${GATEWAY_URL}/api/integration/webhooks/gitlab/${GID}" \
    '{"object_kind":"push","ref":"refs/heads/main"}' \
    -H "X-Gitlab-Token: ${INTEG_SECRET}" -H "X-Gitlab-Event: Push Hook"
expect "Push Hook 200" 200
expect_jq "返回 IGNORED" '.ignored // .status // .result' 'IGNORED'

section "s2 · 正常 merge 回调：状态 MERGED，唯一键一行"
i_gitlab_webhook "$GID" "$P1" "$IID1" merged "Merge Request Hook" "2026-11-09T06:10:00.000Z"
expect "merge 回调 200" 200
expect_db_text "mr_sync 状态 MERGED" \
    "SELECT state FROM mr_sync WHERE tenant_id=${TENANT_ID} AND gitlab_project='${P1}' AND mr_iid=${IID1}" MERGED
expect_db "唯一键 uk_mr：同 project+iid 只有一行" \
    "SELECT COUNT(*) FROM mr_sync WHERE tenant_id=${TENANT_ID} AND gitlab_project='${P1}' AND mr_iid=${IID1}" eq 1

section "s2 · 需求详情聚合端点（AC-4.2：代码卡 + Sonar + 最近同步一次取回）"
api_call GET "/api/req/requirements/${RD}/integration"
expect "聚合端点 200" 200
expect_jq "code 恒为对象（未绑定也不返回 null）" '.code | type' 'object' \
    "RequirementIntegrationService 必须用 MrCard.linkOnly 兜底"
expect_jq "code 取最近一条 MR" '.code.gitlabProject' "$P1"
expect_jq "code 状态 MERGED" '.code.state' 'MERGED'
expect_jq "sonar 卡恒为对象（未配置为 SKIP 卡）" '.sonar | type' 'object'
expect_jq "sync 为最近日志数组" '.sync | type' 'array'

# D-04 门禁（P2）：同步记录必须是「该需求」的，不是租户全局最近 10 条（DESIGN §I4/§325）。
# 修复前这里拿到的是全租户最新 10 条，quiet 需求同样 10 条。
RD_SYNC_N="$(jget "$HTTP_BODY" '.sync | length')"
if [[ "${RD_SYNC_N:-0}" -gt 0 ]]; then
    pass "有 MR 的需求 sync 非空（${RD_SYNC_N} 条）"
else
    fail "有 MR 的需求 sync=0" "MR 同步日志必须落 biz_type='MR' + biz_id=requirement.id（Webhook 分支用 requirementIdOf 反查）"
fi
RD_SYNC_IDS="$(jget "$HTTP_BODY" '[.sync[].id] | join(",")')"
if [[ -n "$RD_SYNC_IDS" ]]; then
    expect_db "sync 每条都带本需求的业务锚点（无跨需求行）" \
        "SELECT COUNT(*) FROM integration_sync_log l WHERE l.id IN (${RD_SYNC_IDS}) AND COALESCE(l.biz_type,'') NOT IN ('MR','SONAR') AND COALESCE(l.correlation_id,'') NOT IN (SELECT COALESCE(b.correlation_id,'') FROM build_record b WHERE b.requirement_id=${RD})" \
        eq 0
fi

section "s2 · 未绑定 MR 的新需求：聚合端点仍返回空卡对象（区分加载失败/未绑定）"
RD_EMPTY="$(i_rd "s2MR-empty-${RUN_TAG}")" || exit 1
api_call GET "/api/req/requirements/${RD_EMPTY}/integration"
expect "未绑定需求聚合 200" 200
expect_jq "code 仍为对象" '.code | type' 'object'
expect_jq "未绑定 code.mrUrl 为空" '(.code.mrUrl == null)' 'true'
expect_jq "无集成事实的需求 sync 为空（不泄漏他人日志）" '.sync | length' '0' \
    "RequirementIntegrationService 必须按 requirement 收缩作用域；与上一段的 RD 非空即「两需求集合不同」"

section "s2 · 重复投递幂等 / 乱序不回退"
i_gitlab_webhook "$GID" "$P1" "$IID1" merged "Merge Request Hook" "2026-11-09T06:10:00.000Z"
expect "重复投递 200" 200
expect_jq "重复返回 DUPLICATE" '.ignored // .status // .result' 'DUPLICATE'
i_gitlab_webhook "$GID" "$P1" "$IID1" opened "Merge Request Hook" "2026-11-09T05:30:00.000Z"
expect "旧事件乱序 200" 200
expect_jq "乱序返回 OUT_OF_ORDER" '.ignored // .status // .result' 'OUT_OF_ORDER'
expect_db_text "last_event_at 单调：仍 MERGED" \
    "SELECT state FROM mr_sync WHERE tenant_id=${TENANT_ID} AND gitlab_project='${P1}' AND mr_iid=${IID1}" MERGED

section "s2 · 跨租户隔离（t1 token + 其他 X-Tenant-Id）"
http_call GET "${GATEWAY_URL}/api/req/requirements/${RD}/mr" "" \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: 999999"
if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "404" ]]; then
    pass "跨租户读取被拒（HTTP ${HTTP_CODE}）"
else
    fail "跨租户读取返回 ${HTTP_CODE}" "网关 TENANT_MISMATCH 403 或仓储层 404"
fi

section "s2 · mrAutoAdvance 默认 false：merge 不自动推需求，需人工 confirm-merge"
expect_db "版本默认 mr_auto_advance=0" "SELECT mr_auto_advance FROM \`release\` WHERE id=${REL}" eq 0
i_rd_status "$RD" TEST_PASSED
api_call GET "/api/req/requirements/${RD}"
REQ_ST="$(jget "$HTTP_BODY" '.status')"
if [[ "$REQ_ST" != "MERGED" ]]; then pass "默认不自动推进（需求仍 ${REQ_ST}）"; else fail "默认就自动推进了" "mrAutoAdvance 默认必须关"; fi
api_call POST "/api/req/requirements/${RD}/mr/confirm-merge" '{"note":"人工确认"}'
expect "人工确认 200" 200
expect_db_text "人工确认后 RD → MERGED" "SELECT status FROM requirement WHERE id=${RD}" MERGED

section "s2 · mrAutoAdvance=true：自动推进 RD（只允许 TEST_PASSED→MERGED）并留审计"
api_call PUT "/api/req/releases/${REL}/integration-settings" '{"mrAutoAdvance":true}'
expect "版本集成设置 200" 200
expect_jq "mrAutoAdvance 已开" '.mrAutoAdvance' 'true'
RD2="$(i_rd "s2MR-auto-${RUN_TAG}")" || exit 1
api_call POST "/api/req/releases/${REL}/requirements" "{\"requirementIds\":[${RD2}]}" >/dev/null 2>&1 || true
i_rd_status "$RD2" TEST_PASSED
i_gitlab_webhook "$GID" "$P1" "$IID3" merged "Merge Request Hook" "2026-11-09T07:00:00.000Z"
expect "auto 模式 merge 回调 200" 200
AUTO_ST="$(db_q "SELECT status FROM requirement WHERE id=${RD2}")"
if [[ "$AUTO_ST" == "MERGED" ]]; then pass "mrAutoAdvance 自动推进 RD → MERGED"; else fail "RD 状态=${AUTO_ST}" "MrAdvanceService 应只动状态机允许的 TEST_PASSED→MERGED"; fi
expect_db "自动推进留审计（integration_sync_log 有 MR 事件）" \
    "SELECT COUNT(*) FROM integration_sync_log WHERE tenant_id=${TENANT_ID} AND \`system\`='GITLAB' AND biz_type='MR' AND created_at > NOW() - INTERVAL 5 MINUTE" ge 1

guard s2-mr
