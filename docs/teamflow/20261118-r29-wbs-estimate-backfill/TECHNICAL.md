# TECHNICAL · 存量工时评估回填（r29）

> **本文定位**：技术阶段的实际产出是 `TECH-CHANGE.md`（技术变更单，含判定链、事务边界、报告契约、十八组断言的全部细节）。本文件只做**索引与落地对账**——把设计映射到实到文件与 `PRD.md` 的 AC 编号，避免出现第二套口径。两者冲突时以 `TECH-CHANGE.md` 为准并在本文件订正。

- **基线依赖**：`docs/teamflow/20261117-r26-wbs-estimate-gate`

## 1. 架构判断（摘自 TECH-CHANGE §3，不再复述细节）

回填是**运维编排**，不是业务域：入口走既有 `/internal/**`（容器内才有 `TenantContext`/`DataScope` 与 `@Transactional` 引擎），开关用独立属性 `platform.ops.backfill.enabled`（默认 `false` → 404），**不复用** `PLATFORM_SEED_ENABLED`（生产 seed 必须长期关，回填是短窗口）。

- **计划 / 执行分离**：计划用 `WbsEstimateService.statusesOf` 批量判态（只读、零写入）；执行逐行走独立 bean 的 `@Transactional(REQUIRES_NEW)`。
- **为什么执行前必须重读**：RD 获批会经 `RequirementService.applyApprovedEstimate` 把父 PDR 的 `estimate_days` 改写成子需求汇总；拿一次性读出的计划去反覆盖会派生 `STALE`。故 RD 排在 PDR 之前（`ORDER BY CASE type WHEN 'RD' THEN 0 WHEN 'PDR' THEN 1 ELSE 2 END, id`）且事务内重读。
- **判定零复制**：状态 `WbsEstimateService.statusOf/statusesOf`；终态 `StateTables.isTerminal`；人天 `RequirementRules.checkHalfDay`；审批人 `ApproverDirectory`；裸 SQL 只经 `SysDb`。
- **失败即整行回滚**：`RowFailure` 让本行（含刚建的实例）整体回滚，绝不留 `PENDING` 孤儿实例——否则重跑会被 `HAS_ACTIVE_ESTIMATE` 全挡。

## 2. 模块蓝图 → 实到文件

| 层 | 文件 | 职责 |
|---|---|---|
| 契约 | `req-service/src/main/java/com/example/req/requirement/WbsBackfillViews.java` | `Command`/`Candidate`/`Summary`/`Row`/`Report` + `RESULT_*`/`MODE_*`/`SOURCE_*`/`REASON_*` 常量；JSON 字段名即 HTTP 契约，脚本与门禁只认这里。`Row`/`Report` 带 `@JsonInclude(ALWAYS)`（`application.yml` 全局 `non_null` 会吞掉 `reason:null`，见 §4） |
| 编排 | `req-service/src/main/java/com/example/req/requirement/WbsBackfillService.java` | 解析 tenant/batchId、扫描候选、批量判态、出 dry-run 报告；apply 时逐行委托 `RowWriter`，`RowFailure` 收敛成 `FAILED` 行。自身不开事务 |
| 单行写入 | `req-service/src/main/java/com/example/req/requirement/WbsBackfillRowWriter.java` | 一个 `REQUIRES_NEW` 内：重读 → 重判 → `checkHalfDay` → 解析审批人 → 切身份 `start` → 取真实待办 → 切身份 `approve` → 复核 `APPROVED` |
| 入口 | `req-service/src/main/java/com/example/req/requirement/WbsBackfillController.java` | `POST /internal/wbs-estimate-backfill`；开关关 → 404 `BACKFILL_DISABLED`；`tenant` 空 → 400 `USAGE`；始终 200 返回完整报告 |
| 开关 | `req-service/src/main/resources/application.yml` | `platform.ops.backfill.enabled: ${PLATFORM_OPS_BACKFILL_ENABLED:false}` |
| 单测 | `req-service/src/test/java/com/example/req/requirement/WbsBackfillServiceTest.java` | dry-run 零写入、BUS/终态/无人天/非法人天分类、失败隔离、二次运行 `planned=0` |
| 运维壳 | `scripts/backfill-wbs-estimate.sh` | 参数解析 + curl + 写报告 + summary→退出码（0/1/2/3），不含业务判定 |
| 门禁 | `scripts/wbs-backfill-verify.sh` | 自声明 `WBS_BACKFILL_TOTAL=18`，六组 S1–S6 |
| 接线 | `scripts/platform-up.sh`、`Makefile` | dev 栈开开关；`make wbs-backfill-verify` 薄封装 |

## 3. AC 追溯

`PRD.md` §5 的 AC-1…AC-8 ←→ `TECH-CHANGE.md` §5.1 的 S1–S6（18 条）／§5.2 回归下限／`RUNBOOK.md` §0–§4。AC 编号在 `PRD.md` 里有完整表格，本文不重复。

## 4. 实测订正（开发阶段发现，已写入对应文档）

1. **`/internal` 错令牌是 401 而非 403**：`platform-common` 的 `InternalTokenFilter` 在控制器之前对 `/internal/**` 返回 `401 INTERNAL_TOKEN_INVALID`，`SeedController`/`DemoResetController` 的 403 分支在真实链路**不可达**。`TECH-CHANGE.md` §3.1 与 `docs/teamflow/memory.md` 的 r29 行已按 401 表述；新控制器保留同构 403 防御分支仅为与既有控制器一致，门禁脚本**不得断言 403**。
2. **`batchId` 只在 `wf_history`**：`audit_log` 的 `ESTIMATE_APPLIED`/`ESTIMATE_APPROVED` 仅带 `instanceId`/`estimateDays`。批次反查 SQL 必须同时 `LIKE` `detail_json`（SUBMIT）与 `comment`（APPROVE）（`TECH-CHANGE.md` §3.7、`RUNBOOK.md` §3 步骤 2）。
3. **`@JsonInclude(ALWAYS)` 是契约要求**：`application.yml` 的 `default-property-inclusion: non_null` 会让 `reason`/`instanceId` 的空值键消失，`TECH-CHANGE.md` §3.6 样张与「每行七键齐全」的断言随之假红。
