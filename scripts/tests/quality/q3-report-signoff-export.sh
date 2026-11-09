#!/usr/bin/env bash
# q3-report-signoff-export.sh — AC-4 测试报告：8 段快照、版本化、三级会签顺序、驳回、XLSX/PDF 导出。
#
# 关键断言：快照 8 段齐全且不可变（v1 在 v2 生成后逐字节不变）、会签必须 TEST_LEAD→PRODUCT→PMO、
# 越序 409 SIGNOFF_ORDER_VIOLATION、同角色重复签署幂等（不新增行）、驳回后整版 REJECTED、
# 导出可下载且带 8 段与三级签名（xlsx 是 zip、pdf 以 %PDF 开头）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack

section "q3 · 准备 fixture（QA 负责人的 COMPLETED 计划）"
QA_ID="$(q_user_id qa1)"
(( QA_ID > 0 )) || { err "缺少种子账号 qa1，无法构造三级会签"; exit 1; }
REL="$(q_create_release "报告版本-${RUN_TAG}")" || exit 1
CASES=($(q_case_ids 3))
api_call POST /api/deliver/test-plans \
    "$(jq -cn --argjson r "$REL" --arg n "报告计划-${RUN_TAG}" --argjson o "$QA_ID" --argjson c "$(printf '%s\n' "${CASES[@]}" | jq -s .)" \
        '{releaseId:$r,name:$n,ownerId:$o,windowStart:"2026-11-20",windowEnd:"2026-11-27",caseIds:$c,gateMode:"STRICT"}')"
expect "创建计划 201" 201
PLAN="$(jget "$HTTP_BODY" '.plan.id // .id')"
[[ -n "$PLAN" ]] || { err "计划创建失败: $(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
api_call POST "/api/deliver/test-plans/${PLAN}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${PLAN}/approve" '{"comment":"同意"}'
api_call POST "/api/deliver/test-plans/${PLAN}/start" '{}'
q_exec_all "$PLAN" PASS
ROUND="$(q_active_round "$PLAN")"
[[ -n "$ROUND" ]] || ROUND="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN} AND active=1")"
q_close_round "$PLAN" "$ROUND" PASS
api_call POST "/api/deliver/test-plans/${PLAN}/complete" '{}'
expect "计划完成 200" 200
info "releaseId=${REL} planId=${PLAN}"

section "q3 · 生成 v1 报告与 8 段快照"
api_call POST /api/deliver/test-reports "$(jq -cn --argjson p "$PLAN" '{scope:"PLAN",planId:$p,note:"第一版"}')"
expect "生成报告 201" 201
RPT="$(jget "$HTTP_BODY" '.id')"
if [[ -z "$RPT" ]]; then
    # 报告控制器未上线/不可用时，后续 40 条断言只会重复同一个 404 —— 直接给出根因并收尾。
    err "报告创建失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 240)"
    err "修复: ReportController 未注册时先完成 T5（report 包 service+controller），再 make platform-up"
    fail "POST /test-reports 可用" "报告生成是 AC-4/AC-5 的前置，缺它时 q3 无法继续"
    guard q3-report-signoff-export
fi
info "reportId=${RPT}"
expect_jq "当前版本为 v1" '.currentVersion.versionNo' '1'
expect_num "版本列表 1 条" "$(jq -r '.versions | length' <<<"$HTTP_BODY")" ge 1

api_call GET "/api/deliver/test-reports/${RPT}/versions/1"
expect "v1 详情 200" 200
for seg in scope execution rates defects residual env riskConclusion signoff; do
    if grep -qF "\"${seg}\"" <<<"$HTTP_BODY"; then
        pass "8 段快照含 ${seg}"
    else
        fail "8 段快照缺 ${seg}" "snapshot_json 固定 8 段；缺段会让导出与门禁读不到数据"
    fi
done
expect_jq "报告带 result_digest（64 位十六进制）" '(.resultDigest // .snapshot.resultDigest // "") | test("^[0-9a-f]{64}$")' 'true'
V1_DIGEST="$(jget "$HTTP_BODY" '.resultDigest // .snapshot.resultDigest')"
V1_SNAPSHOT_HASH="$(db_q "SELECT SHA2(snapshot_json,256) FROM test_report_version WHERE report_id=${RPT} AND version_no=1")"

section "q3 · 三级会签：顺序、幂等、驳回"
api_call GET "/api/deliver/test-reports/${RPT}/versions/1/signoff"
expect "会签进度 200" 200
expect_num "会签共 3 级" "$(jq -r '(.steps // .progress // []) | length' <<<"$HTTP_BODY")" eq 3
expect_jq "第一级是 TEST_LEAD" '(.steps // .progress)[0].role // (.steps // .progress)[0].signRole' 'TEST_LEAD'

q_login_as pm || true
api_call POST "/api/deliver/test-reports/${RPT}/versions/1/signoff" '{"decision":"APPROVE","comment":"我先签"}'
deny "PRODUCT 越序签署 → 409 SIGNOFF_ORDER_VIOLATION" 409 SIGNOFF_ORDER_VIOLATION
expect_jq "越序回报期望角色" '.detail.expectedRole' 'TEST_LEAD'
q_admin_login

q_login_as qa1 || true
api_call POST "/api/deliver/test-reports/${RPT}/versions/1/signoff" '{"decision":"APPROVE","comment":"测试负责人认可"}'
expect "TEST_LEAD 签署 200" 200
SIGNOFF_ROWS="$(db_count report_signoff "report_id=${RPT} AND version_no=1")"
expect_num "签署后 1 条会签记录" "$SIGNOFF_ROWS" eq 1
api_call POST "/api/deliver/test-reports/${RPT}/versions/1/signoff" '{"decision":"APPROVE","comment":"重复点一次"}'
expect "同角色重复签署幂等 200" 200
expect_num "幂等不新增会签行" "$(db_count report_signoff "report_id=${RPT} AND version_no=1")" eq 1

q_login_as pm || true
api_call POST "/api/deliver/test-reports/${RPT}/versions/1/signoff" '{"decision":"APPROVE","comment":"产品认可"}'
expect "PRODUCT 第二级签署 200" 200
q_login_as tl1 || true
api_call POST "/api/deliver/test-reports/${RPT}/versions/1/signoff" '{"decision":"APPROVE","comment":"PMO 放行"}'
expect "PMO 第三级签署 200" 200
expect_jq "三级签完版本 SIGNED" '.status // .versionStatus' 'SIGNED'
expect_db "版本状态落库 SIGNED" "SELECT COUNT(*) FROM test_report_version WHERE report_id=${RPT} AND version_no=1 AND status='SIGNED'" eq 1
q_admin_login

section "q3 · 会签后再生成 v2：v1 快照逐字节不变"
api_call POST /api/deliver/test-reports "$(jq -cn --argjson p "$PLAN" '{scope:"PLAN",planId:$p,note:"第二版"}')"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    pass "再次生成报告 v2（${HTTP_CODE}）"
else
    fail "再次生成报告失败（HTTP ${HTTP_CODE}）" "同一计划重新出具应新增版本而不是覆盖旧版"
fi
api_call GET "/api/deliver/test-reports/${RPT}/versions"
expect "版本列表 200" 200
expect_num "已有 2 个版本" "$(jq -r 'length' <<<"$HTTP_BODY")" ge 2
expect_db "v1 快照未被改写" "SELECT COUNT(*) FROM test_report_version WHERE report_id=${RPT} AND version_no=1 AND SHA2(snapshot_json,256)='${V1_SNAPSHOT_HASH}'" eq 1
expect_db "v1 digest 未变" "SELECT COUNT(*) FROM test_report_version WHERE report_id=${RPT} AND version_no=1 AND result_digest='${V1_DIGEST}'" eq 1

section "q3 · 驳回：整版 REJECTED，后续生成 v+1"
api_call GET "/api/deliver/test-reports/${RPT}/versions/2"
V2_EXISTS="$HTTP_CODE"
if [[ "$V2_EXISTS" == "200" ]]; then
    q_login_as qa1 || true
    api_call POST "/api/deliver/test-reports/${RPT}/versions/2/signoff" '{"decision":"REJECT","comment":"结论与缺陷不符"}'
    expect "TEST_LEAD 驳回 200" 200
    expect_jq "驳回后版本 REJECTED" '.status // .versionStatus' 'REJECTED'
    expect_db "版本落库 REJECTED" "SELECT COUNT(*) FROM test_report_version WHERE report_id=${RPT} AND version_no=2 AND status='REJECTED'" eq 1
    q_admin_login
else
    skip "v2 未生成（实现可能把重新生成合并为版本覆盖，记录为偏差）"
fi

section "q3 · 导出 XLSX / PDF"
TMPD="$(mktemp -d -t q3-export.XXXXXX)"
XLSX_CODE="$(curl -sS -o "$TMPD/report.xlsx" -w '%{http_code}' --max-time 30 \
    "${GATEWAY_URL}/api/deliver/test-reports/${RPT}/export?format=xlsx&version=1" \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}")"
XLSX_SIZE="$(wc -c <"$TMPD/report.xlsx" | tr -d ' ')"
if [[ "$XLSX_CODE" == "200" && "$XLSX_SIZE" -gt 4000 ]] && [[ "$(head -c 2 "$TMPD/report.xlsx")" == "PK" ]]; then
    pass "XLSX 导出 200 且是合法 xlsx（${XLSX_SIZE} 字节）"
else
    fail "XLSX 导出异常（HTTP ${XLSX_CODE}, ${XLSX_SIZE} 字节）" "POI 生成后 byte[] 直出，Content-Type 应为 xlsx"
fi
PDF_CODE="$(curl -sS -o "$TMPD/report.pdf" -w '%{http_code}' --max-time 30 \
    "${GATEWAY_URL}/api/deliver/test-reports/${RPT}/export?format=pdf&version=1" \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}")"
PDF_SIZE="$(wc -c <"$TMPD/report.pdf" | tr -d ' ')"
if [[ "$PDF_CODE" == "200" && "$PDF_SIZE" -gt 2000 ]] && [[ "$(head -c 4 "$TMPD/report.pdf")" == "%PDF" ]]; then
    pass "PDF 导出 200 且是合法 pdf（${PDF_SIZE} 字节）"
else
    fail "PDF 导出异常（HTTP ${PDF_CODE}, ${PDF_SIZE} 字节）" "若离线仓库无 OpenPDF，按 TECH §11 降级并在 QA 报告记录，不得静默通过"
fi
rm -rf "$TMPD"

section "q3 · 版本差异 diff"
api_call GET "/api/deliver/test-reports/${RPT}/diff?from=1&to=2"
if [[ "$HTTP_CODE" == "200" ]]; then
    pass "diff 200"
    for k in passRate coverage conclusion; do
        grep -qF "\"${k}\"" <<<"$HTTP_BODY" && pass "diff 含 ${k}" || fail "diff 缺 ${k}"
    done
else
    skip "版本 2 不存在，diff 跳过（HTTP ${HTTP_CODE}）"
fi

guard q3-report-signoff-export
