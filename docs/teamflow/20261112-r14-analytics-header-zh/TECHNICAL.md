<!-- meta: summary="r14 技术方案：deliver-service 建唯一 code→中文注册表，交叉表头/行头在唯一拼装点翻译并新增 colCodes/rowCodes，前端回显走 dict.ts + 兜底「未知状态」，新增 analytics-i18n-verify 门禁接入 make test。" -->

# r14 技术方案 · 分析域表头与导出中文化

**基线**：`docs/teamflow/20261111-r13-analytics-views/`（r13 分析域四页 + 交叉表 + 导出）
**取代**：`docs/teamflow/20261111-r13#AC-13`（列头由 `IN_DEV · 需求总量` 改为 `开发中 · 需求总量`，code 移入新增 `colCodes`）
**分支**：`feat/analytics-header-zh` · **不新增依赖、不加迁移、不改既有 API 路径与响应语义**

---

## 1. 结论先行

一处拼装点 + 一张注册表 + 一条门禁：

1. `AnalyticsQueryService.crossTab` 是**唯一**把维度取值拼进表头的地方（`colHeaders.add(colKey + " · " + label)`，`rowHeaders` 直接来自 SQL 分组值），导出侧 `ReportService.export` 复用同一个 `CrossTab`（`WatermarkCsv:45/51`、`WatermarkSheet:97-98` 只读 `colHeaders()`），所以**页面与导出天然同源**，改这一处即同时生效，不需要在 XLSX/CSV 里再译一遍。
2. 新建 `AnalyticsLabels`（deliver-service）作为**唯一** `code → 中文` 注册表；未登记且形如枚举码的取值回退 `未知状态`，原 code 进 `colCodes`/`rowCodes` 并记 WARN 日志。
3. 前端只做两件事：`DataStamp` 的 `HIT/MISS` 徽标改中文；编辑器 chips 用既有 `dict.ts` 翻译回显（新增 `has()` 与 `reqType`/`severity` 词条）。**不新建第二份 code→中文 映射**。
4. 新增 `scripts/analytics-i18n-verify.sh` + `make analytics-i18n-verify`，静态查三方一致、动态查四处渲染与导出逐字符串相等，并接入 `scripts/test-all.sh`（`make test`）。

### 1.1 对 PRD §8 五项待澄清的定稿

| 编号 | 定稿 | 理由 |
|---|---|---|
| F1 门禁落点 | **新建** `scripts/analytics-i18n-verify.sh` + `make analytics-i18n-verify`，不伪造 `report-verify`/`workbench-verify` | 两目标在 `Makefile`/`scripts/`/`git ls-files` 均不存在，造空目标=假门禁 |
| T1 表头格式 | 保留 `<维度中文> · <度量中文>`；无列维时仍只有 `<度量中文>`（不加 `合计 ·` 前缀） | 与 r13 列头形态逐字符可比，AC-2 判据 3 的逐列对齐才好断言 |
| T2 短代码 | `PASS/FAIL/BLOCKED/IN_DEV…` 全部中文化；`P0–P3` **也**中文化为 `紧急/高/中/低`（优先级）与 `致命/严重/一般/轻微`（缺陷等级）；仅 `P90`（度量名）与时间桶（`2026-11`、`第 45 周`）保留原样 | `P0` 是等级序号非英文枚举，但产品目标是「用户可见取值全中文」；两者在 `sys_dict`/`RoleCatalog` 里已有中文权威值，直接对齐即可 |
| T3 兜底文案 | `未知状态` | 不破正则、不撒谎，原值仍可从 `colCodes`/日志取回 |
| T4 筛选值输入 | 输入/选择控件仍以 **code** 为值提交；**回显层**（chips、预览、表头）译成中文 | 改输入语义会破坏 `definition_json` 存量（PRD 明确不迁移），只动显示层最省风险 |

### 1.2 与 PRD 原文的两处澄清（实现口径，须在 QA 报告里对齐）

- **C-1 `colCodes` 无列维时**：PRD AC-3 判据 1 同时写了「长度与 headers 相等」和「无列维时 `colCodes = []`」。两者在无列维时冲突（此时列数 = 度量数 ≥ 1）。定稿：**无列维返回空数组 `[]`**（PRD 明写的特例优先），**有列维时逐列一一对应**；`合计` 作为列 key 的哨兵只出现在无列维分支，因此 `null` 不会实际出现，仍在契约里保留（`List` 允许 `null`）。前端必须用 `colCodes.length === colHeaders.length` 判断可用性，**不得按下标直接索引**。
- **C-2 `approval.bizType` 已是中文**：`AnalyticsSql` 用 `CASE` 把 `REQ_SUBMIT/REQ_REVIEW` 译成 `评审入池/需求评审`（其余走 ELSE），所以注册表仍登记这两条（防止 CASE 回退后漏译），`display()` 对中文值直接透传，不重复翻译。

---

## 2. 现状与改造点（均来自源码，非推测）

| 位置 | 现状 | 改造 |
|---|---|---|
| `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsQueryService.java:406` | `colHeaders.add(def.cols().isEmpty() ? label : colKey + " · " + label);` 直接把 SQL 分组值（枚举 code）拼进列头 | 拼装改用注册表解析后的中文；同处填 `colCodes` |
| 同文件 `:352-375` | `rowBuffer`/`colBuffer` 由 `text(row.get("d"+i))` 原始值拼接；`rowHeaders = sorted(rowKeys)` | 并行维护一份显示串；`rowHeaders` 用显示串，`rowCodes` 保原始串 |
| 同文件 `:450`（主路径）与 `:898`（缓存降级重建） | 两处 `new AnalyticsViews.CrossTab(...)` | 两处都要补 `colCodes`/`rowCodes`，否则降级路径字段丢失 |
| `deliver-service/src/main/java/com/example/deliver/support/WatermarkCsv.java:45-54`、`WatermarkSheet.java:86-106` | 只读 `tab.colHeaders()` / `row.key` | **不动**：中文来自上游，天然同源 |
| `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsMetrics.java` | 口径文案含裸 code：`PENDING_TEST`、`POOLED`、`PASS/FAIL/BLOCKED`、`APPROVED/REJECTED` | 改写成中文散文；新增单测禁止口径再出现英文枚举 token |
| `web/src/components/analytics/DataStamp.vue:29,48` | 徽标文本直接渲染 `HIT`/`MISS` | 文本改 `缓存命中`/`缓存未命中`；`HIT/MISS` 只留作样式类与 `store` 语义 |
| `web/src/views/reports/ReportEditorView.vue:138` | `` `需求类型 ${filter.reqType}` `` → 渲染 `需求类型 RD` | 改走 `valueLabel('type', …)` |
| `web/src/stores/dict.ts:432` | `PRIORITY` 的 `label` 是 `P0..P3`；无 `reqType`/`severity` kind | 对齐中文 + 补两个 kind + 新增 `has()` |
| `req-service/src/main/java/com/example/req/sys/RoleCatalog.java:45-61`、`seed/SeedService.java:233-239` | 两处各自种 `sys_dict`，且与 `dict.ts` 漂移：`POOLED` 待规划/需求池 vs 已入池、`IN_DEV` 研发中 vs 开发中、`severity` 用 `S1..S4`（全仓零引用的旧写法） | 对齐到注册表/`dict.ts`；`severity` 改 `P0..P3` |

> `role catalog` 与 `seed` 同时种字典本身是一处既有重复（见 §10 G-3），本轮只对齐文案，不做结构合并——合并会触碰租户初始化路径，超出 r14 范围。

---

## 3. 数据模型与存储

**没有表结构变更、没有 Flyway 迁移、没有数据回填。**

- 注册表是**代码常量表**，随交付物走：`AnalyticsLabels.ROWS`（四数据集 × 字段 × code × 中文）。之所以不做成 `sys_dict` 驱动，是因为 `sys_dict` 只种了 8 行（4 priority + 4 req_status），分析域要 16 个需求状态 + 审批结论 + 类型 + 等级，靠库表就得先补种 + 保证多租户一致；而表头在**导出线程**里也要用，读库会多一次查询与一次租户上下文依赖。代码表 + 门禁三方断言成本更低、失败更早。
- `report_definition.definition_json` **保持 code**（PRD 非目标）：既有报表无需迁移，翻译只发生在展示/导出时刻。
- `sys_dict` 仅改文案（`POOLED → 已入池`、`IN_DEV → 开发中`、`priority` 保持 `紧急/高/中/低`、`severity` 改 `P0..P3`）。`SeedService.link()` 按业务键幂等且**不更新**已存在行的 `label`，所以对已种过的库这次改文案不会自动生效；r14 不引入 heal UPDATE（不影响 AC：断言对象是种子源码 + 现场渲染，两者都不读旧行）。

---

## 4. 契约与 API

### 4.1 唯一新增字段：`CrossTab.colCodes` / `rowCodes`

```java
// AnalyticsViews.java（追加在 cache 之后，保持既有字段顺序/语义不变）
public record CrossTab(List<String> rowHeaders, List<String> colHeaders,
                       List<CrossRow> rows, CrossRow subtotal, CrossRow total,
                       List<String> groups, boolean truncated, String dataAsOf, String cache,
                       List<String> colCodes, List<String> rowCodes) { … }
```

| 规则 | 内容 |
|---|---|
| `rowCodes` | 与 `rowHeaders` **同长同序**；元素是该行各维度原始取值，多值沿用 `" / "` 连接；不含 `小计`/`总计`（它们是独立字段） |
| `colCodes` | 有列维时与 `colHeaders` **同长同序**，元素为该列原始取值（多值 `" / "`）；无列维时 `[]`（C-1）；`合计` 哨兵对应 `null` |
| 不可变性 | 两列表都用 `Collections.unmodifiableList(new ArrayList<>(…))` 包装，**不能用 `List.copyOf`**——`copyOf` 拒绝 `null`，会让 `合计` 哨兵抛 NPE |
| 语义稳定 | `colHeaders[i]` 与 `colCodes[i]` 一一对应；`rowHeaders[i]` 与 `rows.get(i).key()` 相等（前端以 `rows[i].key` 为准渲染行头，`rowCodes[i]` 只做机器可读） |

### 4.2 受影响端点（**路径、参数、状态码、鉴权全不变**）

| 端点 | 变化 |
|---|---|
| `GET /api/deliver/analytics/dashboard` | `breakdowns[0].colHeaders` 变中文（10 列，列序不变） |
| `POST /api/deliver/reports/{id}/run` | 响应 `CrossTab` 增 `colCodes`/`rowCodes`，`colHeaders`/`rowHeaders` 变中文 |
| `GET /api/deliver/reports/{id}/export?format=xlsx\|csv` | 文件内容随 `colHeaders` 变中文；表头/行头/水印页脚保持不变，BOM 与数值零漂移 |
| `GET /api/deliver/reports/{id}` / 列表 | 不变（无维度取值上屏） |
| `GET /api/deliver/analytics/datasets`、`/analytics/metrics` | 结构不变；`metrics[].caliber` 文案变中文 |
| `GET /api/deliver/portal` | 结构不变；卡片标题拼的 `caliber` 变中文 |

**没有新增路由**。C-2 的 `bizType` 中文由既有 SQL 产出，前端无需新接口拿词表（chips 用 `dict.ts`，见 §6）。

### 4.3 前端类型（`web/src/api/analytics.ts`）

```ts
export interface CrossTab {
  …
  /** 机器可读列 code；无列维时为空数组，禁止按下标直接索引 */
  colCodes?: (string | null)[]
  rowCodes?: string[]
}
```

字段设为**可选**：`DashboardView` 会把 `Breakdown` 派生成本地 `CrossTab` 字面量（`matrix`），设成必填会连带改四个页面的构造点，收益为零。

---

## 5. 后端实现

### 5.1 `AnalyticsLabels`（新建）

```java
package com.example.deliver.analytics;

public final class AnalyticsLabels {
    public static final String UNKNOWN = "未知状态";

    /** 注册表：{dataset, field, code, 中文} —— 门禁直接解析这张字面表 */
    private static final String[][] ROWS = { … };

    public static boolean isRegistered(String dataset, String fieldKey, String code);
    public static String label(String dataset, String fieldKey, String code);   // 未登记返回 null
    public static String display(String dataset, String fieldKey, String value); // 上屏文案，永不返回裸 code
    public static String joinDisplay(String dataset, List<String> dims, int from, int to, String[] values);
}
```

- `ROWS` 内容（值必须与 `dict.ts` 逐字符串相等）：

| dataset | field | code → 中文 |
|---|---|---|
| `REQUIREMENT` | `status` | `SUBMITTED 已提报`、`APPROVING 审批中`、`PENDING_REVIEW 待评审`、`POOLED 已入池`、`PENDING_ANALYSIS 待分析`、`SCHEDULED 已排期`、`IN_DEV 开发中`、`DELIVERED 已交付`、`ACCEPTED_CLOSED 验收关闭`、`TODO 待开发`、`PENDING_TEST 待测试`、`TEST_PASSED 测试通过`、`MERGED 已合入`、`DONE 完成`、`CLOSED 已关闭`、`CANCELLED 已取消` |
| `REQUIREMENT` | `type` | `BUS 业务需求`、`PDR 产品需求`、`RD 研发需求` |
| `REQUIREMENT` | `priority` | `P0 紧急`、`P1 高`、`P2 中`、`P3 低` |
| `QUALITY` | `severity` | `P0 致命`、`P1 严重`、`P2 一般`、`P3 轻微` |
| `APPROVAL` | `result` | `PENDING 待审批`、`RUNNING 审批中`、`APPROVED 已通过`、`REJECTED 已驳回`、`WITHDRAWN 已撤回`、`CANCELLED 已取消`、`SKIPPED 自动跳过` |
| `APPROVAL` | `bizType` | `REQ_SUBMIT 评审入池`、`REQ_REVIEW 需求评审` |

- `label()` 解析顺序：① `(dataset, field)` 精确表 → ② `field == "status"` 时回退 `REQUIREMENT.status` 表（`DELIVERY.status`、`QUALITY.status` 都是 `r.status`，复用同一张表，不复制 32 行）→ ③ `null`。
- `display()` 规则（顺序即优先级）：
  1. `value` 为 `null`/空白 → `""`（保持 r13 的空值行为，不新增占位、不改列数）；
  2. `label()` 命中 → 中文；
  3. 值形如枚举码（`^[A-Z][A-Z0-9_]{1,}$`）→ `未知状态` + 每个 `(dataset, field, code)` 只记一次 WARN（`ConcurrentHashMap.newKeySet()` 上限 200，防日志洪水）；
  4. 其余（中文、`2026-11`、`第 45 周`、`未关联计划`、`无缺陷`、节点名）→ 原样透传。
- `joinDisplay()` 与 `AnalyticsQueryService` 的拼接**逐条等价**：仅当目标缓冲非空时前置 `" / "`，逐段 `display()`；行维 `[0,rowDimCount)`、列维 `[rowDimCount,dims.size())`。
- 无静态依赖 `AnalyticsDatasets`（避免与 `AnalyticsLabels → AnalyticsDatasets → AnalyticsLabels` 的静态初始化环）；时间桶靠规则 4 自然透传，不查任何 kind。

### 5.2 `AnalyticsQueryService.crossTab` 改法（两处 `new CrossTab`）

```java
// 采集阶段：raw 与 display 并行
rowBuffer.append(value);                                  // 原样，作为 machine key
rowDisplay.append(AnalyticsLabels.display(dataset, dims.get(i), value)); // 上屏
…
String rowKey = rowBuffer.toString();          // 仍作 cells/rowAggs 的 map key（不参与展示）
rowDisplayByRaw.put(rowKey, rowDisplay.toString());
…
// 输出阶段：排序仍在 raw 上做，列序/行序与改前逐一对应
List<String> rawRows = new ArrayList<>(rowKeys); Collections.sort(rawRows);
List<String> rowHeaders = rawRows.stream().map(k -> rowDisplayByRaw.getOrDefault(k, k)).toList();
List<String> rowCodes   = List.copyOf(rawRows);
…
for (String colKey : orderedCols)
    for (int i = 0; i < measureCount; i++) {
        String measure = AnalyticsMetrics.spec(measures.get(i)).label();
        colHeaders.add(def.cols().isEmpty() ? measure : AnalyticsLabels.display(dataset, colDimKey, colKey) + " · " + measure);
        colCodes.add(def.cols().isEmpty() ? null : colKey);   // 无列维分支最终不回填，见下
    }
List<String> colCodesOut = def.cols().isEmpty() ? List.of() : Collections.unmodifiableList(colCodes);
```

要点：
- **排序保持对 raw 排序**（而非对中文排序）：中文排序会把「已合入/完成」等词的相对次序整体打乱，列序就不再与 r13 逐列对齐（AC-2 判据 3、AC-9 的下标诊断都依赖这一条）。
- `cells`/`rowAggs` 的 key 仍是 raw 拼接串 → 未登记的两个不同 code 即使都显示 `未知状态`，**也不会合并列**（AC-10），只是两列标题同文，靠 `colCodes` 区分。
- `colDimKey = def.cols().get(0)`（`MAX_COLS = 2`，多列维时 `colKey` 是 `" / "` 拼接串，用**第一个**列维字段解析会漏译第二个；因此多列维时对 `colKey` 按 `" / "` 分段、按 `def.cols()` 顺序逐段解析，同为 `AnalyticsLabels.joinDisplay(dataset, def.cols(), …)` 的复用）。
- 行/列维度键都来自 `def.rows()`/`def.cols()`（`ReportDefinition` 的 `List<String>` 字段 key），与 `AnalyticsDatasets.field()` 的白名单键一致。

### 5.3 `AnalyticsMetrics` 口径文案

把 6 条含裸 code 的口径改写成中文散文（`PENDING_TEST → 待测试`、`POOLED → 已入池`、`PASS/FAIL/BLOCKED → 通过/失败/阻塞`、`APPROVED/REJECTED → 已通过/已驳回`、`REQ_SUBMIT/REQ_REVIEW → 需求评审/评审入池`、`SUBMITTED/REJECTED → 已提报/已驳回`）。`P90 交付周期` 这类度量名不动（`\b[A-Z]{3,}` 不命中）。

新增单测守门（`AnalyticsLabelsTest` 内）：遍历 `AnalyticsMetrics.SPECS` 的 `caliber()`，断言均不匹配 `(^|[^A-Za-z])[A-Z]{3,}(_[A-Z]+)*([^A-Za-z]|$)`。

---

## 6. 前端

| 文件 | 改动 |
|---|---|
| `web/src/api/analytics.ts` | `CrossTab` 增 `colCodes?`/`rowCodes?`；注释写明 C-1 的长度守卫 |
| `web/src/stores/dict.ts` | ① `PRIORITY` 的 `label` 改 `紧急/高/中/低`（`hint` 原样保留）；② `StatusKind` 增 `'reqType' \| 'severity'`，新增 `REQ_TYPE`（BUS/PDR/RD）与 `SEVERITY`（P0..P3）两张表并入 `DICTS`；③ 新增 `has(kind, value): boolean`（查 `overrides` 与 `DICTS`，**不改** `meta()` 的既有兜底行为） |
| `web/src/components/analytics/labels.ts`（新建） | `export const UNKNOWN_LABEL = '未知状态'`；`export function valueLabel(field: string, value: string \| null \| undefined): string` —— 字段名→kind（`status→requirement`、`priority→priority`、`type→reqType`、`severity→severity`、`result→approval`，其余无 kind）；`has()` 为假且值形如枚举码 → `UNKNOWN_LABEL`；中文/时间/空 → 原样。内部 `useDictStore()`（pinia 在渲染期已激活，store 实例复用） |
| `web/src/components/analytics/DataStamp.vue` | `cacheLabel = HIT ? '缓存命中' : '缓存未命中'` 上屏；`HIT/MISS` 仅用于 `stamp__cache--hit/miss` 与 `title` 既有分支 |
| `web/src/views/reports/ReportEditorView.vue` | `scopeChips` 的取值一律 `valueLabel(field, value)`（含 `需求类型`），标签文本与 `filter` 字段逐个绑定 |
| `web/src/components/analytics/CrossTable.vue`、`ChartView.vue`、`chartModel.ts`、`PortalView.vue`、`PortalSection.vue`、`ReportListView.vue`、`DashboardView.vue` | **不改**：跨表头/行头/图例/轴标签全部来自服务端 `colHeaders`/`rowHeaders`/`row.key`；列表页已有 `SCOPE_LABEL` 中文映射；图例行 `:key` 已用 `index` |

**为什么 chips 用 `dict.ts` 而不是让后端再下发词表**：`dict.ts` 已是全站唯一前端词典（`meta()/label()` 被 8+ 页面使用），新增 `reqType/severity` 只是补齐它缺的两族；若再让 `/analytics/datasets` 下发一份 `valueLabels`，就等于给前端两套词表，正好踩中 PRD 的反面目标。后端注册表负责**服务端产出**（表头/行头/导出/口径），`dict.ts` 负责**客户端回显**（chips），两者由 `analytics-i18n-verify` 的 R1–R7 断言强制逐字符串相等。

---

## 7. 关键实现点与边界

1. **合计列**：无列维时 `colKey = 合计` 只作内部 key，表头仍是纯度量名；`colCodes = []`。
2. **两个未登记 code**：显示同文 `未知状态`，列数不变、不合并、数值不挪位；日志各记一条；`colCodes` 保留原值。
3. **多列维**：`colKey` 是 `" / "` 拼接串，必须**逐段**按 `def.cols()` 顺序翻译，不能只看首列。
4. **行头重复**：中文译名可能撞车（未来两条 code 同译），`rows` 按 raw 顺序输出、`row.key` 可能重复；`CrossTable` 已用 `r-${rowIndex}-${row.label}` 作 `:key`，不会产生 Vue 重复 key 警告（AC-12 零控制台错误）。
5. **缓存降级路径**（`:898`）重建 `CrossTab` 时必须带上两个新字段，否则命中缓存的响应少字段，AC-3 会间歇性失败——这是本轮最容易漏的一处。
6. **记录级缓存 key** 不含语言（只有一份语言），无需改 key；`AnalyticsCache` 语义不变。
7. **导出**：表头来自同一次 `crossTab` 调用（`EXPORT_MAX_ROWS = 10000`），`AC-9` 的页面/导出对比必须用**同一报表同一筛选**，否则数值差异会掩盖表头差异。
8. **XLSX 水印/页脚**含报表名与数据集名（`datasetLabel` 已是中文），不在本轮改动范围；CSV BOM 由 `WatermarkCsv` 负责，不进翻译链路。
9. **性能**：翻译是 O(维度基数) 的 Map 查表，`MAX_GROUPS = 5000`、导出 10000 行场景下可忽略；`display()` 的日志去重集合有上限，避免大批量导出刷日志。
10. **不翻译的地方**（明确列出，防"顺手改"）：`definition_json`、请求参数、`Filter` 的 `values`、API 错误码（`ANALYTICS_*`）、`Sort`/operator 码、`bucket` 码（`WEEK/MONTH`）。

---

## 8. 测试策略

### 8.1 单元测试（`AnalyticsLabelsTest`，新建，无 DB）

- 覆盖性：四数据集所有枚举字段的每个 code 在 `ROWS` 中都有中文（断言集合相等，防漏登记）。
- `display()`：命中→中文；空白→`""`；中文/时间桶/`未关联计划`→透传；`PENDING_XYZ`→`未知状态` 且不改长度。
- `joinDisplay()`：单段、两段、空段、多列维逐段翻译与 `" / "` 拼接。
- 口径文案不含英文枚举 token（§5.3）。
- `AnalyticsQueryServiceCrossTab` 不在单测覆盖范围（依赖 `JdbcTemplate` 流式查询，既有仓库也无此层单测）；由门禁的现场断言替代。

### 8.2 新门禁 `scripts/analytics-i18n-verify.sh`（`make analytics-i18n-verify`）

脚本结构（bash 只做编排，解析/渲染在 node）：

```
scripts/analytics-i18n-verify.sh
├─ 步骤 1 静态三方一致 → node scripts/tests/analytics/i18n-labels-consistency.mjs
├─ 步骤 2 现场渲染与导出 → node scripts/tests/analytics/i18n-live.mjs（需要平台栈）
└─ 末尾 PASS==TOTAL 守卫（任一红灯非零退出）
```

`i18n-labels-consistency.mjs` 解析三处文本并逐条比对（R1–R7）：

| 规则 | 断言 |
|---|---|
| R1 | `AnalyticsLabels.REQUIREMENT.status` == `dict.ts` `REQUIREMENT`（code 集合相等 + label 相等） |
| R2 | `APPROVAL.result` == `dict.ts` `APPROVAL` |
| R3 | `REQUIREMENT.type` == `dict.ts` `REQ_TYPE` |
| R4 | `REQUIREMENT.priority` == `dict.ts` `PRIORITY.label` |
| R5 | `QUALITY.severity` == `dict.ts` `SEVERITY` |
| R6 | `RoleCatalog.DICTS` 与 `SeedService` 内联数组里凡是注册表覆盖的 `(type, code)`，label 必须相等；`sys_dict` 未覆盖的族（如 `case_priority`）只列出不判错（种子是有意子集） |
| R7 | 注册表不得出现 `未知状态` 作为登记值（兜底文案不能被"登记"成某个 code 的译名） |

`i18n-live.mjs`（Playwright 已全局安装 1.58.2，**不加依赖**；bash 侧用 `NODE_PATH` 无解，改为把 `npm root -g` 路径传给 node，node 里 `await import(pathToFileURL(join(root,'playwright','index.js')))`，取不到时**红灯报错**并提示「勿新增依赖，检查全局 playwright」，绝不静默跳过）：

| 断言 | 内容 |
|---|---|
| A1 | `/dashboard` 交叉表 `th`（含列头/行头/图表图例/轴）文本不匹配 `\b[A-Z]{3,}(_[A-Z]+)*\b` |
| A2 | 缓存徽标文本 ∈ {缓存命中, 缓存未命中, 空}，且不含 `HIT`/`MISS` |
| A3 | `/portal` 五区卡片标题 + 口径文本 0 命中 |
| A4 | `/reports` 列表列名/范围/空态/分页器 0 命中 |
| A5 | `/reports/250` 与 `/reports/261`（及 `/reports/228` 导出）编辑器字段池/预览表头/范围 chips 0 命中，`需求类型` chip 显示 `研发需求/业务需求/产品需求` |
| A6 | 页面表头数组 == 导出表头数组（逐字符串；CSV 取首行按 CRLF 切；XLSX 直接读响应字节太脆，改为导出 `format=csv` 与页面比，另用 `format=xlsx` 断言状态 200 + 文件头 `PK`） |
| A7 | 不一致时打印首个失败下标（`[i] page=… export=…`），供 QA 直接定位 |
| A8 | 三张报表（250/261/228）各跑一遍 A6 |
| A9 | 页面控制台错误/警告（`console.error`、Vue warn）收集为空 |

脚本自带环境判据：先探 `GET /api/deliver/analytics/dashboard`（带登录态），未就绪则调用 `scripts/platform-up.sh` 后重探；仍失败按「哪一项 + 怎么修」打印。

接入：`scripts/test-all.sh` 在 `access-verify` 之后加一步 `run_step "分析域中文标签（analytics-i18n-verify）" bash "$ROOT/scripts/analytics-i18n-verify.sh"`；`Makefile` 增 `.PHONY` 与 `analytics-i18n-verify: ## …` 目标。**不伪造 `report-verify`/`workbench-verify`。**

### 8.3 回归清单（AC-11/AC-12，逐条命令）

```
make lint                       # scripts/tests/lint.sh
make platform-unit              # 单测（含新增 AnalyticsLabelsTest）
cd web && npm run typecheck     # vue-tsc
make platform-up && make platform-verify
make access-verify              # 143 条断言不变
make platform-test-negative
make analytics-i18n-verify      # 新增
make test                       # 全量（含新步骤）
```

截图证据：≥8 张（`/dashboard` 交叉表/转置、`/portal`、`/reports`、`/reports/250` 表头与导出首行），落在 `docs/teamflow/20261112-r14-analytics-header-zh/evidence/`，用仓库同一份 Playwright（`shots.mjs` 思路）重拍，不用 r13 旧图。

---

## 9. 任务拆分（按文件边界，可并行）

| 任务 | 文件（ownership） | 依赖 | 验收 |
|---|---|---|---|
| **T1 注册表与契约** | `deliver-service/.../analytics/AnalyticsLabels.java`(新)、`AnalyticsViews.java`、`AnalyticsQueryService.java`、`deliver-service/src/test/.../analytics/AnalyticsLabelsTest.java`(新) | 无 | `mvn -pl deliver-service test` 绿；`colHeaders` 中文且 `colCodes`/`rowCodes` 符合 §4.1；两处 `new CrossTab` 都补齐 |
| **T2 字典与种子对齐** | `web/src/stores/dict.ts`、`req-service/.../sys/RoleCatalog.java`、`req-service/.../seed/SeedService.java` | 无（与 T1 并行） | R3–R6 静态可过；`vue-tsc` 绿 |
| **T3 前端回显** | `web/src/api/analytics.ts`、`web/src/components/analytics/labels.ts`(新)、`DataStamp.vue`、`ReportEditorView.vue` | T1 契约（可读 §4 实现） | 四页 0 命中；徽标中文；chips 中文 |
| **T4 门禁脚本** | `scripts/analytics-i18n-verify.sh`(新)、`scripts/tests/analytics/i18n-labels-consistency.mjs`(新)、`scripts/tests/analytics/i18n-live.mjs`(新)、`Makefile`、`scripts/test-all.sh` | T1、T2、T3（要扫真实文本） | `make analytics-i18n-verify` 绿且能红（故意注入未登记 code 时 A1/A7 报错） |
| **T5 口径文案** | `deliver-service/.../analytics/AnalyticsMetrics.java` | 无（与 T1 并行；T1 的单测覆盖它） | `/portal`、`/analytics/metrics` 口径 0 命中 |
| **T6 交付自检与证据** | 仅 `docs/teamflow/20261112-r14-analytics-header-zh/evidence/*`（截图） | T1–T5 | 跑 §8.3 全量；重拍 ≥8 张截图；**一次性提交**，`git status` 仅剩忽略项。不写 QA 报告/验收报告，不做跨浏览器矩阵 |

> 并行度：T1、T2、T5 文件互斥可同时开工；T3 待 T1 契约稳定（或按 §4 直接实现，读不阻塞）；T4 收尾；T6 最后统一提交。

---

## 10. 风险、回滚与已知重复

| # | 风险 | 处置 |
|---|---|---|
| R-1 | 两处 `new CrossTab` 只改一处 → 缓存命中时缺 `colCodes` | T1 验收项显式列出；门禁 A6 在冷/热两种缓存态各跑一次 |
| R-2 | 列序被中文排序打乱 | §5.2 固定「raw 排序、只换显示」；AC-2 逐列对齐断言 |
| R-3 | 两个未知 code 合并成一列 | key 保持 raw；门禁注入 `ZZZ_UNKNOWN`/`ZZZ_OTHER` 断言列数 +2 |
| R-4 | 多列维只译首列 | §5.2 逐段翻译；门禁加一条 2 列维报表用例 |
| R-5 | `dict.ts` 改 `PRIORITY.label` 影响别页 | 只改 `label`，`color/hint` 不动；影响面靠 `vue-tsc` + 截图回归覆盖；已确认 `P0` 无测试文本断言 |
| R-6 | 门禁依赖全局 Playwright 缺失 → 误绿 | 取不到模块即红灯（禁止静默跳过） |
| 回滚 | 单提交特性分支，`git revert <sha>` 即可；无迁移、无配置、无依赖变更 | — |

**已知重复（登记，不本轮合并）**
- G-1 注册表（Java）↔ `dict.ts`（TS）↔ `sys_dict` 种子：三份物理副本，语义单一，由 R1–R6 强制一致。
- G-2 `severity` 旧写法 `S1..S4`（仅 `RoleCatalog` 声明，全仓零引用）与 `defect.severity = P0..P3` 不一致 → 统一 `P0..P3`。
- G-3 `RoleCatalog.DICTS` 与 `SeedService` 内联字典双份种 `sys_dict` → 后续需求合并为一份。

---

## 11. 工程约束（PRD「工程约束」，必须落到任务里）

1. 分支 `feat/analytics-header-zh`；**全部改动一次性提交**，提交信息 `feat: 分析域四页与导出表头统一中文（colCodes/rowCodes 保机器可读）`。
2. 提交后 `git status --short` 只允许忽略项（`target/`、`node_modules/`、`.nacos/`、`logs/`），不得留未跟踪临时文件；截图证据入库到本任务夹。
3. **不新增依赖**（`package.json`/`pom.xml` 零 diff）、不加 Flyway 迁移、不改 API 路径与既有字段语义。
4. 门禁不得引入假绿：`report-verify`/`workbench-verify` 不创建；新脚本失败必须给出「哪一项 + 怎么修」。
5. 文档只写 `docs/teamflow/`，`AGENTS.md` 除 TeamFlow 托管区外不动。
