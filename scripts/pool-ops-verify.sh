#!/usr/bin/env bash
# pool-ops-verify.sh — r47 需求池运营（相似需求 / 我也需要 / 合并 / 回归）门禁。
#
# 契约：docs/teamflow/DESIGN-POOL-OPS.md §3（接口）、§6（门禁口径）、§7（演示数据）。
# 33 条断言，四段（编号前缀 = 验收段，执行顺序 = 打印顺序）：
#   P1 相似（7）  自身排除、同名命中 ≥3 且分数降序、同域 > 异域、limit 生效、
#                 终态/已合并不出现、厂商只看本厂商、200 行样本 p95 ≤ 200ms；
#   P2 跟单（9）  201 + followCount、重复 200 且仍 1 行、第二人 +1、
#                 followers 分页/orgs/mine、followedByMe 本人/他人、
#                 终态 409、DELETE 204、通知同日幂等、不可见账号不落行；
#   P3 合并（9）  正常合并状态三段、follow 迁移去重、子需求 reparent、审计 1 条、
#                 重复 409 ALREADY_MERGED、跨类型 400、成环 400 MERGE_CYCLE、
#                 target 已合并 400 TARGET_MERGED、合并通知提报人；
#   P4 回归（8）  列表含 followCount、详情含 followedByMe、无权限 403、
#                 自清理残留 0、platform-demo-reset 80/80、
#                 演示示例跟单存在、演示 followCount 与库一致、§7 演示数量 6/2。
#
# 用法：
#   bash scripts/pool-ops-verify.sh                 # 全量（需 make platform-up && make platform-seed）
#   bash scripts/pool-ops-verify.sh --dry-run       # 不联网，只打印 33 条断言标题（清单自检）
#   bash scripts/pool-ops-verify.sh --static-only   # 只跑不需要后端的静态自检（语法/清单/分段/约束）
#   NO_NET=1 bash scripts/pool-ops-verify.sh        # 同 --dry-run
#
# 约定（与 platform-verify / change-verify / pdr-flow-verify 一致）：
#   - 脚本自声明清单 POOL_MANIFEST；每条断言必须登记且只执行一次，结尾打印
#     `POOL_OPS_TOTAL=<清单条数> PASS=<n> FAIL=<n>`，FAIL>0 或清单有漏执行 → 退出码 1；
#   - 登录只用 scripts/lib/gate-fixture.sh 的 gate_login_as（token/tenant 头由 lib.sh 维护）；
#   - 每轮自造夹具（标题带 GATE_TAG）：3 条同标题 ORD + 1 条同标题 INV（域加分对照）
#     + 1 条 CLOSED + 1 条已合并 + 200 行性能样本（直接 INSERT，纯为 LIKE 预筛样本量）；
#     结尾 teardown 只按本轮 id / title LIKE GATE_TAG 精确删，并断言残留 0；
#   - 不用 sleep 掩盖未就绪：pool_wait_ready 每秒探测、最多 30s，超时打印步骤名与最后一次 HTTP；
#   - 后端可能尚未落地（feat/pool-ops 并行开发）：每条断言先看 HTTP 码，404/000 记 FAIL
#     并给出「接口未就绪」提示，绝不 unbound variable / jq 崩掉。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
# shellcheck source=lib/gate-fixture.sh
source "${ROOT}/scripts/lib/gate-fixture.sh"
# shellcheck source=tests/pool/_lib.sh
source "${ROOT}/scripts/tests/pool/_lib.sh"
set +e

MODE="full"
case "${1:-}" in
    --dry-run | --offline) MODE="dry" ;;
    --static-only) MODE="static" ;;
    -h | --help)
        sed -n '2,/^set -euo pipefail$/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    "") ;;
    *)
        printf '未知参数: %s（可用：--dry-run / --static-only / --help）\n' "$1" >&2
        exit 64
        ;;
esac
if [[ "${NO_NET:-0}" == "1" ]]; then MODE="dry"; fi

# --------------------------------------------------------------------------- 断言清单
# 格式：编号|标题。编号唯一；执行顺序与清单顺序一致；少跑/多跑/重复都会在结尾被清点。
POOL_MANIFEST=(
    "P1-1|相似(id)：self 不在结果且同标题候选 ≥2"
    "P1-2|相似(title)：同名命中 ≥3 条且 score 降序"
    "P1-3|相似：同域(ORD)分数 > 异域(INV)分数"
    "P1-4|相似 limit 生效：limit=1 只回 1 条且 total 仍 ≥3"
    "P1-5|相似：CLOSED 与已合并(merged_into_id)不出现"
    "P1-6|相似：厂商账号只看本厂商（vpm1 结果 ⊆ 可见集，vendor2 看不到 VEN-A）"
    "P1-7|相似性能：200 行 LIKE 预筛样本 p95 ≤ 200ms"
    "P2-1|跟单：首次 → 201 且 followCount=1、库 1 行"
    "P2-2|跟单幂等：重复 → 200 且仍 1 行、followCount 不增"
    "P2-3|跟单：第二人 → 201 且 followCount=2"
    "P2-4|followers：分页(size=1) + orgs 聚合 + mine=true"
    "P2-5|详情 followedByMe：跟单人 true，未跟单人 false"
    "P2-6|终态需求跟单 → 409 REQUIREMENT_CLOSED"
    "P2-7|DELETE follow → 204、行删除、followCount-1"
    "P2-8|通知幂等：同日多次跟单每位接收人只 1 条"
    "P2-9|不可见账号跟单 → 403/404 且不落行"
    "P3-1|合并：200 closed=true；source CLOSED/MERGED/merged_into_id=target"
    "P3-2|合并：follow 迁移去重（source 0 行 / target 2 行 / movedFollows=1）"
    "P3-3|合并：子需求 reparent 到 target 且状态不变"
    "P3-4|合并：审计 REQUIREMENT_MERGED 恰 1 条且 detail 带 targetId/迁移数"
    "P3-5|重复合并 → 409 ALREADY_MERGED"
    "P3-6|跨类型合并 → 400"
    "P3-7|成环（target 是 source 后代）→ 400 MERGE_CYCLE"
    "P3-8|target 已合并 → 400 TARGET_MERGED"
    "P3-9|合并通知 source 提报人（REQUIREMENT_MERGED）"
    "P4-1|回归：GET /requirements 列表 item 含 followCount（数值）"
    "P4-2|回归：详情 view 含 followedByMe（布尔且与跟单一致）"
    "P4-3|回归：无合并权限账号（vpm1）合并 → 403"
    "P4-4|自清理：本轮需求/跟单/审计/通知残留 0 条"
    "P4-5|回归：platform-demo-reset 仍 80/80"
    "P4-6|演示数据含示例跟单（DEMO-R-02 ≥1 条且有 note）"
    "P4-7|演示 followCount 与库一致（DEMO-R-02 API = 行数）"
    "P4-8|演示夹具按 §7 落地（DEMO-R-02 = 6 条，其中 2 条 note）"
)
pool_manifest_parse

# --------------------------------------------------------------------------- 离线 / 静态自检

if [[ "${MODE}" == "dry" ]]; then
    pool_dry_run
    exit 0
fi

# --static-only：不连栈，只验证脚本自身的结构约束（语法/清单/分段/唯一/无 python/原语齐备）。
if [[ "${MODE}" == "static" ]]; then
    ST_N=0
    ST_PASS=0
    ST_FAIL=0
    st_ck() { # <编号> <1|0> <标题> [实际] [修复]
        ST_N=$((ST_N + 1))
        if [[ "$2" == "1" ]]; then
            ST_PASS=$((ST_PASS + 1))
            printf 'PASS [%02d] %-4s %s\n' "${ST_N}" "$1" "$3"
        else
            ST_FAIL=$((ST_FAIL + 1))
            printf 'FAIL [%02d] %-4s %s\n' "${ST_N}" "$1" "$3"
            printf '     实际: %s\n' "${4:-<空>}"
            printf '     修复: %s\n' "${5:-见 docs/teamflow/20261125-r47-pool-ops/QA-PLAN.md}"
        fi
    }
    printf 'POOL_OPS_MODE=static\n'

    if bash -n "${BASH_SOURCE[0]}" >/dev/null 2>&1 && bash -n "${ROOT}/scripts/tests/pool/_lib.sh" >/dev/null 2>&1; then
        st_ck S1 1 "脚本语法：bash -n pool-ops-verify.sh 与 tests/pool/_lib.sh 通过"
    else
        st_ck S1 0 "脚本语法：bash -n 失败" "$(bash -n "${BASH_SOURCE[0]}" 2>&1 | head -n3)" \
            "先修语法错误；bash -n scripts/pool-ops-verify.sh"
    fi

    if (( ${#POOL_M_IDS[@]} >= 24 )); then
        st_ck S2 1 "清单：断言 ≥24 条（实际 ${#POOL_M_IDS[@]} 条）"
    else
        st_ck S2 0 "清单：断言不足 24 条" "实际 ${#POOL_M_IDS[@]} 条" "POOL_MANIFEST 至少登记 24 条"
    fi

    SEG_MISS=""
    for seg in P1 P2 P3 P4; do
        seg_n=0
        for m_id in "${POOL_M_IDS[@]}"; do
            case "${m_id}" in ${seg}-*) seg_n=$((seg_n + 1)) ;; esac
        done
        (( seg_n >= 4 )) || SEG_MISS="${SEG_MISS} ${seg}=${seg_n}"
    done
    if [[ -z "${SEG_MISS}" ]]; then
        st_ck S3 1 "分段：P1 相似 / P2 跟单 / P3 合并 / P4 回归 各 ≥4 条"
    else
        st_ck S3 0 "分段不足（每段至少 4 条）" "${SEG_MISS}" "补齐对应段的断言"
    fi

    DUP=""
    ACC=" "
    for m_id in "${POOL_M_IDS[@]}"; do
        case "${ACC}" in
            *" ${m_id} "*) printf -v DUP '%s %s' "${DUP}" "${m_id}" ;;
        esac
        ACC="${ACC}${m_id} "
    done
    if [[ -z "${DUP}" ]]; then
        st_ck S4 1 "编号唯一：${#POOL_M_IDS[@]} 个断言编号无重复"
    else
        st_ck S4 0 "编号重复" "${DUP}" "断言编号必须唯一（POOL_SEEN 会拒绝重复执行）"
    fi

    # 自匹配陷阱：这条检查本身会扫到自己。把被禁词拆成变量拼出来，
    # 源码里就不存在连续的该字面量（只剩上面那行注释，会被过滤掉）。
    py_word='py'"thon"
    if awk -v w="${py_word}" '!/^[[:space:]]*#/ && index($0, w) > 0' "${BASH_SOURCE[0]}" | grep -q .; then
        st_ck S5 0 "实现约束：脚本调用了 ${py_word}" \
            "$(awk -v w="${py_word}" 'index($0, w) > 0' "${BASH_SOURCE[0]}" | head -n2)" \
            "scripts/ 下禁止调 ${py_word} 做交付动作；JSON 一律 jq（scripts/tests/platform/scripts-contract.sh §5）"
    else
        st_ck S5 1 "实现约束：脚本不调用 ${py_word}，JSON 用 jq"
    fi

    MISSING_FN=""
    for fn in pool_ck pool_summary pool_dry_run pool_wait_ready pool_login; do
        declare -F "${fn}" >/dev/null 2>&1 || MISSING_FN="${MISSING_FN} ${fn}"
    done
    if [[ -z "${MISSING_FN}" ]]; then
        st_ck S6 1 "原语齐备：_lib.sh 提供 pool_ck/pool_summary/pool_dry_run/pool_wait_ready/pool_login"
    else
        st_ck S6 0 "共享原语缺失" "${MISSING_FN}" "在 scripts/tests/pool/_lib.sh 里补齐"
    fi

    printf '\nPOOL_OPS_TOTAL=%d PASS=%d FAIL=%d\n' "${ST_N}" "${ST_PASS}" "${ST_FAIL}"
    (( ST_FAIL == 0 )) && exit 0
    exit 1
fi

# --------------------------------------------------------------------------- 运行参数

GATE_TAG="POOLGATE$(date +%m%d%H%M%S)$$"
export GATE_TAG
TAG_NUM="$(date +%m%d%H%M%S)$$"
TENANT_ID=""
PM_ID=""
PO1_ID=""
TL1_ID=""
BIZ1_ID=""
VENDOR2_ID=""
VPM1_ID=""
DOMAIN_ORD=""
DOMAIN_INV=""
DEMO_R02=""
DEMO_R09=""
DEMO_R10=""
DEMO_R11=""

# 本轮创建的需求（空格分隔）；teardown 用 id + title LIKE GATE_TAG 双口径精确删。
RECORDED_IDS=""

# 夹具 id
R_BUS_ROOT=""
R_SELF=""
R_A=""
R_B=""
R_X=""
R_CLOSED=""
R_MERGED=""
R_FOLLOW=""
R_FOLLOW_TERM=""
R_SRC=""
R_TGT=""
R_C1=""
R_C2=""
R_BUS=""
R_PDRTGT=""
R_CYC_A=""
R_CYC_B=""
R_X2=""
R_T2=""

# 夹具状态快照 / 合并响应
R_C1_STATUS=""
R_C2_STATUS=""
MERGE_CODE=""
MERGE_BODY=""
BASE_TITLE=""
PERF_ROWS=0
TEARDOWN_DONE=0
POOL_RESIDUE=0
DEMO_RESET_RC=""
DEMO_RESET_TAIL=""

# 会发 HTTP 的辅助函数用全局变量回传值（不用命令替换捕获：子壳里赋的全局父壳读不到）。
CREATED_REQ_ID=""
CREATED_REQ_CODE=""
# 详情请求的最近一次响应（req_detail 在父壳里赋值，需要「码 + 体」同时可读时用它）。
REQ_D_CODE=""
REQ_D_BODY=""

# followedByMe 的取值表达式：不能用 `//`（false 会被漏掉，前端渲染错），也不能走 pool_pick（false → empty）。
FOLLOWED_BY_ME_EXPR='if (.view? | type) == "object" and (.view | has("followedByMe")) then .view.followedByMe
  elif has("followedByMe") then .followedByMe else empty end'
FOLLOW_COUNT_EXPR='if (.view? | type) == "object" and (.view | has("followCount")) then .view.followCount
  elif has("followCount") then .followCount else empty end'

on_exit() {
    local rc=$?
    trap - EXIT INT TERM
    teardown_fixture
    exit "${rc}"
}
trap on_exit EXIT INT TERM

# --------------------------------------------------------------------------- 小工具

is2xx() { case "${1:-}" in 2??) return 0 ;; *) return 1 ;; esac; }
in_list() { local needle="$1" list="$2"; case " ${list} " in *" ${needle} "*) return 0 ;; *) return 1 ;; esac; }
db_exec() { db_q "$1" >/dev/null 2>&1 || true; }
float_gt() { awk -v a="${1:-0}" -v b="${2:-0}" 'BEGIN{exit !(a+0 > b+0)}'; }

record_id() {
    [[ -n "${1:-}" ]] || return 0
    in_list "$1" "${RECORDED_IDS}" || RECORDED_IDS="${RECORDED_IDS:+${RECORDED_IDS} }$1"
}

# create_req <type> <title> <domainId> [parentId] [ownerId] [description]
# → CREATED_REQ_ID / CREATED_REQ_CODE（HTTP 码留在 CREATED_REQ_CODE，失败时断言可读）
create_req() {
    local type="$1" title="$2" dom="${3:-${DOMAIN_ORD}}" parent="${4:-}" owner="${5:-}" desc="${6:-}" body
    body="$(jq -cn --arg t "${type}" --arg ti "${title}" --argjson d "${dom}" \
        --arg p "${parent}" --arg o "${owner}" --arg de "${desc}" \
        '{type:$t,title:$ti,priority:"P1",domainIds:[$d],expectedDeliveryDate:"2026-12-31"}
         + (if $de != "" then {description:$de} else {} end)
         + (if $p != "" then {parentId:($p|tonumber)} else {} end)
         + (if $o != "" then {ownerUserId:($o|tonumber)} else {} end)')"
    pool_post /api/req/requirements "${body}"
    CREATED_REQ_CODE="${HTTP_CODE}"
    CREATED_REQ_ID="$(pool_pick "${HTTP_BODY}" '.id' '.view.id')"
    record_id "${CREATED_REQ_ID}"
    [[ -n "${CREATED_REQ_ID}" ]] && is2xx "${CREATED_REQ_CODE}"
}

# req_detail <id>：GET 详情；HTTP 码与响应体都留在父壳（REQ_D_CODE / REQ_D_BODY），
# 这样「先取码、再取字段」不会因为命令替换掉进子壳而读到上一次的码。
req_detail() {
    pool_get "/api/req/requirements/$1"
    REQ_D_CODE="${HTTP_CODE}"
    REQ_D_BODY="${HTTP_BODY}"
}

# req_status <id>：详情状态（view.status 优先）。
req_status() {
    req_detail "$1"
    pool_pick "${REQ_D_BODY}" '.view.status' '.status'
}

# follow_rows <reqId> [userId]：requirement_follow 行数（表未迁移时 0，且由断言给出提示）。
follow_rows() {
    if ! pool_table_has requirement_follow; then printf '0'; return 0; fi
    local where="tenant_id=${TENANT_ID} AND requirement_id=$1"
    [[ -n "${2:-}" ]] && where="${where} AND user_id=$2"
    pool_sql_num "SELECT COUNT(*) FROM requirement_follow WHERE ${where}"
}

# follow_count <id>：详情 followCount（view 优先）。
follow_count() {
    req_detail "$1"
    pool_pick "${REQ_D_BODY}" "${FOLLOW_COUNT_EXPR}" '.view.followCount' '.followCount'
}

# followed_by_me <id>：详情 followedByMe 的布尔字面量（非布尔时输出空串）。
followed_by_me() {
    req_detail "$1"
    pool_bool "${REQ_D_BODY}" "${FOLLOWED_BY_ME_EXPR}"
}

# api_ids <body> <path>：把数组里的 id 拼成逗号串（诊断/包含判断用）。
api_ids() {
    printf '%s' "${1:-}" | jq -r "[${2:-.items[]?}.id] | map(tostring) | join(\",\")" 2>/dev/null || true
}

# result_has_id <body> <id>：相似结果是否包含某 id。
result_has_id() {
    printf '%s' "${1:-}" | jq -r --argjson id "$2" '[.items[]? | select(.id == $id)] | length' 2>/dev/null || printf 'ERR'
}

# score_of <body> <id>：相似结果里某条 score。
score_of() {
    printf '%s' "${1:-}" | jq -r --argjson id "$2" '[.items[]? | select(.id == $id)][0].score // empty' 2>/dev/null || true
}

# fixture_missing <断言编号> <变量值> <变量名> [修复]
fixture_missing() {
    pool_ck "$1" 0 "夹具未就绪：${3} 为空（创建失败/后端未落地，见上方夹具日志）" \
        "${4:-先确认 POST /api/req/requirements 200；再确认本轮夹具是否被提前 reset}"
}

# --------------------------------------------------------------------------- 夹具搭建

build_fixtures() {
    pool_section "夹具：本轮 GATE_TAG=${GATE_TAG}（标题前缀即清理口径）"
    if ! pool_login pm; then
        pool_warn "pm 登录失败：夹具无法创建，后续断言将逐条 FAIL（不是崩溃）"
        return 0
    fi

    # 需求类型是严格三层：BUS → PDR → RD（RequirementRules.checkParentCombination：
    # PDR 必须挂 BUS 父、RD 必须挂 PDR 父、BUS 必须顶层）。且父需求必须先 POOLED
    # （PARENT_NOT_POOLED：新建默认 SUBMITTED），否则下级创建 409。
    create_req BUS "门禁父需求 ${GATE_TAG}" "${DOMAIN_ORD}"
    R_BUS_ROOT="${CREATED_REQ_ID}"
    if [[ -n "${R_BUS_ROOT}" ]]; then
        db_exec "UPDATE requirement SET status='POOLED' WHERE tenant_id=${TENANT_ID} AND id=${R_BUS_ROOT}"
    fi
    pool_info "R_BUS_ROOT=${R_BUS_ROOT:-<失败>} HTTP=${CREATED_REQ_CODE}（本轮所有 PDR 的 BUS 父，已置 POOLED）"

    BASE_TITLE="供应商门户单点登录 ${GATE_TAG}"
    create_req PDR "${BASE_TITLE}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "供应商门户需要单点登录与统一认证，避免多套账号。"
    R_SELF="${CREATED_REQ_ID}"
    pool_info "R_SELF=${R_SELF:-<失败>} HTTP=${CREATED_REQ_CODE}（同标题 ORD 主夹具）"

    create_req PDR "${BASE_TITLE}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "供应商门户需要单点登录与统一认证，避免多套账号。"
    R_A="${CREATED_REQ_ID}"
    pool_info "R_A=${R_A:-<失败>} HTTP=${CREATED_REQ_CODE}（同标题 ORD）"

    create_req PDR "${BASE_TITLE}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "单点登录统一认证，供应商门户多账号问题。"
    R_B="${CREATED_REQ_ID}"
    pool_info "R_B=${R_B:-<失败>} HTTP=${CREATED_REQ_CODE}（同标题 ORD）"

    create_req PDR "${BASE_TITLE}" "${DOMAIN_INV}" "${R_BUS_ROOT}" "${PO1_ID}" "供应商门户需要单点登录与统一认证，避免多套账号。"
    R_X="${CREATED_REQ_ID}"
    pool_info "R_X=${R_X:-<失败>} HTTP=${CREATED_REQ_CODE}（同标题 INV，域加分对照）"

    create_req PDR "供应商门户单点登录（已关闭） ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "终态样本。"
    R_CLOSED="${CREATED_REQ_ID}"
    if [[ -n "${R_CLOSED}" ]]; then
        db_exec "UPDATE requirement SET status='CLOSED', close_reason='DONE', closed_at=NOW(3) WHERE tenant_id=${TENANT_ID} AND id=${R_CLOSED}"
    fi
    pool_info "R_CLOSED=${R_CLOSED:-<失败>} HTTP=${CREATED_REQ_CODE}（终态，不参与相似）"

    create_req PDR "供应商门户单点登录（已合并） ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "已合并样本。"
    R_MERGED="${CREATED_REQ_ID}"
    if [[ -n "${R_MERGED}" ]]; then
        db_exec "UPDATE requirement SET status='CLOSED', close_reason='MERGED', merged_into_id=${R_A:-0}, closed_at=NOW(3) WHERE tenant_id=${TENANT_ID} AND id=${R_MERGED}"
    fi
    pool_info "R_MERGED=${R_MERGED:-<失败>} HTTP=${CREATED_REQ_CODE}（merged_into_id 非空，不参与相似）"

    # ---- P2 跟单夹具
    create_req PDR "跟单门禁 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "跟单门禁用例（reporter=pm，owner=po1）。"
    R_FOLLOW="${CREATED_REQ_ID}"
    pool_info "R_FOLLOW=${R_FOLLOW:-<失败>} HTTP=${CREATED_REQ_CODE}"

    create_req PDR "跟单终态 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "终态跟单 409 用例。"
    R_FOLLOW_TERM="${CREATED_REQ_ID}"
    if [[ -n "${R_FOLLOW_TERM}" ]]; then
        db_exec "UPDATE requirement SET status='CLOSED', close_reason='DONE', closed_at=NOW(3) WHERE tenant_id=${TENANT_ID} AND id=${R_FOLLOW_TERM}"
    fi
    pool_info "R_FOLLOW_TERM=${R_FOLLOW_TERM:-<失败>} HTTP=${CREATED_REQ_CODE}"

    # ---- P3 合并夹具
    create_req PDR "合并源 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "合并源需求。"
    R_SRC="${CREATED_REQ_ID}"
    create_req PDR "合并目标 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "合并目标需求。"
    R_TGT="${CREATED_REQ_ID}"
    # 两个 PDR 都先入池：target 要过「非终态 + POOLED/SCHEDULED/IN_DEV」前置；
    # source 要能建 RD 子需求，而 RD 要求父 PDR 已排期（PARENT_NOT_SCHEDULED：需 SCHEDULED 或 IN_DEV，
    # 光 POOLED 不够），所以 source 置 SCHEDULED（仍属非终态，可被合并）。
    if [[ -n "${R_SRC}" ]]; then
        db_exec "UPDATE requirement SET status='SCHEDULED' WHERE tenant_id=${TENANT_ID} AND id=${R_SRC}"
    fi
    if [[ -n "${R_TGT}" ]]; then
        db_exec "UPDATE requirement SET status='POOLED' WHERE tenant_id=${TENANT_ID} AND id=${R_TGT}"
    fi
    # RD 必须挂 PDR 父：直接以 SRC 为父创建（DDL 里不用再改 parent_id）。
    create_req RD "合并子需求甲 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_SRC}" "${PO1_ID}" "source 子需求甲。"
    R_C1="${CREATED_REQ_ID}"
    create_req RD "合并子需求乙 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_SRC}" "${PO1_ID}" "source 子需求乙。"
    R_C2="${CREATED_REQ_ID}"
    R_C1_STATUS="$(pool_sql "SELECT status FROM requirement WHERE tenant_id=${TENANT_ID} AND id=${R_C1:-0}")"
    R_C2_STATUS="$(pool_sql "SELECT status FROM requirement WHERE tenant_id=${TENANT_ID} AND id=${R_C2:-0}")"
    pool_info "合并夹具：SRC=${R_SRC:-<失败>}(SCHEDULED) TGT=${R_TGT:-<失败>}(POOLED) C1=${R_C1:-<失败>}(parent=SRC) C2=${R_C2:-<失败>}(parent=SRC) C状态=${R_C1_STATUS:-?}/${R_C2_STATUS:-?}"

    # 跨类型：BUS source（顶层） + PDR target
    create_req BUS "合并跨类型 ${GATE_TAG}" "${DOMAIN_ORD}"
    R_BUS="${CREATED_REQ_ID}"
    create_req PDR "合并跨类型目标 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "PDR 目标。"
    R_PDRTGT="${CREATED_REQ_ID}"
    if [[ -n "${R_PDRTGT}" ]]; then
        db_exec "UPDATE requirement SET status='POOLED' WHERE tenant_id=${TENANT_ID} AND id=${R_PDRTGT}"
    fi

    # 成环：B 的 parent 用 DB 直接置为 A（接口会拦 PDR→PDR，这里故意造脏数据），
    # 合并 A→B 时 B 是 A 的后代，必须 400 MERGE_CYCLE（B 为 POOLED，排除状态门干扰）。
    create_req PDR "合并环A ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "环用例 A。"
    R_CYC_A="${CREATED_REQ_ID}"
    create_req PDR "合并环B ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "环用例 B。"
    R_CYC_B="${CREATED_REQ_ID}"
    if [[ -n "${R_CYC_B}" && -n "${R_CYC_A}" ]]; then
        db_exec "UPDATE requirement SET status='POOLED', parent_id=${R_CYC_A} WHERE tenant_id=${TENANT_ID} AND id=${R_CYC_B}"
    fi

    # target 已合并：T2 保持 POOLED（过状态门），但 merged_into_id 非空 → TARGET_MERGED
    create_req PDR "合并源X ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "target 已合并用例 source。"
    R_X2="${CREATED_REQ_ID}"
    create_req PDR "合并目标T2 ${GATE_TAG}" "${DOMAIN_ORD}" "${R_BUS_ROOT}" "${PO1_ID}" "已合并目标。"
    R_T2="${CREATED_REQ_ID}"
    if [[ -n "${R_T2}" ]]; then
        db_exec "UPDATE requirement SET status='POOLED', merged_into_id=${R_TGT:-0}, close_reason='MERGED' WHERE tenant_id=${TENANT_ID} AND id=${R_T2}"
    fi

    # ---- P1-7 性能样本：200 行同 token 标题（LIKE 预筛上限即 200；不建 100k，契约口径是 200 行样本）
    if [[ -n "${DOMAIN_ORD}" && -n "${PM_ID}" && -n "${TENANT_ID}" ]]; then
        local org_id values="" i
        org_id="$(pool_sql "SELECT org_id FROM sys_user WHERE tenant_id=${TENANT_ID} AND id=${PM_ID}")"
        org_id="${org_id:-0}"
        for i in $(seq 1 200); do
            values="${values}${values:+,}(${TENANT_ID},'PQ${TAG_NUM}${i}','PDR',${DOMAIN_ORD},${org_id},${PM_ID},'性能样本 ${GATE_TAG} 第${i}条','P1','POOLED')"
        done
        if db_q "INSERT INTO requirement (tenant_id,code,type,domain_id,origin_org_id,reporter_id,title,priority,status) VALUES ${values}" >/dev/null 2>&1; then
            PERF_ROWS=200
        else
            PERF_ROWS=0
        fi
        pool_info "性能样本：插入 ${PERF_ROWS}/200 行（title LIKE '%性能样本 ${GATE_TAG}%'）"
    fi
}

# --------------------------------------------------------------------------- P1 相似

check_p1_1() {
    local n self_in ids ok=0 code body
    if [[ -z "${R_SELF}" ]]; then fixture_missing P1-1 "${R_SELF}" "R_SELF"; return; fi
    pool_get "/api/req/requirements/${R_SELF}/similar?limit=5"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if ! is2xx "${code}"; then
        pool_ck P1-1 0 "$(pool_similar_hint "${body}")；$(pool_detail)" \
            "实现 GET /api/req/requirements/{id}/similar（契约 §3.1）；404/000 = 接口未就绪"
        return
    fi
    n="$(pool_len "${body}" '.items')"
    self_in="$(result_has_id "${body}" "${R_SELF}")"
    ids="$(api_ids "${body}" '.items[]?')"
    if [[ "${n}" =~ ^[0-9]+$ ]] && (( n >= 2 )) && [[ "${self_in}" == "0" ]]; then ok=1; fi
    pool_ck P1-1 "${ok}" "items=${n} selfIn=${self_in} ids=${ids:-<空>}" \
        "self 必须被排除（WHERE id <> 自身），且同标题候选 ≥2；候选结构见契约 §3.1"
}

check_p1_2() {
    local code body hits total sorted ok=0
    if [[ -z "${R_A}" || -z "${R_B}" ]]; then fixture_missing P1-2 "${R_A}" "R_A/R_B"; return; fi
    pool_get "/api/req/requirements/similar?title=$(pool_urlenc "${BASE_TITLE}")&domainId=${DOMAIN_ORD}&limit=10"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if ! is2xx "${code}"; then
        pool_ck P1-2 0 "$(pool_similar_hint "${body}")；$(pool_detail)" \
            "实现 GET /api/req/requirements/similar?title=…（契约 §3.1）；同名标题应命中同域 3 条"
        return
    fi
    hits="$(printf '%s' "${body}" | jq -r --arg t "${BASE_TITLE}" '[.items[]? | select(.title == $t)] | length' 2>/dev/null || echo 0)"
    total="$(printf '%s' "${body}" | jq -r '.total // -1' 2>/dev/null || echo -1)"
    sorted="$(printf '%s' "${body}" | jq -r '
        ([.items[]?] | length) as $n |
        ([.items[]?.score | select(type=="number")] | length) as $m |
        ([.items[]?.score]) as $s |
        if $n == $m and $n > 0 and $s == ($s | sort | reverse) then "1" else "0" end' 2>/dev/null || echo 0)"
    if [[ "${hits}" =~ ^[0-9]+$ ]] && (( hits >= 3 )) && [[ "${sorted}" == "1" ]]; then ok=1; fi
    pool_ck P1-2 "${ok}" "hits=${hits} total=${total} 降序=${sorted} $(pool_detail)" \
        "标题检索必须命中本轮 3 条同标题 ORD 需求，并按 score 降序返回（score 公式见契约 §3.1）"
}

check_p1_3() {
    local code body s_ord s_inv ok=0
    if [[ -z "${R_A}" || -z "${R_X}" ]]; then fixture_missing P1-3 "${R_X}" "R_A/R_X"; return; fi
    pool_get "/api/req/requirements/similar?title=$(pool_urlenc "${BASE_TITLE}")&domainId=${DOMAIN_ORD}&limit=10"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if ! is2xx "${code}"; then
        pool_ck P1-3 0 "$(pool_similar_hint "${body}")；$(pool_detail)" \
            "同域加分 +1 必须体现在 score：先修 /similar 再对比 ORD 与 INV 的 score"
        return
    fi
    s_ord="$(score_of "${body}" "${R_A}")"
    s_inv="$(score_of "${body}" "${R_X}")"
    if [[ -n "${s_ord}" && -n "${s_inv}" ]] && float_gt "${s_ord}" "${s_inv}"; then ok=1; fi
    pool_ck P1-3 "${ok}" "score(ORD R_A)=${s_ord:-<缺失>} score(INV R_X)=${s_inv:-<缺失>}" \
        "同 domain_id 应 +1（契约 §3.1），同标题同类型下 ORD 必须严格高于 INV"
}

check_p1_4() {
    local code body n total ids n2 code2 ok=0
    if [[ -z "${R_SELF}" ]]; then fixture_missing P1-4 "${R_SELF}" "R_SELF"; return; fi
    pool_get "/api/req/requirements/similar?title=$(pool_urlenc "${BASE_TITLE}")&limit=1"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if ! is2xx "${code}"; then
        pool_ck P1-4 0 "$(pool_similar_hint "${body}")；$(pool_detail)" \
            "limit 参数必须被消费（契约 §3.1：默认 limit=5）；先修 /similar"
        return
    fi
    n="$(pool_len "${body}" '.items')"
    total="$(printf '%s' "${body}" | jq -r '.total // -1' 2>/dev/null || echo -1)"
    pool_get "/api/req/requirements/${R_SELF}/similar?limit=1"
    code2="${HTTP_CODE}"
    n2="$(pool_len "${HTTP_BODY}" '.items')"
    ids="id端点 HTTP ${code2}"
    if [[ "${n}" =~ ^[0-9]+$ ]] && (( n == 1 )) && [[ "${total}" =~ ^[0-9]+$ ]] && (( total >= 3 )) \
        && [[ "${code2}" == "200" && "${n2}" == "1" ]]; then ok=1; fi
    pool_ck P1-4 "${ok}" "title端点 limit=1 → items=${n} total=${total}；${ids} → items=${n2}" \
        "limit 必须截断返回条数（items=1），total 仍是命中总数；title 与 id 两个入口都要生效"
}

check_p1_5() {
    local code body closed_in merged_in n ok=0 ids
    if [[ -z "${R_CLOSED}" || -z "${R_MERGED}" ]]; then fixture_missing P1-5 "${R_CLOSED}" "R_CLOSED/R_MERGED"; return; fi
    pool_get "/api/req/requirements/similar?title=$(pool_urlenc "${BASE_TITLE}")&domainId=${DOMAIN_ORD}&limit=10"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if ! is2xx "${code}"; then
        pool_ck P1-5 0 "$(pool_similar_hint "${body}")；$(pool_detail)" \
            "终态/已合并过滤必须在 SQL 层完成（status<>'CLOSED' AND merged_into_id IS NULL）"
        return
    fi
    closed_in="$(result_has_id "${body}" "${R_CLOSED}")"
    merged_in="$(result_has_id "${body}" "${R_MERGED}")"
    n="$(pool_len "${body}" '.items')"
    ids="$(api_ids "${body}" '.items[]?')"
    if [[ "${closed_in}" == "0" && "${merged_in}" == "0" ]] && [[ "${n}" =~ ^[0-9]+$ ]] && (( n >= 3 )); then ok=1; fi
    pool_ck P1-5 "${ok}" "CLOSED在结果=${closed_in} 已合并在结果=${merged_in} items=${n} ids=${ids:-<空>}" \
        "排除终态 CLOSED 与 merged_into_id IS NOT NULL（契约 §3.1）；items≥3 防止空结果假通过"
}

check_p1_6() {
    local code body vis_json r_json bad extra v2_bad ok=0 pos n
    if [[ -z "${DEMO_R09}" || -z "${DEMO_R10}" || -z "${DEMO_R11}" ]]; then
        fixture_missing P1-6 "${DEMO_R09}" "DEMO-R-09/10/11"
        return
    fi
    if ! pool_login vpm1; then
        pool_ck P1-6 0 "vpm1 登录失败" "先 make platform-seed（vpm1 是 VENDOR_PM + VEN-A 的验收入口）"
        return
    fi
    pool_get "/api/req/requirements?page=1&size=200"
    if [[ "${HTTP_CODE}" != "200" ]]; then
        pool_ck P1-6 0 "vpm1 需求列表 $(pool_detail)" "厂商可见性由 ScopeSql 派生；先修列表 200 再验相似度隔离"
        return
    fi
    vis_json="$(printf '%s' "${HTTP_BODY}" | jq -c '[.items[]?.id]' 2>/dev/null || echo '[]')"

    pool_get "/api/req/requirements/similar?title=$(pool_urlenc '外协订单对账')&limit=10"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if ! is2xx "${code}"; then
        pool_ck P1-6 0 "$(pool_similar_hint "${body}")；$(pool_detail)" \
            "厂商视角走同一 /similar，但必须套既有 /*scope:r*/ 谓词（契约 §3.1）"
        return
    fi
    r_json="$(printf '%s' "${body}" | jq -c '[.items[]?.id]' 2>/dev/null || echo '[]')"
    extra="$(jq -n --argjson r "${r_json}" --argjson v "${vis_json}" '$r - $v | length' 2>/dev/null || echo ERR)"
    n="$(pool_len "${body}" '.items')"
    pos="$(result_has_id "${body}" "${DEMO_R09}")"

    if ! pool_login vendor2; then
        pool_ck P1-6 0 "vendor2 登录失败" "先 make platform-seed"
        return
    fi
    pool_get "/api/req/requirements/similar?title=$(pool_urlenc '外协订单对账')&limit=10"
    v2_bad="$(printf '%s' "${HTTP_BODY}" | jq -r --argjson a "${DEMO_R09}" --argjson b "${DEMO_R10}" --argjson c "${DEMO_R11}" \
        '[.items[]? | select(.id==$a or .id==$b or .id==$c)] | length' 2>/dev/null || echo ERR)"
    if [[ "${extra}" == "0" && "${pos}" == "1" && "${v2_bad}" == "0" ]]; then ok=1; fi
    pool_ck P1-6 "${ok}" "vpm1 items=${n} 越出可见集=${extra} 含DEMO-R-09=${pos}；vendor2 看到VEN-A=${v2_bad}（HTTP ${HTTP_CODE}）" \
        "厂商账号只能看到本厂商相关（契约 §3.1）：/similar 必须复用 ScopeSql，vpm1 至少命中 DEMO-R-09，vendor2 不得命中 VEN-A 链"
}

check_p1_7() {
    local url i out code t times="" bad=0 p95 p95ms ok=0 warm
    if (( PERF_ROWS < 200 )); then
        pool_ck P1-7 0 "性能样本未插入（${PERF_ROWS}/200）：INSERT requirement 失败" \
            "先确认 DB 可达且 requirement 表结构未变；样本只为本条断言造数，teardown 会删"
        return
    fi
    if ! pool_login pm; then
        pool_ck P1-7 0 "pm 登录失败" "先 make platform-seed"
        return
    fi
    url="${GATEWAY_URL}/api/req/requirements/similar?title=$(pool_urlenc "性能样本 ${GATE_TAG}")&limit=5"
    for warm in 1 2 3; do
        curl -sS -o /dev/null --max-time 10 -H "Authorization: Bearer ${API_TOKEN}" \
            -H "X-Tenant-Id: ${API_TENANT}" "${url}" >/dev/null 2>&1 || true
    done
    for i in $(seq 1 20); do
        out="$(curl -sS -o /dev/null -w '%{http_code} %{time_total}' --max-time 10 \
            -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" "${url}" 2>/dev/null || true)"
        code="${out%% *}"
        t="${out##* }"
        [[ "${code}" == "200" ]] || bad=$((bad + 1))
        times="${times}${t}
"
    done
    p95="$(printf '%s' "${times}" | grep -v '^$' | sort -n | awk 'NR==19')"
    p95ms="$(awk -v s="${p95:-0}" 'BEGIN{printf "%.1f", s*1000}')"
    if [[ "${bad}" != "0" ]]; then
        # 性能只在「接口真的返回 200」时有意义；全 4xx/5xx 时 p95 是错误响应的耗时，不能当成性能达标。
        pool_ck P1-7 0 "非200响应=${bad}/20（耗时统计无意义，p95=${p95ms}ms）：接口未就绪，/similar 尚未落地" \
            "先让 GET /requirements/similar?title=… 返回 200；性能断言之后才有意义（契约 §3.1 p95 ≤ 200ms）"
        return
    fi
    if awk -v ms="${p95ms}" 'BEGIN{exit !(ms <= 200)}'; then ok=1; fi
    pool_ck P1-7 "${ok}" "200 行样本 p95=${p95ms}ms（20/20 HTTP 200，末次 code=${code:-?}）" \
        "p95 ≤ 200ms（契约 §3.1）：LIKE 预筛最多 200 行再打分；>200ms 说明退化成全表打分或未走索引"
}

# --------------------------------------------------------------------------- P2 跟单

check_p2_1() {
    local code fc rows ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P2-1 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login pm; then pool_ck P2-1 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_FOLLOW}/follow" '{"note":"pm：我们也要供应商门户 SSO"}'
    code="${HTTP_CODE}"
    fc="$(follow_count "${R_FOLLOW}")"
    rows="$(follow_rows "${R_FOLLOW}" "${PM_ID}")"
    if [[ "${code}" == "201" && "${fc}" == "1" && "${rows}" == "1" ]]; then ok=1; fi
    pool_ck P2-1 "${ok}" "HTTP=${code} followCount=${fc:-<缺失>} 库行=${rows}" \
        "首次跟单 → 201、requirement_follow 1 行、详情 followCount=1（契约 §3.2）"
}

check_p2_2() {
    local code fc rows ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P2-2 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login pm; then pool_ck P2-2 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_FOLLOW}/follow" '{"note":"重复提交应幂等"}'
    code="${HTTP_CODE}"
    fc="$(follow_count "${R_FOLLOW}")"
    rows="$(follow_rows "${R_FOLLOW}" "${PM_ID}")"
    if [[ "${code}" == "200" && "${fc}" == "1" && "${rows}" == "1" ]]; then ok=1; fi
    pool_ck P2-2 "${ok}" "HTTP=${code} followCount=${fc:-<缺失>} 库行=${rows}" \
        "重复提交必须 200 + 不新增行 + followCount 不变（契约 §3.2 幂等；唯一键 uk_follow）"
}

check_p2_3() {
    local code fc rows ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P2-3 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login po1; then pool_ck P2-3 0 "po1 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_FOLLOW}/follow" '{"note":"po1：二级企业也需要"}'
    code="${HTTP_CODE}"
    fc="$(follow_count "${R_FOLLOW}")"
    rows="$(follow_rows "${R_FOLLOW}")"
    if [[ "${code}" == "201" && "${fc}" == "2" && "${rows}" == "2" ]]; then ok=1; fi
    pool_ck P2-3 "${ok}" "HTTP=${code} followCount=${fc:-<缺失>} 库行=${rows}" \
        "第二人跟单 → 201、总行数 2、followCount=2（不同 user 不去重）"
}

check_p2_4() {
    local code total n mine orgs_n org_sum
    local ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P2-4 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login pm; then pool_ck P2-4 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_get "/api/req/requirements/${R_FOLLOW}/followers?page=1&size=1"
    code="${HTTP_CODE}"
    if [[ "${code}" != "200" ]]; then
        pool_ck P2-4 0 "$(pool_transport_hint)；$(pool_detail)" \
            "实现 GET /requirements/{id}/followers?page=&size=（契约 §3.2），返回 {total,mine,orgs}"
        return
    fi
    total="$(printf '%s' "${HTTP_BODY}" | jq -r '.total // -1' 2>/dev/null || echo -1)"
    n="$(pool_len "${HTTP_BODY}" '.items')"
    mine="$(pool_bool "${HTTP_BODY}" '.mine')"
    orgs_n="$(pool_len "${HTTP_BODY}" '.orgs')"
    org_sum="$(printf '%s' "${HTTP_BODY}" | jq -r '[.orgs[]?.count] | add // 0' 2>/dev/null || echo 0)"
    if [[ "${total}" == "2" && "${n}" == "1" && "${mine}" == "true" && "${orgs_n}" =~ ^[0-9]+$ ]] \
        && (( orgs_n >= 1 )) && [[ "${org_sum}" == "2" ]]; then ok=1; fi
    pool_ck P2-4 "${ok}" "total=${total} items(size=1)=${n} mine=${mine:-<非布尔>} orgs=${orgs_n} orgSum=${org_sum}" \
        "followers 分页必须生效（size=1 → 1 条），mine 对当前登录人 true，orgs 聚合 count 合计 = total"
}

check_p2_5() {
    local code_pm f_pm code_po f_po code_ad f_ad ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P2-5 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login pm; then pool_ck P2-5 0 "pm 登录失败" "先 make platform-seed"; return; fi
    # 先取码、再取字段：两步都在父壳里做，避免命令替换把 HTTP 码留在子壳。
    req_detail "${R_FOLLOW}"
    code_pm="${REQ_D_CODE}"
    f_pm="$(pool_bool "${REQ_D_BODY}" "${FOLLOWED_BY_ME_EXPR}")"
    if ! pool_login po1; then pool_ck P2-5 0 "po1 登录失败" "先 make platform-seed"; return; fi
    req_detail "${R_FOLLOW}"
    code_po="${REQ_D_CODE}"
    f_po="$(pool_bool "${REQ_D_BODY}" "${FOLLOWED_BY_ME_EXPR}")"
    if ! pool_login admin; then pool_ck P2-5 0 "admin 登录失败" "先 make platform-seed"; return; fi
    req_detail "${R_FOLLOW}"
    code_ad="${REQ_D_CODE}"
    f_ad="$(pool_bool "${REQ_D_BODY}" "${FOLLOWED_BY_ME_EXPR}")"
    if [[ "${code_pm}" == "200" && "${f_pm}" == "true" && "${code_po}" == "200" && "${f_po}" == "true" \
        && "${code_ad}" == "200" && "${f_ad}" == "false" ]]; then ok=1; fi
    pool_ck P2-5 "${ok}" "pm=${f_pm:-缺失}(HTTP ${code_pm}) po1=${f_po:-缺失}(HTTP ${code_po}) admin=${f_ad:-缺失}(HTTP ${code_ad})" \
        "详情 view.followedByMe 必须按当前登录人计算（契约 §3.2）；缺失/非布尔都算失败"
}

check_p2_6() {
    local code biz ok=0
    if [[ -z "${R_FOLLOW_TERM}" ]]; then fixture_missing P2-6 "${R_FOLLOW_TERM}" "R_FOLLOW_TERM"; return; fi
    if ! pool_login pm; then pool_ck P2-6 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_FOLLOW_TERM}/follow" '{"note":"终态不应可跟"}'
    code="${HTTP_CODE}"
    biz="$(pool_code)"
    if [[ "${code}" == "409" && "${biz}" == "REQUIREMENT_CLOSED" ]]; then ok=1; fi
    pool_ck P2-6 "${ok}" "HTTP=${code} code=${biz:-<缺失>} body=$(pool_snippet)" \
        "终态（CLOSED）跟单必须 409 REQUIREMENT_CLOSED（契约 §3.2）；先修状态判定再谈计数"
}

check_p2_7() {
    local code fc rows ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P2-7 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login pm; then pool_ck P2-7 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_delete "/api/req/requirements/${R_FOLLOW}/follow"
    code="${HTTP_CODE}"
    fc="$(follow_count "${R_FOLLOW}")"
    rows="$(follow_rows "${R_FOLLOW}" "${PM_ID}")"
    if [[ "${code}" == "204" && "${fc}" == "1" && "${rows}" == "0" ]]; then ok=1; fi
    pool_ck P2-7 "${ok}" "HTTP=${code} followCount=${fc:-<缺失>} 本人库行=${rows}" \
        "DELETE 必须 204、删掉本人行、计数 -1（契约 §3.2）；404 = 行没删或路由未落地"
}

check_p2_8() {
    local code rows_pm rows_po rows_total ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P2-8 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login pm; then pool_ck P2-8 0 "pm 登录失败" "先 make platform-seed"; return; fi
    # P2-7 已删除 pm 的跟单；同日再跟一次，通知必须仍只有 1 条/人（幂等键 follow:{reqId}:{date}）
    pool_post "/api/req/requirements/${R_FOLLOW}/follow" '{"note":"同日重复跟单（通知幂等）"}'
    code="${HTTP_CODE}"
    if ! pool_table_has notification; then
        pool_ck P2-8 0 "notification 表缺失" "先 make platform-seed / 跑 Flyway"
        return
    fi
    rows_pm="$(pool_sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${TENANT_ID} AND biz_id=${R_FOLLOW} AND user_id=${PM_ID} AND type='REQUIREMENT_FOLLOWED'")"
    rows_po="$(pool_sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${TENANT_ID} AND biz_id=${R_FOLLOW} AND user_id=${PO1_ID} AND type='REQUIREMENT_FOLLOWED'")"
    rows_total="$(pool_sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${TENANT_ID} AND biz_id=${R_FOLLOW} AND type='REQUIREMENT_FOLLOWED'")"
    if [[ "${code}" == "201" && "${rows_pm}" == "1" && "${rows_po}" == "1" && "${rows_total}" == "2" ]]; then ok=1; fi
    pool_ck P2-8 "${ok}" "HTTP=${code} pm通知=${rows_pm} po1通知=${rows_po} 合计=${rows_total}（期望各 1）" \
        "同日同一需求对 re­porter/owner 各 1 条：幂等键 follow:{reqId}:{date}（契约 §3.2）；多 = 没去重，0 = 没发通知"
}

check_p2_9() {
    local code rows ok=0 body
    if [[ -z "${R_FOLLOW}" || -z "${VENDOR2_ID}" ]]; then fixture_missing P2-9 "${VENDOR2_ID}" "vendor2 id"; return; fi
    if ! pool_login vendor2; then pool_ck P2-9 0 "vendor2 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_FOLLOW}/follow" '{"note":"不可见账号不应能跟"}'
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    rows="$(follow_rows "${R_FOLLOW}" "${VENDOR2_ID}")"
    # 403 = 权限拒绝；404 只有带业务 code 才算「不可见被拒」。路由没落地时 Spring 的裸 404
    # （无 code 字段）不算通过，否则接口没实现也能 PASS。
    if [[ "${code}" == "403" ]] || { [[ "${code}" == "404" ]] && pool_biz_404 "${body}"; }; then
        if [[ "${rows}" == "0" ]]; then ok=1; fi
    fi
    pool_ck P2-9 "${ok}" "HTTP=${code} code=$(pool_code "${body}") vendor2 库行=${rows} body=$(pool_snippet "${body}")" \
        "不可见账号跟单必须 403/业务 404 且 0 行（契约 §3.2：能看到该需求的账号才可提交）；201 = 越权写；裸 404 = 路由未落地"
}

# --------------------------------------------------------------------------- P3 合并

# p3_prepare：合并前置——source 2 位跟单人、target 预置 1 位同人（去重用例）。
p3_prepare() {
    [[ -n "${R_SRC}" && -n "${R_TGT}" ]] || return 0
    pool_login pm || return 0
    pool_post "/api/req/requirements/${R_SRC}/follow" '{"note":"合并前 source 跟单人 pm"}' >/dev/null
    pool_login po1 || return 0
    pool_post "/api/req/requirements/${R_SRC}/follow" '{"note":"合并前 source 跟单人 po1"}' >/dev/null
    pool_post "/api/req/requirements/${R_TGT}/follow" '{"note":"target 已有 po1，迁移时应去重"}' >/dev/null
    local s t
    s="$(follow_rows "${R_SRC}")"
    t="$(follow_rows "${R_TGT}")"
    pool_info "P3 前置：SRC follow=${s}（期望 pm/po1 各 1），TGT follow=${t}（期望 po1 1）"
}

check_p3_1() {
    local code body closed src_out tgt_out st reason merged ok=0
    if [[ -z "${R_SRC}" || -z "${R_TGT}" ]]; then fixture_missing P3-1 "${R_SRC}" "R_SRC/R_TGT"; return; fi
    if ! pool_login pm; then pool_ck P3-1 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_SRC}/merge" \
        "$(jq -cn --argjson t "${R_TGT}" '{targetId:$t,reason:"同诉求重复，合并到主需求（门禁）"}')"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"   # 先落住合并响应：后面 req_detail 会改写 HTTP_BODY
    MERGE_CODE="${code}"
    MERGE_BODY="${body}"
    closed="$(pool_bool "${body}" '.closed')"
    src_out="$(pool_pick "${body}" '.sourceId' '.source_id')"
    tgt_out="$(pool_pick "${body}" '.targetId' '.target_id')"
    req_detail "${R_SRC}"
    st="$(pool_pick "${REQ_D_BODY}" '.view.status' '.status')"
    reason="$(pool_pick "${REQ_D_BODY}" '.view.closeReason' '.closeReason' '.view.close_reason' '.close_reason')"
    merged="$(pool_pick "${REQ_D_BODY}" '.view.mergedIntoId' '.mergedIntoId' '.view.merged_into_id' '.merged_into_id')"
    if [[ "${code}" == "200" && "${closed}" == "true" && "${src_out}" == "${R_SRC}" && "${tgt_out}" == "${R_TGT}" \
        && "${st}" == "CLOSED" && "${reason}" == "MERGED" && "${merged}" == "${R_TGT}" ]]; then ok=1; fi
    pool_ck P3-1 "${ok}" \
        "HTTP=${code} resp{closed=${closed:-<缺失>},sourceId=${src_out:-<缺失>},targetId=${tgt_out:-<缺失>}} detail{status=${st:-<缺失>},closeReason=${reason:-<缺失>},mergedIntoId=${merged:-<缺失>}}" \
        "合并必须 200 且返回 {closed:true,sourceId,targetId}；source 落 status=CLOSED、close_reason=MERGED、merged_into_id=target（契约 §3.3）"
}

check_p3_2() {
    local moved src_rows tgt_rows users ok=0
    if [[ -z "${R_SRC}" || -z "${R_TGT}" ]]; then fixture_missing P3-2 "${R_SRC}" "R_SRC/R_TGT"; return; fi
    if ! pool_table_has requirement_follow; then
        pool_ck P3-2 0 "requirement_follow 表缺失（V22 未迁移）" "按契约 §2 建表后再验迁移去重"
        return
    fi
    moved="$(pool_pick "${MERGE_BODY}" '.movedFollows' '.moved_follows')"
    src_rows="$(follow_rows "${R_SRC}")"
    tgt_rows="$(follow_rows "${R_TGT}")"
    users="$(pool_sql "SELECT GROUP_CONCAT(user_id ORDER BY user_id) FROM requirement_follow WHERE tenant_id=${TENANT_ID} AND requirement_id=${R_TGT}")"
    local want
    want="$(printf '%s\n%s\n' "${PM_ID}" "${PO1_ID}" | sort -n | paste -sd, -)"
    if [[ "${moved}" == "1" && "${src_rows}" == "0" && "${tgt_rows}" == "2" && "${users}" == "${want}" ]]; then ok=1; fi
    pool_ck P3-2 "${ok}" "movedFollows=${moved:-<缺失>} source行=${src_rows} target行=${tgt_rows} target用户=${users:-<空>}（期望 ${want}）" \
        "follow 按 (tenant,user) 迁到 target 并去重：source 2 人、target 已有 po1 → movedFollows=1、target 2 行、source 0 行（契约 §3.3）"
}

check_p3_3() {
    local moved c1 c2 s1 s2 ok=0
    if [[ -z "${R_C1}" || -z "${R_C2}" || -z "${R_TGT}" ]]; then fixture_missing P3-3 "${R_C1}" "R_C1/R_C2"; return; fi
    moved="$(pool_pick "${MERGE_BODY}" '.movedChildren' '.moved_children')"
    c1="$(pool_sql "SELECT IFNULL(parent_id,0) FROM requirement WHERE tenant_id=${TENANT_ID} AND id=${R_C1}")"
    c2="$(pool_sql "SELECT IFNULL(parent_id,0) FROM requirement WHERE tenant_id=${TENANT_ID} AND id=${R_C2}")"
    s1="$(pool_sql "SELECT status FROM requirement WHERE tenant_id=${TENANT_ID} AND id=${R_C1}")"
    s2="$(pool_sql "SELECT status FROM requirement WHERE tenant_id=${TENANT_ID} AND id=${R_C2}")"
    if [[ "${moved}" == "2" && "${c1}" == "${R_TGT}" && "${c2}" == "${R_TGT}" \
        && "${s1}" == "${R_C1_STATUS}" && "${s2}" == "${R_C2_STATUS}" ]]; then ok=1; fi
    pool_ck P3-3 "${ok}" "movedChildren=${moved:-<缺失>} C1.parent=${c1} C2.parent=${c2} 状态 C1=${s1}/${R_C1_STATUS} C2=${s2}/${R_C2_STATUS}" \
        "source 子需求 parent_id 改 target，状态机不许动（契约 §3.3「保留子需求状态，不改状态机」）"
}

check_p3_4() {
    local n detail tgt mf mc ok=0
    if [[ -z "${R_SRC}" ]]; then fixture_missing P3-4 "${R_SRC}" "R_SRC"; return; fi
    n="$(pool_sql_num "SELECT COUNT(*) FROM audit_log WHERE tenant_id=${TENANT_ID} AND action='REQUIREMENT_MERGED' AND biz_id=${R_SRC}")"
    detail="$(pool_sql "SELECT detail_json FROM audit_log WHERE tenant_id=${TENANT_ID} AND action='REQUIREMENT_MERGED' AND biz_id=${R_SRC} ORDER BY id DESC LIMIT 1")"
    tgt="$(printf '%s' "${detail}" | jq -r '(.targetId // .target_id) // empty' 2>/dev/null || true)"
    mf="$(printf '%s' "${detail}" | jq -r '(.movedFollows // .moved_follows) // empty' 2>/dev/null || true)"
    mc="$(printf '%s' "${detail}" | jq -r '(.movedChildren // .moved_children) // empty' 2>/dev/null || true)"
    if [[ "${n}" == "1" && "${tgt}" == "${R_TGT}" && "${mf}" == "1" && "${mc}" == "2" ]]; then ok=1; fi
    pool_ck P3-4 "${ok}" "audit行=${n} targetId=${tgt:-<缺失>} movedFollows=${mf:-<缺失>} movedChildren=${mc:-<缺失>} detail=$(pool_snippet "${detail}")" \
        "合并且只写 1 条 REQUIREMENT_MERGED（biz_id=source），detail 带 targetId/reason/movedFollows/movedChildren（契约 §2/§3.3）"
}

check_p3_5() {
    local code biz ok=0
    if [[ -z "${R_SRC}" || -z "${R_TGT}" ]]; then fixture_missing P3-5 "${R_SRC}" "R_SRC/R_TGT"; return; fi
    if ! pool_login pm; then pool_ck P3-5 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_SRC}/merge" \
        "$(jq -cn --argjson t "${R_TGT}" '{targetId:$t,reason:"重复合并应 409"}')"
    code="${HTTP_CODE}"
    biz="$(pool_code)"
    if [[ "${code}" == "409" && "${biz}" == "ALREADY_MERGED" ]]; then ok=1; fi
    pool_ck P3-5 "${ok}" "HTTP=${code} code=${biz:-<缺失>} $(pool_detail)" \
        "source 已有 merged_into_id → 409 ALREADY_MERGED（契约 §3.3 前置⑤）；先于终态判定，否则口径不同"
}

check_p3_6() {
    local code ok=0
    if [[ -z "${R_BUS}" || -z "${R_PDRTGT}" ]]; then fixture_missing P3-6 "${R_BUS}" "R_BUS/R_PDRTGT"; return; fi
    if ! pool_login pm; then pool_ck P3-6 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_BUS}/merge" \
        "$(jq -cn --argjson t "${R_PDRTGT}" '{targetId:$t,reason:"跨类型不允许"}')"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if [[ "${code}" == "400" ]]; then ok=1; fi
    pool_ck P3-6 "${ok}" "HTTP=${code} code=$(pool_code "${body}") body=$(pool_snippet "${body}")" \
        "BUS→PDR 跨类型必须 400（契约 §3.3 前置① / §8「不做跨类型合并」）；200 = 类型门缺失"
}

check_p3_7() {
    local code biz ok=0
    if [[ -z "${R_CYC_A}" || -z "${R_CYC_B}" ]]; then fixture_missing P3-7 "${R_CYC_A}" "R_CYC_A/R_CYC_B"; return; fi
    if ! pool_login pm; then pool_ck P3-7 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_CYC_A}/merge" \
        "$(jq -cn --argjson t "${R_CYC_B}" '{targetId:$t,reason:"成环必须拒绝"}')"
    code="${HTTP_CODE}"
    biz="$(pool_code)"
    if [[ "${code}" == "400" && "${biz}" == "MERGE_CYCLE" ]]; then ok=1; fi
    pool_ck P3-7 "${ok}" "HTTP=${code} code=${biz:-<缺失>} $(pool_detail)" \
        "target 是 source 后代（B.parent=A）时必须 400 MERGE_CYCLE（契约 §3.3 前置④）；否则合并后父子成环"
}

check_p3_8() {
    local code biz ok=0
    if [[ -z "${R_X2}" || -z "${R_T2}" ]]; then fixture_missing P3-8 "${R_X2}" "R_X2/R_T2"; return; fi
    if ! pool_login pm; then pool_ck P3-8 0 "pm 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_X2}/merge" \
        "$(jq -cn --argjson t "${R_T2}" '{targetId:$t,reason:"target 已合并"}')"
    code="${HTTP_CODE}"
    biz="$(pool_code)"
    if [[ "${code}" == "400" && "${biz}" == "TARGET_MERGED" ]]; then ok=1; fi
    pool_ck P3-8 "${ok}" "HTTP=${code} code=${biz:-<缺失>} $(pool_detail)" \
        "target 已有 merged_into_id → 400 TARGET_MERGED（契约 §3.3 前置⑥）；T2 保持 POOLED 以排除状态门干扰"
}

check_p3_9() {
    local n ok=0
    if [[ -z "${R_SRC}" ]]; then fixture_missing P3-9 "${R_SRC}" "R_SRC"; return; fi
    n="$(pool_sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${TENANT_ID} AND biz_id=${R_SRC} AND user_id=${PM_ID} AND type LIKE '%MERGED%'")"
    if [[ "${n}" =~ ^[0-9]+$ ]] && (( n >= 1 )); then ok=1; fi
    pool_ck P3-9 "${ok}" "source 提报人(pm) 合并通知=${n} 条（type LIKE %MERGED%）" \
        "合并必须通知 source 提报人/跟单人（契约 §3.3「写审计 + 通知 source 提报人」）；0 = 只写了审计没发通知"
}

# --------------------------------------------------------------------------- P4 回归

check_p4_1() {
    local code has num ok=0
    if ! pool_login admin; then pool_ck P4-1 0 "admin 登录失败" "先 make platform-seed"; return; fi
    pool_get "/api/req/requirements?page=1&size=50"
    code="${HTTP_CODE}"
    has="$(printf '%s' "${HTTP_BODY}" | jq -r '[.items[]? | has("followCount")] | all' 2>/dev/null || echo false)"
    num="$(printf '%s' "${HTTP_BODY}" | jq -r '[.items[]? | (.followCount | type == "number")] | all' 2>/dev/null || echo false)"
    if [[ "${code}" == "200" && "${has}" == "true" && "${num}" == "true" ]]; then ok=1; fi
    pool_ck P4-1 "${ok}" "HTTP=${code} 每项含 followCount=${has} 数值=${num} total=$(printf '%s' "${HTTP_BODY}" | jq -r '.total // "?"' 2>/dev/null)" \
        "列表 items[] 必须增 followCount（契约 §3.2「列表页需要显示 N 人需要」）；控制台/需求池列表都读它"
}

check_p4_2() {
    local code f ok=0
    if [[ -z "${R_FOLLOW}" ]]; then fixture_missing P4-2 "${R_FOLLOW}" "R_FOLLOW"; return; fi
    if ! pool_login pm; then pool_ck P4-2 0 "pm 登录失败" "先 make platform-seed"; return; fi
    req_detail "${R_FOLLOW}"
    code="${REQ_D_CODE}"
    f="$(pool_bool "${REQ_D_BODY}" "${FOLLOWED_BY_ME_EXPR}")"
    if [[ "${code}" == "200" && "${f}" == "true" ]]; then ok=1; fi
    pool_ck P4-2 "${ok}" "HTTP=${code} followedByMe=${f:-<缺失/非布尔>}" \
        "详情 view 必须含布尔 followedByMe（契约 §3.2）；字段缺失或字符串 \"true\" 都判红（前端会渲染错）"
}

check_p4_3() {
    local code body ok=0
    if [[ -z "${R_X2}" || -z "${R_PDRTGT}" ]]; then fixture_missing P4-3 "${R_X2}" "R_X2/R_PDRTGT"; return; fi
    if ! pool_login vpm1; then pool_ck P4-3 0 "vpm1 登录失败" "先 make platform-seed"; return; fi
    pool_post "/api/req/requirements/${R_X2}/merge" \
        "$(jq -cn --argjson t "${R_PDRTGT}" '{targetId:$t,reason:"VENDOR_PM 不应有合并权"}')"
    code="${HTTP_CODE}"
    body="${HTTP_BODY}"
    if [[ "${code}" == "403" ]]; then ok=1; fi
    pool_ck P4-3 "${ok}" "HTTP=${code} code=$(pool_code "${body}") body=$(pool_snippet "${body}")" \
        "req.action.merge 只给 PLATFORM_ADMIN/内部 PMO/PRODUCT（契约 §3.3/§4）；VENDOR_PM 必须 403，200/400 都算权限没拦住"
}

# P4-4：teardown 后断言残留 0（先删后查，不依赖 demo-reset 掩盖）。
check_p4_4() {
    TEARDOWN_DONE=0
    teardown_fixture
    local ok=0
    if (( POOL_RESIDUE == 0 )); then ok=1; fi
    pool_ck P4-4 "${ok}" "残留合计=${POOL_RESIDUE}（需求/跟单/审计/通知）" \
        "teardown 必须按本轮 id 与 title LIKE GATE_TAG 精确删；残留>0 时按打印出的表名/ID 补删，不要用 demo-reset 掩"
}

check_p4_5() {
    local rc=0 out=""
    out="$(bash "${ROOT}/scripts/platform-demo-reset.sh" 2>&1)" || rc=$?
    DEMO_RESET_RC="${rc}"
    DEMO_RESET_TAIL="$(printf '%s' "${out}" | tail -n 8)"
    local ok=0
    if [[ "${rc}" == "0" ]] && printf '%s' "${out}" | grep -q '80/80'; then ok=1; fi
    pool_ck P4-5 "${ok}" "rc=${rc} 期望 80/80；末尾: $(printf '%s' "${DEMO_RESET_TAIL}" | tr '\n' ' ' | cut -c1-260)" \
        "platform-demo-reset 必须仍 80/80（契约 §6 P4）：先单独跑 bash scripts/platform-demo-reset.sh 看首个 FAIL，再改重置/夹具"
}

check_p4_6() {
    local rows notes ok=0 want
    # P4-5 的 reset 会删旧建新：这里必须按 code 重新取 id，不能用重置前的快照
    DEMO_R02="$(pool_sql_num "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} AND code='DEMO-R-02'")"
    want="${DEMO_R02}"
    if [[ -z "${want}" ]]; then
        pool_ck P4-6 0 "DEMO-R-02 不在库（演示数据缺失）" "先修 demo-reset/演示夹具；DEMO-R-02 是跟单演示主需求"
        return
    fi
    if ! pool_table_has requirement_follow; then
        pool_ck P4-6 0 "requirement_follow 表缺失（V22 未迁移）" "契约 §2 的 V22 必须先落地；否则演示跟单无处落"
        return
    fi
    rows="$(pool_sql_num "SELECT COUNT(*) FROM requirement_follow WHERE tenant_id=${TENANT_ID} AND requirement_id=${want}")"
    notes="$(pool_sql_num "SELECT COUNT(*) FROM requirement_follow WHERE tenant_id=${TENANT_ID} AND requirement_id=${want} AND note IS NOT NULL AND note<>''")"
    if (( rows >= 1 )) && (( notes >= 1 )); then ok=1; fi
    pool_ck P4-6 "${ok}" "DEMO-R-02 follow 行=${rows} note=${notes}（§7 期望 6/2）" \
        "演示数据必须含示例跟单（契约 §7：DEMO-R-02 挂 6 条 follow，其中 2 条带 note）"
}

check_p4_7() {
    local fc rows ok=0
    if [[ -z "${DEMO_R02}" ]]; then
        pool_ck P4-7 0 "DEMO-R-02 不在库" "先修演示夹具"; return
    fi
    if ! pool_login admin; then pool_ck P4-7 0 "admin 登录失败" "先 make platform-seed"; return; fi
    fc="$(follow_count "${DEMO_R02}")"
    rows="$(follow_rows "${DEMO_R02}")"
    if [[ -n "${fc}" && "${fc}" == "${rows}" ]] && (( rows >= 1 )); then ok=1; fi
    pool_ck P4-7 "${ok}" "API followCount=${fc:-<缺失>} 库行=${rows}" \
        "演示需求详情 followCount 必须与 requirement_follow 行数一致（契约 §3.2/§7）；不等 = 计数缓存或种子漏行"
}

check_p4_8() {
    local rows notes ok=0
    if [[ -z "${DEMO_R02}" ]]; then
        pool_ck P4-8 0 "DEMO-R-02 不在库" "先修演示夹具"; return
    fi
    rows="$(pool_sql_num "SELECT COUNT(*) FROM requirement_follow WHERE tenant_id=${TENANT_ID} AND requirement_id=${DEMO_R02}")"
    notes="$(pool_sql_num "SELECT COUNT(*) FROM requirement_follow WHERE tenant_id=${TENANT_ID} AND requirement_id=${DEMO_R02} AND note IS NOT NULL AND note<>''")"
    # 契约 §7 明确 6 条（2 条 note）；任务简报写「1 条示例跟单」，以 §7 为准（见 QA-PLAN 缺口 G-6）。
    if [[ "${rows}" == "6" && "${notes}" == "2" ]]; then ok=1; fi
    pool_ck P4-8 "${ok}" "DEMO-R-02 follow=${rows} note=${notes}（§7 = 6/2）" \
        "按契约 §7 落 6 条 follow（biz1/biz2/suite1/po1/vendor1/vpm1，其中 2 条 note）；数量不符 = DemoFixture 增补未完成"
}

# --------------------------------------------------------------------------- teardown

teardown_fixture() {
    [[ "${TEARDOWN_DONE}" == "1" ]] && return 0
    TEARDOWN_DONE=1
    POOL_RESIDUE=0
    if ! db_up; then
        pool_warn "teardown 跳过：MySQL 不可达，本轮夹具可能残留（GATE_TAG=${GATE_TAG}），请 make platform-demo-reset"
        return 0
    fi
    local T="${TENANT_ID}" ids="" v id_rows
    for v in ${RECORDED_IDS}; do
        [[ "${v}" =~ ^[0-9]+$ ]] && ids="${ids:+${ids},}${v}"
    done
    # 兜底：性能样本/漏登记的夹具靠标题 tag 收口（tag 里有 pid，不会误伤演示数据）。
    # 必须逐行取 id：GROUP_CONCAT 默认 1024 字节会截断——实测 200 行样本只收回 146 个 id，
    # 剩下的 54 行成了「校验是 0 但人还在库里」的假残留。
    id_rows="$(db_q "SELECT id FROM requirement WHERE tenant_id=${T} AND (title LIKE '%${GATE_TAG}%' OR id IN (${ids:-0}))" 2>/dev/null)"
    ids="$(printf '%s\n' "${id_rows}" | grep -E '^[0-9]+$' | sort -n -u | paste -sd, -)"
    if [[ -z "${ids}" ]]; then
        pool_info "teardown：本轮没有成功创建的需求，无需清理"
        return 0
    fi

    local n_req n_follow n_audit n_notif
    n_req="$(pool_sql_num "SELECT COUNT(*) FROM requirement WHERE tenant_id=${T} AND id IN (${ids})")"
    n_follow="$(pool_sql_num "SELECT COUNT(*) FROM requirement_follow WHERE tenant_id=${T} AND requirement_id IN (${ids})")"
    n_audit="$(pool_sql_num "SELECT COUNT(*) FROM audit_log WHERE tenant_id=${T} AND biz_id IN (${ids})")"
    n_notif="$(pool_sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${T} AND biz_id IN (${ids})")"

    db_exec "DELETE FROM notification WHERE tenant_id=${T} AND biz_id IN (${ids})"
    db_exec "DELETE FROM audit_log WHERE tenant_id=${T} AND biz_id IN (${ids})"
    if pool_table_has requirement_follow; then
        db_exec "DELETE FROM requirement_follow WHERE tenant_id=${T} AND requirement_id IN (${ids})"
    fi
    db_exec "DELETE FROM requirement_domain_rel WHERE tenant_id=${T} AND requirement_id IN (${ids})"
    db_exec "DELETE FROM requirement_tag WHERE tenant_id=${T} AND requirement_id IN (${ids})"
    db_exec "DELETE FROM req_change_item WHERE change_id IN (SELECT id FROM req_change WHERE tenant_id=${T} AND requirement_id IN (${ids}))"
    db_exec "DELETE FROM req_change WHERE tenant_id=${T} AND requirement_id IN (${ids})"
    db_exec "DELETE FROM task WHERE tenant_id=${T} AND requirement_id IN (${ids})"
    db_exec "DELETE FROM requirement WHERE tenant_id=${T} AND id IN (${ids})"

    local left_req left_follow left_audit left_notif left_tag
    left_req="$(pool_sql_num "SELECT COUNT(*) FROM requirement WHERE tenant_id=${T} AND id IN (${ids})")"
    left_follow="$(pool_sql_num "SELECT COUNT(*) FROM requirement_follow WHERE tenant_id=${T} AND requirement_id IN (${ids})")"
    left_audit="$(pool_sql_num "SELECT COUNT(*) FROM audit_log WHERE tenant_id=${T} AND biz_id IN (${ids})")"
    left_notif="$(pool_sql_num "SELECT COUNT(*) FROM notification WHERE tenant_id=${T} AND biz_id IN (${ids})")"
    # 双保险：再按标题 tag 数一遍。id 列表若因任何原因漏行，这里会把「人还在库里」暴露出来。
    left_tag="$(pool_sql_num "SELECT COUNT(*) FROM requirement WHERE tenant_id=${T} AND title LIKE '%${GATE_TAG}%'")"
    POOL_RESIDUE=$((left_tag + left_follow + left_audit + left_notif))
    pool_info "teardown 已删：需求 ${n_req} 跟单 ${n_follow} 审计 ${n_audit} 通知 ${n_notif}（ids=${ids}）"
    if (( POOL_RESIDUE == 0 )); then
        pool_info "teardown 校验：本轮残留 0 条（需求/跟单/审计/通知，按 id 与标题 tag 双口径）"
    else
        pool_info "本轮残留 ${POOL_RESIDUE} 条：需求(byId ${left_req}/byTag ${left_tag}) 跟单 ${left_follow} 审计 ${left_audit} 通知 ${left_notif}（按表名补删，勿用 demo-reset 掩盖）"
    fi
    return 0
}

# --------------------------------------------------------------------------- 主流程

pool_section "S 前置：栈就绪 / 租户 / 种子账号 / 域"
if ! pool_wait_ready "req-service 健康（status=UP db=up flyway）" 30 req_ready "先 make platform-up && make platform-seed"; then
    pool_abort "req-service 未就绪（/internal/health 不满足 UP/up/flyway）"
fi
if ! db_up; then
    pool_abort "MySQL 不可达：P2/P3/P4 的落库与审计断言依赖 db_q（PLATFORM_DB_HOST=${PLATFORM_DB_HOST:-127.0.0.1}）"
fi
pool_login admin || pool_abort "admin 登录失败（tenantCode=${POOL_TENANT_CODE}）"
TENANT_ID="${API_TENANT}"
PM_ID="$(pool_user_id "${TENANT_ID}" pm)"
PO1_ID="$(pool_user_id "${TENANT_ID}" po1)"
TL1_ID="$(pool_user_id "${TENANT_ID}" tl1)"
BIZ1_ID="$(pool_user_id "${TENANT_ID}" biz1)"
VENDOR2_ID="$(pool_user_id "${TENANT_ID}" vendor2)"
VPM1_ID="$(pool_user_id "${TENANT_ID}" vpm1)"
DOMAIN_ORD="$(pool_sql "SELECT id FROM biz_domain WHERE tenant_id=${TENANT_ID} AND code='ORD'")"
DOMAIN_INV="$(pool_sql "SELECT id FROM biz_domain WHERE tenant_id=${TENANT_ID} AND code='INV'")"
DEMO_R02="$(pool_req_id_by_code "${TENANT_ID}" DEMO-R-02)"
DEMO_R09="$(pool_req_id_by_code "${TENANT_ID}" DEMO-R-09)"
DEMO_R10="$(pool_req_id_by_code "${TENANT_ID}" DEMO-R-10)"
DEMO_R11="$(pool_req_id_by_code "${TENANT_ID}" DEMO-R-11)"
for pair in "pm:${PM_ID}" "po1:${PO1_ID}" "tl1:${TL1_ID}" "biz1:${BIZ1_ID}" "vendor2:${VENDOR2_ID}" "vpm1:${VPM1_ID}" \
    "domain ORD:${DOMAIN_ORD}" "domain INV:${DOMAIN_INV}"; do
    if [[ -z "${pair#*:}" ]]; then
        pool_abort "种子数据缺失：${pair%%:*} 在 tenant=${TENANT_ID} 里查不到（先 make platform-seed）"
    fi
done

build_fixtures

pool_section "P1 相似需求（契约 §3.1）"
check_p1_1
check_p1_2
check_p1_3
check_p1_4
check_p1_5
check_p1_6
check_p1_7

pool_section "P2 我也需要（契约 §3.2）"
check_p2_1
check_p2_2
check_p2_3
check_p2_4
check_p2_5
check_p2_6
check_p2_7
check_p2_8
check_p2_9

pool_section "P3 合并（契约 §3.3）"
p3_prepare
check_p3_1
check_p3_2
check_p3_3
check_p3_4
check_p3_5
check_p3_6
check_p3_7
check_p3_8
check_p3_9

pool_section "P4 回归（契约 §6 P4 / §7）"
check_p4_1
check_p4_2
check_p4_3
check_p4_4
check_p4_5
check_p4_6
check_p4_7
check_p4_8

pool_summary
exit $?
