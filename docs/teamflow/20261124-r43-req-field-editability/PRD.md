<!-- meta: summary="以 platform-contract 的状态×字段矩阵为唯一判定源，详情接口逐字段返回 EDIT/CHANGE_REQUIRED/LOCKED 及原因，PUT 按矩阵返回 409，详情页与变更记录只按服务端规则渲染。" -->

基线依赖：docs/teamflow/20261123-r40-req-change-order/（变更单闭环、409 `CHANGE_REQUIRED`、apply 事务、锁定/发布版本仍可 apply、变更 Tab 均为既有行为，不得回退）。矩阵之外仍保留既有业务门：父子类型/环、父状态门、0.5 天精度、版本可见与 `RELEASE_LOCKED`、终态流转限制。

取代：
- docs/teamflow/20261123-r40-req-change-order#AC-12：终态不再表述为「其余状态一律走变更单」——终态无法发起变更单，改为 `LOCKED`；PUT 终态拒绝由 400 `VALIDATION_FAILED` 收敛为 409 `FIELD_LOCKED`（reasonCode=`TERMINAL`）。
- docs/teamflow/20261123-r40-req-change-order#AC-11：PUT 侧「父需求改人天」由 400 `PARENT_DAYS_READONLY` 收敛为 409 `FIELD_LOCKED`（reasonCode=`PARENT_ROLLUP`），规则本身（父需求人天只由子需求汇总）不变；变更单创建/应用侧仍返回 400 `PARENT_DAYS_READONLY`。

# 需求内容可编辑性 + 变更记录（PRD）

## 1. 背景与问题

详情页把字段全量渲染成可编辑控件，「能不能改」目前由三处零散判断各说各话：

- 后端 `requireWritable` 只拦终态，其余交给 `RequirementChangeGuard` 与各 `check*` 规则；
- `permissions.editableFields` 在早期态一次给 7 个字段（全有/全无），但实际 PUT 里 `estimateDays`（BUS/PDR 父需求）与 `parentId`（BUS 顶层）必被拒；
- 前端 `RequirementEditDrawer` 自己判断 `children.length`，`OverviewTab` 与 409 处理各自拼文案。

结果是用户看到的可编辑控件与后端可接受的操作不一致：点进去能填，提交被 409 `CHANGE_REQUIRED` 或 400 `PARENT_DAYS_READONLY` 打回；终态、负责人、类型这些「根本不是一个通道」的字段没有原因码，前端只能写死文案。

r40 已把「改内容」收口成变更单，但「哪些字段现在能改、为什么不能改、该走哪个动作」仍没有单点。本需求把判定收敛为一份 contract 矩阵，详情接口把结果连同原因一起返回，前端只做渲染。

## 2. 目标与非目标

### 2.1 目标

1. **服务端唯一矩阵**：3 类需求（BUS/PDR/RD）× 全部状态 × 11 个字段，每格给出 `EDIT` / `CHANGE_REQUIRED` / `LOCKED` 与原因码；矩阵只存在于 `platform-contract`，req-service 只调用，不再持有第二张表。
2. **契约外显**：`GET /api/req/requirements/{id}` 的 `Detail` 返回 `editableFields`、`changeRequiredFields`、`lockedFields` 三个数组（每项 `{field, reasonCode}`），前端只按数组渲染，不再判断状态。
3. **错误可操作**：PUT 命中受控字段 → 409 `CHANGE_REQUIRED`（detail 带 `fields`，沿用 r40）；命中锁定字段 → 409 `FIELD_LOCKED`（detail 带 `field`、`reasonCode`）；两类不混报。
4. **变更记录可见**：详情「变更」区能直接看到该需求的全部变更单：编号、状态、类型、目标字段、提出人、审批结果、时间；可从详情与字段行直接发起变更（沿用 r40 抽屉）。
5. **可验证**：新增 `scripts/editability-verify.sh` 接入 `make test`，逐字段证明「接口说的」与「PUT 实际做的」一致，且负向自检必须变红。

### 2.2 非目标

- 不做**字段级权限**（按人/角色/负责人区分字段可编辑性）；本轮只做「状态 × 字段」。见 §11 Q1。
- 不新增变更类型、审批流或数据库迁移；r40 的 apply 事务、冲突检测、锁定版本审计不改。
- 不做字段级审计（PUT 的 `field before→after` 写入 `detail_json`）——需求列为「时间允许的可选项」，本轮不纳入验收，见 §2.3 与 §10。
- 不改 deliver 侧（交付单/发布/版本审批）业务语义。
- 不新增业务单号（`CHG-YYYYMMDD-NNN` 之类）；见 §11 Q3。
- 不重构详情页信息架构与视觉；只增加可编辑性标记、禁用原因与变更记录信息。

### 2.3 anti-goals / 反面目标

以下是「技术上都过了、用户仍然会拒绝」的失败形态，验收时必须逐条对照：

1. **页面说能改，提交被拒**：任一状态下页面把某字段画成可输入控件，保存却返回 `CHANGE_REQUIRED` / `FIELD_LOCKED`；或反过来，接口说 `EDIT` 而 PUT 实际 409。检查方式：逐类型 × 逐状态 × 逐字段对照数组与真实 PUT 结果。
2. **只读字段被藏起来**：为了「只展示能改的」，详情页把 `LOCKED` 字段的值藏掉，用户看不到当前负责人、类型、人天。检查方式：任一终态详情仍能看到全部 11 个字段的值（只是不可编辑。
3. **把用户引向死路**：终态或无权限时仍显示可点击的「发起变更」，点完得到 400/403；或两类 409 的引导动作互换（`FIELD_LOCKED` 却弹「发起变更」）。检查方式：终态详情不存在可点击的发起入口；两类错误的动作文案各自正确。
4. **变更记录答不出「改过什么」**：列表只有状态与原因，目标字段/审批结果/应用时间必须点进详情才知道；或默认只显示 `APPLIED`。检查方式：列表行内可直接看到目标字段与审批结果；默认无状态过滤。
5. **文案与原因码漂移**：新增一个 `reasonCode` 后前端显示英文、空白或旧文案；或同一句中文在两个组件里各写一遍。检查方式：Java/TS/文案表三方集合比对 + 未知 code 兜底中文。

## 3. 判定模型

### 3.1 三种结果

| 结果 | 含义 | PUT 行为 |
|---|---|---|
| `EDIT` | 当前状态允许直接 PUT | 进入既有业务校验（父子/精度/版本等），通过后落库 |
| `CHANGE_REQUIRED` | 状态已过早期，必须走变更单 | 409 `CHANGE_REQUIRED`，detail 带 `changeEndpoint` 与 `fields` |
| `LOCKED` | 终态、结构上无此字段或身份字段，任何通道都不能改 | 409 `FIELD_LOCKED`，detail 带 `field`、`reasonCode` |

**`EDIT` 只表示「通道可用」，不保证任意值通过。** `EDIT` 之后仍执行既有业务门，可能返回 400 `INVALID_PARENT` / `INVALID_DAY_PRECISION` / `PARENT_NOT_SCHEDULED` 或 409 `RELEASE_LOCKED`。门禁的一致性断言只要求 `EDIT` 字段**不因可编辑性**被拒（即不返回 `CHANGE_REQUIRED` / `FIELD_LOCKED`），业务错误不算违背矩阵。

### 3.2 规范字段与顺序

11 个字段，数组与错误 detail 一律按此顺序输出：

`title` → `description` → `priority` → `expectedDeliveryDate` → `estimateDays` → `parentId` → `releaseId` → `domainIds` → `sonarProjectKey` → `ownerUserId` → `type`

> 需求文中的 `expectDate` 即接口字段 `expectedDeliveryDate`；`type` 只经 `UpdateRequest` 的 `extra` 传入，创建后不可改。
> 未列入矩阵的字段（如 `actualDays`）保持现状：不参与本矩阵，也不出现在三个数组中。

### 3.3 判定优先级（自上而下，命中即返回）

1. `field == type` → `LOCKED` / `IMMUTABLE`
2. 该类型的状态为终态（BUS `CLOSED`、PDR `ACCEPTED_CLOSED`、RD `DONE`）→ `LOCKED` / `TERMINAL`（`type` 仍按第 1 条）
3. `field == parentId && type == BUS` → `LOCKED` / `NO_PARENT`
4. `field == estimateDays && type != RD` → `LOCKED` / `PARENT_ROLLUP`
5. `field == ownerUserId` → `LOCKED` / `ASSIGN_FLOW`
6. `field ∈ {domainIds, sonarProjectKey}` → `EDIT` / `OPEN_STATE`
7. 状态属于该类型的早期态 → `EDIT` / `EARLY_STAGE`
8. 其余 → `CHANGE_REQUIRED` / `CHANGE_REQUIRED`

早期态：BUS `SUBMITTED` / `APPROVING` / `PENDING_REVIEW`；PDR `PENDING_ANALYSIS`；RD `TODO`。
未知类型或未知状态 → `LOCKED` / `UNKNOWN_STATUS`（fail-closed，绝不静默放行；单测遍历 `StateTables` 保证不漏配）。

### 3.4 矩阵

图例：`EDIT`=可直改；`变更`=`CHANGE_REQUIRED`；`锁·汇总`=`LOCKED/PARENT_ROLLUP`；`锁·无父`=`NO_PARENT`；`锁·指派`=`ASSIGN_FLOW`；`锁·不可变`=`IMMUTABLE`；`锁·终态`=`TERMINAL`；`EDIT·开放`=`EDIT/OPEN_STATE`。

| 类型 | 状态 | 内容四字段¹ | 人天 | 父需求 | 版本 | 域/技术² | 负责人 | 类型 |
|---|---|---|---|---|---|---|---|---|
| BUS | SUBMITTED / APPROVING / PENDING_REVIEW | EDIT | 锁·汇总 | 锁·无父 | EDIT | EDIT·开放 | 锁·指派 | 锁·不可变 |
| BUS | POOLED | 变更 | 锁·汇总 | 锁·无父 | 变更 | EDIT·开放 | 锁·指派 | 锁·不可变 |
| BUS | CLOSED | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·不可变 |
| PDR | PENDING_ANALYSIS | EDIT | 锁·汇总 | EDIT | EDIT | EDIT·开放 | 锁·指派 | 锁·不可变 |
| PDR | SCHEDULED / IN_DEV / DELIVERED | 变更 | 锁·汇总 | 变更 | 变更 | EDIT·开放 | 锁·指派 | 锁·不可变 |
| PDR | ACCEPTED_CLOSED | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·不可变 |
| RD | TODO | EDIT | EDIT | EDIT | EDIT | EDIT·开放 | 锁·指派 | 锁·不可变 |
| RD | IN_DEV / PENDING_TEST / TEST_PASSED / MERGED | 变更 | 变更 | 变更 | 变更 | EDIT·开放 | 锁·指派 | 锁·不可变 |
| RD | DONE | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·终态 | 锁·不可变 |

¹ 内容四字段 = `title`、`description`、`priority`、`expectedDeliveryDate`。
² 域/技术 = `domainIds`、`sonarProjectKey`，不受状态门限制，但终态仍锁。

### 3.5 reasonCode

| code | 用于 | 前端文案（`editability.ts` 唯一来源） |
|---|---|---|
| `EARLY_STAGE` | EDIT | 当前处于早期状态，可直接修改 |
| `OPEN_STATE` | EDIT | 该字段不受状态限制，可直接修改 |
| `CHANGE_REQUIRED` | CHANGE_REQUIRED | 当前状态不能直接修改，需发起变更单 |
| `TERMINAL` | LOCKED | 需求已结束，字段只读 |
| `PARENT_ROLLUP` | LOCKED | 父需求人天由子需求汇总，不可手工填写 |
| `NO_PARENT` | LOCKED | 业务需求为顶层，不能设置父需求 |
| `ASSIGN_FLOW` | LOCKED | 负责人请通过「指派」操作变更 |
| `IMMUTABLE` | LOCKED | 需求类型创建后不可修改 |
| `UNKNOWN_STATUS` | LOCKED | 状态未识别，字段暂不可编辑 |

`NOT_OWNER` 预留给 §11 Q1 的「仅负责人可直改」方案，本轮不产生该 code。

## 4. 接口契约

### 4.1 详情：三数组

`GET /api/req/requirements/{id}` 的 `Detail` 顶层新增三个数组，元素固定 `{field, reasonCode}`：

```json
{
  "view": { "id": 123, "code": "REQ-123", "type": "BUS", "status": "POOLED" },
  "children": [],
  "allowedActions": ["close"],
  "permissions": {
    "changeRequest": true,
    "changeApply": false,
    "editableFields": ["domainIds", "sonarProjectKey"]
  },
  "editableFields": [
    { "field": "domainIds", "reasonCode": "OPEN_STATE" },
    { "field": "sonarProjectKey", "reasonCode": "OPEN_STATE" }
  ],
  "changeRequiredFields": [
    { "field": "title", "reasonCode": "CHANGE_REQUIRED" },
    { "field": "description", "reasonCode": "CHANGE_REQUIRED" },
    { "field": "priority", "reasonCode": "CHANGE_REQUIRED" },
    { "field": "expectedDeliveryDate", "reasonCode": "CHANGE_REQUIRED" },
    { "field": "releaseId", "reasonCode": "CHANGE_REQUIRED" }
  ],
  "lockedFields": [
    { "field": "estimateDays", "reasonCode": "PARENT_ROLLUP" },
    { "field": "parentId", "reasonCode": "NO_PARENT" },
    { "field": "ownerUserId", "reasonCode": "ASSIGN_FLOW" },
    { "field": "type", "reasonCode": "IMMUTABLE" }
  ],
  "estimate": null
}
```

不变量：

- 三个数组**互斥**，并集恰为 §3.2 的 11 个字段，每个字段出现且只出现一次；
- 每个数组内部按 §3.2 规范顺序；同一数据两次 GET 顺序一致；
- 旧 `permissions.editableFields` 保留为 `editableFields[].field` 的同源投影（`string[]`），标注 deprecated，**前端不得再读取**；保留只为不打断外部旧脚本；
- 数组缺失（旧服务端/异常）时前端按 fail-closed 处理：抽屉只读并提示「未获取到可编辑性规则」，不得自行推断；
- 越权/跨租户仍按 r40 口径 403/404，不泄露存在性；矩阵是纯静态计算，不新增 DB 查询。

### 4.2 PUT：两类 409

`PUT /api/req/requirements/{id}`（PATCH 同）只对**真实变化**的字段判定（值与当前相同、字段缺省都不算变化）：

```json
// 409 CHANGE_REQUIRED（沿用 r40，message 不变）
{
  "code": "CHANGE_REQUIRED",
  "message": "该字段需通过变更单修改",
  "detail": {
    "changeEndpoint": "/api/req/requirements/123/changes",
    "fields": ["title", "priority"]
  }
}
```

```json
// 409 FIELD_LOCKED（本轮新增）
{
  "code": "FIELD_LOCKED",
  "message": "该字段在当前状态不可修改",
  "detail": {
    "field": "type",
    "reasonCode": "IMMUTABLE",
    "fields": ["type"]
  }
}
```

判定顺序：可见性/权限 → `status` 专用校验（400 `STATUS_NOT_WRITABLE`，不变）→ **矩阵**（LOCKED 优先，其次 CHANGE_REQUIRED）→ 既有业务校验 → 落库。一次请求只报一类；`detail.field` 为规范序首个被锁字段，`detail.fields` 列出全部同类字段。

### 4.3 变更列表：目标字段

`GET /api/req/requirements/{id}/changes` 行对象新增 `fields: string[]`：由该单 `before`/`after` 快照的真实变化字段得出，按 §3.2 规范序（仅限 r40 七字段）。其余字段与分页、越权口径不变；快照缺失的历史行返回 `[]` 并显示「—」，不得报错。

## 5. 用户故事

- **US-1 看规则**：作为需求提出人，我打开详情就知道哪些字段现在能改、哪些要发起变更、哪些永远不能改，不用靠试错。
  - 任一状态详情返回三数组且互斥并集为 11；
  - 非 EDIT 字段旁有锁定/需变更标记与悬停原因，值仍然可见；
  - 无 EDIT 字段时「编辑」按钮禁用并给出原因。
- **US-2 改受控字段**：作为评审人，我直接改一个受控字段时，系统明确告诉我走变更单，并一键预填。
  - PUT 受控字段返回 409 `CHANGE_REQUIRED`，`detail.fields` 含我改的全部字段；
  - 点击提示打开抽屉并预填这些字段，提交后需求字段不变（r40 口径）。
- **US-3 撞到锁**：作为任何用户，我改终态/身份字段时得到的是「为什么不能改」，而不是一句泛化报错。
  - 409 `FIELD_LOCKED` 的 `reasonCode` 对应到具体中文原因；
  - 页面不提供「发起变更」这类死路入口。
- **US-4 看变更记录**：作为 PM，我在详情就能回答「这需求改过什么、谁提的、批了没有、什么时候生效」。
  - 列表行内直接可见编号/状态/类型/目标字段/提出人/审批结果/时间；
  - 默认展示全部状态；点行进入变更详情，审批进度可见。
- **US-5 验证一致性**：作为开发/QA，我有一条门禁证明矩阵、接口、页面三者一致，且它不会「永远绿」。
  - 三类型 × 三组对照、两类 409、逐字段一致性、变更列表联动全绿；
  - `EDITABILITY_MUTATE=always-edit` 时脚本必须红。

## 6. 验收标准

> 全部可量化；「验证」指向 `editability-verify` 的段或既有门禁。AC 编号仅在本任务夹内有效。

- **AC-1（矩阵单点）**：`platform-contract` 新增矩阵类（建议 `RequirementEditability`），输入 `(type, status, field)` 输出 `(decision, reasonCode)`，覆盖 §3.4 全表，纯静态、无 Spring/DB 依赖；`req-service` 的 `RequirementChangeFields` 不再持有早期态表，只委托矩阵；`RequirementService`/`RequirementChangeGuard` 的 update 路径不再出现按状态判断字段的 `if`。静态断言：早期态表只存在于 contract；`req-service` 内无第二份 status→field 表。验证：S1 + 单测。
- **AC-2（三类型三组对照）**：BUS `SUBMITTED→EDIT`、`POOLED→CHANGE_REQUIRED`、`CLOSED→LOCKED/TERMINAL`；PDR `PENDING_ANALYSIS→EDIT`、`SCHEDULED/IN_DEV/DELIVERED→CHANGE_REQUIRED`、`ACCEPTED_CLOSED→LOCKED`；RD `TODO→EDIT`、`IN_DEV/PENDING_TEST/TEST_PASSED/MERGED→CHANGE_REQUIRED`、`DONE→LOCKED`，均以 `title` 为被测字段。并遍历 `StateTables` 全部状态，断言每格都有明确结果（新增状态漏配即红）。验证：S2 9 条 + 参数化单测。
- **AC-3（人天）**：BUS/PDR 非终态 `estimateDays→LOCKED/PARENT_ROLLUP`、终态 `LOCKED/TERMINAL`；RD `TODO→EDIT`、中间态 `CHANGE_REQUIRED`、`DONE→LOCKED`。PUT：BUS/PDR 改人天 → 409 `FIELD_LOCKED`（reasonCode=`PARENT_ROLLUP`，替代原 400，见「取代」）；RD `TODO` → 200；RD `IN_DEV` → 409 `CHANGE_REQUIRED`。前端删除 `children.length` 判断。验证：S3 + S4。
- **AC-4（结构/身份字段）**：`type` 全状态 `LOCKED/IMMUTABLE`，PUT 改 type → 409 `FIELD_LOCKED`（此前被静默忽略）；`ownerUserId` 非终态 `LOCKED/ASSIGN_FLOW`、终态 `TERMINAL`，PUT 改负责人 → 409 `FIELD_LOCKED`（指派/转派入口不受影响）；`domainIds`/`sonarProjectKey` 非终态 `EDIT/OPEN_STATE`、终态 `TERMINAL`，PUT 仍按现有逻辑落库；`parentId` 在 BUS 非终态 `LOCKED/NO_PARENT`，PDR/RD 早期 `EDIT`、受控态 `CHANGE_REQUIRED`、终态 `TERMINAL`。验证：S3 + S5。
- **AC-5（Detail 三数组）**：`Detail` 顶层返回 `editableFields`、`changeRequiredFields`、`lockedFields`，元素 `{field, reasonCode}`，满足 §4.1 的互斥/并集/顺序不变量；旧 `permissions.editableFields` 作为同源投影保留并标注 deprecated，前端不再读取；越权/跨租户口径不变；不新增 DB 查询。验证：S5（partition + 顺序 + 两次 GET 一致）。
- **AC-6（409 CHANGE_REQUIRED）**：PUT 命中 `CHANGE_REQUIRED` 且值变化 → 409 `CHANGE_REQUIRED`，`detail` 保留 `{changeEndpoint, fields[]}`，`fields` 为全部被拦字段（规范序），message 不变；同值/缺省不拦。验证：S4。
- **AC-7（409 FIELD_LOCKED）**：命中 `LOCKED` → 409 `FIELD_LOCKED`，`detail` 为 `{field, reasonCode, fields[]}`；混合请求先报 LOCKED、再报 CHANGE_REQUIRED，一次只报一类；终态有变化 → 409/`TERMINAL`（不再 400），终态同值 no-op → 200 且不写库、不写审计；`requireWritable` 方法本身与其他调用方（变更单创建/apply、MR 同步、评估）仍返回 400，语义不变。验证：S4（含终态、混合、同值）。
- **AC-8（既有门不放宽）**：矩阵之后仍执行父子类型/环、父状态门、0.5 天精度、版本可见、`RELEASE_LOCKED`、终态流转限制；变更单创建/审批/apply 全链路与 r40 一致（含锁定/发布版本仍可 apply 并写 `CHANGE_AFTER_RELEASE_LOCK`）；本轮只允许 AC-3/AC-7 声明的两类错误码收敛。验证：`change-verify` 42 条 + 既有 negative 全绿。
- **AC-9（前端按数组渲染）**：`RequirementEditDrawer` 只使用三数组与 7 个 UI 字段（内容四字段 + 人天 + 版本 + 父需求），移除 `children.length` 与任何状态判断；非 EDIT 控件 `disabled` 并显示/悬停 reasonCode 文案；CHANGE_REQUIRED 组内提供「发起变更」并预填该组字段；详情页字段行在非 EDIT 时显示锁标记 + 原因，值始终可见；「编辑」按钮仅当 7 个 UI 字段中存在 EDIT 时可用，否则禁用并给原因；「发起变更」仍按 `permissions.changeRequest`。数组缺失时 fail-closed。`domainIds`/`sonarProjectKey`/`ownerUserId`/`type` 在详情页仅只读展示，不新增输入控件。验证：组件断言/E2E + S1 静态（无 `children` 判断、无状态表）。
- **AC-10（文案单点）**：新增 `web/src/views/req/editability.ts`，导出 `ReasonCode` 联合、`FIELD_REASON_TEXT`（§3.5 全部 code）与 `reasonText(code)`（未知 code 兜底中文，不得空白/英文）以及 `editabilityOf(detail)`；组件内不得出现这些原因中文，不得用 `reasonCode + 后缀` 拼接。静态断言：Java 常量集合 = TS 联合 = 文案表键集合；`editability.ts` 之外的原因中文出现次数为 0。验证：S1 + 单测。
- **AC-11（409 引导）**：`CHANGE_REQUIRED` → 横幅/提示 + 一键打开抽屉预填 `detail.fields`；`FIELD_LOCKED` → 提示含 reasonText，**不显示**「发起变更」入口；变更单创建成功后详情刷新，字段仍不变（r40 AC-5）。验证：E2E + 手工。
- **AC-12（变更记录）**：列表接口行新增 `fields: string[]`（§4.3）；「变更」区行内直接可见：编号、状态、类型、目标字段、提出人、审批结果（由单据状态映射：`PENDING→审批中`、`APPROVED/APPLIED→通过`、`REJECTED→驳回`、`WITHDRAWN→撤回`）、时间（`appliedAt` 优先，未应用显示 `createdAt` 并标「创建于」）；原因与影响摘要可悬停或次行查看；默认无状态过滤，分页 20；至少一条联动：新建 → 列表出现 → 点开变更详情 → 审批进度可见。验证：S6 + 组件断言。
- **AC-13（从详情发起）**：详情头部「发起变更」（r40 保留）与每个 CHANGE_REQUIRED 字段行的「发起变更」都打开同一抽屉，预填被拦字段与对应变更类型（内容→CONTENT、人天→DAYS、父需求→PARENT、版本→RELEASE）；无权限时禁用并给原因。验证：E2E。
- **AC-14（门禁）**：新增 `scripts/editability-verify.sh`，声明 `EDITABILITY_TOTAL=36`（≥32），6 段：S1 静态契约 6、S2 三组对照 9、S3 字段特例 7、S4 PUT 错误码 5、S5 响应一致性 5、S6 变更记录与负向 4；`ck` 调用数与 TOTAL 一致，`PASS==TOTAL` 才算过；支持 `--static-only`（免栈）与 `--twice`（两次一致）；fixture 使用 gate 夹具并在结束时 `TAG` 残留为 0；缺栈时 `die` 并给出修复命令；接入 Makefile 的 `.PHONY` 与 `test` 聚合。验证：脚本自身 + `make test`。
- **AC-15（负向自检）**：`EDITABILITY_MUTATE=always-edit` 运行时，脚本把矩阵期望替换为恒 EDIT 并执行同一批逐字段 PUT 断言，必须失败（退出码非 0），失败信息点名字段；该开关只影响脚本期望，**不改产品代码**（静态断言产品代码中无 `MUTATE` 字样）。说明：门禁内不重编译产品源码做真变异（代价不可接受），此开关验证的是「断言确实在比对真实 PUT 结果」。
- **AC-16（回归基线）**：`platform-verify ≥199`、`platform-test-negative ≥132`（需求文本写 130，r42 实测 132，按只增不减取 132）、`access-verify ≥206`、`rbac-verify ≥113`、`release-flow-verify 44`、`gate-order-verify ≥21`、`change-verify 42`、`platform-demo-reset 80` 全绿并连跑两次；lint 与 unit 全绿且数量不低于 r42 实测；新门禁 36 条计入基线并写入 `docs/teamflow/memory.md`。
- **AC-17（文档）**：`docs/teamflow/memory.md` 更新同主题基线行（新增 `editability-verify` 36 条与 8 项门禁数字），新增一条「需求可编辑性矩阵」约定（矩阵位置、三数组、两类 409），替换而非追加；`docs/teamflow/REQUIREMENT-FLOW.md` 增加「可编辑性判定」小节并标注 r40 变更入口不变；不新建版本化文档，不改 AGENTS.md 托管区之外的内容。验证：diff 审阅。
- **AC-18（改动面与卫生）**：只改 `platform-contract/`、`req-service/`、`web/src/views/req/`、`web/src/components/req/RequirementEditDrawer.vue`、`web/src/api/types.ts`（仅类型）、`scripts/`、`docs/`、`Makefile`（仅接入）；不改 deliver 业务语义；`pom.xml` 与 `web/package.json` diff 为空；不新增依赖与 DB 迁移；提交信息含 runId `tf-mwtzd1oa-p2j7xu` 与任务夹 `docs/teamflow/20261124-r43-req-field-editability/`；`.gitignore` 复核结论写入提交说明。验证：`git diff --stat` + 脚本静态断言。

## 7. 交互流程（摘要）

1. 打开详情：前端取 `Detail` 三数组，渲染字段值；非 EDIT 字段显示锁/变更标记与悬停原因；「编辑」按钮按「7 个 UI 字段是否有 EDIT」启用。
2. 点「编辑」：抽屉按数组渲染——EDIT 可输入；CHANGE_REQUIRED 禁用并给「发起变更」（预填该组）；LOCKED 禁用并给具体原因；数组缺失则整体只读并提示。
3. 旧入口/直接 PUT：`CHANGE_REQUIRED` → 横幅 + 一键预填抽屉；`FIELD_LOCKED` → 展示原因，不提供变更入口。
4. 发起变更：沿用 r40 四步抽屉与影响预览；创建成功后刷新详情（字段不变，状态/角标更新）。
5. 「变更」区：默认全部状态，行内看到 7 项信息；点行进入变更详情，审批进度与 APPLY 仍按 r40。
6. 字段行「发起变更」与头部入口共用同一抽屉，只是预填字段不同。

## 8. 优先级

| 优先级 | AC | 说明 |
|---|---|---|
| P0 | AC-1 ~ AC-16、AC-18 | 矩阵、契约、错误码、前端、门禁与回归；任一缺失本轮不验收 |
| P1 | AC-17 | 文档与基线记忆；可与 P0 同批提交，但不能省略 |
| P2（未纳入） | 字段级审计 | 需求标注「时间允许可选做」；本轮不做，见 §2.2。若后续要做，需另立 AC 并保持与 apply 的 `detail_json` 同格式 |

## 9. 依赖与风险

**依赖**：r40 变更单与 apply 事务（不改）；`platform-contract` 的 `StateTables`（终态判定唯一来源）；既有父子/排期/人天门；前端 Vue 3 + Element Plus 既有抽屉与 409 处理；真栈门禁（`platform-up` + `platform-seed` + `platform-demo-reset`）。

**风险与缓解**：

1. **错误码迁移影响既有断言**：终态 400→409、父人天 400→409 会让旧的负向断言变红。缓解：只改 PUT 路径（`requireWritable` 与其他调用方保持 400），在「取代」中逐条声明，QA 同步断言但总数不减；`change-verify` 与 negative 必须全绿。
2. **矩阵与业务门顺序错位**：矩阵先行会让本应 400 的业务错误变成 409。缓解：`EDIT` 只做通道判定，业务校验保持原位置与原错误码；门禁逐字段断言「EDIT 不被两类 409 拦」，同时保留业务 400 用例。
3. **Detail 顶层新增字段破坏构造器/序列化**：`Detail` 是 record，新增组件在编译期暴露所有组装点；旧客户端忽略未知字段。上线顺序：后端先行，前端随后。
4. **前端仍残留判断**：抽屉最易复发（`children.length`、`status` 比较）。缓解：S1 静态断言 + code review 清单。
5. **改动面越界**：抽屉与 `api/types.ts` 不在需求所列目录内，但不改无法满足 AC-9；已在 §11 Q5 显式列为待确认项。
6. **变更列表 `fields` 依赖快照**：历史行 `after` 为空时返回 `[]`，页面显示「—」，不报错；新建行必须非空（S6 断言）。
7. **终态 fixture 造数**：为控制时长，终态用 SQL 置状态（仅用于矩阵读取与 PUT 拒绝），不用于流转断言；在脚本注释中写明。
8. **门禁时长**：36 条含多类型审批链，复用 gate 夹具与 `--static-only` 拆分；若超时，优先复用已有需求而非新建。

## 10. 里程碑

| 里程碑 | 内容 | 覆盖 AC | 出口条件 |
|---|---|---|---|
| M1 契约与矩阵 | contract 矩阵、优先级、单测、Detail 三数组 | AC-1 ~ AC-5 | 单测遍历全部状态；`--static-only` 全绿 |
| M2 服务端收口 | PUT 两类 409、终态/父人天迁移、变更 list `fields` | AC-6 ~ AC-8 | negative + change-verify 全绿，无副作用 |
| M3 前端 | 抽屉/详情/文案单点/409 引导/变更记录 | AC-9 ~ AC-13 | 浏览器逐状态走查 + 截图 |
| M4 门禁与回归 | editability-verify 36 条、8 项回归两次、文档 | AC-14 ~ AC-18 | `make test` 全绿两次，memory 基线更新 |

字段级审计（P2）不排期；若本轮提前完成 M4，可作为独立小项另立 AC，不阻塞验收。

## 11. 假设与待澄清

> 本节 5 项均为**取舍**（taste），没有未决事实项。已核实的事实：旧 `permissions.editableFields` 仅被 `RequirementEditDrawer.vue` 与 `views/req/change.ts` 消费；`ownerUserId` 在 PUT 中无任何一方依赖（仅指派/交付入口写入）；BUS `CLOSED`、PDR `ACCEPTED_CLOSED`、RD `DONE` 当前无 `reopen` 迁移；仓库内没有 deliver 侧读取需求可编辑性的代码。

- **Q1 直改权限是否区分「本人/他人」**：假设本轮不区分——有 `req.pool` 页面权限且可见即可直改，与现状一致。原因：需求只要求「状态 × 字段」矩阵；按人判定会让同一状态对不同用户返回不同数组，并引入 `NOT_OWNER` 分支与多账号门禁用例。若改为「仅负责人可直改」：他人对 EDIT 字段也返回 `LOCKED/NOT_OWNER`，需新增权限矩阵与多账号测试。默认：不区分。kind=taste。
- **Q2 受控态下 `domainIds`/`sonarProjectKey` 是否仍直改**：假设继续直改（与现状一致），不锁进变更单。原因：r40 没有 DOMAIN/SONAR 变更类型，锁死会让这两个字段在入池后完全无入口。若要求锁定：需新增变更类型或明确「入池后不可改」，同时详情页改为只读。默认：继续直改（`OPEN_STATE`）。kind=taste。
- **Q3 变更单「编号」是什么**：假设就是 `#id`（当前无业务单号）。原因：新增 `CHG-YYYYMMDD-NNN` 需要 V17 迁移与发号器，超出本轮改动面与「无 schema 变更」的约束。若要求业务单号：列表字段、深链与详情都要改，且需幂等发号。默认：`#id`。kind=taste。
- **Q4 父需求（BUS/PDR）无子需求时能否手填人天**：假设维持只读（含无子场景），与现状一致。原因：人天来源是子需求汇总，放开无子场景会让汇总口径出现第二个来源。若允许无子时手填：矩阵该格改为 EDIT（有子时仍 `PARENT_ROLLUP`），PUT 与前端提示都要分支。默认：维持只读。kind=taste。
- **Q5 改动面是否纳入抽屉与前端类型文件**：假设允许把 `web/src/components/req/RequirementEditDrawer.vue`（详情页编辑态的唯一实现）与 `web/src/api/types.ts`（`Detail` 类型）纳入改动面。原因：不改这两处无法做到「前端只认数组渲染」，AC-9/AC-11 不成立。若严格限定只改 `web/src/views/req/`：详情页可展示规则，但抽屉仍是旧的全有/全无逻辑，上述 AC 需降级。默认：纳入，且不新增其他目录。kind=taste。

## 12. 工程约束

- **分支**：从最新 main（当前 `eb15750`，含 r42 网关修复）切出 `feat/req-editability-rules` 后实现；当前工作区已在该分支，不要直接在 main 提交。合并回 main 由用户确认。
- **未提交改动**：当前唯一未提交项是 `?? docs/teamflow/20261124-r43-req-field-editability/`，保留、不 stash、不清理；提交时一并纳入。
- **提交**：提交信息必须含 runId `tf-mwtzd1oa-p2j7xu` 与任务夹 `docs/teamflow/20261124-r43-req-field-editability/`；按里程碑可拆多个提交，每个都带 runId。
- **门禁**：提交前 `make test` 全绿（含新 `editability-verify`），`make platform-demo-reset` 连跑两次，`make editability-verify --twice` 两次一致；缺栈失败必须给出「哪一项 + 怎么修」，不得用 `sleep` 掩盖。
- **依赖与迁移**：不新增 Maven/npm 依赖；`pom.xml` 与 `web/package.json` 的 diff 必须为空；无 DB 迁移（本轮不改 schema）。
- **.gitignore 复核**（开发阶段动作，针对本栈逐项确认）：`target/`、`.nacos/`、`.run/`、`.m2/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`*.log`、`.env*`、`.DS_Store`、`.idea/`、`logs/teamflow/` 已覆盖；本轮新增产物（临时 JSON/截图）放 `logs/teamflow/<runId>/` 或 `mktemp`，不入库；若发现缺项补齐并在提交说明中列出。
- **版本库卫生**：不提交 `.run/`、`logs/`、临时脚本、构建产物与演示数据导出；验收证据（如截图）如需入库，放任务夹 `evidence/`。
- **兼容性**：不改 deliver 侧；旧客户端忽略新增字段；错误码迁移仅限 §「取代」两条与 AC-7 的 `FIELD_LOCKED`；`change-verify`、`access-verify`、`rbac-verify` 的既有断言不得减少。
