# r11 报表/门户后端 · 验收报告

- 任务夹：`docs/teamflow/20261110-r11-reports-portal-backend/`（PRD 13 条 AC）
- 分支 / HEAD：`feat/workbench-reports` · `6cff7dc` + 本轮未提交工作树
- 验收时间：2026-11-10 19:55–20:00（Asia/Shanghai）
- 环境：网关 8080 → deliver 8083 / req 8082 / web 5173 / Nacos 8848 / MySQL 已起；deliver jar 晚于全部源码
- 依据：PRD §2.2/§2.3、QA-REPORT、本轮独立复跑（不只采信 QA 结论）

## 1. 验收动作

1. 逐条比对 PRD 的 AC-1～AC-13 与 §2.3 九条完成判定。
2. 独立复跑九步证据脚本 `bash logs/teamflow/tf-mwael1p2-le57sx/scripts/r11-evidence.sh`：**232 PASS / 4 FAIL / 1 SKIP，exit 1**；4 条失败全部在 STEP 5，根因见 §4-E1（非产品缺陷）。
3. 稳态补偿复验 AC-4：admin 4 张个人卡 `req.total=26.0` 与 `/analytics/metrics` 的 `26.0` 相等；pm 模板卡 10 个 `(key,value)` 与 metrics 全等（0 差异）。
4. 架构核对：蓝图类逐项存在、单点 grep、包依赖方向（见 §3）。
5. 采信 QA 已跑出的门禁数据：lint 16/16、platform-negative 25/25、verify 14/14、quality 348/348、deliver 单测 61/61、matrix 7/7、sweep 33/33、negative 22/22、degradation 19/19、wire 契约 22/22、compile 与 web build rc=0。

## 2. 逐条验收

| AC | 判定 | 证据 |
|---|---|---|
| AC-1 编译与改动边界 | 通过（含 3 处声明例外，见 §4-E2） | 复跑 STEP 1：`mvn compile` rc=0、`npm run build` rc=0；STEP 2：`web/`、`quality/`、V10、`*.sql` 零 diff；既有 `/analytics/metrics`、`/analytics/dashboard` 200（QA 回归） |
| AC-2 数据集白名单 | 通过 | STEP 3：4 项、顺序 `requirement/delivery/approval/quality`；别名 `jq -S` 逐字节相同；`new AnalyticsViews.Dataset(` 4 次全部在 `AnalyticsDatasets.java` 1 个文件；未知字段/跨数据集 400 由 negative 22/22 覆盖 |
| AC-3 岗位工作台五区 | 通过 | STEP 4：admin/product/pmo/sub-business/business/dev/qa/vendor/mgmt 九套模板命中；五区固定序；mgr1 `READONLY` 且 `todoSources=[]`；源失败 `count=null`（degradation 19/19，无 0 冒充） |
| AC-4 三处同源 | 通过（稳态；复跑异常见 E1） | pm 模板卡 10 个 `(key,value)` 与 `/analytics/metrics` 全等；admin 个人卡 26.0=26.0；portal/reports 包 `JdbcTemplate|SELECT` 0 命中；除 `AnalyticsMetrics` 外无新百分比算式（仅调用 `completionRate` 与字符串指标名） |
| AC-5 卡片配置 | 通过 | STEP 9：admin GET 200、`maxCards=4`、roleCode 归一；同 body 两次 PUT 单行、`updated_at` 18:56:08→18:56:10 变化；pm PUT/GET 他人 403 且 `detail.functions` 含 `PORTAL_CONFIG`；未知卡 400 `PORTAL_UNKNOWN_CARD`+`detail.known`；>4 的 400 分支在 DEV 模板不可达（SKIP 说明），同一错误码的 409 由 pin 覆盖 |
| AC-6 pin 与 4 张上限 | 通过 | STEP 9：前 4 张 200、第 5 张 409 `PORTAL_CARD_LIMIT`、`detail.maxCards=4`、`detail.cards` 4 张（`report:<id>`）；重复 pin 200 且 DB 仍 1 行；pm 门户不含 admin 个人卡 |
| AC-7 CRUD/复制/软删 | 通过 | STEP 6：201、默认 PRIVATE/TABLE、`jq -S` 定义往返相等、重名 409、copy 201 PRIVATE、DELETE 204→DELETED→GET 404、同名重建 201 |
| AC-8 私有可见性 | 通过 | STEP 6：dev1 对他人私有报表 GET/PUT/DELETE/copy/run 全 404 `REPORT_NOT_FOUND`；MINE 不含；属主可见。管理员 404 由 QA negative 22/22 覆盖 |
| AC-9 共享五档与闸门 | 通过 | STEP 7：ROLE→role id、TENANT→PUBLIC 落库/回读、DOMAIN、非法 scope/scopeRef 400 `detail.field`、非属主 403 `REPORT_SCOPE_FORBIDDEN`、dev1 403 `detail.functions` 含 `REPORT_SHARE`、pm/po1/mgr1 200、PUBLIC 带 ref 400 |
| AC-10 run 与缓存 | 通过 | STEP 6：MISS→HIT 且响应头与 body 一致、草稿 run 不写库、改 A 定义后 A MISS/B 仍 HIT、换账号首跑 MISS（STEP 8 vendor1） |
| AC-11 厂商隔离 | 通过 | STEP 8：vendor1 2 行、vendor2 1 行、交集为空、合计 5 ≤ pm 26；响应互不含对方厂商；portal/reports 无手写 vendor 过滤 |
| AC-12 导出 | 通过 | STEP 9：xlsx `spreadsheetml.sheet` + `filename*=UTF-8''` + 4062B、水印 ≥2 次、`#EAF1FE`、合并首行、行标签齐全；CSV BOM `efbbbf` + `#` 元信息、行数=页面；10001 行由 `ExportCapTest` 断言 400；vendor1 不越权；审计 `report.export` 3 条；他人私有导出 404 |
| AC-13 降级与错误码 | 通过 | `PortalServiceTest` 注入抛错 provider 仅该区 DEGRADED；degradation 19/19 中文文案不泄漏异常类名；三个错误码字面量除 `AnalyticsErrors` 外 0 定义；body `{code,message,detail}` 可定位 |

## 3. 架构一致性（M3，强制）

| 检查 | 结果 |
|---|---|
| 蓝图模块落地 | analytics/reports/portal/support 共 28 个主类与测试全部存在，无改名、无缺失 |
| 蓝图签名 | `catalog()/codes()/knows()/dataset()/check()`、`crossTab(...maxRows)`、`cardLimit(status,maxCards,cards,cardKeys)`、`unknownCard()/forbidden()/visible()` 形状一致（`AnalyticsErrors.java:151/161/173` 等） |
| 包依赖单向 | `reports` 中 `import com.example.deliver.portal` = 0（`ReportLookup` 窄接口生效） |
| 单点 | 白名单 1 文件；五档可见性只在 `support/ShareVisibility`；错误码只在 `AnalyticsErrors`；portal/reports 无 SQL |
| 导出解耦 | `quality/` 零 diff，`WatermarkSheet/Csv` 独立实现 |
| 判定 | 无蓝图偏离、无重复实现、无该抽未抽 → 不构成 ⚠️ |

## 4. 验收复跑异常与边界说明（P3，均不阻断）

**E1 证据脚本不幂等：复跑 STEP 5 出现 4 条 FAIL（card=24.0 ≠ metrics=26.0）**
上一轮 QA 跑完 `r11-evidence.sh` 后未清理 admin 的 4 张个人卡（`portal_card_config` 行 id=19）与 `r11-ev-*` 测试报表（当前库 186 行，134 行 ACTIVE）；本轮 19:52 新增 2 条需求后，个人卡读到 300s TTL 内的旧 CrossTab 快照 24，而 STEP 5 用另一组 keys 的 metrics 现算 26。稳态复验：admin 个人卡 4 张均为 26.0，pm 模板卡 10/10 相等 → **AC-4 产品行为成立**。缺陷在证据脚本自身（创建报表/pin 后无清理、STEP 5 未规避缓存窗口），且 QA 报告“pin 行已还原、终态干净”的说法与本轮实测不符。下一轮：脚本增加清理与缓存规避（或 STEP 5 仅在干净个人配置下对拍），并修正该说法。

**E2 AC-1 字面边界有 3 处声明例外**
`scripts/tests/lint.sh`（+3 行：`AnalyticsDirectory` 白名单与理由）、`platform-contract/.../PermissionMatrix.java`（+1 行 `SUB_BUSINESS_LEADER` 的 `REPORT_SHARE`，AC-9 要求 po1 共享成功）、`req-service/.../PermissionMatrixGridTest.java`（+15 行守住该格）。三处均为满足 AC-9 与 lint 门禁的必要改动，dev/QA 已声明；不触碰 `web/`、`quality/`、V10、迁移 SQL。严格按 AC-1 字面“scripts/** 零 diff”不成立，属 PRD 表述与权限矩阵现状的冲突，列入下一轮文档对齐。

**D-8（r9 遗留，P3）**：`scripts/access-verify.sh a3` 8/9，侧栏 36 vs 基线 33（r9 新增 workbench/analytics/reports 三入口，functionCode 已具备）。PRD §10 明确允许“基数变化记录到 state 留给下一轮”，本轮前端零改动，不作为本轮缺陷。

## 5. 遗留（下一轮，均为 P3）

1. 清理本地测试数据并让 `r11-evidence.sh` 幂等（含 admin 个人卡复位、STEP 5 缓存规避）；复跑九步应 exit 0。
2. AC-1 三处例外与 D-8 基线（33→36）写入下一轮 PRD/文档对齐（state 已有“文档对齐、基数同步”待办）。
3. QA 报告“终态干净”表述更正（过程记录，不影响交付）。

## 6. 结论

13 条 AC 全部满足（AC-1 含已声明、必要的 3 处边界例外；AC-4 稳态逐位一致），架构与蓝图一致、无重复实现与包环；无 P0/P1/P2 开放缺陷，3 条 P3 属证据脚本与文档对齐范畴，已列入下一轮。本轮交付可验收。

验收结论：✅ 通过
