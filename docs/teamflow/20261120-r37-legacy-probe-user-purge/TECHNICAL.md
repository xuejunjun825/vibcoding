# r37 技术方案 · 遗留探针账号清理单点化（scripts/lib/temp-accounts.sh）

- 任务夹：`docs/teamflow/20261120-r37-legacy-probe-user-purge/`
- 基线依赖：`docs/teamflow/20261120-r36-rbac-s7-idempotent/`
- 分支：`feat/legacy-probe-user-purge`（main = `81967c4`）；runId `tf-mwookg49-88sn5b`
- 对应 PRD：同夹 `PRD.md`（AC-1…AC-10）；本文只写「怎么做」，不复述需求
- 改动面：`scripts/` + `docs/` + `Makefile:106` 一行；**无 Java、无迁移、无 pom、无 web**

## 1. 结论（一句话）

把「门禁临时账号是谁 / 谁不许删 / 怎么删」收敛为**唯一文件** `scripts/lib/temp-accounts.sh`（4 个显式前缀 + 14 名保护名单 + 只删 `DISABLED`），让三个消费方共用同一份白名单：薄入口 `legacy-probe-purge.sh` 负责一次性清历史、`gate-order-verify.sh` 第 18 条改成「白名单用户任意状态 0 + DISABLED 0 + 临时角色 0」并新增第 19 条负向自检、`platform-demo-reset.sh` 在 Java 停用步骤之后接入同一清理并补第 76 条。

## 2. 现状与四个必须尊重的实测事实

| # | 实测（2026-11-20，`db_up=yes`） | 对设计的强制影响 |
|---|---|---|
| F1 | 白名单正则命中 4 行，全部 `DISABLED`：`t6probeu`(id72)、`t6pd72599`(76)、`t6pe72710`(77)、`probe11272`(452)；`probe11272` 带 `sys_user_role` 2 行（PMO/QA）、`sys_user_domain` 0 行 | 首跑 `purged=4`；必须先删 join 行再删 `sys_user`，否则外键/孤儿 |
| F2 | 全库 `sys_user_role` 孤儿 = **0** | 可做全局判据（AC-2 要求） |
| F3 | 全库 `sys_user_domain` 孤儿 = **413**（历史存量，早于本需求） | **绝不能**做全局判据，否则永红；域关系证据改为「本次删除的用户 id 集合内 join 行 0」（见 §4.2） |
| F4 | 14 名保护账号：t1 内 14 条 ACTIVE；**全库同名 ACTIVE = 89**（`t2-*` 历史租户各留 1 条） | 保护账号的「14 名 ACTIVE」必须**限定 t1**；白名单用户计数仍按 AC-5 走全库 |

补充事实：`sys_tenant` 有 `UNIQUE uk_tenant_code(code)`；`sys_role(tenant_id, code)`；`sys_user` 的 `name`、`password_hash` 为 `NOT NULL`（第 19 条插探针必须给值）；`scripts/tests/lint.sh` L2 禁止 `${VAR}` 后紧跟全角字符（本轮所有新脚本一律写 `${VAR}`）。

## 3. 架构：一个单点原语，三个消费方

```
scripts/lib.sh                     （db_q / db_up / db_count / err / ok，已存在）
        │  source（调用方负责，本库不 source 回来，避免重复 source 与循环）
        ▼
scripts/lib/temp-accounts.sh       ★新增：常量 + 9 个函数（唯一实现）
        ├── scripts/legacy-probe-purge.sh      ★新增：薄入口（AC-2 证据面）
        ├── scripts/gate-order-verify.sh       改：#18 用函数、新增 #19、18→19 条
        └── scripts/platform-demo-reset.sh     改：Java 停用后清理、75→76 条
```

- **为什么单独成文件而不是塞进 `scripts/lib.sh`**：`lib.sh` 是「环境/DB/HTTP/日志」通用层，被 30+ 脚本 source；账号白名单是门禁语义，放进去会让每个脚本都背上它。放 `scripts/tests/rbac/_lib.sh` 也不行——那个文件只服务 rbac 五段，且被 gate-order 静态第 5 条逐字断言覆盖，改它风险更高。
- **为什么不 source `lib.sh`**：保持依赖单向（`lib.sh` 不知晓账号语义），由调用方保证 `db_q` 可用；`temp_accounts_db_ready` 显式检查并给出修复命令，替代隐式依赖。
- **唯一真源**：`TEMP_ACCOUNT_REGEX` 全仓只在本文件生成；`gate-order-verify.sh` 与 `platform-demo-reset.sh` 里的 `^(s3|s5|s6|s7|s8)` / `t6p` 字面量必须删净（AC-1 反例判据 `grep -c` = 0）。`platform-demo-reset.sh:346` 的 `TEMP_ACTIVE` 正则（与 `DemoFixture.DISABLE_GATE_TEMP_USERS` 同源，管「停用谁」）**不动**——它和本文件管「删谁」是两件事，注释里写明。

## 4. 数据模型与存储

### 4.1 无 schema 变更

不新增迁移、不改表结构。涉及三张既有表（`req-service/src/main/resources/db/migration/V1__platform_schema.sql`）：`sys_user`(86-103)、`sys_user_role`(119-125)、`sys_user_domain`(127-133)。删除顺序即依赖序：**join 行 → 主表行**。

### 4.2 删除语句（固定三条，均在单事务语义内顺序执行）

```sql
-- ① 角色关系（借 u.username 定位，先于主表删）
DELETE ur FROM sys_user_role ur JOIN sys_user u
  ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
 WHERE u.status = 'DISABLED' AND u.username REGEXP '<TEMP_ACCOUNT_REGEX>'
   AND u.username NOT IN <TEMP_PROTECTED_SQL>;

-- ② 域关系（同构）
DELETE ud FROM sys_user_domain ud JOIN sys_user u
  ON u.id = ud.user_id AND u.tenant_id = ud.tenant_id
 WHERE u.status = 'DISABLED' AND u.username REGEXP '<TEMP_ACCOUNT_REGEX>'
   AND u.username NOT IN <TEMP_PROTECTED_SQL>;

-- ③ 主表
DELETE FROM sys_user
 WHERE status = 'DISABLED' AND username REGEXP '<TEMP_ACCOUNT_REGEX>'
   AND username NOT IN <TEMP_PROTECTED_SQL>;
```

- 三个 `WHERE` **逐字相同**（`status='DISABLED'` + 正则 + 保护名单兜底），这是 AC-1「只删 DISABLED」与 AC-3「保护名零删除」在 SQL 层的双保险：守卫先拒（可见失败），`NOT IN` 再兜底（不可见但安全）。
- `NOT IN` 用 `TEMP_PROTECTED_SQL`（14 名）；`TEMP_PROTECT_EXTRA` 注入名只进守卫，不删任何行——守卫不通过时函数在删前 `return 1`。
- **删前抓 id**：`temp_purge_disabled` 先 `SELECT id ... ORDER BY id` 得到 `IDS`，三删之后复核
  `SELECT (SELECT COUNT(*) FROM sys_user_role WHERE user_id IN (IDS)) + (SELECT COUNT(*) FROM sys_user_domain WHERE user_id IN (IDS))` 必须为 0。
  这就是 F3 的绕法：不动 413 条历史域孤儿，只证明**本次删掉的这批**没有留 join 行。
- 租户过滤：按 §11 Q1，删用户/查用户**不过滤租户**（全库）；只有角色计数与保护账号计数限定 `t1`（F4）。

## 5. API 设计

### 5.1 `scripts/lib/temp-accounts.sh`（对外契约，dev 必须逐字使用这些名字）

常量：

| 名称 | 值 / 语义 |
|---|---|
| `TEMP_ACCOUNT_PATTERNS` | 恰 4 元素数组：`s[3-8]`、`t6p`、`t6probe`、`probe`；每个元素上一行写来源注释 |
| `TEMP_ACCOUNT_REGEX` | 由数组拼出：`^(s[3-8]|t6p|t6probe|probe)[a-z0-9]*$`（全仓唯一生成点） |
| `TEMP_ROLE_PREFIXES` / `TEMP_ROLE_REGEX` | `('S5' 'S7' 'S8')` / `^(S5|S7|S8)` |
| `TEMP_PROTECTED_USERS` | 恰 14：`admin pm tl1 po1 biz1 suite1 dev1 dev2 qa1 qa2 vendor1 vendor2 mgr1 vpm1` |
| `TEMP_PROTECTED_SQL` | `('admin','pm',…,'vpm1')`，由上一行数组生成，勿手写第二份 |
| `TEMP_PROTECT_EXTRA` | 测试注入缝，空格或逗号分隔，默认空 |
| `TEMP_TENANT_CODE` | `${PLATFORM_TENANT:-t1}`，角色与保护账号计数的租户口径 |

函数（全部**只打 stdout 一个数字/一行**，失败 `return 1` 并在 stderr 说明）：

| 签名 | 语义 |
|---|---|
| `temp_accounts_db_ready()` | `db_q` 已定义且 `db_up` 通过；否则 stderr 提示 `make platform-up` / `PLATFORM_DB_*` 并 `return 1` |
| `temp_accounts_guard()` | ①`TEMP_PROTECTED_USERS`+`TEMP_PROTECT_EXTRA` 逐个与 `TEMP_ACCOUNT_REGEX` 求交，非空则点名报错 `return 1`；②`temp_protected_active_count` 必须 = 14，否则点名缺失者 `return 1` |
| `temp_users_left()` | `SELECT COUNT(*) FROM sys_user WHERE username REGEXP '<REGEX>'`（全库、任意状态） |
| `temp_disabled_left()` | 同上 + `status='DISABLED'` |
| `temp_disabled_usernames()` | 每行一个 username（`ORDER BY id`），供错误信息打印 |
| `temp_protected_active_count()` | t1 内 14 名中 `status='ACTIVE'` 的条数（期望 14） |
| `temp_orphan_join_left()` | 全库 `sys_user_role` 孤儿条数（`LEFT JOIN sys_user` 为 NULL），期望 0；**不含** `sys_user_domain`（F3 理由写进注释） |
| `temp_roles_left()` | t1 内 `code REGEXP '^(S5|S7|S8)'` 的角色条数，期望 0 |
| `temp_purge_disabled()` | 守卫 → 抓 id → 三删 → 复核本次 id 的 join 行 0 → 回显删除条数；任一步失败 `return 1`（禁止把失败当 0） |

私有：`_temp_count <sql> <label>` —— 取 `db_q` 首行，**必须匹配 `^[0-9]+$`** 才输出，否则 stderr 报「返回 '<空>' 不是计数」并 `return 1`。这是杀掉「`db_q` 丢弃 mysql stderr → 空串被当 0 → 假绿」的唯一闸门（AC-2「DB 不可连必须 exit 1」靠它）。

### 5.2 `scripts/legacy-probe-purge.sh`（薄入口）

- 用法：`bash scripts/legacy-probe-purge.sh`，无参数，无子命令。
- stdout 关键行：`purged=<N> remaining=0 users_left=<N> orphans_role=0`（AC-2 判据按 `purged=`/`remaining=` 取数）。
- 退出码：`0` 全部达成；`1` DB 不可连 / 守卫不成立 / 删后仍有残留（点名 + 修复建议）。
- 流程：`temp_accounts_db_ready` → 打印正则与保护名单 → `temp_accounts_guard`（失败即零删除退出）→ 打印将删条数与用户名 → `temp_purge_disabled` → `temp_disabled_left`/`temp_users_left`/`temp_orphan_join_left` → 汇总行 → `remaining!=0 || orphans!=0` 则 `exit 1`。
- **不留清单文件**（§11 Q3）：全部信息只打 stdout。

### 5.3 门禁侧入口（不新增 public 命令）

| 脚本 | 调用方式 | 可见产物 |
|---|---|---|
| `scripts/gate-order-verify.sh` | `make gate-order-verify` | 末行 `gate-order-verify: 19/19` |
| `scripts/platform-demo-reset.sh` | `make platform-demo-reset` | `platform-demo-reset: 76/76 … 5/5 段` |

## 6. 门禁接线（精确改动点）

### 6.1 `scripts/gate-order-verify.sh`（268 行 → 约 320 行）

1. **头注释**：第 7 行 `75/75` → `76/76`；第 14 行 `18 条 = 1 栈前置 + 5 静态契约 + 9 序列步骤 + 3 收尾基线` → `19 条 = 1 + 5 + 9 + 4`，并加一行 r37 说明（第 19 条为负向自检）。
2. **source**：`lib.sh`、`lib/gate-fixture.sh` 之后加 `scripts/lib/temp-accounts.sh`。
3. **起跑自愈**（`gate_fixture_begin` 之后、第 1 段之前）：`temp_accounts_db_ready` → `temp_accounts_guard` → `temp_purge_disabled`，打印 `r37 自愈：purged=N`；任一步失败 `exit 1`（**不得**降级 WARN，静态第 2 条会抓）。
4. **静态契约第 3 条**：`^TOTAL_CHECKS=75` → `^TOTAL_CHECKS=76`（`platform-demo-reset.sh` 的新口径）。
5. **静态契约第 5 条**（不增条数）：追加断言——`scripts/lib/temp-accounts.sh` 存在；`TEMP_ACCOUNT_PATTERNS` 含 4 个前缀字样；`TEMP_PROTECTED_USERS` 含 14 名；`temp_purge_disabled` 的 `WHERE` 含 `status = 'DISABLED'`；且 `gate-order-verify.sh`/`platform-demo-reset.sh` 内 `^(s3|s5|s6|s7|s8)`、`t6p` 字面量 `grep -c` = 0。
6. **`run_reset`**：`platform-demo-reset: N/M` 的下限 `-ge 75` → `-ge 76`。
7. **第 18 条（收尾基线 #1）**：三个数各 `hit` 一条失败分支：

```bash
u_left="$(temp_users_left)"     || u_left="ERR"   # 任意状态、全库
d_left="$(temp_disabled_left)"  || d_left="ERR"   # 停用、全库
r_left="$(temp_roles_left)"     || r_left="ERR"   # t1、^(S5|S7|S8)
# 三者皆 0 → hit；否则 miss "白名单用户 ${u_left}（期望 0）/ 停用 ${d_left}（期望 0）/ 角色 ${r_left}（期望 0）" \
#   "残余示例：$(temp_disabled_usernames | head -5 | tr '\n' ' ')…；修复：bash scripts/legacy-probe-purge.sh"
```

8. **第 19 条（新增负向自检）**，四步 + 兜底：

```bash
r37_tag="$(date +%m%d%H%M%S)$$"; r37_probe="probe9${r37_tag}"
r37_probe_cleanup() {   # 幂等：先 join 行再主表；probe 名在 TEMP_ACCOUNT_REGEX 内、DISABLED
  db_q "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id AND u.tenant_id=ur.tenant_id
         WHERE u.username='${r37_probe}'" >/dev/null 2>&1 || true
  db_q "DELETE ud FROM sys_user_domain ud JOIN sys_user u ON u.id=ud.user_id AND u.tenant_id=ud.tenant_id
         WHERE u.username='${r37_probe}'" >/dev/null 2>&1 || true
  db_q "DELETE FROM sys_user WHERE username='${r37_probe}'" >/dev/null 2>&1 || true
}
trap 'r37_probe_cleanup; exit 130' INT
trap 'r37_probe_cleanup; exit 143' TERM
```
   - ① 先断言 `temp_disabled_left` = 0（承接第 18 条）；
   - ② 插入 `probe9<纯数字>`（`tenant_id` 用 `TID2`、`status='DISABLED'`、`name`/`password_hash` 给非空值、`created_by`/`updated_by` 置 NULL）；
   - ③ `temp_disabled_left` 必须 ≥1（为 0 则 `miss` 并打印实际值——这是「正则坏了也全绿」的探针）；
   - ④ `r37_probe_cleanup`；条末再断言 `temp_disabled_left` = 0。
   - 中断兜底：`INT`/`TERM` 显式清理；`EXIT` 路径由 `gate_fixture` 的 teardown（→ `platform-demo-reset` → 新清理）兜底，无需再注册 EXIT trap（重复注册会覆盖夹具的 EXIT trap，破坏静态第 1 条）。
9. **末尾**：`TOTAL_N == 18` → `== 19`；`18/18` 文案 → `19/19`；收尾 banner「4) 收尾基线（3）」→「（4）」。

### 6.2 `scripts/platform-demo-reset.sh`（745 行）

1. **头注释**加一条 r37 说明；`TOTAL_CHECKS=75`（第 40 行）→ `76`，注释链补 `75→76`。
2. **source**：第 36 行 `lib.sh` 之后再 source `scripts/lib/temp-accounts.sh`。
3. **第 76 条**插在 section 5 **最后一条既有断言之后**（即 `CUSTOM_ROLES_DB` 自定义角色 0 之后）、结果块（约 733 行）之前：

```bash
# r37 第 76 条：Java 停用步骤只改 status（DemoFixture.DISABLE_GATE_TEMP_USERS），行还在；
# shell 侧在这里把「白名单前缀 + 已停用」的残留连同 join 行真正删掉，并复核 14 名种子仍 ACTIVE。
# 必须放在最后一次 reset_once 之后：早删等于没删。
PURGED_R37="$(temp_purge_disabled)" || PURGED_R37="ERR"
D_R37="$(temp_disabled_left)" || D_R37="ERR"
P_R37="$(temp_protected_active_count)" || P_R37="ERR"
[[ "${D_R37}" == "0" && "${P_R37}" == "14" ]] \
  && pass "r37 停用账号清理：DISABLED ${D_R37} / 种子 ACTIVE ${P_R37}（purged=${PURGED_R37}）" \
  || fail "r37 停用账号：DISABLED ${D_R37}（期望 0）/ 种子 ACTIVE ${P_R37}（期望 14）" \
          "先 bash scripts/legacy-probe-purge.sh 看首个报错；种子缺失=先 make platform-seed"
```
4. **文案**：结果块「断言数漂移…75/75 口径失效」「回到 75/75」及 `ok "platform-demo-reset: N/N"` 相关句一律 `75` → `76`（只改数，不改结构）。
5. 既有 75 条断言**一行不动**（AC-7）。

### 6.3 `scripts/lib/gate-fixture.sh`

只改第 10 行注释里的「75 条」→「76 条」（不新增行、不动 trap 与 reset 逻辑）。

## 7. 文档与口径同步

| 文件 | 改法 |
|---|---|
| `docs/teamflow/memory.md:43` | 「门禁临时主体幂等（r36…）」同一主题行改写为 **r36–r37**：白名单 4 前缀（`s[3-8]`/`t6p`/`t6probe`/`probe`）+ 14 名保护 + 只删 DISABLED + gate-order **19** 条 + demo-reset **76** + 首清 4 条；不追加变更日志段 |
| `docs/teamflow/memory.md:41` | 同主题内的现行计数：`r36 起 **18** 条` → `r36 **18** 条、r37 起 **19** 条`；`3 收尾` → `4 收尾（r37 起含负向自检）` |
| `docs/teamflow/memory.md:42` | r35 主题里的现行下限 `demo-reset 75` → `demo-reset 76（r37 起）`；历史事实「条数仍 **15**」保留不动（用粗体写，朴素 `grep "15 条"` 不命中） |
| `Makefile:106` | `（15 条，独占演示栈）` → `（19 条，独占演示栈）`；**仅此一处 hunk** |
| `scripts/release-flow-verify.sh:1520` | 失败文案「先 make platform-demo-reset 全绿（75/75）」→「（76/76）」；纯字符串，不动断言与 8 次循环 |

## 8. 前端组件与页面拆分

**N/A，本轮零前端改动**：需求全部落在门禁脚本与文档，`web/` 与 `platform-service` 的 API 均不变（不新增删除接口、不加页面、不加权限点）。因此没有组件拆分、没有路由、没有 i18n 词条。若未来要在平台页面上暴露「残留账号」视图，那是一次独立需求（需要 REST + 权限 + 审计），不在 r37 范围。

## 9. 状态管理

**N/A**：无前端状态。脚本侧「状态」只有三类，且都不落盘——① 常量（白名单/保护名单，编译期真源在 `temp-accounts.sh`）；② 运行时计数（每次都现查 DB，不缓存）；③ 第 19 条插入的探针行（用 `probe9<时间戳+pid>` 命名保证不撞名，`trap` + 夹具 teardown 双路清理）。没有清单文件、没有 `.state` 目录（§11 Q3）。

## 10. 关键实现点与边界

1. **空串 ≠ 0**：`db_q` 把 mysql stderr 丢掉，DB 挂掉时输出空串且 `-N -B` 不报错。所有计数一律经 `_temp_count` 的正则校验，`temp_accounts_db_ready` 先跑 `db_up`。入口脚本 DB 不可连的验收方式：`PLATFORM_DB_PORT=1 bash scripts/legacy-probe-purge.sh` 必须 `exit 1`。
2. **`set -e` 与命令替换**：`lib.sh` 带 `set -euo pipefail`，`x="$(f)"` 中 `f` 失败会直接终止脚本。故一律 `x="$(f)" || x="ERR"` / `if ! x="$(f)"; then`，禁止裸赋值后接 `[[ ... ]]`。
3. **正则拼接的 `$`**：`TEMP_ACCOUNT_REGEX` 用 `IFS='|'; printf '%s' "${TEMP_ACCOUNT_PATTERNS[*]}"` 拼装，结尾 `[a-z0-9]*\$`（双引号内转义 `$`，否则会被当变量展开成空）。
4. **L2 静态规则**：脚本里凡 `${VAR}` 后面跟中文标点都会被 `set -u` 吞掉变量名，本轮新脚本/新文案一律 `${VAR}` 花括号形式，`make lint` 必须仍 22/22。
5. **`trap` 只有一个 EXIT**：`gate-fixture` 已注册 EXIT/INT/TERM；第 19 条只能追加/复用 `INT`/`TERM`（覆盖为「先清探针再 exit 130/143」），**不得**再注册 EXIT，否则夹具 teardown 与残留清理互相顶掉。
6. **时序**：`demo-reset` 的清理必须在该脚本**最后一次** `reset_once` 之后（否则后面的 reset 又造出停用行）；`gate-order` 的自愈必须早于任何段（否则第 18 条必然红在别人的残渣上）。
7. **并发**：`gate-order`/`demo-reset` 都是「独占演示栈」门禁（在 Makefile 注释与文档里已声明），本轮不引入锁；`temp_purge_disabled` 的删除以 `status='DISABLED'` 为界，ACTIVE 临时号（`probe9<数字>`、正在跑的门禁）不会被误删——这既是 AC-4，也是并发下的安全边界。
8. **`probe9` 的 `name`/`password_hash` 非空**：`sys_user` 两列 `NOT NULL`，不带值插入会 `ERROR 1364`，被 `_temp_count` 的失败路径接住而报「计数不成立」——排查成本高，实现时务必给值。
9. **已知偏差（唯一允许的残留，QA 不得判红）**：`Makefile:95` 的 `platform-demo-reset` 帮助文案仍写「75 条断言」，因为 AC-9 只允许改 `Makefile:106` 一行（`git diff -U0 <merge-base>..HEAD -- Makefile` 必须只有一处 hunk）。口径不一由此产生，**由本次之后的、允许改 Makefile 的需求修复**。复核命令：

```bash
grep -rn '18 条\|18/18\|75 条\|75/75\|15 条' scripts/ Makefile docs/teamflow/memory.md docs/teamflow/architecture/ \
  | grep -v 'Makefile:95:'   # 必须为空（Makefile:95 是已声明的已知偏差）
```

10. **两笔历史债，只登记不动手**（超出 AC-9 改动面，也超出 PRD 范围）：
    - `sys_user_domain` 全库孤儿 413 条（F3）；
    - `t2-*` 历史租户 75 个 + 每租户 1 条 `admin`（F4）——`rbac_purge_users` 只删用户不删租户，长出来的。
    两者都写进本文 §12 风险，建议 QA 报告登记为 P3；`memory.md` 的「已知待办」行本轮不动（AC-10 只许改同主题行）。

## 11. 测试策略

**分层，先静态后实跑；所有实跑都要求「首跑有数、二跑为 0」的幂等证据。**

### L0 静态（不启服务，秒级）

```bash
bash -n scripts/lib/temp-accounts.sh scripts/legacy-probe-purge.sh \
        scripts/gate-order-verify.sh scripts/platform-demo-reset.sh
bash scripts/tests/lint.sh                                   # 仍须 22/22
grep -c 't6p\|^(s3|s5|s6|s7|s8)' scripts/gate-order-verify.sh scripts/platform-demo-reset.sh   # 期望全 0
grep -c 'TEMP_ACCOUNT_REGEX=' -r scripts/ | grep -v temp-accounts.sh                            # 期望 0（单点）
grep -n '18 条\|18/18\|75 条\|75/75\|15 条' scripts/ Makefile docs/teamflow/memory.md          # 仅 Makefile:95
git diff -U0 "$(git merge-base main HEAD)"..HEAD -- Makefile | grep -c '^@@'                    # 期望 1
```

### L1 清理原语（真栈，`make platform-up` 后）

| 用例 | 期望 |
|---|---|
| `bash scripts/legacy-probe-purge.sh`（首跑） | `purged=4 remaining=0 orphans_role=0`，rc=0 |
| 同一条命令（二跑） | `purged=0 remaining=0`，rc=0（幂等） |
| `sys_user` 中 id 72/76/77/452 | 0 行；三张表对 4 个 id 的 join 行 0 |
| `TEMP_PROTECT_EXTRA=probe123456 bash scripts/legacy-probe-purge.sh` | rc≠0，且 `temp_disabled_left` 与运行前相等（零删除） |
| `PLATFORM_DB_PORT=1 bash scripts/legacy-probe-purge.sh` | rc=1，stderr 指到 `make platform-up` / `PLATFORM_DB_*`（不是空串当 0） |
| 插 ACTIVE `probe9<数字>` 后跑清理（AC-4） | 该行仍在（只删 DISABLED），随后自行删除 |

### L2 门禁（真栈，逐条跑两遍）

| 命令 | 期望 |
|---|---|
| `make platform-demo-reset` ×2 | 各 `76/76`，5/5 段；第 76 条文案含 `purged=` |
| `make gate-order-verify` ×2 | 各 `19/19`；第 19 条四步全绿；中断（Ctrl-C）后 `probe9*` 不残留 |
| `make rbac-verify` ×2 | 各 `113/113`（r36 口径不降） |
| `make release-flow-verify` | `44/44` |
| `make platform-verify` | ≥194 |
| `make platform-test-negative` | ≥130 |
| `make access-verify` | 206/206 |
| `make lint` | 22/22 |

### L3 反假绿（QA 必做，一次即可）

1. 把 `TEMP_ACCOUNT_REGEX` 临时改成不命中任何行的值 → `gate-order-verify` 第 18 条仍绿但**第 19 条必红**（证明第 19 条真的在测正则，而不是自证）；改回。
2. 在 `sys_user` 手插一条 `DISABLED` 的 `s5u999999` → 第 18 条红且打印残留用户名与修复命令；`legacy-probe-purge.sh` 清掉后复绿。

### L4 边界与提交卫生

`git diff --name-status "$(git merge-base main HEAD)"..HEAD` 仅 `scripts/`、`docs/`、`Makefile`；无 `pom.xml`、无 `web/package.json`、无 `pnpm-lock`、无 `db/migration/`；`.gitignore` 覆盖 `target/node_modules/dist/.run/logs/.env*/.nacos`。

## 12. 任务拆分（按文件边界，可并行；T1 先行定契约）

契约先行的原因：T2/T3 都要 `source` 新库并调用 §5.1 的函数名，函数名一改就是 P1。T1 落地后 T2/T3 才能实跑，但它们的**代码**可以并行写（`files` 互不相交，`reads` 不阻塞并行）。

| 任务 | files（独占写） | reads | 交付判据 |
|---|---|---|---|
| **T1 原语与入口** | `scripts/lib/temp-accounts.sh`、`scripts/legacy-probe-purge.sh` | `scripts/lib.sh`、`scripts/tests/rbac/_lib.sh`、`req-service/src/main/resources/db/migration/V1__platform_schema.sql` | §5.1 常量与 9 个函数逐字齐全；首跑 `purged=4`、二跑 0；守卫注入负向 rc≠0；DB 断连 rc=1；`bash -n` 通过 |
| **T2 夹具接线** | `scripts/platform-demo-reset.sh`、`scripts/lib/gate-fixture.sh` | T1 产出、`scripts/lib.sh` | `TOTAL_CHECKS=76`；第 76 条在最后一次 `reset_once` 之后；连跑两次 76/76；既有 75 条未减；`75` 文案同步为 `76` |
| **T3 门禁顺序** | `scripts/gate-order-verify.sh` | T1 产出、`scripts/lib/gate-fixture.sh`、`scripts/lib.sh` | 19 条；#18 三数为 0；#19 四步 + INT/TERM trap + 末尾再断言 0；静态第 3 条 `TOTAL_CHECKS=76`、第 5 条扩展；`run_reset -ge 76`；末行 19/19；连跑两次 |
| **T4 口径同步** | `Makefile`（仅第 106 行）、`docs/teamflow/memory.md`、`scripts/release-flow-verify.sh`（仅 1520 字符串） | `docs/teamflow/memory.md` 现行三行 | `git diff -U0 -- Makefile` 恰一处 hunk（15→19）；memory 同主题行改为 r36–r37 且现行计数为 19/76；`rfv` 只改字符串 |
| **T5 交付自检** | 无（发现问题就地修，改到上面任一文件即回到对应任务） | 全部 | §11 L0–L4 全绿；**不写** `QA-REPORT.md`/`ACCEPTANCE.md`，不跑跨浏览器/长稳压测（那是 QA 阶段） |

**git 约束（必须写进每个任务的 spec，不能丢）**

- 分支：`feat/legacy-probe-user-purge`（已检出；main = `81967c4`）。**不得**新建分支、不得切到 main 提交。
- 提交信息必须含 `runId=tf-mwookg49-88sn5b` 与任务夹路径 `docs/teamflow/20261120-r37-legacy-probe-user-purge/`。
- 提交前 `git status` 复核：只应出现本轮 6 个改动文件 + 1 个新任务夹；`docs/teamflow/20261120-r36-rbac-s7-idempotent/` 等历史夹**不得修改**。
- 提交后复跑 `make release-flow-verify`（第 32 条 `BASE..HEAD` 区间纯判据必须在提交后仍绿）。

## 13. AC → 交付映射

| AC | 落点 |
|---|---|
| AC-1 | `scripts/lib/temp-accounts.sh` §5.1 常量；gate-order 静态第 5 条的反例断言 |
| AC-2 | `scripts/legacy-probe-purge.sh`（首跑 4 / 二跑 0 / 孤儿 0 / DB 断连 rc=1） |
| AC-3 | `temp_accounts_guard` + DELETE 的 `NOT IN` 兜底 |
| AC-4 | 三条 DELETE 的 `status='DISABLED'`；L1 用例「ACTIVE probe9 不动」 |
| AC-5 | gate-order 头注释/静态第 3 条/`run_reset`/第 18 条/末尾 19/19 |
| AC-6 | gate-order 第 19 条（§6.1-8） |
| AC-7 | demo-reset 第 76 条（§6.2-3）+ `gate-fixture.sh:10` 注释 |
| AC-8 | §11 L2 八条命令 |
| AC-9 | §12 git 约束 + §10-9 的 diff/残留复核 |
| AC-10 | §7 三处 memory 行 + `Makefile:106` + `rfv:1520` |

## 14. 风险

| 风险 | 等级 | 处置 |
|---|---|---|
| 白名单口径被复制到别处（下一个人再手写一份正则） | 中 | `TEMP_ACCOUNT_REGEX` 单点生成 + gate-order 静态断言反例 0 命中；注释写明「新增探针只加数组元素」 |
| 全库口径（无租户过滤）误删他租户同名号 | 低 | 白名单前缀是探针专用命名；只删 DISABLED；`NOT IN` 兜底 14 名；ACTIVE 一律不动 |
| `t2-*` 75 个历史租户 + 各 1 条 `admin`（F4 的 89） | P3 已知债 | 不在本轮范围；租户清理需要「租户删除 API + 级联」，属独立需求 |
| `sys_user_domain` 413 条历史孤儿（F3） | P3 已知债 | 明确不做全局判据（否则永红）；本轮只证本次删除集合 0 孤儿 |
| `Makefile:95` 仍写「75 条断言」 | 已知偏差 | §10-9；QA 不得判红；残留复核命令排除该行 |
| Makefile 帮助行改动影响 `make help` 输出 | 低 | 已验证无脚本断言 `15 条` 文案；`make help` 由静态第 4 条只查目标可发现性 |
| 第 19 条插入行残留（中断/异常退出） | 中 | `INT`/`TERM` 显式清理 + 夹具 teardown 的 demo-reset 兜底 + 条末再断言 0；`probe9<时间戳+pid>` 不与历史撞名 |
