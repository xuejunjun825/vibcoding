#!/usr/bin/env bash
# _lib.sh — 需求池运营门禁（scripts/pool-ops-verify.sh）的共享基座。Source it，不要执行。
#
# 只做五件事，不重复既有 lib.sh / gate-fixture.sh 的能力：
#   1. 带租户头的 HTTP 请求与 JSON 取值（复用 lib.sh 的 api_call / jget，只补错误体诊断）；
#   2. 断言登记与计数（编号 + 标题来自门禁脚本里的 POOL_MANIFEST，结尾打印 TOTAL/PASS/FAIL）；
#   3. 就绪轮询（禁止用 sleep 掩盖未就绪：30s 上限，超时打印哪一步没就绪）；
#   4. DB 小工具（jq 解析 API、db_q 复核落库，仓库禁止 scripts/ 调 python）；
#   5. 本轮夹具 id 登记（teardown 只按 id / GATE_TAG 精确删，不误伤演示数据）。
#
# 仓库既有约束（我按它写，不绕过）：
#   * scripts/tests/platform/scripts-contract.sh §5 禁止 scripts/ 下调起 Python；JSON 一律 jq；
#   * 宿主 bash 是 3.2：不使用关联数组与小写展开语法；
#   * 失败必须给「实际」+「修复」；接口未实现时记 FAIL 而不是崩掉。
#
# 用法（在门禁脚本里）：
#   source "${ROOT}/scripts/lib.sh"
#   source "${ROOT}/scripts/lib/gate-fixture.sh"   # gate_login_as / api_call / db_q
#   source "${ROOT}/scripts/tests/pool/_lib.sh"
#   POOL_MANIFEST=( "P1-1|标题" ... ); pool_manifest_parse
#   pool_login pm && pool_get /api/req/requirements/1/similar
#   pool_ck "P1-1" 1 "..." "修复建议"
#   pool_summary

if [[ -n "${POOL_LIB_LOADED:-}" ]]; then
    return 0
fi
POOL_LIB_LOADED=1

# --------------------------------------------------------------------------- 常量与状态

POOL_TENANT_CODE="${POOL_TENANT_CODE:-${PLATFORM_TENANT:-t1}}"
POOL_USER_PASS="${POOL_USER_PASS:-Passw0rd!}"
POOL_ADMIN_USER="${POOL_ADMIN_USER:-admin}"
POOL_ADMIN_PASS="${POOL_ADMIN_PASS:-Admin@12345}"
POOL_SNIPPET_LEN="${POOL_SNIPPET_LEN:-300}"

POOL_TOTAL=0
POOL_PASS=0
POOL_FAIL=0
# 已执行编号用空格分隔的字符串记录（bash 3.2 没有关联数组）。
POOL_SEEN=" "
POOL_ABORT_REASON=""

POOL_RED=$'\033[1;31m'
POOL_GREEN=$'\033[1;32m'
POOL_CYAN=$'\033[1;36m'
POOL_YELLOW=$'\033[1;33m'
POOL_RESET=$'\033[0m'

# --------------------------------------------------------------------------- 输出

pool_section() { printf '\n%s== %s ==%s\n' "${POOL_CYAN}" "$*" "${POOL_RESET}"; }
pool_info() { printf '  %s\n' "$*"; }
pool_warn() { printf '%s[warn]%s %s\n' "${POOL_YELLOW}" "${POOL_RESET}" "$*"; }
pool_fatal() { printf '%s[fatal]%s %s\n' "${POOL_RED}" "${POOL_RESET}" "$*" >&2; }

# 响应片段：单行 + 截断到 POOL_SNIPPET_LEN（默认 300 字），失败时贴进「实际」。
pool_snippet() {
    local body="${1:-${HTTP_BODY:-}}"
    printf '%s' "${body}" | tr '\n\r\t' '   ' | cut -c "1-${POOL_SNIPPET_LEN}"
}

# --------------------------------------------------------------------------- JSON

# pool_json <body> <jq-expr>：取标量；取不到输出空串。
pool_json() {
    local body="${1:-}" expr="${2:-.}"
    printf '%s' "${body}" | jq -r "(${expr}) // empty" 2>/dev/null || true
}

# pool_pick <body> <expr> [expr...]：按顺序取第一个非空值（字段命名未写死时的兜底）。
pool_pick() {
    local body="${1:-}" expr val
    shift || true
    for expr in "$@"; do
        val="$(printf '%s' "${body}" | jq -r "(${expr}) // empty" 2>/dev/null || true)"
        if [[ -n "${val}" && "${val}" != "null" ]]; then
            printf '%s' "${val}"
            return 0
        fi
    done
    return 0
}

# pool_len <body> <path>：数组/对象长度；不是容器时 0。
pool_len() {
    local body="${1:-}" path="${2:-.}" n
    n="$(printf '%s' "${body}" | jq -r "(${path}) | if type==\"array\" or type==\"object\" then length else 0 end" 2>/dev/null || true)"
    printf '%s' "${n:-0}"
}

# pool_bool <body> <path>：true/false；取不到输出空串（调用方必须把它当失败，不能当 false）。
pool_bool() {
    printf '%s' "${1:-}" | jq -r "(${2:-.}) | if type==\"boolean\" then tostring else empty end" 2>/dev/null || true
}

pool_urlenc() { printf '%s' "${1:-}" | jq -rn --arg s "$(printf '%s' "${1:-}")" '$s|@uri' 2>/dev/null || true; }

# --------------------------------------------------------------------------- HTTP

# 说明：登录与请求都走 lib.sh 的 api_call / platform_login（gate_login_as 封装），
# 这里只加语义化命名，避免每个断言都写 api_call GET ...。
pool_get() { api_call GET "$1"; }
pool_post() { api_call POST "$1" "${2:-}"; }
pool_delete() { api_call DELETE "$1"; }

pool_is2xx() { case "${1:-}" in 2??) return 0 ;; *) return 1 ;; esac; }
pool_is4xx() { case "${1:-}" in 4??) return 0 ;; *) return 1 ;; esac; }

# 业务错误码：兼容 {code}、{error:{code}}、{detail:{code}}。
pool_code() { pool_pick "${1:-${HTTP_BODY}}" '.code' '.error.code' '.detail.code'; }

# 业务 404 vs 路由未落地：Spring 的路由缺失 404 形如
# {"timestamp":…,"status":404,"error":"Not Found","path":…}——没有 code 字段；
# 业务 404 走全局异常处理，一定带 code（如 NOT_FOUND）。
# 断言「越权应被拒」时只能接受业务 404，否则路由没落地也会被误判成 PASS。
pool_biz_404() {
    local body="${1:-${HTTP_BODY}}"
    local c; c="$(pool_code "${body}")"
    [[ -n "${c}" ]]
}

# 失败详情：HTTP + 业务码 + 响应片段（≤300 字）。
pool_detail() {
    printf 'HTTP=%s code=%s body=%s' "${HTTP_CODE:-000}" "$(pool_code)" "$(pool_snippet)"
}

# 非 2xx 的统一判据文案：404/000 时点明「接口未就绪」，避免被当成业务断言红。
pool_transport_hint() {
    case "${HTTP_CODE:-000}" in
        404) printf '接口未就绪（404）：该路由尚未落地' ;;
        000) printf '网关/服务不可达（000）' ;;
        401 | 403) printf '鉴权失败（%s），先确认登录与租户头' "${HTTP_CODE}" ;;
        5??) printf '服务端错误（%s），先看 req-service 日志' "${HTTP_CODE}" ;;
        *) printf '非 2xx（%s）' "${HTTP_CODE}" ;;
    esac
}

# pool_login <user>：gate_login_as + 校验 token/租户头；失败打印原因返回 1。
pool_login() {
    local user="${1:-}"
    if [[ -z "${user}" ]]; then pool_fatal "pool_login 缺少用户名"; return 1; fi
    if ! gate_login_as "${user}"; then
        pool_fatal "登录失败：${POOL_TENANT_CODE}/${user}"
        return 1
    fi
    if [[ -z "${API_TOKEN:-}" || -z "${API_TENANT:-}" ]]; then
        pool_fatal "登录后 token/tenant 为空：${POOL_TENANT_CODE}/${user}"
        return 1
    fi
    return 0
}

# --------------------------------------------------------------------------- DB

# 一层薄包装：db_q 失败（表不存在/连接断）时不让 set -e 把诊断吞掉。
pool_sql() { db_q "$1" 2>/dev/null | head -n1; }
pool_sql_num() {
    local n
    n="$(pool_sql "$1")"
    case "${n:-}" in
        '' | *[!0-9-]*) printf '0' ;;
        *) printf '%s' "${n}" ;;
    esac
}
pool_table_has() {
    [[ "$(pool_sql_num "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$1'")" != "0" ]]
}
pool_user_id() {
    pool_sql "SELECT id FROM sys_user WHERE tenant_id=${1:-0} AND username='$2'"
}
pool_req_id_by_code() {
    pool_sql "SELECT id FROM requirement WHERE tenant_id=${1:-0} AND code='$2'"
}

# --------------------------------------------------------------------------- 断言

# pool_manifest_parse：把 POOL_MANIFEST 的 "编号|标题" 拆成两个平行数组（bash 3.2 无关联数组）。
pool_manifest_parse() {
    local row
    POOL_M_IDS=()
    POOL_M_TITLES=()
    for row in "${POOL_MANIFEST[@]}"; do
        POOL_M_IDS+=("${row%%|*}")
        POOL_M_TITLES+=("${row#*|}")
    done
}

pool_title() {
    local id="$1" i
    for ((i = 0; i < ${#POOL_M_IDS[@]}; i++)); do
        if [[ "${POOL_M_IDS[$i]}" == "${id}" ]]; then
            printf '%s' "${POOL_M_TITLES[$i]}"
            return 0
        fi
    done
    printf ''
}

pool_id_known() {
    local id="$1" i
    for ((i = 0; i < ${#POOL_M_IDS[@]}; i++)); do
        [[ "${POOL_M_IDS[$i]}" == "${id}" ]] && return 0
    done
    return 1
}

# pool_ck <编号> <1|0> <实际值> [修复建议]
# 编号必须出现在 POOL_MANIFEST 且只执行一次——清单与实现漂移会当场变红，不会悄悄少跑。
pool_ck() {
    local id="$1" ok="$2" detail="${3:-}" fix="${4:-}" title
    if ! pool_id_known "${id}"; then
        POOL_TOTAL=$((POOL_TOTAL + 1))
        POOL_FAIL=$((POOL_FAIL + 1))
        printf '%sFAIL%s [%02d] %s 不在 POOL_MANIFEST 里（清单/实现漂移）\n' \
            "${POOL_RED}" "${POOL_RESET}" "${POOL_TOTAL}" "${id}"
        return 0
    fi
    case "${POOL_SEEN}" in
        *" ${id} "*)
            POOL_TOTAL=$((POOL_TOTAL + 1))
            POOL_FAIL=$((POOL_FAIL + 1))
            printf '%sFAIL%s [%02d] %s 重复执行（编号必须唯一）\n' \
                "${POOL_RED}" "${POOL_RESET}" "${POOL_TOTAL}" "${id}"
            return 0
            ;;
    esac
    POOL_SEEN="${POOL_SEEN}${id} "
    title="$(pool_title "${id}")"
    POOL_TOTAL=$((POOL_TOTAL + 1))
    if [[ "${ok}" == "1" ]]; then
        POOL_PASS=$((POOL_PASS + 1))
        printf '%sPASS%s [%02d] %-5s %s\n' "${POOL_GREEN}" "${POOL_RESET}" "${POOL_TOTAL}" "${id}" "${title}"
    else
        POOL_FAIL=$((POOL_FAIL + 1))
        printf '%sFAIL%s [%02d] %-5s %s\n' "${POOL_RED}" "${POOL_RESET}" "${POOL_TOTAL}" "${id}" "${title}"
        printf '     实际: %s\n' "${detail:-<空>}"
        printf '     修复: %s\n' "${fix:-见 docs/teamflow/20261125-r47-pool-ops/QA-PLAN.md 对应行}"
    fi
    return 0
}

# --------------------------------------------------------------------------- 就绪与轮询

# pool_wait_ready <步骤名> <最大秒> <探测函数> [提示]
# 每秒探测一次（最多 30s）；超时打印「哪一步没就绪」并返回 1。禁止盲 sleep。
pool_wait_ready() {
    local step="$1" max="${2:-30}" probe="$3" hint="${4:-}" i=0
    while ((i < max)); do
        if "${probe}"; then
            printf '  [ready] %s（%ss）\n' "${step}" "${i}"
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    pool_fatal "未就绪：${step} 在 ${max}s 内未通过探测（最后一次 HTTP ${HTTP_CODE:-000}）${hint:+；${hint}}"
    return 1
}

# pool_probe_non404 <path>：路由存在即可（400/401 也算已注册，404/000 不算）。
pool_probe_non404() {
    pool_get "$1"
    [[ "${HTTP_CODE}" != "404" && "${HTTP_CODE}" != "000" ]]
}

# pool_similar_hint <body>：/similar 未落地时的真实报错不是 404，而是被 /requirements/{id}
# 通配吃掉（id="similar" 转 long 失败 → 400 VALIDATION_FAILED "Failed to convert …"）。
# 把它翻译成「接口未就绪」，别让读者以为是自己请求写错了。
pool_similar_hint() {
    local body="${1:-${HTTP_BODY:-}}"
    if [[ "${HTTP_CODE:-000}" == "400" ]] && printf '%s' "${body}" | grep -q 'Failed to convert'; then
        printf '接口未就绪（/similar 尚无独立路由，被 /requirements/{id} 通配匹配：id="similar" 转 long 失败）'
        return 0
    fi
    pool_transport_hint
}

# --------------------------------------------------------------------------- 收口

# pool_summary：未执行的清单编号按失败计（PASS+FAIL 恒等于清单条数）。
pool_summary() {
    local expected="${#POOL_M_IDS[@]}" missing="" unseen=0 i failn
    for ((i = 0; i < expected; i++)); do
        case "${POOL_SEEN}" in
            *" ${POOL_M_IDS[$i]} "*) ;;
            *)
                missing="${missing} ${POOL_M_IDS[$i]}"
                unseen=$((unseen + 1))
                ;;
        esac
    done
    failn=$((POOL_FAIL + unseen))
    printf '\n'
    if [[ -n "${missing}" ]]; then
        printf '%s未执行断言:%s%s\n' "${POOL_YELLOW}" "${POOL_RESET}" "${missing}"
    fi
    if [[ -n "${POOL_ABORT_REASON}" ]]; then
        printf 'POOL_OPS_ABORTED=%s\n' "${POOL_ABORT_REASON}"
    fi
    printf 'POOL_OPS_TOTAL=%d PASS=%d FAIL=%d\n' "${expected}" "${POOL_PASS}" "${failn}"
    if ((failn == 0)) && ((POOL_PASS == expected)); then
        return 0
    fi
    return 1
}

# pool_abort <一句话>：前置缺失（栈没起/DB 不可达）时收口，仍然打印 TOTAL/PASS/FAIL。
pool_abort() {
    POOL_ABORT_REASON="${1:-unknown}"
    pool_fatal "门禁中止：${POOL_ABORT_REASON}"
    pool_summary
    exit 2
}

# --------------------------------------------------------------------------- 离线模式

# pool_dry_run：不联网，按执行顺序打印全部断言（编号 + 标题），用于清单自检。
pool_dry_run() {
    local i
    printf 'POOL_OPS_DRY_RUN=1 TOTAL=%d\n' "${#POOL_M_IDS[@]}"
    for ((i = 0; i < ${#POOL_M_IDS[@]}; i++)); do
        printf '%02d  %-5s %s\n' "$((i + 1))" "${POOL_M_IDS[$i]}" "${POOL_M_TITLES[$i]}"
    done
}
