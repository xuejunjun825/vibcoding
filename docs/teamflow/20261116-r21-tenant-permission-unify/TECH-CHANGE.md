# 技术变更单 · 20261116-r21 · 权限判定入口统一（P3）+ 两项订正

- 类型：技术驱动改造（无功能 PRD；不改任何既有任务夹的 AC 文本）
- 分支：`feat/rbac-p3-followup` ｜ 基线：`6f93922`（r20 已合回 main）
- 依赖：`docs/teamflow/20261113-r19/`、`docs/teamflow/20261114-r20-r19-rbac-blocker-fix/`
- 范围：判定入口（platform-common）+ 两服务 8 文件 11 处调用 + access 探针 1 处 + r19 文档 2 行

## 1. 背景与目标

r20 修好了三层授权的三个阻断缺陷，本单收口被列为 OUT-OF-SCOPE 的两项 P3：**把所有业务判定从编译期 `PermissionMatrix` 改走唯一入口 `PermissionService`，让工作台、用例、报表、Wiki、甘特真正随角色档位变化**；同时订正 r19 的门禁数字、放宽 a5 的 D3 判据。

## 2. 影响面

| 模块 | 文件 | 改动 |
|---|---|---|
| platform-common | `src/main/java/com/example/platform/common/tenant/TenantContext.java:106-112` | 删除 `access/can`（唯一残留派生） |
| deliver-service | `portal/PortalService.java:85,106`；`testplan/TestPlanService.java:420-421`；`review/CaseReviewService.java:382-383`；`executor/TestExecutorService.java:365`；`reports/ReportService.java:401`；`defect/DefectService.java:446` | 改注入 `PermissionService` |
| req-service | `collab/wiki/WikiSpaceService.java:82,85,132`；`collab/wiki/WikiPageService.java:342`；`collab/gantt/GanttRescheduleService.java:50` | 同上 |
| 测试 | `req-service/src/test/java/com/example/req/sys/TenantContextTest.java:48-49` | 删除两条 `can` 断言 |
| 门禁 | `scripts/tests/access/a5-capability-probe.mjs:496-512` | D3 判据放宽 |
| 文档 | r19 `PRD.md:3`、`TECHNICAL.md:6`；`docs/teamflow/memory.md` | 109→111；补 r21 一行 |

> 需求写“7 处”，实点 **11 处表达式 / 8 个文件**（Wiki 两个类；`DefectService` 是第 8 个文件、直连 `PermissionMatrix`）。以本表与 grep 清单为准，全部收口。

无 DDL、无 seed、无 `pom.xml`/`package.json` 变更。

## 3. 技术方案

1. **唯一入口**：业务侧构造注入 `com.example.platform.common.security.PermissionService`；改造后业务代码不得出现 `TenantContext.can/access` 或 `PermissionMatrix.accessOf`。不新增派生、不新增缓存。
2. **显式码→层映射**（不做运行时猜层；下列 ACTION 均为 ActionRegistry 72 码中已登记项）：

| 旧调用 | 新判定 |
|---|---|
| `PORTAL_CONFIG`（2 处） | `decide("ana.portal","portal.action.config")` |
| `plan:edit` / `plan:execute` | `allows("test.plans","test.action.plan_create")` / `…"test.action.execute"` |
| `plan:manage` | 无 ACTION → `accessOf(FUNCTION,"plan:manage")` |
| `CASE_REVIEW_DECIDE` | `decide("test.cases","test.action.case_review")` |
| `CASE_REVIEW_SUBMIT` | 无 ACTION → `accessOf(FUNCTION,…)` |
| `TEST_EXECUTOR_MANAGE` | 无 ACTION → `accessOf(FUNCTION,…)` |
| `REPORT_SHARE` | `decide("ana.reports","report.action.share")` |
| `WIKI_ADMIN` / `WIKI_EDIT` | `decide("collab.wiki","wiki.action.admin"/"wiki.action.edit")` |
| `GANTT_EDIT` | `decide("collab.gantt","gantt.action.edit")` |
| `proddefect:report` | 无 ACTION → `accessOf(FUNCTION,…)`（`DefectService` 直连改为服务调用） |

3. **三序语义**：`decide` = MENU 层 DENY ⇒ DENY；ACTION 层 DENY ⇒ DENY；否则取两者较窄。`RESTRICTED` 只收窄 DataScope，不等于拒绝。
4. **动态调用点拆平**：`CaseReviewService.requireFunction(code)` 拆成 `requireMenuAction(page, action, what)` 与 `requireFunction(code, what)` 两个显式入口，避免“按码猜层”。
5. **静态方法改实例**：`TestExecutorService.requireManage`、`DefectService.requireProdDefectPermission` 去 `static`、改注入；不用 static holder（那是第二入口）。
6. **403 口径原样保留**：`AnalyticsErrors.forbidden(code, method, path)`、`403 FORBIDDEN {function}`、`403 WIKI_FORBIDDEN`、`403 SCOPE_DENIED {function}` 的 code/message/detail 逐字不改。
7. **a5 D3 放宽**：判据从“规则文本必须含 `bodyAction(`”改为“**动作码必须来自请求体**（`bodyAction` 或等价 body 前缀）；仅当路由规则把动作码写死为端点级单码才红”。page-only 规则 + `@RequiresActionFromBody` / `RequirementActionMatrix` 视为合法；断言条数不变（access 仍 152），并加一条反例自检（喂入 fixed-action 规则仍必须红）。
8. **r19 数字**：`PRD.md:3` / `TECHNICAL.md:6` 的 109 → **111**（r20 实测），只改该行，不动 AC。

## 4. 行为兼容性（用户可见变化）

- 唯一可见变化：在「角色与权限」页把某**动作**档位改为 DENY 后，工作台配置、用例评审结论、报表共享、Wiki 空间管理、甘特改期等接口在下一次请求即按档位拒绝（此前仍按编译期矩阵放行）；改回 ALLOW 即恢复——这正是本单要修的缺陷。
- 收紧一处：**页面**档位为 DENY 时，该页动作即使 ALLOW 也拒绝（三序，与导航一致）。
- 预设库逐格等价：ACTION 种子 = 各角色 legacy 功能的 max，页面种子 = 已绑定功能的 max，故同一角色同一码判定不变；`RESTRICTED` 仍由 DataScope 收窄，不额外拒绝。
- 无 UI、无 API 形状、无 403 detail 变化；无请求上下文（内部回调）时仍 fail-closed，不扩权。

## 5. 回归与验证计划

| 步骤 | 命令 | 地板 |
|---|---|---|
| 编译（每任务首步） | `mvn -o -DskipTests compile` | 0 错 |
| 单测 | `mvn -o -pl deliver-service,req-service -am test` | 306 不缩水 |
| 三层授权 | `bash scripts/rbac-verify.sh` | 111/111 |
| 页面/动作能力 | `bash scripts/access-verify.sh` | 152/152（含 a5 D3 1 条） |
| 平台体检 | `bash scripts/platform-verify.sh` | 167/167 |
| 负向 | `bash scripts/platform-test-negative.sh` | 28 |
| 静态 | `bash scripts/lint.sh`、`bash scripts/verify.sh` | 22、14 |
| 收口静态断言 | `grep -rn "TenantContext\.\(can\|access\)\|PermissionMatrix\.accessOf" deliver-service/src/main req-service/src/main` | 0 命中 |
| 动作档位负向 | `bash logs/teamflow/tf-mwiju0jk-9g728z/scripts/qa-r21-action-deny.sh` | DENY→403→ALLOW→200，库回预设 |

不回归地板：rbac 111、access 152、platform-verify 167、negative 28、lint 22、unit 306、verify 14、analytics 74、collab 263。负向探针必须经**授权保存 API** 改档（触发 epoch++），直连 SQL 会被 45s 缓存挡住而误判为“未生效”。

## 6. 风险与回滚

| 风险 | 处置 |
|---|---|
| 漏改调用点，判定静默回退 | 第 5 节 grep 门禁 + a5/s9 覆盖；11 处逐条对照 |
| 页面 DENY 收紧造成既有页面 403 | 预设库逐格等价；探针覆盖“动作 DENY”与“页面 DENY”两例 |
| 单测构造注入变更（`PortalServiceTest` 等） | 只加 mock，不改断言语义 |
| 改档后仍读旧缓存 | 保存路径已 bump epoch；探针断言“改档后首次请求即生效” |
| a5 放宽掩盖真回归 | 保留反例自检（fixed-action 仍红）+ s9 声明覆盖率 |

回滚：本单无 DDL/seed，`git revert <单提交>` 即完全回退；回退后判定仍 fail-closed，不会放行。

## 7. 任务拆分（3 个，串行，每步先编译）

1. **T1 后端收口**：8 文件 11 处 + 删 `TenantContext.access/can` + `TenantContextTest` + 单测 mock；`mvn -o -DskipTests compile` → `mvn -o -pl deliver-service,req-service -am test`。
2. **T2 门禁与文档**：a5 D3 判据与反例自检；r19 两行 109→111；memory 补一行；`bash scripts/access-verify.sh`。
3. **T3 验证与提交**：全量门禁 + action-DENY 探针 + 证据（403 响应样本、门禁计数）+ 带 runId 的提交。

## 8. 假设与待澄清

1. **“7 处”按 11 处表达式/8 文件落地**（含 `DefectService` 直连与 Wiki 两类）；若只改 7 个类，`DefectService` 会留一条直连，违背“唯一入口”。
2. **`TenantContext.access/can` 直接删除**（非保留委托壳）：代价是同步改 1 个单测；若要求保留，只能委托 `PermissionService` 并标 deprecated、禁止新调用，否则又多一条可误用入口。
3. **无 ACTION 登记的 4 个老码留在 FUNCTION 层**（`plan:manage`、`TEST_EXECUTOR_MANAGE`、`CASE_REVIEW_SUBMIT`、`proddefect:report`），本单不新增 ACTION 行；若要它们也随“动作档位”变化，需先扩 ActionRegistry 与种子（另立需求）。
4. **a5 D3 的“必须 bodyAction”**按“动作码必须来自请求体、不得退化为端点级单码”落地；若本意是只认 `PermissionRouteTable` 字面量 `bodyAction(`，则需全文/常量解析，误报面更大。
5. **`plan:manage` 与 `plan:execute` 共用页面 `test.plans`**：页面 DENY 会同时关掉两条路径；若产品要求“执行不受计划页影响”，需单独页面码（超出本单）。

## 9. 完成定义（DoD）

- 业务代码 0 处 `TenantContext.can/access`、0 处 `PermissionMatrix.accessOf`；`TenantContext` 只留身份与 DataScope。
- 第 3 节 11 处全部走 `PermissionService`，403 code/message/detail 与改造前逐字一致。
- a5 D3 新判据生效且反例自检为红；r19 两行 = 111；memory 有 r21 一行。
- 第 5 节命令全部达标；探针证明“DENY→403→ALLOW→恢复”，结束库回预设。
- 单提交，含 runId `tf-mwiju0jk-9g728z` 与任务夹路径。
