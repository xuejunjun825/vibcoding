# M6 质量管理补全 · 技术方案

> 任务夹：`docs/teamflow/20261109-r3-qa-quality-management/`　分支：`feat/qa-quality-management`（基线 `main@5f1bb66`）
> 基线依赖：`docs/teamflow/20261108-r2-devops-req-platform/`　输入：本夹 `PRD.md`（10 条 AC）、`DESIGN.md`（14 页 / 12 组件）
> 本文只增不改：r2 的 `GatePort`（需求级测试门）、`admission_result_json` 前 5 项语义、`force-release` 路由签名全部保持不变。

## 1. 结论先行

| 问题 | 结论 |
| --- | --- |
| 质量域放哪个服务 | 策略/计划/轮次/分配/环境/报告/门禁/豁免/指标/版本质量统计**全部在 deliver-service**，新增顶层包 `com.example.deliver.quality.*`；req-service 只新增 `review`（Go/No-Go 记录）与 `integration.DeliverQualityGateClient` |
| 门禁怎么算 | 唯一实现 `deliver.quality.gate.GateEvaluator`；req 准入第 6 项经 `POST /internal/quality-gates/{releaseId}/evaluate` 调用，超时 fail-closed |
| 三处数字怎么同源 | `QualityAggregator`（计划进度 / 报告 / 门禁）唯一聚合；比率一律走 `MetricFormulas`；`result_digest` 按 PRD 固定算法（`case_id:case_version:result` 升序 SHA-256） |
| 报告导出 | deliver 新增 Apache POI（XLSX）+ OpenPDF（PDF，STSong-Light/UniGB-UCS2-H 非嵌入中文字体 + 每页水印）；不引 LibreOffice、不起独立进程 |
| 权限 | `PermissionMatrix` 追加 **15 个功能码**（26→41），`PermissionRouteTable` 在既有兜底规则**之前**插入细粒度规则；不新增角色 |
| 迁移 | 只新增 `V5__quality_management.sql`（13 新表 + 5 表增字段 + 5 行指标 + 回填），在 req-service（唯一 Flyway owner） |
| 前端 | 新增 `quality.routes.ts` + 8 个新页面 + 12 个 `components/quality/*`；改造 6 个既有页面；无新 UI 库、无新全局 store |
| 交付拆分 | 12 个任务、5 个批次（M6.1–M6.5），文件边界不相交的可并行（见 §10） |

## 2. 现状核对（实现前必须成立的 8 条事实）

1. `deliver-service` 已有 `testplan`（`test_plan`/`test_plan_case`/`test_execution`）、`testcase`、`defect`、`gate`（需求级测试门）、`metric`、`reqread`，`TestPlanEntity.status` 只有 `DRAFT/RUNNING/DONE`。
2. `req-service` 已有 `release`、`deploy_order`、`AdmissionEvaluator/AdmissionPolicy`（5 项）、`DeployOrderService.submit/forceRelease`、`GatePort` 的 HTTP 客户端 `DeliverGateClient`。
3. Flyway 只在 `req-service/src/main/resources/db/migration/`，最新 `V4__requirement_trace_index.sql`；deliver 无迁移。
4. `release` 表**没有** `ended_at`；观察窗起点必须取 `deploy_order.execution_result='SUCCESS'` 的 `ended_at`。
5. `test_plan_case` 唯一键 `(plan_id, case_id)`、存 `case_version` 与“最新结果”；**没有**轮次维度 → 轮次历史由 `test_execution.round_id` 承载，`plan_case.result` 始终是累计视图。
6. `ScopedJdbc` 要求每条 SQL 带 `/*scope:alias*/`，缺标记直接抛异常；写走 JPA、读走 ScopedJdbc 是现有铁律。
7. `PermissionRouteTable` 按声明顺序**首条命中**：`^/test-plans(/.*)?$`、`^/releases(/.*)?$`、`^/deploy-orders(/.*)?$` 已存在兜底规则，新规则必须插在其前面。
8. 前端路由用 `import.meta.glob('./routes/*.ts')` 自动汇总；`web/src/api/client.ts` 的 409 不弹 toast，由页面渲染 `ApiError.detail`；`stores/dict.ts` 的 `StatusKind` 目前 6 个。

## 3. 架构决策

| # | 决策 | 理由 | 被否方案 |
| --- | --- | --- | --- |
| D1 | 质量域整体落 deliver-service | 报告/门禁/指标的数据源（计划、执行、用例、缺陷）都在 deliver；跨服务只发生在“准入第 6 项”一处 | 报告放 req → 要么复制聚合 SQL，要么每次报告 4 次跨服务调用 |
| D2 | `quality_gate` 按 Release 一份配置，求值结果进不可变 `quality_gate_snapshot` | 配置可改、历史不可改；AC-7 回放只读快照 | 每次求值新建一份“配置” → 历史配置漂移，回放不可信 |
| D3 | 报告版本存**全量快照 JSON** + `result_digest` + `hash` | 旧版本可查可导出且不随新数据变化；digest 供门禁判过期 | 只存汇总数 → 无法导出旧版明细；存 SQL 视图 → 数据变了历史也变 |
| D4 | 轮次历史由 `test_execution(round_id)` 派生，`plan_case` 只存最新 | 不新增第 14 张表，符合 PRD 表清单；累计/分轮两视图同源 | 新建 `round_case_result` → 与 `plan_case` 双写，必漂移 |
| D5 | 豁免独立成 `quality_gate_waiver`，不写进 rule | 一条豁免只覆盖一条规则、有有效期与审批人；快照内固化豁免 id | 直接改 rule.status=WAIVED → 无法追溯谁在何时放行、到期无法自动失效 |
| D6 | 生产缺陷逃逸在**创建时一次判定并落 `is_escape`** | AC-8.3：改窗口不改历史；边界按秒 | 查询时按当前窗口实时算 → 改配置历史统计全变 |
| D7 | 新增 15 个功能码、不新增角色 | PRD §6 是逐格矩阵；T1 明确测试负责人=计划 owner | 新增 `TEST_LEAD` 角色 → 种子、矩阵、断言全要改 |
| D8 | 导出用 POI + OpenPDF，不引 LibreOffice | 纯 JVM、无外部进程、可进 `platform-unit`；CJK 用 PDF 内置 CID 字体不嵌字体文件 | 复用 r2 CSV 导出 → 不满足 XLSX/PDF；调 `soffice` → 环境不可控 |
| D9 | deliver 只读 `release`/`deploy_order` 两张 req 表（新增 `ReleaseWindowReadModel`） | 观察窗与版本质量统计需要发布单成功时间/回滚结果，跨服务调用会把 72h 判定拆成两处 | 让 req 反向读 deliver 的 defect/report → 双向依赖，边界更差 |

**边界表（谁写谁读）**

| 表 | 写 | 读 |
| --- | --- | --- |
| `test_strategy`…`quality_gate_*`、`test_report*`、`report_signoff`、`test_env` | deliver | deliver、req（仅经 HTTP 读快照/摘要） |
| `release_review` | req | req、deliver（不读） |
| `release.observation_hours/report_validity_days` | req | deliver（只读）、req |
| `deploy_order.quality_gate_id/quality_gate_snapshot_id/report_id` | req | req、deliver（只读窗口/回滚） |
| `defect.source/is_escape/...` | deliver | deliver、req（只经 HTTP 摘要） |

## 4. 数据模型与迁移

### 4.1 迁移文件

`req-service/src/main/resources/db/migration/V5__quality_management.sql`（唯一新增迁移；不得改 V1–V4）。
顺序：① 13 张新表 → ② 5 张表 `ALTER` → ③ 默认策略/轮次/字段回填 → ④ 5 行 `metric_definition` → ⑤ 索引。全部 `CREATE TABLE IF NOT EXISTS` / `ALTER ... ADD COLUMN` 前先查 `information_schema`（用存储过程或 `SET @ddl` 动态执行），保证在 r2 种子库上可重复执行。

### 4.2 新表（13）

```sql
-- 策略：一个 Release 多版本，仅一版 ACTIVE
test_strategy(
  id BIGINT UNSIGNED PK AI, tenant_id BIGINT NOT NULL, release_id BIGINT NOT NULL,
  version_no INT NOT NULL DEFAULT 1, scope_json TEXT, risk_json TEXT, env_ids_json VARCHAR(500),
  round_plan_json TEXT, resource_note VARCHAR(500), owner_id BIGINT,
  status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',            -- DRAFT/ACTIVE/RETIRED
  domain_id BIGINT NOT NULL, vendor_id BIGINT NULL,
  created_at/updated_at DATETIME(3), created_by/updated_by BIGINT,
  UNIQUE uk_strategy (tenant_id, release_id, version_no),
  KEY idx_strategy (tenant_id, release_id, status))

-- 轮次：active 列做“同计划仅一轮进行中”的唯一约束（MySQL 多 NULL 可重复）
test_plan_round(
  id PK AI, tenant_id, plan_id BIGINT NOT NULL, round_no INT NOT NULL,
  round_type VARCHAR(16) NOT NULL,                       -- SMOKE/FEATURE/REGRESSION/DEFECT_VERIFY
  name VARCHAR(200), case_scope_json MEDIUMTEXT, executor_ids_json VARCHAR(500),
  start_at/end_at DATETIME(3), status VARCHAR(12) NOT NULL DEFAULT 'PLANNING',
  conclusion VARCHAR(12) NULL,                           -- PASS/FAIL/CONDITIONAL/CANCELLED
  closed_by BIGINT, closed_at DATETIME(3), defect_ids_json VARCHAR(500),
  exec_total/exec_passed/exec_failed/exec_blocked INT NOT NULL DEFAULT 0,
  active TINYINT NULL, note VARCHAR(1000), created_at/updated_at, created_by/updated_by,
  UNIQUE uk_round (plan_id, round_no), UNIQUE uk_round_active (plan_id, active),
  KEY idx_round (tenant_id, plan_id, status))

plan_assignment(
  id PK AI, tenant_id, plan_id, round_id BIGINT NULL, executor_id BIGINT NOT NULL,
  module_id BIGINT NULL, module_key BIGINT AS (IFNULL(module_id,0)) STORED,
  planned_count INT NOT NULL, due_date DATE NULL, created_at/updated_at, created_by/updated_by,
  UNIQUE uk_assign (plan_id, round_id, executor_id, module_key),
  KEY idx_assign_person (tenant_id, executor_id, due_date),
  KEY idx_assign_plan (tenant_id, plan_id))

plan_change_log(
  id PK AI, tenant_id, plan_id, revision INT NOT NULL, change_type VARCHAR(24) NOT NULL,
  before_json MEDIUMTEXT, after_json MEDIUMTEXT, reason VARCHAR(500) NOT NULL,
  approver_id BIGINT, approved_at DATETIME(3), operator_id BIGINT,
  created_at DATETIME(3), KEY idx_change (tenant_id, plan_id, revision))

test_env(
  id PK AI, tenant_id, name VARCHAR(100) NOT NULL, env_type VARCHAR(16) NOT NULL,
  base_url VARCHAR(300), owner_id BIGINT, status VARCHAR(12) NOT NULL DEFAULT 'IDLE',
  domain_id BIGINT NOT NULL, vendor_id BIGINT NULL, note VARCHAR(500),
  created_at/updated_at, created_by/updated_by,
  UNIQUE uk_env (tenant_id, domain_id, name), KEY idx_env_status (tenant_id, status))

test_report(
  id PK AI, tenant_id, code VARCHAR(24) NOT NULL, scope VARCHAR(8) NOT NULL,  -- PLAN/RELEASE
  plan_id BIGINT NULL, release_id BIGINT NULL, status VARCHAR(12) NOT NULL DEFAULT 'SIGNING',
  current_version INT NOT NULL DEFAULT 1, data_cutoff DATETIME(3) NOT NULL,
  domain_id BIGINT NOT NULL, vendor_id BIGINT NULL, created_at/updated_at, created_by/updated_by,
  UNIQUE uk_report_code (tenant_id, code),
  KEY idx_report_release (tenant_id, release_id, scope), KEY idx_report_plan (tenant_id, plan_id))

test_report_version(
  id PK AI, tenant_id, report_id BIGINT NOT NULL, version_no INT NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL, result_digest CHAR(64) NOT NULL,
  conclusion VARCHAR(12) NOT NULL, pass_rate DECIMAL(6,2) NOT NULL, coverage DECIMAL(6,2) NOT NULL,
  generated_by BIGINT, generated_at DATETIME(3) NOT NULL, signed_at DATETIME(3) NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'SIGNING',          -- SIGNING/SIGNED/REJECTED/SUPERSEDED
  hash CHAR(64) NOT NULL, created_at DATETIME(3),
  UNIQUE uk_report_version (report_id, version_no), KEY idx_version_digest (tenant_id, result_digest))

report_signoff(
  id PK AI, tenant_id, report_id, version_no INT, sign_role VARCHAR(16) NOT NULL,
  signer_id BIGINT NOT NULL, decision VARCHAR(12) NOT NULL, comment VARCHAR(1000),
  signed_at DATETIME(3) NOT NULL, order_no INT NOT NULL,
  UNIQUE uk_signoff (report_id, version_no, sign_role))

quality_gate(
  id PK AI, tenant_id, release_id BIGINT NOT NULL, report_id BIGINT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'NOT_EVALUATED',    -- NOT_EVALUATED/PASS/PASS_WITH_WAIVER/FAIL
  ruleset_version INT NOT NULL DEFAULT 1, evaluated_at DATETIME(3), created_by BIGINT,
  domain_id BIGINT NOT NULL, created_at/updated_at,
  UNIQUE uk_gate_release (tenant_id, release_id))

quality_gate_rule(
  id PK AI, tenant_id, gate_id BIGINT NOT NULL, rule_code VARCHAR(8) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1, threshold_json VARCHAR(500), status VARCHAR(12) NULL,
  evidence_json TEXT, evaluated_at DATETIME(3),
  UNIQUE uk_gate_rule (gate_id, rule_code))

quality_gate_snapshot(                                  -- 不可变，只 INSERT
  id PK AI, tenant_id, gate_id, release_id, deploy_order_id BIGINT NOT NULL,
  report_version_id BIGINT NULL, result VARCHAR(20) NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL, hash CHAR(64) NOT NULL,
  computed_at DATETIME(3) NOT NULL, created_by BIGINT,
  KEY idx_snap_order (tenant_id, deploy_order_id), KEY idx_snap_gate (gate_id, computed_at),
  KEY idx_snap_release (tenant_id, release_id, computed_at))

quality_gate_waiver(
  id PK AI, tenant_id, gate_id, rule_code VARCHAR(8) NOT NULL, release_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL, impact_scope TEXT NOT NULL, expiry_at DATETIME(3) NOT NULL,
  applicant_id BIGINT NOT NULL, approver_id BIGINT NULL, approved_at DATETIME(3) NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'PENDING',          -- PENDING/ACTIVE/REJECTED/EXPIRED/REVOKED
  revoked_by BIGINT NULL, revoked_at DATETIME(3) NULL, revoke_reason VARCHAR(500),
  created_at DATETIME(3),
  KEY idx_waiver_rule (gate_id, rule_code, status), KEY idx_waiver_expiry (status, expiry_at))

release_review(                                          -- req-service 写
  id PK AI, tenant_id, release_id, deploy_order_id BIGINT NULL,
  gate_snapshot_id BIGINT NULL, report_id BIGINT NULL, report_version_no INT NULL,
  meeting_at DATETIME(3) NOT NULL, participants_json VARCHAR(1000), risks_json TEXT,
  open_defects_json TEXT, decision VARCHAR(12) NOT NULL,  -- GO/NO_GO/CONDITIONAL
  conditions VARCHAR(1000), doc_path VARCHAR(300), gate_evaluated TINYINT NOT NULL DEFAULT 0,
  created_by BIGINT, created_at DATETIME(3), confirmed_by BIGINT NULL, confirmed_at DATETIME(3) NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'RECORDED',
  KEY idx_review (tenant_id, release_id, meeting_at))
```

> `quality_gate_snapshot.deploy_order_id` 必须非空：门禁快照只在发布单提交/预评估时产生。`test_report.code` 用既有 `CodeGenerator.next("RPT", tenantId)` 发号（`RPT-0001`）。

### 4.3 存量表变更（5 张）

```sql
ALTER TABLE test_plan
  ADD strategy_id BIGINT NULL, ADD env_id BIGINT NULL,
  ADD entry_criteria_json TEXT NULL, ADD exit_criteria_json TEXT NULL,
  ADD approved_by BIGINT NULL, ADD approved_at DATETIME(3) NULL,
  ADD revision INT NOT NULL DEFAULT 0,
  ADD closed_at DATETIME(3) NULL, ADD close_reason VARCHAR(500) NULL,
  ADD review_comment VARCHAR(1000) NULL;

ALTER TABLE test_execution
  ADD round_id BIGINT NULL, ADD env_id BIGINT NULL,
  ADD KEY idx_exec_round (tenant_id, plan_id, round_id, case_id);

ALTER TABLE deploy_order
  ADD quality_gate_id BIGINT NULL, ADD quality_gate_snapshot_id BIGINT NULL,
  ADD report_id BIGINT NULL, ADD KEY idx_deploy_gate (quality_gate_id);

ALTER TABLE defect
  ADD source VARCHAR(8) NOT NULL DEFAULT 'MANUAL',
  ADD root_cause VARCHAR(16) NULL, ADD found_phase VARCHAR(16) NULL,
  ADD is_escape TINYINT NOT NULL DEFAULT 0, ADD escape_window_end DATETIME(3) NULL,
  ADD KEY idx_defect_escape (tenant_id, release_id, is_escape, found_at);

ALTER TABLE `release`
  ADD observation_hours INT NOT NULL DEFAULT 72,
  ADD report_validity_days INT NOT NULL DEFAULT 7,
  ADD CONSTRAINT ck_release_obs CHECK (observation_hours BETWEEN 24 AND 168),
  ADD CONSTRAINT ck_release_valid CHECK (report_validity_days BETWEEN 1 AND 30);
```

`deploy_order.quality_gate_snapshot_id` 是 PRD 两列之外的**只增**字段：AC-7 要求“发布单能回放到它当时用的那份快照”，仅靠 `quality_gate_id`（Release 级）无法定位。

### 4.4 回填（幂等，按租户）

1. **默认策略**：对每个存在计划的 Release 插入 `test_strategy(version_no=1,status='ACTIVE',scope_json='{"source":"backfill"}',domain_id=release.domain_id)`；已存在则跳过。
2. **计划补字段**：`strategy_id`=该 Release 的 ACTIVE 策略；`revision=0`；`exit_criteria_json` 为空时写默认 `{"items":[{"key":"passRate","op":">=","value":95},{"key":"openP0P1","op":"==","value":0},{"key":"roundClosed","op":"==","value":1}]}`；`entry_criteria_json` 默认 `{"items":[{"key":"strategyActive","op":"==","value":1}]}`。
3. **状态映射**：`RUNNING→IN_PROGRESS`、`DONE→COMPLETED`，其余不变（存量计划不会自动进 PENDING_REVIEW，避免阻断 r2 门禁）。
4. **R1 归集**：每个计划插入 `round_no=1, round_type='FEATURE', status='CLOSED', start_at=plan.created_at, closed_at=now(), note='存量执行归集'`，`exec_*` 由 `test_plan_case` 现况统计；`test_execution.round_id` 回填为对应计划 R1 的 id。已有 round_no=1 的计划跳过。
5. **缺陷来源**：`source='TEST' WHERE test_execution_id IS NOT NULL`，其余 `MANUAL`；`found_phase` 留空。
6. **旧发布单**：`quality_gate_id/quality_gate_snapshot_id/report_id` 保持 NULL → 详情页显式“历史单据（质量门未启用）”，不得伪造 PASS。
7. **指标**：5 行 `metric_definition`，`version=1`、`effective_at=now()`、`owner_id`=租户 PMO（无则平台管理员）。

### 4.5 指标行

| code | 公式 | 主题 |
| --- | --- | --- |
| `FPY` | R1 PASS / R1 已执行（分母 0 → null） | quality |
| `REGRESSION_PASS_RATE` | R2+ PASS / R2+ 已执行 | quality |
| `DEFECT_ESCAPE_RATE` | is_escape=1 / 该版本关联缺陷总数 | quality |
| `POST_RELEASE_DEFECTS_72H` | 窗口内关联该 Release 的缺陷数 | quality |
| `ROLLBACK_RATE` | 回滚发布单 / 已执行发布单 | quality |

`MetricFormulas` 追加 `ratioOrNull(long,long)` 与 5 个静态方法；零分母返回 `null`（与既有 `ratio()` 返回 0.00 区分，FPY 必须显示“—”）。

## 5. 接口设计

### 5.1 约定

- 网关前缀：deliver 全部经 `/api/deliver/**`，req 经 `/api/req/**`；路径下表省略服务前缀。
- 分页统一 `?page=1&size=20`，返回 `PageResponse<T>{items,total,page,size}`；时间 `yyyy-MM-dd HH:mm:ss`（JSON 走已有 Jackson 配置）。
- 错误：`ApiException(status, code, message, detail)` 由既有 `GlobalExceptionHandler` 渲染 `{code,message,details,traceId}`；409 的 `details` 必含可执行数组（`failedRules`/`unmet`/`allowedNext`/`unexecutedCaseIds`）。
- 幂等：会签重复提交、门禁重复提交、种子回填均返回既有结果，不新增行。
- 所有列表/详情读取走 `ScopedJdbc` 且 SQL 带 `/*scope:alias*/`；写走 JPA，`domain_id/vendor_id` 在写入时由 Release 派生。

### 5.2 deliver-service 接口

**测试策略**

| 方法 | 路径 | 请求 | 返回 / 错误 |
| --- | --- | --- | --- |
| GET | `/test-strategies?releaseId=&status=` | — | `Page<StrategyRow>` |
| POST | `/test-strategies` | `{releaseId,scope,risk,envIds,roundPlan,resourceNote,ownerId?}` | `StrategyDetail`；同 Release 新版本自动 `version_no+1`，旧版 RETIRED |
| GET | `/test-strategies/{id}` | — | `StrategyDetail` |
| PUT | `/test-strategies/{id}` | 同 POST（ACTIVE 版只读 → 409 `ILLEGAL_PLAN_TRANSITION`） | `StrategyDetail` |
| POST | `/test-strategies/{id}/activate` | — | `StrategyDetail` |

**计划生命周期 / 变更**

| 方法 | 路径 | 请求 | 返回 / 错误 |
| --- | --- | --- | --- |
| GET | `/test-plans?releaseId=&status=&ownerId=&keyword=` | — | `Page<PlanRow>`（含 `revision/roundNo/passRate/openP0P1`） |
| POST | `/test-plans` | `{releaseId,name,windowStart,windowEnd,strategyId?,envId?,entryCriteria?,exitCriteria?,ownerId?}` | `PlanDetail`；缺 window/name → 400 `PLAN_INCOMPLETE` |
| GET | `/test-plans/{id}` | — | `PlanDetail{status,allowedNext,revision,strategy,env,roundsSummary,criteria,assignmentSummary}` |
| PATCH | `/test-plans/{id}` | `{name?,windowStart?,windowEnd?,envId?,entryCriteria?,exitCriteria?,approverId?,reason?}` | `PlanDetail`；APPROVED 之后无 `reason` → 400 `CHANGE_REASON_REQUIRED`；revision+1 且写 change_log；CLOSED/ARCHIVED → 409 `ILLEGAL_PLAN_TRANSITION` |
| POST | `/test-plans/{id}/submit-review` | — | `PlanDetail`；无用例 → 409 `PLAN_INCOMPLETE` |
| POST | `/test-plans/{id}/approve` | `{comment?}` | `PlanDetail`；非 owner 且非 PMO/MGMT → 403 `PLAN_APPROVE_FORBIDDEN` |
| POST | `/test-plans/{id}/reject` | `{comment}` | `PlanDetail`；comment 必填 |
| POST | `/test-plans/{id}/start` | — | `PlanDetail`；无进行中轮次则建 R1；已是 IN_PROGRESS → 幂等 |
| POST | `/test-plans/{id}/complete` | — | `PlanDetail`；准出未满足 → 409 `PLAN_EXIT_CRITERIA_UNMET` + `unmet[]` |
| POST | `/test-plans/{id}/close` | `{reason?}` | `PlanDetail`；CLOSED 后执行/编辑 → 409 `ILLEGAL_PLAN_TRANSITION`(detail.reason=CLOSED) |
| POST | `/test-plans/{id}/archive` | — | `PlanDetail`（仅 CLOSED） |
| GET | `/test-plans/{id}/summary` | — | 既有 `PlanSummary` + `rounds[]`（分轮/累计两视图） |
| GET | `/test-plans/{id}/progress?roundId=` | — | `{byPerson[],byModule[],overdue[],totals}`（工作台同源） |
| GET | `/test-plans/{id}/change-logs` | — | `Page<ChangeLogRow>` |
| POST | `/test-plans/{id}/cases` | 既有 | 既有；CLOSED → 409 |
| DELETE | `/test-plans/{id}/cases/{caseId}?reason=&approverId=` | — | APPROVED 后缺 reason → 400 `CHANGE_REASON_REQUIRED`；revision+1 |
| POST | `/test-plans/{id}/execute` | 既有 + `{roundId?,envId?}` | 201；无进行中轮次 → 409 `NO_ACTIVE_ROUND`；轮次已关 → 409 `ROUND_CLOSED`；计划非 IN_PROGRESS → 409 `ILLEGAL_PLAN_TRANSITION` |

**轮次 / 分配**

| 方法 | 路径 | 请求 | 返回 / 错误 |
| --- | --- | --- | --- |
| GET | `/test-plans/{id}/rounds` | — | `RoundView[]`（含每轮通过率/覆盖率） |
| POST | `/test-plans/{id}/rounds` | `{roundType,name?,scopeMode:CURRENT\|PREVIOUS_FAILED\|EXPLICIT,caseIds?,executorIds?}` | 201 `RoundView`；已有进行中 → 409 `ROUND_IN_PROGRESS` |
| GET | `/test-plans/{id}/rounds/{roundId}` | — | `RoundDetail{cases[],counts}` |
| POST | `/test-plans/{id}/rounds/{roundId}/close` | `{conclusion,note?}` | `RoundView`；有 NOT_RUN → 409 `ROUND_HAS_UNEXECUTED`+`unexecutedCaseIds`；已关 → 409 `ROUND_CLOSED`；上调结论 → 409 `CONCLUSION_UPGRADE_FORBIDDEN` |
| GET | `/test-plans/{id}/assignments?roundId=` | — | `AssignmentView[]` |
| PUT | `/test-plans/{id}/assignments` | `{roundId?,rows:[{executorId,moduleId?,plannedCount,dueDate?}]}` | 全量替换；合计≠用例数 → 400 `ASSIGNMENT_MISMATCH`+`{diff,unassigned}` |

**环境**

| 方法 | 路径 | 请求 | 返回 / 错误 |
| --- | --- | --- | --- |
| GET | `/test-envs?status=&keyword=` | — | `Page<EnvRow>` |
| POST | `/test-envs` | `{name,envType,baseUrl,ownerId?,note?}` | `EnvRow`；重名 409 `ENV_IN_USE`(detail.reason=NAME_TAKEN) |
| PUT | `/test-envs/{id}` | 同上 + `status?` | `EnvRow` |
| DELETE | `/test-envs/{id}` | — | 被进行中计划引用 → 409 `ENV_IN_USE`+`planIds` |

**报告**

| 方法 | 路径 | 请求 | 返回 / 错误 |
| --- | --- | --- | --- |
| POST | `/test-reports` | `{scope,planId?,releaseId?,note?}` | 201 `ReportDetail`；RELEASE 无已批准计划 → 409 `REPORT_NO_PLAN`；plan 非 APPROVED+ → 409 `PLAN_NOT_APPROVED` |
| GET | `/test-reports?releaseId=&planId=&status=` | — | `Page<ReportRow>` |
| GET | `/test-reports/{id}` | — | `ReportDetail{currentVersion,versions[],signoffProgress}` |
| GET | `/test-reports/{id}/versions` | — | `ReportVersionRow[]` |
| GET | `/test-reports/{id}/versions/{n}` | — | `ReportVersionDetail{snapshot 8 段}` |
| POST | `/test-reports/{id}/versions/{n}/signoff` | `{decision:'APPROVE'\|'REJECT',comment?}` | `SignoffProgress`；越序 409 `SIGNOFF_ORDER_VIOLATION`；重复签幂等；结论上调 409 `CONCLUSION_UPGRADE_FORBIDDEN` |
| GET | `/test-reports/{id}/export?format=xlsx\|pdf&version=N` | — | 二进制流，`Content-Disposition` 文件名 `RPT-xxxx-vN.xlsx/.pdf`；未签完的版本可导出但加“未生效”水印；审计单独一条 |
| GET | `/test-reports/{id}/diff?from=1&to=2` | — | `{passRate,coverage,conclusion,addedFails[],closedDefects[]}` |

**质量门 / 豁免**

| 方法 | 路径 | 请求 | 返回 / 错误 |
| --- | --- | --- | --- |
| GET | `/quality-gates?releaseId=&status=` | — | `Page<GateRow>`（含 activeWaiverCount） |
| GET | `/quality-gates/{releaseId}` | — | `GateDetail{rules[],snapshots[],waivers[]}`；无配置时返回默认规则集（不落库） |
| PUT | `/quality-gates/{releaseId}` | `{rules:[{code,enabled,threshold?}]}` | `GateDetail`；QG-04 不可 disabled → 400 `RULE_NOT_WAIVABLE` |
| GET | `/releases/{releaseId}/quality-gate` | — | 同上（PRD 路径别名，同一方法） |
| POST | `/quality-gates/{releaseId}/evaluate` | `{deployOrderId,requirementIds[]}` | `GateSnapshot`（**不固化**，`snapshotId=0`，供页面预评估） |
| POST | `/quality-gates/snapshots/{snapshotId}/replay` | — | `GateSnapshot` + `diff{changedRules[]}`（只读，不写库） |
| GET | `/quality-gates/snapshots/{snapshotId}` | — | `GateSnapshot` |
| GET | `/quality-gates/waivers?status=&releaseId=` | — | `Page<WaiverRow>` |
| POST | `/quality-gates/waivers` | `{ruleCode,releaseId,reason,impactScope,expiryDays}` | 201 `WaiverRow`；字段缺 → 400 `WAIVER_FIELDS_REQUIRED`；QG-04 → 400 `RULE_NOT_WAIVABLE`；期限不在 1–30 → 400 `WAIVER_EXPIRY_INVALID`；一次多规则 → 400 `WAIVER_RULE_SINGLE_ONLY` |
| POST | `/quality-gates/waivers/{id}/approve` | `{comment?}` | `WaiverRow`；自批（非 PMO/管理员）→ 403 `SCOPE_DENIED` |
| POST | `/quality-gates/waivers/{id}/reject` | `{comment}` | `WaiverRow` |
| POST | `/quality-gates/waivers/{id}/revoke` | `{reason}` | `WaiverRow`；已发快照不变 |

**指标 / 工作台 / 版本质量**

| 方法 | 路径 | 请求 | 返回 |
| --- | --- | --- | --- |
| GET | `/metrics/quality?releaseId=&domainId=&vendorId=&from=&to=` | — | `{metrics:[{code,name,value,unit,version,drillQuery}],fpy,regressionPassRate,escapeRate,postReleaseDefects72h,rollbackRate}`；分母 0 → `value:null` |
| GET | `/workbench/my-cases?page=&size=&overdue=` | — | `Page<MyCaseRow>`（分配给我的 + 我负责计划中未分配的用例） |
| GET | `/releases/{releaseId}/quality` | — | `ReleaseQuality{gateHistory[],reports[],openP0P1[],escapeDefects[],counts{fpy,escape,postRelease,rollback}}`（与缺陷/发布单列表同筛选） |

**内部端点**（`InternalTokenFilter`，只允许服务间）

| 方法 | 路径 | 请求 | 返回 |
| --- | --- | --- | --- |
| POST | `/internal/quality-gates/{releaseId}/evaluate` | `{deployOrderId,requirementIds[]}` | `GateSnapshot`（固化；同单同 hash 复用） |
| GET | `/internal/quality-gates/{releaseId}/latest` | — | `GateSnapshot`（可能 404） |
| GET | `/internal/quality/summary/{releaseId}` | — | `QualitySummary`（供 req 生成评审/版本质量） |

### 5.3 req-service 接口

| 方法 | 路径 | 请求 | 返回 / 错误 |
| --- | --- | --- | --- |
| POST | `/deploy-orders/{id}/evaluate` | — | `AdmissionSnapshot`（6 项）；门禁超时 → 第 6 项 `FAIL` detail `GATE_UNAVAILABLE`（fail-closed） |
| GET | `/deploy-orders/{id}/admission` | — | 同上（PRD 别名） |
| POST | `/deploy-orders/{id}/submit` | `{confirmNoGo?}` | `SubmitResult`；门禁 FAIL → 409 `QUALITY_GATE_FAILED`+`failedRules[]`+`snapshotId`；最新评审 NO_GO 且调用者非 PMO 或未 `confirmNoGo` → 409 `NO_GO_CONFIRM_REQUIRED` |
| POST | `/deploy-orders/{id}/force-release` | `{reason}` | **恒 409 `FORCE_RELEASE_RETIRED`**（不校验 reason、不写库、写审计） |
| GET | `/releases/{id}/reviews` | — | `Page<ReviewRow>` |
| POST | `/releases/{id}/reviews` | `{meetingAt,participants,decision,conditions?,risks?,openDefects?,deployOrderId?,reportId?,reportVersionNo?}` | 201 `ReviewDetail`（自动取最新 gate snapshot；门禁未评估 → `gateEvaluated=false`，页面标“门禁未评估”）；CONDITIONAL 无 conditions → 400 `WAIVER_FIELDS_REQUIRED`(detail.field=conditions) |
| GET | `/releases/{id}/reviews/{reviewId}` | — | `ReviewDetail` |
| POST | `/releases/{id}/reviews/{reviewId}/confirm` | `{note?}` | `ReviewDetail`；仅 PMO/管理员 |
| PATCH | `/releases/{id}/quality-settings` | `{observationHours?,reportValidityDays?}` | `ReleaseDetail`；越界 400 `WAIVER_EXPIRY_INVALID`(detail.range) |

> 新增错误码 `NO_GO_CONFIRM_REQUIRED`（409）与 `GATE_UNAVAILABLE`（detail 标记，不单独抛）：PRD §4.15 未列，但 T6 的 PMO 二次确认与门禁超时 fail-closed 需要可断言的机器码，QA 按本文断言。

### 5.4 权限码与路由映射

`PermissionMatrix.FUNCTIONS` 追加（顺序固定，追加在 `notification:read` 之后）：

```text
strategy:manage  plan:edit  plan:approve  plan:execute  plan:assign  env:manage
report:generate  report:sign  gate:view  waiver:apply  waiver:approve
review:manage  proddefect:report  qualitymetric:view  report:export
```

矩阵取值按 PRD §6 逐格：`✓→ALLOW`、`⚪→RESTRICTED`、`—→DENY`（例：`waiver:approve` 仅 PMO/PLATFORM_ADMIN 为 ALLOW；`qualitymetric:view` 对 BUSINESS/SUB_BUSINESS_LEADER/DEV 为 RESTRICTED，其余按表）。

`PermissionRouteTable.RULES` 在既有 `^/test-plans`、`^/releases`、`^/deploy-orders` 兜底**之前**插入（顺序即优先级）：

```java
// 质量域：细粒度动作必须先于兜底规则
rule(null,   "^/test-plans/\\d+/approve$",                              false, "plan:approve"),
rule(null,   "^/test-plans/\\d+/(submit-review|reject|start|complete|close|archive)$", false, "plan:edit"),
rule(null,   "^/test-plans/\\d+/assignments(/.*)?$",                    false, "plan:assign"),
rule(null,   "^/test-plans/\\d+/execute$",                              false, "plan:execute"),
rule(null,   "^/test-strategies(/.*)?$",                                false, "strategy:manage"),
rule("POST", "^/test-envs(/.*)?$",                                      false, "env:manage"),
rule("PUT",  "^/test-envs(/.*)?$",                                      false, "env:manage"),
rule("DELETE","^/test-envs(/.*)?$",                                     false, "env:manage"),
rule("POST", "^/test-reports(/.*)?$",                                   false, "report:generate"),
rule(null,   "^/test-reports/\\d+/versions/\\d+/signoff$",               false, "report:sign"),
rule(null,   "^/test-reports/\\d+/export$",                             false, "report:export"),
rule("PUT",  "^/(quality-gates|releases/\\d+/quality-gate)$",            false, "plan:manage"),
rule("POST", "^/quality-gates/waivers$",                                false, "waiver:apply"),
rule(null,   "^/quality-gates/waivers/\\d+/(approve|reject|revoke)$",    false, "waiver:approve"),
rule(null,   "^/quality-gates(/.*)?$",                                  false, "gate:view"),
rule(null,   "^/metrics/quality$",                                      false, "qualitymetric:view"),
rule(null,   "^/releases/\\d+/quality$",                                false, "qualitymetric:view"),
// req-service 追加（同样在 ^/releases 兜底之前）
rule(null,   "^/releases/\\d+/reviews(/.*)?$",                          false, "review:manage"),
rule(null,   "^/releases/\\d+/quality-settings$",                       false, "review:manage"),
```

- `^/releases/{id}/quality-gate` 的 GET 命中 `^/quality-gates` 规则前需单独一条 `rule("GET", "^/releases/\\d+/quality-gate$", false, "gate:view")`。
- `/workbench/my-cases`、`/test-plans` GET、`/test-reports` GET 不挂功能码：任何已认证用户可见，数据由 `ScopeSql` 收窄。
- 生产缺陷提报在服务层判：`source='PROD'` 且 `PermissionMatrix.accessOf("proddefect:report", roles)==DENY` → 403 `SCOPE_DENIED`（路由无法按 body 分支）。
- 计划批准 owner 校验、豁免自批校验、会签角色校验都在 service 层，矩阵只做入口闸。

## 6. 后端实现要点

### 6.1 包结构（新增/改动）

```
platform-contract/src/main/java/com/example/platform/contract/
  quality/QualityGatePort.java        quality/GateSnapshot.java
  quality/GateRuleResult.java         quality/QualitySummary.java
  quality/QualityCodes.java
  StateTables.java（改：新增 PLAN/ROUND/REPORT/GATE/WAIVER/REVIEW 状态表）
  MetricFormulas.java（改：ratioOrNull + 5 个质量指标方法）
  PermissionMatrix.java（改：+15 功能码）

deliver-service/src/main/java/com/example/deliver/
  quality/aggregate/QualityAggregator.java   QualityAggregate.java  PlanAggregate.java
  quality/aggregate/QualityDigest.java       ExitCriteriaEvaluator.java
  quality/strategy/TestStrategyEntity.java   TestStrategyRepository.java
  quality/strategy/TestStrategyService.java  TestStrategyController.java  TestStrategyViews.java
  quality/plan/TestPlanRoundEntity.java      TestPlanRoundRepository.java
  quality/plan/PlanAssignmentEntity.java     PlanAssignmentRepository.java
  quality/plan/PlanChangeLogEntity.java      PlanChangeLogRepository.java
  quality/plan/PlanLifecycle.java            PlanLifecycleService.java
  quality/plan/PlanLifecycleController.java  PlanChangeController.java
  quality/plan/RoundService.java             RoundController.java
  quality/plan/AssignmentService.java        AssignmentController.java
  quality/plan/PlanViews.java
  quality/env/TestEnvEntity.java … TestEnvController.java
  quality/report/TestReportEntity.java       TestReportVersionEntity.java
  quality/report/ReportSignoffEntity.java    *Repository.java
  quality/report/ReportService.java          SignoffService.java
  quality/report/ReportExportService.java    ReportController.java  ReportViews.java
  quality/gate/QualityGateEntity.java        QualityGateRuleEntity.java
  quality/gate/QualityGateSnapshotEntity.java QualityGateWaiverEntity.java  *Repository.java
  quality/gate/GateRuleset.java              GateEvaluator.java
  quality/gate/QualityGateService.java       WaiverService.java
  quality/gate/QualityGateController.java    QualityGateViews.java
  quality/metric/QualityMetricQuery.java     QualityMetricService.java
  quality/metric/QualityMetricController.java QualityMetricViews.java
  quality/quality/ReleaseQualityService.java ReleaseQualityController.java
  quality/workbench/WorkbenchController.java WorkbenchQuery.java
  quality/internal/QualityInternalController.java
  reqread/ReleaseWindowReadModel.java（新，只读 release + deploy_order）
  defect/DefectService.java（改：逃逸判定）DefectEntity.java（改）DefectViews.java（改）

req-service/src/main/java/com/example/req/
  review/ReleaseReviewEntity.java  ReleaseReviewRepository.java
  review/ReleaseReviewService.java ReleaseReviewController.java ReleaseReviewViews.java
  integration/DeliverQualityGateClient.java
  deploy/AdmissionEvaluator.java（改）AdmissionPolicy.java（改）DeployOrderService.java（改）
  deploy/DeployOrderEntity.java（改）DeployViews.java（改）DeployOrderController.java（改）
  release/ReleaseEntity.java（改）ReleaseController.java（改）ReleaseQualitySettings*.java
```

### 6.2 关键类与签名（dev 按此逐字实现）

```java
// platform-contract
public interface QualityGatePort {
    GateSnapshot evaluate(long releaseId, long deployOrderId, List<Long> requirementIds);
    GateSnapshot snapshot(long snapshotId);
}
public record GateSnapshot(long snapshotId, long releaseId, long deployOrderId, Long reportVersionId,
                           String result, String hash, java.time.Instant computedAt,
                           List<GateRuleResult> rules, List<Long> openP0P1) {}
public record GateRuleResult(String code, String status, boolean required,
                             String actual, String threshold, String fixHint) {}
public record QualitySummary(long releaseId, long escapeCount, long postReleaseDefects,
                             long openP0P1, long rollbackCount, long executedOrders,
                             java.math.BigDecimal fpy, java.util.List<Long> gateSnapshotIds,
                             java.util.List<Long> reportVersionIds) {}

// deliver
public QualityAggregate aggregateRelease(long releaseId);
public PlanAggregate aggregatePlan(long planId);
public String digest(long releaseId);                    // PRD 算法，唯一实现
public List<UnmetCriterion> evaluate(String exitCriteriaJson, QualityAggregate agg);
public PlanDetail action(long planId, PlanAction action, String reason, String comment);
public RoundView open(long planId, RoundService.OpenRound cmd);
public RoundView close(long planId, long roundId, RoundService.CloseRound cmd);
public AssignmentView replace(long planId, Long roundId, List<AssignmentService.Row> rows);
public ReportDetail generate(ReportService.Generate cmd);
public SignoffProgress sign(long reportId, int versionNo, SignoffService.Command cmd);
public byte[] xlsx(long reportId, int versionNo);
public byte[] pdf(long reportId, int versionNo);
public GateSnapshot evaluate(long releaseId, long deployOrderId, List<Long> requirementIds);
public GateDetail configure(long releaseId, List<GateRuleset.RuleConfig> rules);
public WaiverRow apply(WaiverService.Apply cmd);
public List<QualityGateWaiverEntity> activeFor(long gateId, String ruleCode, Instant at);
public QualityMetrics quality(QualityMetricQuery q);
public ReleaseQuality quality(long releaseId);
```

### 6.3 计划状态机

`StateTables.PLAN`（单一实现，供 controller 返回 `allowedNext`、DB 回填校验、前端 stepper 共用）：

```
DRAFT →(submit-review) PENDING_REVIEW →(approve) APPROVED →(start) IN_PROGRESS
      →(complete) COMPLETED →(close) CLOSED →(archive) ARCHIVED
PENDING_REVIEW →(reject) DRAFT
```

- 每次动作 `SELECT ... FOR UPDATE` 锁 `test_plan` 行，重读状态后再判；非法 → 409 `ILLEGAL_PLAN_TRANSITION` + `details.{from,action,allowedNext}`。
- `approve`：`plan.ownerId == me` 或角色含 PMO/MGMT/PLATFORM_ADMIN；否则 403 `PLAN_APPROVE_FORBIDDEN`。
- `complete`：先跑 `ExitCriteriaEvaluator`；未满足 → 409 `PLAN_EXIT_CRITERIA_UNMET`，`unmet[{key,label,actual,expected,fixHint}]`；已有进行中轮次 → 409 `ROUND_IN_PROGRESS`（先关轮次）。
- `close`：写 `closed_at/close_reason`；`CLOSED` 之后一切写操作（执行、改用例、变更、新建轮次）→ 409 `ILLEGAL_PLAN_TRANSITION`，`detail.reason="CLOSED"`。
- 变更（PATCH/删用例）：APPROVED 及之后必须 `reason` + 审批人；同事务写 `plan_change_log(before_json,after_json,reason,approver_id)` 并 `revision+1`；报告因此 `QG-02 REPORT_STALE`。

### 6.4 轮次

- 开轮：`active=1`（唯一键 `(plan_id, active)` 兜住并发），`round_no = max+1`，`case_scope_json` 固化本轮用例 id + `case_version`。
- 作用域：`CURRENT`=计划全部用例；`PREVIOUS_FAILED`=上一轮 `FAIL ∪ BLOCKED`（无上一轮 → 409 `NO_ACTIVE_ROUND` 语义改为 `ROUND_*`，用 `ILLEGAL_PLAN_TRANSITION`）；`EXPLICIT`=传入 `caseIds`（必须属于本计划，否则 400 `ASSIGNMENT_MISMATCH`）。
- 执行绑定：`POST execute` 无 `roundId` 时取 `active=1` 的轮次；无 → 409 `NO_ACTIVE_ROUND`；指定已关轮次 → 409 `ROUND_CLOSED`；写 `test_execution(round_id,env_id)`。
- 关闭：先校验该轮范围内 `plan_case.result='NOT_RUN'` 为空；把 `exec_*` 落列（快照，历史不随新执行变化）；`active=NULL`；结论只能 ≤ 数据建议值（PASS>CONDITIONAL>FAIL；CANCELLED 独立）。
- 分轮视图从 `test_execution` 取“每 (round,case) 最新一条”（`ROW_NUMBER() OVER (PARTITION BY round_id, case_id ORDER BY executed_at DESC, id DESC)`），累计视图直接用 `plan_case`。

### 6.5 聚合与 digest（唯一实现）

```java
QualityAggregate{ long releaseId, long totalPlanned, long executed, long passed, long failed,
                  long blocked, long notRun, BigDecimal passRate, BigDecimal coverage,
                  List<PlanAggregate> plans, Map<Long,Integer> planRevisions }
PlanAggregate{ long planId, String name, String status, int revision, long total, long executed,
               long passed, long failed, long blocked, BigDecimal passRate, BigDecimal coverage }
```

- `passRate = passed / totalPlanned ×100`（`MetricFormulas.passRate`，2 位 HALF_UP）——**分母是计划用例总数**，不是已执行数；`coverage = executed / totalPlanned`。
- `digest(releaseId)`：取该 Release 下纳入的计划（scope 决定）的 `test_plan_case`，按 `case_id` 升序拼 `caseId + ":" + caseVersion + ":" + result`，`\n` 连接，UTF-8，`MessageDigest("SHA-256")` 小写 hex。计划/用例增删、`case_version` 变化、结果变化都会变 digest。
- 报告生成、`QG-02`、`QG-03` 三处都调用 `aggregateRelease` + `digest`，禁止另写 SQL；AC-3.6 的“整数与两位小数完全一致”由同一返回对象保证。
- 只读一次 `plan_case`（`WHERE plan_id IN (...)`，走 `idx_plan_case_result`），在 Java 里分组，避免 N 次查询；1 万用例一次拉取 ≈ 10k 行，P95 预算 500ms 内。

### 6.6 报告与会签

- 生成：`scope=RELEASE` 取该 Release 全部 `APPROVED/IN_PROGRESS/COMPLETED/CLOSED` 计划；`PENDING_REVIEW/DRAFT` 计划列入 `snapshot.excludedPlans`（驱动 QG-01 FAIL）；`scope=PLAN` 只取一个计划且必须已批准。
- `snapshot_json` 固定 8 段：`scope` / `execution`（结果×模块×执行人，含 `caseIds[]`、`executionIds[]`）/ `rates` / `defects`（按日、严重级、来源，含 `defectIds[]`）/ `residual`（遗留缺陷+豁免）/ `env` / `riskConclusion` / `signoff`。明细 id 全量存（上限 20000，超出写 `truncated:true` 并只存计数）。
- 结论计算：未关闭 P0/P1>0 或 必需用例非 100% → FAIL；有遗留缺陷/豁免但 P0/P1=0 → CONDITIONAL；全满足 → PASS。生成人可取更低结论，但会签只能继续下调。
- 版本：`version_no=max+1`，状态 SIGNING；`report.current_version` 同步；**新版本三签完成后**，同报告内旧的 SIGNED 版本置 SUPERSEDED（旧数据不动）。
- 会签：`order_no` 1=TEST_LEAD（=计划 owner）/2=PRODUCT/3=PMO；`step = 已 APPROVE 数+1`，`order_no != step` → 409 `SIGNOFF_ORDER_VIOLATION` + `details.{expectedRole,actualRole}`；同一签署人重复 APPROVE → 200 返回现状（不新增行）；REJECT → 版本 REJECTED、报告 REJECTED，下次生成 v+1。
- 有效期：`signed_at + release.report_validity_days`；`GET` 返回 `expiresAt`。
- 导出：XLSX 用 `SXSSFWorkbook`（>2000 行时 `setRowAccessWindowSize(500)`），8 个 sheet，首行与页脚写水印；PDF 用 `Document` + `PdfWriter`，`BaseFont.createFont("STSong-Light","UniGB-UCS2-H",BaseFont.NOT_EMBEDDED)`，`PdfPageEventHelper.onEndPage` 画 45° 半透明水印 `内部资料 · <tenant> · <code> · v<n> · <generatedAt>`；导出前按调用者 `domain/vendor` 过滤，越权 403 `SCOPE_DENIED`；审计 `report.export` 单独一条。

### 6.7 质量门

规则集 `GateRuleset`（默认阈值可被 `quality_gate_rule.threshold_json` 覆盖）：

| code | 判定（全部数据来自 `QualityAggregator` / 报告 / 缺陷） | 默认 | 可豁免 |
| --- | --- | --- | --- |
| QG-01 | 发布单需求逐条被 ≥1 个已批准计划覆盖，且存在已会签 RELEASE 报告 | 覆盖 100% | 否 |
| QG-02 | 报告存在、SIGNED、未过期、结论可接受（PASS；CONDITIONAL 需 PMO 已批豁免）、`result_digest` 与当前一致、`planRevisions` 一致 | 7 天 | 否 |
| QG-03 | `passRate ≥ 95`（`gate_mode=STRICT` 时要求 `passed==totalPlanned`） | 95% / 100% | 是（PMO） |
| QG-04 | 未关闭 P0/P1 = 0 | 0 | **否（硬红线）** |
| QG-05 | Sonar/制品/构建（本轮 `enabled=0`） | — | — |

- 求值上下文一次装载：计划+用例（一次查询）、报告当前版本、缺陷 P0/P1、有效豁免、`deploy_order_requirement`（由 req 传入 `requirementIds`）。
- 豁免生效条件：`status='ACTIVE' AND expiry_at > now()`；过期行惰性置 EXPIRED（同一事务，不阻塞求值）。一条豁免只作用于 `rule_code` 对应规则。
- 结果汇总：任一 `FAIL` → `FAIL`（即使别的规则 WAIVED，AC-6.4）；否则有 `WAIVED` → `PASS_WITH_WAIVER`；否则 `PASS`（SKIPPED 不计）。
- 快照 JSON 至少：`result`、`rules[{code,status,required,actual,threshold,evidence,fixHint}]`、`report{id,version,conclusion,signedAt,resultDigest}`、`openP0P1[]`、`waivers[{id,ruleCode,approverId,expiryAt}]`、`computedAt`、`deployOrderId`；`hash` = 快照 JSON 的 SHA-256。
- 幂等：同 `deploy_order_id` 且同 `hash` 已存在 → 直接返回旧快照（“重复提交复用 snapshotId”）；否则 INSERT 新快照并更新 `quality_gate` 与 `quality_gate_rule` 当前态。
- `replay`：只解析 `snapshot_json`，用当前权限再过滤一次可见性；返回 `diff.changedRules`（快照 vs 现在重算），不写库。

### 6.8 准入第 6 项（req）

- `AdmissionPolicy` 新增常量 `QUALITY_GATE = "QUALITY_GATE"`，6 项顺序固定：原 5 项在前、质量门第 6；`sort()` 保证稳定。
- `AdmissionEvaluator.facts(order)` 增加：`GateSnapshot gate = qualityGateClient.evaluate(order.releaseId, order.id, requirementIds)`（预算 500ms，与 `ADMISSION_BUDGET` 共用）；异常/超时 → item `FAIL`，detail `{code:"GATE_UNAVAILABLE",fixHint:"质量门服务不可用，请稍后重试"}`（fail-closed，不允许“查不到就通过”）。
- 第 6 项 `PASS` 条件：`result in (PASS, PASS_WITH_WAIVER)`；`FAIL` 时 `detail.failedRules` 原样透传（规则码、阈值、实际值、证据引用、修复指引）。
- `submit`：`evaluateAndStore` → 第 6 项 FAIL → 409 `QUALITY_GATE_FAILED`（`details.snapshotId/failedRules`），状态不变、无 PASS 快照；通过则写 `deploy_order.quality_gate_id`、`quality_gate_snapshot_id`、`report_id` 后 `startApproval`。
- `forceRelease`：保留签名与路由，直接 409 `FORCE_RELEASE_RETIRED`（`details.hint="强制放行已退役，请走质量门或规则级豁免"`），不写 forced 字段、不改状态，审计 `deploy.force-release.retired`。
- 历史单：`admission_result_json` 只有 5 项 → 视图补 `qualityGate: {status:"NOT_APPLICABLE",legacy:true}`，页面显示“质量门未启用（历史单据）”。
- 跨代兼容：`AdmissionPolicy.signature` 加入第 6 项 hash；旧签名解析走 `parse` 的缺省分支。

### 6.9 生产缺陷逃逸与指标

- `DefectService.create`：`source` 缺省 `MANUAL`；执行失败自动建缺陷写 `TEST`；`PROD` 且 `releaseId==null` → 400 `PROD_DEFECT_RELEASE_REQUIRED`。
- 窗口：`ReleaseWindowReadModel.window(releaseId)` 返回最近一次 `execution_result='SUCCESS'` 的 `ended_at` 与 `release.observation_hours`；`found_at < ended_at + hours` → `is_escape=1`，`escape_window_end = ended_at + hours`（边界按秒，`T+72h00m` 为 false）；无成功发布 → `is_escape=0`、窗口 NULL。
- 判定只发生在 INSERT；`observation_hours` 后续修改不影响已存行（AC-8.3）。
- 版本质量统计 `ReleaseQualityService` 一次 SQL 取：门禁快照历史、报告版本、未关闭 P0/P1、`is_escape` 计数、窗口内缺陷数、`ROLLED_BACK` 发布单数、已执行发布单数；比率走 `MetricFormulas`，与缺陷列表/发布单列表同 `WHERE`。
- 5 项指标：`QualityMetricQuery` 输出原始计数，`QualityMetricService` 调 `MetricFormulas` 出值；`metric_definition` 提供 name/formula/version/owner；`value=null` 时前端显 `—`。看板/报告/门禁/版本质量均调本服务，禁止在 controller 里二次除。

### 6.10 事务、锁、审计、通知

- 写路径 `@Transactional`（JPA），读路径 `ScopedJdbc`；两者不在同一方法里混用大事务。
- 行锁：计划动作锁 `test_plan`；轮次开关锁 `test_plan_round`；报告版本号用 `SELECT MAX(version_no) ... FOR UPDATE` + 唯一键兜底重试一次；门禁用 `(deploy_order_id,hash)` 去重查询。
- 审计（`AuditService.record`）：计划流转/变更/轮次/分配/环境/报告生成·签署·驳回·导出/门禁求值/豁免全流程/评审/逃逸标记/指标版本。
- 通知（`NotificationWriter.send`）：计划提交/批准/驳回、报告待签/签署完成/驳回、门禁失败、豁免申请/审批/到期、评审生成、逃逸缺陷；在 `afterCommit` 发送，失败不回滚业务。
- 导出临时文件不落项目目录：全部 `byte[]` 直出响应，避免 `var/` 之外的落盘（PRD 工程约束）。

### 6.11 性能与索引

| 场景 | 预算 | 手段 |
| --- | --- | --- |
| 门禁 1 万用例 | P95 ≤500ms | 一次 `plan_case` 聚合（`idx_plan_case_result (plan_id,result)`）+ Java 计算 digest；缺陷/报告各一次索引查询 |
| 报告 5000 用例 | ≤5s | 单次聚合 + 一次 JSON 序列化；明细 id 截断上限 20000 |
| XLSX / PDF | ≤10s / ≤15s | SXSSF 流式；PDF 逐段写、不重排全表 |
| 进度/工作台 | ≤1s | `plan_assignment(executor_id,due_date)` + `plan_case(plan_id,executor_id,result)` |
| 快照回放 | ≤300ms | 只读 `quality_gate_snapshot` 单行 |
| digest | ≤300ms | 同上，`idx_version_digest` |

## 7. 前端实现

### 7.1 路由与页面（`web/src/router/routes/quality.routes.ts` 新增，glob 自动挂载）

| 路径 | name | 组件 | 权限 meta | 说明 |
| --- | --- | --- | --- | --- |
| `/test-strategies` | `test-strategies` | `views/quality/TestStrategiesView.vue` | `strategy:manage` | 列表 + 抽屉编辑 + 版本切换 |
| `/test-reports` | `test-reports` | `views/quality/TestReportsView.vue` | — | 报告中心（按版本/计划筛选） |
| `/test-reports/:id(\\d+)` | `test-report-detail` | `views/quality/TestReportDetailView.vue` | — | 8 段 Reader + 版本条 + 会签 |
| `/quality-gates` | `quality-gates` | `views/quality/QualityGatesView.vue` | `gate:view` | 门禁总览 + 豁免待办 |
| `/quality-gates/:releaseId(\\d+)` | `quality-gate-detail` | `views/quality/QualityGateDetailView.vue` | `gate:view` | 规则卡 + 快照时间线 + 豁免 |
| `/test-envs` | `test-envs` | `views/quality/TestEnvsView.vue` | `env:manage` | 台账 |
| `/quality-metrics` | `quality-metrics` | `views/quality/QualityMetricsView.vue` | `qualitymetric:view` | 5 卡 + 切片 + 下钻 |
| `/releases/:id(\\d+)/go-no-go` | `release-go-no-go` | `views/release/GoNoGoView.vue` | `review:manage` | 一屏评审 + 生成记录 |

改造：`deliver.routes.ts` 增加 `/test-plans/:id(\\d+)` → `views/deliver/TestPlanDetailView.vue`（6 tab，`?tab=` 深链）；`/workbench` → `views/deliver/WorkbenchView.vue`。
`layouts/nav.ts` 增加 6 个入口（策略/报告/质量门/环境/指标/工作台），沿用“路由注册即显隐”。

### 7.2 页面与组件

- **计划详情（6 tab）**：概览（`LifecycleStepper` + 准出卡 + `AssignmentSummary`）、用例、轮次（`RoundSwitcher` + 关闭抽屉）、分配、变更日志、报告（复用 `ReportReader` 摘要）。状态只走动作按钮，无状态下拉；变更/分配/豁免用抽屉。
- **报告详情**：`ReportReader` 固定 8 段；每段数字可点开明细抽屉（服务端分页 50）；`SignoffProgress` 严格顺序，仅当前角色出现“签署”；驳回二次确认。
- **门禁**：`QualityGatePanel` 5 条 `QualityRuleCard`（`PASS/FAIL/WAIVED/SKIPPED`、actual/threshold/证据 `EvidenceRef`、`fixHint`）；`WaiverBadge`/`WaiverDrawer`；快照时间线可 `replay`；QG-04 不出现“申请豁免”。
- **发布单详情（改造）**：准入 6 项，第 6 项展开规则卡与 `failedRules`；移除强制放行入口，历史强制放行只读条纹徽标；`DigestChip` 显示报告 digest 前缀与有效性。
- **工作台**：我的待执行（分配给我 + 我负责计划中未分配）、超期标红、按截止日排序。
- **Go/No-Go**：门禁逐规则 + 报告结论 + 未关闭 P0/P1 + 风险 + 观察窗；生成抽屉；NO-GO 顶部红条与“提交需 PMO 二次确认”提示。

### 7.3 API 层与类型

- 新增 `web/src/api/quality.ts`：`strategyApi / planApi / roundApi / assignmentApi / envApi / reportApi / gateApi / waiverApi / metricApi / workbenchApi / releaseQualityApi`，全部 `prefix='/api/deliver'`；导出复用 `http.download(url, filename)`。
- `web/src/api/req.ts` 追加 `reviewApi`（`/releases/{id}/reviews`）、`releaseApi.updateQualitySettings`。
- `web/src/api/types.ts` 追加 `Strategy* / PlanDetail / RoundView / AssignmentRow / EnvRow / ReportDetail / ReportVersion* / SignoffProgress / GateDetail / GateRule / GateSnapshot / WaiverRow / ReviewDetail / QualityMetrics / MyCaseRow`（与 §5 返回结构逐字段对齐，snake→camel 由既有序列化约定决定）。
- `web/src/stores/dict.ts` 新增 `StatusKind`：`strategy | plan | planRound | report | signoff | gate | rule | waiver | review | env`，补 label/tone/ACTION_LABELS；`DANGER_ACTIONS` 加 `force-release`（显示为“已退役”只读）。
- 全局 store 只加 `useQualityRefresh`（一个 `ref<number>` 版本号，页面保存后 `bump()` 触发同页重取），不引入新的领域 store；筛选/分页/抽屉状态留在页面 `reactive`。

### 7.4 状态管理与交互边界

- 列表页：`reactive({filters,page,size,rows,total,loading})` + `watch(filters, reload, {deep:true})`。
- 详情页：进入时 `Promise.all([detail, aux...])`，失败按 `ApiError.status` 分态（403 展示无权限、404 空态、409 渲染冲突卡）。
- 409 一律不 toast：计划动作冲突渲染 `allowedNext` 按钮组；准出失败渲染 `unmet` 清单；门禁失败渲染 `failedRules` 卡；会签越序高亮应签角色。
- 导出按钮：`downloading` 态 + 失败提示“导出失败（格式/权限）”，不静默。
- 轮次切换只改 `?roundId=`，不改计划状态；关闭轮次后执行按钮禁用并提示。
- 可访问性：动作按钮 `aria-label`、`ElMessageBox` 焦点归还、表格 `scope` 语义、颜色不作为唯一状态（tone + 文案 + 图标）。

## 8. 边界与异常清单（实现即测）

1. **计划已关闭**：执行/改用例/变更/开轮 → 409 `ILLEGAL_PLAN_TRANSITION`，detail 含 `reason=CLOSED`。
2. **同计划两轮并发**：唯一键 `(plan_id, active)` 冲突 → 捕获 `DataIntegrityViolationException` → 409 `ROUND_IN_PROGRESS`（不 500）。
3. **轮次关闭后补录**：`roundId` 指向 CLOSED → 409 `ROUND_CLOSED`，不静默落到当前轮。
4. **无进行中轮次执行**：409 `NO_ACTIVE_ROUND`，detail 给“先开轮次”链接。
5. **报告生成时存在未批准计划**：生成成功但列入 `excludedPlans`，QG-01 FAIL（AC-4.1）；不得 409。
6. **报告出具后任何 plan_case 变化**：digest 变 → QG-02 FAIL + `fixHint="重新出具报告"`。
7. **会签顺序 / 重复 / 驳回**：越序 409；同人同角色重复 200 幂等；驳回整版 REJECTED，v+1 重走。
8. **会签人结论上调**：409 `CONCLUSION_UPGRADE_FORBIDDEN`（含 from/to）。
9. **豁免绕过**：QG-03 FAIL + QG-01 已豁免 → 门禁仍 FAIL，`failedRules=[QG-03]`；QG-04 申请即 400。
10. **豁免到期**：`expiry_at <= now` 即不生效，重算 QG-03 为 FAIL，记录保留。
11. **门禁评估时报告 CONDITIONAL 但无 PMO 已批豁免**：QG-02 FAIL（AC-5.4）。
12. **门禁服务超时**：准入第 6 项 FAIL / `GATE_UNAVAILABLE`，不允许“超时视为通过”；已存在快照也不得回退旧结果。
13. **重复提交发布单**：命中同 `hash` 快照复用 id，不产生第二份；状态机不变。
14. **历史发布单**：`quality_gate_id IS NULL` → 6 项视图补 `legacy:true`，页面显式说明，不伪造 PASS。
15. **强制放行**：任何角色、任何状态 → 409 `FORCE_RELEASE_RETIRED`，无副作用。
16. **逃逸边界**：`found_at < windowEnd` 为逃逸；`==` 与之后为否；无成功发布 → 0 且窗口 NULL。
17. **改观察窗**：只影响新缺陷；历史 `is_escape`/`escape_window_end` 不变（AC-8.3）。
18. **PROD 缺陷无 release**：400 `PROD_DEFECT_RELEASE_REQUIRED`，不落库。
19. **跨域/跨厂商**：列表/详情/导出/快照回放全部再过 `ScopeGuard`；跨域 403 `SCOPE_DENIED`；快照内固化生成时可见范围，回放仍按当前权限裁剪。
20. **PDF 字体**：STSong-Light 非嵌入；若运行环境缺 CID 字体表 → 启动自检失败并给“改用 XLSX”提示，不生成乱码 PDF。
21. **导出越权**：按调用者 domain/vendor 过滤，越权 403；导出写审计。
22. **时间**：全链路 `Asia/Shanghai`、DB `DATETIME(3)`；窗口/有效期用服务端时间，客户端时间不参与判定。
23. **指标零分母**：返回 `null`（FPY 等），页面显 `—`，不得显示 0%。
24. **重复执行同一用例**：`test_execution` 每次新增，`plan_case` 取最新；轮次视图取 `(round,case)` 最新，不重复计数。

## 9. 测试策略

### 9.1 单元（`make platform-unit` 必须覆盖）

| 测试 | 断言要点 |
| --- | --- |
| `StateTablesTest`（contract） | PLAN/ROUND/REPORT/GATE/WAIVER/REVIEW 的 actions/target/isTerminal 全表；非法动作返回空集 |
| `MetricFormulasQualityTest` | 5 个新公式；零分母 null；2 位 HALF_UP；`passRate` 分母=计划数 |
| `PermissionMatrixQualityTest` | 41 功能码 × 10 角色逐格；新增 15 行与 PRD §6 完全一致；空角色 DENY |
| `QualityDigestTest`（deliver） | 固定 3 行输入的已知 SHA-256；顺序敏感、结果敏感、`case_version` 敏感 |
| `QualityAggregatorTest` | 多计划加权；分轮 vs 累计；`passRate/coverage` 边界（全未执行/全通过） |
| `ExitCriteriaEvaluatorTest` | 每个 key 的 op；未满足项文案与 fixHint |
| `PlanLifecycleTest` | 全状态迁移矩阵；非法迁移码与 `allowedNext`；revision 递增 |
| `RoundConclusionTest` | 下调允许/上调拒绝；关闭冻结 |
| `SignoffOrderTest` | 1→2→3；越序；重复幂等；驳回后 v+1 |
| `GateRulesetTest` | 5 规则逐条 PASS/FAIL/WAIVED/SKIPPED；任一 FAIL 覆盖豁免；QG-04 不可豁免 |
| `EscapeWindowTest` | T+1h/T+71:59:59/T+72h00m/T+72h01m；无发布单；改配置不改历史 |
| `AdmissionPolicyQualityTest`（req） | 第 6 项 PASS/PASS_WITH_WAIVER/FAIL；超时 fail-closed；旧 5 项 JSON 兼容 |

### 9.2 集成/负向（`scripts/tests/quality/`，`make quality-verify`）

| 脚本 | 覆盖 |
| --- | --- |
| `q0-schema.sh` | 13 表/5 表增列/索引/5 指标行/回填（R1 存在、`source` 分布、`revision=0`）；迁移在种子库可重跑 |
| `q1-plan-lifecycle.sh` | AC-1：七态迁移、非法 409+allowedNext、变更无 reason 400、revision+1、change_log 行数=1 |
| `q2-round-exit.sh` | AC-2/3：双轮并发 409、关闭有未执行 409、结论上调 409、准出 409 列未满足项、历史轮结果不变 |
| `q3-report-signoff-export.sh` | AC-4：8 段、v1/v2、越序 409、重复签 200、驳回→REJECTED、XLSX/PDF 可下载且含 8 段与三级签名、水印文本存在 |
| `q4-gate-admission.sh` | AC-5：6 项、无报告 409（QG-01）、过期/94.9/未关闭 P1 各自命中 QG-02/03/04、直连 8083 同样 409、CONDITIONAL 豁免后 PASS_WITH_WAIVER |
| `q5-waiver-replay.sh` | AC-6/7：QG-04 400、单规则、1–30 天、到期失效、一条豁免不放行整门、快照 replay 一致、历史快照不因新数据改变 |
| `q6-escape-metrics.sh` | AC-8：PROD 无版本 400、边界、改窗口、5 指标同源（看板/报告/门禁/版本质量同筛选相等）、digest 变化 → QG-02 FAIL |
| `q7-strategy-env-review.sh` | AC-9/10：策略版本、分配合计 400 `ASSIGNMENT_MISMATCH`、环境 `ENV_IN_USE`、评审 GO/NO_GO/CONDITIONAL、NO-GO 提交需 PMO 确认 |
| `e2e-quality.mjs` | 三条主链路（报告导出、门禁失败、豁免闭环）HTTP 级 E2E；浏览器人工清单 3 条（报告阅读、规则卡展开、抽屉表单） |

`scripts/quality-verify.sh`：与 `platform-verify.sh` 相同的 `PASS/FAIL` 行格式与末尾 `PASS==TOTAL` 守卫；`Makefile` 新增 `quality-verify` 目标，`scripts/test-all.sh` 汇总调用；不并入 `platform-verify.sh`（避免其运行时长翻倍，PRD §14 允许二选一）。

### 9.3 性能

`platform-perf.sh` 追加 4 项：门禁 1 万用例 P95≤500ms、报告 5000 用例≤5s、XLSX≤10s、PDF≤15s；复用既有 p95 统计与 `EXPLAIN` 输出；不达标记 FAIL 并打印最慢 SQL。

### 9.4 回归范围

r1 四步（`make dev-up/verify/test-negative/dev-down`）+ r2 全量（`platform-verify/test-negative/perf/unit`）+ `make lint`。r2 的 AC-3/AC-12/AC-16 断言按“取代”声明改为新口径（6 项准入、计划三态→完整生命周期），历史任务夹不动。

## 10. 任务拆分（12 个，文件边界不相交即可并行）

依赖：T1/T2 先行 → T3 → T4 → T5 → T6 → T7/T8；T9 依赖 T1 的接口契约，可与 T3–T8 并行；T10/T11 依赖 T9；T12 最后。

| # | 任务 | 文件（写） | 读 | 验收 |
| --- | --- | --- | --- | --- |
| T1 | 契约与权限基座 | `platform-contract/.../StateTables.java`、`MetricFormulas.java`、`PermissionMatrix.java`、`quality/QualityGatePort.java`、`GateSnapshot.java`、`GateRuleResult.java`、`QualitySummary.java`、`QualityCodes.java`；`platform-common/.../PermissionRouteTable.java` | `PermissionMatrixTest`、现有 `GatePort`/`GateDecision` | 41 码矩阵逐格通过、路由首命中正确、状态表单测绿 |
| T2 | V5 迁移 + 实体/仓储 + 种子 | `req-service/src/main/resources/db/migration/V5__quality_management.sql`；`deliver/quality/**/{*Entity,*Repository}.java`；`req/review/ReleaseReview*`（实体/仓储）；`req/seed/SeedService.java`（质量种子） | `V1__platform_schema.sql`、`V4`、`SeedService` | 空库/种子库均可迁移；13 表+5 增列+5 指标+回填；种子幂等两遍 |
| T3 | 策略 + 计划生命周期 + 变更 + 准出 | `quality/strategy/*`、`quality/plan/PlanLifecycle*`、`PlanChange*`、`PlanViews`、`quality/aggregate/*`、`quality/env/*` | `TestPlanEntity/Service/Controller`、`plan_case` 仓储 | q1/q7 通过；准出 409 明细；变更留痕 |
| T4 | 轮次 + 分配 + 进度 + 工作台 + 执行改造 | `quality/plan/Round*`、`Assignment*`、`quality/workbench/*`、`TestPlanService`（execute 改造）、`TestExecutionEntity` | T3 的 `PlanViews`、`test_execution` DDL | q2 通过；双轮并发 409；工作台 ≤1s |
| T5 | 报告 + 会签 + 导出 | `quality/report/*`、`deliver/pom.xml`（POI/OpenPDF）、`ReportExportService` | T3 聚合、`release.reportValidityDays`、`RequirementExport` 水印风格 | q3 通过；8 段/版本/三级会签/XLSX+PDF 水印 |
| T6 | 质量门 + 规则 + 快照 + 豁免 + 指标 + 版本质量 | `quality/gate/*`、`quality/metric/*`、`quality/quality/*`、`quality/internal/*`、`reqread/ReleaseWindowReadModel` | T5 报告服务、`deliver.pom`、`Audit/Notification` | q4/q5/q6 通过；唯一实现、幂等、回放 |
| T7 | req 准入 6 项 + 强制放行退役 | `req/deploy/AdmissionEvaluator.java`、`AdmissionPolicy.java`、`DeployOrderService.java`、`DeployOrderEntity.java`、`DeployViews.java`、`DeployOrderController.java`、`req/integration/DeliverQualityGateClient.java` | T6 内部端点、`AdmissionPolicyTest`、`DeliverGateClient` | 6 项、409 `QUALITY_GATE_FAILED`+failedRules、force 恒 409、超时 fail-closed、旧单兼容 |
| T8 | Go/No-Go + 质量设置 + 逃逸回流 | `req/review/*`、`req/release/ReleaseEntity.java`、`ReleaseController.java`、`release/ReleaseQualitySettingsService.java`、`deliver/defect/DefectService.java`、`DefectEntity.java`、`DefectViews.java` | T6 `QualitySummary`、`ReleaseWindowReadModel` | q6/q7 通过；AC-8 边界与「改配置不改历史」 |
| T9 | 前端基座（API/类型/字典/路由/导航） | `web/src/api/quality.ts`、`web/src/api/req.ts`、`web/src/api/types.ts`、`web/src/stores/dict.ts`、`web/src/router/routes/quality.routes.ts`、`web/src/router/routes/deliver.routes.ts`、`web/src/layouts/nav.ts` | `client.ts`、`types.ts` 现有定义、DESIGN §6/§9 | `vue-tsc` 通过；路由可达；字典 10 kind |
| T10 | 前端页面 A（计划详情/执行/工作台/策略/环境） | `web/src/views/deliver/TestPlanDetailView.vue`、`TestPlanExecuteView.vue`、`TestPlansView.vue`、`WorkbenchView.vue`；`web/src/views/quality/TestStrategiesView.vue`、`TestEnvsView.vue`；`web/src/components/quality/{LifecycleStepper,RoundSwitcher,AssignmentSummary,WaiverBadge}.vue` | T1–T4 接口、DESIGN §5 线框 | 6 tab、动作按钮、409 渲染 `unmet/allowedNext`、无状态下拉 |
| T11 | 前端页面 B（报告/门禁/指标/Go-No-Go/部署单改造） | `web/src/views/quality/{TestReportsView,TestReportDetailView,QualityGatesView,QualityGateDetailView,QualityMetricsView}.vue`、`web/src/views/release/{GoNoGoView,ReleaseDetailView}.vue`、`web/src/views/deploy/DeployOrderDetailView.vue`、`web/src/components/quality/{ReportReader,SignoffProgress,QualityGatePanel,QualityRuleCard,WaiverDrawer,DigestChip,EvidenceRef,ConclusionBar}.vue` | T5–T8 接口、DESIGN §9 | 8 段、规则卡、`failedRules`、移除强制放行入口、历史徽标 |
| T12 | 验证脚本 + 交付自检 | `scripts/tests/quality/*.sh`、`scripts/quality-verify.sh`、`Makefile`、`scripts/test-all.sh`、`scripts/platform-perf.sh`、`web/e2e/quality.spec.mjs`（离线则跳过并记录） | T1–T11 | q0–q7 全绿、`quality-verify` PASS==TOTAL、性能 4 项、构建与 lint、自检结论（不写 QA 报告） |

**工程约束随任务落地**：分支 `feat/qa-quality-management` 已存在，全部提交在该分支（T12 统一 `git status` 核对：仅任务夹+代码改动，无 `target/`、导出产物、`.env`）；提交信息注明任务夹与 runId；`.gitignore` 现有规则已覆盖 `logs/teamflow/`、`var/`、`*.log`，本轮若新增导出落盘目录必须命中 `var/` 或补规则（T5/T12 核对）；迁移只增 V5（T2）；不引 Docker/Gradle/Flowable/Redis/MQ/Python（T5 新增依赖仅 POI/OpenPDF）。

**并行建议**：T1+T2 并行 → T3 与 T9 并行 → T4 与 T5 并行（T5 依赖 T3 的聚合接口，可先按签名编码）→ T6 → T7 与 T8 并行 → T10 与 T11 并行 → T12。

## 11. 依赖与风险

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| Maven 拉取 POI/OpenPDF 失败（离线） | 报告导出不可用 | T5 先 `mvn -o` 探测；失败则改用手写 `SpreadsheetML`（XLSX 本质 zip+XML）+ OpenPDF 仍必需；若 PDF 库也不可用，Q3 导出断言降级为“PDF 未实现”必须在 QA 报告显式记录，不得静默通过 |
| PDF 中文字体 | 乱码 | 用 CID 非嵌入字体，单测生成一页含中文的 PDF 并断言字节中存在 `STSong-Light` |
| 15 个新功能码改动矩阵 | 既有权限断言/管理页可能按 26 计数 | T1 同步改 `admin` 权限页与脚本计数（`platform-verify` 中矩阵断言改为 `FUNCTIONS.size()`，不写死 26） |
| 存量计划状态映射 | r2 断言失败 | T2 回填 RUNNING/DONE → IN_PROGRESS/COMPLETED；r2 用例按“取代”声明调整 |
| digest 口径被二次实现 | AC-3.6/AC-5.3 不一致 | 只允许 `QualityDigest.digest()`，review 时 grep `MessageDigest` 仅一处 |
| 跨服务读 `release/deploy_order` | 边界争议 | 仅 `ReleaseWindowReadModel` 一个类、只读、带 scope 标记，写路径禁止 |
