# r22 验收报告 · 需求动作判定改数据驱动（ActionPermissionResolver）

- 任务夹：`docs/teamflow/20261116-r22-action-matrix-datadriven/`；契约：`TECH-CHANGE.md` §6 验收判据 ①–⑤（本需求无独立 PRD/TECHNICAL），下表按 QA 的 AC-1…AC-8 编号逐条对应。
- 基线：`docs/teamflow/20261116-r21-tenant-permission-unify/`；分支 `feat/rbac-contract-action-matrix`，HEAD `211db80`。
- 结论：**可验收**。P0/P1/P2 = 0，P3 = 2（不阻断）；架构符合蓝图，无重复实现、无第二套判定语义。

## 1. 验收判据逐条

| # | 判据 | 本轮复核（验收阶段亲跑） | 结果 |
|---|---|---|---|
| AC-1 | ① 矩阵内 `PermissionMatrix.accessOf` 0 次 | `grep -n accessOf platform-contract/src/main/java/com/example/platform/contract/RequirementActionMatrix.java` → 无命中；全仓仅 `PermissionMatrix` 自身、javadoc 与 `PermissionService` 三序内部使用 | PASS |
| AC-2 | ② 两向 e2e：改 DENY → 下一次请求即生效并 403；改回恢复 | 亲跑 `bash logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-toggle-deny.sh` → 19/19，exit 0。PRODUCT 的 `req.action.review_start` ALLOW→DENY 后**第一次**请求 `allowedActions=["review_pass","review_reject","close"]`（兄弟动作仍在）、写端点 403 `FORBIDDEN` 且 detail 指名 `req.action.review_start`/DENY；还原后 168 格逐格 0 差异 | PASS |
| AC-3 | ③ 10 角色 × 16 动作与 r17 等价 | 亲跑 `mvn -o -pl req-service -am test` → 248/0，BUILD SUCCESS；`RequirementActionMatrixWiringTest` 7 用例、480 格逐格，差异集恰为 `{MGMT submit OWN, MGMT withdraw OWN}`（§4 变化 2 已声明的有意收紧），其余 478 格零差异 | PASS |
| AC-4 | ③ 关键回归：`start_dev` PRODUCT 仍不可、`submit_test` QA 仍可 | 亲跑 `bash logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-live-role-cells.sh` → 6/6，真实 HTTP：SCHEDULED 上 PRODUCT `[]` 而 DEV `[start_dev]`；IN_DEV 上 QA `[submit_test]` 而 BUSINESS `[]`（排除假阴） | PASS |
| AC-5 | ④ 403 四键与审计「先落库再抛」不变 | 亲跑 `bash logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-contract-wiring-check.sh` → 18/18；`RequirementService.java:639-647` 仍先 `recordInNewTx("PERMISSION_DENIED")` 再抛，detail 键未变；QA 同轮 `platform-test-negative` 30/30（含 r17 动作级 5 条） | PASS |
| AC-6 | ⑤ 门禁不缩水 | 亲跑：`compile` exit 0、req-service test 248、`make lint` 22/22（L19 绿）。QA 同轮全量：platform-unit 316、rbac 111（hash `1cb84118…`，与 r21 差异仅 2 动作×10 角色 ACTION 格，属预期）、access 152、platform 167、negative 30、verify 14、demo-reset 57/57，均不低于地板 | PASS |
| AC-7 | 依赖方向 `contract ← common`，端口零 Spring/TenantContext | `grep`：contract 无 `com.example.platform.common`、无 `org.springframework` 引用；唯一实现 `PermissionService.java:50`；`logicprobe_structure_verify` 组件图（req→common→contract + R1/R2 拒绝反向边 + 三层）verdict=pass，0 errors / 0 warnings | PASS |
| AC-8 | 改动面 6 改 2 新，无 pom/前端 | `git status --porcelain`：6 M（4 主代码 + 矩阵测试 + memory 约定行）+ 2 ??（`ActionPermissionResolver.java`、`RequirementActionMatrixWiringTest.java`）+ `scripts/rbac-verify.sh` 模式位（P3-1）；`pom.xml`/`package.json`/`web/` diff 为空 | PASS |

## 2. 架构一致性（M3 质量门）

| 蓝图要求 | 实测 | 判定 |
|---|---|---|
| 端口出在 contract、实现在 common，依赖方向 `contract ← common` | 接口 `ActionPermissionResolver` 在 contract 且只依赖 JDK/`PermissionMatrix`；`PermissionService` 仅加 `implements`，`decide` 方法体零改动（未借机重写三序） | 符合 |
| 矩阵只留关系语义 + 动作码前缀 | `REQUIRED_FUNCTION/requiredFunction` 已删；`ACTION_PREFIX="req.action."`；评审侧为显式 7 动作 `REVIEW_SIDE`，不从注册表派生 | 符合 |
| 唯一判定点，两处调用共用同一 bean | `RequirementService.java:633,653` 均传注入字段 `actionPermissions`；无 `new` 实例，请求级 memo 不退化 | 符合 |
| 无重复实现 / 无第二套判定语义 | `accessForAny` 只在 `PermissionMatrix` 定义与 `RbacSeeder` 播种（蓝图分工）；`union` 仅 `PermissionService`；测试桩只复刻种子公式 | 符合 |
| 页面码单一真相 | `PAGE="req.pool"` 与 `MenuRegistry`、`ActionRegistry` 18 条、`PermissionRouteTable.bodyAction` 由 `everyDecisionAsksTheRequirementPage` 一条断言钉住 | 符合 |
| 既有结构未破坏 | 业务代码 `TenantContext.can/access`、`PermissionMatrix.accessOf` 直连 0 处；lint L19 残留 2→0 | 符合 |

结构审计说明：`logicprobe_structure_verify` 审计的是按蓝图绘制的组件图（结论 pass），代码侧依赖方向由上述 grep 独立核对；两者一致，未见「图上有、代码没有」或反向依赖。

## 3. 意见与遗留（均不阻断）

- **P3-1（版本控制面）**：`scripts/rbac-verify.sh` 的 `100644→100755` 已 staged，是修好 `make rbac-verify` 的必要改动；由 host 在验收后按 runId `tf-mwio9710-zvbc2p` 收口提交，提交说明里点一句即可。
- **P3-2（文档基线）**：`memory.md` 门禁基线行仍记 `platform-test-negative 28`，本轮实测 30。28 是 r20 地板（只增不降），非错记；下次基线订正一并改为 30。
- **未覆盖（非交付缺陷）**：本需求无前端改动，浏览器视觉走查 N/A；45s 快照 TTL 多实例时序、多租户动作格隔离属客户环境验证（详见 QA-REPORT §7）。
- **§8 假设确认（PM 采纳）**：① 代提交借 `req.action.approve` 的 ACTION 格承载全量评审授权，与 r17 `req:review` ALLOW 等价；② 两条注册表校正（`start_dev→req:deliver`、`submit_test→test:quick`）是等价性前提，必须保留；③ 本人提报也受档位管——MGMT 失去自提交属有意收紧，管理员可随时授回，若客户坚持「本人永远可提」按 §8.3 两行回退；④ 页面码门纳入动作判定（三序）；⑤ 不新增 V13，部署/验收第一步固定 `make platform-demo-reset`（旧库 2 格 drift 会让 s2 变红，属门禁正确）。

## 4. 交付与证据

- 代码：接口 `platform-contract/.../ActionPermissionResolver.java`（新）、矩阵/注册表/`PermissionService`/`RequirementService` 改动、测试 `RequirementActionMatrixTest`（11 用例）与 `RequirementActionMatrixWiringTest`（新，7 用例）。
- 证据脚本（本 run，随 host 归档，不入产品 `scripts/`）：`r22-toggle-deny.sh`（19 断言）、`r22-live-role-cells.sh`（6 断言）、`r22-contract-wiring-check.sh`（18 项）、`r22-verification-notes.md`。
- 提交：按流程由 host 在验收后按 runId 收口（当前 6 M + 2 ?? 未提交）。

验收结论：✅ 通过
