# 技术方案 · r43 需求字段可编辑性 + 变更记录

> 任务夹：`docs/teamflow/20261124-r43-req-field-editability/`　分支：`feat/req-editability-rules`
> 基线依赖：`docs/teamflow/20261123-r40-req-change-order/`（变更单闭环）、`20261123-r42-gateway-unicode-query/`（回归基线数字）
> 取代声明见 PRD 头部：r40#AC-12 终态口径、r40#AC-11 PUT 侧父人天；历史文件夹不修改。

## 0. 结论摘要

| 层 | 改动 | 为什么这样做 |
|---|---|---|
| `platform-contract` | 新增 `RequirementEditability`：3 类型 × 全状态 × 11 字段，输出 `EDIT`/`CHANGE_REQUIRED`/`LOCKED` + 9 个 reasonCode | 可编辑性是**协议**（前后端、变更单、门禁共用同一张表）。放 contract 才能让 req-service 只委托、门禁只断言一份 |
| `req-service` | `Detail` 顶层加三数组 + 只读字段；PUT 改为「真实变化 → 矩阵 → 两类 409」；变更列表行加 `fields` | 前端不再推断状态，PUT 与详情同源；错误码从「写库前最后一道」提前到「业务校验前」，LOCKED 优先 |
| `web` | 新增 `editability.ts`（文案/标记/解析单点）与 `FieldFlag.vue`；抽屉/概览/变更 Tab 只按数组渲染 | 三个数组是唯一输入；`children.length` 与状态判断全部清零，否则「页面说能改、提交被拒」必然复发 |
| 门禁 | 新增 `scripts/editability-verify.sh` 36 条 6 段，接入 `make test`；8 项既有门禁只增不减 | 矩阵是枚举型契约，必须逐字段对拍「接口数组 vs 真实 PUT」，静态断言无法替代 |

**零**：无 DB 迁移、无新依赖、无 `pom.xml`/`web/package.json` 变更、不改 deliver 侧、不改网关。

## 1. 事实基线（勘察结论，dev 直接照此改）

- `platform-contract/src/main/java/com/example/platform/contract/StateTables.java:1`：`TABLE`/`TERMINAL`/`statuses(type)`/`isTerminal(type,status)` 是状态唯一源；`Status` 值 BUS=`SUBMITTED|APPROVING|PENDING_REVIEW|POOLED|CLOSED`，PDR=`PENDING_ANALYSIS|SCHEDULED|IN_DEV|DELIVERED|ACCEPTED_CLOSED`，RD=`TODO|IN_DEV|PENDING_TEST|TEST_PASSED|MERGED|DONE`。
- `req-service/src/main/java/com/example/req/requirement/RequirementViews.java:42` `record View(...)` **不含** `sonarProjectKey`、`domainIds`；`:68` `record Detail(View view, List<Child> children, List<String> allowedActions, Map<String,Object> permissions, WbsEstimateViews.EstimateView estimate)`，只在 `RequirementService.java:405` 构造一处。
- `RequirementService.java:204 update`：`requireWritable`（终态 400）→ 冻结 `auditSnapshot` + `RequirementChangeFields.snapshot`（7 字段）→ `applyParentChange/applyDaysChange/applySonarKeyChange`（先跑 400 业务校验、就地写 entity）→ 写四字段 → `RequirementChangeGuard.rejectDirectEdit` → `applyReleaseChange` → `save`。
- `RequirementService.java:396 detail`：`requireVisible` + 子需求 + `toView` + `gateFiltered(allowedActions)` + `changePermissions` + `estimateOf`；`changePermissions` 在 `:1020` 左右，`editableFields` 取 `RequirementChangeFields.editableFields`（`:1027`）。
- `RequirementService.java:830 requireWritable`：终态且无 reopen → 400 `VALIDATION_FAILED`「需求已关闭，不可修改」；**其他调用方（变更单创建/apply、MR 同步、评估）保持原样**。
- `RequirementService.java:885 domainsOf(long)`：`domainRelRepository.findByRequirementId` 取多域；`replaceDomains` 的 fallback = `entity.getDomainId()`。
- `RequirementChangeGuard.java:52` 三参 `rejectDirectEdit(entity, current, request)` + `:90 patchOf` 只取 7 字段；**除 Service 与自身测试外无调用方** → 本方案用 `RequirementEditGuard` 取代并删除该类。
- `RequirementChangeFields.java:63 EARLY_STATUSES`（早期态唯一表，req-service 内），`:122 changedFields` 按 `FIELDS` 顺序、`:179 editableFields`（早期返回 7 字段，否则空）。
- `RequirementChangeService.java:189 toRow`：`new Row(id, changeType, status, reason, createdBy, createdAt, appliedAt, assessor.summary(...))`；`RequirementChangeViews.java:28 Row` 无 `fields`。
- `RequirementErrors.java:166 changeRequired` 409 / detail 三键；`:36` 起错误码常量区。
- 前端：`web/src/views/req/platform.ts:101 normalizeRequirementDetail` 摊平 `view` 且只搬 `children/allowedActions/permissions/estimate`；`web/src/api/types.ts` 的 `RequirementDetail` 是扁平声明、`RequirementDetailResponse` 是 `{view,...}` 真实形状；`web/src/api/req.ts:750 ChangeRow` 无 `fields`。
- `RequirementEditDrawer.vue:53 isEditable` 里仍有 `estimateDays && children.length > 0` 判断；`editable` 来自 `permissions.editableFields`。
- `RequirementDetailView.vue:342` 只把 `CHANGE_REQUIRED` 的 `detail.fields` 收进 `actionBanner`；`:596` 「编辑」按钮无禁用条件；`:749` 已接 `ChangeCreateDrawer` 的 `preset-type/preset-fields`。
- `OverviewTab.vue:273` `<dl class="grid">` 只有 8 个值行，`isParent` 驱动两处 `🔒 汇总`（`:301`、`:309`）；无父需求/Sonar 行。
- `ChangeTab.vue:135` 7 列（变更单/状态/类型/原因/影响摘要/申请人/时间），无 `fields`；`web/src/views/req/change.ts` `FIELD_LABELS` 仅 7 字段。
- `scripts/test-all.sh:43` 在 `change-verify` 后串 `access-verify`；`Makefile:115` 已有 `change-verify` 目标与 `.PHONY`。

## 2. 契约层：`RequirementEditability`

新增 `platform-contract/src/main/java/com/example/platform/contract/RequirementEditability.java`（`public final class`，纯静态、无 Spring、无 IO）。

### 2.1 精确签名（dev 逐字实现，其他模块逐字调用）

```java
public final class RequirementEditability {
    public enum Decision { EDIT, CHANGE_REQUIRED, LOCKED }
    public record Rule(String field, Decision decision, String reasonCode) { }

    // 原因码常量（与前端 editability.ts 一一对应）
    public static final String EARLY_STAGE = "EARLY_STAGE";
    public static final String OPEN_STATE = "OPEN_STATE";
    public static final String CHANGE_REQUIRED = "CHANGE_REQUIRED";
    public static final String TERMINAL = "TERMINAL";
    public static final String PARENT_ROLLUP = "PARENT_ROLLUP";
    public static final String NO_PARENT = "NO_PARENT";
    public static final String ASSIGN_FLOW = "ASSIGN_FLOW";
    public static final String IMMUTABLE = "IMMUTABLE";
    public static final String UNKNOWN_STATUS = "UNKNOWN_STATUS";

    /** 规范序，11 个，顺序即输出顺序 */
    public static final List<String> FIELDS;

    public static List<Rule> rules(String type, String status);
    public static Rule rule(String type, String status, String field);
    public static Decision decision(String type, String status, String field);
    public static String reasonCode(String type, String status, String field);
    public static List<String> editableFields(String type, String status);
    public static List<String> changeRequiredFields(String type, String status);
    public static List<String> lockedFields(String type, String status);
    public static boolean earlyStage(String type, String status);
}
```

`FIELDS` 固定为：`title, description, priority, expectedDeliveryDate, estimateDays, parentId, releaseId, domainIds, sonarProjectKey, ownerUserId, type`。

### 2.2 判定（命中即返回，顺序不可换）

```
rule(type, status, field):
  1. 未知 type 或 status 不在 StateTables.statuses(type) → LOCKED / UNKNOWN_STATUS   // fail-closed 最先
  2. field == "type"                                     → LOCKED / IMMUTABLE
  3. StateTables.isTerminal(type, status)                  → LOCKED / TERMINAL
  4. field == "parentId" && type == "BUS"                  → LOCKED / NO_PARENT
  5. field == "estimateDays" && type != "RD"               → LOCKED / PARENT_ROLLUP
  6. field == "ownerUserId"                                → LOCKED / ASSIGN_FLOW
  7. field ∈ {"domainIds","sonarProjectKey"}               → EDIT   / OPEN_STATE
  8. earlyStage(type, status)                              → EDIT   / EARLY_STAGE
  9. 其余                                                   → CHANGE_REQUIRED / CHANGE_REQUIRED
```

- `earlyStage` 私有集合与 r40 完全一致（BUS 3 态 / PDR `PENDING_ANALYSIS` / RD `TODO`），**只存在于此文件**；`StateTables` 不新增状态。
- `rule(..., field)` 收到不在 `FIELDS` 的字段名 → `LOCKED/UNKNOWN_STATUS`（不抛异常，避免 PUT 因未知键 500）。
- `rules()` 逐 `FIELDS` 生成，返回 `List.copyOf`（不可变）。

### 2.3 不变量（门禁与单测的断言目标）

1. **完备**：对 `StateTables.statuses(type)` 的每个状态、每个字段都返回非 null。
2. **分区**：`editableFields ∪ changeRequiredFields ∪ lockedFields = FIELDS`，两两不交。
3. **顺序**：三个列表都是 `FIELDS` 的子序列（不重排、不排序）。
4. **幂等**：同一 (type,status) 任意次调用结果逐字段相等（无时间、无随机、无上下文）。
5. **fail-closed**：未知 type/status → 11 个 `LOCKED/UNKNOWN_STATUS`（绝不静默放行）。

### 2.4 委托与去重

- `RequirementChangeFields.EARLY_STATUSES` **删除**；`editableFields(type,status)` 改为
  `RequirementEditability.editableFields(type,status).stream().filter(FIELDS::contains).toList()`（保留 r40 七字段投影，供 `permissions.editableFields` 旧键）；`earlyStage` 直接委托。
- 除 contract 外，**任何文件不得出现状态→字段的判定分支**（门禁 S1-3 静态扫描）。

## 3. 服务端：`req-service`

### 3.1 详情契约（`Detail` 顶层新增）

```java
// RequirementViews.java
public record FieldRule(String field, String reasonCode) { }

public record Detail(View view, List<Child> children, List<String> allowedActions,
                     Map<String, Object> permissions,
                     List<FieldRule> editableFields,
                     List<FieldRule> changeRequiredFields,
                     List<FieldRule> lockedFields,
                     List<Long> domainIds,          // 只读展示（多域）
                     String sonarProjectKey,        // 只读展示，为空给 null
                     WbsEstimateViews.EstimateView estimate) { }
```

- `domainIds/sonarProjectKey` 放 `Detail` 而不是 `View`：`toView` 同时服务列表（`RequirementPoolQuery`），放 `View` 会引入逐行 `domainsOf` 的 N+1。
- 组装（`detail()` 内一次完成，**不新增第二个端点**）：

```java
List<RequirementEditability.Rule> rules =
        RequirementEditability.rules(entity.getType(), entity.getStatus());
return new Detail(toView(entity, rollup), childViews, gateFiltered(...), changePermissions(entity),
        project(rules, RequirementEditability.Decision.EDIT),
        project(rules, RequirementEditability.Decision.CHANGE_REQUIRED),
        project(rules, RequirementEditability.Decision.LOCKED),
        domainsOf(id), entity.getSonarProjectKey(), estimateOf(entity));
```

`project` 为 `RequirementService` 私有静态：过滤后 `map(r -> new FieldRule(r.field(), r.reasonCode()))`，顺序天然是 `FIELDS` 序。
- `permissions.editableFields` 保留（deprecated），值 = 矩阵 EDIT ∩ r40 七字段（前端不再读，仅供旧客户端/旧断言）。
- 序列化：`Detail` 是三数组的唯一出口；`permissions` 仍是 `Map`，不新增键。

### 3.2 PUT 新流程（`RequirementService.update`，`:204` 整体替换顺序）

```java
@Transactional
public View update(long id, RequirementDtos.UpdateRequest req) {
    DataScope scope = TenantContext.require();
    rejectStatusInBody(req.extra());
    RequirementEntity entity = requireVisible(id);                 // ① 不再先 requireWritable
    Long oldParentId = entity.getParentId();
    Map<String, Object> before = auditSnapshot(entity,
            String.valueOf(entity.getExpectedDeliveryDate()));
    List<Long> currentDomains = domainsOf(id);
    Map<String, Object> guardBefore = RequirementEditGuard.snapshot(entity, currentDomains);
    List<String> changed = RequirementEditGuard.changed(guardBefore,
            RequirementEditGuard.patch(req, currentDomains));

    if (changed.isEmpty()) {                                       // ② 无真实变化：200 / 零写 / 零审计
        if (RequirementEditGuard.carriesActualDays(req)) {
            requireWritable(id);                                   // actualDays 不在矩阵，保持 r40 的 400
        }
        return toView(entity, null);
    }
    RequirementEditGuard.reject(id, entity.getType(), entity.getStatus(), changed); // ③ 两类 409

    applyParentChange(entity, req.extra());                        // ④ 以下与 r40 完全一致
    applyDaysChange(entity, req.extra());
    applySonarKeyChange(entity, req.extra());
    // title/description/priority/expectedDeliveryDate 原样
    applyReleaseChange(entity, req.extra(), scope.userId());
    entity.setUpdatedBy(scope.userId());
    repository.save(entity);
    if (req.domainIds != null) replaceDomains(entity.getId(), scope.tenantId(),
            req.domainIds, entity.getDomainId());
    // 父汇总、审计 UPDATE 原样（审计 before 已提前冻结）
    return toView(entity, null);
}
```

要点：
- **顺序即契约**：**可见性 → 矩阵（LOCKED 优先）→ 既有业务校验 → 落库**。这比 r40「业务校验先于收口」提前了一格，是 AC-7「终态 409 而非 400」与「混合请求只报 LOCKED」的必然结果；已在 PRD §9 风险 2 记录。
- **no-op 短路**只由「11 字段全部同值」触发；`status` 入体仍 400；`actualDays` 在终态仍 400（先 `requireWritable`），避免把既有可写字段静默吞掉。
- `requireWritable` 方法体与其余调用方**一行不改**（AC-7 尾句）。
- `applyReleaseChange` 仍在 `save` 前：`RELEASE_LOCKED` 的 409 在矩阵通过后照旧出现。

### 3.3 新类：`RequirementEditGuard`（取代 `RequirementChangeGuard`）

`req-service/src/main/java/com/example/req/requirement/RequirementEditGuard.java`

```java
public final class RequirementEditGuard {
    public static final String CHANGE_ENDPOINT_TEMPLATE = "/api/req/requirements/%d/changes";

    /** 11 字段只读快照，顺序 = RequirementEditability.FIELDS；domainIds 已做「空 → 主域」归一 */
    public static Map<String, Object> snapshot(RequirementEntity entity, List<Long> domainIds);

    /** 请求实际携带的 11 字段（缺省键不放入；domainIds 用同一「空 → 主域」归一） */
    public static Map<String, Object> patch(RequirementDtos.UpdateRequest request,
                                            List<Long> currentDomains);

    /** 归一化后逐字段比较，返回按规范序排列的真实变化字段 */
    public static List<String> changed(Map<String, Object> before, Map<String, Object> patch);

    /** LOCKED 优先抛 409 FIELD_LOCKED；否则 CHANGE_REQUIRED 抛 409；两类不混报 */
    public static void reject(long requirementId, String type, String status,
                              List<String> changedFields);

    /** actualDays 不属于矩阵：no-op 分支据此决定是否保留旧的 requireWritable 400 */
    public static boolean carriesActualDays(RequirementDtos.UpdateRequest request);
}
```

实现细则：
- `patch`：`title/description/priority/expectedDeliveryDate` 非 null 才放入；`domainIds` 非 null 才放入；`extra` 里取 `estimateDays/parentId/releaseId/type/ownerUserId/sonarProjectKey`（`containsKey` 语义，`null` 也要放入，供「清空」）。
- `changed` 归一化：
  - `estimateDays`：`RequirementChangeFields.normalize` 后 `BigDecimal.compareTo == 0`；
  - `domainIds`：`distinct().sorted()` 后按列表相等（空列表按主域归一，避免「清空 = 恢复主域」误报）；
  - `parentId/releaseId/ownerUserId`：`Long` 等值，null 参与比较；
  - `sonarProjectKey`：`trim`，空串≡null；
  - 其余：`asText` 文本等值。
- `reject`：按 `changed` 顺序分别收集 `LOCKED` 与 `CHANGE_REQUIRED`；`locked` 非空即抛
  `RequirementErrors.fieldLocked(id, locked.get(0), reasonCodeOf(locked.get(0)), locked)`，
  否则 `controlled` 非空抛 `changeRequired(id, controlled, endpoint)`。
- **删除** `change/RequirementChangeGuard.java` 与其测试；`CHANGE_ENDPOINT_TEMPLATE` 迁到本类（唯一常量）。

### 3.4 错误码与 detail（`RequirementErrors`）

```java
public static final String FIELD_LOCKED = "FIELD_LOCKED";

public static ApiException fieldLocked(long requirementId, String field,
                                       String reasonCode, List<String> fields) {
    return new ApiException(409, FIELD_LOCKED, "该字段在当前状态不可修改",
            Map.of("requirementId", requirementId, "field", field,
                   "reasonCode", reasonCode,
                   "fields", fields == null ? List.of() : fields));
}
```

- `detail.field` = 规范序首个被锁字段；`detail.fields` = 全部同类字段（规范序）；`CHANGE_REQUIRED` 的 message/detail 完全不变。
- 一次请求只报一类：混合时 `fields` 只含 LOCKED 字段，绝不出现两类混装。

### 3.5 变更列表行 `fields`

`RequirementChangeViews.Row` 增加 `List<String> fields`（放在 `appliedAt` 之后、`impactSummary` 之前）；`RequirementChangeService.toRow`：

```java
Map<String, Object> beforeMap = json.readMap(row.getBeforeJson());
Map<String, Object> afterMap = json.readMap(row.getAfterJson());
List<String> fields = (beforeMap == null || beforeMap.isEmpty()
        || afterMap == null || afterMap.isEmpty())
        ? List.of()
        : RequirementChangeFields.changedFields(beforeMap, afterMap);
```

- 只覆盖 r40 七字段（`changedFields` 已按 `FIELDS` 序）；历史空快照 → `[]`，接口不报错。
- 不新增查询：`before_json/after_json` 已在行上。

### 3.6 不放宽清单（AC-8 自查）

父子类型/祖先环（`applyParentChange`）、父状态门、0.5 天精度（`checkDays`）、版本可见、`RELEASE_LOCKED`、终态流转（`transition`）、变更单创建/预览/审批/apply 全链路、`change-verify` 42 条：**均不修改**。矩阵只决定「通道」，通过后仍走原 400/409 业务门。

## 4. 前端

### 4.1 类型与归一化

`web/src/api/types.ts`：

```ts
export interface RequirementFieldRule { field: string; reasonCode: string }

// RequirementDetail 追加（均可缺失 → fail-closed）
editableFields?: RequirementFieldRule[] | null
changeRequiredFields?: RequirementFieldRule[] | null
lockedFields?: RequirementFieldRule[] | null
domainIds?: number[] | null
sonarProjectKey?: string | null

// RequirementDetailResponse 追加同名五项（顶层，与 view 平级）
```

`web/src/views/req/platform.ts:101 normalizeRequirementDetail`：
- 包装分支：`editableFields: rulesOf(record.editableFields ?? view.editableFields)`，其余同构；`domainIds`、`sonarProjectKey` 同法搬运；
- 扁平分支：原样保留；
- `rulesOf` 只做「是数组就透传，否则 null」，**不排序、不补默认值**（顺序是服务端契约）。

`web/src/api/req.ts:750 ChangeRow` 追加 `fields?: string[]`。

### 4.2 单点：`web/src/views/req/editability.ts`（新增）

```ts
export type EditDecision = 'EDIT' | 'CHANGE_REQUIRED' | 'LOCKED'
export interface EditabilityView {
  editable: RequirementFieldRule[]
  changeRequired: RequirementFieldRule[]
  locked: RequirementFieldRule[]
}
/** 抽屉可承载的 7 个 UI 字段（规范序子集） */
export const EDITABLE_UI_FIELDS: readonly string[]
export const REASON_TEXT: Record<string, string>
export const REASON_MARKER: Record<string, string>

export function reasonText(code?: string | null): string          // 未知/null → '该字段当前不可修改'
export function reasonMarker(code?: string | null): string        // 无标记返回 ''
export function editabilityOf(detail?: RequirementDetail | null): EditabilityView | null
export function ruleOf(detail: RequirementDetail | null | undefined, field: string): RequirementFieldRule | null
export function decisionOf(detail: RequirementDetail | null | undefined, field: string): EditDecision | null
export function canEditAny(detail: RequirementDetail | null | undefined, fields: readonly string[]): boolean
export function editDisabledReason(detail: RequirementDetail | null | undefined): string
export function firstFieldOf(detail: RequirementDetail | null | undefined, decision: EditDecision): string | null
```

文案（唯一来源，逐字）：

| code | REASON_TEXT | REASON_MARKER |
|---|---|---|
| `EARLY_STAGE` | 当前处于早期状态，可直接修改 | （空） |
| `OPEN_STATE` | 该字段不受状态限制，可直接修改 | （空） |
| `CHANGE_REQUIRED` | 当前状态不能直接修改，需发起变更单 | （空，由 `需变更` 标记承载） |
| `TERMINAL` | 需求已结束，字段只读 | 已结束 |
| `PARENT_ROLLUP` | 父需求人天由子需求汇总，不可手工填写 | 汇总 |
| `NO_PARENT` | 业务需求为顶层，不能设置父需求 | 顶层 |
| `ASSIGN_FLOW` | 负责人请通过「指派」操作变更 | 指派变更 |
| `IMMUTABLE` | 需求类型创建后不可修改 | 不可改 |
| `UNKNOWN_STATUS` | 状态未识别，字段暂不可编辑 | 状态未知 |

- `editabilityOf`：三个数组任一不是数组 → `null`（fail-closed）；全为数组时原样返回（不合并、不去重）。
- `decisionOf`：扫描三数组，命中即返回；不在任何数组 → `null`。
- `editDisabledReason`：`editabilityOf === null` → `缺少可编辑性规则，暂不可编辑`；否则 `当前状态没有可直接修改的字段`。

### 4.3 `FieldFlag.vue`（新增，8 行内共用）

`web/src/views/req/FieldFlag.vue`
```ts
defineProps<{ rule?: RequirementFieldRule | null; changeDisabled?: boolean }>()
defineEmits<{ requestChange: [] }>()
```
- `EDIT`/`rule=null` → 不渲染（`v-if` 在调用方也可）。
- `CHANGE_REQUIRED` → `<span>需变更</span>` + `<button>发起变更</button>`（`changeDisabled` 时 `disabled` + `aria-disabled` + `title="无发起变更权限（需要需求管理权限）"`；**不隐藏**）。
- `LOCKED` → `<span role="note" tabindex="0" :aria-label="reasonText">🔒 {{ reasonMarker }}</span>` + `el-tooltip`（`--tooltip-max`）。
- 纯文本 12px/500，**不画胶囊、不加底色**（DESIGN §0）。

### 4.4 编辑抽屉（`web/src/components/req/RequirementEditDrawer.vue`）

- 删除 `permissions`/`editable`/`changePermissionsOf` 读取与 `children.length` 判断。
- 新增：
  ```ts
  const matrix = computed(() => editabilityOf(props.detail))
  const matrixMissing = computed(() => matrix.value === null)
  const decision = (field: string): EditDecision | null => decisionOf(props.detail, field)
  const isEditable = (field: string): boolean => decision(field) === 'EDIT'
  const groupLocked = (group: Group): boolean => group.fields.every((f) => decision(f) !== 'EDIT')
  ```
- 逐字段：`.edit-field` 加 `:class="{ 'is-locked': decision(field) !== 'EDIT' }"`；控件下新增
  `<p v-if="decision(field) && decision(field) !== 'EDIT'" class="edit-field__hint">{{ reasonText(ruleOf(props.detail, field)?.reasonCode) }}</p>`。
- `estimateDays` 的旧硬编码 hint 删除（由 `PARENT_ROLLUP` 原因句替代）。
- 顶部 fail-closed 条：`matrixMissing` → `.edit-banner.is-locked`「未获取到可编辑性规则，暂不可编辑」；所有控件 `disabled`；footer 只留「取消」，**不给预填入口**。
- 组头「需发起变更 / 发起变更」保留：仅当整组无 `EDIT`；预填整组字段不变。
- 409 分流（保存失败时）：`CHANGE_REQUIRED` → warning 横幅 + 「发起变更（预填）」；`FIELD_LOCKED` → info 横幅「{reasonText}：{fieldLabel(field)}」+ **无发起入口** + emit `refresh`（父级重取详情）。

### 4.5 详情页

`web/src/views/req/RequirementDetailView.vue`
- 头部「编辑」：`:disabled="!canEditAny(detail, EDITABLE_UI_FIELDS)"`，旁边/下方给可见短句 `editDisabledReason(detail)`（不依赖 `title`）。「发起变更」仍只看 `permissions.changeRequest`。
- `actionBanner` 类型扩为 `{ text: string; code?: string; reasonCode?: string; field?: string; changeFields?: string[] }`；`handleActionError` 的 409 分支按 code 取 `detail.reasonCode`/`detail.field`。
- 横幅渲染：
  - `FIELD_LOCKED` → 前缀「字段已锁定：」+ `reasonText(reasonCode)` + `fieldLabel(field)`；修饰类 `.action-banner--locked`；**不渲染** `data-test="change-banner-cta"`。
  - `CHANGE_REQUIRED` → 保持 r40：`data-test="change-banner-cta"` + 预填。
- 新增 `onFieldRequestChange(field: string)`：`openChangeDrawer(changeTypeOfField(field), [field])`，传给 `OverviewTab`。

`web/src/views/req/tabs/OverviewTab.vue`
- 删除 `isParent`（不再由 `children.length` 推锁定）；`实际人天` 保留静态「汇总」文本（它不是矩阵字段）。
- 每行末尾挂 `<FieldFlag :rule="ruleOf(detail, field)" :change-disabled="!canChange" @request-change="emit('requestChange', field)" />`：
  `type`→`IMMUTABLE`、`domainIds`→ 值 + 无标记、`ownerUserId`→`ASSIGN_FLOW`、`expectedDeliveryDate`/`releaseId`→ 矩阵、`estimateDays`→ 矩阵（`PARENT_ROLLUP` 时显示 `🔒 汇总`，替代原 `v-if="isParent"`）。
- 新增两行：**父需求**（`parentCode` 链到父详情，无父 → 「顶层需求」）、**Sonar 项目键**（mono，空 → 「—」）。
- 新增 emit：`requestChange: [field: string]`。

### 4.6 变更 Tab（`web/src/views/req/tabs/ChangeTab.vue`）

- 7 列改为：变更单 96 / 状态 112 / 类型 88 / **目标字段（弹性，最小 320）** / 审批结果 88 / 提出人 96 / 时间 140；`table-layout: fixed`；内容 < 940 时 `.change-tab__surface` 横向滚动。
- 目标字段单元：`row.fields` chips（`fieldLabel(field)`，不可点），次行 `原因：{reason}` + `影响：{impactSummaryLines(impactSummary).line1}`（`line-clamp` + `title` 全文）；`fields` 空 → 「—」；`reason` 空 → 只显示影响。
- 审批结果：`verdictText(row.status)`（`change.ts` 新增导出）；提出人：`userName(row.createdBy)`，未命中回退 `#id`；时间 `formatDateTime(appliedAt || createdAt)`。
- 移除「原因」「影响摘要」两个独立列；`loading` 行 `colspan` 改 7。

### 4.7 `web/src/views/req/change.ts`

- `FIELD_LABELS` 补 4 项：`domainIds` 业务域、`sonarProjectKey` Sonar 项目键、`ownerUserId` 负责人、`type` 需求类型（共 11，避免详情/抽屉显示英文）。
- 新增 `export function verdictText(status: string): string`：`PENDING` 审批中 / `APPROVED|APPLIED` 通过 / `REJECTED` 驳回 / `WITHDRAWN` 撤回 / 其余回退 `statusLabel`。
- `changePermissionsOf`、`changeTypeOfField`、`statusLabel` 等保持不变。

## 5. 边界与失败模式

| 场景 | 期望 | 落点 |
|---|---|---|
| 终态 PUT 同值 | 200，不 `save`、不写审计、`updated_at` 不变 | `changed.isEmpty()` 短路 |
| 终态 PUT 改任意矩阵字段 | 409 `FIELD_LOCKED` + `TERMINAL`（`type` 仍是 `IMMUTABLE`） | 矩阵第 2/3 条 |
| 混合（title 受控 + type 锁） | 只报 `FIELD_LOCKED`，`fields=["type"]` | `reject` 先 locked |
| 受控态 PUT `domainIds` | 允许（`EDIT/OPEN_STATE`） | 矩阵第 7 条 |
| 早期 BUS PUT `estimateDays` | 409 `PARENT_ROLLUP`（非 400） | 矩阵第 5 条 |
| 终态 PUT `actualDays` | 400（`requireWritable`，非矩阵字段） | no-op 分支 `carriesActualDays` |
| 请求只带 `domainIds` 且为空 | 归一为「主域」；与当前同 → 200 | `patch`/`snapshot` 同一归一函数 |
| 三数组缺失（旧后端/半发布） | 抽屉全禁用 + 提示；详情「编辑」禁用；不猜变更类型 | `editabilityOf === null` |
| 未知状态（历史脏数据） | 11 字段全 `LOCKED/UNKNOWN_STATUS`，PUT 409 | contract 第 1 条 |
| 变更历史行快照为空 | `fields: []`，前端「—」，接口 200 | `toRow` 空判 |
| 并发两次 PUT | 后者以库值重算，同值 200 / 异值按矩阵 | 无缓存，只在请求内取快照 |

## 6. 测试策略

### 6.1 单元测试（`mvn -o -pl req-service -am test`）

- `req-service/src/test/java/com/example/req/requirement/RequirementEditabilityMatrixTest.java`（新增，AC-1/2/3/4）：
  遍历 `StateTables.statuses` × `FIELDS`，断言 11 字段决策 + reasonCode 全表；三组对照（BUS 早期/POOLED/CLOSED；PDR 三态；RD 三态）；分区/并集/顺序；`type` 恒 `IMMUTABLE`；BUS `parentId` 恒 `NO_PARENT`；非 RD `estimateDays` 恒 `PARENT_ROLLUP`；RD `TODO/IN_DEV` 人天 `EDIT/CHANGE_REQUIRED`；域/技术 `OPEN_STATE`；未知 type/status → `UNKNOWN_STATUS`。
- `RequirementEditGuardTest`（由 `RequirementChangeGuardTest` 改写，AC-6/7）：11 字段 patch 提取（含 `extra` 的 `type/ownerUserId/sonarProjectKey` 与 `domainIds`）；同值 → `changed` 空；LOCKED 优先；终态 `TERMINAL`；父人天 `PARENT_ROLLUP`；两参构造删除后的调用点全部更新。
- `RequirementChangeServiceTest`（更新）：`Row.fields` 规范序、空快照 `[]`。
- 既有 `RequirementRulesTest`（父人天 400）**不动**：变更单创建侧语义未变。

### 6.2 新门禁 `scripts/editability-verify.sh`（36 条）

骨架照抄 `scripts/change-verify.sh`：`EDITABILITY_TOTAL=36`、`ck "..."` 计数自证、`--static-only`/`--twice`/`--once`、`source scripts/lib.sh` + `scripts/lib/gate-fixture.sh`、缺栈 `die` 给修复命令、退出清理 `TAG` 残留必须 0。夹具**复用 gate-fixture 的建/复位入口与 tag 变量**（不新建第二套）；终态夹具用 SQL 置状态。

| 段 | 条数 | 断言 |
|---|---|---|
| S1 静态契约 | 6 | ① contract 类含三决策 + 9 常量；② `FIELDS` 顺序 == 规范序 11；③ req-service 无第二份状态→字段表（`SUBMITTED`/`PENDING_ANALYSIS` 在主代码的判定命中为 0，`EARLY_STATUSES` 已删）；④ `views/req` 与抽屉内 `children.length` 判断为 0；⑤ 产品代码无 `MUTATE` 字样；⑥ `pom.xml`/`web/package.json` 相对基线 diff 为空且无新迁移 |
| S2 三组对照 | 9 | BUS 早期/POOLED/CLOSED、PDR `PENDING_ANALYSIS`/`IN_DEV`/`ACCEPTED_CLOSED`、RD `TODO`/`IN_DEV`/`DONE` 各一条：GET 详情逐字段比对 11 字段决策 + 三数组分区/顺序 |
| S3 字段特例 | 7 | ① `type` 三类型全状态 `IMMUTABLE`；② BUS `parentId` 全状态 `NO_PARENT`；③ BUS/PDR `estimateDays` 早期即 `PARENT_ROLLUP`；④ RD `estimateDays` TODO=`EDIT`、IN_DEV=`CHANGE_REQUIRED`；⑤ `domainIds`/`sonarProjectKey` 早期与受控态均 `OPEN_STATE`、终态 `TERMINAL`；⑥ `ownerUserId` 非终态 `ASSIGN_FLOW`；⑦ 未知状态（SQL 造 `status='BOGUS'` 行）→ 11 字段 `UNKNOWN_STATUS` |
| S4 PUT 错误码 | 5 | ① 终态改内容 → 409 `FIELD_LOCKED`、`detail.field=title`、`reasonCode=TERMINAL`；② 受控态多字段 → 409 `CHANGE_REQUIRED`、`fields` 规范序；③ 混合 → 只报 LOCKED 且 `fields` 只含锁字段；④ 终态同值 → 200 且 `updated_at` 与 UPDATE 审计条数不变；⑤ 非 RD 改人天 → 409 `FIELD_LOCKED/PARENT_ROLLUP`（取代 r40 400） |
| S5 响应一致性 | 5 | ① 三数组元素键恰 `{field,reasonCode}`、分区并集 11、规范序；② 两次 GET 完全一致；③ `permissions.editableFields` == EDIT ∩ 7；④ 详情 EDIT 的 7 个 UI 字段逐个 PUT 同值 → 200（不因可编辑性被拒）；⑤ 详情的 LOCKED/CHANGE_REQUIRED 代表字段 PUT 变化 → 409 对应 code（接口与 PUT 同源） |
| S6 变更记录与负向 | 4 | ① 新建变更 → 列表行 `fields` == before/after 真实变化且规范序；② SQL 造空快照行 → `fields: []` 且接口 200；③ `EDITABILITY_MUTATE=always-edit` → S4 必红、退出码非 0、输出点名字段；④ 结束 `TAG` 残留 0 且复位成功 |

- `--twice`：两次运行都 `PASS==TOTAL` 且逐段 PASS 数一致。
- `EDITABILITY_MUTATE=always-edit` 只改脚本内 `expected_decision()`（恒 EDIT），**不编译不改产品代码**；静态段 S1-5 断言产品代码无 `MUTATE`。
- 接入：`scripts/test-all.sh` 在 `change-verify` 后加一行 `run_step "需求字段可编辑性（editability-verify）" bash "$ROOT/scripts/editability-verify.sh"`；`Makefile` 加 `editability-verify` 目标 + `.PHONY` + 帮助文案（`make test` 只经 `test-all.sh` 聚合，不重复内联）。

### 6.3 回归基线（只增不减，连跑两次）

`platform-verify ≥199`、`platform-test-negative ≥132`、`access-verify ≥206`、`rbac-verify ≥113`、`release-flow-verify 44`、`gate-order-verify ≥21`、`change-verify 42`、`platform-demo-reset 80`；`lint` 项数与 `platform-unit` 用例数不低于 r42 实测；`web` 的 `npm run typecheck` + `npm run build` 全绿。

### 6.4 界面走查（dev 自查，不写 QA 报告）

三种类型 × 早期/受控/终态各取一状态：详情标记与值、抽屉逐字段原因、两类 409 横幅动作不同、变更列表 chips + 审批结果；截图留 `logs/teamflow/<runId>/`。

## 7. 任务拆分（按文件边界，可并行）

依赖：**T1 → (T2, T3, T4) → T5 → T6 → T7**。T2/T3/T4 文件不相交可并行；T5 读 T4 的 `editability.ts` API；T6 需 T1/T2 的接口名；T7 最后。

| # | 任务 | files（owner） | reads |
|---|---|---|---|
| T1 | 契约矩阵 + 委托 + 矩阵单测 | `platform-contract/src/main/java/com/example/platform/contract/RequirementEditability.java`（新）、`req-service/src/main/java/com/example/req/requirement/change/RequirementChangeFields.java`、`req-service/src/test/java/com/example/req/requirement/RequirementEditabilityMatrixTest.java`（新） | `StateTables.java`、`RequirementRules.java`（类型常量）、PRD §3 |
| T2 | PUT 收口 + 详情三数组 + 错误码 | `req-service/src/main/java/com/example/req/requirement/RequirementEditGuard.java`（新）、`RequirementService.java`、`RequirementViews.java`、`RequirementErrors.java`、删除 `change/RequirementChangeGuard.java`、改写 `change/RequirementChangeGuardTest.java` → `RequirementEditGuardTest.java` | T1、`RequirementChangeFields.java`、`RequirementDtos.java` |
| T3 | 变更列表 `fields` | `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeViews.java`、`RequirementChangeService.java`、`RequirementChangeServiceTest.java` | T1、`WorkflowJson` |
| T4 | 前端单点 + 类型 + 抽屉 | `web/src/views/req/editability.ts`（新）、`web/src/views/req/FieldFlag.vue`（新）、`web/src/api/types.ts`、`web/src/views/req/platform.ts`、`web/src/views/req/change.ts`、`web/src/components/req/RequirementEditDrawer.vue` | 现有 `change.ts`、`RequirementDetail` 类型、DESIGN §3.2–3.5 |
| T5 | 详情 / 概览 / 变更 Tab | `web/src/views/req/RequirementDetailView.vue`、`web/src/views/req/tabs/OverviewTab.vue`、`web/src/views/req/tabs/ChangeTab.vue` | T4 全部新导出、`ChangeCreateDrawer` props、`usePlatformDict` |
| T6 | 门禁脚本 + 接线 | `scripts/editability-verify.sh`（新）、`scripts/test-all.sh`、`Makefile` | `scripts/change-verify.sh`、`scripts/lib/gate-fixture.sh`、T1/T2 接口 |
| T7 | 交付自查（缺陷当场修，**不写 QA/ACCEPTANCE**） | 无固定 owner；修复回落到对应任务 | 全部 |

**git 约束（每个任务都在自己的提交里落实，不单列任务）**：
- 分支 `feat/req-editability-rules`（已存在）；禁止在 main 提交。
- 提交信息含 `runId=tf-mwtzd1oa-p2j7xu` 与任务夹 `docs/teamflow/20261124-r43-req-field-editability`；例如
  `feat(req-editability): 契约矩阵与详情三数组 (runId=tf-mwtzd1oa-p2j7xu, task=docs/teamflow/20261124-r43-req-field-editability)`。
- `git status` 复核：只允许本清单内文件 + 任务夹；`target/`、`.run/`、`.nacos/`、`logs/teamflow/` 不入库（`.gitignore` 已覆盖，复核即可）。
- **禁止**改 `pom.xml`、`web/package.json`、`deliver-service/**`、`api-gateway/**`；禁止新增 Flyway 迁移。

### 7.1 实施偏差补录（r43 修复轮回写，只补不删）

上表是**开工时的计划**，下表是**实际改动中超出 T1–T6 清单的 6 处**（QA D-2 指出 §7 未登记；此处追加登记，旧行不改）：

| 文件 | 行为变化 | 为什么必须改 |
|---|---|---|
| `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeValidator.java` | 父需求 CONTENT 变更的人天校验由「无条件判」收紧为仅 `daysChanged` 时判 | 矩阵前移后父需求的 `estimateDays` 恒为 `PARENT_ROLLUP`（LOCKED），PUT 侧已先被 `409 FIELD_LOCKED` 拦下；变更单侧若仍无条件报人天错，会让「只改标题」的父需求变更单被无关错误挡住。**未放宽**：父需求真改人天仍被拦、精度判定保留（AC-8 复核 + `change-verify` 42 条双向回归） |
| `req-service/src/test/java/com/example/req/requirement/change/RequirementChangeValidatorTest.java`（新） | 上述收紧的正反用例 | 语义变化必须有测试随行，否则窗口被再次放宽时无人发现 |
| `web/src/api/req.ts` | `ChangeRow` 增加 `fields?: string[] \| null`（仅类型，请求层未动） | 变更列表行新增 `fields[]` 的前端编译期镜像；缺它则 `ChangeTab` 只能 `as any` |
| `web/src/styles/tokens.css` | 新增 `--w-change-verdict`、`--h-change-row`、`--flag-gap`、`--tooltip-max` 四个 token | 变更 Tab 7 列两行制与 `FieldFlag` 的几何由 DESIGN 固定值给出，散写像素会让两处漂移 |
| `scripts/platform-verify.sh` | 父人天断言 `400` → `409 FIELD_LOCKED/PARENT_ROLLUP`、环检测 → `409 NO_PARENT`；条数仍 **199** | 既有断言是被测契约的一部分，矩阵收口后不放宽、只改期望码；不改则门禁与新契约互相矛盾 |
| `docs/teamflow/memory.md`、`docs/teamflow/REQUIREMENT-FLOW.md` | AC-17 回写：门禁基线行 + 「需求可编辑性矩阵」约定 + §5.6「可编辑性判定」 | 文档是产品级长期记忆；由 `editability-verify` S1 静态段守护（基线缺失即红） |

其余与计划一致：`pom.xml`、`web/package.json`、`deliver-service/**`、`api-gateway/**` 均未改，无新增 Flyway 迁移。

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 顺序提前把业务 400 变成 409 | 只对「真实变化且命中矩阵」的字段；EDIT 字段仍走原业务校验；S4-5 与 change-verify 42 条共同回归 |
| `Detail` record 加组件导致构造点编译失败 | 唯一构造点 `RequirementService.java:405` 同步改；全量 `mvn -o -pl req-service -am test` 编译即暴露 |
| 前端仍有 `children.length` 推断 | S1-4 静态断言 + 抽屉/概览代码删除；评审核对 |
| no-op 短路少写审计 | 只对 11 字段全同值生效；`actualDays` 等非矩阵写仍按旧路径；S4-4 断言审计条数不变 |
| 性能 | 详情只多一次 `domainsOf`（详情单条）；列表不加字段、不加查询；变更列表复用已有 JSON |
| 回滚 | 删除 `RequirementEditability` + 恢复 `RequirementChangeGuard`/`Detail` 旧签名/前端旧判定即可；无 DB 变更，回滚无数据代价 |

## 9. 交付清单（DoD）

- [ ] `RequirementEditability` + 矩阵单测全绿；`RequirementChangeFields` 无第二份状态表。
- [ ] `Detail` 三数组 + `domainIds`/`sonarProjectKey`；两次 GET 一致；旧 `permissions.editableFields` 保留。
- [ ] PUT：终端/父人天/混合/同值 四类断言全绿；`requireWritable` 其他调用方未动。
- [ ] 变更列表 `fields`（含空快照）与前端 7 列渲染。
- [ ] `editability.ts`/`FieldFlag.vue` 落地；抽屉与详情无 `children.length`/状态判断。
- [ ] `scripts/editability-verify.sh` 36/36、`--twice` 一致、MUTATE 必红、接入 `make test`。
- [ ] 8 项回归连跑两次、`typecheck`/`build`、lint/unit 不降。
- [ ] 提交信息含 runId 与任务夹；`pom.xml`/`package.json` diff 为空；无新迁移。
