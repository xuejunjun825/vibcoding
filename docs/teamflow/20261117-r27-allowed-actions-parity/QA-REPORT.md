# QA 报告 · r27 需求动作同源（列表/搜索/导出 allowedActions == 详情）

> 任务夹：`docs/teamflow/20261117-r27-allowed-actions-parity/`　阶段：QA（深度复验）
> 分支 `feat/wbs-estimate-approval` · HEAD `0d91715`（本轮未提交，交 host 统一收口）· 栈：gateway `127.0.0.1:8080` + req-service + MySQL（tenant `t1`），`make platform-up` 已起

## 1. 结论

**可验收。** 列表、搜索、导出与详情的 `allowedActions` 在真栈上逐字符串、逐顺序相等；唯一判定源成立（池查询 0 处状态机直出），r26 六态与评估门零回归；门禁全绿且各项不低于 r27 下限。P0/P1/P2 = 0，P3 观察 3 条，均不阻断验收。

## 2. 范围与环境

- 被测改动面（12 文件，486+/25-）：`req-service` 的 `RequirementPoolQuery`、`RequirementService`、`WbsEstimateService`、`EstimateSnapshot`、`ApprovalStore`/`JpaApprovalStore`/`ApprovalInstanceRepository`/`ApprovalHistoryRepository`，新增 `WbsEstimateBatchParityTest`；`scripts/platform-verify.sh`、`platform-test-negative.sh`、`platform-perf.sh`；`docs/teamflow/memory.md` 与任务夹。
- 未改动：`web/` 源码、`pom.xml`、`web/package.json`（`git diff --stat HEAD` 为空）。
- 环境：JDK 25 / Maven 离线 `.m2`；MySQL 真栈；浏览器**不可用**（host 沙箱限制，非交付缺陷），故 UI 类断言以脚本/读图代替。

## 3. 架构与接口一致性核对（静态 + 编译）

| 蓝图要求 | 实测 | 结论 |
|---|---|---|
| `RequirementPoolQuery(ScopedJdbc, RequirementStateMachine, RequirementService)`，`toView(row,rollup,actions)`，`page/exportBatch` 按批取动作 | 构造器三参已注入；`page()`/`exportBatch()` 各调一次 `actionsByRow()`；`toView` 第三参来自 `RequirementService` | 符合 |
| 池查询内 `stateMachine.allowedActions` **0 处** | `grep -c "stateMachine\.allowedActions" RequirementPoolQuery.java` → `0` | 符合 |
| `RequirementService.ActionRow` / `allowedActionsByRow` / `gateFiltered(type,status,actions)` | 三成员存在；`allowedActionsByRow` = `allowedActions(type,status,reporterId)` → `gateFiltered` | 符合 |
| `WbsEstimateService.GateSubject` / `statusesOf` / `applyGate(type,status,actions)`，六态收敛 `decide` | 均存在；`statusOf`（单条）与 `statusesOf`（批量）**只调同一 `decide`**；`effectiveIn` 与 `effectiveInstance` 选取规则逐行一致（active→latest→非 WITHDRAWN 回落） | 符合 |
| `EstimateSnapshot.document(List<ApprovalHistory>, WorkflowJson)` 唯一解析 | 唯一解析器；旧 `document(store,json,id)` 委托它；无第二份 attributes 解析 | 符合 |
| `ApprovalStore.histories(Collection)` 默认方法 + JPA 覆写一次 IN；两个仓储 `...InOrderBy...` | 均存在；`JpaApprovalStore` 覆写为一条 `findByInstanceIdInOrderByInstanceIdAscIdAsc` | 符合 |
| 每页评估查询 ≤2（批量，常数） | `statusesOf`：1 条实例 IN + 0/1 条快照 IN；实测 Δ(200)−Δ(20)=0 | 符合 |
| 蓝图 `List<View> all(PoolFilter)` | 实际 `all(PoolFilter, int maxRows)`（方法先于本需求存在，仅导出内部使用并委托 `page()`） | 偏差 → D-01（P3） |

重复实现排查：`req-service` 内 `stateMachine.allowedActions` 仅剩 `RequirementService.requireWritable`（终态是否可 reopen，非 ACL 判定）与 `ReleaseService:439`（发布域），**均非需求动作判定链**；六态判定仅 `decide` 一处，SUBMIT 快照解析仅 `EstimateSnapshot` 一处。接口调用参数形状与签名一致，`mvn -o -DskipTests compile` exit 0 佐证。

## 4. 用例与结果（全部在真栈/真构建上执行）

| 编号 | 用例 | 命令（一次一条） | 结果 | 关联 |
|---|---|---|---|---|
| T-01 | req-service 单测（含批量=逐条 4 例） | `mvn -o -pl req-service -am test -Dmaven.repo.local=$PWD/.m2/repository` | exit 0，303 tests / 0 fail | AC-2 |
| T-02 | 平台契约全量 | `bash scripts/platform-verify.sh` | exit 0，193/193（含 AC-2 静态同源检查） | AC-2/9/16 |
| T-03 | 负向全集（含 4d 19 条） | `bash scripts/platform-test-negative.sh` | exit 0，130/130 | AC-1/3/4/5/6/16 |
| T-04 | 变异自检必须变红 | `R27_PARITY_MUTATE=order bash scripts/platform-test-negative.sh` | **exit 1**，仅 4d.6、4d.7 第 1 行 FAIL（列表被 reverse 后与其余三处不等），其余 4d 全 PASS | AC-7 |
| T-05 | 四账号跨视图一致性 | `bash logs/teamflow/tf-mwk3086h-ojufgx/scripts/qa-r27-cross-view-parity.sh` | 47 PASS / 0 FAIL，非空行合计 13（admin 11、pm 2；biz1/vpm1 当前态无可执行动作） | AC-1/3 |
| T-06 | 角色×页面准入 | `bash scripts/access-verify.sh` | exit 0，206/206（a7 16：静态 10 + 运行时 6） | AC-10/17 |
| T-07 | 三层授权 | `bash scripts/rbac-verify.sh` | exit 0，113/113（s0–s9 + s2r） | AC-10 |
| T-08 | 演示数据重建（perf 前后各一次） | `bash scripts/platform-demo-reset.sh` | exit 0，69/69（授权 1848 = 11×168，自定义角色 0） | AC-10/15 |
| T-09 | 200 行整页性能双门槛 | `PLATFORM_PERF_P95_BEFORE=0.222 bash scripts/platform-perf.sh` | exit 0，13/13；`req-list-200` p95 **0.215s** ≤1.0s 且 ≤0.244s（改前 0.222 → **0.97×**） | AC-8 |
| T-10 | 评估查询次数与行数无关 | `bash logs/teamflow/tf-mwk3086h-ojufgx/scripts/r27-query-count.sh` | 4/4 PASS，3 轮均 Δ20=10、Δ200=10、Δ200−Δ20=**0** | AC-8 |
| T-11 | 全模块单测 | `bash scripts/platform-unit.sh` | exit 0，371 个单元测试全通过 | AC-10/17 |
| T-12 | 静态门禁 | `bash scripts/tests/lint.sh` | exit 0，22/22 | AC-10 |
| T-13 | 前端类型与构建 | `cd web && npm run typecheck` / `npm run build` | 均 exit 0 | AC-10 |
| T-14 | r26 三张验收证据 | 逐张读图 `docs/teamflow/20261117-r26-wbs-estimate-gate/evidence/*.png` | 3 张均 2880×1800 可判读：①未评估 PDR「排期」灰禁用 + 两处「需先通过工时评估（当前评估单：无）」；②审批中心「工时评估」待办 单据#1901 PDR-0280；③驳回后「已驳回」+ 红色「驳回原因：…」+ 最近动态含原因全文 | AC-11 |
| T-15 | memory 订正与留痕 | `grep -n "r27 本轮实测\|r26 实测" docs/teamflow/memory.md`；`grep -n "§11\|200 行前后对比" TECHNICAL.md` | r26 订正为 192/111/206/113/69（a7 16），r27 实测 193/130/206/113/69/371 + 同源约定；TECHNICAL §11 实测表与 p95 对比在 | AC-12/13 |
| T-16 | 仓库卫生 | `git status --porcelain`；`git diff --stat HEAD -- pom.xml web/package.json`；`git rev-parse --abbrev-ref HEAD` | 仅改动面 + 任务夹 + 新单测 + r26 evidence；pom/package.json diff 空；无 target/dist/node_modules/日志；分支 `feat/wbs-estimate-approval` | AC-14/18 |
| T-17 | biz1 无 review 动作 | `curl .../requirements?size=200`（biz1） | 全部 `allowedActions` 中无 `approve` / `review_*` | AC-3 |
| T-18 | biz1 自建 SUBMITTED 含 submit | 负向 4d.4（真栈建单→提交） | PASS：列表/搜索/详情/view 四处均 `["submit"]` | AC-6 |

补充：4d.1 未评估 PDR 四处 `[]`（AC-4）、4d.2 评估初审通过后四处含 `schedule`（AC-5）、4d.3 RD 过门后含 `start_dev`、4d.5 审阅后 PENDING_REVIEW 四处一致且不含 `approve/review_*`、4d.7 第一页前 5 行逐行与自身详情相等——全部 PASS。导出路径：`export.csv` HTTP 200，`X-Export-Total` 与行数一致（T1 曾实测 12 行；本轮 `exportBatch` 与 `page()` 共用 `actionsByRow`，静态核对一致）。

## 5. 缺陷表

未发现 P0/P1/P2 缺陷。以下 3 条为 P3 观察项（不阻断验收）。

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-01 | P3 | 架构/文档 | 对照任务说明的模块蓝图与源码签名 | 蓝图接口 `all(PoolFilter)` 与实现一致（或蓝图写明 `maxRows` 上限参数） | 实现为 `all(PoolFilter, int maxRows)`，该方法是 r27 之前既有导出辅助，仅内部委托 `page()` | AC-13（留痕） | `grep -n "public List<View> all" req-service/src/main/java/com/example/req/requirement/RequirementPoolQuery.java` | 输出为 `all(PoolFilter, int maxRows)`，蓝图行同轮订正为两参即关闭；无调用方受影响 |
| D-02 | P3 | 性能护栏 | 看 perf 脚本的相对阈值与查询数断言是否常驻 | AC-8 的「≤1.1× 改前」与「Δ 查询 ≤2」在仓库内有常驻护栏 | 相对阈值仅在传 `PLATFORM_PERF_P95_BEFORE` 时生效（默认只锁 1s）；查询数探针 `r27-query-count.sh` 在 run 临时目录，不入库 | AC-8 | `grep -c "PLATFORM_PERF_P95_BEFORE" scripts/platform-perf.sh`；`ls scripts \| grep -i query-count`（无命中） | 相对基线由 CI 传参（并在 memory 记录固定值），或查询数断言进 `platform-perf.sh`/`platform-verify.sh` |
| D-03 | P3 | 需求详情 UI（观察，非本轮引入） | 打开 `docs/teamflow/20261117-r26-wbs-estimate-gate/evidence/estimate-reject-reason.png`，看右侧「审批进度」卡与左侧「工时评估 已驳回」 | 两张卡的状态语义一致（区分「需求审批」与「工时评估」，或空态说明评估单见上方） | 「审批进度」仍显示「尚未发起审批：提交后这里显示当前节点与待办人」，与已存在的评估单状态易混读 | AC-11 / craft 观察 | `test -f docs/teamflow/20261117-r26-wbs-estimate-gate/evidence/estimate-reject-reason.png`（人工读图判读） | 人工确认「审批进度」卡文案不再与评估单状态冲突；属 craft 观察，不阻断 |

## 6. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法/工具 |
|---|---|---|
| 真实浏览器交互 | 列表动作按钮点击后 403 就地提示、禁用按钮 `title` 悬浮显示「需先通过工时评估…」、URL 不变 | Chrome + 双账号（admin/pm 与 biz1/vpm1）；本沙箱无法启浏览器（host 限制，非交付缺陷） |
| 多用户并发下列表 | 并发请求不串权限快照，各自 `allowedActions` 与详情一致 | 两浏览器同时刷新或用 k6 并发 20 打 `/api/req/requirements?size=200`，逐响应比对详情 |
| 目标机型性能 | p95 ≤1.1× 基线且 ≤1s | `PLATFORM_PERF_P95_BEFORE=<实测基线> make platform-perf` |
| 键盘/读屏可达性 | 禁用按钮 `aria-disabled` 正确、焦点顺序可用、提示可被读屏播报 | axe DevTools + 键盘 Tab 走查 |

## 7. 备注与风险

- 4d 段每次运行自建夹具（PDR/RD/BUS 各一条并消耗一次审批），脚本可重复执行；本轮跑完已 `platform-demo-reset` 复原演示基线（69/69）。
- perf 会注入 10 万行数据，收尾亦已 demo-reset；测量时 `status=POOLED` 计数 14285，`size=200` 成立。
- 列表行 `decomposeGate` 仍为 `null`（r23 遗留，前端仅详情 ChildrenTab 读取），不影响本需求 `allowedActions` 一致性断言，记录备查。
- 本轮未提交代码；提交信息需带 `runId=tf-mwk3086h-ojufgx` 与任务夹路径（由 host 收口）。
