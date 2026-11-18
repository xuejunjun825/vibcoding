#!/usr/bin/env bash
# release-flow-upgrade.sh — r30 存量库幂等升级门禁（AC-4）。
#
# 做法：对已起的栈连跑两次 POST /internal/seed，断言
#   - 4 条分级定义（RELEASE 三级 + 一条 REQ_DEPLOY 复用于三个等级）、6 个绑定槽
#     在两次执行后计数不增（ensure 语义，不是「先删再插」）；
#   - 每条分级定义的 version 没有被顶掉（老定义不覆盖）；
#   - 绑定槽保持 6 条且仍可读（自定义绑定不被种子回滚）；
#   - 两次 seed 的 counts 完全一致。
#
# 这是「已上线环境执行一次 /internal/seed 即完成升级，再执行一次不产生变化」的机器证明；
# 老库没有这些表时由 Flyway V14 建表，本脚本只验证数据层幂等。
#
# 用法：bash scripts/release-flow-upgrade.sh   （需 make platform-up 已起栈）
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
set +e

UPGRADE_TOTAL=12
CASE=0
PASS=0
FAIL=0

pass() { CASE=$((CASE + 1)); PASS=$((PASS + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$*"; }
fail() {
    CASE=$((CASE + 1)); FAIL=$((FAIL + 1))
    printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$1"
    [[ -n "${2:-}" ]] && printf '        修复: %s\n' "$2"
}
jqv() { printf '%s' "${2:-}" | jq -r "$1" 2>/dev/null; }

printf '\n\033[1;36m== r30 幂等升级：连跑两次 /internal/seed ==\033[0m\n'
need_cmd curl || die "缺少 curl"
need_cmd jq || die "缺少 jq"
need_cmd mysql || die "缺少 mysql 客户端"

req_ready || die "req-service 未就绪：${REQ_URL}/internal/health —— 先 make platform-up"
db_up || die "MySQL 未就绪：${PLATFORM_DB_URL} —— 先 make platform-up"

TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${T1_TENANT:-t1}' LIMIT 1")"
[[ -n "$TID" ]] || die "租户 t1 不存在 —— 先 make platform-seed"

platform_login "${T1_TENANT:-t1}" admin 'Admin@12345' >/dev/null 2>&1 \
    || die "admin 登录失败 —— 检查种子账号 admin/Admin@12345"

DEF_NAMES="'版本发布-常规三级','版本发布-紧急两级','版本发布-回滚两级','发布单审批-两级'"
defs_before="$(db_count wf_definition "tenant_id=${TID} AND name IN (${DEF_NAMES})")"
bind_before="$(db_count wf_flow_binding "tenant_id=${TID}")"
versions_before="$(db_q "SELECT GROUP_CONCAT(CONCAT(name, ':', version) ORDER BY name)
    FROM wf_definition WHERE tenant_id=${TID} AND name IN (${DEF_NAMES})")"

# 口径：DEFAULTS 是 4 条定义（RELEASE NORMAL/EMERGENCY/ROLLBACK 各一条 + REQ_DEPLOY 一条，
# 后者被三个 REQ_DEPLOY 等级共用），不是 6 条；绑定槽才是 6 条。断言与定义表同一口径。
if [[ "${defs_before:-0}" -eq 4 ]]; then
    pass "升级前已存在 ${defs_before} 条分级定义（RELEASE 三级 + 一条 REQ_DEPLOY 复用）"
else
    fail "升级前分级定义是 ${defs_before:-0} 条（应为 4）" \
        "先 POST /internal/seed 一次完成初次落地；若 404 说明 PLATFORM_SEED_ENABLED=false"
fi
# 绑定行数不是恒 6：管理员 DELETE reset 一个槽后该行被删（设计取舍：删行即回默认，不抄一份默认 id），
# 此后 GET 仍返回 6 个虚拟槽、seed 会把它补回来。所以「升级前」只要求 1..6 且无重复键；
# 「升级后」才是 6（seed 必须补齐缺失的默认绑定）。
if [[ "${bind_before:-0}" -ge 1 && "${bind_before:-0}" -le 6 ]]; then
    pass "升级前绑定行 ${bind_before} 条（≤6；被 reset 的槽没有行，GET 仍补虚拟槽）"
else
    fail "升级前绑定行是 ${bind_before:-0} 条（应在 1..6）" \
        "FlowChainSeedService 为每个 bizType×level 写一行；多出来的行说明键不唯一"
fi

seed_once() {
    internal_call POST "$REQ_URL" /internal/seed
    if [[ "$HTTP_CODE" != "200" ]]; then
        err "POST /internal/seed 失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        err "修复: PLATFORM_SEED_ENABLED=true 才会注册该端点（platform-up 已设置）；404 表示被关闭"
        exit 1
    fi
    json_ok "$HTTP_BODY" || { err "seed 响应不是 JSON: $(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
}

seed_once
FIRST="$HTTP_BODY"
FIRST_CODE="$HTTP_CODE"
pass "第一次 POST /internal/seed 200（升级入口走既有幂等种子，不另写升级脚本）"

seed_once
SECOND="$HTTP_BODY"
SECOND_CODE="$HTTP_CODE"
pass "第二次 POST /internal/seed 200（重复执行不报错）"

C1="$(printf '%s' "$FIRST" | jq -cS '.counts // empty' 2>/dev/null)"
C2="$(printf '%s' "$SECOND" | jq -cS '.counts // empty' 2>/dev/null)"
if [[ -n "$C1" && "$C1" == "$C2" ]]; then
    pass "两次 seed 的 counts 完全一致：${C1}"
else
    fail "两次 seed 计数不一致：${C1:-<缺失>} != ${C2:-<缺失>}" \
        "SeedService 的 ensure 语义要求二次执行不新增；flow-chain 种子同样只 ensure"
fi

defs_after="$(db_count wf_definition "tenant_id=${TID} AND name IN (${DEF_NAMES})")"
bind_after="$(db_count wf_flow_binding "tenant_id=${TID}")"
versions_after="$(db_q "SELECT GROUP_CONCAT(CONCAT(name, ':', version) ORDER BY name)
    FROM wf_definition WHERE tenant_id=${TID} AND name IN (${DEF_NAMES})")"

if [[ "${defs_after:-0}" == "${defs_before:-0}" ]]; then
    pass "两次执行后分级定义数不变（${defs_before} → ${defs_after}）"
else
    fail "分级定义数变化（${defs_before} → ${defs_after}）" \
        "FlowChainSeedService.seed 必须先按 name+tenant 查存在，存在即跳过，不能每次新建定义"
fi
if [[ "${bind_after:-0}" == "6" ]]; then
    pass "两次执行后绑定行恰为 6 条（${bind_before} → ${bind_after}，缺失的默认槽被 ensure 补齐、未重复插入）"
else
    fail "绑定行数为 ${bind_after:-0}（期望 6）" \
        "绑定按 uk_flow_binding(tenant,biz_type,release_level) 去重；重复 seed 必须命中同一行"
fi
if [[ -n "$versions_before" && "$versions_before" == "$versions_after" ]]; then
    pass "分级定义 version 未被顶掉（${versions_after}）"
else
    fail "定义 version 变化：${versions_before:-<空>} → ${versions_after:-<空>}" \
        "种子不得把已发布定义再出版本；老定义不删不覆盖（在途实例按 definitionVersion 走完）"
fi

api_call GET /api/req/admin/flow-bindings
SLOTS="$HTTP_BODY"
SLOT_LEN="$(printf '%s' "$SLOTS" | jq -r 'length' 2>/dev/null)"
if [[ "$HTTP_CODE" == "200" && "$SLOT_LEN" == "6" ]]; then
    pass "GET /admin/flow-bindings 仍返回 6 槽（升级后配置面可读）"
else
    fail "绑定页读取异常（HTTP ${HTTP_CODE}, len=${SLOT_LEN:-?}）" \
        "slots 对未配置槽补虚拟行，恒定 6 条"
fi

# 槽内定义必须仍是 PUBLISHED（种子 upsert 不能把绑定指到草稿）
DRAFT_BIND="$(db_q "SELECT COUNT(*) FROM wf_flow_binding b
    JOIN wf_definition d ON d.id = b.definition_id
    WHERE b.tenant_id=${TID} AND d.status <> 'PUBLISHED'")"
if [[ "${DRAFT_BIND:-0}" == "0" ]]; then
    pass "6 个槽指向的定义全部仍是 PUBLISHED（没有把绑定指到草稿）"
else
    fail "${DRAFT_BIND} 个绑定槽指向非 PUBLISHED 定义" \
        "save/resolve 只接受 PUBLISHED；种子绑定同理"
fi

# RELEASE/NORMAL 槽仍在（删除后种子不重建是允许的，但默认库升级后必须在）
NORMAL_SLOT="$(db_count wf_flow_binding "tenant_id=${TID} AND biz_type='RELEASE' AND release_level='NORMAL'")"
if [[ "${NORMAL_SLOT:-0}" == "1" ]]; then
    pass "RELEASE/NORMAL 槽存在且唯一（升级后常规三级链开箱可用）"
else
    fail "RELEASE/NORMAL 槽计数 ${NORMAL_SLOT:-0}（应为 1）" \
        "uk_flow_binding 保证一槽一行；种子必须补默认槽"
fi

# 发布单两级链的 3 个等级槽在升级后仍各是 2 节点（未被种子改宽/改窄）
DEPLOY_BAD="$(printf '%s' "$SLOTS" | jq -r '[.[] | select(.bizType=="REQ_DEPLOY" and ((.nodes|length) != 2))] | length' 2>/dev/null)"
if [[ "${DEPLOY_BAD:-1}" == "0" ]]; then
    pass "REQ_DEPLOY 三个等级槽仍各为两级链（升级不改链形状）"
else
    fail "REQ_DEPLOY 槽形状漂移（异常槽数=${DEPLOY_BAD:-?}）" \
        "种子对已存在的绑定只 ensure，不覆盖用户/默认链内容"
fi

# 全量种子计数里分级相关键存在（Next SeedService 把 flowChainSeed 计入 counts）
FLOW_COUNT_KEY="$(printf '%s' "$FIRST" | jq -r '.counts | keys[]? | select(test("flow|binding|workflow"; "i"))' 2>/dev/null | head -3 | tr '\n' ' ')"
# 该键非必需（计数键名由实现决定），只作为可读诊断，不计入断言
[[ -n "$FLOW_COUNT_KEY" ]] && printf '  \033[1;36mINFO\033[0m seed counts 含：%s\n' "$FLOW_COUNT_KEY"

printf '\nRELEASE_FLOW_UPGRADE_TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$UPGRADE_TOTAL" "$CASE" "$PASS" "$FAIL"
if ((CASE != UPGRADE_TOTAL)); then
    printf '\033[1;31m断言数与分母不一致：CASE=%d ≠ TOTAL=%d\033[0m\n' "$CASE" "$UPGRADE_TOTAL"
    exit 1
fi
if ((FAIL > 0)); then
    printf '\033[1;31m幂等升级有 %d 条不成立\033[0m\n' "$FAIL"
    exit 1
fi
printf '\033[1;32m升级幂等成立：两次 seed 不增定义/不增槽/不动 version\033[0m\n'
exit 0
