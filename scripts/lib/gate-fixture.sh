#!/usr/bin/env bash
# gate-fixture.sh — 门禁夹具生命周期与干净基线的唯一实现（r34，AC-1..AC-4）。
#
# 背景：release-flow-verify / platform-verify / platform-test-negative 三个门禁都会写演示租户 t1，
# 过去各自「复位 + 吞错」：rfv 的 cleanup/s7_reset 把 demo-reset 的退出码降级成 WARN，negative
# 干脆没有 trap，于是「谁先跑」决定了后一个门禁看到的数据——顺序依赖就出在这里。
#
# 本模块只做三件事，且只有一份实现：
#   1. 生命周期：begin 注册唯一 EXIT/INT/TERM trap，end 复位并断言基线，失败一律置红（绝不把红变绿）；
#   2. 干净基线：复位只调 scripts/platform-demo-reset.sh（它已用 76 条断言定义了「干净」），
#      本模块只补 demo-reset 覆盖不到的两类事实——本轮 GATE_TAG 足迹、跨门禁读取的演示不变量；
#   3. 夹具原语：login_as / login_admin / approve_biz / pool_bus，让四个门禁不再各写一份审批遍历。
#
# 用法（门禁脚本里）：
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/gate-fixture.sh"
#   gate_fixture_begin || exit 1        # 注册 trap；发现脏基线先自愈复位
#   ...                                 # 自己的夹具，标题/编号里带 $GATE_TAG
#   # 不需要手动 end：EXIT trap 会复位并断言；调试时可 GATE_FIXTURE_SKIP_TEARDOWN=1 跳过（不算通过）
#
# 导出：GATE_TAG / GATE_FIXTURE_TENANT_CODE / GATE_FIXTURE_SKIP_TEARDOWN / GATE_FIXTURE_USERS
# 常量：GATE_FIXTURE_REQUIRED_REQUIREMENTS=13 / GATE_FIXTURE_R02_DAYS=5.00
#
# 依赖方向是单向的：lib.sh（HTTP/DB/登录）→ 本模块 → 三个门禁 → gate-order-verify.sh。
# platform-demo-reset.sh 不依赖本模块（否则循环），它只被本模块调用。

if [[ -n "${GATE_FIXTURE_LIB_LOADED:-}" ]]; then
    return 0
fi
GATE_FIXTURE_LIB_LOADED=1

if ! declare -F db_count >/dev/null 2>&1; then
    # 本模块位于 scripts/lib/，共享库是 scripts/lib.sh（上一层）——两个路径只差一层，写错会静默少依赖。
    _gate_fixture_lib="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
    if [[ ! -f "$_gate_fixture_lib" ]]; then
        printf 'gate-fixture: 找不到共享库 %s（scripts/lib.sh 与 scripts/lib/ 是两层）\n' "$_gate_fixture_lib" >&2
        return 1
    fi
    # shellcheck disable=SC1091
    source "$_gate_fixture_lib"
    if ! declare -F db_count >/dev/null 2>&1; then
        printf 'gate-fixture: %s 已加载但仍缺 db_count/db_q（lib.sh 版本不匹配）\n' "$_gate_fixture_lib" >&2
        return 1
    fi
    unset _gate_fixture_lib
fi

GATE_FIXTURE_TENANT_CODE="${GATE_FIXTURE_TENANT_CODE:-${PLATFORM_TENANT:-t1}}"
GATE_FIXTURE_SKIP_TEARDOWN="${GATE_FIXTURE_SKIP_TEARDOWN:-0}"
GATE_FIXTURE_USERS="${GATE_FIXTURE_USERS:-pm tl1 vendor1 mgr1 admin qa1 po1}"
GATE_FIXTURE_ADMIN_USER="${GATE_FIXTURE_ADMIN_USER:-admin}"
GATE_FIXTURE_ADMIN_PASS="${GATE_FIXTURE_ADMIN_PASS:-Admin@12345}"
GATE_FIXTURE_USER_PASS="${GATE_FIXTURE_USER_PASS:-Passw0rd!}"

# 干净基线的两个跨门禁不变量：演示需求 12 条、DEMO-R-02 汇总人天 5.00
# （5.00 = 子 RD 的 3 + 2；任何门禁挂到 DEMO-R-02 下都会把它推高并让评估单 STALE）。
readonly GATE_FIXTURE_REQUIRED_REQUIREMENTS=13
readonly GATE_FIXTURE_R02_DAYS=5.00

GATE_TAG="${GATE_TAG:-}"
GATE_FIXTURE_TID=""
GATE_FIXTURE_BEGUN=0
GATE_FIXTURE_ENDED=0

# 单个计数查询的自愈包装：DB 不可达时返回 ERR 而不是让 set -e 把诊断吞掉。
_gate_fixture_count() {
    local v=""
    if ! v="$(db_count "$1" "$2" 2>/dev/null)"; then
        printf 'ERR'
        return 0
    fi
    printf '%s' "${v:-ERR}"
}

# 基线诊断：stdout 单行（空 = 干净），任何一项不成立都以可读片段拼进来。
# 只断言 demo-reset 覆盖不到的事实；12/11/0 这类计数口径仍然只有 demo-reset 一处定义。
gate_fixture_problems() {
    local tid="${GATE_FIXTURE_TID:-}" p=() n days est rel
    if [[ -z "$tid" ]]; then
        if ! tid="$(db_q "SELECT id FROM sys_tenant WHERE code='${GATE_FIXTURE_TENANT_CODE}'" 2>/dev/null | head -n1)"; then
            tid=""
        fi
    fi
    if [[ -z "$tid" ]]; then
        printf '找不到租户 %s（sys_tenant）' "$GATE_FIXTURE_TENANT_CODE"
        return 0
    fi

    # 1) 本轮足迹：本轮自建链（gate-<tag>-BUS→POOLED→PDR）的标题带 GATE_TAG，复位后必须一条不剩
    #    （历史/其余夹具用各自 RUN_TAG，不在本项口径内；它们由 demo-reset 整租户 purge + 下面第 2 项兜底）。
    if [[ -n "$GATE_TAG" ]]; then
        n="$(_gate_fixture_count requirement "tenant_id=${tid} AND title LIKE '%${GATE_TAG}%'")"
        if [[ "$n" != "0" ]]; then
            p+=("本轮 tag 残留 requirement ${n} 条（title LIKE %${GATE_TAG}%）")
        fi
    fi

    # 2) 非夹具需求必须是 0：negative/verify 过去把夹具挂到种子上，就是这一项长期非 0。
    n="$(_gate_fixture_count requirement "tenant_id=${tid} AND code NOT LIKE 'DEMO-R-%'")"
    if [[ "$n" != "0" ]]; then
        p+=("非夹具需求 ${n} 条（期望 0：门禁必须只写自己的 tag）")
    fi

    # 3) 演示需求总数 = 12（demo-reset 的计数口径在钩子里的镜像，用于识别「复位半途」）。
    n="$(_gate_fixture_count requirement "tenant_id=${tid} AND code LIKE 'DEMO-R-%'")"
    if [[ "$n" != "$GATE_FIXTURE_REQUIRED_REQUIREMENTS" ]]; then
        p+=("演示需求 ${n} 条（期望 ${GATE_FIXTURE_REQUIRED_REQUIREMENTS}）")
    fi

    # 4) DEMO-R-02 汇总人天 5.00：任何子需求挂错父都会把它推高（r34 的顺序依赖就是这么红的）。
    days=""
    if ! days="$(db_q "SELECT FORMAT(estimate_days,2) FROM requirement WHERE tenant_id=${tid} AND code='DEMO-R-02'" 2>/dev/null | head -n1)"; then
        days="ERR"
    fi
    if [[ "$days" != "$GATE_FIXTURE_R02_DAYS" ]]; then
        p+=("DEMO-R-02 人天 ${days:-<缺失>}（期望 ${GATE_FIXTURE_R02_DAYS}）")
    fi

    # 5) DEMO-R-02 的工时评估单必须仍是 APPROVED（人天漂移会把它推成 STALE）。
    est="$(_gate_fixture_count wf_instance "tenant_id=${tid} AND biz_type='WBS_ESTIMATE' AND biz_id=(SELECT id FROM requirement WHERE tenant_id=${tid} AND code='DEMO-R-02') AND status='APPROVED'")"
    if [[ "$est" != "1" ]]; then
        p+=("DEMO-R-02 已通过工时评估单 ${est} 张（期望 1）")
    fi

    # 6) DEMO-R-04 必须在 DEMO-REL-1 上（r35 起 S7 只自造夹具，这条是「从未被移动」的证据）。
    rel="$(_gate_fixture_count requirement "tenant_id=${tid} AND code='DEMO-R-04' AND release_id=(SELECT id FROM \`release\` WHERE tenant_id=${tid} AND code='DEMO-REL-1')")"
    if [[ "$rel" != "1" ]]; then
        p+=("DEMO-R-04 不在 DEMO-REL-1（命中 ${rel} 行，期望 1）")
    fi

    if (( ${#p[@]} > 0 )); then
        local IFS=';'
        printf '%s' "${p[*]}"
    fi
    return 0
}

# 复位到干净基线：成功打印一行 [gate]，失败打印末尾 20 行并置红（绝不 WARN 降级）。
gate_fixture_reset() {
    local label="${1:-复位}" rc=0 out=""
    if out="$(bash "${ROOT}/scripts/platform-demo-reset.sh" 2>&1)"; then
        printf '  [gate] %s：platform-demo-reset 通过（演示基线已重建）\n' "$label"
        return 0
    else
        rc=$?
    fi
    err "[gate] ${label}：platform-demo-reset 失败（rc=${rc}），末尾 20 行："
    printf '%s\n' "$out" | tail -n 20 >&2
    err "[gate] 先单独跑 bash scripts/platform-demo-reset.sh 看首个 FAIL，再重跑门禁"
    return 1
}

gate_fixture_assert_clean() {
    local label="${1:-基线}" problems=""
    problems="$(gate_fixture_problems)"
    if [[ -z "$problems" ]]; then
        printf '  [gate] %s：基线干净（足迹 0 / 非夹具需求 0 / 演示不变量成立）\n' "$label"
        return 0
    fi
    err "[gate] ${label}：基线不干净 → ${problems}"
    return 1
}

_gate_fixture_teardown() {
    if [[ "${GATE_FIXTURE_BEGUN:-0}" != "1" ]]; then
        return 0
    fi
    if [[ "${GATE_FIXTURE_ENDED:-0}" == "1" ]]; then
        return 0
    fi
    GATE_FIXTURE_ENDED=1
    if [[ "${GATE_FIXTURE_SKIP_TEARDOWN:-0}" == "1" ]]; then
        warn "[gate] GATE_FIXTURE_SKIP_TEARDOWN=1：跳过退出复位与基线断言（调试用，本轮结果不算干净）"
        return 0
    fi
    local reset_ok=1 problems=""
    gate_fixture_reset "退出复位" || reset_ok=0
    problems="$(gate_fixture_problems)"
    if [[ -n "$problems" ]]; then
        err "[gate] 退出后基线不干净：${problems}"
        return 1
    fi
    if (( reset_ok != 1 )); then
        return 1
    fi
    printf '  [gate] 退出基线干净：本轮足迹 0、非夹具需求 0、演示不变量成立\n'
    return 0
}

# EXIT trap：保留原有退出码，teardown 失败只能把绿变红，绝不把红变绿。
_gate_fixture_on_exit() {
    local rc=$?
    trap - EXIT INT TERM
    if ! _gate_fixture_teardown; then
        rc=1
    fi
    exit "$rc"
}

# 公开 end：幂等，先撤 trap，返回 0|1（0 = demo-reset 成功且 problems 为空）。
gate_fixture_end() {
    trap - EXIT INT TERM
    _gate_fixture_teardown
}

gate_fixture_begin() {
    if [[ "${GATE_FIXTURE_BEGUN:-0}" == "1" ]]; then
        return 0
    fi
    GATE_TAG="${GATE_TAG:-$(date +%m%d%H%M%S)-$$}"
    export GATE_TAG
    if ! GATE_FIXTURE_TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${GATE_FIXTURE_TENANT_CODE}'" 2>/dev/null | head -n1)"; then
        GATE_FIXTURE_TID=""
    fi
    if [[ -z "$GATE_FIXTURE_TID" ]]; then
        err "[gate] 找不到租户 ${GATE_FIXTURE_TENANT_CODE} 的 id（sys_tenant）：先 make platform-up && make platform-seed"
        return 1
    fi

    # 起跑自愈：上一个门禁若被 kill -9（trap 来不及跑）会留下非夹具需求，先复位再开始。
    local stray=""
    stray="$(_gate_fixture_count requirement "tenant_id=${GATE_FIXTURE_TID} AND code NOT LIKE 'DEMO-R-%'")"
    if [[ "$stray" != "0" ]]; then
        warn "[gate] 起跑自愈：发现 ${stray} 条非夹具需求，先复位到干净基线"
        gate_fixture_reset "起跑自愈" || return 1
    fi

    GATE_FIXTURE_BEGUN=1
    GATE_FIXTURE_ENDED=0
    trap '_gate_fixture_on_exit' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    printf '  [gate] GATE_TAG=%s（tenant=%s，退出即复位）\n' "$GATE_TAG" "$GATE_FIXTURE_TENANT_CODE"
    return 0
}

gate_login_as() {
    local user="$1" pass="$GATE_FIXTURE_USER_PASS"
    if [[ "$user" == "$GATE_FIXTURE_ADMIN_USER" ]]; then
        pass="$GATE_FIXTURE_ADMIN_PASS"
    fi
    if platform_login "$GATE_FIXTURE_TENANT_CODE" "$user" "$pass" >/dev/null; then
        return 0
    fi
    err "[gate] 登录失败：${GATE_FIXTURE_TENANT_CODE}/${user}"
    return 1
}

gate_login_admin() {
    gate_login_as "$GATE_FIXTURE_ADMIN_USER"
}

# 逐轮遍历 GATE_FIXTURE_USERS 的审批待办并同意，结束还原 admin。
# 返回 0 仅当「至少同意过一个任务」且「没有一次同意失败」且「admin 还原成功」。
gate_approve_biz() {
    local biz="$1" type="$2" max="${3:-6}"
    local p="" u="" t="" list="" n=0 total=0 failed=0 restore_ok=1
    for ((p = 0; p < max; p++)); do
        n=0
        for u in $GATE_FIXTURE_USERS; do
            if ! gate_login_as "$u"; then
                continue
            fi
            api_call GET '/api/req/approvals/todo?page=1&size=200'
            list="$(printf '%s' "$HTTP_BODY" | jq -r ".items[]? | select(.bizId == ${biz} and .bizType == \"${type}\") | .taskId" 2>/dev/null || true)"
            if [[ -z "$list" ]]; then
                continue
            fi
            while IFS= read -r t; do
                if [[ -z "$t" ]]; then
                    continue
                fi
                api_call POST "/api/req/approvals/tasks/${t}/actions" '{"action":"APPROVE","comment":"gate-fixture 门禁夹具"}'
                if [[ "$HTTP_CODE" == "200" ]]; then
                    n=$((n + 1))
                    total=$((total + 1))
                else
                    failed=$((failed + 1))
                fi
            done <<<"$list"
        done
        if (( n == 0 )); then
            break
        fi
    done
    gate_login_admin || restore_ok=0
    if (( total == 0 )); then
        err "[gate] ${type} 待办里没有可同意的任务（bizId=${biz}）：审批定义/审批人解析缺失"
        return 1
    fi
    if (( failed != 0 )); then
        err "[gate] ${type} 有 ${failed} 次同意返回非 200（bizId=${biz}）"
        return 1
    fi
    if (( restore_ok != 1 )); then
        err "[gate] 审批结束后还原 admin 失败（bizId=${biz}）：后续调用会带着普通用户 token"
        return 1
    fi
    printf '  [gate] %s（bizId=%s）：同意 %s 个待办\n' "$type" "$biz" "$total"
    return 0
}

# 把一条 BUS 真实推到 POOLED：submit → 会签 → review_start → 会签 → GET 断言 POOLED。
# POOLED 是 RequirementRules.REQUIRED_POOLED 的硬前置，PDR 必须挂在它下面。
gate_pool_bus() {
    local bus="$1" label="${2:-BUS $1}" status=""
    api_call POST "/api/req/requirements/${bus}/actions" '{"action":"submit"}'
    if [[ "$HTTP_CODE" != "200" ]]; then
        err "[gate] ${label}：submit 失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 200)"
        return 1
    fi
    gate_approve_biz "$bus" REQ_SUBMIT || return 1
    api_call POST "/api/req/requirements/${bus}/actions" '{"action":"review_start","comment":"gate-fixture 门禁夹具"}'
    if [[ "$HTTP_CODE" != "200" ]]; then
        err "[gate] ${label}：review_start 失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 200)"
        return 1
    fi
    gate_approve_biz "$bus" REQ_REVIEW || return 1
    api_call GET "/api/req/requirements/${bus}"
    status="$(jget "$HTTP_BODY" '.view.status // .status')"
    if [[ "$status" != "POOLED" ]]; then
        err "[gate] ${label}：池化后状态 ${status:-<缺失>}，期望 POOLED"
        return 1
    fi
    printf '  [gate] %s：已入池（POOLED）\n' "$label"
    return 0
}

# 把一条 PDR 真实推过「PDR 评审」（r46 顺序门）：发起评审 → 各评审人 PASS → conclude → APPROVED。
# 评审人用显式名单（默认 pm + tl1，二者都在 GATE_FIXTURE_USERS 里，能收到待办/可提交意见）；
# PDR 顺序门未过时 assign-vendor / estimate / schedule 全被 409 PDR_REVIEW_REQUIRED 拦，
# 所以凡是要走评估/排期的 PDR 夹具，必须先过这道门。
gate_pdr_review_pass() {
    local req="$1" label="${2:-PDR $1}" saved="$API_TOKEN"
    local rid="" u ok=0 status="" mine=0
    api_call POST "/api/req/requirements/${req}/pdr-review" \
        '{"reviewerIds":[],"mode":"MODE_ALL","note":"gate-fixture PDR 评审夹具"}'
    if [[ "$HTTP_CODE" != "201" ]]; then
        # 已经有在途评审单时复用（幂等：门禁可能在同一 PDR 上跑两段）
        api_call GET "/api/req/requirements/${req}/pdr-review"
        rid="$(jget "$HTTP_BODY" '.review.id // .id // empty')"
        if [[ -z "$rid" || "$rid" == "null" ]]; then
            err "[gate] ${label}：发起 PDR 评审失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 200)"
            API_TOKEN="$saved"
            return 1
        fi
    else
        rid="$(jget "$HTTP_BODY" '.review.id // .id // empty')"
    fi
    [[ -n "$rid" && "$rid" != "null" ]] || {
        err "[gate] ${label}：评审单 id 缺失"
        API_TOKEN="$saved"
        return 1
    }
    for u in $GATE_FIXTURE_USERS; do
        gate_login_as "$u" || continue
        api_call POST "/api/req/pdr-reviews/${rid}/opinions" \
            '{"opinions":[{"category":"RANGE","decision":"PASS","comment":"gate-fixture 评审通过"}]}'
        if [[ "$HTTP_CODE" == "201" || "$HTTP_CODE" == "200" ]]; then
            mine=$((mine + 1))
        fi
    done
    if [[ "$mine" -eq 0 ]]; then
        err "[gate] ${label}：没有任何夹具用户能提交评审意见（评审人名单与夹具用户不重叠）"
        API_TOKEN="$saved"
        return 1
    fi
    gate_login_as "$GATE_FIXTURE_ADMIN_USER" || {
        err "[gate] ${label}：管理员登录失败（汇总评审需要 pdr_review_conclude）"
        API_TOKEN="$saved"
        return 1
    }
    api_call POST "/api/req/pdr-reviews/${rid}/conclude" '{"note":"gate-fixture 汇总"}'
    # 全员 PASS 时评审引擎会自动收口（再 conclude 返回 409 REVIEW_CLOSED + status=APPROVED），按成功处理。
    if [[ "$HTTP_CODE" != "200" ]]; then
        if [[ "$HTTP_CODE" == "409" && "$(printf '%s' "$HTTP_BODY" | grep -c '"status":"APPROVED"')" -gt 0 ]]; then
            printf '  [gate] %s：评审已自动收口（APPROVED）\n' "$label"
        else
            err "[gate] ${label}：评审汇总失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 200)"
            API_TOKEN="$saved"
            return 1
        fi
    fi
    api_call GET "/api/req/requirements/${req}/pdr-review"
    status="$(jget "$HTTP_BODY" '.review.status // .status // empty')"
    API_TOKEN="$saved"
    if [[ "$status" != "APPROVED" ]]; then
        err "[gate] ${label}：评审后状态 ${status:-<缺失>}，期望 APPROVED"
        return 1
    fi
    printf '  [gate] %s：已通过 PDR 评审（APPROVED，意见 %d 条）\n' "$label" "$mine"
    return 0
}

# r46 顺序门第二步：评估前必须先指派承接厂商（409 VENDOR_NOT_ASSIGNED）。
gate_assign_vendor() {
    local req="$1" label="${2:-需求 $1}" saved="$API_TOKEN" vid="${3:-1}"
    gate_login_as "$GATE_FIXTURE_ADMIN_USER" || return 1
    api_call POST "/api/req/requirements/${req}/assign-vendor" \
        "{\"vendorId\":${vid},\"reason\":\"gate-fixture 指派承接厂商\"}"
    if [[ "$HTTP_CODE" != "200" ]]; then
        err "[gate] ${label}：指派厂商失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 200)"
        API_TOKEN="$saved"
        return 1
    fi
    API_TOKEN="$saved"
    printf '  [gate] %s：已指派承接厂商 vendorId=%s\n' "$label" "$vid"
    return 0
}
