# 验收报告 · DevOps 研发管理平台（M1–M5）

| 项 | 值 |
|---|---|
| 需求 | 仿嘉为蓝鲸的 DevOps 研发管理平台，M1–M5 共 20 条 AC |
| 分支 | `feat/devops-req-platform`（产品代码尚未提交，见 §5-5） |
| 任务夹 | `docs/teamflow/20261108-r2-devops-req-platform/` |
| 验收依据 | `PRD.md`（AC-1…AC-20、§15 默认取值）、`TECHNICAL.md`（§0.1 偏差 D1–D6）、`QA-REPORT.md`（第 3 轮复测，OPEN 缺陷 0） |
| 基线依赖 | `docs/teamflow/20261108-r1-springcloud-env-setup`（r1 四步未回退）；历史任务夹未修改 |
| 验收日期 | 2026-11-09 |

## 1. 验收结论摘要

20 条 AC 逐条核对通过；验收人在真实栈上独立复跑全部门禁：`platform-verify` **159/159**、`platform-test-negative` **25/25**、`platform-perf` **6/6**、`platform-unit` **109/109**、`lint` **15/15**、r1 `verify` **14/14**，均 exit 0。架构蓝图的模块顺序、契约单点（JWT/HMAC/通知/指标/门禁/Scope/只读模型）逐项核对无重复实现、无适配器漂移；r1 既有结构未被破坏。QA 第 3 轮 OPEN 缺陷 0，D-10/D-11/D-12 用原检测命令复测闭合。抽查 3 张关键页面截图（发布准入、测试门禁、真栈需求池）无遮挡、布局与文案合理。**判定：通过。**

## 2. 独立复跑证据（验收人执行，非引用 QA）

| 命令 | 结果 | 要点 |
|---|---|---|
| `bash scripts/platform-verify.sh` | **159/159 PASS**，exit 0 | AC-1…AC-20 分组断言全绿，末尾 PASS==TOTAL 守卫通过 |
| `bash scripts/platform-test-negative.sh` | **25/25 PASS**，exit 0 | 非法流转/减签无原因/准入 FAIL 提交/第 4 层模块/未知用例/重复上报/锁定版本增删等均被拒且计数不变 |
| `bash scripts/platform-perf.sh` | **6/6 PASS**，exit 0 | 列表 p50 0.201s/p95 0.216s；看板 0.075/0.077s；准入 0.016/0.019s；矩阵 p95 0.125s；上报 20 并发 p95 0.020s；1000 行导入 3s |
| `bash scripts/platform-unit.sh` | **109/109 PASS**，exit 0 | 当前工作树完整编译（六模块）+ 契约/状态机/矩阵/公式/ScopeSql 单测 |
| `bash scripts/tests/lint.sh` | **15/15 PASS** | L1–L13：scope 标记、迁移只增、deliver 不写需求表、前端无重复状态字面量等 |
| `bash scripts/verify.sh` | **14/14 PASS** | r1 基线未被平台层改动破坏 |

## 3. 逐条 AC 验收

| AC | 关键判据（PRD） | 证据 | 判定 |
|---|---|---|---|
| AC-1 | 租户开通自动预设 10 角色/7 域；缺/错租户头 403 且不落库；跨租户同名可复用 | verify AC-1 组 7 项：201 开通、roles=10、domains=7、403 无新增行、同名跨租户 | ✅ |
| AC-2 | 三类需求与 BUS→PDR→RD 父子链；非法父子/环 400；父人天只读汇总（0.5 天） | verify AC-2 组 9 项：非法父 400、成环 400、直改人天 400、0.5 精度、跨版本汇总 | ✅ |
| AC-3 | 状态机不可绕：非法流转 409 带 allowedTargets、直写 status 400、每次流转写审计 | verify AC-3 组 8 项：409/400/`audit_log` from-to-source | ✅ |
| AC-4 | 域×厂商隔离覆盖 8 入口；直连 403+审计；撤销域授权下次请求生效；跨厂商转派翻转可见 | verify AC-4 组 11 项：A 可见受让需求、A 列表他厂商 0 行、导出同源 <50 行、B 直连 A 需求 403/404 不泄露存在性、PMO 全量 | ✅ |
| AC-5 | 10 角色×§6 矩阵逐格 2xx/403；多角色并集；后端拒绝而非隐藏按钮；审计 | verify AC-5 组 6 项按接口生成探测矩阵逐格断言；PermissionInterceptor 403 + `recordInNewTx` | ✅ |
| AC-6 | 四类单据流程配置、类型×域绑定与默认回退、发布固化版本、无审批人禁发布 | verify AC-6 组 5 项：发布 201、编辑已发布 409、无审批人 400 带 `nodeKey` | ✅ |
| AC-7 | 会签全过/或签一人/条件首分支/抄送不阻塞；驳回终止并回退 | verify AC-7 组 10 项 | ✅ |
| AC-8 | v2 发布后新单走 v2、在途 v1 走完；不卡死/跳步/重复任务；记 definition_version | verify AC-8 组 6 项（含加签后新人全过） | ✅ |
| AC-9 | 前/后加签、减签（原因+通知）、转办、撤回、催办留痕；已办任务加签 409 | verify 4 项 + negative：减签空原因 400 `REASON_REQUIRED`、已办加签 409 `TASK_ALREADY_HANDLED` | ✅ |
| AC-10 | 审批驱动四类单据：提交→PENDING_REVIEW、评审→POOLED、发布→级联、发布单→登记执行；驳回回退 | verify AC-10 组 11 项（含 REQ_REVIEW 启动/驳回/通过、发布级联、回调） | ✅ |
| AC-11 | Tab+8 筛选、聚合/平铺一致、CSV 行数=页面行、权限过滤与水印、版本聚合、延期标红 | verify AC-11 组 9 项；D-12 keyset 分批后全量 100126 行与筛选 100005/83 行仍同源 | ✅ |
| AC-12 | 发布单挂 Release、5 项准入服务端计算、FAIL 409 不可绕、强制放行原因/审计/通知、统计可复算 | verify 6 项 + negative：未过直接 submit 409 `ADMISSION_FAILED`、空原因强制放行 400、审核后登记执行 | ✅ |
| AC-13 | 缺陷 P0-P3、域/厂商派生不信任请求体、执行失败/手工来源、状态机+重开计数、看板 4 指标同源、业务方仅摘要、跨厂商不可见 | verify AC-13 组 8 项（派生归属、重开 +1、4 指标复算、vendor B 0 行） | ✅ |
| AC-14 | 任务拆分与完成率回写、迭代挂 Release、看板拖动持久化、未迁移任务拒关迭代 | verify 5 项 + negative：未迁移关闭 409 | ✅ |
| AC-15 | 需求→任务→MR→执行→缺陷→发布单六环互跳；缺口实时计算不假绿；缺口筛选；权限过滤 | verify AC-15 组 3 项（gaps 与 complete 同一行计算、缺 MR/缺执行/阻塞/未发布、scope 过滤） | ✅ |
| AC-16 | 模块≤3 层、TC 编号、版本化执行钉 case_version、四种结果、通过率/覆盖率、失败转缺陷、严格/宽松门禁、接口不可绕 | verify AC-16 组 33 项 + negative：第 4 层 400 不落库、空计划 FAIL、绕过 UI 仍 409 | ✅ |
| AC-17 | AppKey+HMAC+时间戳+IP+scope、幂等、未知用例/版本 422、NOT_RUN 400 不落库、最新结果合并、限流与调用日志 | verify AC-17 组 9 项 + negative 5 项：错签名/超时 401、重复 200 `idempotent=true` 只 1 行、失败不落执行、每次调用有 `api_call_log` | ✅ |
| AC-18 | 4 条指标定义含公式/描述/负责人/版本/生效日；看板与计划可下钻；同过滤条件三处一致；改口径出新版本 | verify AC-18 组 3 项；公式只在 `MetricFormulas`，查询层只取计数 | ✅ |
| AC-19 | 一条幂等种子：7 域/3 厂商/10 账号/完整 BUS→…→发布链路/1 计划 ≥10 用例；各角色仅见权限内 | verify AC-19 组 5 项 + QA `platform-seed` 连跑两遍 10/10 计数不变 | ✅ |
| AC-20 | 列表 P95≤1s、看板≤2s、准入≤500ms、矩阵≤2s、上报 P95≤300ms、1000 行导入≤10s | `platform-perf` 6/6（§2）；验收脚本与基线报告随交付 | ✅ |

## 4. 架构一致性（强制项）

| 检查 | 结论 | 证据 |
|---|---|---|
| 模块与构建顺序 | 符合蓝图 | 根 `pom.xml` modules：platform-contract→platform-common→api-gateway→demo-service→req-service→deliver-service；web 独立 |
| 契约模块 | 齐备 | 12 类：PermissionMatrix、JwtCodec、HmacSigner、StateTables、MetricFormulas、GateDecision/GateBlocker、GatePort、ApprovalCallback、DataScope、JwtPayload、ScopeTier |
| JWT 单点 | 无漂移 | 仅 `JwtCodec` 一份；网关 `JwtGatewayFilter:47`、common `AuthFilter:59`、req `AuthService:42` 复用 |
| HMAC 单点 | 无漂移 | `HmacSHA256` 只出现在 `HmacSigner`；`PasswordService` 用 PBKDF2 做口令散列，不是第二套签名 |
| 通知单点 | 无漂移 | 全仓 `INSERT INTO notification` 仅 `NotificationWriter` |
| 指标公式单点 | 无漂移 | 公式只在 `MetricFormulas`；`DefectBoardQuery`/`TestPlanQuery`/`MetricQuery`/`TaskService` 只取计数 |
| 门禁单点 | 无漂移 | 唯一实现 `deliver/.../gate/TestGateService`；req 侧仅 `DeliverGateClient implements GatePort` |
| Scope 谓词单点 | 无漂移 | `ScopeSql` 只被 `ScopedJdbc:146/164/188` 调用，业务查询无手写 domain/vendor 条件；`ScopeGuard` 负责按 ID 可见/可写 |
| 跨服务读模型 | 符合 | deliver 只经 `ReqReadModel` 读需求/版本/域；lint L11 证明 deliver 不写需求表 |
| 抽象接线 | 已落地 | `GatePort`、`ApprovalCallback` 四类回调、`RequirementApprovalStarter`（D-10 修复后唯一实现/唯一注入/唯一入口）均有真实实现与消费方，无“声明了没人实现” |
| 既有结构 | 未破坏 | r1 `ApiGatewayApplication` 未改，`PlatformRoutes` 独立配置类，`/api/demo/**` 放行；r1 verify 14/14 |
| 技术偏差 | 已记录、不改 AC 语义 | TECHNICAL §0.1 D1–D6（密钥可逆存储、无计划走快速测试、发布驳回回 DRAFT、矩阵常量化、级联范围、提交范围）；D2 的双输入在验收中两条路径均有断言 |

**结论**：实现与架构蓝图一致，未发现重复实现、适配器漂移或“应抽未抽”的结构问题，不构成有条件通过项。

## 5. 遗留与风险（不阻断验收）

1. **深业务流浏览器验证**：真浏览器全量覆盖基于契约桩（14/14 + 20/20），真栈仅 6 项冒烟（登录、需求池、详情预览、版本、发布单、console 0）；真栈上的跨页深度操作建议后续补一条 E2E。
2. **浏览器矩阵**：仅 Chromium headless；跨浏览器、窄屏与无障碍未测。
3. **导出规模**：实测到 100126 行（keyset 分批、无上限）；百万级未压测。
4. **CORS**：默认仅放行 5173，可用 `PLATFORM_CORS_ORIGINS` 覆盖，部署时需按实际域名配置。
5. **代码未提交**：PRD 要求按里程碑提交，当前全部平台代码仍未提交（untracked）。验收通过后请按 M1–M5 或单次提交入库，注意 `logs/`、`.pnpm-store/`、`target/`、`node_modules/` 不入库。
6. **5 项待拍板**按 PRD §15 默认落地（严格门禁、通过率分母=计划总数、缺陷重开口径、通用 CI 契约、M4 用快速测试过渡）；若用户另择，需重跑受影响 AC（主要 AC-16/18/19）。
7. QA 记录的测试资产维护项（`e2e-base` 落点断言、mock 桩 `allowedActions` 不一致）只影响自检桩，不入交付，不计产品缺陷。

## 6. 验收意见

本轮交付把 M1–M5 的 20 条 AC 全部落到可复现断言，负面目标（用例版本两张皮、上报刷通过率、门禁只在按钮、看板数字点不进、矩阵假绿）均有对应 negative 用例守住。建议：先按 §5-5 提交代码并保留本次验收证据，再进入下一里程碑；§5-1 的真栈深流程 E2E 作为下一轮质量项跟踪即可。

验收结论：✅ 通过
