#!/usr/bin/env bash
# pdr-flow-verify.sh — r46 PDR 评审 / 厂商工时评估 / 评审附件 / 意见分类统计 / 意见转任务 门禁。
#
# 66 条断言，四组（编号前缀 = 验收组，执行顺序 = 打印顺序）：
#   A1–A5  项目组评审：成员发起评审、逐人提交意见、非评审人 403、全员通过才放行、
#          未通过时 assign-vendor/estimate/schedule 均 409 PDR_REVIEW_REQUIRED、
#          评审人上传 2MB 附件 201 且下载 sha256 一致；
#   B1–B9  厂商与附件：未指派厂商 409 VENDOR_NOT_ASSIGNED、产品经理 403 / 别家厂商 403 OUT_OF_SCOPE、
#          本厂商 201、换厂商后原评估 STALE + 排期 409、RD IN_DEV 后 409 VENDOR_LOCKED、
#          换厂商审计 + 通知、附件越权 404 / 超限 413 / 类型 415、评审结束上传 409 而下载仍 200；
#   C1–C4  分类统计：缺/非法 category 400 INVALID_CATEGORY（detail.allowed 9 类）、
#          stats 与明细逐分类相等且占比合计 100%、/reports 交叉表与 stats 逐项相等、
#          厂商账号 stats 只统计本厂商可见口径；
#   D1–D4  意见转任务：同一人 3 条意见落 3 行且结论不放大、to-task 201 + 任务可见 + task_id 回写 +
#          审计、重复转 409 OPINION_ALREADY_TASKED 且任务表不新增、非评审人 403、
#          评审关闭后新增意见 409 但 to-task 仍 201。
#
# 用法：
#   bash scripts/pdr-flow-verify.sh              # 全量（需 make platform-up && make platform-seed）
#   bash scripts/pdr-flow-verify.sh --dry-run    # 不联网，只打印 66 条断言标题（清单自检）
#   NO_NET=1 bash scripts/pdr-flow-verify.sh     # 同上
#
# 约定（与 platform-verify / change-verify / wbs-backfill-verify 一致）：
#   - 脚本自声明清单 PDR_MANIFEST；每条断言必须登记且只执行一次，结尾打印
#     `PDR_FLOW_TOTAL=<清单条数> PASS=<n> FAIL=<n>`，FAIL>0 或清单有漏执行 → 退出码 1；
#   - 前置缺失（栈没起、拿不到评审单 id）直接 pdr_abort 并说明「哪一步没就绪」，不 skip；
#   - 每轮自造夹具：复用**种子域**（ORD 主夹具 / SUP 统计隔离，不再新建 biz_domain——
#     新建域没有 sys_user_domain 授权，pm 提报会 403 OUT_OF_SCOPE）+ 独立 project + BUS→POOLED→PDR；
#     结尾 teardown 删掉本轮造的需求/评审/任务/附件并打印残留，不依赖上一次运行残留，
#     也不调用 demo-reset 抹掉别人的演示数据；
#   - 不用 sleep 掩盖未就绪：pdr_wait_ready 每秒探测、最多 30s，超时打印步骤名与最后一次 HTTP；
#   - 失败一律打印「实际」（HTTP 码 + 业务码 + 响应片段 ≤300 字）与「修复」。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
# shellcheck source=lib/gate-fixture.sh
source "${ROOT}/scripts/lib/gate-fixture.sh"
# shellcheck source=tests/pdr/_lib.sh
source "${ROOT}/scripts/tests/pdr/_lib.sh"
set +e

MODE="full"
case "${1:-}" in
    --dry-run | --offline) MODE="dry" ;;
    -h | --help)
        sed -n '2,/^set -uo pipefail$/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    "") ;;
    *)
        printf '未知参数: %s（可用：--dry-run / --offline / --help）\n' "$1" >&2
        exit 64
        ;;
esac
if [[ "${NO_NET:-0}" == "1" ]]; then MODE="dry"; fi

# --------------------------------------------------------------------------- 断言清单
# 格式：编号|标题。编号唯一；执行顺序与清单顺序一致；少跑/多跑/重复都会在结尾被清点。
PDR_MANIFEST=(
    "S1|前置：取种子域 ORD（不再新建域，新域无授权）→ 2xx 且 domainId 非空"
    "S2|前置：建项目组（org:manage）→ 2xx 且 projectId 非空"
    "S3|前置：项目成员 5 人（PO/PM/DEV/QA/SME）→ 5/5 成功且列表 5 条"
    "S4|前置：BUS 创建（pm）→ 201"
    "S5|前置：BUS 真实入池 → GET status=POOLED"
    "S6|前置：PDR 夹具 A/B/D/E/F 创建 → 5 个 id 均非空"
    "S7|前置：PDR 绑定 project_id → GET view.projectId = 本项目"
    "S8|前置：PDR 评审接口就绪（轮询 ≤30s）→ /pdr-reviews/stats 非 404"
    "A1-1|项目组成员发起评审（4 位评审人）→ 2xx 且 reviewId 非空"
    "A1-2|4 位项目组成员各提交 1 条意见 → 4/4 为 2xx"
    "A1-3|评审单意见 4 条且评审人去重 = 4"
    "A3-1|3 PASS + 1 NEED_CHANGE → 评审 REJECTED（不全员通过不放行）"
    "A3-2|REJECTED 后 PDR 内容仍可编辑 → PUT 200"
    "A3-3|未通过评审时 assign-vendor → 409 PDR_REVIEW_REQUIRED"
    "A3-4|重新发起评审 → 新 reviewId 且与旧单不同"
    "A2-1|非评审人（vendor2）提交意见 → 403"
    "A5-1|评审人上传 2MB PDF（bizType=PDR_REVIEW）→ 201"
    "A5-2|附件列表含刚上传的 id → 200"
    "A5-3|评审人下载附件 → 200 且 sha256 与本地一致"
    "B6-1|非项目成员（vendor2）下载评审附件 → 404"
    "A3-5|3/4 通过时评审仍 IN_REVIEW（未全员通过不放行）"
    "A3-6|第 4 位通过 → 评审 PASSED"
    "B8-1|评审结束（PASSED）后上传附件 → 409 REVIEW_CLOSED"
    "B8-2|评审结束后下载原附件仍 → 200"
    "B1-1|已过评审但未指派厂商提交评估 → 409 VENDOR_NOT_ASSIGNED"
    "B1-2|错误体 detail.required = assign_vendor"
    "B9-1|换厂商缺 reason → 400/422 且业务码可读（REASON_REQUIRED）"
    "B2-1|assign-vendor 指派 VEN-A → 200 且 assigneeVendorId=VEN-A"
    "B2-2|产品经理（pm）提交评估 → 403"
    "B2-3|别家厂商账号（vendor2/VEN-B）提交评估 → 403 OUT_OF_SCOPE"
    "B2-4|承接厂商账号（vpm1/VEN-A）提交评估 → 201 且 estimate.vendorId=VEN-A"
    "B2-5|评估审批通过 → estimate.status=APPROVED"
    "B3-1|换厂商 VEN-A→VEN-B → 200 且 assigneeVendorId=VEN-B"
    "B3-2|原评估失效 → estimate.status=STALE 且 reason=VENDOR_CHANGED"
    "B3-3|换厂商后排期 → 409 ESTIMATE_NOT_APPROVED"
    "B3-4|新厂商重新评估 → 201"
    "B3-5|新评估审批通过 → APPROVED"
    "B3-6|重新排期 → 200 且 PDR=SCHEDULED"
    "B5-1|换厂商写审计 → audit_log 存在 VENDOR_CHANGED（本需求、本轮）"
    "B5-2|换厂商通知新旧厂商 → notification 本轮新增 ≥1"
    "B7-1|25MB 附件 → 413 FILE_TOO_LARGE（评审进行中）"
    "B7-2|.exe 附件 → 415 UNSUPPORTED_MEDIA_TYPE"
    "B4-1|拆解 RD 并置 IN_DEV（夹具自检）→ requirement.status=IN_DEV"
    "B4-2|RD IN_DEV 后换厂商 → 409 VENDOR_LOCKED"
    "A1-4|空评审人列表发起评审 → 2xx 且 fallback=true（回退 PM+域负责人）"
    "C1-1|意见缺 category → 400 INVALID_CATEGORY"
    "C1-2|category=FOO → 400 INVALID_CATEGORY 且 detail.allowed 为 9 类"
    "C2-1|6 条意见按 3 RANGE / 2 TECH / 1 ACCEPTANCE 落库 → 6/6 2xx"
    "C2-2|stats(category) 与明细逐分类相等"
    "C2-3|stats 占比合计 = 100%"
    "C3-1|数据集白名单含 pdr_review_opinion → 200"
    "C3-2|/reports 交叉表与 stats 逐分类相等"
    "C4-1|厂商账号 stats 只统计 VEN-A 可见口径 → 6 条"
    "C4-2|厂商 stats 严格小于全量（隔离收窄）→ 6 < 7"
    "D1-1|同一评审人 3 条意见落 3 行且 seq=1/2/3"
    "D1-2|3 条 PASS 不放大结论：另一评审人 1 条 NEED_CHANGE → REJECTED"
    "D1-3|意见列表按 reviewerId 过滤 → 3 条（分页/过滤生效）"
    "D2-1|意见转任务 → 201 且 taskId/taskCode 非空"
    "D2-2|意见回写 status=TASKED 且 task_id=taskId"
    "D2-3|任务在需求任务列表可见"
    "D2-4|审计 PDR_OPINION_TO_TASK 本轮 +1"
    "D3-1|重复转任务 → 409 OPINION_ALREADY_TASKED 且 detail.taskId 指向原任务"
    "D3-2|重复转任务不新增 task 行"
    "D4-1|非评审人（vendor2）转任务 → 403"
    "D4-2|评审关闭后新增意见 → 409 REVIEW_CLOSED"
    "D4-3|评审关闭后 to-task 仍 → 201"
)
pdr_manifest_parse
if [[ "${MODE}" == "dry" ]]; then
    pdr_dry_run
    exit 0
fi

# --------------------------------------------------------------------------- 运行参数

RTAG="$(date +%m%d%H%M%S)-$$"
TAG_NUM="$(date +%m%d%H%M%S)$$"
PROJECT_CODE="PDRP${TAG_NUM}"
RUN_START="$(date '+%Y-%m-%d %H:%M:%S')"
TMPDIR_PDR="$(mktemp -d "${TMPDIR:-/tmp}/pdr-flow.XXXXXX")"
cleanup() {
    teardown_fixture
    rm -rf "${TMPDIR_PDR}"
}
on_exit() {
    local rc=$?
    trap - EXIT INT TERM
    cleanup
    exit "${rc}"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# shellcheck disable=SC2034
TENANT_ID=""
PM_ID=""
PO1_ID=""
DEV1_ID=""
DEV2_ID=""
QA1_ID=""
TL1_ID=""
VPM1_ID=""
VENDOR2_ID=""
VEN_A=""
VEN_B=""
DOMAIN_ID=""
DOMAIN2_ID=""
PROJECT_ID=""
BUS_ID=""
BUS2_ID=""
PDR_A=""
PDR_B=""
PDR_D=""
PDR_H=""
PDR_E=""
PDR_F=""
REVIEW_A=""
REVIEW_A2=""
REVIEW_D=""
REVIEW_H=""
REVIEW_E=""
REVIEW_F=""
ATT_A=""
RD_ID=""
OP1=""
OP2=""
OP3=""
TASK_ID=""
TASK_CODE=""
REPORT_ID=""
# 会发 HTTP 的辅助函数用这些全局变量回传值（不用命令替换捕获，见下方函数注释）
CREATED_REQ_ID=""
CREATED_REQ_CODE=""
STARTED_REVIEW_ID=""
START_REVIEW_CODE=""
SUBMITTED_OP_ID=""
OP_CODE=""
REVIEW_BODY=""
REQ_BODY=""
REQ_STATUS=""
CLOSED_STATUS=""
DOMAIN_CODE=""
DOMAIN_CODE_HTTP=""
DOMAIN2_CODE=""
DOMAIN2_CODE_HTTP=""
SCHED_CODE=""
SCHED_STATUS=""
SCHED_BODY=""
CODE_A=""
CODE_B=""
CODE_D=""
CODE_E=""
CODE_F=""
CODE_BUS2=""
TEARDOWN_DONE=0

J_OPINIONS='(.opinions // .pdrReview.opinions // .review.opinions // .view.pdrReview.opinions // .view.opinions // [])'
J_REVIEW_STATUS='(.status // .pdrReview.status // .review.status // .view.pdrReview.status)'
J_SCHEDULE_STATUS='(.view.status // .status)'
ALLOWED_CATEGORIES="RANGE TECH SCHEDULE RISK ACCEPTANCE UI DATA COMPLIANCE OTHER"

# --------------------------------------------------------------------------- 小工具

adetail() { printf 'HTTP=%s code=%s body=%s' "${PDR_HTTP_CODE:-000}" "$(pdr_code)" "$(pdr_snippet)"; }
is2xx() { case "${1:-}" in 2??) return 0 ;; *) return 1 ;; esac; }
is4xx() { case "${1:-}" in 4??) return 0 ;; *) return 1 ;; esac; }
in_list() { local needle="$1" list="$2"; case " ${list} " in *" ${needle} "*) return 0 ;; *) return 1 ;; esac; }
sql() { db_q "$1" 2>/dev/null | head -n1; }
sql_num() { local n; n="$(sql "$1")"; printf '%s' "${n:-0}"; }
user_id() { sql "SELECT id FROM sys_user WHERE tenant_id=${TENANT_ID} AND username='$1'"; }
vendor_id() { sql "SELECT id FROM sys_vendor WHERE tenant_id=${TENANT_ID} AND code='$1'"; }

# 约定：会发 HTTP 的辅助函数一律通过**全局变量**回传值，调用方绝不写 X="$(fn ...)"——
# 命令替换在子壳里执行，函数里赋的 CREATED_REQ_CODE/OP_CODE 父壳读不到，set -u 下就是
# "CREATED_REQ_CODE: unbound variable"（S4 崩溃的根因）。
# 返回值只表达「拿到 id 没有」：非 0 = 夹具失败；调用方要么 || pdr_abort，
# 要么用回传的 HTTP 码做断言（断言口径不变）。

# create_req <type> <title> <parent> <project> <domain> → CREATED_REQ_ID / CREATED_REQ_CODE
create_req() {
    local type="$1" title="$2" parent="${3:-}" proj="${4:-}" dom="${5:-${DOMAIN_ID}}" body
    body="$(jq -cn --arg t "${type}" --arg ti "${title}" --argjson d "${dom}" \
        --arg p "${parent}" --arg pj "${proj}" \
        '{type:$t,title:$ti,priority:"P1",domainIds:[$d],expectedDeliveryDate:"2026-12-31"}
         + (if $p != "" then {parentId:($p|tonumber)} else {} end)
         + (if $pj != "" then {projectId:($pj|tonumber)} else {} end)')"
    pdr_post /api/req/requirements "${body}"
    CREATED_REQ_CODE="${PDR_HTTP_CODE}"
    CREATED_REQ_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.id' '.view.id')"
    [[ -n "${CREATED_REQ_ID}" ]] && is2xx "${CREATED_REQ_CODE}"
}

# start_review <reqId> <reviewersJson|null> <note> → STARTED_REVIEW_ID / START_REVIEW_CODE
start_review() {
    local req="$1" reviewers="$2" note="$3" body
    if [[ -z "${reviewers}" || "${reviewers}" == "null" ]]; then
        body="$(jq -cn --arg n "${note}" '{reviewerIds:[],note:$n}')"
    else
        body="$(jq -cn --argjson r "${reviewers}" --arg n "${note}" '{reviewerIds:$r,note:$n}')"
    fi
    pdr_post "/api/req/requirements/${req}/pdr-review" "${body}"
    START_REVIEW_CODE="${PDR_HTTP_CODE}"
    STARTED_REVIEW_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.reviewId' '.id' '.review.id')"
    [[ -n "${STARTED_REVIEW_ID}" ]] && is2xx "${START_REVIEW_CODE}"
}

# submit_op <reviewId> <decision> <category|""> <comment> → SUBMITTED_OP_ID / OP_CODE
submit_op() {
    local review="$1" decision="$2" category="$3" comment="$4" body
    if [[ -z "${category}" ]]; then
        body="$(jq -cn --arg d "${decision}" --arg c "${comment}" '{decision:$d,comment:$c}')"
    else
        body="$(jq -cn --arg d "${decision}" --arg g "${category}" --arg c "${comment}" \
            '{decision:$d,category:$g,comment:$c}')"
    fi
    pdr_post "/api/req/pdr-reviews/${review}/opinions" "${body}"
    OP_CODE="${PDR_HTTP_CODE}"
    # POST /pdr-reviews/{id}/opinions 回 ReviewDetail{review,opinions[]}：新意见是数组最后一条
    SUBMITTED_OP_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.opinions[-1].id' '.opinion.id' '.id')"
    [[ -n "${SUBMITTED_OP_ID}" ]] && is2xx "${OP_CODE}"
}

# req_get <id>：刷新 REQ_BODY / REQ_STATUS（不再回显，避免调用方命令替换丢全局）
req_get() {
    pdr_get "/api/req/requirements/$1"
    REQ_BODY="${PDR_HTTP_BODY}"
    REQ_STATUS="$(pdr_pick "${REQ_BODY}" "${J_SCHEDULE_STATUS}")"
    is2xx "${PDR_HTTP_CODE}"
}

estimate_status() { req_get "$1" >/dev/null; pdr_pick "${REQ_BODY}" '.estimate.status' '.view.estimate.status'; }
estimate_field() { req_get "$1" >/dev/null; pdr_pick "${REQ_BODY}" ".estimate.$2" ".view.estimate.$2"; }

# approve_estimate <reqId>：以 pm 逐轮同意 WBS_ESTIMATE 待办，最后断言 APPROVED
approve_estimate() {
    local rid="$1" i task
    pdr_login pm || return 1
    for i in 1 2 3 4 5; do
        pdr_get '/api/req/approvals/todo?page=1&size=200'
        task="$(pdr_json "${PDR_HTTP_BODY}" "[.items[]? | select((.bizId|tostring)==\"${rid}\" and .bizType==\"WBS_ESTIMATE\")][0].taskId")"
        [[ -z "${task}" || "${task}" == "null" ]] && break
        pdr_post "/api/req/approvals/tasks/${task}/actions" '{"action":"APPROVE","comment":"PDR 门禁评估审批"}'
        [[ "${PDR_HTTP_CODE}" == "200" ]] || return 1
    done
    estimate_status "${rid}" >/dev/null
    [[ "$(estimate_status "${rid}")" == "APPROVED" ]]
}

# ensure_closed <reqId> <reviewId>：评审若还 IN_REVIEW 就 conclude 收口（自动汇总未触发时兜底）
# → CLOSED_STATUS；已 REJECTED/PASSED 时什么都不做（重复 conclude 会污染断言）。
ensure_closed() {
    local rid="$1" review="$2" st=""
    if get_review "${rid}"; then
        st="$(pdr_pick "${REVIEW_BODY}" "${J_REVIEW_STATUS}")"
    fi
    if [[ "${st}" == "IN_REVIEW" ]]; then
        pdr_post "/api/req/pdr-reviews/${review}/conclude" '{}'
        if get_review "${rid}"; then
            st="$(pdr_pick "${REVIEW_BODY}" "${J_REVIEW_STATUS}")"
        fi
    fi
    CLOSED_STATUS="${st}"
    [[ -n "${st}" ]]
}

# get_review <reqId> → REVIEW_BODY（调用方用 pdr_pick 取字段）
get_review() {
    pdr_get "/api/req/requirements/$1/pdr-review"
    REVIEW_BODY="${PDR_HTTP_BODY}"
    is2xx "${PDR_HTTP_CODE}"
}

# --------------------------------------------------------------------------- teardown
# 本轮造的需求/评审/意见/任务/附件/流程/通知/项目全部删掉，避免下一轮 demo-reset 撞「非夹具需求 0」。
# 库里没有外键约束，但删除顺序仍按依赖来（意见→任务→评审→需求→项目），并把删不掉的显式标记出来。
teardown_fixture() {
    [[ "${TEARDOWN_DONE}" == "1" ]] && return 0
    TEARDOWN_DONE=1
    if ! db_up; then
        pdr_info "teardown 跳过：MySQL 不可达，本轮夹具可能残留（BUS=${BUS_ID:-无} BUS2=${BUS2_ID:-无} A=${PDR_A:-无} B=${PDR_B:-无} D=${PDR_D:-无} E=${PDR_E:-无} F=${PDR_F:-无} H=${PDR_H:-无}），请 make platform-demo-reset"
        return 0
    fi
    local ids="" v T="${TENANT_ID}" revs proj="${PROJECT_ID}"
    for v in "${BUS_ID}" "${BUS2_ID}" "${PDR_A}" "${PDR_B}" "${PDR_D}" "${PDR_E}" "${PDR_F}" "${PDR_H}" "${RD_ID}"; do
        [[ "${v}" =~ ^[0-9]+$ ]] && ids="${ids:+${ids},}${v}"
    done
    if [[ -z "${ids}" ]]; then
        pdr_info "teardown：本轮没有成功创建的需求，无需清理"
        return 0
    fi
    [[ "${proj}" =~ ^[0-9]+$ ]] || proj=0
    revs="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM pdr_review WHERE tenant_id=${T} AND requirement_id IN (${ids})" 2>/dev/null | head -n1)"
    revs="${revs:-0}"

    local n_req n_rev n_op n_task n_att n_wf n_notif n_proj
    n_req="$(sql_num "SELECT COUNT(*) FROM requirement WHERE tenant_id=${T} AND id IN (${ids})")"
    n_rev="$(sql_num "SELECT COUNT(*) FROM pdr_review WHERE tenant_id=${T} AND id IN (${revs})")"
    n_op="$(sql_num "SELECT COUNT(*) FROM pdr_review_opinion WHERE tenant_id=${T} AND review_id IN (${revs})")"
    n_task="$(sql_num "SELECT COUNT(*) FROM task WHERE tenant_id=${T} AND requirement_id IN (${ids})")"
    n_att="$(sql_num "SELECT COUNT(*) FROM attachment WHERE tenant_id=${T} AND ((biz_type='PDR_REVIEW' AND biz_id IN (${revs})) OR (biz_type='REQUIREMENT' AND biz_id IN (${ids})))")"
    n_wf="$(sql_num "SELECT COUNT(*) FROM wf_instance WHERE tenant_id=${T} AND (biz_id IN (${ids}) OR biz_id IN (${revs}))")"
    n_notif="$(sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${T} AND (biz_id IN (${ids}) OR biz_id IN (${revs}))")"
    n_proj="$(sql_num "SELECT COUNT(*) FROM project WHERE tenant_id=${T} AND id=${proj}")"

    td_del() {
        db_q "DELETE FROM $1 WHERE $2" >/dev/null 2>&1 \
            || pdr_info "teardown: DELETE $1 失败（表可能不存在）；残留检查会把它列出来"
    }
    td_del_if() { # <table> <where>：表不存在就跳过（不同迭代的表集不完全一样）
        local has
        has="$(sql_num "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$1'")"
        [[ "${has}" == "0" ]] && return 0
        td_del "$1" "$2"
    }
    local wfkids="tenant_id=${T} AND instance_id IN (SELECT id FROM wf_instance WHERE tenant_id=${T} AND (biz_id IN (${ids}) OR biz_id IN (${revs})))"

    # 1) 评审意见（含转任务回写）→ 附件 → 流程 → 通知 → 任务 → 评审单
    td_del_if pdr_review_opinion "tenant_id=${T} AND (review_id IN (${revs}) OR task_id IN (SELECT id FROM task WHERE tenant_id=${T} AND requirement_id IN (${ids})))"
    td_del_if attachment "tenant_id=${T} AND ((biz_type='PDR_REVIEW' AND biz_id IN (${revs})) OR (biz_type='REQUIREMENT' AND biz_id IN (${ids})))"
    td_del_if wf_history "${wfkids}"
    td_del_if wf_cc "${wfkids}"
    td_del_if wf_task "${wfkids}"
    td_del_if wf_instance "tenant_id=${T} AND (biz_id IN (${ids}) OR biz_id IN (${revs}))"
    td_del_if notification "tenant_id=${T} AND (biz_id IN (${ids}) OR biz_id IN (${revs}))"
    td_del_if task "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if pdr_review "tenant_id=${T} AND id IN (${revs})"

    # 2) 需求周边（测试/缺陷/变更/构建/发布/集成/知识库）
    td_del_if defect "tenant_id=${T} AND (requirement_id IN (${ids}) OR test_case_id IN (SELECT id FROM test_case WHERE tenant_id=${T} AND requirement_id IN (${ids})))"
    td_del_if test_execution "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if test_plan_case "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if test_case "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if req_change_item "change_id IN (SELECT id FROM req_change WHERE tenant_id=${T} AND requirement_id IN (${ids}))"
    td_del_if req_change "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if artifact_record "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if automation_event "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if automation_run "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if build_record "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if mr_sync "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if quality_gate_snapshot "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if release_requirement "requirement_id IN (${ids})"
    td_del_if deploy_order_requirement "requirement_id IN (${ids})"
    td_del_if integration_sync_log "tenant_id=${T} AND biz_id IN (${ids})"
    td_del_if wiki_page_link "tenant_id=${T} AND biz_id IN (${ids})"
    td_del_if requirement_domain_rel "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if requirement_tag "tenant_id=${T} AND requirement_id IN (${ids})"
    td_del_if requirement "tenant_id=${T} AND id IN (${ids})"

    # 3) 本轮自建的项目（域不再新建，所以没有域残留）
    td_del_if project_member "tenant_id=${T} AND project_id=${proj}"
    td_del_if project "tenant_id=${T} AND id=${proj}"

    # 4) 残留校验：删不掉的显式标记 + 打印，绝不静默
    local left_req left_rev left_op left_task left_att left_wf left_notif left_proj
    local residue="" residue_n=0
    left_req="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM requirement WHERE tenant_id=${T} AND id IN (${ids})" 2>/dev/null | head -n1)"
    left_rev="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM pdr_review WHERE tenant_id=${T} AND id IN (${revs})" 2>/dev/null | head -n1)"
    left_op="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM pdr_review_opinion WHERE tenant_id=${T} AND review_id IN (${revs})" 2>/dev/null | head -n1)"
    left_task="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM task WHERE tenant_id=${T} AND requirement_id IN (${ids})" 2>/dev/null | head -n1)"
    left_att="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM attachment WHERE tenant_id=${T} AND ((biz_type='PDR_REVIEW' AND biz_id IN (${revs})) OR (biz_type='REQUIREMENT' AND biz_id IN (${ids})))" 2>/dev/null | head -n1)"
    left_wf="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM wf_instance WHERE tenant_id=${T} AND (biz_id IN (${ids}) OR biz_id IN (${revs}))" 2>/dev/null | head -n1)"
    left_notif="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM notification WHERE tenant_id=${T} AND (biz_id IN (${ids}) OR biz_id IN (${revs}))" 2>/dev/null | head -n1)"
    left_proj="$(db_q "SELECT IFNULL(GROUP_CONCAT(id),'') FROM project WHERE tenant_id=${T} AND id=${proj}" 2>/dev/null | head -n1)"
    residue_add() {
        local label="$1" list="${2:-}" n
        [[ -z "${list}" ]] && return 0
        n="$(printf '%s' "${list}" | awk -F, '{print NF}')"
        residue_n=$((residue_n + n))
        residue="${residue:+${residue} }${label}#$(printf '%s' "${list}" | tr ',' '/')"
    }
    residue_add requirement "${left_req}"
    residue_add pdr_review "${left_rev}"
    residue_add opinion "${left_op}"
    residue_add task "${left_task}"
    residue_add attachment "${left_att}"
    residue_add wf_instance "${left_wf}"
    residue_add notification "${left_notif}"
    residue_add project "${left_proj}"
    if [[ -n "${left_req}" ]]; then
        db_q "UPDATE requirement SET title=CONCAT('[残留 PDR${RTAG}] ', title) WHERE tenant_id=${T} AND id IN (${left_req})" >/dev/null 2>&1 || true
    fi
    pdr_info "teardown 已删除：需求 ${n_req} 评审 ${n_rev} 意见 ${n_op} 任务 ${n_task} 附件 ${n_att} 流程实例 ${n_wf} 通知 ${n_notif} 项目 ${n_proj}"
    if [[ "${residue_n}" == "0" ]]; then
        pdr_info "teardown 校验：本轮残留 0 条（需求/评审/意见/任务/附件/流程/通知/项目）"
    else
        pdr_info "本轮残留 ${residue_n} 条：${residue}（需求标题已加 [残留 PDR${RTAG}] 前缀，可 make platform-demo-reset 清掉）"
    fi
}

# make_pdf <path> <megabytes>
make_pdf() {
    local path="$1" mb="$2"
    {
        printf '%%PDF-1.4\n%% PDR gate fixture %s\n' "${RTAG}"
        dd if=/dev/zero bs=1048576 count="${mb}" 2>/dev/null
    } >"${path}"
    printf '%%%%EOF\n' >>"${path}"
}

sha_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

file_bytes() { wc -c <"$1" | tr -d ' '; }

stats_rows_tsv() { # <statsBody> → "category\tcount\tratio" 每行一类
    printf '%s' "${1:-}" | jq -r '
        ((.buckets // .items // .byCategory // .groups // .categories // .data
          // .stats.buckets // .stats.items // .stats.byCategory // .rows // []))
        | if type=="array" then .
          elif type=="object" then [to_entries[] | {category:.key, count:.value}]
          else [] end
        | .[]
        | [ ((.category // .key // .name // .dimension // "")|tostring),
            ((.count // .total // .value // .opinionCount // "")|tostring),
            ((.ratio // .percent // .share // .percentage // "")|tostring) ]
        | @tsv' 2>/dev/null || true
}

stats_total() { # <statsBody>
    local total sum
    total="$(printf '%s' "${1:-}" | jq -r '(.opinionCount // .total // .count // .stats.opinionCount // .stats.total) // empty' 2>/dev/null || true)"
    if [[ -n "${total}" ]]; then printf '%s' "${total}"; return 0; fi
    sum="$(stats_rows_tsv "${1}" | awk -F'\t' 'NF>=2 {s+=$2} END {printf "%d", s+0}')"
    printf '%s' "${sum:-0}"
}

detail_pairs_tsv() { # <opinionsBody> <pathToArray> → "category\tcount" 按字母序
    printf '%s' "${1:-}" | jq -r "
        (${2:-${J_OPINIONS}}) | map(.category // \"\") | group_by(.) | .[]
        | [.[0], (length|tostring)] | @tsv" 2>/dev/null | LC_ALL=C sort || true
}

# --------------------------------------------------------------------------- S 前置

pdr_section "S 前置：栈就绪 / 独立域 / 项目组 / BUS→POOLED / PDR 夹具"
GATE_FIXTURE_TENANT_CODE="${PDR_TENANT_CODE}"

pdr_wait_ready "req-service 健康（status=UP db=up flyway）" 30 req_ready "先 make platform-up && make platform-seed" \
    || pdr_abort "req-service 未就绪（/internal/health 不满足 UP/up/flyway）"
pdr_wait_ready "deliver-service 健康" 30 deliver_ready "先 make platform-up" \
    || pdr_abort "deliver-service 未就绪（/internal/health 不是 UP/up）"
if ! db_up; then
    pdr_abort "MySQL 不可达：B5/D2/D3 的审计与任务行断言依赖 db_q（PLATFORM_DB_HOST=${PLATFORM_DB_HOST:-127.0.0.1}，先 make platform-up）"
fi
pdr_login_admin || pdr_abort "admin 登录失败（tenantCode=${PDR_TENANT_CODE}）"
TENANT_ID="${PDR_TENANT_ID}"
PM_ID="$(user_id pm)"
PO1_ID="$(user_id po1)"
DEV1_ID="$(user_id dev1)"
DEV2_ID="$(user_id dev2)"
QA1_ID="$(user_id qa1)"
TL1_ID="$(user_id tl1)"
VPM1_ID="$(user_id vpm1)"
VENDOR2_ID="$(user_id vendor2)"
VEN_A="$(vendor_id VEN-A)"
VEN_B="$(vendor_id VEN-B)"
for pair in "pm:${PM_ID}" "po1:${PO1_ID}" "dev1:${DEV1_ID}" "dev2:${DEV2_ID}" "qa1:${QA1_ID}" "tl1:${TL1_ID}" \
    "vpm1:${VPM1_ID}" "vendor2:${VENDOR2_ID}" "VEN-A:${VEN_A}" "VEN-B:${VEN_B}"; do
    if [[ -z "${pair#*:}" ]]; then pdr_abort "种子数据缺失：${pair%%:*} 在 tenant=${TENANT_ID} 里查不到（先 make platform-seed）"; fi
done

# S1：不再新建域。新建的 biz_domain 没有 sys_user_domain 授权（种子域才有），
# pm 在新域里提报会 403 OUT_OF_SCOPE；且每轮新建域会永久污染 demo-reset 的「域 7」断言。
# 改用种子域 ORD（订单管理），它本来就有全部种子账号的授权。
DOMAIN_CODE="ORD"
pdr_get /api/req/admin/domains
DOMAIN_CODE_HTTP="${PDR_HTTP_CODE}"
DOMAIN_ID="$(pdr_json "${PDR_HTTP_BODY}" '[(if type=="array" then . else (.items // .members // []) end)[] | select((.code//"")=="ORD")][0].id')"
pdr_ck "S1" "$([[ -n "${DOMAIN_ID}" ]] && is2xx "${DOMAIN_CODE_HTTP}" && echo 1 || echo 0)" \
    "domainId=${DOMAIN_ID:-<空>} code=ORD $(adetail)" \
    "GET /api/req/admin/domains 需返回种子域清单（admin 自带域授权），并含 code=ORD 的订单管理域"
[[ -z "${DOMAIN_ID}" ]] && pdr_abort "种子域 ORD 取不到（先 make platform-seed）"

pdr_post /api/req/projects "$(jq -cn --arg c "${PROJECT_CODE}" --arg n "PDR门禁项目-${RTAG}" \
    --argjson d "${DOMAIN_ID}" --argjson o "${PM_ID}" '{code:$c,name:$n,domainId:$d,ownerUserId:$o}')"
PROJECT_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.id' '.projectId' '.project.id')"
pdr_ck "S2" "$([[ -n "${PROJECT_ID}" ]] && is2xx "${PDR_HTTP_CODE}" && echo 1 || echo 0)" \
    "projectId=${PROJECT_ID:-<空>} $(adetail)" \
    "POST /api/req/projects 需 org:manage，body 契约未写死（本脚本用 code/name/domainId/ownerUserId）"

MEMBER_OK=0
MEMBER_ROLE_KEY=""
for spec in "${PM_ID}:PO" "${TL1_ID}:PM" "${DEV1_ID}:DEV" "${DEV2_ID}:SME" "${QA1_ID}:QA"; do
    uid="${spec%%:*}"
    role="${spec#*:}"
    pdr_post "/api/req/projects/${PROJECT_ID}/members" \
        "$(jq -cn --argjson u "${uid}" --arg r "${role}" '{userId:$u,roleInProject:$r}')"
    if [[ "${PDR_HTTP_CODE}" == "200" || "${PDR_HTTP_CODE}" == "201" ]]; then
        MEMBER_OK=$((MEMBER_OK + 1))
        MEMBER_ROLE_KEY="roleInProject"
        continue
    fi
    pdr_post "/api/req/projects/${PROJECT_ID}/members" \
        "$(jq -cn --argjson u "${uid}" --arg r "${role}" '{userId:$u,role:$r}')"
    if [[ "${PDR_HTTP_CODE}" == "200" || "${PDR_HTTP_CODE}" == "201" ]]; then
        MEMBER_OK=$((MEMBER_OK + 1))
        MEMBER_ROLE_KEY="role"
    fi
done
pdr_get "/api/req/projects/${PROJECT_ID}/members"
# 该接口返回**裸数组**（不是 {items:[]}/{members:[]}）；对数组取 .items 会让 jq 报错 → 长度恒 0。
MEMBER_LIST_LEN="$(pdr_len "${PDR_HTTP_BODY}" '(if type=="array" then . else (.items // .members // []) end)')"
pdr_ck "S3" "$([[ "${MEMBER_OK}" == "5" && "${MEMBER_LIST_LEN}" == "5" ]] && echo 1 || echo 0)" \
    "成功 ${MEMBER_OK}/5，列表 ${MEMBER_LIST_LEN} 条（role 字段名=${MEMBER_ROLE_KEY:-<未识别>}）body=$(pdr_snippet)" \
    "POST /projects/{id}/members 接受 {userId,roleInProject} 或 {userId,role}；列表返回 5 条"

pdr_login pm || pdr_abort "pm 登录失败"
create_req BUS "PDR门禁BUS-${RTAG}" "" "" "${DOMAIN_ID}"
BUS_ID="${CREATED_REQ_ID}"
pdr_ck "S4" "$([[ -n "${BUS_ID}" ]] && [[ "${CREATED_REQ_CODE}" == "201" ]] && echo 1 || echo 0)" \
    "busId=${BUS_ID:-<空>} HTTP=${CREATED_REQ_CODE} body=$(pdr_snippet)" \
    "POST /api/req/requirements 需 type=BUS + priority + domainIds + expectedDeliveryDate"
[[ -z "${BUS_ID}" ]] && pdr_abort "BUS 夹具未创建（后续 S5/A/B/D/E/F 全依赖它）"

# gate_pool_bus 内部用 api_call（读 API_TOKEN/API_TENANT，由 platform_login 写），
# 所以要先 gate_login_as pm；它结束后再 pdr_login pm 把 PDR_TOKEN 换回来。
gate_login_as pm >/dev/null 2>&1
gate_pool_bus "${BUS_ID}" "PDR 门禁 BUS" >/dev/null 2>&1
pdr_login pm >/dev/null 2>&1
req_get "${BUS_ID}"
BUS_STATUS="${REQ_STATUS}"
pdr_ck "S5" "$([[ "${BUS_STATUS}" == "POOLED" ]] && echo 1 || echo 0)" \
    "status=${BUS_STATUS:-<空>} $(adetail)" \
    "BUS 必须走 submit→REQ_SUBMIT 会签→review_start→REQ_REVIEW 会签；PDR 只能挂 POOLED 的 BUS"

create_req PDR "PDR门禁-主链-${RTAG}" "${BUS_ID}" "${PROJECT_ID}" "${DOMAIN_ID}"
PDR_A="${CREATED_REQ_ID}"; CODE_A="${CREATED_REQ_CODE}"
create_req PDR "PDR门禁-未评审-${RTAG}" "${BUS_ID}" "" "${DOMAIN_ID}"
PDR_B="${CREATED_REQ_ID}"; CODE_B="${CREATED_REQ_CODE}"
# 统计口径单独一个**种子**域（SUP 供应链） + 单独 BUS：C 组 stats 只数到本域数据，
# 不会被 D1 的评审污染；SUP 里没有 demo 的 PDR 意见（ORD/INV/LOG 有）。
DOMAIN2_CODE="SUP"
pdr_login_admin >/dev/null 2>&1
pdr_get /api/req/admin/domains
DOMAIN2_CODE_HTTP="${PDR_HTTP_CODE}"
DOMAIN2_ID="$(pdr_json "${PDR_HTTP_BODY}" '[(if type=="array" then . else (.items // .members // []) end)[] | select((.code//"")=="SUP")][0].id')"
pdr_login pm >/dev/null 2>&1
create_req BUS "PDR门禁统计BUS-${RTAG}" "" "" "${DOMAIN2_ID}"
BUS2_ID="${CREATED_REQ_ID}"; CODE_BUS2="${CREATED_REQ_CODE}"
gate_login_as pm >/dev/null 2>&1
gate_pool_bus "${BUS2_ID}" "PDR 门禁统计 BUS" >/dev/null 2>&1
pdr_login pm >/dev/null 2>&1
create_req PDR "PDR门禁-统计-${RTAG}" "${BUS2_ID}" "" "${DOMAIN2_ID}"
PDR_D="${CREATED_REQ_ID}"; CODE_D="${CREATED_REQ_CODE}"
create_req PDR "PDR门禁-转任务-${RTAG}" "${BUS_ID}" "${PROJECT_ID}" "${DOMAIN_ID}"
PDR_E="${CREATED_REQ_ID}"; CODE_E="${CREATED_REQ_CODE}"
create_req PDR "PDR门禁-附件边界-${RTAG}" "${BUS_ID}" "${PROJECT_ID}" "${DOMAIN_ID}"
PDR_F="${CREATED_REQ_ID}"; CODE_F="${CREATED_REQ_CODE}"
PDR_IDS_OK=1
for pair in "A:${PDR_A}:${CODE_A}" "B:${PDR_B}:${CODE_B}" "D:${PDR_D}:${CODE_D}" \
    "E:${PDR_E}:${CODE_E}" "F:${PDR_F}:${CODE_F}"; do
    if [[ -z "${pair#*:*:}" || "${pair##*:}" != "201" ]]; then PDR_IDS_OK=0; fi
done
if [[ -z "${DOMAIN2_ID}" ]] || ! is2xx "${DOMAIN2_CODE_HTTP}" || [[ "${CODE_BUS2}" != "201" ]]; then
    PDR_IDS_OK=0
fi
pdr_ck "S6" "${PDR_IDS_OK}" \
    "A=${PDR_A:-<空>}(${CODE_A}) B=${PDR_B:-<空>}(${CODE_B}) D=${PDR_D:-<空>}(${CODE_D}) E=${PDR_E:-<空>}(${CODE_E}) F=${PDR_F:-<空>}(${CODE_F})；域2=${DOMAIN2_ID:-<空>}(${DOMAIN2_CODE_HTTP}) BUS2=${BUS2_ID:-<空>}(${CODE_BUS2})" \
    "PDR 必须挂 POOLED BUS；统计夹具另用种子域 ${DOMAIN2_CODE}（无 demo PDR 意见，避免污染 C 组口径）"
if [[ -z "${PDR_A}" || -z "${PDR_B}" || -z "${PDR_D}" || -z "${PDR_E}" || -z "${PDR_F}" ]]; then
    pdr_abort "PDR 夹具未创建（A=${PDR_A:-空} B=${PDR_B:-空} D=${PDR_D:-空} E=${PDR_E:-空} F=${PDR_F:-空}）"
fi

# 需求→项目绑定：契约 §5 没写入口，按「创建体带 projectId，否则 PUT 补」两种合法猜测都试一次。
req_get "${PDR_A}" >/dev/null
BOUND_PID="$(pdr_pick "${REQ_BODY}" '.view.projectId' '.projectId' '.view.project_id')"
if [[ "${BOUND_PID}" != "${PROJECT_ID}" ]]; then
    pdr_put "/api/req/requirements/${PDR_A}" "$(jq -cn --argjson p "${PROJECT_ID}" '{projectId:$p}')"
    req_get "${PDR_A}" >/dev/null
    BOUND_PID="$(pdr_pick "${REQ_BODY}" '.view.projectId' '.projectId' '.view.project_id')"
fi
pdr_ck "S7" "$([[ "${BOUND_PID}" == "${PROJECT_ID}" ]] && echo 1 || echo 0)" \
    "view.projectId=${BOUND_PID:-<空>} 期望=${PROJECT_ID}（PUT 后仍不生效）" \
    "契约 §5 未定义需求→项目绑定入口：请给 CreateRequest/UpdateRequest 增加 projectId，或新增 POST /projects/{id}/requirements；否则 A1 只能走回退规则"

PDR_PROBE_DOMAIN="${DOMAIN_ID}"
pdr_probe_stats() { pdr_probe_non404 "/api/req/pdr-reviews/stats?domainId=${PDR_PROBE_DOMAIN}&groupBy=CATEGORY"; }
if pdr_wait_ready "PDR 评审接口 /pdr-reviews/stats" 30 pdr_probe_stats "后端可能尚未实现 r46 接口"; then
    pdr_ck "S8" 1 "/pdr-reviews/stats 可达（HTTP ${PDR_HTTP_CODE}）" ""
else
    pdr_ck "S8" 0 "30s 内 /pdr-reviews/stats 一直是 404/不可达（最后一次 $(adetail)）" \
        "先确认 req-service 已包含 PDR 评审路由（新迁移 + PdrReviewController）"
fi

# --------------------------------------------------------------------------- A 组：项目组评审

pdr_section "A 组：项目组成员评审（A1–A5）"

pdr_login pm || pdr_abort "pm 登录失败"
REVIEWERS="[${PM_ID},${TL1_ID},${DEV1_ID},${DEV2_ID}]"
start_review "${PDR_A}" "${REVIEWERS}" "PDR 门禁首轮评审 ${RTAG}"
REVIEW_A="${STARTED_REVIEW_ID}"
pdr_ck "A1-1" "$([[ -n "${REVIEW_A}" ]] && is2xx "${START_REVIEW_CODE}" && echo 1 || echo 0)" \
    "reviewId=${REVIEW_A:-<空>} HTTP=${START_REVIEW_CODE} body=$(pdr_snippet)" \
    "POST /requirements/{id}/pdr-review {reviewerIds,note}，评审人必须是项目成员"
[[ -z "${REVIEW_A}" ]] && pdr_abort "首轮评审单未创建（reviewId 为空）：后续 A/B 组断言全部依赖它"

OP_COUNT=0
for spec in "pm:PASS:RANGE" "tl1:PASS:TECH" "dev1:PASS:SCHEDULE" "dev2:NEED_CHANGE:RISK"; do
    who="${spec%%:*}"
    rest="${spec#*:}"
    decision="${rest%%:*}"
    category="${rest#*:}"
    pdr_login "${who}" >/dev/null 2>&1 || continue
    submit_op "${REVIEW_A}" "${decision}" "${category}" "${who} 首轮意见 ${RTAG}"
    if [[ "${OP_CODE}" == "201" || "${OP_CODE}" == "200" ]]; then OP_COUNT=$((OP_COUNT + 1)); fi
done
pdr_ck "A1-2" "$([[ "${OP_COUNT}" == "4" ]] && echo 1 || echo 0)" \
    "成功 ${OP_COUNT}/4（最后一次 $(adetail)）" \
    "POST /pdr-reviews/{reviewId}/opinions {decision,category,comment}；评审人各一条"

pdr_login pm >/dev/null 2>&1
get_review "${PDR_A}"
REV_BODY="${REVIEW_BODY}"
OP_LEN="$(pdr_len "${REV_BODY}" "${J_OPINIONS}")"
OP_REVIEWERS="$(pdr_json "${REV_BODY}" "[${J_OPINIONS}[] | .reviewerId] | unique | length")"
pdr_ck "A1-3" "$([[ "${OP_LEN}" == "4" && "${OP_REVIEWERS}" == "4" ]] && echo 1 || echo 0)" \
    "opinions=${OP_LEN} 去重评审人=${OP_REVIEWERS} body=$(pdr_snippet "${REV_BODY}")" \
    "GET /requirements/{id}/pdr-review 需返回 opinions[]（reviewerId/decision/category/seq）"

ST="$(pdr_pick "${REV_BODY}" "${J_REVIEW_STATUS}")"
pdr_ck "A3-1" "$([[ "${ST}" == "REJECTED" ]] && echo 1 || echo 0)" \
    "review.status=${ST:-<空>}" \
    "R2 MODE_ALL：任一 NEED_CHANGE/REJECT → REJECTED（不是 PASSED、不是 IN_REVIEW）"

pdr_login pm >/dev/null 2>&1
pdr_put "/api/req/requirements/${PDR_A}" "$(jq -cn --arg t "PDR门禁-主链-已改-${RTAG}" '{title:$t}')"
pdr_ck "A3-2" "$([[ "${PDR_HTTP_CODE}" == "200" ]] && echo 1 || echo 0)" \
    "$(adetail)" "REJECTED 后 PDR 仍在 PENDING_ANALYSIS，内容可改（改动走 PUT /requirements/{id}）"

pdr_post "/api/req/requirements/${PDR_A}/assign-vendor" \
    "$(jq -cn --argjson v "${VEN_A}" --arg r "PDR 门禁未过评审先试指派" '{vendorId:$v,reason:$r}')"
pdr_ck "A3-3" "$([[ "${PDR_HTTP_CODE}" == "409" && "$(pdr_code)" == "PDR_REVIEW_REQUIRED" ]] && echo 1 || echo 0)" \
    "$(adetail)" "R1：评审未通过时 assign-vendor 必须 409 PDR_REVIEW_REQUIRED（detail.required=pdr_review）"

pdr_login pm >/dev/null 2>&1
start_review "${PDR_A}" "${REVIEWERS}" "PDR 门禁二次评审 ${RTAG}"
REVIEW_A2="${STARTED_REVIEW_ID}"
pdr_ck "A3-4" "$([[ -n "${REVIEW_A2}" && "${REVIEW_A2}" != "${REVIEW_A}" ]] && is2xx "${START_REVIEW_CODE}" && echo 1 || echo 0)" \
    "旧=${REVIEW_A} 新=${REVIEW_A2:-<空>} HTTP=${START_REVIEW_CODE}" \
    "重新发起评审必须是新单（旧单 REJECTED 不复活；uk(requirement_id) where IN_REVIEW）"
[[ -z "${REVIEW_A2}" ]] && pdr_abort "二次评审单未创建（reviewId 为空）"

pdr_login vendor2 >/dev/null 2>&1
submit_op "${REVIEW_A2}" "PASS" "RANGE" "非评审人越权提交 ${RTAG}"
NOT_REVIEWER_CODE="$(pdr_code)"
pdr_ck "A2-1" "$([[ "${OP_CODE}" == "403" ]] && echo 1 || echo 0)" \
    "HTTP=${OP_CODE} code=${NOT_REVIEWER_CODE:-<空>} body=$(pdr_snippet)" \
    "只有评审人（及发起人）能提交意见；非评审人 403（NOT_REVIEWER/NOT_ALLOWED）"

pdr_login tl1 >/dev/null 2>&1
make_pdf "${TMPDIR_PDR}/review-2mb.pdf" 2
printf 'pdr-review-%s\n' "${RTAG}" >>"${TMPDIR_PDR}/review-2mb.pdf"
PDF_SHA="$(sha_file "${TMPDIR_PDR}/review-2mb.pdf")"
PDF_BYTES="$(file_bytes "${TMPDIR_PDR}/review-2mb.pdf")"
pdr_upload "${TMPDIR_PDR}/review-2mb.pdf" "PDR_REVIEW" "${REVIEW_A2}" "application/pdf"
ATT_A="$(pdr_pick "${PDR_HTTP_BODY}" '.id' '.attachmentId' '.attachment.id')"
pdr_ck "A5-1" "$([[ "${PDR_HTTP_CODE}" == "201" && -n "${ATT_A}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} id=${ATT_A:-<空>} 字节=${PDF_BYTES} body=$(pdr_snippet)" \
    "评审人 IN_REVIEW 期间上传 2MB PDF 必须 201；若 500/413 先查 spring.servlet.multipart.max-file-size（仓库当前未配置，Boot 默认 1MB）"

pdr_get "/api/req/attachments?bizType=PDR_REVIEW&bizId=${REVIEW_A2}"
LIST_HAS="$([[ -n "${ATT_A}" ]] && pdr_has "${PDR_HTTP_BODY}" "\"id\":${ATT_A}" && echo 1 || echo 0)"
pdr_ck "A5-2" "$([[ "${PDR_HTTP_CODE}" == "200" && "${LIST_HAS}" == "1" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} 含 id=${ATT_A:-<空>} body=$(pdr_snippet)" \
    "GET /attachments?bizType=PDR_REVIEW&bizId={reviewId} 需列出评审附件"

pdr_download "${ATT_A}" "${TMPDIR_PDR}/downloaded.pdf"
DL_SHA="$(sha_file "${TMPDIR_PDR}/downloaded.pdf" 2>/dev/null || true)"
pdr_ck "A5-3" "$([[ "${PDR_HTTP_CODE}" == "200" && "${DL_SHA}" == "${PDF_SHA}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} 本地=${PDF_SHA} 下载=${DL_SHA:-<空>}" \
    "GET /attachments/{id}/download 必须回原字节（sha256 一致，不是重新编码）"

pdr_login vendor2 >/dev/null 2>&1
pdr_download "${ATT_A}" "${TMPDIR_PDR}/steal.pdf"
pdr_ck "B6-1" "$([[ "${PDR_HTTP_CODE}" == "404" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} body=$(pdr_snippet)" \
    "跨项目/跨厂商下载必须 404（不暴露存在性），不是 403/200"

pdr_login tl1 >/dev/null 2>&1
submit_op "${REVIEW_A2}" "PASS" "TECH" "tl1 通过 ${RTAG}"
pdr_login dev1 >/dev/null 2>&1
submit_op "${REVIEW_A2}" "PASS" "SCHEDULE" "dev1 通过 ${RTAG}"
pdr_login dev2 >/dev/null 2>&1
submit_op "${REVIEW_A2}" "PASS" "RISK" "dev2 通过 ${RTAG}"
pdr_login pm >/dev/null 2>&1
get_review "${PDR_A}"
REV_BODY="${REVIEW_BODY}"
ST="$(pdr_pick "${REV_BODY}" "${J_REVIEW_STATUS}")"
pdr_ck "A3-5" "$([[ "${ST}" == "IN_REVIEW" ]] && echo 1 || echo 0)" \
    "3/4 通过后 review.status=${ST:-<空>}" \
    "未全员通过不得放行：此时必须仍是 IN_REVIEW（提前 PASSED 会让 A3-3 的 409 失去意义）"

pdr_login pm >/dev/null 2>&1
submit_op "${REVIEW_A2}" "PASS" "ACCEPTANCE" "pm 通过 ${RTAG}"
get_review "${PDR_A}"
REV_BODY="${REVIEW_BODY}"
ST="$(pdr_pick "${REV_BODY}" "${J_REVIEW_STATUS}")"
pdr_ck "A3-6" "$([[ "${ST}" == "APPROVED" || "${ST}" == "PASSED" ]] && echo 1 || echo 0)" \
    "4/4 通过后 review.status=${ST:-<空>}" \
    "全员 PASS 后自动汇总（或 conclude）→ APPROVED（旧词 PASSED 亦接受），此时才允许 assign-vendor"

pdr_login tl1 >/dev/null 2>&1
pdr_upload "${TMPDIR_PDR}/review-2mb.pdf" "PDR_REVIEW" "${REVIEW_A2}" "application/pdf"
pdr_ck "B8-1" "$([[ "${PDR_HTTP_CODE}" == "409" && "$(pdr_code)" == "REVIEW_CLOSED" ]] && echo 1 || echo 0)" \
    "$(adetail)" "R7：评审结束后上传必须 409 REVIEW_CLOSED"

pdr_download "${ATT_A}" "${TMPDIR_PDR}/after-close.pdf"
DL_SHA2="$(sha_file "${TMPDIR_PDR}/after-close.pdf" 2>/dev/null || true)"
pdr_ck "B8-2" "$([[ "${PDR_HTTP_CODE}" == "200" && "${DL_SHA2}" == "${PDF_SHA}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} sha=${DL_SHA2:-<空>}" \
    "关闭只拦写不拦读：下载仍 200 且字节一致"

# --------------------------------------------------------------------------- B 组：厂商与附件

pdr_section "B 组：厂商工时评估与附件边界（B1–B9）"

pdr_login dev1 >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/estimate" \
    "$(jq -cn '{estimateDays:6,note:"PDR 门禁未指派厂商"}')"
pdr_ck "B1-1" "$([[ "${PDR_HTTP_CODE}" == "409" && "$(pdr_code)" == "VENDOR_NOT_ASSIGNED" ]] && echo 1 || echo 0)" \
    "$(adetail)" "R1：评审通过但未指派厂商 → 409 VENDOR_NOT_ASSIGNED"
REQ_REQUIRED="$(pdr_pick "${PDR_HTTP_BODY}" '.detail.required' '.required')"
pdr_ck "B1-2" "$([[ "${REQ_REQUIRED}" == "assign_vendor" ]] && echo 1 || echo 0)" \
    "detail.required=${REQ_REQUIRED:-<空>} body=$(pdr_snippet)" \
    "错误体 detail.required 固定为 assign_vendor（前端据此跳指派弹窗）"

# B1-1 用 dev1 触发厂商门（见上），assign-vendor 是 pm 的权限：这里必须切回来。
pdr_login pm >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/assign-vendor" \
    "$(jq -cn --argjson v "${VEN_A}" '{vendorId:$v}')"
NOREASON_CODE="$(pdr_code)"
pdr_ck "B9-1" "$(is4xx "${PDR_HTTP_CODE}" && [[ -n "${NOREASON_CODE}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} code=${NOREASON_CODE:-<空>} body=$(pdr_snippet)" \
    "R3 换厂商必须填原因：应 400/422 且业务码可读（期望 REASON_REQUIRED）"

pdr_post "/api/req/requirements/${PDR_A}/assign-vendor" \
    "$(jq -cn --argjson v "${VEN_A}" --arg r "PDR 门禁指派 VEN-A" '{vendorId:$v,reason:$r}')"
VENDOR_NOW="$(pdr_pick "${PDR_HTTP_BODY}" '.view.assigneeVendorId' '.assigneeVendorId' '.view.assignee_vendor_id')"
pdr_ck "B2-1" "$([[ "${PDR_HTTP_CODE}" == "200" && "${VENDOR_NOW}" == "${VEN_A}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} assigneeVendorId=${VENDOR_NOW:-<空>} 期望=${VEN_A} body=$(pdr_snippet)" \
    "POST /requirements/{id}/assign-vendor {vendorId,reason} → 200 且回写 assignee_vendor_id"

pdr_login pm >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/estimate" \
    "$(jq -cn '{estimateDays:6,note:"产品经理提交评估"}')"
pdr_ck "B2-2" "$([[ "${PDR_HTTP_CODE}" == "403" ]] && echo 1 || echo 0)" \
    "$(adetail)" "R4：厂商承接后产品经理不再拥有评估提交权（403）；内部 PMO/研发保留"

pdr_login vendor2 >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/estimate" \
    "$(jq -cn '{estimateDays:8,note:"别家厂商越权评估"}')"
pdr_ck "B2-3" "$([[ "${PDR_HTTP_CODE}" == "403" && "$(pdr_code)" == "OUT_OF_SCOPE" ]] && echo 1 || echo 0)" \
    "$(adetail)" "R4：requirement.assignee_vendor_id 必须等于登录账号 vendorId，否则 403 OUT_OF_SCOPE"

pdr_login vpm1 >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/estimate" \
    "$(jq -cn '{estimateDays:8,note:"VEN-A 厂商评估 PDR 门禁"}')"
EST_VENDOR="$(pdr_pick "${PDR_HTTP_BODY}" '.estimate.vendorId' '.vendorId' '.view.estimate.vendorId')"
pdr_ck "B2-4" "$([[ "${PDR_HTTP_CODE}" == "201" && "${EST_VENDOR}" == "${VEN_A}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} estimate.vendorId=${EST_VENDOR:-<空>} 期望=${VEN_A} body=$(pdr_snippet)" \
    "厂商账号提交评估 201，且评估单落 vendor_id（VEN-A）；vendorName 建议同时回填"

APPROVED_OK=0
if approve_estimate "${PDR_A}"; then APPROVED_OK=1; fi
pdr_ck "B2-5" "${APPROVED_OK}" \
    "estimate.status=$(estimate_status "${PDR_A}")（last $(adetail)）" \
    "pm 需能在 /approvals/todo 看到 WBS_ESTIMATE 待办并 APPROVE 到 APPROVED"

pdr_login pm >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/assign-vendor" \
    "$(jq -cn --argjson v "${VEN_B}" --arg r "PDR 门禁换厂商 VEN-A→VEN-B" '{vendorId:$v,reason:$r}')"
VENDOR_NOW="$(pdr_pick "${PDR_HTTP_BODY}" '.view.assigneeVendorId' '.assigneeVendorId' '.view.assignee_vendor_id')"
pdr_ck "B3-1" "$([[ "${PDR_HTTP_CODE}" == "200" && "${VENDOR_NOW}" == "${VEN_B}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} assigneeVendorId=${VENDOR_NOW:-<空>} 期望=${VEN_B} body=$(pdr_snippet)" \
    "R3：RD 未进 IN_DEV 前允许换厂商，200 且回写新厂商"

EST_ST="$(estimate_status "${PDR_A}")"
EST_REASON="$(estimate_field "${PDR_A}" reason)"
pdr_ck "B3-2" "$([[ "${EST_ST}" == "STALE" && "${EST_REASON}" == "VENDOR_CHANGED" ]] && echo 1 || echo 0)" \
    "estimate.status=${EST_ST:-<空>} reason=${EST_REASON:-<空>}" \
    "R3：换厂商作废已通过评估 → STALE + reason=VENDOR_CHANGED（回到未评估态可重评）"

pdr_post "/api/req/requirements/${PDR_A}/actions" '{"action":"schedule"}'
pdr_ck "B3-3" "$([[ "${PDR_HTTP_CODE}" == "409" && "$(pdr_code)" == "ESTIMATE_NOT_APPROVED" ]] && echo 1 || echo 0)" \
    "$(adetail)" "换厂商后 STALE 评估不得放行排期：409 ESTIMATE_NOT_APPROVED（detail.required=ESTIMATE_APPROVED）"

pdr_login vendor2 >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/estimate" \
    "$(jq -cn '{estimateDays:9,note:"VEN-B 重新评估"}')"
pdr_ck "B3-4" "$([[ "${PDR_HTTP_CODE}" == "201" ]] && echo 1 || echo 0)" \
    "$(adetail)" "换厂商后新厂商（vendor2/VEN-B）必须能重新提交评估 → 201"

pdr_login pm >/dev/null 2>&1
APPROVED_OK2=0
if approve_estimate "${PDR_A}"; then APPROVED_OK2=1; fi
pdr_ck "B3-5" "${APPROVED_OK2}" \
    "estimate.status=$(estimate_status "${PDR_A}")" "新评估需审批到 APPROVED 才能排期"

pdr_post "/api/req/requirements/${PDR_A}/actions" '{"action":"schedule"}'
SCHED_CODE="${PDR_HTTP_CODE}"
SCHED_BODY="${PDR_HTTP_BODY}"
req_get "${PDR_A}"
SCHED_STATUS="${REQ_STATUS}"
pdr_ck "B3-6" "$([[ "${SCHED_CODE}" == "200" && "${SCHED_STATUS}" == "SCHEDULED" ]] && echo 1 || echo 0)" \
    "HTTP=${SCHED_CODE} status=${SCHED_STATUS:-<空>} body=$(pdr_snippet "${SCHED_BODY}")" \
    "APPROVED 评估 + schedule → 200 且 PDR=SCHEDULED"

AUD_VENDOR="$(sql_num "SELECT COUNT(*) FROM audit_log WHERE tenant_id=${TENANT_ID} AND biz_id=${PDR_A} AND action LIKE '%VENDOR_CHANGED%' AND created_at >= '${RUN_START}'")"
pdr_ck "B5-1" "$([[ "${AUD_VENDOR}" -ge 1 ]] && echo 1 || echo 0)" \
    "audit_log VENDOR_CHANGED=${AUD_VENDOR}（biz_id=${PDR_A}, since ${RUN_START}）" \
    "R6：换厂商必须写审计 action=VENDOR_CHANGED、biz_type=REQUIREMENT、biz_id=需求 id"

NOTIFY_N="$(sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${TENANT_ID} AND biz_id IN (${PDR_A},${REVIEW_A2}) AND created_at >= '${RUN_START}'")"
pdr_ck "B5-2" "$([[ "${NOTIFY_N}" -ge 1 ]] && echo 1 || echo 0)" \
    "notification=${NOTIFY_N}（biz_id in {${PDR_A},${REVIEW_A2}}）" \
    "R3：换厂商要通知新旧厂商（notification 至少 1 条；类型码契约未写死）"

pdr_login pm >/dev/null 2>&1
start_review "${PDR_F}" "null" "PDR 门禁附件边界评审 ${RTAG}"
REVIEW_F="${STARTED_REVIEW_ID}"
if [[ -z "${REVIEW_F}" ]]; then pdr_abort "附件边界评审单未创建（reviewerIds=[] 回退规则不可用）"; fi
make_pdf "${TMPDIR_PDR}/huge.pdf" 25
HUG_BYTES="$(file_bytes "${TMPDIR_PDR}/huge.pdf")"
pdr_upload "${TMPDIR_PDR}/huge.pdf" "PDR_REVIEW" "${REVIEW_F}" "application/pdf"
pdr_ck "B7-1" "$([[ "${PDR_HTTP_CODE}" == "413" && "$(pdr_code)" == "FILE_TOO_LARGE" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} code=$(pdr_code) 字节=${HUG_BYTES} body=$(pdr_snippet)" \
    "20MB 上限 → 413 FILE_TOO_LARGE；若 500/连接被重置，先配 spring.servlet.multipart.max-file-size≥25MB 并把 MaxUploadSizeExceededException 映射成 413"
printf 'MZ pdr gate fixture %s\n' "${RTAG}" >"${TMPDIR_PDR}/evil.exe"
pdr_upload "${TMPDIR_PDR}/evil.exe" "PDR_REVIEW" "${REVIEW_F}" "application/octet-stream"
pdr_ck "B7-2" "$([[ "${PDR_HTTP_CODE}" == "415" && "$(pdr_code)" == "UNSUPPORTED_MEDIA_TYPE" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} code=$(pdr_code) body=$(pdr_snippet)" \
    "白名单 pdf/docx/xlsx/png/jpg/zip/csv/md；.exe → 415 UNSUPPORTED_MEDIA_TYPE"

pdr_post "/api/req/requirements/${PDR_A}/decompose" \
    "$(jq -cn --arg t "PDR门禁拆解 RD ${RTAG}" --argjson d "${DOMAIN_ID}" '{title:$t,domainIds:[$d],priority:"P1"}')"
RD_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.id' '.view.id')"
RD_STATUS=""
if [[ -n "${RD_ID}" ]]; then
    sql "UPDATE requirement SET status='IN_DEV' WHERE tenant_id=${TENANT_ID} AND id=${RD_ID}" >/dev/null
    RD_STATUS="$(sql "SELECT status FROM requirement WHERE tenant_id=${TENANT_ID} AND id=${RD_ID}")"
fi
pdr_ck "B4-1" "$([[ -n "${RD_ID}" && "${RD_STATUS}" == "IN_DEV" ]] && echo 1 || echo 0)" \
    "rdId=${RD_ID:-<空>} RD.status=${RD_STATUS:-<空>} decompose HTTP=${PDR_HTTP_CODE} body=$(pdr_snippet)" \
    "PDR 拆解出 RD（POST /requirements/{id}/decompose）；本门禁按既有 editability 先例用 db_q 把 RD 置 IN_DEV 作为夹具"

pdr_login pm >/dev/null 2>&1
pdr_post "/api/req/requirements/${PDR_A}/assign-vendor" \
    "$(jq -cn --argjson v "${VEN_A}" --arg r "PDR 门禁 RD 已开发中再换厂商" '{vendorId:$v,reason:$r}')"
pdr_ck "B4-2" "$([[ "${PDR_HTTP_CODE}" == "409" && "$(pdr_code)" == "VENDOR_LOCKED" ]] && echo 1 || echo 0)" \
    "$(adetail)" "R3：RD 已 IN_DEV（或 PDR IN_DEV）后换厂商必须 409 VENDOR_LOCKED"

# --------------------------------------------------------------------------- C 组：分类统计

pdr_section "C 组：意见分类统计（C1–C4）"

# A1-4：空评审人 → 回退 PM+域负责人（用 PDR_B；PDR_D 的评审单要给 C2/C4 用，
# 回退常常只剩 pm 一人，单人 PASS 就会自动收口，C2 的 6 条意见会被 409 REVIEW_CLOSED 挡住）。
pdr_login pm >/dev/null 2>&1
start_review "${PDR_B}" "null" "PDR 门禁回退评审（空评审人） ${RTAG}"
REVIEW_FALLBACK="${STARTED_REVIEW_ID}"
FALLBACK="$(pdr_pick "${PDR_HTTP_BODY}" '.fallback' '.review.fallback' '.detail.fallback')"
pdr_ck "A1-4" "$([[ -n "${REVIEW_FALLBACK}" ]] && is2xx "${START_REVIEW_CODE}" && [[ "${FALLBACK}" == "true" ]] && echo 1 || echo 0)" \
    "reviewId=${REVIEW_FALLBACK:-<空>} fallback=${FALLBACK:-<空>} HTTP=${START_REVIEW_CODE} body=$(pdr_snippet)" \
    "评审人为空时回退 PM+域负责人，响应 fallback:true（契约 B5）"
[[ -z "${REVIEW_FALLBACK}" ]] && pdr_abort "回退评审单未创建（fallback 规则不可用）"

# C 组统计评审单：显式 2 位评审人 —— 同一人多条意见（C2/D1-1）时不能让评审单中途自动收口。
pdr_login pm >/dev/null 2>&1
start_review "${PDR_D}" "[${TL1_ID},${DEV1_ID}]" "PDR 门禁统计评审 ${RTAG}"
REVIEW_D="${STARTED_REVIEW_ID}"
[[ -z "${REVIEW_D}" ]] && pdr_abort "统计评审单未创建（reviewerIds=[tl1,dev1]）"

submit_op "${REVIEW_D}" "PASS" "" "缺分类应 400 ${RTAG}"
pdr_ck "C1-1" "$([[ "${OP_CODE}" == "400" && "$(pdr_code)" == "INVALID_CATEGORY" ]] && echo 1 || echo 0)" \
    "HTTP=${OP_CODE} code=$(pdr_code) body=$(pdr_snippet)" \
    "R2：category 必填，缺失 → 400 INVALID_CATEGORY（不是 500/NOT NULL 泄露）"

submit_op "${REVIEW_D}" "PASS" "FOO" "非法分类应 400 ${RTAG}"
ALLOWED_JSON="$(pdr_pick "${PDR_HTTP_BODY}" '.detail.allowed' '.allowed')"
ALLOWED_LEN="$(printf '%s' "${ALLOWED_JSON}" | jq -r 'if type=="array" then length else 0 end' 2>/dev/null || echo 0)"
ALLOWED_HIT=1
for cat in ${ALLOWED_CATEGORIES}; do
    if ! pdr_has "${ALLOWED_JSON}" "\"${cat}\""; then ALLOWED_HIT=0; fi
done
pdr_ck "C1-2" "$([[ "${OP_CODE}" == "400" && "$(pdr_code)" == "INVALID_CATEGORY" && "${ALLOWED_LEN}" == "9" && "${ALLOWED_HIT}" == "1" ]] && echo 1 || echo 0)" \
    "HTTP=${OP_CODE} code=$(pdr_code) allowed=${ALLOWED_JSON:-<空>} len=${ALLOWED_LEN}" \
    "detail.allowed 必须是 9 类：${ALLOWED_CATEGORIES}"

C2_OK=0
for spec in "RANGE:1" "RANGE:2" "RANGE:3" "TECH:1" "TECH:2" "ACCEPTANCE:1"; do
    cat="${spec%%:*}"
    submit_op "${REVIEW_D}" "PASS" "${cat}" "统计夹具 ${cat} ${RTAG}"
    if [[ "${OP_CODE}" == "201" || "${OP_CODE}" == "200" ]]; then C2_OK=$((C2_OK + 1)); fi
done
pdr_ck "C2-1" "$([[ "${C2_OK}" == "6" ]] && echo 1 || echo 0)" \
    "成功 ${C2_OK}/6（最后一次 $(adetail)）" \
    "同一评审单可多条意见：3 RANGE + 2 TECH + 1 ACCEPTANCE"

pdr_get "/api/req/pdr-reviews/stats?domainId=${DOMAIN2_ID}&groupBy=CATEGORY"
STATS_BODY="${PDR_HTTP_BODY}"
STATS_CODE="${PDR_HTTP_CODE}"
STATS_RAW_TSV="$(stats_rows_tsv "${STATS_BODY}")"
STATS_TSV="$(printf '%s\n' "${STATS_RAW_TSV}" | awk -F'\t' 'NF>=2 {print $1"\t"$2}' | LC_ALL=C sort)"
get_review "${PDR_D}"
DETAIL_TSV="$(detail_pairs_tsv "${REVIEW_BODY}" "${J_OPINIONS}" | LC_ALL=C sort)"
pdr_ck "C2-2" "$([[ "${STATS_CODE}" == "200" && -n "${STATS_TSV}" && "${STATS_TSV}" == "${DETAIL_TSV}" ]] && echo 1 || echo 0)" \
    "stats(HTTP ${STATS_CODE})=[$(printf '%s' "${STATS_TSV}" | tr '\n' ';')] 明细=[$(printf '%s' "${DETAIL_TSV}" | tr '\n' ';')] body=$(pdr_snippet "${STATS_BODY}")" \
    "stats 按 groupBy=CATEGORY 的每类计数必须等于明细逐分类聚合；stats 形状契约未写死（脚本容忍 items/byCategory/groups/数组或映射）"

RATIO_SUM="$(printf '%s\n' "${STATS_RAW_TSV}" | awk -F'\t' 'NF>=3 {s+=$3} END {printf "%.4f", s+0}')"
PERCENT_SUM="$(printf '%s' "${RATIO_SUM}" | awk '{v=$1; if (v<=1.5) v=v*100; printf "%.2f", v}')"
pdr_ck "C2-3" "$(awk -v v="${PERCENT_SUM}" 'BEGIN {d=v-100; if (d<0) d=-d; exit !(d<=0.6)}' && echo 1 || echo 0)" \
    "占比合计=${PERCENT_SUM}%（ratio 列合计=${RATIO_SUM}）body=$(pdr_snippet "${STATS_BODY}")" \
    "占比合计 100%（±0.6）；ratio 允许 0–1 小数或 0–100 百分数"

pdr_get /api/req/pdr-reviews/datasets
DS_BODY="${PDR_HTTP_BODY}"
DS_OBJ="$(printf '%s' "${DS_BODY}" | jq -c '[.. | objects | select(((.code // .id // "")|tostring)=="pdr_review_opinion")][0] // empty' 2>/dev/null || true)"
DIM_CODE="$(printf '%s' "${DS_OBJ}" | jq -r '[.dimensions[]? | if type=="object" then (.code // .key // "") else . end | select(test("categor";"i"))][0] // empty' 2>/dev/null || true)"
MEAS_CODE="$(printf '%s' "${DS_OBJ}" | jq -r '[.measures[]? | if type=="object" then (.code // .key // "") else . end | select(test("count|total|opinion";"i"))][0] // empty' 2>/dev/null || true)"
pdr_ck "C3-1" "$([[ "${PDR_HTTP_CODE}" == "200" && -n "${DS_OBJ}" && -n "${DIM_CODE}" && -n "${MEAS_CODE}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} dataset=$(printf '%s' "${DS_OBJ}" | cut -c 1-200) dim=${DIM_CODE:-缺} measure=${MEAS_CODE:-缺}" \
    "GET /pdr-reviews/datasets 必须含 pdr_review_opinion，且有 category 维度与 count/opinion 度量"

# deliver 侧是否已把该数据集注册进 /reports 目录（C3 交叉表的入口）
pdr_get /api/deliver/reports/datasets
DELIVER_DS_BODY="${PDR_HTTP_BODY}"
DS_PRESENT="$(printf '%s' "${DELIVER_DS_BODY}" | jq -r '[.. | objects | select(((.code // .id // "")|tostring)=="pdr_review_opinion")] | length' 2>/dev/null || echo 0)"
C3_OK=0
C3_DETAIL=""
if [[ -n "${DIM_CODE}" && -n "${MEAS_CODE}" ]]; then
    if [[ "${DS_PRESENT}" -lt 1 ]]; then
        C3_DETAIL="deliver /reports/datasets 未注册 pdr_review_opinion（命中 0）：交叉表建不起来，需 deliver AnalyticsDatasets 补注册后重跑；req 侧数据集=$(printf '%s' "${DS_OBJ}" | cut -c 1-160)"
    else
        # report.action.define：PLATFORM_ADMIN/QA ALLOW，PRODUCT(pm) DENY —— 用 admin 建报告
        pdr_login_admin >/dev/null 2>&1
        pdr_post /api/deliver/reports "$(jq -cn --arg n "PDR门禁统计-${RTAG}" --arg ds "pdr_review_opinion" \
            --arg dim "${DIM_CODE}" --arg m "${MEAS_CODE}" \
            '{name:$n,datasetId:$ds,definition:{datasetId:$ds,rows:[$dim],columns:[],measures:[$m],filters:[]},chartType:"TABLE"}')"
        REPORT_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.id' '.reportId' '.report.id')"
        if [[ -n "${REPORT_ID}" ]]; then
            pdr_post "/api/deliver/reports/${REPORT_ID}/run" "$(jq -cn --argjson d "${DOMAIN2_ID}" '{domainId:$d}')"
            XTAB_TSV="$(printf '%s' "${PDR_HTTP_BODY}" | jq -r '
                [.rows[]? | [((.key // .rowCode // "")|tostring),
                             (((.counts // .values // [])[0]) // "")]]
                | .[] | @tsv' 2>/dev/null | LC_ALL=C sort)"
            C3_DETAIL="dim=${DIM_CODE} measure=${MEAS_CODE} report=${REPORT_ID} crossTab=[$(printf '%s' "${XTAB_TSV}" | tr '\n' ';')]"
            if [[ -n "${XTAB_TSV}" && "${XTAB_TSV}" == "${DETAIL_TSV}" ]]; then C3_OK=1; fi
        else
            C3_DETAIL="建报告失败 HTTP=${PDR_HTTP_CODE} body=$(pdr_snippet)"
        fi
    fi
else
    C3_DETAIL="数据集元信息缺 category 维度或 count 度量：$(pdr_snippet "${DS_BODY}")"
fi
pdr_ck "C3-2" "${C3_OK}" "${C3_DETAIL}" \
    "POST /reports + POST /reports/{id}/run 的交叉表行计数必须与 stats 逐分类相等（同一数据两个出口）"

# C4：VEN-A 可见 6 条，全量 7 条（再建一条 VEN-B 的意见）
pdr_login pm >/dev/null 2>&1
ensure_closed "${PDR_D}" "${REVIEW_D}"
D_CLOSED="${CLOSED_STATUS}"
pdr_post "/api/req/requirements/${PDR_D}/assign-vendor" \
    "$(jq -cn --argjson v "${VEN_A}" --arg r "统计需求指派 VEN-A" '{vendorId:$v,reason:$r}')"
D_ASSIGN_CODE="${PDR_HTTP_CODE}"
create_req PDR "PDR门禁-统计对照-${RTAG}" "${BUS2_ID}" "" "${DOMAIN2_ID}"
PDR_H="${CREATED_REQ_ID}"
start_review "${PDR_H}" "null" "统计对照评审 ${RTAG}"
REVIEW_H="${STARTED_REVIEW_ID}"
submit_op "${REVIEW_H}" "PASS" "OTHER" "对照意见 ${RTAG}"
ensure_closed "${PDR_H}" "${REVIEW_H}"
H_CLOSED="${CLOSED_STATUS}"
pdr_post "/api/req/requirements/${PDR_H}/assign-vendor" \
    "$(jq -cn --argjson v "${VEN_B}" --arg r "统计对照指派 VEN-B" '{vendorId:$v,reason:$r}')"
H_ASSIGN_CODE="${PDR_HTTP_CODE}"

pdr_login vpm1 >/dev/null 2>&1
pdr_get "/api/req/pdr-reviews/stats?domainId=${DOMAIN2_ID}&groupBy=CATEGORY"
V_STATS_BODY="${PDR_HTTP_BODY}"
V_STATS_CODE="${PDR_HTTP_CODE}"
V_TOTAL="$(stats_total "${V_STATS_BODY}")"
pdr_ck "C4-1" "$([[ "${V_STATS_CODE}" == "200" && "${V_TOTAL}" == "6" ]] && echo 1 || echo 0)" \
    "HTTP=${V_STATS_CODE} 厂商可见合计=${V_TOTAL} 期望=6（PDR_D review=${D_CLOSED} assign=${D_ASSIGN_CODE}；PDR_H review=${H_CLOSED} assign=${H_ASSIGN_CODE}）body=$(pdr_snippet "${V_STATS_BODY}")" \
    "R4 口径：VEN-A 账号只应统计 VEN-A 承接需求的意见（PDR_D 6 条）；不等于全量 7 条"

pdr_login pm >/dev/null 2>&1
pdr_get "/api/req/pdr-reviews/stats?domainId=${DOMAIN2_ID}&groupBy=CATEGORY"
FULL_TOTAL="$(stats_total "${PDR_HTTP_BODY}")"
pdr_ck "C4-2" "$([[ "${FULL_TOTAL}" == "7" && "${V_TOTAL}" -lt "${FULL_TOTAL}" ]] && echo 1 || echo 0)" \
    "全量=${FULL_TOTAL} 厂商=${V_TOTAL}（期望 7 与 6）" \
    "对照需求（VEN-B）必须让全量 > 厂商可见，证明 stats 做了厂商范围过滤而不是全租户直出"

# --------------------------------------------------------------------------- D 组：意见转任务

pdr_section "D 组：多条意见与意见转任务（D1–D4）"

pdr_login pm >/dev/null 2>&1
start_review "${PDR_E}" "[${TL1_ID},${DEV1_ID}]" "PDR 门禁转任务评审 ${RTAG}"
REVIEW_E="${STARTED_REVIEW_ID}"
[[ -z "${REVIEW_E}" ]] && pdr_abort "转任务评审单未创建（reviewerIds=[tl1,dev1]）"

pdr_login tl1 >/dev/null 2>&1
submit_op "${REVIEW_E}" "PASS" "RANGE" "tl1 第 1 条 ${RTAG}"; OP1="${SUBMITTED_OP_ID}"
submit_op "${REVIEW_E}" "PASS" "TECH" "tl1 第 2 条 ${RTAG}"; OP2="${SUBMITTED_OP_ID}"
submit_op "${REVIEW_E}" "PASS" "RISK" "tl1 第 3 条 ${RTAG}"; OP3="${SUBMITTED_OP_ID}"
get_review "${PDR_E}"
REV_E_BODY="${REVIEW_BODY}"
SEQ_OK="$(pdr_json "${REV_E_BODY}" "[${J_OPINIONS}[] | select((.reviewerId|tostring)==\"${TL1_ID}\") | .seq] | sort | join(\",\")")"
PO1_OPS="$(pdr_json "${REV_E_BODY}" "[${J_OPINIONS}[] | select((.reviewerId|tostring)==\"${TL1_ID}\")] | length")"
pdr_ck "D1-1" "$([[ "${PO1_OPS}" == "3" && "${SEQ_OK}" == "1,2,3" ]] && echo 1 || echo 0)" \
    "同一评审人意见=${PO1_OPS} 条 seq=[${SEQ_OK}]（opinionIds=${OP1:-空},${OP2:-空},${OP3:-空}）" \
    "R8：同一评审人可多条意见，seq 自增 1/2/3；不允许 (review_id,reviewer_id) 唯一约束吞掉后两条"

pdr_login dev1 >/dev/null 2>&1
submit_op "${REVIEW_E}" "NEED_CHANGE" "TECH" "dev1 需修改 ${RTAG}"
pdr_login pm >/dev/null 2>&1
get_review "${PDR_E}"
REV_E_BODY="${REVIEW_BODY}"
E_ST="$(pdr_pick "${REV_E_BODY}" "${J_REVIEW_STATUS}")"
pdr_ck "D1-2" "$([[ "${E_ST}" == "REJECTED" ]] && echo 1 || echo 0)" \
    "review.status=${E_ST:-<空>}（3 PASS + 1 NEED_CHANGE）" \
    "结论不按意见条数放大：同一人 3 条 PASS 不能压过另一人 1 条 NEED_CHANGE"

pdr_get "/api/req/requirements/${PDR_E}/pdr-review/opinions?reviewerId=${TL1_ID}&page=1&size=50"
FILTER_LEN="$(pdr_len "${PDR_HTTP_BODY}" '(if type=="array" then . else (.items // .opinions // []) end)')"
pdr_ck "D1-3" "$([[ "${PDR_HTTP_CODE}" == "200" && "${FILTER_LEN}" == "3" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} reviewerId 过滤条数=${FILTER_LEN} body=$(pdr_snippet)" \
    "GET /requirements/{id}/pdr-review/opinions 必须支持 reviewerId/category/status/page/size"

pdr_login tl1 >/dev/null 2>&1
pdr_post "/api/req/pdr-reviews/${REVIEW_E}/opinions/${OP1}/to-task" '{}'
TASK_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.taskId' '.task.id')"
TASK_CODE="$(pdr_pick "${PDR_HTTP_BODY}" '.taskCode' '.code' '.task.code')"
pdr_ck "D2-1" "$([[ "${PDR_HTTP_CODE}" == "201" && -n "${TASK_ID}" && -n "${TASK_CODE}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} taskId=${TASK_ID:-<空>} taskCode=${TASK_CODE:-<空>} body=$(pdr_snippet)" \
    "POST /pdr-reviews/{reviewId}/opinions/{opinionId}/to-task → 201 {taskId,taskCode}（跨服务建任务）"

pdr_get "/api/req/requirements/${PDR_E}/pdr-review"
OP1_ROW="$(printf '%s' "${PDR_HTTP_BODY}" | jq -c "(${J_OPINIONS}) | .[] | select((.id|tostring)==\"${OP1}\")" 2>/dev/null || true)"
OP1_STATUS="$(printf '%s' "${OP1_ROW}" | jq -r '.status // empty' 2>/dev/null || true)"
OP1_TASK="$(printf '%s' "${OP1_ROW}" | jq -r '.taskId // .task_id // empty' 2>/dev/null || true)"
pdr_ck "D2-2" "$([[ "${OP1_STATUS}" == "TASKED" && "${OP1_TASK}" == "${TASK_ID}" ]] && echo 1 || echo 0)" \
    "opinion.status=${OP1_STATUS:-<空>} task_id=${OP1_TASK:-<空>} 期望 TASKED/${TASK_ID}" \
    "建任务与回写 task_id 同一事务：意见状态 TASKED + task_id=返回的 taskId"

pdr_get "/api/deliver/requirements/${PDR_E}/tasks"
if [[ "${PDR_HTTP_CODE}" != "200" ]]; then
    pdr_get "/api/deliver/tasks?requirementId=${PDR_E}&page=1&size=200"
fi
TASK_VIS="$(pdr_has "${PDR_HTTP_BODY}" "\"id\":${TASK_ID}" && echo 1 || echo 0)"
pdr_ck "D2-3" "$([[ "${PDR_HTTP_CODE}" == "200" && "${TASK_VIS}" == "1" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} 含 taskId=${TASK_ID:-<空>} body=$(pdr_snippet)" \
    "转出的任务必须挂在该需求下可见（deliver task.requirement_id = PDR id）"

AUD_AFTER="$(sql_num "SELECT COUNT(*) FROM audit_log WHERE tenant_id=${TENANT_ID} AND action LIKE '%PDR_OPINION_TO_TASK%' AND created_at >= '${RUN_START}'")"
pdr_ck "D2-4" "$([[ "${AUD_AFTER}" -ge 1 ]] && echo 1 || echo 0)" \
    "audit_log PDR_OPINION_TO_TASK=${AUD_AFTER}（since ${RUN_START}，biz_id 口径契约未写死）" \
    "R6：转任务写审计 PDR_OPINION_TO_TASK（至少 1 条，biz_id 可为意见/任务/需求）"

TASK_CNT_1="$(sql_num "SELECT COUNT(*) FROM task WHERE tenant_id=${TENANT_ID} AND requirement_id=${PDR_E}")"
pdr_post "/api/req/pdr-reviews/${REVIEW_E}/opinions/${OP1}/to-task" '{}'
DUP_CODE="$(pdr_code)"
DUP_TASK="$(pdr_pick "${PDR_HTTP_BODY}" '.detail.taskId' '.taskId')"
pdr_ck "D3-1" "$([[ "${PDR_HTTP_CODE}" == "409" && "${DUP_CODE}" == "OPINION_ALREADY_TASKED" && "${DUP_TASK}" == "${TASK_ID}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} code=${DUP_CODE:-<空>} detail.taskId=${DUP_TASK:-<空>} 期望=${TASK_ID}" \
    "R8：重复转任务 409 OPINION_ALREADY_TASKED，detail.taskId 指回原任务（幂等提示而非重复建单）"

TASK_CNT_2="$(sql_num "SELECT COUNT(*) FROM task WHERE tenant_id=${TENANT_ID} AND requirement_id=${PDR_E}")"
pdr_ck "D3-2" "$([[ "${TASK_CNT_2}" == "${TASK_CNT_1}" ]] && echo 1 || echo 0)" \
    "task 行数 before=${TASK_CNT_1} after=${TASK_CNT_2}" \
    "重复转任务不得新增 task 行（同一事务/唯一约束兜底）"

pdr_login vendor2 >/dev/null 2>&1
pdr_post "/api/req/pdr-reviews/${REVIEW_E}/opinions/${OP2}/to-task" '{}'
pdr_ck "D4-1" "$([[ "${PDR_HTTP_CODE}" == "403" ]] && echo 1 || echo 0)" \
    "$(adetail)" "R8：仅评审人/产品经理可转任务，其他人 403 NOT_ALLOWED"

pdr_login pm >/dev/null 2>&1
ensure_closed "${PDR_E}" "${REVIEW_E}"
E_CLOSED="${CLOSED_STATUS}"
pdr_post "/api/req/pdr-reviews/${REVIEW_E}/opinions" \
    "$(jq -cn '{decision:"PASS",category:"OTHER",comment:"关闭后新增应 409"}')"
pdr_ck "D4-2" "$([[ "${E_CLOSED}" == "REJECTED" || "${E_CLOSED}" == "PASSED" ]] && [[ "${PDR_HTTP_CODE}" == "409" && "$(pdr_code)" == "REVIEW_CLOSED" ]] && echo 1 || echo 0)" \
    "review.status=${E_CLOSED:-<空>} 新增意见 HTTP=${PDR_HTTP_CODE} code=$(pdr_code) body=$(pdr_snippet)" \
    "R7/R8：评审关闭后新增/修改意见 409 REVIEW_CLOSED（关闭状态需先由 conclude/自动汇总落定）"

pdr_post "/api/req/pdr-reviews/${REVIEW_E}/opinions/${OP2}/to-task" '{}'
TASK_ID2="$(pdr_pick "${PDR_HTTP_BODY}" '.taskId' '.task.id')"
pdr_ck "D4-3" "$([[ "${PDR_HTTP_CODE}" == "201" && -n "${TASK_ID2}" ]] && echo 1 || echo 0)" \
    "HTTP=${PDR_HTTP_CODE} taskId=${TASK_ID2:-<空>} body=$(pdr_snippet)" \
    "关闭后仍允许把未转任务的意见转任务（PM 可转）→ 201"

# --------------------------------------------------------------------------- 收口

pdr_section "收口"
pdr_info "本轮夹具：domain=${DOMAIN_CODE}(${DOMAIN_ID})/${DOMAIN2_CODE}(${DOMAIN2_ID}) project=${PROJECT_CODE} bus=${BUS_ID}/${BUS2_ID}"
pdr_info "PDR A=${PDR_A} B=${PDR_B} D=${PDR_D} H=${PDR_H:-未建} E=${PDR_E} F=${PDR_F} review A2=${REVIEW_A2}"
pdr_info "附件 ${ATT_A:-无} 任务 ${TASK_ID:-无}（审计/通知按 since ${RUN_START} 过滤，不依赖上一次运行）"
teardown_fixture
pdr_summary
