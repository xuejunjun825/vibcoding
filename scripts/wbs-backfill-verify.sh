#!/usr/bin/env bash
# wbs-backfill-verify.sh — 存量 PDR/RD 工时评估单回填的回归门禁（make wbs-backfill-verify）。
#
# 自声明 18 条断言，六组：
#   S1 契约（4）      开关默认关 → 404 契约 + 令牌门先于控制器 / dry-run 零写入 / 逐行 7 键 / wf_* 双表零写入
#   S2 生效（5）      目标 PDR 评估单 APPROVED / 该 PDR 可排期(200) / RD 可开工(200) / created==planned>0 / 留痕 note+comment
#   S3 幂等（3）      同 batchId 二次 planned=0&&created=0 / wf_instance 不变 / wf_history 不变
#   S4 BUS（2）       BUS 不补（无 WBS_ESTIMATE 实例）/ 报告有 TYPE_BUS 行
#   S5 人天缺失（2）  报告有 NO_ESTIMATE_DAYS 行 / 该需求仍被评估门拦（409 ESTIMATE_NOT_APPROVED）
#   S6 失败语义（2）  3.2 天 → FAILED/INVALID_ESTIMATE_DAYS 且同批其余仍 CREATED / 该次跑退出码 1
#
# 末尾 `CASE != WBS_BACKFILL_TOTAL` 即红：增删断言必须同步改声明，
# 否则「18/18 全绿」只是一句祝福（r28 的教训：条件跳过的断言让 130 变成 128）。
#
# 用法: bash scripts/wbs-backfill-verify.sh [--static-only]
#   --static-only  只跑免栈的 7 条脚本自证（改脚本/文档后秒级自查，不碰现场、不重置演示数据）
# 依赖: make platform-up && make platform-seed && make platform-demo-reset
# 夹具: 一律走 API（BUS/PDR/RD 建单、提交+pm 批单），SQL 只用于读断言与 arrangement 字段
#       （3.2 人天绕过 0.5 精度校验的唯一办法；绝不 INSERT/UPDATE wf_*）。
# 收尾: EXIT trap 自动跑 scripts/platform-demo-reset.sh，演示数据复原（含本次夹具）。
# 声明: 报告写到 .run/wbs-backfill/（.run 已 gitignore），不进 logs/teamflow。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
# 门禁脚本不能被 set -e 提前打断：失败要跑完 18 条并打印清单，而不是断在第 3 条。
set +e

T1_TENANT="${WBS_BACKFILL_TENANT:-t1}"
ADMIN_USER="${PLATFORM_ADMIN_USER:-admin}"
ADMIN_PASS="${PLATFORM_ADMIN_PASS:-Admin@12345}"
PM_USER="pm"
PM_PASS="Passw0rd!"

OPS="$ROOT/scripts/backfill-wbs-estimate.sh"
RESET_SCRIPT="$ROOT/scripts/platform-demo-reset.sh"
CTRL_JAVA="$ROOT/req-service/src/main/java/com/example/req/requirement/WbsBackfillController.java"
APP_YML="$ROOT/req-service/src/main/resources/application.yml"
REPORT_DIR="$RUN_DIR/wbs-backfill"

WBS_BACKFILL_TOTAL=18
STATIC_TOTAL=7
RUN_TAG="$(date +%m%d%H%M%S)"

SECTIONS=("契约" "生效" "幂等" "BUS 不再补" "人天缺失" "失败语义")
CASE=0
FAIL=0
SECTION_IDX=0

pass() { CASE=$((CASE + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$1"; }
fail() { CASE=$((CASE + 1)); FAIL=$((FAIL + 1)); printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$1"; printf '       修复: %s\n' "$2"; }
section() {
    SECTION_IDX="$1"
    printf '\n\033[1m==> 段 %d/%d: %s\033[0m\n' "$SECTION_IDX" "${#SECTIONS[@]}" "${SECTIONS[$((SECTION_IDX - 1))]}"
}

# ---------------------------------------------------------------------------
# 免栈自查（--static-only）：证明「脚本自己的契约」没被改坏，7 条
# ---------------------------------------------------------------------------
static_mode() {
    local s_case=0 s_fail=0 rc out
    spass() { s_case=$((s_case + 1)); printf '  \033[1;32mPASS\033[0m [s%02d] %s\n' "$s_case" "$1"; }
    sfail() { s_case=$((s_case + 1)); s_fail=$((s_fail + 1)); printf '  \033[1;31mFAIL\033[0m [s%02d] %s\n' "$s_case" "$1"; printf '       修复: %s\n' "$2"; }

    printf '\033[1m==> 静态自查（免栈，%d 条）\033[0m\n' "$STATIC_TOTAL"

    rc=0; bash -n "$OPS" >/dev/null 2>&1 || rc=$?
    (( rc == 0 )) && spass "backfill-wbs-estimate.sh 语法（bash -n）" \
                  || sfail "backfill-wbs-estimate.sh 语法错误" "bash -n $OPS 看报错行"

    rc=0; bash -n "$0" >/dev/null 2>&1 || rc=$?
    (( rc == 0 )) && spass "wbs-backfill-verify.sh 语法（bash -n）" \
                  || sfail "wbs-backfill-verify.sh 语法错误" "bash -n $0 看报错行"

    if grep -q "WBS_BACKFILL_TOTAL=${WBS_BACKFILL_TOTAL}" "$0"; then
        spass "自声明断言总数 ${WBS_BACKFILL_TOTAL}（与 WBS_BACKFILL_TOTAL 一致）"
    else
        sfail "自声明断言总数漂移" "脚本里必须有 WBS_BACKFILL_TOTAL=${WBS_BACKFILL_TOTAL}"
    fi

    rc=0; out="$("$OPS" --help 2>&1)" || rc=$?
    if (( rc == 0 )) && grep -q -- '--tenant' <<<"$out" && grep -q -- '--batch-id' <<<"$out" \
        && grep -q -- '--report' <<<"$out" && grep -q -- '--apply' <<<"$out"; then
        spass "运维壳 --help 退出 0 且列出 --tenant/--batch-id/--report/--apply"
    else
        sfail "运维壳 --help 契约不符（rc=${rc}）" "帮助里必须能查到四个参数与 0/1/2/3 退出码口径"
    fi

    rc=0; out="$("$OPS" --report /tmp/wbs-bf-x.json 2>&1)" || rc=$?
    if (( rc == 2 )) && grep -q -- '--tenant 必填' <<<"$out"; then
        spass "缺 --tenant → 用法错误退出 2（不拿服务端当参数校验器）"
    else
        sfail "缺 --tenant 的退出码应为 2（实际 ${rc}）" "参数错误必须由壳自己判 2，不能落到传输/业务码"
    fi

    # REQ_URL 由 lib.sh 从 REQ_PORT 派生（.env 也走这条），所以用 REQ_PORT=1 制造连不上。
    rc=0; out="$(REQ_PORT=1 BACKFILL_TIMEOUT=5 "$OPS" --tenant "$T1_TENANT" 2>&1)" || rc=$?
    if (( rc == 3 )) && grep -q '传输失败' <<<"$out"; then
        spass "连不上服务（000）→ 传输失败退出 3（与 FAILED 行区分开）"
    else
        sfail "传输失败应退出 3（实际 ${rc}）" "CI 只凭退出码判红：000/502/503/504 必须是 3，不能混成 1（用 REQ_PORT 改端点）"
    fi

    # 文档读法门（r29 D-1）：dry-run 的待建行是 PLANNED，approverSource/flags 只有 apply 行才有值。
    # 把待建行写成 `result=CREATED` 会让运维 grep 出「没有单要建」的反结论（而 planned>0）。
    RUNBOOK="$ROOT/docs/teamflow/20261118-r29-wbs-estimate-backfill/RUNBOOK.md"
    if [[ -f "$RUNBOOK" ]] && grep -q 'result=PLANNED' "$RUNBOOK" \
        && grep -q '只有 apply 行才有值' "$RUNBOOK" \
        && ! grep -q '^| `result=CREATED`' "$RUNBOOK"; then
        spass "RUNBOOK dry-run 读法 = result=PLANNED，approverSource/flags 标注 apply-only"
    else
        sfail "RUNBOOK 读法与报告实际值不一致" "§1 表写 result=PLANNED，并注明 approverSource/flags 只有 apply 行才有值"
    fi

    printf '\n静态自查 %d/%d' "$s_case" "$STATIC_TOTAL"
    if (( s_fail > 0 )); then printf '（%d 条失败）\n' "$s_fail"; else printf ' 全绿\n'; fi
    if (( s_case != STATIC_TOTAL )); then
        err "静态断言数漂移: 实际 ${s_case}，声明 ${STATIC_TOTAL}"
        return 1
    fi
    (( s_fail == 0 ))
}

if [[ "${1:-}" == "--static-only" ]]; then
    static_mode && exit 0 || exit 1
fi
[[ -z "${1:-}" ]] || die "未知参数: $1（可用: --static-only）"

# ---------------------------------------------------------------------------
# 现场门禁：18 条
# ---------------------------------------------------------------------------
need_cmd curl
need_cmd jq
need_cmd mysql
mkdir -p "$REPORT_DIR"

log "wbs-backfill-verify：租户 ${T1_TENANT}，断言 ${WBS_BACKFILL_TOTAL} 条，报告目录 ${REPORT_DIR}"

req_ready || die "req-service 未就绪（${REQ_URL}/internal/health 非 UP/db=up/flyway）：先 make platform-up"
db_up || die "MySQL 不可连（${PLATFORM_DB_HOST}:${PLATFORM_DB_PORT}/${PLATFORM_DB_NAME}）：先 make platform-up"

TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${T1_TENANT}' LIMIT 1")"
[[ -n "$TID" ]] || die "租户 ${T1_TENANT} 不存在：先 make platform-seed"
DOMAIN_ID="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
[[ -n "$DOMAIN_ID" ]] || die "租户 ${T1_TENANT} 没有业务域：先 make platform-seed"
POOLED_BUS="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} AND type='BUS' AND status='POOLED' ORDER BY id LIMIT 1")"
[[ -n "$POOLED_BUS" ]] || die "租户 ${T1_TENANT} 没有 POOLED 状态的 BUS 可挂父需求：先 make platform-demo-reset"
DEMO_ROWS="$(db_count requirement "tenant_id=${TID} AND code LIKE 'DEMO-R-%'")"
[[ "${DEMO_ROWS:-0}" -gt 0 ]] || die "演示数据缺失（DEMO-R-* = 0）：先 make platform-demo-reset"

platform_login "$T1_TENANT" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null || die "管理员登录失败（${ADMIN_USER}）：先 make platform-seed"

FIX_ID=""
FIX_CODE=""
create_req() { # <type> <title> <parentId|""> <estimateDays|"">
    local type="$1" title="$2" parent="$3" estimate="$4" body
    body="$(jq -cn --arg t "$type" --arg ti "$title" --arg p "$parent" --arg e "$estimate" \
        --argjson d "$DOMAIN_ID" \
        '{type:$t,title:$ti,priority:"P2",domainIds:[$d],expectedDeliveryDate:"2026-12-31"}
         + (if $p != "" then {parentId:($p|tonumber)} else {} end)
         + (if $e != "" then {estimateDays:($e|tonumber)} else {} end)')"
    api_call POST /api/req/requirements "$body"
    if [[ "$HTTP_CODE" != "201" ]]; then
        return 1
    fi
    FIX_ID="$(jget "$HTTP_BODY" '.id')"
    FIX_CODE="$(jget "$HTTP_BODY" '.code')"
    [[ -n "$FIX_ID" && "$FIX_ID" != "null" ]]
}

approve_estimate() { # <rid> <days> <note> —— 借引擎走完整流程（提交→pm 批），不直插 wf_*
    local rid="$1" days="$2" note="$3" saved_token="$API_TOKEN" saved_tenant="$API_TENANT" task_id="" i status
    api_call POST "/api/req/requirements/${rid}/estimate" \
        "$(jq -cn --argjson d "$days" --arg n "$note" '{estimateDays:$d, note:$n}')"
    if [[ "$HTTP_CODE" != "201" ]]; then
        API_TOKEN="$saved_token"; API_TENANT="$saved_tenant"
        err "提交评估单失败（需求 ${rid}）: HTTP ${HTTP_CODE} $(printf '%s' "$HTTP_BODY" | head -c 200)"
        return 1
    fi
    platform_login "$T1_TENANT" "$PM_USER" "$PM_PASS" >/dev/null || { API_TOKEN="$saved_token"; API_TENANT="$saved_tenant"; return 1; }
    for i in 1 2 3 4 5; do
        api_call GET "/api/req/approvals/todo?page=1&size=100"
        task_id="$(jget "$HTTP_BODY" "[.items[] | select(.bizId == ${rid} and .bizType == \"WBS_ESTIMATE\")][0].taskId")"
        [[ -z "$task_id" || "$task_id" == "null" ]] && break
        api_call POST "/api/req/approvals/tasks/${task_id}/actions" '{"action":"APPROVE","comment":"回填门禁夹具前置审批"}'
        [[ "$HTTP_CODE" == "200" ]] || break
    done
    API_TOKEN="$saved_token"; API_TENANT="$saved_tenant"
    api_call GET "/api/req/requirements/${rid}"
    status="$(jget "$HTTP_BODY" '.estimate.status')"
    [[ "$status" == "APPROVED" ]] || { err "评估单未生效（需求 ${rid}，status=${status:-<缺失>}）"; return 1; }
    return 0
}

# ---- 夹具（全部 API 建；SQL 只改 arrangement 字段）--------------------------
# 收尾必须在建夹具之前挂上：夹具建到一半 die 时也要复位，否则残留单会污染下一次跑。
FIXTURES_SET=0
cleanup() {
    local rc=$?
    if (( FIXTURES_SET == 1 )); then
        FIXTURES_SET=0
        log "收尾：复位演示数据（${RESET_SCRIPT##*/}，含本次夹具）"
        local out
        if out="$(bash "$RESET_SCRIPT" 2>&1)"; then
            ok "演示数据已复位（下一次跑门禁仍是干净起点）"
        else
            err "演示数据复位失败：手工执行 make platform-demo-reset（夹具可能残留在库里）"
            printf '%s\n' "$out" | tail -n 12 >&2
        fi
    fi
    exit "$rc"
}
FIXTURES_SET=1
trap cleanup EXIT

log "建立夹具（BUS / 裸 PDR / 无人天 PDR / RD+其父 PDR）"
# 存量数据的两个硬事实（本脚本据此造夹具）：
# 1) BUS/PDR 的人天是子需求汇总，创建与 PUT 都会被 400 PARENT_DAYS_READONLY 拦下，
#    所以「有一个人天、但没有评估单」的存量父需求只能用 arrangement SQL 摆值——
#    这正是回填要修的形态，也解释了为什么 BUS 必须先按 type 判成 TYPE_BUS 而不是 NO_ESTIMATE_DAYS。
# 2) RD 是叶子，创建时可以直接填 estimateDays，且 rollup 会把父 PDR 汇总成同一值（不破坏父的 APPROVED）。
set_days() { # <requirementId> <期望值，如 5.0> <为什么>
    db_q "UPDATE requirement SET estimate_days=$2 WHERE id=$1 AND tenant_id=${TID}" >/dev/null
    local got
    got="$(db_q "SELECT estimate_days FROM requirement WHERE id=$1")"
    [[ "$got" == "$2" ]] || die "arrangement 写入失败（需求 $1 estimate_days=${got:-<空>}，期望 $2）：$3"
}
create_req BUS "回填门禁-BUS-${RUN_TAG}" "" "" || die "夹具 BUS 建立失败（HTTP ${HTTP_CODE}）"
BF_BUS_ID="$FIX_ID"; BF_BUS_CODE="$FIX_CODE"
# 裸 PDR：有人天（arrangement）、没有评估单 = 存量待补的典型形态
create_req PDR "回填门禁-裸PDR-${RUN_TAG}" "$POOLED_BUS" "" || die "夹具裸 PDR 建立失败（HTTP ${HTTP_CODE}）"
BARE_ID="$FIX_ID"; BARE_CODE="$FIX_CODE"
set_days "$BARE_ID" 5.0 "PDR 人天 API 只读，存量行只能直接摆值"
# 无人天 PDR：连人天都没有，回填只能报 NO_ESTIMATE_DAYS，不得凭空编人天
create_req PDR "回填门禁-无人天-${RUN_TAG}" "$POOLED_BUS" "" || die "夹具无人天 PDR 建立失败（HTTP ${HTTP_CODE}）"
NOEST_ID="$FIX_ID"; NOEST_CODE="$FIX_CODE"
# RD 的父 PDR 必须先 SCHEDULED 才能挂 RD，所以先给它本身补一张评估单并通过。
create_req PDR "回填门禁-RD父-${RUN_TAG}" "$POOLED_BUS" "" || die "夹具 RD 父 PDR 建立失败（HTTP ${HTTP_CODE}）"
RD_PARENT_ID="$FIX_ID"
approve_estimate "$RD_PARENT_ID" 2 "回填门禁夹具：RD 父 PDR" || die "夹具前置：RD 父 PDR 评估单未通过"
api_call POST "/api/req/requirements/${RD_PARENT_ID}/actions" '{"action":"schedule"}'
[[ "$HTTP_CODE" == "200" ]] || die "夹具前置：RD 父 PDR 排期失败（HTTP ${HTTP_CODE}）"
create_req RD "回填门禁-RD-${RUN_TAG}" "$RD_PARENT_ID" "2" || die "夹具 RD 建立失败（HTTP ${HTTP_CODE}）"
RD_ID="$FIX_ID"; RD_CODE="$FIX_CODE"

printf '\n夹具: BUS=%s(%s) BARE=%s(%s) NOEST=%s(%s) RD_PARENT=%s RD=%s(%s) domain=%s\n' \
    "$BF_BUS_ID" "$BF_BUS_CODE" "$BARE_ID" "$BARE_CODE" "$NOEST_ID" "$NOEST_CODE" \
    "$RD_PARENT_ID" "$RD_ID" "$RD_CODE" "$DOMAIN_ID"

# ---- S1 契约：dry-run 偷偷写库是回填最贵的缺陷 -----------------------------
section 1
DRY_REPORT="$REPORT_DIR/dryrun-${RUN_TAG}.json"
INS_BEFORE="$(db_count wf_instance)"
HIS_BEFORE="$(db_count wf_history)"
DRY_RC=0
"$OPS" --tenant "$T1_TENANT" --report "$DRY_REPORT" >"$REPORT_DIR/dryrun-${RUN_TAG}.out" 2>&1 || DRY_RC=$?

# 1) 开关关闭必须是 404（生产默认关）。真实链路要「重启一个关着开关的实例」才能现场看 404，
#    所以这里用两条静态契约 + 一条现场断言合成：默认值 false、控制器 404/BACKFILL_DISABLED 分支存在、
#    且 /internal 令牌门确实先于控制器（错令牌 401 而不是控制器的 403/404）。
static_switch=0
if grep -Eq 'PLATFORM_OPS_BACKFILL_ENABLED *: *false' "$APP_YML" && grep -q 'BACKFILL_DISABLED' "$CTRL_JAVA"; then
    static_switch=1
fi
api_direct POST "$REQ_URL" "/internal/wbs-estimate-backfill?tenant=${T1_TENANT}" "" \
    -H "X-Internal-Token: wrong-${RUN_TAG}"
if (( static_switch == 1 )) && [[ "$HTTP_CODE" == "401" ]] && [[ "$(jget "$HTTP_BODY" '.code')" == "INTERNAL_TOKEN_INVALID" ]]; then
    pass "未开开关的契约在（yml 默认 false + 控制器 404 BACKFILL_DISABLED），且令牌门先于控制器（错令牌 401）"
else
    fail "开关/令牌门契约不符（static=${static_switch} http=${HTTP_CODE} code=$(jget "$HTTP_BODY" '.code')）" \
        "application.yml 的 platform.ops.backfill.enabled 必须默认 false（\${PLATFORM_OPS_BACKFILL_ENABLED:false}）；错令牌由 InternalTokenFilter 返回 401 INTERNAL_TOKEN_INVALID（控制器 403 分支不可达，别断言 403）"
fi

# 2) dry-run 只读：mode=dry-run 且 created=0
dry_mode="$(jq -r '.mode // "?"' "$DRY_REPORT" 2>/dev/null)"
dry_created="$(jq -r '.summary.created // "?"' "$DRY_REPORT" 2>/dev/null)"
dry_planned="$(jq -r '.summary.planned // "?"' "$DRY_REPORT" 2>/dev/null)"
if (( DRY_RC == 0 )) && [[ "$dry_mode" == "dry-run" ]] && [[ "$dry_created" == "0" ]] && [[ "${dry_planned:-0}" -gt 0 ]]; then
    pass "dry-run：mode=dry-run planned=${dry_planned} created=0（计划可信，且没建任何单）"
else
    fail "dry-run 报告不符（rc=${DRY_RC} mode=${dry_mode} planned=${dry_planned} created=${dry_created}）" \
        "dry-run 必须 mode=dry-run、created=0、planned>0；rc=2/3 时先看 ${REPORT_DIR}/dryrun-${RUN_TAG}.out"
fi

# 3) 逐行 7 键：code/type/status/estimateDays/instanceId/result/reason。
#    req-service 的 Jackson 是 non_null，空值键会被吞掉（instanceId/reason 在 planned 行为 null），
#    所以断言「该有值的行必须有该键」，而不是机械要求每行都出现 7 个名字。
if jq -e '(.rows|length) > 0
        and all(.rows[]; has("code") and has("type") and has("status") and has("result"))
        and all(.rows[] | select((.estimateDays // null) != null); has("estimateDays"))
        and all(.rows[] | select(.result == "CREATED"); has("instanceId"))
        and all(.rows[] | select(.result == "SKIPPED" or .result == "FAILED"); has("reason"))' \
        "$DRY_REPORT" >/dev/null 2>&1; then
    pass "逐行 7 键（code/type/status/estimateDays/instanceId/result/reason）该有值的行全在"
else
    fail "行结构缺键（code/type/status/estimateDays/instanceId/result/reason）" \
        "契约键缺失就是脚本与前端都读不到：jq -r '.rows[0]' ${DRY_REPORT} 逐行对"
fi

# 4) 双表零写入：dry-run 不许碰 wf_instance / wf_history
INS_AFTER="$(db_count wf_instance)"
HIS_AFTER="$(db_count wf_history)"
if [[ "$INS_BEFORE" == "$INS_AFTER" ]] && [[ "$HIS_BEFORE" == "$HIS_AFTER" ]]; then
    pass "dry-run 零写入：wf_instance ${INS_BEFORE}→${INS_AFTER}、wf_history ${HIS_BEFORE}→${HIS_AFTER}"
else
    fail "dry-run 偷写了库：wf_instance ${INS_BEFORE}→${INS_AFTER}、wf_history ${HIS_BEFORE}→${HIS_AFTER}" \
        "dry-run 必须只读；任何 wf_* 变化都会让运维在「预演」时留下真实单据。若窗口内确有别的回填进程在同一租户上跑（回填是租户级操作），也会表现成 +N——先确认没有并发回填再排查"
fi

# ---- S2 生效：真批准，真放行 ----------------------------------------------
section 2
APPLY_REPORT="$REPORT_DIR/apply-${RUN_TAG}.json"
APPLY_RC=0
"$OPS" --tenant "$T1_TENANT" --apply --report "$APPLY_REPORT" >"$REPORT_DIR/apply-${RUN_TAG}.out" 2>&1 || APPLY_RC=$?
MAIN_BATCH="$(jq -r '.batchId // empty' "$APPLY_REPORT" 2>/dev/null)"
apply_planned="$(jq -r '.summary.planned // "?"' "$APPLY_REPORT" 2>/dev/null)"
apply_created="$(jq -r '.summary.created // "?"' "$APPLY_REPORT" 2>/dev/null)"
apply_failed="$(jq -r '.summary.failed // "?"' "$APPLY_REPORT" 2>/dev/null)"

# 5) created==planned>0（派生的算数在报告里，脚本不重算 planned）
if (( APPLY_RC == 0 )) && [[ "$apply_created" == "$apply_planned" ]] && [[ "${apply_created:-0}" -gt 0 ]] \
    && [[ "$apply_failed" == "0" ]]; then
    pass "apply：created==planned=${apply_created}>0、failed=0、退出码 0（batchId=${MAIN_BATCH}）"
else
    fail "apply 报告不符（rc=${APPLY_RC} planned=${apply_planned} created=${apply_created} failed=${apply_failed}）" \
        "apply 必须 planned==created+failed：落不下的行要以 FAILED 出现，不能被静默吞掉；planned=0 且夹具却已 APPROVED 时，先确认没有另一个回填进程并发补了同一租户（详见 ${REPORT_DIR}/apply-${RUN_TAG}.out）"
fi

# 6) 目标 PDR 的评估单已 APPROVED
api_call GET "/api/req/requirements/${BARE_ID}"
bare_status="$(jget "$HTTP_BODY" '.estimate.status')"
if [[ "$bare_status" == "APPROVED" ]]; then
    pass "被回填的 PDR ${BARE_CODE} 评估单 status=APPROVED（页面与详情同源）"
else
    fail "被回填的 PDR ${BARE_CODE} 评估单未生效（status=${bare_status:-<缺失>}）" \
        "回填的目的一是让存量需求过 r26 评估门：detail.estimate.status 必须 APPROVED"
fi

# 7) 该 PDR 现在可以排期（200）
api_call POST "/api/req/requirements/${BARE_ID}/actions" '{"action":"schedule"}'
if [[ "$HTTP_CODE" == "200" ]]; then
    pass "PDR ${BARE_CODE} 排期 200（回填前应是 409 ESTIMATE_NOT_APPROVED）"
else
    fail "PDR ${BARE_CODE} 排期仍失败（HTTP ${HTTP_CODE} $(jget "$HTTP_BODY" '.code')）" \
        "回填后该 PDR 必须能 schedule：否则等于没补"
fi

# 8) RD 现在可以开工（200）
api_call POST "/api/req/requirements/${RD_ID}/actions" '{"action":"start_dev"}'
if [[ "$HTTP_CODE" == "200" ]]; then
    pass "RD ${RD_CODE} 开始开发 200（评估门放行）"
else
    fail "RD ${RD_CODE} 开工仍失败（HTTP ${HTTP_CODE} $(jget "$HTTP_BODY" '.code')）" \
        "回填后 RD 必须能 start_dev"
fi

# 9) 留痕：SUBMIT 快照 note 与 APPROVE 评论都带批次号（wf_history 里可追溯）。
#    注意列名是 detail_json（TECH §3.7 写的 wf_history.detail 在库里不存在，照抄会静默查空）。
bare_inst="$(jq -r --arg c "$BARE_CODE" '.rows[] | select(.code == $c) | .instanceId // empty' "$APPLY_REPORT" 2>/dev/null)"
note_hits=0
comment_hits=0
if [[ -n "$bare_inst" ]]; then
    note_hits="$(db_q "SELECT COUNT(*) FROM wf_history WHERE instance_id=${bare_inst} AND action='SUBMIT' AND detail_json LIKE '%存量回填（${MAIN_BATCH}）%'")"
    comment_hits="$(db_q "SELECT COUNT(*) FROM wf_history WHERE instance_id=${bare_inst} AND action='APPROVE' AND comment LIKE '%存量回填（${MAIN_BATCH}）%'")"
fi
if [[ "${note_hits:-0}" -ge 1 ]] && [[ "${comment_hits:-0}" -ge 1 ]]; then
    pass "留痕：实例 ${bare_inst} 的 SUBMIT note 与 APPROVE 评论均含「存量回填（${MAIN_BATCH}）」"
else
    fail "批次留痕缺失（instance=${bare_inst:-<无>} SUBMIT=${note_hits:-0} APPROVE=${comment_hits:-0}）" \
        "回填必须可审计：note/comment 都要带批次号，否则事后无法区分人工单与回填单"
fi

# ---- S3 幂等：同一批再跑一次不许再建单 --------------------------------------
section 3
INS_MID="$(db_count wf_instance)"
HIS_MID="$(db_count wf_history)"
RERUN_RC=0
"$OPS" --tenant "$T1_TENANT" --batch-id "$MAIN_BATCH" --apply --report "$REPORT_DIR/apply-again-${RUN_TAG}.json" \
    >"$REPORT_DIR/apply-again-${RUN_TAG}.out" 2>&1 || RERUN_RC=$?
again_planned="$(jq -r '.summary.planned // "?"' "$REPORT_DIR/apply-again-${RUN_TAG}.json" 2>/dev/null)"
again_created="$(jq -r '.summary.created // "?"' "$REPORT_DIR/apply-again-${RUN_TAG}.json" 2>/dev/null)"

# 10) 二次 apply：planned=0 且 created=0
if (( RERUN_RC == 0 )) && [[ "$again_planned" == "0" ]] && [[ "$again_created" == "0" ]]; then
    pass "同批二次 apply：planned=0 created=0（batchId=${MAIN_BATCH}，退出码 0）"
else
    fail "二次 apply 仍有计划（rc=${RERUN_RC} planned=${again_planned} created=${again_created}）" \
        "已 APPROVED 的需求必须走 HAS_APPROVED_ESTIMATE 跳过，而不是重复建单"
fi

# 11) 二次 apply 不新增实例
INS_END="$(db_count wf_instance)"
if [[ "$INS_MID" == "$INS_END" ]]; then
    pass "二次 apply 未新增 wf_instance（${INS_MID}→${INS_END}）"
else
    fail "二次 apply 新增了实例（${INS_MID}→${INS_END}）" "幂等被破：同批重跑在建新单"
fi

# 12) 二次 apply 不新增历史
HIS_END="$(db_count wf_history)"
if [[ "$HIS_MID" == "$HIS_END" ]]; then
    pass "二次 apply 未新增 wf_history（${HIS_MID}→${HIS_END}）"
else
    fail "二次 apply 新增了历史（${HIS_MID}→${HIS_END}）" "没有新单据就不该有新审批痕迹"
fi

# ---- S4 BUS：业务需求不走工时评估 ------------------------------------------
section 4
bus_inst="$(db_count wf_instance "biz_type='WBS_ESTIMATE' AND biz_id=${BF_BUS_ID}")"
if [[ "${bus_inst:-1}" == "0" ]]; then
    pass "BUS ${BF_BUS_CODE} 没有被创建 WBS_ESTIMATE 实例（BUS 无工时评估语义）"
else
    fail "BUS ${BF_BUS_CODE} 被建了 ${bus_inst} 个评估实例" \
        "BUS 永远不该进评估门：回填必须先按 type 过滤（BUS → SKIPPED/TYPE_BUS）"
fi

if jq -e --arg c "$BF_BUS_CODE" 'any(.rows[]; .code == $c and .result == "SKIPPED" and .reason == "TYPE_BUS")' \
        "$APPLY_REPORT" >/dev/null 2>&1; then
    pass "报告里 BUS ${BF_BUS_CODE} 为 SKIPPED/TYPE_BUS（跳过原因可读，不是静默略过）"
else
    fail "报告没有 BUS 的 TYPE_BUS 行" \
        "跳过必须留原因：ops 看到 skipped 才知道为什么没补"
fi

# ---- S5 人天缺失：补不了，但要留下可读原因 ---------------------------------
section 5
if jq -e --arg c "$NOEST_CODE" 'any(.rows[]; .code == $c and .reason == "NO_ESTIMATE_DAYS")' \
        "$APPLY_REPORT" >/dev/null 2>&1; then
    pass "无人天的 PDR ${NOEST_CODE} 在报告里为 NO_ESTIMATE_DAYS"
else
    fail "无人天的 PDR ${NOEST_CODE} 缺少 NO_ESTIMATE_DAYS 行" \
        "人天为空的存量需求是回填的主场景：必须显式报 NO_ESTIMATE_DAYS，而不是凭空编一个人天"
fi

api_call POST "/api/req/requirements/${NOEST_ID}/actions" '{"action":"schedule"}'
if [[ "$HTTP_CODE" == "409" ]] && [[ "$(jget "$HTTP_BODY" '.code')" == "ESTIMATE_NOT_APPROVED" ]]; then
    pass "无人天的 PDR ${NOEST_CODE} 仍被评估门拦住（409 ESTIMATE_NOT_APPROVED，没被「假批准」放行）"
else
    fail "无人天的 PDR ${NOEST_CODE} 排期结果不符（HTTP ${HTTP_CODE} code=$(jget "$HTTP_BODY" '.code')）" \
        "回填不该绕过评估门：无人天就是补不了，必须保持 409"
fi

# ---- S6 失败语义：一行失败不许拖垮整批 -------------------------------------
section 6
create_req PDR "回填门禁-非半数-${RUN_TAG}" "$POOLED_BUS" "" || die "S6 夹具建立失败（HTTP ${HTTP_CODE}）"
BAD_ID="$FIX_ID"; BAD_CODE="$FIX_CODE"
create_req PDR "回填门禁-同批好行-${RUN_TAG}" "$POOLED_BUS" "" || die "S6 好行夹具建立失败（HTTP ${HTTP_CODE}）"
GOOD6_ID="$FIX_ID"; GOOD6_CODE="$FIX_CODE"
# 3.2 天绕不过创建/提交时的 0.5 精度校验（RequirementRules.checkHalfDay），
# 只能事后改 arrangement 字段估计值——这是本脚本唯一一处业务字段写入，不碰任何 wf_*。
set_days "$BAD_ID" 3.2 "非法人天要能在建单前被拦成 FAILED/INVALID_ESTIMATE_DAYS"
set_days "$GOOD6_ID" 3.0 "同批好行：证明一行失败不拖垮整批"

S6_REPORT="$REPORT_DIR/apply-invalid-${RUN_TAG}.json"
S6_RC=0
"$OPS" --tenant "$T1_TENANT" --apply --report "$S6_REPORT" >"$REPORT_DIR/apply-invalid-${RUN_TAG}.out" 2>&1 || S6_RC=$?

# 17) 非法人天 → FAILED/INVALID_ESTIMATE_DAYS，同批其余照常 CREATED
if jq -e --arg b "$BAD_CODE" --arg g "$GOOD6_CODE" \
        'any(.rows[]; .code == $b and .result == "FAILED" and .reason == "INVALID_ESTIMATE_DAYS")
         and any(.rows[]; .code == $g and .result == "CREATED")' "$S6_REPORT" >/dev/null 2>&1; then
    pass "3.2 人天 ${BAD_CODE} → FAILED/INVALID_ESTIMATE_DAYS，同批 ${GOOD6_CODE} 仍 CREATED（失败隔离）"
else
    fail "失败语义不符（3.2 天应 FAILED/INVALID_ESTIMATE_DAYS 且同批好行 CREATED）" \
        "单行失败必须整体回滚本行（不留 PENDING 孤儿实例）且不影响其余行：见 ${REPORT_DIR}/apply-invalid-${RUN_TAG}.out"
fi

# 18) 有 FAILED 行 → 退出码 1（CI 只凭退出码判红）
if (( S6_RC == 1 )); then
    pass "含 FAILED 行时运维壳退出码 1（与 0/2/3 互斥，CI 可只凭码判红）"
else
    fail "含 FAILED 行的退出码应为 1（实际 ${S6_RC}）" \
        "退出码是契约：0 无失败 / 1 有失败行 / 2 用法环境 / 3 传输"
fi

# ---------------------------------------------------------------------------
printf '\n\033[1m==> 结果: %d/%d 条断言，%d 失败\033[0m\n' "$CASE" "$WBS_BACKFILL_TOTAL" "$FAIL"
echo "WBS_BACKFILL_TOTAL=${WBS_BACKFILL_TOTAL} PASS=$((CASE - FAIL)) FAIL=${FAIL}"

if (( CASE != WBS_BACKFILL_TOTAL )); then
    err "断言数漂移: 实际执行 ${CASE} 项，声明 ${WBS_BACKFILL_TOTAL} 项——增删断言时必须同步 WBS_BACKFILL_TOTAL，否则 ${WBS_BACKFILL_TOTAL}/${WBS_BACKFILL_TOTAL} 口径失效"
    exit 1
fi
if (( FAIL > 0 )); then
    err "wbs-backfill-verify 失败 ${FAIL}/${WBS_BACKFILL_TOTAL}（逐条修复提示见上）"
    exit 1
fi
ok "wbs-backfill-verify ${CASE}/${WBS_BACKFILL_TOTAL} 全绿（回填：契约/生效/幂等/BUS/缺人天/失败语义）"
exit 0
