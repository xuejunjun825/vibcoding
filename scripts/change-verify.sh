#!/usr/bin/env bash
# change-verify.sh — r40 需求变更单闭环门禁（AC-19）。
#
# 40 条断言，六组：
#   S1 迁移与结构（5）        V16 在 flyway 历史且 V1–V15 各一条成功、req_change 列齐全、
#                            req_change_item 主键 = (change_id, field)、两表索引存在、起跑 req_change = 0 行
#   S2 申请与影响（8）        preview 200（7 键 before/after + 8 键 impact + fields）且不落库、
#                            非白名单 400 CHANGE_FIELD_NOT_ALLOWED、空原因 400 REASON_REQUIRED、
#                            无变化 400 VALIDATION_FAILED、创建 201 PENDING + 审批实例/待办、
#                            CHANGE_REQUESTED 审计 1 条、两张 PENDING 并存（列表 total）
#   S3 审批映射（6）          两级通过 → APPROVED 且需求 7 字段逐字段不变、通过不写字段事件、
#                            驳回 → REJECTED、撤回 → WITHDRAWN、撤回后 apply 409 CHANGE_NOT_APPROVED
#   S4 应用正负（8）          apply 200 APPLIED + items == 变更字段数、需求字段生效 + applied_at/by、
#                            审计恰 1 条 + title 字段事件恰 1 条、重复 apply 409 CHANGE_ALREADY_APPLIED、
#                            同字段旧快照 409 CHANGE_CONFLICT 且零写、不同字段先后成功、items 与审计一一对应
#   S5 人天与锁（7）          恰好 +20% 不 STALE、+20.1% STALE、预测(冻结 impact) == 实际(评估状态)、
#                            LOCKED 版本 apply 200 + CHANGE_AFTER_RELEASE_LOCK 恰 1 条、PLAN 不写锁审计、
#                            晚期态 PUT 四类字段 409 CHANGE_REQUIRED（detail.changeEndpoint/fields）
#   S6 收口与审计查询（6）    早期态 PUT 不误拦、列表分页/过滤/total、详情 before/after/items/impact 形状、
#                            厂商 VENDOR_PM 发起变更 403、按 changeId 查审计 ≥2 条、
#                            退出复位成功、复位后本轮 tag 需求 0 条且 req_change 0 行
#
# 用法：
#   bash scripts/change-verify.sh                # 全量（需 make platform-up 已起栈）
#   bash scripts/change-verify.sh --once         # 同上（供 --twice 内部调用）
#   bash scripts/change-verify.sh --twice        # 连跑两次并断言两次结果一致（AC-19「两次一致」）
#   bash scripts/change-verify.sh --static-only  # 不连栈，只查脚本/文档/计数自洽（12 条）
#
# 约定（与 release-flow-verify / wbs-backfill-verify 一致）：
#   - 脚本自声明 CHANGE_VERIFY_TOTAL=42；静态检查会用 grep 数 `ck "` 调用点，声明与实现不符即红，
#     所以「少跑一条断言」不可能悄悄变绿；
#   - 前置缺失（栈没起、V16 没跑、变更接口 404）直接 die，不 skip（r28 教训：条件跳过=假绿）；
#   - 本轮所有夹具标题带 RTAG，退出复位交给 scripts/lib/gate-fixture.sh（demo-reset + 基线断言），
#     复位失败或 tag 残留一律置红，绝不用 WARN 降级；
#   - 自动化规则引擎的 run/通知口径由 scripts/automation-verify.sh（r39）覆盖；本门禁只断言
#     「apply 派生字段事件」这一段接缝（FIELD_CHANGE 条数），不重复造规则夹具。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
# shellcheck source=lib/gate-fixture.sh
source "${ROOT}/scripts/lib/gate-fixture.sh"
set +e

CHANGE_VERIFY_TOTAL=42
CASE=0
PASS=0
FAIL=0
STATIC_ONLY=0
MODE="full"
case "${1:-}" in
    --static-only) STATIC_ONLY=1 ;;
    --twice) MODE="twice" ;;
    --once) MODE="once" ;;
    --help | -h)
        sed -n '2,/^set -uo pipefail$/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
        exit 0
        ;;
esac

REPORT_DIR="${RUN_DIR:-${ROOT}/.run}/change"
FAIL_LOG=""
RTAG="${CHANGE_VERIFY_TAG:-$(date +%m%d%H%M%S)-$$}"
T1_TENANT="${T1_TENANT:-t1}"
GATE_FIXTURE_TENANT_CODE="$T1_TENANT"
[[ "${CHANGE_VERIFY_SKIP_RESET:-0}" == "1" ]] && GATE_FIXTURE_SKIP_TEARDOWN=1
ADMIN_USER="admin"
ADMIN_PASS="Admin@12345"
USER_PASS="Passw0rd!"
USERS="pm tl1 vendor1 mgr1 admin qa1 po1"
ADMIN_TOKEN=""
ADMIN_TENANT=""
TID=""
DOMAIN_ID=""

# 夹具需求（标题带 RTAG，退出复位后必须一条不剩）
F_EARLY=""   # BUS → SUBMITTED（早期态）：PUT 不误拦 + 变更主链
F_LATE=""    # BUS → POOLED（晚期态）：PUT 必须 409
F_DAYS=""    # RD + 已批评估 10.00：人天阈值
F_LOCK=""    # BUS → SUBMITTED，挂到 LOCKED 版本
F_REL=""     # 自造版本（LOCKED）
C_TITLE=""   # F_EARLY 的 CONTENT(title) 单：S3 通过 → S4 应用
C_TITLE_AFTER="" # 该单的 after.title（应用后比对）
C_REJECT=""  # F_EARLY 的 CONTENT(priority) 单：S3 驳回
C_WITHDRAW="" # F_EARLY 的 CONTENT(description) 单：S3 撤回，S3 末尾 apply 409
C_PRIO_A=""  # F_EARLY 的 CONTENT(priority) 单 A：S4 先应用
C_PRIO_B=""  # F_EARLY 的 CONTENT(priority) 单 B：S4 旧快照冲突
C_DESC=""    # F_EARLY 的 CONTENT(description) 单：S4 不同字段先后成功
C_DAYS20=""  # F_DAYS 的 DAYS 单：恰好 +20%
C_DAYS201="" # F_DAYS 的 DAYS 单：+20.1%
C_LOCK=""    # F_LOCK 的 CONTENT 单：LOCKED 版本下应用

section() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
ck() { # ck <0|1> <pass-msg> <fail-msg> <fix>
    local ok="${1:-0}" pmsg="${2:-}" fmsg="${3:-}" fix="${4:-}"
    CASE=$((CASE + 1))
    if [[ "$ok" == "1" ]]; then
        PASS=$((PASS + 1))
        printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$pmsg"
    else
        FAIL=$((FAIL + 1))
        printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$fmsg"
        printf '        修复: %s\n' "$fix"
        if [[ -n "${FAIL_LOG:-}" ]]; then printf '[%02d] %s | 修复: %s\n' "$CASE" "$fmsg" "$fix" >>"$FAIL_LOG"; fi
    fi
    return 0
}
jqv() { printf '%s' "${2:-}" | jq -r "$1" 2>/dev/null; }
jlen() { printf '%s' "${1:-}" | jq -r 'if type=="array" or type=="object" then length else 0 end' 2>/dev/null; }
needle() { [[ "${1:-}" == *"${2:-__nope__}"* ]]; }

# --------------------------------------------------------------------------- 身份与夹具

as_user() { # as_user <name>
    if [[ "$1" == "admin" ]]; then
        platform_login "$T1_TENANT" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null 2>&1
    else
        platform_login "$T1_TENANT" "$1" "$USER_PASS" >/dev/null 2>&1
    fi
}
as_admin() {
    API_TOKEN="$ADMIN_TOKEN"
    API_TENANT="$ADMIN_TENANT"
}
todo_task() { # todo_task <bizId> <bizType> → 第一条待办 taskId
    api_call GET '/api/req/approvals/todo?page=1&size=200'
    jqv "[.items[]? | select(.bizId == $1 and .bizType == \"$2\")][0].taskId // empty" "$HTTP_BODY"
}
approve_biz() { # approve_biz <bizId> <bizType> [maxPasses] — 逐轮把所有待办同意完（两级串行要跨节点）
    local biz="$1" type="$2" max="${3:-6}" p u t list n total=0
    for ((p = 0; p < max; p++)); do
        n=0
        for u in $USERS; do
            as_user "$u" || continue
            api_call GET '/api/req/approvals/todo?page=1&size=200'
            list="$(jqv ".items[]? | select(.bizId == ${biz} and .bizType == \"${type}\") | .taskId" "$HTTP_BODY")"
            [[ -z "$list" ]] && continue
            while IFS= read -r t; do
                [[ -z "$t" ]] && continue
                api_call POST "/api/req/approvals/tasks/${t}/actions" '{"action":"APPROVE","comment":"r40 变更单门禁-同意"}'
                if [[ "$HTTP_CODE" == "200" ]]; then n=$((n + 1)); total=$((total + 1)); fi
            done <<<"$list"
        done
        ((n == 0)) && break
    done
    as_admin
    printf '%s' "$total"
}
reject_biz() { # reject_biz <user> <bizId> <bizType> <reason>
    as_user "$1" || return 1
    local t
    t="$(todo_task "$2" "$3")"
    [[ -z "$t" ]] && { as_admin; return 1; }
    api_call POST "/api/req/approvals/tasks/${t}/actions" \
        "$(jq -cn --arg c "$4" '{action:"REJECT",comment:$c}')"
    as_admin
    [[ "$HTTP_CODE" == "200" ]]
}
create_req() { # create_req <type> <title> [parentId] [estimateDays] → id
    local type="$1" title="$2" parent="${3:-}" est="${4:-}" body
    body="$(jq -cn --arg t "$type" --arg ti "$title" --arg p "$parent" --arg e "$est" \
        --argjson d "$DOMAIN_ID" \
        '{type:$t,title:$ti,priority:"P1",domainIds:[$d],expectedDeliveryDate:"2026-12-31"}
         + (if $p != "" then {parentId:($p|tonumber)} else {} end)
         + (if $e != "" then {estimateDays:($e|tonumber)} else {} end)')"
    api_call POST /api/req/requirements "$body"
    jqv '.id // .view.id // empty' "$HTTP_BODY"
}
act() { # act <reqId> <action> [extraJson]
    local extra="${3:-}"
    if [[ -n "$extra" ]]; then
        api_call POST "/api/req/requirements/$1/actions" \
            "$(jq -cn --arg a "$2" --argjson e "$extra" '{action:$a} + $e')"
    else
        api_call POST "/api/req/requirements/$1/actions" "$(jq -cn --arg a "$2" '{action:$a}')"
    fi
}
approve_estimate() { # approve_estimate <reqId> <days>：提交评估并让审批人同意到 APPROVED
    local rid="$1" days="$2" task
    api_call POST "/api/req/requirements/${rid}/estimate" \
        "$(jq -cn --argjson d "$days" '{estimateDays:$d,note:"r40 变更单门禁"}')"
    [[ "$HTTP_CODE" == "201" ]] || return 1
    as_user pm || return 1
    for _ in 1 2 3 4 5; do
        task="$(todo_task "$rid" WBS_ESTIMATE)"
        [[ -z "$task" ]] && break
        api_call POST "/api/req/approvals/tasks/${task}/actions" '{"action":"APPROVE","comment":"r40 工时评估"}'
        [[ "$HTTP_CODE" == "200" ]] || break
    done
    as_admin
    api_call GET "/api/req/requirements/${rid}"
    [[ "$(jqv '.estimate.status' "$HTTP_BODY")" == "APPROVED" ]]
}
create_release() { # create_release <name> [level] → id
    local name="$1" level="${2:-NORMAL}" body
    body="$(jq -cn --arg n "$name" --arg l "$level" --argjson d "$DOMAIN_ID" \
        '{name:$n,domainId:$d,startDate:"2026-11-01",endDate:"2026-11-30",
          plannedReleaseDate:"2026-12-05",gateMode:"STRICT",releaseLevel:$l}')"
    api_call POST /api/req/releases "$body"
    jqv '.id // .release.id // empty' "$HTTP_BODY"
}

# 变更单 API 薄封装（契约见 TECHNICAL §4.1；代码由 T1–T3 提供）
chg_create() { # chg_create <reqId> <type> <reason> <patchJson> → 设 CC/ CB
    api_call POST "/api/req/requirements/$1/changes" \
        "$(jq -cn --arg t "$2" --arg r "$3" --argjson p "$4" '{changeType:$t,reason:$r,patch:$p}')"
    CC="$HTTP_CODE"
    CB="$HTTP_BODY"
}
chg_preview() {
    api_call POST "/api/req/requirements/$1/changes/preview" \
        "$(jq -cn --arg t "$2" --arg r "$3" --argjson p "$4" '{changeType:$t,reason:$r,patch:$p}')"
    CC="$HTTP_CODE"
    CB="$HTTP_BODY"
}
chg_apply() {
    api_call POST "/api/req/changes/$1/apply"
    CC="$HTTP_CODE"
    CB="$HTTP_BODY"
}
chg_get() {
    api_call GET "/api/req/changes/$1"
    CC="$HTTP_CODE"
    CB="$HTTP_BODY"
}
err_code() { jqv '.code // empty' "${1:-$CB}"; }
err_field() { jqv '.detail.field // empty' "${1:-$CB}"; }
req_title() { api_call GET "/api/req/requirements/$1"; jqv '.view.title // empty' "$HTTP_BODY"; }
req_status() { api_call GET "/api/req/requirements/$1"; jqv '.view.status // empty' "$HTTP_BODY"; }
chg_status() { chg_get "$1"; jqv '.status // empty' "$CB"; }

# --------------------------------------------------------------------------- 静态检查（免栈）

STATIC_TOTAL=12
SCASE=0
SPASS=0
sck() {
    local ok="$1"
    SCASE=$((SCASE + 1))
    if [[ "$ok" == "1" ]]; then
        SPASS=$((SPASS + 1))
        printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$SCASE" "$2"
    else
        printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$SCASE" "$3"
        printf '        修复: %s\n' "$4"
    fi
}

static_checks() {
    local self="${BASH_SOURCE[0]}" sites
    sites="$(grep -cE '^[[:space:]]*ck "' "$self")"
    sck "$([[ "$sites" == "$CHANGE_VERIFY_TOTAL" ]] && echo 1 || echo 0)" \
        "自声明断言数 ${CHANGE_VERIFY_TOTAL} == 实际 ck 调用点 ${sites}（断言数不可能漂移）" \
        "自声明 ${CHANGE_VERIFY_TOTAL} ≠ 实际 ck 调用点 ${sites}" \
        "改了断言就同步 CHANGE_VERIFY_TOTAL；这是「少跑一条也绿」的唯一防线"

    sck "$(bash -n "$self" >/dev/null 2>&1 && echo 1 || echo 0)" \
        "bash -n 语法检查通过" "bash -n 报语法错" "先修语法，再谈断言"

    sck "$(grep -q 'gate_fixture_begin' "$self" && grep -q 'gate_fixture_end' "$self" && echo 1 || echo 0)" \
        "接入共享夹具钩子（gate_fixture_begin / gate_fixture_end）" \
        "未接入 scripts/lib/gate-fixture.sh" \
        "门禁必须用共享钩子复位并断言基线干净；自带 cleanup 会静默降级成 WARN"

    sck "$(grep -q '^change-verify:' "${ROOT}/Makefile" && grep -q 'scripts/change-verify.sh' "${ROOT}/Makefile" && echo 1 || echo 0)" \
        "Makefile 有 change-verify 薄目标并调用本脚本" \
        "Makefile 缺 change-verify 目标（或内联了逻辑）" \
        "Makefile 只做薄封装：change-verify: @./scripts/change-verify.sh"

    local in_order=0
    if grep -q '需求变更单（change-verify）' "${ROOT}/scripts/test-all.sh"; then
        local pv cv
        pv="$(grep -n 'platform-verify' "${ROOT}/scripts/test-all.sh" | head -n1 | cut -d: -f1)"
        cv="$(grep -n '需求变更单（change-verify）' "${ROOT}/scripts/test-all.sh" | head -n1 | cut -d: -f1)"
        [[ -n "$pv" && -n "$cv" && "$cv" -gt "$pv" ]] && in_order=1
    fi
    sck "$in_order" \
        "test-all 在 platform-verify 之后接入 change-verify" \
        "test-all 未接入，或接在 platform-verify 之前" \
        "栈与种子在 platform-verify 之后才稳定；顺序反了会在半成品库上跑变更闭环"

    sck "$([[ -f "${ROOT}/docs/teamflow/CHANGE-REQUEST-FLOW.md" ]] && echo 1 || echo 0)" \
        "docs/teamflow/CHANGE-REQUEST-FLOW.md 存在（AC-21）" \
        "缺少变更单流程文档" "补 申请→影响→审批→应用→基线 五段 + 状态机 + 错误码表 + 角色矩阵"

    sck "$(grep -q 'CHANGE_REQUIRED' "${ROOT}/docs/teamflow/CHANGE-REQUEST-FLOW.md" 2>/dev/null && grep -q 'CHANGE_CONFLICT' "${ROOT}/docs/teamflow/CHANGE-REQUEST-FLOW.md" 2>/dev/null && echo 1 || echo 0)" \
        "流程文档含错误码表（CHANGE_REQUIRED / CHANGE_CONFLICT 等）" \
        "流程文档缺错误码表" "错误码是前端/QA 的共同契约，必须逐条列出 http 状态 + detail 形状"

    sck "$(grep -q 'CHANGE-REQUEST-FLOW\|变更单' "${ROOT}/docs/teamflow/REQUIREMENT-FLOW.md" 2>/dev/null && echo 1 || echo 0)" \
        "REQUIREMENT-FLOW.md 指向变更子流程（读者能找到入口）" \
        "主流程没有变更入口" "在 PUT 收口分支补「409 CHANGE_REQUIRED → 变更单」并互链 CHANGE-REQUEST-FLOW.md"

    sck "$(grep -q 'r40\|20261123-r40' "${ROOT}/docs/teamflow/DELIVERED-MODULES.md" 2>/dev/null && grep -q 'req_change' "${ROOT}/docs/teamflow/DELIVERED-MODULES.md" 2>/dev/null && echo 1 || echo 0)" \
        "DELIVERED-MODULES.md 补了 r40 行（含 req_change 两表）" \
        "交付台账缺 r40" "台账是验收的对照物：模块/接口/表/脚本/门禁都要能对上"

    local counts_bad=()
    grep -q 'sys_action = 75' "${ROOT}/scripts/tests/rbac/s0-schema-migration.sh" || counts_bad+=("s0 动作 75")
    grep -q '1903' "${ROOT}/scripts/tests/rbac/s0-schema-migration.sh" || counts_bad+=("s0 1903")
    grep -q '825' "${ROOT}/scripts/tests/rbac/s2-seed-equivalence.sh" || counts_bad+=("s2 825")
    grep -q '173' "${ROOT}/scripts/tests/rbac/s7-negative-nine.sh" || counts_bad+=("s7 173")
    if (( ${#counts_bad[@]} == 0 )); then
        sck 1 "RBAC 计数迁移到 75/173/1903/825（s0/s2/s7 均已同步）" "" ""
    else
        sck 0 "" "计数迁移缺项：$(printf '%s ' "${counts_bad[@]}")" \
            "两个动作码使 73→75、171→173、1881→1903、803→825；四处必须同源"
    fi

    sck "$(grep -q 'Flyway 最新成功版本 = 16' "${ROOT}/scripts/tests/rbac/s0-schema-migration.sh" && echo 1 || echo 0)" \
        "s0 的 Flyway 版本断言已到 16（V16 是本轮唯一新迁移）" \
        "s0 仍断言 15" "V16__requirement_change.sql 落地后，最高成功版本必须是 16"

    sck "$(grep -qE '>>?[^&|]*docs/teamflow' "$self" 2>/dev/null && echo 0 || echo 1)" \
        "脚本不往 docs/teamflow 写文件（门禁只读产品文档，只做存在性/内容断言）" \
        "脚本把 docs/teamflow 当写入目标" "门禁产出的文档会污染交付物；文档只能由文档任务写，日志只能进 RUN_DIR"
}

# --------------------------------------------------------------------------- 主流程

run_main() {
    local any_req="" probe
    section "前置检查（缺一项直接 die，不 skip）"
    need_cmd curl || die "缺少 curl"
    need_cmd jq || die "缺少 jq"
    need_cmd mysql || die "缺少 mysql 客户端（db_q 依赖它）"
    req_ready || die "req-service 未就绪：${REQ_URL}/internal/health —— 先 make platform-up"
    db_up || die "MySQL 未就绪：${PLATFORM_DB_URL} —— 先 make platform-up"

    TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${T1_TENANT}' LIMIT 1")"
    [[ -n "$TID" ]] || die "租户 ${T1_TENANT} 不存在 —— 先 make platform-seed"
    DOMAIN_ID="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
    [[ -n "$DOMAIN_ID" ]] || die "租户 ${TID} 没有业务域 —— 先 make platform-demo-reset"

    gate_fixture_begin || die "gate-fixture 起跑失败（见上方 [gate] 输出）"
    platform_login "$T1_TENANT" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null 2>&1 \
        || die "admin 登录失败 —— 检查种子账号 ${ADMIN_USER}/${ADMIN_PASS}"
    ADMIN_TOKEN="$API_TOKEN"
    ADMIN_TENANT="$API_TENANT"

    local t16
    t16="$(db_count information_schema.TABLES "TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('req_change','req_change_item')")"
    [[ "$t16" == "2" ]] || die "缺表 req_change / req_change_item（命中 ${t16:-0} 张）：V16 未执行 —— make platform-down && make platform-up"

    local codes
    codes="$(db_count sys_action "code IN ('req.action.change_request','req.action.change_apply')")"
    [[ "$codes" == "2" ]] || die "sys_action 缺变更动作码（命中 ${codes:-0}/2）：ActionRegistry 未登记 —— 重启 req-service 让 RbacSeeder 重灌"

    any_req="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
    [[ -n "$any_req" ]] || die "租户下没有任何需求 —— 先 make platform-demo-reset"
    api_call GET "/api/req/requirements/${any_req}/changes?page=1&size=5"
    probe="$HTTP_CODE"
    [[ "$probe" != "404" && "$probe" != "405" ]] \
        || die "GET /requirements/{id}/changes 返回 ${probe}：运行中的 req-service 还是 r40 之前的构建 —— make platform-down && make platform-up"
    [[ "$probe" == "200" ]] || die "GET /requirements/{id}/changes 返回 ${probe}：${HTTP_BODY:0:200}"

    mkdir -p "$REPORT_DIR"
    FAIL_LOG="${REPORT_DIR}/failures-${RTAG}.txt"
    : >"$FAIL_LOG"

    # ---------------------------------------------------------------- S1 迁移与结构
    section "S1 迁移与结构（V16 + 两表 + 索引 + 干净起跑）"
    local v16 v1_15 req_rows cols pk idx_c idx_i
    v16="$(db_count flyway_schema_history "version='16' AND success=1")"
    v1_15="$(db_q "SELECT COUNT(*) FROM (
              SELECT version FROM flyway_schema_history
               WHERE success=1 AND version REGEXP '^[0-9]+\$' AND CAST(version AS UNSIGNED) BETWEEN 1 AND 15
               GROUP BY version) x")"
    ck "$([[ "$v16" == "1" && "$v1_15" == "15" ]] && echo 1 || echo 0)" \
        "V16 已成功执行（1 条），V1–V15 各恰好 1 条成功记录（校验和未被改写）" \
        "迁移历史不符：V16=${v16:-?} / V1–V15 唯一版本数=${v1_15:-?}（期望 1 / 15）" \
        "V16__requirement_change.sql 未成功执行，或旧迁移被改写（Flyway validate 会拒绝）；先 make platform-up 看首个 ERROR"

    cols="$(db_q "SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='req_change'
                     AND COLUMN_NAME IN ('id','tenant_id','requirement_id','change_type','status','reason',
                                         'before_json','after_json','impact_json','instance_id',
                                         'applied_at','applied_by','created_by','created_at')")"
    ck "$([[ "${cols:-0}" -ge 14 ]] && echo 1 || echo 0)" \
        "req_change 必需列齐全（命中 ${cols:-0}/14：状态/原因/两份快照/impact/审批实例/apply 元数据）" \
        "req_change 列不全（命中 ${cols:-0}/14）" \
        "AC-1 要求 before/after/impact 与状态持久化；缺列会让 apply 只能写半张单"

    pk="$(db_q "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='req_change_item' AND INDEX_NAME='PRIMARY'")"
    ck "$([[ "$pk" == "change_id,field" ]] && echo 1 || echo 0)" \
        "req_change_item 主键 = (change_id, field)（同一字段只能一行，主键即幂等约束）" \
        "req_change_item 主键不是 (change_id, field)：${pk:-<缺失>}" \
        "复合主键让重复写字段从「靠代码判断」变成「数据库拒绝」，apply 重跑才会真的幂等"

    idx_c="$(db_q "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='req_change' AND INDEX_NAME<>'PRIMARY'")"
    idx_i="$(db_q "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='req_change_item'
                      AND COLUMN_NAME='change_id' AND SEQ_IN_INDEX=1")"
    ck "$([[ "${idx_c:-0}" -ge 1 && "${idx_i:-0}" -ge 1 ]] && echo 1 || echo 0)" \
        "两表各有查询索引（req_change 非主键索引 ${idx_c:-0} 个，req_change_item 有 change_id 前导索引）" \
        "索引缺失：req_change=${idx_c:-0}，req_change_item.change_id=${idx_i:-0}" \
        "列表按 (tenant_id, requirement_id, status) 走索引，items 按 change_id 读取；缺索引在演示库看不出来，量起来就全表扫"

    req_rows="$(db_count req_change "tenant_id=${TID}")"
    ck "$([[ "${req_rows:-x}" == "0" ]] && echo 1 || echo 0)" \
        "起跑基线 req_change = 0 行（本段之后的所有行都是本轮造的）" \
        "req_change 已有 ${req_rows:-?} 行：上一个门禁没复位（或手工造的单没清）" \
        "先 make platform-demo-reset；门禁不允许在别人的残留数据上判绿"

    # ---------------------------------------------------------------- S2 申请与影响
    section "S2 申请与影响评估（冻结 7 字段 + 8 键 impact）"
    # BUS 创建即 SUBMITTED（StateTables: BUS initial=SUBMITTED），SUBMITTED 本身就是早期态；
    # 再 act submit 会推到 APPROVING，后面的「早期态 PUT 200」就变成对晚期态的误判。
    F_EARLY="$(create_req BUS "r40-早期BUS-${RTAG}")"
    [[ -n "$F_EARLY" ]] || die "创建夹具 BUS 失败：${HTTP_BODY:0:300}"
    [[ "$(req_status "$F_EARLY")" == "SUBMITTED" ]] || die "夹具 BUS ${F_EARLY} 未到 SUBMITTED（早期态 PUT 与收口断言都依赖它）：${HTTP_BODY:0:200}"

    F_LATE="$(create_req BUS "r40-晚期BUS-${RTAG}")"
    [[ -n "$F_LATE" ]] || die "创建夹具 BUS(晚期) 失败"
    act "$F_LATE" submit
    approve_biz "$F_LATE" REQ_SUBMIT >/dev/null
    act "$F_LATE" review_start '{"comment":"r40 变更单门禁评审"}'
    approve_biz "$F_LATE" REQ_REVIEW >/dev/null
    [[ "$(req_status "$F_LATE")" == "POOLED" ]] || die "夹具 BUS(晚期) 未到 POOLED（实际 $(req_status "$F_LATE")）：审批定义/审批人缺失"

    # 人天夹具必须是有父链的叶子 RD：RD 无父 → 400 INVALID_PARENT；BUS/PDR 的人天由子需求汇总
    # → 400 PARENT_DAYS_READONLY（变更单也不例外）。链路：POOLED BUS → PDR → schedule → RD。
    F_DAYS_BUS="$(create_req BUS "r40-人天BUS-${RTAG}")"
    [[ -n "$F_DAYS_BUS" ]] || die "创建人天夹具 BUS 失败"
    act "$F_DAYS_BUS" submit
    approve_biz "$F_DAYS_BUS" REQ_SUBMIT >/dev/null
    act "$F_DAYS_BUS" review_start '{"comment":"r40 人天夹具评审"}'
    approve_biz "$F_DAYS_BUS" REQ_REVIEW >/dev/null
    [[ "$(req_status "$F_DAYS_BUS")" == "POOLED" ]] || die "人天夹具 BUS 未到 POOLED（实际 $(req_status "$F_DAYS_BUS")）"

    F_DAYS_PDR="$(create_req PDR "r40-人天PDR-${RTAG}" "$F_DAYS_BUS")"
    [[ -n "$F_DAYS_PDR" ]] || die "创建人天夹具 PDR 失败：${HTTP_BODY:0:200}"
    approve_estimate "$F_DAYS_PDR" 10 || die "夹具 PDR ${F_DAYS_PDR} 的 10.00 评估未到 APPROVED"
    act "$F_DAYS_PDR" schedule
    [[ "$(req_status "$F_DAYS_PDR")" == "SCHEDULED" ]] || die "人天夹具 PDR 未到 SCHEDULED（实际 $(req_status "$F_DAYS_PDR")）"

    F_DAYS="$(create_req RD "r40-人天RD-${RTAG}" "$F_DAYS_PDR")"
    [[ -n "$F_DAYS" ]] || die "创建夹具 RD 失败：${HTTP_BODY:0:200}"
    approve_estimate "$F_DAYS" 10 || die "夹具 RD ${F_DAYS} 的 10.00 评估未到 APPROVED：WBS 评估审批链缺失"

    local before_title n0 pv_before_keys pv_after_keys pv_impact_keys pv_fields n1
    before_title="$(req_title "$F_EARLY")"
    n0="$(db_count req_change "tenant_id=${TID}")"
    chg_preview "$F_EARLY" CONTENT "r40 预览" '{"title":"r40-预览标题"}'
    pv_before_keys="$(jlen "$(jqv '.before' "$CB")")"
    pv_after_keys="$(jlen "$(jqv '.after' "$CB")")"
    pv_impact_keys="$(jlen "$(jqv '.impact' "$CB")")"
    pv_fields="$(jqv '.fields | join(",")' "$CB")"
    ck "$([[ "$CC" == "200" && "$pv_before_keys" == "7" && "$pv_after_keys" == "7" && "$pv_impact_keys" == "8" && "$pv_fields" == "title" ]] && echo 1 || echo 0)" \
        "preview 200：before/after 各 7 键、impact 8 键、fields=[title]（第④步的真实预览，不伪造受影响发布单）" \
        "preview 形状不符：http=${CC} before=${pv_before_keys:-?} after=${pv_after_keys:-?} impact=${pv_impact_keys:-?} fields=${pv_fields:-?}" \
        "preview 必须与创建共用 validateCreate+assess：7 键 before/after、8 键 impact、fields 只列真实变化的字段"

    n1="$(db_count req_change "tenant_id=${TID}")"
    ck "$([[ "$n0" == "$n1" ]] && echo 1 || echo 0)" \
        "preview 不落库（req_change 仍 ${n1} 行）且不起审批" \
        "preview 写了库：${n0} → ${n1} 行" \
        "预览是只读路径；一落库就会在审批中心出现幽灵单据"

    chg_create "$F_EARLY" CONTENT "r40 非白名单" '{"status":"DONE"}'
    ck "$([[ "$CC" == "400" && "$(err_code)" == "CHANGE_FIELD_NOT_ALLOWED" && -n "$(jqv '.detail.allowedFields | length' "$CB")" ]] && echo 1 || echo 0)" \
        "非白名单字段 400 CHANGE_FIELD_NOT_ALLOWED 且 detail.allowedFields 非空（前端可直接渲染可改字段）" \
        "非白名单字段未按契约拒绝：http=${CC} code=$(err_code) allowedFields=$(jqv '.detail.allowedFields // "null" | tostring' "$CB")" \
        "白名单由 RequirementChangeFields.allowedFields(changeType) 单点判定，detail 必须回传 allowedFields"

    chg_create "$F_EARLY" CONTENT "" '{"title":"r40-空原因"}'
    ck "$([[ "$CC" == "400" && "$(err_code)" == "REASON_REQUIRED" ]] && echo 1 || echo 0)" \
        "原因为空 400 REASON_REQUIRED（变更单是留痕单据，原因不可省）" \
        "空原因未拒绝：http=${CC} code=$(err_code)" \
        "创建前校验 reason 非空且 ≤500；错误码固定 REASON_REQUIRED，别退化成正泽校验的 VALIDATION_FAILED"

    chg_create "$F_EARLY" CONTENT "r40 无变化" "$(jq -cn --arg t "$before_title" '{title:$t}')"
    ck "$([[ "$CC" == "400" && "$(err_code)" == "VALIDATION_FAILED" ]] && echo 1 || echo 0)" \
        "改动前后同值 400 VALIDATION_FAILED（无变化的单不该进审批）" \
        "同值改动未拒绝：http=${CC} code=$(err_code)" \
        "changedFields 为空即 400：空单会白占审批人时间，也会让 items=fields 的断言失去意义"

    chg_create "$F_EARLY" CONTENT "r40 标题变更" '{"title":"r40-标题-改后"}'
    C_TITLE="$(jqv '.id // empty' "$CB")"
    # 创建响应只带 id/status/instanceId/impact（不带 before/after）；after 就是刚提交的 patch 值。
    C_TITLE_AFTER="r40-标题-改后"
    ck "$([[ "$CC" == "201" && -n "$C_TITLE" && "$(jqv '.status' "$CB")" == "PENDING" && -n "$(jqv '.instanceId // empty' "$CB")" && "$(jlen "$(jqv '.impact' "$CB")")" == "8" ]] && echo 1 || echo 0)" \
        "创建 201：id=${C_TITLE:-?} status=PENDING、instanceId 非空、impact 8 键（申请即冻结影响快照）" \
        "创建响应不符：http=${CC} id=${C_TITLE:-?} status=$(jqv '.status // empty' "$CB") instance=$(jqv '.instanceId // empty' "$CB") impact=$(jlen "$(jqv '.impact' "$CB")")" \
        "创建事务里先插单再起审批，响应必须带回 instanceId 与冻结的 impact；缺一样前端就无法跳审批单"

    local wf_task pending_inst audit_created
    wf_task="$(db_count wf_task "tenant_id=${TID} AND instance_id=(SELECT id FROM wf_instance WHERE tenant_id=${TID} AND biz_type='REQ_CHANGE' AND biz_id=${C_TITLE:-0} LIMIT 1)")"
    pending_inst="$(db_count wf_instance "tenant_id=${TID} AND biz_type='REQ_CHANGE' AND biz_id=${C_TITLE:-0} AND status='PENDING'")"
    ck "$([[ "${pending_inst:-0}" == "1" && "${wf_task:-0}" -ge 1 ]] && echo 1 || echo 0)" \
        "审批实例落地：wf_instance(REQ_CHANGE, biz_id=${C_TITLE:-?}) PENDING 1 条 + 待办 ${wf_task:-0} 条" \
        "审批实例/待办不符：PENDING=${pending_inst:-0}（期望 1）待办=${wf_task:-0}（期望 ≥1）" \
        "第 6 类单据必须走既有引擎：默认 PRODUCT→DOMAIN_OWNER 两级；待办为空说明种子流程或审批人没落地"

    audit_created="$(db_count audit_log "tenant_id=${TID} AND action='CHANGE_REQUESTED' AND biz_type='REQ_CHANGE' AND biz_id=${C_TITLE:-0}")"
    ck "$([[ "${audit_created:-0}" == "1" ]] && echo 1 || echo 0)" \
        "创建写 1 条 CHANGE_REQUESTED 审计（biz_type=REQ_CHANGE, biz_id=${C_TITLE:-?}）" \
        "创建审计条数 ${audit_created:-0}（期望恰 1）" \
        "审计与单据同事务；多条说明重试没被条件更新拦住，0 条说明审计旁路没接上"

    local list_pending
    chg_create "$F_EARLY" CONTENT "r40 第二张 PENDING" '{"description":"r40-描述-待驳回"}'
    C_REJECT="$(jqv '.id // empty' "$CB")"
    api_call GET "/api/req/requirements/${F_EARLY}/changes?status=PENDING&page=1&size=50"
    list_pending="$(jqv '.total // 0' "$HTTP_BODY")"
    ck "$([[ -n "$C_REJECT" && "${list_pending:-0}" -ge 2 ]] && echo 1 || echo 0)" \
        "同一需求可并存多张 PENDING（列表 total=${list_pending:-0} ≥ 2，第二张 id=${C_REJECT:-?}）" \
        "第二张 PENDING 未并存：id=${C_REJECT:-?} total=${list_pending:-0}" \
        "变更单不是「编辑锁」：同时改不同字段的两张单应各自走审批；互斥只在 apply 用 before 比对判冲突"

    # ---------------------------------------------------------------- S3 审批映射
    section "S3 审批映射（通过 ≠ 生效）"
    local snap_before req_after_json applied_events field_events
    snap_before="$(jqv '.before' "$CB")"
    api_call GET "/api/req/requirements/${F_EARLY}"
    req_after_json="$(jqv '.view | {title,description,priority,expectedDeliveryDate,estimateDays,releaseId,parentId}' "$HTTP_BODY")"
    chg_get "$C_TITLE"
    snap_before="$(jqv '.before' "$CB")"

    approve_biz "$C_TITLE" REQ_CHANGE >/dev/null
    chg_get "$C_TITLE"
    ck "$([[ "$(jqv '.status // empty' "$CB")" == "APPROVED" ]] && echo 1 || echo 0)" \
        "两级审批通过后单据 status=APPROVED（通过只是「可以应用」，不是已生效）" \
        "审批通过后 status=$(jqv '.status // empty' "$CB")（期望 APPROVED）" \
        "回调只做 PENDING→APPROVED；若仍是 PENDING，看 ReqChangeCallback 是否登记到引擎回调表"

    api_call GET "/api/req/requirements/${F_EARLY}"
    local req_now
    req_now="$(jqv '.view | {title,description,priority,expectedDeliveryDate,estimateDays,releaseId,parentId}' "$HTTP_BODY")"
    ck "$([[ "$req_now" == "$req_after_json" ]] && echo 1 || echo 0)" \
        "审批通过后需求 7 个字段逐字段不变（需求仍是申请前的值）" \
        "审批通过却改了需求字段：before=${req_after_json:0:120} after=${req_now:0:120}" \
        "回调绝不能写需求字段：写字段只允许 apply 在行锁+条件更新里做，否则「审批通过=已生效」的误读成真"

    applied_events="$(db_count audit_log "tenant_id=${TID} AND action='REQUIREMENT_CHANGE_APPLIED' AND biz_type='REQ_CHANGE' AND biz_id=${C_TITLE}")"
    field_events="$(db_count automation_event "tenant_id=${TID} AND requirement_id=${F_EARLY} AND event_type='FIELD_CHANGE'")"
    ck "$([[ "${applied_events:-0}" == "0" && "${field_events:-0}" == "0" ]] && echo 1 || echo 0)" \
        "通过不写字段事件（REQUIREMENT_CHANGE_APPLIED=0、FIELD_CHANGE=0）" \
        "通过产生了写痕迹：APPLIED 审计 ${applied_events:-0} 条、FIELD_CHANGE ${field_events:-0} 条（期望 0/0）" \
        "审批回调若写字段/发事件，自动化规则会在「还没生效」时就触发，等于假生效"

    reject_biz pm "$C_REJECT" REQ_CHANGE "r40 驳回用例" \
        || reject_biz tl1 "$C_REJECT" REQ_CHANGE "r40 驳回用例" \
        || reject_biz mgr1 "$C_REJECT" REQ_CHANGE "r40 驳回用例"
    chg_get "$C_REJECT"
    local rej_status rej_reason
    rej_status="$(jqv '.status // empty' "$CB")"
    rej_reason="$(jqv '.reason // empty' "$CB")"
    ck "$([[ "$rej_status" == "REJECTED" && -n "$rej_reason" ]] && echo 1 || echo 0)" \
        "驳回 → status=REJECTED，原因可查（${rej_reason:0:40}）" \
        "驳回映射不符：status=${rej_status:-?} reason=${rej_reason:-<空>}" \
        "onRejected 只做 PENDING→REJECTED，且把审批意见写回 reason/审计；驳回绝不动需求字段"

    chg_create "$F_EARLY" CONTENT "r40 撤回用例" '{"description":"r40-描述-撤回"}'
    C_WITHDRAW="$(jqv '.id // empty' "$CB")"
    local inst_w
    inst_w="$(db_q "SELECT id FROM wf_instance WHERE tenant_id=${TID} AND biz_type='REQ_CHANGE' AND biz_id=${C_WITHDRAW:-0} LIMIT 1")"
    api_call POST "/api/req/approvals/instances/${inst_w:-0}/withdraw" '{"comment":"r40 撤回用例"}'
    local wd_code wd_status
    wd_code="$HTTP_CODE"
    chg_get "$C_WITHDRAW"
    wd_status="$(jqv '.status // empty' "$CB")"
    ck "$([[ ( "$wd_code" == "204" || "$wd_code" == "200" ) && "$wd_status" == "WITHDRAWN" ]] && echo 1 || echo 0)" \
        "撤回审批 → 单据 WITHDRAWN（http=${wd_code}，不再阻塞同一字段的新单）" \
        "撤回映射不符：http=${wd_code} status=${wd_status:-?}" \
        "onWithdrawn 只做 PENDING→WITHDRAWN；停在 PENDING 会让该字段永远「有单在途」"

    chg_apply "$C_WITHDRAW"
    ck "$([[ "$CC" == "409" && "$(err_code)" == "CHANGE_NOT_APPROVED" ]] && echo 1 || echo 0)" \
        "撤回后再 apply → 409 CHANGE_NOT_APPROVED 且 detail.status 带回（$(jqv '.detail.status // empty' "$CB")）" \
        "未审批的单可应用：http=${CC} code=$(err_code) detail=$(jqv '.detail // "null" | tostring' "$CB")" \
        "apply 第一道闸读单据状态；WITHDRAWN/REJECTED/PENDING 一律 409，detail.status 让前端说清卡在哪"

    # ---------------------------------------------------------------- S4 应用正负
    section "S4 应用（单事务 + 条件更新 + before 比对）"
    local items_n fields_n applied_at applied_by applied_audit title_events
    chg_apply "$C_TITLE"
    local c1_code="$CC" c1_status
    c1_status="$(jqv '.status // empty' "$CB")"
    items_n="$(jqv '.items | length' "$CB")"
    fields_n="$(jqv '.items | map(.field) | unique | length' "$CB")"
    ck "$([[ "$c1_code" == "200" && "$c1_status" == "APPLIED" && "${items_n:-0}" -ge 1 && "$items_n" == "$fields_n" ]] && echo 1 || echo 0)" \
        "apply 200 → APPLIED，items=${items_n:-0} 行（每个变更字段恰好一行）" \
        "apply 响应不符：http=${c1_code} status=${c1_status:-?} items=${items_n:-0} uniqueFields=${fields_n:-0}" \
        "items 是「改了什么」的证据：字段数必须等于行数，多行/少行都说明主键幂等没生效"

    local cur_title
    cur_title="$(req_title "$F_EARLY")"
    applied_at="$(db_q "SELECT applied_at FROM req_change WHERE id=${C_TITLE}")"
    applied_by="$(db_q "SELECT applied_by FROM req_change WHERE id=${C_TITLE}")"
    ck "$([[ "$cur_title" == "$C_TITLE_AFTER" && -n "$applied_at" && "$applied_at" != "NULL" && -n "$applied_by" && "$applied_by" != "NULL" ]] && echo 1 || echo 0)" \
        "应用生效：需求 title=${cur_title:-?} == after.title、applied_at/applied_by 已落（${applied_at:-?} / ${applied_by:-?}）" \
        "应用未生效或不完整：title=${cur_title:-?}（期望 ${C_TITLE_AFTER:-?}）applied_at=${applied_at:-?} applied_by=${applied_by:-?}" \
        "写字段与写单据元数据必须在同一事务：title 变了但 applied_at 空 = 半写，回滚会留下无法对账的单"

    applied_audit="$(db_count audit_log "tenant_id=${TID} AND action='REQUIREMENT_CHANGE_APPLIED' AND biz_type='REQ_CHANGE' AND biz_id=${C_TITLE}")"
    title_events="$(db_count automation_event "tenant_id=${TID} AND requirement_id=${F_EARLY} AND event_type='FIELD_CHANGE' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'\$.field'))='title'")"
    ck "$([[ "${applied_audit:-0}" == "1" && "${title_events:-0}" == "1" ]] && echo 1 || echo 0)" \
        "应用写 1 条 REQUIREMENT_CHANGE_APPLIED 审计 + 恰好 1 条 title 字段事件（mapper 派生，不逐字段写审计）" \
        "审计/事件条数不符：APPLIED=${applied_audit:-0}（期望 1）title FIELD_CHANGE=${title_events:-0}（期望 1）" \
        "apply 只写一条审计，字段事件由 AutomationEventMapper 派生；审计逐字段写会让「一次变更」变成 N 条记录"

    chg_apply "$C_TITLE"
    ck "$([[ "$CC" == "409" && "$(err_code)" == "CHANGE_ALREADY_APPLIED" && -n "$(jqv '.detail.appliedAt // empty' "$CB")" ]] && echo 1 || echo 0)" \
        "同单第二次 apply → 409 CHANGE_ALREADY_APPLIED + detail.appliedAt（重放不会二次写）" \
        "重复应用未拦：http=${CC} code=$(err_code) detail=$(jqv '.detail // "null" | tostring' "$CB")" \
        "状态闸在事务第一步；靠「已应用」状态拦下重放，而不是靠前端按钮变灰"

    # 同字段冲突：两张单都基于 priority=P1 的旧快照，A 先应用把值改掉，B 必须 409 且零写
    chg_create "$F_EARLY" CONTENT "r40 优先级A" '{"priority":"P2"}'
    C_PRIO_A="$(jqv '.id // empty' "$CB")"
    chg_create "$F_EARLY" CONTENT "r40 优先级B" '{"priority":"P3"}'
    C_PRIO_B="$(jqv '.id // empty' "$CB")"
    [[ -n "$C_PRIO_A" && -n "$C_PRIO_B" ]] || die "冲突夹具创建失败：A=${C_PRIO_A:-?} B=${C_PRIO_B:-?}"
    approve_biz "$C_PRIO_A" REQ_CHANGE >/dev/null
    approve_biz "$C_PRIO_B" REQ_CHANGE >/dev/null
    chg_apply "$C_PRIO_A"
    local a_code="$CC"
    chg_apply "$C_PRIO_B"
    local b_code="$CC" b_conflict_prio
    api_call GET "/api/req/requirements/${F_EARLY}"
    b_conflict_prio="$(jqv '.view.priority // empty' "$HTTP_BODY")"
    ck "$([[ "$a_code" == "200" && "$b_code" == "409" && "$(err_code)" == "CHANGE_CONFLICT" && "$(err_field)" == "priority" && "$b_conflict_prio" == "P2" ]] && echo 1 || echo 0)" \
        "同字段旧快照：A 应用 200，B 409 CHANGE_CONFLICT(field=priority) 且需求零写（priority=${b_conflict_prio:-?} 仍是 A 的 P2）" \
        "冲突判定不符：A=${a_code} B=${b_code} code=$(err_code) field=$(err_field) priority=${b_conflict_prio:-?}（期望 200/409/priority/P2）" \
        "apply 先比对 before 再写：冲突必须在写入前判出，且整事务回滚；detail.field 要指名是哪个字段旧了"

    chg_create "$F_EARLY" CONTENT "r40 描述变更" '{"description":"r40-描述-已应用"}'
    C_DESC="$(jqv '.id // empty' "$CB")"
    approve_biz "$C_DESC" REQ_CHANGE >/dev/null
    chg_apply "$C_DESC"
    local d_code="$CC" d_items
    d_items="$(jqv '.items | length' "$CB")"
    ck "$([[ "$d_code" == "200" && "${d_items:-0}" == "1" ]] && echo 1 || echo 0)" \
        "不同字段先后成功：描述变更 apply 200（items=1），B 的冲突不污染其它字段" \
        "不同字段被误判冲突：http=${d_code} code=$(err_code) items=${d_items:-0}" \
        "before 比对必须只针对本单声明的字段；全字段比对会让任何一次变更都无法连续应用"

    local item_rows applied_rows mismatch
    item_rows="$(db_count req_change_item "change_id=${C_TITLE}")"
    applied_rows="$(db_count audit_log "tenant_id=${TID} AND action='REQUIREMENT_CHANGE_APPLIED' AND biz_id=${C_TITLE} AND biz_type='REQ_CHANGE'")"
    mismatch=0
    [[ "${item_rows:-0}" == "1" && "${applied_rows:-0}" == "1" ]] || mismatch=1
    ck "$((1 - mismatch))" \
        "items 与审计一一对应：c${C_TITLE} 有 ${item_rows:-0} 行 items、${applied_rows:-0} 条 APPLIED 审计（1:1）" \
        "items/审计不配对：items=${item_rows:-0} audit=${applied_rows:-0}" \
        "一次 apply = 一条审计 + N 行 items；两者数量对不上，说明写 items 与写审计不在同一事务"

    # 并发 apply：同一张已通过的单被两个请求同时应用。行锁必须让一个 200、另一个 409，
    # 绝不能把 Hibernate 的 StaleObjectStateException 漏成 500（AC-7 的单事务/恰一次）。
    chg_create "$F_LATE" CONTENT "r40 并发应用" '{"title":"r40-并发后标题"}'
    local C_CONC conc_a conc_b conc_code_a conc_code_b conc_status
    C_CONC="$(jqv '.id // empty' "$CB")"
    [[ -n "$C_CONC" ]] || die "并发 apply 夹具创建失败"
    approve_biz "$C_CONC" REQ_CHANGE >/dev/null
    conc_a="$(mktemp)"; conc_b="$(mktemp)"
    ( api_call POST "/api/req/changes/${C_CONC}/apply" '{}'; printf '%s' "$HTTP_CODE" >"$conc_a" ) &
    ( api_call POST "/api/req/changes/${C_CONC}/apply" '{}'; printf '%s' "$HTTP_CODE" >"$conc_b" ) &
    wait
    conc_code_a="$(cat "$conc_a")"; conc_code_b="$(cat "$conc_b")"
    rm -f "$conc_a" "$conc_b"
    chg_get "$C_CONC"
    conc_status="$(jqv '.status // empty' "$CB")"
    ck "$([[ ( "$conc_code_a $conc_code_b" == "200 409" || "$conc_code_a $conc_code_b" == "409 200" ) && "$conc_status" == "APPLIED" ]] && echo 1 || echo 0)" \
        "并发 apply 恰一次生效：一个 200、另一个 409，无 500（codes=${conc_code_a}/${conc_code_b}，最终 ${conc_status:-?}）" \
        "并发 apply 不符：codes=${conc_code_a}/${conc_code_b} status=${conc_status:-?}（期望 200/409 且 APPLIED）" \
        "需求行锁必须先于任何实体加载：先 requireVisible 再 FOR UPDATE 会因持久化上下文里已有旧版本而抛 500"

    # ---------------------------------------------------------------- S5 人天与锁
    section "S5 人天阈值与版本锁（预测 = 实际）"
    local r20 r201 stale200 stale201 ratio200
    chg_create "$F_DAYS" DAYS "r40 恰好20%" '{"estimateDays":12.00}'
    C_DAYS20="$(jqv '.id // empty' "$CB")"
    ratio200="$(jqv '.impact.days.deltaRatio // empty' "$CB")"
    # 不要写 `// empty`：jq 把 false 也当空，恰好 +20% 的 false 会被读成空串。
    stale200="$(jqv '.impact.days.becomesStale' "$CB")"
    ck "$([[ -n "$C_DAYS20" && "$stale200" == "false" ]] && echo 1 || echo 0)" \
        "恰好 +20%（10.00→12.00，deltaRatio=${ratio200:-?}）不转 STALE：阈值是严格 > 0.20" \
        "恰好 20% 被判成 STALE：deltaRatio=${ratio200:-?} becomesStale=${stale200:-?}" \
        "判定必须用未舍入比值严格大于 0.20，且与 WbsEstimateService.wouldBeStale 同源；显示层才做 2 位小数"

    # 12.01 不是 0.5 的整数倍：必须在归一化（scale 到 1 位）之前拒绝，不能被静默舍成 12.0。
    chg_create "$F_DAYS" DAYS "r40 20.1%" '{"estimateDays":12.01}'
    local prec_code="$CC" prec_err
    prec_err="$(err_code)"
    ck "$([[ "$prec_code" == "400" && "$prec_err" == "INVALID_DAY_PRECISION" ]] && echo 1 || echo 0)" \
        "12.01 在 scale 前被判精度非法（400 INVALID_DAY_PRECISION），不静默变 12.0" \
        "12.01 未被拒：http=${prec_code} code=${prec_err:-?}（期望 400/INVALID_DAY_PRECISION）" \
        "先 scale 再校验会把 20.1% 抹成 20.0%：申请人写的 after 与实际落库值不同，阈值结论也跟着错"

    # 用可表示值验证严格 >20%：10→12.5 = +25%。
    chg_create "$F_DAYS" DAYS "r40 25%" '{"estimateDays":12.5}'
    C_DAYS25="$(jqv '.id // empty' "$CB")"
    stale25="$(jqv '.impact.days.becomesStale // empty' "$CB")"
    ck "$([[ -n "$C_DAYS25" && "$stale25" == "true" ]] && echo 1 || echo 0)" \
        "+25%（10.00→12.50）预测 becomesStale=true（分母是已批评估，不是提交时人天）" \
        "+25% 未判 STALE：becomesStale=${stale25:-?} delta=$(jqv '.impact.days.delta // empty' "$CB")" \
        "deltaRatio 分母必须是 approvedDays；用提交时人天做分母会让阈值随「上次改过多少」漂移"

    approve_biz "$C_DAYS25" REQ_CHANGE >/dev/null
    chg_apply "$C_DAYS25"
    local apply25="$CC" est_status="" frozen_stale
    api_call GET "/api/req/requirements/${F_DAYS}"
    est_status="$(jqv '.estimate.status // empty' "$HTTP_BODY")"
    frozen_stale="$stale25"
    ck "$([[ "$apply25" == "200" && "$est_status" == "STALE" && "$frozen_stale" == "true" ]] && echo 1 || echo 0)" \
        "预测 = 实际：冻结的 impact.days.becomesStale=true ↔ 应用后评估 status=${est_status:-?}（STALE）" \
        "预测与实际不符：apply=${apply25} 实际评估=${est_status:-?}（期望 STALE）冻结预测=${frozen_stale:-?}" \
        "impact 冻结的快照必须能当验收依据；两套阈值实现迟早给出两种结论"

    # LOCKED 版本：apply 仍成功，并补一条 CHANGE_AFTER_RELEASE_LOCK
    F_LOCK="$(create_req BUS "r40-锁定BUS-${RTAG}")"
    [[ -n "$F_LOCK" ]] || die "创建锁定夹具 BUS 失败"
    act "$F_LOCK" submit
    F_REL="$(create_release "r40-锁定版本-${RTAG}")"
    [[ -n "$F_REL" ]] || die "创建锁定夹具版本失败：${HTTP_BODY:0:200}"
    api_call POST "/api/req/releases/${F_REL}/requirements" "{\"requirementIds\":[${F_LOCK}]}"
    [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]] || die "把需求挂到夹具版本失败：http=${HTTP_CODE} ${HTTP_BODY:0:200}"
    api_call POST "/api/req/releases/${F_REL}/lock"
    [[ "$HTTP_CODE" == "200" ]] || die "锁定夹具版本失败：http=${HTTP_CODE} ${HTTP_BODY:0:200}"

    chg_create "$F_LOCK" CONTENT "r40 锁后变更" '{"title":"r40-锁定后标题"}'
    C_LOCK="$(jqv '.id // empty' "$CB")"
    approve_biz "$C_LOCK" REQ_CHANGE >/dev/null
    chg_apply "$C_LOCK"
    local lock_code="$CC" lock_audit="" lock_status=""
    lock_status="$(jqv '.status // empty' "$CB")"
    lock_audit="$(db_count audit_log "tenant_id=${TID} AND action='CHANGE_AFTER_RELEASE_LOCK' AND biz_id=${C_LOCK:-0}")"
    ck "$([[ "$lock_code" == "200" && "$lock_status" == "APPLIED" && "${lock_audit:-0}" == "1" ]] && echo 1 || echo 0)" \
        "LOCKED 版本下仍可应用（200 APPLIED），并恰写 1 条 CHANGE_AFTER_RELEASE_LOCK（biz_id=${C_LOCK:-?}）" \
        "锁定版本应用不符：http=${lock_code} status=${lock_status:-?} 锁审计=${lock_audit:-0}（期望 200/APPLIED/1）" \
        "变更单的意义就是「锁后仍能改」：必须能应用，同时用锁审计留下越锁痕迹；只挡不记等于悄悄改"

    local plan_lock_audit
    plan_lock_audit="$(db_count audit_log "tenant_id=${TID} AND action='CHANGE_AFTER_RELEASE_LOCK' AND biz_id=${C_TITLE}")"
    ck "$([[ "${plan_lock_audit:-0}" == "0" ]] && echo 1 || echo 0)" \
        "未挂锁定版本的单应用后不写锁审计（c${C_TITLE} 命中 ${plan_lock_audit:-0} 条，期望 0）" \
        "不该有的锁审计：c${C_TITLE} 命中 ${plan_lock_audit:-0} 条" \
        "锁审计只在 LOCKED/PUBLISHED 成员上写；无差别写会让「哪些改动越了锁」失去意义"

    api_call PUT "/api/req/requirements/${F_LATE}" '{"title":"r40-晚期直改"}'
    CB="$HTTP_BODY"   # 直调 api_call 后 CB 仍是上一条响应；err_code/detail 都读 CB
    local late_code="$HTTP_CODE" late_err late_ep late_fields
    late_err="$(err_code)"
    late_ep="$(jqv '.detail.changeEndpoint // empty' "$CB")"
    late_fields="$(jlen "$(jqv '.detail.fields' "$CB")")"
    ck "$([[ "$late_code" == "409" && "$late_err" == "CHANGE_REQUIRED" && -n "$late_ep" && "${late_fields:-0}" -ge 1 ]] && echo 1 || echo 0)" \
        "晚期态 PUT 四类字段 409 CHANGE_REQUIRED：changeEndpoint=${late_ep:-?}、fields=${late_fields:-0} 个（banner 可直接引导）" \
        "PUT 收口不符：http=${late_code} code=${late_err:-?} endpoint=${late_ep:-?} fields=${late_fields:-0}" \
        "guard 放在 requireWritable 之后、写字段之前；detail 必须给端点与命中字段，否则前端只能弹一个干瘪 toast"

    # ---------------------------------------------------------------- S6 收口与审计查询
    section "S6 收口、列表/详情与审计查询"
    api_call PUT "/api/req/requirements/${F_EARLY}" '{"title":"r40-早期直改"}'
    CB="$HTTP_BODY"
    local early_code="$HTTP_CODE" early_title
    early_title="$(jqv '.title // .view.title // empty' "$CB")"
    ck "$([[ ( "$early_code" == "200" ) && "$early_title" == "r40-早期直改" ]] && echo 1 || echo 0)" \
        "早期态 PUT 不误拦（BUS SUBMITTED 改 title → 200，且值已落）" \
        "早期态被误拦：http=${early_code} code=$(err_code) title=${early_title:-?}" \
        "收口只在「真实变化 + 非早期态」触发；把早期态也拦掉等于把草稿编辑也逼进审批"

    local page_total page_items filter_bad
    api_call GET "/api/req/requirements/${F_EARLY}/changes?page=1&size=1"
    page_total="$(jqv '.total // 0' "$HTTP_BODY")"
    page_items="$(jlen "$(jqv '.items' "$HTTP_BODY")")"
    api_call GET "/api/req/requirements/${F_EARLY}/changes?status=APPLIED&page=1&size=50"
    filter_bad="$(jqv '[.items[]? | select(.status != "APPLIED")] | length' "$HTTP_BODY")"
    ck "$([[ "${page_items:-0}" == "1" && "${page_total:-0}" -ge 3 && "${filter_bad:-1}" == "0" ]] && echo 1 || echo 0)" \
        "列表分页与过滤：size=1 只回 1 条、total=${page_total:-0}（≥3）、status=APPLIED 无一条非 APPLIED" \
        "列表不符：size=1 回 ${page_items:-0} 条、total=${page_total:-0}、过滤越界 ${filter_bad:-?} 条" \
        "total 必须是不受分页影响的过滤后总数；角标（PENDING/APPROVED 计数）就靠它"

    local d_before d_after d_impact d_items d_bad
    chg_get "$C_TITLE"
    d_before="$(jlen "$(jqv '.before' "$CB")")"
    d_after="$(jlen "$(jqv '.after' "$CB")")"
    d_impact="$(jlen "$(jqv '.impact' "$CB")")"
    d_items="$(jlen "$(jqv '.items' "$CB")")"
    d_bad="$(jqv '[.items[]? | select((.field|length)==0 or (.before|tostring|length)==0)] | length' "$CB")"
    ck "$([[ "$d_before" == "7" && "$d_after" == "7" && "$d_impact" == "8" && "${d_items:-0}" == "1" && "${d_bad:-1}" == "0" ]] && echo 1 || echo 0)" \
        "详情形状：before/after 各 7 键、impact 8 键、items ${d_items:-0} 行且都有 field/before（证据不省略）" \
        "详情形状不符：before=${d_before:-?} after=${d_after:-?} impact=${d_impact:-?} items=${d_items:-0} 空字段 ${d_bad:-?}" \
        "详情是深链页的唯一数据源：7/8 键与 items 缺一样，页面就只能显示「无法评估」而不是证据"

    local vpm_user="vpm1" vpm_code
    [[ "$(db_count sys_user "tenant_id=${TID} AND username='vpm1'")" == "1" ]] || vpm_user="vendor1"
    as_user "$vpm_user"
    local vpm_tok_ok=$?
    chg_create "$F_EARLY" CONTENT "r40 厂商越权" '{"title":"r40-厂商改"}'
    vpm_code="$CC"
    as_admin
    ck "$([[ $vpm_tok_ok -eq 0 && "$vpm_code" == "403" ]] && echo 1 || echo 0)" \
        "厂商账号（${vpm_user}）发起变更 → 403（VENDOR_PM 的两个变更动作是显式 DENY）" \
        "厂商越权未拦：login_rc=${vpm_tok_ok} http=${vpm_code} code=$(err_code)" \
        "any-of 功能码可能把厂商放行：RbacSeeder.ACTION_DENY_OVERRIDES 必须显式 DENY 这两个动作"

    local chg_audit_total
    chg_audit_total="$(db_count audit_log "tenant_id=${TID} AND biz_type='REQ_CHANGE' AND biz_id=${C_TITLE}")"
    ck "$([[ "${chg_audit_total:-0}" -ge 2 ]] && echo 1 || echo 0)" \
        "按 changeId 查审计 ≥2 条（biz_type=REQ_CHANGE, biz_id=${C_TITLE}：CHANGE_REQUESTED + REQUIREMENT_CHANGE_APPLIED）" \
        "单据审计不足：${chg_audit_total:-0} 条（期望 ≥2）" \
        "审计必须能按 (biz_type,biz_id) 聚合成一条时间线；缺一条就无法回答「谁申请、谁应用」"

    # 退出复位：交给共享钩子（demo-reset + 基线断言），失败只能置红
    local reset_rc residual req_residual
    gate_fixture_end
    reset_rc=$?
    residual="$(db_count req_change "tenant_id=${TID}")"
    req_residual="$(db_count requirement "tenant_id=${TID} AND title LIKE '%${RTAG}%'")"
    ck "$([[ $reset_rc == 0 ]] && echo 1 || echo 0)" \
        "退出复位成功（platform-demo-reset + 基线断言全过）" \
        "退出复位失败（rc=${reset_rc}）：上方 [gate] 行给出首个不成立项" \
        "复位是门禁的一部分：手工跑 bash scripts/platform-demo-reset.sh 看首个 FAIL；不许 WARN 降级"

    ck "$([[ "${req_residual:-x}" == "0" && "${residual:-x}" == "0" ]] && echo 1 || echo 0)" \
        "复位后本轮 tag 需求 0 条、req_change 0 行（RUN_TAG 清理彻底，可重复跑）" \
        "残留：tag 需求 ${req_residual:-?} 条、req_change ${residual:-?} 行" \
        "req_change/req_change_item 必须进 DemoFixture.DELETE_ORDER（先子后父），否则第二轮起 S1 就红"
}

# --------------------------------------------------------------------------- 入口

if ((STATIC_ONLY == 1)); then
    section "静态检查（免栈，${STATIC_TOTAL} 条）"
    static_checks
    printf '\nCHANGE_VERIFY_STATIC TOTAL=%d CASE=%d PASS=%d\n' "$STATIC_TOTAL" "$SCASE" "$SPASS"
    if ((SCASE != STATIC_TOTAL)); then
        printf '\033[1;31m静态断言数与自声明分母不一致：CASE=%d ≠ TOTAL=%d\033[0m\n' "$SCASE" "$STATIC_TOTAL"
        exit 1
    fi
    ((SPASS == STATIC_TOTAL)) || exit 1
    printf '\033[1;32m静态检查全部 %d 条通过：接入/文档/计数/自声明一致\033[0m\n' "$STATIC_TOTAL"
    exit 0
fi

if [[ "$MODE" == "twice" ]]; then
    local_out1="$(mktemp)"
    local_out2="$(mktemp)"
    trap 'rm -f "$local_out1" "$local_out2"' EXIT
    bash "$0" --once >"$local_out1" 2>&1
    rc1=$?
    bash "$0" --once >"$local_out2" 2>&1
    rc2=$?
    l1="$(grep -E '^CHANGE_VERIFY_TOTAL=' "$local_out1" | tail -n1)"
    l2="$(grep -E '^CHANGE_VERIFY_TOTAL=' "$local_out2" | tail -n1)"
    printf '\n== 两次连跑 ==\n  第 1 轮 rc=%d %s\n  第 2 轮 rc=%d %s\n' "$rc1" "$l1" "$rc2" "$l2"
    if ((rc1 != 0)); then
        printf '\033[1;31m第 1 轮失败：\033[0m\n'
        grep -E '^(  \x1b\[1;31mFAIL|[0-9]+\)|修复:)' "$local_out1" | head -n 20
        exit 1
    fi
    if ((rc2 != 0)); then
        printf '\033[1;31m第 2 轮失败（第一轮绿、第二轮红 = 夹具没复位干净）：\033[0m\n'
        tail -n 40 "$local_out2"
        exit 1
    fi
    if [[ -z "$l1" || "$l1" != "$l2" ]]; then
        printf '\033[1;31m两轮结果不一致：%s vs %s\033[0m\n' "${l1:-<空>}" "${l2:-<空>}"
        exit 1
    fi
    printf '\033[1;32m两次连跑结果一致（%s）：可重复，无顺序依赖\033[0m\n' "$l1"
    exit 0
fi

run_main

printf '\nCHANGE_VERIFY_TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$CHANGE_VERIFY_TOTAL" "$CASE" "$PASS" "$FAIL"
SUMMARY_FILE="${REPORT_DIR}/summary-${RTAG}.txt"
{
    printf 'r40 change gate @ %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
    printf 'TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$CHANGE_VERIFY_TOTAL" "$CASE" "$PASS" "$FAIL"
    printf 'RTAG=%s\n' "$RTAG"
    [[ -s "$FAIL_LOG" ]] && { printf -- '--- failures ---\n'; cat "$FAIL_LOG"; }
} >"$SUMMARY_FILE"
printf '  报告: %s\n' "$SUMMARY_FILE"

if ((CASE != CHANGE_VERIFY_TOTAL)); then
    printf '\033[1;31m断言数与自声明分母不一致：CASE=%d ≠ TOTAL=%d\033[0m\n' "$CASE" "$CHANGE_VERIFY_TOTAL"
    printf '修复: 某段被提前 die/return 掉了；断言必须线性执行，缺一条就是红\n'
    exit 1
fi
if ((FAIL > 0)); then
    printf '\033[1;31m有 %d 条断言失败\033[0m\n' "$FAIL"
    exit 1
fi
printf '\033[1;32m全部 %d 条通过：变更单 申请→影响→审批→应用→基线 闭环成立，锁后仍可应用且留痕\033[0m\n' "$CHANGE_VERIFY_TOTAL"
exit 0
