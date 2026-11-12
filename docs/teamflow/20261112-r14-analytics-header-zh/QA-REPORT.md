# r14 QA 复验报告（round-3）· 分析域表头中文化

- **结论：可验收**。上一轮唯一阻断项 D6（门禁不可重复）已用原检测命令复证关闭；连续两次 `platform-verify` 均 168/168，`make test` exit 0。D5 已关闭，D3（证据截图重复）仍未关闭但为 **P3 观察**，不阻断验收。
- 分支 `feat/analytics-header-zh`，HEAD `11224f1`；交付改动 31 项未提交（源码 + 任务夹），按流水线在验收阶段一次性提交。
- 复验轮次：round-3；执行人：QA（独立探针，不采信 dev 自检结论）；所有数字为本轮实跑输出逐字照录。

## 1. 范围与环境

- 范围：PRD AC-1..AC-12 复验 + 上轮 D1..D6 逐条回归 + **上轮未覆盖的「缓存降级路径」面**（新增探针）。
- 环境：macOS；JDK 25.0.2；Node 22；MySQL `127.0.0.1:3306/devops_platform`；`make platform-up` 拉起 gateway 8080 / req 8082 / deliver 8083 / web 5173；Playwright 全局 headless（未新增依赖）。
- 探针临时对象（临时报表、探针需求、租户）均在 `trap`/`finally` 中清理，复核残留为 0。

## 2. 复验执行结果（探针全量重跑）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 三方一致性 R1–R7 | `node scripts/tests/analytics/i18n-labels-consistency.mjs` | exit 0，`COUNT static 33 33` |
| 字典/种子对齐 | `node logs/teamflow/tf-mwdbg4h2-g9mxdh/scripts/t2-dict-seed-align.mjs` | exit 0，`TOTAL 40，PASS 40，FAIL 0` |
| 度量口径无英文 token | `bash logs/teamflow/tf-mwdbg4h2-g9mxdh/scripts/caliber-i18n-check.sh` | exit 0，29 条口径 0 命中 |
| 新门禁全量 | `make analytics-i18n-verify` | exit 0，`74/74`（static 33 + live 41） |
| D1 导出首列=页面行头 | `node logs/.../qa-r14-export-rowlabel.mjs` | exit 0，`COUNT export-rowlabel 3/3` |
| D4 质量域 severity | `bash logs/.../qa-r14-quality-severity.sh` | exit 0，`COUNT quality-severity 2/2`（P1/P2/无缺陷） |
| AC-10 未登记兜底 | `bash logs/.../qa-r14-ac10-unknown.sh` | exit 0，`4/4`；`rowHeaders[8]=未知状态`、`rowCodes[8]=QA_UNKNOWN_CODE` |
| 范围外漂移回归 | `node logs/.../r14-out-of-scope-check.mjs` | exit 0，`8/8` |
| **缓存降级路径（本轮新增）** | `bash logs/.../qa-r14-cache-codes.sh` | exit 0，`COUNT cache-codes 6/6`；first=MISS second=HIT，命中后 colCodes/rowCodes 与首次逐元素相等 |
| D6 门禁可重复 | `bash logs/.../qa-r14-density-gate.sh` | exit 0，`VERDICT 门禁已可重复` |
| 平台体检 第 1 次 | `bash scripts/platform-verify.sh` | exit 0，`168/168 项通过（AC-1..AC-20）` |
| 平台体检 第 2 次（连续） | `bash scripts/platform-verify.sh` | exit 0，`168/168 项通过`；跑完后「验证缺陷-*」残留 = 0 |
| 准入 | `bash scripts/access-verify.sh` | exit 0，`145/145`（a0 29/a1 8/a2 63/a3 9/a4 14/a5 22） |
| 单测 | `bash scripts/platform-unit.sh` | exit 0，`259 个单元测试全部通过` |
| 负向 | `bash scripts/platform-test-negative.sh` | exit 0，`25/25` |
| lint | `bash scripts/tests/lint.sh` | exit 0，`18/18`（新增 L16 体检可重复） |
| 类型 | `cd web && npx vue-tsc --noEmit` | exit 0，0 错误 |
| 全量门禁 | `make test` | exit 0，`#SUMMARY collab pass=263 total=263 fail=0` |
| 证据重拍 | `node logs/.../r14-evidence-shots.mjs` | exit 0，11 张；`AC-9 页面↔导出逐列 20/20`、`colCodes 20 / rowCodes 6` |
| XLSX 真实字节 | `python3 logs/.../xlsx-first-rows.py logs/.../exports/report-250.xlsx 3` | 表头 21 格全中文（首格「分组」），首行「物流管理」 |
| D3 截图唯一性 | `cd docs/teamflow/20261112-r14-analytics-header-zh/evidence && md5 -q *.png \| sort -u \| wc -l` | **10**（期望 11）→ 仍开放 |

## 3. 逐缺陷复验（本轮判定，勿与 §6 混读）

| 编号 | round-2 级 | 本轮判定 | 复验证据（原检测命令 → 输出） |
|---|---|---|---|
| D1 | P1 | **已关闭** | `qa-r14-export-rowlabel.mjs` → 3/3；页面 10 个中文行头与 CSV 第 1 列逐字符串相等，XLSX 0 个英文枚举码 |
| D2 | P2 | **已关闭** | 门禁 74/74 含 A6 行头逐列断言；`grep -c rowHeaders scripts/tests/analytics/i18n-live.mjs` 仍为 3 |
| D4 | P2 | **已关闭** | `qa-r14-quality-severity.sh` → 2/2，`rowHeaders=["严重","一般","无缺陷"]`、`rowCodes=["P1","P2","无缺陷"]`，无「未知状态」 |
| D5 | P3 | **已关闭** | `PRD.md:98` 已改为「断言数不少于 143」；实跑 `access-verify` 145/145 |
| D6 | P2 | **已关闭** | `qa-r14-density-gate.sh` → exit 0「VERDICT 门禁已可重复」（容差 0.0055 接受 0.12/0.13、清理调用点 2）；连续两次 `platform-verify` 168/168；跑后残留 `验证缺陷-*` = 0；lint L16 固化两条不变量 |
| D3 | P3 | **仍开放（换了一对图）** | 按 `evidence/README.md` §4 重放 `r14-evidence-shots.mjs` 后：11 张 PNG → **10** 个唯一 md5；本轮重复对为 `dashboard-chart.png` 与 `dashboard-crosstab.png`（md5 同为 `e38c62d4…`），而 dev 自检声称 11 唯一 → **自检结论与本轮实跑不一致** |

## 4. 本轮新增覆盖：缓存降级路径（上轮为何漏）

蓝图为 T1 明写「`CrossTab` 有两处构造（主路径、`withCache` 缓存降级），只改一处会让命中缓存的响应缺 `colCodes/rowCodes`」。round-1/2 的全部探针（i18n-live、export-rowlabel、ac10）都只跑**冷启动首次取数**，`AnalyticsQueryService.java:309` 的 `cache.get(...) != null → withCache(cached, HIT)` 分支一次没被执行过——这是「改一处、另一处没改」最可能漏过的地方。

新增 `qa-r14-cache-codes.sh`：TTL 内连跑同一报表两次，第二次强制走缓存命中，断言 (1) `cache=HIT` 确实命中；(2) 命中响应 `colCodes` 长度 = `colHeaders` 长度、内容与首次逐元素相等；(3) `rowCodes` 长度 = `rows` 长度；(4) 命中后表头仍无英文枚举码。实测 `first=MISS second=HIT`、`colHeaders=3/colCodes=3`、`rows=10/rowCodes=10`、6/6 通过 → `withCache` 已正确透传两个新字段，蓝图告警点已闭合。

## 5. 架构与接口一致性（静态 + 编译期反证）

- **蓝图落实**：唯一注册表 `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsLabels.java`；`AnalyticsViews.CrossTab` 末尾追加 `colCodes/rowCodes` 且保留 9 参兼容构造；`AnalyticsQueryService.java` 中 raw 与 display 双轨采集，列序按 raw 排序；两处 `new CrossTab`（主路径、`withCache`）都补齐（§4 已用运行期断言证明）。
- **跨模块成员逐个存在**：`web/src/components/analytics/labels.ts` 的 `UNKNOWN_LABEL`/`valueLabel` 被 `ReportEditorView.vue` 消费；`CrossTab.rowLabel(int, CrossRow)` 被 `WatermarkCsv.java`、`WatermarkSheet.java`（含小计/合计）调用，签名一致；`web/src/api/analytics.ts` 的 `colCodes?/rowCodes?` 与记录字段同名同型。`vue-tsc` 0 错误 + 259 单测通过为编译期反证。
- **无重复实现**：后端仅 `AnalyticsLabels` 一份 code→中文；前端仅 `dict.ts` 一份词表，`labels.ts` 只声明「字段→kind」绑定；导出复用同一 `CrossTab`，无第二套取数。
- **无坏结构**：`AnalyticsLabels` 不静态引用 `AnalyticsDatasets`（避免类初始化环）；门禁 `scripts/tests/analytics/*` 与 `make` 目标为薄封装。
- **本轮无架构级新增缺陷。**

## 6. 缺陷表（仅列本轮结束时仍开放项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D3 | P3 | 交付证据（`docs/teamflow/20261112-r14-analytics-header-zh/evidence/`） | 按 `evidence/README.md` §4 重放 `node logs/teamflow/tf-mwdbg4h2-g9mxdh/scripts/r14-evidence-shots.mjs`，再对 PNG 取 md5 去重 | 每张截图对应不同视图，11 张 11 个唯一 md5 | 11 张只有 10 个唯一 md5：`dashboard-chart.png` 与 `dashboard-crosstab.png` 字节相同（两条 spec 都未给 `clipTo`，`head:` 只用于文本断言、不裁剪，两图都是 `/dashboard` 首屏整页） | AC-12 判据 2 | `cd docs/teamflow/20261112-r14-analytics-header-zh/evidence && md5 -q *.png \| sort -u \| wc -l` | 输出 11（当前 10） |

> 修复建议（不阻断验收）：给 `dashboard-chart.png` 的 spec 加 `clipTo: '[data-block="breakdown"]'`（与 `dashboard-trend` 同一手法），使两张 dashboard 图各自裁剪到自己的卡片。

## 7. 人工补测清单（环境限制，非交付缺陷）

| 项目 | 期望 | 方法 / 工具 |
|---|---|---|
| ECharts 图例/坐标轴的中文渲染 | 图上文字全中文、无 `DELIVERED` 等枚举 | `i18n-live.mjs` 已用 `fillText` 钩子做 0 命中断言；像素级美观需人眼 → 打开 `evidence/dashboard-chart.png`、`editor-250-bar.png` 目视 |
| 弹层遮挡 / 布局美观（pin 409、分享面板） | 无遮挡、间距一致 | Playwright 有 `qa-r13-share-pin` 历史探针；视觉判定需人眼 → 浏览器打开 `/dashboard` 手动触发 |
| 动画与过渡手感 | 无跳变 | 仅人眼，环境无法量化 |
| 读屏器朗读（图表 aria） | 中文图表类型 | `r14-out-of-scope-check.mjs` 已断言 aria 文案「折线图：1 个分类，1 个系列，单位 个」；真实读屏需人工 |

## 8. 结论

- **可验收**：AC-1..AC-12 的功能面全部实测通过，三条阻断类缺陷（D1/D2/D4）与上轮唯一 P2（D6）均已用原命令复证关闭，`make test` 可重复全绿（连续两次 platform-verify 168/168、跑后数据残留 0）。
- 唯一开放项 D3 为 P3 证据一致性问题（截图重复），不影响产品行为与门禁，建议随下次改动顺手修。
- 建议把本轮新增的 `qa-r14-cache-codes.sh` 纳入该需求回归契约，后续轮次继续复跑。
