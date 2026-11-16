# r23 技术方案：需求流转前置门（父状态）与提报必填（domainIds）

> 基线依赖：`docs/teamflow/20261116-r22-action-matrix-datadriven`（main=535939a）
> 分支：`feat/flow-precondition-gates`（不新开分支、不 rebase、不切分支）
> 一句话结论：把「产生子需求的前置条件」做成**一个纯函数 + 一个服务端调用点**，三条写入口（`decompose` / `POST /requirements` 直建 / `PUT` 改父）共用同一错误结构与同一句中文原因；详情 `view.decomposeGate` 用同一个函数派生；`domainIds` 必填在提报入口统一 400/403，其余校验一行不改。

## 1. 范围

| 做 | 不做 |
|---|---|
| 父状态门：PDR 需父 BUS `POOLED`；RD 需父 PDR `SCHEDULED\|IN_DEV`（409，结构化 detail） | 不改 `StateTables`、不新增状态、不动 16 动作契约（`decompose` 仍不在 `allowedActions`） |
| `decomposeGate` 详情字段（allowed / reason / parentStatus / required） | 不封 Seed、DemoReset、`/internal/**`、批量导入（已核查无用户批量入口） |
| 提报必填 `domainIds`：缺失/空/null → 400；不存在/跨租户 → 400；越权 → 403 | 不改 `PUT` 的 `domainIds: []` 语义（AC-11：仍回退原域、净 no-op） |
| 前端两入口按 `can('req:split') && decomposeGate.allowed` 禁用 + 原因 | 不重构通用校验框架、不改 `DataScope` / `PermissionMatrix` / UI-STYLE |
| 负向 30 → ≥36；`platform-verify` 夹具适配（断言数 167 不变） | 不改 `pom.xml`、`web/package.json`、`AGENTS.md` 托管区外内容 |

## 2. 现状与判定点（已核对源码）

| 写入口 | 代码位置 | 现有校验顺序 | 缺的门 |
|---|---|---|---|
| 拆解 `POST /requirements/{id}/decompose` | `RequirementController.decompose:131` → `RequirementService.decompose` → **`create(cmd)`** | `requireWritable`（终态 400）→ `childTypeOf`（RD 拆解 400）→ `create` 内父校验 | 父状态门 |
| 直建 `POST /requirements` | `RequirementController.create` → `RequirementService.create:98` | 类型→标题→优先级→人天→父存在→父子组合→祖先环 | 父状态门 + `domainIds` 必填 |
| 改父 `PUT/PATCH /requirements/{id}` | `RequirementService.applyParentChange:200` | 父未变直接 return → 父可见 → 父子组合 → 祖先环 | 父状态门 |
| 详情 `GET /requirements/{id}` | `RequirementService.detail` → `toView:651` | 无门信息 | `decomposeGate` |

`decompose` 复用 `create(cmd)` 是本轮的结构性资产：**门加在 `create` 的父校验段，拆解与直建自动同源**，不需要在 `decompose` 里复制一份判定。改父入口单独复用同一个纯函数。

`POST /requirements/{id}/decompose` 的路由权限已是 `req.action.decompose`（`PermissionRouteTable:137`，功能码 `req:split`），**403 天然先于 409**，本轮不动鉴权。

## 3. 领域规则：唯一判定函数

新增到 `req-service/src/main/java/com/example/req/requirement/RequirementRules.java`（纯函数，无 Spring/DB，和现有 `checkParentCombination`/`checkDays` 同风格）：

```java
public static final String PARENT_NOT_POOLED = "PARENT_NOT_POOLED";
public static final String PARENT_NOT_SCHEDULED = "PARENT_NOT_SCHEDULED";
public static final String REQUIRED_POOLED = "POOLED";
/** RD 的父 PDR 允许状态；与 StateTables 的 PDR 状态表由单测对齐，防状态改名漂移。 */
public static final List<String> RD_PARENT_ALLOWED = List.of("SCHEDULED", "IN_DEV");

/** 父状态门判定结果：allowed=false 时 code/message/required 必填。 */
public record ParentGate(boolean allowed, String code, String message, Object required) {}

/** 唯一判定：parentType=BUS 要求 POOLED；parentType=PDR 要求 SCHEDULED|IN_DEV。 */
public static ParentGate parentGate(String parentType, String parentStatus)

/** 把「未通过」翻译成 409 Violation；通过时返回 null（供 applyRule 使用）。 */
public static Violation parentGateViolation(long parentId, String parentStatus, ParentGate gate)

/** 详情 decomposeGate 的同一来源：RD 不可再拆 → allowed=false；BUS/PDR 委托 parentGate。 */
public static ParentGate decomposeGate(String type, String status)
```

判定与 detail 形状（只有这两个分支，不出现第三套）：

| 父类型 | 允许 | 错误码 | detail（**恰含**） |
|---|---|---|---|
| BUS | `POOLED` | `PARENT_NOT_POOLED` | `{parentId, parentStatus, required:"POOLED"}` |
| PDR | `SCHEDULED`,`IN_DEV` | `PARENT_NOT_SCHEDULED` | `{parentId, parentStatus, allowed:["SCHEDULED","IN_DEV"]}` |
| RD | 不可拆 | —（拆解走既有 400） | `decomposeGate.allowed=false` |

统一文案（409 `message` 与 `view.decomposeGate.reason` **逐字相同**，含当前状态与所需状态）：

- BUS：`父需求尚未入池（当前 {status}，需 POOLED），入池后才能创建下级需求`
- PDR：`父产品需求需先排期（当前 {status}，需 SCHEDULED 或 IN_DEV），排期后才能创建研发需求`
- RD：`该类型不能再拆解：RD`（与既有 400 文案同句）

`parentGateViolation` 用 `LinkedHashMap` 组 detail（保持 key 顺序），通过 `RequirementErrors.rule(v)` 抛 `ApiException(409, code, message, detail)`。

### 3.1 顺序契约（不得调换）

1. 终态 `400 STATUS_NOT_WRITABLE`（`requireWritable`，仅拆解/改父）
2. 类型：`400 VALIDATION_FAILED`（未知 type）/ `400 INVALID_PARENT`（父子组合、祖先环）
3. 权限 403（路由过滤器，天然在控制器之前）
4. **父状态门 409**
5. 落库、`DECOMPOSE` 审计、rollup 刷新

AC-4/AC-6 的硬证据：`RD 挂 BUS` 仍是 400 而不是 409；`BUS 的父设为 PDR` 仍是 400 `INVALID_PARENT`；`BUS` 终态拆解仍 400。

### 3.2 父未变 / 无父

- `applyParentChange` 首行 `Objects.equals(parentId, entity.getParentId())` → **直接 return，不触发状态门**（编辑标题/人天不被误伤）。
- `parentId=null`：走既有 `checkParentCombination`（BUS 合法；PDR/RD 400「必须指定父需求」），状态门不参与。
- `parentId` 不存在：`requireVisible` 404（既有）。

## 4. 数据模型与存储

**无 DDL**：不新增表/列/索引，不改 `StateTables`、`PermissionMatrix`、`ActionRegistry`、`sys_*` 种子。

| 变化 | 说明 |
|---|---|
| `requirement` | 只读 + 既有写入；门的判定发生在 `repository.save` **之前**，失败不消耗编号（`codeGenerator.next` 在其后） |
| `biz_domain` | 新增只读校验：`SELECT id FROM biz_domain WHERE tenant_id=? AND id IN (?,…)`（`JdbcTemplate`，与 `RequirementService` 现有直查同风格；lint 只拦写 SQL，不拦 SELECT） |
| `audit_log` | 409/400 失败**不写** CREATE/DECOMPOSE；403 越权域沿用 `FORBIDDEN`/`OUT_OF_SCOPE` 语义，不新增审计动作 |
| API 序列化 | `RequirementViews.View` 末尾新增 `decomposeGate`（见 §5.2），列表行为 `null` |

## 5. 后端改动（文件级）

### 5.1 `RequirementRules.java`

新增 §3 的常量、`ParentGate`、`parentGate`、`parentGateViolation`、`decomposeGate`，以及提报域校验的纯函数：

```java
/** 缺失/空/null → 400 {field:"domainIds"}；含 null/非正整数 → 400 {field,invalidIds}。 */
public static Violation checkDomainIds(List<Long> domainIds)
/** 去重保序（null 元素已被 checkDomainIds 拦下）。 */
public static List<Long> distinctDomainIds(List<Long> domainIds)
```

`parentGate` 对未知父类型抛 `IllegalArgumentException`（只可能来自脏数据；BUS/PDR/RD 之外的类型在 `create` 的类型校验就被拦）。

### 5.2 `RequirementViews.java`

```java
public record DecomposeGate(boolean allowed, String reason, String parentStatus, Object required) {
    /** 唯一映射点：408/详情共用 RequirementRules.ParentGate。 */
    public static DecomposeGate from(RequirementRules.ParentGate gate, String parentStatus)
}
```

`View` 末尾（第 26 个分量，`permissions` 之后）追加 `DecomposeGate decomposeGate`。三个构造点：

| 位置 | 传值 |
|---|---|
| `RequirementService.toView`（约 663 行） | `DecomposeGate.from(RequirementRules.decomposeGate(entity.getType(), entity.getStatus()), entity.getStatus())` |
| `RequirementPoolQuery.toView`（约 198 行） | `null`（列表行不带门，保持载荷不变） |
| `ReleaseService.toRequirementView`（约 417 行） | `null` |

`required` 类型为 `Object`：BUS 为 `"POOLED"`，PDR 为 `["SCHEDULED","IN_DEV"]`，RD 为 `null`；`reason` 在 allowed 时为 `null`。

### 5.3 `RequirementErrors.java`

只加一个工厂（400 走既有 `validation(message, detail)`）：

```java
/** 403 OUT_OF_SCOPE，detail {field:"domainIds", domainIds:[…]}（AC-10 #7）。 */
public static ApiException domainOutOfScope(List<Long> domainIds)
```

### 5.4 `RequirementDomainValidator.java`（新，`@Component`）

```java
@Component
public class RequirementDomainValidator {
    public RequirementDomainValidator(JdbcTemplate jdbcTemplate)
    /** 提报入口：必填 + 存在性 + 越权；返回去重保序的 domainIds，失败抛 400/403。 */
    public List<Long> requireForCreate(DataScope scope, List<Long> raw)
    /** 纯函数：raw 中不在 known 里的 id（AC-10 #6 invalidIds）。 */
    public static List<Long> unseen(List<Long> raw, Collection<Long> known)
    /** 纯函数：越权 id。TENANT 档或域集合为空 => 不限制（按 DataScope 注释语义）。 */
    public static List<Long> outOfScope(List<Long> raw, DataScope scope)
}
```

`requireForCreate` 顺序：`checkDomainIds`（400）→ `distinctDomainIds` → 查询 `biz_domain` → `unseen` 非空 400 `{field,invalidIds}` → `outOfScope` 非空 403 → 返回 ids。

**为什么校验在控制器入口而不是 `create` 里**：`decompose` 复用 `create`，而拆解的空域语义是「继承父域」（`DecomposeRequest.domainIds` 为空表示继承），若把必填写进 `create` 会把拆解一并拦死，违反 AC-5。`POST /requirements` 是唯一的「用户自选业务域」写入口，因此把必填绑在这个入口；`decompose` 与种子/内部路径保持原样。约束：未来新增直建入口时必须复用 `requireForCreate`。

### 5.5 `RequirementService.java`

```java
// create：父校验段末尾（祖先环之后、组装实体之前）
if (cmd.parentId() != null) {
    parent = requireVisible(cmd.parentId());
    applyRule(RequirementRules.checkParentCombination(type, parent.getType()));
    applyRule(RequirementRules.checkAncestorCycle(null, parent.getId(), this::parentOf));
    requireParentGate(parent);                       // ← 唯一状态门调用点
} else {
    applyRule(RequirementRules.checkParentCombination(type, null));
}

/** 三条写入口共用的门；applyRule 对 null 无操作。 */
private void requireParentGate(RequirementEntity parent) {
    RequirementRules.ParentGate gate =
            RequirementRules.parentGate(parent.getType(), parent.getStatus());
    applyRule(RequirementRules.parentGateViolation(
            parent.getId(), parent.getStatus(), gate));
}

// applyParentChange：祖先环之后
if (parent != null) {
    requireParentGate(parent);                       // 父未变时早退，不会走到这里
}

// toView：第 26 个参数按 §5.2 传值
```

`decompose` **一行不改**（继续 `create(cmd)`）：门在 `create` 内，拆解自然同源。`decompose` 的 400 顺序保持：`requireWritable` → `childTypeOf == null` → `create`。

### 5.6 `RequirementController.java`

构造器追加 `RequirementDomainValidator domainValidator`；`create` 方法：

```java
DataScope scope = TenantContext.require();
List<Long> domainIds = domainValidator.requireForCreate(scope, request.domainIds);
return ResponseEntity.status(201).body(requirementService.create(new CreateCommand(
        request.type, request.title, request.description, domainIds, request.priority,
        request.expectedDeliveryDate, request.parentId, request.ownerUserId,
        request.estimateDays, request.actualDays)));
```

其余端点（`update`、`decompose`、`actions`、`assign-vendor`、`deliver`、`quick-test`、`export`）不动。

## 6. API 设计

### 6.1 错误响应（结构由 `ApiError` 统一输出）

```json
{"status":409,"code":"PARENT_NOT_POOLED","message":"父需求尚未入池（当前 SUBMITTED，需 POOLED），入池后才能创建下级需求","detail":{"parentId":42,"parentStatus":"SUBMITTED","required":"POOLED"}}
{"status":409,"code":"PARENT_NOT_SCHEDULED","message":"父产品需求需先排期（当前 PENDING_ANALYSIS，需 SCHEDULED 或 IN_DEV），排期后才能创建研发需求","detail":{"parentId":7,"parentStatus":"PENDING_ANALYSIS","allowed":["SCHEDULED","IN_DEV"]}}
{"status":400,"code":"VALIDATION_FAILED","message":"请选择业务域","detail":{"field":"domainIds"}}
{"status":400,"code":"VALIDATION_FAILED","message":"业务域不存在或不属于当前租户","detail":{"field":"domainIds","invalidIds":[99]}}
{"status":403,"code":"OUT_OF_SCOPE","message":"无权在这些业务域下提报","detail":{"field":"domainIds","domainIds":[3]}}
```

三种写入口的 409 响应体**逐字节同构**（同一 `RequirementRules` 产出），前端只按 `code` 分支。

### 6.2 详情新增字段

`GET /api/req/requirements/{id}` → `Detail.view.decomposeGate`：

```json
"decomposeGate":{"allowed":false,"reason":"父需求尚未入池（当前 SUBMITTED，需 POOLED），入池后才能创建下级需求","parentStatus":"SUBMITTED","required":"POOLED"}
```

- 只描述「类型/状态」前置，**不含权限信息**（`req:split` 由前端 `can()` 另行判断）。
- `allowed=true` 时 `reason=null`；`required` 仍返回所需状态（信息性）。
- RD：`{"allowed":false,"reason":"该类型不能再拆解：RD","parentStatus":"TODO","required":null}`。
- 列表行 `decomposeGate=null`（列宽/载荷不变）。

## 7. 前端（两文件）

### 7.1 `web/src/api/types.ts`

```ts
export interface RequirementDecomposeGate {
  allowed: boolean
  reason: string | null
  parentStatus: string
  required: string | string[] | null
}
// RequirementDetail 末尾追加
decomposeGate?: RequirementDecomposeGate
```

`normalizeRequirementDetail` 展开 `view`，字段自动透传，`platform.ts` 不改。

### 7.2 `web/src/views/req/tabs/ChildrenTab.vue`

```ts
const gate = computed(() => props.detail.decomposeGate ?? null)
const gateReason = computed(() => gate.value?.reason ?? '')
const canDecompose = computed(() => can('req:split') && gate.value?.allowed === true)
function onDecompose(): void {
  if (!canDecompose.value) return
  emit('decompose')
}
```

两入口都只绑 `onDecompose` + `:disabled="!canDecompose"`（AC-8 静态断言据此检查）：

```html
<!-- 入口 1：汇总条右侧 -->
<el-button v-if="can('req:split')" size="small" :disabled="!canDecompose"
           :title="gateReason" @click="onDecompose">拆解子需求</el-button>

<!-- 原因：两入口共用一行，空态时也可见 -->
<p v-if="can('req:split') && !canDecompose && gateReason" class="children__gate" role="note">{{ gateReason }}</p>

<!-- 入口 2：空状态，改用 EmptyState 的 action 插槽（组件已支持 <slot name="action">，无需改组件） -->
<EmptyState v-if="children.length === 0" title="还没有子需求" description="…" icon="file" compact>
  <template #action>
    <el-button v-if="can('req:split')" type="primary" :disabled="!canDecompose"
               :title="gateReason" @click="onDecompose">拆解子需求</el-button>
  </template>
</EmptyState>
```

删除 `action-text="拆解子需求"` 与 `@action="emit('decompose')"`，保证空状态动作不可能绕过门。样式追加 `.children__gate{font-size:12px;color:var(--color-warning,#b45309);margin:0 0 0 4px;}`（用现有 token，不新增主题）。

## 8. 边界与易错点

1. **同一函数**：`RequirementService` 中只有 `requireParentGate` 一处调用 `RequirementRules.parentGate`；`decompose` 不得出现状态字面量或第二套判定。
2. **父未变**：`applyParentChange` 早退在门之前；否则「只改标题」在父未排期时会被 409（AC-4）。
3. **类型先于状态**：`RD 挂 BUS` 必须 400，`BUS 挂 PDR` 必须 400；顺序错会改变既有断言。
4. **终态先于状态门**：拆解已关闭需求仍 400，不是 409。
5. **不消耗编号**：门在 `codeGenerator.next` 之前；负向用例要同时断言 `requirement` 行数与 `parent_id` 未变。
6. **详情同源**：`DecomposeGate.from` 是唯一映射；`reason` 必须与 409 `message` 逐字一致（单测断言）。
7. **RD 详情的门**：`allowed=false`，不能让前端误以为 RD 可拆；文案与既有 400 同句。
8. **域校验不误伤拆解**：`decompose` 继承父域为空时不得 400（校验只在控制器直建入口）。
9. **空集语义**：`DataScope.domainIds()` 为空表示「该维度不限制」（注释契约），但 `DataScope.canSeeDomain` 实现是空集=不可见；**本轮不改 `DataScope`**，新校验用 `outOfScope` 显式实现注释语义并由单测锁定。
10. **文案只写一处**：409/400 的中文都在后端生成，前端只展示 `message`/`reason`，不新增前端错误码映射表（避免 R5 的「两处中文」）。
11. **契约零改动**：`platform-contract` 四个文件（StateTables/PermissionMatrix/ActionRegistry/DataScope）diff 为空；`pom.xml`、`web/package.json` diff 为空。

## 9. 测试策略

### 9.1 单测（`platform-unit` ≥316 不缩水）

新增 `req-service/src/test/java/com/example/req/requirement/RequirementParentGateTest.java`：

- 遍历 `StateTables.statuses("BUS")`：`parentGate("BUS", s).allowed() == "POOLED".equals(s)`；未通过时 `parentGateViolation` 非 null、`status=409`、`code=PARENT_NOT_POOLED`、`detail` key **恰为** `parentId/parentStatus/required`、`message` 与 `ParentGate.message()` 相等。
- 遍历 `StateTables.statuses("PDR")`：allowed ⇔ `s ∈ {SCHEDULED,IN_DEV}`；detail key 恰为 `parentId/parentStatus/allowed`，`allowed` 与 `RD_PARENT_ALLOWED` 相等。
- `decomposeGate` 全状态：`DecomposeGate.from(gate, status)` 的 `allowed/reason/parentStatus/required` 与 409 的 `code/message/detail` 一一对应（AC-7）。
- `RD`：`decomposeGate("RD", "TODO").allowed()==false`，reason 含「不能再拆解」。
- 漂移守卫：`RD_PARENT_ALLOWED ⊆ StateTables.statuses("PDR")` 且 `statuses("BUS").contains("POOLED")`。

新增 `RequirementDomainRulesTest.java`（纯函数，无 Spring）：

- `checkDomainIds(null / List.of() / List.of(1L) 通过)`、`null 元素 / 0 / -1 → 400 field=domainIds`。
- `distinctDomainIds` 去重保序。
- `unseen([1,2,3],[1,2]) == [3]`；`outOfScope`：TENANT→空、DOMAIN+空集→空（注释语义）、DOMAIN+{1} 请求[1,2]→[2]（R3）。

### 9.2 负向脚本（`scripts/platform-test-negative.sh`，30 → 49 断言）

新增一段（放在第 4 节 RD 跳步之后、第 5 节之前），夹具全部走 API（不直连改库，R2）：

| # | 用例 | 期望 | 不落库断言 |
|---|---|---|---|
| 1 | 种子 POOLED BUS `decompose` | 201 + 子 PDR `PENDING_ANALYSIS`（正向回归 AC-5） | — |
| 2 | 未入池 BUS（新建即 `SUBMITTED`）`decompose` | 409 `PARENT_NOT_POOLED` + detail `required:"POOLED"` | 子需求计数不变 |
| 3 | 未入池 BUS 直建 `PDR` | 409 `PARENT_NOT_POOLED` | `requirement` 计数不变 |
| 4 | 未排期 PDR（#1 产物）`decompose` | 409 `PARENT_NOT_SCHEDULED` + `allowed:["SCHEDULED","IN_DEV"]` | 子需求计数不变 |
| 5 | 未排期 PDR 直建 `RD` | 409 `PARENT_NOT_SCHEDULED` | `requirement` 计数不变 |
| 6 | `PUT` 把 RD 改挂到未排期 PDR | 409 `PARENT_NOT_SCHEDULED` | `parent_id` 等于改前 |
| 7 | `POST /requirements` 缺 `domainIds` | 400 `VALIDATION_FAILED` + `detail.field="domainIds"` | `requirement` 计数不变 |
| 8 | 种子 `SCHEDULED` PDR 直建 RD | 201（正向）；改挂到合法父 200；原父回写 200 | — |

`PDR_ID`（§3 夹具）改用 `type='PDR' AND status IN ('SCHEDULED','IN_DEV')` 选取（见 9.4）。

### 9.3 `platform-verify` 夹具改造（断言数 **167 不变**，不加不删断言）

**AC-2（约 180–193 行）**——现夹具「新建 BUS(SUBMITTED) → PDR → RD」在门生效后必红。采用「种子 POOLED BUS + 新 PDR 先排期」：

```bash
# r23：PDR 的前置门要求父 BUS 已 POOLED；新建 BUS 保持 SUBMITTED，仅用于类型/状态断言。
SEED_POOLED_BUS="$(db_q "SELECT id FROM requirement WHERE tenant_id=$(tenant_id)
                         AND type='BUS' AND status='POOLED' ORDER BY id LIMIT 1")"
[[ -n "$SEED_POOLED_BUS" ]] || SEED_POOLED_BUS="$BUS_ID"      # 兜底：让下一条断言红并带提示
create_req PDR "验证-产品需求-${RUN_TAG}" "$SEED_POOLED_BUS"
expect "创建 PDR（父=BUS[POOLED]）201" 201
PDR_ID="$(jget "$HTTP_BODY" '.id')"
api_call POST "/api/req/requirements/${PDR_ID}/actions" '{"action":"schedule"}'
[[ "$HTTP_CODE" == "200" ]] || warn "PDR 排期失败（HTTP ${HTTP_CODE}）：后续 RD 创建会 409 PARENT_NOT_SCHEDULED，确认已 make platform-seed（BUS-0001 POOLED）"
```

PDR 为**全新**节点，后续「PDR 人天=子需求之和 2.0」与 `PARENT_DAYS_READONLY` 断言原样成立（无需改增量断言）。`create_req RD … "$BUS_ID"` 仍返回 400（类型先于状态），保留原文案。

**D9（约 804–807 行）**——同样受门影响（`D9_PDR` 创建后立刻建 RD）：在 `D9_PDR=…` 之后插入同一段 `schedule` 调用 + `warn`（不加断言）。

> 对 PRD R1 的修正：方案①「新 PDR 先经 schedule」漏了「PDR 的父 BUS 必须先 POOLED」，方案②会把父汇总改成增量断言、扩大改动面。本方案等价于①+补父：父用种子 `BUS-0001`，PDR 新建后 `schedule`，断言语义与数量都不变。

### 9.4 R7：四处「首个 PDR」选取加状态过滤

| 文件 | 现状 | 改为 |
|---|---|---|
| `scripts/platform-test-negative.sh:91` | `type='PDR' ORDER BY id LIMIT 1` | `type='PDR' AND status IN ('SCHEDULED','IN_DEV') ORDER BY id LIMIT 1` |
| `scripts/collab-perf.sh:71` | 同上 | 同上 |
| `scripts/tests/collab/_lib.sh:102-103` | 同上 + 兜底选任意 requirement | 同上；删掉「任意 requirement」兜底，改为 `err "没有 SCHEDULED/IN_DEV 的 PDR 可作 RD 父需求：先 make platform-seed"` + `return 1` |
| `scripts/tests/integration/_lib.sh:178-179` | 同上 | 同上 |

理由：首个 PDR 一旦终态（`ACCEPTED_CLOSED`），RD 直建会 409 假红；兜底选任意 requirement 则会撞 `400 INVALID_PARENT`。

### 9.5 静态断言（放 `logs/teamflow/tf-mwisc832-uxfle0/scripts/`，不进 lint 计数）

- `ac3-same-gate-check.sh`：`RequirementService.java` 中 `RequirementRules.parentGate(` 恰出现 1 次（在 `requireParentGate`）；`create` 与 `applyParentChange` 各调用 `requireParentGate(` 1 次；`decompose` 体内无 `parentGate`/`PARENT_NOT_` 且调用 `create(cmd)`。
- `ac8-decompose-gate-check.mjs`：`ChildrenTab.vue` 含 `decomposeGate`、`canDecompose` 由 `can('req:split')` + `gate.value?.allowed === true` 组成、两处 `@click="onDecompose"` 且都带 `:disabled="!canDecompose"`、不存在 `@action="emit('decompose')"`；`types.ts` 含 `decomposeGate?: RequirementDecomposeGate`。

### 9.6 门禁（不缩水，收尾以实测为准）

`mvn -o -DskipTests compile`；`make platform-unit`（≥316）；`make platform-verify`（**167**）；`make platform-test-negative`（30 → 49）；`make lint`（22）；`make rbac-verify`（111，`CANONICAL_HASH` 不变）；`make access-verify`（152）；`make verify`（14）；`make analytics-i18n-verify`（74）；`bash scripts/collab-verify.sh`（s0–s8）；`cd web && npm run typecheck`（0）+ `npm run build`。

## 10. 任务拆分（文件所有权，可并行）

| 任务 | 文件（owns） | reads | 说明 |
|---|---|---|---|
| **T1 后端门与域校验** | `RequirementRules.java`、`RequirementViews.java`、`RequirementErrors.java`、`RequirementService.java`、`RequirementController.java`、`RequirementPoolQuery.java`、`release/ReleaseService.java`、`RequirementDomainValidator.java`(新)、2 个测试类(新) | `StateTables.java`、`DataScope.java`、`ScopeTier.java`、`EmptyState` 无关 | 规则→视图→服务→控制器一次改完；同包互锁，不拆并行 |
| **T2 前端两入口** | `web/src/api/types.ts`、`web/src/views/req/tabs/ChildrenTab.vue` | `platform.ts`(normalize)、`usePermission.ts` | 与 T1 无文件交集，可并行；只读 `decomposeGate` |
| **T3 脚本夹具与负向** | `scripts/platform-verify.sh`、`scripts/platform-test-negative.sh`、`scripts/collab-perf.sh`、`scripts/tests/collab/_lib.sh`、`scripts/tests/integration/_lib.sh` | `scripts/lib.sh` | 依赖 T1 的 409/400 形状（契约见 §6），可先写后跑 |
| **T4 交付自检** | 无（仅 `logs/teamflow/tf-mwisc832-uxfle0/scripts/` 下的断言脚本与笔记） | 全部改动 | 编译 + 目标单测 + 静态断言 + `bash -n` 脚本 + 起栈跑 6 条负向/正向；发现缺陷当场修，**不写 QA-REPORT/ACCEPTANCE、不跑 QA 全矩阵** |
| **T5 记忆与提交** | `docs/teamflow/memory.md`（仅确认已就位的两行，不重复改写）+ 全量 `git add` | — | 提交含任务夹与 evidence；commit message 带 `runId=tf-mwisc832-uxfle0` |

依赖：T1、T2、T3 可并行 → T4 串行收口 → T5 提交。

**Git 约束（来自 PRD 工程约束，必须执行）**：留在 `feat/flow-precondition-gates`；不新建分支、不 rebase、不切换；不 `git clean`、不 `git checkout -- .`；提交只含本需求改动 + 任务夹/证据；`pom.xml`、`web/package.json`、`AGENTS.md`（托管区外）、`platform-contract/**` 必须零 diff。

## 11. 风险与回滚

| 风险 | 处置 |
|---|---|
| 门把既有脚本打红（R1/R7） | §9.3/§9.4 夹具先行；`platform-verify` 用 `warn` 暴露排期失败而不改断言数 |
| 拆解被域必填误伤 | 域校验只放控制器直建入口（§5.4），拆解继承路径不动 |
| `decomposeGate` 增加 View 构造点编译错误 | 三个构造点一次性补 `null`/计算值，编译即证明 |
| 前端 `decomposeGate` 缺失（旧缓存） | `allowed === true` 才放行 → 默认禁用；服务端始终返回，不会长期缺失 |
| 状态字面量漂移 | 单测把 `RD_PARENT_ALLOWED` 与 `StateTables.statuses` 对齐 |
| 回滚 | 纯新增判定 + 三处构造参数 + 两处前端；无 DDL，回滚即 revert 提交（不含数据迁移） |

## 12. 证据与交付

- ≥4 份证据（HTTP 原文 + DB 计数）：409 `PARENT_NOT_POOLED` / 409 `PARENT_NOT_SCHEDULED`（detail 全文）、400 `field=domainIds`、正向 201 回归（POOLED 拆 PDR、SCHEDULED 建 RD）；另附 `npm run typecheck` 与 §9.5 静态断言输出。
- 证据脚本/输出放 `logs/teamflow/tf-mwisc832-uxfle0/scripts/`（run 结束归档），文件内标注 `runId=tf-mwisc832-uxfle0`。
- 收尾提交：代码 + 脚本 + 任务夹（PRD/TECHNICAL/QA-REPORT/ACCEPTANCE）+ evidence；提交信息含 runId。
