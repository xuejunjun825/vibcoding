# r27 验收报告 · 需求池列表 allowedActions 与详情同源

> 任务夹：`docs/teamflow/20261117-r27-allowed-actions-parity/`　验收人：产品经理（acceptance）
> 分支 `feat/wbs-estimate-approval` · 基线 HEAD `0d91715`（本轮改动仍在工作树，由 host 收口提交）· 真栈 gateway `127.0.0.1:8080` / MySQL / tenant `t1`
> 验收方式：不转引 QA 数字——P0 门禁逐条重跑；证据图逐张目视；蓝图签名逐项对照源码。

## 1. 结论

**✅ 通过。** 14 条 AC 逐条满足；P0 十条在真栈复跑全绿；实现与架构蓝图一致，`RequirementPoolQuery` 已无第二套判定，且未发现重复实现或结构破坏。P0/P1/P2 = 0，P3 观察 6 条（§6），均不阻断合回。

## 2. 验收人独立复跑

| 命令 | 结果 |
|---|---|
| `bash scripts/platform-verify.sh` | exit 0，**193/193**（含 AC-2 静态同源检查） |
| `bash scripts/platform-test-negative.sh` | exit 0，**130/130**（含 4d 段 19 条） |
| `bash scripts/access-verify.sh` | exit 0，**206/206**（a7 16） |
| `bash scripts/rbac-verify.sh` | exit 0，**113/113** |
| `bash scripts/platform-demo-reset.sh` | exit 0，**69/69**（授权 1848 = 11×168） |
| `bash scripts/tests/lint.sh` | exit 0，**22/22** |
| `bash scripts/platform-unit.sh` | exit 0，**371** 条全通过 |
| `mvn -o -pl req-service -am test -Dtest=WbsEstimateBatchParityTest` | exit 0，4/4 |
| `bash logs/.../scripts/r27-list-detail-parity.sh 11`（reset 后抽样） | PASS，列表与详情逐字符相等 |
| `git diff --stat HEAD -- pom.xml web/package.json` | 空 |

## 3. AC 逐条核对

| AC | 结论 | 证据 |
|---|---|---|
| AC-1 列表/搜索/详情逐字符串相等 | ✅ | 负向 4d 段 19 条全绿，比较对象为列表行、搜索结果行、详情根、`.view` 四处 `jq -c` 原始数组；4d.7 另取整页前 5 行逐行与自身详情比对；复跑 `r27-list-detail-parity.sh` 亦 PASS。导出 CSV 不含该字段（非目标）。 |
| AC-2 唯一判定源、禁止二次实现 | ✅ | `grep "stateMachine.allowedActions" RequirementPoolQuery.java` 无输出；`platform-verify.sh:289-300` 静态检查 PASS；单测 4/4：`batchStatusesEqualPerRowStatus`、`batchUsesConstantQueries`、`gateKeepsOrderAndDropsOnlyGatedActions`、`busIsNeverEvaluated`。 |
| AC-3 biz1 无 `req:review` | ✅ | 负向 4d.5：biz1 看自己 PENDING_REVIEW 需求，四处一致且不含 `approve`/`review_*`（负向全集 130/130 通过）。 |
| AC-4 未过评估门为 `[]` | ✅ | 4d.1：未评估 PDR 四处均为 `[]`。 |
| AC-5 过门后含 `schedule` | ✅ | 4d.2：pm 初审通过后四处一致且含 `schedule`。 |
| AC-6 自服务 `submit` 不误伤 | ✅ | 4d.4：biz1 自建 BUS（SUBMITTED）四处一致且含 `submit`；4d.6 pm 看同一单仍含 `approve`（反「一律空数组」假修复）。 |
| AC-7 比较口径 + 变异自检 | ✅ | 断言只做 `jq -c` 原始数组文本比较（`platform-test-negative.sh:271-299`，含顺序），无 length/contains/sort 替代；`R27_PARITY_MUTATE=order` 对列表侧 `reverse`，QA 实跑 exit 1（4d.6、4d.7 第 1 行变红），改回复绿。 |
| AC-8 200 行性能不变差 | ✅ | 改前 p95 0.222s → 改后 0.218s（0.98×，限 0.244s 且 ≤1s，`perf-before/after.txt` 各 20 次采样、末页 200 行）；评估查询 Δ(200)−Δ(20)=0（`query-count.md`）；`platform-perf` 带 `PLATFORM_PERF_P95_BEFORE` 实跑 PASS。 |
| AC-9 r26 判定语义零回归 | ✅ | negative 130/130 含 4c 全段（六态拦人、10→12 放行 / 10→12.5 拦、409 三键、判定顺序）；verify 193（≥192）。 |
| AC-10 门禁不缩水 | ✅ | verify 193 / negative 130 / access 206 / rbac 113 / demo-reset 69 / lint 22 / unit 371；typecheck+build 绿（web 本轮零 diff，QA 实跑记录在案）。 |
| AC-11 r26 evidence ≥3 张可判读截图 | ✅ | 3 张均 2880×1800、文字可读，见 §5 逐张判读。 |
| AC-12 memory 基线订正 + 同源约定 | ✅ | `memory.md:36` 将 r26 订正为 192/111/206/113/69 并写入 r27 实测 193/130/206/113/69/371 与「只增不减」下限；`memory.md:35` r26 约定行补「列表/搜索/导出与详情 `allowedActions` 必须同源，禁止在池查询二次实现」。均为同主题行替换。 |
| AC-13 技术留痕 | ✅ | TECHNICAL §11 有批量取数预算（整页 ≤6 条 SQL，与行数无关）、AC-2 静态检查点、200 行前后对比；QA-REPORT §4 有四组用例命令与变异自检。`ACCEPTANCE` 逐张引用证据图；提交哈希待 host 收口回填（P3-5）。 |
| AC-14 改动面与仓库卫生 | ✅ | pom / web/package.json diff 空；改动仅 `req-service`（8 文件）+ 新单测 + 3 个 scripts + memory + 任务夹；r26 夹只新增 `evidence/`；`git status` 无 `target/`、构建产物或命令日志。 |

## 4. 架构一致性（M3 质量闸）

| 蓝图要求 | 实测 | 判定 |
|---|---|---|
| 池查询不再判定，`page/exportBatch` 按页一次批量取 | `COLUMNS` 补 `reporter_id`；`page()`/`exportBatch()` 各调一次 `actionsByRow()`；`toView(row,rollup,actions)` 第三参外置 | 符合 |
| `RequirementService` 唯一判定链 | `allowedActionsByRow` → 逐行 `allowedActions(type,status,reporterId)` → `gateFiltered(type,estimateStatus,actions)`；详情 `toView` 仍走 `gateFiltered(entity,...)` | 符合 |
| 六态收敛为唯一 `decide` | `statusOf`（单条）与 `statusesOf`（批量）只调同一 `decide`；绑定 `GateSubject`、1 条实例 IN + 0/1 条快照 IN | 符合 |
| 唯一快照解析 / 批量仓储 | `EstimateSnapshot.document(histories,json)` 为唯一解析器；`ApprovalStore.histories` 默认方法 + JPA 覆写一次 IN；两个 `...InOrderBy...` | 符合 |
| 无重复实现 | `stateMachine.allowedActions` 剩余仅 `RequirementService:644`（reopen 终态判断）与 `ReleaseService:439`（发布域），均非需求 ACL 判定链；池查询无权限/门逻辑 | 符合 |
| 接口签名 | 蓝图 `all(PoolFilter)`，实现为既有的 `all(PoolFilter,int maxRows)`（导出辅助，仅内部委托 `page()`） | 轻微偏差 → P3-1 |

结论：结构质量达标，无需返工。

## 5. r26 证据逐张目视（AC-11）

1. [estimate-gate-pdr-disabled.png](../20261117-r26-wbs-estimate-gate/evidence/estimate-gate-pdr-disabled.png)：PDR-0280「待分析」，右上「排期」灰禁用，两处提示「需先通过工时评估（当前评估单：无）」；「工时评估 未评估」与人天空值自洽。
2. [estimate-todo-approval-center.png](../20261117-r26-wbs-estimate-gate/evidence/estimate-todo-approval-center.png)：pm「产品经理-张」审批中心「我的待办」，左侧「工时评估」卡、右侧单据 #1901 待审批、被审单据 PDR-0280，`bizType=WBS_ESTIMATE` 场景成立。
3. [estimate-reject-reason.png](../20261117-r26-wbs-estimate-gate/evidence/estimate-reject-reason.png)：「工时评估 已驳回」，红色驳回原因原文（工时估算粒度过粗…），「最近动态」含同一原因全文；驳回原因可追溯。

## 6. P3 观察与遗留（不阻断）

1. **蓝图签名偏差**：`all(PoolFilter,int maxRows)` 先于本需求存在，建议下轮订正蓝图行或补 `maxRows` 说明。
2. **性能护栏依赖传参**：相对门槛仅在 CI 传 `PLATFORM_PERF_P95_BEFORE` 时生效；查询数探针在 run 目录不入库。建议 CI 固定传基线并把「Δ 查询 ≤2」并入 `platform-perf`。
3. **r26 详情页文案**：「审批进度」卡写「尚未发起审批」，与上方「工时评估 已驳回」并排易混读（r26 既有 UI，非本轮引入），建议后续区分需求审批与工时评估。
4. **AC-1「五处」与枚举四处**不一致：实际断言的四处（列表/搜索/详情根/view）已全等，导出路径静态共用批量判定但未单独断言。建议订正 PRD 措辞。
5. **提交哈希待回填**：本轮工作树未提交，host 以 `runId=tf-mwk3086h-ojufgx` + 任务夹路径统一收口；回填该提交哈希后 AC-13 闭环。
6. **文档数字小差异**：TECHNICAL 记 `platform-perf 10/10`、QA 记 13/13，p95 记录 0.218 与 0.215 并存（均满足门槛），建议下轮统一口径。

## 7. 提交与回滚

- 提交范围：8 个 Java 文件、新增 `WbsEstimateBatchParityTest.java`、`scripts/platform-{verify,test-negative,perf}.sh`、`docs/teamflow/memory.md`、本任务夹、r26 `evidence/` 3 张图；`pom.xml`、`web/package.json` 无 diff，无新依赖、零 schema 变更。
- 回滚：单提交 revert 即可；r26 判定语义、授权矩阵与演示夹具均未改。
- 合回前提：本报告 + QA-REPORT 同时归档；host 提交后再合 main。

验收结论：✅ 通过
