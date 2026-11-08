# QA 报告 · 第 3 轮复测（fix round 2 → re-verification）

- 需求：仿嘉为蓝鲸的 DevOps 研发管理平台（M1–M5，20 条 AC）
- 分支：`feat/devops-req-platform`（未提交；本轮只读产品代码，未改任何产品文件）
- 任务夹：`docs/teamflow/20261108-r2-devops-req-platform/`
- 复测日期：2026-11-09
- 复测对象：fix round 2 对 D-10 / D-11 / D-12 的修复（修复自报 verify 158/158、negative 25/25、unit 109/109、lint 15/15、perf 6/6、r1 14/14、seed 10/10）

## 1. 结论

**可验收。** 第 2 轮仍 OPEN 的 3 条缺陷（D-10 P1 / D-11 P2 / D-12 P2）用各自原「检测命令」逐条重跑，全部真实闭合；上两轮 D-01…D-09 的回归检查器无一回退；全量门禁在实栈上全绿；本轮新增的 4 个检测面（评审驳回/重复发起、筛选导出同源、规模夹具三层链、真浏览器 E2E）也全部通过。

- 本轮 OPEN 缺陷：**0**（P0/P1/P2/P3 均无）。
- 主证据：`platform-verify` 158/158（首跑）与 159/159（写完探针后的终跑，总数随库内夹具动态 +1）、`platform-test-negative` 25/25、`platform-unit` 109/109、真浏览器 E2E 14/14 + 20/20 + 真栈冒烟 6/6。

## 2. 范围与环境

| 项 | 值 |
|---|---|
| 栈 | 网关 8080 / demo 8081 / req 8082 / deliver 8083 / web 5173(preview) / Nacos 8848（8849、9848-9849）全 LISTEN；MySQL 3306 |
| 数据库 | MySQL 9.6 `devops_platform`，Flyway V1–V4；需求 100126 行（含 10 万规模夹具与各轮探针自建夹具） |
| 依赖 | JDK 25.0.2、Maven 六模块、Node 22 + pnpm、Nacos 3.3.0、Chromium 145（headless） |
| 浏览器 | **本轮可用**：`chromium.launch()` 实测 LAUNCHED（上两轮记录的 crashpad 限制本轮未复现）→ 浏览器项从「人工补测」改为实跑；前端用 5173（CORS 白名单默认只放 5173，见 `req-service/src/main/java/com/example/req/config/WebConfig.java:21`，端口可用 `PLATFORM_CORS_ORIGINS` 覆盖） |
| 命令纪律 | 每条命令一个检查、≤120s；未把命令输出重定向成 dump 文件；探针与截图只落 `logs/teamflow/tf-mw80llxu-g2efl1/` |

## 3. 复测执行结果

### 3.1 上轮 3 条缺陷：原检测命令逐条重跑

| 编号 | 原检测命令 | 本轮实测 | 判定 |
|---|---|---|---|
| D-10（P1，REQ_REVIEW 无启动入口） | `bash logs/teamflow/tf-mw80llxu-g2efl1/scripts/req-review-approval-probe.sh` | **exit 0，3/3 PASS**：主代码 1 处 `startReview` 接线；待评审 BUS 未发起时 `review_pass` 409 `REVIEW_APPROVAL_REQUIRED` 且实例数 6→6 不变；`wf_instance(REQ_REVIEW)`=6 | 已闭合 |
| D-11（P2，规模夹具破坏树不变量） | `bash logs/teamflow/tf-mw80llxu-g2efl1/scripts/perf-fixture-parent-probe.sh` | **exit 0**（本轮已扩断言）：无 PDR 父的 RD=**0**；无 BUS 父的 PDR=**0**；BUS 带父=**0**；`PDR-BULK-0001` 的父=`BUS-0001(BUS)` → 三层链完整 | 已闭合 |
| D-12（P2，导出 10 万行截断） | `bash logs/teamflow/tf-mw80llxu-g2efl1/scripts/export-rows-probe.sh` | **exit 0**：CSV 数据行 **100115** = `X-Export-Total` 100115，耗时 1s（无上限、keyset 分批） | 已闭合 |

修复轮的连带验证：`bash scripts/platform-seed.sh` 在 `platform-perf` 之后运行仍 **10/10**（两遍 counts 一致、`[09]` BUS=82→PDR=38→RD=100004 全挂 PDR 下、10 账号登录），D-11 的「门禁结果依赖执行顺序」已消除。

### 3.2 全量门禁重跑（回归契约，逐条报数）

| 检查器 | 命令 | 本轮结果 |
|---|---|---|
| 平台总账 | `bash scripts/platform-verify.sh` | **158/158 PASS**（AC-1…AC-20）；探针写库后再跑 **159/159 PASS**（总数随夹具动态变化，无失败项） |
| 平台负向 | `bash scripts/platform-test-negative.sh` | **25/25 PASS** |
| 静态门禁 | `bash scripts/tests/lint.sh` | **15/15 PASS**（L1–L13） |
| 单元测试 | `bash scripts/platform-unit.sh` | **109/109** 全通过 |
| 性能基线 | `bash scripts/platform-perf.sh` | **6/6**：列表 p50 0.201/p95 0.216s；看板 0.075/0.077s；准入 0.016/0.019s；矩阵 0.119/0.121s；上报 0.012/0.022s；1000 行导入 3s |
| 种子幂等 | `bash scripts/platform-seed.sh` | **10/10**（perf 之后运行） |
| r1 基线 | `bash scripts/verify.sh` | **14/14 PASS** |
| r1 负向 | `bash scripts/tests/verify-negative.sh` | 停 demo-service 后 4 项指名失败、10 项仍 PASS；重启 18s 恢复、复测 14/14，总 20s |
| 租户/鉴权/角色 | `bash logs/.../scripts/t1-auth-sys-check.sh` | **11/11**（缺租户头 403、跨租户 403、开租户 201 roles=10/domains=7、26 函数码、审计、内部 health flyway=4） |
| T2 需求/状态机 | `bash logs/.../scripts/t2-verify.sh` | **8/8**（maven module 模式；直写 status 拒、非法流转 409、聚合终态谓词、CSV 同源、LOCKED 409、发布级联、回调） |
| 种子审批链 | `bash logs/.../scripts/check-seeded-approval-flows.sh` | **18/18**（REQ_SUBMIT 94/REQ_REVIEW 1/RELEASE 1/REQ_DEPLOY 1；版本审批 201；发布单提交 409 `ADMISSION_FAILED`；第 5 项阻断未评审链路） |
| T3 审批引擎 | `bash logs/.../scripts/t3-approval-verify.sh` | **29 tests / 0 failed** |
| T4 发布单/准入 | `bash logs/.../scripts/t4-compile-test.sh` | **34/34 tests** |
| T6 用例/门禁 | `bash logs/.../scripts/t6-compile-test.sh` | **10/10 tests** |
| T6 端到端 | `bash logs/.../scripts/t6-e2e.sh` | **67 PASS / 0 FAIL**（scratch DB 应用 V1–V4；计划 DONE / 门禁 PASS / AUTO 执行 2 / `api_call_log` 8） |
| 前端 API 契约 | `python3 logs/.../scripts/fe-api-contract.py` | **PASS**：后端 129 路由 / 前端 101 调用全命中 |
| 种子列名 | `python3 logs/.../scripts/seed-columns-check.py` | **exit 0**，238 条列引用 |
| 前端构建 | `cd web && pnpm build` | **exit 0**，3.67s，element 924.65 kB / vendor 114.20 kB |

### 3.3 本轮新增检测面（上轮未覆盖）

| 面（为何上轮没覆盖） | 命令 | 本轮实测 |
|---|---|---|
| 评审线的「驳回 / 重复发起」语义：上轮只验了「无实例→409」「通过→POOLED」，D-10 修复新增的幂等与驳回回退是新增代码，未被任何检查覆盖 | `bash logs/.../scripts/review-line-reject-probe.sh` | **exit 0，9/9 PASS**：`allowedActions=[review_start,review_pass,review_reject,close]`；首次发起 active 实例=1 且状态仍 PENDING_REVIEW；重复发起仍=1（幂等）；驳回后需求留 PENDING_REVIEW、实例 REJECTED、`review_pass` **409**；再次发起后总实例=2、active=1（旧实例让位） |
| 筛选导出同源性：上轮只验「全量」等式；D-12 把循环改成 keyset 分批，一旦分批丢 WHERE，全量仍绿而筛选导出静默导错行 | `bash logs/.../scripts/export-filter-probe.sh` | **exit 0，5/5 PASS**：全量 total=100126=行数；`type=RD` 100005=行数且<全量；`type=BUS` 83=行数且与 RD 区分 |
| 规模夹具的**三层**链：上轮只查「RD 有 PDR 父」，修复把 RD 挂到合成 PDR 后，合成 PDR 自己是否满足「PDR 父必须 BUS」未被检查 | `bash logs/.../scripts/perf-fixture-parent-probe.sh`（已就地扩断言） | **exit 0**：RD 孤儿 0、PDR 孤儿 0、BUS 带父 0、`PDR-BULK-0001`→`BUS-0001` |
| 真浏览器端到端：上两轮记录浏览器不可启动，全部转人工 | `node logs/.../scripts/e2e-base.mjs http://127.0.0.1:5174`（mock 18080 + preview 5174） | **14/14**：登录内联错误、落点、侧栏身份、token 只进 sessionStorage、通知分页/未读 URL/刷新保持/已读/空态、403 功能码、404、已登录回弹、console 0 |
| 前端业务页（T8） | `node logs/.../scripts/e2e-t8.mjs http://127.0.0.1:5174` | **20/20**：池统计筛选/类型 tab/搜索/聚合平铺/CSV 水印、详情 7 tab、门禁 FAIL 明细、缺陷 4 卡、追溯缺口、409 常驻、提报→详情、审批待办 2→1、版本/发布单 5 项准入、执行记录、console 0 |
| 真栈 × 真浏览器联通（生产构建 + 真网关 + 真 MySQL；mock E2E 无法覆盖） | `node logs/.../scripts/real-stack-ui-smoke.mjs http://127.0.0.1:5173` | **6/6**：admin@t1 登录→`/requirements` 20 行；点开 `RD-100049` 预览；版本计划 20 行；发布单 20 行；console 0 |
| 组件视觉（工艺观察） | `node logs/.../scripts/gallery-shots.mjs http://127.0.0.1:5175`、`gallery-parts.mjs` | **exit 0**：整页 1440×3000、timeline 560×318、matrix 1166×247、metrics 1200×385；人工核对见 §7 |

### 3.4 测试资产维护（非产品缺陷）

- `e2e-base.mjs` 的落点断言写死 `/notifications`，那是 T7 阶段（`/requirements` 尚未注册）的兜底路径；T8/T9 落地后 `landingRoute` 首选 `/requirements`（`web/src/router/landing.ts:10`）。本轮把该断言改为「已注册入口中第一个可用者」，通知相关断言改为显式导航，14/14 通过。
- `mock-api.mjs` 的需求 #1 夹具 `allowedActions` 含 `submit`，与其自身 `NEXT_ACTIONS.POOLED=['schedule','assign','close']` 不一致；桩只服务前端自检、不入交付，未计缺陷，但「提交审批 409 常驻」这条 E2E 依赖它，后续改桩时注意别把 409 场景一起改没。

### 3.5 修复对比（**非 OPEN 清单**，严重级列已改名）

| 编号 | round-2 级 | round-2 现象 | 本轮重跑命令 | 本轮实测 | 判定 |
|---|---|---|---|---|---|
| D-10 | P1 | REQ_REVIEW 无启动入口，`review_pass` 直通 POOLED，实例恒 0 | `review-line-reject-probe.sh` + `req-review-approval-probe.sh` | 启动入口 1 处；未发起 409；幂等；驳回回退；通过后 POOLED | 闭合 |
| D-11 | P2 | 99931 条无父 RD，seed 在 perf 后 9/10 | `perf-fixture-parent-probe.sh` + `platform-seed.sh` | 孤儿 0/0/0；seed 10/10 | 闭合 |
| D-12 | P2 | 导出 100000 行截断、水印写 total=100077 | `export-rows-probe.sh` + `export-filter-probe.sh` | 100115/100115；筛选 100005、83 同源 | 闭合 |

## 4. 修复点静态核查

- 契约：`platform-contract/src/main/java/com/example/platform/contract/StateTables.java:41-46` 的 BUS `PENDING_REVIEW` 含 `review_start→PENDING_REVIEW`、`review_pass→POOLED`、`review_reject→PENDING_REVIEW`、`close→CLOSED`；`allowedActions` 的唯一来源仍是该表。
- 入口：`req-service/src/main/java/com/example/req/requirement/RequirementService.java:274-300` 在常规流转前处理 `review_start`（先经 `stateMachine.target` 校验合法性，非法状态仍 409），调 `starter.startReview(id, source, definitionId)` 并写 `REVIEW_START` 审计；`review_pass` 仅当 BUS 且 `starter.isApproved(id, REQ_REVIEW)`，否则 409 `REVIEW_APPROVAL_REQUIRED`（`RequirementErrors.java:85-90`，detail 指回 `review_start`）。
- 端口：`RequirementApprovalStarter.java:18-23` 定义 `startReview` / `isApproved`；实现 `RequirementApprovalStarterAdapter.java:50-67` 按 `active_flag` 幂等复用实例、否则 `engine.start(BIZ_REQ_REVIEW,…)`；`isApproved` 取最近实例状态、fail-closed。唯一实现者、`RequirementService` 唯一注入点（58/277/294/311 行），无第二套判定。
- 回调：`RequirementReviewCallback.java` 通过 → `review_pass`、驳回 → `review_reject`，状态不符直接忽略（幂等）。
- 准入：`req-service/src/main/java/com/example/req/deploy/AdmissionEvaluator.java:146-180` 沿父子链的 `approvalLine` 找承载审批的一代，提报/评审任一未发起或非 APPROVED 即阻断第 5 项；`check-seeded-approval-flows.sh` 18/18 实证「未评审链路 → 发布单提交 409」。
- 导出：`RequirementController.java:185-213` 写 `X-Export-Total`，`EXPORT_BATCH=1000` keyset 循环、无上限，末行按 `written` 审计；筛选由同一 `PoolFilter` 生成 count 与 batch。
- 夹具：`SeedService.bulkRequirements` 把批量 RD 挂到合成 `PDR-BULK-0001`（父 `BUS-0001`、人天 0），并对旧孤儿行自愈；三层不变量实测完整。
- 前端：`web/src/views/req/platform.ts:62` 注册 `review_start`（权限 `req:review`）、`web/src/stores/dict.ts:100` 中文「发起评审」；`RequirementDetailView.vue:149` 对 409 渲染常驻条而非 toast。

## 5. 架构核查（蓝图一致性 · 重复实现 · 抽象）

| 检查 | 结论 | 证据 |
|---|---|---|
| 六模块与构建顺序 | 符合蓝图 | 根 `pom.xml:24-29` 顺序 contract→common→gateway→demo→req→deliver；`platform-unit`/`platform-up` 整树构建通过 |
| 契约模块内容 | 与蓝图一致 | `platform-contract/.../contract/` 含 `PermissionMatrix` `JwtCodec` `HmacSigner` `StateTables` `MetricFormulas` `GateDecision` `GatePort` `ApprovalCallback` `DataScope`（另 `GateBlocker` `JwtPayload` `ScopeTier` 为必要值对象） |
| JWT 单点 | 无漂移 | 仅 `JwtCodec` 一份；`JwtGatewayFilter.java:47`、`AuthFilter.java:59`、`AuthService.java:42` 复用 |
| 开放 API 签名单点 | 无漂移 | `HmacSHA256` 只出现在 `HmacSigner`；`PasswordService` 用 `PBKDF2WithHmacSHA256` 做口令散列，不是第二套签名 |
| 通知单点 | 无漂移 | 全仓 `INSERT INTO notification` 仅 `platform-common/.../notify/NotificationWriter.java` |
| 指标公式单点 | 无漂移 | 公式只在 `MetricFormulas`；调用点 `DefectBoardQuery.java:60-62`、`TestPlanQuery.java:124-126,153`、`TaskService.java:63`、`MetricQuery.java:84-86,176`、`SprintService.java:138`，查询层只取计数 |
| 门禁单点 | 无漂移 | 唯一实现 `deliver-service/.../gate/TestGateService.java`；req 侧只有 `DeliverGateClient implements GatePort` |
| Scope 谓词单点 | 无漂移 | `ScopedJdbc.java:146` 调 `ScopeSql.forTable`，`ScopeSql` 仅被 `ScopedJdbc` 引用；业务查询不带手写 `domain_id` 条件（grep 仅 seed/link 写入） |
| 抽象是否落地 | 已接线 | D-10 的教训（端口声明了没人实现）本轮已消除：`RequirementApprovalStarter` 有唯一实现、唯一注入点、唯一 HTTP 入口、前端动作与字典齐备 |
| 既有结构 | 未见破坏 | 未改 r1 的 `ApiGatewayApplication`；`PlatformRoutes` 独立配置类；lint 15/15 |

## 6. 接口一致性（静态，不依赖运行时）

- 跨模块调用：整仓 Maven 构建 + 109 个单测通过（`bash scripts/platform-unit.sh`），跨模块 import、方法存在性、参数形状由编译保证。
- 蓝图 api 抽查：`GatePort.evaluate/evaluateAll` 由 `DeliverGateClient` 实现并被 `RequirementService` 消费；`ScopedJdbc.page/list/one/count/stream` 五方法在 platform-common 存在；`StateTables.actions/target/isTerminal` 是 `RequirementStateMachine` 唯一来源；`ApprovalCallback` 四个方法由 `RequirementApprovalCallback` / `RequirementReviewCallback` / `ReleaseApprovalCallback` / `DeployApprovalCallback` 实现，签名一致。
- 新增接口：`RequirementApprovalStarter.startReview(long,String,Long)` 的调用方（`RequirementService:277`）与实现方（Adapter:50）参数逐个对齐；`review_start` 在前端 `ACTION_META`、字典、契约 `StateTables`、后端 action 分派四处字符串一致（`req:review` 权限码在 `PermissionMatrix.java:47` 存在）。
- 前后端：`fe-api-contract.py` 后端 129 端点 / 前端 101 调用全命中，0 缺失；真浏览器真栈冒烟 6/6 进一步验证路径与鉴权头。
- 结论：无「调用不存在成员」型 P1。

## 7. 缺陷表（本轮 OPEN）

**未发现缺陷。**

上两轮 D-01…D-09 于第 2 轮已逐条闭合并在本轮全量回归中保持；D-10/D-11/D-12 于本轮原命令复跑确认闭合（见 §3.1、§3.5），不再列入本表。

## 8. 人工补测清单（剩余项，均为环境/成本限制，非交付缺陷）

| 项 | 验收点 | 方法 / 工具 | 为何仍需人工 |
|---|---|---|---|
| 真栈深业务流浏览器回放 | 提报→审批→评审→入池→版本→发布单全链在真数据上的端到端 | 以 5173 + 真栈手工走一遍，或用本轮 `real-stack-ui-smoke.mjs` 扩展业务步骤 | 本轮真栈只做了 6 项联通冒烟；深流程断言跑在 mock 契约桩上（20/20），桩与真后端的差异（如夹具 `allowedActions`）需人眼复核 |
| 跨浏览器 / 高 DPI 视觉 | 字号、折行、色差、滚动条 | Chrome/Safari/Edge 1440×900 与 200% 缩放截图比对 DESIGN §7 | 本轮只跑了 Chromium headless |
| 100 万行级导出 | 更大规模下的内存与时延 | 构造 100 万行后 `export.csv` 计时 | 构造成本高；10 万行已实测 1s、等式成立 |
| 真实 CI 对接 | Jenkins/GitLab 上报的签名与重试 | 用真实 CI 插件按契约示例上报 | 交付只含通用契约 + curl 示例，无真实 CI 环境 |
| 无障碍 / 读屏 | 焦点顺序、aria、对比度 | axe + 读屏人工 | 自动化覆盖有限 |

## 9. 判定与残留风险

- **可验收**：20 条 AC 在实栈门禁（158→159 全绿）、25 条负向、109 单测、15 条静态门禁、67 项 T6 端到端、真浏览器 14+20+6 项上均有可复跑证据；三条历史缺陷按原判据闭合，未见回退。
- 残留风险（不构成阻断）：① 深业务流的浏览器验证目前是「mock 全量 + 真栈冒烟」的组合，不是「真栈全量」；② 10 万行以上导出仅验证到 100126 行；③ 前端 CORS 白名单默认只放 5173（可配置），换端口预览会被网关以 `Invalid CORS request` 拒绝——属部署约定，非缺陷。
