# ACCEPTANCE · r29 存量 PDR/RD 工时评估单回填（终验）

- 任务夹：`docs/teamflow/20261118-r29-wbs-estimate-backfill/`　runId：`tf-mwlztf64-ylus7r`　分支：`feat/wbs-estimate-backfill`
- 对照物：`PRD.md` §5（AC-1…AC-8）、`TECH-CHANGE.md` §5.1/§5.2、`RUNBOOK.md` §0–§4、`QA-REPORT.md`（19:36 复验轮）
- 结论：**✅ 通过**。PRD 八条 AC 逐条核对成立；0 个 P0/P1/P2 开放项；架构与蓝图一致；仅留 1 个 P3 架构技术债（下轮立项，非阻塞）。

## 1. 验收方法（本轮实跑 = 终验自己跑的；QA 实测 = 引用 QA-REPORT 的复跑结果）

| 手段 | 命令 | 结果 |
|---|---|---|
| 交付门禁（真栈） | `make wbs-backfill-verify` | **18/18**（`WBS_BACKFILL_TOTAL=18 PASS=18 FAIL=0`），exit 0；末尾自动 `platform-demo-reset` 复原 |
| 交付门禁（静态） | `bash scripts/wbs-backfill-verify.sh --static-only` | **7/7**（s01–s07，含 s07 守 RUNBOOK 字段口径） |
| 报告契约（活接口） | `curl -X POST …/internal/wbs-estimate-backfill?tenant=t1&dryRun=true` | `mode=dry-run`、`summary{scanned:12,planned:0,created:0,skipped:12,failed:0}`；行含 12 键，`instanceId:null` 保留（ALWAYS 生效） |
| 新增单测 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service -Dtest=WbsBackfillServiceTest test` | exit 0（4 例：dry-run 零写入／BUS+终态／非法人天隔离／二次 planned=0） |
| 静态质量 | `make lint` | **22/22** |
| 判定零复制/直插 | `grep` 新增四文件 | 无 `JdbcTemplate`/`INSERT wf_*`（`NO_DIRECT_JDBC`）；`0.20` 只出现在注释里，无阈值副本 |
| 改动面 | `git diff --stat` | 仅 4 个 M（`Makefile`、`memory.md`、`application.yml`、`platform-up.sh`）+ 8 个 `??`（4 Java + 1 单测 + 2 脚本 + 任务夹）；**无迁移/SQL/schema 改动** |
| 回归门禁 | QA 复验轮实跑（本轮未重跑，仅抽 `lint` 与新增门禁） | platform-verify 193 / negative 130 / access 206 / rbac 113 / unit 375 / demo-reset 69 / web typecheck+build 全绿 |

## 2. AC 逐条核对

| AC | 判据 | 证据 | 结论 |
|---|---|---|---|
| **AC-1** 双态入口与开关 | 开关未开→404 `BACKFILL_DISABLED`；dry-run `mode=dry-run`；dry-run 前后 `wf_instance`/`wf_history` 计数不变 | ① 门禁 S1 四条全绿（含逐表计数不变）；② QA `qa-r29-disabled-404.sh` **5/5**：真实 `stop/start` req-service 两次，404+`BACKFILL_DISABLED`、错令牌 401、恢复后 200 `dry-run`；③ 代码：`WbsBackfillController` 开关分支 + `application.yml` `platform.ops.backfill.enabled: ${PLATFORM_OPS_BACKFILL_ENABLED:false}` 默认 `false`；④ 本轮活接口 `mode=dry-run` | ✅ |
| **AC-2** 选取口径 | BUS 零单据；人天空/≤0→`NO_ESTIMATE_DAYS` 且仍 409；终态/`PENDING`/`APPROVED`→对应 SKIPPED | 门禁 S4（BUS 2 条：按 `biz_id` 查无单 + 报告 `TYPE_BUS`）、S5（无人天 2 条：仍 409 `ESTIMATE_NOT_APPROVED`）全绿；`WbsBackfillService.classify` 依次 BUS→`isTerminal`→`estimateDays<=0`→`statusesOf`（`PENDING`/`APPROVED`）；QA 新探针 `qa-r29-stale-pending-prevstatus.sh` **22/22** 覆盖 `STALE`/`PENDING`（PENDING 未被越权改写）；终态沿用 `StateTables.isTerminal` | ✅ |
| **AC-3** 真实审批链路 | 存在 `APPROVED` 单；`wf_history` 有 SUBMIT+APPROVE 且 `detail_json`/`comment` 含同一 batchId；无直插；`schedule`/`start_dev` 不再 409 | 门禁 S2 **5/5**：PDR 评估单 `APPROVED`、PDR 排期 200、RD 开始开发 200、`created==planned=2>0`、实例 4121 的 SUBMIT note 与 APPROVE 评论均含 `存量回填（wbsbf-20261118T1136-765efc）`；QA 接口检查 **36/36**（跨模块签名比对 + `wf_*` 零直插）；`WbsBackfillRowWriter` 只调 `engine.start`/`approve`、`ApproverDirectory` | ✅ |
| **AC-4** 幂等 | 二次运行 `planned=0 && created=0`，两表计数不变 | 门禁 S3 **3/3**：同批二次 apply planned=0/created=0、`wf_instance` 14→14、`wf_history` 56→56；QA 另测不同 `batchId` 19/19 | ✅ |
| **AC-5** 报告契约与失败语义 | 七键齐全（null 也留键）；有 FAILED 仍 200 完整报告；退出码 0/1/2/3；单行失败不中断 | 本轮活接口：`instanceId:null` 键在（`@JsonInclude(ALWAYS)`，`WbsBackfillViews` 两处注解）；门禁 S6 **2/2**：`3.2` 天行 `FAILED/INVALID_ESTIMATE_DAYS` 而同批好行仍 `CREATED`、该次退出码 **1**；静态 s05 缺 `--tenant`→**2**、s06 连不上→**3**；全成功门禁/脚本 exit **0** | ✅ |
| **AC-6** 门禁自声明 | `make wbs-backfill-verify` 自声明 18，实跑数必须相等 | 本轮 `make wbs-backfill-verify` → `18/18`；脚本 `WBS_BACKFILL_TOTAL=18` + 末尾 `CASE != TOTAL` 即红（`wbs-backfill-verify.sh:40,476-480`） | ✅ |
| **AC-7** 不回归 | 各门禁达 R7 下限；六态/±20%/409 三键/状态机/迁移零改动 | `git diff` 无 `WbsEstimateService`/`StateTables`/`ApprovalEngine`/`RequirementRules`/迁移文件改动，无 schema 变更；QA 复跑：platform-verify **193**、negative **130**、access **206**、rbac **113**、unit **375**（≥371+新单测）、demo-reset **69**、lint **22**（本轮复跑 22/22）、web typecheck+build 绿 | ✅ |
| **AC-8** 运维文档 | RUNBOOK 覆盖备份四表、dry-run 读法、apply 窗口与开关、失败重试、回滚口径、按 batchId 反查 | `RUNBOOK.md` §0.1 备份 `requirement/wf_instance/wf_task/wf_history`；§1 `result=PLANNED` 读法 + apply-only 字段标注（s07 守）；§2 窗口期与「同 batchId 重跑」；§3 反查 SQL 已用真实列名 `detail_json`/`comment`（并订正 `audit_log` 不含 batchId）；§4 只增不撤、禁 `UPDATE`/`DELETE` 单据 | ✅ |

## 3. 架构一致性（M3 质量门）

- **蓝图落地**：`WbsBackfillViews`（契约，`ALWAYS`）/`WbsBackfillService`（计划，只读、批量 `statusesOf`、不开事务）/`WbsBackfillRowWriter`（独立 bean + `REQUIRES_NEW`，事务内重读→重判→`checkHalfDay`→`ApproverDirectory`→`start`→真实待办 `approve`→复核 `APPROVED`）/`WbsBackfillController`（`/internal`，独立开关）/`application.yml` 一行开关——五件套与蓝图逐项对应，RD 先于 PDR 的排序与「事务内重读」均实测生效（父 PDR 汇总被改写后仍正确）。
- **判定零复制**：`statusesOf/statusOf`、`StateTables.isTerminal`、`RequirementRules.checkHalfDay`、`ApproverDirectory`、`engine.start/approve` 全为调用；无第二份终态表、无 0.20 副本、无域负责人裸 SQL、无 `wf_*` 直插。
- **两处无害形变（已记录，非偏离）**：① `Candidate` 比蓝图多一个 `status` 字段——同一份蓝图要求 `Row` 带 `status`，不带就无法填行；该 DTO 只在进程内流转，HTTP 契约 `Row`/`Report` 与蓝图逐字段一致；② 控制器返回 `ResponseEntity<?>` 而非直接 `Report`，为承载 404/400 两个错误体，正常路径仍是完整报告。
- **已知技术债（P3，不阻塞）**：`WbsBackfillRowWriter` 与 `DemoEstimateFixture.seedDemo` 各持一份「start→挑待办→切身份 approve→还原 scope」驱动链。这是**蓝图本身明令的边界**（「本轮明令不改夹具……后续可抽 `WbsEstimateApprovalDriver` 统一两处」），PRD §3 也把「不改演示夹具」列为不做，因此不构成本轮偏离或应抽未抽；已登记 memory 已知待办，建议下轮单独立项。

## 4. 遗留、假设与人工补测

| 项 | 级别 | 说明 |
|---|---|---|
| 驱动链两份（D-3） | P3 | 同上；不影响行为与审计，抽取属下轮范围 |
| §7-2 管理员代批口径 | 决策已落地 | 现实现：域负责人优先 → 否则实例真实待办审批人 + `FALLBACK_APPROVER`/`approverSource`，**不代批**；若要管理员真能代批，须给 `WBS_ESTIMATE` 或签加 `USER:admin`（改种子、动 r26 快照，另计回归面）——本轮按「不改引擎」拍板，PRD §6 已载明 |
| `audit_log` 无 batchId | 已订正文档 | 批次审计只走 `wf_history`（SUBMIT `detail_json` + APPROVE `comment`），RUNBOOK/TECH-CHANGE 已一致；补 `ESTIMATE_BACKFILLED` 事件需先加 web 文案，本轮未做 |
| 生产形态联调 | 人工补测（环境限制） | 不注入开关时的 404/401 已在 QA 的真实重启探针验证；真实存量规模的锁/耗时、真库备份回滚演练需预发环境按 RUNBOOK §0/§5 执行 |
| UI/截图 | N/A | 纯后端运维通道，无 UI 变更 |

**环境复原确认**：终验末尾 dry-run 为 `scanned=12 planned=0 skipped=12 failed=0`，演示数据已由门禁收尾 `platform-demo-reset` 复位，无残留夹具与在途回填单。

验收结论：✅ 通过
