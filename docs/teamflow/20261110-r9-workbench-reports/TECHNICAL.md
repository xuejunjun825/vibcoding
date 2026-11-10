# TECHNICAL — 岗位工作台 / 数据看板 / 自助报表

> 基线依赖：`20261110-r2-platform`（§4.9 `/portal`、§4.11 `/reports` 的平台规格）、`20261110-r7-role-page-access-fix`（导航准入与 `functionCode` 口径）、`20261110-r8-code-policies-403-fix`（可选能力探测 + `silentForbidden`）
> 取代：`20261110-r8-code-policies-403-fix/#AC-6`（negative 基线 25 → 23）
> 需求：`docs/teamflow/20261110-r9-workbench-reports/PRD.md`；设计：同目录 `DESIGN.md`
> 分支：`feat/workbench-reports`（开发期零提交，验收后一次 commit）

## 0 方案一句话与三处口径修订

**一句话**：在 deliver-service 新增 `analytics` / `portal` / `reports` 三个包，把「需求、周期、人天、交付、质量」的读数收进**唯一**的 `AnalyticsQueryService` + `AnalyticsMetrics`（比率与分位数只在这里算，前端零除法）；工作台、看板、交叉表、导出四个出口都只调用它；`V10` 由 req-service Flyway 出两张表（`report_definition`、`portal_card_config`）；前端新增四条路由、三个页面族，`/workbench` 一字不动。

写作前逐条核对了既有代码，PRD 的三处口径必须落到实现里的修订（**不是可选建议**，QA 与验收按这里判定）：

| 编号 | PRD 写法 | 代码事实 | 本方案落法 |
|---|---|---|---|
| D-1 | 审批数据集业务类型含「评审入池 / 发布 / 变更 / 上线」 | 审批实例真表是 `wf_instance` / `wf_task`（`ApprovalInstance`→`wf_instance`、`ApprovalTask`→`wf_task`，JPA）；**两表未登记 `ScopeSql`**，打 `/*scope:*/` 标记会抛错；`REQ_DEPLOY` 的 `biz_id` 是发布单，连不上 `requirement`，一旦放进来厂商账号就会读到别人的发布审批 | `approval` 数据集**只覆盖需求类审批** `biz_type IN ('REQ_SUBMIT','REQ_REVIEW')`，作用域靠 `JOIN requirement r ON r.id = ai.biz_id` 上的 `/*scope:r*/` 收口；发布类审批不进报表（其信息在 `delivery` 数据集与发布单页）。`report-verify r5` 断言结果里 `bizType` 只出现这两个值 |
| D-2 | `req.rejected`＝「状态为已驳回的需求数」 | `StateTables` 的 `BUS` 没有 `REJECTED` 状态，驳回是 `reject` → 回到 `SUBMITTED`；`wf_instance.status` 才有 `REJECTED` | `req.rejected` 定义为「最近一次 `REQ_SUBMIT`/`REQ_REVIEW` 实例为 `REJECTED`、且需求未终态」，取 `MAX(id)` 那一条；口径写进卡片 tooltip 与 `MetricDefinition` 文案 |
| D-3 | 导出复用既有水印管道（草稿曾写「改造 `ReportExportService.xlsx()` 委托新 Sheet」） | 既有实现是 `byte[] xlsx(detail, watermark)`（包级私有），水印**是灰字**（`GREY_50_PERCENT` 9pt 右对齐），没有 `#EAF1FE` 填充；改造它会动 M6 质量导出的视觉，撞 `q3` 零回归 | **不动** `deliver-service/.../quality/report/ReportExportService.java`；新建 `deliver-service/.../support/WatermarkSheet.java` 只服务新报表导出（首行 `#EAF1FE` 合并 + 页脚水印 + 冻结表头）。`q3` 与 `report-verify` 各自守自己的导出 |

另外两条硬口径（PRD 已写，这里给出实现位置，防止第二份实现）：

- **完成态**只有一个来源：`platform-contract/.../contract/RequirementStates.java` 由 `StateTables.isTerminal(BUS|PDR|RD)` **推导**（推导结果 `{CLOSED, ACCEPTED_CLOSED, DONE}`），任何地方不得再写字面量集合。草稿里的 `MERGED`/`CANCELLED` 是错的：`MERGED` 是 RD 的中途态（`merge` → `MERGED` → `finish` → `DONE`），`CANCELLED` 并不存在。
- **P90** 一律 `FlowMetricFormulas.p90Dwell(List<Long> seconds)`（nearest-rank，`ceil(0.9n)` 1 基），`analytics` 包内不得出现第二个分位数实现；`avgDwell` 同理（返回“天”，`DWELL_SCALE=1`）。

## 1 落点与既有代码对齐

| 关注点 | 结论 | 依据 |
|---|---|---|
| 放哪个服务 | `deliver-service`（`com.example.deliver.{analytics,portal,reports}`） | 已有跨域只读需求（`FlowMetricService` 读 `requirement`/`audit_log`）、POI 水印导出（`ReportExportService`）、`ScopedJdbc`（97 处）、`PageResponse`、`ApiException` |
| 网关 | 不改 | `PlatformRoutes` 已把 `/api/deliver/**` → `lb://deliver-service`（order -90） |
| 迁移 | `req-service/src/main/resources/db/migration/V10__workbench_reports.sql` | deliver 无 Flyway；现网最新为 `V9` |
| 隔离 | 所有读走 `/*scope:alias*/` → `ScopedJdbc` | `ScopedJdbc.MISSING_MARKER = "scope marker missing in SQL"` |
| 权限码 | `platform-contract/.../contract/PermissionMatrix.java` +4 | 现 56 码 / 10 角色 |
| 路由准入 | `platform-common/.../security/PermissionRouteTable.java` 新段**必须插在** `^/releases`、`^/deploy-orders`、`^/test-plans` 兜底之前 | 首匹配生效，末尾追加是静默 no-op |
| 审计 | `AuditService.record(action, bizType, bizId, detail)` | 导出/共享/删除各一条（`report.export` / `report.share` / `report.delete`） |
| 前端路由 | 新建 `web/src/router/routes/analytics.routes.ts`，`router/index.ts` 的 `import.meta.glob('./routes/*.ts')` 自动汇总，无需改 index | 既有约定 |
| 错误呈现 | `ApiError`（403 跳 `/403`、409 绝不 toast） | `web/src/api/client.ts` |

## 2 数据模型与 V10 迁移

### 2.1 `report_definition`（报表定义）

```sql
CREATE TABLE report_definition (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    name            VARCHAR(60)     NOT NULL,
    dataset         VARCHAR(16)     NOT NULL,               -- requirement|delivery|approval|quality
    definition_json JSON            NOT NULL,               -- 见 2.3
    chart_type      VARCHAR(8)      NOT NULL DEFAULT 'TABLE',-- TABLE|BAR|LINE|PIE
    owner_id        BIGINT          NOT NULL,
    owner_role      VARCHAR(32)     NULL,
    scope           VARCHAR(8)      NOT NULL DEFAULT 'PRIVATE', -- PRIVATE|ROLE|DOMAIN|PUBLIC
    scope_ref       BIGINT          NULL,                   -- ROLE→role_id / DOMAIN→domain_id
    status          VARCHAR(8)      NOT NULL DEFAULT 'ACTIVE',-- ACTIVE|DELETED（软删）
    active_flag     TINYINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', 1, NULL)) STORED,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by      BIGINT          NULL,
    updated_by      BIGINT          NULL,
    version         INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_name (tenant_id, owner_id, name, active_flag),
    KEY idx_report_owner (tenant_id, owner_id, status, updated_at),
    KEY idx_report_scope (tenant_id, scope, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

**为什么用生成列而不是 `uk(...,status)`**：软删后同名同属主的报表会与「已删除的同名行」撞唯一键，第二次删除直接 500。`active_flag` 在 `ACTIVE` 时为 1、`DELETED` 时为 `NULL`，MySQL 的唯一索引不判 `NULL` 重复 —— 与既有 `wf_instance.uk_wf_instance_active` 同一手法，重名只在**活跃**行之间互斥。

### 2.2 `portal_card_config`（工作台卡片配置）

```sql
CREATE TABLE portal_card_config (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    role_code  VARCHAR(32) NOT NULL,
    user_id    BIGINT      NOT NULL DEFAULT 0,   -- 0 = 角色级（NULL 不判重，故用哨兵）
    card_keys  VARCHAR(200) NOT NULL,            -- 逗号分隔，≤4 项
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_portal_card (tenant_id, role_code, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

`user_id = 0` 是**角色级**哨兵：`UNIQUE` 对 `NULL` 不判重，用可空列会让「同一角色的配置」插出重复行。

### 2.3 定义 JSON（服务端唯一 schema）

```json
{"rows":["priority"],"cols":["status"],"measures":["req.count","req.avgDwell"],
 "filters":[{"field":"domainId","op":"IN","values":["1","2"]}],
 "sort":{"by":"req.count","dir":"DESC"}}
```

上限（服务端常量，前端只做同值提示）：`rows` 1–3、`cols` 0–2、`rows+cols ≤ 4`、`measures` 1–3、`filters ≤ 8`、`sort.by` 必须是已选度量或行字段。

### 2.4 迁移附加项与边界

- 两张新表**不登记** `ScopeSql`（PRD AC-13）：它们只被 JPA 以 `tenant_id` 过滤访问，永不出现 `/*scope:*/` 标记；`report-verify r0` 断言 `ScopeSql.scopedTables()` 里没有这两个名字（登记了反而会诱导后续代码用标记绕过属主判定）。
- 追加索引 `ALTER TABLE requirement ADD KEY idx_req_created (tenant_id, created_at, status)`：看板与报表按时间窗扫描（AC-18 的 P95 靠它），现有 `idx_req_list(type,status,domain_id,updated_at)` 的第二列是 `type`，`created_at` 范围扫描用不上。
- 幂等：`CREATE TABLE IF NOT EXISTS` + `ADD KEY` 前 `SELECT` 判断（Flyway 只跑一次，仍写成可重跑，方便 `q0` 式脚本直跑同一份 SQL）。
- `SeedService` 增 `mgr1`（`MGMT`，无 vendor）进 `userDefs`：`ensure()` 幂等，重复 `make platform-seed` 计数不变（`AC-19` 的种子断言）。

## 3 唯一聚合引擎（`com.example.deliver.analytics`）

### 3.1 类与签名（下游任务必须逐字调用）

```java
// AnalyticsRequest.java —— 一次查询的入参（也是缓存 key 的原料）
public record AnalyticsRequest(Long domainId, Long vendorId, LocalDate from, LocalDate to,
                               String preset, String reqType) {
    public AnalyticsRequest resolve(LocalDate today);      // preset: 7d|30d|90d|quarter|custom
    public String cacheKey(String tenant, long userId);     // scope 指纹 + 入参
}

// AnalyticsDatasets.java —— 白名单唯一真相（字段 → SQL 表达式的唯一映射）
public final class AnalyticsDatasets {
    public static final List<String> CODES;                 // requirement, delivery, approval, quality
    public static boolean knows(String dataset);
    public static AnalyticsViews.Dataset dataset(String code);   // 未知 → ApiException REPORT_UNKNOWN_FIELD
    public static AnalyticsViews.Field field(String dataset, String fieldKey);
    public static String measuresFor(String dataset);       // 默认度量
}

// AnalyticsMetrics.java —— 指标字典 + 唯一公式入口
public final class AnalyticsMetrics {
    public static AnalyticsViews.MetricSpec spec(String key);            // label/unit/digits/caliber
    public static AnalyticsViews.Metric compute(String key, Number num, Number den, List<Long> samples);
    public static Double p90(List<Long> seconds);           // 委托 FlowMetricFormulas.p90Dwell
    public static Double avgCycle(List<Long> seconds);      // 委托 FlowMetricFormulas.avgDwell
    public static Double completionRate(long done, long total); // MetricFormulas.passRate
    public static Double defectDensity(long defects, long reqs); // MetricFormulas.defectDensity
}

// AnalyticsSql.java —— FROM/JOIN + 标记 + 维度分桶，全部 SQL 文本只在这里拼
public final class AnalyticsSql {
    public String base(String dataset);                     // 含 /*scope:r*/ 等标记，无 WHERE
    public String column(String dataset, String fieldKey);  // 白名单表达式
    public String bucket(String fieldKey, String unit);     // WEEK|MONTH（DATE_FORMAT）
    public boolean isMeasure(String dataset, String fieldKey);
}

// AnalyticsCache.java —— 进程内 TTL 缓存（无 Redis/Caffeine，与既有约定一致）
@Component public class AnalyticsCache {
    public static final String HIT = "HIT"; public static final String MISS = "MISS";
    public <T> T get(String key, Class<T> type, String[] header);   // header[0] 回填 HIT/MISS
    public void put(String key, Object value);
    public void evictReport(long reportId);                  // 保存/删除/共享后按前缀失效
    public void invalidateAll();
}

// AnalyticsQueryService.java —— 三个出口共用
@Service public class AnalyticsQueryService {
    public AnalyticsViews.Metrics metrics(AnalyticsRequest req, List<String> keys, String[] cacheHeader);
    public AnalyticsViews.Dashboard dashboard(AnalyticsRequest req, String bucket, String[] cacheHeader);
    public AnalyticsViews.CrossTab crossTab(String dataset, ReportDefinition def,
                                            AnalyticsRequest req, int maxRows, String[] cacheHeader);
    public long countGroups(String dataset, ReportDefinition def, AnalyticsRequest req);
}

// AnalyticsViews.java —— 所有出参（record）
public record Metric(String key, String label, String unit, int digits,
                     Double value, Double prev, Double delta, String caliber, String asOf);
public record Metrics(String dataAsOf, String cache, List<Metric> items);
public record Dataset(String code, String label, List<Field> fields);
public record Field(String key, String label, String kind, boolean measure); // kind: DIM|TIME|MEASURE
public record CrossTab(List<String> rowHeaders, List<String> colHeaders,
                       List<CrossRow> rows, CrossRow subtotal, CrossRow total,
                       boolean truncated, String dataAsOf, String cache);
public record CrossRow(String key, List<Double> values, List<Long> counts);
public record Dashboard(List<Breakdown> breakdowns, Trend trend, List<Segment> segments,
                        Effort effort, List<VendorRank> vendorRank, Quality quality,
                        String dataAsOf, String cache);
```

`AnalyticsController`（`GET /analytics/metrics`、`GET /analytics/dashboard`）只做参数绑定与缓存头回填，不写 SQL。

### 3.2 指标口径表（`AnalyticsMetrics` 唯一实现）

| key | 口径 | 实现要点 |
|---|---|---|
| `req.total` | 时间窗内新建需求数 | `COUNT(*)`，`created_at ∈ [from, to]` |
| `req.newCount` | 同 `req.total` 的环比口径 | `prev` 用上一等长窗口 |
| `req.inPoolRate` | `status='POOLED'` / 总数 | 比率后端算 |
| `req.completionRate` | `RequirementStates` 完成态 / 总数 | **不得写字面量** |
| `req.splitRate` | 有子需求的需求数 / 总数 | `EXISTS(SELECT 1 FROM requirement c WHERE c.parent_id = r.id)` |
| `req.overdueCount` | `plan_end_date < CURDATE()` 且未完成 | 未完成 = `NOT IN complete` |
| `req.pendingTest` | `status='PENDING_TEST'` | RD 流 |
| `req.mineOpen` | `owner_user_id = :uid` 且未完成 | 取 `UserIdentity.userId` |
| `req.rejected` | 最近一次 `REQ_SUBMIT`/`REQ_REVIEW` 实例 `REJECTED` 且未终态 | D-2 |
| `req.avgDwell` | 需求存活期（创建→关闭）均值 | 秒样本 → `avgDwell` |
| `cycle.avg` | 周期均值 | 同 `avgDwell` |
| `cycle.p90` | 周期 P90 | `p90Dwell`（nearest-rank） |
| `effort.estimate` / `effort.actual` / `effort.deviation` | `SUM(estimate_days)` / `SUM(actual_days)` / `(actual-estimate)/estimate` | 无估计 → `null`（前端「—」） |
| `vendor.onTimeRate` | 需求 `closed_at ≤ expected_delivery_date` / 已关闭 | `expected_delivery_date IS NULL` 不计入分母 |
| `vendor.accepted` | 已验收数（`ACCEPTED_CLOSED`/`CLOSED`） | 排名次键用准时率 |
| `quality.passRate` | 用例通过 / 已执行 | `MetricFormulas.passRate` |
| `quality.defectDensity` | 缺陷数 / 需求数 | `MetricFormulas.defectDensity` |
| `quality.releaseSuccess` | 按期发布版本 / 已发布版本 | `release.released_at ≤ planned_release_date` |
| `org.memberCount` / `org.activeReq` | 组织在册人数 / 在办需求 | 模板卡用，`ScopeSql` 只给 tenant 谓词 |
| `workflow.enabled` | 当前租户启用的审批流程数 | 兜底模板的说明条用 |

### 3.3 SQL 形态（唯一一套）

```sql
-- requirement 数据集（其余三个同构，只是 FROM 与字段白名单不同）
FROM requirement r
WHERE /*scope:r*/ AND r.created_at >= :from AND r.created_at < :to + INTERVAL 1 DAY
  AND (:domainId IS NULL OR r.domain_id = :domainId)
  AND (:vendorId IS NULL OR r.assignee_vendor_id = :vendorId)
```

- 交叉表：`SELECT <rowExpr>, <colExpr>, <agg> ... GROUP BY 1,2`；分组数先 `countGroups` 判 ≤5000（超限 `REPORT_GROUP_LIMIT`，前端提示收窄）。
- `approval` 数据集：`FROM wf_instance ai JOIN wf_task t ON t.instance_id = ai.id JOIN requirement r ON r.id = ai.biz_id AND ai.biz_type IN ('REQ_SUBMIT','REQ_REVIEW') WHERE /*scope:r*/`（D-1）。
- 分位数样本：只 `SELECT TIMESTAMPDIFF(SECOND, …)` 单列 + `stream(...)` 逐行喂 `p90Dwell`，不在 SQL 里写 `PERCENTILE`（否则就是第二份实现）。
- 导出与页面**共用同一个 `crossTab(...)`**（同 `maxRows`，页面 5000、导出 10000），这是 AC-9「导出行数与页面一致」的实现前提。

### 3.4 缓存

- key = `sha256(tenantId|userId|scopeTier|scopeDigest|dataset|rows|cols|measures|filters|from|to|bucket)`，`scopeDigest` 由 `DataScope` 四元组摘要生成 —— 跨账号不串（AC-12）。
- TTL 300s（`analytics.cache.ttl-seconds`），`ConcurrentHashMap` + 惰性过期；`HIT`/`MISS` 写响应头 `X-Report-Cache`，body 带 `dataAsOf`（查询开始时刻，`Asia/Shanghai`）。
- 报表保存/删除/共享 → `evictReport(id)`（key 前缀 `report:<id>:`）；导出不复用缓存（每次都现算，保证与审计口径一致）。

### 3.5 错误码（`AnalyticsErrors` 静态工厂，全部 `ApiException`）

| code | HTTP | 触发 |
|---|---|---|
| `REPORT_UNKNOWN_FIELD` | 400 | 字段/度量不在白名单 |
| `REPORT_DATASET_MISMATCH` | 400 | 定义里出现别的数据集的字段 |
| `REPORT_DIM_LIMIT` | 400 | `rows+cols > 4` 或 `rows>3` 或 `cols>2` |
| `REPORT_MEASURE_LIMIT` | 400 | `measures > 3` |
| `REPORT_FILTER_LIMIT` | 400 | `filters > 8` |
| `REPORT_SPAN_LIMIT` | 400 | 时间跨度 > 366 天 |
| `REPORT_GROUP_LIMIT` | 400 | 分组 > 5000（附 `detail.groups`） |
| `REPORT_EXPORT_LIMIT` | 400 | 导出行 > 10000 |
| `REPORT_NAME_DUPLICATED` | 409 | 同属主活跃重名 |
| `REPORT_NOT_FOUND` | 404 | 不存在 / 他人 PRIVATE（**同码**，不泄露存在性） |
| `REPORT_SCOPE_FORBIDDEN` | 403 | 非属主改共享/删除 |
| `PORTAL_CARD_LIMIT` | 409 | 设为卡片 > 4 |

## 4 API 契约（全部挂在 deliver-service，网关前缀 `/api/deliver`）

| 方法 | 路径 | 权限码 | 入参 | 出参 |
|---|---|---|---|---|
| GET | `/analytics/metrics` | `PORTAL_VIEW` | `keys`(逗号) `domainId` `vendorId` `from` `to` `preset` | `Metrics` |
| GET | `/analytics/dashboard` | `PORTAL_VIEW` | 同上 + `bucket=WEEK\|MONTH` | `Dashboard` |
| GET | `/portal` | `PORTAL_VIEW` | 同上 | `Portal`（见 §5） |
| GET | `/portal/config` | `PORTAL_VIEW` | — | `PortalConfig` |
| PUT | `/portal/config` | `PORTAL_CONFIG` | `{roleCode, cardKeys[]}` | `PortalConfig` |
| GET | `/reports` | `REPORT_VIEW` | `segment=MINE\|SHARED` `keyword` `page` `size` | `PageResponse<ReportSummary>` |
| GET | `/reports/datasets` | `REPORT_VIEW` | — | `{items: Dataset[]}` |
| POST | `/reports` | `REPORT_VIEW` | `ReportCmd{name,datasetId,definition,chartType}` | `ReportDetail`（201） |
| GET | `/reports/{id}` | `REPORT_VIEW` | — | `ReportDetail` |
| PUT | `/reports/{id}` | `REPORT_VIEW` | `ReportCmd` | `ReportDetail` |
| POST | `/reports/{id}/copy` | `REPORT_VIEW` | — | `ReportDetail`（201，名 `原名-副本`） |
| DELETE | `/reports/{id}` | `REPORT_VIEW` | — | 204（软删 + 审计） |
| POST | `/reports/{id}/share` | `REPORT_SHARE` | `{scope, scopeRef}` | `ReportDetail` |
| POST | `/reports/{id}/pin` | `PORTAL_CONFIG` | — | `{pinned:true, cards:[...], roleCode}` |
| POST | `/reports/{id}/run` | `REPORT_VIEW` | `{definition?}`（省略用已存定义） | `CrossTab` |
| GET | `/reports/{id}/export` | `REPORT_VIEW` | `format=xlsx\|csv` `domainId` `vendorId` `from` `to` | 二进制 + `Content-Disposition` |

- `X-Report-Cache: HIT|MISS` 出现在 `/analytics/*`、`/reports/{id}/run` 的响应头；导出不带缓存头。
- 二维筛选（`domainId`/`vendorId`）在服务端与 `DataScope` **取交集**：请求里越权的 id 不报错，只会被 `ScopeSql` 更进一步收窄（避免探测）。
- 导出文件名由服务端 `Content-Disposition: attachment; filename*=UTF-8''<urlencoded>` 给出，前端不拼名（DESIGN §9 硬约束）。

## 5 工作台（`com.example.deliver.portal`）

```java
// PortalTemplates.java —— 8 模板 + fallback，代码常量（DB 只存管理员覆盖）
public final class PortalTemplates {
    public static final List<String> TEMPLATES;   // business, sub-business, product, pmo, dev, qa, mgmt, admin, fallback
    public static String templateFor(List<String> roles, boolean hasVendor);   // 多角色取优先级最高者
    public static List<CardSpec> cards(String template);       // record CardSpec(key,title,metricKeys,link,hint)
    public static List<TodoSource> todoSources(String template); // 三源或空（mgmt）
    public static String label(String template);
}

// PortalViews.Portal: record(String template, String templateLabel, String todoMode,
//     List<Section> sections, List<TodoSource> todoSources, String dataAsOf, String cache)
//     todoMode ∈ FULL | READONLY | FALLBACK；Section(key,title,cards[],state,error)
// PortalService: Portal load(AnalyticsRequest req); PortalConfig config(); PortalConfig saveCards(CardSaveCmd cmd);
// PortalController: GET /portal, GET /portal/config, PUT /portal/config
```

- **五区**（设计 §2 的装配顺序）：`todo`（待办三源）→ `overview`（需求概览）→ `cycle`（周期与人天）→ `delivery`（交付与质量）→ `action`（模板卡 + 快捷动作）。
- **一次请求返回五区**，服务端串行 6 条查询（每区一条 + 待办三源合并一条）；任何一块失败只置该块 `state=DEGRADED` + `error`，不 500 整页；待办单源失败只换数字位。
- 卡片装配：`user_id != 0` 的个人配置 > 角色配置 > 模板 presets；总数硬截 4；`mgmt` 渲染 48 高只读条（`todoMode=READONLY`，`todoSources=[]`）；无角色匹配（如 `OPS` 无种子账号）走 `fallback`（`FALLBACK`，说明条 + 只读卡，绝不四张「—」）。
- `PUT /portal/config` 只接受 `cardKeys` 的**显隐**（4 项上限、必须是模板已知 key），不扩契约、不能加自定义卡；写库 `ON DUPLICATE` 语义由 `uk_portal_card`（`user_id=0`）保证幂等（AC-14）。审计：`portal.config`。

## 6 报表（`com.example.deliver.reports`）

```java
@Entity @Table("report_definition") public class ReportDefinitionEntity { /* 2.1 全部列 */ }
public interface ReportDefinitionRepository extends JpaRepository<ReportDefinitionEntity, Long> {
    Page<ReportDefinitionEntity> findByTenantIdAndOwnerIdAndStatusOrderByUpdatedAtDesc(
            long tenantId, long ownerId, String status, Pageable pageable);
    Optional<ReportDefinitionEntity> findByIdAndTenantIdAndStatus(long id, long tenantId, String status);
    boolean existsByTenantIdAndOwnerIdAndNameAndActiveFlag(long tenantId, long ownerId, String name, Integer activeFlag);
}

@Service public class ReportService {
    public PageResponse<ReportSummary> list(String segment, String keyword, int page, int size);
    public ReportDetail get(long id, boolean forWrite);
    public ReportDetail create(ReportCmd cmd);
    public ReportDetail update(long id, ReportCmd cmd);
    public ReportDetail copy(long id);
    public void delete(long id);
    public ReportDetail share(long id, ReportShareCmd cmd);
    public PinResult pin(long id);
    public AnalyticsViews.CrossTab run(long id, ReportDefinition override, AnalyticsRequest req, int maxRows);
}

@Component public class ReportVisibility {     // 可见性唯一判定
    public boolean canRead(ReportDefinitionEntity def, UserIdentity user);
    public boolean canWrite(ReportDefinitionEntity def, UserIdentity user);
}
```

- **共享只共享定义**：`list(SHARED)` 返回 `scope != PRIVATE` 且（`PUBLIC` / `ROLE=我的角色` / `DOMAIN ∈ 我的域`）的定义；**数据永远按查看者 `DataScope` 重算**，共享段常驻提示「行数可能与分享者不同」（DESIGN §4）。
- 可见性两段：`MINE`（`owner_id=me`）/ `SHARED`（其余公开档）；`PRIVATE` 他人 → `REPORT_NOT_FOUND`（404，不泄露存在性）。
- 拖拽/转置/图表切换全是前端行为；服务端只认 `definition` 白名单与上限，以及 `chartType ∈ {TABLE,BAR,LINE,PIE}`。
- 软删：`status='DELETED'` + 审计 `report.delete`；`copy` 复制 `definition_json`/`chart_type`，`scope` 重置 `PRIVATE`。
- `pin(...)`：取属主所在角色的 `portal_card_config(user_id=0)`，已有则幂等返回；追加 `report:<id>` 到 `card_keys`；>4 → `PORTAL_CARD_LIMIT`（409，`detail.cardKeys` 列出当前 4 项）。卡片 key 形如 `report:<id>`，模板 key 形如 `req.overview`，两者同池计数。

### 6.1 导出（`support/WatermarkSheet` + `reports/AnalyticsExportService`）

```java
public final class WatermarkSheet {   // deliver-service/.../support/WatermarkSheet.java
    public static byte[] build(String sheetName, String title, String watermark,
                               List<String> headers, List<List<Object>> rows);
}
@Component public class AnalyticsExportService {
    public Export xlsx(ReportDetail report, AnalyticsViews.CrossTab table, String watermark);
    public Export csv(ReportDetail report, AnalyticsViews.CrossTab table, String watermark);
    public record Export(byte[] bytes, String fileName, String contentType) {}
}
@RestController public class ReportExportController {  // GET /reports/{id}/export —— 独立文件，避免与 T4 争 ReportController
}
```

- XLSX：第 1 行合并 `A1:D1`、填充 `#EAF1FE`、内容为水印（`内部资料 · <租户> · <报表名> · <生成时间>`）；表头加粗 + 冻结（`createFreezePane(0, 2)` 让表头吸顶）；末行页脚水印；`SXSSFWorkbook(500)` 流式，10 万行内存可控。
- CSV：首行元信息 `# 报表,<名>,数据集,<code>,生成时间,<t>,行数,<n>,水印,<...>`，UTF-8 **BOM**；值按 `,`/`"` 转义。
- 行数：与页面同一 `crossTab`，`maxRows=10000`；超过 → `REPORT_EXPORT_LIMIT`（提示收窄筛选，不静默截断）。
- 审计：`report.export`（`deliver-service` 已有同名 action，bizType 换 `report_definition`），detail 含 format/rows/tenant。

## 7 前端

### 7.1 路由与导航

`web/src/router/routes/analytics.routes.ts`（新文件，glob 自动生效）：

| path | name | 组件 | meta |
|---|---|---|---|
| `/portal` | `portal` | `views/portal/PortalView.vue` | `{title:'岗位工作台', navKey:'portal', functionCode:'PORTAL_VIEW'}` |
| `/dashboard` | `analytics-dashboard` | `views/analytics/DashboardView.vue` | `{title:'数据看板', navKey:'analytics-dashboard', functionCode:'PORTAL_VIEW'}` |
| `/reports` | `report-list` | `views/reports/ReportListView.vue` | `{title:'自助报表', navKey:'reports', functionCode:'REPORT_VIEW'}` |
| `/reports/:id` | `report-editor` | `views/reports/ReportEditorView.vue` | `{title:'报表编辑', navKey:'reports', parentPath:'/reports', functionCode:'REPORT_VIEW'}` |

`layouts/nav.ts`：分析域组末尾追加 `portal` / `analytics-dashboard` / `reports` 三项（`functionCode` 必填，漏写 `vue-tsc` 直接红）；`TOP_NAV` 8 项不动 —— 入口从侧栏进，`/portal` 不在顶栏重复出现。`/workbench` 保持原路由与 meta（M6 质量工作台）。

### 7.2 数据层与状态

- `web/src/api/analytics.ts`：`listPortal` `savePortalConfig` `fetchDashboard` `fetchMetrics` `fetchDatasets` `listReports` `getReport` `createReport` `updateReport` `copyReport` `deleteReport` `shareReport` `pinReport` `runReport` `exportReport`（`responseType:'blob'`，文件名从 `Content-Disposition` 解析）；全部走 `request()`，403/409 语义沿用 `ApiError`。
- `web/src/stores/analytics.ts`（pinia，三个页面共用一套筛选）：
  - `filter: {domainId, vendorId, from, to, preset}`、`presetOf()`、`syncFromQuery(route)`、`applyToRoute(router)`（`router.replace`，**唯一真相是 URL**，刷新与深链等价）。
  - `cacheState: 'HIT'|'MISS'|''`、`dataAsOf`：页面右下角「数据截至 hh:mm · 缓存命中」。
  - `portal: {template, sections, todoMode}`、`dashboard`、`editor: {definition, dirty}` 分片，`$reset()` 按页面切换调用。
- 切换筛选：`preset` → 计算 `from/to` → `replace` → 页面 `watch([...filter])` 触发一次请求（300ms 防抖，`AbortController` 取消上一次，避免 AC-3 的「读数不一致」假象）。

### 7.3 页面与组件

| 目录 | 文件 | 职责 |
|---|---|---|
| `views/portal/` | `PortalView.vue` `PortalCards.vue` `PortalConfigDrawer.vue` | 五区装配、降级条、管理员 Drawer（只勾 4 张卡显隐） |
| `views/analytics/` | `DashboardView.vue` `parts/BreakdownMatrix.vue` `parts/TrendChart.vue` `parts/CycleSegments.vue` `parts/EffortBars.vue` `parts/VendorRankTable.vue` `parts/QualityCards.vue` | 看板五块；空态/≤2 周期不画图/长尾段标记 |
| `views/reports/` | `ReportListView.vue` `ReportEditorView.vue` `CrossTable.vue` `FieldPalette.vue` `ShareDialog.vue` `chartOptions.ts` | 列表两段、编辑器 240+948、拖拽/键盘等价、图表切换、共享四档、导出 |
| `components/` 复用 | `MetricCard.vue` `EmptyState.vue` `ErrorState.vue` `ConfirmDialog.vue` `StatusPill.vue` | 不改 `MetricCard`（D1）；数字格式化统一 `utils/format.ts` |

关键交互（细节见 DESIGN §2–§4）：

- 拖拽用原生 HTML5 `dragstart/drop`（不引第三方库），每个槽位同时提供键盘路径：`Tab` 到字段 → `Enter` 加入下一步槽位 → `方向键` 在槽位间移动 → `Delete` 移除。
- 图表切换规则：`PIE` 的分类 > 8 → 自动切 `BAR` 并在图下明示「分类过多，已改用柱状图」；`LINE` 只在时间维度上可选。
- 交叉表：`position:sticky` 冻结首列 + 表头吸顶，小计/总计行常驻；`truncated=true` 时顶部条「已省略 N 组，请收窄筛选」。
- 空态三分：无数据（`EmptyState` + 放宽筛选按钮）/ 无权限（`ErrorState` 403 文案）/ 降级（区级错误条 + 重试）。

## 8 权限码与门禁

- `PermissionMatrix` 追加 `PORTAL_VIEW` `PORTAL_CONFIG` `REPORT_VIEW` `REPORT_SHARE`（56 → 60），矩阵 `10 × 60 = 600` 格：`PORTAL_VIEW`/`REPORT_VIEW` 全角色；`PORTAL_CONFIG` 仅 `PLATFORM_ADMIN`；`REPORT_SHARE` 全角色（共享自己的报表）。
- `PermissionRouteTable` 新段（**位置**：M8 段之后、`^/releases` 兜底之前）：

```java
rule("PUT",    "^/portal/config$",      false, "PORTAL_CONFIG"),
rule(null,     "^/portal(/.*)?$",       false, "PORTAL_VIEW"),
rule(null,     "^/analytics(/.*)?$",    false, "PORTAL_VIEW"),
rule("POST",   "^/reports/\\d+/share$", false, "REPORT_SHARE"),
rule(null,     "^/reports(/.*)?$",      false, "REPORT_VIEW"),   // 含 datasets / run / export
```

- 三处硬编码基数必须同步：`scripts/platform-verify.sh:344`（56 → 60）、`scripts/tests/collab/s0-schema.sh:99-102`（`'.functions | length'` 56 → 60）、`scripts/access-verify.sh`（`TOTAL < 115` → `TOTAL < 123`，并给新页补 a2 矩阵行、a3 导航声明计数）。`a2` 是「5 页 × 7 角色」，本轮加 3 页即 8 页；`a3` 静态审计的期望条目数随之 +3 —— 两者由 `access-verify.sh a2 a3` 当场校准，不靠记忆。
- 新门禁两套（聚合器 + 分段，复用 `scripts/tests/quality/_lib.sh` 的 `require_stack`/`expect_*`/`db_q`/`guard`）：

| 聚合器 | 段 | 断言下限 | 覆盖 |
|---|---|---|---|
| `scripts/workbench-verify.sh` | `w0` schema/w1 metrics fidelity/w2 portal templates/w3 degradation/w4 filters/w5 isolation | ≥36 | AC-2/3/12/13/14 与降级 |
| `scripts/report-verify.sh` | `r0` schema(`r0b` 幂等)/`r1` CRUD/`r2` run/`r3` 上限/`r4` 共享/`r5` 数据集/`r6` 导出/`r7` 缓存/`r8` 隔离 | ≥48 | AC-5/6/7/8/9/10/11/12 |

- 两套都 `fail-closed`：`require_stack` 失败即退出 2 并打印 `make platform-up` 提示（PRD AC-16「栈未起非 0 退出」）。
- `scripts/tests/verify-negative.sh` 基线 25 → 23（取代 r8#AC-6，依据 r8 QA 实测校正）；新门禁各加 1 条负向（改坏白名单字段 → 必须红）。
- `Makefile` 追加 `workbench-verify` / `report-verify` 目标并纳入 `test` 聚合。

## 9 测试策略

| 层 | 内容 | 命令 |
|---|---|---|
| 单元（deliver） | `AnalyticsSqlTest`（白名单/越界字段/标记齐全）、`AnalyticsMetricsTest`（P90 nearest-rank n=10/n=11 边界、环比 null、比率零分母）、`AnalyticsCacheTest`（TTL/HIT-MISS/跨账号 key 不同）、`ReportDefinitionTest`（上限/JSON 往返不变）、`ReportVisibilityTest`（PRIVATE 他人 404）、`PortalTemplatesTest`（8 模板各 4 卡、mgmt 无三源、fallback 非空） | `bash scripts/platform-unit.sh`（自动收集四个模块，无需改脚本） |
| 契约 | `report-verify r0` 断言 `flyway_schema_history` 有 `version=10`、两表齐、索引齐、`ScopeSql.scopedTables()` 不含两表 | `bash scripts/report-verify.sh r0` |
| 端到端 | `workbench-verify w1` 逐位比对 `/portal` 卡片与 `/analytics/metrics` 同 key 值（AC-3 的「逐位一致」）；`r2` 交叉表 vs `/reports/{id}/run`；`r6` 导出 XLSX 解析行数 = 页面行数 | 两套聚合器 |
| 隔离 | `workbench-verify w5`：`vendor1` 与 `vendor2` 同参数读数不同；`r8`：同一共享报表两厂商行数不同且互不含对方 `domainId/vendorId`（用 `scope marker` 覆盖断言：每条 analytics SQL 必须含 `/*scope:`） | 同上 |
| 变异 | 删掉 `AnalyticsSql` 里一条 `/*scope:r*/` → 门禁必须红（fail-closed 自检） | `report-verify r8` 内置 |
| 前端 | `vue-tsc --noEmit` + `vite build`；导航 `functionCode` 漏写即编译失败 | `cd web && npm run build` |
| 性能 | AC-18：5 万需求下 `/analytics/metrics` P95 ≤ 800ms、`/reports/{id}/run` ≤ 1.2s、导出 ≤ 5s、缓存 HIT ≤ 120ms（`workbench-verify w1` 内做 p95 采样，`EXPLAIN` 附索引命中） | `bash scripts/workbench-verify.sh` |

## 10 任务拆分（文件边界互斥 → 可并行）

| 任务 | 文件（owns） | 依赖 | 验收 |
|---|---|---|---|
| **T1 地基**（串行前置） | `req-service/.../db/migration/V10__workbench_reports.sql`、`platform-contract/.../contract/{PermissionMatrix,RequirementStates}.java`、`platform-common/.../security/PermissionRouteTable.java`、`req-service/.../seed/SeedService.java`、`.gitignore` | — | `make platform-up` 后 `flyway` V10 成功；`/api/req/...` 矩阵 60 码；`mgr1` 可登录 |
| **T2 聚合引擎** | `deliver-service/.../analytics/*`（10 个文件） | T1（`RequirementStates`） | 单测绿；`/analytics/metrics` 三账号读数不同 |
| **T3 工作台后端** | `deliver-service/.../portal/*` | T2 | 8 模板各返回五区；mgmt/fallback 正确 |
| **T4 报表后端** | `deliver-service/.../reports/{ReportDefinitionEntity,ReportDefinitionRepository,ReportService,ReportVisibility,ReportController,ReportViews}.java` | T2 | CRUD/共享/软删/404 语义 |
| **T5 导出** | `deliver-service/.../support/WatermarkSheet.java`、`.../reports/{AnalyticsExportService,ReportExportController}.java` | T2, T4 | XLSX 首行 `#EAF1FE` + 页脚水印；行数与页面一致 |
| **T6 前端骨架** | `web/src/api/analytics.ts`、`web/src/stores/analytics.ts`、`web/src/router/routes/analytics.routes.ts`、`web/src/layouts/nav.ts` | T1（码） | `npm run build` 绿；四条路由可达 |
| **T7 工作台页 + 看板页** | `web/src/views/portal/*`、`web/src/views/analytics/*` | T6 | 五区/五块渲染、空错降级三态 |
| **T8 报表页** | `web/src/views/reports/*` | T6, T5 | 拖拽/转置/切图/共享/导出 |
| **T9 门禁** | `scripts/{workbench-verify,report-verify}.sh`、`scripts/tests/{workbench,report}/*`、`Makefile`、`scripts/platform-verify.sh`、`scripts/access-verify.sh`、`scripts/tests/access/a2,a3`、`scripts/tests/collab/s0-schema.sh`、`scripts/tests/verify-negative.sh` | T1–T5 | 两套聚合器 PASS==TOTAL 且达下限；negative 23 |
| **T10 文档与截图** | `docs/teamflow/20261110-r9-workbench-reports/screenshots/*`、`DELIVERED-MODULES.md §9`、`DEMO-BY-ROLE.md` | T2–T8 | 3 张 1440×900 截图 + 文档与真实状态一致 |
| **T11 交付自检**（收尾） | 无新文件（修正 + 提交清单） | 全部 | 全量门禁绿；`git status` 仅本需求文件；**不提交**（验收阶段一次 commit） |

工程约束落地：开发期零提交；`.gitignore` 增 `docs/teamflow/*/screenshots/*.png` 之外的导出产物（`*.xlsx`/`*.csv` 测试残留）；不夹带无关重构。

## 11 风险与遗留

1. **审批数据集口径收窄（D-1）**：若验收坚持 PRD 的四种业务类型，需要先给 `wf_instance` 补 `domain_id`/`vendor_id` 并回填 —— 那是独立迁移，不在本轮。
2. **P90 走 Java 而非 SQL**：单次样本量受时间窗（≤366 天）与 `Scope` 限制，5 万需求下实测 P95 需在 `w1` 采样；若超阈值，退路是给 `(tenant_id, created_at)` 加覆盖索引（已在 V10）。
3. **`access-verify` 的 a2/a3 计数**：新增 3 页会让若干「条目数」断言变化，T9 必须现场跑 `access-verify.sh a2 a3` 校准，不能沿用 115 的旧心智。
4. **缓存一致性**：报表定义变更只按 `report:<id>:` 前缀失效，`/analytics/*`（工作台/看板）走 TTL 自然过期 —— 与 PRD「300s」一致，但演示时改数后需等一个 TTL 或重启，验收脚本应显式断言 `MISS → HIT` 两次调用而不是依赖时序。
