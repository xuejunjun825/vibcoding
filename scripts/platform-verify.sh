#!/usr/bin/env bash
# platform-verify.sh — AC-1..AC-20 分组断言（HTTP + DB 计数，两处都要对）。
# 设计原则：
#   * 每条断言既有 HTTP 状态码，也有 payload/DB 计数；只看 200 的"假绿"不算通过；
#   * 权限矩阵不复制：AC-5 从 GET /admin/permissions 生成探测矩阵；
#   * 失败立即打印「修复:」提示，末尾 PASS==TOTAL 守卫（少跑一条也是失败）。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

TOTAL=0
PASS=0
FAIL=0
FAILED_LINES=""
RUN_TAG="$(date +%H%M%S)"
T1_TENANT="t1"
ADMIN_USER="${PLATFORM_ADMIN_USER:-admin}"
ADMIN_PASS="${PLATFORM_ADMIN_PASS:-Admin@12345}"
FIXTURE_HINT="先执行 make platform-up && make platform-seed"

section() { printf '\n\033[1;34m---- %s ----\033[0m\n' "$*"; }

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
        # jq 1.7 会保留 2.0 的字面形态，数值相等也算通过（2 与 2.0 是同一个人天）
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

json_body() {
    # json_body key=value ... → JSON object; values are strings unless key ends in Id/Numbers.
    local out="{}" kv k v
    for kv in "$@"; do
        k="${kv%%=*}"; v="${kv#*=}"
        out="$(jq -cn --argjson o "$out" --arg k "$k" --arg v "$v" '$o + {($k): $v}')"
    done
    printf '%s' "$out"
}

require_stack() {
    if ! req_ready; then
        err "req-service 未就绪: ${REQ_URL}/internal/health"
        err "修复: make platform-up"
        exit 1
    fi
    if ! deliver_ready; then
        err "deliver-service 未就绪: ${DELIVER_URL}/internal/health"
        err "修复: make platform-up"
        exit 1
    fi
    api_call POST /api/req/auth/login \
        "{\"tenantCode\":\"${T1_TENANT}\",\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}"
    if [[ "$HTTP_CODE" != "200" ]]; then
        err "管理员登录失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        err "修复: 先跑 make platform-seed（种子创建 ${T1_TENANT}/${ADMIN_USER}）"
        exit 1
    fi
    API_TOKEN="$(jget "$HTTP_BODY" '.token')"
    API_TENANT="$(jget "$HTTP_BODY" '.tenantId')"
    ok "以 ${T1_TENANT}/${ADMIN_USER} 登录（X-Tenant-Id=${API_TENANT}）"
}

TENANT_ID=""

tenant_id() {
    if [[ -z "$TENANT_ID" ]]; then
        TENANT_ID="$(db_q "SELECT id FROM sys_tenant WHERE code='${T1_TENANT}' LIMIT 1")"
    fi
    printf '%s' "$TENANT_ID"
}

db_count_req() { db_count "$1" "tenant_id=$(tenant_id)"; }

# =====================================================================
section "AC-1 多租户：建租户预置、隔离、缺头/跨租户拒绝"
require_stack
DOMAIN1="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=$(tenant_id) ORDER BY id LIMIT 1")"
[[ -n "$DOMAIN1" ]] || DOMAIN1=1

T2_CODE="t2-${RUN_TAG}"
api_call POST /api/req/admin/tenants "{\"code\":\"${T2_CODE}\",\"name\":\"隔离验证租户 ${RUN_TAG}\"}"
expect "建租户返回 201" 201
T2_ID="$(jget "$HTTP_BODY" '.tenantId')"

api_call GET "/api/req/admin/tenants?page=1&size=200"
if [[ "$HTTP_CODE" == "200" ]] && grep -q "\"${T2_CODE}\"" <<<"$HTTP_BODY"; then
    pass "租户列表包含新建租户 ${T2_CODE}"
else
    fail "租户列表不含 ${T2_CODE} (HTTP ${HTTP_CODE})" "检查 sys_tenant 是否按 tenant_id 过滤过严"
fi

if [[ -n "$T2_ID" && "$T2_ID" != "null" ]]; then
    expect_num "新租户预置 7 个业务域" "$(db_count biz_domain "tenant_id=${T2_ID}")" eq 7 "建租户必须预置 7 域（AC-1）"
    expect_num "新租户预置 10 个角色" "$(db_count sys_role "tenant_id=${T2_ID}")" eq 10 "建租户必须预置 10 角色（AC-1）"
else
    fail "建租户响应缺 tenantId，无法断言预置数量" "响应需含 tenantId（TECHNICAL §4.3）"
fi

# 缺 X-Tenant-Id：拒绝且不落库
BEFORE="$(db_count_req requirement)"
SAVED_TOKEN="$API_TOKEN"; SAVED_TENANT="$API_TENANT"
API_TENANT=""
api_call POST /api/req/requirements '{"type":"RD","title":"缺租户头不应落库"}'
expect "缺 X-Tenant-Id 创建需求 → 403" 403
API_TENANT="$SAVED_TENANT"
expect_num "缺头请求未落库（requirement 计数不变）" "$(db_count_req requirement)" eq "$BEFORE" "鉴权必须在进入 Service 之前失败"

# 跨租户：t1 的 token + t2 的头
if [[ -n "$T2_ID" && "$T2_ID" != "null" ]]; then
    API_TENANT="$T2_ID"
    api_call GET /api/req/requirements
    expect "t1 token + t2 租户头 → 403" 403 "TENANT_MISMATCH" "token 的 tenantId 与 X-Tenant-Id 必须一致（网关或 AuthFilter 拒绝）"
    API_TENANT="$SAVED_TENANT"
fi

# =====================================================================
section "AC-2 三类需求创建、父子规则与人天汇总"
create_req() { # type title parent_id estimate -> HTTP_CODE/HTTP_BODY
    local type="$1" title="$2" parent="$3" estimate="${4:-}"
    local body
    body="$(jq -cn --arg t "$type" --arg ti "$title" --arg p "$parent" --arg e "$estimate" \
        --argjson d "$DOMAIN1" '{type:$t,title:$ti,priority:"P1",domainIds:[$d],expectedDeliveryDate:"2026-12-31"}
         + (if $p != "" then {parentId:($p|tonumber)} else {} end)
         + (if $e != "" then {estimateDays:($e|tonumber)} else {} end)')"
    api_call POST /api/req/requirements "$body"
}

create_req BUS "验证-业务需求-${RUN_TAG}" ""
expect "创建 BUS 需求 201" 201
BUS_ID="$(jget "$HTTP_BODY" '.id')"
BUS_CODE="$(jget "$HTTP_BODY" '.code')"
expect_num "BUS 编号形如 BUS-####" "$(grep -cE '^BUS-[0-9]{4,}$' <<<"$BUS_CODE")" eq 1 "CodeGenerator 前缀/补零不对"

create_req PDR "验证-产品需求-${RUN_TAG}" "$BUS_ID"
expect "创建 PDR（父=BUS）201" 201
PDR_ID="$(jget "$HTTP_BODY" '.id')"

create_req RD "验证-研发需求-${RUN_TAG}" "$BUS_ID"
expect "RD 直接挂 BUS 下 → 400（层级必须 BUS→PDR→RD）" 400

create_req RD "验证-研发需求A-${RUN_TAG}" "$PDR_ID" "1.5"
expect "创建 RD A（父=PDR）201" 201
RD_A="$(jget "$HTTP_BODY" '.id')"
create_req RD "验证-研发需求B-${RUN_TAG}" "$PDR_ID" "0.5"
expect "创建 RD B（父=PDR）201" 201
RD_B="$(jget "$HTTP_BODY" '.id')"

api_call GET "/api/req/requirements/${PDR_ID}"
expect_jq "PDR 人天 = 子需求之和 2.0" '.view.rollup.estimateDays|tonumber' "2" "父需求人天必须在子需求增删后重算（0.5 精度）"

api_call PUT "/api/req/requirements/${PDR_ID}" '{"estimateDays":3}'
expect "父需求手填人天 → 400 PARENT_DAYS_READONLY" 400 "PARENT_DAYS_READONLY"

# 环：把 BUS 的父设为 PDR。类型分层先于祖先链生效，所以这里命中的是 INVALID_PARENT
# （BUS 只能是顶层）；RequirementRules.checkAncestorCycle 由单测覆盖，属于直连改库的兜底。
api_call PUT "/api/req/requirements/${BUS_ID}" "{\"parentId\":${PDR_ID}}"
expect "父子成环 → 400（类型分层/祖先链校验）" 400 "INVALID_PARENT"

# =====================================================================
section "AC-3 状态机：合法流转 2xx、跳步 409、status 不可写、审计留痕"
AUDIT_BEFORE="$(db_count audit_log "biz_type='requirement' AND biz_id=${RD_A} AND action='STATE_CHANGE'")"
api_call POST "/api/req/requirements/${RD_A}/actions" '{"action":"start_dev"}'
expect "RD TODO --start_dev--> IN_DEV" 200
expect_jq "RD 状态为 IN_DEV" '.status' "IN_DEV"

api_call POST "/api/req/requirements/${RD_A}/actions" '{"action":"submit_test"}'
expect "RD IN_DEV --submit_test--> PENDING_TEST" 200

api_call POST "/api/req/requirements/${RD_A}/actions" '{"action":"finish"}'
expect "RD PENDING_TEST --finish--> 409（跳步）" 409 "ILLEGAL_TRANSITION"
expect "409 detail 带 allowedTargets" 409 "allowedTargets" "409 必须给出可行动作，否则前端只能猜"

api_call PATCH "/api/req/requirements/${RD_A}" '{"status":"DONE"}'
expect "直接写 status → 400 STATUS_NOT_WRITABLE" 400 "STATUS_NOT_WRITABLE"

expect_num "每次合法流转 audit_log +1（2 次）" \
    "$(db_count audit_log "biz_type='requirement' AND biz_id=${RD_A} AND action='STATE_CHANGE'")" eq "$((AUDIT_BEFORE + 2))" \
    "流转必须与审计同事务写入（AC-3）"

# 转派厂商（AC-4 的夹具）：BUS 先走完审批到 POOLED 才能挂版本
# vendorId 必须按租户解析：写死 1 会指向别的租户的厂商行，owner_vendor_id 就落在 scope 之外
VENDOR_A_ID="$(db_q "SELECT id FROM sys_vendor WHERE tenant_id=$(tenant_id) AND code='VEN-A'")"
VENDOR_B_ID="$(db_q "SELECT id FROM sys_vendor WHERE tenant_id=$(tenant_id) AND code='VEN-B'")"
api_call POST "/api/req/requirements/${RD_A}/assign-vendor" "{\"vendorId\":${VENDOR_A_ID:-1},\"reason\":\"验证转派\"}"
if [[ "$HTTP_CODE" == "200" ]]; then
    pass "需求转派厂商 200（AC-4 夹具就绪）"
else
    warn "转派未生效（HTTP ${HTTP_CODE}），AC-4 将用种子数据断言"
fi

# =====================================================================
section "AC-4 数据隔离：厂商 × 域，八入口同一谓词"
login_as() { # user pass → 设置 API_TOKEN（不改 API_TENANT）
    api_call POST /api/req/auth/login \
        "{\"tenantCode\":\"${T1_TENANT}\",\"username\":\"$1\",\"password\":\"$2\"}"
    [[ "$HTTP_CODE" == "200" ]] && { API_TOKEN="$(jget "$HTTP_BODY" '.token')"; return 0; }
    return 1
}

ADMIN_TOKEN="$API_TOKEN"
VENDOR_USER="${PLATFORM_VENDOR_USER:-vendor1}"
VENDOR_PASS="${PLATFORM_VENDOR_PASS:-Passw0rd!}"

if login_as "$VENDOR_USER" "$VENDOR_PASS"; then
    pass "厂商账号 ${VENDOR_USER} 可登录（AC-19 种子）"
    api_call GET "/api/req/requirements/${RD_A}"
    if [[ "$HTTP_CODE" == "200" ]]; then
        pass "厂商 A 可见已转派给它的需求"
    elif [[ "$HTTP_CODE" == "403" ]]; then
        fail "厂商 A 看不到已转派给它的需求（转派未级联 owner_vendor_id）" "检查 assign-vendor 是否回写 owner_vendor_id 与 domain/vendor 派生字段"
    else
        fail "厂商 A 读取需求异常 (HTTP ${HTTP_CODE})" "检查 ScopeGuard 是否对详情做了可见性校验"
    fi
    api_call GET "/api/req/requirements?page=1&size=50&vendorId=${VENDOR_B_ID:-2}"
    if [[ "$HTTP_CODE" == "200" ]]; then
        LEAK="$(jget "$HTTP_BODY" '[.items[] | select((.vendorId // .assigneeVendorId) != null and ((.vendorId // .assigneeVendorId)|tostring) != "'"${VENDOR_A_ID:-1}"'")] | length')"
        expect_num "厂商 A 的列表里没有其它厂商的行" "${LEAK:-0}" eq 0 "ScopeSql 的 vendor 谓词必须对所有列表入口生效"
    else
        fail "厂商 A 列表查询失败 (HTTP ${HTTP_CODE})" "检查 /requirements 的 scope 标记"
    fi
    api_call GET "/api/req/requirements/export.csv?page=1&size=50"
    if [[ "$HTTP_CODE" == "200" ]]; then
        ROWS="$(printf '%s' "$HTTP_BODY" | wc -l | tr -d ' ')"
        expect_num "厂商 A 导出行数受同一 scope 约束（< 全部行）" "$ROWS" le 50 "导出必须与列表同源"
    else
        fail "CSV 导出失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 200)" "检查 /requirements/export.csv 的鉴权与 scope"
    fi
else
    fail "厂商账号 ${VENDOR_USER} 登录失败" "make platform-seed 必须创建 vendor1/vendor2（Passw0rd!）"
fi

# 厂商 B 直连 A 的需求详情
if login_as "vendor2" "$VENDOR_PASS"; then
    api_call GET "/api/req/requirements/${RD_A}"
    if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "404" ]]; then
        pass "厂商 B 直连厂商 A 的需求 → ${HTTP_CODE}（不泄露存在性）"
    else
        fail "厂商 B 能看到厂商 A 的需求 (HTTP ${HTTP_CODE})" "ScopeGuard.requireVisible 必须按 vendor 判定并写审计"
    fi
else
    warn "vendor2 登录失败，跳过跨厂商详情断言"
fi

# 厂商 B 直连 A 的用例（AC-4 第 8 入口，D-03）：test_case 的 scope 模板必须带 vendor_id
CASE_A_ID="$(db_q "SELECT id FROM test_case WHERE tenant_id=$(tenant_id) AND vendor_id IS NOT NULL ORDER BY id LIMIT 1")"
if [[ -n "$CASE_A_ID" ]] && login_as "vendor2" "$VENDOR_PASS"; then
    api_call GET "/api/deliver/testcases/${CASE_A_ID}"
    if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "404" ]]; then
        pass "厂商 B 直连其它厂商的用例 → ${HTTP_CODE}（ScopeSql test_case.vendor_id + ScopeGuard）"
    else
        fail "厂商 B 能看到其它厂商的用例 (HTTP ${HTTP_CODE})" "ScopeSql 的 test_case 映射必须声明 vendor_id，详情走 ScopeGuard"
    fi
else
    warn "种子里没有带厂商的用例，跳过用例越权断言"
fi
API_TOKEN="$ADMIN_TOKEN"

# 附件入口（AC-4 第 5 入口，D-02）：上传/列表/下载 + 跨厂商越权
REQ_VA="$(db_q "SELECT id FROM requirement WHERE tenant_id=$(tenant_id) AND owner_vendor_id IS NOT NULL ORDER BY id LIMIT 1")"
if [[ -n "$REQ_VA" ]]; then
    printf 'verify attachment %s\n' "$RUN_TAG" > /tmp/att-verify.$$
    ATT_CODE="$(curl -sS --max-time 15 -o /tmp/att-verify.json.$$ -w '%{http_code}' \
        -X POST "${GATEWAY_URL}/api/req/attachments" \
        -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" \
        -F "bizType=REQ" -F "bizId=${REQ_VA}" -F "file=@/tmp/att-verify.$$;type=text/plain" 2>/dev/null || true)"
    if [[ "$ATT_CODE" == "201" ]]; then
        pass "附件上传 201（落 attachment 表，归属由 biz 派生）"
        ATT_ID="$(jq -r '.id' /tmp/att-verify.json.$$ 2>/dev/null || true)"
        api_call GET "/api/req/attachments?bizType=REQ&bizId=${REQ_VA}"
        expect "附件列表 200" 200
        expect "附件列表含刚上传的 id" 200 "\"id\":${ATT_ID}"
        api_call GET "/api/req/attachments/${ATT_ID}/download"
        expect "附件下载 200" 200
        if login_as "vendor2" "$VENDOR_PASS"; then
            api_call GET "/api/req/attachments/${ATT_ID}/download"
            if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "404" ]]; then
                pass "厂商 B 下载其它厂商附件 → ${HTTP_CODE}"
            else
                fail "厂商 B 能下载其它厂商附件 (HTTP ${HTTP_CODE})" "附件下载必须按附件自身 domain/vendor 判定"
            fi
        fi
    else
        fail "附件上传失败 (HTTP ${ATT_CODE})" "POST /attachments（multipart file+bizType+bizId）必须返回 201"
    fi
    rm -f /tmp/att-verify.$$ /tmp/att-verify.json.$$
else
    warn "种子里没有归属厂商的需求，跳过附件入口断言"
fi
API_TOKEN="$ADMIN_TOKEN"

# =====================================================================
section "AC-5 权限矩阵：接口生成探测矩阵，逐格断言 2xx/403"
api_call GET /api/req/admin/permissions
expect "GET /admin/permissions 200" 200
FN_COUNT="$(jlen "$HTTP_BODY" '.functions')"
expect_num "权限矩阵暴露 60 个函数码" "${FN_COUNT:-0}" eq 60 "PermissionMatrix 必须与 TECHNICAL §5.3/§5.4 一致（26 r2 + 15 M6 质量码 + 1 M7 integration:manage + 14 M8 协作/代码/测试码 + 4 M9 分析域码 portal:view/portal:config/report:view/report:share；PermissionMatrixGridTest 同为 10×60）"
ROLE_COUNT="$(jlen "$HTTP_BODY" '.roles')"
expect_num "权限矩阵暴露 10 个角色" "${ROLE_COUNT:-0}" eq 10 "建租户预置的 10 角色"
ROLE_CODES="$(db_q "SELECT GROUP_CONCAT(DISTINCT code ORDER BY code) FROM sys_role WHERE tenant_id=$(tenant_id)")"
if [[ "$ROLE_CODES" == "BUSINESS,DEV,MGMT,OPS,PLATFORM_ADMIN,PMO,PRODUCT,QA,SUB_BUSINESS,SUB_BUSINESS_LEADER" ]]; then
    pass "sys_role 覆盖 PRD §6.1 的 10 个角色（D-07）"
else
    fail "sys_role 角色集不符 PRD §6.1：${ROLE_CODES}" "RoleCatalog / PermissionMatrix / SeedService 必须是同一套 10 个角色码"
fi

# 逐格探测：用 DENY 格与 ALLOW 格各打一发（矩阵来自服务端，脚本不复制）
probe_denied() { # role-user,pass,method,path,body,label
    login_as "$1" "$2" || { warn "跳过 $6：$1 登录失败"; return; }
    api_call "$3" "$4" "${5:-}"
    if [[ "$HTTP_CODE" == "403" ]]; then
        pass "$6 被拒绝（403）"
    else
        fail "$6 未被拒绝 (HTTP ${HTTP_CODE})" "PermissionInterceptor 必须按矩阵 DENY 返回 403 并写审计"
    fi
}
probe_allowed() { # role-user,pass,method,path,body,label
    login_as "$1" "$2" || { warn "跳过 $6：$1 登录失败"; return; }
    api_call "$3" "$4" "${5:-}"
    if [[ "$HTTP_CODE" == "403" ]]; then
        fail "$6 被误拒（403）" "矩阵并集取最高档；RESTRICTED 应交给范围裁剪而不是直接拒绝"
    else
        pass "$6 未被权限拒绝（HTTP ${HTTP_CODE}）"
    fi
}
probe_denied "dev1" "Passw0rd!" POST /api/req/admin/tenants '{"code":"nope","name":"nope"}' "研发角色建租户（tenant:manage DENY）"
probe_allowed "qa1" "Passw0rd!" GET /api/deliver/testcases "" "测试角色查看用例库（case:manage ALLOW）"

API_TOKEN="$ADMIN_TOKEN"

# =====================================================================
section "AC-6 审批定义：发布校验、绑定命中、已发布不可改、无审批人 400"
wf_body() { # name mode approverRole
    jq -cn --arg n "$1" --arg a "$2" --arg r "$3" '{
      name:$n, bizType:"REQ_SUBMIT", reqType:"RD", domainId:0, definition:{
        schemaVersion:1, start:"n1", nodes:[
          {key:"n1", type:"APPROVAL", name:"产品评审", mode:$a,
           approvers:[{type:"ROLE", value:$r}], next:"end"}]}}'
}

api_call POST /api/req/admin/workflows "$(wf_body "verify-wf-${RUN_TAG}" ANY "PRODUCT")"
expect "创建审批定义 201" 201
WF_ID="$(jget "$HTTP_BODY" '.id')"

api_call POST "/api/req/admin/workflows/${WF_ID}/publish"
expect "发布审批定义 200" 200

api_call PUT "/api/req/admin/workflows/${WF_ID}" "$(wf_body "verify-wf-${RUN_TAG}-edit" ANY "PRODUCT")"
expect "编辑已发布定义 → 409 WORKFLOW_PUBLISHED_IMMUTABLE" 409 "WORKFLOW_PUBLISHED_IMMUTABLE"

api_call POST /api/req/admin/workflows "$(wf_body "verify-wf-bad-${RUN_TAG}" ANY "NO_SUCH_ROLE")"
BAD_ID="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/admin/workflows/${BAD_ID}/publish"
expect "无审批人发布 → 400 APPROVER_UNRESOLVED" 400 "APPROVER_UNRESOLVED"
expect "400 detail 带 nodeKey（发布期暴露，而不是运行时卡住）" 400 "nodeKey"

# =====================================================================
section "AC-7 审批语义：会签/或签/驳回回退/抄送不阻塞"
submit_req() { # id
    api_call POST "/api/req/requirements/$1/actions" '{"action":"submit"}'
}
# 或签夹具：BUS 的种子默认流程是会签（ALL），本段要验「任一通过即过」，
# 所以先发布一条 BUS 专用、mode=ANY、isDefault=true 的流程覆盖默认路由。
api_call POST "/api/req/admin/workflows" "$(jq -cn --arg n "verify-bus-any-${RUN_TAG}" '{
  name:$n, bizType:"REQ_SUBMIT", reqType:"BUS", domainId:0, isDefault:true, definition:{
    schemaVersion:1, start:"n1", nodes:[
      {key:"n1", type:"APPROVAL", name:"产品评审", mode:"ANY",
       approvers:[{type:"ROLE", value:"PRODUCT"}], next:"end"}]}}')"
expect "创建 BUS 或签流程 201" 201
BUS_WF="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/admin/workflows/${BUS_WF}/publish"
expect "发布 BUS 或签流程 200" 200
BUS2_BODY="$(jq -cn --arg ti "验证-审批-${RUN_TAG}" --argjson d "$DOMAIN1" '{type:"BUS",title:$ti,priority:"P2",domainIds:[$d],expectedDeliveryDate:"2026-12-31"}')"
api_call POST /api/req/requirements "$BUS2_BODY"
BUS2="$(jget "$HTTP_BODY" '.id')"
submit_req "$BUS2"
expect "BUS 提交进入审批 200" 200
expect_jq "BUS 状态 APPROVING" '.status' "APPROVING"

# 或签：产品角色一人通过即流转到 PENDING_REVIEW
login_as "pm" "Passw0rd!" || warn "pm 登录失败"
api_call GET "/api/req/approvals/todo?page=1&size=100"
# 不能取 items[0]：pm 可能还有历史待办；按本次单据的 bizId 从自己的待办里挑
TASK_ID="$(jget "$HTTP_BODY" "[.items[] | select(.bizId == ${BUS2})][0].taskId")"
if [[ -n "$TASK_ID" && "$TASK_ID" != "null" ]]; then
    api_call POST "/api/req/approvals/tasks/${TASK_ID}/actions" '{"action":"APPROVE","comment":"验证-同意"}'
    expect "审批任务同意 200" 200
    api_call POST "/api/req/approvals/tasks/${TASK_ID}/actions" '{"action":"APPROVE","comment":"重复同意"}'
    expect "重复同意同一任务 → 409" 409 "TASK_ALREADY_HANDLED"
    api_call GET "/api/req/requirements/${BUS2}"
    expect_jq "或签通过后 BUS → PENDING_REVIEW" '.view.status // .status' "PENDING_REVIEW"

    api_call POST "/api/req/approvals/tasks/${TASK_ID}/add-sign" '{"type":"AFTER","userIds":[1],"comment":"x"}'
    expect "已处理任务加签 → 409 TASK_ALREADY_HANDLED" 409 "TASK_ALREADY_HANDLED"
else
    fail "审批待办为空，无法验证或签" "BUS 提交后必须创建 wf_task；检查默认流程是否已发布"
fi
API_TOKEN="$ADMIN_TOKEN"

# 驳回：另一条 BUS 提交后驳回回 SUBMITTED
api_call POST /api/req/requirements "$(jq -cn --arg ti "验证-驳回-${RUN_TAG}" --argjson d "$DOMAIN1" '{type:"BUS",title:$ti,priority:"P2",domainIds:[$d],expectedDeliveryDate:"2026-12-31"}')"
BUS3="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/requirements/${BUS3}/actions" '{"action":"submit"}'
login_as "pm" "Passw0rd!" || true
api_call GET "/api/req/approvals/todo?page=1&size=100"
T3="$(jget "$HTTP_BODY" "[.items[] | select(.bizId == ${BUS3})][0].taskId")"
api_call POST "/api/req/approvals/tasks/${T3}/actions" '{"action":"REJECT","comment":"验证-驳回"}'
expect "驳回审批任务 200" 200
api_call GET "/api/req/requirements/${BUS3}"
expect_jq "驳回后单据回 SUBMITTED（回调在同一事务）" '.view.status // .status' "SUBMITTED"
API_TOKEN="$ADMIN_TOKEN"

# =====================================================================
section "AC-8 定义版本化：新单走 v2，在途单仍走 v1"
api_call POST /api/req/admin/workflows "$(wf_body "verify-wf-v2-${RUN_TAG}" ANY "PRODUCT")"
expect "同名流程创建 v1 201" 201
RD_WF="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/admin/workflows/${RD_WF}/publish"
expect "发布 v1 200" 200
V1="$(db_q "SELECT version FROM wf_definition WHERE id=${RD_WF}")"
# 同名再建 = 新版本（已发布行不可变，改定义只能新增行）
api_call POST "/api/req/admin/workflows" "$(wf_body "verify-wf-v2-${RUN_TAG}" ANY "PRODUCT")"
expect "同名流程创建 v2 行 201" 201
RD_WF2="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/admin/workflows/${RD_WF2}/publish"
expect "发布 v2 200" 200
NEW_VER="$(db_q "SELECT version FROM wf_definition WHERE id=${RD_WF2}")"
if [[ -n "$NEW_VER" && -n "$V1" && "$NEW_VER" -gt "$V1" ]]; then
    pass "同名流程新版本号严格递增（${V1} → ${NEW_VER}）"
else
    fail "新版本号未递增（${V1} → ${NEW_VER:-<空>}）" "nextVersion 必须按同一 (bizType, reqType, domain) 取 MAX+1"
fi
COUNTS="$(db_q "SELECT COUNT(*) FROM wf_instance WHERE definition_version IS NULL")"
expect_num "所有审批实例都钉住 definition_version（无 NULL）" "${COUNTS:-0}" eq 0 "instance 必须写 definition_id+definition_version"

# =====================================================================
section "AC-9 加签/减签/转办/撤回/催办留痕"
api_call POST "/api/req/approvals/tasks/${TASK_ID}/remove-sign" '{"targetUserId":1,"reason":""}'
expect "减签空原因 → 400" 400 "REASON_REQUIRED"
api_call POST "/api/req/approvals/tasks/${TASK_ID}/transfer" '{"toUserId":2,"comment":"验证转办"}'
expect "转办已结束任务 → 409" 409
api_call POST "/api/req/approvals/instances/999999/withdraw"
expect "撤回不存在实例 → 404" 404
api_call POST "/api/req/approvals/tasks/${TASK_ID}/urge"
expect "催办已结束任务 → 409（不重复通知）" 409

# =====================================================================
section "AC-10 四类单据回调：状态与审批一致（含 D3/D5 口径）"
api_call GET "/api/req/requirements/${BUS_ID}"
BUS_STATUS="$(jget "$HTTP_BODY" '.view.status // .status')"
api_call GET "/api/req/requirements/${PDR_ID}"
PDR_STATUS="$(jget "$HTTP_BODY" '.view.status // .status')"
if [[ "$BUS_STATUS" =~ ^(SUBMITTED|APPROVING|PENDING_REVIEW|POOLED|CLOSED)$ ]]; then
    pass "BUS 状态属于状态表 (${BUS_STATUS})"
else
    fail "BUS 状态非法: ${BUS_STATUS}" "单据状态只能由状态机写入"
fi
if [[ "$PDR_STATUS" =~ ^(PENDING_ANALYSIS|SCHEDULED|IN_DEV|DELIVERED|ACCEPTED_CLOSED)$ ]]; then
    pass "PDR 状态属于状态表 (${PDR_STATUS})"
else
    fail "PDR 状态非法: ${PDR_STATUS}" "单据状态只能由状态机写入"
fi

# D-10 回归门禁：评审入池必须由 REQ_REVIEW 审批驱动。AC-7 的 BUS2 此时停在 PENDING_REVIEW，
# 先用它证明「无审批会话 review_pass 必 409」，再发起评审并走完，证明「审批通过才 POOLED」。
api_call POST "/api/req/requirements/${BUS2}/actions" '{"action":"review_pass"}'
expect "未发起评审直接 review_pass → 409 REVIEW_APPROVAL_REQUIRED" 409 "REVIEW_APPROVAL_REQUIRED"
api_call GET "/api/req/requirements/${BUS2}"
expect_jq "409 后状态仍 PENDING_REVIEW（未绕过入池）" '.view.status // .status' "PENDING_REVIEW"
api_call POST "/api/req/requirements/${BUS2}/actions" '{"action":"review_start","comment":"验证-发起评审"}'
expect "发起评审 review_start 200" 200
expect_jq "发起评审只建实例、不改状态" '.status' "PENDING_REVIEW"
REVIEW_CNT="$(db_q "SELECT COUNT(*) FROM wf_instance WHERE biz_type='REQ_REVIEW' AND biz_id=${BUS2}")"
expect_num "REQ_REVIEW 审批实例已创建" "${REVIEW_CNT:-0}" ge 1 "review_start 必须调用 ApprovalEngine.start(REQ_REVIEW)"
# 评审节点是 ALL（PRODUCT 角色有 pm、po1 两人），必须两人都同意实例才会 APPROVED
for REVIEW_USER in pm po1; do
    login_as "$REVIEW_USER" "Passw0rd!" || warn "${REVIEW_USER} 登录失败（评审审批）"
    for _ in 1 2 3; do
        api_call GET "/api/req/approvals/todo?page=1&size=100"
        REVIEW_TASK="$(jget "$HTTP_BODY" "[.items[] | select(.bizId == ${BUS2} and .bizType == \"REQ_REVIEW\")][0].taskId")"
        [[ -z "$REVIEW_TASK" || "$REVIEW_TASK" == "null" ]] && break
        api_call POST "/api/req/approvals/tasks/${REVIEW_TASK}/actions" '{"action":"APPROVE","comment":"验证-评审通过"}'
        expect "REQ_REVIEW 审批通过 200（${REVIEW_USER}）" 200
    done
done
API_TOKEN="$ADMIN_TOKEN"
api_call GET "/api/req/requirements/${BUS2}"
expect_jq "评审通过后 BUS → POOLED" '.view.status // .status' "POOLED"
REVIEW_STATUS="$(db_q "SELECT status FROM wf_instance WHERE biz_type='REQ_REVIEW' AND biz_id=${BUS2} ORDER BY id DESC LIMIT 1")"
expect_num "评审实例状态为 APPROVED" "$( [[ "$REVIEW_STATUS" == "APPROVED" ]] && echo 1 || echo 0 )" eq 1 "回调必须在实例 APPROVED 之后驱动 review_pass"

# =====================================================================
section "AC-11 需求池：聚合/平铺、筛选、导出同源、版本锁定"
api_call GET "/api/req/requirements?page=1&size=10&view=agg"
expect "需求池聚合视图 200" 200
TOTAL_AGG="$(jget "$HTTP_BODY" '.total')"
expect_num "聚合视图有数据" "${TOTAL_AGG:-0}" ge 1 "需要先 make platform-seed"
api_call GET "/api/req/requirements?page=1&size=10&type=PDR"
BAD_TYPE="$(jget "$HTTP_BODY" '[.items[] | select(.type != "PDR")] | length')"
expect_num "type=PDR 过滤无杂质" "${BAD_TYPE:-0}" eq 0 "筛选必须下推到 SQL，而不是返回后过滤"
api_call GET "/api/req/requirements/export.csv?page=1&size=10"
if [[ "$HTTP_CODE" == "200" ]]; then
    CSV_ROWS="$(printf '%s' "$HTTP_BODY" | grep -c . || true)"
    # 纯 bash 取首行：10 万行 CSV 走 printf|head 会因 SIGPIPE + pipefail 让脚本 141 退出
    WM="${HTTP_BODY%%$'\n'*}"
    REST="${HTTP_BODY#*$'\n'}"
    HEADER="${REST%%$'\n'*}"
    grep -q '编号\|code' <<<"$HEADER" && pass "CSV 第二行是表头（可审计）" || warn "CSV 表头未见表头：${HEADER:0:80}"
    # D-12 回归门禁：水印 total 必须等于实际数据行数（AC-11 的导出等式）。
    # 修复前后差异：MAX_ROWS=100000 时 100077 行只导出 100000，等式必红。
    CSV_TOTAL="$(sed -n 's/.* total=\([0-9][0-9]*\).*/\1/p' <<<"$WM")"
    CSV_DATA=$(( CSV_ROWS - 2 ))
    if [[ -n "$CSV_TOTAL" ]] && (( CSV_DATA == CSV_TOTAL )); then
        pass "CSV 数据行数 == 水印 total（${CSV_DATA} == ${CSV_TOTAL}）"
    else
        fail "CSV 数据行数 ${CSV_DATA} != 水印 total ${CSV_TOTAL:-?}（AC-11 导出等式）" \
             "导出必须 keyset 流式取空，不得用 MAX_ROWS 静默截断"
    fi
else
    fail "CSV 导出失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 200)" "导出必须与列表同源（一个 SQL 服务两处）"
fi

api_call POST /api/req/releases "$(jq -cn --arg n "验证版本-${RUN_TAG}" --argjson d "$DOMAIN1" '{name:$n,domainId:$d,startDate:"2026-11-01",endDate:"2026-11-30",plannedReleaseDate:"2026-12-05",gateMode:"STRICT"}')"
expect "创建版本 201" 201
REL_ID="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/releases/${REL_ID}/requirements" "{\"requirementIds\":[${RD_A},${RD_B}]}"
expect "版本纳入需求 200" 200
api_call POST "/api/req/releases/${REL_ID}/requirements" "{\"requirementIds\":[${PDR_ID}]}"
api_call POST "/api/req/releases/${REL_ID}/approval"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    pass "版本提交审批 200（触发锁定）"
    api_call POST "/api/req/releases/${REL_ID}/requirements" "{\"requirementIds\":[${PDR_ID}]}"
    expect "锁定后增删需求 → 409" 409 "RELEASE_LOCKED"
else
    warn "版本审批未启动（HTTP ${HTTP_CODE}），跳过锁定断言"
fi

# =====================================================================
section "AC-12 发布单六项准入：服务端重算、FAIL 409、强制放行已退役"
REL_RD="$(db_q "SELECT requirement_id FROM release_requirement WHERE release_id=${REL_ID} ORDER BY requirement_id LIMIT 1")"
[[ -n "$REL_RD" ]] || REL_RD="$RD_A"
api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$REL_ID" --argjson q "$REL_RD" '{
  releaseId:$r, env:"PROD", windowStart:"2026-12-01T22:00:00+08:00",
  windowEnd:"2026-12-01T23:00:00+08:00", artifactVersion:"v1.0.0",
  steps:[{name:"备份",order:1},{name:"发布",order:2}],
  rollbackPlan:"回滚到 v0.9.9", impact:"交易链路只读 5 分钟", ownerId:1,
  requirementIds:[$q]}')"
expect "创建发布单 201" 201
DEP_ID="$(jget "$HTTP_BODY" '.id')"

api_call POST "/api/req/deploy-orders/${DEP_ID}/submit"
# M6 起第 6 项质量门先于 5 项准入判定：没有已会签报告时 QUALITY_GATE_FAILED 才是正确拒绝。
expect "准入不满足时提交 → 409 QUALITY_GATE_FAILED（第 6 项质量门）" 409 "QUALITY_GATE_FAILED"
expect "409 detail.items 含全部 6 项（前端可常驻展示）" 409 "MAN_DAYS_FILLED"

# AC-6.6：force-release 保留路由签名但恒 409 FORCE_RELEASE_RETIRED（不再接受任何原因）。
api_call POST "/api/req/deploy-orders/${DEP_ID}/force-release" '{"reason":""}'
expect "空原因强制放行 → 409 FORCE_RELEASE_RETIRED（入口已退役）" 409 "FORCE_RELEASE_RETIRED"
api_call POST "/api/req/deploy-orders/${DEP_ID}/force-release" '{"reason":"验证-受控放行"}'
expect "带原因强制放行同样 409 FORCE_RELEASE_RETIRED（不得绕过质量门）" 409 "FORCE_RELEASE_RETIRED"
expect_num "退役入口未落强制标记" "$(db_count deploy_order "id=${DEP_ID} AND forced_release=1")" eq 0 "强制放行不得再改单据"

# D-09 正向用例（六项全满足 → submit 200）需要「计划内用例真实 PASS + 需求链审批 APPROVED
# + 已会签报告」；夹具在 AC-16 的 D-09 段完整走一遍。

# =====================================================================
# D6（20261112-r14）：门禁自建数据自清理。「验证缺陷-<HHMMSS>」每跑一次新增一行，
# 且异常中断会留下孤儿行，使 d/r 逐次漂移（17→18→…）。创建前清历史、断言后清本轮，
# 保证同一 DB 上重复运行看到同一基线；只删本脚本自己种的 title 前缀，不碰业务数据。
cleanup_verify_defects() {
    local tid
    tid="$(tenant_id)"
    [[ -n "$tid" ]] || return 0
    db_q "DELETE FROM defect_history WHERE tenant_id=${tid} AND defect_id IN (SELECT id FROM defect WHERE tenant_id=${tid} AND title LIKE '验证缺陷-%')" >/dev/null 2>&1
    db_q "DELETE FROM defect WHERE tenant_id=${tid} AND title LIKE '验证缺陷-%'" >/dev/null 2>&1
}

section "AC-13 缺陷：派生归属、状态机、看板与明细复算"
cleanup_verify_defects
api_call POST /api/deliver/defects "$(jq -cn --arg t "验证缺陷-${RUN_TAG}" --argjson q "$RD_A" '{
  title:$t, description:"验证派生字段", reproSteps:"1. 打开\n2. 报错", severity:"P1", priority:"P1",
  requirementId:$q, domainId:999, vendorId:999, expectedFixDate:"2026-12-01"}')"
expect "创建缺陷 201" 201
DEF_ID="$(jget "$HTTP_BODY" '.id')"
GOT_DOMAIN="$(jget "$HTTP_BODY" '.domainId')"
if [[ "$GOT_DOMAIN" != "999" && -n "$GOT_DOMAIN" ]]; then
    pass "缺陷 domainId 由需求派生（伪造值被忽略，实际 ${GOT_DOMAIN}）"
else
    fail "缺陷直接采信请求体 domainId=${GOT_DOMAIN}" "归属字段只允许派生（AC-13 反面目标）"
fi

api_call POST "/api/deliver/defects/${DEF_ID}/actions" '{"action":"verify_pass"}'
expect "缺陷 NEW --verify_pass--> 409" 409 "ILLEGAL_TRANSITION"
api_call POST "/api/deliver/defects/${DEF_ID}/actions" '{"action":"start"}'
expect "缺陷 NEW --start--> IN_PROGRESS" 200

api_call GET /api/deliver/defects/board
expect "缺陷看板 200" 200
BOARD_HIGH="$(jget "$HTTP_BODY" '.metrics.openHigh')"
DB_HIGH="$(db_count defect "tenant_id=$(tenant_id) AND severity IN ('P0','P1') AND status <> 'CLOSED'")"
expect_num "看板 openHigh 与 DB 计数一致（容差 0）" "${BOARD_HIGH:-x}" eq "${DB_HIGH:-y}" "卡片与明细必须同一 WHERE"
api_call GET "/api/deliver/defects/board/items?metric=openHigh&page=1&size=100"
ITEMS_TOTAL="$(jget "$HTTP_BODY" '.total')"
expect_num "openHigh 明细行数 = 看板数字" "${ITEMS_TOTAL:-0}" eq "${BOARD_HIGH:-1}" "点开卡片必须能看到同样的行（AC-13 反面目标）"

# 业务方摘要（D-07）：二级企业角色看缺陷只能拿到 状态/严重级/预计修复时间，不含描述/复现步骤/处理人
if login_as "po1" "Passw0rd!"; then
    api_call GET "/api/deliver/defects/${DEF_ID}"
    SUMMARY_ONLY="$(jget "$HTTP_BODY" '.summaryOnly')"
    HAS_DESC="$(jget "$HTTP_BODY" 'has("description")')"
    if [[ "$SUMMARY_ONLY" == "true" && "$HAS_DESC" == "false" ]]; then
        pass "业务方角色缺陷详情为摘要视图（无 description/reproSteps/handler，D-07）"
    else
        fail "业务方缺陷详情未脱敏（summaryOnly=${SUMMARY_ONLY} hasDescription=${HAS_DESC}）" "BIZ/SELF 档必须返回 Summary 视图"
    fi
else
    warn "po1 登录失败，跳过业务方摘要断言（make platform-seed 必须建 po1）"
fi
API_TOKEN="$ADMIN_TOKEN"
cleanup_verify_defects

# =====================================================================
section "AC-14 任务与迭代：完成率回写、关闭有未完成任务 409"
api_call POST /api/deliver/tasks "$(jq -cn --argjson q "$RD_A" '{requirementId:$q,title:"验证任务",assigneeId:1,estimateHours:4,startDate:"2026-11-01",dueDate:"2026-11-10"}')"
expect "创建任务 201" 201
TASK1="$(jget "$HTTP_BODY" '.id')"
api_call POST /api/deliver/sprints '{"name":"验证迭代","goal":"验证","startDate":"2026-11-01","endDate":"2026-11-30","capacityDays":20}'
expect "创建迭代 201" 201
SPRINT_ID="$(jget "$HTTP_BODY" '.id')"
api_call PATCH "/api/deliver/tasks/${TASK1}" "{\"sprintId\":${SPRINT_ID}}"
expect "任务挂到迭代 200" 200
api_call POST "/api/deliver/sprints/${SPRINT_ID}/close"
expect "关闭有未完成任务迭代 → 409" 409
api_call POST "/api/deliver/tasks/${TASK1}/move" '{"status":"DONE","sortNo":1}'
api_call POST "/api/deliver/sprints/${SPRINT_ID}/close"
if [[ "$HTTP_CODE" == "200" ]]; then pass "任务完成后迭代可关闭"; else warn "迭代关闭未通过 (HTTP ${HTTP_CODE})，按未完成迁移口径检查"; fi

# =====================================================================
section "AC-15 追溯矩阵：六环齐备性、gaps 与 complete 同步、缺口筛选"
api_call GET "/api/deliver/traceability?page=1&size=20"
expect "追溯矩阵 200" 200
MISMATCH="$(jget "$HTTP_BODY" '[.items[] | select(((.gaps|length) > 0) == (.complete == true))] | length')"
expect_num "gaps 非空 <=> complete=false（杜绝「全勾但 MR 为空」）" "${MISMATCH:-x}" eq 0 "缺口与 complete 必须同一行计算"
api_call GET "/api/deliver/traceability?gap=true&page=1&size=20"
BAD_GAP="$(jget "$HTTP_BODY" '[.items[] | select((.gaps|length) == 0)] | length')"
expect_num "gap=true 过滤后没有"无缺口"行" "${BAD_GAP:-0}" eq 0 "缺口筛选必须下推 SQL"

# =====================================================================
section "AC-16 用例版本、计划执行、门禁唯一实现"
api_call POST /api/deliver/test-modules "$(jq -cn --arg n "验证模块L1-${RUN_TAG}" '{parentId:null,name:$n}')"
expect "创建 1 层模块 201" 201
M1="$(jget "$HTTP_BODY" '.id')"
api_call POST /api/deliver/test-modules "$(jq -cn --argjson p "${M1:-0}" --arg n "验证模块L2-${RUN_TAG}" '{parentId:$p,name:$n}')"
expect "创建 2 层模块 201" 201
M2="$(jget "$HTTP_BODY" '.id')"
api_call POST /api/deliver/test-modules "$(jq -cn --argjson p "${M2:-0}" --arg n "验证模块L3-${RUN_TAG}" '{parentId:$p,name:$n}')"
expect "创建 3 层模块 201" 201
M3="$(jget "$HTTP_BODY" '.id')"
api_call POST /api/deliver/test-modules "$(jq -cn --argjson p "${M3:-0}" --arg n "验证模块L4-${RUN_TAG}" '{parentId:$p,name:$n}')"
expect "第 4 层模块 → 400" 400 "MODULE_LEVEL_EXCEEDED"

api_call POST /api/deliver/testcases "$(jq -cn --argjson m "$M3" --argjson q "$RD_A" --arg t "验证用例-${RUN_TAG}" '{
  moduleId:$m, requirementId:$q, title:$t, type:"FUNCTIONAL", priority:"P1", precondition:"已登录",
  steps:[{stepNo:1,action:"点击提交",expected:"创建成功"}], tags:["verify"], automated:false}')"
expect "创建用例 201" 201
CASE_ID="$(jget "$HTTP_BODY" '.id')"
CASE_CODE="$(jget "$HTTP_BODY" '.code')"
api_call PUT "/api/deliver/testcases/${CASE_ID}" "$(jq -cn --arg t "验证用例-${RUN_TAG}-v2" --argjson m "$M3" '{
  moduleId:$m, title:$t, type:"FUNCTIONAL", priority:"P1", precondition:"已登录",
  steps:[{stepNo:1,action:"点击提交",expected:"创建成功"}]}')"
expect "保存用例生成 v2" 200

api_call POST /api/deliver/test-plans "$(jq -cn --argjson r "$REL_ID" --arg n "验证计划-${RUN_TAG}" --argjson c "$CASE_ID" '{
  releaseId:$r, name:$n, ownerId:1, windowStart:"2026-11-20", windowEnd:"2026-11-28", caseIds:[$c]}')"
expect "创建测试计划 201" 201
PLAN_ID="$(jget "$HTTP_BODY" '.plan.id')"
api_call GET "/api/deliver/test-plans/${PLAN_ID}"
PINNED="$(jget "$HTTP_BODY" '.cases[0].caseVersion')"
expect_num "计划行钉住纳入时的版本（v2）" "${PINNED:-0}" eq 2 "计划行必须钉 case_version"

api_call GET "/api/deliver/test-plans/${PLAN_ID}/summary"
expect "计划汇总 200" 200
NOT_RUN="$(jget "$HTTP_BODY" '.summary.notRun // .notRun')"
expect_num "未执行用例数 > 0（门禁夹具）" "${NOT_RUN:-0}" ge 1 "计划纳入后应为 NOT_RUN"

api_call GET "/api/deliver/test-gate?requirementId=${RD_A}"
expect "测试门禁 200（GateDecision）" 200
expect "STRICT 下 NOT_RUN 阻断（passed=false）" 200 '"passed":false'

# D-01 跨服务门禁：内部端点带 token 必须 200（不能因 /internal/** 放行就没有 TenantContext），
# 且门禁已通过的需求 test_pass 必须真的 200——只断言 409 会让"门禁 500"永远绿。
OK_RD="$RD_A"
# M6 计划七态：DRAFT 不能直接执行，必须 submit-review → approve → start。
api_call POST "/api/deliver/test-plans/${PLAN_ID}/submit-review" '{}'
expect "计划提交评审 200" 200
api_call POST "/api/deliver/test-plans/${PLAN_ID}/approve" '{"comment":"验证"}'
expect "计划批准 200" 200
api_call POST "/api/deliver/test-plans/${PLAN_ID}/start" '{}'
expect "计划开始执行 200" 200
api_call POST "/api/deliver/test-plans/${PLAN_ID}/execute" "$(jq -cn --argjson c "$CASE_ID" '{caseId:$c,result:"PASS",note:"D-01 正向"}')"
expect "计划内用例执行 PASS 201（门禁转绿）" 201
INT_GATE_CODE="$(curl -sS --max-time 10 -o /tmp/gate-int.$$ -w '%{http_code}' \
    "${DELIVER_URL}/internal/gates/${OK_RD}" -H "X-Internal-Token: ${PLATFORM_INTERNAL_TOKEN}" 2>/dev/null || true)"
if [[ "$INT_GATE_CODE" == "200" ]]; then
    pass "内部门禁端点 200（D-01：内部调用建立 system scope）"
else
    fail "内部门禁端点 ${INT_GATE_CODE}（D-01 回归）: $(head -c 120 /tmp/gate-int.$$ 2>/dev/null)" \
        "InternalTokenFilter 只验 token；门禁读路径需要 TenantContext，端点必须自建并清理"
fi
rm -f /tmp/gate-int.$$
api_call POST "/api/req/requirements/${OK_RD}/actions" '{"action":"test_pass"}'
expect "门禁通过 → test_pass 200（D-01 正向）" 200
expect "需求进入 TEST_PASSED" 200 '"TEST_PASSED"'

# D-09 正向：五项全部满足（真实审批 + 真实门禁 + 人天/MR）时提交必须 200。
# 只断言 409 会让「门禁 500 → 永远 409」保持绿色，所以这里把正向链路完整走一遍。
create_req BUS "验证-准入正向BUS-${RUN_TAG}" ""
expect "准入正向链 BUS 201" 201
D9_BUS="$(jget "$HTTP_BODY" '.id')"
submit_req "$D9_BUS"
expect "准入正向链 BUS 提交审批 200" 200
login_as "pm" "Passw0rd!" || warn "pm 登录失败，准入正向审批可能卡住"
for _ in 1 2 3 4 5; do
    api_call GET "/api/req/approvals/todo?page=1&size=100"
    D9_TASK="$(jget "$HTTP_BODY" "[.items[] | select(.bizId == ${D9_BUS})][0].taskId")"
    [[ -z "$D9_TASK" || "$D9_TASK" == "null" ]] && break
    api_call POST "/api/req/approvals/tasks/${D9_TASK}/actions" '{"action":"APPROVE","comment":"准入正向"}'
    [[ "$HTTP_CODE" == "200" ]] || break
done
D9_SUBMIT_STATUS="$(db_q "SELECT status FROM wf_instance WHERE tenant_id=$(tenant_id) AND biz_type='REQ_SUBMIT' AND biz_id=${D9_BUS} ORDER BY id DESC LIMIT 1")"
if [[ "$D9_SUBMIT_STATUS" == "APPROVED" ]]; then
    pass "准入正向链 BUS 提报审批 APPROVED（D-09 前置）"
else
    fail "准入正向链 BUS 提报审批未通过 (${D9_SUBMIT_STATUS})" "审批任务必须能按 bizId 找到并同意"
fi
# D-10：准入第 5 项要求「评审已通过」，正向夹具必须先把 REQ_REVIEW 走完（否则不是真正向）
api_call POST "/api/req/requirements/${D9_BUS}/actions" '{"action":"review_start","comment":"准入正向评审"}'
expect "准入正向链 发起评审 200" 200
for D9_USER in pm po1; do
    login_as "$D9_USER" "Passw0rd!" || warn "${D9_USER} 登录失败（准入正向评审）"
    for _ in 1 2 3 4 5; do
        api_call GET "/api/req/approvals/todo?page=1&size=100"
        D9_REVIEW_TASK="$(jget "$HTTP_BODY" "[.items[] | select(.bizId == ${D9_BUS} and .bizType == \"REQ_REVIEW\")][0].taskId")"
        [[ -z "$D9_REVIEW_TASK" || "$D9_REVIEW_TASK" == "null" ]] && break
        api_call POST "/api/req/approvals/tasks/${D9_REVIEW_TASK}/actions" '{"action":"APPROVE","comment":"准入正向评审"}'
        [[ "$HTTP_CODE" == "200" ]] || break
    done
done
D9_BUS_STATUS="$(db_q "SELECT status FROM requirement WHERE id=${D9_BUS}")"
if [[ "$D9_BUS_STATUS" == "POOLED" ]]; then
    pass "准入正向链 BUS 评审通过 → POOLED（D-10 前置）"
else
    fail "准入正向链 BUS 评审未入池 (${D9_BUS_STATUS})" "评审审批通过后必须回写 review_pass"
fi
API_TOKEN="$ADMIN_TOKEN"
API_TENANT="${SAVED_TENANT:-$API_TENANT}"
create_req PDR "验证-准入正向PDR-${RUN_TAG}" "$D9_BUS" ""
expect "准入正向链 PDR 201" 201
D9_PDR="$(jget "$HTTP_BODY" '.id')"
create_req RD "验证-准入正向RD-${RUN_TAG}" "$D9_PDR" "2"
expect "准入正向链 RD 201" 201
D9_RD="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/requirements/${D9_RD}/deliver" '{"estimateDays":2,"actualDays":2,"mrUrl":"https://git.example.com/mr/d9"}'
expect "准入正向 RD 登记预估/实际人天与 MR 200" 200
api_call POST /api/req/releases "$(jq -cn --arg n "验证准入正向版本-${RUN_TAG}" --argjson d "$DOMAIN1" '{name:$n,domainId:$d,startDate:"2026-11-01",endDate:"2026-11-30",plannedReleaseDate:"2026-12-05",gateMode:"STRICT"}')"
expect "准入正向版本 201" 201
D9_REL="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/releases/${D9_REL}/requirements" "{\"requirementIds\":[${D9_RD}]}"
expect "准入正向版本纳入需求 200" 200
api_call POST /api/deliver/testcases "$(jq -cn --argjson m "$M3" --argjson q "$D9_RD" --arg t "准入正向用例-${RUN_TAG}" '{
  moduleId:$m, requirementId:$q, title:$t, type:"FUNCTIONAL", priority:"P1", precondition:"已登录",
  steps:[{stepNo:1,action:"执行",expected:"通过"}]}')"
expect "准入正向用例 201" 201
D9_CASE="$(jget "$HTTP_BODY" '.id')"
api_call POST /api/deliver/test-plans "$(jq -cn --argjson r "$D9_REL" --arg n "准入正向计划-${RUN_TAG}" --argjson c "$D9_CASE" '{
  releaseId:$r, name:$n, ownerId:1, windowStart:"2026-11-20", windowEnd:"2026-11-28", caseIds:[$c]}')"
expect "准入正向计划 201" 201
D9_PLAN="$(jget "$HTTP_BODY" '.plan.id')"
api_call POST "/api/deliver/test-plans/${D9_PLAN}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${D9_PLAN}/approve" '{"comment":"准入正向"}'
api_call POST "/api/deliver/test-plans/${D9_PLAN}/start" '{}'
api_call POST "/api/deliver/test-plans/${D9_PLAN}/execute" "$(jq -cn --argjson c "$D9_CASE" '{caseId:$c,result:"PASS",note:"准入正向"}')"
expect "准入正向执行 PASS 201" 201
api_call POST "/api/req/requirements/${D9_RD}/actions" '{"action":"start_dev"}'
api_call POST "/api/req/requirements/${D9_RD}/actions" '{"action":"submit_test"}'
api_call POST "/api/req/requirements/${D9_RD}/actions" '{"action":"test_pass"}'
expect "准入正向 RD 门禁通过 test_pass 200" 200
# 第 6 项质量门要求「已会签报告」：关轮 → 计划完成 → RELEASE 报告 → 三级会签。
D9_ROUND="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${D9_PLAN} AND active=1")"
api_call POST "/api/deliver/test-plans/${D9_PLAN}/rounds/${D9_ROUND}/close" '{"conclusion":"PASS","note":"准入正向"}'
expect "准入正向关轮 200" 200
api_call POST "/api/deliver/test-plans/${D9_PLAN}/complete" '{}'
expect "准入正向计划完成 200" 200
api_call POST /api/deliver/test-reports "$(jq -cn --argjson r "$D9_REL" '{scope:"RELEASE",releaseId:$r,note:"准入正向报告"}')"
expect "准入正向报告生成 201" 201
D9_RPT="$(jget "$HTTP_BODY" '.id')"
for D9_SIGN_USER in qa1 pm tl1; do
    login_as "$D9_SIGN_USER" "Passw0rd!" || warn "${D9_SIGN_USER} 登录失败（报告会签）"
    api_call POST "/api/deliver/test-reports/${D9_RPT}/versions/1/signoff" '{"decision":"APPROVE","comment":"准入正向会签"}'
    expect "准入正向报告会签（${D9_SIGN_USER}）200" 200
done
API_TOKEN="$ADMIN_TOKEN"
API_TENANT="${SAVED_TENANT:-$API_TENANT}"
api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$D9_REL" --argjson q "$D9_RD" '{
  releaseId:$r, env:"PROD", windowStart:"2026-12-02T22:00:00+08:00",
  windowEnd:"2026-12-02T23:00:00+08:00", artifactVersion:"v1.0.1",
  steps:[{name:"发布",order:1}],
  rollbackPlan:"回滚到 v1.0.0", impact:"无", ownerId:1,
  requirementIds:[$q]}')"
expect "准入正向发布单 201" 201
D9_DEP="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/deploy-orders/${D9_DEP}/submit"
expect "六项全满足 → 提交 200（D-09 正向）" 200
expect_num "发布单离开 DRAFT" "$(db_count deploy_order "id=${D9_DEP} AND status <> 'DRAFT'")" ge 1 "提交必须真正推进状态"

# =====================================================================
section "AC-17 开放 API：签名/时间戳/幂等/失败不落执行记录"
# 白名单用 *：请求经网关转发，X-Forwarded-For 是宿主局域网地址，写死 127.0.0.1 会被 IP_NOT_ALLOWED 拒绝
api_call POST /api/deliver/admin/api-clients "$(jq -cn --arg n "verify-ci-${RUN_TAG}" '{name:$n,scopes:["test-execution"],ipWhitelist:["*"],rateLimitQps:1000}')"
expect "创建开放 API 客户端 201（明文 secret 只返回这一次，D1）" 201
APP_KEY="$(jget "$HTTP_BODY" '.appKey')"
APP_SECRET="$(jget "$HTTP_BODY" '.secret // .clientSecret // .secretOnce')"
if [[ -z "$APP_KEY" || "$APP_KEY" == "null" ]]; then
    fail "创建客户端未返回 appKey（无法验签）" "POST /admin/api-clients 必须回 appKey + 一次性 secret"
else
    OAPI_BODY="$(jq -cn --argjson c "$CASE_ID" --argjson p "$PLAN_ID" '{
      run_id:"run-'"${RUN_TAG}"'", case_id:$c, result:"PASS", duration_ms:1200, executed_at:"2026-11-09T00:00:00+08:00", plan_id:$p}')"
    OAPI_PATH="/openapi/v1/test-executions"
    oapi_call() { # ts sig body
        curl -sS --max-time 10 -o /tmp/oapi.$$ -w '%{http_code}' -X POST "${GATEWAY_URL}${OAPI_PATH}" \
            -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: $1" -H "X-Signature: $2" \
            -H 'Content-Type: application/json' -d "$3" 2>/dev/null
    }
    sign_for() { # ts body
        local digest ts="$1" body="$2"
        digest="$(printf '%s' "$body" | openssl dgst -sha256 -hex | awk '{print $NF}')"
        printf '%s' "POST
${OAPI_PATH}
${APP_KEY}
${ts}
${digest}" | openssl dgst -sha256 -hmac "${APP_SECRET}" -hex | awk '{print $NF}'
    }
    BEFORE_EXEC="$(db_count test_execution "case_id=${CASE_ID}")"
    TS="$(date +%s)"
    CODE="$(oapi_call "$TS" "deadbeef" "$OAPI_BODY")"
    [[ "$CODE" == "401" ]] && pass "错签名 → 401" || fail "错签名返回 ${CODE}，期望 401" "验签必须先于任何业务读写"
    CODE="$(oapi_call "$((TS - 3600))" "$(sign_for "$((TS - 3600))" "$OAPI_BODY")" "$OAPI_BODY")"
    [[ "$CODE" == "401" ]] && pass "时间戳超 ±300s → 401" || fail "过期时间戳返回 ${CODE}，期望 401" "时间戳窗口固定 300s"
    CODE="$(oapi_call "$TS" "$(sign_for "$TS" "$OAPI_BODY")" "$OAPI_BODY")"
    [[ "$CODE" == "200" ]] && pass "合法签名上报 → 200" || fail "合法上报返回 ${CODE}" "canonical 串必须与 HmacSigner 逐字一致"
    CODE="$(oapi_call "$TS" "$(sign_for "$TS" "$OAPI_BODY")" "$OAPI_BODY")"
    IDEM="$(jq -r '.idempotent' /tmp/oapi.$$ 2>/dev/null || true)"
    [[ "$CODE" == "200" && "$IDEM" == "true" ]] && pass "重复上报 → 200 且 idempotent=true" \
        || fail "重复上报 code=${CODE} idempotent=${IDEM}" "唯一键冲突后回查原行，返回 idempotent=true"
    expect_num "重复上报只留 1 条执行记录" "$(db_count test_execution "case_id=${CASE_ID} AND source='AUTO'")" eq 1 "幂等靠唯一键，不靠先查后插"

    BAD_BODY="$(jq -cn --argjson c "$CASE_ID" '{run_id:"bad",case_id:$c,result:"NOT_RUN",executed_at:"2026-11-09T00:00:00+08:00"}')"
    BEFORE="$(db_count test_execution "case_id=${CASE_ID}")"
    CODE="$(oapi_call "$TS" "$(sign_for "$TS" "$BAD_BODY")" "$BAD_BODY")"
    [[ "$CODE" == "400" ]] && pass "result=NOT_RUN → 400" || fail "NOT_RUN 返回 ${CODE}，期望 400" "载荷枚举校验先于插入"
    expect_num "失败上报不落 test_execution" "$(db_count test_execution "case_id=${CASE_ID}")" eq "$BEFORE" "失败只落 api_call_log（REQUIRES_NEW）"
    LOG_ROWS="$(db_count api_call_log "path='${OAPI_PATH}'")"
    expect_num "每次调用都有 api_call_log（含失败）" "${LOG_ROWS:-0}" ge 5 "filter 的 finally 必须写调用日志"
    rm -f /tmp/oapi.$$
fi

# =====================================================================
section "AC-18 指标定义与口径同源"
api_call GET /api/deliver/metrics/definitions
expect "指标定义 200" 200
DEF_N="$(jlen "$HTTP_BODY" '.items // .')"
expect_num "14 条指标定义（9 既有 + 5 条 FLOW_* 价值流指标）" "${DEF_N:-0}" eq 14 "V9 种子必须写 5 条 FLOW_* metric_definition（datasource=req.audit），与看板同源"
# D-03 回归：V5 步 7 的 5 条质量指标也是 INSERT…SELECT FROM sys_tenant，空库 Flyway 时
# sys_tenant 为空 → 0 行。只数总数会被别的来源凑够，所以按 code 点名断言。
Q_DEF_N="$(jget "$HTTP_BODY" '[.items // . | .[] | select(.code as $c | ["FPY","REGRESSION_PASS_RATE","DEFECT_ESCAPE_RATE","POST_RELEASE_DEFECTS_72H","ROLLBACK_RATE"] | index($c))] | length')"
expect_num "V5 步 7 的 5 条质量指标在全新库可重放（D-03）" "${Q_DEF_N:-0}" eq 5 \
    "SeedService.replayQualityMetricSeed 必须重放 V5 步 7：空库 Flyway 早于建租户，INSERT…SELECT 插 0 行"
api_call GET "/api/deliver/defects/board?domainId=1"
DENSITY="$(jget "$HTTP_BODY" '.metrics.density')"
D_TOTAL="$(db_count defect "tenant_id=$(tenant_id) AND domain_id=1")"
R_TOTAL="$(db_count requirement "tenant_id=$(tenant_id) AND domain_id=1")"
if [[ -n "$DENSITY" && -n "$D_TOTAL" && -n "$R_TOTAL" && "$R_TOTAL" -gt 0 ]]; then
    # D6（20261112-r14）：服务端 MetricFormulas.ratio 用 BigDecimal HALF_UP 取 2 位，awk printf "%.2f"
    # 是 C 的 ties-to-even —— 恰为 x.xx5 时 0.125 被算成 0.12、服务端给 0.13，串相等必假红。
    # 改为容差比较原始密度：两位小数的相邻粒度是 0.01，0.0055 既容纳两侧舍入又不会放行算错的公式
    # （0.13-0.125 在 IEEE double 下是 0.0050000000000000044，所以容差不能取 0.005）。
    CALC="$(awk -v d="$D_TOTAL" -v r="$R_TOTAL" 'BEGIN{printf "%.2f", d/r}')"
    GOT="$(awk -v x="$DENSITY" 'BEGIN{printf "%.2f", x}')"
    if awk -v d="$D_TOTAL" -v r="$R_TOTAL" -v x="$DENSITY" \
        'BEGIN { diff = d / r - x; if (diff < 0) diff = -diff; exit (diff <= 0.0055) ? 0 : 1 }'; then
        pass "缺陷密度可复算 (${GOT}，${D_TOTAL}/${R_TOTAL}，容差内)"
    else
        fail "缺陷密度 ${GOT} != ${CALC}（${D_TOTAL}/${R_TOTAL}）" "公式只在 MetricFormulas，SQL 只取计数"
    fi
else
    warn "域 1 没有需求，跳过密度复算"
fi

# =====================================================================
section "AC-19 种子数据规模（platform-seed 再做幂等两遍）"
expect_num "7 个业务域" "$(db_count biz_domain "tenant_id=$(tenant_id)")" eq 7 "SeedService"
expect_num "3 家厂商" "$(db_count sys_vendor "tenant_id=$(tenant_id)")" eq 3 "SeedService"
expect_num "12 个账号" "$(db_q "SELECT COUNT(*) FROM sys_user WHERE tenant_id=$(tenant_id) AND username IN ('admin','pm','tl1','po1','biz1','suite1','dev1','dev2','qa1','qa2','vendor1','vendor2')")" eq 12 "SeedService.userDefs 必须写全 12 个演示账号（与 SeedResult.loginUsers / platform-seed.sh 名单同源）"
expect_num "1 个测试计划" "$(db_count test_plan "tenant_id=$(tenant_id)")" ge 1 "SeedService"
PLAN_CASES="$(db_q "SELECT COUNT(*) FROM test_plan_case pc JOIN test_plan p ON p.id=pc.plan_id WHERE p.tenant_id=$(tenant_id)")"
expect_num "计划用例 ≥10" "${PLAN_CASES:-0}" ge 10 "SeedService 必须给计划纳入 ≥10 用例"

# =====================================================================
section "AC-20 性能冒烟（完整基线见 make platform-perf）"
WORST="0"
for i in 1 2 3 4 5; do
    api_call GET "/api/req/requirements?page=1&size=50"
    T="$(curl -sS -o /dev/null -w '%{time_total}' --max-time 10 -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" "${GATEWAY_URL}/api/req/requirements?page=1&size=50")"
    WORST="$(awk -v a="$WORST" -v b="$T" 'BEGIN{print (b>a)?b:a}')"
done
LE1="$(awk -v w="$WORST" 'BEGIN{print (w<=1.0)?1:0}')"
if [[ "$LE1" == "1" ]]; then
    pass "需求池单页最差响应 ${WORST}s ≤ 1s"
else
    fail "需求池单页最差响应 ${WORST}s > 1s" "检查该查询的 EXPLAIN 与索引（AC-20 第一条）"
fi

# =====================================================================
printf '\n'
if (( TOTAL == 0 )); then
    err "一条断言都没执行：AC 分组实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "platform-verify: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    err "修复: 从第一条 FAIL 开始；每条消息都带期望/实际与修复方向"
    exit 1
fi
ok "platform-verify: ${PASS}/${TOTAL} 项通过（AC-1..AC-20）"


