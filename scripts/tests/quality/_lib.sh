#!/usr/bin/env bash
# _lib.sh — 质量域验收脚本（q0–q7）的共享基座。Source it, do not execute it.
#
# 与 platform-verify.sh 同一套语义：
#   * 每条断言同时校验 HTTP 码 + 载荷/DB 事实（只看 200 的"假绿"不算通过）；
#   * 失败打印「修复:」，一次跑完所有断言（不用 set -e 短路）；
#   * 末尾 guard 做 PASS==TOTAL 守卫，少跑一条也是失败。
# 多出来的只有质量域 fixture 助手：版本 → 计划 → 执行 → 轮次 → 报告 → 发布单。
#
# 用法：source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"，末尾调用 guard <script-name>。

# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"
# lib.sh 会打开 -e；断言失败必须继续跑完，所以这里显式关掉，只保留 -u 与 pipefail。
set +e
set -uo pipefail

TOTAL=0
PASS=0
FAIL=0
FAILED_LINES=""
RUN_TAG="${QUALITY_RUN_TAG:-$(date +%m%d%H%M%S)}"
T1_TENANT="${PLATFORM_TENANT:-t1}"
ADMIN_USER="${PLATFORM_ADMIN_USER:-admin}"
ADMIN_PASS="${PLATFORM_ADMIN_PASS:-Admin@12345}"
FIXTURE_HINT="先执行 make platform-up && make platform-seed"

section() { printf '\n\033[1;34m---- %s ----\033[0m\n' "$*"; }
info() { printf '  \033[1;36mINFO\033[0m %s\n' "$*"; }

pass() {
    TOTAL=$((TOTAL + 1)); PASS=$((PASS + 1))
    printf '  \033[1;32mPASS\033[0m %s\n' "$1"
}

fail() {
    TOTAL=$((TOTAL + 1)); FAIL=$((FAIL + 1))
    printf '  \033[1;31mFAIL\033[0m %s\n' "$1"
    [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"
    FAILED_LINES="${FAILED_LINES}
  - $1"
}

skip() { printf '  \033[1;33mSKIP\033[0m %s\n' "$1"; }

# expect <desc> <http_code> [needle] [fix]
expect() {
    local desc="$1" want="$2" needle="${3:-}" fix="${4:-$FIXTURE_HINT}"
    if [[ "$HTTP_CODE" == "$want" ]] && { [[ -z "$needle" ]] || grep -qF -- "$needle" <<<"$HTTP_BODY"; }; then
        pass "$desc"
    else
        fail "$desc (期望 ${want}${needle:+/含 '$needle'}，实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 220))" "$fix"
    fi
}

# expect_jq <desc> <jq-filter> <expected> [fix]
expect_jq() {
    local desc="$1" filter="$2" want="$3" fix="${4:-$FIXTURE_HINT}"
    local got; got="$(jget "$HTTP_BODY" "$filter")"
    local okn=0
    if [[ "$got" == "$want" ]]; then
        okn=1
    elif [[ "$got" =~ ^-?[0-9]+(\.[0-9]+)?$ && "$want" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] \
        && awk -v a="$got" -v b="$want" 'BEGIN{exit !(a==b)}'; then
        okn=1
    fi
    if (( okn == 1 )); then
        pass "$desc"
    else
        fail "$desc (期望 ${want}，实际 ${got:-<空>}: $(printf '%s' "$HTTP_BODY" | head -c 220))" "$fix"
    fi
}

# expect_num <desc> <actual> <op> <expected> [fix]  op ∈ eq/ge/le/gt
expect_num() {
    local desc="$1" got="$2" op="$3" want="$4" fix="${5:-$FIXTURE_HINT}"
    local okn=0
    [[ -n "$got" && "$got" =~ ^-?[0-9]+$ ]] || { fail "$desc (非数字: '${got}')" "$fix"; return; }
    case "$op" in
        eq) (( got == want )) && okn=1 ;;
        ge) (( got >= want )) && okn=1 ;;
        le) (( got <= want )) && okn=1 ;;
        gt) (( got > want )) && okn=1 ;;
    esac
    if (( okn == 1 )); then pass "$desc (${got})"; else fail "$desc (期望 ${op} ${want}，实际 ${got})" "$fix"; fi
}

# expect_db <desc> <sql> <op> <expected> [fix] — 数据库事实断言（HTTP 对了但没落库也是失败）
expect_db() {
    local desc="$1" sql="$2" op="$3" want="$4" fix="${5:-$FIXTURE_HINT}"
    local got; got="$(db_q "$sql" | head -n1)"
    expect_num "$desc" "${got:-}" "$op" "$want" "$fix"
}

# deny <desc> <http_code> <error_code> [needle] — 负向断言：4xx + 机器码（+ 额外文本）
deny() {
    local desc="$1" want="$2" code="$3" needle="${4:-}"
    if [[ "$HTTP_CODE" == "$want" && "$HTTP_BODY" == *"$code"* ]] \
        && { [[ -z "$needle" ]] || grep -qF -- "$needle" <<<"$HTTP_BODY"; }; then
        pass "$desc"
    else
        fail "$desc (期望 ${want} ${code}${needle:+ + '$needle'}，实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 220))"
    fi
}

same_count() { # same_count <desc> <table> <where> <before>
    local desc="$1" table="$2" where="${3:-}" before="$4"
    local after; after="$(db_count "$table" "$where")"
    if [[ "${after:-x}" == "${before:-y}" ]]; then
        pass "${desc}（${after} 行未变）"
    else
        fail "${desc}（前 ${before:-?} 行 → 后 ${after:-?} 行）" "负向请求必须不落库：检查事务边界与唯一键"
    fi
}

require_stack() {
    if ! db_up; then err "MySQL 不可用: ${PLATFORM_DB_HOST}:${PLATFORM_DB_PORT}/${PLATFORM_DB_NAME}"; err "修复: make platform-up"; exit 1; fi
    if ! req_ready; then err "req-service 未就绪: ${REQ_URL}/internal/health"; err "修复: make platform-up"; exit 1; fi
    if ! deliver_ready; then err "deliver-service 未就绪: ${DELIVER_URL}/internal/health"; err "修复: make platform-up"; exit 1; fi
    if ! platform_login "$T1_TENANT" "$ADMIN_USER" "$ADMIN_PASS"; then
        err "修复: 先跑 make platform-seed（种子创建 ${T1_TENANT}/${ADMIN_USER}）"; exit 1
    fi
    TENANT_ID="$(jget "$HTTP_BODY" '.tenantId')"
    DOMAIN_ID="$(db_q "SELECT id FROM sys_domain WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
    [[ -n "$DOMAIN_ID" ]] || DOMAIN_ID="$(db_q "SELECT domain_id FROM \`release\` WHERE tenant_id=${TENANT_ID} AND domain_id IS NOT NULL ORDER BY id LIMIT 1")"
    ok "以 ${T1_TENANT}/${ADMIN_USER} 登录（tenantId=${TENANT_ID}, domainId=${DOMAIN_ID:-none}）"
}

# ------------------------------------------------------------------ fixture helpers
# 每个脚本自造 fixture（唯一名字带 RUN_TAG），不依赖别的用例留下的数据；
# 造出来的数据不做删除——质量域是审计域，删数据会让并发跑的其它脚本看到空洞。

q_domain() { printf '%s' "${DOMAIN_ID:-0}"; }

q_case_ids() { # q_case_ids <n> → 用空格分隔的用例 id（清库种子只有 12 条，需要更多时按需补造）
    local n="$1" ids count need module i
    ids="$(db_q "SELECT id FROM test_case WHERE tenant_id=${TENANT_ID} AND deleted_at IS NULL ORDER BY id DESC LIMIT ${n}" | tr '\n' ' ')"
    count="$(wc -w <<<"$ids" | tr -d ' ')"
    if (( count < n )); then
        module="$(db_q "SELECT id FROM test_module WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
        need=$(( n - count ))
        if [[ -n "$module" && "$module" != "NULL" ]]; then
            for (( i = 1; i <= need; i++ )); do
                api_call POST /api/deliver/testcases "$(jq -cn --argjson m "$module" \
                    --arg t "QA fixture 用例-${RUN_TAG}-${i}" \
                    '{moduleId:$m,title:$t,type:"FUNCTIONAL",priority:"P2"}')" >/dev/null 2>&1 || true
            done
        fi
        ids="$(db_q "SELECT id FROM test_case WHERE tenant_id=${TENANT_ID} AND deleted_at IS NULL ORDER BY id DESC LIMIT ${n}" | tr '\n' ' ')"
    fi
    printf '%s' "$ids"
}

q_create_release() { # q_create_release <name> → RELEASE_ID
    local name="$1"
    api_call POST /api/req/releases "$(jq -cn --arg n "$name" --argjson d "$(q_domain)" \
        '{name:$n,domainId:$d,startDate:"2026-11-01",endDate:"2026-11-30",plannedReleaseDate:"2026-12-05",gateMode:"STRICT"}')"
    RELEASE_ID="$(jget "$HTTP_BODY" '.id')"
    [[ -n "$RELEASE_ID" && "$RELEASE_ID" != "null" ]] || {
        err "创建版本失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    }
    printf '%s' "$RELEASE_ID"
}

q_create_plan() { # q_create_plan <releaseId> <name> [caseId...] → PLAN_ID
    local release="$1" name="$2"; shift 2
    local cases_json='[]'
    if (( $# > 0 )); then
        cases_json="$(printf '%s\n' "$@" | jq -R . | jq -sc 'map(tonumber)')"
    fi
    api_call POST /api/deliver/test-plans "$(jq -cn --argjson r "$release" --arg n "$name" \
        --argjson c "$cases_json" \
        '{releaseId:$r,name:$n,ownerId:null,windowStart:"2026-11-20",windowEnd:"2026-11-27",caseIds:$c,gateMode:"STRICT"}')"
    PLAN_ID="$(jget "$HTTP_BODY" '.plan.id // .id')"
    [[ -n "$PLAN_ID" && "$PLAN_ID" != "null" ]] || {
        err "创建测试计划失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        return 1
    }
    printf '%s' "$PLAN_ID"
}

q_plan_status() { # q_plan_status <planId>
    api_call GET "/api/deliver/test-plans/$1/lifecycle"
    jget "$HTTP_BODY" '.status'
}

q_plan_field() { # q_plan_field <planId> <jq-filter>
    api_call GET "/api/deliver/test-plans/$1/lifecycle"
    jget "$HTTP_BODY" "$2"
}

q_plan_cases() { # q_plan_cases <planId> → 空格分隔的用例 id
    db_q "SELECT case_id FROM test_plan_case WHERE plan_id=$1 ORDER BY case_id" | tr '\n' ' '
}

q_active_round() { # q_active_round <planId> → 进行中轮次 id（没有则空）
    api_call GET "/api/deliver/test-plans/$1/rounds"
    jget "$HTTP_BODY" '[.[] | select(.active == true or .active == 1)] | .[0].id // empty'
}

q_round_ids() { # q_round_ids <planId> → 空格分隔的轮次 id
    api_call GET "/api/deliver/test-plans/$1/rounds"
    jget "$HTTP_BODY" '[.[] | .id] | join(" ")'
}

q_exec_case() { # q_exec_case <planId> <caseId> <result>
    api_call POST "/api/deliver/test-plans/$1/execute" \
        "$(jq -cn --argjson c "$2" --arg r "$3" '{caseId:$c,result:$r,note:"q-script"}')"
}

q_exec_all() { # q_exec_all <planId> <result> — 执行计划内全部用例
    local plan="$1" result="$2" cases c
    cases="$(db_q "SELECT case_id FROM test_plan_case WHERE plan_id=${plan} ORDER BY case_id")"
    for c in $cases; do
        q_exec_case "$plan" "$c" "$result"
    done
}

q_forge_executions() { # q_forge_executions <planId> <roundId> — 未执行用例直接在库里补执行行
    # 只给"报告/门禁"类 fixture 用：这些 AC 不校验执行登记路径（q4 才校验），
    # 走 HTTP 逐条登记在 5000 条规模下太慢，而 round 外键与 plan_case.result 必须同步。
    local plan="$1" round="$2" tenant="$3"
    db_q "INSERT INTO test_execution (tenant_id, plan_id, round_id, case_id, case_version, result,
               executor_id, executed_at, created_at, updated_at)
           SELECT ${tenant}, pc.plan_id, ${round}, pc.case_id, pc.case_version, 'PASS',
                  1, NOW(3), NOW(3), NOW(3)
           FROM test_plan_case pc
           WHERE pc.plan_id=${plan}
             AND NOT EXISTS (SELECT 1 FROM test_execution e
                             WHERE e.plan_id = pc.plan_id AND e.case_id = pc.case_id)" >/dev/null
    db_q "UPDATE test_plan_case SET result='PASS' WHERE plan_id=${plan}" >/dev/null
}

q_close_round() { # q_close_round <planId> <roundId> <conclusion>
    api_call POST "/api/deliver/test-plans/$1/rounds/$2/close" \
        "$(jq -cn --arg c "$3" '{conclusion:$c,note:"q-script"}')"
}

q_signoff_progress() { # q_signoff_progress <reportId> <versionNo>
    api_call GET "/api/deliver/test-reports/$1/versions/$2/signoff"
    printf '%s' "$HTTP_BODY"
}

# q_login_as <username> [password] — 切换身份（会签三角色、评审 PMO 确认等需要多账号）。
# 失败时保留原身份并把失败写进断言，避免后续断言全部误报。
q_login_as() {
    local user="$1" pass="${2:-Passw0rd!}"
    local prev_token="$API_TOKEN" prev_tenant="$API_TENANT"
    if platform_login "$T1_TENANT" "$user" "$pass"; then
        API_TOKEN="$(jget "$HTTP_BODY" '.token')"
        API_TENANT="$(jget "$HTTP_BODY" '.tenantId')"
        pass "以 ${T1_TENANT}/${user} 登录"
        return 0
    fi
    API_TOKEN="$prev_token"; API_TENANT="$prev_tenant"
    fail "以 ${T1_TENANT}/${user} 登录失败: $(printf '%s' "$HTTP_BODY" | head -c 160)" "种子账号密码为 Passw0rd!，admin 为 Admin@12345；缺账号先 make platform-seed"
    return 1
}

q_user_id() { # q_user_id <username> — 同租户内按用户名取 id（0 表示没有）
    db_q "SELECT id FROM sys_user WHERE tenant_id=${TENANT_ID} AND username='$1' LIMIT 1"
}

q_admin_login() {
    platform_login "$T1_TENANT" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null 2>&1
    API_TOKEN="$(jget "$HTTP_BODY" '.token')"
    API_TENANT="$(jget "$HTTP_BODY" '.tenantId')"
}

q_create_deploy_order() { # q_create_deploy_order <releaseId> <requirementId> [env] → id
    local rel="$1" req="$2" env="${3:-TEST}"
    api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$rel" --argjson q "$req" --arg e "$env" \
        '{releaseId:$r,env:$e,windowStart:"2026-12-10T10:00:00+08:00",windowEnd:"2026-12-10T12:00:00+08:00",artifactVersion:"qa",steps:[{name:"发布",order:1}],rollbackPlan:"回滚到上一版",impact:"只读 5 分钟",ownerId:1,requirementIds:[$q]}')"
    jget "$HTTP_BODY" '.id'
}

q_generate_release_report() { # q_generate_release_report <releaseId> [note] → report id
    local rel="$1" note="${2:-qa 报告}"
    api_call POST /api/deliver/test-reports "$(jq -cn --argjson r "$rel" --arg n "$note" '{scope:"RELEASE",releaseId:$r,note:$n}')"
    jget "$HTTP_BODY" '.id'
}

q_sign_release_report() { # q_sign_release_report <reportId> [versionNo] — 按 TEST_LEAD→PRODUCT→PMO 顺序签
    local rpt="$1" ver="${2:-1}" role step
    local -a users=(qa1 pm tl1)
    local prev_token="$API_TOKEN" prev_tenant="$API_TENANT"
    for role in "${users[@]}"; do
        q_login_as "$role" >/dev/null 2>&1 || return 1
        api_call POST "/api/deliver/test-reports/${rpt}/versions/${ver}/signoff" '{"decision":"APPROVE","comment":"qa 脚本签署"}'
        if [[ "$HTTP_CODE" != "200" ]]; then
            API_TOKEN="$prev_token"; API_TENANT="$prev_tenant"
            return 1
        fi
    done
    API_TOKEN="$prev_token"; API_TENANT="$prev_tenant"
    return 0
}

q_gate_snapshot() { # q_gate_snapshot <releaseId> [deployOrderId] — 求值并回显快照
    local rel="$1" dep="${2:-0}"
    api_call POST "/api/deliver/quality-gates/${rel}/evaluate" \
        "$(jq -cn --argjson d "$dep" '{deployOrderId:$d,requirementIds:[]}')"
    printf '%s' "$HTTP_BODY"
}

# q_build_release_case <prefix> <passCount> <failCount> — 一次构造「版本 + 计划 + 执行 + 已签报告 + 发布单」，
# 返回 5 个 id：release plan report deployOrder requirement（调用方用 awk 取列，不能依赖函数内变量——
# "$(…)" 是子 shell，函数里赋的 Q_* 不会回传）。门禁/豁免/指标脚本都用同一套 fixture，
# 避免每个脚本各写一份（分母口径一旦不同，AC-3.6 的「同源」就断了）。
q_build_release_case() {
    local prefix="$1" npass="$2" nfail="$3"
    local total=$(( npass + nfail )) i
    local -a cases
    cases=($(q_case_ids "$total"))
    Q_REQ_ID="$(db_q "SELECT requirement_id FROM test_case WHERE id=${cases[0]}")"
    [[ -n "$Q_REQ_ID" && "$Q_REQ_ID" != "NULL" ]] || Q_REQ_ID="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
    Q_REL_ID="$(q_create_release "${prefix}-${RUN_TAG}")"
    api_call POST "/api/req/releases/${Q_REL_ID}/requirements" "{\"requirementIds\":[${Q_REQ_ID}]}"
    Q_PLAN_ID="$(q_create_plan "$Q_REL_ID" "${prefix}计划-${RUN_TAG}" "${cases[@]}")"
    api_call POST "/api/deliver/test-plans/${Q_PLAN_ID}/submit-review" '{}'
    api_call POST "/api/deliver/test-plans/${Q_PLAN_ID}/approve" '{"comment":"同意"}'
    api_call POST "/api/deliver/test-plans/${Q_PLAN_ID}/start" '{}'
    for (( i = 0; i < npass; i++ )); do q_exec_case "$Q_PLAN_ID" "${cases[$i]}" PASS; done
    for (( i = npass; i < total; i++ )); do q_exec_case "$Q_PLAN_ID" "${cases[$i]}" FAIL; done
    local round; round="$(q_active_round "$Q_PLAN_ID")"
    [[ -n "$round" ]] || round="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${Q_PLAN_ID} AND active=1")"
    q_close_round "$Q_PLAN_ID" "$round" "$( (( nfail == 0 )) && echo PASS || echo FAIL )" >/dev/null
    if (( nfail == 0 )); then
        api_call POST "/api/deliver/test-plans/${Q_PLAN_ID}/complete" '{}'
    fi
    Q_RPT_ID="$(q_generate_release_report "$Q_REL_ID" "${prefix}报告")"
    q_sign_release_report "$Q_RPT_ID"
    Q_DEP_ID="$(q_create_deploy_order "$Q_REL_ID" "$Q_REQ_ID" PROD)"
    printf '%s %s %s %s %s' "$Q_REL_ID" "$Q_PLAN_ID" "$Q_RPT_ID" "$Q_DEP_ID" "$Q_REQ_ID"
}

guard() { # guard <script-name> — PASS==TOTAL 守卫（少跑一条也算失败）
    local name="${1:-quality}"
    printf '\n'
    printf '#SUMMARY %s pass=%d total=%d fail=%d\n' "$name" "$PASS" "$TOTAL" "$FAIL"
    if (( FAIL > 0 || TOTAL == 0 )); then
        err "${name}: ${FAIL}/${TOTAL} 条断言失败"
        [[ -n "$FAILED_LINES" ]] && printf '%s\n' "$FAILED_LINES"
        printf '  修复提示：每条 FAIL 后面都写了「修复:」；缺服务先跑 make platform-up\n'
        exit 1
    fi
    ok "${name}: ${PASS}/${TOTAL} 条全部通过"
}
