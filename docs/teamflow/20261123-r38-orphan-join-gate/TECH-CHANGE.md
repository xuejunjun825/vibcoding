# r38 技术变更单：孤儿行判定扩到 sys_user_domain 并清理 413 条历史孤儿

- 任务夹：`docs/teamflow/20261123-r38-orphan-join-gate/`（本需求全部产物；不改写任何历史 PRD 的功能 AC）
- 分支：`feat/orphan-domain-cleanup`（基于 main `8c39a3a`，r37 已合回）
- 类型：tech-driven rework（无新增用户可见功能 AC；先例 r37）
- 基线依赖：`docs/teamflow/20261120-r37-legacy-probe-user-purge/`（其 AC-2 原文「sys_user_role / sys_user_domain 均无孤儿」只落地了 role 一半，见该夹 TECHNICAL §10-10 的已知债 F3）

## 1. 背景与目标

`temp_orphan_join_left()` 只数 `sys_user_role`（当前 0），`sys_user_domain` 的 **413 条**历史孤儿（59 个已不存在的 user_id）因此长期隐形；本单把判据扩到两表、一次性清账，并让门禁永久兜住同类残留。

## 2. 影响范围

| 文件 | 改动 |
|---|---|
| `scripts/lib/temp-accounts.sh` | 孤儿判定扩两表；`temp_orphan_join_left()` 输出两个计数；新增两个单表计数器 |
| `scripts/orphan-join-purge.sh` | **新增**：dry-run / `--apply` 一次性清理孤儿 join 行 |
| `scripts/legacy-probe-purge.sh` | 证据行与 exit 条件同步 `orphans_domain` |
| `scripts/gate-order-verify.sh` | 收尾 19→20 条：第 20 条 = role/domain 孤儿 0 + domain 负向自检 |
| `Makefile` | 仅第 106 行帮助文案 19→20 条 |
| `docs/teamflow/memory.md` | 同主题两行（门禁顺序无关、临时主体幂等）同步 |

不碰：Java、`pom.xml`、迁移（V15+）、种子数据、`web/`、删除 API。

## 3. 技术方案

### 3.1 判定口径：两表同形，删除集 ≡ 断言集

- 新增 `temp_orphan_role_left()` / `temp_orphan_domain_left()`：各返回一个整数，走 `_temp_count` 的 `^[0-9]+$` 校验，查询失败 return 1（**绝不**把 DB 不可连当 0）。
- `temp_orphan_join_left()` 改为只打一行：`orphans_role=<n> orphans_domain=<n>`；任一分量失败即 return 1，不出半行（半行会被下游当合法值）。
- 判定谓词两表逐字同形：`LEFT JOIN sys_user u ON u.id = x.user_id AND u.tenant_id = x.tenant_id WHERE u.id IS NULL`（同租户无主）。
- 为什么用同租户口径：与 r37 的 role 谓词逐字一致，且比「全库不存在」更强——跨租户错配行也会被判红；对现有 413 条两者等价（59 个 id 全库无行）。
- `temp_purge_disabled()` 的三删顺序（role → domain → user）与「本次删除 id 集合 join 行 0」复核**不动**：删号路径本来就不产生孤儿，413 条是脚本外删号的历史债。

### 3.2 新脚本 `scripts/orphan-join-purge.sh`

- 用法：`bash scripts/orphan-join-purge.sh`（默认 dry-run，只读）/ `--apply`（实删）；未知参数 exit 2。
- dry-run 输出：`role=<n> domain=<n> distinct_role_users=<n> distinct_domain_users=<n>`，并提示下一步；退出码 0（非 0 不是失败）。
- `--apply`：用与判定**同一条**谓词 `DELETE x FROM sys_user_domain x LEFT JOIN ... WHERE u.id IS NULL`（role 同）逐表删；打印 `deleted_domain=<n>`，删后用同一计数器复核，两数必须为 0，否则 exit 1。
- 只删 join 行，不删 `sys_user` / `sys_role`；因此**不调** `temp_accounts_guard`（14 名保护名单是账号删除的守卫，与 join 行无关），只要求 `temp_accounts_db_ready`。
- DB 不可连 / 计数非数字 → exit 1；幂等：第二遍 `role=0 domain=0`。

### 3.3 门禁收尾 19→20

- 结构：1 栈前置 + 5 静态契约 + 9 序列步骤 + **5 收尾基线** = 20；汇总 `TOTAL_N == 20`，文案 20/20。
- 第 20 条：`role=0 && domain=0` **且**内置负向自检——插一行 `sys_user_domain(tenant_id=TID2, user_id=9<tag>, domain_id=<夹具租户任一域>)` → domain 计数必须 ≥1 → 删除 → 回起跳值；零断言与负向自检合并且在一条，不拆第 21 条。
- 探针 user_id 取 `9` 前缀（实现为 `9${r37_tag}`，远高于真实自增 id），不依赖真实账号；`domain_id` 取 `biz_domain` 中门禁夹具租户（`GATE_FIXTURE_TENANT_CODE`，默认 `t1`，经 `sys_tenant` 解析为 TID2）的首行，见 `req-service/src/main/resources/db/migration/V1__platform_schema.sql:70`。租户 id 为空或查询失败一律退化为 `1`，**不跳过自检**（取不到域不是跳过负向自检的理由）。
- 口径纠偏：域表是 `biz_domain`，**不存在** `sys_domain`（早期草案笔误，勿照抄，否则 QA 会拿到空结果当 0）；`sys_user_domain` 无外键（同文件 :127-133），故 `domain_id=1` 的退化值不会触发约束。
- 清理并入现有 `r37_probe_cleanup`（同时删 user 探针与 `user_id=<r38_uid>` 的 domain 行）；**不新增 trap**（第二个 trap 会覆盖第一个，导致用户探针漏删）；变量用 `${r38_uid:-}` 防 `set -u`。
- 失败信息指向 `bash scripts/orphan-join-purge.sh --apply`。

### 3.4 legacy-probe-purge 证据行

- 改为 `purged=<n> remaining=<n> users_left=<n> ${orphans}`（即末尾展开为 `orphans_role=<n> orphans_domain=<n>`）。
- exit 条件改为 `remaining != 0 || role != 0 || domain != 0`；失败提示追加 orphan purge 命令。

### 3.5 文档与 Makefile

- `Makefile:106` 仅 `（19 条，独占演示栈）` → `（20 条，独占演示栈）`；其余行不动。
- `memory.md` 同主题两行：门禁顺序无关行补「r38 起 20 条」，临时主体幂等行追加 r38 口径（单行替换，幂等）。

## 4. 行为与兼容性影响

- **用户可见行为：无**。无 API / UI / Java / 迁移 / 种子变化；`platform-demo-reset` 仍 76/76。
- 运维可见：新增一条命令；purge 证据行多一个计数；gate-order 多 1 条（20）。
- 历史库首跑 gate-order 会红（domain=413）——这是判据生效，不是回归；修复命令固定为 `orphan-join-purge.sh --apply` 后复跑。
- `legacy-probe-purge` 退出码收紧：domain≠0 也 exit 1，故历史库顺序为「先 orphan purge，再 legacy purge」。
- 数据影响：413 行按定义无主（59 个 user_id 全库不存在），不涉及任何真实账号；`sys_user_domain` 无外键，删除不触发级联。

## 5. 验收与回归计划

### 5.1 QA 真栈顺序

1. `make platform-up && make platform-seed`。
2. 记录基线：`bash scripts/orphan-join-purge.sh` → 期望 `role=0 domain=413 distinct_domain_users=59`（已干净库则先插一条负向探针再验）。
3. `bash scripts/orphan-join-purge.sh --apply` → `deleted_domain=413`、`post role=0 domain=0`；再跑 dry-run 全 0（幂等）。
4. `bash scripts/legacy-probe-purge.sh` → 证据行两数均 0，二跑 `purged=0`。
5. `bash scripts/platform-demo-reset.sh` → 76/76；之后 orphan dry-run 仍 0/0。
6. `bash scripts/gate-order-verify.sh` 连跑两次 → 每次 20/20（A/B 两序列；第 20 条每次自带负向自检）。
7. 负向复核（函数级一次）：手工插 domain 孤儿 → `temp_orphan_join_left` 输出 `orphans_domain=1` → 删除回 0；若先预置孤儿再跑 gate，第 20 条必须红。

### 5.2 回归下限（只增不减）

`rbac 113/113 ×2`、`rfv 44/44`、`platform-verify 194/194`、`platform-test-negative 130/130`、`access 206/206`、`lint 22/22`、`demo-reset 76/76`、`gate-order 20/20 ×2`。无 Java 改动，`mvn` 仅作可选烟测（`mvn -o -q -DskipTests compile`）。

### 5.3 假绿防线

- 计数统一走 `_temp_count` 的 `^[0-9]+$`：DB 不可连不会变成 0。
- 第 20 条内置负向自检：查询写错 / 被删掉 / 谓词退化，本条必红。
- 判定与删除共用同一条谓词（脚本内只生成一次），杜绝「门禁查的」与「清理删的」各自演化。
- `--apply` 必须删后复核 0 才算成功，不拿「删了 N 条」当结论。

### 5.4 diff 卫生

`git diff --stat` 仅 `scripts/`、`docs/` 与 `Makefile`；`git diff -U0 $(git merge-base main HEAD)..HEAD -- Makefile` 恰 1 个 hunk；`git diff -- '*.java' pom.xml '*/db/migration/*'` 为空；`target/`、`logs/` 不入库；提交信息含 `runId=tf-mwsm0khj-297ost` 与本任务夹路径。

## 6. 风险与回滚

| 风险 | 处置 |
|---|---|
| 413 行删除不可逆 | 默认 dry-run 先核对 59 个 distinct id；需要审计留档则执行前自行 `SELECT` 导出（本轮不写清单文件，沿用 r37 边界） |
| 跨租户错配行被同租户口径判为孤儿并删除 | dry-run 的 distinct 列表可先核对；有疑问停手，不跑 `--apply` |
| 历史库首跑 gate 红被误判为回归 | 门禁失败信息固定指向 `orphan-join-purge.sh --apply`；先清账再复跑 |
| 回滚 | 纯 shell 改动，`git revert` 本轮提交即回旧判据；已删行不恢复（按定义无主、无引用方） |

## 7. 假设与待澄清

1. **条数口径**：选 19→20（第 20 条内含零断言 + 负向自检）。若要求把零断言与负向自检拆成两条，则 21 条，`Makefile:106` 与 memory 同批改一个数。
2. **孤儿口径**：选「同租户 join 不中」（与 r37 role 同形、更强）。若坚持字面「user_id 全库不存在」，去掉谓词里 `AND u.tenant_id = x.tenant_id` 一处即可；代价是跨租户错配行不再判红。
3. **legacy-probe-purge 退出码**：domain≠0 纳入 exit 条件。若希望它只管账号、domain 只由 gate-order 管，把该条件降为仅打印（不推荐：会留下只打印不拦的假绿口子）。
4. **CLI 形状**：默认即 dry-run，无 `--dry-run` 别名。若 CI 习惯显式开关，加一行参数归一即可，不影响判据。
5. **413 条留档**：本轮按「无主垃圾」直接删、不导出。若需审计留档，再加 `--dump <path>`（会新增产物文件，需另批范围）。
