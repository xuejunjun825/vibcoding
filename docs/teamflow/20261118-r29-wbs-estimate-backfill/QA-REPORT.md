# QA-REPORT · r29 存量 PDR/RD 工时评估单回填（复验轮）

- 任务夹：docs/teamflow/20261118-r29-wbs-estimate-backfill/　runId：tf-mwlztf64-ylus7r　分支：feat/wbs-estimate-backfill
- 环境：本机 dev 栈（req 8082 / gateway 8080 / web 5173 / MySQL 容器），req-service 为修复后构建（target 19:22）
- 判定：**可验收（0 个 P0/P1/P2）**；上轮 D-1(P2)、D-2(P3) 已修并逐条复验通过；仍开放 **1 个 P3 架构技术债（D-3，蓝图明令本轮不改夹具）**。本轮新增 3 个上轮未覆盖面的探针（STALE、PENDING、审批人回退），共 32 条断言全绿。

## 1. 范围与方法（复验轮）

被测交付：`POST /internal/wbs-estimate-backfill`（独立开关 `platform.ops.backfill.enabled` 默认 false→404 + `X-Internal-Token`）、`WbsBackfillViews/Service/RowWriter/Controller`、`scripts/backfill-wbs-estimate.sh`（运维壳，退出码 0/1/2/3）、`scripts/wbs-backfill-verify.sh`（自声明 18 条）、`make wbs-backfill-verify`、`scripts/platform-up.sh` 开关导出、单测 `WbsBackfillServiceTest`（4 例）。

方法：①**先重跑**上轮留在 `logs/teamflow/tf-mwlztf64-ylus7r/scripts/` 的全部 6 个探针（回归契约）；②按缺陷行的原检测命令/通过判据逐条复验 D-1/D-2；③只为**上轮未覆盖**的面新增 2 个探针（STALE/PENDING 的 `previousStatus`、域负责人缺失时的审批人回退）；④全量回归门禁复跑；⑤架构/接口轻检与提交面卫生。

## 2. 用例与结果（全部本轮实跑；命令一条一检）

| 探针 / 门禁 | 命令 | 结果 | 覆盖面 |
|---|---|---|---|
| 接口一致性 | `bash logs/teamflow/tf-mwlztf64-ylus7r/scripts/qa-r29-interface-check.sh` | **36/36** | 跨模块成员存在性/签名、判定零复制、`wf_*` 零直插、pom/package.json 未改 |
| 不同 batchId 幂等 | `bash logs/teamflow/tf-mwlztf64-ylus7r/scripts/qa-r29-batch-and-contract.sh` | **19/19** | 二次 planned=0、11 键契约（null 保留键）、租户 400、退出码 2 |
| 开关/令牌 | `bash logs/teamflow/tf-mwlztf64-ylus7r/scripts/qa-r29-disabled-404.sh` | **5/5** | 真停/重启 req-service 两次：404 `BACKFILL_DISABLED`、错令牌 401、恢复后 200 `mode=dry-run` |
| **STALE / PENDING 的 previousStatus（新增）** | `bash logs/teamflow/tf-mwlztf64-ylus7r/scripts/qa-r29-stale-pending-prevstatus.sh` | **22/22** | 见 §3 |
| **审批人回退（新增）** | `bash logs/teamflow/tf-mwlztf64-ylus7r/scripts/qa-r29-approver-fallback.sh` | **10/10** | 见 §3 |
| 端到端 | `bash logs/teamflow/tf-mwlztf64-ylus7r/scripts/dev-backfill-e2e.sh` | **31/31** | dry-run→apply→同批重跑→失败行退出码 |
| 手工闭环 | `bash logs/teamflow/tf-mwlztf64-ylus7r/scripts/manual-backfill-roundtrip.sh` | **14/14** | 运维壳 dry-run/apply/重跑、详情 `estimate.status`、评估门放行 |
| 交付门禁（静态） | `bash scripts/wbs-backfill-verify.sh --static-only` | **7/7** | 含 s07：RUNBOOK 必须写 `result=PLANNED` 且标注 apply-only 字段 |
| 交付门禁（真栈） | `make wbs-backfill-verify` | **18/18**（`WBS_BACKFILL_TOTAL=18 PASS=18 FAIL=0`） | S1 契约/S2 生效/S3 幂等/S4 BUS/S5 无人天/S6 失败语义 |

回归门禁（AC-7，全部本轮实跑）：`make platform-verify` **193/193**、`make platform-test-negative` **130/130**、`make access-verify` **206/206**（a5 25/25、a6 25/25、a7 16/16）、`make rbac-verify` **113/113**（11 段）、`make platform-unit` **375/375**、`make platform-demo-reset` **69/69**、`make lint` **22/22**、`cd web && npm run typecheck && npm run build` exit 0。

## 3. 新增覆盖面（上轮为什么漏、本轮补了什么）

上轮的 dry-run 样例里 `previousStatus` 只出现过 `NONE`/`APPROVED`，审批人也只出现过 `DOMAIN_OWNER`——因为演示夹具只造「无漂移 / 已获批 / 有域负责人」三种形态，D-2 修复真正新增的分支（`STALE`、`PENDING`）和 TECH §7-2 拍板落地的回退路径**没有任何夹具**。本轮补两个自造夹具的探针：

- `qa-r29-stale-pending-prevstatus.sh`（22 条）：把 DEMO-R-05 人天改成获批快照 ×1.3（>20% 阈值）→ dry-run 行 `PLANNED` 且 `previousStatus=STALE`（D-2 的核心断言），dry-run 前后 `wf_instance`/`wf_history` 11/44 不变；同批 apply → 行 `CREATED` + `previousStatus=STALE` + `approverSource=DOMAIN_OWNER`，旧 APPROVED 单 4013 转 `SUPERSEDED`、新单 4016 `APPROVED`、需求人天=6.5、详情 `estimate.status=APPROVED`；二次 dry-run `planned=0` 且该行转 `SKIPPED/HAS_APPROVED_ESTIMATE`（`previousStatus=APPROVED`）。PENDING 面：以 **dev1** 提交评估单（6.0 天）→ 201，dry-run 行 `SKIPPED/HAS_ACTIVE_ESTIMATE` 且 `previousStatus=PENDING`，apply `planned=0 failed=0`、RD 单数仍 2、在途单 4017 仍 `PENDING`（未被越权改写）。
- `qa-r29-approver-fallback.sh`（10 条）：把域 SET 的 `owner_user_id` 置空（arrangement，reset 复原）→ 新建其域内 PDR 并摆 5.0 天 → dry-run `PLANNED` → apply `CREATED`、`approverId=2`、`approverSource=PENDING_TASK`、`flags` 含 `FALLBACK_APPROVER`、新单 4085 `APPROVED`、人天落地、整批 `failed=0`。即 §7-2 的落地方案（域负责人→管理员兜底→**由真实待办人本人批准**、不代批）可走通且可审计。
- 顺带记录一条权限事实（非缺陷）：`pm` 调 `POST /api/req/requirements/{id}/estimate` 返回 **403 `FORBIDDEN` `required=req:deliver`**（r26 权限口径，pm 只有 `req:review`），故 PENDING 夹具的提交人必须是 owner/dev1。

## 4. 上轮缺陷复验（对比表，非本轮开放项）

| 编号 | round-1 级 | 复验结论 | 原检测命令/判据（本轮实跑） | 本轮证据 |
|---|---|---|---|---|
| D-1 | P2 | **已修·通过** | `grep -q 'result=PLANNED' docs/teamflow/20261118-r29-wbs-estimate-backfill/RUNBOOK.md` → rc=0；且任务夹无 `^\| \`result=CREATED\`` 表行 | `RUNBOOK.md:33` 已写「`result=PLANNED`…apply 成功后同一张单的行才变 `CREATED`」；`:36` 明确 dry-run 与 apply 行都有 `previousStatus`；`:37` 明确「approverSource／flags **只有 apply 行才有值**」。同时 s07 静态门禁守此口径（修前红/修后绿） |
| D-2 | P3 | **已修·通过** | `jq -c '[.rows[]\|select(.result=="PLANNED")\|{code,previousStatus}]' .run/wbs-backfill/dryrun-1118192443.json` | 返回 `[{"code":"RD-1702","previousStatus":"NONE"},{"code":"PDR-0406","previousStatus":"NONE"}]`（上轮判据「计划行全为 null」不再成立）；本轮扩到 `STALE`/`PENDING` 两个新面亦全绿（§3） |
| D-3 | P3 | **仍开放（不修）** | 对比 `WbsBackfillRowWriter.apply` 与 `DemoEstimateFixture.seedDemo` 的建单+审批链 | 两处各一份「start→挑待办→切身份→approve→还原 scope」；蓝图明令本轮不改夹具，作为技术债保留，建议下轮立项抽 `WbsEstimateApprovalDriver` |

## 5. 架构与接口轻检

- 蓝图五件套落地且签名一致：`WbsBackfillViews`（public final class，5 record + `@JsonInclude(ALWAYS)`）、`WbsBackfillService`（4 依赖构造、`run(Command)→Report`、批量 `statusesOf`、自身不写库不开事务）、`WbsBackfillRowWriter`（独立 `@Component` + `@Transactional(REQUIRES_NEW)`，事务内重读→重判→`checkHalfDay`→`ApproverDirectory`→`start`→真实待办 `approve`→复核 `APPROVED`，失败抛 `RowFailure` 整行回滚）、`WbsBackfillController`（`/internal`，开关 404、令牌兜底 403、始终 200 报告）、yml 一行开关。
- 判定零复制：`statusesOf/statusOf`、`StateTables.isTerminal`、`RequirementRules.checkHalfDay`、`ApproverDirectory.domainOwners`、`engine.start/approve` 全为调用；无 0.20 阈值副本、无第二份终态表、无 `JdbcTemplate`、无直插 `wf_*`（36 条接口检查逐成员比对）。
- 一处**蓝图自洽性小缺口（非实现缺陷）**：蓝图给 `Candidate` 列了 6 字段（无 `status`），实现为 7 字段（多一个需求状态）——因为同一份蓝图里的 `Row` 要求 `status` 字段，`Candidate` 不带它就无法填行；该 DTO 只在进程内流转、不进 HTTP 契约（HTTP 契约 `Row`/`Report` 与蓝图逐字段一致），故按「补字段是正确的自洽解」记录，不计缺陷。
- 接线：`scripts/platform-up.sh:76` 导出 `PLATFORM_OPS_BACKFILL_ENABLED=true`（生产不设即默认 404）；`Makefile` 目标体仅 `@./scripts/wbs-backfill-verify.sh`，与既有 verify 家族同格式。

## 6. 缺陷表（仅列本轮仍 OPEN 的项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-3 | P3 | 架构 | 对比 `WbsBackfillRowWriter.apply`（:`118-156`）与 `DemoEstimateFixture.seedDemo` 的「start→挑待办→切身份→approve→还原」链 | 建单+审批的驱动链只有一份，供回填与演示夹具共用 | 两处各一份，未来的审批链路调整需同时改两处，易漂移 | AC-3（非阻塞观察） | - | - |

## 7. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 工具 |
|---|---|---|
| 生产形态下的 404 门 + 令牌门联调（不注入 `PLATFORM_OPS_BACKFILL_ENABLED`） | 部署后 `curl -X POST .../internal/wbs-estimate-backfill?tenant=t1` 确认 404 `BACKFILL_DISABLED`；错令牌确认 401 | curl + 平台日志 |
| 真实存量规模的 apply 窗口与锁观察（本沙箱仅 12 条需求） | 低峰窗口 `--apply` 时记录逐行耗时与 InnoDB 行锁等待，确认无长事务 | MySQL 慢查询日志 + `--report` |
| 真库备份/回滚演练（RUNBOOK §0/§5 的 `mysqldump` 与只增不撤口径） | 预发库执行备份 → 小批 apply → 按 `batchId` 反查 `wf_history.detail_json`/`comment` 复核痕迹 | mysql CLI + RUNBOOK |
| 浏览器/截图类 | N/A：本交付为后端运维通道，无 UI 变更；沙箱内 Chromium crashpad 受宿主令牌限制无法启动（环境限制） | - |

## 8. 提交面卫生

`git status --porcelain` 共 **12 项**，全部为本需求改动：4 个 M（`Makefile`、`docs/teamflow/memory.md`、`req-service/src/main/resources/application.yml`、`scripts/platform-up.sh`）+ 4 个新 Java + 1 单测 + 2 脚本 + 任务夹。`.run/`（`.gitignore:15`）、`target/`、`web/dist/`、`logs/` 均被忽略，无依赖/构建/IDE/密钥噪声；未提交（按统一收口）。收尾已 `make platform-demo-reset` 复位（11 张在途演示单），端点复测 200 `mode=dry-run`、`scanned=12 planned=0`。

## 9. 结论

- **可验收**：上轮 1 个 P2 与 1 个 P3 缺陷已修并按其原检测命令复验通过；本轮 162 条断言（5 个既有探针 105 条 + 2 个新探针 32 条 + 静态门禁 7 条 + 真栈门禁 18 条）与 8 项回归门禁全绿，主路径为真 HTTP + 真 MySQL 写库并留 SUBMIT/APPROVE 流水。
- 仍开放 1 个 P3（D-3 架构技术债），不阻塞签收；建议下轮单独立项抽 `WbsEstimateApprovalDriver`。
- 无 P0/P1/P2 开放项；无 UI 变更，craft 观察项 N/A。
