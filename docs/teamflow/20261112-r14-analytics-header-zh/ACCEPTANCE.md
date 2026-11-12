# r14 验收报告 · 分析域表头中文化

- 需求：/portal、/dashboard、/reports、/reports/:id 与 XLSX/CSV 导出中用户可见的维度取值统一为一份后端注册的中文标签；机器可读 code 只留在 API（新增 colCodes/rowCodes）。
- 分支 `feat/analytics-header-zh` · HEAD `11224f1` · 交付 31 项改动（24 改 + 7 新增，含 V11 迁移、门禁脚本、任务夹与 11 张证据）。
- 验收方式：QA 报告 + 本阶段独立复跑（不采信 dev/QA 自检结论）。**下表数字未标注「QA」者均为本次实跑输出。**

## 1. 逐条验收

| AC | 判据 | 本次实测证据 | 判定 |
|---|---|---|---|
| AC-1 | 单一注册表、三方差异 0、前端不得新建第二份映射 | `bash scripts/analytics-i18n-verify.sh --static-only` → exit 0，`COUNT static 33 33`（R1–R7 逐 code 比 label：Java 注册表 ↔ `dict.ts` ↔ `RoleCatalog`/`SeedService` 种子）；后端仅 `AnalyticsLabels` 一份 code→中文，前端仅 `dict.ts` 一份词表、`labels.ts` 只做字段→kind 绑定 | ✅（判据 1 的验证机制未按 PRD 实现，见 R2） |
| AC-2 | 表头/行头只中文，结构仍「维度 · 度量」，列数列序逐列不变 | 现场 `/dashboard` 表头「已交付 · 需求总量 / 开发中 · 需求总量 / 待分析 · 需求总量…」，行头「物流管理 / 主数据管理…」；live 门禁 41/41（含 A6 页面↔导出逐列） | ✅ |
| AC-3 | CrossTab 追加 colCodes/rowCodes，同长同序、合计列 null、路径与既有字段语义不变 | 主路径与 `withCache` 两处构造均已补；TTL 内连跑两次：`first=MISS second=HIT`，命中后 `colCodes`/`rowCodes` 与首次逐元素相等（QA `qa-r14-cache-codes.sh` 6/6）；无列维时 `[]` | ✅ |
| AC-4 | /dashboard 五区渲染 0 命中，含缓存徽标、图表图例/轴、转置 | live 扫描 0 命中；ECharts canvas 用 `fillText` 钩子录制（A1「录制条数>0」）；`evidence/dashboard-crosstab.png` 目视徽标为「缓存命中」、表头行头全中文 | ✅ |
| AC-5 | /portal 五角色模板卡片标题与口径文案 0 命中 | `caliber-i18n-check.sh` → 29 条口径 0 命中（PENDING_TEST→待测试、PASS/FAIL/BLOCKED→通过/失败/阻塞）；live 0 命中；卡片目视中文 | ✅ |
| AC-6 | /reports 列表列名/scope/空态/分页 0 命中 | live 页扫描 0 命中；QA 列表探针 24/24 | ✅ |
| AC-7 | /reports/:id 字段池/预览/范围 chips 0 命中，需求类型 chip 显示中文 | `ReportEditorView.vue` chips 走 `valueLabel`（实测「需求类型 · 研发需求」）；live 扫描 0 命中；`editor-250-*` 三图目视中文 | ✅ |
| AC-8 | XLSX 表头行/行头/水印与 CSV 首行 0 命中，BOM 与数值零漂移 | A6：XLSX/CSV 表头全中文（首格「分组」）；`export-250-xlsx-head.png` 目视 20 列中文；QA 导出 19/19、`xlsx-first-rows.py` 表头 21 格全中文 | ✅ |
| AC-9 | 同一报表页面表头与导出表头逐字符串相等（250/261/228），失败可定位下标 | A6 逐列断言、失败打印首个下标 `[i] page=… export=…`；本次 live 41/41，三张报表均比 | ✅ |
| AC-10 | 未登记取值回退「未知状态」、列数不变不合并、原 code 进 colCodes 与日志 | QA `qa-r14-ac10-unknown.sh` 4/4（`rowHeaders[8]=未知状态`、`rowCodes[8]=QA_UNKNOWN_CODE`）；`AnalyticsLabels.warnOnce` 按 dataset/field/code 去重；单测 `displayFallsBackToUnknownWithoutChangingShape` 覆盖时间桶/中文/空值透传 | ✅ |
| AC-11 | 新门禁 make analytics-i18n-verify 扫四处并接入 make test，其余门禁全绿 | 本次：`make analytics-i18n-verify` 74/74、`lint` 18/18、`access-verify` 145/145（≥143）、`vue-tsc` 0 错、`platform-verify` 168/168；`scripts/test-all.sh:45` 已 `run_step`。QA：`make test` 263/263、unit 259、negative 25/25；未伪造 report-verify/workbench-verify | ✅ |
| AC-12 | r13 20 条 AC 零回归；表头截图全部重拍 ≥8 张；一次性提交后仅忽略项 | QA 26 个 r13 探针全重跑通过（pages-smoke 57/57、portal 195、导出 19/19、交叉表 17/17…）；截图 11 张但 **仅 10 个唯一 md5**（`dashboard-chart.png` ≡ `dashboard-crosstab.png`，均为 `e38c62d4…`/127928B）；**交付仍未提交**（`git status` 31 项） | ⚠️ 判据 2、3 未满足 |

## 2. 架构一致性（M3）

- **按蓝图落位**：唯一拼装点 `AnalyticsQueryService.crossTab` 接入 `AnalyticsLabels`（raw/display 双轨、排序仍对 raw、`rowHeadersOf` 在 sortRows 之后按 key 回查，避开错位）；`CrossTab` 末尾追加两字段并保留 9 参兼容构造；导出复用同一 `CrossTab`，页面与导出天然同源；前端只做回显；门禁为 `scripts/tests/analytics/*` + Makefile 薄封装；`AnalyticsLabels` 不静态引用 `AnalyticsDatasets`（无类初始化环）。
- **对蓝图的一处必要纠正**：`WatermarkCsv`/`WatermarkSheet` 原用 `row.key()` 出裸码，蓝图称「导出无需改」不成立，已改用 `rowLabel(int, CrossRow)`——这是让 AC-8/AC-9 成立的正确修正，不算偏离。
- **质量问题（真正拉低判定）**：① `AnalyticsLabels.joinDisplay` 生产路径零调用，`crossTab` 用 `rowShow/colShow` 内联重写了同一拼接 → 死代码 + 同一逻辑两份实现，且单测 `joinDisplayTranslatesEverySegment` 测的是死代码而非生产路径；② AC-1 判据 1 要求的「遍历白名单、差集为空」未实现：`everyEnumValueIsRegistered` 用手抄 expected 表镜像 `ROWS`，遗漏登记时仍会绿（`AnalyticsDatasets` 只有 dim 字段键、无取值白名单，需另建来源）。
- **无坏结构**：无第二套取数、无适配器漂移（两处 `new CrossTab` 均已补并由运行期断言证），跨模块成员（`UNKNOWN_LABEL`/`valueLabel`/`rowLabel`/`colCodes?`）逐个存在，`vue-tsc` 0 错 + 259 单测为编译期反证。

## 3. 返工项（有条件通过的理由）

| 编号 | 级别 | 内容 |
|---|---|---|
| R1 | P2 | 消除拼接双实现，二选一：(a) `crossTab` 改调 `AnalyticsLabels.joinDisplay`；(b) 删除 `joinDisplay`，把该单测改为直接断言 `crossTab` 产出的 `rowHeaders`/`colHeaders` |
| R2 | P2 | AC-1 判据 1 落地：从取值来源派生白名单（`AnalyticsDatasets` 增每字段 CODES，或解析 `AnalyticsSql` 常量），断言与 `ROWS` 键集合相等，替换手抄镜像 |
| R3 | P3 | D3：`dashboard-chart.png` 的 spec 加 `clipTo: '[data-block="breakdown"]'` 后重拍（11 图 11 唯一 md5）；`evidence/README.md` §3b 已过时（SideNav 现输出「平台管理员」，截图可证）；建议收敛「未知状态」与枚举正则的多副本（Java `^[A-Z][A-Z0-9_]{1,}$` 与 TS `^[A-Z][A-Z0-9_]*$` 最短长度不一致）；V11 迁移内 severity 中文是第四份副本，门禁未覆盖，宜注明来源 |
| R4 | 流程 | 返工后把源码 + 门禁脚本 + 证据 + 任务夹一次性提交（AC-12 判据 3），提交前 `git status` 只剩忽略项 |

## 4. 人工补测（环境限制，非缺陷）

图表像素美观、弹层遮挡/间距、动画手感、读屏器朗读——与 QA 报告 §7 同一清单，需人眼/真机；本次已目视 `dashboard-crosstab.png`、`export-250-xlsx-head.png`，表头与徽标渲染正常。

## 5. 结论

产品行为面 10 条 AC 全绿、无 P0–P2 功能缺陷；AC-12 的「截图唯一性 + 一次性提交」未满足，另有 2 项 M3 代码质量返工（重复实现、覆盖性断言不可失败）。返工面很小（两处本地改动 + 重拍一张图 + 一次提交），复验只需重跑 static 门禁、单测、截图 md5 与 `git status`。

验收结论：⚠️ 有条件通过
