#!/usr/bin/env bash
# 静态门禁（回归防线）：把修复轮确认过的缺陷「类」变成可执行断言，`make lint` 调用。
# 每条断言都对应一个真实发生过的缺陷，修复前必红、修复后必绿；不启动任何服务。
#   L1 所有脚本 `bash -n` 语法通过
#   L2 脚本内禁止 `$VAR` 紧跟非 ASCII 字节：中文全角标点会被 set -u 并入变量名，
#      使错误路径提示变成 "unbound variable"（D1 的类，修复前 5 处）
#   L3 公共逻辑单点：own_jvm_pids / kill_own_jvms / chain_ok 只允许在 lib.sh 定义一次（D5）
#   L4 Nacos v3 配置 URL 只允许由 lib.sh:nacos_config_url 生成，禁止各脚本手拼（D5）
#   L5 Nacos token secret 必须可用 NACOS_TOKEN_SECRET 覆盖（D6）
#   L6 错误路径提示实测可用：下载失败必须输出「下载失败: <url>」且退出码为 1（D1）
#   L7 文档与实测基线一致：README / ARCHITECTURE 不得残留过期描述（D2/D3）
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/lib.sh"

TOTAL=0
FAILED=0

run_check() {
    local desc="$1" out
    shift
    TOTAL=$((TOTAL + 1))
    if out="$("$@" 2>&1)"; then
        ok "[$TOTAL] $desc"
    else
        err "[$TOTAL] $desc"
        printf '%s\n' "$out" | sed 's/^/    /'
        FAILED=$((FAILED + 1))
    fi
}

# ---- L1 语法 ----
syntax_ok() {
    local f
    for f in "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh "$ROOT"/scripts/tests/platform/*.sh; do
        [[ -e "$f" ]] || continue
        bash -n "$f" || { echo "语法错误: $f"; return 1; }
    done
}

# ---- L2 裸变量名 + 全角标点（set -u 把全角字节并入变量名） ----
no_bare_var_before_wide_char() {
    local hits
    hits="$(LC_ALL=C grep -nE '\$[A-Za-z_][A-Za-z0-9_]*[^ -~]' \
        "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh "$ROOT"/scripts/tests/platform/*.sh 2>/dev/null || true)"
    if [[ -n "$hits" ]]; then
        echo "以下行在 \${VAR} 后紧跟非 ASCII 字节，请改为 \$ 花括号形式:"
        echo "$hits"
        return 1
    fi
}

# ---- L3 公共函数单点定义 ----
defined_once_in_lib() {
    local name="$1" hits n
    hits="$(grep -lE "^${name}\(\)" "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh 2>/dev/null || true)"
    n="$(printf '%s\n' "$hits" | grep -c . || true)"
    if [[ "$n" -ne 1 || "$hits" != "$ROOT/scripts/lib.sh" ]]; then
        echo "${name}() 应仅在 scripts/lib.sh 定义一次，实际 ${n} 处:"
        printf '%s\n' "$hits"
        return 1
    fi
}

# ---- L4 Nacos v3 配置 URL 单点生成 ----
config_url_single_source() {
    local hits
    # lint.sh 自身持有该字符串作为断言字面量，不算手拼 URL 的调用方。
    hits="$(grep -l '/nacos/v3/admin/cs/config' "$ROOT"/scripts/*.sh "$ROOT"/scripts/tests/*.sh 2>/dev/null \
        | grep -v "$ROOT/scripts/tests/lint.sh" || true)"
    if [[ "$hits" != "$ROOT/scripts/lib.sh" ]]; then
        echo "配置 URL 只允许 lib.sh:nacos_config_url 生成，实际出现于:"
        printf '%s\n' "$hits"
        return 1
    fi
}

# ---- L5 token secret 可覆盖 ----
token_secret_overridable() {
    grep -q 'NACOS_TOKEN_SECRET' "$ROOT/scripts/nacos.sh" \
        || { echo "scripts/nacos.sh 未提供 NACOS_TOKEN_SECRET 覆盖"; return 1; }
}

# ---- L6 错误提示实测（真实失败路径，不再是 unbound variable） ----
error_hint_works() {
    local tmp out rc
    tmp="$(mktemp -d)"
    if out="$(NACOS_HOME="$tmp" NACOS_DOWNLOAD_URL="http://127.0.0.1:9/nacos.tar.gz" \
        bash "$ROOT/scripts/nacos.sh" ensure 2>&1)"; then
        rc=0
    else
        rc=$?
    fi
    rm -rf "$tmp"
    if grep -q 'unbound variable' <<<"$out"; then
        echo "错误路径仍报 unbound variable:"; echo "$out"; return 1
    fi
    if (( rc != 1 )); then
        echo "期望退出码 1，实际 ${rc}"; return 1
    fi
    grep -q '下载失败:' <<<"$out" || { echo "缺少「下载失败:」提示:"; echo "$out"; return 1; }
    grep -q 'http://127.0.0.1:9/nacos.tar.gz' <<<"$out" \
        || { echo "提示未包含失败的 URL:"; echo "$out"; return 1; }
}

# ---- L7 文档与实测基线一致 ----
docs_match_measured_baseline() {
    local hits
    hits="$(grep -nE '约 25 秒|约 25s|logs/start\.out|不是幂等|约 20s|约 50 秒|返回的是 500' \
        "$ROOT/README.md" || true)"
    if [[ -n "$hits" ]]; then
        echo "README.md 残留过期描述:"; echo "$hits"; return 1
    fi
    hits="$(grep -nE 'start\.out|不是幂等|25–26s|返回 500' \
        "$ROOT/docs/teamflow/architecture/ARCHITECTURE.md" || true)"
    if [[ -n "$hits" ]]; then
        echo "ARCHITECTURE.md 残留过期描述:"; echo "$hits"; return 1
    fi
}

# ---- L8 平台路由不得覆盖 r1 的 /api/demo（基线回归的结构保证） ----
platform_routes_preserve_demo() {
    local f="$ROOT/api-gateway/src/main/java/com/example/gateway/PlatformRoutes.java"
    local g="$ROOT/api-gateway/src/main/java/com/example/gateway/JwtGatewayFilter.java"
    [[ -f "$f" ]] || { echo "缺少 ${f}（T0 未交付）：平台路由没有落地"; return 1; }
    if grep -n '/api/demo' "$f" >/dev/null 2>&1; then
        echo "PlatformRoutes 不得声明 /api/demo 路由（r1 的 /api/** 路由必须继续命中 demo）:"
        grep -n '/api/demo' "$f"
        return 1
    fi
    grep -q 'stripPrefix(2)' "$f" || { echo "PlatformRoutes 的 /api/req/** 缺 stripPrefix(2)"; return 1; }
    [[ -f "$g" ]] || { echo "缺少 ${g}（T0 未交付）：网关 JWT 过滤器没有落地"; return 1; }
    grep -q '/api/demo' "$g" || { echo "JwtGatewayFilter 未放行 /api/demo/**，r1 四步会被 401 打红"; return 1; }
}

# ---- L9 读路径单点：ScopedJdbc / SeedService 之外禁止 JdbcTemplate ----
# 白名单（每条例外都必须写明为什么不能走 ScopedJdbc；新增条目要在这里解释，而不是放宽 grep）：
#   requirement/RequirementService.java      quickTest 用 GeneratedKeyHolder 写 test_execution(MANUAL)；
#                                            originOrgId 在建租户上下文后补查根组织（无子树时的回退）
#   openapi/ApiClientStore.java              验签发生在租户上下文建立之前，只能按全局唯一 app_key 读
#   openapi/ApiClientAdminController.java    api_client 管理写（创建/轮换/停用），ScopedJdbc 只读
#   gate/GateController.java                 内部门禁端点只凭 X-Internal-Token 调用，请求里没有租户；
#                                            必须先按 requirement_id 反查 tenant_id 才能建 DataScope。
#                                            这正是 D-01 的根因（ScopedJdbc 的所有入口都要求 TenantContext）
#   quality/                                  M6 质量域：ScopedJdbc 只读，快速路径聚合（计划/轮次/报告版本/
#                                            门禁快照）需要在同一事务里读改写；与 deliver 既有的
#                                            NamedParameterJdbcTemplate 写路径约定一致，故整体放行
scoped_jdbc_single_source() {
    local allow_re='requirement/RequirementService\.java|openapi/ApiClientStore\.java|openapi/ApiClientAdminController\.java|gate/GateController\.java|quality/'
    local hits
    hits="$(grep -rl 'JdbcTemplate' "$ROOT/req-service/src/main/java" "$ROOT/deliver-service/src/main/java" 2>/dev/null \
        | grep -v '/seed/' | grep -Ev "$allow_re" || true)"
    if [[ -n "$hits" ]]; then
        echo "JdbcTemplate 只允许出现在 ScopedJdbc（platform-common）、SeedService 与上面注释里的白名单，实际:"
        printf '%s\n' "$hits"
        return 1
    fi
    local scope="$ROOT/platform-common/src/main/java/com/example/platform/common/scope/ScopedJdbc.java"
    [[ -f "$scope" ]] || { echo "缺少 ${scope}：强制 scope 标记的读入口没有落地"; return 1; }
    grep -q '/\*scope:' "$scope" || { echo "ScopedJdbc 缺少 /*scope:*/ 标记校验（fail-closed）"; return 1; }
}

# ---- L10 迁移只增不改：已跟踪的 db/migration 文件不得被修改 ----
migrations_append_only() {
    local dir="$ROOT/req-service/src/main/resources/db/migration" modified dup
    [[ -d "$dir" ]] || { echo "缺少迁移目录 ${dir}（T0 未交付）"; return 1; }
    modified="$(cd "$ROOT" && git diff --name-only -- req-service/src/main/resources/db/migration 2>/dev/null || true)"
    if [[ -n "$modified" ]]; then
        echo "已提交的迁移文件被修改（Flyway 校验会失败，历史库无法升级）:"
        echo "$modified"
        return 1
    fi
    dup="$(find "$dir" -maxdepth 1 -name 'V*__*.sql' -exec basename {} \; 2>/dev/null \
        | sed 's/__.*//' | sort | uniq -d || true)"
    if [[ -n "$dup" ]]; then
        echo "迁移版本号重复:"; echo "$dup"; return 1
    fi
    if ! find "$dir" -maxdepth 1 -name 'V*__*.sql' | grep -q .; then
        echo "没有 V*__*.sql 迁移文件"; return 1
    fi
}

# ---- L11 跨服务写表：deliver 不得写 req 侧的表 ----
deliver_does_not_write_req_tables() {
    local dir="$ROOT/deliver-service/src/main/java" hits
    [[ -d "$dir" ]] || { echo "缺少 ${dir}（T5/T6 未交付）"; return 1; }
    hits="$(grep -rniE '(insert[[:space:]]+into|update|delete[[:space:]]+from)[[:space:]]+(requirement|release|release_requirement|requirement_domain_rel|wf_instance|wf_task)\b' "$dir" 2>/dev/null || true)"
    if [[ -n "$hits" ]]; then
        echo "deliver 只允许读 req 侧表（ReqReadModel），发现写语句:"
        echo "$hits"
        return 1
    fi
}

# ---- L12 前端不复制状态表：views 里不得硬编码状态中文文案 ----
frontend_has_no_status_literals() {
    local dir="$ROOT/web/src/views" hits
    [[ -d "$dir" ]] || { echo "缺少 ${dir}（T8/T9 未交付）"; return 1; }
    hits="$(grep -rnE "'(待测试|测试中|待验收|已验收|开发中|需求池)'" "$dir" 2>/dev/null || true)"
    if [[ -n "$hits" ]]; then
        echo "状态文案只允许来自 dict/StatusPill，views 中发现字面量:"
        echo "$hits"
        return 1
    fi
}

# ---- L13 .gitignore 覆盖前端产物与运行态 ----
gitignore_covers_artifacts() {
    local f="$ROOT/.gitignore" miss=""
    [[ -f "$f" ]] || { echo "缺少 .gitignore"; return 1; }
    for pat in 'node_modules/' 'dist/' '.vite/' '.pnpm-store/' 'coverage/' '*.tsbuildinfo' '.env.*' 'var/' '*.hprof'; do
        grep -qxF "$pat" "$f" || miss="${miss} ${pat}"
    done
    if [[ -n "$miss" ]]; then
        echo "以下产物未被 .gitignore 覆盖:${miss}"
        return 1
    fi
}

# ---- L14 报告取数单点：报告总数/通过数只允许经 QualityAggregator（D-06 的类） ----
report_aggregation_single_source() {
    local f="$ROOT/deliver-service/src/main/java/com/example/deliver/quality/report/ReportService.java"
    local p="$ROOT/deliver-service/src/main/java/com/example/deliver/testplan/TestPlanQuery.java"
    [[ -f "$f" && -f "$p" ]] || { echo "缺少 ReportService/TestPlanQuery"; return 1; }
    grep -q 'QualityAggregator' "$f" \
        || { echo "ReportService 未引用 QualityAggregator：报告统计会与计划/门禁各算一套"; return 1; }
    grep -q 'QualityAggregator' "$p" \
        || { echo "TestPlanQuery 未引用 QualityAggregator：计划进度会与报告/门禁各算一套"; return 1; }
    local hits
    hits="$(grep -rn "SUM(pc.result" "$ROOT/deliver-service/src/main/java" 2>/dev/null || true)"
    if [[ -n "$hits" ]]; then
        echo "发现自算用例统计 SQL；总数/通过数必须经 QualityAggregator（唯一聚合实现）:"
        printf '%s\n' "$hits"
        return 1
    fi
}

log "静态门禁：脚本语法 / 变量插值 / 单点实现 / 错误提示 / 文档一致性"
run_check "L1 所有脚本 bash -n 语法通过" syntax_ok
run_check "L2 无 \$VAR 紧跟全角标点（防 set -u 吞变量名）" no_bare_var_before_wide_char
run_check "L3 own_jvm_pids 仅在 lib.sh 定义" defined_once_in_lib own_jvm_pids
run_check "L3 kill_own_jvms 仅在 lib.sh 定义" defined_once_in_lib kill_own_jvms
run_check "L3 chain_ok 仅在 lib.sh 定义" defined_once_in_lib chain_ok
run_check "L4 Nacos v3 配置 URL 仅由 lib.sh 生成" config_url_single_source
run_check "L5 Nacos token secret 可用 NACOS_TOKEN_SECRET 覆盖" token_secret_overridable
run_check "L6 下载失败提示可读（下载失败: + URL，退出码 1）" error_hint_works
run_check "L7 README/ARCHITECTURE 与实测基线一致" docs_match_measured_baseline
run_check "L8 平台路由不覆盖 r1 /api/demo 且网关放行 demo" platform_routes_preserve_demo
run_check "L9 JdbcTemplate 只在 ScopedJdbc（SeedService 白名单）" scoped_jdbc_single_source
run_check "L10 db/migration 只增不改、版本号唯一" migrations_append_only
run_check "L11 deliver 不写 req 侧表（只走 ReqReadModel）" deliver_does_not_write_req_tables
run_check "L12 前端 views 不复制状态中文文案" frontend_has_no_status_literals
run_check "L13 .gitignore 覆盖前端产物与运行态" gitignore_covers_artifacts
run_check "L14 报告取数只经 QualityAggregator（禁止 SUM(pc.result=...)）" report_aggregation_single_source

if (( FAILED > 0 )); then
    err "lint 失败 ${FAILED}/${TOTAL} 项：按上面的行号修正后重跑 make lint"
    exit 1
fi
ok "lint 全部通过（${TOTAL} 项）"
