#!/usr/bin/env bash
# release-flow-verify.sh — r30 发版分级审批链 + 不可变链路快照门禁（AC-20）。
#
# 32 条断言，六组：
#   S1 绑定与等级（6）       6 个槽可读、NORMAL 三级 / REQ_DEPLOY 两级、换绑只影响新单、
#                           reset 回默认、非法等级 400 detail.field、非管理员 403
#   S2 正常链（7）           逐级建待办、NODE_APPROVED 三键留痕、中间态 LOCKED、
#                           末级 PUBLISHED、approvalLevels 序列、发布单两级链
#   S3 驳回与越权（6）       驳回回 PLAN 且五个驳回字段齐全、重提新实例回 n1、
#                           越级 403（两个角色各一条）、重复发起 409、REJECTED 单不可执行
#   S4 链路与快照（7）       四段同源、与 deliver 事实一致、连续读稳定、提交出快照、
#                           改报告后 hash 不变且 signoff stale、回放只读且可重复、被拒不落轮次
#   S5 负向（4）             计划未批 409 PLAN_NOT_APPROVED、未三签 409 QUALITY_GATE_FAILED
#                           （QG-02）、CONDITIONAL 未豁免 409、EMERGENCY 缺原因 400
#   S6 回归与卫生（2）       无发布路由 / 快照表无 UPDATE·DELETE；提交区间 BASE..HEAD 内
#                           7 个 *pom.xml 与 web/package.json·web/pnpm-lock.yaml 无 diff，
#                           迁移只允许 0/1 条新增 V14（M/D/其它 A 即红）；判据自检 1 正 3 反同场执行
#
# 用法：
#   bash scripts/release-flow-verify.sh                # 全量（需 make platform-up 已起栈必、已 demo-reset）
#   bash scripts/release-flow-verify.sh --static-only  # 不连栈，只查脚本/文档/契约自洽（8 条）
#
# 约定（与 wbs-backfill-verify 一致）：
#   - 脚本自声明 RELEASE_FLOW_TOTAL=32，CASE 必须等于 TOTAL，缺一条就是红；
#   - 前置缺失（栈没起、种子账号不在）直接 die，不 skip（r28 教训：条件跳过=假绿）；
#   - 第 32 条只读 git 对象库（BASE..HEAD），工作树脏不影响结论；基线默认 main，
#     RELEASE_FLOW_BASE_REF 可覆盖，解析失败判红而不回退 HEAD；
#   - S1 开头幂等复位六个绑定槽（setup，不计数）；EXIT trap 复位演示数据（RELEASE_FLOW_SKIP_RESET=1 可跳过）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
set +e

RELEASE_FLOW_TOTAL=32
CASE=0
PASS=0
FAIL=0
FIXTURES_SET=0
STATIC_ONLY=0
[[ "${1:-}" == "--static-only" ]] && STATIC_ONLY=1
[[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && {
    sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

REPORT_DIR="${RUN_DIR:-${ROOT}/.run}/release-flow"
FAIL_LOG=""
TAG="$(date +%m%d%H%M%S)"
T1_TENANT="${T1_TENANT:-t1}"
ADMIN_USER="admin"
ADMIN_PASS="Admin@12345"
USER_PASS="Passw0rd!"
USERS="pm tl1 vendor1 mgr1 admin qa1 po1"
ADMIN_TOKEN=""
ADMIN_TENANT=""
TID=""
DOMAIN_ID=""
MODULE_ID=""
ANY_REQ=""

section() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
pass() { CASE=$((CASE + 1)); PASS=$((PASS + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$*"; }
fail() {
    CASE=$((CASE + 1)); FAIL=$((FAIL + 1))
    printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$1"
    [[ -n "${2:-}" ]] && printf '        修复: %s\n' "$2"
    [[ -n "${FAIL_LOG:-}" ]] && printf '[%02d] %s | 修复: %s\n' "$CASE" "$1" "${2:-}" >>"$FAIL_LOG"
}

jqv() { printf '%s' "${2:-}" | jq -r "$1" 2>/dev/null; }
json_len() { printf '%s' "${1:-}" | jq -r 'if type=="array" or type=="object" then length else 0 end' 2>/dev/null; }
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
todo_count() { # todo_count <bizId> <bizType>
    api_call GET '/api/req/approvals/todo?page=1&size=200'
    jqv "[.items[]? | select(.bizId == $1 and .bizType == \"$2\")] | length" "$HTTP_BODY"
}
todo_task() { # todo_task <bizId> <bizType> [nodeKey] → 第一条待办 taskId
    local node="${3:-}"
    api_call GET '/api/req/approvals/todo?page=1&size=200'
    if [[ -n "$node" ]]; then
        jqv "[.items[]? | select(.bizId == $1 and .bizType == \"$2\" and .nodeKey == \"$node\")][0].taskId // empty" "$HTTP_BODY"
    else
        jqv "[.items[]? | select(.bizId == $1 and .bizType == \"$2\")][0].taskId // empty" "$HTTP_BODY"
    fi
}

approve_biz() { # approve_biz <bizId> <bizType> [maxPasses] — 逐轮把所有待办同意完（ALL 会签要多人）
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
                api_call POST "/api/req/approvals/tasks/${t}/actions" '{"action":"APPROVE","comment":"r30 门禁-同意"}'
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
    [[ -z "$t" ]] && return 1
    api_call POST "/api/req/approvals/tasks/${t}/actions" \
        "$(jq -cn --arg c "$4" '{action:"REJECT",comment:$c}')"
    as_admin
    [[ "$HTTP_CODE" == "200" ]]
}

instance_of() { # instance_of <releaseId> → activeInstanceId
    api_call GET "/api/req/releases/$1"
    jqv '.activeInstanceId // empty' "$HTTP_BODY"
}
current_node() { # current_node <instanceId>
    api_call GET "/api/req/approvals/instances/$1"
    jqv '.instance.currentNode // .currentNode // empty' "$HTTP_BODY"
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

approve_estimate() { # approve_estimate <reqId> <days>
    local rid="$1" days="$2" task
    api_call POST "/api/req/requirements/${rid}/estimate" \
        "$(jq -cn --argjson d "$days" '{estimateDays:$d,note:"r30 门禁"}')"
    [[ "$HTTP_CODE" == "201" ]] || return 1
    as_user pm || return 1
    for _ in 1 2 3 4 5; do
        task="$(todo_task "$rid" WBS_ESTIMATE)"
        [[ -z "$task" ]] && break
        api_call POST "/api/req/approvals/tasks/${task}/actions" '{"action":"APPROVE","comment":"r30 工时评估"}'
        [[ "$HTTP_CODE" == "200" ]] || break
    done
    as_admin
    api_call GET "/api/req/requirements/${rid}"
    [[ "$(jqv '.estimate.status' "$HTTP_BODY")" == "APPROVED" ]]
}

create_release() { # create_release <name> [level] [extraJson] → id
    local name="$1" level="${2:-NORMAL}" extra="${3:-}" body
    [[ -z "$extra" ]] && extra='{}'
    body="$(jq -cn --arg n "$name" --arg l "$level" --argjson d "$DOMAIN_ID" --argjson e "$extra" \
        '{name:$n,domainId:$d,startDate:"2026-11-01",endDate:"2026-11-30",
          plannedReleaseDate:"2026-12-05",gateMode:"STRICT",releaseLevel:$l} + $e')"
    api_call POST /api/req/releases "$body"
    jqv '.id // .release.id // empty' "$HTTP_BODY"
}

attach_req() { api_call POST "/api/req/releases/$1/requirements" "{\"requirementIds\":[$2]}"; }

start_release() { # start_release <id> → instanceId（START_BODY/START_INSTANCE 留在当前 shell 的全局）
    # 注意：调用方必须写 `start_release "$id" >/dev/null` 再读 ${START_INSTANCE}，
    # 不要用 `X="$(start_release "$id")"`——命令替换是子 shell，全局变量拿不回来（[04] 曾因此 unbound）。
    api_call POST "/api/req/releases/$1/approval"
    START_BODY="$HTTP_BODY"
    START_CODE="$HTTP_CODE"
    START_INSTANCE="$(jqv '.instanceId // empty' "$HTTP_BODY")"
    printf '%s' "$START_INSTANCE"
}

release_field() { api_call GET "/api/req/releases/$1"; jqv "$2" "$HTTP_BODY"; }

create_case() { # create_case <reqId> <title> → id
    api_call POST /api/deliver/testcases "$(jq -cn --argjson m "${MODULE_ID:-1}" --argjson q "$1" --arg t "$2" '{
      moduleId:$m, requirementId:$q, title:$t, type:"FUNCTIONAL", priority:"P1", precondition:"已登录",
      steps:[{stepNo:1,action:"执行",expected:"通过"}]}')"
    jqv '.id // empty' "$HTTP_BODY"
}

create_plan() { # create_plan <releaseId> <name> <caseId> → planId
    api_call POST /api/deliver/test-plans "$(jq -cn --argjson r "$1" --arg n "$2" --argjson c "$3" '{
      releaseId:$r, name:$n, ownerId:1, windowStart:"2026-11-20", windowEnd:"2026-11-27",
      caseIds:[$c], gateMode:"STRICT"}')"
    jqv '.plan.id // .id // empty' "$HTTP_BODY"
}

create_report() { # create_report <releaseId> [note] → reportId
    api_call POST /api/deliver/test-reports \
        "$(jq -cn --argjson r "$1" --arg n "${2:-r30 门禁报告}" '{scope:"RELEASE",releaseId:$r,note:$n}')"
    jqv '.id // .report.id // empty' "$HTTP_BODY"
}

sign_report() { # sign_report <reportId> <version> <user> [conclusion]
    as_user "$3" || return 1
    api_call POST "/api/deliver/test-reports/$1/versions/$2/signoff" \
        "$(jq -cn --arg c "${4:-}" '{decision:"APPROVE",comment:"r30 门禁会签"}
            + (if $c != "" then {conclusion:$c} else {} end)')"
    as_admin
    [[ "$HTTP_CODE" == "200" ]]
}

create_order() { # create_order <releaseId> <reqId> [env] → id
    api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$1" --argjson q "$2" --arg e "${3:-PROD}" '{
      releaseId:$r, env:$e, windowStart:"2026-12-02T22:00:00+08:00",
      windowEnd:"2026-12-02T23:00:00+08:00", artifactVersion:"v1.0.1",
      steps:[{name:"发布",order:1}], rollbackPlan:"回滚到 v1.0.0", impact:"无", ownerId:1,
      requirementIds:[$q]}')"
    jqv '.id // empty' "$HTTP_BODY"
}

submit_order() { # submit_order <orderId>：夹具缺失时给出可诊断的失败，而不是拼出 /deploy-orders/submit → 405
    if [[ -z "${1:-}" ]]; then
        HTTP_CODE="000"
        HTTP_BODY='{"code":"FIXTURE_MISSING","message":"发布单 id 为空：create_order 未成功（看上一段的创建响应）"}'
        return 0
    fi
    api_call POST "/api/req/deploy-orders/$1/submit"
}

snapshot_count() { db_count deploy_flow_snapshot "deploy_order_id=$1"; }

# build_positive_chain — D-09 正向夹具：BUS→PDR→RD 真实审批 + 真实门禁 + 已签报告 + 发布单。
# 只有这条链能让 submit 走到 200；S4 的快照与 S2 的两级发布单审批都复用它。
# 全局：F_BUS F_PDR F_RD F_REL F_PLAN F_CASE F_RPT F_ORDER
build_positive_chain() {
    local prefix="$1" rnd
    as_admin
    F_BUS="$(create_req BUS "r30-正向BUS-${prefix}-${TAG}")"
    [[ -n "$F_BUS" ]] || return 1
    act "$F_BUS" submit
    approve_biz "$F_BUS" REQ_SUBMIT >/dev/null
    act "$F_BUS" review_start '{"comment":"r30 门禁评审"}'
    approve_biz "$F_BUS" REQ_REVIEW >/dev/null

    F_PDR="$(create_req PDR "r30-正向PDR-${prefix}-${TAG}" "$F_BUS")"
    [[ -n "$F_PDR" ]] || return 1
    approve_estimate "$F_PDR" 2 || return 1
    act "$F_PDR" schedule

    F_RD="$(create_req RD "r30-正向RD-${prefix}-${TAG}" "$F_PDR" 2)"
    [[ -n "$F_RD" ]] || return 1
    # 预估/实际人天与 MR 走专用入口（不是 actions 里的动作）：准入第 4/5 项读的就是这三个字段
    api_call POST "/api/req/requirements/${F_RD}/deliver" \
        '{"estimateDays":2,"actualDays":2,"mrUrl":"https://git.example.com/mr/r30"}'
    [[ "$HTTP_CODE" == "200" ]] || return 1

    F_REL="$(create_release "r30-正向版本-${prefix}-${TAG}" NORMAL)"
    [[ -n "$F_REL" ]] || return 1
    attach_req "$F_REL" "$F_RD"

    F_CASE="$(create_case "$F_RD" "r30-正向用例-${prefix}-${TAG}")"
    [[ -n "$F_CASE" ]] || return 1
    F_PLAN="$(create_plan "$F_REL" "r30-正向计划-${prefix}-${TAG}" "$F_CASE")"
    [[ -n "$F_PLAN" ]] || return 1

    start_release "$F_REL" >/dev/null

    api_call POST "/api/deliver/test-plans/${F_PLAN}/submit-review" '{}'
    api_call POST "/api/deliver/test-plans/${F_PLAN}/approve" '{"comment":"r30 门禁"}'
    api_call POST "/api/deliver/test-plans/${F_PLAN}/start" '{}'
    api_call POST "/api/deliver/test-plans/${F_PLAN}/execute" \
        "$(jq -cn --argjson c "$F_CASE" '{caseId:$c,result:"PASS",note:"r30 门禁"}')"

    approve_estimate "$F_RD" 2 || return 1
    act "$F_RD" start_dev
    act "$F_RD" submit_test
    act "$F_RD" test_pass
    [[ "$HTTP_CODE" == "200" ]] || return 1

    rnd="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${F_PLAN} AND active=1")"
    [[ -n "$rnd" ]] || return 1
    api_call POST "/api/deliver/test-plans/${F_PLAN}/rounds/${rnd}/close" '{"conclusion":"PASS","note":"r30 门禁"}'
    api_call POST "/api/deliver/test-plans/${F_PLAN}/complete" '{}'

    F_RPT="$(create_report "$F_REL")"
    [[ -n "$F_RPT" ]] || return 1
    sign_report "$F_RPT" 1 qa1 || return 1
    sign_report "$F_RPT" 1 pm || return 1
    sign_report "$F_RPT" 1 tl1 || return 1

    F_ORDER="$(create_order "$F_REL" "$F_RD")"
    [[ -n "$F_ORDER" ]] || return 1
    return 0
}

cleanup() {
    trap - EXIT
    if ((STATIC_ONLY == 0)) && [[ "$FIXTURES_SET" == "1" && "${RELEASE_FLOW_SKIP_RESET:-0}" != "1" ]]; then
        printf '\n\033[1;36m== 复位演示数据（make platform-demo-reset） ==\033[0m\n'
        bash "${ROOT}/scripts/platform-demo-reset.sh" >/dev/null 2>&1
        rc=$?
        ((rc == 0)) || printf '  \033[1;33mWARN\033[0m demo-reset 退出码 %s（不影响本次断言）\n' "$rc"
    fi
}
trap cleanup EXIT

# --------------------------------------------------------------------------- 第 32 条判据与夹具自愈
# r31：提交卫生判据必须是「提交区间的纯函数」——入参只有 git 引用，出参只有 0|1 与
# STATUS<TAB>relpath 行，全程只读 git 对象库、不读工作树。旧判据用 `git status`（提交后
# 树必干净 → 恒红）或 `git diff HEAD`（对已提交改动恒绿），两种都是假信号。

resolve_git_base() { # stdout=merge-base 的 sha；失败非 0，绝不回退 HEAD（回退=判据恒绿）
    local ref="${RELEASE_FLOW_BASE_REF:-main}" base
    base="$(git -C "$ROOT" merge-base "$ref" HEAD 2>/dev/null)" || return 1
    [[ -n "$base" ]] || return 1
    printf '%s\n' "$base"
}

check_commit_hygiene() { # <repo> <base> <head> -> 0 合规 / 1 违规 / 2 入参或 git 对象不可用
    local repo="$1" base="$2" head="$3" bad=0 st path out
    local pom_paths=('*pom.xml' 'web/package.json' 'web/pnpm-lock.yaml')
    local mig_dir='req-service/src/main/resources/db/migration'
    local mig_allowed="${mig_dir}/V14__release_flow_chain.sql"
    local mig_count=0
    [[ -n "$repo" && -n "$base" && -n "$head" ]] || return 2

    # 1) 依赖/锁文件：根 pom 与全部模块 pom（仓库现有 7 个）都不许在区间内变动；
    #    lockfile 是 pnpm 的 web/pnpm-lock.yaml（仓库没有 package-lock.json，指向不存在的
    #    文件等于一条永远为空的判据）。
    out="$(git -C "$repo" diff --name-status --no-renames "$base" "$head" -- "${pom_paths[@]}")" || return 2
    while IFS=$'\t' read -r st path; do
        [[ -n "$path" ]] || continue
        printf '%s\t%s\n' "$st" "$path"
        bad=1
    done <<<"$out"

    # 2) 迁移：只允许 0 或 1 条 `A .../V14__release_flow_chain.sql`（合并后 BASE 已含 V14 时
    #    区间为空也必须绿）；M/D/R 与其它 A（V15+、R__*.sql）一律红。
    out="$(git -C "$repo" diff --name-status --no-renames "$base" "$head" -- "$mig_dir")" || return 2
    while IFS=$'\t' read -r st path; do
        [[ -n "$path" ]] || continue
        printf '%s\t%s\n' "$st" "$path"
        if [[ "$st" == "A" && "$path" == "$mig_allowed" ]]; then
            mig_count=$((mig_count + 1))
            ((mig_count <= 1)) || bad=1
        else
            bad=1
        fi
    done <<<"$out"

    return "$bad"
}

hygiene_self_test() { # <base> -> 0 判据按预期红/绿 / 1 判据退化或 worktree 不可用
    # 自检与判据同文件、同一 case 内执行：先证明判据会红（3 个已提交变异），再证明它不会恒红
    # （空区间 + 未跟踪临时文件必须绿）。变异必须是「已提交」的，这样退回 `git status` 判据会
    # 立刻被反例 a/b/c 抓住。
    local base="${1:-}"
    [[ -n "$base" ]] || { printf '自检失败：基线为空（RELEASE_FLOW_BASE_REF）\n'; return 1; }
    (
        set +e
        trap - EXIT # 子 shell 内不继承主流程的 demo-reset trap
        # 故意不用 local：EXIT trap 在函数作用域退出后才执行，local 变量那时已被 unset，
        # 清理会因 set -u 报 "unbound variable" 而漏删 worktree。子 shell 本身已隔离命名空间。
        tmp=""
        wt=""
        orig=""
        mut=""
        mig_dir=""
        tmp="$(mktemp -d "${TMPDIR:-/tmp}/rf-hygiene.XXXXXX")" || { printf '自检失败：mktemp 不可用\n'; exit 1; }
        wt="$tmp/wt"
        st_cleanup() {
            [[ -n "${wt:-}" ]] && git -C "$ROOT" worktree remove --force "${wt:-}" >/dev/null 2>&1
            [[ -n "${tmp:-}" ]] && rm -rf "${tmp:-}"
            git -C "$ROOT" worktree prune >/dev/null 2>&1
        }
        trap st_cleanup EXIT
        git -C "$ROOT" worktree add --detach "$wt" HEAD >/dev/null 2>&1 \
            || { printf '自检失败：git worktree add 不可用（判据 fail-closed，不提供跳过开关）\n'; exit 1; }
        orig="$(git -C "$wt" rev-parse HEAD)" || exit 1
        local g=(git -C "$wt" -c user.name=hygiene-probe -c user.email=hygiene@example.invalid
                 -c commit.gpgsign=false -c core.hooksPath=/dev/null)
        mig_dir="$wt/req-service/src/main/resources/db/migration"

        # 正例：空区间 + 工作树留一个未跟踪临时文件 → 必须绿（r31 的原始症状就是脏树影响了判定）
        : >"$wt/.hygiene-untracked-probe"
        if ! check_commit_hygiene "$wt" "$orig" "$orig" >/dev/null 2>&1; then
            printf '自检正例未变绿：空区间 + 未跟踪文件被判违规\n'; exit 1
        fi
        rm -f "$wt/.hygiene-untracked-probe"

        # 反例 a：已提交修改历史迁移 V13 → 必须红
        printf '\n-- hygiene mutation a\n' >>"$mig_dir/V13__vendor_pm_role.sql"
        "${g[@]}" add -A >/dev/null 2>&1 && "${g[@]}" commit -qm 'hygiene mutation a' >/dev/null 2>&1 \
            || { printf '自检失败：变异 a 无法提交\n'; exit 1; }
        mut="$(git -C "$wt" rev-parse HEAD)"
        if check_commit_hygiene "$wt" "$orig" "$mut" >/dev/null 2>&1; then
            printf '自检反例 a 未变红：已提交改 V13 仍被判合规\n'; exit 1
        fi
        git -C "$wt" reset --hard "$orig" >/dev/null 2>&1 || { printf '自检失败：变异 a 无法还原\n'; exit 1; }

        # 反例 b：已提交新增 V15 → 必须红
        : >"$mig_dir/V15__mutation_probe.sql"
        "${g[@]}" add -A >/dev/null 2>&1 && "${g[@]}" commit -qm 'hygiene mutation b' >/dev/null 2>&1 \
            || { printf '自检失败：变异 b 无法提交\n'; exit 1; }
        mut="$(git -C "$wt" rev-parse HEAD)"
        if check_commit_hygiene "$wt" "$orig" "$mut" >/dev/null 2>&1; then
            printf '自检反例 b 未变红：已提交新增 V15 仍被判合规\n'; exit 1
        fi
        git -C "$wt" reset --hard "$orig" >/dev/null 2>&1 || { printf '自检失败：变异 b 无法还原\n'; exit 1; }

        # 反例 c：已提交修改模块 pom → 必须红（守住 pom 覆盖面；只写反例的话判据被写成恒红也不会被发现）
        printf '\n<!-- hygiene mutation c -->\n' >>"$wt/req-service/pom.xml"
        "${g[@]}" add -A >/dev/null 2>&1 && "${g[@]}" commit -qm 'hygiene mutation c' >/dev/null 2>&1 \
            || { printf '自检失败：变异 c 无法提交\n'; exit 1; }
        mut="$(git -C "$wt" rev-parse HEAD)"
        if check_commit_hygiene "$wt" "$orig" "$mut" >/dev/null 2>&1; then
            printf '自检反例 c 未变红：已提交改 req-service/pom.xml 仍被判合规\n'; exit 1
        fi
        git -C "$wt" reset --hard "$orig" >/dev/null 2>&1 || { printf '自检失败：变异 c 无法还原\n'; exit 1; }

        printf '自检 → 正例绿；a 改 V13 红；b 新增 V15 红；c 改 req-service/pom.xml 红\n'
        exit 0
    )
}

reset_binding_slots() { # 六个 (bizType,level) 槽 DELETE 回默认；幂等、不计数（setup，不是断言）
    local bt lv rc=0
    for bt in RELEASE REQ_DEPLOY; do
        for lv in NORMAL EMERGENCY ROLLBACK; do
            api_call DELETE "/api/req/admin/flow-bindings/${bt}/${lv}"
            if [[ "$HTTP_CODE" != "200" ]]; then
                printf '  槽 %s/%s 复位失败：HTTP %s\n' "$bt" "$lv" "$HTTP_CODE"
                rc=1
            fi
        done
    done
    return "$rc"
}

# --------------------------------------------------------------------------- 静态模式

static_checks() {
    local s_case=0 s_fail=0
    s_pass() { s_case=$((s_case + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$s_case" "$1"; }
    s_fail() { s_case=$((s_case + 1)); s_fail=$((s_fail + 1)); printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$s_case" "$1"; printf '        修复: %s\n' "$2"; }

    section "静态契约（不连栈）"
    if bash -n "${ROOT}/scripts/release-flow-verify.sh" 2>/dev/null; then
        s_pass "release-flow-verify.sh 语法通过（bash -n）"
    else
        s_fail "release-flow-verify.sh 语法错误" "bash -n scripts/release-flow-verify.sh"
    fi
    if bash -n "${ROOT}/scripts/release-flow-upgrade.sh" 2>/dev/null; then
        s_pass "release-flow-upgrade.sh 语法通过（bash -n）"
    else
        s_fail "release-flow-upgrade.sh 缺失或语法错误" "补齐 scripts/release-flow-upgrade.sh"
    fi
    if [[ "$(grep -c '^RELEASE_FLOW_TOTAL=32$' "${ROOT}/scripts/release-flow-verify.sh")" == "1" ]]; then
        s_pass "脚本自声明 RELEASE_FLOW_TOTAL=32（AC-20 的分母唯一）"
    else
        s_fail "RELEASE_FLOW_TOTAL 不是 32 或重复声明" "断言数变化必须同步 TECHNICAL §7.2"
    fi
    if [[ "$(grep -cE '^section "S[1-6] ' "${ROOT}/scripts/release-flow-verify.sh")" == "6" ]]; then
        s_pass "六组断言 S1–S6 均有 section 标记"
    else
        s_fail "S1–S6 分组标记缺失" "每组一个 section，便于失败定位"
    fi
    if grep -q '^release-flow-verify:' "${ROOT}/Makefile" && grep -q '^release-flow-upgrade:' "${ROOT}/Makefile"; then
        s_pass "Makefile 暴露 release-flow-verify / release-flow-upgrade 两个薄封装"
    else
        s_fail "Makefile 缺 release-flow 目标" "按既有约定：Makefile 只做 ./scripts/*.sh 的薄包装"
    fi
    if [[ "$(grep -cE '^release-flow-(verify|upgrade):.*## ' "${ROOT}/Makefile")" == "2" ]]; then
        s_pass "make help 能发现两个新目标（## 注释成对）"
    else
        s_fail "help 注释缺失" "每个目标上方一行 ## 说明，与既有目标同风格"
    fi
    if [[ -f "${ROOT}/docs/teamflow/DEMO-RELEASE-FLOW.md" ]] \
        && [[ "$(grep -cE '^## [1-8]\. ' "${ROOT}/docs/teamflow/DEMO-RELEASE-FLOW.md")" == "8" ]] \
        && grep -q '^## 被拦链' "${ROOT}/docs/teamflow/DEMO-RELEASE-FLOW.md"; then
        s_pass "DEMO-RELEASE-FLOW.md 8 步正常链 + 被拦链齐备"
    else
        s_fail "演示文档缺步骤或被拦链" "按 TECHNICAL §7.4：8 步 + 三条被拦链"
    fi
    if grep -q 'trap cleanup EXIT' "${ROOT}/scripts/release-flow-verify.sh" \
        && grep -q 'platform-demo-reset.sh' "${ROOT}/scripts/release-flow-verify.sh"; then
        s_pass "EXIT trap 复位演示数据（RELEASE_FLOW_SKIP_RESET=1 可跳过）"
    else
        s_fail "缺 EXIT trap / demo-reset" "夹具污染会让下一次门禁从脏数据开始"
    fi

    printf '\nRELEASE_FLOW_STATIC_TOTAL=%d PASS=%d FAIL=%d\n' "$s_case" $((s_case - s_fail)) "$s_fail"
    ((s_fail == 0 && s_case > 0)) || exit 1
    exit 0
}

((STATIC_ONLY == 1)) && static_checks

# --------------------------------------------------------------------------- 前置

section "前置：栈与种子"

# r31：基线在 preflight 解析、只打印、不 die —— 「基线不可解析」是第 32 条的结论而不是前置，
# die 掉会让整轮门禁只剩「环境没起来」，看不到真正的红线。
GIT_HEAD="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)"
GIT_BASE_REF="${RELEASE_FLOW_BASE_REF:-main}"
GIT_BASE="$(resolve_git_base)"
GIT_BASE_RC=$?
printf '  基线：BASE=%s HEAD=%s BASE_REF=%s\n' "${GIT_BASE:0:7}" "${GIT_HEAD:0:7}" "$GIT_BASE_REF"
if ((GIT_BASE_RC != 0)); then
    printf '  \033[1;33mWARN\033[0m merge-base %s HEAD 解析失败：第 32 条将判红；用 RELEASE_FLOW_BASE_REF 指定可用基线（绝不回退 HEAD）\n' "$GIT_BASE_REF"
fi

need_cmd curl || die "缺少 curl"
need_cmd jq || die "缺少 jq"
need_cmd mysql || die "缺少 mysql 客户端（db_q 依赖它）"
mkdir -p "$REPORT_DIR"
FAIL_LOG="${REPORT_DIR}/failures-${TAG}.txt"
: >"$FAIL_LOG"

req_ready || die "req-service 未就绪：${REQ_URL}/internal/health —— 先 make platform-up"
deliver_ready || die "deliver-service 未就绪：${DELIVER_URL}/internal/health —— 先 make platform-up"
db_up || die "MySQL 未就绪：${PLATFORM_DB_URL} —— 先 make platform-up"

TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${T1_TENANT}' LIMIT 1")"
[[ -n "$TID" ]] || die "租户 ${T1_TENANT} 不存在 —— 先 make platform-seed"
DOMAIN_ID="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
[[ -n "$DOMAIN_ID" ]] || die "租户 ${TID} 没有业务域 —— 先 make platform-demo-reset"
MODULE_ID="$(db_q "SELECT id FROM test_module WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
[[ -n "$MODULE_ID" ]] || MODULE_ID=1
ANY_REQ="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"

platform_login "$T1_TENANT" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null 2>&1 \
    || die "admin 登录失败 —— 检查种子账号 admin/${ADMIN_PASS}"
ADMIN_TOKEN="$API_TOKEN"
ADMIN_TENANT="$API_TENANT"
[[ -n "$(db_q "SELECT id FROM sys_user WHERE tenant_id=${TID} AND username='tl1' LIMIT 1")" ]] \
    || die "种子账号 tl1 不存在 —— 先 make platform-demo-reset"
[[ -n "$ANY_REQ" ]] || die "租户下没有任何需求 —— 先 make platform-demo-reset"

# r30 前置：V14 三张新表 + /admin/flow-bindings 路由必须已落地。缺任何一项都不是「跳过」，
# 而是环境没升级到 r30 —— 32 条断言在这种栈上不可能判绿（r28 教训：不能条件跳过）。
[[ -n "$(db_q "SHOW TABLES LIKE 'deploy_flow_snapshot'")" ]] \
    || die "缺表 deploy_flow_snapshot：当前库未执行 V14 —— 重启 req-service 让 Flyway 迁移后重跑"
[[ -n "$(db_q "SHOW TABLES LIKE 'wf_flow_binding'")" ]] \
    || die "缺表 wf_flow_binding：当前库未执行 V14 —— 重启 req-service 让 Flyway 迁移后重跑"
api_call GET /api/req/admin/flow-bindings
[[ "$HTTP_CODE" == "200" ]] \
    || die "GET /api/req/admin/flow-bindings 返回 ${HTTP_CODE}：运行中的 req-service 还是 r30 之前的构建 —— 先 make platform-down && make platform-up"

FIXTURES_SET=1
printf '  租户=%s 域=%s 模块=%s 需求样本=%s\n' "$TID" "$DOMAIN_ID" "$MODULE_ID" "$ANY_REQ"

# --------------------------------------------------------------------------- S1
section "S1 绑定与等级（6）"
# S1 前置自愈（H5）：上一轮中断/残留改绑会让 S1-1 假红；六个槽幂等 DELETE 回默认，setup 不计数。
reset_binding_slots || printf '  \033[1;33mWARN\033[0m 六个绑定槽未能全部复位（S1-1 可能因残留改绑而红）\n'
api_call GET /api/req/admin/flow-bindings
SLOTS="$HTTP_BODY"

# S1-1：6 槽可读，且全部 PUBLISHED/isDefault
SLOT_KEYS="$(jqv '[.[] | "\(.bizType)/\(.releaseLevel)"] | sort | join(",")' "$SLOTS")"
if [[ "$HTTP_CODE" == "200" && "$(json_len "$SLOTS")" == "6" \
    && "$SLOT_KEYS" == "RELEASE/EMERGENCY,RELEASE/NORMAL,RELEASE/ROLLBACK,REQ_DEPLOY/EMERGENCY,REQ_DEPLOY/NORMAL,REQ_DEPLOY/ROLLBACK" \
    && "$(jqv '[.[] | select(.status != "PUBLISHED" or .isDefault != true)] | length' "$SLOTS")" == "0" ]]; then
    pass "GET /admin/flow-bindings 返回 6 槽且全部默认指向 PUBLISHED 定义"
else
    fail "绑定槽不是 6 条默认配置（HTTP ${HTTP_CODE}, keys=${SLOT_KEYS}）" \
        "POST /internal/seed 必须写入 6 条 definition + 6 条 binding；slots 对空槽补虚拟行"
fi

# S1-2：NORMAL = 三级 n1,n2,n3
NORMAL_NODES="$(jqv '[.[] | select(.bizType=="RELEASE" and .releaseLevel=="NORMAL")][0].nodes | map(.key) | join(",")' "$SLOTS")"
NORMAL_NAME="$(jqv '[.[] | select(.bizType=="RELEASE" and .releaseLevel=="NORMAL")][0].definitionName' "$SLOTS")"
if [[ "$NORMAL_NODES" == "n1,n2,n3" && "$NORMAL_NAME" == *"常规三级"* ]]; then
    pass "RELEASE/NORMAL 指向「${NORMAL_NAME}」三级链 n1,n2,n3"
else
    fail "RELEASE/NORMAL 不是三级链（nodes=${NORMAL_NODES}, name=${NORMAL_NAME}）" \
        "种子默认：产品经理会签 → PMO 会签 → 管理层"
fi

# S1-3：REQ_DEPLOY 三个等级都是两级
DEPLOY_BAD="$(jqv '[.[] | select(.bizType=="REQ_DEPLOY" and ((.nodes|length) != 2))] | length' "$SLOTS")"
DEPLOY_CNT="$(jqv '[.[] | select(.bizType=="REQ_DEPLOY")] | length' "$SLOTS")"
if [[ "$DEPLOY_CNT" == "3" && "$DEPLOY_BAD" == "0" ]]; then
    pass "REQ_DEPLOY 三个等级槽均为两级链（发布单默认 PMO → 平台管理员）"
else
    fail "REQ_DEPLOY 槽不是 3×2（count=${DEPLOY_CNT}, bad=${DEPLOY_BAD}）" \
        "三个 REQ_DEPLOY 等级共用「发布单审批-两级」"
fi

# S1-4：换绑只影响新单 —— 新单走 2 级，在途实例仍是 3 级
REL_A="$(create_release "r30-S1-在途-${TAG}" NORMAL)"
attach_req "$REL_A" "$ANY_REQ"
start_release "$REL_A" >/dev/null
INST_A="$START_INSTANCE"
REL_A_LEVELS="$(release_field "$REL_A" '.approvalLevels | length')"
EMERG_DEF="$(jqv '[.[] | select(.bizType=="RELEASE" and .releaseLevel=="EMERGENCY")][0].definitionId' "$SLOTS")"
api_call PUT /api/req/admin/flow-bindings/RELEASE/NORMAL \
    "$(jq -cn --argjson d "$EMERG_DEF" '{definitionId:$d}')"
BIND_CODE="$HTTP_CODE"
REL_A2="$(create_release "r30-S1-新单-${TAG}" NORMAL)"
attach_req "$REL_A2" "$ANY_REQ"
start_release "$REL_A2" >/dev/null
INST_A2="$START_INSTANCE"
A2_KEYS="$(jqv '.nodeKeys | length' "$START_BODY")"
if [[ "$BIND_CODE" == "200" && -n "$INST_A" && -n "$INST_A2" \
    && "$A2_KEYS" == "2" && "$REL_A_LEVELS" == "3" ]]; then
    pass "换绑 NORMAL→紧急两级：新单 2 节点，在途实例仍是 3 级（定义版本钉住）"
else
    fail "换绑未按「只影响新单」生效（PUT=${BIND_CODE}, 新单节点=${A2_KEYS:-?}, 在途级数=${REL_A_LEVELS:-?}）" \
        "绑定只在 startApproval 解析 definitionId；在途实例读自己的 definitionVersion"
fi

# S1-5：reset 删行回默认 → 三级
api_call DELETE /api/req/admin/flow-bindings/RELEASE/NORMAL
RESET_BODY="$HTTP_BODY"
RESET_CODE="$HTTP_CODE"
REL_A3="$(create_release "r30-S1-reset-${TAG}" NORMAL)"
attach_req "$REL_A3" "$ANY_REQ"
start_release "$REL_A3" >/dev/null
A3_KEYS="$(jqv '.nodeKeys | length' "$START_BODY")"
if [[ "$RESET_CODE" == "200" && "$(jqv '.isDefault' "$RESET_BODY")" == "true" \
    && "$(jqv '.nodes | length' "$RESET_BODY")" == "3" && "$A3_KEYS" == "3" ]]; then
    pass "DELETE 绑定删行即恢复默认：isDefault=true、三级链、新单重新走 3 节点"
else
    fail "reset 未恢复默认（HTTP ${RESET_CODE}, isDefault=$(jqv '.isDefault' "$RESET_BODY"), 新单节点=${A3_KEYS:-?}）" \
        "reset 是 DELETE 行而不是抄一份默认 id；解析回退到默认定义"
fi

# S1-6：非法等级 400 detail.field；非管理员读绑定 403
REL_K="$(create_release "r30-S1-非法等级-${TAG}" NORMAL)"
api_call PATCH "/api/req/releases/${REL_K}/level" '{"releaseLevel":"SUPER"}'
BAD_LEVEL_CODE="$HTTP_CODE"
BAD_LEVEL_FIELD="$(jqv '.detail.field // empty' "$HTTP_BODY")"
as_user pm || true
api_call GET /api/req/admin/flow-bindings
PM_BIND_CODE="$HTTP_CODE"
as_admin
if [[ "$BAD_LEVEL_CODE" == "400" && "$BAD_LEVEL_FIELD" == "releaseLevel" && "$PM_BIND_CODE" == "403" ]]; then
    pass "非法等级 400 detail.field=releaseLevel；非管理员 GET 绑定 403"
else
    fail "等级校验/管理员守卫不符（PATCH=${BAD_LEVEL_CODE}/field=${BAD_LEVEL_FIELD:-?}, pm GET=${PM_BIND_CODE}）" \
        "ReleaseLevelValidator 统一抛 400；/admin/flow-bindings 复用 workflow:manage"
fi

# --------------------------------------------------------------------------- S2
section "S2 正常链（7）"
REL_B="$(create_release "r30-S2-三级链-${TAG}" NORMAL)"
attach_req "$REL_B" "$ANY_REQ"
start_release "$REL_B" >/dev/null
INST_B="$START_INSTANCE"
CUR_B="$(current_node "$INST_B")"
as_user tl1 || true; T_TL1="$(todo_count "$REL_B" RELEASE)"
as_user vendor1 || true; T_VEN="$(todo_count "$REL_B" RELEASE)"
as_user mgr1 || true; T_MGR="$(todo_count "$REL_B" RELEASE)"
as_user pm || true; T_PM="$(todo_count "$REL_B" RELEASE)"
as_admin
# S2-1
if [[ "$CUR_B" == "n1" && "$T_TL1" == "0" && "$T_VEN" == "0" && "$T_MGR" == "0" && "$T_PM" == "1" ]]; then
    pass "发起后 currentNode=n1，只有 n1 审批人（pm）有待办，n2/n3 角色待办为 0"
else
    fail "发起后待办分布不对（current=${CUR_B:-?}, pm=${T_PM}, tl1=${T_TL1}, vendor1=${T_VEN}, mgr1=${T_MGR}）" \
        "引擎逐级建待办：n1 通过后才创建 n2 的 wf_task"
fi

# S2-2：n1 通过 → n2 待办出现
as_user pm || true
T_B_N1="$(todo_task "$REL_B" RELEASE n1)"
api_call POST "/api/req/approvals/tasks/${T_B_N1}/actions" '{"action":"APPROVE","comment":"r30 常规三级-产品"}'
N1_CODE="$HTTP_CODE"
as_user tl1 || true; T_TL1_N2="$(todo_task "$REL_B" RELEASE n2)"
as_admin
if [[ "$N1_CODE" == "200" && -n "$T_TL1_N2" ]]; then
    pass "n1（产品经理会签）通过后，n2（PMO 会签）待办出现"
else
    fail "逐级建待办失败（n1 approve=${N1_CODE}, n2 task=${T_TL1_N2:-空}）" \
        "ApprovalEngine.advance 必须在节点通过后创建下一节点待办"
fi

# S2-3：NODE_APPROVED 留痕三键
HIST_DETAIL="$(db_q "SELECT detail_json FROM wf_history WHERE tenant_id=${TID} AND instance_id=${INST_B} AND action='NODE_APPROVED' ORDER BY id DESC LIMIT 1")"
H_KEY="$(jqv '.nodeKey // empty' "$HIST_DETAIL")"
H_NAME="$(jqv '.nodeName // empty' "$HIST_DETAIL")"
H_MODE="$(jqv '.mode // empty' "$HIST_DETAIL")"
if [[ "$H_KEY" == "n1" && -n "$H_NAME" && -n "$H_MODE" ]]; then
    pass "NODE_APPROVED 留痕含 nodeKey/nodeName/mode（${H_KEY}/${H_NAME}/${H_MODE}）"
else
    fail "NODE_APPROVED detail 缺三键（${HIST_DETAIL:-空}）" \
        "引擎补 nodeName；这是 AC-2 审计可回放的最小事实"
fi

# S2-4：中间态仍是 LOCKED（不是 PUBLISHED）
STATUS_B="$(release_field "$REL_B" '.release.status')"
LOCKED_AT_B="$(release_field "$REL_B" '.release.lockedAt')"
if [[ "$STATUS_B" == "LOCKED" && -n "$LOCKED_AT_B" && "$LOCKED_AT_B" != "null" ]]; then
    pass "n1 通过后版本仍为 LOCKED（lockedAt 非空），未提前发布"
else
    fail "中间态状态不对（status=${STATUS_B:-?}, lockedAt=${LOCKED_AT_B:-?}）" \
        "只有最后一个节点通过才回调 publish；中间节点不得改 release.status"
fi

# S2-6：approvalLevels 序列 = APPROVED,ACTIVE,WAITING（在末级前读）
LEVEL_SEQ="$(release_field "$REL_B" '[.approvalLevels[].status] | join(",")')"
LEVEL_LEN="$(release_field "$REL_B" '.approvalLevels | length')"
if [[ "$LEVEL_LEN" == "3" && "$LEVEL_SEQ" == "APPROVED,ACTIVE,WAITING" ]]; then
    pass "approvalLevels 长度 3 且状态序列 APPROVED,ACTIVE,WAITING"
else
    fail "逐级状态序列不对（len=${LEVEL_LEN:-?}, seq=${LEVEL_SEQ:-?}）" \
        "ApprovalLevelProjector：已过级 APPROVED、当前级 ACTIVE、后续 WAITING"
fi

# S2-5：走完 n2/n3 → PUBLISHED
approve_biz "$REL_B" RELEASE >/dev/null
STATUS_B2="$(release_field "$REL_B" '.release.status')"
if [[ "$STATUS_B2" == "PUBLISHED" ]]; then
    pass "末级（管理层）通过后版本 PUBLISHED（审批回调唯一发布口）"
else
    fail "三级走完仍未发布（status=${STATUS_B2:-?}）" \
        "ReleaseApprovalCallback.onApproved 只在最后一级触发 publish"
fi

# S2-7：发布单两级链
build_positive_chain "S2-dt7" || POSITIVE_OK=0
if [[ -n "${F_ORDER:-}" ]]; then
    submit_order "$F_ORDER"
    SUB_CODE="$HTTP_CODE"
    ORDER_LEVELS="$(api_call GET "/api/req/deploy-orders/${F_ORDER}"; jqv '.approvalLevels | length' "$HTTP_BODY")"
    ORDER_INST="$(db_q "SELECT id FROM wf_instance WHERE tenant_id=${TID} AND biz_type='REQ_DEPLOY' AND biz_id=${F_ORDER} ORDER BY id DESC LIMIT 1")"
    approve_biz "$F_ORDER" REQ_DEPLOY >/dev/null
    N2_TASKS="$(db_count wf_task "instance_id=${ORDER_INST:-0} AND node_key='n2'")"
    as_user admin || true
    ADMIN_N2="$(todo_count "$F_ORDER" REQ_DEPLOY)"
    as_admin
    # n1 通过后 admin 的 n2 任务必须存在（上面 approve_biz 已推完，故查 wf_task 而不是待办余额）
    ORDER_STATUS="$(api_call GET "/api/req/deploy-orders/${F_ORDER}"; jqv '.order.status // .status' "$HTTP_BODY")"
    ORDER_SEQ="$(api_call GET "/api/req/deploy-orders/${F_ORDER}"; jqv '[.approvalLevels[].status] | join(",")' "$HTTP_BODY")"
    if [[ "$SUB_CODE" == "200" && "$ORDER_LEVELS" == "2" && "${N2_TASKS:-0}" -ge 1 \
        && "$ORDER_STATUS" == "APPROVED" && "$ORDER_SEQ" == "APPROVED,APPROVED" ]]; then
        pass "发布单默认两级链：提交后 2 级、n2 待办真实创建、逐级通过后 APPROVED（与版本共用投影）"
    else
        fail "发布单两级链不符（submit=${SUB_CODE}, levels=${ORDER_LEVELS:-?}, n2Tasks=${N2_TASKS:-0}, status=${ORDER_STATUS:-?}, seq=${ORDER_SEQ:-?}, adminN2=${ADMIN_N2:-?}）" \
            "REQ_DEPLOY 绑定「发布单审批-两级」；DeployOrderService.detail 用 ApprovalLevelProjector"
    fi
else
    fail "发布单夹具缺失，无法验证两级链" "先修 build_positive_chain（BUS/PDR/RD 审批链或门禁未过）"
fi

# --------------------------------------------------------------------------- S3
section "S3 驳回与越权（6）"
REL_D="$(create_release "r30-S3-驳回-${TAG}" NORMAL)"
attach_req "$REL_D" "$ANY_REQ"
start_release "$REL_D" >/dev/null
INST_D="$START_INSTANCE"
as_user pm || true
T_D_N1="$(todo_task "$REL_D" RELEASE n1)"
api_call POST "/api/req/approvals/tasks/${T_D_N1}/actions" '{"action":"APPROVE","comment":"r30 驳回前 n1"}'
as_admin
reject_biz tl1 "$REL_D" RELEASE "r30 门禁：测试报告不完整，驳回"
REJ_CODE=$?
D_STATUS="$(release_field "$REL_D" '.release.status')"
D_LOCKED="$(release_field "$REL_D" '.release.lockedAt')"
D_FIELDS="$(release_field "$REL_D" '[.rejectedNodeKey,.rejectedNodeName,.rejectedBy,.rejectedAt,.rejectReason] | map(select(. != null and . != "")) | length')"
# `release` 是 MySQL 保留字，必须反引号；但在双引号的 $(...) 里裸反引号会被当成命令替换
# （曾经报 "release: command not found"，D_PUB 变空，把 D-7 的真缺陷混进脚本噪声）。
D_PUB="$(db_q "SELECT COUNT(*) FROM \`release\` WHERE id=${REL_D} AND status='PUBLISHED'")"
# S3-1
if [[ "$REJ_CODE" == "0" && "$D_STATUS" == "PLAN" && ( "$D_LOCKED" == "null" || -z "$D_LOCKED" ) \
    && "$D_FIELDS" == "5" && "$D_PUB" == "0" ]]; then
    pass "n2 驳回 → 版本回 PLAN、lockedAt 清空、五个驳回字段齐全、未发布"
else
    fail "驳回回滚不完整（reject=${REJ_CODE}, status=${D_STATUS:-?}, locked=${D_LOCKED:-?}, fields=${D_FIELDS:-?}, published=${D_PUB}）" \
        "onApprovalRejected：PLAN + 五列来自最后一条 REJECT 历史"
fi

# S3-2：重提 → 新实例、回 n1
OLD_INST="$INST_D"
start_release "$REL_D" >/dev/null
INST_D2="$START_INSTANCE"
CUR_D2="$(current_node "$INST_D2")"
D2_KEYS="$(jqv '.nodeKeys | length' "$START_BODY")"
if [[ -n "$INST_D2" && "$INST_D2" != "$OLD_INST" && "$CUR_D2" == "n1" && "$D2_KEYS" == "3" ]]; then
    pass "驳回后重提生成新实例（${OLD_INST}→${INST_D2}），currentNode 回 n1"
else
    fail "重提未新建实例（old=${OLD_INST}, new=${INST_D2:-?}, current=${CUR_D2:-?}, keys=${D2_KEYS:-?}）" \
        "旧实例 REJECTED；startApproval 清驳回列后 engine.start 新实例"
fi

# S3-3/4：越级审批 403（mgr1 与 pm 各一条），且任务仍 PENDING、无 APPROVE 历史
REL_E="$(create_release "r30-S3-越权-${TAG}" NORMAL)"
attach_req "$REL_E" "$ANY_REQ"
start_release "$REL_E" >/dev/null
INST_E="$START_INSTANCE"
as_user pm || true
T_E_N1="$(todo_task "$REL_E" RELEASE n1)"
api_call POST "/api/req/approvals/tasks/${T_E_N1}/actions" '{"action":"APPROVE","comment":"r30 越权前 n1"}'
as_admin
as_user tl1 || true
T_E_N2="$(todo_task "$REL_E" RELEASE n2)"
as_admin

as_user mgr1 || true
api_call POST "/api/req/approvals/tasks/${T_E_N2}/actions" '{"action":"APPROVE","comment":"r30 越级-mgr1"}'
MGR_CODE="$HTTP_CODE"
as_admin
as_user pm || true
api_call POST "/api/req/approvals/tasks/${T_E_N2}/actions" '{"action":"APPROVE","comment":"r30 越级-pm"}'
PM_CODE="$HTTP_CODE"
as_admin
TASK_STATE="$(db_q "SELECT status FROM wf_task WHERE id=${T_E_N2}")"
APPROVE_HIST="$(db_count wf_history "task_id=${T_E_N2} AND action='APPROVE'")"
# S3-3
if [[ "$MGR_CODE" == "403" && "$TASK_STATE" == "PENDING" && "$APPROVE_HIST" == "0" ]]; then
    pass "非本级审批人（mgr1）审批 n2 任务 403，任务仍 PENDING、无 APPROVE 留痕"
else
    fail "越级未被拦（mgr1=${MGR_CODE}, task=${TASK_STATE:-?}, approveHist=${APPROVE_HIST}）" \
        "assertAssignee 必须在写历史之前 403；不允许管理员代批旁路"
fi
# S3-4
if [[ "$PM_CODE" == "403" ]]; then
    pass "本级之外的角色（pm，n1 审批人）审批 n2 任务同样 403"
else
    fail "pm 在 n2 审批返回 ${PM_CODE}（应为 403）" \
        "越级是按任务 assignee 判定的，不是按「是否参与过实例」"
fi

# S3-5：在途重复发起 409
api_call POST "/api/req/releases/${REL_E}/approval"
DUP_CODE="$HTTP_CODE"
if [[ "$DUP_CODE" == "409" ]]; then
    pass "在途实例存在时重复发起审批 409 RELEASE_LOCKED（不会产生第二个实例）"
else
    fail "重复发起返回 ${DUP_CODE}（应为 409）" \
        "requireEditable + activeInstance 检查必须先于 engine.start"
fi

# S3-6：REJECTED 发布单不可执行
ORD2="$(create_order "$F_REL" "$F_RD")"
submit_order "$ORD2"
ORD2_SUB="$HTTP_CODE"
reject_biz tl1 "$ORD2" REQ_DEPLOY "r30 门禁：发布窗口冲突，驳回"
ORD2_STATUS="$(api_call GET "/api/req/deploy-orders/${ORD2}"; jqv '.order.status // .status' "$HTTP_BODY")"
api_call POST "/api/req/deploy-orders/${ORD2}/execution" '{"result":"SUCCESS"}'
EXEC_CODE="$HTTP_CODE"
if [[ "$ORD2_SUB" == "200" && "$ORD2_STATUS" == "REJECTED" && "$EXEC_CODE" == "409" ]]; then
    pass "被驳回的发布单（REJECTED）登记执行 409（状态机不接受跳步）"
else
    fail "REJECTED 单仍可执行（submit=${ORD2_SUB}, status=${ORD2_STATUS:-?}, exec=${EXEC_CODE}）" \
        "DeployOrderStates.canRecordExecution 只允许 PENDING_APPROVAL/APPROVED/EXECUTING 等合法前态"
fi

# --------------------------------------------------------------------------- S4
section "S4 链路与快照（7）"
api_call GET "/api/req/releases/${F_REL}"
CHAIN="$HTTP_BODY"
# S4-1
SEG_OK="$(jqv '[.flowChain.testPlan,.flowChain.signoff,.flowChain.qualityGate,.flowChain.deployAdmission] | map(select(. != null and .status != null)) | length' "$CHAIN")"
if [[ "$SEG_OK" == "4" ]]; then
    pass "版本详情 flowChain 四段齐全（testPlan/signoff/qualityGate/deployAdmission 均有 status）"
else
    fail "flowChain 段缺失（完整段数=${SEG_OK:-0}）" \
        "FlowChainService.assemble 是唯一聚合点；缺段只能是 MISSING 而不是 null"
fi

# S4-2：与 deliver 事实同源
CP_SIGN_APPROVED="$(jqv '.flowChain.signoff.approved' "$CHAIN")"
CP_SIGN_TOTAL="$(jqv '.flowChain.signoff.total' "$CHAIN")"
api_call GET "/api/deliver/test-reports/${F_RPT}/versions/1/signoff"
D_SIGN_APPROVED="$(jqv '.approved' "$HTTP_BODY")"
D_SIGN_TOTAL="$(jqv '.total' "$HTTP_BODY")"
CP_GATE_RESULT="$(jqv '.flowChain.qualityGate.result' "$CHAIN")"
GATE_SNAP_ID="$(jqv '.flowChain.qualityGate.snapshotId' "$CHAIN")"
api_call GET "/api/deliver/quality-gates/snapshots/${GATE_SNAP_ID}"
D_GATE_RESULT="$(jqv '.result' "$HTTP_BODY")"
api_call GET "/api/deliver/test-plans/${F_PLAN}/lifecycle"
D_PLAN_STATUS="$(jqv '.status' "$HTTP_BODY")"
CP_PLAN_STATUS="$(jqv '.flowChain.testPlan.plans[0].status' "$CHAIN")"
CP_ALL_APPROVED="$(jqv '.flowChain.testPlan.allApproved' "$CHAIN")"
if [[ "$CP_SIGN_APPROVED" == "$D_SIGN_APPROVED" && "$CP_SIGN_TOTAL" == "$D_SIGN_TOTAL" \
    && "$CP_GATE_RESULT" == "$D_GATE_RESULT" && "$CP_PLAN_STATUS" == "$D_PLAN_STATUS" \
    && "$CP_ALL_APPROVED" == "true" ]]; then
    pass "flowChain 的 testPlan/signoff/qualityGate 与 deliver 详情逐字段一致（同源不二次判定）"
else
    fail "链路段与 deliver 详情不一致（sign ${CP_SIGN_APPROVED}/${CP_SIGN_TOTAL}≠${D_SIGN_APPROVED}/${D_SIGN_TOTAL}, gate ${CP_GATE_RESULT}≠${D_GATE_RESULT}, plan ${CP_PLAN_STATUS}≠${D_PLAN_STATUS}）" \
        "req 只消费 deliver 事实（/internal/releases/{id}/flow-evidence），门判定不在 req 重算"
fi

# S4-3：连续 5 次读 digest 稳定
DIGEST_FIRST="$(jqv '.flowChain.digests | to_entries | sort_by(.key) | map("\(.key)=\(.value)") | join("|")' "$CHAIN")"
STABLE=1
for _ in 1 2 3 4; do
    api_call GET "/api/req/releases/${F_REL}"
    [[ "$(jqv '.flowChain.digests | to_entries | sort_by(.key) | map("\(.key)=\(.value)") | join("|")' "$HTTP_BODY")" == "$DIGEST_FIRST" ]] || STABLE=0
done
if [[ "$STABLE" == "1" && -n "$DIGEST_FIRST" ]]; then
    pass "同一链路连续 5 次读取 digest 完全一致（${DIGEST_FIRST}）"
else
    fail "digest 不稳定或缺失（first=${DIGEST_FIRST:-空}）" \
        "digest 只在 FlowChainService 计算一次（规范化 JSON + sha256 前 16 位）"
fi

# S4-4：提交出了快照
api_call GET "/api/req/deploy-orders/${F_ORDER}"
OD_BODY="$HTTP_BODY"
SNAP_ROUND="$(jqv '.flowSnapshot.round' "$OD_BODY")"
SNAP_HASH="$(jqv '.flowSnapshot.payloadHash' "$OD_BODY")"
SNAP_HIST="$(jqv '.flowSnapshotHistory | length' "$OD_BODY")"
if [[ "$SNAP_ROUND" == "1" && "$SNAP_HASH" =~ ^[0-9a-f]{64}$ && "$SNAP_HIST" -ge 1 ]]; then
    pass "提交后发布单带第 1 轮快照，payloadHash 为 64 位小写 hex，历史含该轮"
else
    fail "快照不符合契约（round=${SNAP_ROUND:-?}, hash=${SNAP_HASH:-?}, history=${SNAP_HIST:-?}）" \
        "capture 在 startApproval 同事务内写；hash 是完整 sha256(64 hex)"
fi

# S4-5：改报告 → hash 不变、signoff stale
HASH_BEFORE="$SNAP_HASH"
api_call POST /api/deliver/test-reports \
    "$(jq -cn --argjson r "$F_REL" '{scope:"RELEASE",releaseId:$r,note:"r30 门禁：报告改版"}')"
V2_CODE="$HTTP_CODE"
V2_NO="$(jqv '.versionNo // .version.no // empty' "$HTTP_BODY")"
[[ -n "$V2_NO" ]] || V2_NO=2
sign_report "$F_RPT" "$V2_NO" qa1 || true
api_call GET "/api/req/deploy-orders/${F_ORDER}"
HASH_AFTER="$(jqv '.flowSnapshot.payloadHash' "$HTTP_BODY")"
STALE_SIGN="$(jqv '.flowSnapshot.segmentStale.signoff' "$HTTP_BODY")"
if [[ "$V2_CODE" == "201" && "$HASH_AFTER" == "$HASH_BEFORE" && "$STALE_SIGN" == "true" ]]; then
    pass "报告改版后快照 hash 不变（${HASH_AFTER:0:12}…），segmentStale.signoff=true"
else
    fail "快照 stale 判定不对（V2=${V2_CODE}, hash ${HASH_BEFORE:-?}→${HASH_AFTER:-?}, stale=${STALE_SIGN:-?}）" \
        "快照是不可变回执；stale 由后端比对当前 digest 与快照 digest"
fi

# S4-6：回放只读且两次一致
api_call GET "/api/req/deploy-orders/${F_ORDER}/flow-snapshots/1"
R1="$(printf '%s' "$HTTP_BODY" | jq -S . 2>/dev/null)"
R1_CODE="$HTTP_CODE"
api_call GET "/api/req/deploy-orders/${F_ORDER}/flow-snapshots/1"
R2="$(printf '%s' "$HTTP_BODY" | jq -S . 2>/dev/null)"
if [[ "$R1_CODE" == "200" && -n "$R1" && "$R1" == "$R2" ]]; then
    pass "GET /flow-snapshots/1 回放两次逐字段一致（只读、无副作用）"
else
    fail "回放不稳定或失败（code=${R1_CODE:-?}）" \
        "replay 只读 payload_json 并重算 stale；不得重写快照"
fi

# S4-7：被拒的提交不落轮次（发布 F + 提交前新增的未批计划 + 订单 F）
# 注意：create 也过 planGuard（r26），所以「先建未批计划再建单」永远拿不到单号——
# 必须先建单（此刻计划已批），提交前再补一张未批计划，让 submit 自己的计划闸门真被触发。
REL_F="$(create_release "r30-S4-未批计划-${TAG}" NORMAL)"
attach_req "$REL_F" "$ANY_REQ"
start_release "$REL_F" >/dev/null
ORDER_F="$(create_order "$REL_F" "$F_RD")"
PLAN_F=""
if [[ -n "$ORDER_F" ]]; then
    PLAN_F="$(create_plan "$REL_F" "r30-S4-未批计划-${TAG}" "$F_CASE")"
fi
submit_order "$ORDER_F"
SUB_F_CODE="$HTTP_CODE"
SNAP_F="$(snapshot_count "${ORDER_F:-0}")"
if [[ -n "$ORDER_F" && "$SUB_F_CODE" == "409" && "$SNAP_F" == "0" ]]; then
    pass "计划未批被拒的提交不留快照（409，轮次 0）"
else
    fail "被拒提交仍写了快照或未被拒（order=${ORDER_F:-空}, plan=${PLAN_F:-空}, code=${SUB_F_CODE:-?}, snapshots=${SNAP_F}）" \
        "闸门顺序：计划→LOCKED→准入→门→capture→审批；拒绝必须发生在 capture 之前"
fi

# --------------------------------------------------------------------------- S5
section "S5 负向（4）"
# S5-1：计划未批 409 + detail.planIds
submit_order "$ORDER_F"
PLAN_FAIL_CODE="$HTTP_CODE"
PLAN_FAIL_BODY="$HTTP_BODY"
PLAN_IDS="$(jqv '.detail.planIds // [] | length' "$PLAN_FAIL_BODY")"
if [[ "$PLAN_FAIL_CODE" == "409" && "$(jqv '.code // empty' "$PLAN_FAIL_BODY")" == "PLAN_NOT_APPROVED" \
    && "${PLAN_IDS:-0}" -ge 1 ]]; then
    pass "计划未批准 → 409 PLAN_NOT_APPROVED，detail.planIds 非空（指名哪张计划）"
else
    fail "计划闸门不符（code=${PLAN_FAIL_CODE}, body=$(printf '%s' "$PLAN_FAIL_BODY" | head -c 160)）" \
        "submit 第 3 步 planGuard.requireApproved 必须在准入/门之前"
fi

# S5-2：未三签 → QG-02 失败
REL_H="$(create_release "r30-S5-未签报告-${TAG}" NORMAL)"
attach_req "$REL_H" "$F_RD"
start_release "$REL_H" >/dev/null
PLAN_H="$(create_plan "$REL_H" "r30-S5-未签报告-${TAG}" "$F_CASE")"
api_call POST "/api/deliver/test-plans/${PLAN_H}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${PLAN_H}/approve" '{"comment":"r30 门禁"}'
api_call POST "/api/deliver/test-plans/${PLAN_H}/start" '{}'
api_call POST "/api/deliver/test-plans/${PLAN_H}/execute" \
    "$(jq -cn --argjson c "$F_CASE" '{caseId:$c,result:"PASS",note:"r30 门禁"}')"
RH_ROUND="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN_H} AND active=1")"
api_call POST "/api/deliver/test-plans/${PLAN_H}/rounds/${RH_ROUND}/close" '{"conclusion":"PASS","note":"r30 门禁"}'
api_call POST "/api/deliver/test-plans/${PLAN_H}/complete" '{}'
RPT_H="$(create_report "$REL_H" "r30 门禁：未签报告")"
ORDER_H="$(create_order "$REL_H" "$F_RD")"
submit_order "$ORDER_H"
GATE_FAIL_CODE="$HTTP_CODE"
GATE_FAIL_BODY="$HTTP_BODY"
QG02="$(jqv '[.detail.failedRules[]? | .code] | index("QG-02") != null' "$GATE_FAIL_BODY")"
if [[ "$GATE_FAIL_CODE" == "409" && "$(jqv '.code // empty' "$GATE_FAIL_BODY")" == "QUALITY_GATE_FAILED" \
    && "$QG02" == "true" ]]; then
    pass "报告未三签 → 409 QUALITY_GATE_FAILED，failedRules 含 QG-02（签署事实来自 deliver）"
else
    fail "未三签未被门拦（code=${GATE_FAIL_CODE}, body=$(printf '%s' "$GATE_FAIL_BODY" | head -c 200)）" \
        "req 只透传 deliver 快照的 failedRules，不得按签署人数自行判定"
fi

# S5-3：CONDITIONAL 无豁免 → QG-02 失败且原因可读
sign_report "$RPT_H" 1 qa1 CONDITIONAL || true
sign_report "$RPT_H" 1 pm || true
sign_report "$RPT_H" 1 tl1 || true
submit_order "$ORDER_H"
COND_CODE="$HTTP_CODE"
COND_BODY="$HTTP_BODY"
COND_QG02="$(jqv '[.detail.failedRules[]? | .code] | index("QG-02") != null' "$COND_BODY")"
if [[ "$COND_CODE" == "409" && "$COND_QG02" == "true" ]] && needle "$COND_BODY" "CONDITIONAL"; then
    pass "报告结论 CONDITIONAL 且无豁免 → 409，QG-02 实际值含 CONDITIONAL"
else
    fail "CONDITIONAL 未被拦或原因丢失（code=${COND_CODE}, body=$(printf '%s' "$COND_BODY" | head -c 200)）" \
        "QG-02 只接受 PASS 或已批准豁免；actual 必须带 REPORT_CONDITIONAL_UNAPPROVED"
fi

# S5-4：EMERGENCY 缺原因 400 + 等级不变
api_call PATCH "/api/req/releases/${REL_K}/level" '{"releaseLevel":"EMERGENCY","emergencyReason":"   "}'
EM_CODE="$HTTP_CODE"
EM_FIELD="$(jqv '.detail.field // empty' "$HTTP_BODY")"
EM_LEVEL="$(release_field "$REL_K" '.release.releaseLevel')"
if [[ "$EM_CODE" == "400" && "$EM_FIELD" == "emergencyReason" && "$EM_LEVEL" == "NORMAL" ]]; then
    pass "EMERGENCY 缺原因 → 400 detail.field=emergencyReason，等级保持 NORMAL"
else
    fail "紧急原因校验不符（code=${EM_CODE}, field=${EM_FIELD:-?}, level=${EM_LEVEL:-?}）" \
        "ReleaseLevelValidator：原因 trim 后 1–500 字；校验先于写库"
fi

# --------------------------------------------------------------------------- S6
section "S6 回归与卫生（2）"
RC_FILE="${ROOT}/req-service/src/main/java/com/example/req/release/ReleaseController.java"
SNAP_REPO="${ROOT}/req-service/src/main/java/com/example/req/release/FlowSnapshotRepository.java"
S6_SRC_MISSING=""
NO_PUBLISH=1
# H4：grep 读不到文件仍返回 1（未匹配），会让 NO_PUBLISH 保持 1 = 假绿；先判存在。
if [[ ! -f "$RC_FILE" ]]; then
    NO_PUBLISH=0
    S6_SRC_MISSING="$RC_FILE"
else
    grep -qE '@(Post|Put|Patch)Mapping\("[^"]*publish' "$RC_FILE" && NO_PUBLISH=0
fi
if [[ ! -f "$SNAP_REPO" ]]; then
    SNAP_READONLY=0
else
    SNAP_READONLY=1
    grep -qiE '\b(UPDATE|DELETE)[[:space:]]+[a-z_]+' "$SNAP_REPO" && SNAP_READONLY=0
    grep -qE '(void|int|long|boolean|Optional|List)[[:space:]]+(update|delete)[A-Za-z]*[[:space:]]*\(' "$SNAP_REPO" && SNAP_READONLY=0
fi
if [[ "$NO_PUBLISH" == "1" && "$SNAP_READONLY" == "1" ]]; then
    pass "无发布 HTTP 路由；FlowSnapshotRepository 只有 save/读，无 UPDATE/DELETE（静态审计）"
else
    fail "只读证据被破坏（publishRoute=$NO_PUBLISH, repoReadOnly=${SNAP_READONLY:-file-missing}${S6_SRC_MISSING:+, 缺 ${S6_SRC_MISSING}}）" \
        "发布只能由审批回调调用；快照仓库不得提供更新/删除方法；源文件缺失也必须判红"
fi

# 第 32 条（r31 重写）：提交区间纯判据 BASE..HEAD + 同场正负变异自检。
# 分母仍 32：自检验证的是判据本身，不是新业务事实，独立计数会污染 AC-20 的分母语义。
POM_COUNT="$(git -C "$ROOT" ls-files '*pom.xml' | wc -l | tr -d ' ')"
HYGIENE_OUT=""
HYGIENE_RC=2
SELF_OUT=""
SELF_RC=2
if [[ -z "$GIT_BASE" || -z "$GIT_HEAD" ]]; then
    HYGIENE_OUT="基线不可解析：merge-base ${GIT_BASE_REF} HEAD 失败；用 RELEASE_FLOW_BASE_REF 指定可用基线"
else
    HYGIENE_OUT="$(check_commit_hygiene "$ROOT" "$GIT_BASE" "$GIT_HEAD" 2>&1)"
    HYGIENE_RC=$?
    SELF_OUT="$(hygiene_self_test "$GIT_BASE" 2>&1)"
    SELF_RC=$?
fi
HYGIENE_BRIEF="$(printf '%s' "$HYGIENE_OUT" | tr '\n' ';' | sed 's/;$//')"
SELF_BRIEF="$(printf '%s' "$SELF_OUT" | tr '\n' ';' | sed 's/;$//')"
[[ "$SELF_RC" == "0" ]] && printf '  %s\n' "$SELF_BRIEF"
if [[ "$HYGIENE_RC" == "0" && "$SELF_RC" == "0" ]]; then
    pass "提交区间 ${GIT_BASE:0:7}..${GIT_HEAD:0:7} 卫生：${POM_COUNT} 个 *pom.xml + web/package.json + web/pnpm-lock.yaml 无改动、迁移仅 0/1 条 A V14；判据自检 4/4（正例绿 / 改 V13 / 新增 V15 / 改模块 pom 均红）"
else
    fail "提交卫生不符或判据自检失败（range=${GIT_BASE:0:7}..${GIT_HEAD:0:7}, hygiene=${HYGIENE_RC}, selfTest=${SELF_RC}）" \
        "${HYGIENE_BRIEF:-无判据输出} | ${SELF_BRIEF:-无自检输出} —— AC-23：只认 BASE..HEAD；pom/lockfile 不得改；迁移只允许新增 V14；自检必须 1 正 3 反"
fi

# --------------------------------------------------------------------------- 汇总
printf '\nRELEASE_FLOW_TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$RELEASE_FLOW_TOTAL" "$CASE" "$PASS" "$FAIL"
SUMMARY_FILE="${REPORT_DIR}/summary-${TAG}.txt"
{
    printf 'r30 release flow gate @ %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
    printf 'TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$RELEASE_FLOW_TOTAL" "$CASE" "$PASS" "$FAIL"
    printf 'REPORT_DIR=%s\n' "$REPORT_DIR"
    [[ -s "$FAIL_LOG" ]] && { printf -- '--- failures ---\n'; cat "$FAIL_LOG"; }
} >"$SUMMARY_FILE"
printf '  报告: %s\n' "$SUMMARY_FILE"
if ((CASE != RELEASE_FLOW_TOTAL)); then
    printf '\033[1;31m断言数与自声明分母不一致：CASE=%d ≠ TOTAL=%d\033[0m\n' "$CASE" "$RELEASE_FLOW_TOTAL"
    printf '修复: 少执行的分组被提前 return，或新增断言未同步 TECHNICAL §7.2\n'
    exit 1
fi
if ((FAIL > 0)); then
    printf '\033[1;31m有 %d 条断言失败\033[0m\n' "$FAIL"
    exit 1
fi
printf '\033[1;32m全部 32 条通过：分级绑定、四段链路、不可变快照与三道闸门均成立\033[0m\n'
exit 0
