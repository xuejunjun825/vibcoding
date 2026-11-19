# QA 报告 · r32 演示夹具到 PUBLISHED（复验轮 round-2）

- 结论：**可验收**。0×P0/P1/P2；上轮唯一 P2（D-1）经原判据复跑确认**已关闭**（关键字命中 0 → 13）；本轮新增 2 条 P3 观察（N-1 门禁注释编号错位、N-2 发布单「发布步骤」渲染 `[object Object]`），均不阻塞。
- 范围：`req-service/src/main/java/com/example/req/seed/{DemoFixture,DemoApprovalFixture,DemoResetService}.java`、`scripts/platform-demo-reset.sh`（70→74）、`scripts/release-flow-verify.sh`（S7 八条 / 32→40 / 静态 6→7）、`docs/teamflow/DEMO-RELEASE-FLOW.md`、`docs/teamflow/demo-shots/r32-*.png`、`docs/teamflow/memory.md`。
- 环境：macOS，JDK 25，Maven 离线仓 `.m2/repository`；演示栈已起（网关 8080 对未认证请求 401 属预期、req-service 8082 health 200、MySQL 可直连）；浏览器在宿主沙箱内不可启动（crashpad 限制，非交付缺陷）。
- 方法：**复验轮口径**——先按原样重跑上轮留在 `logs/teamflow/tf-mwmvwpj7-6kmkas/scripts/` 的全部 checker 与每条缺陷的 Check command，再只补上轮未用脚本覆盖的面（A.3 SQL 可执行性、发布单详情渲染），最后核对架构/接口/提交面。
- 基线：r30 审批链 + r31 提交区间判据；本轮不改业务语义，无迁移、无 pom/web 依赖改动。

## 1. 回归契约重跑（上轮 checker，逐条通过数）

| # | checker（logs/teamflow/tf-mwmvwpj7-6kmkas/scripts/） | 结果 | 说明 |
|---|---|---|---|
| 1 | `r32-d1-a3-evidence.sh` | ✅ 8/8，exit 0 | 8 步标题 8 条、被拦链在、A.3 标题含「全绿」、A.3 内含 PUBLISHED/released_at/wf_history/409、关键字 13≥3 |
| 2 | `qa-r32-ac-probe.sh` | ✅ **61/61**，exit 0 | 上轮 59/61（仅 D-1 两条红），本轮全绿 |
| 3 | `t1-fixture-chain.sh` | ✅ 16/16，exit 0 | R-01 两单 APPROVED、净态 POOLED、流水 8 条（含 NODE_APPROVED 2）、R-04 PENDING_TEST+MR+actual 2.0+DELIVER 审计、种子通知 4 / 链上 0 |
| 4 | 平台/门禁/权限/lint（6 条） | ✅ 全绿 | 见 §2 |

## 2. 用例与结果

| # | 用例 | 命令 | 结果 |
|---|---|---|---|
| 1 | 夹具三段事务 + 幂等 | `bash scripts/platform-demo-reset.sh` | ✅ exit 0，**74/74**（5/5 段；`[28] 1\|1\|1\|1\|1\|1`、`[29] 1\|1\|1\|1`、`[30] 1\|1\|1`、`[31] DEMO-C-03,DEMO-C-04`；段末两次重置计数逐键一致） |
| 2 | 端到端 8 步到 PUBLISHED + 负向 | `bash scripts/release-flow-verify.sh` | ✅ exit 0，`RELEASE_FLOW_TOTAL=40 CASE=40 PASS=40 FAIL=0`；`[36]` 清空 MR → 409 `failedRules` 恰 `[MR_FILLED]`、快照 0→0；`[37]` 恢复后 200、`round=1`、`payloadHash=56479a71b0dc…`（64hex）、四段同源；`[38]` 质量门快照 #799、QG-01/02/03 PASS；`[39]` 发布单两级 `APPROVED,APPROVED`；`[40]` `LOCKED→PUBLISHED`、`releasedAt=2026-11-19T11:02:26.074`、三级全 APPROVED |
| 3 | 静态契约（分母 40 / 分段 7 / 文档 8 步 + 被拦链 + A.3 取证） | `bash scripts/release-flow-verify.sh --static-only` | ✅ exit 0，`STATIC_TOTAL=9 PASS=9`（第 08 条已扩为校验 A.3 自带 PUBLISHED/released_at/留痕/409） |
| 4 | 平台门禁不缩水 | `bash scripts/platform-verify.sh` | ✅ 194/194 |
| 5 | 负向不退化 | `bash scripts/platform-test-negative.sh` | ✅ 130/130 |
| 6 | 角色×页面准入 | `bash scripts/access-verify.sh` | ✅ 206/206（a7 16/16，下限 12） |
| 7 | RBAC 声明一致 | `bash scripts/rbac-verify.sh` | ✅ 113/113（11 段） |
| 8 | 工程 lint | `bash scripts/tests/lint.sh` | ✅ 22/22 |
| 9 | 单元/集成测试（Java 面，本轮零改动，复验跑一次） | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service test` | ✅ exit 0，`Tests run: 307, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |
| 10 | 缺陷 D-1 原判据复跑 | `awk '/^### A\.3 /{f=1} /^### A\.4 /{f=0} f' docs/teamflow/DEMO-RELEASE-FLOW.md \| grep -cE 'PUBLISHED\|releasedAt\|409'` | ✅ **13**（修复前 0），判据 ≥3 通过 |
| 11 | A.3 内嵌 SQL 真库可执行（本轮补，上轮未覆盖） | `bash logs/teamflow/tf-mwmvwpj7-6kmkas/scripts/r32-d1-a3-sql.sh` | ✅ 4/4：wf_task 阶梯查询、wf_history `detail_json->>'$.nodeKey'` 提取、release 查询（1 行 DEMO-REL-1）、deploy_flow_snapshot 查询全部可执行 |
| 12 | 发布单「发布步骤」渲染（本轮补，上轮只读图） | `bash logs/teamflow/tf-mwmvwpj7-6kmkas/scripts/r32-n2-steps-render.sh` | ⚠️ 4/4 命中缺陷 N-2（详见 §4，非本轮引入） |
| 13 | 截图真实性（读图复核，非只查存在） | `r32-published-chain.png` / `r32-admission-409-mr.png` | ✅ 均为 r32 产物、未覆盖 r30；图 1「已发布」+ 四段全通过 + n1/n2/n3 全通过 + R-04「测试通过 2/2」；图 2「通过 5 / 失败 1（`MR_FILLED`）/ 关闭 3」+「提交快照：尚未提交」 |

## 3. 本轮新增覆盖面（为什么补）

- **A.3 内嵌 SQL 可执行性**：D-1 的修复把「查库证据」写进 A.3，但「文档里有 SQL」不等于「读者照着能跑」。上轮只做关键字计数，本轮把 A.3.2/A.3.3 的 4 条查询原样喂给真库（`r32-d1-a3-sql.sh`，4/4），并核对列名存在于 `V1__platform_schema.sql:321-357` / `V14__release_flow_chain.sql:141-153`。
- **发布单详情渲染**：上轮对 `r32-admission-409-mr.png` 只做读图，未做脚本判定。本轮以「门禁 payload 形态 → 前端 `stepsText()` 逻辑 → 渲染结果」三段复现（`r32-n2-steps-render.sh`），命中 N-2。
- 其余面（夹具形态、8 步链路、两把梯子、负向 409、四门禁、提交面）上轮已覆盖，本轮按原样重跑，未另造等价探针。

## 4. 缺陷复验比较（round-2 级；仅供比对，不是新开缺陷）

| 编号 | round-2 级 | 模块 | 判据复跑结果 | 状态 |
|---|---|---|---|---|
| D-1 | 原 P2 → **closed** | 文档（发版手册 A.3） | `awk…A.3…\|grep -cE 'PUBLISHED\|releasedAt\|409'` = 0 → **13**（≥3）；A.3 现有 A.3.1 十五步响应码表、A.3.2 两阶梯待办+查库 SQL、A.3.3 `released_at=2026-11-19 10:17:36.943`+`round_no=1`、A.3.4 清空 MR 的 409 | **已关闭**，A.1 已收敛为摘要并回指 A.3，无重复叙述 |
| O-1 | 原 P3 → **open** | 质量门展示口径 | 截图 ③ 卡片仍是「PASS」徽标 + 副标题「1 条未通过」；`FlowEvidenceService.java:278-295` 仍把 `SKIPPED` 计入 `failedRules`（与 `QualityGateService.java:468` 口径不一致） | 仍开（既有面，dev 已列 out-of-scope） |
| O-2 | 原 P3 → **open** | 技术文档 | `TECHNICAL.md:72` 仍写 `wf_task.assignee_id`，库列为 `approver_id` | 仍开（任务夹不可变，仅记录） |

## 5. 缺陷表（仅 OPEN）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| N-1 | P3 | 门禁脚本（注释契约） | 读 `scripts/release-flow-verify.sh` 的 S7 注释标号 | AC-12 八条编号与 `TECHNICAL.md` §8.1 一致：S7-6=发布单两级、S7-7=版本三级、S7-8=负向 409 | `:1347` 标 `[S7-7] 发布单两级`、`:1367` 标 `[S7-8] 版本三级`、`:1277` 标 `[S7-8] 负向` ⇒ 出现两个 `[S7-8]`、缺 `[S7-6]`；函数 `s7_order_levels():1348`/`s7_release_levels():1368`/`s7_negative_mr():1280` 均被调用，40 条断言全绿，行为不受影响 | AC-12 | `grep -nE '^\s*# \[S7-[0-9]\]' scripts/release-flow-verify.sh` | 输出 8 行且 S7-1..S7-8 各恰一次；`S7-6` 在发布单两级、`S7-7` 在版本三级、`S7-8` 在负向（当前：7 行、两个 S7-8、无 S7-6） |
| N-2 | P3 | 发布单详情页（r30 遗留面） | 打开发布单详情「发布基本信息」 | 「发布步骤」显示可读文本 | 显示 `1. [object Object]`（截图 `r32-admission-409-mr.png` 可见）：门禁造单 payload 为对象数组（`scripts/release-flow-verify.sh:212` `steps:[{stepNo:1,action:…}]`、`:242` `steps:[{name:"发布",order:1}]`），`web/src/views/deploy/DeployOrderDetailView.vue:407-412` 对数组元素 `String(step)` | AC-18（证据图可读性） | `bash logs/teamflow/tf-mwmvwpj7-6kmkas/scripts/r32-n2-steps-render.sh` | 第 3 条输出为可读步骤串（如 `1. 执行（预期：通过）`），不含 `[object Object]`；当前 4/4 为「缺陷命中」 |
| O-1 | P3 | 质量门展示 | 看 `r32-published-chain.png` ③ 卡片 | 质量门 PASS 时不出现「未通过」字样 | 副标题「1 条未通过」（`SKIPPED` 的 QG-05 被计入 `failedRules`） | AC-10/AC-18 | - | - |
| O-2 | P3 | 技术文档 | 读 `TECHNICAL.md:72` | 字段名与库表一致 | 写 `assignee_id`，实为 `approver_id`（任务夹不可变，仅记录） | AC-2 | - | - |

P0/P1/P2：未发现缺陷。N-1/N-2 均为 P3、不阻塞验收；N-2 已确认属 r30 遗留（`git diff HEAD -- scripts/release-flow-verify.sh` 无 `steps:` 行），本轮未改。

## 6. 架构核查（轻量，M3）

| 蓝图项 | 落地 | 判定 |
|---|---|---|
| 常量收口 `DemoFixture.MR_URL/ACTUAL_DAYS` | `:45` `public static final String MR_URL="https://git.example.com/mr/r32-e2e"`、`:46` `BigDecimal ACTUAL_DAYS=new BigDecimal("2.0")`，文档/门禁同值引用 | 符合 |
| `DemoApprovalFixture` 独立 `@Service`（与 Tx1 裸 JDBC 分事务） | 新增类，`seed()/approveAll(long)/notificationBaseline()/cleanupNotifications(long)` 与蓝图签名一致 | 符合 |
| 单入口 `reset()`，Tx3 由 `TransactionTemplate` 包裹 | `DemoResetService.java:145` `approvals.seed()` 为唯一调用点 | 符合 |
| 不直插 `wf_*` | `grep -rn 'INSERT INTO wf_' req-service/src/main/java/com/example/req/seed/ \| wc -l` = 0；审批走引擎，流水含 SUBMIT/APPROVE/NODE_APPROVED | 符合 |
| 运维入口唯一（scripts/ 承载、Makefile 薄封装） | 新逻辑全在 `scripts/*.sh`；S7 复用既有建版本 helper，无第二个入口脚本 | 符合 |
| 重复实现/漏抽抽象 | `approve-until-empty` 的 Bash/Java 双份属跨运行时不可共享（蓝图已声明靠同一断言口径约束）；MR 字面量分布在 Java/两脚本/文档，值一致且注释声明同改 | 可接受（P3 级风险，不计缺陷） |
| 坏结构/回归 | 四门禁 + 307 单测全绿，无既有结构破坏 | 符合 |

## 7. 接口一致性（静态，无需运行）

| 被调成员 | 目标 | 证据 |
|---|---|---|
| `DemoFixture.requirementsRows(long, Map<String,Long>)` | `DemoFixture.java` | `DemoResetService` 调用实参形状一致；`MR_URL`/`ACTUAL_DAYS` 公开常量被夹具与断言读取 |
| `DemoApprovalFixture.seed()` | `DemoApprovalFixture.java:86` | `DemoResetService.java:145` 调用；Spring 组件扫描覆盖 `com.example.req.seed`（reset 实测生效） |
| `Requirements.deliver(long, DeliverRequest)` | `DemoApprovalFixture.java:110` | `new DeliverRequest(ACTUAL_DAYS, ACTUAL_DAYS, MR_URL, null)` 与四参 record 顺序/类型一致 |
| `ApprovalEngine.approve(long,String)`、`ApprovalStore.activeInstance/tasks`、`ApprovalTask.isPending()` | 引擎/存储/模型 | 调用形状一致；`wf_task` 实际列 `approver_id`（无 `assignee_id`），实现与断言均用真实列 |
| `DemoResetService` 注入 `DemoApprovalFixture` | 同包 `@Service` | 构造注入，reset 实测通过 |

无「文件在但成员不存在」情形；`requirementsRows`/`MR_URL` 等符号 `grep` 全部命中，无 P1 接口缺陷。

## 8. 提交面卫生

- `git status --porcelain`：6 改（seed 三文件、两脚本、`docs/teamflow/DEMO-RELEASE-FLOW.md`、`docs/teamflow/memory.md`）+ 4 增（任务夹、两张 r32 PNG、`DemoApprovalFixture.java`），**无** `target/`、`node_modules/`、`__pycache__/`、IDE/密钥类噪声；diffstat 668+/60−。
- 改动面：`git diff --stat -- '*pom.xml' 'web/package.json' 'web/pnpm-lock.yaml'` 为空；迁移文件 0；`git check-ignore -v docs/teamflow/demo-shots/r32-published-chain.png` exit 1（未被忽略，可入库）；`logs/teamflow/` 已被忽略。
- 未提交态影响：门禁 `[32]` 为 `a03be22..a03be22`（空区间自愈绿）+ 判据自检 4/4（正例绿、改 V13/新增 V15/改模块 pom 均红）；**提交后须复跑一次**（人工补测 M-3）。

## 9. 人工补测清单（环境限制，非交付缺陷）

| # | 项 | 方法 / 工具 |
|---|---|---|
| M-1 | 真浏览器视觉复核（遮挡、动画、hover/focus 细节） | `make platform-up` 后 Chrome 打开版本详情与发布单详情；宿主沙箱禁浏览器（crashpad 无法拉起），需桌面环境人工看 |
| M-2 | MR 链接可点性 | `https://git.example.com/mr/r32-e2e` 为示例域名（不可解析）；换真实地址时需同改 `DemoFixture.MR_URL`、两脚本 `DEMO_MR_URL`、文档示例 |
| M-3 | 提交后区间判据（第 32 条 / S6） | host 提交后跑 `bash scripts/release-flow-verify.sh`，确认 `[31]/[32]` 由 `a03be22..a03be22` 变为 `a03be22..<new>` 且全绿 |
| M-4 | 外部质量源接入后的 QG-05（当前 SKIPPED） | 接 Nexus/Sonar 后看准入第 6 项与 ③ 卡片文案是否与 `QualityGateService` 口径统一（O-1 的根治面） |

## 10. 结论

- **可验收**：D-1 已按原判据关闭（0→13），本轮全部回归契约重跑全绿（74/74、40/40、9/9、194/194、130/130、206/206、113/113、22/22、307/307、61/61、8/8、16/16），端到端终态 `PUBLISHED`（`releasedAt=2026-11-19T11:02:26.074`）+ 两级/三级逐级留痕 + 负向 409 指名真实落库。
- 架构与接口：与蓝图一致（常量收口、独立夹具类、单入口 reset、禁直插 `wf_*`、scripts 唯一入口）；跨模块成员全部存在且签名匹配。
- 遗留：N-1（P3 注释编号）、N-2（P3 发布步骤 `[object Object]`，r30 遗留建议 r33 修）、O-1/O-2（P3 观察）不阻塞签收；提交后按 M-3 复跑一次区间判据即闭环。
