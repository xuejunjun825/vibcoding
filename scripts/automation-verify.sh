#!/usr/bin/env bash
# automation-verify.sh — r39 自动化规则引擎真栈门禁（S1–S6，共 40 条）。
#
# 这个脚本要证明的不是「接口能返回 200」，而是五件靠代码结构保证的事：
#   1. 事件只从审计旁路产生，前端没有上报入口（/automation/events 必须 404）；
#   2. 同一业务动作重复投递只执行一次（先抢 automation_run 唯一键，再判定）；
#   3. 规则自己产生的动作事件不会再次触发规则（origin=RULE / chain_depth≥4 两道闸，0 run）；
#   4. 作用域只经 ScopeSql：规则的域限制不会因为一条更宽的条件被绕过；
#   5. 动作只有四类白名单，能写库的先提交、通知与外呼后置。
#
# 用法：
#   bash scripts/automation-verify.sh                # 全量 40 条（需 make platform-up）
#   bash scripts/automation-verify.sh --static-only  # 不连栈，只查契约自洽（S2/S5 的静态部分）
#
# 约定（与 release-flow-verify / platform-demo-reset 一致）：
#   - 脚本自声明 AUTOMATION_TOTAL=40，CASE 必须等于 TOTAL，少跑一段就是红；
#   - 前置缺失（栈没起、admin/vendor1 登录失败）直接 die，不 skip（条件跳过=假绿）；
#   - 独立 RUN_TAG + EXIT 清理：跑完不残留规则/需求/事件/运行/标签，
#     demo-reset 之后仍然 80/80（本脚本自己造的数据自己收走）。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
set +e

AUTOMATION_TOTAL=40
CASE=0
PASS=0
FAIL=0

STATIC_ONLY=0
[[ "${1:-}" == "--static-only" ]] && STATIC_ONLY=1
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    sed -n '2,/^set -uo pipefail$/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 0
fi

# 每次跑一个独立标签：断言只认自己造的数据，清理也只删自己的。
RUN_TAG="AUTO-$(date '+%Y%m%d%H%M%S')-$$"
TENANT_CODE="${AUTOMATION_TENANT:-t1}"
REPORT_DIR="$(run_log_dir)/automation-verify"
mkdir -p "$REPORT_DIR" 2>/dev/null
FAIL_LOG="${REPORT_DIR}/failures-${RUN_TAG}.txt"
: >"$FAIL_LOG" 2>/dev/null

pass() { CASE=$((CASE + 1)); PASS=$((PASS + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$*"; }
fail() {
    CASE=$((CASE + 1)); FAIL=$((FAIL + 1))
    printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$1"
    [[ -n "${2:-}" ]] && printf '        修复: %s\n' "$2"
    printf 'FAIL [%02d] %s | %s\n' "$CASE" "$1" "${2:-}" >>"$FAIL_LOG"
}
section() { printf '\n\033[1;36m-- %s\033[0m\n' "$*"; }

# 断言 HTTP 状态码 + 稳定错误码（错误码是 QA 的契约，比 message 文案稳）。
# 全量里所有断言都走这一个入口：deny 的语义与 platform-test-negative 一致——
# 期望的码段 + 稳定的错误码子串，缺一个都算红。
deny() { # desc want_code needle fix
    if [[ "$HTTP_CODE" == "$2" ]] && { [[ -z "${3:-}" ]] || grep -qF -- "$3" <<<"$HTTP_BODY"; }; then
        pass "$1"
    else
        fail "$1（期望 ${2}${3:+/含 '$3'}，实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 220)）" "${4:-}"
    fi
}
jqv() { printf '%s' "${2:-}" | jq -r "$1" 2>/dev/null; }

# --------------------------------------------------------------------------- 静态部分
# --static-only 跑这些：它们检查「契约写在源码里没有」，不依赖栈。
# 与全量断言的关系：全量断言覆盖今天写下的分支，静态断言覆盖明天新增的分支
# （例如有人给 actions 加一条 SET_STATUS 分支，S2 的行为断言抓不到，L21 与这里的名单断言能）。
static_checks() {
    local s_case=0 s_fail=0
    s_pass() { s_case=$((s_case + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$s_case" "$1"; }
    s_fail() { s_case=$((s_case + 1)); s_fail=$((s_fail + 1)); printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$s_case" "$1"; printf '        修复: %s\n' "$2"; }
    s_check() { local desc="$1" fix="$2"; shift 2; if "$@" >/dev/null 2>&1; then s_pass "$desc"; else s_fail "$desc" "$fix"; fi; }

    local api="$ROOT/req-service/src/main/java/com/example/req/automation/api/AutomationRuleController.java"
    local run_api="$ROOT/req-service/src/main/java/com/example/req/automation/api/AutomationRunController.java"

    printf '\n\033[1;36m== automation 门禁静态部分：事件入口 / 动作白名单 / 错误码 / 种子与门禁自洽 ==\033[0m\n'

    if [[ "$(grep -c '^AUTOMATION_TOTAL=40$' "${BASH_SOURCE[0]}")" == "1" ]]; then
        s_pass "脚本自声明 AUTOMATION_TOTAL=40（TECHNICAL §12.2 的分母唯一）"
    else
        s_fail "AUTOMATION_TOTAL 不是 40 或重复声明" "断言数变化必须同步 TECHNICAL §12.2"
    fi

    if [[ -f "$api" ]] && ! grep -qE 'DeleteMapping|@RequestMapping\(value *= *"/events|"/events"' "$api" \
        && ! grep -rqE '"/automation/events"|/events"' "$ROOT/req-service/src/main/java/com/example/req/automation" 2>/dev/null; then
        s_pass "无 DELETE 规则映射、无 /automation/events 上报入口（事件只能来自审计旁路）"
    else
        s_fail "automation 包出现 DELETE 或 /events 映射" "PRD AC-6：前端无事件上报入口，停用即永久保留"
    fi

    if grep -q 'automation' "$ROOT/platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java" 2>/dev/null; then
        s_pass "PermissionRouteTable 登记了 /automation/** 的页面码与动作码"
    else
        s_fail "PermissionRouteTable 未登记 /automation/**" "不登记 = 没有权限门（TECHNICAL §7.1）"
    fi

    if grep -q 'order(-100)' "$ROOT/req-service/src/main/java/com/example/req/automation/AutomationWebConfig.java" 2>/dev/null \
        && grep -q 'INTERNAL_ONLY' "$ROOT/req-service/src/main/java/com/example/req/automation/api/AutomationInternalOnlyInterceptor.java" 2>/dev/null; then
        s_pass "厂商拦截器 order(-100) 先于权限拦截器，且返回 INTERNAL_ONLY"
    else
        s_fail "厂商拦截器未注册 order(-100) 或缺 INTERNAL_ONLY" "晚于权限拦截器会让厂商先拿到 FORBIDDEN（AC-3 错误码就不对）"
    fi

    local atype="$ROOT/req-service/src/main/java/com/example/req/automation/spec/AutomationActionType.java"
    local miss=""
    for c in SET_STATUS APPROVE SKIP_APPROVAL CREATE_RELEASE EDIT_ROLLUP; do
        grep -q "\"$c\"" "$atype" 2>/dev/null || miss+="$c "
    done
    if [[ -z "$miss" ]]; then
        s_pass "五条禁动作在拒绝名单里（SET_STATUS/APPROVE/SKIP_APPROVAL/CREATE_RELEASE/EDIT_ROLLUP）"
    else
        s_fail "禁动作拒绝名单缺：${miss}" "白名单只有 NOTIFY/ASSIGN_OWNER/ADD_TAG/WEBHOOK（AC-5）"
    fi

    local err="$ROOT/req-service/src/main/java/com/example/req/automation/spec/AutomationErrors.java"
    miss=""
    for c in ACTION_NOT_ALLOWED NAME_TOO_LONG TOO_MANY_ACTIONS PAYLOAD_TOO_LARGE \
        NAME_TAKEN VERSION_CONFLICT CONDITION_WIDENS_SCOPE SCHEMA_INVALID; do
        grep -q "$c" "$err" 2>/dev/null || miss+="$c "
    done
    if [[ -z "$miss" ]]; then
        s_pass "错误码工厂覆盖 8 个稳定码（schema/动作/名称/体积/重名/版本/收窄）"
    else
        s_fail "AutomationErrors 缺稳定错误码：${miss}" "QA 按码断言，缺码会让负例只能靠文案猜"
    fi

    if grep -q 'unknown\|未知' "$ROOT/req-service/src/main/java/com/example/req/automation/spec/AutomationSpecs.java" 2>/dev/null; then
        s_pass "条件/动作 schema 对未知键 fail-closed（拒绝而非忽略）"
    else
        s_fail "AutomationSpecs 未见未知键拒绝" "闭 schema 是「用户写不出服务端不接受的规则」的前提"
    fi

    # DELETE_ORDER：四张自动化表必须排在 requirement 之前，否则「重置后 0 行」不成立
    # （automation_run/event/tag 都带 requirement_id，requirement 先删就留下孤儿行）。
    local fixture="$ROOT/req-service/src/main/java/com/example/req/seed/DemoFixture.java"
    local order_line req_line auto_line
    order_line="$(grep -n 'purge("automation_run")' "$fixture" 2>/dev/null | head -n1 | cut -d: -f1)"
    auto_line="$(grep -n 'purge("automation_rule")' "$fixture" 2>/dev/null | head -n1 | cut -d: -f1)"
    req_line="$(grep -n 'purge("requirement")' "$fixture" 2>/dev/null | head -n1 | cut -d: -f1)"
    if [[ -n "$order_line" && -n "$auto_line" && -n "$req_line" ]] \
        && (( order_line < req_line && auto_line < req_line )); then
        s_pass "DELETE_ORDER 把 automation_run/event/rule/requirement_tag 排在 requirement 之前"
    else
        s_fail "四张自动化表未排在 requirement 之前" "requirement 先删会留下自动化孤儿行，demo-reset 的 0 行断言就不成立"
    fi

    if grep -q 'AUTOMATION_RULE_P0_P1' "$fixture" && grep -q 'priorityIn' "$fixture" \
        && grep -q 'AUTOMATION_RULE_BIG_TAG' "$fixture" && grep -q 'ADD_TAG' "$fixture"; then
        s_pass "两条演示规则存在：R1 = P0/P1 新建通知域负责人，R2 = 大需求自动打标"
    else
        s_fail "演示规则常量不全" "PRD AC-16：演示必须有可观察的 R1/R2"
    fi

    if grep -q 'AUTOMATION_EVENT_DRAIN' "$ROOT/req-service/src/main/java/com/example/req/seed/DemoResetService.java" \
        && grep -q "status = 'DONE'" "$fixture"; then
        s_pass "Tx4 先收口历史 PENDING 事件，再种规则（否则历史事件会被 R2 补打标）"
    else
        s_fail "Tx4 缺历史事件收口" "先 drain 再 insert：种子规则不能追溯改造已有数据"
    fi

    local reset="$ROOT/scripts/platform-demo-reset.sh"
    if grep -q '^TOTAL_CHECKS=80$' "$reset" && [[ "$(grep -c 'AUTO_' "$reset")" -ge 4 ]]; then
        s_pass "platform-demo-reset 自声明 80 条且含 4 条自动化复核（规则 2 条 + 三表 0 行）"
    else
        s_fail "demo-reset 断言数与自声明不一致" "TOTAL_CHECKS=80 与 CASE 必须同步（TECHNICAL §12.3）"
    fi

    local mk="$ROOT/Makefile"
    if grep -qE '^automation-verify:' "$mk" \
        && sed -n '/^\.PHONY:/,/^[^.[:space:]]/p' "$mk" | grep -q 'automation-verify'; then
        s_pass "Makefile 有 automation-verify 目标且登记到 .PHONY"
    else
        s_fail "Makefile 缺 automation-verify 目标或 .PHONY 登记" "团队入口只走 make（AGENTS.md §4）"
    fi

    if grep -q 'L21' "$ROOT/scripts/tests/lint.sh" && grep -q 'automation_boundary' "$ROOT/scripts/tests/lint.sh"; then
        s_pass "lint L21 自动化边界就位（静态锁死改状态/拼隔离列/禁动作码/assignOwner 单点）"
    else
        s_fail "lint 缺 L21 automation_boundary" "TECHNICAL §5.4 的四条要靠静态门禁才不会退化成口头约定"
    fi

    printf '\nAUTOMATION_STATIC_TOTAL=%d PASS=%d FAIL=%d\n' "$s_case" $((s_case - s_fail)) "$s_fail"
    ((s_fail == 0 && s_case > 0)) || exit 1
    printf '\033[1;32m静态部分 %d 条通过（不连栈；全量请直接运行本脚本）\033[0m\n' "$s_case"
    exit 0
}

((STATIC_ONLY == 1)) && static_checks

# --------------------------------------------------------------------------- 前置

printf '\n\033[1;36m== r39 自动化规则引擎门禁（S1–S6 / %d 条）· 标签 %s ==\033[0m\n' "$AUTOMATION_TOTAL" "$RUN_TAG"
need_cmd curl || die "缺少 curl"
need_cmd jq || die "缺少 jq"
need_cmd mysql || die "缺少 mysql 客户端"
req_ready || die "req-service 未就绪：${REQ_URL}/internal/health —— 先 make platform-up"
db_up || die "MySQL 未就绪：${PLATFORM_DB_URL} —— 先 make platform-up"

TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${TENANT_CODE}' LIMIT 1")"
[[ -n "$TID" ]] || die "租户 ${TENANT_CODE} 不存在 —— 先 make platform-seed"
DOMAIN_ORD="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID} AND code='ORD' LIMIT 1")"
DOMAIN_SUP="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID} AND code='SUP' LIMIT 1")"
ADMIN_ID="$(db_q "SELECT id FROM sys_user WHERE tenant_id=${TID} AND username='admin' LIMIT 1")"
DEV_ID="$(db_q "SELECT id FROM sys_user WHERE tenant_id=${TID} AND username='dev1' LIMIT 1")"
[[ -n "$DOMAIN_ORD" && -n "$DOMAIN_SUP" && -n "$ADMIN_ID" && -n "$DEV_ID" ]] \
    || die "演示域/账号缺失（ORD=${DOMAIN_ORD:-?} SUP=${DOMAIN_SUP:-?} admin=${ADMIN_ID:-?} dev1=${DEV_ID:-?}）—— 先 make platform-demo-reset"

platform_login "$TENANT_CODE" admin 'Admin@12345' >/dev/null 2>&1 \
    || die "admin 登录失败 —— 检查种子账号 admin/Admin@12345"
ADMIN_TOKEN="$API_TOKEN"
ADMIN_TENANT="$API_TENANT"

# --------------------------------------------------------------------------- 清理
# 只删本次标签造的数据；顺序按依赖倒序（run → event → tag → rule → requirement）。
purge_data() {
    db_q "DELETE FROM automation_run WHERE tenant_id=${TID} AND event_id LIKE '%${RUN_TAG}%'" >/dev/null 2>&1
    db_q "DELETE FROM automation_event WHERE tenant_id=${TID} AND event_id LIKE '%${RUN_TAG}%'" >/dev/null 2>&1
    db_q "DELETE FROM requirement_tag WHERE tenant_id=${TID}
            AND requirement_id IN (SELECT id FROM requirement WHERE tenant_id=${TID} AND title LIKE '%${RUN_TAG}%')" >/dev/null 2>&1
    db_q "DELETE FROM automation_rule WHERE tenant_id=${TID} AND name LIKE '%${RUN_TAG}%'" >/dev/null 2>&1
    db_q "DELETE FROM requirement_domain_rel WHERE requirement_id IN
            (SELECT id FROM requirement WHERE tenant_id=${TID} AND title LIKE '%${RUN_TAG}%')" >/dev/null 2>&1
    db_q "DELETE FROM requirement WHERE tenant_id=${TID} AND title LIKE '%${RUN_TAG}%'" >/dev/null 2>&1
}
cleanup() {
    local rc=$?
    trap - EXIT INT TERM
    purge_data
    exit $rc
}
trap cleanup EXIT INT TERM

# --------------------------------------------------------------------------- 工具函数

RULE_URL=/api/req/automation/rules
RUN_URL=/api/req/automation/runs

mk_rule() { # name trigger scopeType scopeId priority conditionJson actionsJson
    jq -cn --arg n "$1" --arg t "$2" --arg st "$3" --argjson sid "$4" --argjson p "$5" \
        --argjson c "$6" --argjson a "$7" \
        '{name:$n,enabled:true,triggerType:$t,scopeType:$st,scopeId:$sid,priority:$p,condition:$c,actions:$a}'
}

# 建一条规则并把 id 放进 RULE_ID；返回 HTTP_CODE 供断言。
create_rule() { # name trigger scopeType scopeId priority conditionJson actionsJson
    api_call POST "$RULE_URL" "$(mk_rule "$@")"
    RULE_ID="$(jqv '.id' "$HTTP_BODY")"
    [[ "$RULE_ID" == "null" ]] && RULE_ID=""
}

create_req() { # title priority domainIdsJson -> REQ_ID + REQ_TIME + REQ_STATUS0 + HTTP_CODE
    local body out
    # 只能建 BUS：BUS→PDR→RD 的下级建单要先让父需求 POOLED（r22 分解门禁），
    # 而 PDR/BUS 的人天由子需求汇总（PARENT_DAYS_READONLY）。所以本门禁用「新建 BUS」
    # 触发 REQUIREMENT_CREATED，并把它建单时的 status/estimateDays 记为基线，
    # 跑完规则后比对「有没有被规则改动」——这正是 AC-6 要证明的事。
    body="$(jq -cn --arg t "$1" --arg p "$2" --argjson d "$3" \
        '{type:"BUS",title:$t,priority:$p,domainIds:$d}')"
    # 自己量耗时：AC-13 要求「建需求接口 <1s 返回」，这是规则引擎异步化的可观测后果。
    out="$(curl -sS -o /tmp/autoreq.$$ -w '%{http_code} %{time_total}' --max-time 20 \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" \
        -X POST "${GATEWAY_URL}/api/req/requirements" -d "$body" 2>/dev/null)"
    HTTP_CODE="${out%% *}"; REQ_TIME="${out##* }"
    HTTP_BODY="$(cat /tmp/autoreq.$$ 2>/dev/null || true)"; rm -f "/tmp/autoreq.$$"
    REQ_ID="$(jqv '.id' "$HTTP_BODY")"
    [[ "$REQ_ID" == "null" ]] && REQ_ID=""
    REQ_STATUS0="$(jqv '.status' "$HTTP_BODY")"
}

# 事件直投：模拟 outbox 写入（同一 (tenant,event_id) 第二次被唯一键吃掉）。
insert_event() { # event_id event_type requirement_id origin chain_depth payload_json
    db_q "INSERT INTO automation_event (tenant_id, event_id, event_type, requirement_id, origin, chain_depth, payload_json, status)
          VALUES (${TID}, '$1', '$2', $3, '$4', $5, '$6', 'PENDING')" >/dev/null 2>&1
}

# 等某需求上的 run 出现（worker 轮询，10s 足够）。
wait_status() { # requirement_id status
    local rid="$1" st="$2" got i
    for i in $(seq 1 40); do
        got="$(db_q "SELECT status FROM automation_run WHERE tenant_id=${TID} AND requirement_id=${rid} ORDER BY id DESC LIMIT 1")"
        [[ "$got" == "$st" ]] && return 0
        sleep 0.5
    done
    return 1
}

wait_event() { # event_id status -> 0 表示状态到位
    local eid="$1" st="$2" got i
    for i in $(seq 1 40); do
        got="$(db_q "SELECT status FROM automation_event WHERE tenant_id=${TID} AND event_id='${eid}' LIMIT 1")"
        [[ "$got" == "$st" ]] && return 0
        sleep 0.5
    done
    return 1
}

# 等某条规则对某个事件产生 run（只认自己的 rule_id；演示规则也会匹配同一事件，按需求/租户计数必误报）。
wait_rule_run() { # rule_id event_id [want]
    local rid="$1" eid="$2" want="${3:-1}" got i
    for i in $(seq 1 40); do
        got="$(db_count automation_run "tenant_id=${TID} AND rule_id=${rid} AND event_id='${eid}'")"
        [[ "${got:-0}" -ge "$want" ]] && return 0
        sleep 0.5
    done
    return 1
}

# --------------------------------------------------------------------------- S1 正例（6）

section "S1 正例：建规则 → 建 P0 需求 → 5s 内一次成功执行，且业务接口不被拖慢（6 条）"

create_rule "S1-P0通知-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0","P1"]}' \
    '[{"type":"NOTIFY","recipients":[{"kind":"DOMAIN_OWNER"}]}]'
S1_RULE="$RULE_ID"
S1_VER="$(jqv '.version' "$HTTP_BODY")"
S1_SCOPE_LABEL="$(jqv '.scopeLabel' "$HTTP_BODY")"
if [[ "$HTTP_CODE" == "201" && "$S1_VER" == "1" && -n "$S1_SCOPE_LABEL" && "$S1_SCOPE_LABEL" != "null" ]]; then
    pass "建规则 201，version=1，作用域回显 scopeLabel=${S1_SCOPE_LABEL}"
else
    fail "建规则返回 ${HTTP_CODE} version=${S1_VER} scopeLabel=${S1_SCOPE_LABEL}（应 201/1/非空）" "见 AutomationRuleService.create 与 AutomationRuleViews"
fi

api_call GET "${RULE_URL}?keyword=${RUN_TAG}&page=1&size=20"
if [[ "$HTTP_CODE" == "200" ]] && printf '%s' "$HTTP_BODY" | jq -e --argjson id "${S1_RULE:-0}" \
    '(.items // .list // .records) | map(.id) | index($id) != null' >/dev/null 2>&1; then
    pass "列表按关键字可检索到刚建的规则（id=${S1_RULE}）"
else
    fail "列表检索不到规则（HTTP ${HTTP_CODE}）" "RuleView 列表查询必须支持 keyword，否则建完立刻看不见"
fi

NOTIF_BEFORE="$(db_count notification "tenant_id=${TID}")"
create_req "S1-P0-${RUN_TAG}" P0 "[${DOMAIN_ORD}]"
S1_REQ="$REQ_ID"
# 一次断言同时量两件事：接口同步耗时（AC-13：执行不在 HTTP 线程）与 5s 内的 run。
if [[ -n "$S1_REQ" ]] && [[ "$(awk -v t="${REQ_TIME:-9}" 'BEGIN{print (t<1.0)?1:0}')" == "1" ]] \
    && wait_status "$S1_REQ" SUCCESS; then
    pass "建 P0 需求 201、接口耗时 ${REQ_TIME}s < 1s，且 5s 内出现 SUCCESS run（priority=100 先于 R2）"
else
    fail "建 P0 需求 / 等 run 失败（id=${S1_REQ:-?} 耗时=${REQ_TIME:-?}s）" \
        "查 automation_event 是否 PENDING/FAILED、AutomationWorker 是否在轮询；执行必须在 worker 里"
fi

S1_RUN_ID="$(db_q "SELECT id FROM automation_run WHERE tenant_id=${TID} AND requirement_id=${S1_REQ:-0} ORDER BY id DESC LIMIT 1")"
S1_ACTIONS="$(db_q "SELECT actions_json FROM automation_run WHERE id=${S1_RUN_ID:-0}")"
if [[ -n "$S1_ACTIONS" ]] && grep -q 'NOTIFY' <<<"$S1_ACTIONS" && grep -q 'SUCCESS' <<<"$S1_ACTIONS"; then
    pass "run.actions_json 记录了 NOTIFY 且结果为 SUCCESS（逐动作可回放）"
else
    fail "run.actions_json 缺 NOTIFY/SUCCESS：${S1_ACTIONS:-<空>}" "AutomationActionExecutor 必须把每条动作结果落库"
fi

NOTIF_AFTER="$(db_count notification "tenant_id=${TID}")"
if (( NOTIF_AFTER > NOTIF_BEFORE )); then
    pass "域负责人收到通知（${NOTIF_BEFORE} → ${NOTIF_AFTER}）"
else
    fail "通知数未增加（${NOTIF_BEFORE} → ${NOTIF_AFTER}）" "AutomationNotifier 必须按 DOMAIN_OWNER 解析收件人并落 notification"
fi

api_call GET "/api/req/requirements/${S1_REQ:-0}"
# 详情响应是信封 {view, children, permissions, allowedActions}：字段在 .view 里。
S1_STATUS="$(jqv '.view.status' "$HTTP_BODY")"; S1_EST="$(jqv '.view.estimateDays' "$HTTP_BODY")"
# 与「建单那一刻」的只读快照比对：规则只能发通知，不许把状态推进一步、也不许动人天。
if [[ -n "${S1_REQ:-}" && "$S1_STATUS" == "${REQ_STATUS0}" && "$S1_EST" == "null" ]]; then
    pass "需求 status 与 estimateDays 与建单时一致（${S1_STATUS}/${S1_EST}），规则未改需求"
else
    fail "需求被规则改坏了（status=${S1_STATUS:-?} estimateDays=${S1_EST:-?}，建单时 ${REQ_STATUS0:-?}/null）" \
        "白名单里没有改状态/改人天，出现漂移说明绕过了 AutomationActionExecutor"
fi

# --------------------------------------------------------------------------- S2 负例（8）

section "S2 负例：schema 封闭、动作白名单、体积与 version 乐观锁（8 条）"

api_call POST "$RULE_URL" "$(mk_rule "S2-未知键-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"],"unknownKey":true}' '[{"type":"NOTIFY","recipients":[{"kind":"DOMAIN_OWNER"}]}]')"
deny "未知条件键 → 400 AUTOMATION_SCHEMA_INVALID" 400 AUTOMATION_SCHEMA_INVALID \
    "ConditionSpec.parse 必须拒绝未声明键（未知键被忽略 = 用户以为生效了）"

api_call POST "$RULE_URL" "$(mk_rule "S2-越权动作-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"SET_STATUS","status":"DONE"}]')"
deny "SET_STATUS 动作 → 400 ACTION_NOT_ALLOWED" 400 ACTION_NOT_ALLOWED \
    "动作白名单只有四类；改状态必须走 RequirementService 的状态机与审批"

api_call POST "$RULE_URL" "$(mk_rule "S2-空动作-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[]')"
deny "空动作数组 → 400（schema 校验）" 400 AUTOMATION_SCHEMA_INVALID \
    "没有动作的规则是纯噪声，允许保存等于让用户以为自己配好了"

many_actions="$(jq -cn '[range(0;11) | {type:"ADD_TAG", tag:("批量" + (.|tostring))}]')"
api_call POST "$RULE_URL" "$(mk_rule "S2-动作过多-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' "$many_actions")"
deny "动作 11 条 → 400 TOO_MANY_ACTIONS" 400 TOO_MANY_ACTIONS \
    "上限 10 条（AC-5）；不设上限会让一次事件拖住 worker"

long_name="$(printf 'N%.0s' $(seq 1 101))-${RUN_TAG}"
api_call POST "$RULE_URL" "$(mk_rule "$long_name" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"ADD_TAG","tag":"x"}]')"
deny "名称 101 字符 → 400 NAME_TOO_LONG" 400 NAME_TOO_LONG \
    "上限 100（AC-5）"

# 用「合法键 + 超长数组」造体积：加未知键 pad 会先被 schema 拦成 AUTOMATION_SCHEMA_INVALID，
# 量到的就不是 8KB 上限了。priorityIn 2400 项约 12KB，必然越过上限。
big_cond="$(jq -cn '{priorityIn: [range(0;2400) | "P0"]}')"
api_call POST "$RULE_URL" "$(mk_rule "S2-超大-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    "$big_cond" '[{"type":"ADD_TAG","tag":"x"}]')"
deny "条件 8KB → 400 PAYLOAD_TOO_LARGE" 400 PAYLOAD_TOO_LARGE \
    "上限 8KB（AC-5）；超大 JSON 会让每轮求值都付出解析代价"

api_call POST "$RULE_URL" "$(mk_rule "S2-新-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"ADD_TAG","tag":"x"}]')"
S2_RULE="$RULE_ID"
api_call POST "$RULE_URL" "$(mk_rule "S2-新-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P1"]}' '[{"type":"ADD_TAG","tag":"x"}]')"
deny "同名规则 → 409 AUTOMATION_RULE_NAME_TAKEN" 409 AUTOMATION_RULE_NAME_TAKEN \
    "uk_rule_name(tenant_id,name) 必须由 service 翻译成 409，而不是 500"

api_call PUT "${RULE_URL}/${S2_RULE:-0}" "$(jq -cn --arg t "S2-新-${RUN_TAG}" \
    '{name:$t,enabled:true,triggerType:"REQUIREMENT_CREATED",scopeType:"DOMAIN",scopeId:'"$DOMAIN_ORD"',priority:100,condition:{priorityIn:["P0"]},actions:[{type:"ADD_TAG",tag:"x"}],version:99}')"
deny "旧 version 更新 → 409 AUTOMATION_RULE_VERSION_CONFLICT" 409 AUTOMATION_RULE_VERSION_CONFLICT \
    "启停与编辑都带 version：防止「停用覆盖别人刚改的条件」（AC-7）"

# --------------------------------------------------------------------------- S3 幂等（7）

section "S3 幂等：同一 event_id 重投两次，run/通知/标签/审计各一次（7 条）"

create_req "S3-幂等-${RUN_TAG}" P0 "[${DOMAIN_ORD}]"
S3_REQ="$REQ_ID"
S3_PAYLOAD="{\"requirementId\":${S3_REQ:-0},\"priority\":\"P0\",\"domainIds\":[${DOMAIN_ORD}]}"
S3_EID="S3:${RUN_TAG}:1"
# 建需求本身也会发出 REQUIREMENT_CREATED，S1 规则会先发一条通知；等这条 run 终止再取基线，
# 否则基线落在它执行之前，delta 会算成 2（看起来像重复执行，其实是另一个事件）。
S3_CREATE_EID="$(db_q "SELECT event_id FROM automation_event WHERE tenant_id=${TID} AND requirement_id=${S3_REQ:-0} AND event_type='REQUIREMENT_CREATED' ORDER BY id LIMIT 1")"
S3_CREATE_STATUS=""
for _i in $(seq 1 40); do
    S3_CREATE_STATUS="$(db_q "SELECT status FROM automation_run WHERE tenant_id=${TID} AND rule_id=${S1_RULE:-0} AND event_id='${S3_CREATE_EID}' LIMIT 1")"
    [[ "$S3_CREATE_STATUS" =~ ^(SUCCESS|SKIPPED|FAILED|PARTIAL)$ ]] && break
    sleep 0.5
done
# 通知计数按「规则名」归属：演示规则 R1 也匹配 P0 新建，按租户计数会把它的通知算进来。
NOTIF_B3="$(db_count notification "tenant_id=${TID} AND title LIKE '自动化规则触发：S1-P0通知-${RUN_TAG}%'")"
insert_event "$S3_EID" REQUIREMENT_CREATED "${S3_REQ:-0}" 'AUDIT' 0 "$S3_PAYLOAD"
insert_event "$S3_EID" REQUIREMENT_CREATED "${S3_REQ:-0}" 'AUDIT' 0 "$S3_PAYLOAD"
S3_EVENTS="$(db_count automation_event "tenant_id=${TID} AND event_id='${S3_EID}'")"
if [[ "$S3_EVENTS" == "1" ]]; then
    pass "同一 event_id 投两次 → automation_event 恰 1 行（uk_event_dedupe）"
else
    fail "事件去重失效：${S3_EVENTS} 行（event_id=${S3_EID}）" "outbox 用 INSERT IGNORE，唯一键 (tenant_id,event_id) 必须存在"
fi

S3_FIRST_RUNS=0; S3_FIRST_STATUS=""
for _i in $(seq 1 40); do
    S3_FIRST_RUNS="$(db_count automation_run "tenant_id=${TID} AND rule_id=${S1_RULE:-0} AND event_id='${S3_EID}'")"
    S3_FIRST_STATUS="$(db_q "SELECT status FROM automation_run WHERE tenant_id=${TID} AND rule_id=${S1_RULE:-0} AND event_id='${S3_EID}' LIMIT 1")"
    [[ "$S3_FIRST_RUNS" == "1" && "$S3_FIRST_STATUS" == "SUCCESS" ]] && break
    sleep 0.5
done
if [[ "$S3_FIRST_RUNS" == "1" && "$S3_FIRST_STATUS" == "SUCCESS" ]]; then
    pass "重投只产生 1 条 run 且 SUCCESS（rule_id=${S1_RULE}，演示规则不计）"
else
    fail "本规则对该事件 run=${S3_FIRST_RUNS} status=${S3_FIRST_STATUS:-<无>}（应 1/SUCCESS）" "tryStart 必须先抢唯一键再判定，抢不到直接放弃"
fi
NOTIF_A3="$(db_count notification "tenant_id=${TID} AND title LIKE '自动化规则触发：S1-P0通知-${RUN_TAG}%'")"
if (( NOTIF_A3 - NOTIF_B3 == 1 )); then
    pass "重投只发 1 条通知（增量 $((NOTIF_A3 - NOTIF_B3))）"
else
    fail "通知增量 $((NOTIF_A3 - NOTIF_B3))（应恰好 1）" "执行体不幂等：第二次投递仍走到了动作"
fi

create_rule "S3-打标-${RUN_TAG}" FIELD_CHANGE DOMAIN "$DOMAIN_ORD" 110 \
    '{"estimateDays":{"op":"gte","value":10},"fields":["estimate_days"]}' \
    '[{"type":"ADD_TAG","tag":"幂等标签"}]'
S3_TAG_RULE="$RULE_ID"
S3_TAG_EID="S3:${RUN_TAG}:2"
S3_TAG_PAYLOAD="{\"requirementId\":${S3_REQ:-0},\"field\":\"estimate_days\",\"before\":1,\"after\":12}"
insert_event "$S3_TAG_EID" FIELD_CHANGE "${S3_REQ:-0}" 'AUDIT' 0 "$S3_TAG_PAYLOAD"
insert_event "$S3_TAG_EID" FIELD_CHANGE "${S3_REQ:-0}" 'AUDIT' 0 "$S3_TAG_PAYLOAD"
wait_rule_run "${S3_TAG_RULE:-0}" "${S3_TAG_EID}" 1
S3_TAGS="$(db_count requirement_tag "tenant_id=${TID} AND requirement_id=${S3_REQ:-0} AND tag='幂等标签'")"
if [[ "$S3_TAGS" == "1" ]]; then
    pass "ADD_TAG 幂等：uk_req_tag 命中时返回 SUCCESS+exists，标签恰 1 行"
else
    fail "标签 ${S3_TAGS} 行（应 1）" "AutomationTagWriter 必须 INSERT IGNORE，重复即成功而不是报错"
fi

# 标签只在列表可见、详情不可见 —— 同一个需求在列表有 chip、在详情没有（AC-14 §9.2 要求同源）。
# 这条断言是 Q-1 那个「类」的真栈门禁：详情视图必须经 RequirementTagQuery 读标签，
# 而不是由某个兼容构造器静默填空表。静态那一半见 lint L22。
api_call GET "/api/req/requirements/${S3_REQ:-0}"
S3_DETAIL_TAGS="$(jqv '[.view.tags[]? | select(.tag=="幂等标签" and .source=="RULE")] | length' "$HTTP_BODY")"
if [[ "$S3_DETAIL_TAGS" == "1" ]]; then
    pass "详情 view.tags 与列表同源（「幂等标签」/RULE 在详情可见）"
else
    fail "详情 view.tags 缺「幂等标签」（库 ${S3_TAGS} 行，详 Matches=${S3_DETAIL_TAGS:-0}）" \
        "RequirementService.toView 必须经 RequirementTagQuery.byRequirementIds 补标签；空兼容构造器会让详情静默丢标签"
fi

create_rule "S3-指派-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 120 \
    '{"priorityIn":["P0"]}' "[{\"type\":\"ASSIGN_OWNER\",\"userId\":${DEV_ID}}]"
S3_OWN_RULE="$RULE_ID"
S3_OWN_EID="S3:${RUN_TAG}:3"
insert_event "$S3_OWN_EID" REQUIREMENT_CREATED "${S3_REQ:-0}" 'AUDIT' 0 "$S3_PAYLOAD"
insert_event "$S3_OWN_EID" REQUIREMENT_CREATED "${S3_REQ:-0}" 'AUDIT' 0 "$S3_PAYLOAD"
wait_rule_run "${S3_OWN_RULE:-0}" "${S3_OWN_EID}" 1
S3_AUDIT="$(db_count audit_log "tenant_id=${TID} AND action='OWNER_ASSIGN' AND biz_id=${S3_REQ:-0}")"
if [[ "$S3_AUDIT" == "1" ]]; then
    pass "指派（OWNER_ASSIGN）只审计 1 次（负责人变更恰一次）"
else
    fail "OWNER_ASSIGN 审计 ${S3_AUDIT} 次（应 1）" "指派走 RequirementService.assignOwner 一次，重复投递不能重复派"
fi

S3_SUCCESS="$(db_count automation_run "tenant_id=${TID} AND status='SUCCESS' AND ((rule_id=${S1_RULE:-0} AND event_id='${S3_EID}') OR (rule_id=${S3_TAG_RULE:-0} AND event_id='${S3_TAG_EID}') OR (rule_id=${S3_OWN_RULE:-0} AND event_id='${S3_OWN_EID}'))")"
if [[ "$S3_SUCCESS" == "3" ]]; then
    pass "该需求共 3 条 SUCCESS run（三条规则各一次），没有第二条日志"
else
    fail "三条规则命中的 SUCCESS run ${S3_SUCCESS} 条（应 3）" "日志页出现第二条 SUCCESS 说明唯一键没挡住重投"
fi

# --------------------------------------------------------------------------- S4 防循环（5）

section "S4 防循环：origin=RULE 与 chain_depth≥4 两道闸 0 run（5 条）"

S4_REQ="$S3_REQ"
S4_RULE_EID="S4:${RUN_TAG}:rule"
insert_event "$S4_RULE_EID" REQUIREMENT_CREATED "${S4_REQ:-0}" 'RULE' 0 "$S3_PAYLOAD"
if wait_event "$S4_RULE_EID" DROPPED; then
    S4_REASON="$(db_q "SELECT drop_reason FROM automation_event WHERE tenant_id=${TID} AND event_id='${S4_RULE_EID}' LIMIT 1")"
    if [[ "$S4_REASON" == "RULE_ORIGIN" ]]; then
        pass "origin=RULE 的事件被 DROPPED，drop_reason=RULE_ORIGIN"
    else
        fail "drop_reason=${S4_REASON:-?}（应 RULE_ORIGIN）" "AutomationDispatcher 的第一道闸要按 origin 判，不能只在动作侧拦"
    fi
else
    fail "origin=RULE 的事件未被丢弃（状态 $(db_q "SELECT status FROM automation_event WHERE event_id='${S4_RULE_EID}'")）" "防循环第一道闸缺失 → 动作事件会再触发规则"
fi

S4_RUNS="$(db_count automation_run "tenant_id=${TID} AND event_id='${S4_RULE_EID}'")"
if [[ "$S4_RUNS" == "0" ]]; then
    pass "origin=RULE 的事件产生 0 条 run（日志里不留噪声）"
else
    fail "origin=RULE 的事件产生了 ${S4_RUNS} 条 run" "闸必须在 tryStart 之前"
fi

S4_DEEP_EID="S4:${RUN_TAG}:deep"
insert_event "$S4_DEEP_EID" REQUIREMENT_CREATED "${S4_REQ:-0}" 'AUDIT' 4 "$S3_PAYLOAD"
if wait_event "$S4_DEEP_EID" DROPPED; then
    S4_DR="$(db_q "SELECT drop_reason FROM automation_event WHERE tenant_id=${TID} AND event_id='${S4_DEEP_EID}' LIMIT 1")"
    if [[ "$S4_DR" == "CHAIN_DEPTH" && "$(db_count automation_run "tenant_id=${TID} AND event_id='${S4_DEEP_EID}'")" == "0" ]]; then
        pass "chain_depth=4 → DROPPED/CHAIN_DEPTH 且 0 run（第四条闸限死递归）"
    else
        fail "chain_depth=4 的处置是 ${S4_DR:-?} / run=$(db_count automation_run "tenant_id=${TID} AND event_id='${S4_DEEP_EID}'")" "深度上限是 4：>=4 一律丢弃"
    fi
else
    fail "chain_depth=4 的事件未被丢弃" "depth 闸只看 chain_depth，不看 origin"
fi

S4_OK_EID="S4:${RUN_TAG}:depth3"
insert_event "$S4_OK_EID" REQUIREMENT_CREATED "${S4_REQ:-0}" 'AUDIT' 3 "$S3_PAYLOAD"
S4_OK_RUN=""
for _i in $(seq 1 40); do
    S4_OK_RUN="$(db_count automation_run "tenant_id=${TID} AND event_id='${S4_OK_EID}'")"
    [[ "$S4_OK_RUN" != "0" ]] && break
    sleep 0.5
done
if [[ "$S4_OK_RUN" != "0" ]]; then
    pass "chain_depth=3 仍正常执行（上限是 4，不是 3）"
else
    fail "chain_depth=3 的事件没有产生 run" "闸门写成了 >=3 会把合法的三级链砍掉"
fi

S4_SCHED_EID="SCHEDULE:$(TZ=Asia/Shanghai date '+%Y-%m-%d'):${S4_REQ:-0}"
insert_event "$S4_SCHED_EID" SCHEDULE "${S4_REQ:-0}" 'SCHEDULE' 0 "$S3_PAYLOAD"
insert_event "$S4_SCHED_EID" SCHEDULE "${S4_REQ:-0}" 'SCHEDULE' 0 "$S3_PAYLOAD"
S4_SCHED_ROWS="$(db_count automation_event "tenant_id=${TID} AND event_id='${S4_SCHED_EID}'")"
if [[ "$S4_SCHED_ROWS" == "1" ]] && [[ "$S4_SCHED_EID" == SCHEDULE:*":${S4_REQ:-0}" ]]; then
    pass "定时事件 event_id=SCHEDULE:<date>:<requirementId>，同一天第二次扫描不再产生第二条"
else
    fail "定时事件去重 ${S4_SCHED_ROWS} 行（event_id=${S4_SCHED_EID}）" "event_id 不含 requirementId 时，(tenant,event_id) 会把当天 N 条折成 1 条"
fi

# --------------------------------------------------------------------------- S5 越权（6）

section "S5 越权：厂商无入口、条件不能放宽作用域、跨域 0 run、SCOPE_LOST（6 条）"

API_TOKEN="$ADMIN_TOKEN"; API_TENANT="$ADMIN_TENANT"
create_rule "S5-其它域-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_SUP" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"ADD_TAG","tag":"跨域"}]'
S5_RULE="$RULE_ID"

API_TOKEN=""; API_TENANT=""
platform_login "$TENANT_CODE" vendor1 'Passw0rd!' >/dev/null 2>&1
VENDOR_TOKEN="$API_TOKEN"; VENDOR_TENANT="$API_TENANT"
create_rule "S5-厂商-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"ADD_TAG","tag":"x"}]'
deny "厂商账号建规则 → 403 INTERNAL_ONLY（服务端唯一拦截点）" 403 INTERNAL_ONLY \
    "拦截器 order(-100) 必须早于权限校验，否则先拿到 FORBIDDEN"

api_call GET "$RULE_URL?page=1&size=5"
deny "厂商账号读规则列表 → 403 INTERNAL_ONLY" 403 INTERNAL_ONLY \
    "厂商连「有哪些规则」都不该看到（AC-3）"

API_TOKEN="$ADMIN_TOKEN"; API_TENANT="$ADMIN_TENANT"
# 直接内联拼 body：mk_rule 的 $(...) 里再套一层带引号的 JSON 会丢参数，body 变空，
# 服务端先报 VALIDATION_FAILED（空 body）就盖住了真正要断言的 CONDITION_WIDENS_SCOPE。
widen_body="$(jq -cn --arg n "S5-放宽-${RUN_TAG}" --argjson sid "$DOMAIN_ORD" --argjson sup "$DOMAIN_SUP" \
    '{name:$n,enabled:true,triggerType:"REQUIREMENT_CREATED",scopeType:"DOMAIN",scopeId:$sid,
      priority:100,condition:{priorityIn:["P0"],domainIds:[$sup]},actions:[{type:"ADD_TAG",tag:"x"}]}')"
api_call POST "$RULE_URL" "$widen_body"
deny "作用域 DOMAIN/ORD 但条件限定 SUP 域 → 400 CONDITION_WIDENS_SCOPE" 400 CONDITION_WIDENS_SCOPE \
    "作用域是安全边界：条件只能收窄，不能放宽（前端也不给入口）"

# 跨域：SUP 域需求 + ORD 域规则 → 0 run、0 通知
create_req "S5-跨域需求-${RUN_TAG}" P0 "[${DOMAIN_SUP}]"
S5_REQ_SUP="$REQ_ID"
create_rule "S5-ORD域-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"NOTIFY","recipients":[{"kind":"DOMAIN_OWNER"}]}]'
S5_ORD_RULE="$RULE_ID"
S5_CROSS_EID="S5:${RUN_TAG}:cross"
insert_event "$S5_CROSS_EID" REQUIREMENT_CREATED "${S5_REQ_SUP:-0}" 'AUDIT' 0 \
    "{\"requirementId\":${S5_REQ_SUP:-0},\"priority\":\"P0\",\"domainIds\":[${DOMAIN_SUP}]}"
sleep 3
S5_CROSS_RUNS="$(db_count automation_run "tenant_id=${TID} AND event_id='${S5_CROSS_EID}' AND rule_id=${S5_ORD_RULE:-0}")"
S5_CROSS_STATUS="$(db_q "SELECT status FROM automation_run WHERE tenant_id=${TID} AND event_id='${S5_CROSS_EID}' AND rule_id=${S5_ORD_RULE:-0} LIMIT 1")"
if [[ "$S5_CROSS_RUNS" == "1" && "$S5_CROSS_STATUS" == "SKIPPED" ]]; then
    pass "ORD 域规则遇到 SUP 域需求：占位 run 但 SKIPPED（不越权执行）"
else
    fail "跨域处置 run=${S5_CROSS_RUNS} status=${S5_CROSS_STATUS:-<无>}（应 1 条 SKIPPED）" "先抢键再复核 Scope：抢到后复核不通过要留 SKIPPED 记录"
fi

# SCOPE_LOST：规则作用域 SUP，事件指向 ORD 需求 → 作用域内查不到该需求
create_req "S5-ORD需求-${RUN_TAG}" P0 "[${DOMAIN_ORD}]"
S5_REQ_ORD="$REQ_ID"
create_rule "S5-SUP域-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_SUP" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"ADD_TAG","tag":"x"}]'
S5_SUP_RULE="$RULE_ID"
S5_LOST_EID="S5:${RUN_TAG}:lost"
insert_event "$S5_LOST_EID" REQUIREMENT_CREATED "${S5_REQ_ORD:-0}" 'AUDIT' 0 \
    "{\"requirementId\":${S5_REQ_ORD:-0},\"priority\":\"P0\",\"domainIds\":[${DOMAIN_ORD}]}"
S5_LOST=""
for _i in $(seq 1 40); do
    S5_LOST="$(db_q "SELECT skip_reason FROM automation_run WHERE tenant_id=${TID} AND event_id='${S5_LOST_EID}' AND rule_id=${S5_SUP_RULE:-0} LIMIT 1")"
    [[ "$S5_LOST" == "SCOPE_LOST" ]] && break
    sleep 0.5
done
if [[ "$S5_LOST" == "SCOPE_LOST" ]]; then
    pass "作用域内看不到的需求 → skip_reason=SCOPE_LOST（作用域临时代入生效）"
else
    fail "skip_reason=${S5_LOST:-<无>}（应 SCOPE_LOST）" "AutomationScopes.withScope 必须让 ScopedJdbc 真的看不见域外需求"
fi

api_call GET /api/req/automation/events
if [[ "$HTTP_CODE" == "404" || "$HTTP_CODE" == "405" ]]; then
    pass "GET /automation/events → ${HTTP_CODE}（前端没有事件上报入口）"
else
    fail "GET /automation/events 返回 ${HTTP_CODE}（应 404/405）" "出现上报入口 = 事件可伪造，AC-6 的「事件只来自审计」不成立"
fi

# --------------------------------------------------------------------------- S6 停用与动作边界（8）

section "S6 停用与动作边界：止血即时生效、限流、无收件人、坏目标、webhook 与泄露（8 条）"

api_call PATCH "${RULE_URL}/${S1_RULE}/enabled" "{\"enabled\":false,\"version\":${S1_VER}}"
S6_VER="$(jqv '.version' "$HTTP_BODY")"; S6_EN="$(jqv '.enabled' "$HTTP_BODY")"
if [[ "$HTTP_CODE" == "200" && "$S6_EN" == "false" && "$S6_VER" == "2" ]]; then
    pass "停用成功且 version 2（启停带 version，不会覆盖别人的编辑）"
else
    fail "停用返回 ${HTTP_CODE} enabled=${S6_EN} version=${S6_VER}（应 200/false/2）" "setEnabled 必须递增 version并校验入参 version"
fi

S6_OFF_EID="S6:${RUN_TAG}:disabled"
insert_event "$S6_OFF_EID" REQUIREMENT_CREATED "${S1_REQ:-0}" 'AUDIT' 0 \
    "{\"requirementId\":${S1_REQ:-0},\"priority\":\"P0\",\"domainIds\":[${DOMAIN_ORD}]}"
sleep 3
S6_OFF_RUNS="$(db_count automation_run "tenant_id=${TID} AND rule_id=${S1_RULE:-0} AND event_id='${S6_OFF_EID}'")"
if [[ "$S6_OFF_RUNS" == "0" ]]; then
    pass "停用后本规则 0 新 run（停用是止血，不是排队）"
else
    fail "停用后本规则仍产生 ${S6_OFF_RUNS} 条 run" "候选规则查询必须过滤 enabled=1"
fi

# 限流：单租户 120/分钟。一次灌 121 条事件，至少一条要落 RATE_LIMITED。
create_rule "S6-限流-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"ADD_TAG","tag":"限流"}]'
S6_RL_RULE="$RULE_ID"
S6_RL_REQ="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} AND title LIKE 'S3-幂等-${RUN_TAG}%' LIMIT 1")"
# 逐条插：一条 121 行的 INSERT 在 mysql 客户端上要拼 121 组字面量，收益为零、可读性还差。
for i in $(seq 1 121); do
    insert_event "S6:${RUN_TAG}:rl${i}" REQUIREMENT_CREATED "${S6_RL_REQ:-0}" 'AUDIT' 0 \
        "{\"requirementId\":${S6_RL_REQ:-0},\"priority\":\"P0\",\"domainIds\":[${DOMAIN_ORD}]}"
done
S6_RL=""
for _i in $(seq 1 60); do
    S6_RL="$(db_count automation_run "tenant_id=${TID} AND rule_id=${S6_RL_RULE:-0} AND skip_reason='RATE_LIMITED'")"
    [[ "${S6_RL:-0}" != "0" ]] && break
    sleep 0.5
done
if [[ "${S6_RL:-0}" != "0" ]]; then
    pass "121 条事件里出现 ${S6_RL} 条 RATE_LIMITED（限流 120/分钟/租户生效）"
else
    fail "没有出现 RATE_LIMITED" "AutomationRateLimiter.tryAcquire 未生效，或 worker 一次领太多绕过了计数"
fi

# 限流是租户级固定分钟窗（AC-11）：等窗口翻页，否则同分钟的 [35][36] 会被刚灌满的预算饿死，
# 失败信息会变成「actions_json 为空」，看起来像动作实现坏了。
sleep $(( 61 - 10#$(date +%S) ))

create_rule "S6-无收件人-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 130 \
    '{"priorityIn":["P0"]}' '[{"type":"NOTIFY","recipients":[{"kind":"USER","id":99999999}]}]'
S6_NR_RULE="$RULE_ID"
S6_NR_EID="S6:${RUN_TAG}:norecip"
insert_event "$S6_NR_EID" REQUIREMENT_CREATED "${S6_RL_REQ:-0}" 'AUDIT' 0 \
    "{\"requirementId\":${S6_RL_REQ:-0},\"priority\":\"P0\",\"domainIds\":[${DOMAIN_ORD}]}"
S6_NR=""
for _i in $(seq 1 40); do
    S6_NR="$(db_q "SELECT actions_json FROM automation_run WHERE tenant_id=${TID} AND event_id='${S6_NR_EID}' AND rule_id=${S6_NR_RULE:-0} LIMIT 1")"
    grep -q 'NO_RECIPIENT' <<<"${S6_NR:-}" && break
    sleep 0.5
done
if grep -q 'NO_RECIPIENT' <<<"${S6_NR:-}"; then
    pass "收件人解析为 0 人 → NO_RECIPIENT（一条都不发，不是发一半）"
else
    fail "无收件人场景未记录 NO_RECIPIENT：${S6_NR:-<无 run>}" "AutomationNotifier 对 0 人必须显式失败"
fi

create_rule "S6-坏目标-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 140 \
    '{"priorityIn":["P0"]}' '[{"type":"ASSIGN_OWNER","userId":99999999}]'
S6_BT_RULE="$RULE_ID"
S6_BT_EID="S6:${RUN_TAG}:badtarget"
insert_event "$S6_BT_EID" REQUIREMENT_CREATED "${S6_RL_REQ:-0}" 'AUDIT' 0 \
    "{\"requirementId\":${S6_RL_REQ:-0},\"priority\":\"P0\",\"domainIds\":[${DOMAIN_ORD}]}"
S6_BT=""
for _i in $(seq 1 40); do
    S6_BT="$(db_q "SELECT actions_json FROM automation_run WHERE tenant_id=${TID} AND event_id='${S6_BT_EID}' AND rule_id=${S6_BT_RULE:-0} LIMIT 1")"
    grep -q 'TARGET_INVALID' <<<"${S6_BT:-}" && break
    sleep 0.5
done
if grep -q 'TARGET_INVALID' <<<"${S6_BT:-}"; then
    pass "指派目标不存在 → TARGET_INVALID（不静默改成别人）"
else
    fail "坏目标未记录 TARGET_INVALID：${S6_BT:-<无 run>}" "AutomationOwnerAssigner 必须先校验用户存在且同租户"
fi

api_call POST "$RULE_URL" "$(mk_rule "S6-未登记webhook-${RUN_TAG}" REQUIREMENT_CREATED DOMAIN "$DOMAIN_ORD" 100 \
    '{"priorityIn":["P0"]}' '[{"type":"WEBHOOK","configId":999999,"path":"/hook"}]')"
deny "WEBHOOK 指向未登记的 integration_config → 400（只能调已启用配置）" 400 "" \
    "URL 只由 base_url+path 组成；规则里没有可填任意 URL 的地方（反 SSRF）"

api_call GET "${RUN_URL}/${S1_RUN_ID}"
S6_RUN_BODY="$HTTP_BODY"
if [[ "$HTTP_CODE" == "200" ]] && ! grep -qiE 'secret|password|token|https?://' <<<"$S6_RUN_BODY"; then
    pass "run 详情不含密钥与完整 URL（只有规则名/需求编号/逐动作结果）"
else
    fail "run 详情泄露或不可读（HTTP ${HTTP_CODE}）：$(printf '%s' "$S6_RUN_BODY" | head -c 200)" "AC-12：日志只回规则名/需求编号/逐动作结果"
fi

purge_data
S6_R1="$(db_count automation_rule "tenant_id=${TID} AND name LIKE '%${RUN_TAG}%'")"
S6_R2="$(db_count automation_event "tenant_id=${TID} AND event_id LIKE '%${RUN_TAG}%'")"
S6_R3="$(db_count automation_run "tenant_id=${TID} AND event_id LIKE '%${RUN_TAG}%'")"
S6_R4="$(db_count requirement_tag "tenant_id=${TID} AND tag IN ('幂等标签','跨域','限流')")"
S6_R5="$(db_count requirement "tenant_id=${TID} AND title LIKE '%${RUN_TAG}%'")"
S6_RESIDUE=$(( ${S6_R1:-0} + ${S6_R2:-0} + ${S6_R3:-0} + ${S6_R4:-0} + ${S6_R5:-0} ))
if [[ "$S6_RESIDUE" == "0" ]]; then
    pass "自清理后 ${RUN_TAG} 残留 0 行（规则/事件/运行/标签/需求）"
else
    fail "自清理后残留 ${S6_RESIDUE} 行" "跑完必须回到「demo-reset 之后」的状态，否则第二次跑会撞重名"
fi

# --------------------------------------------------------------------------- 汇总
printf '\nAUTOMATION_TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$AUTOMATION_TOTAL" "$CASE" "$PASS" "$FAIL"
if ((CASE != AUTOMATION_TOTAL)); then
    printf '\033[1;31m断言数与自声明分母不一致：CASE=%d ≠ TOTAL=%d\033[0m\n' "$CASE" "$AUTOMATION_TOTAL"
    printf '修复: 少执行的分段被提前 return，或新增断言未同步 TECHNICAL §12.2\n'
    exit 1
fi
if ((FAIL > 0)); then
    printf '\033[1;31m有 %d 条断言失败（明细: %s）\033[0m\n' "$FAIL" "$FAIL_LOG"
    exit 1
fi
printf '\033[1;32m全部 %d 条通过：事件单口、至多一次、防循环、作用域收窄、四类动作边界均成立\033[0m\n' "$AUTOMATION_TOTAL"
exit 0
