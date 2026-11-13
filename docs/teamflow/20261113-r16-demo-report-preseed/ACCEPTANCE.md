# 验收报告 · r16 演示重置后预置 2 张演示报表

- 任务夹：`docs/teamflow/20261113-r16-demo-report-preseed/`｜分支：`feat/demo-report-preseed`（未提交，工作区即交付面）
- 交付面：`DemoFixture.java`（+96）、`DemoResetService.java`（+60/−9）、`scripts/platform-demo-reset.sh`（+107/−20）、`scripts/tests/analytics/i18n-live.mjs`（门禁适配）、新增 `DemoReportFixtureTest.java`、任务夹
- 替代行为：`取代：docs/teamflow/20261113-r15-demo-reset-pollution#AC-1` —— 重置后报表中心由「空态 total=0」改为「恰 2 张演示报表」
- 验收方式：不只采信 QA 结论——独立复跑重置脚本 3 次、i18n 门禁、幂等/越权探针，逐行走查 4 个改动文件的 diff，并回源核对厂商 code/name 与白名单

## 1. 逐条验收（PRD AC-1~AC-12）

| AC | 判据 | 独立证据（本人复跑/核对） | 结论 |
|---|---|---|---|
| AC-1 数量与名称 | total=2 且名称集合恰为两张；counts/库内/API 三处一致 | `bash scripts/platform-demo-reset.sh` → **52/52**：[30] 库内 2 行且 `name IN` 命中 2/2；[42] API `total=2`、名称集合「厂商交付概览,需求状态分布」；服务端 `check_count reports 2` 同轮通过 | ✅ |
| AC-2 两表不同且字段合法 | `(dataset,rows,columns,measures)` 两两不同、全在白名单 | `DemoFixture.REPORTS`：A=`requirement/[domain]/[status]/[req.total,cycle.avg]`，B=`requirement/[vendor]/[]/[req.total,effort.estimate,effort.deviation]`；白名单 `AnalyticsDatasets.java:177-187`；两表 run 均 200（字段越界会 400，构成反证） | ✅ |
| AC-3 打开即出数 | A 行≥1 且需求数有正值；B 厂商行≥2 且预估人天>0；cells 非全空 | [43] A：rowCodes 5 行 / colHeaders 14 列 / 首度量合计 **12.0**；[44] B：**3 行（未设置/甲方科技/丙方信息）**、预估人天 **62.0**。VEN-A=甲方科技、VEN-C=丙方信息（`SeedService.java:166-168/171-173`），PRD 点名的两家均出现；`未设置` 行来自无厂商的需求，不违反「≥2 行」 | ✅ |
| AC-4 中文标签不回归 | 表头全中文、枚举码 0 命中、codes 同长同序、i18n 门禁绿 | 本人直读两表 run：全部行/列表头为中文，正则 `\b[A-Z]{3,}(_[A-Z]+)*\b` **0 命中**；A `colCodes` 14/14 同序、`rowCodes` 5/5；B 无列维故 `colCodes=[]`（`AnalyticsQueryService.java:425-426` 既有特例，非本需求引入）。`bash scripts/analytics-i18n-verify.sh` → **74/74**，且提示「预置报表 250/261/228 命中 0 个，补入 421/420」——两张预置表被门禁真实纳入、夹具跑完即删 | ✅ |
| AC-5 可导出且带水印 | 200 + PK 头 + 表头与页面逐字相等 + 水印 | 导出 A 4551B / B 4193B，均 `PK` 头、`sharedStrings.xml` 各含 1 处「内部资料（水印）」；页面全部 row/col 表头逐个 `grep -F` 命中导出共享字符串（0 缺失）；门禁 CSV 逐列比对 41/41 | ✅ |
| AC-6 幂等 | 两次重置后 total=2、名称相同、`definition_json` 规范化逐字段相等；id 不写死 | 幂等探针 **6/6**：两次 reset 均 exit 0、活动行 2/2、`definition_json` 规范化 md5 `9bea00e2f9bd…` 相等、id `417,416 → 421,420` 可变、API total=2 | ✅ |
| AC-7 探针零残留 | `T8 编辑器探针`/`r11-ev-`/`r13-` 0 命中 | 重置脚本 `check_count probeReports 0` 在 52/52 中通过；探针谓词仍单点在 `DemoResetService.java:67`，本轮未改 | ✅ |
| AC-8 只在重置链路产生 | seed 后报表仍 0、正常启动不插入、不新增 make 目标/第二套目录 | `grep -rn report_definition req-service/src/main/java`：写入仅 `DemoResetService.java:440` 回填一处，`SeedService` 无报表分支；越权探针 [13]「internal/seed 之后报表集合不变」；无新 migration、无新 make 目标、定义以代码常量驻留 `DemoFixture`（无独立 JSON 文件） | ✅ |
| AC-9 不越权 | 非属主 run/export 403/404 且无数据泄漏 | 探针 **13/13**：po1/vendor1 报表列表为空；对 420/421 的 run+export 全部 **404**，响应不含需求行/厂商名/rowHeaders，导出非 xlsx | ✅ |
| AC-10 门户口径不变 | admin 4 张模板卡、无 `report:` 前缀、portalPins=0 | [46] cards=4、[47] 无 `report:` 前缀；`portalPins` 计数断言 0 通过；两张报表 `scope=PRIVATE, scope_ref=null` 不 pin | ✅ |
| AC-11 门禁全绿 | 8 道门禁绿 + 重置脚本 0 fail 且写明条数 | 本人复跑：reset **52/52**（其中一次是在 i18n 门禁写入软删行之后跑的，仍 52/52 → r15 的「门禁污染可被重置修复」未回归）、analytics-i18n **74/74**。QA 实测：unit 262→**268**、platform-verify **167**、access **145**、negative **25**、lint **18**、verify **14**、seed **10** | ✅ |
| AC-12 失败可定位 | 新断言失败信息带数量/集合/维度 | diff 可见：库内断言打印「X 行 / 名称命中 Y 行，期望 2 行且命中 2」；列表断言打印实际 total 与名称集合及期望集合；B 的 run 失败文案直指 `assignee_vendor_id`（→`AnalyticsSql.java:172`） | ✅ |

> 状态索引中的 AC-13~AC-20（交叉表/守卫/pin/截图/access 路由等）属既往任务（r14 等）的 AC，本需求 PRD 仅有 AC-1~AC-12；其回归由既存门禁承担（access 145、verify 14 等本轮全绿）。

## 2. 架构一致性（M3 质量门）

- **蓝图对位**：蓝图 4 个文件全部落实且职责与规定一致——`DemoFixture` 单点拥有 `ReportFixture`/`REPORTS`/`REPORT_NAMES`/`REPORT_COUNT`/`reportRows`（`definitionJson()` 固定键序 `datasetId,rows,columns,measures,filters`、`filters` 恒 `[]`）；`DemoResetService` 在 `purge(report_definition)` 后第 9 步回填、`references()` 纳入 `user:admin`、`violations()` 改 2 + TreeSet 名称集合、`reportNames()` 不 `ORDER BY`；脚本只做搬运与复核（不持表清单、不做保留判断）。
- **单点与无重复**：`definition_json` 仅在 `definitionJson()` 构造一次（`:226-232`），插入处直接复用（`:275`）；`REPORT_COUNT` 由 `REPORTS` 派生，无第二份数字；服务端 `TreeSet`、脚本 `jq sort` 各自规范化，中文排序不依赖 MySQL collation。未复刻 deliver 的字段白名单（漂移由 run 的 400 暴露）。
- **蓝图外改动 1 个（已判定可接受）**：`scripts/tests/analytics/i18n-live.mjs` 把导出 query 补上 `reqType=RD`（`I18N_REQ_TYPE` 默认仍为 `RD`，行为不变），使门禁的「页面 vs 导出」比对不再因两条不同 query 的缓存条目而漂移。仅门禁脚本、非产品代码、方向是收紧比对，不构成 adapter drift 或结构破坏。
- **spec 外但必需的一处**：需求 INSERT 增写 `assignee_vendor_id`（与 `owner_vendor_id` 同值）。不加则厂商维整列「未设置」、AC-3 不成立；写法和 `RequirementService.java:392-393` 一致，代码注释已写明原因；隔离口径未变（`ScopeSql:54` owner∨assignee，实测 po1=8/vendor1=3/vendor2=0）。
- **结论**：无「该抽未抽」的重复实现、无旁路写报表入口、无既有结构破坏 → 不触发 ⚠️ 条件通过。

## 3. 提交面卫生

`git status --porcelain` 仅 4 个预期修改 + 2 个预期新增（任务夹、单测）；无 `target/`、`node_modules/`、`dist/`、`.env`、`logs/` 噪声（`logs/teamflow/` 已被忽略且由宿主管理生命周期）。

## 4. 遗留与观察（均不阻断）

| 编号 | 级别 | 内容 | 处置 |
|---|---|---|---|
| O-1 | P3 | `reqType` 只进缓存键不进 SQL（r14 遗留），同一报表 RD/PDR/BUS 结果相同 | 演示集仅 RD，不影响本轮；沿用既有 OUT-OF-SCOPE |
| O-2 | P3 | 「人天偏差」整列为 0（`actual_days = estimate_days`） | AC-3 只要求非全空；改数据会动 r15 已确认的 12 行口径，留待后续迭代 |
| V-1 | 提示 | 卡片工艺/宽表横向滚动/Excel 观感未经截图覆盖（宿主沙箱无浏览器） | 按 QA §7 人工补测清单执行，不影响功能验收 |
| V-2 | 提示 | 两张报表额外带回一行「未设置」（无厂商的需求） | 属真实数据分布，非缺陷；若演示需更干净可后续调演示集 |

## 5. 复跑命令（验收证据）

```bash
bash scripts/platform-demo-reset.sh            # 52/52，含 run/export 自证
bash scripts/analytics-i18n-verify.sh          # 74/74（预置表 421/420 被纳入）
bash logs/teamflow/tf-mwed9rbz-vnqp59/scripts/r16-qa-idempotency.sh   # 6/6
bash logs/teamflow/tf-mwed9rbz-vnqp59/scripts/r16-qa-ac9-scope.sh     # 13/13
```

验收结论：✅ 通过
