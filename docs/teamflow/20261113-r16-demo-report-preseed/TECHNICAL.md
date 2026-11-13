# r16 技术方案 · 演示重置后预置 2 张开箱可用的演示报表

> 基线依赖：`docs/teamflow/20261113-r15-demo-reset-pollution`；本方案**取代** `#AC-1`（「重置后报表中心为空：reports=0 / total=0」→「恰好 2 张预置演示报表」）。
> 本文只描述「在既有 DemoFixture / DemoResetService / 重置脚本上做最小改造」，不改报表引擎、不改前端、不新增表/迁移/接口/make 目标。

## 0. 结论（架构判断）

| 决策 | 内容 | 理由 |
|---|---|---|
| 数据落点 | 仍写 `report_definition`（req-service 直写共用库 `devops_platform`），不新建表、不新增迁移 | 两个服务用同一个 `PLATFORM_DB_URL`；`report_definition` 无 FK、无跨服务写入协议，多写一张表只会多一套漂移面 |
| 定义来源 | `DemoFixture` 内的常量 `REPORTS`（代码常量），**不**引入独立 JSON 定义文件/配置项 | AC-8 明令禁止第二套目录或配置文件；「删什么/留什么/回填什么」继续单点收敛在 `DemoFixture` |
| 幂等做法 | `DELETE_ORDER` 里已有的 `purge("report_definition")`（`DemoFixture.java:202`）保持不变，**先整表清空，再连插 2 行** | 唯一键是 `(tenant_id, owner_id, name, active_flag)`（`V10__workbench_reports.sql:25-47`），upsert 无法表达「上轮多出来的那张要消失」，清后回填是唯一幂等解 |
| 可见性 | `scope=PRIVATE`、`scope_ref=NULL`、`owner_id=admin` | `ShareVisibility.visible`：PRIVATE 只认 owner（admin 无绕过），把暴露面压到最小；不 pin 门户（AC-10） |
| 口径 | 两张都用 `requirement` 数据集，字段全部在 `AnalyticsDatasets` 已注册白名单内 | 人天类度量（`effort.estimate/actual/deviation`）只在 requirement 注册（`AnalyticsDatasets.java:177-187`），delivery 数据集没有；不改数据集、不改 i18n 注册 |
| 脚本 | `scripts/platform-demo-reset.sh` 三处断言 0→2 + 名称集合，另加 3 条运行/导出自证 | AC-1/AC-6/AC-12；AC-11 要求 pass 数写明且 +3~5，本方案 49 → **52** |

改动面（4 个文件，1 新增）：

| 文件 | 动作 |
|---|---|
| `req-service/src/main/java/com/example/req/seed/DemoFixture.java` | 新增 `ReportFixture`、`REPORTS`、`REPORT_NAMES`、`REPORT_COUNT`、`reportRows(...)` |
| `req-service/src/main/java/com/example/req/seed/DemoResetService.java` | `references()` 增必需键；`insertFixture()` 第 9 步回填；`violations()` 断言 2 张 + 名称集合；新增 `reportNames()` |
| `scripts/platform-demo-reset.sh` | `:107`、`:157-165`、`:228-233` 三处 0→2 + 名称集合；新增 run A / run B / export 自证（+3 pass） |
| `req-service/src/test/java/com/example/req/seed/DemoReportFixtureTest.java` | **新增**：钉住两张表的形状、JSON、owner、字节稳定 |

---

## 1. 数据模型与存储

### 1.1 表结构（不迁移，复用 `report_definition`）

`req-service/src/main/resources/db/migration/V10__workbench_reports.sql:25-47`：

- 列：`id`（BIGINT UNSIGNED AI）、`tenant_id`、`name VARCHAR(60)`、`dataset VARCHAR(16)`、`definition_json JSON NOT NULL`、`chart_type VARCHAR(8) DEFAULT 'TABLE'`、`owner_id BIGINT NOT NULL`、`owner_role VARCHAR(32) NULL`、`scope VARCHAR(8) DEFAULT 'PRIVATE'`、`scope_ref BIGINT NULL`、`status VARCHAR(8) DEFAULT 'ACTIVE'`、`active_flag INT GENERATED ALWAYS AS (IF(status='ACTIVE',1,NULL)) STORED`、`created_at/updated_at DATETIME(3)`、`created_by/updated_by`、`version INT DEFAULT 0`
- 唯一键 `uk_report_name (tenant_id, owner_id, name, active_flag)`；普通索引 `idx_report_owner`、`idx_report_scope`；**无外键**
- `active_flag` 为生成列：**插入时不能给值**；软删（`status='DELETED'`）时生成列变 NULL，唯一键因此不挡新行（i18n 门禁自建夹具走的就是这条路）

`counts().reports` 是 `report_definition` 该租户的行数（不分状态）；重置后必须恰为 2，因此实现只能是「清空 + 精确插入 2 行」。

### 1.2 两张表的契约（`DemoFixture.REPORTS`，顺序即 `REPORT_NAMES` 顺序）

| # | name | dataset | rows | columns | measures | chart_type | 形态 |
|---|---|---|---|---|---|---|---|
| 1 | `需求状态分布` | `requirement` | `domain` | `status` | `req.total`, `cycle.avg` | `TABLE` | 二维交叉表（域 × 状态，需求数 + 平均交付周期） |
| 2 | `厂商交付概览` | `requirement` | `vendor` | `[]` | `req.total`, `effort.estimate`, `effort.deviation` | `TABLE` | 单维三度量（厂商 一行一度量组） |

固定列值（两行相同）：`scope='PRIVATE'`、`scope_ref=NULL`、`status='ACTIVE'`、`version=1`、`owner_id=refs.get("user:admin")`、`owner_role='admin'`、`created_by=updated_by=admin id`。

为什么这些维度能出数（演示数据现成，不改一行）：

- A：12 条演示需求分布在 `ORD/INV/LOG`（前 8 条）+ `ORD/SUP/MDM`（后 4 条），域 ≥ 1 行、状态 5 类（`POOLED/SCHEDULED/IN_DEV/PENDING_TEST/DELIVERED/DONE/TODO`），`req.total` 合计 12 > 0；`cycle.avg` 只对终态行有值，中文表头由 r14 的 code→label 层给出。
- B：`DemoFixture.REQUIREMENTS` 的 `vendor` 列填了 `VEN-A`（`DEMO-R-09/10/11`）与 `VEN-C`（`DEMO-R-12`），其余 8 条为空 → 行 = `（未指定）+ VEN-A + VEN-C` ≥ 2，`rowCodes` 同时含 `VEN-A`、`VEN-C`；`effort.estimate` 合计 `20+8+3+2+5+4+6+2+5+3+2+2 = 62` > 0。

### 1.3 `definition_json` 形状与生成

```json
{"datasetId":"requirement","rows":["domain"],"columns":["status"],"measures":["req.total","cycle.avg"],"filters":[]}
```

- 键序固定为 `datasetId, rows, columns, measures, filters`，`filters` 恒为 `[]`；数组用 `String.join` 拼短串，**不引 ObjectMapper**：req-service 不依赖 deliver 的 `ReportDefinitionCodec`，也不能让 Jackson 的字段顺序/缩进成为两次重置是否「相等」的隐变量（AC-6 要按字段规范化比较，字节稳定最省事）。
- 该形状必须能被 deliver 的 `ReportDefinitionCodec.read(JsonNode)` 反序列化，且 `datasetId` 与行内 `dataset` 列一致（`ReportService.create` 的 `requireDefinition` 就是这条规则；虽然我们绕过了 create 直接写库，但 `run/export` 走 `codec.read(entity.getDefinitionJson())`，形状写错会 400/500）。
- 字段拼错不会静默：`ReportService.run` → `AnalyticsDatasets.check` 未注册字段会返回 400（`REPORT_UNKNOWN_FIELD` 一类），脚本新增的 run 断言就是它的探针。

### 1.4 owner 解析（不写死 id）

`DemoResetService.references(tenantId)`（`:226-257`）已经把 `sys_user` 全表映射成 `user:<username>`。做法：

- 复用 `refs.get("user:admin")`，并在必需键清单（`:245-250`）追加 `"user:admin"` → 种子缺 admin 时抛既有 `ApiException(409, "DEMO_RESET_TENANT", ...)`，而不是 `NullPointerException`/空 owner（`owner_id NOT NULL` 会炸成 500）。
- `owner_role='admin'`：与 `PortalTemplates.resolve(...).code()` 对 admin 的实际取值一致（`PortalTemplates.java:82` 起 `ROLES.put("admin", ...)`，role code 是小写 `admin`）。`ShareVisibility` 不读这个字段，它只决定详情页展示，取真实值即可。

---

## 2. 后端实现设计

### 2.1 `DemoFixture`（新增，不改既有常量/行数据）

放在 `DELETE_ORDER`（`:157-207`）之后、需求行常量之前，保持「先保留策略、再数据」的阅读顺序：

```java
public record ReportFixture(String name, String dataset, List<String> rows,
                            List<String> columns, List<String> measures, String chartType) {
    public String definitionJson();          // 固定键序；filters 恒 []
}

public static final List<ReportFixture> REPORTS;      // 2 条，顺序稳定
public static final List<String> REPORT_NAMES;        // ["需求状态分布","厂商交付概览"]
public static final int REPORT_COUNT = 2;             // 与 REPORTS.size() 同源

/** report_definition 的插入行；列顺序见 §2.2 的 INSERT。 */
public static List<Object[]> reportRows(long tenantId, Map<String, Long> refs);
```

`reportRows` 内部对每张表用 `ref(refs, "user:admin")` 取属主（`ref` 缺键抛 `IllegalStateException`，见 `:391-397`，与其它行构造函数一致），`created_by/updated_by` 同值；列顺序与 `INSERT` 一致：

```
{tenant_id, name, dataset, definition_json, chart_type, owner_id, owner_role, scope, scope_ref, status, created_by, updated_by, version}
```

不新增 `REPORT_*` 之外的计数常量，也不复制 `AnalyticsDatasets` 的字段表：字段合法性的唯一真源仍在 deliver-service，本侧只用字面量（单测钉住字面量，见 §6.1）。

### 2.2 `DemoResetService`

1. **`references()`**：必需键追加 `"user:admin"`（`List.of("user:po1","user:pm","user:admin","domain:ORD","vendor:VEN-A","supplyOrg","itOrg","module")`）。
2. **`insertFixture()` 第 9 步**（现有 8 步之后、方法结束前 `:409` 附近）：

```java
// 9) 演示报表 2 张（r16）：report_definition 已在 DELETE_ORDER 里清空，这里只回填
for (Object[] row : DemoFixture.reportRows(tenantId, refs)) {
    db.insert("INSERT INTO report_definition (tenant_id, name, dataset, definition_json,"
            + " chart_type, owner_id, owner_role, scope, scope_ref, status,"
            + " created_by, updated_by, version)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", row);
}
```

`report_definition` 无 FK，插入位置不影响其它表；放在最后一步的好处是「报表是演示态的最后一块」，读代码时不会打断需求/用例/缺陷链路。

`db.insert(sql, Object... args)` 支持变长参数（同文件其它插入一致），`definition_json` 直接传字符串，MySQL 会按 JSON 列校验。

3. **`violations(tenantId, counts)`**（`:431-465`）：

- `:451` 由 `expect(violations, "报表定义", counts.get("reports"), 0)` 改为 `... DemoFixture.REPORT_COUNT)`；
- 紧随其后加名称集合断言（AC-1 的可定位失败信息，AC-12）：

```java
List<String> names = reportNames(tenantId);
if (!new TreeSet<>(names).equals(new TreeSet<>(DemoFixture.REPORT_NAMES))) {
    List<String> sorted = new ArrayList<>(names);
    sorted.sort(Comparator.naturalOrder());
    violations.add("报表名称集合应为 " + DemoFixture.REPORT_NAMES + "，实际 " + sorted);
}
```

- 新增私有方法 `private List<String> reportNames(long tenantId)`：`db.rows("SELECT name FROM report_definition WHERE tenant_id = ?", tenantId)` 取第一列（不做 `ORDER BY`，排序在 Java 侧做——见 §5.2）。
- `:452-456` 的 `portalCards=0 / portalPins=0 / executors=2 / apiClients=0 / probeReports=0` **不变**。这是「2 张里没有探针」的分界：`probeReports=0` 现在不是 `reports=0` 的子集，而是 `reports=2` 子集上的过滤结果。

4. `counts()` 各键顺序不变（`reports` 仍在 `notifications` 之后）；`reset()` 的 `409 DEMO_RESET_INVARIANT` + 整体回滚语义不变——报表插一半失败会连 12 条需求一起回滚，不会留下「有报表没数据」的演示态。

### 2.3 缓存与可见性（为什么 AC-3/AC-9 不额外做失效）

- 分析缓存键以报表 id 为前缀（`report:<id>|...`）。重置会删行重插，自增 id 必变 → 新 id 不可能命中上一轮的旧缓存，**不需要**跨进程失效（deliver 的 TTL 300s 与本需求无冲突）。
- 可见性只看 `(scope, scope_ref, owner_id)`：`PRIVATE` + `owner=admin` → vendor1 的列表查不到、`load()` 抛 `AnalyticsErrors.notFound(id)`（404），`run/export` 同一条 `load` 路径，因此 AC-9 的 403/404 是既有机制的自然结果，不加代码。

---

## 3. API 设计（无新增接口，仅约定被消费的契约）

| 方法 | 路径 | 入参 | 返回 | 本条需求的作用 |
|---|---|---|---|---|
| POST | `/internal/demo-reset`（req-service，`X-Internal-Token`） | 无 | `{resetAt, counts:{...}, ...}`；`counts.reports=2`、`probeReports=0` | AC-1 服务端计数；失败 409 `DEMO_RESET_INVARIANT` + 差异列表 |
| GET | `/api/deliver/reports?page&size&keyword&segment` | 分页+关键词 | `PageResponse<ReportSummary>`：`items[{id,name,datasetId,datasetLabel,scope,scopeRef,ownerId,ownerName,dimCount,measureCount,chartType,updatedAt}]`, `total,page,size` | AC-1 API 数量 + 名称集合；脚本按 name 取 id（不写死 id） |
| GET | `/api/deliver/reports/{id}` | id | `ReportDetail`（含 `definition`、`canWrite`、`canShare`） | AC-6 逐字段比较 `definition`（规范化） |
| POST | `/api/deliver/reports/{id}/run?preset&reqType&domainId&vendorId&from&to` | body 可省（`RunCmd` 为 null 时用库内定义，`ReportService.run:322-324`） | `CrossTab{rowHeaders,colHeaders,rows[{key,values,counts}],subtotal,total,groups,truncated,dataAsOf,cache,colCodes,rowCodes}` | AC-3 自证：A `.total.values[0]>0`、B `.rowCodes` 含 VEN-A/VEN-C 且 `.total.values[1]>0` |
| GET | `/api/deliver/reports/{id}/export?format=xlsx` | `xlsx`/`csv` | 二进制 + `report-<id>-<slug>-<yyyymmdd>` 文件名 | AC-5：PK 头 + `xl/sharedStrings.xml` 含水印 `内部资料（水印）`（`WatermarkSheet.java:44`） |
| GET | `/api/deliver/reports/datasets` | 无 | 数据集 + 字段白名单 | AC-2 的字段合法性真源（本方案不新增字段） |

错误码约定（沿用，不新增）：`404 REPORT_NOT_FOUND`（非属主/不存在，不区分）、`400 REPORT_UNKNOWN_FIELD`/`REPORT_DATASET_MISMATCH`（定义写错时在 run 上立刻暴露）、`409 DEMO_RESET_TENANT`（种子不会齐）、`409 DEMO_RESET_INVARIANT`（重置结果不符，整体回滚）。

---

## 4. 前端组件与页面拆分（零改动）

改动 **0 个**前端文件，只确认数据流：

- 列表页 `web/src/views/reports/ReportListView.vue` ← `web/src/api/analytics.ts:411` `GET ${DELIVER}/reports`：重置后多出 2 行，空态消失；分页/关键词仍是本地 `ref`，无新增状态。
- 编辑器 `web/src/views/reports/ReportEditorView.vue` ← `/reports/{id}`：`definition`/`datasets`/`scopeChips` 全从接口读，两张表的维度度量由后端返回的 code 组成；r14 的 code→中文标签层不变（表头中文、`colCodes/rowCodes` 同长同序）。
- 范围 chips 是**路由范围**（「范围（不随报表保存）」，`ReportEditorView.vue:506-511`），与报表 `filters` 无关 → 两表 `filters=[]` 时 `/reports/{id}?reqType=RD` 仍渲染「需求类型 研发需求」，i18n 门禁的 chips 断言因此不会红。
- 门户 `/portal`：`portal_card_config` 仍被清空且不新增行 → 4 张模板卡、无 `report:` 前缀卡片、`portalPins=0`（AC-10）。工作台卡片不 pin，`/reports` 卡片依旧是模板默认。

状态管理结论：**不需要**新增 store/缓存失效逻辑；页面每次进入都请求列表（既有行为），没有客户端持久化的报表集合。

---

## 5. 关键实现点与边界

1. **字节稳定的定义 JSON**：常量模板 + `String.join`，不经过 Jackson/Map 遍历；`definitionJson()` 每次调用结果逐字节相同 → 两次重置 `definition_json` 相等（AC-6），且与 id 无关。
2. **中文排序绝不交给 MySQL**：名称集合比较不用 `ORDER BY name`（`utf8mb4` collation 随版本/字符集变化，中文排序不稳定）。Java 侧用 `TreeSet`/`naturalOrder`；脚本侧用同一 `jq` 表达式对两侧排序（`jq -r 'sort|join(",")'`）或直接比较 `COUNT(*) WHERE name IN (...)`。
3. **id 不写死**：脚本先按 name 从列表响应取 id，再 run/export；单测只断言「有 2 行、name 对」，不断言 id。
4. **探针名与演示名不得相交**：`PROBE_REPORT_PATTERNS`（`DemoResetService.java:66-67`）是 `%T8 编辑器探针%`、`r11-ev%`、`r13-%`，两张表名不含这些片段 → `probeReports=0` 与 `reports=2` 同时成立；`:112` 的注释同步改成「探针是 reports=2 的子集」。
5. **`platform-seed` 不产生报表**：种子只建租户/用户/域/厂商/组织；报表插入只存在于 `insertFixture`（仅 `reset()` 调用），AC-8 天然满足。不新增 make 目标、不新增第二套目录。
6. **人天偏差列可能整列 0/NULL**：`insertFixture` 第 3 步只在终态（DONE）写 `actual_days = estimate_days`，其余行为 NULL → `effort.deviation` 只有 `DEMO-R-08` 有值且为 0。本方案**不改演示需求行数据**：① AC-3 只要求「cells 非全空」；② 改 `actual_days` 会动 r15 已确认的 12 行演示口径并引入第二处「实际人天」来源，收益（一列更好看）小于回归风险。作为已知取舍记入 §8，后续 r17 若要「偏差非零」再单独改数据。
7. **失败信息可定位**（AC-12）：服务端名称集合断言把「期望集合 + 实际集合」写进 409 文案；脚本三处断言分别打印实际数量/名称集合/实际行头，禁止 `|| fail "报表数量不对"` 这类无信息失败。
8. **不越权**（AC-9）：`PRIVATE` + owner=admin 是唯一机制；不给 `scope_ref` 填值（填了反而会变成 DOMAIN/VENDOR 档），不写 `PUBLIC`。
9. **事务边界**：回填在 `reset()` 的 `@Transactional` 内，`counts`/`invariants`/`violations` 在同一事务里读，任何不一致抛 409 并整体回滚（不会出现「报表在、需求不在」）。
10. **共用库依赖**：req-service 直写 `report_definition` 的前提是两个服务指向同一个 `PLATFORM_DB_URL`（现状如此）；若将来拆库，本方案会以「重置成功但报表不出现」的方式暴露——已用脚本 API 断言兜底。

---

## 6. 测试策略

### 6.1 `DemoReportFixtureTest`（新增单测，`make platform-unit` 覆盖）

`req-service/src/test/java/com/example/req/seed/DemoReportFixtureTest.java`，纯常量测试（不启 Spring、不连库）：

1. `REPORTS` 恰好 2 条，`REPORT_NAMES` 与 `REPORTS` 名字同序，名字互不相同、均 ≤ 60 字符。
2. A：`dataset="requirement"`、`rows=["domain"]`、`columns=["status"]`、`measures=["req.total","cycle.avg"]`、`chartType="TABLE"`。
3. B：`dataset="requirement"`、`rows=["vendor"]`、`columns` 为空、`measures=["req.total","effort.estimate","effort.deviation"]`。
4. `definitionJson()` 解析后键恰为 `datasetId/rows/columns/measures/filters`，`filters` 为空数组，数组元素与 record 字段逐个相等（用 `ObjectMapper` 解析，测试侧允许引 Jackson）。
5. 两次调用 `definitionJson()` 字符串 `equals`（字节稳定，护 AC-6）。
6. `reportRows(1L, refs)` 返回 2 行，`row[0]=1L`、`row[4]="TABLE"`、`row[7]="PRIVATE"`、`row[8]==null`、`row[9]="ACTIVE"`、`row[5]=admin id`、`row[11]=admin id`。
7. `refs` 缺 `user:admin` 时抛 `IllegalStateException`（与 `ref()` 的既有语义一致）。

`DemoResetRetentionTest` **不改**：`report_definition` 仍在 `DELETE_ORDER` 里（`:202`），它的「定义表不被清空」断言与「业务实例被清空」断言都继续成立；本需求是「清空后回填」，不是「不清空」。

### 6.2 `scripts/platform-demo-reset.sh`（49 → 52 pass，0 fail）

| 位置 | 现状 | 改为 |
|---|---|---|
| `:107` | `check_count reports 0` | `check_count reports 2 "报表定义（report_definition）= 2 张演示报表"` |
| `:112` | 注释「probeReports 是 reports=0 的子集」 | 「probeReports 是 reports=2 上的探针过滤，必须为 0」 |
| `:157-165` | `DB_REPORTS` 断言 `==0` | 同一断言块内：`DB_REPORTS==2` **且** `SELECT COUNT(*) ... WHERE name IN ('需求状态分布','厂商交付概览')==2`（两者都不符才 fail，文案带实际计数与命中数） |
| `:228-233` | `REPORT_TOTAL ==0` | `total==2` 且 `.items` 名称集合 == 期望集合（期望集合先用同一 `jq -r 'sort|join(",")'` 规范化，避开中文排序差异） |
| 新增（`:233` 之后） | — | ① 按 name 取 A 的 id → `POST /run?reqType=RD&preset=30d`，断言 `.rowCodes|length>=1`、`.colHeaders|length>=2`、`.total.values[0] > 0`；② 同法对 B，断言 `rowCodes` 含 `VEN-A`/`VEN-C` 且 `.total.values[1] > 0`；③ 两张表 `export?format=xlsx` 落临时文件：HTTP 200 + PK 头 + `unzip -p ... xl/sharedStrings.xml` 含 `内部资料（水印）`（`grep -F -c` 读完流，避免 `pipefail` 下 `grep -q` 的 SIGPIPE 误判） |

计数说明：三处改写的 CASE 数不变（仍是 3 个 pass），新增 3 个 → `52/52 全部通过`（脚本结尾仍打印 `${CASE}/${CASE}`）。

### 6.3 门禁矩阵

| 命令 | 期望 | 关联 AC |
|---|---|---|
| `bash scripts/platform-demo-reset.sh` | `52/52`，0 fail | AC-1/3/5/6/7/12 |
| `make platform-unit` | 新增 7 条断言绿 | AC-1/2/6 |
| `make platform-verify` | 绿（重置不在其中，验证服务契约不变） | AC-11 |
| `make analytics-i18n-verify` | 静态 + 全量绿；**全量分支会通过 fallback（`i18n-live.mjs:325-331`）把这两张 TABLE 报表当样本跑页面/CSV/XLSX 对比** → 顺带验证 AC-4/AC-5 | AC-4/AC-5 |
| `make access-verify` | 绿（`a2-page-matrix.sh:50` 只判可达/权限，不看数量） | AC-17 |
| `make platform-test-negative` / `make platform-seed` | 绿；seed 后报表仍 0 | AC-8 |
| `make lint` / `make verify` | 绿 | AC-18 |

### 6.4 QA 阶段（本阶段不写报告、不跑全矩阵）

QA 用 `logs/teamflow/<runId>/scripts/` 的探针补齐：两表 run 的 `rowHeaders/colHeaders` 中文校验与 `colCodes/rowCodes` 同长同序（AC-4）、`export` 表头与页面逐字符串相等（AC-5）、vendor1 登录 `run/export` 得 404 且响应不含需求行/厂商名（AC-9）、20 张截图与视觉基线（AC-16）。

---

## 7. 任务拆分（文件边界并行）

无 `[PIPELINE-DISPATCHED TASKS]`；下列 4 个任务按「可修改文件互斥」拆分：

### T1 · 定义数据与 JSON 构造（`DemoFixture.java`）
- files：`req-service/src/main/java/com/example/req/seed/DemoFixture.java`
- reads：无（`ref()`/行构造是同文件私有成员）
- spec：按 §2.1 加 `ReportFixture` + `REPORTS` + `REPORT_NAMES` + `REPORT_COUNT` + `reportRows`；`definitionJson()` 固定键序、`filters` 恒 `[]`；两表字段严格取 §1.2 表；不动既有常量、`DELETE_ORDER` 与 12 行需求数据。
- 验收：`mvn -q -DskipTests -pl req-service -am compile` 通过；两个方法可被服务与单测直接调用（签名与本文一致，不得改名）。

### T2 · 重置链路接线（`DemoResetService.java`）
- files：`req-service/src/main/java/com/example/req/seed/DemoResetService.java`
- reads：`DemoFixture.java`（T1 的 API 契约以本文 §2.1 为准，可先按契约写、T1 落地后编译）
- spec：§2.2 的 4 项（必需键、第 9 步插入、`violations` 期望 2 + 名称集合、`reportNames`）；不改其它断言与删除序列。
- 验收：`mvn -q -pl req-service -am compile`；`make platform-unit` 绿。

### T3 · 脚本断言同步（`scripts/platform-demo-reset.sh`）
- files：`scripts/platform-demo-reset.sh`
- reads：本文 §3/§6.2
- spec：三处 0→2 + 名称集合；新增 run A / run B / export 三条自证；`set -euo pipefail` 下不得因管道提前退出；失败文案带实际值。
- 验收：脚本自身 `bash -n` 通过；**不独立造数据**，运行时依赖 T1+T2 已落地（见 T4 顺序）。

### T4 · 单测 + 交付自检 + 提交
- files：`req-service/src/test/java/com/example/req/seed/DemoReportFixtureTest.java`（新增）
- reads：`DemoFixture.java`、`DemoResetService.java`、`DemoResetRetentionTest.java`、`scripts/platform-demo-reset.sh`
- spec：写 §6.1 的 7 条断言 → 跑 `make platform-unit` → `make platform-up && bash scripts/platform-demo-reset.sh`（必须 52/52）→ `make platform-verify && make analytics-i18n-verify && make access-verify && make platform-test-negative && make platform-seed && make lint`。自检发现问题就地修（只改本轮 4 个文件），**不写** `QA-REPORT.md`/`ACCEPTANCE.md`，不跑跨浏览器/多环境全矩阵。
- git（必须带进任务）：仍在 `feat/demo-report-preseed`，不新建分支、不切 `main`；提交前先 `git log --oneline -5` + `git diff --stat` 确认范围；未跟踪的 `docs/teamflow/20261113-r16-demo-report-preseed/`（含本文件）**不得丢弃/stash**，与代码一并提交；不触碰任何历史任务夹（含 r15 全部产物）；提交信息写清「r16：重置后预置 2 张演示报表」+ 三处断言位置。

依赖：T1 ∥ T2 ∥ T3 → T4（T4 需要三者的最终形态做端到端自检）。

---

## 8. 风险与取舍

1. **其它门禁对报表数量的硬断言**：已 grep 全部脚本——只有本脚本断言 total；`access` 的 a2 只判 200；`report-verify.sh`/`workbench-verify.sh` 不提 reports；`platform-perf.sh:235` 只测时延。唯一「隐式消费」是 `i18n-live.mjs` 的 fallback（`REPORT_IDS` 默认 `250,261,228` 命不中 → 取列表里 TABLE 报表补齐），因此两张表必须能通过它的页面/CSV/XLSX 断言（已在 §6.3 作为正面覆盖使用）。
2. **名称与断言耦合**：名称只出现在 `DemoFixture.REPORT_NAMES` 一处，服务端断言与脚本期望都从「已排序集合」比较，改名必须同步脚本字面量——这是有意的（AC-1 要求名称可断言）。
3. **人天偏差列偏弱**：见 §5.6，接受「0 值列」；若验收要求更强演示力，r17 单独改演示数据 + 单测口径。
4. **不新增迁移**：若后续给报表加列（如 `is_demo`），需要新迁移；本轮刻意不做标记列——探针名 + 名称集合已足够区分演示报表与用户自建。
5. **数据集字段真源在 deliver**：req-service 侧只有字面量，字段拼错靠 run 的 400 与脚本 run 断言暴露；不复制 `AnalyticsDatasets` 的字段表（避免两套白名单漂移）。
