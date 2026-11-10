# r11 技术方案 · 岗位工作台与自助报表后端补齐

> 对应 PRD：`docs/teamflow/20261110-r11-reports-portal-backend/PRD.md`（13 条 AC） · 基线：`docs/teamflow/20261110-r11-reports-portal-backend/PRD.md` 引用的 `20261110-r9-workbench-reports/`
> 结论：**不新增库表、不改 V10、不动前端**，在 deliver-service 内新增 `portal/`、`reports/` 两个包 + `support/` 两个写出器，`analytics/` 只做 4 处最小补充；所有数字仍由 `AnalyticsQueryService` / `AnalyticsMetrics` 产出，所有行级可见性仍由 `ScopedJdbc` / `ScopeSql` 决定。

## 1. 现状与缺口

| 层 | 已存在（r9/r10） | 本轮补 |
|---|---|---|
| 迁移 | `V10__*.sql`：`report_definition`、`portal_card_config`、`requirement` 时间索引；req-service 唯一迁移权威 | 不动（deliver-service `ddl-auto=validate` + `flyway.enabled=false`） |
| 权限 | `PermissionMatrix` 60 码；`PermissionRouteTable` 已含 `PUT /portal/config→PORTAL_CONFIG`、`^/portal→PORTAL_VIEW`、`^/analytics→PORTAL_VIEW`、`POST /reports/{id}/share→REPORT_SHARE`、`^/reports→REPORT_VIEW` | 不动路由表；`PUT share` 用方法级 `@RequiresPermission("REPORT_SHARE")` 覆盖（注解优先于路由表），确保 AC-1「非文档改动只在 deliver-service」 |
| 聚合 | `AnalyticsQueryService`（`metrics`/`dashboard`/`crossTab`/`countGroups`，933 行）、`AnalyticsMetrics`、`AnalyticsCache`、`AnalyticsDatasets`、`AnalyticsErrors`、`AnalyticsSql` | 4 处补充：`datasets()` 目录装配、`crossTab` 上限用调用方 `cap`、错误码 3 个工厂、`AnalyticsDirectory`（角色 id） |
| 接口 | `GET /analytics/metrics`、`GET /analytics/dashboard` | `GET /analytics/datasets`、`/reports/datasets`、`/portal`、`/portal/config`、`/reports/**`（CRUD/copy/share/pin/run/export） |
| 前端 | `web/src/api/analytics.ts`（496 行类型即契约）、`web/src/stores/analytics.ts`、4 条路由（`lazyView` 惰性解析，视图文件尚不存在） | 零改动；只按既有类型对齐字段名 |

## 2. 包结构与依赖（新代码）

```
com.example.deliver
├── analytics/            ← 既有，补 4 处
│   ├── AnalyticsController.java      + GET /analytics/datasets
│   ├── AnalyticsDatasets.java        + catalog()
│   ├── AnalyticsErrors.java          + PORTAL_UNKNOWN_CARD / unknownCard / cardLimit(status,…) / forbidden
│   ├── AnalyticsQueryService.java    ~ crossTab: groups > cap（原 MAX_GROUPS）
│   └── AnalyticsDirectory.java       ★ 角色 code→sys_role.id（唯一新增 SQL 点，非报表数据）
├── portal/               ★ 工作台
│   ├── PortalTemplates.java          9 套模板 + fallback 的静态表
│   ├── PortalViews.java             Portal / Section / Card / TodoSource / CardRef / Config / PinResult
│   ├── PortalSectionProvider.java   五区 SPI（降级隔离点）
│   ├── PortalSections.java          todo/actions/metrics/list/messages 五个 @Component
│   ├── PortalService.java           装配 + config 读写 + pin
│   ├── PortalController.java        /portal、/portal/config、POST /reports/{id}/pin
│   ├── PortalCardConfigEntity.java
│   └── PortalCardConfigRepository.java
├── reports/              ★ 报表
│   ├── ReportDefinitionEntity.java / ReportDefinitionRepository.java   ← T1（读模型）
│   ├── ReportLookup.java / ReportCards.java                            ← T1（portal 只依赖这个接口）
│   ├── ReportDatasetsController.java                                   ← T1（/reports/datasets 别名）
│   ├── ReportViews.java / ReportDefinitionCodec.java / ReportScopes.java
│   ├── ReportService.java / ReportController.java
├── support/
│   ├── ShareVisibility.java      ★ 五档共享可见性谓词（T1）
│   ├── WatermarkSheet.java       ★ xlsx 水印写出（T2）
│   └── WatermarkCsv.java         ★ CSV BOM/元信息写出（T2）
```

**依赖方向（唯一，无环）**：`controller → portal → reports → analytics → support`，`report/* → support`。

- `portal` 读报表卡片/我的列表只经 `reports.ReportLookup`（`ReportCards` 实现），因此 **M1 阶段不依赖 M2 的 `ReportService`**，T1 可独立编译并发证。
- `POST /reports/{id}/pin` 放在 `PortalController`（不是 `ReportController`）：pin 的写入对象是 `portal_card_config`，若放 reports 包会形成 `reports ↔ portal` 包环；Spring 允许两个控制器共用 `/reports` 前缀的不同路径。
- `reports` 包内**不出现** `JdbcTemplate`、`SELECT `、`vendorId/vendor_id` 字面量（AC-4/AC-11 的 grep 断言），可见性全部委托 `support.ShareVisibility`。

## 3. 数据模型与存储（只映射，不改表）

### 3.1 `report_definition` → `ReportDefinitionEntity`

| 列 | 映射 | 说明 |
|---|---|---|
| `id` | `@Id @GeneratedValue(IDENTITY) Long` | |
| `tenant_id` | `Long` | 全部读写以 `TenantContext.require().tenantId()` 收口 |
| `name` | `String length=60` | `<原名>-副本` 也受 60 限制，超长截断后再加后缀 |
| `dataset` | `String length=16` | 4 个 `AnalyticsDatasets` code 之一 |
| `definition_json` | `String` + `@Column(columnDefinition = "JSON")` | **原样保存前端 wire 形态**（见 §5.2），GET 直接回显 |
| `chart_type` | `String length=8` | `TABLE`（默认）/`BAR`/`LINE`/`PIE` |
| `owner_id` / `owner_role` | `Long` / `String length=32` | `owner_role` 取调用者主角色码 |
| `scope` / `scope_ref` | `String length=8` / `Long` | 五档；PRIVATE/PUBLIC 时 `scope_ref=NULL` |
| `status` | `String length=8` | **只写 `ACTIVE`/`DELETED`**（V10 注释的 `TOMBSTONE` 超出列宽，不采用） |
| `active_flag` | 不映射 | 生成列，唯一键 `uk_report_name(tenant_id,owner_id,name,active_flag)` 的一半；软删后同名可重建 |
| `created_at/updated_at/created_by/updated_by/version` | 映射 | `updated_at` 由应用写（`ON UPDATE` 只在列值变化时触发，幂等场景见 §6.5） |

Repository（派生查询，不写 JPQL/原生 SQL）：

```java
Optional<ReportDefinitionEntity> findByIdAndTenantId(long id, long tenantId);
Optional<ReportDefinitionEntity> findByIdAndTenantIdAndStatus(long id, long tenantId, String status);
Page<ReportDefinitionEntity> findByTenantIdAndOwnerIdAndStatusAndNameContainingOrderByUpdatedAtDesc(
        long tenantId, long ownerId, String status, String keyword, Pageable page);
boolean existsByTenantIdAndOwnerIdAndNameAndStatus(long tenantId, long ownerId, String name, String status);
List<ReportDefinitionEntity> findByTenantIdAndStatusOrderByUpdatedAtDesc(long tenantId, String status);
```

`SHARED` 段不进 SQL：取 `findByTenantIdAndStatusOrderByUpdatedAtDesc` 后在内存用 `ShareVisibility.visible(...)` 过滤 + 分页（同租户报表数量级为百，AC-20 列表 500ms 足够；换来的是**可见性谓词只有一处**，SQL 里不会再出现第二份 scope 条件）。

### 3.2 `portal_card_config` → `PortalCardConfigEntity`

`(tenant_id, role_code, user_id)` 唯一。两类行，互不覆盖：

- `user_id = 0`：管理员配置的**角色级显隐**，`role_code` ∈ `PortalTemplates.roleCodes()`（`PLATFORM_ADMIN/PRODUCT/BUSINESS/SUB_BUSINESS_LEADER/PMO/DEV/QA/MGMT/VENDOR`），`card_keys` = 模板卡 key 的逗号串（可空串=全隐藏）。
- `user_id = <当前用户>`：个人 pin，`role_code` 记调用者当时的主角色码，`card_keys` = `report:<id>` 逗号串，顺序即挂载顺序。

`card_keys VARCHAR(200)` 上限：模板卡 ≤6 个 key（当前 4 个）；个人卡 4×`report:` 前缀足够。写入前校验长度，超长截断不静默（抛 `PORTAL_CARD_LIMIT`）。

Repository：`Optional<PortalCardConfigEntity> findByTenantIdAndRoleCodeAndUserId(long, String, long)`、`List<PortalCardConfigEntity> findByTenantIdAndUserIdOrderByIdAsc(long, long)`。

### 3.3 不新增表/列

角色 id（`ROLE` 共享的 `scope_ref`）与角色目录只读：`AnalyticsDirectory` 用 `NamedParameterJdbcTemplate` 查 `sys_role(id, tenant_id, code)`（两列，带 `tenant_id` 参数，不拼业务过滤）。这是**授权目录**不是报表数据，因此不违反「取数单点」，且不出现在 `portal/reports` 包内。

## 4. API 契约

约定：网关前缀 `/api`，`D=/api/deliver`；响应体一律 Jackson 序列化 record；错误体 `{code,message,detail}`（平台既有 `ApiException` 渲染器）。分页沿用 `PageResponse{items,total,page,size}`，`page` 从 **1** 开始，默认 `page=1&size=20`。

### 4.1 数据集

**`GET $D/analytics/datasets`** · `GET $D/reports/datasets`（别名）

- 参数：无。权限：`PORTAL_VIEW`（路由表 `^/analytics`）/ `REPORT_VIEW`（`^/reports`）。
- 返回 `200 {items:[{code,label,fields:[{key,label,kind,measure}]}]}`，`items` 固定 4 项、顺序 `requirement,delivery,approval,quality`。
- 两个端点都返回 `AnalyticsDatasets.catalog()` 的**同一个 record 实例**（`AnalyticsViews.DatasetList`），序列化逐字节相同；`new AnalyticsViews.Dataset` 仍只在 `AnalyticsDatasets` 一处。

### 4.2 工作台

**`GET $D/portal`**

- 参数（与 `/analytics/metrics` 同名同义）：`domainId,vendorId,preset(7d|30d|90d|quarter|custom),from,to,reqType`。
- 返回 `200 Portal{template,templateLabel,todoMode,sections[5],todoSources[],dataAsOf,cache}`。
- `sections` 顺序固定 `todo→actions→metrics→list→messages`；每区 `{key,title,state:OK|DEGRADED,error,cards[]}`。
- 单区失败只把该区置 `DEGRADED`（`error` = `ApiException.message()` 或异常类简名），**整体仍 200**。
- `sections[2].cards`（数据卡片）：个人卡优先 + 模板卡补足，总数 ≤4；模板卡 `metrics` 直接取自同一次 `metrics()` 的 `Metric`（与 `/analytics/metrics` 的 `value` 逐位相同），个人卡 `metrics` 取该报表 `crossTab` 的总计行 + `AnalyticsMetrics.spec(measureKey)`（不新写公式）。
- `todoMode`：`mgmt` → `READONLY` 且 `todoSources=[]`；其余 `FULL`。`todoSources[].count` 来自 `AnalyticsMetrics` 的计数量指标（`req.pendingTest`/`req.newCount`/`req.total`），源失败 → `count=null,state=DEGRADED,error`，**不以 0 冒充**。
- `dataAsOf` = 各成功区 `dataAsOf` 的最大值（ISO-8601 本地时间）；`cache` = 任一次聚合 `MISS` 则 `MISS`，否则 `HIT`；同样写响应头 `X-Report-Cache`。

**`GET $D/portal/config?roleCode=DEV`**

- `roleCode` 可省略 → 取调用者有效角色码。权限：路由表 `^/portal` 的 `PORTAL_VIEW`；**控制器再判**：`roleCode ≠ 自己` 且 `!TenantContext.can("PORTAL_CONFIG")` → `403 FORBIDDEN`，`detail.functions=["PORTAL_CONFIG"]`（`AnalyticsErrors.forbidden(...)`）。
- 返回 `200 {roleCode,cardKeys[],maxCards:4,cards:[{key,title,reportId:null}]}`；`cards` 是该角色模板的**全部**可配卡（未配置过 → `cardKeys` = 全部卡 key）。
- 未知 `roleCode` → `400 REPORT_UNKNOWN_FIELD`，`detail.field="roleCode"`。

**`PUT $D/portal/config`** body `{roleCode,cardKeys[]}`

- 权限：路由表 `PORTAL_CONFIG`（`pm` → `403 FORBIDDEN detail.functions=["PORTAL_CONFIG"]`，由拦截器产出）。
- 只做显隐：`cardKeys` 必须是该角色模板卡 key 的子集（顺序按传入）；未知 key → `400 PORTAL_UNKNOWN_CARD`，`detail.known=[...]`；>4 → `400 PORTAL_CARD_LIMIT`，`detail.maxCards=4`。
- 幂等 upsert 单行 `(tenant, role_code, user_id=0)`：先查再写，**即使 card_keys 未变也写 `updated_at=now`**（否则 MySQL 不触发行更新，AC-5 的 `updated_at` 变化断言会失败）。
- 响应同 GET；写审计 `portal.config`（`bizType=portal_card_config`）。

**`POST $D/reports/{id}/pin`**

- 权限：`REPORT_VIEW`（路由表）。报表不可见 → `404 REPORT_NOT_FOUND`。
- 幂等：已 pin → `200 {pinned:true,roleCode,cards[]}`，不新增行。
- 已有 4 张且非重复 → `409 PORTAL_CARD_LIMIT`，`detail={maxCards:4,cards:[{key,title,reportId}]×4,cardKeys:["report:1",…]}`（`cardKeys` 仅为旧契约兼容）。
- 成功：把 `report:<id>` 追加到个人行 `(tenant, role_code=<我的有效角色码>, user_id=<我>)`，返回当前全部个人卡。
- 审计 `report.pin`（`bizType=report_definition`, `bizId=报表 id`）。
- 注：PRD 未定义「取消 pin」，本轮不增删契约；409 弹层里的「撤一张」需下一轮加 `DELETE /reports/{id}/pin`（已记入 state 待办）。

### 4.3 报表（wire 类型见 §5.2）

| 方法/路径 | 入参 | 返回 | 规则 |
|---|---|---|---|
| `POST /reports` | `Cmd{name,datasetId,definition,chartType}` | `201 Detail` | 默认 `scope=PRIVATE`、`chartType=TABLE`；`AnalyticsDatasets.check` 全量校验；重名 → `409 REPORT_NAME_DUPLICATED`（DB 唯一键兜底并发，捕获 `DataIntegrityViolationException` 同码） |
| `GET /reports?segment=MINE\|SHARED&keyword&page&size` | — | `PageResponse<Summary>` | `MINE`=属主查询；`SHARED`=同租户可见但非私有、非本人，内存过滤后分页；`keyword` 对 name 忽略大小写包含 |
| `GET /reports/{id}` | — | `Detail` | 不可见 → `404 REPORT_NOT_FOUND`（**管理员同样 404**，AC-8） |
| `PUT /reports/{id}` | `Cmd`（字段可省） | `Detail` | 仅属主；可见但非属主 → `403 REPORT_SCOPE_FORBIDDEN`；改 `definition`/`datasetId` → `cache.evictReport(id)`；`version+1` |
| `DELETE /reports/{id}` | — | `204` | 仅属主；`status=DELETED`；`evictReport`；同名可再建（生成列） |
| `POST /reports/{id}/copy` | — | `201 Detail` | 可读者可复制；`name=<原名>-副本`，重名追加 `-2`…`-9`；`scope=PRIVATE`、`owner=调用者`、定义逐字复制 |
| `PUT\|POST /reports/{id}/share` | `ShareCmd{scope,scopeRef}` | `Detail` | 仅属主（否则 `403 REPORT_SCOPE_FORBIDDEN`）；`POST` 兼容前端、`PUT` 满足 AC-9；两方法均 `@RequiresPermission("REPORT_SHARE")` |
| `POST /reports/{id}/run` | `RunCmd{definition?,domainId?,vendorId?,preset?,from?,to?}`（query 同名参数可覆盖） | `CrossTab` | `definition` 省略用已存定义，传了则**只试算不写库**；`maxRows=MAX_GROUPS(5000)`；响应头 `X-Report-Cache` 与 body `cache` 一致 |
| `GET /reports/{id}/export?format=xlsx\|csv&…` | `format` 默认 `xlsx` | `200 byte[]` / `400` | 先用 `countGroups` 判 `>10000` → `REPORT_EXPORT_LIMIT`；再 `crossTab(..., EXPORT_MAX_ROWS, id, header)`；文件名 `report-<id>-<name>-<yyyyMMdd>.<ext>`，`Content-Disposition: attachment; filename*=UTF-8''<rfc5987>` |

- **导出不越权**：可见性判定与 `run` 同一入口；行集只由 `AnalyticsSql`+`ScopeSql` 决定（vendor1 导出=其 run 的组数）。
- **审计**：`report.create/update/delete/copy/share/run/export`，`bizType=report_definition`，AC-12 只硬性要求 `report.export`。
- **`scope` 规范化**：入参 `TENANT` → 落库与响应均为 `PUBLIC`；`PRIVATE/PUBLIC` 必须 `scopeRef=null`；`ROLE/DOMAIN/VENDOR` 必须给数值 `scopeRef`（DTO 接受数字或数字串，非数值 → `400 REPORT_UNKNOWN_FIELD detail.field="scopeRef"`）；非法 `scope` → 同码 `detail.field="scope"`。

## 5. 关键实现点

### 5.1 口径单点（AC-4）

- `AnalyticsMetrics` 之外不出现比率/除法：portal 卡的模板指标**复用** `Metric` record，个人卡的总计值来自 `CrossTab.total`；`reports` 的 `run` 只透传 `crossTab`。
- 模板卡读数的取数方式：一个 `metrics(req, keys, header)`，`keys` = 当前区所有模板卡指标 key 的**去重并集**（排序后传入，保证同一账号同一请求共享缓存条目）；每张卡按自己的 `metricKeys` 从结果里取子集，**不再二次取数**，因此与 `GET /analytics/metrics?keys=k` 的 `value` 同源同值。
- 数据集白名单：`AnalyticsDatasets.catalog()` 是唯一装配点；`ReportDefinitionCodec` 与 `run` 只调用 `AnalyticsDatasets.check`。

### 5.2 wire ↔ 引擎定义（AC-7 往返 + 前端契约）

前端 `ReportDefinition` 与后端 `AnalyticsViews.ReportDefinition` 字段名不同，必须在**边界一次转换**，不得改引擎 record：

| wire（前端/`definition_json`） | 引擎（`AnalyticsViews`） |
|---|---|
| `datasetId` | 不参与（数据集由实体列给） |
| `rows` | `rows` |
| `columns` | `cols` |
| `measures` | `measures` |
| `filters[{field,op,value}]`，`op ∈ =,!=,>,>=,<,<=,IN`，`value: string\|string[]` | `filters[{field,op,values}]`，`op ∈ eq,ne,gt,gte,lt,lte,in`，`values: List<String>` |

- `ReportDefinitionCodec`：`parse(JsonNode)` / `engine(dataset,dto)` / `store(dto)` / `read(json)` / `dimCount` / `measureCount`。
- **落库保存的是 wire 形态**（`value` 保持标量或数组原样、`op` 保持符号原样、`datasetId` 归一为顶层值），因此 `GET .definition` 与 `POST .definition` 经 `jq -S` 深比较必然相等（AC-7 证据）。`op` 的符号→引擎码映射只发生在 `engine()`，不影响回显。
- 未知字段 / 跨数据集字段 / 超限 / 非法算子全部由 `AnalyticsDatasets.check` 抛既有 12 码（AC-2 负向）。
- `null` 列表按 `[]` 参与 `check`，但**不写回**存储（保持入参原样，避免往返不等）。

### 5.3 可见性（AC-8/AC-9/AC-11）

`support.ShareVisibility`：

```java
public record Viewer(long userId, long tenantId, Set<Long> roleIds, Set<Long> domainIds,
                     Long vendorId, boolean admin) {}
public static Viewer viewer();                     // TenantContext.principal() + AnalyticsDirectory.roleIds(...)
public static boolean visible(String scope, Long scopeRef, long ownerId, Viewer v);
```

- 规则：属主恒可见；`PRIVATE` 仅属主；`ROLE` → `scopeRef ∈ v.roleIds()`；`DOMAIN` → `scopeRef ∈ v.domainIds()`；`VENDOR` → `scopeRef == v.vendorId()`；`PUBLIC` → 同租户。
- `admin` **不参与**判定（AC-8：管理员对他人私有报表同样 404）；`admin` 只用于 `PORTAL_CONFIG` 的跨角色读。
- 共享报表 `run/export` 一律按**查看者** `AnalyticsRequest` 取数：`AnalyticsRequest#cacheKey` 已混入 tenant/userId/tier/domainIds/vendorId，`AnalyticsCache` 不会回放属主缓存（AC-10 换账号首跑必 MISS）。

### 5.4 模板与装配（AC-3/AC-5/AC-6）

`PortalTemplates` 静态表，`resolve(principal)` 优先级（**厂商先于角色**，因为 `vendor1` 的角色是 `PMO`）：

1. `PLATFORM_ADMIN` → `admin`
2. `vendorId != null` → `vendor`（roleCode `VENDOR`）
3. 按固定序命中：`PRODUCT→product`、`PMO→pmo`、`SUB_BUSINESS_LEADER→sub-business`、`SUB_BUSINESS→sub-business`、`BUSINESS→business`、`DEV→dev`、`QA→qa`、`MGMT→mgmt`
4. 其余（`OPS` 等）→ `fallback`

每套模板 4 张卡（`maxCards=4`），卡 key 全局唯一（如 `overview/pending/cycle/quality/effort`），每卡 1–2 个 `AnalyticsMetrics` key；`todo` 1–3 个 tile 指向 `/requirements`、`/quality-gates`、`/test-reports` 等既有路由；`actions` 2–3 个快捷入口。`mgmt` 的 `todoMode=READONLY`、todos 为空。

五区由 `PortalSectionProvider` 五个 bean 各自装配，`PortalService` 逐个 `try/catch`：

```java
public interface PortalSectionProvider {
    String key();          // todo|actions|metrics|list|messages
    int order();           // 1..5，决定 sections 顺序
    PortalViews.Section build(PortalContext ctx);
}
public record PortalContext(UserIdentity user, String template, String todoMode,
                            AnalyticsRequest request, String[] cacheHeader,
                            List<PortalViews.CardRef> personalCards) {}
```

- `metrics` 区：个人卡（≤4，按 `portal_card_config` 顺序，跳过已删/不可见报表）→ 再用角色配置（`user_id=0` 的 `cardKeys`，缺省全部）补足到 4。
- `list` 区：`ReportLookup` 取「我的最近 5 张报表」作为 tile（`metrics=[]`，`link=/reports/{id}`，`reportId` 有值）。
- `messages` 区：静态入口（`/notifications`、`/test-reports`），无计数。
- 任一层抛异常：该区 `DEGRADED`，`error` 用 `ApiException.message()`；`todoSources` 的单个源失败只影响该源。

### 5.5 缓存与失效（AC-10）

- 复用 `AnalyticsCache`（TTL `analytics.cache.ttl-seconds` 默认 300，512 条）。
- `crossTab` key 已含 `cachePrefix(req,reportId)|cross|dataset|signature(def)|cap`；`run` 传真实 `reportId`，因此：同账号同参数 MISS→HIT；换账号 scope 变 → 新 key（MISS）；改定义 → `signature` 变（MISS）且 `PUT/DELETE` 显式 `evictReport(id)` 清掉旧定义条目。
- `PUT` 改定义后「其他报表仍 HIT」自然成立（只清本 id 前缀）。
- `share` 变更**不**清缓存（数据未变，可见性在取数时按查看者 scope 重算）。

### 5.6 导出（AC-12）

- `support/WatermarkSheet.xlsx(CrossTab, Meta)`：`SXSSFWorkbook(200)` + 内存字节；第 0 行合并 `0..colCount-1`、填充 `#EAF1FE`、文本 `报表：<name> · 数据集：<label> · 生成时间：<ts> · 内部资料（水印）`；第 1 行表头（灰底加粗）；随后数据行 + `小计`/`总计` 行；末行页脚水印；`freezePane(2,0)`、列宽按内容 clamp。
- `support/WatermarkCsv.csv(CrossTab, Meta)`：`EF BB BF` + `# 报表：… | 数据集：… | 生成时间：… | 行数：N | 水印：内部资料` + 表头 + 数据 + 页脚；`\r\n` 行尾（Excel 友好），字段含逗号/引号/换行时加引号转义。
- `support/ExportCap.check(long groups)`：`groups > AnalyticsQueryService.EXPORT_MAX_ROWS` → `AnalyticsErrors.exportLimit(groups)`；`export` 在校验后才创建 workbook，**不会产生半截文件**（字节在内存完成后才返回 `ResponseEntity`）。
- `crossTab` 的组数上限从常量 `MAX_GROUPS` 改为调用方 `cap`（`groups > cap`），页面仍 5000、导出 10000；`dashboard` 传 5000 行为不变。
- 行数与页面一致：xlsx/csv 的数据行 = `rows + (subtotal?1:0) + (total?1:0)`，与 `CrossTab` 同源。

### 5.7 错误码（AC-13）

只在 `AnalyticsErrors` 增加：

```java
public static final String UNKNOWN_CARD = "PORTAL_UNKNOWN_CARD";
public static ApiException unknownCard(String key, List<String> known);            // 400
public static ApiException cardLimit(int status, int maxCards,                     // 400 配置 / 409 pin
        List<Map<String, Object>> cards, List<String> cardKeys);
public static ApiException forbidden(String function, String method, String path); // 403 FORBIDDEN
```

- `cardLimit(int)` 旧签名被替换（本轮无其他调用方，编译期暴露）。
- `detail` 必带可定位字段：`field`（未知字段/角色/scope/scopeRef）、`known`、`maxCards`+`cards`、`rows`+`maxRows`。

## 6. 边界与易错点

1. **V10 注释里的 `TOMBSTONE` 不可用**：`status VARCHAR(8)`，只写 `ACTIVE`/`DELETED`。
2. **软删与唯一键**：靠生成列 `active_flag`；并发重建同名要捕获 `DataIntegrityViolationException` 转 `409 REPORT_NAME_DUPLICATED`，不能 500。
3. **`scopeRef` 双向类型**：入参 `number|numeric string`，出参统一字符串（前端 `ReportSummary.scopeRef?: string`）；DB 仍 `BIGINT`。
4. **`TENANT` 只是入参别名**：落库/回显均为 `PUBLIC`（AC-9 逐字断言）。
5. **`admin` 不是后门**：私有报表对管理员也 404；管理员特权只体现在 `PORTAL_CONFIG` 的跨角色读。
6. **两种 4 张上限状态码不同**：`PUT /portal/config` 超限 `400`，`POST /pin` 第 5 张 `409`，共用 `PORTAL_CARD_LIMIT`。
7. **`user_id=0` 哨兵**：角色级与个人级 `card_keys` 语义不同（模板卡 key vs `report:<id>`），装配时不能混。
8. **幂等 `updated_at`**：JPA 脏检查不会在值未变时发 UPDATE，需显式 `setUpdatedAt(now)`。
9. **定义往返**：`definition_json` 存 wire 形态；`columns`≠`cols`、`value`≠`values`、`IN`≠`in` 三处映射只在 `ReportDefinitionCodec.engine()` 做。
10. **导出上限**：`countGroups` 先判 10000，再 `crossTab(cap=10000)`；不要先写流。
11. **分页 1 基**：`PageResponse(page,size)` 与既有 `TestCaseController` 一致（`page` 默认 1）。
12. **`updatedAt` 序列化**：`LocalDateTime.toString()`（ISO），前端只展示。
13. **`GET /portal` 不做整页缓存**：五区各自取数，避免一个失败拖垮全页；`dataAsOf/cache` 由各区合成。
14. **`list` 区读报表**：只经 `ReportLookup`，不直连 repository，保证 M1 可编译、包无环。

## 7. 前端对接（本轮不改，仅核对）

- 类型即契约：`web/src/api/analytics.ts`（`Portal/Section/Card/TodoSource/PortalConfig/PinResult/ReportDetail/ReportDefinition/CrossTab/PageResponse`）。后端 record 字段名逐一对齐；`CrossTab.colGroups?` 前端可选，本轮后端不产出（不阻塞渲染）。
- 视图按既有路由 `lazyView` 约定落位（视图文件尚不存在，导航会抛可读错误，本轮不补页）：`web/src/views/portal/PortalView.vue`、`web/src/views/analytics/DashboardView.vue`、`web/src/views/reports/ReportListView.vue`、`web/src/views/reports/ReportEditorView.vue`。
- 状态管理沿用 `web/src/stores/analytics.ts`（筛选条件/缓存态/数据集），本轮后端只需保证 `cache` 同时出现在 body 与 `X-Report-Cache`。
- 本轮唯一前端动作：`cd web && npm run build` 作为零回归证据（AC-1）。

## 8. 测试策略

**单测（JUnit5 + AssertJ，纯对象，不起 Spring）**

| 测试 | 断言要点 | AC |
|---|---|---|
| `reports/ReportDefinitionCodecTest` | 符号 op↔引擎 op、标量 value→单元素 list、未知字段 `REPORT_UNKNOWN_FIELD.field`、跨数据集 `REPORT_DATASET_MISMATCH`、常量 JSON 往返 `equals` | AC-2/AC-7 |
| `portal/PortalTemplatesTest` | 9 套模板 + fallback；vendor 先于 PMO；`mgmt` READONLY 且 todos 空；每套 4 卡、key 唯一、指标 key ⊆ `AnalyticsMetrics.keys()` | AC-3 |
| `portal/PortalSectionsTest` | mock `AnalyticsQueryService`：注入一个抛错的 provider → 该区 `DEGRADED`、其余 `OK`、整体 200 | AC-13 |
| `portal/PortalPinTest` | 第 5 张 → `ApiException(409,"PORTAL_CARD_LIMIT")` 且 `detail.cards` 长度 4、`maxCards=4`；重复 pin 不新增行 | AC-6 |
| `analytics/ShareVisibilityTest`（或 `support`） | 五档命中/不命中；tenant 不同 false；admin 对 PRIVATE 他人 false；`TENANT→PUBLIC` 规范化 | AC-8/AC-9 |
| `support/WatermarkCsvTest` | 前三字节 `EF BB BF`；首行以 `#` 开头；数据行数 = rows+2 | AC-12 |
| `support/WatermarkSheetTest` | 读回 workbook：首行含「水印」且为合并区、末行页脚、数据行数 = rows+2 | AC-12 |
| `support/ExportCapTest` | 10001 → `REPORT_EXPORT_LIMIT` 且 `detail.maxRows=10000`、10000 通过 | AC-12 |

**端到端证据（AC-1/§2.3 九条）**：`logs/teamflow/<runId>/scripts/r11-evidence.sh`（一个脚本，幂等，所有产物写 `$TMPDIR`）：

1. `mvn -o -q -DskipTests compile` + `cd web && npm run build`；
2. `git status --porcelain` 边界 + `git diff --stat -- web scripts req-service/src/main/resources/db/migration deliver-service/src/main/java/com/example/deliver/quality`（应空）；
3. datasets 4 项 + 别名 `diff <(jq -S)` 相同 + `grep -c "new AnalyticsViews.Dataset"` == 1；
4. 九账号 `/portal` 的 `{template,todoMode,sections keys}` + `sections[2].cards`；
5. portal 卡与 `/analytics/metrics` 同 key 读数相等；
6. 建报表→GET 定义深比较→run 两次 `MISS/HIT`→改定义后 `MISS`、另一报表 `HIT`；
7. PUT share→DOMAIN、`TENANT` 别名回读 `PUBLIC`、dev1 share 403、pm/po1/mgr1 成功、vendor1/vendor2 SHARED 各一条；
8. vendor1/vendor2/pm 三次 run 的 `rowHeaders` 交集为空、`grep -c VEN-B`=0；
9. 导出 xlsx（头 + 字节数 + 首行水印）与 csv（`head -c 3|xxd -p`=`efbbbf`）；连 pin 5 张第 5 条 409 `cards` 长度 4；`pm` PUT config 403；DB 单行/软删 `status` 两条 SQL。

**负向与边界**：未知字段/跨数据集/非法 scope/非法 scopeRef/超限（400）、非属主（403）、不可见（404）、重名与 pin 上限（409）。

## 9. 任务拆分（2 个任务，串行；文件所有权互斥）

> PRD §10 硬约束：开发任务最多 2 个且**串行**（先 datasets+portal，再 reports）。`reads` 只读不占所有权；两个任务的 `files` 无交集，但因为串行，实际不会并发写。

### T1（M1）数据集 + 门户 + 配置 + pin + 报表读模型

- 文件（写）：`analytics/AnalyticsController.java`、`analytics/AnalyticsDatasets.java`、`analytics/AnalyticsErrors.java`、`analytics/AnalyticsQueryService.java`、`analytics/AnalyticsDirectory.java`、`portal/PortalTemplates.java`、`portal/PortalViews.java`、`portal/PortalSectionProvider.java`、`portal/PortalSections.java`、`portal/PortalService.java`、`portal/PortalController.java`、`portal/PortalCardConfigEntity.java`、`portal/PortalCardConfigRepository.java`、`reports/ReportDefinitionEntity.java`、`reports/ReportDefinitionRepository.java`、`reports/ReportLookup.java`、`reports/ReportCards.java`、`reports/ReportDatasetsController.java`、`support/ShareVisibility.java`、测试 `src/test/java/com/example/deliver/portal/*`、`.../analytics/AnalyticsDirectory` 相关。
- 读：`analytics/AnalyticsViews.java`、`AnalyticsRequest.java`、`AnalyticsCache.java`、`AnalyticsMetrics.java`、`AnalyticsSql.java`、`web/src/api/analytics.ts`、`platform-common` 的 `TenantContext/AuditService/PageResponse`、V10 迁移。
- 接口契约：§4.1/4.2 全部；`PortalTemplates` 9 套模板；`ShareVisibility.visible` 五档；`AnalyticsErrors` 三个新工厂。
- 验收：AC-2、AC-3、AC-4、AC-5、AC-6 的 curl 证据；`mvn -o -q -DskipTests compile` 绿；`npm run build` 绿（未改前端）。
- 完成即：按 AC-2/4/11 的 grep 自查（`portal`、`reports` 包内无 `JdbcTemplate`/`SELECT `/`vendorId`）。

### T2（M2）报表全套（CRUD/复制/软删/共享/运行/导出）

- 文件（写）：`reports/ReportViews.java`、`reports/ReportDefinitionCodec.java`、`reports/ReportScopes.java`、`reports/ReportService.java`、`reports/ReportController.java`、`support/WatermarkSheet.java`、`support/WatermarkCsv.java`、`support/ExportCap.java`、测试 `src/test/java/com/example/deliver/reports/*`、`.../support/*`。
- 读：T1 的 `ReportDefinitionEntity/Repository`、`ReportLookup`、`support/ShareVisibility`、`analytics/AnalyticsQueryService`、`analytics/AnalyticsErrors`、`AnalyticsViews.ReportDefinition/CrossTab`、`AnalyticsCache`、`poi-ooxml 5.4.1`。
- 接口契约：§4.3 全部（含 `PUT|POST share` 双方法 + `@RequiresPermission("REPORT_SHARE")`、`run` 草稿试算、导出上限与水印）。
- 验收：AC-7～AC-12 的 curl 证据；AC-10 的 MISS/HIT/换账号/只清自己四条；`mvn compile` 绿。

### 收口（不属于开发任务，交付自检）

- 跑 §8 九条证据脚本，贴输出；不写 `QA-REPORT.md`/`ACCEPTANCE.md`（属 QA/验收阶段）；不做 QA 式全矩阵。
- **git 动作**：留在 `feat/workbench-reports`；不 merge、不打 tag；证据与产物写 `$TMPDIR` 或 `logs/teamflow/<runId>/`；收尾提交一次 `chore: r11 断点存档——analytics datasets / portal / reports 全套后端`（含任务夹，`.gitignore` 已覆盖 `target/ node_modules/ *.xlsx *.csv logs/` 等）。
- state 待办：前端视图、`workbench-verify.sh`/`report-verify.sh`、文档对齐、基数同步、`DELETE /reports/{id}/pin`。

## 10. 风险与回滚

| 风险 | 处置 |
|---|---|
| `AnalyticsQueryService` 草稿（45KB）行为未端到端验证 | T1 先用 `GET /analytics/dashboard` 与 r9 已验收读数对齐，再在它上面加接口；**不重写** |
| JPA `validate` 与 V10 列不符 → 启动失败 | 实体逐列对齐（`definition_json` 用 `columnDefinition="JSON"`，`active_flag` 不映射） |
| `definition_json` 与 wire 字段漂移 | `ReportDefinitionCodec` 单测 + 证据脚本的 `jq -S` 深比较 |
| 缓存跨账号串数据 | 复用 `AnalyticsRequest#cacheKey`（已混 scope）；证据要求换账号首跑 MISS |
| 新路由/权限码使既有套件基数变化 | 只记录到 state 待办，不改既有断言（PRD §10） |
| 导出改到质量导出管道 | 新建 `support/WatermarkSheet`/`WatermarkCsv`，`quality/report/ReportExportService` 零 diff |

**回滚**：本轮全是新增文件 + analytics 4 处小改；回滚 = `git revert` 单次断点提交即可，无 DDL、无数据迁移。
