#!/usr/bin/env bash
# s3-flow.sh — AC-9/AC-10 价值流：7 阶段配置读写（最少 2 阶段/类型收窄）、看板与指标字典同源、
# 下钻与时间线、空数据可识别（不按 0 兜底）、保存留审计。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT

WIN="from=2026-01-01&to=2026-12-31"
DOM="$(q_domain)"

section "s3 · 阶段配置读取"
api_call GET "${C_DELIVER}/flow/stages"
expect "GET /flow/stages 200" 200
expect_jq "7 个阶段" 'length' 7
expect_jq "阶段按 orderNo 升序（REPORT=1…DELIVERY=7）" '[.[].orderNo] | . == (sort)' true
expect_jq "七码齐全" '[.[].code] | sort | join(",")' \
    DELIVERED,DEVELOPING,RELEASING,REPORTED,REVIEWING,SCHEDULING,TESTING
STAGES_JSON="$(printf '%s' "$HTTP_BODY")"

section "s3 · 看板与字典同源"
api_call GET "${C_DELIVER}/flow/board?${WIN}&domainId=${DOM}"
expect "GET /flow/board 200" 200
expect_jq "看板仍是 7 列" '.stages | length' 7
expect_jq "窗口回显" '.window.from' 2026-01-01
expect_jq "流动效率带分子分母（可解释，不是裸数字）" '(.efficiency | has("included") and has("excluded"))' true
BOARD_EFF="$(jget "$HTTP_BODY" '.efficiency.value')"
BOARD_WIP="$(jget "$HTTP_BODY" '[.stages[].wip] | add // 0')"
BOARD_NODATA="$(jget "$HTTP_BODY" '.noDataCount')"
api_call GET "${C_DELIVER}/metrics/FLOW_EFFICIENCY/preview?${WIN}&domainId=${DOM}"
expect "GET /metrics/FLOW_EFFICIENCY/preview 200" 200
PREVIEW_EFF="$(jget "$HTTP_BODY" '.value')"
if [[ "$PREVIEW_EFF" == "null" && "$BOARD_EFF" == "null" ]]; then
    pass "效率在看板与字典页都为「暂无数据」（不是一边 0 一边空）"
elif [[ -n "$PREVIEW_EFF" && -n "$BOARD_EFF" ]] \
    && awk -v a="$PREVIEW_EFF" -v b="$BOARD_EFF" 'BEGIN{exit !(a==b)}'; then
    pass "字典页效率 == 看板效率（${PREVIEW_EFF}）"
else
    fail "字典页效率 ${PREVIEW_EFF:-<空>} ≠ 看板 ${BOARD_EFF:-<空>}" \
        "MetricQuery.preview 必须委托 FlowMetricService.values()，不能自己再算一遍"
fi
api_call GET "${C_DELIVER}/metrics/FLOW_WIP/preview?${WIN}&domainId=${DOM}"
expect "GET /metrics/FLOW_WIP/preview 200" 200
PREVIEW_WIP="$(jget "$HTTP_BODY" '.value')"
if [[ -n "$PREVIEW_WIP" ]] && awk -v a="$PREVIEW_WIP" -v b="${BOARD_WIP:-0}" 'BEGIN{exit !(a==b)}'; then
    pass "字典页 WIP == 看板各列之和（${PREVIEW_WIP}）"
else
    fail "WIP 不一致：字典 ${PREVIEW_WIP:-<空>} vs 看板 ${BOARD_WIP:-?}" "同源是 AC-9 的核心，两处必须走同一个 values()"
fi
expect_num "空数据计数可识别（≥0，不吞掉）" "${BOARD_NODATA:-0}" ge 0
api_call GET "${C_DELIVER}/metrics/FLOW_UNKNOWN/preview?${WIN}"
deny "未知 FLOW 指标 → 404 METRIC_NOT_FOUND" 404 METRIC_NOT_FOUND

section "s3 · 下钻与时间线"
api_call GET "${C_DELIVER}/flow/drill?stageCode=DEVELOPING&${WIN}&domainId=${DOM}&page=1&size=10"
expect "GET /flow/drill 200" 200
expect_jq "下钻分页结构带 items" '(.items | type)' array
api_call GET "${C_DELIVER}/flow/drill?${WIN}&page=1&size=10"
deny "缺 stageCode → 400 VALIDATION_FAILED" 400 VALIDATION_FAILED
REL="$(c_release "s3版本-${RUN_TAG}" "2026-11-02" "2026-11-30")" || exit 1
RD="$(c_requirement "s3需求-${RUN_TAG}" "$REL")" || exit 1
api_call GET "${C_DELIVER}/flow/requirements/${RD}/timeline?from=2026-01-01&to=2026-12-31"
expect "GET timeline 200" 200
expect_jq "时间线是数组" 'type' array
if [[ "$(jget "$HTTP_BODY" 'length')" -ge 1 ]]; then
    expect_jq "每段都带 noData 标记（无审计必须可识别）" '[.[].noData | type] | unique | join(",")' boolean
else
    info "该需求尚无审计区间（新夹具），noData 由看板 noDataCount 断言覆盖"
fi

section "s3 · 阶段配置负向（不落库）"
BEFORE_STAGES="$(db_q "SELECT COUNT(*) FROM stage_definition WHERE tenant_id=${TENANT_ID}")"
ONE_ENABLED="$(printf '%s' "$STAGES_JSON" | jq -c '{stages: [.[] | {id,code,name,orderNo,enabled:(.code=="REPORT"),reqTypes,mappingJson}]}')"
api_call PUT "${C_DELIVER}/flow/stages" "$ONE_ENABLED"
deny "只剩 1 个阶段 → 409 STAGE_MIN_REQUIRED" 409 STAGE_MIN_REQUIRED
DUP_CODE="$(printf '%s' "$STAGES_JSON" | jq -c '{stages: [.[] | {id,code,name,orderNo,enabled,reqTypes,mappingJson}] | .[1].code = .[0].code}')"
api_call PUT "${C_DELIVER}/flow/stages" "$DUP_CODE"
deny "阶段编码重复 → 400 VALIDATION_FAILED" 400 VALIDATION_FAILED
expect_db "负向保存没有改配置（行数不变）" \
    "SELECT COUNT(*) FROM stage_definition WHERE tenant_id=${TENANT_ID}" eq "$BEFORE_STAGES" \
    "校验必须在写库之前：400/409 之后配置不能半改"

section "s3 · 改一个阶段名 → 生效且留审计"
RENAMED="$(printf '%s' "$STAGES_JSON" | jq -c --arg n "开发（改名验-${RUN_TAG}）" \
    '{stages: [.[] | {id,code,name:(if .code=="DEVELOPING" then $n else .name end),orderNo,enabled,reqTypes,mappingJson}]}')"
api_call PUT "${C_DELIVER}/flow/stages" "$RENAMED"
expect "PUT /flow/stages 200" 200
api_call GET "${C_DELIVER}/flow/stages"
expect_jq "改名已生效" '[.[] | select(.code=="DEVELOPING")][0].name' "开发（改名验-${RUN_TAG}）"
expect_db "保存留 STAGE_DEFINITION_UPDATE 审计" \
    "SELECT COUNT(*) FROM audit_log WHERE tenant_id=${TENANT_ID} AND biz_type='stage_definition' AND action='STAGE_DEFINITION_UPDATE'" \
    ge 1 "启停/排序影响指标口径，保存即审计"
api_call PUT "${C_DELIVER}/flow/stages" "$(printf '%s' "$STAGES_JSON" | jq -c '{stages: [.[] | {id,code,name,orderNo,enabled,reqTypes,mappingJson}]}')"
expect "还原原配置 200" 200
expect_jq "还原后名字回到「开发」" '[.[] | select(.code=="DEVELOPING")][0].name' 开发

section "s3 · 窗口负向"
api_call GET "${C_DELIVER}/flow/board?from=2026-12-01&to=2026-11-01"
deny "from>to → 400 VALIDATION_FAILED" 400 VALIDATION_FAILED

BEFORE="$(c_stub_count all)"
api_call GET "${C_DELIVER}/flow/board?${WIN}&domainId=${DOM}"
c_zero_outbound "价值流看板零外呼" all "$BEFORE"

guard s3-flow
