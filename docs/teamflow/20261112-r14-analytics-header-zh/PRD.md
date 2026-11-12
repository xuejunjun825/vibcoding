<!-- meta: summary="把分析域四页与 XLSX/CSV 导出中用户可见的维度取值统一成一份中文标签（后端单一注册表），英文枚举 code 只保留在 API payload 的新增 colCodes/rowCodes 中。" -->
基线依赖：docs/teamflow/20261111-r13（四页视图、共享组件、交叉表/导出链路与 20 条 AC 的既有行为，本轮不得回归）
取代：docs/teamflow/20261111-r13#AC-13：交叉表列头由「数据库取值 · 度量」（形如 `IN_DEV · 需求总量`）改为「维度中文标签 · 度量」（形如 `开发中 · 需求总量`），机器可读 code 移入新增的 `colCodes`

# 分析域表头中文化：用户可见枚举只留中文

## 1. 背景与目标

r13 把四个分析域页面做了出来，但看板、报表与导出的表头直接把数据库维度取值（状态 code）拼进了给人看的列头。实测（2026-11-12 17:00，租户 t1，admin）：

| 面 | 实测文本 | 根因 |
|---|---|---|
| `/dashboard` 交叉表列头 | `DELIVERED · 需求总量`、`IN_DEV · 需求总量`、`PENDING_ANALYSIS · 需求总量` … 共 10 列 | `deliver-service/.../analytics/AnalyticsQueryService.java:406` `colHeaders.add(... colKey + " · " + label)`，`colKey` 是库里的原始取值 |
| 报表预览（报表 250 / 261） | `DONE · 平均交付周期` 等 20 / 10 列 | 同一构造点，`:373-374` 行头同样是原始 code |
| CSV / XLSX 导出 | 首行表头 `分组,DELIVERED · 需求总量,…` | 导出复用同一 `CrossTab`（`reports/ReportService.java:338-372`），直接写 `tab.colHeaders()` |
| `/portal` 卡片口径 | `待测试 · 状态为 PENDING_TEST 的需求数`、`测试通过率 · PASS 的执行数 ÷ …` | `web/src/components/portal/PortalSection.vue:58` 拼 `metric.caliber`，口径文案来自 `AnalyticsMetrics` |
| 图表视图 | 图例/轴标签 `SUBMITTED`、`DONE` | `web/src/components/analytics/chartModel.ts:136-175` 直接消费 `rowHeaders`/`colHeaders` |
| 缓存徽标（四页共用） | `HIT` / `MISS` | `web/src/components/analytics/DataStamp.vue:26-35` 原样渲染 `cache` |

前端早已有全套中文标签（`web/src/stores/dict.ts` 自称「文案唯一来源」），但分析域的表头没走它；后端的 `AnalyticsMetrics` 只有度量名是中文，维度取值没有任何 code→中文 映射。结果是同一个 `IN_DEV`，需求列表说「开发中」，看板说 `IN_DEV`——一套系统两套说法。

**目标**：一处定义、四处同源。中文标签在 deliver-service 侧统一注册，页面与导出只消费同一份结果；英文 code 只留在 API payload（新增机器可读字段），任何人可见的文本里不再出现全大写枚举。

### 判定口径（贯穿全文）

- **用户可见文本** = 页面渲染文本 + 导出文件文本，含四类：①表头/行头/分组列；②列名与图例、坐标轴；③系统生成的取值回显（筛选 chip、状态徽标）；④页脚与元信息（水印行、分页器、CSV 首行 `#`）。
- **判定阈值**：`\b[A-Z]{3,}(_[A-Z]+)*\b` 命中即失败。`P0` 这类两字符不命中，是否也中文化见 §8 的 T2。
- **不算系统文案**：用户在筛选值输入框里手写的 code（自由文本输入，原样回显），见 §3。

## 2. 用户故事与验收标准

- **US-1** 作为业务用户，我在看板与报表里看到的是「开发中 · 需求总量」，不用先猜 `IN_DEV` 是什么。
- **US-2** 作为汇报者，我导出的 XLSX/CSV 与屏幕上逐字一致——同一份标签，不是各译一遍。
- **US-3** 作为研发/运维，我仍能从响应的 `colCodes`/`rowCodes` 拿到机器可读 code，不用解析中文。
- **US-4** 作为 QA，我用一条命令扫四处文本，命中 0 即通过；新增状态漏登记会被拦下。

### AC-1 中文标签单一注册表（P0）

- 判据 1：deliver-service 内存在**唯一**的「维度取值 code → 中文标签」注册表，覆盖四数据集全部可作行/列维度的枚举字段（requirement 的 status/type/priority、delivery 的 status、approval 的 bizType/node/result、quality 的 severity/status 等）；对每个字段，其实际取值集合与注册表键集合的差集为空（单测遍历 `AnalyticsDatasets` 白名单断言，未登记数 = 0）。
- 判据 2：注册表与 `web/src/stores/dict.ts`、`sys_dict` 种子三处逐条一致（同一 code 的 label 字符串相等），差异数 = 0；不一致时断言输出 `code + 三处 label`。
- 判据 3：表头/行头构造只消费注册表解析结果，`AnalyticsQueryService.java:406` 式的 `colKey + " · "` 拼接 0 处残留；`web/src` 内不得为分析域新建第二份 code→中文 映射（中文只能来自后端下发或复用 `dict.ts`）。

### AC-2 表头与行头只含中文（P0）

- 判据 1：`GET /api/deliver/analytics/dashboard` 的 `breakdowns[*].colHeaders` 与 `POST /api/deliver/reports/{id}/run` 的 `rowHeaders`/`colHeaders`，正则命中 0（含多值维度按 `" / "` 拼接后逐段翻译，分隔符与顺序不变）。
- 判据 2：列头结构保持 `<维度中文> · <度量中文>`（列维非空时）；无列维时仍是 `合计`；小计/总计行头不变。
- 判据 3：**列数、列序不变**。dashboard 10 列、报表 250 的 20 列、报表 261 的 10 列，改后同数同序；`colCodes[i]` 等于改前第 i 列表头的前段 code（逐列对齐断言）。

### AC-3 机器可读 code 留在 payload（P0）

- 判据 1：`CrossTab` 新增 `colCodes[]` 与 `rowCodes[]`，长度与对应 headers 相等、顺序一一对应，元素是原始取值（多值沿用 `" / "`）；无列维时 `colCodes = []`，`合计` 列对应 `null`。
- 判据 2：既有字段名、路径、语义不变（`colHeaders` 仍是字符串数组，只是内容变中文；`rows[].key` 语义不变）；不新增/删除端点。
- 判据 3：前端 `web/src/api/analytics.ts` 的类型同步到位，`vue-tsc` 0 错误；空值/缺字段时不抛错（老响应兼容）。

### AC-4 `/dashboard` 渲染文本零英文枚举（P0）

- 判据 1：五区（KPI、交叉表、趋势、分段、质量卡）渲染文本正则命中 0；转置视图同样 0。
- 判据 2：缓存徽标显示「命中 / 未命中」（`HIT`/`MISS` 只留在 payload 的 `cache` 字段）；`title` 保持「服务端缓存命中/未命中」语义。
- 判据 3：图表视图（柱/堆叠柱/折线/热力）的图例与轴标签命中 0——图表不得另起一套标签。

### AC-5 `/portal` 卡片与分区文案零英文枚举（P0）

- 判据 1：5 个角色模板（admin/po/sm/dev/qa1 现有模板集合）的卡片标题、分区标题、空态与降级文案命中 0。
- 判据 2：卡片「标题 · 口径」中的口径文案把 code 换成中文标签（如 `状态为 PENDING_TEST 的需求数` → `状态为「待测试」的需求数`，`PASS/FAIL/BLOCKED` → `通过/未通过/阻塞`）；具体措辞可微调，但整句必须 0 命中。
- 判据 3：口径文案与图表/表格用的是同一份标签，不出现「卡片说待测试、看板说测试中」。

### AC-6 `/reports` 列表零英文枚举（P0）

- 判据 1：列表表头列名、数据集名、范围标签（私有/角色/域/厂商/公开）、所有者与更新时间格式、操作列、空态文案命中 0。
- 判据 2：分页器文案（`共 N 条`、`第 x 页`、每页条数）命中 0；`/dashboard` 与交叉表的分页/截断提示同样 0。

### AC-7 `/reports/:id` 编辑器零英文枚举（P0）

- 判据 1：左栏字段池与四槽、图表模式、预览区（`CrossTable` 与图表）、范围 chips、错误与降级提示命中 0。
- 判据 2：「需求类型」chip 显示中文（`RD`→研发需求、`BUS`→业务需求、`PDR`→产品需求），不得直接拼 `filter.reqType`。
- 判据 3：`业务域 #3`、`厂商 #2` 一类既有中文 chip 保持不变（不借机改文案）。

### AC-8 导出表头全中文（P0）

- 判据 1：XLSX 的表头行、行头首列、水印行与页脚命中 0；CSV 的 `#` 元信息首行与表头行命中 0；BOM、分隔符、列顺序不变。
- 判据 2：与改前同一报表逐单元格对比，除表头/行头文本外，行数、列数、数值完全相同（数值零漂移）。
- 判据 3：`format=xlsx|csv` 两种都验；导出件落在被 `.gitignore` 覆盖的路径，不污染仓库。

### AC-9 页面与导出逐字一致（P0）

- 判据 1：同一报表、同一 scope，页面表头字符串数组与导出文件表头逐字符串相等（至少覆盖报表 250 / 261 / 228 三张：有列维两张、无列维一张）。
- 判据 2：断言失败时能指出第一处不一致的下标与两侧文本（可定位，不是布尔值）。

### AC-10 未登记取值的兜底（P1）

- 判据 1：注入一个注册表没有的维度取值，列头回退为约定的兜底文案（见 §8 的 T3，默认「未知状态」），**列数不变**、两列不同 code 不会被合并成一列；`colCodes` 保留原 code，日志/响应 warnings 记录「未登记」原值。
- 判据 2：兜底文案本身不命中正则（不把 code 当兜底）。
- 判据 3：已登记取值不受兜底逻辑影响（回归：10/20/10 列结果与 AC-2 一致）。

### AC-11 门禁与扫描（P0）

- 判据 1：新增 `make analytics-i18n-verify`（`scripts/analytics-i18n-verify.sh`），一次跑完四处扫描（dashboard 交叉表、报表预览、XLSX、CSV）+ AC-9 一致性 + AC-1 判据 2 字典一致性；输出可读计数（如 `headers=31 exported=31 hits=0`），失败给「哪一处 + 哪段文本」。
- 判据 2：该目标接入 `make test`；`make lint`、单测、`vue-tsc`、`scripts/platform-verify.sh`、`scripts/access-verify.sh`（断言数不少于 143，不因本轮减少）、`scripts/tests/verify-negative.sh` 全绿。
- 判据 3：需求点名的 `report-verify` / `workbench-verify` 在当前仓库不存在（Makefile、`scripts/`、`git ls-files` 均无）；**不伪造同名空目标**，由新脚本承接其意图，并在 TECHNICAL 中记录这一缺口（见 §8 F1）。

### AC-12 零回归与证据（P0）

- 判据 1：r13 的 20 条 AC 对应探针全部重跑通过（四页可达、共享弹层五档、pin 409、导出、交叉表/转置/宽表、缓存与 dataAsOf 等），不因改表头而放宽或删除断言。
- 判据 2：凡画面包含表头的截图全部重拍（≥8 张），以中文表头为准；其余截图不动；重拍件零控制台错误。不得用 r13 的英文截图充当本轮证据。
- 判据 3：一次性提交（代码 + 门禁脚本 + 证据），提交后 `git status` 只剩忽略项。

## 3. 范围与非目标

**范围**：四个分析域页面（`/portal`、`/dashboard`、`/reports`、`/reports/:id`）的全部系统生成文本；报表导出（XLSX/CSV）；`CrossTab` 契约的 `colCodes`/`rowCodes` 扩展；新增一条扫描门禁。

**非目标**（本轮明确不做）：

- 视觉改版：不调配色、字体、间距、布局，不新增页面与区块。
- 不做值选择器与「中文 → code」反向映射：编辑器筛选值输入框仍是自由文本，用户手写的 code 原样回显（那是用户数据，不是系统文案）；要改成中文下拉是另一个需求（见 §8 T4）。
- 不做多语言/i18n 框架：产品面向中文用户，标签硬编码为中文，不做语言包与运行时切换。
- 不做演示数据清理（128 张报表、探针命名、脏数据），另开一轮。
- 不改 API 路径与既有字段语义，不做 API 版本化；不引入新依赖。
- 不中文化 `FilterBar` 降级提示里的 query key（`部分筛选已重置：domainId`）——它不是表头/列名/取值，改了要连带设计一套 query key 文案表，另轮处理（此处显式记账，避免"漏了"的误解）。
- 不做管理端字典维护页（`sys_dict` 的编辑 UI）。

## 4. 反面目标 / anti-goals

以下任何一条成立，即使测试全绿，这次交付也算失败：

1. **同一个状态两套译名**：看板列头写「开发中」，需求列表写「研发中」，导出又写「开发」——用户得记两套词，且没人知道哪个是准的。（观察方式：同一 `IN_DEV` 在需求列表、看板列头、导出表头三处并排看。）
2. **页面与导出各译一遍**：页面先拼中文、导出在 `WatermarkCsv`/`WatermarkSheet` 里再译一次；某天改文案，页面变了导出没变，用户拿到的 XLSX 与屏幕对不上。（观察方式：改一处标签，重新导出同一报表对比。）
3. **不认识的取值被"处理"掉**：新增一个状态后，列头统一变成「其他」，两列合并、列数变少或数值挪位——数字对不上比看到英文更糟。（观察方式：注入未登记 code，数一数列数与每列合计。）
4. **只改了一半**：表头中文了，图表图例、坐标轴、缓存徽标、导出单元格、portal 口径还是 `SUBMITTED`/`HIT`/`PASS`；用户切到柱状图一眼看到英文。（观察方式：四页各切一次图表视图并导出一张表。）
5. **证据是旧的**：拿 r13 的英文截图宣称 AC 通过，而当前界面已是中文；评审打开目录看到的是"另一套界面"。（观察方式：打开截图与当前页面并排。）

## 5. 交互流程摘要

1. 用户进入 `/dashboard`（或打开报表预览/编辑器预览），前端请求 dashboard 或 `run`。
2. 后端把维度取值交给注册表解析成中文；表头 = `维度中文 · 度量中文`，同时把原始取值填进 `colCodes`/`rowCodes`。
3. 前端把 headers 直接当列头、图例、轴标签用（不再做二次翻译）；缓存徽标把 `cache` 映射成「命中/未命中」。
4. 用户点导出：`ReportService.export` 调同一个 `crossTab`，`WatermarkSheet`/`WatermarkCsv` 写同一份中文表头——页面与导出天然同源。
5. 遇到未登记取值：兜底文案上屏、原值进 `colCodes` 与日志，列数与列序不塌。
6. QA 跑 `make analytics-i18n-verify`：四处扫描 + 字典一致性 + 页面/导出逐字比对，命中 0 则通过。

## 6. 优先级

- **P0**（本轮必须）：AC-1 ~ AC-9、AC-11、AC-12。
- **P1**（本轮做，但不阻塞主链路）：AC-10（未登记兜底；没有它新状态会破列，故仍列入本轮，若时间紧可降级为"列头显示兜底文案 + 日志"最小实现）。
- **P2**：无。

## 7. 依赖与风险

**依赖**：r13 的四页与共享组件（`CrossTable.vue`、`chartModel.ts`、`DataStamp.vue`、`FilterBar.vue`、`PortalSection.vue`）；后端 `AnalyticsMetrics`/`AnalyticsDatasets` 注册表与 `AnalyticsViews.CrossTab`；`web/src/stores/dict.ts`；`sys_dict` 种子（`SeedService.java:234-237` 只种了 4 类，多数状态 code 不在其中）。

**风险**：

| 风险 | 影响 | 缓解 |
|---|---|---|
| 标签出现三处副本（后端注册表 / `dict.ts` / `sys_dict`） | 漂移后同码不同词，正是 anti-goal 1 | AC-1 判据 2 的三方一致性断言进 `make test` |
| 中文列头变宽，导出列宽/表头折行 | 打开 XLSX 先看到折行或 `####` | 列宽按中文宽度自适应；截图核对首屏表头可读 |
| 前端类型漂移（r13 已知 `api/analytics.ts` 与后端有 wire 漂移） | `colCodes` 未进类型，页面静默丢失 | AC-3 判据 3 + `vue-tsc` 门禁 |
| 既有探针按英文列头匹配 | 回归变红，容易被"改断言"掩盖 | 只允许改文本期望值，不允许删断言；AC-12 判据 1 |
| 128 张旧报表的定义里存着 code | 误解为"数据要迁移" | 展示层翻译，存储不动；本轮零数据库迁移 |
| 需求点名的门禁不存在 | "全绿"是假绿 | 新脚本承接 + 在 TECHNICAL 记录缺口 |

## 8. 假设与待澄清

- **F1（事实）门禁缺口**：需求要求 `report-verify` / `workbench-verify` 全绿，但当前仓库没有这两个目标与脚本（已核 Makefile、`scripts/`、`git ls-files`）。假设：新建 `scripts/analytics-i18n-verify.sh` + `make analytics-i18n-verify` 承接"四处扫描"意图并纳入 `make test`，不伪造同名空目标。若你要求保留原名，会让"绿"变成空跑。
- **T1（口味）表头文案格式**：保留 `维度 · 度量`（`开发中 · 需求总量`），还是维度值单行 + 度量做合并表头，或 `需求总量（开发中）`。推荐保留 `维度 · 度量`：改动最小、导出结构不变、一行能读全；换格式会让宽表更宽。
- **T2（口味）短代码是否也中文化**：`P0/P1/P2/P3`（优先级）与 `PASS/FAIL/BLOCKED`（执行结论）这类业界通用缩略语。推荐一并中文化（紧急/高/中/低、通过/未通过/阻塞），因为需求要求"一律只用中文"；但团队日常说"P0 缺陷"，保留也说得通。
- **T3（口味）未登记取值的兜底文案**：显示「未知状态」、「其他」，还是原样显示 code。推荐「未知状态」——不破正则、不撒谎、原值仍可从 `colCodes` 与日志拿到；「其他」会和真实的"其他"分类混淆。
- **T4（口味）编辑器筛选值**：只中文化系统文案、输入仍收 code（推荐，范围可控、不动契约），还是同时提供中文值下拉（要加"中文→code"反查与已存 code 的回显翻译，范围明显变大）。

## 9. 里程碑建议

- **M1 注册表与后端契约**：注册表 + `CrossTab.colCodes/rowCodes` + 表头/行头改造 + 单测（AC-1/2/3/10）。
- **M2 前端四页**：`CrossTable`/`chartModel`/`DataStamp`/`PortalSection`/编辑器 chip（AC-4~AC-7）。
- **M3 导出**：XLSX/CSV 同源验证与逐单元格对比（AC-8/9）。
- **M4 门禁与证据**：新脚本接入 `make test`、全量回归、截图重拍、一次性提交（AC-11/12）。

## 10. 工程约束

**分支与提交（用户已拍板，原样保留）**

- 分支：**新建分支开发**（用户已选）。host 状态显示当前已在 `feat/analytics-header-zh`，无需再建第二条；本轮所有改动在此分支完成。
- 改前工作区仅有一处未提交改动：`?? docs/teamflow/20261112-r14-analytics-header-zh/`（本任务夹，未跟踪）。实现阶段与代码、门禁脚本一并纳入**一次性提交**，提交后 `git status` 只剩忽略项；不要为本任务夹单独开提交。

**.gitignore（提交前核查，已确认无需扩项）**

- 现有规则已覆盖本栈产物：`target/`、`.nacos/`、`.run/`、`.m2/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`var/`、`*.pid`、`logs/teamflow/`、`logs/`、`*.log`、`.env*`、`test-results/`、`playwright-report/`、`*.xlsx`、`*.csv`、`.idea/`、`.vscode/`、`.DS_Store`。
- 本轮会手工导出 XLSX/CSV 做验证，落在项目根或 `var/` 下均已被忽略；若新增其他导出目录，必须同步补 `.gitignore`，不得让二进制件进历史。

**硬约束（原文保留）**

- 界面与导出表头必须同源同一份中文标签（不要前端译一遍、后端导出再译一遍）：优先在 deliver-service 侧统一「维度值 code → 中文标签」映射，前端不再二次拼英文。
- 不改既有 API 路径与响应结构语义；如需保留机器可读 code，可新增字段（如 `colCodes`）而不是把 code 塞进给用户看的 `colHeaders`。
- 页面与导出必须一致（既有 AC：导出与页面一致），因此导出表头也必须是中文。
- 不破坏既有门禁：`platform-verify` / `report-verify` / `workbench-verify` / `platform-test-negative` / `unit` / `lint` / `access-verify` 全绿；r13 的 20 条 AC 不得回归。
- 新增或扩展断言：扫描渲染文本与导出表头不得出现全大写枚举模式（`\b[A-Z]{3,}(_[A-Z]+)*\b` 命中即失败），覆盖 dashboard 交叉表、报表预览、XLSX 与 CSV 导出四处。
- 范围外：不做视觉改版、不改配色与布局、不加新页面；演示数据污染（报表 128 张、探针命名）另开一轮，不在本轮。

## 11. 验收证据清单

1. `make analytics-i18n-verify` 输出（四处扫描 + 一致比对 + 字典一致性，命中 0）。
2. dashboard 改前/改后 `colHeaders` 与 `colCodes` 对照（10 列逐列对齐）。
3. 报表 250 / 261 / 228 的页面表头与导出表头的逐字符串比对记录。
4. XLSX 与 CSV 各一份样本（首个表头行截图或文本转录）。
5. 重拍截图目录（≥8 张，含表头画面），零控制台错误。
6. 既有门禁全绿记录：lint、单测、`vue-tsc`、platform-verify、access-verify（143）、platform-test-negative、r13 分析域探针。
