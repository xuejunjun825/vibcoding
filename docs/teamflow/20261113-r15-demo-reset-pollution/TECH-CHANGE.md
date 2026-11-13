# r15 技术变更单 · 演示数据污染修复

> 类型：技术驱动返工（无新增功能；唯一用户可见变化是「重置后数据真的干净了」）
> 基线依赖：`docs/teamflow/20261112-r14-analytics-header-zh/`
> 取代：`docs/teamflow/20261110-r7-role-page-access-fix/QA-REPORT.md#D-02`（执行器/api_client「保留不清」口径）与同文件 `#D-04` 的待办；不修改任何历史任务夹
> 范围外：业务功能、UI 样式、中英文标签、筛选值中文化（另一单）

## 1. 背景与目标（一句）

`make platform-demo-reset` 只清 `DemoFixture.DELETE_ORDER` 里的 42 张表，V10 的报表/卡配置、V9 的执行器、V1 的 api_client 都不在其中，且脚本没有表级计数断言，于是漏删静默通过——目标是一次重置后四个页面只剩演示数据，漏删必须让 `make platform-demo-reset` 变红。

现场复核（read-only，2026-11-13，tenant=t1）：`report_definition` 251（ACTIVE 162/DELETED 89）、`portal_card_config` 3（1 条个人 pin 占 `report:223..226`，即被占位的四张卡；2 条角色级显隐）、`test_executor` 15（2 种子 + 13 条 `S7-*`）、`api_client` 60（全为 `s7-ci-*/verify-ci-*/neg-ci-*`，无种子）、`requirement` 250、`release` 219、`test_plan` 140、`notification` 901、`wiki_page` 51。数字比需求书（128 报表/241 需求/7 执行器/11 客户端）更大，证明污染随门禁累积，方案口径不变。

## 2. 覆盖审计：V1–V11 共 71 张表 ↔ DELETE_ORDER 50 项

证据：`req-service/src/main/java/com/example/req/seed/DemoFixture.java` 的 `DELETE_ORDER`（50 项，末尾 `` `release` ``、`notification`、`portal_card_config`、`report_definition`、`test_executor`、`api_client`、`test_env`、`wf_definition`、`test_module`）；迁移仅 `req-service/src/main/resources/db/migration/`（V1 39 + V5 13 + V8 6 + V9 11 + V10 2 = 71；V2/V3/V4/V6/V7/V11 无建表）。

| 分类 | 数量 | 明细（文件:行） |
|---|---|---|
| 已覆盖 | 42 | 评审/报告/门禁/计划轮次/用例/缺陷/审批/需求/版本/通知，见上 |
| **漏删·本轮纳入** | 4 | `report_definition` V10:25；`portal_card_config` V10:55；`api_client` V1:668；`test_executor` V9:216（有 2 行 V9 种子，需保留过滤） |
| **运行时残留·本轮纳入**（r15-fix，D-01/D-02） | 4 | `test_strategy` V5:27（**派生**：V5:448 只按当时已有 release 回填一次，release 清空后必成孤儿 → 全清）；`test_env` V5:127、`wf_definition` V1:277、`test_module` V1:513（**门禁 RUN_TAG 探针行**用 `purgeRunTagResidue` 清，种子/人工行保留）|
| 保留·定义/种子 | 19 | sys_tenant:21 sys_org:36 sys_vendor:55 biz_domain:70 sys_user:86 sys_role:105 sys_user_role:119 sys_user_domain:127 sys_dict:135 code_sequence:150 metric_definition:648 audit_log:704（V1）；integration_config V8:12 admission_item_config V8:153；wiki_space V9:35 wiki_page V9:58 wiki_page_version V9:83 stage_definition V9:122 branch_policy V9:140 |
| 未纳入·待办 | 2 | `wiki_comment` V9:97、`wiki_page_link` V9:109（纯实例无种子，但与保留的 3 张种子 wiki 页同域，删链接只留半套内容；探针 wiki 页另案） |

> **修正 r15 初版口径（QA D-01/D-02）**：原表把 `test_strategy`/`test_env`/`wf_definition`/`test_module` 归为「保留·定义/种子」并给迁移行号，但 V1/V5 **只有 CREATE、没有 INSERT**——这四张表的真种子在 Java 侧 `req-service/src/main/java/com/example/req/seed/SeedService.java:366`（test_env）、`:735`（wf_definition，4 条默认审批流）、`:309-315`（test_module，3 节模块树）。因此正确口径是「种子行保留 + 门禁 RUN_TAG 行删除」，而不是整表保留；`test_strategy` 更是纯派生数据，必须随 release 清空。

点名但不存在的表：`export_task`、`webhook_*`、`worklog`、`comment` —— V1–V11 无这些 `CREATE TABLE`；唯一 comment 表是 `mr_comment` V9:179（已在序列）。r13/r14 无新增表（V11 仅 severity 数据归一）。注意 `report_definition`（报表中心）与 V5 的 `test_report`/`report_signoff`（测试报告会签）**不是一回事**，后者已有覆盖。

## 3. 影响范围

| 文件 | 改动 |
|---|---|
| `req-service/.../seed/DemoFixture.java` | `DELETE_ORDER` 由 `List<String>` 改为 `List<TableReset>`；新增 4 条（executor 带保留谓词）；注释改写 |
| `req-service/.../seed/DemoResetService.java` | `deleteBusinessData` 支持 keepFilter；`counts()` +6 键；`violations()` 通知改精确值 + 6 条断言；删 `NOTIFICATION_LIMIT` |
| `scripts/platform-demo-reset.sh` | 删 `PRE_CLIENTS`(:60) 与 129-140 的「前后相等」断言；新增 DB/API 复核；executor `>=2` → `==2` |
| `req-service/src/test/.../DemoResetRetentionTest.java` | 保留表契约按新设计更新 |
| `docs/teamflow/memory.md` | 同主题行替换，补 r15 口径 |

## 4. 技术方案

### 4.1 删除序列仍是单一来源

```java
public record TableReset(String table, String keepFilter) {}          // keepFilter=null 表示全删
static TableReset purge(String t) { return new TableReset(t, null); }
static TableReset purgeExcept(String t, String keep) { return new TableReset(t, keep); } // keep=保留谓词
```

`deleteBusinessData`：`DELETE FROM <t> WHERE tenant_id = ?`，`keepFilter != null` 时追加 `AND NOT (<keepFilter>)`。表名与谓词都来自代码常量，无请求参数，拼串安全。

新增 4 条（追加在序列末尾，71 张表无任何 FOREIGN KEY，顺序不影响正确性）：

- `purge("report_definition")`、`purge("portal_card_config")`、`purge("api_client")`
- `purgeExcept("test_executor", "code IN ('STUB-LOCAL','LEGACY-OFF')")`

`req-service` 与 `deliver-service` 同库（迁移全在 req-service），故重置无需跨服务调用；批量删除仍只存在于 `/internal/demo-reset`（`DemoResetController` 双门：`PLATFORM_SEED_ENABLED` + `X-Internal-Token`，关闭 404），不新增任何对外删除接口。`reset()` 已 `@Transactional`，不变量失败整体回滚。

### 4.2 三层断言（服务端不变量 + 脚本 + API 复核）

`counts()` 新增 6 键（tenant 级 COUNT）：

| 键 | 期望 | 口径 |
|---|---|---|
| `reports` | 0 | `report_definition` |
| `portalCards` | 0 | `portal_card_config` 全表 |
| `portalPins` | 0 | `portal_card_config WHERE user_id <> 0`（个人 pin） |
| `executors` | 2 | `test_executor` |
| `apiClients` | 0 | `api_client` |
| `probeReports` | 0 | `report_definition` 名命中 `%T8 编辑器探针%` / `r11-ev-%` / `r13-%`（按实测把 `r11-ev-pin` 放宽为 `r11-ev`，现场 143 行） |

`violations()`：上述 6 键走 `expect(...)`；通知由 `> 5` 改为 `expect(..., NOTIFICATION_COUNT)`（=4，`DemoFixture` 第 279-284 行恰 4 条，常量早已存在却未用）；原 AC-13 口径（需求 12/用例 12/缺陷 3/版本 1/迭代 1/任务 8）与可见性、夹具痕迹断言全部保留。三层各挡一类失败：服务端 409 挡住「漏加表」，脚本挡住「服务端漏加键」，API 复核挡住「库干净但接口/缓存仍返回旧数据」。

### 4.3 `scripts/platform-demo-reset.sh`

- 删除 `PRE_CLIENTS`（:60）与 `api_client` 前后相等的断言（:138-140），改为 `apiClients == 0`。
- `KEPT_EXECUTORS >= 2` 改 `== 2`，并直查 codes 恰为 `STUB-LOCAL`/`LEGACY-OFF`。
- 从 reset 返回的 counts JSON 断言：`reports/portalCards/portalPins/apiClients/probeReports = 0`、`executors = 2`、`notifications = 4`。
- DB 直查（`db_count`）复核四张表：`report_definition=0`、`portal_card_config=0`、`api_client=0`、`test_executor=2`。
- DB 直查（r15-fix，D-01/D-02）：`test_strategy=0` 且无 `release_id` 孤儿；`wf_definition`/`test_module`/`test_env` 的 RUN_TAG 残留（`verify-%`/`neg-%`/`验证模块%`/`QA环境-%`）全为 0，且种子行仍在（流程定义 ≥4 / 模块 ≥3 / 环境 ≥1）。**注**：`test_env` 的判据是「残留 0 + 种子行在」，不是「全表 0」——种子环境由 `SeedService.java:366` 种下、重置不重放，全清会让环境台账空白（与 `test_executor` 保留 2 行同口径）。
- API 复核（r15-fix）：`GET /api/req/admin/workflows` 与 `GET /api/deliver/test-modules` 中 `^(verify-|neg-)` / `^(验证模块|neg-)` 命名残留为 0。
- API 复核（登录后）：`GET /api/deliver/reports` → `total=0`；`GET /api/deliver/portal/config` → `cards` 长度 4 且无一 `key` 以 `report:` 开头（角色模板回退）。
- 两遍重置幂等逻辑沿用（counts 逐键相等，新键自动纳入）。

### 4.4 单测契约（`DemoResetRetentionTest`）

必须在序列：`report_definition`、`portal_card_config`、`api_client`、`test_executor`、`test_strategy`、`test_env`、`wf_definition`、`test_module`；`test_executor` 必须带 `keepFilter` 且含 `STUB-LOCAL/LEGACY-OFF`，`api_client` 必须无 `keepFilter`（有意清空），`test_env`/`wf_definition`/`test_module` 的 `keepFilter` 必须是 `NOT (<GATE_RUN_TAG_RESIDUE>)`（r15-fix：keepFilter 是「保留」谓词，直传残留模式会把种子删掉、把探针留下——实测 409 残留 476 行），`test_strategy` 必须无 `keepFilter`（派生数据全清）。不得在序列：`branch_policy`、`wiki_page`、`metric_definition`、`sys_user`（定义/种子，重置不重放）。原「反向断言」（requirement/release/test_case/test_plan/defect/test_execution/branch_policy_check/quality_gate_snapshot 必须在）保留。

## 5. 行为兼容影响

- 重置后 `/reports` 为空态（原 251 张）、`/portal` 四张卡回到角色模板（原被 4 个 pin 占位）、`/admin` 开放客户端为 0（原 60 条 CI 残留）、需求池 12 条。这是修复目标本身。
- 破坏性：手工建的报表、pin、api_client 在重置时一并清除——演示栈语义即「回到演示基线」；生产不跑该接口（双门 + 关闭 404）。
- 无 API 形状变化、无迁移、无 schema 变化；不触碰非 reset 的任何写入路径。

## 6. 回归与验证计划

1. `make platform-unit`（含更新后的 retention 单测）
2. `make platform-up && make platform-seed && make platform-demo-reset`（连跑两遍验幂等）
3. `make platform-verify`、`make platform-test-negative`、`make access-verify`（≥143）、`make analytics-i18n-verify`（静态 R1–R7 + 现场 A1–A9）
4. `make lint`、`make verify`、`make test`
5. r1 四步：`make dev-up && make verify && make test-negative && make dev-down`
6. 取证（QA/验收）：重置后 `reports` total=0、`portal/config` cards=4、需求池 12，三张截图前后对比

回归下限：r13 的 20 条 AC、r14 的 12 条 AC 零回归；`platform-verify` 断言数不减；`access-verify` ≥143。失败信息必须带「哪一项 + 怎么修」，`probeReports` 失败时附实际命名样例（LIMIT 3）。

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 全删 `api_client`，未来若加种子客户端会被误删 | 断言 `apiClients=0` 会先红；届时改 `purgeExcept` 白名单，仍是单点 |
| 清掉角色级 `portal_card_config`，管理员显隐配置丢失 | 演示栈可接受，重置即重建模板默认；如需保留改为按 `role_code` 过滤 |
| `/reports` 变空影响演示观感 | 断言口径按「0 或仅演示」预留；要演示报表时在 `insertFixture` 补 2 张并把期望改 2 |
| 表序写错只留孤儿（无 FK 不报错） | 审计表 + retention 单测双保险 |
| 回滚 | 单次提交整体 revert 4 个代码/脚本文件；无 schema 与数据迁移，数据由 `platform-seed` + reset 重建 |

## 8. 假设与待澄清

- **wiki 探针页（51 行中 48 行非种子）本轮不清**：需为 `wiki_page` 设计「种子标题白名单」keepFilter，+0.5d；若要一并处理，本单扩为 5 个文件。
- **`/reports` 重置后取空态**（AC 允许「0 或仅演示」）；若要 2 张演示报表，`reports` 期望改 2 且 `insertFixture` 增写。
- **手工 `api_client` 是否保留**：按全清实现；若要保留，改为按 `app_key` 白名单 keepFilter。
- **demo-reset 是否进自动门禁**：维持 r7 约定「显式幂等、不进 `make test`」，本轮不改。
