#!/usr/bin/env bash
# s0-schema-migration.sh — 迁移与计数：4 张表列齐、sys_role 行数、37/73/1881（TECHNICAL §3.1/§3.3）
#
# 这一段是后面所有段的前提：迁移没落库或种子不齐，s1~s9 的失败都会指向错误的原因，
# 所以先在最前面把「形状」和「数量」钉死。V12 只做 DDL、V13 只建 VENDOR_PM 角色行（授权行数据
# 一律由 RbacSeeder 从常量反推），因此这里同时断言「表在」与「行数对」——只查其一都发现不了
# 「迁移跑了但种子没跑」。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"

TOTAL=0
PASS=0
FAIL=0
FAILED_LINES=""
T1_TENANT="${RBAC_TENANT:-t1}"

pass() {
    TOTAL=$((TOTAL + 1)); PASS=$((PASS + 1))
    printf '  \033[1;32mPASS\033[0m %s\n' "$1"
}

fail() {
    TOTAL=$((TOTAL + 1)); FAIL=$((FAIL + 1))
    printf '  \033[1;31mFAIL\033[0m %s\n' "$1"
    [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"
    FAILED_LINES="${FAILED_LINES}
  - $1"
}

report_counts() {
    if [[ -n "${RBAC_COUNTS:-}" ]]; then
        printf 's0 %s %s\n' "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

one() { db_q "$1" | head -n1; }

check_eq() { # check_eq <label> <want> <got> <hint>
    if [[ "$3" == "$2" ]]; then pass "$1（${3}）"; else fail "$1：实际 ${3:-<空>}，期望 ${2}" "${4:-}"; fi
}

col_count() { # col_count <table> <col...>
    local table="$1"; shift
    local list="" c
    for c in "$@"; do list="${list}'${c}',"; done
    list="${list%,}"
    one "SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = '${table}'
            AND column_name IN (${list})"
}

table_exists() {
    one "SELECT COUNT(*) FROM information_schema.tables
          WHERE table_schema = DATABASE() AND table_name = '$1'"
}

TENANT_ID="$(one "SELECT id FROM sys_tenant WHERE code = '${T1_TENANT}'")"
if [[ -z "${TENANT_ID}" ]]; then
    err "租户 ${T1_TENANT} 不存在：先 make platform-up && make platform-seed"
    report_counts
    exit 1
fi

log "s0：迁移形状与计数（tenant=${T1_TENANT} id=${TENANT_ID}）"

# ---------------------------------------------------------------- 1. 四张表
MISSING_TABLES=""
for t in sys_role sys_menu sys_action sys_role_permission; do
    [[ "$(table_exists "$t")" == "1" ]] || MISSING_TABLES="${MISSING_TABLES} ${t}"
done
if [[ -z "${MISSING_TABLES}" ]]; then
    pass "V12 三张新表 + sys_role 均在库（sys_menu / sys_action / sys_role_permission）"
else
    fail "缺表:${MISSING_TABLES}" "先跑 make platform-up 让 Flyway 到 V12"
fi

# ---------------------------------------------------------------- 2. 列
check_eq "sys_role 增列 description / status" 2 \
    "$(col_count sys_role description status)" \
    "TECHNICAL §3.1：ALTER TABLE sys_role ADD description / status"

check_eq "sys_menu 必需列齐全（code/module/name/route_name/function_codes/sort_no）" 6 \
    "$(col_count sys_menu code module name route_name function_codes sort_no)" \
    "sys_menu 是全局页面注册表（不带 tenant_id）"

check_eq "sys_action 必需列齐全（code/menu_code/name/function_codes/high_risk/sort_no）" 6 \
    "$(col_count sys_action code menu_code name function_codes high_risk sort_no)" \
    "sys_action 必须带 menu_code：动作归属页面是三序判定的第一依据"

check_eq "sys_role_permission 必需列齐全（含 subject_type/subject_code/access/uk_rp）" 7 \
    "$(col_count sys_role_permission tenant_id role_id subject_type subject_code access created_at updated_at)" \
    "三层授权共用一张表：subject_type ∈ FUNCTION|MENU|ACTION"

UK_RP="$(one "SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = DATABASE() AND table_name = 'sys_role_permission'
                AND index_name = 'uk_rp' AND non_unique = 0")"
check_eq "uk_rp 唯一键存在（tenant_id, role_id, subject_type, subject_code）" 4 "${UK_RP}" \
    "唯一键是 INSERT IGNORE 幂等的前提（AC-4）"

# ---------------------------------------------------------------- 3. 计数
ROLE_ROWS="$(one "SELECT COUNT(*) FROM sys_role WHERE tenant_id = ${TENANT_ID}")"
check_eq "t1 的 sys_role = 11 个内置角色（r24 起 VENDOR_PM）" 11 "${ROLE_ROWS}" \
    "V13 只保证 VENDOR_PM 角色行存在；行数少了说明迁移没跑（make platform-up）"

check_eq "sys_menu = 37（MenuRegistry 页面数）" 37 \
    "$(one "SELECT COUNT(*) FROM sys_menu")" \
    "RbacSeeder.seedRegistries 未跑或漏页"

check_eq "sys_action = 73（ActionRegistry 动作数）" 73 \
    "$(one "SELECT COUNT(*) FROM sys_action")" \
    "RbacSeeder.seedRegistries 未跑或漏动作"

check_eq "t1 的 sys_role_permission = 1881 行（671+407+803）" 1881 \
    "$(one "SELECT COUNT(*) FROM sys_role_permission WHERE tenant_id = ${TENANT_ID}")" \
    "RbacBootstrap/RbacSeeder.seedTenant 未补齐；先看 req-service 启动日志的 ERROR"

MIX="$(one "SELECT COUNT(*) FROM (
        SELECT subject_type, COUNT(*) AS c FROM sys_role_permission
         WHERE tenant_id = ${TENANT_ID} GROUP BY subject_type) x
       WHERE (subject_type = 'FUNCTION' AND c = 671)
          OR (subject_type = 'MENU'     AND c = 407)
          OR (subject_type = 'ACTION'   AND c = 803)")"
check_eq "分布 671 FUNCTION / 407 MENU / 803 ACTION" 3 "${MIX}" \
    "每层行数必须等于 11 角色 × 61/37/73"

THIN="$(one "SELECT COUNT(*) FROM (
        SELECT role_id, COUNT(*) AS c FROM sys_role_permission
         WHERE tenant_id = ${TENANT_ID} GROUP BY role_id) x WHERE c <> 171")"
check_eq "每个角色恰好 171 行（61+37+73 之外无缺口）" 0 "${THIN}" \
    "授权行必须写满：缺行在判定里等价于 DENY，会让内置角色突然失权"

# ---------------------------------------------------------------- 4. 迁移版本与全租户
FLYWAY="$(one "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1")"
# r39：V15__automation_rule.sql（自动化规则引擎四表）是当前最高版本；
# 本条同时兜住「V14/V15 迁移没跑成功」——版本落后会让后续所有授权断言在错库上假绿。
check_eq "Flyway 最新成功版本 = 15" 15 "${FLYWAY}" \
    "V15__automation_rule.sql 未执行（先 make platform-up）"

BAD_TENANTS="$(one "SELECT COUNT(*) FROM (
        SELECT t.id FROM sys_tenant t
          LEFT JOIN sys_role_permission p ON p.tenant_id = t.id
         GROUP BY t.id HAVING COUNT(*) <> 1881) x")"
check_eq "所有租户都补齐 1881 行（RbacBootstrap 遍历全部租户）" 0 "${BAD_TENANTS}" \
    "新租户由 TenantProvisioner 同事务种子；存量租户由 RbacBootstrap.seedAllTenants 补齐"

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "s0: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "s0-schema-migration: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
ok "s0-schema-migration: ${PASS}/${TOTAL} 项通过（V13 形状 + 37/73/1881）"
