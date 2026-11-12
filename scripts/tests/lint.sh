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
#   integration/                              M7 集成域：三类都必须绕过 ScopedJdbc，且都显式带 tenant_id 参数：
#                                            (1) 后台任务（BuildPollingJob/MrFallbackPollingJob/SonarRefreshJob）
#                                                按 tenant 遍历，请求里根本没有 TenantContext；
#                                            (2) 快照读改写（sonar 快照 upsert 用 GeneratedKeyHolder 拿自增 id）；
#                                            (3) 幂等 upsert / 单调写（mr_sync 的 last_event_at 比较、build_record
#                                                的唯一键冲突重试），ScopedJdbc 只有读入口。
#                                            所有语句一律 `AND tenant_id = ?` 绑定，不放宽为拼接。
#   collab/                                   M8 CTeam/CCode 协作域：ScopedJdbc 只有读入口，而下列写路径必须
#                                            在同一事务里「锁行 → 校验版本/父窗口/唯一性 → 写 → 产版/审计」：
#                                            甘特改期乐观锁与确认码消费（GanttRescheduleService）、Wiki 建改移删
#                                            与产版/回滚（WikiPageService/WikiVersionService）、空间 CRUD、
#                                            回收站 purge 后台任务（按 tenant 遍历，请求里没有 TenantContext）、
#                                            分支策略 upsert 与核验记录（BranchPolicyService）。
#                                            读路径（甘特三视图取数、Wiki 树/详情/检索/版本/回收站/关联）
#                                            一律走 ScopedJdbc + `/*scope:*/` + WikiVisibility；直连语句
#                                            显式 `tenant_id = :tenantId`，进入前先 assertVisible* 校验。
#   flow/ review/ executor/                   M8 交付域：阶段配置、用例评审裁决（失效与用例升版同事务）、
#                                            执行器台账（code 唯一冲突重试）都需要同事务读改写；
#                                            列表读走 ScopedJdbc，直连语句同样显式绑定 tenant_id。
#   testplan/TestPlanService.java            测试数据说明：COMPLETED 锁定与 revision 乐观锁必须在同一事务里
#                                            `SELECT ... FOR UPDATE` 后 UPDATE；读路径仍走 ScopedJdbc（jdbcRead）。
#   analytics/AnalyticsDirectory.java        只读「角色 code → sys_role.id」的授权目录（组装共享可见性
#                                            Viewer.roleIds）；不取业务数据、不参与行级过滤，故不走 ScopedJdbc。
scoped_jdbc_single_source() {
    local allow_re='requirement/RequirementService\.java|openapi/ApiClientStore\.java|openapi/ApiClientAdminController\.java|gate/GateController\.java|quality/|integration/|collab/|flow/|review/|executor/|testplan/TestPlanService\.java|analytics/AnalyticsDirectory\.java'
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

# ---- L15 分析域取数原语单点：portal/dashboard/editor 三页共用 useAnalyticsFeed（D2 的类） ----
# 缺陷现场：编辑器自建 runTimer/runSeq/inflight 与手动防抖，与另两页各写一套；
# 这条断言把「三页共用一层」钉死：负向禁止自建原语，正向要求三页都 import 单点。
analytics_feed_single_source() {
    local root="$ROOT/web/src" composable="$ROOT/web/src/composables/useAnalyticsFeed.ts"
    local f missing="" hand=""
    [[ -f "$composable" ]] || { echo "缺少 web/src/composables/useAnalyticsFeed.ts"; return 1; }
    grep -q 'AbortController' "$composable" \
        || { echo "useAnalyticsFeed 未持 AbortController：abort 不再有单点"; return 1; }
    for f in "$root/views/portal/PortalView.vue" "$root/views/analytics/DashboardView.vue" \
             "$root/views/reports/ReportEditorView.vue"; do
        [[ -f "$f" ]] || { echo "取数页缺失: $f"; return 1; }
        grep -q "@/composables/useAnalyticsFeed" "$f" || missing="$missing $f"
    done
    if [[ -n "$missing" ]]; then
        echo "以下取数页未 import useAnalyticsFeed（会各写一套防抖+abort+竞态）:"
        printf '%s\n' "$missing"
        return 1
    fi
    # 自检：判据必须命中修复前的真实写法，否则这条门禁形同虚设
    if ! printf '%s\n' 'const controller = new AbortController()' 'const current = ++runSeq' \
        | grep -qE 'new[[:space:]]+AbortController[[:space:]]*\(|(runSeq|requestSeq|loadSeq)[[:space:]]*(\+\+|=)'; then
        echo "L15 自检失效：判据已不再命中修复前写法（提取器坏了）"
        return 1
    fi
    local hits
    hits="$(grep -rnE 'new[[:space:]]+AbortController[[:space:]]*\(|(runSeq|requestSeq|loadSeq)[[:space:]]*(\+\+|=)' \
        "$root/views/portal" "$root/views/analytics" "$root/views/reports" 2>/dev/null \
        | grep -vE ':[0-9]+:[[:space:]]*(\*|//)' || true)"
    if [[ -n "$hits" ]]; then
        echo "views 内自建取数原语（只允许存在于 useAnalyticsFeed）:"
        printf '%s\n' "$hits"
        return 1
    fi
    # 手写防抖：同一视图里既有 DEBOUNCE 常量又调用 setTimeout(
    for f in $(grep -rl 'DEBOUNCE' "$root/views/portal" "$root/views/analytics" "$root/views/reports" 2>/dev/null || true); do
        if grep -q 'setTimeout[[:space:]]*(' "$f"; then
            hand="$hand $f"
        fi
    done
    if [[ -n "$hand" ]]; then
        echo "视图内手写防抖定时器（应改用 useAnalyticsFeed 的 load/reload）:"
        printf '%s\n' "$hand"
        return 1
    fi
}

# ---- L16 体检可重复：密度按容差判等 + 门禁自建缺陷自清理（D6 的类） ----
# 缺陷现场：platform-verify.sh 用 awk printf "%.2f" 与服务端 BigDecimal HALF_UP 的两位小数做串相等，
# d/r 恰为 x.xx5 时 0.125 被判 0.12 vs 0.13 → 假红；AC-13 每次新建「验证缺陷-<HHMMSS>」且无人删除，
# 同一 DB 连跑会让 d/r 逐次漂移。两条一起才可重复，缺一不可。
verify_gate_repeatable() {
    local f="$ROOT/scripts/platform-verify.sh" n
    [[ -f "$f" ]] || { echo "缺少 scripts/platform-verify.sh"; return 1; }
    # 自检：负向判据必须命中修复前的真实写法，否则这条门禁形同虚设
    if ! printf '%s\n' '[[ "$CALC" == "$GOT" ]]' | grep -qF '[[ "$CALC" == "$GOT" ]]'; then
        echo "L16 自检失效：负向判据不再命中修复前写法（提取器坏了）"
        return 1
    fi
    if grep -qF '[[ "$CALC" == "$GOT" ]]' "$f"; then
        echo "platform-verify.sh 仍用两位小数串相等判密度：x.xx5 时两侧舍入不同会假红"
        return 1
    fi
    grep -qF 'diff <= 0.0055' "$f" \
        || { echo "platform-verify.sh 密度缺少 <=0.0055 容差比较（0.005 会被 double 误差击穿）"; return 1; }
    grep -q 'DELETE FROM defect' "$f" \
        || { echo "platform-verify.sh 未清理自建的「验证缺陷-*」（d/r 会逐次漂移）"; return 1; }
    n="$(grep -c '^cleanup_verify_defects$' "$f" || true)"
    [[ "$n" -ge 2 ]] \
        || { echo "cleanup_verify_defects 调用点 ${n} 处：创建前清历史 + 断言后清本轮，共需 2 处"; return 1; }
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
run_check "L15 分析域取数原语单点（三页共用 useAnalyticsFeed）" analytics_feed_single_source
run_check "L16 体检可重复：密度容差判等 + 自建缺陷自清理" verify_gate_repeatable

if (( FAILED > 0 )); then
    err "lint 失败 ${FAILED}/${TOTAL} 项：按上面的行号修正后重跑 make lint"
    exit 1
fi
ok "lint 全部通过（${TOTAL} 项）"
