# 需求变更单流程图 · 谁申请 → 影响评估 → 审批 → 应用 → 基线

> 本文件是 [REQUIREMENT-FLOW.md](REQUIREMENT-FLOW.md) 的**子流程**：前者画「需求从提报到交付」的主干，本文件只画「需求已经不该被直接改，但业务确实要改」之后的那条路。
> 依据：`docs/teamflow/20261123-r40-req-change-order/PRD.md`（AC-1…AC-21）、`TECHNICAL.md`（§4 接口契约、§5 事务、§9 计数、§12 门禁）、迁移 `req-service/src/main/resources/db/migration/V16__requirement_change.sql`。
> 状态：r40 交付物。门禁 `make change-verify`（6 段 40 条）是本文档的唯一机器判据。

## 0. 一句话

**变更单是留痕单据，不是编辑表单。** 它把「谁、因为什么、把哪个字段从什么改成什么、影响了哪些子需求/版本/发布单/人天、审批走到哪一步、最后到底应用了没有」冻在一张单子上；审批通过**不等于**生效，只有显式 `apply` 才写需求字段。

## 1. 五段流程（与代码对齐）

```
① 申请 ──► ② 影响评估 ──► ③ 审批（第 6 类 REQ_CHANGE，默认两级） ──► ④ 应用（apply） ──► ⑤ 基线
   POST .../changes       同一事务内冻结            PRODUCT → DOMAIN_OWNER          单事务 12 步         审计 + 字段事件
   preview 先看后提交      before/after + impact    通过=APPROVED，不写需求字段     行锁 + before 比对     可回查、可对账
```

| 段 | 触发 | 端点 | 落库 |
|---|---|---|---|
| ① 申请 | 详情页头部「发起变更」/ Tab「变更」/ 编辑抽屉锁定组 / `409 CHANGE_REQUIRED` banner（四入口同一个抽屉） | `POST /api/req/requirements/{id}/changes` | `req_change` 1 行（`PENDING`）+ 同事务起 `wf_instance(REQ_CHANGE)` |
| ② 影响评估 | 抽屉第 ④ 步（也可先单独预览） | `POST /api/req/requirements/{id}/changes/preview` | **不落库、不起审批**（只读路径） |
| ③ 审批 | 审批中心「需求变更」待办 | 既有引擎 `POST /api/req/approvals/tasks/{id}/actions` | `wf_*` + 单据 `PENDING → APPROVED/REJECTED`；撤回 → `WITHDRAWN` |
| ④ 应用 | 变更详情页「应用变更」（仅 `canApply` 时整颗渲染） | `POST /api/req/changes/{id}/apply` | `requirement` 字段 + `req_change_item` + 1 条审计（同一事务） |
| ⑤ 基线 | 应用后回查 | `GET /api/req/changes/{id}`、`GET /api/req/admin/audit?bizType=REQ_CHANGE&bizId=` | `before_json` 永不被覆盖；`items` 是逐字段证据 |

**为什么要有 preview**（r40 技术阶段补的 P1 缺口）：抽屉第 ④ 步必须展示真实影响（受影响子需求、所属/目标版本、是否越锁、人天预测），而创建接口会落库并起审批。没有 preview 就只能本地猜受影响发布单，等于在决策页上伪造证据。preview 与创建**共用** `validateCreate + RequirementChangeImpactAssessor.assess`，保证「预览看到的」与「冻结下来的」是同一个函数算出来的。

## 2. 状态机（单据状态，只有五态）

```
            创建
             │
             ▼
        ┌─────────┐  两级审批通过   ┌──────────┐  apply   ┌─────────┐
        │ PENDING │ ──────────────► │ APPROVED │ ───────► │ APPLIED │
        └─────────┘                 └──────────┘          └─────────┘
          │      │                        │
   驳回   │      │ 申请人撤回              │ apply 前置校验失败（树/版本/人天已变）
          ▼      ▼                        ▼
     REJECTED  WITHDRAWN           仍停在 APPROVED，返回 409
```

- **`APPROVED` 的横幅必须写「审批已通过，尚未应用 —— 需求字段仍是申请前的值」**，全站不出现「已生效」。这是防「审批通过 = 已改完」误读的主防线；`APPLIED` 才是生效。
- 状态迁移只由三处驱动：创建（→`PENDING`）、审批回调（`PENDING → APPROVED/REJECTED/WITHDRAWN`）、apply（`APPROVED → APPLIED`）。
- 条件更新（`expected → next`）是并发闸：回调与 apply 都走 `WHERE status = expected`，返回 0 行由服务判 409，**不抛异常**（抛异常会让审批引擎事务回滚掉它自己刚写完的内容）。
- 同一需求允许多张 `PENDING` 并存（改不同字段的两张单各自走审批）；互斥只在 apply 用 `before` 逐字段比对时判 `CHANGE_CONFLICT`。

## 3. 四类变更与白名单

| changeType | 允许字段 | 说明 |
|---|---|---|
| `CONTENT` | `title` `description` `priority` `expectedDeliveryDate` | 内容类；`priority` 变更不改状态，但可能触发自动化事件 |
| `DAYS` | `estimateDays` | 人天类；**必须**走 `WbsEstimateService` 的 20% 派生规则（见 §5） |
| `PARENT` | `parentId` | 换父；复验父链状态、防环、父子类型合法 |
| `RELEASE` | `releaseId` | 目标版本；先删旧 `release_requirement` 成员再插新成员，再写 `release_id` |

- 白名单由 `RequirementChangeFields.allowedFields(changeType)` **单点**判定；越界 400 `CHANGE_FIELD_NOT_ALLOWED`，detail 回传 `allowedFields`（前端可据此禁用字段，不需要在页面里再写一份白名单）。
- `before/after` **固定 7 个键**：`title`、`description`、`priority`、`expectedDeliveryDate`、`estimateDays`、`releaseId`、`parentId`。`before` 是创建时冻结的需求快照，**永不被后续操作覆盖**。
- 与 `AuditService.auditSnapshot` **刻意分离**：后者不含 `description/parentId`，复用它当变更基线会丢字段，两边形状也会互相拖累。禁止合并成一个快照器。

## 4. 影响快照 `impact`（固定 8 个顶层键）

| 键 | 内容 | 空数据时 |
|---|---|---|
| `fields` | 本次真实变化的字段列表（= `changedFields(before, after)`） | 空数组 ⇒ 创建直接 400（无变化的单不该进审批） |
| `children` | 直接子需求（id/code/title/type/status） | `[]` |
| `release` | 当前所属版本（id/name/status/locked） | `null` ⇒ 前端写「未关联版本」 |
| `targetRelease` | 目标版本（RELEASE 变更时非空） | `null` |
| `days` | `{before, after, delta, estimateStatus, approvedDays, deltaRatio, becomesStale}` | `approvedDays=null` ⇒ 前端写「无法评估」，**禁止显示 0 或 —** |
| `admission` | 版本准入门状态（是否已过门/是否已发布） | `null` |
| `postLock` | 该变更应用后是否处于「锁后变更」 | `false` 时不写锁审计 |
| `frozenAt` | 冻结时间戳 | 必填 |

**缺数据只写「无法评估」**：空值看起来像 0，会把「没人评估过」误读成「影响为零」。

## 5. 人天：两个数、一个阈值

- 展示两个数：本次改动 `+2 天`（相对 `before.estimateDays`）与对**已批评估** `+25.0%`（相对 `approvedDays`）。
- 阈值判定只认 `|delta / approvedDays| > 0.20`，**严格大于**、用**未舍入**比值，且与 `WbsEstimateService.wouldBeStale(approvedDays, afterDays)` 同源（`decide()` 与影响评估共用）。预测（冻结在 impact 里）必须等于应用后的实际评估状态——两套阈值实现迟早给出两种结论。
- 恰好 20% 显示「恰好 20%，不转 STALE」；`|r|` 落在 20.0%–20.1% 带内改显 2 位小数，避免「显示 20.0% 却转了 STALE」。

## 6. 版本锁与 `CHANGE_REQUIRED` 收口

| 场景 | 行为 |
|---|---|
| 版本 `LOCKED`/`PUBLISHED` 下改需求 | 变更单**仍可应用**（这才是变更单存在的意义），但 apply 额外写 1 条 `CHANGE_AFTER_RELEASE_LOCK` 审计；`PLAN` 版本不写 |
| 晚期态直接 `PUT` 四类字段 | 409 `CHANGE_REQUIRED`，detail `{changeEndpoint, fields[]}`；前端 banner 引导到抽屉并预填 |
| 早期态直接 `PUT` 四类字段 | **放行**（草稿编辑不该被逼进审批） |
| 只改 `domainIds`/`sonarKey`，或值没变 | 放行（不是四类字段 / 无真实变化） |

**早期态表**（唯一来源 `RequirementChangeFields.editableFields`，前端只读后端下发的 `permissions.editableFields`，禁止复制文案）：

| 类型 | 可直接编辑（早期态） | 其余状态 |
|---|---|---|
| BUS | `SUBMITTED`、`APPROVING`、`PENDING_REVIEW` | 409 `CHANGE_REQUIRED` |
| PDR | `PENDING_ANALYSIS` | 409 |
| RD | `TODO` | 409 |

## 7. 角色 × 动作矩阵

| 动作码 | 功能码（any-of） | 产品经理 | 域负责人 | 业务方 | 测试 | 研发 | 厂商 PM | 管理员 |
|---|---|---|---|---|---|---|---|---|
| `req.action.change_request`（发起） | `req:split` | ✅ | ✅ | ✅（自己的需求） | ✅ | ✅ | ⛔ 显式 DENY | ✅ |
| `req.action.change_apply`（应用） | `req:deliver` + `req:split` | ✅ | ✅ | ⛔ | ⛔ | ✅ | ⛔ 显式 DENY | ✅ |
| 审批（`REQ_CHANGE`） | — | 节点 1 `PRODUCT` | 节点 2 `DOMAIN_OWNER` | — | — | — | — | 兜底 |

- 两个动作码**不进** 16 条状态迁移矩阵（否则 `StateTables = 16` 契约会被打破）；写端点由 `PermissionRouteTable` 声明式鉴权，不新增 `@RequiresAction`。
- **权限两处口径不同**：发起被拒 = 按钮禁用 + 悬停原因；应用没有权限 = 整颗按钮不渲染（连置灰都没有），因为「批完了却点不动」会被误读成系统坏了。
- 前端只读 `allowedActions` 短码（`canRequestChange` / `canApplyChange` 由 view 下发），不复刻 any-of 双依赖。

## 8. 错误码表（前端/QA 的共同契约）

| HTTP | code | detail | 触发 |
|---|---|---|---|
| 400 | `CHANGE_FIELD_NOT_ALLOWED` | `{changeType, field, allowedFields[]}` | 字段不在该类型的白名单 |
| 400 | `REASON_REQUIRED` | `{maxLength}` | 原因空/超 500 字 |
| 400 | `VALIDATION_FAILED` | `{field, reason}` | 无变化、目标版本已取消、父链非法、防环、日期非法 |
| 409 | `CHANGE_REQUIRED` | `{changeEndpoint, fields[]}` | 晚期态 PUT 命中四类字段 |
| 409 | `CHANGE_NOT_APPROVED` | `{status}` | apply 时单据不是 `APPROVED`（含 `PENDING`/`REJECTED`/`WITHDRAWN`/`APPLIED`） |
| 409 | `CHANGE_ALREADY_APPLIED` | `{appliedAt}` | 同单第二次 apply |
| 409 | `CHANGE_CONFLICT` | `{field, expected, actual, retryable?}` | apply 时需求字段已不是本单 `before` 的值（别人先改了）；**整事务回滚，零写** |
| 403 | `FORBIDDEN` | `{action, allowedActions[]}` | 厂商越权（`VENDOR_PM` 显式 DENY）或页面不允许 |

## 9. apply 的 12 步（单事务）

1. 读单据 → 状态必须是 `APPROVED`（否则 409 并带回 `status`）
2. `findWithLockByIdAndTenantId` **锁需求行**（防并发双写 / 部分写）
3. 逐字段比对需求现值 == `before`（不等 → 409 `CHANGE_CONFLICT`，一个字节都不写）
4. 复验：父链状态、防环、人天、目标版本（树/版本可能在审批期间变了）
5. 条件更新单据 `APPROVED → APPLIED`（返回 0 行 → 409，重放安全）
6. `RELEASE` 变更：删旧 `release_requirement` 成员
7. 写需求字段
8. `RELEASE` 变更：插新成员 + 写 `release_id`
9. 写 `req_change_item`（每个变更字段 1 行，复合主键即幂等约束）
10. 写**恰好 1 条** `REQUIREMENT_CHANGE_APPLIED` 审计（不逐字段写）
11. 成员版本为 `LOCKED`/`PUBLISHED` 时补 1 条 `CHANGE_AFTER_RELEASE_LOCK`
12. 提交；`AutomationEventMapper` 按审计派生每字段 1 条 `FIELD_CHANGE` 事件（8 字段：7 可变更 + `owner_user_id`）

**审计三条动作**：`CHANGE_REQUESTED`（创建，1 条）、`REQUIREMENT_CHANGE_APPLIED`（apply，1 条）、`CHANGE_AFTER_RELEASE_LOCK`（锁后，1 条）。apply 只写一条审计、事件由 mapper 派生——逐字段写审计会让「一次变更」在审计里变成 N 条，对账时数不清。

## 10. 前端入口（一个抽屉，四个触发点）

| 入口 | 位置 | 预填 |
|---|---|---|
| Tab「变更」→「发起变更」 | 需求详情第 10 个 Tab | 无 |
| 头部「发起变更」 | 详情页头部主按钮 | 无 |
| 编辑抽屉锁定组内「需发起变更」 | `RequirementEditDrawer` 四组按 `editableFields` 锁定 | 该组对应 `changeType` |
| `409 CHANGE_REQUIRED` banner CTA | 原地 banner（不是 toast） | `detail.fields` |

抽屉固定 720 宽、四步（类型 → 原因 → 字段 → 影响预览），第 ④ 步**必须**打 `preview`，禁止本地伪造受影响发布单；提交按钮防重复。结果落深链 `/requirements/:id/changes/:changeId`（`pageCode=req.pool`，零新菜单码）。

## 11. 本轮不做

- **不回滚变更**：`APPLIED` 之后要改回去，请再开一张变更单（`before` 会被冻结成当前值，形成可审计的回滚链）。理由：回滚需要「旧值快照 + 期间其它变更的兼容性判定」，属于另一张单据的语义，硬塞进本单会让 `before`/`items` 同时承担两种含义。
- 不支持跨类型合并单（一张单只做一类变更）；不支持批量选多张单一起应用（避免部分成功）。
- 不做变更单评论/附件；审批意见仍在审批引擎侧。

## 12. 门禁与证据

| 证据 | 位置 |
|---|---|
| 契约（接口/字段/错误码/事务） | `docs/teamflow/20261123-r40-req-change-order/TECHNICAL.md` §4–§6 |
| 迁移 | `V16__requirement_change.sql`（`req_change` / `req_change_item`，无 FK，JSON 存快照） |
| 白名单/早期态/快照/精度单点 | `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeFields.java` |
| 动作码 | `platform-contract/.../ActionRegistry.java`（`req.action.change_request` / `req.action.change_apply`）、`PermissionRouteTable`（5 条路由规则） |
| 错误码 | `req-service/.../requirement/RequirementErrors.java` |
| 机器判据 | `make change-verify`（`scripts/change-verify.sh`，S1–S6 共 40 条；`--static-only` 免栈，`--twice` 连跑两次） |
| 演示 | 需求详情 Tab「变更」→ 发起 → 审批中心通过 → 详情页应用 → 审计回查 |
