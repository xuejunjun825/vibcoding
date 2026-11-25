#!/usr/bin/env bash
# _lib.sh — PDR 评审 / 厂商评估门禁（scripts/pdr-flow-verify.sh）的共享基座。Source it，不要执行。
#
# 只做四件事，不重复既有 lib.sh 的能力：
#   1. 登录与带租户头的 HTTP 请求（curl 直打网关，错误体原样保留给断言）；
#   2. multipart 附件上传（评审附件走 /api/req/attachments?bizType=PDR_REVIEW&bizId={reviewId}）；
#   3. JSON 取值（jq）与 300 字响应截断；
#   4. 断言登记与计数（编号 + 标题来自门禁脚本里的 PDR_MANIFEST，结尾打印 TOTAL/PASS/FAIL）。
#
# 仓库既有约束（我按它写，不绕过）：
#   * scripts/tests/platform/scripts-contract.sh §5 禁止 scripts/ 下调起 Python 做交付动作（交付物禁令），
#     所以 JSON 一律用 jq 解析——全仓门禁（platform-verify / change-verify / collab-verify）同款做法；
#   * 宿主 bash 是 3.2：不使用关联数组与小写展开语法（两者都会在运行期炸）；
#   * 禁止用 `sleep` 掩盖未就绪：就绪只用 pdr_wait_ready 轮询，超时打印「哪一步没就绪」。
#
# 用法（在门禁脚本里）：
#   source "${ROOT}/scripts/lib.sh"            # 取 GATEWAY_URL / db_q / req_ready
#   source "${ROOT}/scripts/tests/pdr/_lib.sh" # 本文件
#   PDR_MANIFEST=( "A1-1|标题" ... ); pdr_manifest_parse
#   pdr_login admin && pdr_get /api/req/...
#   pdr_ck "A1-1" 1 "HTTP 201 reviewId=12"
#   pdr_summary

if [[ -n "${PDR_LIB_LOADED:-}" ]]; then
    return 0
fi
PDR_LIB_LOADED=1

# --------------------------------------------------------------------------- 常量与状态

PDR_GATEWAY="${PDR_GATEWAY:-http://127.0.0.1:${GATEWAY_PORT:-8080}}"
PDR_TENANT_CODE="${PDR_TENANT_CODE:-${PLATFORM_TENANT:-t1}}"
PDR_USER_PASS="${PDR_USER_PASS:-Passw0rd!}"
PDR_ADMIN_USER="${PDR_ADMIN_USER:-admin}"
PDR_ADMIN_PASS="${PDR_ADMIN_PASS:-Admin@12345}"
PDR_SNIPPET_LEN="${PDR_SNIPPET_LEN:-300}"

PDR_HTTP_CODE=""
PDR_HTTP_BODY=""
PDR_TOKEN=""
PDR_TENANT_ID=""

PDR_TOTAL=0
PDR_PASS=0
PDR_FAIL=0
# 已执行编号用空格分隔的字符串记录（bash 3.2 没有关联数组，所以用字符串 + case 匹配）。
PDR_SEEN=" "
PDR_ABORT_REASON=""

PDR_RED=$'\033[1;31m'
PDR_GREEN=$'\033[1;32m'
PDR_CYAN=$'\033[1;36m'
PDR_YELLOW=$'\033[1;33m'
PDR_RESET=$'\033[0m'

# --------------------------------------------------------------------------- 输出

pdr_section() { printf '\n%s== %s ==%s\n' "${PDR_CYAN}" "$*" "${PDR_RESET}"; }
pdr_info() { printf '  %s\n' "$*"; }
pdr_ok() { printf '  %sPASS%s %s\n' "${PDR_GREEN}" "${PDR_RESET}" "$*"; }
pdr_warn() { printf '%s[warn]%s %s\n' "${PDR_YELLOW}" "${PDR_RESET}" "$*"; }
pdr_fatal() { printf '%s[fatal]%s %s\n' "${PDR_RED}" "${PDR_RESET}" "$*" >&2; }

# 响应片段：单行 + 截断到 PDR_SNIPPET_LEN（默认 300 字），失败时贴进实际值。
pdr_snippet() {
    local body="${1:-${PDR_HTTP_BODY:-}}"
    printf '%s' "${body}" | tr '\n\r\t' '   ' | cut -c "1-${PDR_SNIPPET_LEN}"
}

# --------------------------------------------------------------------------- JSON

# pdr_json <body> <jq-expr>：取标量；数组/对象输出紧凑 JSON；取不到输出空串。
pdr_json() {
    local body="${1:-}" expr="${2:-.}"
    printf '%s' "${body}" | jq -r "(${expr}) // empty" 2>/dev/null || true
}

# pdr_pick <body> <expr> [expr...]：按顺序取第一个非空值（字段名未写死时的兜底）。
pdr_pick() {
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

# pdr_len <body> <path>：数组/对象长度；不是容器时返回 0。
pdr_len() {
    local body="${1:-}" path="${2:-.}" n
    n="$(printf '%s' "${body}" | jq -r "(${path}) | if type==\"array\" or type==\"object\" then length else 0 end" 2>/dev/null || true)"
    printf '%s' "${n:-0}"
}

pdr_json_ok() { printf '%s' "${1:-}" | jq -e . >/dev/null 2>&1; }

# pdr_has <body> <needle>：字面包含（grep -F，避免正则意外）。
pdr_has() { printf '%s' "${1:-}" | grep -Fq -- "${2:-}" 2>/dev/null; }

# --------------------------------------------------------------------------- HTTP

# pdr_req <method> <path> [body] [extra curl args...]：网关 + Bearer + X-Tenant-Id。
pdr_req() {
    local method="$1" path="$2" body="${3:-}"
    shift 3 2>/dev/null || true
    local args=(-sS --max-time 40 -X "${method}" "${PDR_GATEWAY}${path}")
    args+=(-H 'Accept: application/json')
    if [[ -n "${PDR_TOKEN}" ]]; then args+=(-H "Authorization: Bearer ${PDR_TOKEN}"); fi
    if [[ -n "${PDR_TENANT_ID}" ]]; then args+=(-H "X-Tenant-Id: ${PDR_TENANT_ID}"); fi
    if [[ -n "${body}" ]]; then args+=(-H 'Content-Type: application/json' -d "${body}"); fi
    local out
    out="$(curl "${args[@]}" "$@" -w '\n%{http_code}' 2>&1)" || true
    PDR_HTTP_CODE="$(printf '%s' "${out}" | tail -n1)"
    PDR_HTTP_BODY="$(printf '%s' "${out}" | sed '$d')"
}

# 注意：必须显式传第 3 个参数（哪怕空串）。pdr_req 里 shift 3，少传一个时 shift 失败、
# "$@" 仍留着 method/path，会被 curl 当成额外的 URL → 最后一个 %{http_code} 变成 000
# （body 却是对的，症状极隐蔽：GET 全 000、POST 正常）。
pdr_get() { pdr_req GET "$1" ""; }
pdr_post() { pdr_req POST "$1" "${2:-}"; }
pdr_put() { pdr_req PUT "$1" "${2:-}"; }
pdr_patch() { pdr_req PATCH "$1" "${2:-}"; }
pdr_delete() { pdr_req DELETE "$1" ""; }

# pdr_whoami：当前 token 的用户名（诊断用；登录响应里没回 username 时为空）。
pdr_whoami() {
    pdr_get /api/req/auth/me
    pdr_pick "${PDR_HTTP_BODY}" '.username' '.user.username'
}

# pdr_login <user> [password]：POST /api/req/auth/login，设置 PDR_TOKEN / PDR_TENANT_ID。
pdr_login() {
    local user="${1:-}" pass="${2:-${PDR_USER_PASS}}" body
    if [[ -z "${user}" ]]; then pdr_fatal "pdr_login 缺少用户名"; return 1; fi
    body="$(jq -cn --arg t "${PDR_TENANT_CODE}" --arg u "${user}" --arg p "${pass}" \
        '{tenantCode:$t,username:$u,password:$p}')"
    PDR_TOKEN=""
    PDR_TENANT_ID=""
    pdr_req POST /api/req/auth/login "${body}"
    if [[ "${PDR_HTTP_CODE}" != "200" ]]; then
        pdr_fatal "登录失败 ${user}（HTTP ${PDR_HTTP_CODE}）：$(pdr_snippet)"
        return 1
    fi
    PDR_TOKEN="$(pdr_json "${PDR_HTTP_BODY}" '.token')"
    # X-Tenant-Id 必须是数字 tenantId（网关按 Long 解析），不是 t1。
    PDR_TENANT_ID="$(pdr_pick "${PDR_HTTP_BODY}" '.tenantId' '.tenant.id')"
    if [[ -z "${PDR_TOKEN}" || -z "${PDR_TENANT_ID}" ]]; then
        pdr_fatal "登录响应缺 token/tenantId（${user}）：$(pdr_snippet)"
        return 1
    fi
    return 0
}

pdr_login_admin() { pdr_login "${PDR_ADMIN_USER}" "${PDR_ADMIN_PASS}"; }

# pdr_upload <file> <bizType> <bizId> [content-type]
# 评审附件复用 /api/req/attachments：bizType/bizId 只出现在 query string。
# 不要在 multipart 里再放一份 —— 服务端会把 query 与 form 的同名参数合并成
# "PDR_REVIEW,PDR_REVIEW"，直接 400 BIZ_TYPE_UNSUPPORTED。
pdr_upload() {
    local file="$1" biz_type="$2" biz_id="$3" ctype="${4:-}"
    local spec="file=@${file}"
    if [[ -n "${ctype}" ]]; then spec="file=@${file};type=${ctype}"; fi
    local out
    out="$(curl -sS --max-time 120 -X POST \
        "${PDR_GATEWAY}/api/req/attachments?bizType=${biz_type}&bizId=${biz_id}" \
        -H "Authorization: Bearer ${PDR_TOKEN}" \
        -H "X-Tenant-Id: ${PDR_TENANT_ID}" \
        -F "${spec}" \
        -w '\n%{http_code}' 2>&1)" || true
    PDR_HTTP_CODE="$(printf '%s' "${out}" | tail -n1)"
    PDR_HTTP_BODY="$(printf '%s' "${out}" | sed '$d')"
}

# pdr_download <attachmentId> <destFile>：下载到文件（比较 sha256 用），code 写 PDR_HTTP_CODE。
pdr_download() {
    local id="$1" dest="$2" code
    code="$(curl -sS --max-time 60 -o "${dest}" -w '%{http_code}' \
        -H "Authorization: Bearer ${PDR_TOKEN}" \
        -H "X-Tenant-Id: ${PDR_TENANT_ID}" \
        "${PDR_GATEWAY}/api/req/attachments/${id}/download" 2>/dev/null || true)"
    PDR_HTTP_CODE="${code:-000}"
    PDR_HTTP_BODY=""
}

# --------------------------------------------------------------------------- 断言

# pdr_manifest_parse：把 PDR_MANIFEST 的 "编号|标题" 拆成两个平行数组（bash 3.2 无关联数组）。
pdr_manifest_parse() {
    local row
    PDR_M_IDS=()
    PDR_M_TITLES=()
    for row in "${PDR_MANIFEST[@]}"; do
        PDR_M_IDS+=("${row%%|*}")
        PDR_M_TITLES+=("${row#*|}")
    done
}

pdr_title() {
    local id="$1" i
    for ((i = 0; i < ${#PDR_M_IDS[@]}; i++)); do
        if [[ "${PDR_M_IDS[$i]}" == "${id}" ]]; then
            printf '%s' "${PDR_M_TITLES[$i]}"
            return 0
        fi
    done
    printf ''
}

pdr_id_known() {
    local id="$1" i
    for ((i = 0; i < ${#PDR_M_IDS[@]}; i++)); do
        [[ "${PDR_M_IDS[$i]}" == "${id}" ]] && return 0
    done
    return 1
}

# pdr_ck <编号> <1|0> <实际值> [修复建议]
# 编号必须出现在 PDR_MANIFEST 且只执行一次——清单与实现漂移会当场变红，不会悄悄少跑。
pdr_ck() {
    local id="$1" ok="$2" detail="${3:-}" fix="${4:-}" title
    if ! pdr_id_known "${id}"; then
        PDR_TOTAL=$((PDR_TOTAL + 1))
        PDR_FAIL=$((PDR_FAIL + 1))
        printf '  %sFAIL%s [%02d] %s 不在 PDR_MANIFEST 里（清单/实现漂移）\n' \
            "${PDR_RED}" "${PDR_RESET}" "${PDR_TOTAL}" "${id}"
        return 0
    fi
    case "${PDR_SEEN}" in
        *" ${id} "*)
            PDR_TOTAL=$((PDR_TOTAL + 1))
            PDR_FAIL=$((PDR_FAIL + 1))
            printf '  %sFAIL%s [%02d] %s 重复执行（编号必须唯一）\n' \
                "${PDR_RED}" "${PDR_RESET}" "${PDR_TOTAL}" "${id}"
            return 0
            ;;
    esac
    PDR_SEEN="${PDR_SEEN}${id} "
    title="$(pdr_title "${id}")"
    PDR_TOTAL=$((PDR_TOTAL + 1))
    if [[ "${ok}" == "1" ]]; then
        PDR_PASS=$((PDR_PASS + 1))
        printf '  %sPASS%s [%02d] %-7s %s\n' "${PDR_GREEN}" "${PDR_RESET}" "${PDR_TOTAL}" "${id}" "${title}"
    else
        PDR_FAIL=$((PDR_FAIL + 1))
        printf '  %sFAIL%s [%02d] %-7s %s\n' "${PDR_RED}" "${PDR_RESET}" "${PDR_TOTAL}" "${id}" "${title}"
        printf '        实际: %s\n' "${detail:-<空>}"
        printf '        修复: %s\n' "${fix:-见 docs/teamflow/20261125-r46-pdr-review-vendor-estimate/QA-PLAN.md 对应行}"
    fi
    return 0
}

# pdr_code：当前响应的业务错误码（兼容 ApiError.code 与嵌套 error/detail.code）。
pdr_code() { pdr_pick "${1:-${PDR_HTTP_BODY}}" '.code' '.error.code' '.detail.code'; }

# pdr_abort <一句话>：前置缺失（拿不到后续断言必需的 id）时收口，仍然打印 TOTAL/PASS/FAIL。
pdr_abort() {
    PDR_ABORT_REASON="${1:-unknown}"
    pdr_fatal "门禁中止：${PDR_ABORT_REASON}"
    pdr_summary
    exit 2
}

# --------------------------------------------------------------------------- 就绪与轮询

# pdr_wait_ready <步骤名> <最大秒> <探测函数> [提示]
# 每秒探测一次，最多 30 秒（调用方传 30）；超时打印「哪一步没就绪」并返回 1。禁止盲 sleep。
pdr_wait_ready() {
    local step="$1" max="${2:-30}" probe="$3" hint="${4:-}" i=0
    while ((i < max)); do
        if "${probe}"; then
            printf '  [ready] %s（%ss）\n' "${step}" "${i}"
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    pdr_fatal "未就绪：${step} 在 ${max}s 内未通过探测（最后一次 HTTP ${PDR_HTTP_CODE:-000}）${hint:+；${hint}}"
    return 1
}

# pdr_probe_non404 <path>：探测「路由存在」——只要不是 404 就算就绪（400 也是路由存在）。
pdr_probe_non404() {
    pdr_get "$1"
    [[ "${PDR_HTTP_CODE}" != "404" && "${PDR_HTTP_CODE}" != "000" ]]
}

# --------------------------------------------------------------------------- 收口

pdr_summary() {
    local expected="${#PDR_M_IDS[@]}" missing="" i
    for ((i = 0; i < expected; i++)); do
        case "${PDR_SEEN}" in
            *" ${PDR_M_IDS[$i]} "*) ;;
            *) missing="${missing} ${PDR_M_IDS[$i]}" ;;
        esac
    done
    printf '\n'
    if [[ -n "${missing}" ]]; then
        printf '%s未执行断言:%s%s\n' "${PDR_YELLOW}" "${PDR_RESET}" "${missing}"
    fi
    if [[ -n "${PDR_ABORT_REASON}" ]]; then
        printf 'PDR_FLOW_ABORTED=%s\n' "${PDR_ABORT_REASON}"
    fi
    printf 'PDR_FLOW_TOTAL=%d PASS=%d FAIL=%d\n' "${expected}" "${PDR_PASS}" "${PDR_FAIL}"
    if ((PDR_FAIL == 0)) && [[ -z "${missing}" ]] && ((expected == PDR_PASS)); then
        return 0
    fi
    return 1
}

# --------------------------------------------------------------------------- 离线模式

# pdr_dry_run：不联网，按执行顺序打印 66 条断言（编号 + 标题），用于 checklist 自检。
pdr_dry_run() {
    local i
    printf 'PDR_FLOW_DRY_RUN=1 TOTAL=%d\n' "${#PDR_M_IDS[@]}"
    for ((i = 0; i < ${#PDR_M_IDS[@]}; i++)); do
        printf '%02d  %-7s %s\n' "$((i + 1))" "${PDR_M_IDS[$i]}" "${PDR_M_TITLES[$i]}"
    done
}
