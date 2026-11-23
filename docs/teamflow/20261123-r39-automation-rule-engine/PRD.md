<!-- meta: summary="为 CTeam 补齐可启停、可审计、受数据 Scope 约束且动作不越权的自动化规则引擎：V15 四表 + 服务端事件 outbox + 四类触发/四类动作 + 执行日志与防循环/限流 + 四个前端视图 + automation-verify 门禁。" -->
基线依赖：docs/teamflow/20261123-r38-orphan-join-gate（其孤儿门禁、演示重置口径、权限/审计单点不得回归）

# CTeam 自动化规则引擎 PRD（r39）

## 1. 背景与目标

### 1.1 今天的问题

平台已有需求状态机、5 类单据的审批引擎、站内通知、审计日志和三层 RBAC，但「需求发生变化后自动做一件小事」仍靠人肉：P0/P1 新建后没人通知域负责人；预估人天超过 10 的需求不会自动打「大需求」标签；跨天扫描只能靠人记得。CTeam 的「自动化规则」在能力差距评估里被列为二期，本轮补齐。

现有能力是**动作的合法入口**，也是本轮的边界：状态只能走 `StateTables` / `RequirementActionMatrix` 与既有动作接口；通知只能走 `NotificationWriter`；审计只能走 `AuditService`；数据可见性只能走 `ScopeSql` / `ScopedJdbc`；外呼只能走 M7 的 `OutboundHttpClient` + `HmacSigner`。规则引擎是这些入口的**编排者**，不是第二个业务实现。

### 1.2 本轮目标

- **G1 可配置**：PMO 用表单配置「触发事件 + 条件 + 动作」，可启停、可查看每次命中/跳过原因，不需要改代码或写脚本。
- **G2 不越权**：动作只有通知、改负责人、打标签、调 webhook 四类；规则不能改状态、不能跳审批、不能改人天汇总、不能提发布单。
- **G3 不越界**：规则候选集与需求查询共用同一套 Scope 谓词；厂商规则不会在别的厂商/别的域的数据上命中，也不会把标题泄漏到通知或 webhook。
- **G4 不失控**：同一规则对同一需求同一事件只执行一次；规则动作产生的事件不再触发规则；单链深度上限 3；每租户每分钟执行数可配；停用立即生效。
- **G5 可验证**：新增 `make automation-verify`，正例/负例/幂等/防循环/越权/停用六类场景可重复跑；既有 9 条门禁一条不降。

## 2. 用户故事

### US-1 配置一条规则（PMO）

作为内部 PMO，我在「平台管理 → 自动化规则」新建一条规则：选「需求创建」、作用域「订单域」、条件「优先级 ∈ {P0,P1}」、动作「通知域负责人」，保存后立即启用。列表里能看到触发类型、作用域、优先级和启停状态。

**验收**：AC-2、AC-3、AC-17。

### US-2 让规则替我盯着（域负责人）

作为订单域负责人，当有人在该域新建 P0/P1 需求时，我在 5 秒内收到一条站内通知，点开能回到该需求；同一条需求不会因为事件重投或服务重启收到第二封。

**验收**：AC-4、AC-6、AC-9、AC-13。

### US-3 大需求自动打标（产品经理）

作为产品经理，当我把一条需求的预估人天从 8 改成 12，规则自动给它打上「大需求」标签；需求详情和列表都能看到这个标签，重复触发不会产生第二行标签。

**验收**：AC-5、AC-8、AC-17。

### US-4 厂商标记不能越界（安全）

作为平台管理员，我建一条作用域为厂商 A 的规则，它只能在厂商 A 可见的需求上命中；厂商 A 的账号即使角色码是 PMO，也看不到、改不了规则管理页。规则日志里不会出现别的厂商的需求标题。

**验收**：AC-3、AC-7。

### US-5 规则不能代做（审计）

作为审批人，我不接受任何规则绕过审批或直接改需求状态。规则只能建议/通知/改负责人/打标签/外呼；状态仍然只能由有权限的人在既有动作接口里改。

**验收**：AC-8、AC-10。

### US-6 出问题能止血（运维）

作为值班人员，我在规则列表点一下「停用」，下一次事件起就不再执行；执行日志能告诉我某条规则为什么没命中、哪个动作失败了、耗时多少；如果一分钟内命中太多，多出的部分被限流并留下原因，而不是把通知栏刷爆。

**验收**：AC-11、AC-12、AC-15。

### US-7 开箱即用（演示）

作为演示讲解人，`make platform-demo-reset` 之后就能看到两条预置规则，并按 `DEMO-BY-ROLE.md` 的脚本在 2 分钟内演示「P0 新建通知域负责人」「人天 > 10 打标」和「停用后不再执行」。

**验收**：AC-14、AC-18。

## 3. 功能规格

### 3.1 规则模型与迁移

新增迁移 `req-service/src/main/resources/db/migration/V15__automation_rule.sql`，**只允许新增**，V1–V14 文件字节不变。四张表（`CREATE TABLE IF NOT EXISTS`，与 V8 的幂等风格一致；不加外键，沿用现有 schema 只建索引的做法）：

- `automation_rule`：`id / tenant_id / name / enabled / trigger_type / condition_json / actions_json / scope_type / scope_id / priority / version / created_by / created_at / updated_at / updated_by`。
  - `trigger_type ∈ {REQUIREMENT_CREATED, STATUS_CHANGE, FIELD_CHANGE, SCHEDULE}`；
  - `scope_type ∈ {DOMAIN, VENDOR}`，`scope_id` 指向 `biz_domain.id` 或 `vendor.id`；
  - `priority` 数值小者先执行，默认 100；`version` 从 1 起，每次保存 +1，用于乐观锁与执行日志回溯；
  - 唯一键 `(tenant_id, name)`；派发索引 `(tenant_id, enabled, trigger_type, priority)`。
- `automation_event`：服务端事件 outbox。`id / tenant_id / event_id / event_type / requirement_id / origin / chain_depth / payload_json / status / drop_reason / attempts / created_at / processed_at / locked_at`；唯一键 `(tenant_id, event_id)`；`status ∈ {PENDING, PROCESSING, DONE, DROPPED}`；`drop_reason ∈ {RULE_ORIGIN, CHAIN_DEPTH, MALFORMED}`；`origin ∈ {USER, SYSTEM, RULE}`。定时扫描的事件 `requirement_id` 允许为空。
- `automation_run`：`id / tenant_id / rule_id / rule_version / requirement_id / event_id / event_type / status / skip_reason / actions_json / duration_ms / created_at`；唯一键 `(tenant_id, rule_id, requirement_id, event_id)`；`status ∈ {RUNNING, SUCCESS, PARTIAL, FAILED, SKIPPED}`（`RUNNING` 只在抢占后的执行窗口内出现，崩溃恢复时置 `FAILED`）。
- `requirement_tag`：`id / tenant_id / requirement_id / tag / source / created_by / created_at`；唯一键 `(tenant_id, requirement_id, tag)`；`tag` 最长 32 字符；`source ∈ {RULE, MANUAL}`。现有 schema 没有需求标签表（只有 `test_case.tags`），本表是「打标签」动作的落地处。

规则名去首尾空格后唯一；同名重复保存返回 409，不允许静默覆盖。

### 3.2 触发事件

**唯一事件源**：`AuditService` 的审计写入钩子。业务事务提交时，钩子把同一个 `requirement` 相关审计动作写成 `automation_event`（outbox），**与业务写入同事务**；不新增任何接收前端事件的公开接口，前端也无法指定 `event_id`、`origin` 或 `chain_depth`。

| 触发类型 | 产生时机 | 事件里的关键字段 |
|---|---|---|
| `REQUIREMENT_CREATED` | 需求创建成功（BUS/PDR/RD） | 需求快照：类型、域、厂商、优先级、状态、人天、负责人 |
| `STATUS_CHANGE` | 需求状态流转，**含审批结果** | `fromStatus/toStatus/action`；审批导致时带 `approvalBizType`（REQ_SUBMIT/REQ_REVIEW/WBS_ESTIMATE）与 `approvalResult`（APPROVED/REJECTED） |
| `FIELD_CHANGE` | 优先级、负责人、预估人天、版本（`release_id`）变更 | `field/before/after`；一次动作改多个字段时产生多条字段事件或一条多字段事件，但每个字段都必须可被条件单独筛选 |
| `SCHEDULE` | 每天一次扫描（默认 08:30 Asia/Shanghai，可配） | `scanDate`；按规则作用域扫描存量需求，事件 id 为 `SCHEDULE:<yyyy-MM-dd>` |

审批结果事件不是前端传的，也不是从通知反推的：审批完成回调在写需求审计时必须把 `approvalInstanceId/approvalTaskId/approvalResult` 放进 `detail`，事件钩子只做搬运。若现有审计 detail 缺这些字段，技术方案负责补齐（只加字段，不改旧消费者语义）。

**持久性**：事件先落 `automation_event`，由后台 worker 领取；业务事务回滚时事件也不存在。进程重启后 `PENDING` 事件继续处理；`PROCESSING` 超过 60 秒可被重新领取，但幂等键保证不会重复执行动作。

### 3.3 条件

条件 JSON 是**封闭 schema**，不引入用户可写的表达式/脚本（无 SpEL、JS、Groovy）。未知键、类型错误、越界值在保存时返回 400，不做“忽略未知键”的宽容解析。

```json
{
  "types": ["BUS"],
  "priorityIn": ["P0", "P1"],
  "statusIn": ["SUBMITTED"],
  "approvalResults": ["APPROVED"],
  "estimateDays": { "op": "gte", "value": 10 },
  "domainIds": [1],
  "vendorIds": [5],
  "fields": ["estimate_days"]
}
```

语义：

- 不同键之间是 AND；同一键的数组内部是 OR；
- `estimateDays.op ∈ {gt, gte, lt, lte, eq}`；
- `fields` 只对 `FIELD_CHANGE` 有意义，取值 `priority / owner_user_id / estimate_days / release_id`；
- 条件在**事件快照**上求值（`SCHEDULE` 用扫描时的当前行），避免“事件排队时状态又前进”导致规则时灵时不灵；动作执行前再用当前行做一次 Scope 复核（见 3.4）；
- 字段缺失按“不命中”处理并记原因，不抛 500；未知枚举值在保存时拒绝。

### 3.4 作用域与数据隔离

规则作用域就是规则能看见的数据边界，**不靠 Java 里再写一遍 if**：

- `DOMAIN`：候选集 = `ScopeSql.forTable("requirement")` 的域谓词（`domain_id = scope_id`）∩ 租户；
- `VENDOR`：候选集 = 同一套 requirement 谓词里的厂商列（`owner_vendor_id / assignee_vendor_id`）= `scope_id`；
- 所有候选查询、条件取数、动作前的复核都经 `ScopedJdbc` / `ScopeSql`；automation 包内不得出现第二份 `domain_id` / `owner_vendor_id` 拼接（自动化门禁做静态断言）；
- 条件里的 `domainIds` / `vendorIds` 只能**收窄**作用域：`DOMAIN` 规则只能填 `scope_id` 本身，`VENDOR` 规则只能填 `scope_id` 本身或留空；试图放宽返回 400 `CONDITION_WIDENS_SCOPE`；
- 事件发生时可见、执行时已失去可见性（例如需求转给了别的厂商）：记 `SKIPPED / SCOPE_LOST`，不执行动作，也不把标题写进日志。

执行身份是系统身份（审计里 `actor=SYSTEM`，detail 带 `ruleId/ruleName/runId/createdBy`），但数据范围永远是规则作用域，不是创建人的全部可见范围。

### 3.5 动作

动作 JSON 是白名单数组，单条规则最多 10 个动作；每个动作必须有 `type`，未知类型保存时 400 `ACTION_NOT_ALLOWED`。

| 动作 | 参数 | 落地方式 | 约束 |
|---|---|---|---|
| `NOTIFY` | `recipients[]`：`{kind: USER|ROLE|DOMAIN_OWNER, id/code}` | `NotificationWriter.send` | 收件人必须 ACTIVE、同租户、且**按自身 Scope 能看到该需求**；0 人记 `NO_RECIPIENT`，>50 人记 `TOO_MANY_RECIPIENTS` 且不发 |
| `ASSIGN_OWNER` | `userId` | 既有需求负责人变更入口（系统身份），写审计 | 目标必须 ACTIVE、同租户、在规则作用域内（域规则须有 `sys_user_domain` 关系，厂商规则须 `sys_user.vendor_id = scope_id`）；否则 `TARGET_INVALID`；已是负责人则 no-op |
| `ADD_TAG` | `tag`（≤32 字符） | 写 `requirement_tag` | 唯一键幂等；标签在需求列表/详情可见；写审计 |
| `WEBHOOK` | `configId` + `path` | M7 `OutboundHttpClient.exchangeWithRetry` + `HmacSigner.canonical` | 只允许引用已启用的 `integration_config`；URL 由 `base_url + path` 组成，规则 JSON 不存 URL/密钥；签名头 `X-App-Key / X-Timestamp / X-Signature`，另带 `X-Event-Id` 供对端去重；重试耗尽走 `IntegrationNotifier` 告警 |

**明确禁止**（保存即 400，运行期若数据被篡改则 `FAILED` 并告警）：`SET_STATUS`、`APPROVE`、`SKIP_APPROVAL`、`CREATE_RELEASE`、`EDIT_ROLLUP`（人天汇总）、以及任何未列入上表的类型。automation 包不得 import / 调用 `ApprovalEngine`、发布单创建、状态流转入口；这是代码级静态断言，不是口头约定。

动作执行顺序：先做数据库动作（`ASSIGN_OWNER` / `ADD_TAG`，同一事务）→ 提交 → 再发通知 / webhook。数据库动作失败则整条 run `FAILED`、不发通知；通知或 webhook 失败不回滚已提交的数据库动作，run 记 `PARTIAL` 并在 `actions_json` 里逐项标结果。通知/外呼不可撤回，PRD 接受这一语义并把它写进日志。

### 3.6 执行管线

1. 业务提交 → 审计钩子写 `automation_event`（同事务）→ 返回，不等待规则执行。
2. Worker 领取 `PENDING` 事件（`FOR UPDATE SKIP LOCKED`，默认并发 2）。
3. 按 `tenant + trigger_type + enabled` 取候选规则，按 `priority` 升序、同优先级按 id 升序。
4. 对每条规则：先用唯一键 `(tenant, rule, requirement, event)` 抢占一行 `automation_run`（`RUNNING`）。抢占失败说明该规则对该事件已处理过，**不写第二行**，只在应用日志记 `DUPLICATE_EVENT` 并继续下一条规则。
5. 抢占成功后：用 `ScopeSql` 复核事件需求仍在作用域内（否则行更新为 `SKIPPED / SCOPE_LOST`）；在事件快照上求值条件（不命中更新为 `SKIPPED / CONDITION_NOT_MET`）；再做租户分钟级限流（超限更新为 `SKIPPED / RATE_LIMITED`）；全部通过则执行动作（行最终为 `SUCCESS / PARTIAL / FAILED`）。
6. 规则动作产生的审计/事件标 `origin=RULE`：派发器直接置 `DROPPED / RULE_ORIGIN`，不产生 `automation_run`，不再触发任何规则。`chain_depth` 是兜底：任何来源的事件深度 ≥4 一律 `DROPPED / CHAIN_DEPTH`（正常链路 1→2→3，第 4 层被拦）。
7. 同一事件的所有候选规则基于同一快照求值；动作按优先级串行，后一条不重新求值，避免“前一条规则改了字段导致后一条时灵时不灵”。

**至多一次**：唯一键在动作前抢占，崩溃恢复看到已有 run 就只补状态、不重放动作。宁可漏一次（并留 `FAILED`/`DROPPED` 证据），不重复打扰。

### 3.7 执行日志 `automation_run`

每个“成功抢占幂等位并完成判定”的候选规则都有一行，包括未命中（`SKIPPED / CONDITION_NOT_MET`）；停用规则、越权数据和重复事件不进入（或抢不到）候选，因此不产生第二行——这本身是隔离与幂等的证据。

- 状态：`RUNNING`（已抢占、动作未结束）/ `SUCCESS`（全部动作成功）/ `PARTIAL`（部分动作失败或被跳过）/ `FAILED`（数据库动作失败、动作非法或进程中断）/ `SKIPPED`；
- `skip_reason` 枚举：`CONDITION_NOT_MET / RATE_LIMITED / SCOPE_LOST / NO_RECIPIENT / TOO_MANY_RECIPIENTS / TARGET_INVALID / SCHEDULE_TRUNCATED`；事件级丢弃原因（`RULE_ORIGIN / CHAIN_DEPTH`）记录在 `automation_event.drop_reason`，不占 run 行；
- `actions_json`：逐动作 `{type, status, message, target}`，不写密钥、不写完整 URL、不写需求正文；
- `duration_ms`：从领取到动作结束的墙钟毫秒；`rule_version` 记录执行时的规则版本；
- 日志页支持按规则、状态、需求编号、时间范围筛选，支持展开看逐动作结果。

保留期本轮不做自动清理（非目标）；日志量由限流、单规则动作上限和定时扫描上限约束。

### 3.8 权限

- 新增页面码 `automation.rules`（模块「平台管理」，路由 `automation-rules`）、功能码 `automation:manage`、动作码 `automation.action.manage`，三处注册表同源新增；
- 授权：`PLATFORM_ADMIN` 全允许；`PMO` 页面 + 动作允许；其他内置角色 DENY；
- 厂商账号（`sys_user.vendor_id` 非空）即使角色码是 PMO，也**不能**看到或调用规则管理：服务端返回 403 `INTERNAL_ONLY`（与需求评审侧 `vendorId == null` 同口径），前端导航与路由守卫按 `me.vendorId` 隐藏/拦截；厂商只能作为规则的**作用域对象**，不能成为规则的操作者；
- 读接口同样受页面码保护；本轮不提供“其他角色只读日志”的入口（见待澄清 Q3）；
- 规则创建/修改/启停写 `audit_log`（`AUTOMATION_RULE_CREATE / UPDATE / ENABLE / DISABLE`），detail 含 before/after 与 version，不含密钥。

### 3.9 前端

技术栈沿用 Vue3 + Element Plus + Vite + 现有 `UI-STYLE.md`，不引新组件库。四个视图：

1. `/automation/rules`：列表（名称、触发、作用域、优先级、状态、版本、最近执行、操作），支持关键词/触发/启停筛选；行内启停开关（带二次确认与乐观锁 version）；空态说明“还没有规则”。
2. `/automation/rules/new`：新增表单；基础信息 → 触发类型及参数 → 作用域 → 条件 → 动作 → 摘要预览；前端只做表单校验，服务端错误按字段回显。
3. `/automation/rules/:id/edit`：编辑；带 version 提交，409 时提示“规则已被他人修改，请刷新”；停用状态顶部有醒目提示。
4. `/automation/runs`：执行日志；按规则/状态/需求/时间筛选，展开看逐动作结果；空态说明“规则命中后会在这里出现”。

交互约束：**不提供原始 JSON 编辑框**（避免用户写出服务端不接受的 schema）；动作类型只能从四类里选；作用域选定后，条件里的域/厂商选项被限制在同一范围；`WEBHOOK` 只提供已启用集成配置的下拉。所有写操作按钮对无 `automation.action.manage` 的用户渲染为 disabled + title（沿用现有权限组件），页面码 DENY 时路由守卫拦截而不是白屏。

需求列表/详情的标签 chip 属于本轮：`ADD_TAG` 写入后必须能在既有需求视图看到，否则“打标签”是不可观察的。

### 3.10 演示种子

在 `DemoFixture` / `DemoResetService` 的夹具之后种两条规则（Tx1 直插业务表不发审计，规则不会在重置时触发，通知仍精确 4 条）：

- R1 `P0/P1 新建通知域负责人`：`REQUIREMENT_CREATED`，作用域 `DOMAIN=ORD`，条件 `priorityIn=[P0,P1]`，动作 `NOTIFY DOMAIN_OWNER`，优先级 100，启用；
- R2 `大需求自动打标`：`FIELD_CHANGE` + `fields=[estimate_days]`，作用域 `DOMAIN=ORD`，条件 `estimateDays.gte=10`，动作 `ADD_TAG 大需求`，优先级 110，启用。

`DemoFixture.DELETE_ORDER` 加入 `automation_run` / `automation_event` / `automation_rule` / `requirement_tag`（在 `requirement` 之前），保证重置无残留；`platform-demo-reset` 从 76 条增至 **80** 条（+2 规则存在且启用、+R1 触发/作用域、+R2 条件/动作），通知仍断言恰好 4 条。

## 4. 验收标准

> 每条都可由 QA 在真栈上重复验证；命令默认已 `make dev-up`。AC 只属于本任务夹，从 AC-1 起编号。

**AC-1 迁移与四表**
`V15__automation_rule.sql` 是唯一新增迁移；只建 `automation_rule / automation_event / automation_run / requirement_tag` 四表，列、唯一键、索引如 3.1；V1–V14 文件字节不变；空库与已有库各启动一次，`flyway_schema_history` 仅新增 V15 一行；对同一库重复执行 V15 的 DDL 不报错（幂等）；`SHOW TABLES` 四表齐全。

**AC-2 规则 CRUD 与封闭校验**
`POST/PUT /automation/rules` 对未知条件键、未知动作类型、空动作、动作 >10、名称 >100、JSON >8KB、非法枚举一律 400 并带稳定错误码；`SET_STATUS/APPROVE/SKIP_APPROVAL/CREATE_RELEASE/EDIT_ROLLUP` 返回 400 `ACTION_NOT_ALLOWED`；同名 409；PUT 携带旧 `version` 返回 409；每次保存 `version` +1；GET 响应不出现密钥/完整 webhook URL；创建/修改/启停各写一条 `AUTOMATION_RULE_*` 审计。

**AC-3 权限与注册表**
`automation.rules` 页面、`automation:manage` 功能码、`automation.action.manage` 动作码进入 MenuRegistry/ActionRegistry/PermissionMatrix；PLATFORM_ADMIN 全允许、PMO 页面+动作允许、其他角色 DENY；厂商账号（vendor1）访问列表/写接口均 403 `INTERNAL_ONLY`，导航不显示，直接输 URL 被守卫拦截；`rbac-verify` 仍 113/113，`s1` 三向一致通过；权限矩阵与 `DEMO-BY-ROLE.md` 的每角色授权格数按注册表重算（新增 1 页面 + 1 动作 + 1 功能码，整体 +3/角色），不得残留旧数字。

**AC-4 事件来源唯一且持久**
审计钩子在业务同事务写 `automation_event`；公开接口中不存在接收前端事件的路由（`POST/PUT /automation/events` 返回 404/405）；事件字段 `event_id/origin/chain_depth` 只由服务端生成；业务事务回滚时事件行不存在；`PENDING` 事件在服务重启后仍被处理为 `DONE`；同一 `event_id` 重复写入只保留一行。

**AC-5 四类触发覆盖**
- 创建 BUS/PDR/RD 各产生 `REQUIREMENT_CREATED` 事件；
- 审批通过、审批驳回各产生一条 `STATUS_CHANGE`，payload 的 `approvalResult` 分别为 APPROVED/REJECTED，`approvalBizType` 正确；
- 优先级、负责人、预估人天、`release_id` 各改一次，各产生可被 `fields` 命中的 `FIELD_CHANGE`，带 before/after；
- `SCHEDULE` 在配置时刻扫描一次，作用域内的需求各产生一条 `SCHEDULE:<date>` 事件；
- 以上事件均无前端上报路径。

**AC-6 条件求值**
条件键间 AND、数组内 OR；`estimateDays` 五种比较符逐一验证；未知键/非法枚举 400；字段缺失按不命中处理且 run 有 `CONDITION_NOT_MET`；条件在事件快照上求值（事件后状态前进不影响本次判定）；`SCHEDULE` 用扫描时当前行。

**AC-7 Scope 共源与越权为零**
候选查询只经 `ScopeSql/ScopedJdbc`；automation 包静态断言无第二份 `domain_id/owner_vendor_id` 拼接；`DOMAIN` 规则只命中该域，`VENDOR` 规则只命中该厂商为 owner/assignee 的需求；厂商 A 规则对厂商 B 需求 0 候选、0 run、0 通知；条件 `domainIds/vendorIds` 试图放宽返回 400 `CONDITION_WIDENS_SCOPE`；事件后失去可见性记 `SCOPE_LOST` 且动作不执行。

**AC-8 动作白名单与边界**
四类动作各至少一次成功；禁止动作保存即 400；automation 包静态断言不 import/call `ApprovalEngine`、状态流转入口、发布单创建；NOTIFY 收件人必须是 ACTIVE、同租户、能按自身 Scope 看到需求，0 人 `NO_RECIPIENT`，>50 人 `TOO_MANY_RECIPIENTS` 且不发；ASSIGN_OWNER 目标非法 `TARGET_INVALID`；ADD_TAG 唯一键幂等且需求详情/列表可见；WEBHOOK 只接受已启用 `integration_config`，签名可被对端用 `HmacSigner` 验证，500→500→200 时第 3 次成功，重试耗尽 `FAILED` 并产生 IntegrationNotifier 告警。

**AC-9 幂等**
同一 `event_id` 重投两次（含并发两 worker）：`automation_run` 恰 1 行（首次即使 `SKIPPED` 也占住唯一键，第二次不写第二行）、通知恰 1 条、标签恰 1 行、负责人变更恰 1 次；进程在动作后、状态更新前被终止，恢复后不重放动作；日志中不出现第二条同键 SUCCESS；语义为至多一次。

**AC-10 防循环**
规则动作产生的审计/事件 `origin=RULE`，派发器置 `DROPPED/RULE_ORIGIN`（`automation_event.drop_reason=RULE_ORIGIN`），0 个 `automation_run`、0 个新动作；直接落一条 `chain_depth=4` 的待处理事件，结果为 `DROPPED/CHAIN_DEPTH`、0 run；`chain_depth=3` 仍可执行，第 4 层被拦；一次“打标签→再触发规则”的尝试不产生第二条执行链。

**AC-11 限流与止血**
`platform.automation.max-runs-per-minute` 可配（默认 120，按租户/分钟）；超限的规则不执行、记 `SKIPPED/RATE_LIMITED`；单元测试覆盖 119/120/121 边界；停用规则后下一次事件 0 新 run（≤1 秒生效）；批量创建 50 条命中需求时，通知数不超过上限且日志能解释每一条被拦记录。

**AC-12 执行日志语义**
每个成功抢占幂等位的候选规则都有 run 行（含 `CONDITION_NOT_MET`），停用规则、越权数据与重复事件不产生第二行；状态与 `skip_reason` 取值符合 3.7；`actions_json` 逐动作有 status/message/target；`duration_ms >= 0`；`rule_version` 等于执行时规则版本；日志接口可按规则/状态/需求编号/时间筛选；响应不含密钥、完整 URL、需求正文。

**AC-13 不阻塞业务链路**
WEBHOOK 对端 sleep 2 秒时，创建/更新需求接口仍在 1 秒内返回；事件在 5 秒内出现在执行日志；业务事务回滚时无事件、无 run；规则执行使用独立线程/事务，不在 HTTP 业务事务内。

**AC-14 演示种子与重置**
`make platform-demo-reset` 后两条规则存在且启用（名称、触发、作用域、条件、动作如 3.10）；`notification` 仍恰好 4 条；`automation_run/automation_event/requirement_tag` 重置后为 0 行（规则本身 2 行）；`DELETE_ORDER` 覆盖四张新表且顺序在 `requirement` 之前；连续两次 reset 均 **80/80**；`DEMO-BY-ROLE.md` 的 §9 脚本可按序复现 P0 通知、人天 12 打标、停用后不执行，并留 3 张截图。

**AC-15 `make automation-verify`**
新增目标与 `scripts/automation-verify.sh`，可重复执行；分 6 段（正例 / 负例 / 幂等 / 防循环 / 越权 / 停用+动作边界），断言总数 **≥36**；`PASS == TOTAL` 且失败非 0 退出；连续两次运行第二次仍全绿；运行使用独立 `RUN_TAG` 并在结束时清理自己造的 run/event/tag/notification/需求，清理后新表 `RUN_TAG` 残留为 0；运行后 `platform-demo-reset` 仍 80/80；不在项目根写日志。

**AC-16 既有门禁不缩水**
`platform-verify ≥194`、`platform-test-negative ≥130`、`access-verify ≥206`、`rbac-verify 113`、`release-flow-verify 44`、`gate-order-verify 20`、`platform-demo-reset 80`、`lint 22` 全绿且断言数只增不减；`unit` 新增条件/幂等/防循环/限流/签名/定时扫描用例且旧用例不删；`make help` 出现 `automation-verify`；没有任何门禁被放宽、跳过或改成分段可选。

**AC-17 前端四视图**
`/automation/rules`、`/automation/rules/new`、`/automation/rules/:id/edit`、`/automation/runs` 四个视图可访问；列表启停、创建、编辑、日志筛选与展开可用；无原始 JSON 编辑；条件中的域/厂商选择受作用域限制；`WEBHOOK` 只列已启用集成配置；无权限按钮 disabled + title，页面 DENY 时守卫拦截；厂商账号导航隐藏；空态/加载/错误态齐全；`npm run build` 通过；视觉符合 `UI-STYLE.md`；标签 chip 在需求列表/详情可见。

**AC-18 文档与提交卫生**
`DEMO-BY-ROLE.md` 增 §9；`memory.md` 只改同主题行（新增自动化规则的约定，不写变更日志）；`architecture/ARCHITECTURE.md` 增「自动化规则」一节（事件流、四表、动作边界），不改旧章节结论；`.gitignore` 复核覆盖 `target/`、`node_modules/`、`dist/`、`.run/`、`.nacos/`、`logs/`、`.env*` 与 IDE 文件，演示截图放 `docs/teamflow/demo-shots/` 且可入库；在既有 `feat/automation-rules-engine` 分支开发，不新建/不合并；提交信息含 `runId=tf-mwspkg4u-nvy5y5` 与任务夹路径；diff 不含 `pom.xml`、`web/package.json`、锁文件（本轮无新依赖）、不含 V1–V14 改动；提交后复跑 AC-16 全绿。

## 5. 范围与非目标

**范围内**：规则模型与 V15；四类触发；条件与 Scope；四类动作；权限与审计；幂等/防循环/限流；执行日志；四个前端视图；需求标签展示；两条演示规则；`automation-verify`；文档与门禁。

**非目标**：

- 不做通用工作流/BPM，不做用户可写表达式或脚本（无 SpEL/JS/Groovy）；
- 不做跨租户规则、规则市场、规则模板中心、版本回滚（`version` 只用于并发与审计）；
- 不做风险基线、敏感扫描（用户明确本轮不扩）；
- 不做短信/邮件/IM 外部通知渠道，站内通知 + webhook 足够；
- 不做规则动作的人工审批（动作本身不越权）；
- 不做历史事件回放：新建/修改规则不补跑历史事件；
- 不做标签管理后台（标签由动作写入，规则视图可筛选）；
- 不做定时任务补跑：服务在扫描时刻停机，本次错过不补；
- 不提供规则硬删除：停用即永久保留审计与日志（见 3.8）。

## 6. anti-goals / 反面目标

> 下面每一条都是“测试全绿但人会用脚投票”的具体失败，不是“我们不做某功能”。

1. **规则把状态改了，或绕过了审批**：有人保存一条 `SET_STATUS=POOLED` 的规则并成功执行，需求没走审批就到了发布池。即使门禁全绿，这也是产品失败。可观察：保存即 400；任何规则执行后需求 `status` 不变；代码里没有从 automation 包到 `ApprovalEngine`/状态流转的调用。
2. **厂商规则命中了别人的数据并把标题发了出去**：厂商 A 的规则给厂商 B 的需求负责人发了通知，或 webhook 里出现 B 的标题。可观察：厂商 A 规则对 B 需求 0 run、0 通知；日志里没有 B 的需求编号；条件放宽被 400 拒绝。
3. **一条规则把通知栏刷爆，且停不下来**：门禁造数/批量导入触发 500 条通知，PMO 点停用后仍有新通知。可观察：超过每分钟上限的部分只留 `RATE_LIMITED`，通知数不增长；停用后 1 秒内 0 新 run。
4. **规则“看起来启用”却从不触发，也没有任何解释**：用户配了 P0 通知，新建 P0 后既没有通知，日志里也没有一行——只能猜。可观察：作用域内、触发类型匹配的每个事件都有 run 行；未命中一定有 `skip_reason`。
5. **服务器被规则当成内网探测器，或密钥能从规则里读出来**：PMO 在 webhook 动作里填 `http://127.0.0.1:8080/actuator`，保存后服务器立刻去请求。可观察：保存任意 URL 返回 400；GET 规则不返回密钥；webhook 只能选已登记的集成配置。

## 7. 交互流程摘要

**配置**：登录（PLATFORM_ADMIN 或内部 PMO）→ 平台管理 → 自动化规则 → 新建 → 选触发（创建/状态/字段/定时）→ 选作用域（域/厂商）→ 填条件（类型、优先级、状态、审批结果、人天、域/厂商、字段）→ 加动作（通知人/角色/域负责人、改负责人、打标签、webhook）→ 保存（服务端校验 + version=1）→ 列表显示启用。

**执行**：业务动作提交 → 审计钩子同事务写事件 → 返回用户 → worker 领事件 → 按优先级取规则 → Scope 复核 → 抢幂等位 → 快照条件求值 → 限流 → 执行动作 → 写/更新 run → 通知/webhook 可查。规则动作产生的事件被标 `origin=RULE` 并丢弃，不回流。

**排障**：规则列表看最近执行 → 进执行日志按需求编号筛 → 看 `skip_reason` 与逐动作结果 → 需要止血就停用规则（立即生效）→ 需要改条件就编辑（version+1，旧日志保留旧版本号）。

## 8. 优先级与里程碑

**P0（本轮必须交付）**：AC-1 ～ AC-17。

**P1（本轮完成，但不阻塞主链验收）**：AC-18 中 `ARCHITECTURE.md` 一节（文档补全）。

**里程碑建议**：

- M1：V15 四表 + 规则模型/校验 + 三处注册表与权限（AC-1/2/3）。
- M2：审计钩子 outbox + 四类触发 + 条件/Scope + 四类动作（AC-4/5/6/7/8）。
- M3：幂等/防循环/限流/日志 + 异步 worker（AC-9/10/11/12/13）。
- M4：演示种子 + `automation-verify` + 四视图 + 标签展示（AC-14/15/17）。
- M5：文档、全量门禁、提交（AC-16/18）。

## 9. 依赖与风险

**依赖**：

- r38 的孤儿门禁、`platform-demo-reset` 76 条与 `gate-order-verify` 20 条口径；本需求把 demo-reset 提到 80，必须在同一份脚本里加断言而不是另起一套。
- 现有 `AuditService`（唯一审计入口）、`NotificationWriter`（唯一通知入口）、`ScopeSql/ScopedJdbc`（唯一隔离入口）、`OutboundHttpClient/HmacSigner`（M7 外呼与签名）、`ApproverResolver` 的 `DOMAIN_OWNER` 解析。
- RBAC 三张注册表与启动自愈；新增页面/动作会改每角色授权格数，`s1` 三向一致与 `DEMO-BY-ROLE.md` 必须同批更新。
- MySQL 9.6 的 `JSON` 列与 `FOR UPDATE SKIP LOCKED`。

**风险与应对**：

- **审计 detail 不够**：现有 `UPDATE` 只带 title/priority，负责人/人天/版本/审批结果可能不在 detail 里。应对：只扩 detail 字段（不改旧键语义），事件层做字段映射；技术方案先核实再动手。
- **演示通知精确 4 条**：夹具直插不发审计，规则不会在 reset 时触发；任何通过 API 造数的门禁都可能触发预置规则。应对：`automation-verify` 用独立 RUN_TAG 并在结束时清理自己的 notification；演示脚本只在讲解时手动触发。
- **门禁互相污染**：`gate-fixture.sh` 经 API 造 BUS→PDR→RD 会触发规则。应对：异步执行 + 规则动作不写演示夹具依赖的行 + 结束时清理；若仍影响既有断言，规则预置默认只对 `DOMAIN=ORD` 生效并在门禁夹具中显式停用。
- **新增页面导致权限格数漂移**：应对：格数从注册表计算，不写死；`rbac-verify` 断言数不变，`DEMO-BY-ROLE.md` 同批重算。
- **单实例限流**：内存计数在多实例下会放大上限。应对：本轮部署是单实例；代码注释标明多实例需改 DB 计数，登记为技术债。
- **webhook 拖慢 worker**：重试 + 超时可能占住 worker。应对：默认 2 并发、单次超时沿用集成配置（默认 5s）、动作级失败不阻塞同事件其他规则；监控 `duration_ms`。
- **规则动作的副作用不可回滚**：通知已发出、webhook 已送达。应对：数据库动作先提交、通知/外呼后置；日志逐动作标结果，产品接受“至多一次 + 部分成功”。
- **性能**：候选查询必须走索引；`automation_run` 的 `(rule_id, created_at)` 与 `(requirement_id)` 建索引，避免日志页拖慢主库。

## 10. 工程约束

- **迁移**：只新增 `V15__automation_rule.sql`；不改 V1–V14；DDL 幂等；四张新表必须进 Flyway；不加外键、不引 MQ/Redis/Flowable。
- **技术栈**：Java 25 / Spring Boot 4.0.9；前端 Vue3 + Element Plus；不引新依赖（`pom.xml`、`web/package.json`、锁文件 diff 为空）；不引入 Python 交付物。
- **单点入口**：审计走 `AuditService`，通知走 `NotificationWriter`，数据范围走 `ScopeSql/ScopedJdbc`，外呼走 `OutboundHttpClient`，签名走 `HmacSigner`，域负责人解析走 `ApproverResolver`；不得复制第二份。
- **脚本**：新增 `scripts/automation-verify.sh`，Makefile 只做薄封装；日志写 `logs/teamflow/<runId>/`，不在项目根散落文件。
- **.gitignore 复核（开发前执行）**：确认 `target/`、`node_modules/`、`dist/`、`.run/`、`.nacos/`、`logs/`、`.env*`、IDE 文件（`.idea/`、`*.iml`、`.vscode/`）已被忽略；演示截图放 `docs/teamflow/demo-shots/` 且不被误忽略；`automation-verify` 的临时产物不得入库。
- **分支与提交**：在既有 `feat/automation-rules-engine` 分支开发，**不新建分支、不合并**；当前未提交的 `docs/teamflow/20261123-r39-automation-rule-engine/` 是任务夹，提交时一并纳入，不丢弃；提交信息含 `runId=tf-mwspkg4u-nvy5y5` 与任务夹路径；提交后复跑 AC-16 的九条门禁。
- **文档边界**：只写 `docs/teamflow/` 与 `logs/teamflow/`；不写宿主 `docs/<角色>/`；不改 AGENTS.md 托管区以外的内容。

## 11. 假设与待澄清

- 【事实】审批完成时，需求审计的 `detail` 是否已经包含 `approvalInstanceId / approvalTaskId / approvalResult`，本轮勘察没有读到审批回调处的 detail 构造。**为什么**：决定 `STATUS_CHANGE` 事件是直接搬运还是需要扩审计字段。**若改变**：若已包含，技术方案只做映射；若没有，必须扩 detail（只加字段、不改旧键），并接受历史事件不回填。
- 【品味】每分钟执行超限时，是直接丢弃并记 `RATE_LIMITED`，还是排队到下一分钟补跑。**默认**：丢弃 + 日志（宁可漏一次，不叠加延迟与重复打扰；与“至多一次”一致）。**若改变**：需要新增队列/积压策略，限流语义和 `automation_run` 的 `SKIPPED` 口径随之变化。
- 【品味】`WEBHOOK` 动作是否只允许引用 M7 已登记的 `integration_config`，还是允许用户填任意 HTTPS URL + 单独密钥。**默认**：只允许已登记配置（URL/密钥不入规则 JSON，天然挡 SSRF 与密钥泄漏；代价是每个接收方要先登记）。**若改变**：要多一套 webhook 端点与密钥管理、URL 白名单/内网地址拦截。
- 【品味】规则与执行日志的可见范围，是否只给 PLATFORM_ADMIN + 内部 PMO，还是给产品经理/域负责人只读入口。**默认**：只给管理员与内部 PMO（规则条件会暴露需求筛选口径与需求编号，最小暴露）。**若改变**：新增只读页面码/动作码，权限矩阵与 `DEMO-BY-ROLE.md` 再算一轮。
- 【品味】定时扫描的时刻与单规则单日上限。**默认**：每天 08:30（Asia/Shanghai，可配），单规则单日最多扫描 200 条，超出记 `SCHEDULE_TRUNCATED` 并在日志告警，不静默截断。**若改变**：改配置与演示脚本；若要求全量扫描，需要分批与进度可见。
- 【事实】“版本”字段变更的口径：需求表同时有 `release_id` 与 `version`（行版本），本轮按 `release_id` 理解“所属版本”，`version` 只作为规则自身的乐观锁。**为什么**：两者同名但语义不同，事件字段必须选一个。**若改变**：`FIELD_CHANGE.fields` 增加 `version` 或改名，条件与文档同步。

> 以上 6 条均进入 `openQuestions`，`kind` 与上文一致；没有“我替用户定了但不告诉他”的项。
