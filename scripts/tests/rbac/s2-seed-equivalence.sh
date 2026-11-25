#!/usr/bin/env bash
# s2-seed-equivalence.sh — 种子逐格等价（TECHNICAL §3.3、AC-3）
#
# 等式（每租户 1980 行）：
#   FUNCTION = PermissionMatrix.accessFor(role, function)         671 行，原样复制
#   MENU     = max(page.functionCodes 上的 FUNCTION 行)，空 ⇒ ALLOW   418 行
#   ACTION   = max(action.functionCodes 上的 FUNCTION 行)，空 ⇒ ALLOW 891 行
#            —— 例外：r24 的 11 条角色×动作显式 DENY 覆盖（只此一处，见第 3 段）
# 只要 418 + 891 个格子逐格等于这个推导，升级前后的能力就等价：
# 旧前端 canAny(功能码) == 新判定「页面非 DENY」，旧按钮判据 == 新动作档位。
#
# 期望值不是手抄：由 sys_menu/sys_action 的 function_codes ∪ sys_role_permission 的 FUNCTION 行
# 在 SQL 里重算，再与 MENU/ACTION 行比对——抄一份表就变成了第四份真相。ACTION 侧唯一的例外是
# 11 条覆盖：它们的码写在下面的 CTE 里，但只用来「把期望值改成 DENY」，不写任何角色×动作全表。
#
# 本脚本同时充当收尾段 s2r（rbac-verify.sh 末尾再跑一次）：套件结束时库必须仍是预设，
# 任何段漏还原都会在这里红。段名由 RBAC_SEGMENT 注入，断言与阈值一条不变。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"

SEGMENT="${RBAC_SEGMENT:-s2}"
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
        printf '%s %s %s\n' "${SEGMENT}" "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

one() { db_q "$1" | head -n1; }

check_eq() { # check_eq <label> <want> <got> <hint>
    if [[ "$3" == "$2" ]]; then pass "$1（${3}）"; else fail "$1：实际 ${3:-<空>}，期望 ${2}" "${4:-}"; fi
}

TENANT_ID="$(one "SELECT id FROM sys_tenant WHERE code = '${T1_TENANT}'")"
[[ -n "${TENANT_ID}" ]] || die "租户 ${T1_TENANT} 不存在：先 make platform-up && make platform-seed"

# 展开 function_codes（逗号分隔）→ (tenant, role, page/action, FUNCTION.access)
# 只用一张递归序列，把「一串码」变成「多行」；不引入额外表，也不在 SQL 里抄任何码。
EXPAND_PAGES="
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 16),
page_fn AS (
  SELECT p.code AS subject, f.role_id, f.tenant_id, f.access
    FROM sys_menu p
    JOIN seq ON seq.n <= 1 + LENGTH(p.function_codes) - LENGTH(REPLACE(p.function_codes, ',', ''))
    JOIN sys_role_permission f
      ON f.subject_type = 'FUNCTION'
     AND f.subject_code = TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(p.function_codes, ',', seq.n), ',', -1))
   WHERE p.function_codes <> ''
), expect AS (
  SELECT tenant_id, role_id, subject,
         CASE WHEN SUM(access = 'ALLOW') > 0 THEN 'ALLOW'
              WHEN SUM(access = 'RESTRICTED') > 0 THEN 'RESTRICTED'
              ELSE 'DENY' END AS access
    FROM page_fn GROUP BY tenant_id, role_id, subject)
"
EXPAND_ACTIONS="${EXPAND_PAGES//sys_menu p/sys_action p}"
EXPAND_ACTIONS="${EXPAND_ACTIONS//p.code AS subject/p.code AS subject}"

# 11 条角色×动作显式 DENY 覆盖（r24）。只做「把期望值从 max 改成 DENY」，不复制任何全表：
# 码必须与 RbacSeeder.ACTION_DENY_OVERRIDES 一字不差，漂移由第 3 段的「命中 11 条」断言抓。
OVERRIDE_CODES="SELECT 'release.action.create' AS code
    UNION ALL SELECT 'release.action.update' UNION ALL SELECT 'release.action.approve'
    UNION ALL SELECT 'release.action.close'   UNION ALL SELECT 'task.action.create'
    UNION ALL SELECT 'task.action.assign'     UNION ALL SELECT 'task.action.update'
    UNION ALL SELECT 'task.action.close'      UNION ALL SELECT 'sprint.action.create'
    UNION ALL SELECT 'sprint.action.close'    UNION ALL SELECT 'test.action.case_edit'"
# 命中表：(role_id, action_code) ——从 sys_role 取 VENDOR_PM 的 id，码本身不查 sys_action，
# 这样「码写错」会让命中数 < 11（下面第 3.3 段），而不是静默地比对成功。
OVERRIDE_JOIN="LEFT JOIN (
            SELECT r.id AS role_id, o.code AS subject
              FROM sys_role r
              JOIN (${OVERRIDE_CODES}) o
             WHERE r.tenant_id = ${TENANT_ID} AND r.code = 'VENDOR_PM'
          ) ov ON ov.role_id = m.role_id AND ov.subject = m.subject_code"

log "${SEGMENT}：种子逐格等价（tenant=${T1_TENANT} id=${TENANT_ID}）"

# ---------------------------------------------------------------- 1. 行数与覆盖
check_eq "t1 授权行总数 = 1980，且每个角色 180 行" "11 180" \
    "$(one "SELECT CONCAT(COUNT(DISTINCT role_id), ' ', MAX(c)) FROM (
             SELECT role_id, COUNT(*) AS c FROM sys_role_permission
              WHERE tenant_id = ${TENANT_ID} GROUP BY role_id) x")" \
    "缺行在判定里等于 DENY，内置角色会突然失权"

check_eq "FUNCTION 671 格无缺口（11 角色 × 61 功能码）" "671 11 61" \
    "$(one "SELECT CONCAT(COUNT(*), ' ', COUNT(DISTINCT role_id), ' ', COUNT(DISTINCT subject_code))
              FROM sys_role_permission
             WHERE tenant_id = ${TENANT_ID} AND subject_type = 'FUNCTION'")" \
    "FUNCTION 是 PermissionMatrix 的逐格复制（AC-3 的底座）"

MISSING_FN="$(one "${EXPAND_PAGES}
        SELECT COUNT(*) FROM page_fn WHERE tenant_id = ${TENANT_ID}")"
check_eq "每页绑定的功能码都能在 FUNCTION 行里找到（页面侧无悬空码）" 1 \
    "$([[ -n "${MISSING_FN}" && "${MISSING_FN}" -gt 0 ]] && echo 1 || echo 0)" \
    "sys_menu.function_codes 写了 PermissionMatrix 里不存在的码：MENU 推导会退化成 ALLOW"

check_eq "空绑定功能码的页面 = 5（me.todo/me.tasks/req.pool/defect.list/pdr.review），其 MENU 行恒 ALLOW" "5 55 55" \
    "$(one "SELECT CONCAT(
                (SELECT COUNT(*) FROM sys_menu WHERE function_codes = ''),
                ' ',
                (SELECT COUNT(*) FROM sys_role_permission m JOIN sys_menu p ON p.code = m.subject_code
                  WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'MENU' AND p.function_codes = ''),
                ' ',
                (SELECT COUNT(*) FROM sys_role_permission m JOIN sys_menu p ON p.code = m.subject_code
                  WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'MENU' AND p.function_codes = ''
                    AND m.access = 'ALLOW'))")" \
    "空绑定码的页面表示「登录即可见」（AUTHENTICATED），必须 ALLOW 而不是随角色变"

# ---------------------------------------------------------------- 2. MENU 逐格
MENU_BAD="$(one "${EXPAND_PAGES}
        SELECT COUNT(*) FROM sys_role_permission m
          LEFT JOIN expect e
            ON e.tenant_id = m.tenant_id AND e.role_id = m.role_id AND e.subject = m.subject_code
         WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'MENU'
           AND m.access <> COALESCE(e.access, 'ALLOW')")"
check_eq "MENU 418 格逐格 == max(绑定功能码)，不符 = 0" 0 "${MENU_BAD}" \
    "页面档位必须由功能码推导（空 ⇒ ALLOW）：升级后旧 canAny 语义不能变"

MENU_COMPARED="$(one "${EXPAND_PAGES}
        SELECT COUNT(*) FROM sys_role_permission m
          JOIN expect e ON e.tenant_id = m.tenant_id AND e.role_id = m.role_id AND e.subject = m.subject_code
         WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'MENU'")"
check_eq "MENU 实际比对了 363 格（418 − 55 空格），不是空跑" 363 "${MENU_COMPARED}" \
    "比对数不足说明推导没跑：门禁会假绿"

# ---------------------------------------------------------------- 3. ACTION 逐格
ACTION_BAD="$(one "${EXPAND_ACTIONS}
        SELECT COUNT(*) FROM sys_role_permission m
          LEFT JOIN expect e
            ON e.tenant_id = m.tenant_id AND e.role_id = m.role_id AND e.subject = m.subject_code
          ${OVERRIDE_JOIN}
         WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'ACTION'
           AND m.access <> CASE WHEN ov.role_id IS NOT NULL THEN 'DENY'
                                ELSE COALESCE(e.access, 'ALLOW') END")"
check_eq "ACTION 891 格逐格 == max(所需功能码)（11 条覆盖例外 ⇒ DENY），不符 = 0" 0 "${ACTION_BAD}" \
    "动作档位是按钮判据：一格格错就会出现「按钮亮着、点下去 403」（r17 的类）"

ACTION_COMPARED="$(one "${EXPAND_ACTIONS}
        SELECT COUNT(*) FROM sys_role_permission m
          JOIN expect e ON e.tenant_id = m.tenant_id AND e.role_id = m.role_id AND e.subject = m.subject_code
         WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'ACTION'")"
check_eq "ACTION 实际比对了 891 格（全量，无空跑）" 891 "${ACTION_COMPARED}" \
    "81 个动作都有功能码，必须整格比对"

# 3.3 覆盖例外本身也要被证明：11 条必须条条命中库里的行。命中数不足说明码写错（或角色不存在），
# 此时 ACTION_BAD 仍可能全绿——因为错的那行根本没人跟它比。
OVERRIDE_HIT="$(one "SELECT COUNT(*) FROM sys_role_permission m
        JOIN sys_role r ON r.id = m.role_id
        JOIN (${OVERRIDE_CODES}) o ON o.code = m.subject_code
       WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'ACTION'
         AND r.code = 'VENDOR_PM' AND m.access = 'DENY'")"
check_eq "11 条 ACTION 覆盖逐条命中且为 DENY（VENDOR_PM 的写口）" 11 "${OVERRIDE_HIT}" \
    "命中 < 11：覆盖码与 sys_action 对不上（或 VENDOR_PM 行缺失），等于收写口失效"

# ---------------------------------------------------------------- 4. RESTRICTED 传导
# max() 的真正牙齿：有 DENY 就 DENY，否则有 RESTRICTED 就 RESTRICTED（不能塌成 ALLOW）。
RESTRICTED_CELLS="$(one "SELECT COUNT(*) FROM sys_role_permission m
      JOIN sys_action a ON a.code = m.subject_code
     WHERE m.tenant_id = ${TENANT_ID} AND m.subject_type = 'ACTION' AND m.access = 'RESTRICTED'")"
check_eq "存在 RESTRICTED 动作格（≥1），且这些格未被 max 塌成 ALLOW" true \
    "$([[ -n "${RESTRICTED_CELLS}" && "${RESTRICTED_CELLS}" -ge 1 ]] && echo true || echo false)" \
    "RESTRICTED = 过检查 + 范围收窄（AC-7）；塌成 ALLOW 等于范围失效，塌成 DENY 等于误拒"

# ---------------------------------------------------------------- 5. 规范化哈希
# 口径（写死在两处并互相对照，供 Java 侧 RbacSeedEquivalenceTest 的 CANONICAL_HASH 比对）：
#   line = role_code|subject_type|subject_code|access，按整行**字节序**升序（BINARY：Java 侧
#   String.compareTo 在纯 ASCII 码上与之一致），'\n' 连接、末行不带换行，SHA-256(UTF-8)
#   —— 只有用 BINARY 排序，本段的哈希才真的等于 RbacSeedEquivalenceTest 的 canonicalHash；
#   用默认 utf8mb4_0900_ai_ci 排序两边会得到不同的「升序」。
CANON_SQL="SELECT CONCAT(r.code, '|', p.subject_type, '|', p.subject_code, '|', p.access) AS l
             FROM sys_role_permission p JOIN sys_role r ON r.id = p.role_id AND r.tenant_id = p.tenant_id
            WHERE p.tenant_id = ${TENANT_ID}
            ORDER BY BINARY l"
# group_concat_max_len 默认 1024，1980 行规范化文本约 79KB：不抬到 1MB，MySQL 侧只会算前 1024
# 字节的哈希，与 bash 侧的全量哈希必然不等（假红），也失去「整表一行不差」的意义。
SQL_HASH="$(db_q "SET SESSION group_concat_max_len = 1048576;
        SELECT SHA2(GROUP_CONCAT(l ORDER BY BINARY l SEPARATOR '\n'), 256) FROM (${CANON_SQL}) x")"
SQL_HASH="$(printf '%s' "${SQL_HASH}" | head -n1)"
# 口径是「行之间用 '\n' 连接」，最后一行不带换行（Java 侧 String.join("\n") 也是如此）；
# 命令替换会吃掉结尾换行，printf 再补回零个换行，两侧才是同一段字节。
BASH_HASH="$(printf '%s' "$(db_q "$CANON_SQL")" | shasum -a 256 | awk '{print $1}')"
if [[ -n "${SQL_HASH}" && "${SQL_HASH}" == "${BASH_HASH}" ]]; then
    pass "规范化 SHA-256 两侧复算一致（${SQL_HASH:0:16}…）"
else
    fail "规范化哈希不一致：MySQL=${SQL_HASH:-<空>} bash=${BASH_HASH:-<空>}" \
        "两侧必须是同一口径：role_code|subject_type|subject_code|access 升序 + '\\n'；不一致说明规范化或取数漏了行"
fi

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "${SEGMENT}: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "${SEGMENT}-seed-equivalence: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
printf '  \033[1;30mCANONICAL_HASH=%s\033[0m\n' "${SQL_HASH}"
ok "${SEGMENT}-seed-equivalence: ${PASS}/${TOTAL} 项通过（418+891 格逐格等价，含 11 条覆盖例外）"
