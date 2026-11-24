# NULL 安全扫描：不可变集合成员判定与 switch 选择器（r44）

- 扫描基线：`main@bd3426d`（r43 已合回）；任务夹 `docs/teamflow/20261124-r44-null-safe-enums/`
- 扫描范围：`req-service` / `deliver-service` / `platform-common` / `platform-contract` 的 **main** 源码（不含测试与 `web/`）
- 触发缺陷：`POST /api/req/requirements/{id}/changes` 漏传 `changeType` → `RequirementChangeFields.knownType` 的 `List.of(...).contains(null)` → NPE → 500

## 1. 结论

| 家族 | 命中 | 需加固 | 建议加固 | 不可达（本轮不改） |
|---|---|---|---|---|
| 不可变集合成员判定（`contains`/`containsKey`/`indexOf`） | 63 | **1** | 1 | 61 |
| `switch` 选择器（String/枚举/字符） | 79 | 0 | 0 | 79 |
| 合计 | 142 | 1 | 1 | 140 |

唯一必改：`req-service/src/main/java/com/example/req/requirement/change/RequirementChangeFields.java:87`（`knownType`）。
唯一建议项：`req-service/src/main/java/com/example/req/integration/admission/AdmissionItemPolicy.java:101`（`List.of().indexOf(null)` 同源 NPE，当前入参来自 DB 非空，本轮按「只修外部可达点」不改）。

## 2. 判定口径

1. **可达性**：实参只能来自请求体/查询参数/路径变量（或它们的直接派生）才算可达；来自 DB 列、内部常量、枚举名、字符循环变量的记为不可达。
2. **短路证据**：同一表达式内的 `x != null &&`、三元缺省、上一行的 early-return/归一化，均视为已判空。
3. **不可变集合语义**：`List.of`/`Set.of`/`Map.of`/`List.copyOf`/`Set.copyOf` 的 `contains(null)`/`containsKey(null)`/`indexOf(null)` 在 JDK 均抛 NPE（`ImmutableCollections` 走 `indexOf` + `requireNonNull`），与 JDK 版本无关，故按「同族」统一扫描。
4. **修不修**：只改外部可达点；不可达点在表中保留「为什么不可达」，便于新入口出现时复核。

## 3. 清单 A：不可变集合成员判定（63 处）

### `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsDatasets.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 107 | `return SPECIAL_FILTERS.contains(fieldKey) \|\| !field(dataset, fieldKey).measure();` | 不可达·调用点已判空 | 唯一外部调用点 :150 先判 `filter.field() == null`；`isFilterable` 只被它调用 |
| 111 | `return SPECIAL_FILTERS.contains(fieldKey);` | 不可达·调用点已判空 | 同上，入口条件已排除 null |
| 157 | `if (!OPERATORS.contains(op)) {` | 不可达·已归一化 | :156 `op == null ? "eq" : op` |
| 161 | `&& !List.of("isEmpty", "isNotEmpty").contains(op)) {` | 不可达·已归一化 | 同一 `op`，已在上方缺省归一 |

### `deliver-service/src/main/java/com/example/deliver/defect/DefectService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 458 | `if (!SOURCES.contains(normalised)) {` | 不可达·已归一化 | :456 三元表达式给出非空默认值 |
| 482 | `if (!SEVERITIES.contains(upper)) {` | 不可达·已归一化 | :481 `null -> ""` 后再 upper |

### `deliver-service/src/main/java/com/example/deliver/openapi/ExecutionIngestService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 161 | `if (!RESULTS.contains(value)) {` | 不可达·已判空 | :156 空值提前 return |

### `deliver-service/src/main/java/com/example/deliver/openapi/HmacAuthFilter.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 78 | `return !PATHS.contains(stripTrailingSlash(request.getRequestURI()));` | 不可达·非空构造 | `getRequestURI()` 非 null；`stripTrailingSlash` 返回非空字符串 |

### `deliver-service/src/main/java/com/example/deliver/quality/env/TestEnvService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 213 | `if (!ENV_TYPES.contains(envType)) {` | 不可达·已归一化 | :211 缺省 `TEST` |
| 219 | `if (status != null && !ENV_STATUSES.contains(status)) {` | 不可达·已判空 | 表达式内短路 |

### `deliver-service/src/main/java/com/example/deliver/quality/gate/WaiverService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 321 | `if (APPROVER_ROLES.contains(role)) {` | 不可达·非空构造 | 元素来自 `Set.copyOf(TenantContext.roles())`，集合元素非 null |

### `deliver-service/src/main/java/com/example/deliver/quality/internal/FlowEvidenceService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 138 | `if (PLAN_APPROVED.contains(status)) {` | 不可达·非空构造 | `plan_status` 列 NOT NULL（联表缺失才是 null） |
| 140 | `} else if (PLAN_PENDING.contains(status)) {` | 不可达·非空构造 | 同上 |

### `deliver-service/src/main/java/com/example/deliver/quality/metric/QualityMetricService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 300 | `if (code == null \|\| !DEFAULT_DEFINITIONS.containsKey(code)) {` | 不可达·已判空 | 表达式内短路 |

### `deliver-service/src/main/java/com/example/deliver/quality/plan/RoundService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 106 | `if (!SCOPE_MODES.contains(mode)) {` | 不可达·已归一化 | :103 缺省 `CURRENT` |

### `deliver-service/src/main/java/com/example/deliver/reports/ReportScopes.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 50 | `return scope != null && TIERS.contains(scope);` | 不可达·已判空 | 表达式内短路 |

### `deliver-service/src/main/java/com/example/deliver/reports/ReportService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 347 | `if (!FORMATS.contains(fmt)) {` | 不可达·已归一化 | :345 缺省 `xlsx` 且 trim/lower |
| 484 | `if (!CHART_TYPES.contains(type)) {` | 不可达·已归一化 | :483 缺省 `TABLE` 且 trim/upper |

### `deliver-service/src/main/java/com/example/deliver/review/CaseReviewService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 279 | `if (!STATUSES.contains(normalised)) {` | 不可达·已判空 | :277-278 空值跳过 |
| 368 | `if (!CONCLUSIONS.contains(value)) {` | 不可达·已判空 | :363 空值提前 return |

### `deliver-service/src/main/java/com/example/deliver/task/TaskService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 205 | `if (!STATUSES.contains(upper)) {` | 不可达·同族不成立 | 入参由上层必填；null 在 :204 `status.trim()` 先 NPE——属「可空字符串调方法」另一族，不是 `contains(null)` |

### `deliver-service/src/main/java/com/example/deliver/testplan/TestPlanService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 331 | `DATA_NOTE_LOCKED_STATUSES.contains(plan.getStatus()),` | 不可达·非空构造 | `test_plan.status` 列 NOT NULL（`V5__quality_management.sql`） |
| 356 | `if (DATA_NOTE_LOCKED_STATUSES.contains(plan.getStatus())) {` | 不可达·非空构造 | 同上 |
| 466 | `if (!EXECUTION_RESULTS.contains(normalised)) {` | 不可达·已判空 | :461 空值提前 return |
| 453 | `if (!List.of("STRICT", "LOOSE").contains(normalised)) {` | 不可达·已判空 | :449 空值 return null |

### `deliver-service/src/main/java/com/example/deliver/testcase/TestCaseService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 768 | `if (!List.of("P0", "P1", "P2", "P3").contains(normalised)) {` | 不可达·已归一化 | :764 缺省 `P2` |

### `deliver-service/src/main/java/com/example/deliver/quality/plan/PlanLifecycle.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 108 | `if (!Set.of("PASS", "CONDITIONAL", "FAIL", "CANCELLED").contains(normalised)) {` | 不可达·已判空 | :103 空值提前 return |

### `platform-common/src/main/java/com/example/platform/common/scope/ScopedJdbc.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 166 | `if (alias == null \|\| RESERVED.contains(alias.toUpperCase(Locale.ROOT))) {` | 不可达·已判空 | 表达式内短路 |

### `platform-common/src/main/java/com/example/platform/common/security/PermissionDeclarationValidator.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 76 | `if (!WRITE_METHODS.contains(method)) {` | 不可达·非空构造 | 元素来自 `RequestMethod.name()` |

### `platform-contract/src/main/java/com/example/platform/contract/PermissionMatrix.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 433 | `if (!FUNCTIONS.contains(function)) {` | 不可达·非空构造 | 内部注册表常量；未知即 `IllegalStateException` |

### `platform-contract/src/main/java/com/example/platform/contract/RequirementActionMatrix.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 199 | `if (SELF_SERVICE.contains(action)) {` | 不可达·非空构造 | action 为内部动作码；:193 `code == null` 已 return false |
| 210 | `if (REVIEW_SIDE.contains(action) && caller.vendorId() != null) {` | 不可达·非空构造 | 同上 |

### `platform-contract/src/main/java/com/example/platform/contract/RequirementEditability.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 107 | `if (!knownState(normalizedType, normalizedStatus) \|\| field == null \|\| !FIELDS.contains(field)) {` | 不可达·已判空 | 表达式内 `field == null` 短路（r43 矩阵，本轮不动） |

### `req-service/src/main/java/com/example/req/automation/rule/AutomationRuleValidation.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 59 | `if (scopeType == null \|\| !SCOPE_TYPES.contains(scopeType)) {` | 不可达·已判空 | 表达式内短路 |

### `req-service/src/main/java/com/example/req/automation/spec/AutomationSpecs.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 139 | `if (!CONDITION_KEYS.contains(key)) {` | 不可达·非空构造 | `node.propertyNames()` 非 null |
| 179 | `if (kind == null \|\| !KINDS.contains(kind)) {` | 不可达·已判空 | 表达式内短路 |
| 340 | `if (!PRIORITIES.contains(value)) {` | 不可达·非空构造 | 值经 :402 `strings()`（元素 null/blank 先 400） |
| 353 | `if (!APPROVAL_RESULTS.contains(value)) {` | 不可达·非空构造 | 同上 |
| 360 | `if (!FIELDS.contains(value)) {` | 不可达·非空构造 | 同上 |
| 379 | `if (op == null \|\| !ESTIMATE_OPS.contains(op)) {` | 不可达·已判空 | 表达式内短路 |

### `req-service/src/main/java/com/example/req/collab/policy/BranchPolicyValidator.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 72 | `if (!METHODS.contains(mergeMethod)) {` | 不可达·已归一化 | :70 缺省 `MERGE` |

### `req-service/src/main/java/com/example/req/collab/wiki/WikiPageService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 458 | `if (!BIZ_TYPES.contains(bizType) \|\| bizId == null) {` | 不可达·非空构造 | `@RequestParam String bizType` 必填：缺参由 Spring 400，空白非 null 也在此返回 400 |
| 485 | `if (cmd == null \|\| cmd.bizType() == null \|\| !BIZ_TYPES.contains(cmd.bizType()) \|\| cmd.bizId() == null) {` | 不可达·已判空 | 表达式内短路 |

### `req-service/src/main/java/com/example/req/deploy/AdmissionPolicy.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 67 | `return status != null && TEST_PASSED_STATUSES.contains(status);` | 不可达·已判空 | 表达式内短路 |

### `req-service/src/main/java/com/example/req/deploy/DeployOrderService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 210 | `if (env.isEmpty() \|\| !ENVS.contains(env)) {` | 不可达·非空构造 | `normalizeEnv` 返回非 null；空串另判 |

### `req-service/src/main/java/com/example/req/integration/admission/AdmissionItemPolicy.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 84 | `return code != null && LOCKED.contains(code);` | 不可达·已判空 | 表达式内短路 |
| 101 | `int index = ORDER.indexOf(code);` | 建议加固（本轮不改） | `List.of().indexOf(null)` 与本族同源；当前调用方 code 来自 DB 快照非空，故不改；若要求同族零残留，前置 `code == null` 退化为末位序号即可 |

### `req-service/src/main/java/com/example/req/integration/config/IntegrationConfigService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 488 | `if (!AUTH_TYPES.contains(type)) {` | 不可达·已归一化 | :484 空值缺省 `TOKEN` |
| 561 | `if (!LOG_RESULTS.contains(value)) {` | 不可达·已判空 | :557 空值 return null |

### `req-service/src/main/java/com/example/req/integration/outbound/OutboundHttpClient.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 226 | `if (name.isEmpty() \|\| RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {` | 不可达·非空构造 | name 为内部 header 名；表达式内先 `isEmpty()` |

### `req-service/src/main/java/com/example/req/integration/security/SsrfGuard.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 88 | `if (!ALLOWED_SCHEMES.contains(scheme)) {` | 不可达·已归一化 | :86 `scheme == null -> ""` |

### `req-service/src/main/java/com/example/req/release/ReleaseLevel.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 36 | `return level != null && ALL.contains(level);` | 不可达·已判空 | 表达式内短路 |

### `req-service/src/main/java/com/example/req/release/ReleaseLevelValidator.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 89 | `if (!ROLLBACK_SOURCES.contains(status)) {` | 不可达·已归一化 | :88 `null -> ""` |

### `req-service/src/main/java/com/example/req/release/ReleaseService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 171 | `if (!List.of("STRICT", "LOOSE").contains(gateMode)) {` | 不可达·已归一化 | :169 缺省 `STRICT` |

### `req-service/src/main/java/com/example/req/requirement/RequirementRules.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 68 | `boolean allowed = RD_PARENT_ALLOWED.contains(parentStatus);` | 不可达·非空构造 | parentStatus 来自父实体状态列（DB NOT NULL），父实体已加载 |

### `req-service/src/main/java/com/example/req/requirement/RequirementService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 1143 | `if (!List.of("P0", "P1", "P2", "P3").contains(normalized)) {` | 不可达·已归一化 | :1139 缺省 `P2` |

### `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeFields.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 87 | `return TYPES.contains(changeType);` | ★ 需加固（本轮修复） | `TYPES` 为 `List.of(...)`，JDK 不可变集合 `contains(null)` 走 `indexOf(null)` 抛 NPE；`changeType` 直连请求体（缺省即 null）→ 500 |
| 111 | `if (!FIELDS.contains(field)) {` | 不可达·非空构造 | `field` 是 JSON 对象键（Jackson 键非 null）；`merge` 只由 validator/apply 用本服务生成的快照调用 |
| 221 | `if (!PRIORITIES.contains(priority)) {` | 不可达·已判空 | 函数入口已判空并缺省 `P2` |

### `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 247 | `if (!STATUSES.contains(normalized)) {` | 不可达·已判空 | :243 空值 return null |

### `req-service/src/main/java/com/example/req/review/ReleaseReviewService.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 241 | `if (!DECISIONS.contains(value)) {` | 不可达·已判空 | :233 空值提前抛 400 |

### `req-service/src/main/java/com/example/req/seed/RbacSeeder.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 221 | `if (ACTION_DENY_OVERRIDES.getOrDefault(roleCode, Set.of()).contains(action.code())) {` | 不可达·非空构造 | roleCode 与 `action.code()` 均为内部常量 |

### `req-service/src/main/java/com/example/req/integration/jenkins/BuildStateMachine.java`

| 行 | 表达式 | 判定 | 理由 |
|---|---|---|---|
| 109 | `return ALLOWED.getOrDefault(current, Set.of()).contains(incoming)` | 不可达·已判空 | :105 current/incoming 已判空 |

## 4. 清单 B：`switch` 选择器（79 处）

分组：**B1** 已判空/归一化（30）· **B2** 非空构造——枚举/字符/内部常量/DB NOT NULL（46）· **B3** 外部字符串但容器或上游保证非空（3）。三组均未发现「外部可空选择器直入 switch」。

| # | 文件:行 | 选择器 | 组 | 依据 |
|---|---|---|---|---|
| 1 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsQueryService.java:595` | `op` | G1 | :594 `filter.op() == null ? "eq"` |
| 2 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsQueryService.java:613` | `op` | G1 | 同一归一化后的 op |
| 3 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsRequest.java:57` | `normalised` | G1 | :52-53 局部变量 `normalised` 已归一 |
| 4 | `deliver-service/src/main/java/com/example/deliver/defect/DefectBoardQuery.java:152` | `metric` | G1 | :149 `metric == null` 提前 return |
| 5 | `deliver-service/src/main/java/com/example/deliver/flow/FlowMetricService.java:145` | `code` | G1 | :142 `code == null` 提前 return |
| 6 | `deliver-service/src/main/java/com/example/deliver/quality/gate/GateRuleset.java:170` | `code == null ? "" : code` | G1 | 三元缺省 |
| 7 | `deliver-service/src/main/java/com/example/deliver/quality/plan/PlanLifecycle.java:93` | `conclusion.toUpperCase()` | G1 | :90 `conclusion == null` return -1 |
| 8 | `deliver-service/src/main/java/com/example/deliver/quality/plan/PlanLifecycleService.java:99` | `action` | G1 | `requireTarget` 先行校验动作；缺失/未知即 400 |
| 9 | `deliver-service/src/main/java/com/example/deliver/quality/plan/RoundService.java:110` | `mode` | G1 | :103-105 缺省 `CURRENT` |
| 10 | `deliver-service/src/main/java/com/example/deliver/quality/report/ReportExportService.java:331` | `status == null ? "PENDING" : status` | G1 | 三元缺省 |
| 11 | `deliver-service/src/main/java/com/example/deliver/quality/report/ReportService.java:1747` | `conclusion == null ? "" : conclusion.trim()` | G1 | 三元缺省 |
| 12 | `deliver-service/src/main/java/com/example/deliver/support/ShareVisibility.java:76` | `tier` | G1 | :75 空值缺省 `PRIVATE` |
| 13 | `deliver-service/src/main/java/com/example/deliver/trace/TraceabilityQuery.java:274` | `status.trim().toUpperCase()` | G1 | :268 空值 return 占位 |
| 14 | `platform-common/src/main/java/com/example/platform/common/audit/ActualPeriodRecorder.java:122` | `bizType == null ? "" : bizType.trim()` | G1 | 三元缺省 |
| 15 | `platform-common/src/main/java/com/example/platform/common/audit/ActualPeriodRecorder.java:138` | `bizType == null ? "" : bizType.trim()` | G1 | 三元缺省 |
| 16 | `platform-common/src/main/java/com/example/platform/common/audit/ActualPeriodRecorder.java:156` | `bizType.trim().toUpperCase()` | G1 | :153 `bizType == null` 提前 return |
| 17 | `platform-common/src/main/java/com/example/platform/common/security/PermissionGrantsRepository.java:88` | `raw.trim().toUpperCase()` | G1 | :85 `raw == null` 提前 return |
| 18 | `platform-common/src/main/java/com/example/platform/common/security/PermissionGrantsRepository.java:100` | `raw.trim().toUpperCase()` | G1 | 同上 |
| 19 | `req-service/src/main/java/com/example/req/deploy/DeployViews.java:82` | `item.status()` | G1 | 选择器为 `item.status() == null ? FAIL : item.status()` |
| 20 | `req-service/src/main/java/com/example/req/integration/gitlab/GitLabAdapter.java:341` | `level` | G1 | :338 `level == null` 提前 return |
| 21 | `req-service/src/main/java/com/example/req/integration/gitlab/MrViews.java:220` | `value` | G1 | :216 空值 return null 后归一 |
| 22 | `req-service/src/main/java/com/example/req/integration/jenkins/BuildService.java:851` | `result.trim().toUpperCase()` | G1 | :850 `result != null && !isBlank()` |
| 23 | `req-service/src/main/java/com/example/req/integration/sonar/SonarAdapter.java:182` | `value` | G1 | `upper(raw)` 为 null 时 :179 已 return |
| 24 | `req-service/src/main/java/com/example/req/integration/sonar/SonarThresholds.java:180` | `key` | G1 | :177 `key == null` 提前 return |
| 25 | `req-service/src/main/java/com/example/req/release/FlowChainService.java:336` | `status` | G1 | :332-334 归一化并排除 null/空 |
| 26 | `req-service/src/main/java/com/example/req/release/ReleaseLevel.java:52` | `level` | G1 | :49 `level == null` 提前 return |
| 27 | `req-service/src/main/java/com/example/req/release/ReleaseService.java:690` | `status == null ? "" : status` | G1 | 三元缺省 |
| 28 | `req-service/src/main/java/com/example/req/release/ReleaseService.java:699` | `status == null ? "" : status` | G1 | 三元缺省 |
| 29 | `req-service/src/main/java/com/example/req/sys/AdminPermissionController.java:65` | `access == null ? "DENY" : access` | G1 | 三元缺省 |
| 30 | `req-service/src/main/java/com/example/req/sys/RoleGuard.java:130` | `type.trim().toUpperCase()` | G1 | :127 空白先抛 400 |
| 31 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsDatasets.java:62` | `dataset(code)` | G2 | 未知 code 即抛错 |
| 32 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsMetrics.java:73` | `spec.kind()` | G2 | 枚举 |
| 33 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsQueryService.java:779` | `key` | G2 | 内部映射键 |
| 34 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsQueryService.java:824` | `key` | G2 | 内部映射键 |
| 35 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsSql.java:98` | `dataset` | G2 | dataset 来自 `AnalyticsDatasets` 定义 |
| 36 | `deliver-service/src/main/java/com/example/deliver/analytics/AnalyticsSql.java:109` | `fieldKey` | G2 | fieldKey 由数据集定义校验 |
| 37 | `deliver-service/src/main/java/com/example/deliver/gate/GatePolicy.java:68` | `result` | G2 | 策略内部结论常量 |
| 38 | `deliver-service/src/main/java/com/example/deliver/gate/GatePolicy.java:122` | `result` | G2 | 同上 |
| 39 | `deliver-service/src/main/java/com/example/deliver/openapi/HmacAuthFilter.java:255` | `ch` | G2 | 字符循环变量 |
| 40 | `deliver-service/src/main/java/com/example/deliver/portal/PortalTemplates.java:250` | `roleCode.trim()` | G2 | 内部角色码 |
| 41 | `deliver-service/src/main/java/com/example/deliver/quality/aggregate/ExitCriteriaEvaluator.java:141` | `op` | G2 | op 由评估器内部常量提供 |
| 42 | `deliver-service/src/main/java/com/example/deliver/quality/gate/GateEvaluator.java:312` | `config.code()` | G2 | 规则表 `code` 列 NOT NULL |
| 43 | `deliver-service/src/main/java/com/example/deliver/quality/plan/RoundService.java:177` | `result` | G2 | 内部结论常量 |
| 44 | `deliver-service/src/main/java/com/example/deliver/quality/report/ReportExportService.java:564` | `key` | G2 | 内部映射键 |
| 45 | `deliver-service/src/main/java/com/example/deliver/quality/report/ReportService.java:1459` | `role` | G2 | 内部角色码 |
| 46 | `deliver-service/src/main/java/com/example/deliver/support/Visibility.java:27` | `tier` | G2 | 枚举 |
| 47 | `deliver-service/src/main/java/com/example/deliver/testcase/SnapshotJson.java:70` | `ch` | G2 | 字符循环变量 |
| 48 | `deliver-service/src/main/java/com/example/deliver/testcase/SnapshotJson.java:191` | `ch` | G2 | 字符循环变量 |
| 49 | `deliver-service/src/main/java/com/example/deliver/testcase/SnapshotJson.java:274` | `escaped` | G2 | 转义字符循环变量 |
| 50 | `platform-common/src/main/java/com/example/platform/common/security/PermissionService.java:321` | `layer` | G2 | 枚举 |
| 51 | `platform-common/src/main/java/com/example/platform/common/security/PermissionService.java:330` | `layer` | G2 | 枚举 |
| 52 | `platform-contract/src/main/java/com/example/platform/contract/JwtCodec.java:173` | `c` | G2 | 字符循环变量 |
| 53 | `platform-contract/src/main/java/com/example/platform/contract/JwtCodec.java:264` | `c` | G2 | 字符循环变量 |
| 54 | `platform-contract/src/main/java/com/example/platform/contract/JwtCodec.java:334` | `esc` | G2 | 转义字符循环变量 |
| 55 | `req-service/src/main/java/com/example/req/auth/AuthService.java:117` | `scope.tier()` | G2 | 枚举 |
| 56 | `req-service/src/main/java/com/example/req/automation/action/AutomationActionExecutor.java:77` | `AutomationActionType.of(action.type())` | G2 | 枚举解析，未知即抛错 |
| 57 | `req-service/src/main/java/com/example/req/automation/action/AutomationActionExecutor.java:97` | `AutomationActionType.of(action.type())` | G2 | 同上 |
| 58 | `req-service/src/main/java/com/example/req/automation/action/AutomationActionExecutor.java:131` | `outcome.status()` | G2 | 枚举 |
| 59 | `req-service/src/main/java/com/example/req/automation/action/AutomationNotifier.java:123` | `recipient.kind()` | G2 | 枚举 |
| 60 | `req-service/src/main/java/com/example/req/automation/engine/AutomationConditionEvaluator.java:95` | `estimate.op()` | G2 | 枚举 |
| 61 | `req-service/src/main/java/com/example/req/automation/event/AutomationEventMapper.java:174` | `action` | G2 | 内部事件分发常量（映射表键） |
| 62 | `req-service/src/main/java/com/example/req/automation/spec/AutomationSpecs.java:258` | `actionType` | G2 | 动作类型经 `strings()`/枚举校验 |
| 63 | `req-service/src/main/java/com/example/req/collab/gantt/GanttQuery.java:197` | `q.view()` | G2 | 枚举 |
| 64 | `req-service/src/main/java/com/example/req/collab/gantt/GanttQuery.java:208` | `type` | G2 | `GanttQuery.of` 工厂已校验 type |
| 65 | `req-service/src/main/java/com/example/req/collab/gantt/GanttQuery.java:243` | `type` | G2 | 同上 |
| 66 | `req-service/src/main/java/com/example/req/collab/gantt/GanttQuery.java:262` | `type` | G2 | 同上 |
| 67 | `req-service/src/main/java/com/example/req/collab/gantt/GanttQuery.java:509` | `type` | G2 | 同上 |
| 68 | `req-service/src/main/java/com/example/req/collab/gantt/GanttQuery.java:557` | `type` | G2 | 同上 |
| 69 | `req-service/src/main/java/com/example/req/collab/gantt/GanttQuery.java:648` | `type` | G2 | 同上 |
| 70 | `req-service/src/main/java/com/example/req/collab/policy/BranchPolicyValidator.java:137` | `c` | G2 | 字符循环变量 |
| 71 | `req-service/src/main/java/com/example/req/integration/sonar/SonarThresholds.java:245` | `status` | G2 | `rank` 只由 `upper()` 结果调用，null 在上游已 return |
| 72 | `req-service/src/main/java/com/example/req/requirement/RequirementEditGuard.java:161` | `field` | G2 | field 来自 `FIELDS` 常量（:196 已核对） |
| 73 | `req-service/src/main/java/com/example/req/requirement/RequirementEditGuard.java:191` | `field` | G2 | 同上 |
| 74 | `req-service/src/main/java/com/example/req/requirement/RequirementStateMachine.java:23` | `type` | G2 | 内部需求类型常量 |
| 75 | `req-service/src/main/java/com/example/req/requirement/WbsBackfillRowWriter.java:277` | `approval.code()` | G2 | 枚举 |
| 76 | `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeApplyService.java:175` | `field` | G2 | field 来自本服务写入的快照键 |
| 77 | `req-service/src/main/java/com/example/req/attachment/AttachmentService.java:244` | `type` | G3 | 控制器路径变量必填（缺失由 Spring 400），空白进 default 分支 |
| 78 | `req-service/src/main/java/com/example/req/attachment/AttachmentService.java:286` | `type` | G3 | `@RequestParam bizType` 必填；:280-281 空白已 400 |
| 79 | `req-service/src/main/java/com/example/req/collab/wiki/WikiPageService.java:785` | `bizType` | G3 | `listLinks` 先用 `BIZ_TYPES` 校验（:458），到达 switch 时非空；其余调用点传内部常量 |

## 5. 本轮加固范围

```java
// RequirementChangeFields.java:86-88（唯一行为修复点）
- public static boolean knownType(String changeType) { return TYPES.contains(changeType); }
+ public static boolean knownType(String changeType) {
+     return changeType != null && !changeType.isBlank() && TYPES.contains(changeType);
+ }
```

- 合法值集合 `CONTENT/DAYS/PARENT/RELEASE` 与判定语义不变：`""`/`" "` 原本就是 false（且不抛 NPE），本轮只把 `null` 从「抛 NPE」改为 false。
- 不做 trim / 大小写归一：`" content "` 仍按未知类型 400，避免隐式改变契约。
- 未知/缺失类型的错误 detail 追加 `allowedTypes`（`RequirementErrors` 四参重载），其余字段越界分支保持原三键。

## 6. 观察项（相邻但不同族，本轮不改）

- `deliver-service/src/main/java/com/example/deliver/task/TaskService.java:204`：`status.trim()` 对可空字符串直接调方法，同属「缺参 500」诱因，但不在 `contains(null)` 族；入参由上层必填。
- `req-service/src/main/java/com/example/req/integration/outbound/OutboundHttpClient.java:226`：`name.isEmpty()` 亦然（name 为内部 header 名）。
- `req-service/src/main/java/com/example/req/integration/admission/AdmissionItemPolicy.java:101`：`indexOf(null)` 同源，见 §1 建议项。

## 7. 复现方式

```bash
# 全部不可变集合成员判定点（常量接收者）
grep -rnE '(List|Set|Map)\.(of|copyOf)\(' --include=*.java req-service/src/main deliver-service/src/main platform-common/src/main platform-contract/src/main | wc -l
# 内联工厂直接判成员的点
grep -rnE '(List|Set|Map)\.(of|copyOf)\([^)]*\)\.(contains|containsKey|indexOf)\(' --include=*.java req-service/src/main deliver-service/src/main platform-common/src/main platform-contract/src/main
# 全部 switch 选择器
grep -rn 'switch (' --include=*.java req-service/src/main deliver-service/src/main platform-common/src/main platform-contract/src/main | wc -l
```

扫描日期：2026-11-24 · 计数：成员判定 63 处 · switch 79 处 · 合计 142 处
