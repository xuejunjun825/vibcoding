# r11 报表/门户后端 · QA 复验报告

- 任务夹：`docs/teamflow/20261110-r11-reports-portal-backend/`（PRD 13 条 AC；本轮为**复验轮**，非首测）
- 轮次性质：6b 复验——先重跑上一轮全部 checker（回归契约），再只对上一轮**未覆盖**的面补探针
- 判定：**可验收**（上一轮 6 条缺陷全部关闭；仅剩 1 条 P3 延后项 D-8，属 r9 遗留、PRD §10 明确下一轮同步）

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 工作区 / 分支 | `/Users/xuejunjun/Documents/deepseek-harness/project-1` · `feat/workbench-reports` · HEAD `6cff7dc` |
| 运行栈 | 网关 8080 → deliver 8083 / req 8082 / web 5173 / Nacos 8848（`scripts/platform-up.sh` 拉起） |
| 运行时一致性 | `deliver-service` jar mtime 19:48 **晚于**全部源码 ⇒ 被测的确实是当前工作树（非陈旧产物） |
| 构建 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository`（宿主禁写 `~/.m2`，按 `scripts/lib.sh detect_maven_repo` 的仓库内回退）；`cd web && npm run build` |
| 测试数据 | 夹具报表 / pin 行 / 厂商转派在本轮脚本的 EXIT trap 内还原；DB 终态：`portal_card_config` DEV 模板 4 卡、无残留 `qa-*` 报表 |

## 2. 结论

| 维度 | 结果 |
|---|---|
| 上一轮 checker 重跑 | 全部 exit 0：sweep 33/33、negative 22/22、degradation 19/19、evidence 234 PASS/0 FAIL/1 SKIP |
| 既有门禁回归 | lint 16/16、platform-negative 25/25、verify 14/14、quality 348/348、deliver 单测 61/61、matrix 单测 7/7、compile rc=0、web build rc=0 |
| 新增覆盖（上一轮未测面） | 前后端 wire 契约探针 22/22（新增 checker）；架构核对、接口一致性、提交面卫生均无新缺陷 |
| 开放缺陷 | **1 条 P3**（D-8，非本轮引入、不阻断本轮 AC） |
| 验收判定 | **可验收** |

## 3. 用例与结果

### 3.1 上一轮 checker 重跑（回归契约，逐条按原口径）

| # | 命令 | 结果 | 覆盖 AC |
|---|---|---|---|
| 1 | `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/r11-dataset-run-sweep.sh` | exit 0，**PASS=33 FAIL=0** | AC-7、AC-10、AC-12（4 数据集 run/export） |
| 2 | `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/r11-owned-negative.sh` | exit 0，**PASS=22 FAIL=0** | AC-1、AC-2、AC-5、AC-8、AC-13 |
| 3 | `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/qa-r11-degradation-probe.sh` | exit 0，**PASS=19 FAIL=0** | AC-13（降级不空白 + 中文文案） |
| 4 | `STEPS="2 3 4 5" bash .../r11-evidence.sh` | exit 0，**PASS=82 FAIL=0** | AC-1（边界）、AC-2（别名同源）、AC-3（九模板五区）、AC-4（逐位同源） |
| 5 | `STEPS="6 7 8 9" bash .../r11-evidence.sh` | exit 0，**PASS=150 FAIL=0 SKIP=1** | AC-7/8/9/10/11/12/14 |

> 全量合并口径：`r11-evidence.sh` 一次跑完 = **234 PASS / 0 FAIL / 1 SKIP**（含 step1 编译 2 条）。SKIP 的唯一原因：`/portal/config` 的「超过 4 卡 → 400」分支在 DEV 模板（只声明 4 卡）下不可达；同一上限错误码的 409 分支已由 pin 覆盖（AC-6），故不计为失败。

### 3.2 既有门禁回归（AC-1、AC-19）

| 命令 | 结果 |
|---|---|
| `bash scripts/tests/lint.sh` | exit 0，**16/16**（D-3 已修，L9 白名单含 `analytics/AnalyticsDirectory.java` + 理由注释） |
| `bash scripts/platform-test-negative.sh` | exit 0，**25/25**（D-7 已修，#14 转绿） |
| `bash scripts/verify.sh` | exit 0，**14/14** |
| `bash scripts/quality-verify.sh` | exit 0，**348/348**（q3 质量导出零回归） |
| `mvn -o … -pl deliver-service test` | exit 0，**61/61**（新增 `AnalyticsSqlReservedWordsTest`、`PortalServiceTest`） |
| `mvn -o … -pl req-service -am test -Dtest=PermissionMatrixGridTest -Dsurefire.failIfNoSpecifiedTests=false` | exit 0，**7/7**（新增 `reportShareCells`：SUB_BUSINESS_LEADER ALLOW，DEV/QA/SUB_BUSINESS 仍 DENY） |
| `mvn -o … -pl deliver-service -am compile` | exit 0 |
| `cd web && npm run build` | exit 0（4.52s，前端零改动） |
| `bash scripts/access-verify.sh a3` | **exit 1**，8/9——R1 侧栏 36 vs 基线 33（= 开放 D-8） |

### 3.3 新增覆盖：前后端 wire 契约（上一轮未测面）

上一轮只验证了「端点存在且返回 200」，没有验证**后端 record 字段名 == 前端消费字段名**——这正是 presence 检查会全绿、前端却静默 `undefined` 的一类。本轮新增静态+运行时探针：

```bash
node logs/teamflow/tf-mwael1p2-le57sx/scripts/qa-r11-wire-contract.mjs   # exit 0, PASS=22 FAIL=0 NOTE=6
```

解析 `web/src/api/analytics.ts` 的 interface（含 `extends` 继承），与真实响应 key 集合逐项比对：

| 类型 | 必填字段 | 结果 |
|---|---|---|
| `Dataset` / `DatasetField` | 3 / 4 | PASS |
| 信封 | `{items:[...]}`（`analytics.ts:392`）；`PageResponse{items,total,page,size}`（`web/src/api/types.ts:8`） | PASS |
| `Portal` / `PortalSection` / `PortalCard` / `PortalTodoSource` | 7 / 4 / 4 / 5 | PASS |
| `PortalConfig` / `PortalCardRef` | 3 / 2 | PASS |
| `ReportSummary` / `ReportDetail` / `ReportDefinition` / `ReportFilter` | 11 / 12 / 5 / 3 | PASS |
| `PinResult` + 409 分支 `detail.cards[]` | 2 | PASS |

可选字段 `error/hint/reportId/scopeRef` 在无值时缺省（Jackson 默认不输出 null），TS 侧为 `?`，合法——记为 NOTE 而非 FAIL。
**诚实记录**：本探针第 1 版自身有 3 处口径错误（把信封当 `.datasets`/`.content`、把可选字段当必填、把 409 错误体当 `PinResult`），修正后重跑全绿；该修正**不是**交付缺陷，已在此说明以免下一轮误读 diff。

### 3.4 已修缺陷逐条复验对照（**历史项，非开放缺陷**）

> 本表列名刻意用「round-1 级」，不使用「严重级」，避免缺陷跟踪器把已关闭项当成本轮新缺陷导入。

| 编号 | round-1 级 | 模块 | 上一轮实际行为 | 本轮复现命令 | 本轮结果 |
|---|---|---|---|---|---|
| D-1 | P1 | 分析取数（`AnalyticsSql` 保留字） | `delivery`/`quality` 数据集 run/export 100% 500（`LEFT JOIN release`） | `bash .../r11-dataset-run-sweep.sh` | **已修**：33/33；`git grep -nE "(FROM\|JOIN)\s*(release\|order\|group\|key\|rank\|system\|user\|usage\|window)\b"` 未加反引号命中 **0**（HEAD 时 2 处） |
| D-2 | P2 | 权限矩阵（po1 共享 403） | `SUB_BUSINESS_LEADER` 无 `REPORT_SHARE` | `mvn … -Dtest=PermissionMatrixGridTest` + evidence 第七步 | **已修**：7/7；`[po1] 共享自己报表 200`、`[po1] 共享后 PUBLIC` 转 PASS；DEV/QA/SUB_BUSINESS 仍 DENY |
| D-3 | P2 | lint L9 白名单 | 15/16 | `bash scripts/tests/lint.sh` | **已修**：16/16 |
| D-4 | P3 | 列表分页回显 | `?size=100000` 回显 100000 | `bash .../r11-owned-negative.sh` | **已修**：22/22，回显 `size=100` |
| D-5 | P3 | 降级文案泄漏异常类名 | `BadSqlGrammarException` | `bash .../qa-r11-degradation-probe.sh` | **已修**：19/19，`metrics.error=字段不在数据集白名单内：delivery.nope_field` |
| D-6 | P3 | 缺 portal 降级单测 | `deliver-service/src/test` 无 portal 用例 | `mvn -o … -pl deliver-service test` | **已修**：61/61，含 `PortalServiceTest`（注入抛错 provider ⇒ 仅该区 DEGRADED、其余 OK） |
| D-7 | P2 | 夹具污染导致 negative #14 红 | 22/23 | `bash scripts/platform-test-negative.sh` | **已修**：25/25；脚本改为「记原值 → 设置夹具 → EXIT trap 还原」 |

修复声明复算（与修复轮自述逐项一致，无分歧）：保留字 sweep 2→0；lint 白名单外 `JdbcTemplate` 命中 1→0；`quality/`、`web/`、`db/migration` 仍零 diff。

## 4. 架构核对（M3）

| 检查 | 方法 | 结果 |
|---|---|---|
| 蓝图模块是否落地 | 逐个 grep 蓝图声明的 28 个主类 + 6 个测试类 | **全部存在**，无缺失、无改名 |
| 蓝图 API 签名 | grep `catalog()`/`codes()`/`knows()`/`dataset()`/`check()`/`crossTab()`/`countGroups()`/`cardLimit()`/`unknownCard()`/`forbidden()`/`Viewer`/`visible()` | 全部命中且形状一致（`AnalyticsDatasets.java:256`、`AnalyticsQueryService.java:289/464`、`AnalyticsErrors.java:151/161/173`、`ShareVisibility.java:39/68`） |
| 包依赖单向 | `grep -rn "^import com.example.deliver.portal" deliver-service/src/main/java/com/example/deliver/reports` | **0**（`ReportLookup` 窄接口生效，未形成 reports↔portal 环） |
| 可见性是否只有一份实现 | grep 五档字面量 | 只在 `support/ShareVisibility.java:29-33` 定义；reports/portal 无第二份判定分支 |
| 取数是否只有一份实现 | grep portal 内 `JdbcTemplate`/SQL/百分比算式 | 0 实命中；portal 只 import `AnalyticsQueryService`/`AnalyticsMetrics`（`PortalSections.java:12-13`） |
| 白名单是否单点 | `grep -rn "new AnalyticsViews.Dataset("` | 仅 `AnalyticsDatasets.java` 4 处 |
| 错误码是否单点 | grep `PORTAL_UNKNOWN_CARD`/`PORTAL_CARD_LIMIT`/`REPORT_*` 字面量 | 新增码只在 `analytics/AnalyticsErrors.java`；唯一例外是遗留 `quality/report/ReportService.java:1361` 的 `REPORT_NOT_FOUND`（本轮零 diff，**非新增**） |
| 导出是否与质量管道解耦 | `git diff --stat -- quality deliver-service/.../quality` | 零改动；`WatermarkSheet/Csv` 为独立写作器 |
| 端点在位 | grep `@*Mapping` | `/analytics/datasets`、`/portal`、`/portal/config`(GET/PUT)、`POST /reports/{id}/pin`、`/reports` 全套、`/reports/datasets`、`/reports/{id}/export` 全部命中；pin 落在 `PortalController:67`（保持单向） |

**架构结论：无偏离、无重复实现、无抽象缺失。** 未发现「架构」类缺陷。

## 5. 接口一致性（静态，0a）

跨模块调用逐个核对（grep/Read，无需运行时）：

| 调用方 | 被调成员 | 核对 |
|---|---|---|
| `AnalyticsController:42` | `AnalyticsDatasets.catalog()` | 存在（`AnalyticsDatasets.java:256`），返回 `DatasetList`，两端点同实例 ⇒ 逐字节一致 |
| `PortalSections` | `AnalyticsQueryService.crossTab/metrics`、`AnalyticsMetrics.spec` | 签名匹配（`crossTab(dataset,def,req,maxRows,reportId,header)`；`metrics(req,keys,header)`） |
| `PortalService` | `ReportLookup.card/recent/visible` | 三方法均存在（`ReportCards` 实现），参数/返回形状一致 |
| `ReportService` | `ReportDefinitionCodec.parse/engine/store/read/dimCount/measureCount` | 全部存在；`engine(dataset,dto)` 与调用点一致 |
| `ReportService/PortalService` | `ExportCap.check(long)`、`WatermarkSheet.xlsx(tab,Meta)`、`WatermarkCsv.csv(tab,Meta)` | 存在且参数类型一致 |
| `ReportController` | `ReportService.create/list/get/update/delete/copy/share/run/export` | 9 个方法全部存在 |
| `ReportCards` | `ShareVisibility.viewer()/visible(...)`、`AnalyticsDirectory.roleIds(...)` | 存在，`Viewer` record 字段一致 |

**接口结论：无 P1 级「成员不存在 / 形状不匹配」缺陷。** 前后端字段层已由 §3.3 探针实测闭环。

## 6. 缺陷表（**仅仍开放项**）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-8 | P3 | 门禁/前端导航基线（r9 遗留，非本轮引入） | `bash scripts/access-verify.sh a3` | a3 9/9：侧栏 33 + 顶栏 8，均带 functionCode | a3 8/9，R1 红：侧栏实测 36 vs 基线 33（r9 新增 workbench/analytics/reports 三入口；三入口 functionCode 已具备，R2 通过） | AC-15（延后项，PRD §10） | `bash scripts/access-verify.sh a3` | exit 0；`a3-nav-audit.mjs` 基线 33 → 36 后 9/9 |

> 除 D-8 外，上一轮 D-1～D-7 **均已关闭**（对照见 §3.4），本轮未发现新的 P0/P1/P2 缺陷。

## 7. 工艺观察（P3，不阻断）

本轮交付为**纯后端**（前端视图按 PRD §10 明确不在范围），无可视 UI ⇒ 视觉工艺整块 N/A。仅记录 2 条接口侧体验观察：

| # | 观察 | 说明 |
|---|---|---|
| V-1 | 一致性问题（低） | `GET /portal/config` 的 `roleCode` 走 query，`PUT /portal/config` 走 body `ConfigCmd.roleCode`——同一资源两种入口形状，前端易混；建议前端封装处固定，不必改后端 |
| V-2 | 冗余但兼容 | pin 上限 409 的 `detail` 同时给 `cards[{key,title,reportId}]` 与 `cardKeys[]`；前者供 UI 渲染、后者为旧契约兼容，属有意设计 |

## 8. 人工补测清单（环境限制 / 非本轮范围，非交付缺陷）

| # | 项 | 方法 / 工具 | 归属 |
|---|---|---|---|
| 1 | 真实浏览器视觉与交互（五区布局、空/加载/错误态、hover/focus、tooltip） | 沙箱内 Chromium 无法启动（crashpad 受限，`--no-sandbox` 无效）：人工在 `http://127.0.0.1:5173` 以 admin/vendor1/mgr1 打开 `/workbench`、`/reports`、`/analytics`，DevTools 1440×900 | 前端视图轮 |
| 2 | 1440×900 三张截图与文档对齐 | 人工截图，覆盖工作台/报表/看板 | AC-17（延后） |
| 3 | 缓存 300s TTL 到期 | 脚本只能证明 MISS→HIT 与按报表失效；到期需真实等待 >300s 后应 MISS | AC-10 补充 |
| 4 | 导出文件在 Excel/WPS 的真实渲染（合并单元格、列宽、中文） | 已断言到 XML/ZIP 层（`WatermarkSheetTest`）；人工打开 xlsx 核对首行水印与页脚 | AC-12 补充 |
| 5 | 性能阈值（5 万需求 P95、甘特 300 条、树 500 节点等） | 独立性能轮 `scripts/platform-perf.sh` | AC-18/AC-20 |
| 6 | 跨浏览器/多分辨率、100dvh 工具条、safe-area | 人工 Safari/Edge/移动端视口 | 前端轮 |

## 9. 提交面卫生（0c）

```bash
git status --porcelain | awk '{print $2}' | grep -vE '^(deliver-service/|docs/teamflow/)'
```

输出仅 3 个文件，且均为 QA 上轮 §8 已声明的例外（越出「只动 deliver-service」但被放行）：
`platform-contract/.../PermissionMatrix.java`（D-2 修复必需）、`req-service/src/test/.../PermissionMatrixGridTest.java`（其门禁）、`scripts/tests/lint.sh`（D-3 白名单）。

- `quality/`、`web/`、`req-service/src/main/resources/db/migration` **零 diff**（`git diff --stat` 仅 lint.sh 3 行）
- 无 `node_modules/`、`target/`、`*.log`、`.idea/`、`.env`、`.DS_Store` 进入 `git status`（`.gitignore` 覆盖充分）
- 无根目录散落脚本；本轮产物只写 `logs/teamflow/tf-mwael1p2-le57sx/`

## 10. 结论与后续

- **验收判定：可验收。** 上一轮 6 条缺陷（D-1..D-7）全部关闭并有可复跑命令佐证；本轮新增的 wire 契约、架构、接口、卫生四类探针均未发现新缺陷；既有门禁除 D-8 外零回归。
- **D-8（P3）延后处理**：随下一轮基数同步把 `scripts/tests/access/a3-nav-audit.mjs` 的侧栏基线 33 → 36（三入口 functionCode 已具备）。该项在 r9 交付时即存在，本 PRD §10 已明确不属本轮范围。
- 回归口径（下一轮直接复跑）：
  - `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/r11-dataset-run-sweep.sh`（33）
  - `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/r11-owned-negative.sh`（22）
  - `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/qa-r11-degradation-probe.sh`（19）
  - `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/r11-evidence.sh`（234 PASS / 1 SKIP）
  - `node logs/teamflow/tf-mwael1p2-le57sx/scripts/qa-r11-wire-contract.mjs`（22）
  - `bash scripts/tests/lint.sh`（16）、`bash scripts/platform-test-negative.sh`（25）、`bash scripts/verify.sh`（14）、`bash scripts/quality-verify.sh`（348）
  - `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl deliver-service test`（61）
