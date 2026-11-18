#!/usr/bin/env bash
# backfill-wbs-estimate.sh — 存量 PDR/RD 工时评估单回填的运维入口（20261118-r29）。
#
# 薄封装，只做四件事：解析参数 → 调 POST /internal/wbs-estimate-backfill →
# 落完整报告文件 → 把报告里的 summary 翻成退出码。
# 业务判定（哪些需求该补、人天是否合法、审批人怎么解析、STALE 阈值多少）全部在服务端，
# 本脚本一行都不复制——脚本里出现第二份判定，就会和页面/门禁不同源。
#
# 用法：
#   bash scripts/backfill-wbs-estimate.sh --tenant t1                     # dry-run（默认只读）
#   bash scripts/backfill-wbs-estimate.sh --tenant t1 --apply             # 真正建单+批单
#   bash scripts/backfill-wbs-estimate.sh --tenant t1 --batch-id wbsbf-20261118T2000-a1b2c3 \
#        --apply --report /tmp/wbs-backfill.json
#
# 退出码（契约，见 docs/teamflow/20261118-r29-wbs-estimate-backfill/TECH-CHANGE.md §3.6）：
#   0 没有任何 FAILED 行（dry-run 恒为 0）
#   1 报告里有 FAILED 行——逐行原因在 rows[].reason；修因后可用同一 batchId 重跑
#   2 用法错误 / 环境错误（令牌不符 401、开关未开 404、参数被拒 400、响应不是报告）
#   3 传输失败（连不上 / 000 / 502 / 503 / 504）
#
# 环境：REQ_URL（默认 http://127.0.0.1:8082，直连 req-service，不经网关）
#       PLATFORM_INTERNAL_TOKEN（默认 dev-internal-token）
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
# lib.sh 为了脚本健壮性打开了 set -e；本脚本的退出码是契约（0/1/2/3），
# 不能被中途任何一条非零子命令改写，故显式关掉 -e，所有失败点自己判。
set +e

OPS_PATH="/internal/wbs-estimate-backfill"

usage() {
    cat <<'EOF'
用法: bash scripts/backfill-wbs-estimate.sh --tenant <code> [--batch-id <id>]
                                             [--report <path>] [--apply|--dry-run]

存量 PDR/RD 工时评估单回填（默认 dry-run，只读；业务判定全在服务端）。
  --tenant <code>   租户编码，必填（如 t1）
  --batch-id <id>   批次号，可选；不传由服务端生成 wbsbf-yyyyMMddTHHmm-xxxxxx 并在报告里回显
  --report <path>   把完整报告（JSON）写到该文件（父目录自动创建）
  --apply           真正写库（建单+批单）；不带即 dry-run
  --dry-run         显式声明只读（默认行为，便于脚本自证）
  -h, --help        打印本帮助

环境: REQ_URL（默认 http://127.0.0.1:8082） PLATFORM_INTERNAL_TOKEN（默认 dev-internal-token）
退出码: 0 无 FAILED 行 / 1 有 FAILED 行 / 2 用法或环境错误 / 3 传输失败(000/502/503/504)
EOF
}

usage_err() {
    err "参数错误: $*"
    usage >&2
    exit 2
}

TENANT=""
BATCH=""
REPORT=""
DRY=1

while (( $# > 0 )); do
    case "$1" in
        --tenant)
            (( $# >= 2 )) || usage_err "--tenant 缺少取值"
            TENANT="$2"; shift 2 ;;
        --batch-id)
            (( $# >= 2 )) || usage_err "--batch-id 缺少取值"
            BATCH="$2"; shift 2 ;;
        --report)
            (( $# >= 2 )) || usage_err "--report 缺少取值"
            REPORT="$2"; shift 2 ;;
        --apply)
            DRY=0; shift ;;
        --dry-run)
            DRY=1; shift ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            usage_err "未知参数: $1" ;;
    esac
done

[[ -n "$TENANT" ]] || usage_err "--tenant 必填（如 --tenant t1）"
need_cmd curl
need_cmd jq

: "${REQ_URL:=http://127.0.0.1:8082}"
: "${PLATFORM_INTERNAL_TOKEN:=dev-internal-token}"
REQ_URL="${REQ_URL%/}"
[[ -n "$REQ_URL" ]] || usage_err "REQ_URL 不能为空"

enc() { jq -rn --arg v "$1" '$v|@uri'; }

URL="${REQ_URL}${OPS_PATH}?tenant=$(enc "$TENANT")&dryRun=$([[ "$DRY" == "1" ]] && printf true || printf false)"
[[ -n "$BATCH" ]] && URL="${URL}&batchId=$(enc "$BATCH")"

log "$([[ "$DRY" == "1" ]] && printf 'dry-run（只读）' || printf 'apply（写库）'): POST ${OPS_PATH} tenant=${TENANT}${BATCH:+ batchId=${BATCH}}"

out="$(curl -sS --max-time "${BACKFILL_TIMEOUT:-180}" -X POST "$URL" \
    -H "X-Internal-Token: ${PLATFORM_INTERNAL_TOKEN}" -w '\n%{http_code}' 2>&1)"
curl_rc=$?
code="$(printf '%s' "$out" | tail -n1 | tr -d '\r')"
body="$(printf '%s' "$out" | sed '$d')"
[[ "$code" =~ ^[0-9]{3}$ ]] || code=000

# ---- 传输层：连不上 / 网关超时类状态码 → 3（与报告无关，纯环境问题）
case "$code" in
    000|502|503|504)
        err "传输失败（HTTP ${code}，curl exit ${curl_rc}）: POST ${URL}"
        printf '%s\n' "$body" | sed -n '1,3p' >&2
        err "修复: 确认 req-service 已启动（make platform-up），且 REQ_URL 指向直连端口 ${REQ_URL}（不是网关端口）"
        exit 3 ;;
esac

# ---- 非 200：服务端已应答但拒绝 → 2（令牌/开关/参数都属「环境或用法」）
if [[ "$code" != "200" ]]; then
    detail_msg="$(printf '%s' "$body" | jq -r '(.code // "?") + ": " + (.message // "")' 2>/dev/null)"
    err "服务端拒绝（HTTP ${code}）${detail_msg:-$(printf '%s' "$body" | head -c 200)}"
    case "$code" in
        404) err "修复: 回填开关未打开——export PLATFORM_OPS_BACKFILL_ENABLED=true 并重启 req-service（RUNBOOK §0-2）；生产窗口期外应保持关闭" ;;
        401|403) err "修复: X-Internal-Token 与 req-service 的 PLATFORM_INTERNAL_TOKEN 不一致" ;;
        400) err "修复: 参数不合法（tenant 为空 / batchId 形状不对），本脚本不猜服务端口径" ;;
    esac
    exit 2
fi

# ---- 200 但不是报告形状：端点被别的东西占用或契约漂移 → 2
if ! printf '%s' "$body" | jq -e '(.summary|type=="object") and (.rows|type=="array")
        and (.batchId|type=="string") and (.mode|type=="string")' >/dev/null 2>&1; then
    err "响应不是回填报告（缺 batchId/mode/summary/rows）: HTTP 200 但 body 不符契约"
    printf '%s' "$body" | head -c 300 >&2
    printf '\n' >&2
    exit 2
fi

# ---- 落报告（运维唯一需要留存的东西；报告里的 rows 是逐行证据）
if [[ -n "$REPORT" ]]; then
    report_dir="$(dirname "$REPORT")"
    mkdir -p "$report_dir"
    if printf '%s' "$body" | jq . >"$REPORT" 2>/dev/null; then
        :
    else
        printf '%s\n' "$body" >"$REPORT"
    fi
    ok "报告已写入 ${REPORT}"
fi

printf '%s' "$body" | jq -r '"batchId=\(.batchId) mode=\(.mode) tenant=\(.tenant) "
    + "scanned=\(.summary.scanned) planned=\(.summary.planned) created=\(.summary.created) "
    + "skipped=\(.summary.skipped) failed=\(.summary.failed)"'

# dry-run：列出「将要建单」的行（plan 才是 apply 的依据）；apply：列出失败行。
printf '%s' "$body" | jq -r '.rows[] | select(.result=="PLANNED" or .result=="CREATED")
    | "  \(if .result=="CREATED" then "CREATED" else "PLAN   " end) \(.code) \(.type)"
      + " \(.estimateDays // "-") 人天 approver=\(.approverSource // "-")"
      + (if ((.flags // [])|length) > 0 then " flags=\((.flags // [])|join(","))" else "" end)' 2>/dev/null
printf '%s' "$body" | jq -r '.rows[] | select(.result=="FAILED")
    | "  FAILED  \(.code) \(.type) \(.reason // "-") \(.estimateDays // "-") 人天"' 2>/dev/null

failed_rows="$(printf '%s' "$body" | jq -r '[.rows[] | select(.result=="FAILED")] | length' 2>/dev/null)"
summary_failed="$(printf '%s' "$body" | jq -r '.summary.failed // 0' 2>/dev/null)"
[[ "$failed_rows" =~ ^[0-9]+$ ]] || failed_rows=0
[[ "$summary_failed" =~ ^[0-9]+$ ]] || summary_failed=0

if (( failed_rows > 0 || summary_failed > 0 )); then
    err "有 ${failed_rows} 行 FAILED（退出码 1）：逐行原因见报告 rows[].reason；修因后可用同一 batchId 重跑，已通过的行会被 HAS_APPROVED_ESTIMATE 跳过"
    exit 1
fi

if [[ "$DRY" == "1" ]]; then
    ok "dry-run 完成，无 FAILED 行；确认 planned 与存量口径一致后加 --apply（退出码 0）"
else
    ok "apply 完成，无 FAILED 行（退出码 0）"
fi
exit 0
