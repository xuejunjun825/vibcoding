# 技术变更单 · 需求动作判定改数据驱动（r22）

> 基线依赖：`docs/teamflow/20261116-r21-tenant-permission-unify/`。技术驱动返工，**不改前序任务夹任何 AC 文本、不新增用户可见行为 AC**（唯一可见差异见 §4，无「取代」条目）。

## 1. 背景与目标

r21 收了 8 文件 15 个判定点，唯留 `platform-contract/src/main/java/com/example/platform/contract/RequirementActionMatrix.java` 第 135-136、144-145 行仍读编译期 `PermissionMatrix.accessOf`：管理员在授权页把某动作档位改成 DENY，需求按钮与执行仍按旧常量判。目标一句话——**把 16 个需求动作的可见/可执行改成 `sys_role_permission` 数据驱动，且对 10 个内置角色的判定结果与 r17 逐角色一致**。

## 2. 影响面（6 改 1 新，无 pom/前端）

| 文件 | 改动 |
|---|---|
| `platform-contract/.../ActionPermissionResolver.java`（新） | `@FunctionalInterface Access decide(String menuCode, String actionCode)` |
| `platform-contract/.../RequirementActionMatrix.java` | `REQUIRED_FUNCTION` → `ACTION_CODE`（`req.action.<动作>`）+ 保留 `REVIEW` 常量；`can`/`filter` 增 resolver 参数；自服务/厂商/评审规则不变 |
| `platform-contract/.../ActionRegistry.java` | 2 条登记校正（§3.3） |
| `platform-common/.../security/PermissionService.java` | 仅 `implements ActionPermissionResolver`（`decide` 签名已有，判定逻辑零改动） |
| `req-service/.../requirement/RequirementService.java` | 构造注入接口（容器给 `PermissionService`），`authorize` 与 `allowedActions` 各传一次 |
| `req-service/src/test/.../RequirementActionMatrixTest.java` | 谓词用例改桩 resolver；新增「16 动作 ↔ 注册表」对齐断言 |

不动：`web/`、`pom.xml`、`package.json`、`PermissionMatrix`、`V12`、`StateTables`、403 文案与 `PermissionRouteTable`。

## 3. 技术方案

### 3.1 依赖方向（为什么是接口）

`platform-contract ← platform-common ← req-service`，contract 不得反向依赖 common。故**接口在 contract、实现在 common**：`PermissionService.decide(menuCode, actionCode)` 已存在且正是三序前两序（页面 DENY → 动作 DENY → 取较窄者），只加 `implements` 一行即可注入，服务层只依赖接口。contract 继续持有「关系语义」（谁可代提交、厂商不得评审、动作集来自 `StateTables`），数据只决定「档位」。

### 3.2 判定规则（与 r17 一一对应）

1. `known(action)` 仍是前置：未登记动作**不** 403（409 归状态机）。
2. 页面/动作两序：`resolver.decide("req.pool", "req.action." + action)` 非 DENY 才继续；RESTRICTED 放行（只收窄 `DataScope`）。同一 `PermissionService` 实例同一请求缓存，不新增派生或缓存。
3. `submit`/`withdraw` 自服务：本人提报 → 仅要求第 2 步非 DENY；代他人 → `vendorId == null` 且 `req.action.approve` 的 ACTION 格为 ALLOW（该动作登记功能码恰为 `req:review`，等价 r17 的「全量评审授权」）。
4. 评审侧 7 动作（approve/reject/review_start/review_pass/review_reject/accept/close）：`vendorId != null` 一律 false（vendor1 的角色码就是 PMO，只有 vendorId 能区分）；其余过第 2 步即可。
5. `filter` 逐动作委托 `can`（同一谓词，按钮与执行不可能分叉）；表不可用/空集仍返回 `List.of()`；403 仍先 `recordInNewTx("PERMISSION_DENIED", …)` 再抛，code/message/detail 一字不改。

### 3.3 必须校正的两条 ACTION 登记（否则数据驱动会固化错误）

`ActionRegistry` 中 `req.action.start_dev → req:split`、`req.action.submit_test → req:deliver`，与 r17 的 `req:deliver`/`test:quick` 不一致，直接按 ACTION 判会：

- PRODUCT：`req:split`=RESTRICTED、无 `req:deliver` → 由「不能开始开发」变「可以」（**扩权**）；
- QA：无 `req:deliver` → `submit_test` 变 DENY（**提测人不能提测**，功能回退）。

故校正为 `start_dev → req:deliver`、`submit_test → test:quick`；其余 14 条已一致（`submit`/`withdraw` 保留 `req:report:bus`/`req:report:dev`，那是自服务语义，不能拿它判代提交）。校正后 16 动作的 ACTION 格与 r17 逐角色等价。

### 3.4 数据落地（不新增 V13）

`sys_action` 由启动 upsert 自愈（`ON DUPLICATE KEY UPDATE … function_codes`）；ACTION 格由 reset 重播（`RbacSeeder.preset` 从注册表现算）。**部署/验收第一步必须 `make platform-demo-reset`**：旧库会保留这 2 个动作的旧格，s2 以 drift 变红——那是门禁在正确工作，不是放宽。只有「不停机升级」才需 V13 重算 2×10 格，本单不做。

## 4. 行为兼容与用户可见影响

- **不变**：403 四键形状；`allowedActions` 语义（仅当前调用者可执行）；biz1 自提交 200、提交他人 403、vendor1 提交他人 403、vendor1 `review_start` 403、pm 代提交 200；`source=APPROVAL/GITLAB` 的内部回调照旧走同一 `callerOf`，不新增主体来源。
- **变化 1（新增控制面）**：`req.pool` 页面格 DENY 现在会连同 16 个动作一起关（三序第一序）。10 个内置角色该格均为 ALLOW（注册表 `functionCodes` 为空 → `accessForAny` = ALLOW），故对内建角色无感。
- **变化 2（收紧，有意）**：本人提报行由「无条件 true」改为「动作格非 DENY」。MGMT 无任何 `req:report:*` → 失去本人提交；这正是「本人也受档位管」的直接结果，管理员可随时授回。
- **变化 3（自定义角色）**：只授 FUNCTION、没有 ACTION 格的角色，需求动作会消失——r18 之后 ACTION 格才是动作真相；升级时按授权页补齐即可。

## 5. 改动任务（3 个，串行；每步先编译）

- **T1 契约层**：新接口 + 矩阵改写（16 动作码、resolver 参数）+ `PermissionService implements`；先 `mvn -o -q -DskipTests compile`，编译过即止（单测在 T2 跑）。
- **T2 接线与数据**：`RequirementService` 注入并在 2 处传 resolver；`ActionRegistry` 2 条校正；`mvn -o -pl req-service -am test`（`RequirementActionMatrixTest`、`RbacSeederTest`、`RbacSeedEquivalenceTest`）。
- **T3 门禁与证据**：`make platform-demo-reset` → 8 条门禁 + 档位探针 e2e；证据脚本放本 run 的 `logs/teamflow/<runId>/scripts/`，不入产品 `scripts/`。

## 6. 回归与验证（下限 = r20 订正后的实测基线，168/30 是旧漂移不作判据）

| 门禁 | 命令 | 下限 |
|---|---|---|
| 编译 | `mvn -o -q -DskipTests compile` | exit 0 |
| 单测 | `mvn -o -pl req-service -am test` | 306（本轮新增用例只增） |
| RBAC | `make rbac-verify` | 101 + s2r 10 = 111；各段下限 8/8/8/6/5/8/6/9/6/5/8 |
| 数据一致 | `s2`/`s2r` 逐格 1680 + `CANONICAL_HASH` 两侧一致 | 全绿 |
| 访问 | `make access-verify` | 152（a5 的 D3 仍要求矩阵文件含 `submit` 与 `req:review`，本单保留两者） |
| 平台 | `make platform-verify` | 167 |
| 负向 | `make platform-test-negative` | 28（含第 16 段 r17 语义） |
| 静态 | `make lint` | 22；L19 残留计数 2 → 0（只减不增） |
| 冒烟 | `make verify` | 14 |

验收判据：① `RequirementActionMatrix` 内 `PermissionMatrix.accessOf` 出现 0 次；② e2e：把 BUSINESS 的 `req.action.review_start` 改 DENY → 下一次请求 `allowedActions` 不含 `review_start` 且执行 403，改回 ALLOW → 恢复（两向，≥6 断言）；③ 内置 10 角色的 16 动作判定与 r17 一致（`submit_test` QA 仍可、`start_dev` PRODUCT 仍不可）；④ 403 四键与审计顺序不变；⑤ 上述门禁不缩水。

## 7. 风险与回滚

- 忘了 reset → s2 报 2 格 drift：T3 第一步固定 reset，禁止用「改门禁期望」消红。
- 页面码写错（应为 `req.pool`）→ 全员 DENY：单测断言 `MenuRegistry` 含该页、注册表 16 个 action 的 `menuCode` 均为它。
- 内部回调用空主体 → 动作集空：与 r17 同源 `callerOf`，并由审批回调 e2e（approve/review_pass）守住。
- 回滚：revert 6 个文件 + 一次 reset（ACTION 格由 preset 重算，无需回滚数据）；无迁移脚本，无数据不可逆变更。

## 8. 假设与待澄清

1. **代提交门槛**取 `req.action.approve` 的 ACTION 格（等价 r17 `req:review` ALLOW）。若要求「不得借 approve 语义」，改接口加 `Access function(String code)` 走 `canFunction("req:review")`，仅 3 行。
2. **两条 ACTION 登记校正**（start_dev/submit_test）是等价性的前提。若坚持不改注册表，就必须接受 PRODUCT 扩权 + QA 失去提测——不建议。
3. **本人提报行**改为受档位管（MGMT 失去自提交）。若要求「本人永远可提」，在自服务分支前置 `return true`，代价是该动作对数据驱动失效。
4. **页面码门**纳入动作判定（三序）。若只判动作层，resolver 签名去掉 menu 参数即可。
5. **不新增 V13**，靠启动 upsert + reset。若要求免 reset 的不停机升级，需补 V13 重算 2×10 格（并新增迁移测试）。
