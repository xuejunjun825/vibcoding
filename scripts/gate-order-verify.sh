#!/usr/bin/env bash
# gate-order-verify.sh — 顺序无关的机器化证明（r34，AC-5/AC-6/AC-7；r36 加 rbac）。
#
# 为什么需要独立门禁：顺序依赖只能由「两种顺序都跑」证伪，单序列 + 人肉推理证明不了。
#   A 序列：reset → release-flow-verify → access-verify → access-verify
#   B 序列：reset → access-verify → release-flow-verify → access-verify → rbac-verify
# 每一步同时看两件事：该门禁自己的分母（75/75、CASE=44 PASS=44 FAIL=0、206/206、113/113），
# 以及 scripts/lib/gate-fixture.sh 的 gate_fixture_problems 为空（足迹/非夹具需求/演示不变量）。
#
# r36：rbac 只进 B 末尾，不进 A。rbac 段内 s8 会调两次 /internal/demo-reset（复位演示库），
# 插在序列中间会把后一步的起跑基线悄悄换掉；放尾部则「rbac 自己收得干净」由第 3 条收尾基线
# （临时主体残留 0 条）背书，A 序列的 r34 证据（rfv 之后 access 仍绿 + access 双跑幂等）原样保留。
#
# 18 条 = 1 栈前置 + 5 静态契约 + 9 序列步骤 + 3 收尾基线。
# 它要独占演示栈、单轮 6–11 分钟，所以不进 make test（每次提交门禁），只做发布前门禁：
#   make gate-order-verify
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
# shellcheck source=lib/gate-fixture.sh
source "${ROOT}/scripts/lib/gate-fixture.sh"

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

# ---------------------------------------------------------------- 2-5 静态契约
banner "1) 静态契约（5）"

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

if grep -q '^TOTAL_CHECKS=75' "${ROOT}/scripts/platform-demo-reset.sh" \
    && grep -q 'NON_FIXTURE_REQS' "${ROOT}/scripts/platform-demo-reset.sh" \
    && grep -q "NOT LIKE 'DEMO-R-%'" "${ROOT}/scripts/platform-demo-reset.sh"; then
    hit "干净基线单点定义：demo-reset TOTAL_CHECKS=75 且含「非夹具需求 0 条」"
else
    miss "demo-reset 分母/断言缺失" "TOTAL_CHECKS 必须为 75，并含 NON_FIXTURE_REQS（code NOT LIKE 'DEMO-R-%' 期望 0）"
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
if [[ -z "$bad" ]]; then
    hit "rbac 五段造名/清理走 _lib.sh 原语、全部有 trap、无 \$RANDOM 造名（s5/s7/s8 另用 purge_roles）"
else
    miss "rbac 段未收敛到 _lib.sh: ${bad}" \
        "source tests/rbac/_lib.sh；造名用 rbac_run_tag；cleanup 里先 rbac_purge_users 再 rbac_purge_roles；\`trap cleanup EXIT\` 必须存在"
fi

# ---------------------------------------------------------------- 7-15 双序列
# 每一步 = 该门禁自己的分母 + gate_fixture_problems 为空；两者任何一个不成立都判该步红。
run_reset() { # label
    local label="$1" out rc m a b probs
    out="$(bash "${ROOT}/scripts/platform-demo-reset.sh" 2>&1)"; rc=$?
    m="$(printf '%s' "$out" | grep -oE 'platform-demo-reset: [0-9]+/[0-9]+' | tail -n 1)"
    a="${m##*: }"; b="${a##*/}"; a="${a%%/*}"
    probs="$(gate_fixture_problems)"
    if (( rc == 0 )) && [[ -n "$m" && "$a" == "$b" && "$a" -ge 75 && -z "$probs" ]]; then
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

# ---------------------------------------------------------------- 16-18 收尾基线
banner "4) 收尾基线（3）"
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

# r36 第 3 条：rbac 跑完临时主体必须 0 条。前缀口径（而不是本轮 tag）是刻意的：
# 历史上 s7 用 $RANDOM 造名 + 只停用不删留下的 527 条垃圾，只有按段族前缀清才清得掉；
# 名字形状同时要落进 demo-reset 的 TEMP_ACTIVE（^(s[0-9]+[a-z]*u|t[0-9]+[a-z]*|probe)[0-9]*$），
# 所以 tag 是全数字、用户名是 s3u/s5u/s6u/s7u1/s7u2/s8u + tag。
RBAC_USERS_LEFT="$(db_count sys_user "tenant_id=${TID2} AND username REGEXP '^(s3|s5|s6|s7|s8)[a-z0-9]*\$'")"
RBAC_ROLES_LEFT=0
for p in S5 S7 S8; do
    n="$(db_count sys_role "tenant_id=${TID2} AND code LIKE '${p}%'")"
    RBAC_ROLES_LEFT=$(( RBAC_ROLES_LEFT + ${n:-0} ))
done
if [[ "${RBAC_USERS_LEFT:-x}" == "0" && "$RBAC_ROLES_LEFT" == "0" ]]; then
    hit "收尾：rbac 临时主体残留 0 条（s3/s5/s6/s7/s8 用户 + S5/S7/S8 角色）"
else
    miss "收尾残留：rbac 临时用户 ${RBAC_USERS_LEFT:-?} 条 / 临时角色 ${RBAC_ROLES_LEFT} 条" \
        "每段 cleanup 必须 rbac_purge_users 再 rbac_purge_roles（先删用户再删角色，否则 ROLE_IN_USE 409 会吞掉删除）"
fi

# ---------------------------------------------------------------- 汇总
printf '\n'
if (( FAIL_N == 0 && TOTAL_N == 18 )); then
    ok "gate-order-verify: 18/18 —— A/B 两种顺序跑完基线一致（门禁顺序无关）"
    exit 0
fi
err "gate-order-verify: ${PASS_N}/${TOTAL_N}（声明 18 条）—— 失败 ${FAIL_N} 条"
err "修复方向：先看每条 FAIL 上方的问题清单（问题: ...）；残留类问题执行 make platform-demo-reset 后再跑"
exit 1
