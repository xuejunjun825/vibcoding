# r14 中文表头交付证据（T6 自检）

本目录是 20261112-r14-analytics-header-zh 的**交付自检证据**：11 张真实栈截图 + 现场接口比对。
不写 QA 报告、不做跨浏览器矩阵（那是 QA/验收阶段的事）。

## 1. 截图（1440 宽，整页，`deviceScaleFactor=1`）

| 文件 | 页面 | 证明了哪条 AC |
|---|---|---|
| `dashboard-crosstab.png` | `/dashboard` 交叉表 | AC-2：10 列全是「中文维度 · 中文度量」，列序与 r13 一致；AC-4 缓存徽标 |
| `dashboard-trend.png` | `/dashboard` 周期与 P90 卡片（只截该卡片，避免与整页图重复） | AC-4：周期分档/KPI 卡片文案全中文（24 条文本 0 命中） |
| `dashboard-chart.png` | `/dashboard` 厂商分布图（canvas） | AC-4：图渲染就位；图例/轴文字的 0 命中断言由 `i18n-live.mjs` 的 canvas 录制承接 |
| `portal-admin.png` | `/portal`（admin） | AC-5：五区 + 卡片「标题 · 口径」，141 条文本 0 命中 |
| `portal-pm.png` | `/portal`（pm 模板） | AC-5：换角色后口径文案同样中文（37 条） |
| `reports-list.png` | `/reports` 列表 | AC-6：列表表头/范围标签/分页器 0 命中 |
| `editor-250-preview.png` | `/reports/250` 预览 | AC-2：20 列「中文状态 · 中文度量」+ 角格「分组」 |
| `editor-250-transpose.png` | `/reports/250` 转置 | AC-4：原列头搬到行首，行头仍全中文（`tbody th` 也扫） |
| `editor-250-bar.png` | `/reports/250` 柱状图 | AC-4：图表视图不另起一套标签（报表 261 的图表按钮对 3 行维禁用，故选 250） |
| `export-250-xlsx-head.png` | 报表 250 XLSX 首行 | AC-8：21 个表头单元格全中文，首格「分组」；图上字符即文件字符 |
| `export-250-csv-head.png` | 报表 250 CSV 表头 | AC-8/AC-9：CSV 第二行 21 格与页面逐字符串相等 |

截图里的英文枚举扫描用与门禁同一条正则 `\b[A-Z]{3,}(_[A-Z]+)*\b`；命中即失败，图不会落盘。

## 2. 现场接口比对（AC-9 / AC-3）

`GET /api/deliver/reports/250/run` 与导出的 CSV 表头逐列比对：**20/20 列逐字符串相等**。
同一次响应里 `colCodes` 20 个（首列 `DELIVERED`）、`rowCodes` 6 个——机器可读的 code 还在，
只是不再出现在人读的位置。报表 228（无列维）返回 `colCodes: []`，与 PRD AC-3 的特例一致。

## 3. 顺手修掉的两处交付缺陷（都在本轮范围内）

1. `web/src/views/analytics/DashboardView.vue` 两个 `an-hint` 把内部枚举漏到界面上：
   「本厂商（mine）行高亮」「level=NONE 表示该指标无样本」——被 `i18n-live.mjs` 的 A1 抓到
   （`/dashboard .dash` 命中 `NONE`）。改为纯中文说明后 A1 转绿。
2. 门禁基线漂移（**不是 r14 引入**）：`scripts/platform-verify.sh:344` 与
   `scripts/tests/collab/s0-schema.sh:102` 仍断言「功能码 56 个」，而 `PermissionMatrix` 自 r9/M9
   起已是 **60** 个（r13 的 portal:view/portal:config/report:view/report:share 已入库，
   `PermissionMatrixGridTest` 亦按 10×60 断言）。两处期望值同步为 60 并在注释里写明构成，
   否则 `make test` 会一直红在一个与 r14 无关的陈旧数字上。

## 3b. 截图里刻意保留的、不属于 r14 范围的一处英文

侧边栏用户卡第二行显示 `PLATFORM_ADMIN`（`web/src/layouts/SideNav.vue:101`
`auth.user?.roles?.join(' / ')` 直出角色码）。这是全站身份区，不是分析域维度取值，
r14 的 AC 也不覆盖它；已在交付摘要的 [OUT-OF-SCOPE] 里登记，改它需要动共享布局 + 角色词典，
属于另一个需求。

## 4. 怎么重放

```bash
make platform-up                       # 起栈（web 走 vite preview，服务 web/dist）
node logs/teamflow/<runId>/scripts/r14-evidence-shots.mjs   # 重拍 11 张图 + AC-9/AC-3 比对
make analytics-i18n-verify             # R1–R7 三方一致 + A1–A9 现场渲染/导出
```

`r14-evidence-shots.mjs` 是无依赖 CDP 探针（`SOFT=1` 只报警不落盘，用于选择器自检；
`--only=<前缀>` 单拍一张）。导出证据先把真实字节存到 run 目录再渲染成 HTML 截图，
所以图上的表头就是文件里的表头，不是页面复制品。
