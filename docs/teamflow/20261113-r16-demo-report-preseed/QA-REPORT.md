# QA 报告 · r16 演示重置后预置 2 张演示报表

- 任务夹：`docs/teamflow/20261113-r16-demo-report-preseed/`
- 分支：`feat/demo-report-preseed`（未提交，工作区即交付面）
- 变更面：`req-service/.../seed/DemoFixture.java`、`.../seed/DemoResetService.java`、`scripts/platform-demo-reset.sh`、`scripts/tests/analytics/i18n-live.mjs`、新增 `req-service/src/test/java/com/example/req/seed/DemoReportFixtureTest.java`（`git diff --stat`：4 文件 +257/−20，另 1 新单测 + 任务夹）
- 结论：**可验收**。AC-1~AC-12 全部有执行证据，未发现交付缺陷；3 条范围外观察与 1 条人工视觉补测见 §6/§7。

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 被测行为 | `make platform-demo-reset` 之后报表中心为 2 张开箱可用的演示报表（取代 r15 的「重置后 reports=0」） |
| 运行栈 | 8080 网关 / 8082 req / 8083 deliver / 5173 web，全部 200；req-service jar 构建时间 11:17 晚于源码 11:12，jar 内 `DemoFixture`/`DemoResetService` 含 `REPORT_NAMES`（运行的是新代码） |
| 数据 | MySQL `devops_platform`；`report_definition` 2 行（DEMO 终态 2 报表 + 12 条演示需求），账号 admin/`Admin@12345`、po1|vendor1/`Passw0rd!` |
| 环境限制 | 沙箱内无法启动真实浏览器（crashpad 进程权限），属宿主沙箱限制，非交付缺陷；本轮所有判定均由脚本/HTTP/DB/静态检查取得，纯视觉项见 §7 |
| 工具限制（非缺陷） | 本机 MySQL 9.6 已无 `MD5()` 函数，QA 侧快照改用 `md5 -q` 在 shell 计算 |

## 2. 用例与结果（全部 PASS，0 fail / 0 blocked）

| # | 验收项 | 检查命令 | 结果 |
|---|---|---|---|
| 1 | AC-1/AC-3/AC-5/AC-7/AC-10/AC-12 | `bash scripts/platform-demo-reset.sh` | exit 0，**52/52**：DB 2 行且名称命中 2/2；API total=2、名称集合=厂商交付概览,需求状态分布；A run 5 行/14 列/需求数 12.0；B run 3 行（未设置/甲方科技/丙方信息）预估人天 62.0；两表 export 200+`PK`+「内部资料（水印）」（4547B/4191B）；门户 4 张模板卡、无 `report:` 前缀；探针模式 0 命中；po1=8 / vendor1=3 / vendor2=0 不回归 |
| 2 | AC-1/AC-3/AC-5 | `bash logs/teamflow/tf-mwed9rbz-vnqp59/scripts/r16-t4-report-preseed-check.sh` | exit 0，**5/5**：名称集合、数据集/图表/属主/可见域/状态 2/2、A run 5×14、B run 3×3、export 表头 14/14 与页面逐字符串相等 |
| 3 | AC-6 幂等 | `bash logs/teamflow/tf-mwed9rbz-vnqp59/scripts/r16-qa-idempotency.sh` | exit 0，**6/6**：两次 reset 均 exit 0；活动行数 2/2；`definition_json` 规范化 md5 `9bea00e2f9bd…` 两次相等；id `401,400 → 405,404` 可变（断言只按名称定位）；API total=2 |
| 4 | AC-9 不越权 + AC-8 运行态 | `bash logs/teamflow/tf-mwed9rbz-vnqp59/scripts/r16-qa-ac9-scope.sh` | exit 0，**13/13**：admin 列表恰 2 张（按名称取 id，不写死）；po1/vendor1 列表为空；两角色对两表 run/export 均 **404**，响应体不含 `DEMO-R-`/甲方科技/丙方信息/rowHeaders，导出非 `PK`；`/internal/seed` 后报表集合不变 |
| 5 | AC-2 白名单 | `grep -n 'domain\|vendor\|status\|cycle.avg\|effort.estimate\|effort.deviation' deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsDatasets.java` | `:177-187` requirement 数据集注册 `domain/vendor/status`、`req.total/cycle.avg/effort.estimate/effort.deviation`，两表字段全在白名单（run 走的是同一注册表，字段错会 400） |
| 6 | AC-4 i18n | `bash scripts/analytics-i18n-verify.sh` | exit 0，**74/74**（static 33 + live 41）；预置报表 id 382/383 被门禁自动纳入并在结束后软删夹具 |
| 7 | AC-11 门禁 | `bash scripts/platform-unit.sh` | exit 0，**268 tests / 0 fail**（含新 `DemoReportFixtureTest` 6 例） |
| 8 | AC-11 | `bash scripts/platform-verify.sh` | exit 0，**167/167** |
| 9 | AC-11 | `bash scripts/access-verify.sh` | exit 0，**145/145**（a0 29/a1 8/a2 63/a3 9/a4 14/a5 22） |
| 10 | AC-11 | `bash scripts/platform-test-negative.sh` | exit 0，**25/25** |
| 11 | AC-11 | `bash scripts/tests/lint.sh`（`make lint`） | exit 0，**18/18** |
| 12 | AC-11 | `bash scripts/verify.sh`（`make verify`） | exit 0，**14/14**（端口监听 + HTTP 语义） |
| 13 | AC-11 | `bash scripts/platform-seed.sh`（`make platform-seed`） | exit 0，**10/10**（两遍 seed 计数一致） |
| 14 | AC-8 静态 | `grep -rn 'report_definition' req-service/src/main/java --include=*.java` | 只有 `DemoResetService` 写该表（`purge` + 回填），`SeedService` 无报表分支 → 正常启动/seed 不插入 |
| 15 | 终态复核 | 重置后重跑 #2 | 5/5，演示终态 = 2 报表 + 12 需求（写报告前已复位，无探针残留） |

## 3. 架构与接口一致性（P1 门，全部通过）

- **蓝图符合**：定义与插入行单点收敛在 `DemoFixture`（`record ReportFixture` + `definitionJson()` 固定键序 `datasetId,rows,columns,measures,filters` + `REPORTS`/`REPORT_NAMES`/`REPORT_COUNT`/`reportRows`），编排在 `DemoResetService`（`references()` 含 `user:admin`、`insertFixture()` 第 9 步回填、`violations()` 数量+TreeSet 名称集合断言、`reportNames()` 无 `ORDER BY`），脚本只做搬运/复核，单测只锁常量——与注入蓝图 4 个文件一一对应，**未新增表/迁移/接口/前端改动**（`git status` 无 migration、无 web 变更）。
- **无重复实现**：定义 JSON 仅在 `DemoFixture.definitionJson()` 构造一次（`DemoFixture.java:226-232`）；插入处直接取 `report.definitionJson()`（`:275`）；脚本不复制字段，只持名称集合且用 `jq sort` 规范化比较（中文不依赖 collation）。`grep` 全仓无第二处拼 `datasetId` 的代码。
- **调用方/被调方签名一致**：`DemoResetService.java:444` 调 `DemoFixture.reportRows(tenantId, refs)`，`:491` 用 `DemoFixture.REPORT_COUNT`，`:493/:496` 用 `REPORT_NAMES`；单测 `DemoReportFixtureTest.java:156` 调同一 `reportRows` 并按 13 列断言。JSON 键与 `deliver-service/.../reports/ReportViews.java:30-31` 的 `ReportDefinitionDto(datasetId,rows,columns,measures,filters)` 一致，`ReportDefinitionCodec.read` 可反序列化（run/export 已实测 200）。
- **一处需要知晓的偏差（非缺陷）**：`DemoResetService` 的需求 INSERT 增写 `assignee_vendor_id`（与 `owner_vendor_id` 同值），不在 T2 原始 spec 内，但这是 AC-3「厂商行 ≥2」成立的前提（`AnalyticsSql.java:172` 的 vendor 维只读该列），写法与产品侧 `RequirementService.java:392-393` 一致，且隔离口径（`ScopeSql:54` owner∨assignee，两列同值）与 po1=8/vendor1=3 实测未变。建议收官时在代码注释或 TECHNICAL 中留一句，避免后人误删。
- **未发现**：该抽未抽的重复实现、旁路写报表的入口、结构破坏。

## 4. 提交面卫生

- `git status --porcelain`：仅 4 个预期修改 + 2 个预期新增（任务夹、单测），**无** `target/`、`node_modules/`、`.pnpm-store/`、`__pycache__/`、`dist/`、`.idea/`、`.env` 等噪声。
- `.gitignore` 已覆盖 `logs/teamflow/`、`logs/`、`target/`、`node_modules/`、`dist/`、`.env*`（除 `.env.example`）、`.idea/`；本次新增文件均落在受控路径内。

## 5. 缺陷表

未发现缺陷（P0/P1/P2/P3 皆无交付缺陷）。

## 6. 范围外观察（不阻断验收，供后续迭代决策）

| 编号 | 级别 | 观察 | 证据 | 影响面 |
|---|---|---|---|---|
| O-1 | P3 | `reqType` 只进缓存键、不进 SQL：同一报表 `reqType=RD/PDR/BUS` 的 run 结果完全相同 | `for t in RD PDR BUS; do …/run?preset=30d&reqType=$t; done` → 三次均 200、rows=5、total0=12.0；`AnalyticsRequest.java:103` 仅把 reqType 拼进 key | r14 遗留（T5 已作为 OUT-OF-SCOPE 记录）。演示数据仅 RD，故 AC-3/AC-5 口径一致；若将来演示集含 PDR/BUS，页面与导出可能出现列数差 |
| O-2 | P3 | `effort.deviation` 整列为 0 | 演示数据 `actual_days = estimate_days` | AC-3 只要求「非空非全 0 单元格」，当前 B 表 cells 非全空；演示说服力弱，改数据会动 r15 已确认的 12 行口径，本轮不动 |
| O-3 | 提示 | 本机 MySQL 9.6 无 `MD5()`/`GROUP_CONCAT` 哈希函数，QA 快照脚本需在 shell 侧算哈希 | `ERROR 1305 (42000): FUNCTION devops_platform.MD5 does not exist` | 仅影响 QA 工具写法，与产品无关 |

## 7. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 工具 | 判据 |
|---|---|---|---|
| 两张演示报表卡片的视觉工艺（卡片留白、名称换行、悬停/聚焦反馈、空态与加载态） | 真实浏览器打开报表中心，逐项截图对照 DESIGN/PRD 的视觉预期 | Chrome/Edge + 人工目检 | 2 张卡片完整可见、名称不截断、交互有反馈；无 DESIGN.md，按平台既有卡片样式判断 |
| 交叉表/图表在真实视口的渲染（宽表横向滚动、图表切换） | 打开 A 表切「图表」视图并截图 | 同上 | 5×14 交叉表可横向滚动，图表正常出图 |
| 导出文件在 Excel/WPS 中的观感（水印位置、列宽） | 打开导出的 xlsx | Excel/WPS | 水印可见、表头与页面一致 |

## 8. 结论

- **可验收**：AC-1~AC-12 全绿，门禁 268/167/145/25/18/74/14/10 全通过，重置脚本 52/52；幂等、越权、探针残留、门户口径均有独立探针复验。
- 架构与接口一致性通过，无重复实现；提交面无噪声。
- 无阻断项；§6 三条为范围外观察，§7 为人工视觉补测（环境限制）。
- 证据脚本：`logs/teamflow/tf-mwed9rbz-vnqp59/scripts/r16-qa-ac9-scope.sh`、`r16-qa-idempotency.sh`、`r16-t4-report-preseed-check.sh`。
