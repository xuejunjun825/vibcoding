#!/usr/bin/env bash
# q0-schema.sh — AC-0 数据面基线：V5 迁移的 13 张新表、5 张表的增列、索引、5 项质量指标行、
# 幂等回填（R1 归集 / 历史状态映射 / 缺陷来源），以及「迁移可以再跑一遍」。
#
# 只依赖数据库，不依赖服务：schema 是其它 7 个脚本的前置条件，能在服务起来之前先拦住。
# 迁移 owner 只有 req-service 一个（deliver 无迁移机制），所以这里直接跑那一份 SQL。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

MIGRATION="$ROOT/req-service/src/main/resources/db/migration/V5__quality_management.sql"
QUALITY_TABLES=(
    test_strategy test_plan_round plan_assignment plan_change_log test_env
    test_report test_report_version report_signoff quality_gate quality_gate_rule
    quality_gate_snapshot quality_gate_waiver release_review
)
QUALITY_METRICS=(FPY REGRESSION_PASS_RATE DEFECT_ESCAPE_RATE POST_RELEASE_DEFECTS_72H ROLLBACK_RATE)

section "q0 · 迁移文件与环境"
if [[ -f "$MIGRATION" ]]; then pass "V5 迁移文件存在"; else fail "V5 迁移文件缺失: $MIGRATION" "迁移只能写在 req-service/src/main/resources/db/migration/"; fi
if db_up; then pass "MySQL 可达 ${PLATFORM_DB_HOST}:${PLATFORM_DB_PORT}"; else fail "MySQL 不可达" "make platform-up"; fi
db_exists && pass "数据库 ${PLATFORM_DB_NAME} 存在" || fail "数据库 ${PLATFORM_DB_NAME} 不存在" "make platform-up"
if (( FAIL > 0 )); then guard q0-schema; fi

section "q0 · Flyway 已应用 V5"
V5_ROWS="$(db_q "SELECT COUNT(*) FROM flyway_schema_history WHERE version='5' AND success=1")"
expect_num "flyway_schema_history 有 version=5 success=1" "${V5_ROWS:-0}" ge 1
MAX_VERSION="$(db_q "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")"
expect_num "最新成功版本号 ≥ 5" "${MAX_VERSION:-0}" ge 5

section "q0 · 13 张质量表"
MISSING_TABLES=""
for t in "${QUALITY_TABLES[@]}"; do
    [[ "$(db_q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${PLATFORM_DB_NAME}' AND table_name='${t}'")" == "1" ]] || MISSING_TABLES="${MISSING_TABLES} ${t}"
done
if [[ -z "$MISSING_TABLES" ]]; then
    pass "13 张新表全部存在"
else
    fail "缺表:${MISSING_TABLES}" "V5 必须 CREATE TABLE IF NOT EXISTS 建齐 13 张表"
fi

section "q0 · 5 张既有表的增列"
declare -a COL_SPECS=(
    "test_plan strategy_id"
    "test_plan env_id"
    "test_plan entry_criteria_json"
    "test_plan exit_criteria_json"
    "test_plan approved_by"
    "test_plan approved_at"
    "test_plan revision"
    "test_plan closed_at"
    "test_plan close_reason"
    "test_plan review_comment"
    "test_execution round_id"
    "test_execution env_id"
    "deploy_order quality_gate_id"
    "deploy_order quality_gate_snapshot_id"
    "deploy_order report_id"
    "defect source"
    "defect root_cause"
    "defect found_phase"
    "defect is_escape"
    "defect escape_window_end"
    "release observation_hours"
    "release report_validity_days"
)
MISSING_COLS=""
for spec in "${COL_SPECS[@]}"; do
    tbl="${spec%% *}"; col="${spec#* }"
    n="$(db_q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${PLATFORM_DB_NAME}' AND table_name='${tbl}' AND column_name='${col}'")"
    [[ "$n" == "1" ]] || MISSING_COLS="${MISSING_COLS} ${tbl}.${col}"
done
if [[ -z "$MISSING_COLS" ]]; then
    pass "22 个新增列全部存在（test_plan 10 / test_execution 2 / deploy_order 3 / defect 5 / release 2）"
else
    fail "缺列:${MISSING_COLS}" "V5 的 information_schema 动态 ALTER 必须每列都补上"
fi

section "q0 · 并发/唯一性索引（AC-2/4/5/6 的兜底）"
declare -a UK_SPECS=(
    "test_plan_round uk_round_active"
    "test_plan_round uk_round"
    "test_strategy uk_strategy"
    "test_env uk_env"
    "test_report_version uk_report_version"
    "report_signoff uk_signoff"
    "quality_gate uk_gate_release"
    "quality_gate_rule uk_gate_rule"
    "plan_assignment uk_assign"
)
MISSING_UK=""
for spec in "${UK_SPECS[@]}"; do
    tbl="${spec%% *}"; idx="${spec#* }"
    n="$(db_q "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='${PLATFORM_DB_NAME}' AND table_name='${tbl}' AND index_name='${idx}' AND non_unique=0")"
    [[ "${n:-0}" -ge 1 ]] || MISSING_UK="${MISSING_UK} ${tbl}.${idx}"
done
if [[ -z "$MISSING_UK" ]]; then
    pass "9 个唯一键存在（同计划仅一轮、报告版本号、会签角色不可重复）"
else
    fail "缺唯一键:${MISSING_UK}" "并发 409 依赖 DB 唯一键，不能只靠先查后写"
fi
SETTLED="$(db_q "SELECT COUNT(*) FROM (SELECT plan_id FROM test_plan_round WHERE active=1 GROUP BY plan_id HAVING COUNT(*)>1) t")"
expect_num "没有计划同时存在两个 active 轮次" "${SETTLED:-0}" eq 0

section "q0 · 5 项质量指标行（metric_definition）"
# q0 守的是 V5 迁移本身：只断言「V5 安装前已存在的租户/计划」。
# 运行时（隔离验证、并发开发）会持续新建租户/计划，它们的指标与轮次由后续代码负责，
# 拿全表计数当迁移失败会误报，且让 q0 依赖 suite 执行顺序。
V5_INSTALLED="$(db_q "SELECT installed_on FROM flyway_schema_history WHERE version='5' AND success=1 ORDER BY installed_rank DESC LIMIT 1")"
V5_TENANT=""
V5_PLAN=""
if [[ -n "$V5_INSTALLED" ]]; then
    V5_TENANT="AND t.created_at < '${V5_INSTALLED}'"
    V5_PLAN="AND p.created_at < '${V5_INSTALLED}'"
fi
TENANTS="$(db_q "SELECT COUNT(*) FROM sys_tenant t WHERE 1=1 ${V5_TENANT}")"
TENANTS_WITH_5="$(db_q "SELECT COUNT(*) FROM (SELECT d.tenant_id FROM metric_definition d JOIN sys_tenant t ON t.id=d.tenant_id WHERE d.topic='quality' AND d.version=1 ${V5_TENANT} GROUP BY d.tenant_id HAVING COUNT(*)=5) t")"
expect_num "每个租户恰好 5 条质量指标（${TENANTS_WITH_5}/${TENANTS}）" "${TENANTS_WITH_5:-0}" eq "${TENANTS:-1}"
for code in "${QUALITY_METRICS[@]}"; do
    n="$(db_q "SELECT COUNT(DISTINCT d.tenant_id) FROM metric_definition d JOIN sys_tenant t ON t.id=d.tenant_id WHERE d.topic='quality' AND d.version=1 AND d.code='${code}' ${V5_TENANT}")"
    expect_num "指标 ${code} 覆盖全部租户" "${n:-0}" eq "${TENANTS:-1}"
done

section "q0 · 幂等回填（历史数据迁移）"
LEGACY="$(db_q "SELECT COUNT(*) FROM test_plan WHERE status IN ('RUNNING','DONE')")"
expect_num "旧状态 RUNNING/DONE 已映射为 IN_PROGRESS/COMPLETED" "${LEGACY:-1}" eq 0
ORPHAN="$(db_q "SELECT COUNT(*) FROM test_execution e JOIN test_plan p ON p.id=e.plan_id WHERE e.round_id IS NULL ${V5_PLAN}")"
expect_num "计划级执行都已归集到轮次（R1）" "${ORPHAN:-1}" eq 0
expect_num "至少有一条 R1 归集轮次" "$(db_q "SELECT COUNT(*) FROM test_plan_round WHERE round_no=1")" ge 1
if [[ -n "$V5_INSTALLED" ]]; then
    expect_num "V5 前的老计划准出条件已补齐" "$(db_q "SELECT COUNT(*) FROM test_plan WHERE created_at < '${V5_INSTALLED}' AND exit_criteria_json IS NULL")" eq 0
    expect_num "V5 前的老计划已挂载策略版本" "$(db_q "SELECT COUNT(*) FROM test_plan WHERE created_at < '${V5_INSTALLED}' AND strategy_id IS NULL")" eq 0
else
    skip "拿不到 V5 安装时间，跳过老计划回填断言"
fi
expect_num "每个计划的 R1 归集轮次不重复" "$(db_q "SELECT COUNT(*) FROM (SELECT plan_id FROM test_plan_round WHERE round_no=1 AND round_type='FEATURE' GROUP BY plan_id HAVING COUNT(*)>1) t")" eq 0
BAD_REVISION="$(db_q "SELECT COUNT(*) FROM test_plan WHERE revision IS NULL OR revision < 0")"
expect_num "test_plan.revision 默认 0 且非空" "${BAD_REVISION:-1}" eq 0
BAD_WINDOW="$(db_q "SELECT COUNT(*) FROM \`release\` WHERE observation_hours NOT BETWEEN 1 AND 720 OR report_validity_days NOT BETWEEN 1 AND 90")"
expect_num "release 观察窗/报告有效期落在合法区间" "${BAD_WINDOW:-1}" eq 0
BAD_SOURCE="$(db_q "SELECT COUNT(*) FROM defect WHERE source IS NULL OR source NOT IN ('MANUAL','TEST','PROD','OPENAPI','BUILD')")"
expect_num "defect.source 取值在枚举内" "${BAD_SOURCE:-1}" eq 0
expect_num "缺陷逃逸标记默认 0" "$(db_q "SELECT COUNT(*) FROM defect WHERE is_escape IS NULL")" eq 0

section "q0 · 迁移可重跑（Flyway 之外的手工重放不改变数据）"
BEFORE_TABLES="$(db_q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${PLATFORM_DB_NAME}'")"

BEFORE_LOG="$(db_count plan_change_log)"
REPLAY_LOG="$(mktemp -t q0-v5-replay.XXXXXX)"
if MYSQL_PWD="${PLATFORM_DB_PASSWORD:-}" mysql --protocol=TCP -h "$PLATFORM_DB_HOST" -P "$PLATFORM_DB_PORT" \
        -u "$PLATFORM_DB_USER" "$PLATFORM_DB_NAME" < "$MIGRATION" >"$REPLAY_LOG" 2>&1; then
    pass "V5 重复执行退出码 0（CREATE TABLE IF NOT EXISTS + 动态 ALTER + WHERE NOT EXISTS）"
else
    fail "V5 重复执行失败: $(tail -n 3 "$REPLAY_LOG" | tr '\n' ' ')" "迁移必须可重跑，否则回滚/重建环境会中断"
fi
rm -f "$REPLAY_LOG"
expect_db "重放后表数量不变" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${PLATFORM_DB_NAME}'" eq "${BEFORE_TABLES:-0}"
# 轮次总量会被并发用例推进，这里改为断言「重放不给任何计划新增重复 R1 归集轮次」——这才是幂等的语义。
expect_num "重放后不存在重复 R1 归集轮次" "$(db_q "SELECT COUNT(*) FROM (SELECT plan_id FROM test_plan_round WHERE round_no=1 AND round_type='FEATURE' GROUP BY plan_id HAVING COUNT(*)>1) t")" eq 0
expect_num "重放后无遗留轮次引用（round_id 空）" "$(db_q "SELECT COUNT(*) FROM test_execution e JOIN test_plan p ON p.id=e.plan_id WHERE e.round_id IS NULL ${V5_PLAN}")" eq 0
expect_num "重放后质量指标不重复（每租户每 code 至多 1 行）" "$(db_q "SELECT COUNT(*) FROM (SELECT tenant_id, code FROM metric_definition WHERE topic='quality' AND version=1 GROUP BY tenant_id, code HAVING COUNT(*)>1) x")" eq 0
expect_db "重放后变更日志数量不变" "SELECT COUNT(*) FROM plan_change_log" eq "${BEFORE_LOG:-0}"

guard q0-schema
