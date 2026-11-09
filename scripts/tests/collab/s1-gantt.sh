#!/usr/bin/env bash
# s1-gantt.sh — AC-1/AC-2/AC-3 甘特：三视图读取、告警由服务端算（逾期/临期/截断）、
# 改期唯一写入口（乐观锁 409 / 越父窗口 409+二次确认 / 无日期 400 / 落库可见）、改期留审计。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
c_stub_reset

section "s1 · 三视图读取"
REL_OLD="$(c_release "s1逾期-${RUN_TAG}" "2026-10-01" "2026-10-20")" || exit 1
REL_FUT="$(c_release "s1窗口-${RUN_TAG}" "2026-11-02" "2026-11-30")" || exit 1
RD="$(c_requirement "s1需求-${RUN_TAG}" "$REL_FUT")" || exit 1
info "release(旧)=${REL_OLD} release(窗口)=${REL_FUT} requirement=${RD}"

c_gantt "view=RELEASE&zoom=MONTH&domainId=$(q_domain)"
expect "GET /gantt/rows（版本/月）200" 200
expect_jq "view 回显 RELEASE" '.view' RELEASE
expect_jq "zoom 回显 MONTH" '.zoom' MONTH
expect_jq "容量是服务端给的（默认 5.0 人天 → 50 十分之一天）" '.capacityTenths' 50
if [[ "$(jget "$HTTP_BODY" "[.rows[] | select(.type==\"RELEASE\" and .id==$REL_OLD) | .warnings[] | select(.code==\"OVERDUE\")] | length")" -ge 1 ]]; then
    pass "计划完成已过的版本带 OVERDUE 告警（服务端计算）"
else
    fail "旧版本没有 OVERDUE 告警" "GanttWarnings 必须按 planEnd<today 给 RED 告警，前端不得自行推断"
fi

section "s1 · 需求视图 + 折叠/截断"
c_gantt "view=REQUIREMENT&zoom=WEEK&releaseId=${REL_FUT}&expanded=true"
expect "GET /gantt/rows（需求/周）200" 200
if [[ "$(jget "$HTTP_BODY" "[.rows[] | select(.type==\"REQUIREMENT\" and .id==$RD)] | length")" == "1" ]]; then
    pass "本版本的需求出现在需求视图"
else
    fail "需求 ${RD} 不在需求视图" "releaseId 过滤或 requirement 树组装漏行"
fi
expect_jq "需求行带父级（树形由 parentId/level 表达）" \
    "[.rows[] | select(.id==$RD)][0].parentId != null" true
c_gantt "view=RELEASE&limit=1&domainId=$(q_domain)"
expect "limit=1 仍 200" 200
expect_jq "行数按 limit 截断" '.truncated' true
if [[ "$(jget "$HTTP_BODY" '[.boardWarnings[] | select(.code=="TRUNCATED")] | length')" -ge 1 ]]; then
    pass "截断时 boardWarnings 带 TRUNCATED（不静默丢行）"
else
    fail "截断没有 TRUNCATED 告警" "超过 300 行必须显式告知被截断，否则用户以为看全了"
fi

section "s1 · 参数负向（400，且不改库）"
c_gantt "view=NOPE"
deny "未知视图 → 400 GANTT_INVALID_VIEW" 400 GANTT_INVALID_VIEW
c_gantt "view=RELEASE&zoom=NOPE"
deny "未知缩放 → 400 GANTT_INVALID_ZOOM" 400 GANTT_INVALID_ZOOM
c_gantt "view=RELEASE&from=2026-12-01&to=2026-11-01"
deny "起始晚于结束 → 400 GANTT_INVALID_RANGE" 400 GANTT_INVALID_RANGE

section "s1 · 发布单实际期（D-01 回归门禁）"
# 实际期只有两个写入者能开：需求状态流转与发布单 publish。后者曾整条漏接
# ActualPeriodRecorder（D-01）——甘特发布视图的浅色实际条于是永远缺失。
# 这里走真实路径：建单 → 提交审批 → 审批人通过 → 断言 actual_start_date 非空。
api_call POST "${C_REQ}/releases" "$(jq -cn --arg n "s1实际期-${RUN_TAG}" --argjson d "$(q_domain)" \
    '{name:$n,domainId:$d,startDate:"2026-11-01",endDate:"2026-12-15",plannedReleaseDate:"2026-12-15",gateMode:"LOOSE"}')"
REL_ACT="$(jget "$HTTP_BODY" '.id')"
[[ -n "$REL_ACT" && "$REL_ACT" != "null" ]] || { fail "创建发布单失败（HTTP ${HTTP_CODE}）" "${HTTP_BODY:0:300}"; guard s1-gantt; }
api_call POST "${C_REQ}/releases/${REL_ACT}/approval"
INST="$(jget "$HTTP_BODY" '.instanceId')"
TASK_ID=""
if [[ -n "$INST" && "$INST" != "null" ]]; then
    api_call GET "${C_REQ}/approvals/instances/${INST}"
    TASK_ID="$(jget "$HTTP_BODY" '(.tasks[]? | select(.status=="PENDING") | .id) // empty' | head -1)"
    APPROVER_ID="$(jget "$HTTP_BODY" '(.tasks[]? | select(.status=="PENDING") | .approverId) // empty' | head -1)"
fi
if [[ -z "$TASK_ID" || "$TASK_ID" == "null" ]]; then
    fail "发布单没有生成待办审批任务（instance=${INST:-none}）" "审批流夹具缺失：先 make platform-seed"
else
    APPROVER="$(db_q "SELECT username FROM sys_user WHERE id=${APPROVER_ID}")"
    ACT_TOKEN="$(curl -sS -m 8 -X POST "${GATEWAY_URL}${C_REQ}/auth/login" -H 'Content-Type: application/json' \
        -d "$(jq -cn --arg u "$APPROVER" '{tenantCode:"t1",username:$u,password:"Passw0rd!"}')" | jq -r '.token // empty')"
    if [[ -z "$ACT_TOKEN" ]]; then
        fail "审批人 ${APPROVER} 登录失败" "审批驱动不了就没法验证 publish 路径：检查种子账号密码"
    else
        http_call POST "${GATEWAY_URL}${C_REQ}/approvals/tasks/${TASK_ID}/actions" \
            '{"action":"APPROVE","comment":"s1 实际期回归"}' \
            -H "Authorization: Bearer ${ACT_TOKEN}" -H 'X-Tenant-Id: 1'
        expect "审批人通过 → 发布 200" 200
        q_admin_login >/dev/null 2>&1
        expect_db "发布后 actual_start_date 已写入（RELEASE 接入 ActualPeriodRecorder）" \
            "SELECT COUNT(*) FROM \`release\` WHERE id=${REL_ACT} AND tenant_id=${TENANT_ID}
                AND status='PUBLISHED' AND actual_start_date IS NOT NULL" eq 1 \
            "ReleaseService.publish 必须调用 ActualPeriodRecorder.record(RELEASE,…)：否则甘特发布视图永远没有实际条"
    fi
fi

section "s1 · 改期唯一写入口"
VER="$(c_gantt_version REQUIREMENT "$RD" "view=REQUIREMENT&releaseId=${REL_FUT}")"
[[ -n "$VER" ]] || { fail "取不到需求行版本" "行上必须带 version（updated_at 乐观锁）"; guard s1-gantt; }
c_gantt_patch REQUIREMENT "$RD" '{"planStart":"2026-11-05"}'
deny "只给开始不给结束 → 400 GANTT_INVALID_RANGE" 400 GANTT_INVALID_RANGE
c_gantt_patch REQUIREMENT "$RD" "$(jq -cn --arg v "$VER" '{planStart:"2026-12-06",planEnd:"2026-12-01",version:$v}')"
deny "开始晚于结束 → 400 GANTT_INVALID_RANGE" 400 GANTT_INVALID_RANGE
c_gantt_patch REQUIREMENT "$RD" "$(jq -cn --arg v "$VER" '{planStart:"2026-11-05",planEnd:"2026-11-10",version:"1"}')"
deny "旧版本 → 409 GANTT_VERSION_CONFLICT（不覆盖别人刚改的）" 409 GANTT_VERSION_CONFLICT
c_gantt_patch REQUIREMENT "$RD" "$(jq -cn --arg v "$VER" '{planStart:"2026-12-01",planEnd:"2026-12-05",version:$v}')"
deny "超出父版本窗口且无确认码 → 409 GANTT_OUT_OF_PARENT_WINDOW" 409 GANTT_OUT_OF_PARENT_WINDOW
TOKEN="$(jget "$HTTP_BODY" '.detail.confirmToken // empty')"
if [[ -n "$TOKEN" ]]; then
    pass "409 里给出一次性确认码"
else
    fail "越窗 409 没给 confirmToken" "二次确认是 AC-2 的硬要求：前端不能自己弹个框就放过"
fi
c_gantt_patch REQUIREMENT "$RD" "$(jq -cn --arg v "$VER" --arg t "${TOKEN:-}" \
    '{planStart:"2026-12-01",planEnd:"2026-12-05",version:$v,confirmToken:$t}')"
expect "带确认码重试 200" 200
expect_jq "返回的 planStart 是 2026-12-01" '.planStart' 2026-12-01
expect_db "改期已落库" "SELECT COUNT(*) FROM requirement WHERE id=${RD} AND tenant_id=${TENANT_ID}
    AND plan_start_date='2026-12-01' AND plan_end_date='2026-12-05'" eq 1 \
    "唯一写入口必须真的写库：检查 GanttQuery.updatePlan"
expect_num "改期留 GANTT_RESCHEDULE 审计 1 条" \
    "$(c_audit_count REQUIREMENT "$RD" GANTT_RESCHEDULE)" eq 1 \
    "改期不审计等于无法追责：确认码消费后必须落一条"

section "s1 · 一次性确认码不可重放"
c_gantt_patch REQUIREMENT "$RD" "$(jq -cn --arg v "$(c_gantt_version REQUIREMENT "$RD" "view=REQUIREMENT&releaseId=${REL_FUT}")" \
    --arg t "${TOKEN:-}" '{planStart:"2026-12-08",planEnd:"2026-12-10",version:$v,confirmToken:$t}')"
deny "同一确认码第二次使用被拒（5 分钟一次性）" 409 GANTT_CONFIRM_INVALID

section "s1 · 只读不外呼"
BEFORE="$(c_stub_count all)"
c_gantt "view=REQUIREMENT&zoom=MONTH&domainId=$(q_domain)"
expect "再读一次看板 200" 200
c_zero_outbound "甘特读取零外呼" all "$BEFORE"

section "s1 · 实际期夹具自清"
if [[ -n "${REL_ACT:-}" && "$REL_ACT" != "null" ]]; then
    mysql -uroot devops_platform -e "DELETE FROM wf_task WHERE instance_id=${INST:-0};
        DELETE FROM wf_history WHERE instance_id=${INST:-0};
        DELETE FROM wf_cc WHERE instance_id=${INST:-0};
        DELETE FROM wf_instance WHERE id=${INST:-0};
        DELETE FROM release_requirement WHERE release_id=${REL_ACT};
        DELETE FROM audit_log WHERE tenant_id=${TENANT_ID} AND biz_type='RELEASE' AND biz_id=${REL_ACT};
        DELETE FROM \`release\` WHERE id=${REL_ACT};" 2>/dev/null \
        && info "已清理实际期夹具 release=${REL_ACT} instance=${INST:-none}"
fi

guard s1-gantt
