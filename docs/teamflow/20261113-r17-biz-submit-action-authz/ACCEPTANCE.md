# r17 业务需求提交动作级授权 · 验收报告（产品经理）

- 任务夹：`docs/teamflow/20261113-r17-biz-submit-action-authz/`　分支：`feat/biz-submit-permission`（未提交）
- 验收时间：2026-11-13 14:00　依据：`PRD.md`（AC-1~AC-12）+ `TECHNICAL.md` + `QA-REPORT.md` + 本人在当前工作区的独立复核
- 交付面：12 个修改文件 + 2 个新增文件（`RequirementActionMatrix.java`、`RequirementActionMatrixTest.java`）+ 本任务夹；无提交、无 pom/迁移/权限码新增

## 1. 结论

**可验收（✅ 通过）**。核心诉求成立：biz1 能提交自己的业务需求（200 / `APPROVING` / 生成 `REQ_SUBMIT` 实例 / 详情不再给 `submit`），pm 仍可代提交，厂商与非本人一律 403 且不落库、不建实例；动作授权收敛为 `platform-contract` 一张矩阵，`allowedActions` 过滤与执行共用同一谓词；前端只认服务端动作集、403 就地提示不跳页。上轮 4 条缺陷（2×P2 + 2×P3）已修复并经运行时复验。

## 2. 验收人独立复核（非转述 QA）

| 项 | 我实际执行的命令 / 观察 | 结果 |
|---|---|---|
| 静态门禁 | `bash scripts/tests/lint.sh` | 20/20，L17/L18 在列 |
| 准入门禁 | `bash scripts/access-verify.sh` | 148/148（a5 25/25，下限 23；总数守卫 ≥148 保留） |
| 负向门禁 | `bash scripts/platform-test-negative.sh` | 30/30，第 16 段（r17）5 条全 PASS |
| 全链路 | `bash scripts/platform-verify.sh` | 168/168 |
| 单测 | QA 复跑 278/278 + 现存 surefire 报告 req-service 210 例（含矩阵 9 例、路由表 13 例）；`PermissionMatrixGridTest` 600 断言未改 | 通过 |
| 代码新鲜度 | `find ... -newermt '2026-11-13 13:53'` | 空：QA 之后代码零改动，其门禁数据对本交付有效 |
| 改动边界 | `git diff --stat`（12 文件 + 2 新增） | 全部落在 AC-12 白名单内；`PermissionMatrix`/`StateTables`/`RequirementStateMachine`/审批模板 diff 为空 |
| 视觉 | 目检 `evidence/` 3 张截图 | 通过：toast「提交审批成功」、BUS-0283 `APPROVING`、页脚「已提交审批」+ 单个「撤回」、审批中心 pm 待办 #471 出现待审单据 |

## 3. 逐条验收

| AC | 判定 | 证据（可复跑） |
|---|---|---|
| AC-1 本人提交 | ✅ | `r17-action-authz-probe.sh` P1（18/18）：200 / `APPROVING` / `REQ_SUBMIT` 0→1 / `allowedActions` 无 `submit`；`evidence/ac11-1,2` |
| AC-2 pm 代提交 | ✅ | 同探针 P2：200 / `APPROVING` |
| AC-3 矩阵唯一谓词 + 先鉴权后状态 | ✅ | `RequirementActionMatrix.java:110-190`（16 动作登记、`known/can/filter`）；`RequirementService.java:290-298` 顺序 `requireWritable → authorize(仅 UI) → stateMachine.target`；`t3-order-probe.sh` 15/15（无权+非法态=403 不泄状态、未登记动作=409） |
| AC-4 负向三例 | ✅ | 负向第 16 段 5 条（含厂商评审内部需求）：403 + 状态不变 + 实例 0，我独立复跑 30/30 |
| AC-5 评审/拆解不越权、不误拒 | ✅ | 探针 P3：biz1 `review_start` 403、`decompose` 403；pm `review_start` 200、dev1 `start_dev` 200；厂商评审侧 7 动作×2 状态全 403（`qa-r17-vendor-review-scope.sh` 35/35） |
| AC-6 allowedActions 按权限过滤 | ✅ | `qa-r17-authorized-actions.sh` 15/15：biz1 自己 `SUBMITTED` 恰为 `["submit"]`（`StateTables` 该态仅 submit）、vendor1 不含、pm 含；详情页本地权限过滤 `grep -c` = 0 |
| AC-7 403 就地提示 | ✅ | `web/src/api/req.ts:127` `silentForbidden`；`RequirementDetailView.vue:144-156` 403 → warning + `refreshAll`；`t4-frontend-403-smoke.mjs` 13/13（响应序列 `[403,200]`，pathname 不变）；L17 静态钉住 |
| AC-8 门禁全绿与计数 | ✅ | 见 §2：lint 20、access 148、negative 30、platform-verify 168、unit 278、verify 14、analytics 74；两处 `PASS==TOTAL` 守卫保留；600 不变 |
| AC-9 状态机/回调零改动 | ✅ | 受保护文件 diff 为空；`RequirementApprovalCallback.java:33/50`、`RequirementReviewCallback.java:30` 以 `source=APPROVAL` 推进，矩阵仅 `"UI".equals(source)` 生效；探针 P3a 证审批通过仍到 `PENDING_REVIEW` |
| AC-10 拒绝可定位 + 留痕 | ✅ | `RequirementErrors.actionForbidden:104` 带 `action`/`allowedActions`；`RequirementService:616-636` 写 `PERMISSION_DENIED`（`biz_type=REQUIREMENT`、新事务，避免被 403 回滚吞掉）；探针 P5 4 条；路由表删除位留注释 + `PermissionRouteTableTest.requirementActionsAreNotRouteGuarded`（13 例） |
| AC-11 演示复跑留证 | ✅ | `evidence/` 3 张 13:58 新图；`DEMO-BUSINESS-FLOW.md:104`「已修复（r17）」指向本任务夹；人工目检一致 |
| AC-12 改动边界 | ✅ | 仅 contract/common/req-service/web 两文件/scripts/docs；无新表/迁移/接口/权限码/第二套前端权限表（`web/src/views/req/platform.ts` 未改，因池视图仍引用） |

## 4. 架构一致性（M3 质量门）

- **蓝图吻合**：矩阵落在 `platform-contract`（`grep '^import'` 仅 `java.util.*`，符合「Deliberately dependency-free」）；`record Caller(long, List<String>, Long)` 与 `known/can/filter/actions/requiredFunction` 签名与蓝图逐字一致。
- **单一谓词、无重复实现**：服务层 `allowedActions(type,status,reporterId)` 单点委托 `filter`（`:639-641`），`authorize` 用同一 `can`（`:621`）；服务层内无第二份 `accessOf/SELF_SERVICE/vendorId` 判据（grep 已核）。
- **顺序契约正确**：鉴权在 `requireWritable` 之后（不泄露需求存在性）、`target` 之前（403 不伪装成 409），且仅 `source=UI`；内部回调不受影响。
- **错误与审计集中**：403 体只由 `RequirementErrors.actionForbidden` 构造；`PERMISSION_DENIED` 两个写点 `biz_type` 可分流（`REQUIREMENT` vs `function`），属蓝图中已声明的有意并存。
- **可接受的偏差（1 处，需下次 PRD 追认）**：PRD AC-3 表对评审侧只写 `req:review`，实现额外要求 `vendorId==null`（QA 首轮 BUG-1：vendor1 角色码即 PMO，能评审内部需求）。方向是**收紧**、有单测（`vendorCannotFireReviewSideActions`）+ 运行时 35/35 + 负向用例双重锁定，pm/dev 侧无回归，`memory.md` 已记；不构成返工。AC-6 的 `GET /allowed-actions` 为 PRD 笔误，实现沿用既有 `GET /requirements/{id}/actions`（AC-12 明令不新增接口），行为等价。

## 5. 意见与遗留

- **接受为 P3（不阻塞）**：OBS-1 池视图仍用 `ACTION_META.permissions` 本地过滤（仅显示层，执行侧由矩阵兜底）；OBS-2 `silentForbidden` 统一后池视图 403 也变为就地提示（与 AC-7「其他页面默认跳转不变」措辞不符，行为更优，建议下轮改 PRD 措辞）。
- **驳回 OBS-3**：QA 称「厂商不得评审」分支无单测，实际 `RequirementActionMatrixTest.vendorCannotFireReviewSideActions`（`req-service/.../RequirementActionMatrixTest.java:109-121`）已覆盖 7 个评审侧动作 + `filter`，QA 的 grep 判据写错，非交付缺陷。
- **演示库**：探针遗留 `r17-*`/`qa-*`/`t4-smoke-*`/夹具（`BUS-0227/0228/0270-0283` 等）与 28 条审批待办，演示前 `make platform-demo-reset` 即可；非交付缺陷。
- **人工补测**（环境限制）：toast 动效/悬停观感、移动端安全区、整链路人工走查、读屏可达性，按 QA-REPORT §8 执行。
- 本需求未引入需回写 `memory.md` 的新约定之外的变更（r17 约定行已落盘且与实现一致，无需再改）。

验收结论：✅ 通过
