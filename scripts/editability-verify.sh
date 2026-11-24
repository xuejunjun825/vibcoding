#!/usr/bin/env bash
# editability-verify.sh — r43 需求字段可编辑性门禁（AC-1..AC-18）。
#
# 36 条断言，六组：
#   S1 静态契约（6）          自声明 36 == ck+sck 调用点、契约类 3 决策 + 9 reasonCode、
#                            FIELDS 规范序 11、req-service 无第二份「状态→字段」表、
#                            前端无 children.length / isParent 判定、产品代码无 MUTATE 开关、
#                            交付面（pom/package.json 未改、无新迁移、Makefile 薄目标、
#                            test-all 在 change-verify 之后接入、共享夹具钩子）
#   S2 三组对照（9）          BUS SUBMITTED/POOLED/CLOSED、PDR PENDING_ANALYSIS/IN_DEV/ACCEPTED_CLOSED、
#                            RD TODO/IN_DEV/DONE：每组逐行取 detail，三条数组的「字段:reasonCode」
#                            必须与脚本内独立实现的矩阵逐字相同（同时锁住分区、规范序、判定）
#   S3 字段特例（7）          type 全域 IMMUTABLE、BUS parentId NO_PARENT（终态由 TERMINAL 优先）、
#                            BUS/PDR estimateDays 早期也 PARENT_ROLLUP、RD estimateDays TODO=EDIT /
#                            IN_DEV=CHANGE_REQUIRED、domainIds+sonarProjectKey 早期与受控 OPEN_STATE、
#                            ownerUserId ASSIGN_FLOW、未知状态（SQL 置 BOGUS）11 字段全 UNKNOWN_STATUS
#   S4 PUT 错误码（5）        终态改 title → 409 FIELD_LOCKED(field=title,reasonCode=TERMINAL)、
#                            受控多字段 → 409 CHANGE_REQUIRED(fields 规范序)、混合改动只报 LOCKED、
#                            终态同值 → 200 且 updated_at 与 UPDATE 审计条数都不变、
#                            非 RD 改 estimateDays → 409 FIELD_LOCKED/PARENT_ROLLUP（取代 r40 的 400）
#   S5 响应一致性（5）        三数组元素键恰为 {field,reasonCode}、两次 GET 完全一致、
#                            permissions.editableFields == EDIT ∩ 旧七字段投影、
#                            detail 标记为 EDIT 的 UI 字段同值 PUT 全 200、
#                            代表字段的 detail 判定与真实 PUT 结果同源（LOCKED→FIELD_LOCKED，
#                            CHANGE_REQUIRED→CHANGE_REQUIRED，reasonCode 逐字相同）
#   S6 变更记录与负向（4）    新建变更单行 fields == before/after 真实差集（规范序）、
#                            空快照行 fields == [] 且列表仍 200、
#                            EDITABILITY_MUTATE=always-edit 必须让门禁变红（断言非空转）、
#                            退出复位成功且本轮 TAG 残留 0
#
# 用法：
#   bash scripts/editability-verify.sh                # 全量（需 make platform-up 已起栈）
#   bash scripts/editability-verify.sh --once         # 同上（供 --twice 内部调用）
#   bash scripts/editability-verify.sh --twice        # 连跑两次并断言两次结果一致
#   bash scripts/editability-verify.sh --static-only  # 不连栈，只查契约/接线/自声明自洽（6 条）
#   EDITABILITY_MUTATE=always-edit bash scripts/editability-verify.sh   # 负向自检：必须变红
#
# 约定（与 change-verify / release-flow-verify 一致）：
#   - 脚本自声明 EDITABILITY_TOTAL=36；静态检查会用 grep 数 `ck "` + `sck "` 调用点，
#     声明与实现不符即红，所以「少跑一条断言」不可能悄悄变绿；
#   - 期望矩阵在本脚本内独立实现（expected_rule），不调用产品代码：只有两份实现独立，
#     「接口说 EDIT 但 PUT 拒绝」这类漂移才抓得住；
#   - 前置缺失（栈没起、detail 没有三数组）直接 die，不 skip（r28 教训：条件跳过=假绿）；
#   - 本轮所有夹具标题带 RTAG，退出复位交给 scripts/lib/gate-fixture.sh（demo-reset + 基线断言），
#     复位失败或 tag 残留一律置红，绝不用 WARN 降级；
#   - 终态/中间态夹具用 SQL 置状态（动作链要过审批，本门禁只关心状态→字段矩阵，不重复造审批夹具）；
#   - 变更单闭环（申请/影响/审批/应用）由 scripts/change-verify.sh（r40）覆盖，本门禁只断言
#     变更列表行新增的 fields 一列。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib.sh
source "${ROOT}/scripts/lib.sh"
# shellcheck source=lib/gate-fixture.sh
source "${ROOT}/scripts/lib/gate-fixture.sh"
set +e

EDITABILITY_TOTAL=36
STATIC_TOTAL=6
CASE=0
PASS=0
FAIL=0
STATIC_ONLY=0
MODE="full"
case "${1:-}" in
    --static-only) STATIC_ONLY=1 ;;
    --twice) MODE="twice" ;;
    --once) MODE="once" ;;
    --help | -h)
        sed -n '2,/^set -uo pipefail$/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
        exit 0
        ;;
esac

REPORT_DIR="${RUN_DIR:-${ROOT}/.run}/editability"
FAIL_LOG=""
RTAG="${EDITABILITY_VERIFY_TAG:-$(date +%m%d%H%M%S)-$$}"
T1_TENANT="${T1_TENANT:-t1}"
GATE_FIXTURE_TENANT_CODE="$T1_TENANT"
[[ "${EDITABILITY_VERIFY_SKIP_RESET:-0}" == "1" ]] && GATE_FIXTURE_SKIP_TEARDOWN=1
# 负向自检开关：只把本脚本的期望矩阵翻成「一律 EDIT」，不碰产品代码（产品代码出现 MUTATE 由 S1-⑤ 置红）。
MUTATE="${EDITABILITY_MUTATE:-}"
ADMIN_USER="admin"
ADMIN_PASS="Admin@12345"
ADMIN_TOKEN=""
ADMIN_TENANT=""
TID=""
DOMAIN_ID=""

# 规范序（与 RequirementEditability.FIELDS 逐字一致；S1-② 会静态比对，漂了先红）
CANON=(title description priority expectedDeliveryDate estimateDays parentId releaseId \
       domainIds sonarProjectKey ownerUserId type)
# 旧 permissions.editableFields 的七字段投影（r40 口径，S5-③ 用）
UI7=(title description priority expectedDeliveryDate estimateDays releaseId parentId)

section() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
ck() { # ck <0|1> <pass-msg> <fail-msg> <fix>
    local ok="${1:-0}" pmsg="${2:-}" fmsg="${3:-}" fix="${4:-}"
    CASE=$((CASE + 1))
    if [[ "$ok" == "1" ]]; then
        PASS=$((PASS + 1))
        printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$pmsg"
    else
        FAIL=$((FAIL + 1))
        printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$fmsg"
        printf '        修复: %s\n' "$fix"
        if [[ -n "${FAIL_LOG:-}" ]]; then printf '[%02d] %s | 修复: %s\n' "$CASE" "$fmsg" "$fix" >>"$FAIL_LOG"; fi
    fi
    return 0
}
jqv() { printf '%s' "${2:-}" | jq -r "$1" 2>/dev/null; }
jlen() { printf '%s' "${1:-}" | jq -r 'if type=="array" or type=="object" then length else 0 end' 2>/dev/null; }
needle() { [[ "${1:-}" == *"${2:-__nope__}"* ]]; }

# --------------------------------------------------------------------------- 期望矩阵（独立实现）

# 未知 type/status 一律 fail-closed；名单与 StateTables 一致，状态改名必须两处同时改（S2/S3 会红）。
spec_known() {
    case "$1:$2" in
        BUS:SUBMITTED | BUS:APPROVING | BUS:PENDING_REVIEW | BUS:POOLED | BUS:CLOSED) return 0 ;;
        PDR:PENDING_ANALYSIS | PDR:SCHEDULED | PDR:IN_DEV | PDR:DELIVERED | PDR:ACCEPTED_CLOSED) return 0 ;;
        RD:TODO | RD:IN_DEV | RD:PENDING_TEST | RD:TEST_PASSED | RD:MERGED | RD:DONE) return 0 ;;
        *) return 1 ;;
    esac
}
spec_terminal() {
    case "$1:$2" in
        BUS:CLOSED | PDR:ACCEPTED_CLOSED | RD:DONE) return 0 ;;
        *) return 1 ;;
    esac
}
spec_early() {
    case "$1:$2" in
        BUS:SUBMITTED | BUS:APPROVING | BUS:PENDING_REVIEW | PDR:PENDING_ANALYSIS | RD:TODO) return 0 ;;
        *) return 1 ;;
    esac
}

expected_rule() { # expected_rule <type> <status> <field> → "DECISION:reasonCode"
    local t="$1" s="$2" f="$3"
    if [[ "$MUTATE" == "always-edit" ]]; then
        # 只翻期望，不动产品代码：矩阵断言必须因此变红，否则断言是空转（S6-③）。
        printf 'EDIT:EARLY_STAGE'
        return 0
    fi
    spec_known "$t" "$s" || { printf 'LOCKED:UNKNOWN_STATUS'; return 0; }
    [[ "$f" == "type" ]] && { printf 'LOCKED:IMMUTABLE'; return 0; }
    spec_terminal "$t" "$s" && { printf 'LOCKED:TERMINAL'; return 0; }
    [[ "$f" == "parentId" && "$t" == "BUS" ]] && { printf 'LOCKED:NO_PARENT'; return 0; }
    [[ "$f" == "estimateDays" && "$t" != "RD" ]] && { printf 'LOCKED:PARENT_ROLLUP'; return 0; }
    [[ "$f" == "ownerUserId" ]] && { printf 'LOCKED:ASSIGN_FLOW'; return 0; }
    [[ "$f" == "domainIds" || "$f" == "sonarProjectKey" ]] && { printf 'EDIT:OPEN_STATE'; return 0; }
    spec_early "$t" "$s" && { printf 'EDIT:EARLY_STAGE'; return 0; }
    printf 'CHANGE_REQUIRED:CHANGE_REQUIRED'
}
expected_decision() { expected_rule "$@" | cut -d: -f1; }
expected_reason() { expected_rule "$@" | cut -d: -f2; }

expected_array_csv() { # expected_array_csv <type> <status> <EDIT|CHANGE_REQUIRED|LOCKED> → "f:reason,..."
    local t="$1" s="$2" want="$3" f out=()
    for f in "${CANON[@]}"; do
        [[ "$(expected_decision "$t" "$s" "$f")" == "$want" ]] && out+=("$f:$(expected_reason "$t" "$s" "$f")")
    done
    local IFS=,
    # macOS 自带 bash 3.2 + set -u：空数组的 ${out[*]} 会报 "unbound variable"，
    # `${out[*]-}` 才是空数组安全的展开；否则终态/无受控字段的那几条会「实际==期望」却判红。
    printf '%s' "${out[*]-}"
}

# --------------------------------------------------------------------------- 夹具与 detail 读取

as_admin() {
    API_TOKEN="$ADMIN_TOKEN"
    API_TENANT="$ADMIN_TENANT"
}
detail_get() { # detail_get <id> → DB_CODE / DB_BODY
    api_call GET "/api/req/requirements/$1"
    DB_CODE="$HTTP_CODE"
    DB_BODY="$HTTP_BODY"
}
detail_array_csv() { # detail_array_csv <body> <editableFields|changeRequiredFields|lockedFields>
    printf '%s' "${1:-}" | jq -r --arg k "$2" '.[$k] // [] | map(.field + ":" + .reasonCode) | join(",")' 2>/dev/null
}
rule_of() { # rule_of <id> <field> → "DECISION:reasonCode"（从三条数组反查，不猜）
    detail_get "$1"
    printf '%s' "${DB_BODY:-}" | jq -r --arg f "$2" '
        ((.editableFields // []) | map({(.field): ("EDIT:" + .reasonCode)}))
        + ((.changeRequiredFields // []) | map({(.field): ("CHANGE_REQUIRED:" + .reasonCode)}))
        + ((.lockedFields // []) | map({(.field): ("LOCKED:" + .reasonCode)}))
        | add // {} | .[$f] // "MISSING"' 2>/dev/null
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
set_status() { # set_status <id> <status>：状态机要过审批，门禁只验「状态→字段」，直接置状态
    local id="$1" st="$2" got=""
    # 必须用 db_q（带 -D 选了库）：db_admin_q 不选库，表名会以「No database selected」静默失败（stderr 被吞）。
    db_q "UPDATE requirement SET status='${st}' WHERE tenant_id=${TID} AND id=${id}" >/dev/null
    got="$(db_q "SELECT status FROM requirement WHERE tenant_id=${TID} AND id=${id}")"
    [[ "$got" == "$st" ]]
}
put_req() { # put_req <id> <json> → PB_CODE / PB_BODY（err_* 读 CB）
    api_call PUT "/api/req/requirements/$1" "$2"
    PB_CODE="$HTTP_CODE"
    PB_BODY="$HTTP_BODY"
    CB="$HTTP_BODY"
}
err_code() { jqv '.code // empty' "${1:-$CB}"; }
err_field() { jqv '.detail.field // empty' "${1:-$CB}"; }
err_reason() { jqv '.detail.reasonCode // empty' "${1:-$CB}"; }
err_fields() { jqv '.detail.fields // [] | join(",")' "${1:-$CB}"; }
err_endpoint() { jqv '.detail.changeEndpoint // empty' "${1:-$CB}"; }

# --------------------------------------------------------------------------- 静态检查（免栈）

SCASE=0
SPASS=0
sck() {
    local ok="$1"
    SCASE=$((SCASE + 1))
    if [[ "$ok" == "1" ]]; then
        SPASS=$((SPASS + 1))
        printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$SCASE" "$2"
    else
        printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$SCASE" "$3"
        printf '        修复: %s\n' "$4"
    fi
}

static_checks() {
    local self="${BASH_SOURCE[0]}" contract="${ROOT}/platform-contract/src/main/java/com/example/platform/contract/RequirementEditability.java"
    local ck_sites sck_sites bad=() f

    # ① 计数自证 + 契约常量：断言数漂移与 reasonCode 缺项都在这里红。
    ck_sites="$(grep -cE '^[[:space:]]*ck[[:space:]]' "$self")"
    sck_sites="$(grep -cE '^[[:space:]]*sck[[:space:]]' "$self")"
    [[ "$((ck_sites + sck_sites))" == "$EDITABILITY_TOTAL" ]] || bad+=("调用点 ck=${ck_sites}+sck=${sck_sites} ≠ ${EDITABILITY_TOTAL}")
    [[ "$sck_sites" == "$STATIC_TOTAL" ]] || bad+=("静态段 sck=${sck_sites} ≠ STATIC_TOTAL=${STATIC_TOTAL}")
    [[ -f "$contract" ]] || bad+=("缺 ${contract#"$ROOT"/}")
    for f in EDIT CHANGE_REQUIRED LOCKED; do
        grep -q "enum Decision" "$contract" 2>/dev/null && grep -qxE "[[:space:]]*${f},?" "$contract" 2>/dev/null \
            || bad+=("Decision 缺 ${f}")
    done
    for f in EARLY_STAGE OPEN_STATE CHANGE_REQUIRED TERMINAL PARENT_ROLLUP NO_PARENT ASSIGN_FLOW IMMUTABLE UNKNOWN_STATUS; do
        grep -q "String ${f} = \"${f}\"" "$contract" 2>/dev/null || bad+=("reasonCode 缺 ${f}")
    done
    local sc_ok=1 sc_fail=""
    (( ${#bad[@]} == 0 )) || { sc_ok=0; sc_fail="静态契约不符：$(printf '%s；' "${bad[@]}")"; }
    sck "$sc_ok" "自声明 ${EDITABILITY_TOTAL} == ck+${sck_sites} 调用点；契约类 3 决策 + 9 reasonCode 齐全" \
        "$sc_fail" \
        "改了断言就同步 EDITABILITY_TOTAL；reasonCode 是前后端共享契约，少一个就会让前端兜底文案接管"

    # ② FIELDS 规范序：11 个字段的名称与顺序（父需求 field 在 releaseId 之前）。
    local fields_csv="" want_csv
    fields_csv="$(sed -n '/FIELDS = List.of(/,/);/p' "$contract" 2>/dev/null \
        | tr -d ' \n' | sed 's/.*List.of(//; s/).*//' \
        | sed 's/F_//g' | tr 'A-Z' 'a-z' | sed 's/_//g')"
    local w=("${CANON[@]}") f
    for f in "${!w[@]}"; do w[$f]="$(printf '%s' "${w[$f]}" | tr 'A-Z' 'a-z')"; done
    want_csv="$(IFS=,; printf '%s' "${w[*]}")"
    sck "$([[ -n "$fields_csv" && "$fields_csv" == "$want_csv" ]] && echo 1 || echo 0)" \
        "FIELDS 规范序与门禁期望逐位一致（${#CANON[@]} 个字段）" \
        "FIELDS 顺序/成员漂移：实际[${fields_csv}] 期望[${want_csv}]" \
        "规范序是前端渲染顺序与 fields[] 数组顺序的唯一来源；改表必须同时改门禁与 editability.ts"

    # ③ 第二份「状态→字段」表：req-service 不许再出现早期态清单或状态字面量判定。
    local early_hits lit_hits
    early_hits="$(grep -rn 'EARLY_STATUSES\|earlyStatuses' "${ROOT}/req-service/src/main/java" 2>/dev/null | wc -l | tr -d ' ')"
    lit_hits="$(grep -rn 'SUBMITTED\|PENDING_ANALYSIS' "${ROOT}/req-service/src/main/java/com/example/req/requirement/change" 2>/dev/null \
        | grep -vE ': *(\*|/\*|//)' | wc -l | tr -d ' ')"
    sck "$([[ "$early_hits" == "0" && "$lit_hits" == "0" ]] && echo 1 || echo 0)" \
        "req-service 已无第二份状态表（EARLY_STATUSES=${early_hits}、change 包状态字面量=${lit_hits}）" \
        "req-service 仍有第二份状态→字段表：EARLY_STATUSES 命中 ${early_hits}、状态字面量命中 ${lit_hits}" \
        "早期态只在 RequirementEditability.earlyStatuses() 维护；RequirementChangeFields 必须委托，否则两份表迟早给出两种结论"

    # ④ 前端编辑判定不许再靠 children.length / isParent 推断（父需求人天由 PARENT_ROLLUP 表达）。
    local surfaces=("${ROOT}/web/src/components/req/RequirementEditDrawer.vue"
        "${ROOT}/web/src/views/req/RequirementDetailView.vue"
        "${ROOT}/web/src/views/req/tabs/OverviewTab.vue"
        "${ROOT}/web/src/views/req/editability.ts")
    local child_hits parent_hits
    child_hits="$(grep -rnE 'children[?.]*\.?length' "${surfaces[@]}" 2>/dev/null | grep -E '>[[:space:]]*0' | wc -l | tr -d ' ')"
    parent_hits="$(grep -rn 'isParent' "${surfaces[@]}" 2>/dev/null | wc -l | tr -d ' ')"
    sck "$([[ "$child_hits" == "0" && "$parent_hits" == "0" ]] && echo 1 || echo 0)" \
        "抽屉/详情/概览已删除 children.length 与 isParent 判定（0/0）" \
        "仍有子需求长度推断：children.length>0 命中 ${child_hits}、isParent 命中 ${parent_hits}" \
        "父需求人天是否可改由 ReasonCode=PARENT_ROLLUP 决定；用 children.length 推断会让「没有子需求但已入池」的父项被误判可改"

    # ⑤ 产品代码不含 MUTATE：负向开关只活在本脚本里。
    local mutate_hits
    mutate_hits="$(grep -rn 'MUTATE' "${ROOT}/web/src" "${ROOT}/platform-contract/src" "${ROOT}/req-service/src" 2>/dev/null | wc -l | tr -d ' ')"
    sck "$([[ "$mutate_hits" == "0" ]] && echo 1 || echo 0)" \
        "产品代码无 MUTATE 开关（负向能力只在本脚本内）" \
        "产品代码出现 MUTATE 字样：${mutate_hits} 处" \
        "测试开关一旦进产品代码就会被误开；负向自检只能改门禁自己的期望矩阵"

    # ⑥ 交付面：不动构件声明、不加迁移、门禁自身接线完整（否则脚本在 CI 里根本不会被跑到）。
    local dep_hits mig_hits wire=() mk_tgt ta_line cv_line
    dep_hits="$(git -C "$ROOT" status --porcelain=v1 -uall 2>/dev/null | awk '{print $2}' \
        | grep -E '(^|/)(pom\.xml|package\.json|package-lock\.json)$' | wc -l | tr -d ' ')"
    mig_hits="$(git -C "$ROOT" status --porcelain=v1 -uall 2>/dev/null | awk '{print $2}' \
        | grep -E 'db/migration/V[0-9]+__' | wc -l | tr -d ' ')"
    [[ "$dep_hits" == "0" ]] || wire+=("pom/package.json 改动 ${dep_hits} 个")
    [[ "$mig_hits" == "0" ]] || wire+=("新增/改动迁移 ${mig_hits} 个")
    grep -q '^editability-verify:' "${ROOT}/Makefile" 2>/dev/null \
        && grep -q 'scripts/editability-verify.sh' "${ROOT}/Makefile" 2>/dev/null \
        || wire+=("Makefile 缺 editability-verify 薄目标")
    ta_line="$(grep -n '字段可编辑性（editability-verify）' "${ROOT}/scripts/test-all.sh" 2>/dev/null | head -n1 | cut -d: -f1)"
    cv_line="$(grep -n '需求变更单（change-verify）' "${ROOT}/scripts/test-all.sh" 2>/dev/null | head -n1 | cut -d: -f1)"
    [[ -n "$ta_line" && -n "$cv_line" && "$ta_line" -gt "$cv_line" ]] || wire+=("test-all 未在 change-verify 之后接入")
    grep -q 'gate_fixture_begin' "$self" && grep -q 'gate_fixture_end' "$self" \
        || wire+=("未接共享夹具钩子")
    # ⑦ 文档基线与禁用文案同源（QA D-1/D-3）：memory 基线行必须含本门禁与矩阵约定，
    #    REQUIREMENT-FLOW 必须有「可编辑性判定」小节；禁用「编辑」的完整原因必须按
    #    DESIGN §3.1 分成「需发起变更单」与「字段均被锁定」两支——恒返回变更单文案会让
    #    终态需求指向一条实际走不通的入口。
    local copy_body
    { grep -q 'editability-verify' "${ROOT}/docs/teamflow/memory.md" 2>/dev/null \
        && grep -q '需求可编辑性矩阵' "${ROOT}/docs/teamflow/memory.md" 2>/dev/null \
        && grep -q '可编辑性判定' "${ROOT}/docs/teamflow/REQUIREMENT-FLOW.md" 2>/dev/null \
        && grep -q 'FIELD_LOCKED' "${ROOT}/docs/teamflow/REQUIREMENT-FLOW.md" 2>/dev/null; } \
        || wire+=("文档基线未回写（memory 缺 editability-verify/矩阵约定，或 REQUIREMENT-FLOW 缺可编辑性小节）")
    copy_body="$(sed -n '/export function editDisabledReason/,/^}/p' "${ROOT}/web/src/views/req/editability.ts" 2>/dev/null)"
    [[ "$copy_body" == *'需发起变更单'* && "$copy_body" == *'字段均被锁定'* ]] \
        || wire+=("editDisabledReason 未按 DESIGN §3.1 分支（终态仍指向走不通的变更入口）")
    local wire_ok=1 wire_fail=""
    (( ${#wire[@]} == 0 )) || { wire_ok=0; wire_fail="交付面不符：$(printf '%s；' "${wire[@]}")"; }
    sck "$wire_ok" "交付面干净：pom/package.json 未改、无新迁移、Makefile 薄目标 + test-all 在 change-verify 之后、接入共享夹具、文档基线已回写、禁用文案按 DESIGN 分支" \
        "$wire_fail" \
        "本轮无迁移/无新依赖；门禁不接线等于没做，顺序错会在半成品库上跑可编辑性"
}

# --------------------------------------------------------------------------- S2 三组对照

ROW_DIFF=""
row_ok() { # row_ok <type> <status> <id> → 0/1，细节写 ROW_DIFF；注意 ck 的约定是 1=通过，调用点须换算 rc
    local t="$1" s="$2" id="$3" a_e a_c a_l e_e e_c e_l
    detail_get "$id"
    if [[ "$DB_CODE" != "200" ]]; then
        ROW_DIFF="detail HTTP ${DB_CODE}: $(printf '%s' "$DB_BODY" | head -c 160)"
        return 1
    fi
    a_e="$(detail_array_csv "$DB_BODY" editableFields)"
    a_c="$(detail_array_csv "$DB_BODY" changeRequiredFields)"
    a_l="$(detail_array_csv "$DB_BODY" lockedFields)"
    e_e="$(expected_array_csv "$t" "$s" EDIT)"
    e_c="$(expected_array_csv "$t" "$s" CHANGE_REQUIRED)"
    e_l="$(expected_array_csv "$t" "$s" LOCKED)"
    ROW_DIFF="editable[$a_e] 期望[$e_e] | changeRequired[$a_c] 期望[$e_c] | locked[$a_l] 期望[$e_l]"
    if [[ "$a_e" == "$e_e" && "$a_c" == "$e_c" && "$a_l" == "$e_l" ]]; then
        return 0
    fi
    return 1
}

# --------------------------------------------------------------------------- 主流程

F_BUS_EARLY=""
F_BUS_POOL=""
F_BUS_CLOSED=""
F_PDR_EARLY=""
F_PDR_DEV=""
F_PDR_CLOSED=""
F_PDR_SCHED=""
F_RD_TODO=""
F_RD_DEV=""
F_RD_DONE=""

run_main() {
    local probe="" t16="" any_req="" rc=0

    section "前置检查（缺一项直接 die，不 skip）"
    need_cmd curl || die "缺少 curl"
    need_cmd jq || die "缺少 jq"
    need_cmd mysql || die "缺少 mysql 客户端（db_q 依赖它）"
    req_ready || die "req-service 未就绪：${REQ_URL}/internal/health —— 先 make platform-up"
    db_up || die "MySQL 未就绪：${PLATFORM_DB_URL} —— 先 make platform-up"

    TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${T1_TENANT}' LIMIT 1")"
    [[ -n "$TID" ]] || die "租户 ${T1_TENANT} 不存在 —— 先 make platform-seed"
    DOMAIN_ID="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
    [[ -n "$DOMAIN_ID" ]] || die "租户 ${TID} 没有业务域 —— 先 make platform-demo-reset"

    gate_fixture_begin || die "gate-fixture 起跑失败（见上方 [gate] 输出）"
    platform_login "$T1_TENANT" "$ADMIN_USER" "$ADMIN_PASS" >/dev/null 2>&1 \
        || die "admin 登录失败 —— 检查种子账号 ${ADMIN_USER}/${ADMIN_PASS}"
    ADMIN_TOKEN="$API_TOKEN"
    ADMIN_TENANT="$API_TENANT"
    as_admin

    # 运行中的 req-service 必须是 r43 之后的构建：detail 没有三数组时所有断言都会以「全锁定」失败，
    # 那种红会让人误以为是矩阵写错，所以这里直接 die 并给升级命令。
    any_req="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
    [[ -n "$any_req" ]] || die "租户下没有任何需求 —— 先 make platform-demo-reset"
    api_call GET "/api/req/requirements/${any_req}"
    probe="$(printf '%s' "$HTTP_BODY" | jq -r 'has("editableFields") and has("changeRequiredFields") and has("lockedFields")' 2>/dev/null)"
    [[ "$probe" == "true" ]] \
        || die "detail 缺三数组（HTTP ${HTTP_CODE}）：运行中的 req-service 还是 r43 之前的构建 —— make platform-down && make platform-up"

    mkdir -p "$REPORT_DIR"
    FAIL_LOG="${REPORT_DIR}/failures-${RTAG}.txt"
    : >"$FAIL_LOG"

    # ---------------------------------------------------------------- S1 静态契约
    section "S1 静态契约（契约常量 + 单一矩阵 + 前端判定 + 交付面，${STATIC_TOTAL} 条）"
    static_checks

    # ---------------------------------------------------------------- 夹具
    section "夹具（10 个状态位，标题带 ${RTAG}，退出复位）"
    F_BUS_EARLY="$(create_req BUS "r43-早期BUS-${RTAG}")"
    F_BUS_POOL="$(create_req BUS "r43-入池BUS-${RTAG}")"
    F_BUS_CLOSED="$(create_req BUS "r43-终态BUS-${RTAG}")"
    [[ -n "$F_BUS_EARLY" && -n "$F_BUS_POOL" && -n "$F_BUS_CLOSED" ]] || die "创建 BUS 夹具失败：${HTTP_BODY:0:200}"
    as_admin
    gate_pool_bus "$F_BUS_POOL" "r43 入池BUS" || die "把 BUS ${F_BUS_POOL} 推到 POOLED 失败（见上方 [gate] 输出）"
    as_admin

    F_PDR_EARLY="$(create_req PDR "r43-早期PDR-${RTAG}" "$F_BUS_POOL")"
    F_PDR_DEV="$(create_req PDR "r43-开发中PDR-${RTAG}" "$F_BUS_POOL")"
    F_PDR_CLOSED="$(create_req PDR "r43-终态PDR-${RTAG}" "$F_BUS_POOL")"
    F_PDR_SCHED="$(create_req PDR "r43-已排期PDR-${RTAG}" "$F_BUS_POOL")"
    [[ -n "$F_PDR_EARLY" && -n "$F_PDR_DEV" && -n "$F_PDR_CLOSED" && -n "$F_PDR_SCHED" ]] \
        || die "创建 PDR 夹具失败（父 BUS 必须是 POOLED）：${HTTP_BODY:0:200}"

    set_status "$F_PDR_SCHED" SCHEDULED || die "PDR ${F_PDR_SCHED} 置 SCHEDULED 失败"
    F_RD_TODO="$(create_req RD "r43-早期RD-${RTAG}" "$F_PDR_SCHED")"
    F_RD_DEV="$(create_req RD "r43-开发中RD-${RTAG}" "$F_PDR_SCHED")"
    F_RD_DONE="$(create_req RD "r43-终态RD-${RTAG}" "$F_PDR_SCHED")"
    [[ -n "$F_RD_TODO" && -n "$F_RD_DEV" && -n "$F_RD_DONE" ]] \
        || die "创建 RD 夹具失败（父 PDR 必须是 SCHEDULED/IN_DEV）：${HTTP_BODY:0:200}"

    set_status "$F_BUS_CLOSED" CLOSED || die "BUS ${F_BUS_CLOSED} 置 CLOSED 失败"
    set_status "$F_PDR_DEV" IN_DEV || die "PDR ${F_PDR_DEV} 置 IN_DEV 失败"
    set_status "$F_PDR_CLOSED" ACCEPTED_CLOSED || die "PDR ${F_PDR_CLOSED} 置 ACCEPTED_CLOSED 失败"
    set_status "$F_RD_DEV" IN_DEV || die "RD ${F_RD_DEV} 置 IN_DEV 失败"
    set_status "$F_RD_DONE" DONE || die "RD ${F_RD_DONE} 置 DONE 失败"
    printf '  [fixture] BUS %s/%s/%s PDR %s/%s/%s/%s RD %s/%s/%s\n' \
        "$F_BUS_EARLY" "$F_BUS_POOL" "$F_BUS_CLOSED" "$F_PDR_EARLY" "$F_PDR_DEV" "$F_PDR_CLOSED" \
        "$F_PDR_SCHED" "$F_RD_TODO" "$F_RD_DEV" "$F_RD_DONE"

    # ---------------------------------------------------------------- S2 三组对照
    section "S2 三组对照（3 类型 × 3 状态：三条数组逐字段对拍矩阵）"
    row_ok BUS SUBMITTED "$F_BUS_EARLY"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "BUS/SUBMITTED（早期）三数组与矩阵逐字一致" \
        "矩阵不符（BUS/SUBMITTED）：${ROW_DIFF}" \
        "早期态 7 个内容字段应为 EDIT/EARLY_STAGE，type/parentId/estimateDays/ownerUserId 保持 LOCKED"
    row_ok BUS POOLED "$F_BUS_POOL"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "BUS/POOLED（已入池未终态）三数组与矩阵逐字一致" \
        "矩阵不符（BUS/POOLED）：${ROW_DIFF}" \
        "已入池后内容字段应转 CHANGE_REQUIRED；domainIds/sonarProjectKey 仍是 EDIT/OPEN_STATE"
    row_ok BUS CLOSED "$F_BUS_CLOSED"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "BUS/CLOSED（终态）三数组与矩阵逐字一致" \
        "矩阵不符（BUS/CLOSED）：${ROW_DIFF}" \
        "终态必须整行 LOCKED/TERMINAL（type 仍是 IMMUTABLE）；终态可读不可写是 AC-10 的底线"
    row_ok PDR PENDING_ANALYSIS "$F_PDR_EARLY"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "PDR/PENDING_ANALYSIS（早期）三数组与矩阵逐字一致" \
        "矩阵不符（PDR/PENDING_ANALYSIS）：${ROW_DIFF}" \
        "PDR 早期只有 PENDING_ANALYSIS；estimateDays 由 PARENT_ROLLUP 先拦"
    row_ok PDR IN_DEV "$F_PDR_DEV"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "PDR/IN_DEV（受控）三数组与矩阵逐字一致" \
        "矩阵不符（PDR/IN_DEV）：${ROW_DIFF}" \
        "开dev后内容字段必须走变更单；type 不可改、人天仍由父级汇总"
    row_ok PDR ACCEPTED_CLOSED "$F_PDR_CLOSED"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "PDR/ACCEPTED_CLOSED（终态）三数组与矩阵逐字一致" \
        "矩阵不符（PDR/ACCEPTED_CLOSED）：${ROW_DIFF}" \
        "PDR 终态是 ACCEPTED_CLOSED（不是 DELIVERED）；整行 LOCKED 才能让详情不再出现编辑入口"
    row_ok RD TODO "$F_RD_TODO"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "RD/TODO（早期）三数组与矩阵逐字一致" \
        "矩阵不符（RD/TODO）：${ROW_DIFF}" \
        "RD 早期只有 TODO；RD 的 estimateDays 是唯一 EDIT 的人天（需求方正源）"
    row_ok RD IN_DEV "$F_RD_DEV"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "RD/IN_DEV（受控）三数组与矩阵逐字一致" \
        "矩阵不符（RD/IN_DEV）：${ROW_DIFF}" \
        "RD 进入 IN_DEV 后内容与人天都转 CHANGE_REQUIRED（人天 409 但可走变更单，不是 400）"
    row_ok RD DONE "$F_RD_DONE"
    rc=$?
    ck "$([[ $rc -eq 0 ]] && echo 1 || echo 0)" "RD/DONE（终态）三数组与矩阵逐字一致" \
        "矩阵不符（RD/DONE）：${ROW_DIFF}" \
        "RD 终态 DONE 整行 LOCKED/TERMINAL；终态同值 PUT 仍要 200（S4-④）"

    # ---------------------------------------------------------------- S3 字段特例
    section "S3 字段特例（不可变/父级汇总/指派/域/未知状态）"
    local bad=() r="" state_pair st_type="" st_status="" st_id=""
    for state_pair in "BUS SUBMITTED $F_BUS_EARLY" "BUS POOLED $F_BUS_POOL" "BUS CLOSED $F_BUS_CLOSED" \
                      "PDR PENDING_ANALYSIS $F_PDR_EARLY" "PDR IN_DEV $F_PDR_DEV" "PDR ACCEPTED_CLOSED $F_PDR_CLOSED" \
                      "RD TODO $F_RD_TODO" "RD IN_DEV $F_RD_DEV" "RD DONE $F_RD_DONE"; do
        read -r st_type st_status st_id <<<"$state_pair"
        r="$(rule_of "$st_id" type)"
        [[ "$r" == "LOCKED:IMMUTABLE" ]] || bad+=("${st_type}/${st_status}→${r:-MISSING}")
    done
    local type_ok=1 type_fail=""
    (( ${#bad[@]} == 0 )) || { type_ok=0; type_fail="type 判定不符：$(printf '%s ' "${bad[@]}")"; }
    ck "$type_ok" "type 在 3 类型 × 9 状态位全部 LOCKED/IMMUTABLE（含终态）" "$type_fail" \
        "type 必须排在终态判定之前：类型变了整张状态表都会变，任何时候都不许直接改"

    r="$(rule_of "$F_BUS_EARLY" parentId)"
    state_pair="$(rule_of "$F_BUS_POOL" parentId)"
    local r_closed
    r_closed="$(rule_of "$F_BUS_CLOSED" parentId)"
    ck "$([[ "$r" == "LOCKED:NO_PARENT" && "$state_pair" == "LOCKED:NO_PARENT" && "$r_closed" == "LOCKED:TERMINAL" ]] && echo 1 || echo 0)" \
        "BUS parentId：SUBMITTED/POOLED=LOCKED/NO_PARENT，CLOSED=LOCKED/TERMINAL（终态优先）" \
        "BUS parentId 不符：SUBMITTED=${r:-?} POOLED=${state_pair:-?} CLOSED=${r_closed:-?}" \
        "BUS 是顶层需求，挂父级只能走分解；终态判定在 NO_PARENT 之前，所以 CLOSED 报 TERMINAL"

    r="$(rule_of "$F_BUS_EARLY" estimateDays)"
    state_pair="$(rule_of "$F_PDR_EARLY" estimateDays)"
    ck "$([[ "$r" == "LOCKED:PARENT_ROLLUP" && "$state_pair" == "LOCKED:PARENT_ROLLUP" ]] && echo 1 || echo 0)" \
        "BUS/PDR estimateDays 早期态也是 LOCKED/PARENT_ROLLUP（不许被子需求汇总覆盖）" \
        "非 RD 人天判定不符：BUS=${r:-?} PDR=${state_pair:-?}" \
        "父级人天由子需求汇总：早期态也不能直接写，否则汇总值会被父行覆盖"

    r="$(rule_of "$F_RD_TODO" estimateDays)"
    state_pair="$(rule_of "$F_RD_DEV" estimateDays)"
    ck "$([[ "$r" == "EDIT:EARLY_STAGE" && "$state_pair" == "CHANGE_REQUIRED:CHANGE_REQUIRED" ]] && echo 1 || echo 0)" \
        "RD estimateDays：TODO=EDIT/EARLY_STAGE、IN_DEV=CHANGE_REQUIRED（人天来源在 RD）" \
        "RD 人天判定不符：TODO=${r:-?} IN_DEV=${state_pair:-?}" \
        "RD 是工时正源：早期可直改，进入开发后必须走变更单（apply 后重算 WBS，不是 400）"

    local d_early d_pool d_closed s_early s_pool s_closed
    d_early="$(rule_of "$F_BUS_EARLY" domainIds)"; s_early="$(rule_of "$F_BUS_EARLY" sonarProjectKey)"
    d_pool="$(rule_of "$F_BUS_POOL" domainIds)"; s_pool="$(rule_of "$F_BUS_POOL" sonarProjectKey)"
    d_closed="$(rule_of "$F_BUS_CLOSED" domainIds)"; s_closed="$(rule_of "$F_BUS_CLOSED" sonarProjectKey)"
    ck "$([[ "$d_early" == "EDIT:OPEN_STATE" && "$s_early" == "EDIT:OPEN_STATE" \
        && "$d_pool" == "EDIT:OPEN_STATE" && "$s_pool" == "EDIT:OPEN_STATE" \
        && "$d_closed" == "LOCKED:TERMINAL" && "$s_closed" == "LOCKED:TERMINAL" ]] && echo 1 || echo 0)" \
        "domainIds/sonarProjectKey：早期与受控都 EDIT/OPEN_STATE，终态 LOCKED/TERMINAL" \
        "域/技术键判定不符：early=${d_early:-?}/${s_early:-?} pool=${d_pool:-?}/${s_pool:-?} closed=${d_closed:-?}/${s_closed:-?}" \
        "域归属与技术键不改变需求内容，所以非终态一律可改；终态由 TERMINAL 统一拦（详情仍可读）"

    r="$(rule_of "$F_BUS_EARLY" ownerUserId)"
    state_pair="$(rule_of "$F_BUS_POOL" ownerUserId)"
    ck "$([[ "$r" == "LOCKED:ASSIGN_FLOW" && "$state_pair" == "LOCKED:ASSIGN_FLOW" ]] && echo 1 || echo 0)" \
        "ownerUserId 非终态一律 LOCKED/ASSIGN_FLOW（负责人只能走指派）" \
        "负责人判定不符：SUBMITTED=${r:-?} POOLED=${state_pair:-?}" \
        "直接改负责人会绕过指派审批与交接审计；改派必须走「指派」动作"

    set_status "$F_RD_TODO" BOGUS || die "把 RD ${F_RD_TODO} 置 BOGUS 失败（未知状态夹具）"
    detail_get "$F_RD_TODO"
    local bogus_bad=() arr
    if [[ "$DB_CODE" == "200" ]]; then
        for f in "${CANON[@]}"; do
            arr="$(printf '%s' "$DB_BODY" | jq -r --arg f "$f" '
                ((.editableFields // []) + (.changeRequiredFields // []) + (.lockedFields // []))
                | map(select(.field == $f)) | .[0] | if . == null then "MISSING" else .reasonCode end')"
            [[ "$arr" == "UNKNOWN_STATUS" ]] || bogus_bad+=("${f}→${arr:-MISSING}")
        done
    else
        bogus_bad+=("HTTP ${DB_CODE}")
    fi
    local bogus_ok=1 bogus_fail=""
    (( ${#bogus_bad[@]} == 0 )) || { bogus_ok=0; bogus_fail="未知状态未 fail-closed：$(printf '%s ' "${bogus_bad[@]}")"; }
    ck "$bogus_ok" "未知状态（SQL 置 BOGUS）11 字段全部 LOCKED/UNKNOWN_STATUS（fail-closed）" "$bogus_fail" \
        "脏数据/新状态上线时最危险的默认是「可编辑」；未知必须整行锁死并给 UNKNOWN_STATUS"
    set_status "$F_RD_TODO" TODO || die "把 RD ${F_RD_TODO} 恢复 TODO 失败"

    # ---------------------------------------------------------------- S4 PUT 错误码
    section "S4 PUT 错误码（FIELD_LOCKED 优先 / CHANGE_REQUIRED / 同值 200）"
    put_req "$F_BUS_CLOSED" "{\"title\":\"r43-终态直改-${RTAG}\"}"
    local code="$PB_CODE" ecode err_f err_r
    ecode="$(err_code)"; err_f="$(err_field)"; err_r="$(err_reason)"
    ck "$([[ "$code" == "409" && "$ecode" == "FIELD_LOCKED" && "$err_f" == "title" && "$err_r" == "TERMINAL" \
        && "$(expected_decision BUS CLOSED title)" == "LOCKED" ]] && echo 1 || echo 0)" \
        "终态改 title → 409 FIELD_LOCKED（field=title、reasonCode=TERMINAL）" \
        "终态 PUT 不符：http=${code} code=${ecode:-?} field=${err_f:-?} reason=${err_r:-?}（期望 409/FIELD_LOCKED/title/TERMINAL）" \
        "终态由 400 改判 409：前端要按 FIELD_LOCKED 给「不可改」而不是「参数错」；field 必须是首个命中字段"

    put_req "$F_PDR_DEV" "{\"title\":\"r43-受控直改-${RTAG}\",\"priority\":\"P0\"}"
    local f_fields
    code="$PB_CODE"; ecode="$(err_code)"; f_fields="$(err_fields)"
    ck "$([[ "$code" == "409" && "$ecode" == "CHANGE_REQUIRED" && "$f_fields" == "title,priority" \
        && -n "$(err_endpoint)" ]] && echo 1 || echo 0)" \
        "受控多字段 → 409 CHANGE_REQUIRED（fields=title,priority 规范序 + changeEndpoint）" \
        "受控 PUT 不符：http=${code} code=${ecode:-?} fields=${f_fields:-?} endpoint=$(err_endpoint)" \
        "fields 必须按规范序（不是请求键序），前端才能稳定高亮；changeEndpoint 让横幅能直接开变更单"

    put_req "$F_PDR_DEV" "{\"title\":\"r43-混合直改-${RTAG}\",\"estimateDays\":3,\"type\":\"RD\"}"
    code="$PB_CODE"; ecode="$(err_code)"; f_fields="$(err_fields)"; err_f="$(err_field)"
    ck "$([[ "$code" == "409" && "$ecode" == "FIELD_LOCKED" && "$f_fields" == "estimateDays,type" \
        && "$err_f" == "estimateDays" ]] && echo 1 || echo 0)" \
        "混合改动（受控 title + 锁定 estimateDays/type）只报 LOCKED 一类：FIELD_LOCKED fields=estimateDays,type" \
        "混合改动分类不符：http=${code} code=${ecode:-?} field=${err_f:-?} fields=${f_fields:-?}" \
        "一次只报一类：用户先解决「不能改」的字段，再谈走变更单；两类混报会让横幅同时给 CTA 与禁止，前后矛盾"

    detail_get "$F_BUS_CLOSED"
    local upd_before aud_before same_body upd_after aud_after
    upd_before="$(jqv '.view.updatedAt // empty' "$DB_BODY")"
    aud_before="$(db_count audit_log "tenant_id=${TID} AND action='UPDATE' AND biz_id=${F_BUS_CLOSED}")"
    same_body="$(printf '%s' "$DB_BODY" | jq -c \
        '{title:.view.title, description:.view.description, priority:.view.priority,
          expectedDeliveryDate:.view.expectedDeliveryDate} | with_entries(select(.value != null))')"
    put_req "$F_BUS_CLOSED" "$same_body"
    code="$PB_CODE"
    detail_get "$F_BUS_CLOSED"
    upd_after="$(jqv '.view.updatedAt // empty' "$DB_BODY")"
    aud_after="$(db_count audit_log "tenant_id=${TID} AND action='UPDATE' AND biz_id=${F_BUS_CLOSED}")"
    ck "$([[ "$code" == "200" && "$upd_before" == "$upd_after" && "${aud_before:-x}" == "${aud_after:-y}" ]] && echo 1 || echo 0)" \
        "终态同值 PUT → 200 且 updated_at/UPDATE 审计条数都不变（${aud_after:-?} 条）" \
        "同值短路不符：http=${code} updatedAt ${upd_before:-?}→${upd_after:-?} 审计 ${aud_before:-?}→${aud_after:-?}" \
        "同值是「没改」：既不该 409，也不该写库写审计；否则前端每次保存都会给终态刷一条假审计"

    put_req "$F_PDR_EARLY" '{"estimateDays":12}'
    code="$PB_CODE"; ecode="$(err_code)"; err_r="$(err_reason)"; err_f="$(err_field)"
    ck "$([[ "$code" == "409" && "$ecode" == "FIELD_LOCKED" && "$err_r" == "PARENT_ROLLUP" \
        && "$err_f" == "estimateDays" ]] && echo 1 || echo 0)" \
        "非 RD 改 estimateDays → 409 FIELD_LOCKED/PARENT_ROLLUP（取代 r40 的 400）" \
        "非 RD 人天判定不符：http=${code} code=${ecode:-?} field=${err_f:-?} reason=${err_r:-?}（期望 409/FIELD_LOCKED/estimateDays/PARENT_ROLLUP）" \
        "「父级人天由子需求汇总」是权限规则不是参数错误；409 + field 才能让前端把该行标成只读并给原因"

    # ---------------------------------------------------------------- S5 响应一致性
    section "S5 响应一致性（数组形状 / 两次一致 / 旧投影 / 接口与 PUT 同源）"
    detail_get "$F_BUS_POOL"
    local shape_ok twice_a twice_b
    shape_ok="$(printf '%s' "$DB_BODY" | jq -r '
        [(.editableFields // []), (.changeRequiredFields // []), (.lockedFields // [])]
        | map(map((keys | sort) == ["field","reasonCode"]) | all) | all')"
    ck "$([[ "$shape_ok" == "true" ]] && echo 1 || echo 0)" \
        "三数组元素键恰为 {field,reasonCode}（无 reasonText/pill 等多余字段）" \
        "数组元素形状不符：${shape_ok:-?}，样例 $(printf '%s' "$DB_BODY" | jq -c '.editableFields[0]? // {}')" \
        "文案只在 web/src/views/req/editability.ts 映射；后端多塞一个字段就会让前端三处渲染开始漂移"

    detail_get "$F_BUS_POOL"
    twice_a="$(printf '%s' "$DB_BODY" | jq -c '[.editableFields, .changeRequiredFields, .lockedFields]')"
    detail_get "$F_BUS_POOL"
    twice_b="$(printf '%s' "$DB_BODY" | jq -c '[.editableFields, .changeRequiredFields, .lockedFields]')"
    ck "$([[ -n "$twice_a" && "$twice_a" == "$twice_b" ]] && echo 1 || echo 0)" \
        "同一需求两次 GET 的三数组完全一致（顺序与 reasonCode 都稳定）" \
        "两次 GET 不一致：第 1 次 ${twice_a:0:120} / 第 2 次 ${twice_b:0:120}" \
        "数组顺序必须是确定的（规范序），否则前端渲染顺序与截图对比会在两次刷新之间跳变"

    local proj_actual proj_expect
    proj_actual="$(printf '%s' "$DB_BODY" | jq -r '.permissions.editableFields // [] | join(",")')"
    proj_expect="$(expected_projection BUS POOLED)"
    ck "$([[ "$proj_actual" == "$proj_expect" ]] && echo 1 || echo 0)" \
        "permissions.editableFields == EDIT ∩ 旧七字段投影（[${proj_expect}]）" \
        "旧投影不符：实际[${proj_actual}] 期望[${proj_expect}]" \
        "旧键是兼容层的 deprecated 视图，必须由同一次矩阵判定投影出来；另算一份就会出现两个真相"

    local sent=0 same_fail=() val body
    detail_get "$F_BUS_EARLY"
    local early_json="$DB_BODY"
    for f in "${UI7[@]}"; do
        [[ "$(printf '%s' "$early_json" | jq -r --arg f "$f" '
            ((.editableFields // []) | map(select(.field == $f)) | length)')" == "1" ]] || continue
        val="$(printf '%s' "$early_json" | jq -r --arg f "$f" '.view[$f] // empty')"
        [[ -n "$val" ]] || continue
        case "$f" in
            title | description | priority) body="$(jq -cn --arg k "$f" --arg v "$val" '{($k):$v}')" ;;
            expectedDeliveryDate) body="$(jq -cn --arg v "$val" '{expectedDeliveryDate:$v}')" ;;
            estimateDays | releaseId | parentId) body="$(jq -cn --arg k "$f" --argjson v "$val" '{($k):$v}')" ;;
            *) continue ;;
        esac
        put_req "$F_BUS_EARLY" "$body"
        sent=$((sent + 1))
        [[ "$PB_CODE" == "200" ]] || same_fail+=("${f}→${PB_CODE}")
    done
    ck "$([[ "$sent" -ge 3 && ${#same_fail[@]} -eq 0 ]] && echo 1 || echo 0)" \
        "detail 标记 EDIT 的 UI 字段逐个同值 PUT 全 200（8 个中命中 ${sent} 个）" \
        "同值 PUT 不符：尝试 ${sent} 个，失败 $(printf '%s ' "${same_fail[@]-}")" \
        "「页面说能改」必须等于「PUT 接受」：标记 EDIT 的字段连原值都存不回去，说明两条判定不同源"

    local lock_field lock_reason cr_field detail_lock_reason put_lock_reason put_lock_code put_cr_code
    detail_get "$F_BUS_POOL"
    lock_field="$(printf '%s' "$DB_BODY" | jq -r '.lockedFields[0].field // empty')"
    lock_reason="$(printf '%s' "$DB_BODY" | jq -r '.lockedFields[0].reasonCode // empty')"
    cr_field="$(printf '%s' "$DB_BODY" | jq -r '.changeRequiredFields[0].field // empty')"
    case "$lock_field" in
        estimateDays) put_req "$F_BUS_POOL" '{"estimateDays":9}' ;;
        parentId) put_req "$F_BUS_POOL" '{"parentId":1}' ;;
        ownerUserId) put_req "$F_BUS_POOL" '{"ownerUserId":1}' ;;
        type) put_req "$F_BUS_POOL" '{"type":"RD"}' ;;
        *) put_req "$F_BUS_POOL" "{\"${lock_field:-title}\":\"r43-锁定探针-${RTAG}\"}" ;;
    esac
    put_lock_code="$PB_CODE"
    put_lock_reason="$(err_reason)"
    put_req "$F_BUS_POOL" "{\"${cr_field:-title}\":\"r43-受控探针-${RTAG}\"}"
    put_cr_code="$PB_CODE"
    ck "$([[ "$put_lock_code" == "409" && "$(err_code)" == "CHANGE_REQUIRED" && "$put_cr_code" == "409" \
        && "$put_lock_reason" == "$lock_reason" && -n "$lock_field" && -n "$cr_field" ]] && echo 1 || echo 0)" \
        "接口与 PUT 同源：locked[0]=${lock_field}/${lock_reason:-?} → FIELD_LOCKED 同 reasonCode；changeRequired[0]=${cr_field:-?} → CHANGE_REQUIRED" \
        "同源不符：锁定 ${lock_field:-?}→http=${put_lock_code} reason=${put_lock_reason:-?}（期望 ${lock_reason:-?}）；受控 ${cr_field:-?}→http=${put_cr_code} code=$(err_code)" \
        "数组与 PUT 必须读同一张矩阵：数组说 LOCKED 而 PUT 回 CHANGE_REQUIRED，前端给的入口就是死路"

    # ---------------------------------------------------------------- S6 变更记录与负向
    section "S6 变更记录 fields 与负向自检"
    local cid="" list_fields="" list_code=""
    api_call POST "/api/req/requirements/${F_BUS_EARLY}/changes" \
        "$(jq -cn --arg t CONTENT --arg r "r43 字段级变更-${RTAG}" \
            --arg ti "r43-变更后标题-${RTAG}" '{changeType:$t,reason:$r,patch:{priority:"P0",title:$ti}}')"
    cid="$(jqv '.id // empty' "$HTTP_BODY")"
    api_call GET "/api/req/requirements/${F_BUS_EARLY}/changes?page=1&size=50"
    list_code="$HTTP_CODE"
    list_fields="$(printf '%s' "$HTTP_BODY" | jq -r --argjson c "${cid:-0}" \
        '[.items[]? | select(.id == $c)][0].fields // [] | join(",")' 2>/dev/null)"
    ck "$([[ -n "$cid" && "$list_code" == "200" && "$list_fields" == "title,priority" ]] && echo 1 || echo 0)" \
        "变更列表行 fields == 真实 before/after 差集（规范序 title,priority）" \
        "字段级变更记录不符：http=${list_code} changeId=${cid:-?} fields=[${list_fields}]（期望 title,priority）" \
        "fields 由行上的 before/after JSON 就地计算：多一次请求或另存一份列表都会与真实差集漂移"

    db_q "UPDATE req_change SET before_json='{}', after_json='{}' WHERE tenant_id=${TID} AND id=${cid:-0}" >/dev/null
    [[ "$(db_q "SELECT before_json FROM req_change WHERE tenant_id=${TID} AND id=${cid:-0}")" == "{}" ]] \
        || die "空快照夹具写入失败：req_change ${cid:-?} 的 before_json 未变成 {} —— 这一条会退化成拿真实快照做断言"
    api_call GET "/api/req/requirements/${F_BUS_EARLY}/changes?page=1&size=50"
    list_code="$HTTP_CODE"
    list_fields="$(printf '%s' "$HTTP_BODY" | jq -r --argjson c "${cid:-0}" '
        [.items[]? | select(.id == $c)][0]
        | if . == null then "MISSING"
          elif (.fields // null) == null then "NO_FIELDS"
          else (.fields | join(",")) end' 2>/dev/null)"
    ck "$([[ "$list_code" == "200" && "$list_fields" == "" ]] && echo 1 || echo 0)" \
        "历史空快照行（before/after={}）fields == [] 且列表仍 200（降级不 500）" \
        "空快照降级不符：http=${list_code} fields=[${list_fields}]（期望空数组；MISSING/NO_FIELDS 视为未降级）" \
        "r40 之前的行没有字段证据：返回空数组，绝不能因为算不出差集就让整页 500"

    local neg_out neg_rc neg_ok=0 neg_msg="" neg_fail="" neg_fix=""
    if [[ "$MUTATE" == "always-edit" ]]; then
        neg_ok=1
        neg_msg="负向自检递归保护：MUTATE 模式下不再嵌套自检（本轮本身已在负向模式）"
    else
        neg_out="$(mktemp)"
        EDITABILITY_MUTATE=always-edit bash "$0" --once >"$neg_out" 2>&1
        neg_rc=$?
        if [[ $neg_rc -ne 0 ]] && grep -q 'FAIL' "$neg_out" \
            && grep -qE '矩阵|FIELD_LOCKED|期望' "$neg_out" \
            && ! grep -q '全部 36 条通过' "$neg_out"; then
            neg_ok=1
            neg_msg="负向自检成立：EDITABILITY_MUTATE=always-edit 让门禁变红（rc=${neg_rc}），断言不是空转"
        else
            neg_fail="负向自检不成立：rc=${neg_rc}，输出里没有期望的变红信号（矩阵断言可能已被改写成恒真）"
            neg_fix="负向开关必须能把「期望 EDIT vs 实际 LOCKED」顶出来；先看 $(basename "$neg_out") 的第一条 FAIL"
        fi
        rm -f "$neg_out"
    fi
    ck "$neg_ok" "$neg_msg" "$neg_fail" "$neg_fix"

    local reset_rc="" residual="" tag_rows=""
    gate_fixture_end
    reset_rc=$?
    tag_rows="$(db_count requirement "tenant_id=${TID} AND title LIKE '%${RTAG}%'")"
    residual="$(db_count requirement "tenant_id=${TID}")"
    ck "$([[ $reset_rc == 0 && "${tag_rows:-x}" == "0" && "${residual:-x}" == "${GATE_FIXTURE_REQUIRED_REQUIREMENTS}" ]] && echo 1 || echo 0)" \
        "退出复位成功（platform-demo-reset + 基线断言），本轮 TAG 需求 ${tag_rows:-?} 条、基线需求 ${residual:-?} 条" \
        "复位不符：rc=${reset_rc} tag 需求=${tag_rows:-?} 基线需求=${residual:-?}（期望 0/${GATE_FIXTURE_REQUIRED_REQUIREMENTS}）" \
        "复位是门禁的一部分：手工跑 bash scripts/platform-demo-reset.sh 看首个 FAIL；不许 WARN 降级"
}

expected_projection() { # expected_projection <type> <status> → EDIT ∩ UI7（规范序）
    local t="$1" s="$2" f out=()
    for f in "${CANON[@]}"; do
        [[ " ${UI7[*]} " == *" $f "* ]] || continue
        [[ "$(expected_decision "$t" "$s" "$f")" == "EDIT" ]] && out+=("$f")
    done
    local IFS=,
    printf '%s' "${out[*]-}"
}

# --------------------------------------------------------------------------- 入口

if ((STATIC_ONLY == 1)); then
    section "静态检查（免栈，${STATIC_TOTAL} 条）"
    static_checks
    printf '\nEDITABILITY_STATIC TOTAL=%d CASE=%d PASS=%d\n' "$STATIC_TOTAL" "$SCASE" "$SPASS"
    if ((SCASE != STATIC_TOTAL)); then
        printf '\033[1;31m静态断言数与自声明分母不一致：CASE=%d ≠ TOTAL=%d\033[0m\n' "$SCASE" "$STATIC_TOTAL"
        exit 1
    fi
    ((SPASS == STATIC_TOTAL)) || exit 1
    printf '\033[1;32m静态检查全部 %d 条通过：契约/单一矩阵/前端判定/接线自洽\033[0m\n' "$STATIC_TOTAL"
    exit 0
fi

if [[ "$MODE" == "twice" ]]; then
    local_out1="$(mktemp)"
    local_out2="$(mktemp)"
    trap 'rm -f "$local_out1" "$local_out2"' EXIT
    bash "$0" --once >"$local_out1" 2>&1
    rc1=$?
    bash "$0" --once >"$local_out2" 2>&1
    rc2=$?
    l1="$(grep -E '^EDITABILITY_TOTAL=' "$local_out1" | tail -n1)"
    l2="$(grep -E '^EDITABILITY_TOTAL=' "$local_out2" | tail -n1)"
    printf '\n== 两次连跑 ==\n  第 1 轮 rc=%d %s\n  第 2 轮 rc=%d %s\n' "$rc1" "$l1" "$rc2" "$l2"
    if ((rc1 != 0)); then
        printf '\033[1;31m第 1 轮失败：\033[0m\n'
        grep -E '^(  \x1b\[1;31mFAIL|修复:)' "$local_out1" | head -n 20
        exit 1
    fi
    if ((rc2 != 0)); then
        printf '\033[1;31m第 2 轮失败（第一轮绿、第二轮红 = 夹具没复位干净）：\033[0m\n'
        tail -n 40 "$local_out2"
        exit 1
    fi
    if [[ -z "$l1" || "$l1" != "$l2" ]]; then
        printf '\033[1;31m两轮结果不一致：%s vs %s\033[0m\n' "${l1:-<空>}" "${l2:-<空>}"
        exit 1
    fi
    printf '\033[1;32m两次连跑结果一致（%s）：可重复，无顺序依赖\033[0m\n' "$l1"
    exit 0
fi

run_main

printf '\nEDITABILITY_TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$EDITABILITY_TOTAL" "$CASE" "$PASS" "$FAIL"
SUMMARY_FILE="${REPORT_DIR}/summary-${RTAG}.txt"
{
    printf 'r43 editability gate @ %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
    printf 'TOTAL=%d CASE=%d PASS=%d FAIL=%d\n' "$EDITABILITY_TOTAL" "$CASE" "$PASS" "$FAIL"
    printf 'RTAG=%s\n' "$RTAG"
    [[ -s "$FAIL_LOG" ]] && { printf -- '--- failures ---\n'; cat "$FAIL_LOG"; }
} >"$SUMMARY_FILE"
printf '  报告: %s\n' "$SUMMARY_FILE"

if ((CASE != EDITABILITY_TOTAL - STATIC_TOTAL)); then
    printf '\033[1;31m断言数与自声明分母不一致：CASE=%d ≠ %d（TOTAL %d − STATIC %d）\033[0m\n' \
        "$CASE" "$((EDITABILITY_TOTAL - STATIC_TOTAL))" "$EDITABILITY_TOTAL" "$STATIC_TOTAL"
    printf '修复: 某段被提前 die/return 掉了；断言必须线性执行，缺一条就是红\n'
    exit 1
fi
if ((FAIL > 0)); then
    printf '\033[1;31m有 %d 条断言失败\033[0m\n' "$FAIL"
    exit 1
fi
printf '\033[1;32m全部 %d 条通过：可编辑性矩阵在 3 类型 × 9 状态位上与 PUT 同源，字段级变更记录可追溯\033[0m\n' "$EDITABILITY_TOTAL"
exit 0
