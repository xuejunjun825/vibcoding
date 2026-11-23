#!/usr/bin/env bash
# gate-order-verify.sh — 顺序无关的机器化证明（r34，AC-5/AC-6/AC-7；r36 加 rbac；r37 加临时账号自愈与负向自检）。
#
# 为什么需要独立门禁：顺序依赖只能由「两种顺序都跑」证伪，单序列 + 人肉推理证明不了。
#   A 序列：reset → release-flow-verify → access-verify → access-verify
#   B 序列：reset → access-verify → release-flow-verify → access-verify → rbac-verify
# 每一步同时看两件事：该门禁自己的分母（80/80、CASE=44 PASS=44 FAIL=0、206/206、113/113），
# 以及 scripts/lib/gate-fixture.sh 的 gate_fixture_problems 为空（足迹/非夹具需求/演示不变量）。
#
# r36：rbac 只进 B 末尾，不进 A。rbac 段内 s8 会调两次 /internal/demo-reset（复位演示库），
# 插在序列中间会把后一步的起跑基线悄悄换掉；放尾部则「rbac 自己收得干净」由第 3 条收尾基线
# （临时主体残留 0 条）背书，A 序列的 r34 证据（rfv 之后 access 仍绿 + access 双跑幂等）原样保留。
#
# r37：临时账号口径收敛到 scripts/lib/temp-accounts.sh 一份真源（4 个白名单前缀 + 14 名保护名单 +
# 只删 status='DISABLED'）。本脚本只做三件事：起跑先自愈一次（清掉历史遗留下来的停用探针号，
# 清理失败直接 exit 1，不降级成 WARN）；第 18 条改成三个计数都为 0（白名单用户任意状态 / 停用 /
# 临时角色，数与清理共用同一个正则）；第 19 条是负向自检——插一条 probe9<时间戳+pid> 的 DISABLED
# 账号，计数必须 ≥1。没有第 19 条，正则退化成永不命中时第 18 条会永远绿，门禁静默失效。
#
# r38：临时主体卫生从账号层扩到 join 行层——第 20 条要求 sys_user_role / sys_user_domain 的
# 「同租户无主」行都为 0，并内置负向自检（插一行无主 sys_user_domain，domain 计数必须 ≥1，
# 删后回起跳值）。它只断言、不自愈：413 条 domain 孤儿是 r38 之前攒下的历史债，门禁代删会掩盖
# 「谓词又被复制后各自演化」这类回归；修复命令固定为 bash scripts/orphan-join-purge.sh --apply。
#
# 21 条 = 1 栈前置 + 6 静态契约 + 9 序列步骤 + 5 收尾基线（r37 第 4 条 / r38 第 5 条为负向自检；
# r41 第 21 条为判据防回退静态契约）。
# 它要独占演示栈、单轮 6–11 分钟，所以不进 make test（每次提交门禁），只做发布前门禁：
#   make gate-order-verify
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
# shellcheck source=lib/gate-fixture.sh
source "${ROOT}/scripts/lib/gate-fixture.sh"
# shellcheck source=lib/temp-accounts.sh
source "${ROOT}/scripts/lib/temp-accounts.sh"

GATE_FIXTURE_TENANT_CODE="${PLATFORM_TENANT:-t1}"

TOTAL_N=0
PASS_N=0
FAIL_N=0

hit() {
    TOTAL_N=$((TOTAL_N + 1)); PASS_N=$((PASS_N + 1))
    printf '  \033[1;32mOK\033[0m   %s\n' "$1"
}
miss() { # label fix
    TOTAL_N=$((TOTAL_N + 1)); FAIL_N=$((FAIL_N + 1))
    printf '  \033[1;31mFAIL\033[0m %s\n' "$1"
    [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"
}
banner() { printf '\n\033[1;34m---- %s ----\033[0m\n' "$*"; }

# 子门禁必须各自从零开始：清掉本进程导出的 GATE_TAG，否则它们会共用 gate-order 的 tag，
# 「每个门禁自己复位自己的足迹」这件事就被掩盖了。
run_gate() { # script -> stdout，$? 为该脚本退出码
    env GATE_TAG= bash "${ROOT}/scripts/$1" 2>&1
}

# ---------------------------------------------------------------- 1 栈前置
banner "0) 演示栈前置（缺栈直接失败，不做条件跳过）"
if ! req_ready; then
    err "req-service 未就绪：${REQ_URL}/internal/health —— 先 make platform-up"
    exit 1
fi
if ! deliver_ready; then
    err "deliver-service 未就绪：${DELIVER_URL}/internal/health —— 先 make platform-up"
    exit 1
fi
if ! platform_login "$GATE_FIXTURE_TENANT_CODE" admin 'Admin@12345' >/dev/null 2>&1; then
    err "admin 登录失败 —— 先 make platform-seed（或 make platform-demo-reset）"
    exit 1
fi
hit "演示栈就绪（req/deliver health + ${GATE_FIXTURE_TENANT_CODE}/admin 登录）"
gate_fixture_begin || { err "gate-fixture 起跑失败（见上方 [gate] 输出）"; exit 1; }

# ---------------------------------------------------------------- 起跑自愈（r37）
# 上一轮门禁/探针可能留下「白名单前缀 + 已停用」的账号行：Java 的 DemoFixture 只把 status 改成
# DISABLED，行仍在，真正的删除归 shell 侧。第 18 条要求三数皆 0，所以在任何段之前先清一次；
# 否则第 18 条会红在别人的残渣上，而残渣本身不是本次改动造成的。
# 清理失败必须让本门禁失败（静态第 2 条禁止 `不影响本次断言` 这类 WARN 降级措辞）。
r37_selfheal() {
    local purged
    temp_accounts_db_ready || { err "r37 自愈：MySQL 不可连 —— 先 make platform-up"; return 1; }
    temp_accounts_guard || { err "r37 自愈：保护名单守卫不成立，拒绝清理（见上方 [temp-accounts] 输出）"; return 1; }
    purged="$(temp_purge_disabled)" \
        || { err "r37 自愈：清理停用临时账号失败（见上方 [temp-accounts] 输出）"; return 1; }
    printf '  r37 自愈：purged=%s（白名单前缀 + DISABLED；14 名保护账号不动）\n' "${purged}"
    return 0
}
r37_selfheal || exit 1

# ---------------------------------------------------------------- 2-5 静态契约
banner "1) 静态契约（6）"

GATES=("release-flow-verify.sh" "platform-verify.sh" "platform-test-negative.sh")

bad=""
for g in "${GATES[@]}"; do
    f="${ROOT}/scripts/${g}"
    { grep -q 'lib/gate-fixture.sh' "$f" && grep -q 'gate_fixture_begin' "$f"; } || bad+="${g} "
    # D-3：不许再「按状态取第一条种子 PDR」当父（negative 旧 :97 的写法会把夹具挂到 DEMO-R-02 上，
    # 把汇总从 5.00 推高并让评估单 STALE）——父链必须自建 gate-<tag>-BUS→POOLED→PDR。
    grep -qE "type='PDR' AND status IN \('SCHEDULED','IN_DEV'\) ORDER BY id" "$f" \
        && bad+="${g}:按状态取种子PDR "
done
if [[ -z "$bad" ]]; then
    hit "三门禁接入共享钩子，且不按状态取种子 PDR（父链自建）"
else
    miss "未接入共享钩子或仍按状态取种子 PDR: ${bad}" "source scripts/lib/gate-fixture.sh + gate_fixture_begin；父链用自建 gate-<tag>-BUS→POOLED→PDR"
fi

bad=""
for g in "${GATES[@]}"; do
    f="${ROOT}/scripts/${g}"
    grep -qE "platform-demo-reset\.sh\" >/dev/null" "$f" && bad+="${g}:静默reset "
    grep -q '不影响本次断言' "$f" && bad+="${g}:WARN降级 "
done
if [[ -z "$bad" ]]; then
    hit "三门口禁都无 >/dev/null 静默 reset / WARN 降级（清理失败只能置红）"
else
    miss "仍有静默清理: ${bad}" "复位只走 gate_fixture_reset；失败必须把退出码变差，不能只打 WARN"
fi

if grep -q '^TOTAL_CHECKS=80' "${ROOT}/scripts/platform-demo-reset.sh" \
    && grep -q 'NON_FIXTURE_REQS' "${ROOT}/scripts/platform-demo-reset.sh" \
    && grep -q "NOT LIKE 'DEMO-R-%'" "${ROOT}/scripts/platform-demo-reset.sh"; then
    hit "干净基线单点定义：demo-reset TOTAL_CHECKS=80 且含「非夹具需求 0 条」"
else
    miss "demo-reset 分母/断言缺失" "TOTAL_CHECKS 必须为 80（r39 起含 4 条自动化复核），并含 NON_FIXTURE_REQS（code NOT LIKE 'DEMO-R-%' 期望 0）"
fi

if grep -q '^gate-order-verify:' "${ROOT}/Makefile" \
    && grep -q 'gate-order-verify: ## ' "${ROOT}/Makefile" \
    && make -C "${ROOT}" help 2>/dev/null | grep -q 'gate-order-verify'; then
    hit "make gate-order-verify 可发现（目标 + ## 帮助行）"
else
    miss "Makefile 未暴露 gate-order-verify" "加进 .PHONY，目标体 @./scripts/gate-order-verify.sh，并用 ## 写一行帮助"
fi

# r36 第 5 条：临时主体的造名与清理必须收敛到 scripts/tests/rbac/_lib.sh 的三个原语。
# 造名 = rbac_run_tag（全数字 tag，让 s7u1<tag> 仍落进 demo-reset 的 TEMP_ACTIVE 正则），
# 清理 = rbac_purge_users（先删用户）+ rbac_purge_roles（再删角色，RoleAdminService.delete 只认「非内置 + 用户数 0」）。
# 段内不许再有 $RANDOM 造名：它和 demo-reset 的停用正则各自演化过一次，s7a*/s7b* 两边都不匹配。
RBAC_SEGS=("s3-decision-order.sh" "s5-role-lockout.sh" "s6-user-org.sh" "s7-negative-nine.sh" "s8-audit-reset.sh")
bad=""
for g in "${RBAC_SEGS[@]}"; do
    f="${ROOT}/scripts/tests/rbac/${g}"
    [[ -f "$f" ]] || { bad+="${g}:缺失 "; continue; }
    grep -q 'rbac_run_tag' "$f" || bad+="${g}:缺rbac_run_tag "
    grep -q 'rbac_purge_users' "$f" || bad+="${g}:缺rbac_purge_users "
    grep -q 'trap cleanup EXIT' "$f" || bad+="${g}:缺trap "
    cnt="$(grep -c '\$RANDOM' "$f" || true)"
    [[ "${cnt:-0}" == "0" ]] || bad+="${g}:仍有\$RANDOM(${cnt}处) "
done
# 只有造角色的段才需要 rbac_purge_roles（s3/s6 不造角色，它们只造用户/组织）。
for g in s5-role-lockout.sh s7-negative-nine.sh s8-audit-reset.sh; do
    grep -q 'rbac_purge_roles' "${ROOT}/scripts/tests/rbac/${g}" || bad+="${g}:缺rbac_purge_roles "
done
grep -q 'rbac_run_tag' "${ROOT}/scripts/tests/rbac/_lib.sh" || bad+="_lib.sh:缺rbac_run_tag "
# r37（并入第 5 条，不增条数）：临时账号的白名单/保护名单/清理谓词只许有 scripts/lib/temp-accounts.sh
# 一份真源。造名与删除各自复制一份正则，就会演变成「门禁查的」与「清理删的」不是同一个口径
# （r36 之前 527 条残留正是这么攒出来的）；再坏一点是正则退化成永不命中，门禁静默变绿。
# 自匹配陷阱：下面两个 grep 模式写成 t6[p] / s3[|]s5，否则本行自己就会被自己的 grep 命中。
R37_LIB="${ROOT}/scripts/lib/temp-accounts.sh"
[[ -f "$R37_LIB" ]] || bad+="temp-accounts.sh:缺失 "
grep -q 'TEMP_ACCOUNT_PATTERNS=(' "$R37_LIB" || bad+="temp-accounts.sh:缺白名单数组 "
# 四个前缀逐个认。两个 t6 前缀写成 t6[p] / t6[p]robe：字面量会自己命中下面那条
# 「消费方不得有旧正则副本」的扫描，而这个文件就是消费方之一（自匹配陷阱）。
grep -qF -- 's[3-8]' "$R37_LIB" || bad+="白名单缺 s[3-8] "
grep -qE 't6[p]' "$R37_LIB" || bad+="白名单缺 t6 短前缀族 "
grep -qE 't6[p]robe' "$R37_LIB" || bad+="白名单缺 t6 长前缀族 "
grep -qF -- 'probe' "$R37_LIB" || bad+="白名单缺 probe "
if declare -p TEMP_PROTECTED_USERS >/dev/null 2>&1; then
    [[ "${#TEMP_PROTECTED_USERS[@]}" == "14" ]] || bad+="保护名单 ${#TEMP_PROTECTED_USERS[@]}/14 "
else
    bad+="temp-accounts.sh:保护名单未加载 "
fi
grep -q "status = 'DISABLED'" "$R37_LIB" || bad+="temp-accounts.sh:缺 status = 'DISABLED' 谓词 "
for g in gate-order-verify.sh platform-demo-reset.sh; do
    n="$(grep -cE 't6[p]|s3[|]s5' "${ROOT}/scripts/${g}" || true)"
    [[ "${n:-0}" == "0" ]] || bad+="${g}:旧正则字面量(${n}处) "
done
if [[ -z "$bad" ]]; then
    hit "rbac 五段造名/清理走 _lib.sh 原语、全部有 trap、无 \$RANDOM 造名（s5/s7/s8 另用 purge_roles）；临时账号单一真源 temp-accounts.sh（4 前缀 / 14 名保护 / 只删 DISABLED，消费方无旧正则副本）"
else
    miss "rbac 段未收敛到 _lib.sh 或临时账号正则出现了副本: ${bad}" \
        "source tests/rbac/_lib.sh；造名用 rbac_run_tag；cleanup 里先 rbac_purge_users 再 rbac_purge_roles；\`trap cleanup EXIT\` 必须存在；白名单/保护/清理谓词只写在 scripts/lib/temp-accounts.sh"
fi

# 第 21 条（r41）：判据不许退回「写死允许版本号」。只读 rfv 源码、不运行 rfv，A/B 双序列语义零影响；
# 四组断言先累加到 bad 再一次性 hit/miss（任一 grep 直接 return 会把「已失败」洗成「没跑」）。
bad=""
if grep -qE '只允许新增 V1[0-9]' "${ROOT}/scripts/release-flow-verify.sh"; then
    bad+="旧文案(只允许新增 V1x) "
fi
if grep -qE 'mig_allowed=.*V[0-9]+__' "${ROOT}/scripts/release-flow-verify.sh"; then
    bad+="写死允许文件名(mig_allowed) "
fi
if ! grep -q 'mig_max_version' "${ROOT}/scripts/release-flow-verify.sh"; then
    bad+="缺 mig_max_version "
fi
if ! grep -q 'ls-tree -r' "${ROOT}/scripts/release-flow-verify.sh"; then
    bad+="缺 ls-tree -r（版本上界未从基线树派生） "
fi
for i in 1 2 3 4 5; do
    grep -q "HYG_SELF\[$i/5\]" "${ROOT}/scripts/release-flow-verify.sh" || bad+="缺 HYG_SELF[$i/5] "
done
if [[ -z "$bad" ]]; then
    hit "release-flow-verify 第 32 条判据：版本上界由 mig_max_version 从基线提交树（ls-tree -r）派生，无写死的允许版本/文件名，5 条自检标记齐备（r41 上锁，防判据退回硬编码）"
else
    miss "release-flow-verify 迁移判据退化: ${bad}" \
        "迁移判据必须 mig_max_version + ls-tree -r 动态派生；不得出现『只允许新增 V1x』旧文案或 mig_allowed= 写死文件名；自检 5 条标记 HYG_SELF[1/5]..[5/5] 必须是字面量"
fi

# ---------------------------------------------------------------- 8-16 双序列
# 每一步 = 该门禁自己的分母 + gate_fixture_problems 为空；两者任何一个不成立都判该步红。
run_reset() { # label
    local label="$1" out rc m a b probs
    out="$(bash "${ROOT}/scripts/platform-demo-reset.sh" 2>&1)"; rc=$?
    m="$(printf '%s' "$out" | grep -oE 'platform-demo-reset: [0-9]+/[0-9]+' | tail -n 1)"
    a="${m##*: }"; b="${a##*/}"; a="${a%%/*}"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ -n "$m" && "$a" == "$b" && "$a" -ge 76 && -z "$probs" ]]; then
        hit "${label}：reset ${a}/${b} 且基线干净"
    else
        miss "${label}：reset 未到 ${a:-?}/${b:-?}（rc=${rc}）或基线不干净" \
            "问题: ${probs:-<无>}；末尾输出:
$(printf '%s' "$out" | tail -n 20)"
    fi
}

run_rfv() { # label
    local label="$1" out rc line probs
    out="$(run_gate release-flow-verify.sh)"; rc=$?
    line="$(printf '%s' "$out" | grep -E '^RELEASE_FLOW_TOTAL=' | tail -n 1)"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ "$line" == *"RELEASE_FLOW_TOTAL=44 CASE=44 PASS=44 FAIL=0"* && -z "$probs" ]]; then
        hit "${label}：release-flow-verify 44/44 且基线干净"
    else
        miss "${label}：release-flow-verify 未达 44/44（rc=${rc}, ${line:-无计数行}）或基线不干净" \
            "问题: ${probs:-<无>}；末尾输出:
$(printf '%s' "$out" | tail -n 20)"
    fi
}

run_access() { # label
    local label="$1" out rc m a b probs
    out="$(run_gate access-verify.sh)"; rc=$?
    m="$(printf '%s' "$out" | grep -oE '验收通过：[0-9]+/[0-9]+' | tail -n 1)"
    a="${m#*：}"; b="${a##*/}"; a="${a%%/*}"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ -n "$m" && "$a" == "$b" && "$a" -ge 206 && -z "$probs" ]]; then
        hit "${label}：access-verify ${a}/${b} 且基线干净"
    else
        miss "${label}：access-verify 未到 ${a:-?}/${b:-?}（rc=${rc}）或基线不干净" \
            "问题: ${probs:-<无>}；末尾输出:
$(printf '%s' "$out" | tail -n 20)"
    fi
}

# rbac 是写门禁（造用户/角色/组织，s8 段内还会复位演示库），所以只放在 B 的最后一步：
# 分母取 rbac-verify 的末行 `rbac-verify: N/M`，下限 113（r36 前全段总数，删断言即红），
# 并要求 PASS==TOTAL、rc==0、gate_fixture_problems 为空（rbac 收干净后基线必须仍干净）。
run_rbac() { # label
    local label="$1" out rc m a b probs
    out="$(env GATE_TAG= bash "${ROOT}/scripts/rbac-verify.sh" 2>&1)"; rc=$?
    m="$(printf '%s' "$out" | grep -oE 'rbac-verify: [0-9]+/[0-9]+' | tail -n 1)"
    a="${m##*: }"; b="${a##*/}"; a="${a%%/*}"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ -n "$m" && "$a" == "$b" && "$a" -ge 113 && -z "$probs" ]]; then
        hit "${label}：rbac-verify ${a}/${b} 且基线干净"
    else
        miss "${label}：rbac-verify 未到 ${a:-?}/${b:-?}（rc=${rc}）或基线不干净" \
            "问题: ${probs:-<无>}；末尾输出:
$(printf '%s' "$out" | tail -n 20)"
    fi
}

banner "2) A 序列：reset → rfv → access → access"
run_reset "A1 起跑基线"
run_rfv "A2 rfv（首个写门禁）"
run_access "A3 access（rfv 之后）"
run_access "A4 access 重复（只读门禁幂等）"

banner "3) B 序列：reset → access → rfv → access → rbac"
run_reset "B1 起跑基线"
run_access "B2 access（rfv 之前）"
run_rfv "B3 rfv（access 之后）"
run_access "B4 access（rfv 之后）"
run_rbac "B5 rbac（access+rfv 之后，全段）"

# ---------------------------------------------------------------- 17-21 收尾基线
banner "4) 收尾基线（5）"
TID2="$(db_q "SELECT id FROM sys_tenant WHERE code='${GATE_FIXTURE_TENANT_CODE}'" 2>/dev/null | head -n1)"
NONFIX="$(db_count requirement "tenant_id=${TID2} AND code NOT LIKE 'DEMO-R-%'")"
TAGLEFT="0"
if [[ -n "$GATE_TAG" ]]; then
    TAGLEFT="$(db_count requirement "tenant_id=${TID2} AND title LIKE '%${GATE_TAG}%'")"
fi
# r35：S7 改为自造夹具后，租户下不得再留任何 S7- 前缀的需求（种子数据里没有这个前缀）。
S7LEFT="$(db_count requirement "tenant_id=${TID2} AND title LIKE '%S7-%'")"
if [[ "$NONFIX" == "0" && "$TAGLEFT" == "0" && "$S7LEFT" == "0" ]]; then
    hit "收尾：非夹具需求 0 条、本轮 tag 残留 0 条、S7- 痕迹 0 条"
else
    miss "收尾残留：非夹具 ${NONFIX} 条 / 本 tag ${TAGLEFT} 条 / S7- ${S7LEFT} 条" \
        "两种顺序跑完必须回到同一张干净基线；残留说明某一步的 teardown 没跑或失败被吞（S7 夹具由 s7_reset 自己清）"
fi

R02="$(db_q "SELECT CONCAT(FORMAT(estimate_days,2),'|',(SELECT COUNT(*) FROM wf_instance w WHERE w.tenant_id=${TID2} AND w.biz_type='WBS_ESTIMATE' AND w.biz_id=requirement.id AND w.status='APPROVED')) FROM requirement WHERE tenant_id=${TID2} AND code='DEMO-R-02'" 2>/dev/null | head -n1)"
R04="$(db_q "SELECT COUNT(*) FROM requirement r JOIN \`release\` v ON v.id=r.release_id WHERE r.tenant_id=${TID2} AND r.code='DEMO-R-04' AND v.code='DEMO-REL-1'" 2>/dev/null | head -n1)"
if [[ "$R02" == "5.00|1" && "$R04" == "1" ]]; then
    hit "收尾：DEMO-R-02 汇总 5.00 + 评估单 APPROVED、DEMO-R-04 仍在 DEMO-REL-1"
else
    miss "收尾演示不变量漂移：R-02='${R02:-<缺失>}'（期望 5.00|1）R-04='${R04:-<缺失>}'（期望 1）" \
        "门禁夹具必须挂在自建父链上；挂到种子会把 DEMO-R-02 的汇总推高并让评估单 STALE"
fi

# r37 第 3 条：临时主体必须 0 条，口径全部来自 scripts/lib/temp-accounts.sh（唯一正则生成点）。
# 三个数分别挡三种漏法：
#   ① 任意状态的用户（全库）：只清了一部分、或清完又被造出来；
#   ② 停用（全库）：Java 的 DemoFixture 只改 status 不删行，历史遗留全落在这里（r37 首清 4 条：
#      r18 早期探针 3 条 + 11-17 的 probe11272）；
#   ③ 临时角色（租户内）：S5/S7/S8 自建角色——RoleAdminService.delete 只认「非内置 + 用户数 0」，
#      所以必须先删用户再删角色，否则 ROLE_IN_USE 409 会吞掉删除。
# 断言与清理共用同一个 TEMP_ACCOUNT_REGEX，杜绝「门禁查的」与「清理删的」各自演化；
# 计数走 _temp_count 的 ^[0-9]+$ 校验，DB 不可连时不会被当成 0（假绿）。
U_LEFT="$(temp_users_left)" || U_LEFT="ERR"
D_LEFT="$(temp_disabled_left)" || D_LEFT="ERR"
R_LEFT="$(temp_roles_left)" || R_LEFT="ERR"
if [[ "$U_LEFT" == "0" && "$D_LEFT" == "0" && "$R_LEFT" == "0" ]]; then
    hit "收尾：白名单用户 0 条（任意状态、全库）、停用 0 条（全库）、临时角色 0 条（${GATE_FIXTURE_TENANT_CODE}）"
else
    left_names="$(temp_disabled_usernames 2>/dev/null | head -5 | tr '\n' ' ')"
    miss "收尾残留：白名单用户 ${U_LEFT:-?} 条（期望 0）/ 停用 ${D_LEFT:-?} 条（期望 0）/ 临时角色 ${R_LEFT:-?} 条（期望 0）" \
        "残余示例：${left_names:-<无停用号>}；修复：bash scripts/legacy-probe-purge.sh（先看它打印的正则与保护名单）后再复跑本门禁"
fi

# r37 第 4 条：负向自检——证明上面那条真的在测正则，而不是自证清白。
# 做法：插一条白名单前缀（probe9<时间戳+pid>）且 status='DISABLED' 的账号，停用计数必须从 0 变 ≥1，
# 然后删掉、再断言回到 0。正则一旦退化成永不命中（锚点写错、前缀漏掉 probe），这里必红。
# name / password_hash 是 NOT NULL，不带值插入会 1364 报错——必须给非空值（TECHNICAL §10-8）。
# 清理三路：INT/TERM 显式兜底 + 条末主动删 + 夹具 teardown（EXIT → demo-reset → 同一清理）。
# 刻意不再注册 EXIT trap：那会顶掉 gate_fixture 的 EXIT trap，静态第 1 条会红。
r37_tag="$(date +%m%d%H%M%S)$$"
r37_probe="probe9${r37_tag}"
r38_uid="9${r37_tag}"   # r38 负向自检：无主 join 行的 user_id，9 前缀远离真实自增 id
r37_probe_cleanup() { # 幂等：先 join 行再主表（名字落在白名单正则内、状态 DISABLED，清理也能兜住）
    db_q "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id WHERE u.username = '${r37_probe}'" >/dev/null 2>&1 || true
    db_q "DELETE ud FROM sys_user_domain ud JOIN sys_user u ON u.id = ud.user_id AND u.tenant_id = ud.tenant_id WHERE u.username = '${r37_probe}'" >/dev/null 2>&1 || true
    db_q "DELETE FROM sys_user WHERE username = '${r37_probe}'" >/dev/null 2>&1 || true
    # r38：无主 domain 行没有对应账号，只能按 user_id 删。并轨在这里是为了不注册第二个 EXIT trap
    # （第二个 trap 会顶掉 gate_fixture 的收尾，静态第 1 条当场红）。${r38_uid:-} 兜 set -u。
    if [[ -n "${r38_uid:-}" ]]; then
        db_q "DELETE FROM sys_user_domain WHERE user_id = ${r38_uid}" >/dev/null 2>&1 || true
    fi
}
trap 'r37_probe_cleanup; exit 130' INT
trap 'r37_probe_cleanup; exit 143' TERM

r37_bad=""
r37_d0="$(temp_disabled_left)" || r37_d0="ERR"
[[ "$r37_d0" == "0" ]] || r37_bad+="①起跳计数已非 0(${r37_d0}) "

r37_ins_rc=0
db_q "INSERT INTO sys_user (tenant_id, username, name, password_hash, status, created_by, updated_by) VALUES (${TID2}, '${r37_probe}', 'r37 gate-order probe', 'r37probehashunused', 'DISABLED', NULL, NULL)" >/dev/null 2>&1 \
    || r37_ins_rc=$?
(( r37_ins_rc == 0 )) || r37_bad+="②插入失败(rc=${r37_ins_rc}) "

r37_d1="$(temp_disabled_left)" || r37_d1="ERR"
if [[ "${r37_d1:-x}" =~ ^[0-9]+$ ]] && (( r37_d1 >= 1 )); then
    :
else
    r37_bad+="③插入后计数未增(${r37_d1}，期望 ≥1) "
fi

r37_probe_cleanup
r37_d2="$(temp_disabled_left)" || r37_d2="ERR"
[[ "$r37_d2" == "0" ]] || r37_bad+="④清理后仍非 0(${r37_d2}) "

if [[ -z "$r37_bad" ]]; then
    hit "负向自检：插入 ${r37_probe}（DISABLED）后计数 ${r37_d0}→${r37_d1}，清理后回 ${r37_d2}（正则没有退化成永不命中）"
else
    miss "负向自检失败：${r37_bad}" \
        "③ 计数未增=TEMP_ACCOUNT_PATTERNS 里少了能命中 ${r37_probe} 的前缀（probe）；② 插入失败=sys_user 的 name/password_hash 非空约束；④ 清理后仍有=DATABASE 里有并发写入"
fi

# r38 第 5 条：孤儿 join 行归零 + 内置负向自检（只断言，不自愈）。
# 判据与删除同源：两个计数都来自 temp_orphan_join_where 渲染的同一条谓词；这里只对
# temp_orphan_join_left 的整行输出做整串比较，不 awk/cut 取 token（半行会被当合法值）。
# 413 条 domain 孤儿是 r38 之前攒下的历史债：门禁只报红并指路 orphan-join-purge.sh --apply，
# 代删会掩盖「谓词又被复制后各自演化」这类回归（r36 的 527 条就是这么攒出来的）。
r38_bad=""
r38_join="$(temp_orphan_join_left)" || r38_join="ERR"
[[ "$r38_join" == 'orphans_role=0 orphans_domain=0' ]] \
    || r38_bad+="①残留 ${r38_join:-ERR}（期望 orphans_role=0 orphans_domain=0） "

# domain_id 取 biz_domain（TECH-CHANGE 里的 sys_domain 不存在）；该表无外键，取不到也不能跳过自检，退化 1。
r38_domain_id=""
if [[ -n "${TID2:-}" ]]; then
    r38_domain_id="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID2} ORDER BY id LIMIT 1" 2>/dev/null | head -n1)"
fi
[[ "${r38_domain_id:-}" =~ ^[0-9]+$ ]] || r38_domain_id=1

r38_d0="$(temp_orphan_domain_left)" || r38_d0="ERR"
r38_ins_rc=0
db_q "INSERT INTO sys_user_domain (tenant_id, user_id, domain_id) VALUES (${TID2:-0}, ${r38_uid}, ${r38_domain_id})" >/dev/null 2>&1 \
    || r38_ins_rc=$?
(( r38_ins_rc == 0 )) || r38_bad+="②插入无主 join 行失败(rc=${r38_ins_rc}) "

r38_d1="$(temp_orphan_domain_left)" || r38_d1="ERR"
if [[ "${r38_d1:-x}" =~ ^[0-9]+$ ]] && (( r38_d1 >= 1 )); then
    :
else
    r38_bad+="③插入后 domain 计数未增(${r38_d1}，期望 ≥1) "
fi

r37_probe_cleanup   # 同一把伞：user 探针 + r38 domain 行一起走
r38_d2="$(temp_orphan_domain_left)" || r38_d2="ERR"
# 回起跳值而不是硬编码 0：历史债未清时 ① 已红，这里只证「本探针删得掉」，两种红不混在一起。
[[ "${r38_d2:-x}" == "${r38_d0:-y}" ]] || r38_bad+="④清理后未回起跳值(${r38_d2}，期望 ${r38_d0}) "

if [[ -z "$r38_bad" ]]; then
    hit "收尾：孤儿 join 行 role=0 domain=0（负向自检 ${r38_d0}→${r38_d1}→${r38_d2}，谓词没有退化成永不命中）"
else
    miss "孤儿 join 行检查失败：${r38_bad}" \
        "修复：bash scripts/orphan-join-purge.sh --apply（默认 dry-run 只报数；删的是 sys_user_role / sys_user_domain 的无主行，不动账号）后复跑本门禁"
fi

# ---------------------------------------------------------------- 汇总
printf '\n'
if (( FAIL_N == 0 && TOTAL_N == 21 )); then
    ok "gate-order-verify: 21/21 —— A/B 两种顺序跑完基线一致（门禁顺序无关，临时主体口径单一真源）"
    exit 0
fi
err "gate-order-verify: ${PASS_N}/${TOTAL_N}（声明 21 条）—— 失败 ${FAIL_N} 条"
err "修复方向：先看每条 FAIL 上方的问题清单（问题: ...）；残留类问题执行 bash scripts/legacy-probe-purge.sh 或 make platform-demo-reset 后再跑"
exit 1
