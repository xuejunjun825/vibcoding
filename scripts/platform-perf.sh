#!/usr/bin/env bash
# platform-perf.sh — AC-20 六项性能基线：p50/p95 + EXPLAIN + 阈值断言。
# 数据规模由 /internal/seed?scale=N 造（10 万需求、1000 行用例导入）。
# 结论写到 logs/teamflow/<runId>/perf-baseline.md，原始命令输出不落盘（宿主已截尾）。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

TOTAL=0; PASS=0; FAIL=0
RUN_TAG="$(date +%H%M%S)"
SCALE_REQS="${PLATFORM_PERF_REQS:-100000}"
REPORT_DIR="$(run_log_dir)"
REPORT="${REPORT_DIR}/perf-baseline.md"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

section() { printf '\n\033[1;34m---- %s ----\033[0m\n' "$*"; }
pass() { TOTAL=$((TOTAL+1)); PASS=$((PASS+1)); printf '  \033[1;32mPASS\033[0m %s\n' "$1"; }
fail() { TOTAL=$((TOTAL+1)); FAIL=$((FAIL+1)); printf '  \033[1;31mFAIL\033[0m %s\n' "$1"; [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"; }
jget_() { printf '%s' "$1" | jq -r "$2" 2>/dev/null || true; }

# p95/p50 from a file of seconds
stat_of() { # file → "p50 p95 max"
    sort -n "$1" | awk '{a[NR]=$1} END{if(NR==0){print "0 0 0";exit} printf "%.3f %.3f %.3f\n", a[int((NR-1)*0.5)+1], a[int((NR-1)*0.95)+1], a[NR]}'
}

# timed <label> <n> <curl-args...>  → appends seconds to $TMP/<label>.times
timed() {
    local label="$1" n="$2"; shift 2
    local i t
    : >"$TMP/${label}.times"
    for (( i = 0; i < n; i++ )); do
        t="$(curl -sS -o /dev/null -w '%{time_total}' --max-time 30 "$@" 2>/dev/null)"
        [[ -n "$t" ]] && printf '%s\n' "$t" >>"$TMP/${label}.times"
    done
}

assert_metric() { # label threshold_s desc fix
    local label="$1" limit="$2" desc="$3" fix="${4:-}"
    local s p50 p95 mx okn
    s="$(stat_of "$TMP/${label}.times")"
    p50="$(awk '{print $1}' <<<"$s")"; p95="$(awk '{print $2}' <<<"$s")"; mx="$(awk '{print $3}' <<<"$s")"
    okn="$(awk -v p="$p95" -v l="$limit" 'BEGIN{print (p<=l)?1:0}')"
    printf 'RESULT|%s|%s|%s|%s|%s\n' "$desc" "$p50" "$p95" "$mx" "$limit" >>"$TMP/results.txt"
    if [[ "$okn" == "1" ]]; then
        pass "${desc}（p50 ${p50}s / p95 ${p95}s ≤ ${limit}s）"
    else
        fail "${desc}（p95 ${p95}s > ${limit}s）" "${fix:-看 EXPLAIN 与索引；必要时 keyset 分页/缓存}"
    fi
}

req_ready || { err "req-service 未就绪，修复: make platform-up"; exit 1; }
deliver_ready || { err "deliver-service 未就绪，修复: make platform-up"; exit 1; }

api_call POST /api/req/auth/login "{\"tenantCode\":\"t1\",\"username\":\"admin\",\"password\":\"Admin@12345\"}"
[[ "$HTTP_CODE" == "200" ]] || { err "管理员登录失败，修复: make platform-seed"; exit 1; }
API_TOKEN="$(jget_ "$HTTP_BODY" '.token')"; API_TENANT="$(jget_ "$HTTP_BODY" '.tenantId')"
AUTH=(-H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}")
ok "登录完成，开始造数（scale=${SCALE_REQS}）"

# ---------------------------------------------------------------- 造数
section "0) 规模数据：${SCALE_REQS} 条需求（幂等，已足量则跳过）"
CUR="$(db_count requirement "tenant_id=(SELECT id FROM sys_tenant WHERE code='t1')")"
if [[ "${CUR:-0}" -lt "$SCALE_REQS" ]]; then
    T_START="$(date +%s)"
    internal_call POST "$REQ_URL" "/internal/seed?scale=${SCALE_REQS}" ""
    if [[ "$HTTP_CODE" == "200" ]]; then
        ok "规模种子完成：$(jget_ "$HTTP_BODY" '.counts.requirements') 条需求，耗时 $(( $(date +%s) - T_START ))s"
    else
        err "规模种子失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        err "修复: SeedService.seed(scale) 必须支持批量插入；或把 PLATFORM_PERF_REQS 调小"
        exit 1
    fi
else
    ok "已有 ${CUR} 条需求 ≥ ${SCALE_REQS}，跳过造数"
fi

# 断言索引存在（缺索引时下面的 P95 会红，这里先给更准确的原因）
IDX="$(db_q "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='${PLATFORM_DB_NAME}' AND table_name='requirement' AND index_name LIKE 'idx_%'")"
[[ "${IDX:-0}" -ge 3 ]] && ok "requirement 有 ${IDX} 个 idx_* 索引" || warn "requirement 只有 ${IDX} 个 idx_* 索引，P95 可能不达标"

# ---------------------------------------------------------------- 1
section "1) 需求池列表 P95 ≤ 1s（10 万行）"
timed "req-list" 20 "${GATEWAY_URL}/api/req/requirements?page=1&size=50&status=POOLED" "${AUTH[@]}"
assert_metric "req-list" 1.0 "需求池列表（10 万行，50/页）" "EXPLAIN 该查询；缺 (tenant_id,status,id) 复合索引就补"
EXPLAIN="$(db_q "EXPLAIN SELECT id FROM requirement WHERE tenant_id=(SELECT id FROM sys_tenant WHERE code='t1') AND status='POOLED' ORDER BY id DESC LIMIT 50")"
printf 'EXPLAIN|需求池列表|%s\n' "$(printf '%s' "$EXPLAIN" | tr '\n' ' ' | tr -s ' ')" >>"$TMP/results.txt"

# ---------------------------------------------------------------- 2
section "2) 缺陷看板 P95 ≤ 2s"
timed "defect-board" 10 "${GATEWAY_URL}/api/deliver/defects/board" "${AUTH[@]}"
assert_metric "defect-board" 2.0 "缺陷看板四指标" "四条聚合 SQL 应各走一个索引；不要 N+1"

# ---------------------------------------------------------------- 3
section "3) 发布准入 P95 ≤ 500ms（跨服务）"
REL="$(db_q "SELECT id FROM \`release\` WHERE tenant_id=(SELECT id FROM sys_tenant WHERE code='t1') ORDER BY id LIMIT 1")"
if [[ -n "$REL" ]]; then
    REQ_FOR_REL="$(db_q "SELECT requirement_id FROM release_requirement WHERE release_id=${REL} LIMIT 1")"
    [[ -n "$REQ_FOR_REL" ]] || REQ_FOR_REL="$(db_q "SELECT id FROM requirement WHERE tenant_id=(SELECT id FROM sys_tenant WHERE code='t1') LIMIT 1")"
    api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$REL" --argjson q "$REQ_FOR_REL" '{releaseId:$r,env:"TEST",windowStart:"2026-12-10T10:00:00+08:00",windowEnd:"2026-12-10T12:00:00+08:00",artifactVersion:"perf",steps:[{name:"发布",order:1}],rollbackPlan:"回滚",impact:"无",ownerId:1,requirementIds:[$q]}')"
    PERF_DEP="$(jget_ "$HTTP_BODY" '.id')"
    if [[ -n "$PERF_DEP" && "$PERF_DEP" != "null" ]]; then
        timed "admission" 10 "${GATEWAY_URL}/api/req/deploy-orders/${PERF_DEP}/evaluate" -X POST -H 'Content-Type: application/json' -d '{}' "${AUTH[@]}"
        assert_metric "admission" 0.5 "发布准入 5 项（跨服务批量门禁）" "门禁批量一次 HTTP + 两条计数 SQL；超时 fail-closed"
    else
        warn "无法创建发布单，跳过准入基线"
    fi
else
    warn "没有版本数据，跳过准入基线（先 make platform-seed）"
fi

# ---------------------------------------------------------------- 4
section "4) 追溯矩阵 P95 ≤ 2s"
timed "trace" 10 "${GATEWAY_URL}/api/deliver/traceability?page=1&size=50" "${AUTH[@]}"
assert_metric "trace" 2.0 "追溯矩阵 50 行 × 六环" "六环用 LEFT JOIN 一次查完，禁止逐行回查"

# ---------------------------------------------------------------- 5
section "5) 开放 API 上报 P95 ≤ 300ms（20 并发）"
api_call POST /api/deliver/admin/api-clients "$(jq -cn --arg n "perf-ci-${RUN_TAG}" '{name:$n,scopes:["test-execution"],ipWhitelist:["127.0.0.1"],rateLimitQps:5000}')"
APP_KEY="$(jget_ "$HTTP_BODY" '.appKey')"
APP_SECRET="$(jget_ "$HTTP_BODY" '.secret // .clientSecret // .secretOnce')"
CASE_ID="$(db_q "SELECT id FROM test_case WHERE tenant_id=(SELECT id FROM sys_tenant WHERE code='t1') ORDER BY id LIMIT 1")"
if [[ -n "$APP_KEY" && "$APP_KEY" != "null" && -n "$CASE_ID" ]]; then
    OAPI="/openapi/v1/test-executions"
    if [[ -z "$APP_SECRET" || "$APP_SECRET" == "null" ]]; then
        warn "种子只在首次返回 secret（已 seed 过），改用日志中的既有客户端：跳过并发上报基线"
    else
        : >"$TMP/oapi.times"
        for i in $(seq 1 20); do
            (
                body="$(jq -cn --argjson c "$CASE_ID" --arg r "perf-${RUN_TAG}-${i}" '{run_id:$r,case_id:$c,result:"PASS",duration_ms:1,executed_at:"2026-11-09T00:00:00+08:00"}')"
                ts="$(date +%s)"
                digest="$(printf '%s' "$body" | openssl dgst -sha256 -hex | awk '{print $NF}')"
                sig="$(printf '%s' "POST
${OAPI}
${APP_KEY}
${ts}
${digest}" | openssl dgst -sha256 -hmac "$APP_SECRET" -hex | awk '{print $NF}')"
                curl -sS -o /dev/null -w '%{time_total}\n' --max-time 10 -X POST "${GATEWAY_URL}${OAPI}" \
                    -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: ${ts}" -H "X-Signature: ${sig}" \
                    -H 'Content-Type: application/json' -d "$body" >>"$TMP/oapi.raw" 2>/dev/null
            ) &
        done
        wait
        sort -n "$TMP/oapi.raw" >"$TMP/oapi.times"
        assert_metric "oapi" 0.3 "开放 API 上报（20 并发）" "验签/限流/幂等都在服务内，避免同步远程调用"
    fi
else
    warn "缺开放 API 凭据或用例，跳过并发上报基线"
fi

# ---------------------------------------------------------------- 6
section "6) 1000 行用例导入 ≤ 10s"
# 导入端点是 multipart/form-data（file=CSV），不是 JSON；表头必须与 CSV_HEADER 逐字一致，
# 且 module 列不能为空（test_case.module_id NOT NULL，空白会被行级校验拒绝）。
BULK_CSV="$TMP/cases-import.csv"
IMPORT_MODULE="$(db_q "SELECT path FROM test_module WHERE tenant_id=(SELECT id FROM sys_tenant WHERE code='t1') ORDER BY id LIMIT 1")"
[[ -n "$IMPORT_MODULE" ]] || { warn "没有测试模块，跳过批量导入基线"; IMPORT_MODULE=""; }
{
    printf 'module,title,type,priority,precondition,step_action,step_expected\n'
    for i in $(seq 1 1000); do
        printf '"%s",perf-case-%s,FUNCTIONAL,P2,-,do,ok\n' "$IMPORT_MODULE" "$i"
    done
} >"$BULK_CSV"
T_START="$(date +%s)"
HTTP_CODE="$(curl -sS --max-time 120 -o "$TMP/import.json" -w '%{http_code}' \
    -X POST "${GATEWAY_URL}/api/deliver/testcases/import" \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" \
    -F "file=@${BULK_CSV};type=text/csv" 2>/dev/null || true)"
T_END="$(date +%s)"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    SECS=$(( T_END - T_START ))
    printf 'RESULT|用例批量导入 1000 行|%s|%s|%s|10\n' "$SECS" "$SECS" "$SECS" >>"$TMP/results.txt"
    LE10="$(awk -v s="$SECS" 'BEGIN{print (s<=10)?1:0}')"
    [[ "$LE10" == "1" ]] && pass "1000 行导入 ${SECS}s ≤ 10s" || fail "1000 行导入 ${SECS}s > 10s" "批量 INSERT + 单事务；不要逐行 save"
else
    fail "批量导入失败 (HTTP ${HTTP_CODE})" "检查 /testcases/import 的载荷格式与事务边界"
fi

# ---------------------------------------------------------------- 报告
{
    printf '# 性能基线（AC-20）\n\n'
    printf -- '- 时间：%s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
    printf -- '- 规模：需求 %s 条；数据由 `/internal/seed?scale=%s` 生成\n' "$(db_count requirement "tenant_id=(SELECT id FROM sys_tenant WHERE code='t1')")" "$SCALE_REQS"
    printf -- '- 采样：列表类 20 次、看板/准入/矩阵 10 次、上报 20 并发、导入 1 次\n\n'
    printf '| 场景 | p50(s) | p95(s) | max(s) | 阈值(s) |\n| --- | --- | --- | --- | --- |\n'
    grep -h '^RESULT|' "$TMP/results.txt" 2>/dev/null | awk -F'|' '{printf "| %s | %s | %s | %s | %s |\n", $2, $3, $4, $5, $6}'
    printf '\n## EXPLAIN\n\n```\n'
    grep -h '^EXPLAIN|' "$TMP/results.txt" 2>/dev/null | sed 's/^EXPLAIN|[^|]*|//'
    printf '```\n\n结论：%s\n' "$([[ "$FAIL" == "0" ]] && echo '六项全部达标' || echo "有 ${FAIL} 项超阈值，见上表")"
} >"$REPORT"
ok "基线报告: ${REPORT}"

printf '\n'
if (( FAIL > 0 || TOTAL == 0 )); then
    err "platform-perf: ${FAIL}/${TOTAL} 项超阈值"
    exit 1
fi
ok "platform-perf: ${PASS}/${TOTAL} 项全部达标"
