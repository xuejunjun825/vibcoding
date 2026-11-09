# M7 CI 集成对接 · 技术方案（TECHNICAL）

> 任务夹：`docs/teamflow/20261109-r5-ci-integration-adapters/`（PRD.md / DESIGN.md 同夹）
> 分支：`feat/ci-integration-adapters`；本轮新增 5 张表 + 3 张表加列，零新中间件、零新依赖、零新服务。
> 目标：把 Jenkins 构建、GitLab MR、Sonar 质量门、Nexus 制品接进既有平台，并把发布准入从 M6 的 6 项扩到 **9 项**，
> 全程"默认关闭 + 本地桩验收"，真实外呼只在被显式打开且被显式触发时发生。

---

## 0. 一页结论

| 维度 | 结论 |
|---|---|
| 落点 | 全部新增能力落在 **req-service**（配置底座、四适配器、准入 9 项、制品/构建/MR 事实表）；deliver-service 只改两处只读投影（追溯矩阵、缺陷内部端点） |
| 出站 | **单一** `OutboundHttpClient`（超时/重试/脱敏/日志/SSRF 一处实现），四个适配器只做协议映射 |
| 准入 | 准入路径**零外呼**：只读本地快照与事实表；外呼由"触发/轮询/刷新"异步产生事实 |
| 数据 | 新表 `integration_config` / `integration_sync_log` / `build_record` / `mr_sync` / `artifact_record` / `admission_item_config`；**复用** `quality_gate_snapshot` 并加 `source` 维度存 Sonar 快照 |
| 兼容 | 集成默认关闭 ⇒ ⑦⑧⑨ 三项为 `CLOSED`（关闭，不计分母、不阻断提交）⇒ 既有 25/167/347 条门禁的提交路径行为不变 |
| 验证 | `make integration-verify`（桩 127.0.0.1:19090）≥48 条断言，四适配器各 ≥6 |

---

## 1. 架构决策（每条都写"为什么"）

**ADR-T1：四适配器同居 req-service，不新建服务。**
集成的事实（配置、构建、MR、快照、制品）全部服务于"发布准入"这一 req 域决策；跨服务会立刻把 500ms 准入预算变成两次网络跳。新服务还要新端口、新 Flyway owner、新网关路由——收益为零。故 req-service 内新增 `com.example.req.integration` 子包族，按系统再分包。

**ADR-T2：一个出站客户端，四个适配器只做映射。**
PRD §9 明令"禁止各系统散落 HTTP 调用"。`OutboundHttpClient` 独占超时、重试分类、脱敏、`integration_sync_log` 落库、SSRF 校验；`JenkinsAdapter/GitLabAdapter/SonarAdapter/NexusAdapter` 只负责 URL/载荷/响应到域对象的转换。这样 AC-2（重试/日志/脱敏）只需实现一次、断言一次，也避免四份重试逻辑各自漂移。

**ADR-T3：同步/异步分工由"是否阻断用户动作"决定，而不是由代码方便决定。**
- **交互读**（连通性测试、制品检索、Sonar 手动刷新、构建详情刷新）：**单次尝试**，显式超时（connect 2s / read 默认 5s），失败即降级返回 `degraded:true`，不让用户在 31s 的重试里等。
- **异步写**（构建触发提交、构建轮询、webhook 后处理、GitLab 兜底轮询、Sonar 定时刷新）：走完整重试策略（1/2/4/8/16s，±20% 抖动，最多 5 次重试 = 6 次尝试）。
- **准入**：零外呼。
理由：AC-2.2 要求"最多 5 次重试"可被桩观测，而 AC-8.6 要求准入 P95≤500ms——两者只有在"重试只发生在后台"时才能同时成立。

**ADR-T4：构建触发是 202 + 后台提交，不是同步调用 Jenkins。**
`POST /deploy-orders/{id}/builds` 先落 `build_record(status=QUEUED)` 再入后台队列，立即 202。理由三条：① Jenkins 提交可能 10s+，不能让 HTTP 线程挂着；② 并发触发要靠"活动行唯一键"（见 §3.2）在 DB 层挡住，而不是靠内存锁；③ 提交失败也能留下一条可重试的 QUEUED 事实，而不是一个 500。

**ADR-T5：Sonar 快照复用 `quality_gate_snapshot`，加 `source` 维度。**
M6 的第 6 项回放依赖 `quality_gate_snapshot` 的 `gate_id/deploy_order_id`。新建一张 sonar 表会让"证据"出现两个源头，追溯链要写两套 join。做法：加列 `source`（默认 `QUALITY_GATE`）/`requirement_id`/`project_key`/`metrics_json`/`metric_status`，并把 `gate_id`、`deploy_order_id` 放宽为 NULL；Sonar 行 `source='SONAR'` 且这两列为 NULL，于是 M6 的所有既有查询（均带 `gate_id=?` 或 `deploy_order_id=?`）**结构上排除** Sonar 行，第 6 项语义零改动。该表无 JPA 实体（deliver 全是原生 SQL），加列/放宽不影响 Hibernate validate。

**ADR-T6：口令加密复用既有线上格式，但把算法提到 platform-common。**
deliver 的 `ClientSecretCodec` 已是 `AES/GCM/NoPadding` + `SHA-256(masterKey)` + 12 字节随机 IV + `v1:` 前缀 Base64(iv‖ct‖tag)。直接复制会得到两份必须同步演进的密码学实现。故提取 `com.example.platform.common.crypto.AesGcmCodec`，req 直接用，deliver 的 `ClientSecretCodec` 改为委托（保持其公开方法签名与 `{plain}` 开发分支不变，既有 openapi 测试不受影响）。

**ADR-T7：密钥来源是环境变量，缺失即启动失败。**
`INTEGRATION_CREDENTIAL_KEY`（首选）→ 缺失时回落到 `PLATFORM_MASTER_KEY`（开发默认值已在 application.yml）→ 两者皆空则 **启动失败**，消息里点名两个变量。为什么允许回落：现有 `mvn test` / CI 不设置集成密钥，强制单独变量会让所有既有测试红掉；而"零明文密钥入库"由 `.run/integration.key`（gitignore）在 `scripts/platform-up.sh` 中生成并导出满足。轮换靠 wire 前缀：`v1:` 解码时按前缀取密钥环（`platform.integration.credential-keys` 可配 `v1=…,v2=…`），写入一律用 `credential-key-version` 指向的版本。

**ADR-T8：准入项开关的"必开"由代码常量锁死，不由数据保证。**
`AdmissionItemPolicy.LOCKED = {REQ_TEST_PASSED, NO_OPEN_HIGH_DEFECT, REQ_APPROVALS_DONE, QUALITY_GATE}`；`PUT /integration/admission-items` 对锁定项写 `enabled=false` 返回 409 `ITEM_LOCKED`。理由：DB 里一条脏数据（或一次误操作）就能把"测试通过"关掉，这是安全红线，必须在写入口拦截而不是靠运维自觉。

**ADR-T9：需求详情的集成卡片走**新**只读端点，不改需求域视图契约。**
`GET /requirements/{id}/integration` 由 integration 包自己的控制器提供。这样 GitLab 任务与 Sonar 任务不需要同时改 `RequirementViews/RequirementService`（两个并行任务抢同一文件 = 必然冲突），也不会因为加字段而打破既有需求详情快照断言。

**ADR-T10：构建/制品/快照的状态机在 Java 里显式建模，DB 只存结果。**
`BuildStateMachine.next(current, incoming)` 是纯函数，非法迁移抛 `OUT_OF_ORDER`，可被单测穷举（3×4 矩阵）。DB 侧只用唯一键保证"一单一条活动构建"，不试图在 SQL 里表达状态机。

**ADR-T11：追溯矩阵新增的两个节点是"数据驱动的 cells key"，不是新列。**
`TraceabilityQuery` 的 `cells` 是 `Map<String,TraceCell>`，加 `build`/`sonar` 两个 key 即可；`TraceRow.gaps` 由同一份 cells 派生，保证"缺口实时计算、不假绿"这条既有不变量不被破坏。

**ADR-T12：前端不引入新的全局 store。**
集成页是低频配置页 + 单实体详情页，沿用既有 `ref + async load` 模式；只有两处需要"跨组件共享"的东西：字典（进 `stores/dict.ts`）与构建状态轮询（进 `composables/useBuildPolling.ts`，随组件卸载自动停）。

---

## 2. 后端分层与包结构

```
req-service/src/main/java/com/example/req/integration/
├─ config/        IntegrationConfigEntity/Repository/Service/Controller/Views
│                 IntegrationSystem(enum)  IntegrationProperties  IntegrationErrors
│                 IntegrationCrypto(密钥环+加解密)
├─ store/         BuildRecordEntity/Repo  MrSyncEntity/Repo  ArtifactRecordEntity/Repo
│                 IntegrationSyncLogEntity/Repo  AdmissionItemConfigEntity/Repo
├─ outbound/      OutboundHttpClient  OutboundRequest/OutboundResponse
│                 RetryPolicy  RetryClassifier
├─ sync/          SyncLogWriter（唯一写日志入口）  SyncLogViews
├─ security/      SensitiveMasker  SsrfGuard  WebhookSignatureVerifier  WebhookRateLimiter
├─ adapter/       IntegrationAdapter(接口) AdapterRegistry AdapterResult AdapterContext
├─ notify/        IntegrationNotifier（委托 platform-common NotificationWriter，失败只记不抛）
├─ jenkins/       JenkinsAdapter JenkinsTriggerService BuildService BuildStateMachine
│                 BuildController BuildViews BuildPollingJob JenkinsWebhookController ConsoleUrlGuard
├─ gitlab/        GitLabAdapter MrUrlParser MrSyncService GitlabWebhookController
│                 MrFallbackPollingJob MrViews MrAdvanceService
├─ sonar/         SonarAdapter SonarSnapshotService SonarThresholds SonarRefreshJob
│                 SonarController SonarViews
├─ artifact/      NexusAdapter ArtifactSearchService ArtifactService ArtifactCoordinates
│                 ArtifactController ArtifactViews
├─ admission/     AdmissionItemPolicy AdmissionItemConfigService AdmissionItemController
│                 IntegrationEvidenceReader BuildEvidence SonarEvidence ArtifactEvidence
└─ release/       ReleaseIntegrationSettingsService（PUT /releases/{id}/integration-settings）
```

依赖方向（硬约束，`logicprobe_structure_verify` 可验）：`adapter → outbound → sync/security`；`jenkins/gitlab/sonar/artifact → adapter`；`admission → store`（**不得**依赖四个适配器，保证准入零外呼）；`config` 被所有人只读依赖，反向零依赖。

---

## 3. 数据模型与存储（V8 迁移）

迁移文件：`req-service/src/main/resources/db/migration/V8__ci_integration.sql`（唯一迁移点，沿用 V5 风格：`CREATE TABLE IF NOT EXISTS`、`information_schema` 判定后 `PREPARE/EXECUTE`、`INT` 存 0/1、`VARCHAR` 存码值、`TEXT/MEDIUMTEXT` 存 JSON）。

### 3.1 新表

**integration_config**（一系统可多份，至多一份启用）
```
id BIGINT PK AI · tenant_id BIGINT NOT NULL · system VARCHAR(16) NOT NULL   -- JENKINS/GITLAB/SONAR/NEXUS
name VARCHAR(64) NOT NULL · base_url VARCHAR(500) NOT NULL
auth_type VARCHAR(16) NOT NULL            -- TOKEN / USERNAME_PASSWORD / NONE
username VARCHAR(128) NULL
credential_enc VARBINARY(1024) NULL · credential_last4 VARCHAR(8) NULL    -- 只写不读
secret_enc VARBINARY(1024) NULL · secret_last4 VARCHAR(8) NULL            -- webhook secret / api token
extra_json TEXT NULL                       -- 具名字段序列化：job_name/project_path/repository/...
enabled INT NOT NULL DEFAULT 0 · active_key VARCHAR(8) NULL                -- 'Y' 且仅当 enabled=1
timeout_ms INT NOT NULL DEFAULT 5000       -- 1000..30000
last_test_at DATETIME(3) NULL · last_test_ok INT NULL · last_test_message VARCHAR(300) NULL
created_at/updated_at DATETIME(3) · created_by/updated_by BIGINT
UNIQUE uk_cfg_active (tenant_id, system, active_key)   -- 每系统仅一条启用；NULL 可重复
KEY idx_cfg_tenant (tenant_id, system)
```

**integration_sync_log**（每次出站尝试一行，含重试）
```
id BIGINT PK AI · tenant_id BIGINT NOT NULL · correlation_id VARCHAR(40) NOT NULL
config_id BIGINT NULL · system VARCHAR(16) NOT NULL
direction VARCHAR(4) NOT NULL              -- OUT / IN
operation VARCHAR(24) NOT NULL             -- TRIGGER/POLL/CALLBACK/REFRESH/SEARCH/PROMOTE/TEST/SYNC
method VARCHAR(8) NULL · target_url VARCHAR(500) NULL
request_summary VARCHAR(1000) NULL         -- 已脱敏（敏感头替换为 ***）
response_status INT NULL · http_status INT NULL
response_summary VARCHAR(1000) NULL        -- 已脱敏
result VARCHAR(16) NOT NULL                -- SUCCESS/DEGRADED/FAILED/RETRY_EXHAUSTED/IGNORED
error_code VARCHAR(40) NULL · retry_count INT NOT NULL DEFAULT 0 · duration_ms INT NOT NULL DEFAULT 0
biz_type VARCHAR(24) NULL · biz_id BIGINT NULL · created_at DATETIME(3) NOT NULL
KEY idx_log_corr (tenant_id, correlation_id) · idx_log_time (tenant_id, created_at) · idx_log_cfg (config_id, created_at)
```

**build_record**（一单至多一条活动构建——唯一键是并发触发的唯一闸门）
```
id BIGINT PK AI · tenant_id BIGINT NOT NULL · deploy_order_id BIGINT NOT NULL
requirement_id BIGINT NULL · config_id BIGINT NOT NULL
job_name VARCHAR(200) NOT NULL · build_number BIGINT NULL · queue_id BIGINT NULL
status VARCHAR(12) NOT NULL                -- QUEUED/RUNNING/SUCCESS/FAILURE/ABORTED
active_key VARCHAR(24) NULL                -- = deploy_order_id 当且仅当 QUEUED/RUNNING，终态置 NULL
stages_json TEXT NULL · console_url VARCHAR(500) NULL
started_at/finished_at DATETIME(3) NULL · duration_ms BIGINT NULL
timeout_at DATETIME(3) NOT NULL · poll_fail_count INT NOT NULL DEFAULT 0
correlation_id VARCHAR(40) NOT NULL · trigger_by BIGINT NULL · created_at/updated_at DATETIME(3)
UNIQUE uk_build_active (tenant_id, active_key)      -- 第二条活动构建撞键 → 409 BUILD_IN_PROGRESS
KEY idx_build_order (tenant_id, deploy_order_id, id DESC) · KEY idx_build_status (status, timeout_at)
```
状态机（`BuildStateMachine`，纯函数）：`QUEUED→RUNNING→SUCCESS|FAILURE|ABORTED`；`QUEUED→FAILURE`（提交即失败/超时）；`RUNNING→FAILURE(BUILD_TIMEOUT)`。任何终态→任何态 = `OUT_OF_ORDER`（不回退）。

**mr_sync**（MR 事实的唯一键 + 单调）
```
id BIGINT PK AI · tenant_id BIGINT NOT NULL · requirement_id BIGINT NOT NULL · config_id BIGINT NOT NULL
gitlab_project VARCHAR(200) NOT NULL · mr_iid BIGINT NOT NULL · mr_url VARCHAR(500) NOT NULL
title VARCHAR(300) NULL · state VARCHAR(16) NOT NULL        -- OPENED/MERGED/CLOSED/LOCKED
source_branch VARCHAR(200) NULL · target_branch VARCHAR(200) NULL
approvals_count INT NOT NULL DEFAULT 0 · additions/deletions INT NULL
last_event_at DATETIME(3) NOT NULL · merged_at DATETIME(3) NULL
payload_json MEDIUMTEXT NULL · created_at/updated_at DATETIME(3)
UNIQUE uk_mr (tenant_id, gitlab_project, mr_iid)
KEY idx_mr_req (tenant_id, requirement_id, last_event_at DESC)
```

**artifact_record**
```
id BIGINT PK AI · tenant_id BIGINT NOT NULL · config_id BIGINT NULL
repo VARCHAR(64) NOT NULL · group_id VARCHAR(128) NOT NULL · artifact_name VARCHAR(128) NOT NULL
version VARCHAR(64) NOT NULL · packaging VARCHAR(16) NOT NULL DEFAULT 'jar' · classifier VARCHAR(32) NULL
sha256 CHAR(64) NOT NULL · download_url VARCHAR(500) NULL · size_bytes BIGINT NULL
source VARCHAR(8) NOT NULL                 -- NEXUS / MANUAL
status VARCHAR(12) NOT NULL DEFAULT 'REGISTERED'   -- REGISTERED/VERIFIED/PROMOTED
promoted_from_id BIGINT NULL · promoted_at DATETIME(3) NULL · promoted_by BIGINT NULL
requirement_id BIGINT NULL · deploy_order_id BIGINT NULL · created_at DATETIME(3) · created_by BIGINT
UNIQUE uk_artifact (tenant_id, repo, group_id, artifact_name, version, packaging, sha256)
UNIQUE uk_promoted (promoted_from_id)               -- 晋级幂等：同一来源只能晋级一次
KEY idx_artifact_order (tenant_id, deploy_order_id)
```

**admission_item_config**
```
id BIGINT PK AI · tenant_id BIGINT NOT NULL · item_code VARCHAR(40) NOT NULL
enabled INT NOT NULL DEFAULT 1 · updated_by BIGINT NULL · updated_at DATETIME(3) NOT NULL
UNIQUE uk_item (tenant_id, item_code)
```

### 3.2 既有表加列（全部幂等）

| 表 | 变更 | 为什么 |
|---|---|---|
| `requirement` | `+gitlab_project VARCHAR(200) NULL`、`+mr_iid BIGINT NULL`、`+sonar_project_key VARCHAR(200) NULL` | MR 结构化与 Sonar key 继承的落点；`mr_url` 保留为"人工填的链接"不变 |
| `release` | `+mr_auto_advance INT NOT NULL DEFAULT 0`、`+sonar_project_key VARCHAR(200) NULL` | AC-5 默认人工确认、AC-6.1 版本级继承 |
| `deploy_order` | `+build_id BIGINT NULL`、`+artifact_id BIGINT NULL` | 准入 ⑦⑨ 的指针（不建物理外键，与既有风格一致） |
| `quality_gate_snapshot` | `+source VARCHAR(16) NOT NULL DEFAULT 'QUALITY_GATE'`、`+requirement_id BIGINT NULL`、`+project_key VARCHAR(200) NULL`、`+metrics_json MEDIUMTEXT NULL`、`+metric_status VARCHAR(16) NULL`；`gate_id`、`deploy_order_id` 放宽为 NULL；`+KEY idx_snap_source (tenant_id, source, requirement_id, id)` | ADR-T5。**不加**唯一键：历史行的 `hash` 可能重复，加唯一约束会让迁移在老库上失败；幂等改由"插入前按 `(tenant_id, source, requirement_id, hash)` 查存在"保证 |

`quality_gate_snapshot` 行语义：`source='QUALITY_GATE'` 时字段含义完全同 M6（`gate_id/deploy_order_id/result/snapshot_json/hash/computed_at`）；`source='SONAR'` 时 `result` 存 `OK|ERROR`（对应 Sonar 的 quality gate status），`metric_status` 存 `OK|ERROR|WARN`，`metrics_json` 存五度量，`hash` 由"五度量+status+project_key"规范化后 SHA-256 得出（同值同 hash ⇒ 幂等不新增行）。

**只增不改**：快照行一旦写入，除 `hash` 幂等去重外不再 UPDATE（AC-6.6）。

---

## 4. API 设计

前缀约定：网关 `/api/req/**` strip 2 → req-service 根路径；下列路径均为 **req-service 内路径**（前端经网关访问时加 `/api/req`）。webhook 例外（见 §4.5）。

### 4.1 集成配置（管理员）

| 方法 | 路径 | 入参 | 出参 | 关键错误 |
|---|---|---|---|---|
| GET | `/integration/configs` | `system?` | `[{id,system,name,baseUrl,authType,username,hasCredential,credentialLast4,hasSecret,secretLast4,extra,enabled,timeoutMs,lastTest}]` | — |
| POST | `/integration/configs` | `{system,name,baseUrl,authType,username?,credential?,secret?,extra?,timeoutMs?}` | 201 同上 | 400 `VALIDATION_FAILED`（base_url 非 http/https、>500、含 userinfo） |
| PATCH | `/integration/configs/{id}` | 同上，`credential/secret` 缺省或 `""` = 不变；`clearCredential:true` 时必须同时给 `confirmName` 且等于 `name` | 同上 | 400 `CONFIRM_NAME_MISMATCH`、409 `ITEM_LOCKED`（不存在此码，见下） |
| DELETE | `/integration/configs/{id}` | — | 204 | 404 |
| POST | `/integration/configs/{id}/test` | — | `{ok,latencyMs,code,message,checkedAt}` | 200 恒返回（失败 `ok:false`），单次尝试 |
| GET | `/integration/logs` | `system?result?correlationId?from?to?page=1&size=20` | `{items:[{id,correlationId,system,operation,method,targetUrl,requestSummary,responseSummary,httpStatus,result,errorCode,retryCount,durationMs,createdAt}],total,page,size}` | — |
| GET | `/integration/logs/{id}` | — | 单条明细 | 404 |

`credential/secret` 的响应**只有** `hasX/xxxLast4`，永不返回密文或明文（AC-1.2 / AC-10.1）。`extra` 的具名字段按系统写死并服务端校验：JENKINS `{jobName}`；GITLAB `{projectPath, webhookSecret 走 secret 字段}`；SONAR `{projectKey?}`；NEXUS `{repository, allowedHosts?}`。

### 4.2 准入项开关

| 方法 | 路径 | 入参 | 出参 | 错误 |
|---|---|---|---|---|
| GET | `/integration/admission-items` | — | `[{code,name,order,locked,enabled,description}]`（恒 9 条） | — |
| PUT | `/integration/admission-items` | `{items:[{code,enabled}]}` | 同上 | 409 `ITEM_LOCKED`（`detail.itemCode`）、400 `VALIDATION_FAILED`（未知 code） |

### 4.3 构建（Jenkins）

| 方法 | 路径 | 入参 | 出参 | 错误 |
|---|---|---|---|---|
| POST | `/deploy-orders/{id}/builds` | `{branch?}` | 202 `{buildId,status:"QUEUED",correlationId}` | 409 `BUILD_IN_PROGRESS`、409 `INTEGRATION_DISABLED`、400 `BUILD_NOT_CONFIGURED` |
| GET | `/deploy-orders/{id}/builds` | `limit=5`（1..20） | `{items:[BuildView]}` 倒序 | — |
| GET | `/builds/{id}` | — | `BuildView` | 404 |
| POST | `/builds/{id}/refresh` | — | `BuildView`（原地拉一次 Jenkins；不可用则返回现有状态 + `degraded:true`） | 404 |

`BuildView`：`{id,deployOrderId,status,jobName,buildNumber,queueId,queuedAt,startedAt,finishedAt,durationMs,stages:[{name,status,durationMs}],consoleUrl,timeoutAt,retries,timedOut}`。
`console_url` 落库前过 `ConsoleUrlGuard`：必须与配置 `base_url` **同源**（scheme+host+port），否则存 NULL 并记 `CONSOLE_URL_REJECTED`（AC-3.9），构建状态不受影响。

### 4.4 MR / Sonar / 制品

| 方法 | 路径 | 入参 | 出参 | 错误 |
|---|---|---|---|---|
| POST | `/requirements/{id}/mr-sync` | `{mrUrl}` | `MrCard` | 400 `MR_URL_INVALID` |
| GET | `/requirements/{id}/mr` | — | `MrCard`（无则 `null`） | — |
| POST | `/requirements/{id}/mr/confirm-merge` | `{note?}` | `{requirementId,status,advanced:true}` | 409 `STATE_TRANSITION_REJECTED` |
| GET | `/requirements/{id}/integration` | — | `{code:MrCard|null, sonar:SonarCard|null, sync:[最近 10 条日志]}` | — |
| PUT | `/releases/{id}/integration-settings` | `{mrAutoAdvance?, defaultSonarProjectKey?}` | `{releaseId,mrAutoAdvance,defaultSonarProjectKey}` | 400 `SONAR_KEY_INVALID` |
| POST | `/requirements/{id}/sonar/refresh` | — | `SonarCard`（不可用 → `degraded:true` + `reason`） | 400 `SONAR_KEY_INVALID`、409 `INTEGRATION_DISABLED` |
| GET | `/requirements/{id}/sonar` | — | `SonarCard`（最新快照 + 五度量 + 是否过期） | — |
| GET | `/integration/artifacts/search` | `q,repository?,version?,limit=20`（≤50） | `{items:[ArtifactItem],degraded,reason}` | 409 `INTEGRATION_DISABLED` |
| POST | `/deploy-orders/{id}/artifacts` | `{artifactId}` 或 `{manual:{repo,groupId,artifactName,version,packaging,sha256}}` | `ArtifactView` | 400 `ARTIFACT_COORDINATES_INVALID`、400 `SHA256_INVALID`、409 `ARTIFACT_ALREADY_BOUND` |
| GET | `/deploy-orders/{id}/artifacts` | — | `ArtifactView|null` | — |
| POST | `/artifacts/{id}/promote` | `{targetRepo}` | `{artifactId,promotedFromId,status:"PROMOTED"}` | 409 `CHECKSUM_MISMATCH`、409 `ARTIFACT_ALREADY_PROMOTED`（幂等返回既有行时 200） |

`MrCard`：`{gitlabProject,mrIid,mrUrl,title,state,sourceBranch,targetBranch,approvalsCount,additions,deletions,lastEventAt,mergedAt,stale}`。
`SonarCard`：`{projectKey,status:"OK|ERROR",metrics:{coverage,bugs,vulnerabilities,codeSmells,duplicatedLinesDensity},snapshotId,hash,fetchedAt,stale,skipReason?}`。
`ArtifactView`：`{id,repo,groupId,artifactName,version,packaging,sha256,source,status,downloadUrl,sizeBytes,promotedFromId,promotedAt}`。

### 4.5 Webhook（匿名，网关级放行）

| 方法 | 路径（网关） | 必带头 | 语义 |
|---|---|---|---|
| POST | `/api/integration/webhooks/jenkins/{configId}` | `X-Jenkins-Signature: sha256=<hex>`、`X-Jenkins-Timestamp`、`X-Jenkins-Event` | HMAC-SHA256(secret, timestamp + "." + rawBody) |
| POST | `/api/integration/webhooks/gitlab/{configId}` | `X-Gitlab-Token`、`X-Gitlab-Event`、`X-Gitlab-Event-UUID?` | 常量时间比较 secret（GitLab 原生 Secret Token，无时间戳；时间戳窗口规则只适用于 HMAC 系统） |

网关：`PlatformRoutes` 新增 order **-95** 的 `/api/integration/**` → `stripPrefix(1)` → `lb://req-service`；`JwtGatewayFilter.ANONYMOUS_PREFIXES` 追加 `"/api/integration/webhooks/"`（只放行这一前缀，管理端接口仍要 JWT）。
req-service 内控制器：`@RequestMapping("/integration/webhooks")`。

响应约定（AC-2.7 / AC-4.3）：
- 验签失败 / 时间戳偏离 ±300s → **401** `WEBHOOK_SIGNATURE_INVALID` / `WEBHOOK_TIMESTAMP_INVALID`，**不写任何业务行**（只写一条 `direction=IN,result=FAILED` 的日志，不含 body）；
- 未知事件类型（GitLab 只认 `Merge Request Hook`/`Push Hook`/`Pipeline Hook`）→ **200** `{"ignored":true,"reason":"UNKNOWN_EVENT"}` + `IGNORED` 日志；
- 重复事件 → **200** `{"ignored":true,"reason":"DUPLICATE"}`；
- 乱序（终态后又来 RUNNING）→ **200** `{"ignored":true,"reason":"OUT_OF_ORDER"}`，状态不回退（Jenkins 收到 4xx 会重投，所以这里刻意用 200）；
- 频率：每 configId 20 req/s 令牌桶，超出 **429** `WEBHOOK_RATE_LIMITED`（`WebhookRateLimiter`，内存即可，单实例部署）。

### 4.6 准入（既有路径 + 扩展字段）

`GET /deploy-orders/{id}/admission`、`POST /deploy-orders/{id}/evaluate`、`POST /deploy-orders/{id}/submit` 路径不变；响应扩展：
```json
{
  "items":[{"code":"BUILD_PASSED","name":"构建通过","order":7,"status":"PASS",
            "passed":true,"detail":"build #42 SUCCESS","fixHint":"…","reasonCode":null,"evidence":{...}}],
  "counts":{"passed":6,"failed":0,"skipped":0,"closed":3,"active":6,"total":9},
  "blocking":false,"skipCount":0,"checkedAt":"…","stale":false,"signature":"…","legacy":false
}
```
`SubmitCmd` 增加 `acknowledgedSkipCount`：`skipCount>0` 且未等值确认 → 400 `SKIP_NOT_ACKNOWLEDGED`（`detail.skipCount`）。既有调用方不传该字段，`skipCount=0` 时通过（回归零破坏）。

---

## 5. 适配层契约（dev 必须逐字调用）

```java
// com.example.req.integration.adapter.IntegrationAdapter
public interface IntegrationAdapter {
    IntegrationSystem system();
    AdapterResult testConnection(IntegrationConfigEntity config);   // 单次尝试，不抛传输异常
}

// AdapterResult
public record AdapterResult(boolean ok, int latencyMs, String code, String message) {}

// com.example.req.integration.outbound.OutboundHttpClient
public OutboundResponse exchange(OutboundRequest request);          // 单次；交互读用
public OutboundResponse exchangeWithRetry(OutboundRequest request); // 全策略；仅后台路径用

// OutboundRequest
public record OutboundRequest(long tenantId, SystemType system, String operation,
        HttpMethod method, String url, Map<String,String> headers, Object body,
        Duration connectTimeout, Duration readTimeout, RetryPolicy retry,
        String correlationId, String bizType, Long bizId) {}

// OutboundResponse
public record OutboundResponse(int httpStatus, String body, long durationMs,
        int attempts, String errorCode, boolean transportFailure) {}
```

`RetryPolicy`：`public static RetryPolicy none()` / `public static RetryPolicy standard()`；
`RetryClassifier.shouldRetry(int httpStatus, boolean transportFailure)` → 仅 `transportFailure`（连接失败/超时）或 `5xx`/`429` 为 true；`4xx`（除 429）**不重试**（AC-2.1）。
`OutboundHttpClient` 内部：`SsrfGuard.check(url)` → 逐次写 `integration_sync_log` → `SensitiveMasker.mask(...)` 处理请求/响应摘要（`Authorization`、`X-Gitlab-Token`、`X-Jenkins-Signature`、`private-token`、`password` 一律 `***`；URL query 里的 `token/secret/private_token` 直接剔除）→ 重试用 `Thread.sleep(backoff · jitter)`，`backoff = 1<<attempt` 秒，抖动 ±20%。

四个适配器的公开方法（签名固定）：

```java
// JenkinsAdapter
public BuildViews.JenkinsSubmit submit(IntegrationConfigEntity config, String jobName,
        Map<String,Object> params, String correlationId);
public BuildViews.JenkinsStatus status(IntegrationConfigEntity config, long buildNumber);

// GitLabAdapter
public MrViews.MrPayload fetchMergeRequest(IntegrationConfigEntity config, String project, long iid);
public List<MrViews.MrPayload> recentMergeRequests(IntegrationConfigEntity config, String project, int days);

// SonarAdapter
public SonarViews.SonarMeasures measures(IntegrationConfigEntity config, String projectKey);

// NexusAdapter
public List<ArtifactViews.ArtifactItem> search(IntegrationConfigEntity config, String query, int limit);
public String checksum(IntegrationConfigEntity config, String coordinates);   // 返回 sha256 或 null
```

---

## 6. 安全实现要点

1. **凭据只写不读**：`IntegrationCrypto.encode(String) -> byte[]`、`decode(byte[]) -> String`；实体字段 `credential_enc` 无 getter（只有 `credentialLast4` 的 getter），序列化层永远拿不到密文。日志/审计里 `credential` 恒为 `"***" + last4`。
2. **审计**：`INTEGRATION_CONFIG_CREATED/UPDATED/DELETED/TESTED`、`ADMISSION_ITEM_TOGGLED`、`BUILD_TRIGGERED`、`MR_SYNCED`、`REQ_MR_MERGED_ADVANCE`、`SONAR_PULLED`、`ARTIFACT_SELECTED`、`ARTIFACT_PROMOTED`，经 `platform-common AuditService.record(action, bizType, bizId, detail)`；webhook 拒绝路径用 `recordInNewTx`（无业务事务也要留证）。
3. **SSRF**：`SsrfGuard.check(url)` 拒绝非 `http/https`、含 userinfo、协议为 `file:/gopher:/jar:`、解析到回环/私网/链路本地（`169.254.0.0/16`、`10/8`、`172.16/12`、`192.168/16`、`::1`、`fc00::/7`）。`platform.integration.allow-loopback=true` 只额外放行 `127.0.0.0/8` 与 `::1`（本地桩需要），**永不放行 169.254**。重定向最多 3 跳且必须同 host，跨主机 → `REDIRECT_BLOCKED`（AC-7.6）。
4. **令牌桶**：`WebhookRateLimiter.allow(long configId)`，20/s，滑动窗口 1s。
5. **零明文密钥入库**：`INTEGRATION_CREDENTIAL_KEY` 由 `scripts/platform-up.sh` 从 `.run/integration.key`（gitignore，首次 `openssl rand -base64 32` 生成）导出；CI 走 secret。
6. **导出/日志 0 命中**：`SensitiveMasker` 是唯一摘要生成器；`AdminAuditView` 的导出沿用既有实现，集成审计 detail 只放 `changed` 字段名与末四位。

---

## 7. 准入 9 项

### 7.1 固定顺序与代码

| # | code | 名称 | 开关 | 数据源（本地） |
|---|---|---|---|---|
| 1 | `REQ_TEST_PASSED` | 需求测试通过 | **锁定开** | M6 既有 |
| 2 | `NO_OPEN_HIGH_DEFECT` | 无 P0/P1 缺陷 | **锁定开** | M6 既有 + 构建失败转缺陷 |
| 3 | `REQ_APPROVALS_DONE` | 需求级审批通过 | **锁定开** | M6 既有 |
| 4 | `MR_FILLED` | MR 链接已填 | 可关 | M6 既有（`mr_url` 或 `mr_sync`） |
| 5 | `MAN_DAYS_FILLED` | 人天已填 | 可关 | M6 既有 |
| 6 | `QUALITY_GATE` | 质量门通过 | **锁定开** | M6 既有（语义不变） |
| 7 | `BUILD_PASSED` | 构建通过 | 可关 | `build_record` 最近一条 |
| 8 | `SONAR_GATE_PASSED` | Sonar 质量门通过 | 可关 | `quality_gate_snapshot(source='SONAR')` 最新 |
| 9 | `ARTIFACT_VERIFIED` | 制品已校验 | 可关 | `deploy_order.artifact_id` → `artifact_record` |

> ⚠️ **口径变更**：PRD AC-8.1 把"需求级审批"放到 ③、"MR 已填"放到 ④，与 M6 的 `[test,noP0,mr,manDays,approvals,QG]` 相比 3/4/5 位置调换。第 6 项仍是质量门，语义不变。`scripts/tests/quality/q4-gate-admission.sh:38` 的顺序断言必须同步改写（属于 T11 的文件）。

### 7.2 四态判定表（`AdmissionItem.status`）

| 情况 | ⑦ 构建 | ⑧ Sonar | ⑨ 制品 |
|---|---|---|---|
| 项开关关闭（管理端） | `CLOSED` / `ITEM_OFF` | 同 | 同 |
| 系统配置缺失或 `enabled=0` | `CLOSED` / `INTEGRATION_DISABLED` | 同 | 同 |
| 已启用但未配 key（仅 Sonar） | — | `SKIP` / `NOT_CONFIGURED` | — |
| 已启用但无事实 | `FAIL` / `NO_BUILD_RECORD` | `FAIL` / `SNAPSHOT_MISSING` | `FAIL` / `ARTIFACT_MISSING` |
| 事实存在但不满足 | `FAIL` / `BUILD_FAILED`\|`BUILD_IN_PROGRESS`\|`BUILD_TIMEOUT` | `FAIL` / `SONAR_GATE_ERROR`\|`METRIC_BELOW_THRESHOLD` | `FAIL` / `CHECKSUM_MISMATCH`\|`ARTIFACT_NOT_VERIFIED` |
| 事实满足 | `PASS` | `PASS` | `PASS` |
| 取数异常（DB/解析） | `FAIL` / `EVIDENCE_UNAVAILABLE` | 同（含 `ADAPTER_UNAVAILABLE`） | 同 |

不变量：
- `passed == (status == PASS)`（`passed` 字段保留，仅为不破坏既有前端/断言与历史 JSON 反序列化；历史快照无 `status` 时按 `passed?PASS:FAIL` 兜底）。
- **SKIP 永不计 PASS**；`percentage = passed / active`，`active = 9 - closed`，`skipped>0` 时前端不得出现 100%/`9/9`。
- 提交闸门 = `items.noneMatch(status==FAIL)`；`CLOSED` 不阻断、不计分母。
- `fail-closed`：准入侧任何"拿不准"都落到 FAIL，绝不落到 SKIP。

### 7.3 500ms 预算

`AdmissionEvaluator` 持一个 `Deadline`（`System.nanoTime()` 起算，总 500ms），每次远程读之前把 `remaining` 传给下游客户端，`remaining < 30ms` 则直接该证据 FAIL(`ADAPTER_TIMEOUT`)：

| 步骤 | 上限 |
|---|---|
| 门禁证据（`DeliverGateClient`） | min(250, remaining) |
| 高优缺陷（`DeliverGateClient`/缺陷查询） | min(200, remaining) |
| 质量门快照（`DeliverQualityGateClient`） | min(400, remaining) |
| ⑦⑧⑨ 本地读（3 条走索引的单行查询） | ≤30ms 合计 |
| 序列化 + 签名 | ≤10ms |

准入**不发起任何外部系统调用**（Jenkins/GitLab/Sonar/Nexus 一律不碰）——这是 AC-8.6 与 AC-2.3 的结构性保证。

### 7.4 快照与 stale

`AdmissionSnapshot` 增 `counts`、`skipCount`、`partial`（`items.size() < 9`）。`signature` 改为 `sha256(orderId|env|artifactVersion|mrIid|每项 code:status:evidenceHash)`；`stale = stored.signature != current.signature`。历史 5/6 项快照：`partial=true`，前端显示"历史快照（N 项）· 重新校验后显示 9 项"，并可一键重新校验（旧行为完全保留）。

---

## 8. 前端

### 8.1 文件与路由

| 类别 | 文件 |
|---|---|
| API | `web/src/api/integration.ts`（新）、`web/src/api/types.ts`（追加类型） |
| 状态 | `web/src/stores/dict.ts`（新增 `integration/build/mr/sync/admission/sonar/artifactSource` 字典）、`web/src/composables/useBuildPolling.ts` |
| 样式 | `web/src/styles/tokens.css`（追加 M7 令牌，只加不改） |
| 路由 | `web/src/router/routes/integration.routes.ts`（新，glob 自动装载）、`web/src/router/nav.ts`、`web/src/layouts/AdminLayout.vue`（二级菜单） |
| 管理页 | `web/src/views/admin/AdminIntegrationsView.vue` + `web/src/views/admin/integrations/{ConfigTab,AdmissionTab,LogsTab}.vue` |
| 集成组件 | `web/src/components/integration/{IntegrationConfigCard,CredentialField,ConnectionTestBar,AdmissionItemTable,SyncLogTable,HelpDrawer,BuildStatusCard,ArtifactSummaryCard,ArtifactPickerDrawer,PromoteDialog}.vue` |
| 需求组件 | `web/src/components/req/{CodeCard,SonarGateCard,SonarSnapshotDrawer}.vue` |
| 改造页 | `web/src/views/deploy/DeployOrderDetailView.vue`、`web/src/views/req/RequirementDetailView.vue`（+ `views/req/tabs/IntegrationTab.vue`）、`web/src/views/release/ReleaseDetailView.vue`、`web/src/views/deliver/TraceabilityView.vue`、`web/src/components/TraceMatrix.vue`、`web/src/components/GateChecklist.vue` |

路由：`/admin/integrations`（`?tab=config|admission|logs&correlationId=`），权限 `integration:manage`。
导航：`nav.ts` 增 `admin-integrations`（icon `plug`）；`AdminLayout` 侧栏与既有 admin 页同构。

### 8.2 状态管理

- **不新增 Pinia store**：三个 tab 各自 `ref` 持有列表/分页/筛选，`useListQuery` 复用既有分页 composable。
- **字典**：`dict.ts` 提供 `statusText('build', s)` 等；`CLOSED/SKIP` 文案与颜色只在字典里定义一次，`GateChecklist` 与 `AdmissionItemTable` 共用（避免两处文案漂移）。
- **轮询**：`useBuildPolling(orderId)` → 仅当存在 `QUEUED/RUNNING` 时每 5s 拉 `GET /deploy-orders/{id}/builds?limit=5`；终态或 `onUnmounted` 立即 `clearInterval`；页签隐藏（`document.hidden`）暂停。
- **错误**：`ApiError.isConflict`（409）不弹全局 toast，就地展示 `detail`（`BUILD_IN_PROGRESS` 展示"进行中构建 #42"、`ITEM_LOCKED` 展示"该项为必开项"、`CHECKSUM_MISMATCH` 展示两侧 sha256 前 12 位）。401 webhook 与前端无关。
- **凭据输入**：`CredentialField` 只持有明文输入（`type=password`，提交后清空本地值），展示层只有末四位 chip；"清除"走二次确认输入系统名。

### 8.3 三处关键交互

1. **准入清单**：`GateChecklist` 四态 pill（64px 同宽防跳动）+ 计数条"通过 x · 失败 y · 跳过 z · 关闭 w"；存在跳过时提交按钮文案为"提交（跳过 z 项）"，确认框要求勾选"我确认跳过 z 项"→ 传 `acknowledgedSkipCount`。
2. **构建卡**：右列 320px，状态/阶段 chip/最近 5 条/console 链接（无链接时提示"来源未提供同源链接"）；2h 超时行黄条"已超时转为失败"。
3. **制品**：检索抽屉（720）→ 选中后主列制品卡显示 sha256 前 12 位 chip 与 `MANUAL` 黄标 → 晋级二次确认 → 成功后 `CHECKSUM_MISMATCH` 红色内联错误。

---

## 9. 关键实现点与边界

1. **并发触发**：靠 `uk_build_active(tenant_id, active_key)` 的 `DuplicateKeyException` → 409 `BUILD_IN_PROGRESS`（不用内存锁，多实例安全）。
2. **构建结束清理 active_key**：终态写入与 `active_key=NULL` 必须在同一事务；先置空再写终态会开出瞬时并发窗口。
3. **2h 超时**：`timeout_at = created_at + 2h`（配置 `platform.integration.build.timeout-hours=2`），由轮询任务扫描 `status IN ('QUEUED','RUNNING') AND timeout_at < NOW(3)` → `FAILURE` + `reason=BUILD_TIMEOUT` + 转缺陷。
4. **轮询节奏**：`@Scheduled(fixedDelay=10000)`（`fixedDelay` 而非 `fixedRate`，天然不重叠）；单次 tick 内每条活动构建只做一次尝试，`poll_fail_count` 连续失败 5 次 → 写 `RETRY_EXHAUSTED` 日志 + 通知触发人，并把该构建的轮询间隔降级为每 6 次 tick 一次（仍受 2h 超时兜底）。
5. **构建失败转缺陷**：调用 deliver `POST /internal/defects/build-failure`，幂等键 `job#buildNumber`；`source=BUILD`、描述含 `job #buildNumber` 与 console 链接。重复回调不产生第二条缺陷（AC-3.8）。
6. **MR 单调**：upsert 时 `incoming.last_event_at <= stored.last_event_at` 直接丢弃（200 `DUPLICATE`/`OUT_OF_ORDER`），避免 GitLab 重投把 `MERGED` 打回 `OPENED`。
7. **merged 推进**：`MrAdvanceService` 只在 `release.mr_auto_advance=1` 时自动推进；推进只动"需求状态机允许的 RD 状态"，非法则 409 `STATE_TRANSITION_REJECTED` 并写 `MR_ADVANCE_REJECTED` 日志（**不**静默）。
8. **Sonar 五度量缺失**：任一缺失 → `FAIL(METRIC_MISSING)`（不猜 0），`metrics_json` 仍记录已拿到的值。
9. **Nexus 校验和**：选取制品时取 Nexus 给的 `sha256`（`X-Checksum-Sha256` 或检索响应字段）；晋级时对目标仓库再取一次，两者与 `artifact_record.sha256` 三者一致才写；否则 409 `CHECKSUM_MISMATCH`，`detail` 带三段值。
10. **手工兜底**：`source=MANUAL` 必须人工填 sha256（64 hex 校验），准入第 9 项对 MANUAL 与 NEXUS **同等**校验（不因手工放宽）。
11. **租户隔离**：所有仓储方法首参 `tenantId`；webhook 从 `configId` 反查 `tenant_id` 后显式 `TenantContext` 注入（无 JWT 场景），跨租户 id → 404（AC-10.3）。
12. **时区/精度**：一律 `LocalDateTime` + `DATETIME(3)`，与既有实体一致；比较用 `Instant` 只在计算 duration 时使用。
13. **幂等日志**：`correlation_id` 由触发动作生成，重试共享同一 id；同一 `(correlation_id, retry_count)` 不重复写（`SyncLogWriter` 内按 attempt 序号写，天然唯一）。
14. **默认关闭**：`integration_config` 空 ⇒ `AdapterRegistry.find(system)` 返回空 ⇒ 触发类接口 409 `INTEGRATION_DISABLED`、准入 ⑦⑧⑨ `CLOSED`；后台任务扫描不到活动构建即空转，60s 内零 OUT 日志（AC-9.6）。

---

## 10. 测试策略

### 10.1 单测（`platform-unit`，JUnit 5，不起容器）
`AdmissionPolicyM7Test`（9 项顺序/锁定/四态/提交闸门/百分比）、`BuildStateMachineTest`（3×4 迁移矩阵穷举）、`RetryClassifierTest`（4xx 不重试、5xx/429/超时重试、最多 5 次）、`SensitiveMaskerTest`（四类头 + URL query）、`SsrfGuardTest`（169.254/私网/file:/userinfo/跨主机重定向）、`MrUrlParserTest`（嵌套 group、`!123` 与 `/-/merge_requests/123` 两种形态、非法串）、`ArtifactCoordinatesTest`、`SonarThresholdsTest`、`AdmissionSignatureTest`（stale 判定）。

### 10.2 集成脚本（`make integration-verify`）

```
scripts/integration-verify.sh          # 编排：起桩 → 跑 s0..s7 → 汇总 → trap 收尾
scripts/tests/integration/stub/server.mjs   # Node 桩，127.0.0.1:19090
scripts/tests/integration/s0-config.sh      # 配置底座/凭据/409/审计
scripts/tests/integration/s1-build.sh       # Jenkins 触发/状态机/超时/转缺陷/回调验签
scripts/tests/integration/s2-mr.sh          # GitLab 解析/webhook/幂等/推进
scripts/tests/integration/s3-sonar.sh       # key 继承/五度量/三态/快照幂等/追溯
scripts/tests/integration/s4-artifact.sh    # 检索/唯一键/sha256/晋级/SSRF
scripts/tests/integration/s5-admission.sh   # 9 项/开关/跳过/关闭/fail-closed/P95
scripts/tests/integration/s6-security.sh    # 凭据 0 命中/跨租户/默认关闭 60s
scripts/tests/integration/s7-regression.sh  # 既有门禁抽查
```
桩按要求提供：`/job/{name}/build`（可 `trap` 返回 5xx/429/超时/慢响应）、`/job/{name}/{n}/api/json`、`/api/v4/projects/...`、`/api/qualitygates/project_status`、`/service/rest/v1/search`、`/health`、`/#SUMMARY` 兼容输出。桩只被脚本引用，不进入任何产品代码路径（AC-9.3）。

断言预算（≥48，四适配器各 ≥6）：s0=10、s1=9、s2=8、s3=7、s4=9、s5=9、s6=6。
脚本以 `#SUMMARY integration-verify pass=N total=N fail=N` 结尾，`TOTAL==0` 守卫失败（沿用 `scripts/quality-verify.sh` 约定）。

### 10.3 既有门禁回归

| 门禁 | 影响 | 处理 |
|---|---|---|
| `scripts/tests/quality/q4-gate-admission.sh:36-38` | 断言固定 6 项与旧顺序 | T11 改为 9 项 + 新顺序（第 6 项仍是 `QUALITY_GATE`） |
| `q5-waiver-replay.sh:200/209/217` | 历史 5 项快照回放 | **不动**：快照是存量 JSON，`partial` 兜底后仍 5 行 |
| `make platform-verify`（167）/`platform-test-negative`（25）/`quality-verify`（347）/`lint`（14） | 提交路径、准入 6 项语义 | 默认关闭 ⇒ ⑦⑧⑨ `CLOSED`，`ADMISSION_FAILED` 仍由 6 项决定，预期全绿（T12 自检必须实跑） |
| `cd web && npm run typecheck && npm run build` | `GateChecklist` props/字典 | 新 props 全部可选，旧调用点零改动 |

---

## 11. 任务拆分（按文件边界，含 git 动作）

> 无 `[PIPELINE-DISPATCHED TASKS]` 下发，以下为并行任务表。`files` = 独占修改；`reads` = 只读依赖，不阻断并行。
> 波次：W1 = T1/T8；W2 = T2/T9；W3 = T3/T4/T5/T6/T7；W4 = T10/T11；W5 = T12。

| ID | 任务 | files（独占） | reads | 验收要点 |
|---|---|---|---|---|
| **T0** | **文档提交（先于实现，PRD §9）** | — | — | `git add docs/teamflow/20261109-r5-ci-integration-adapters && git commit -m "docs: M7 CI 集成对接 PRD/设计/技术方案"`；**单独一个 commit**，不夹带实现文件 |
| T1 | 数据底座 + 网关 + 密钥 | `req-service/src/main/resources/db/migration/V8__ci_integration.sql`、`req-service/.../integration/config/{IntegrationSystem,IntegrationConfigEntity,IntegrationConfigRepository,IntegrationProperties,IntegrationCrypto}.java`、`req-service/.../integration/store/*.java`（5 实体+5 仓储）、`platform-common/.../common/crypto/AesGcmCodec.java`、`deliver-service/.../openapi/ClientSecretCodec.java`、`api-gateway/.../{PlatformRoutes,JwtGatewayFilter}.java`、`req-service/src/main/resources/application.yml`、`scripts/platform-up.sh`、`.gitignore` | PRD §3 | V8 可重复执行（跑两次不报错）；`ddl-auto=validate` 起得来；网关 `/api/integration/webhooks/` 免 JWT 其余仍需；`.gitignore` 增 `scripts/tests/integration/.stub-run/`、`*.pid`、`test-results/`、`playwright-report/`、`.run/` |
| T2 | 出站客户端 + 日志 + 脱敏 + SSRF + 适配器接口 | `req-service/.../integration/outbound/*.java`、`.../integration/sync/*.java`、`.../integration/security/*.java`、`.../integration/adapter/*.java`、`.../integration/notify/IntegrationNotifier.java`、`.../integration/IntegrationAsyncConfig.java` | T1 实体、application.yml | 单次/重试两条入口；每次尝试一行日志；脱敏单测全绿；`@EnableScheduling` 只在此处 |
| T3 | Jenkins 适配器 + 构建 | `req-service/.../integration/jenkins/*.java`、`deliver-service/.../defect/{DefectInternalController,DefectService}.java`（增量方法） | T1/T2、`DeployOrderRepository` | 202+QUEUED；409 `BUILD_IN_PROGRESS`（撞唯一键）；状态机不回退；2h 超时；转缺陷幂等；console 同源校验 |
| T4 | GitLab 适配器 + MR + merged 推进 | `req-service/.../integration/gitlab/*.java`、`.../integration/release/ReleaseIntegrationSettingsService.java` | T1/T2 | 两种 URL 解析；webhook 401 不落库；未知事件 IGNORED；`uk_mr` + 单调；`mrAutoAdvance` 默认 false |
| T5 | Sonar 适配器 + 快照 + 追溯节点 | `req-service/.../integration/sonar/*.java`、`deliver-service/.../trace/TraceabilityQuery.java` | T1/T2、`quality_gate_snapshot` | 三态；跳过原因具体；快照只增；`cells` 增 `build`/`sonar` 且 `gaps` 同源派生 |
| T6 | Nexus 适配器 + 制品 | `req-service/.../integration/artifact/*.java` | T1/T2 | 检索 ≤50；唯一键；sha256 三方一致；晋级幂等；SSRF 目标 0 请求 |
| T7 | 准入 9 项 | `req-service/.../integration/admission/*.java`、`req-service/.../deploy/{AdmissionItem,AdmissionPolicy,AdmissionEvaluator,DeployViews,DeployOrderService,DeployOrderController,DeployErrors}.java` | T1 仓储、T2 无关 | 顺序/锁定/四态/SKIP 不计数/`ITEM_LOCKED`/fail-closed/500ms 预算/历史快照 `partial` |
| T8 | 前端底座 | `web/src/api/integration.ts`、`web/src/api/types.ts`、`web/src/stores/dict.ts`、`web/src/styles/tokens.css`、`web/src/router/routes/integration.routes.ts`、`web/src/router/nav.ts`、`web/src/layouts/AdminLayout.vue`、`web/src/composables/useBuildPolling.ts` | PRD §4、DESIGN §2 | 类型与后端 DTO 逐字段对齐；字典 7 个 kind；路由可直达且权限生效 |
| T9 | 管理端集成页 | `web/src/views/admin/AdminIntegrationsView.vue`、`web/src/views/admin/integrations/*.vue`、`web/src/components/integration/*.vue` | T8 | 三 tab；凭据末四位/清除二次确认；锁定项置灰并解释；日志按 correlation 聚合 |
| T10 | 业务页改造 | `web/src/components/GateChecklist.vue`、`web/src/components/req/{CodeCard,SonarGateCard,SonarSnapshotDrawer}.vue`、`web/src/views/req/{RequirementDetailView.vue,tabs/IntegrationTab.vue}`、`web/src/views/deploy/DeployOrderDetailView.vue`、`web/src/views/release/ReleaseDetailView.vue`、`web/src/views/deliver/TraceabilityView.vue`、`web/src/components/TraceMatrix.vue` | T8/T9 组件、DESIGN | 四态计数条；跳过确认传 `acknowledgedSkipCount`；构建轮询；追溯 8 节点 |
| T11 | 桩 + 验证脚本 + Makefile + 接入文档 | `scripts/integration-verify.sh`、`scripts/tests/integration/**`、`scripts/tests/quality/q4-gate-admission.sh`、`Makefile`、`README.md` | T3–T7 接口 | ≥48 断言、四适配器 ≥6、`#SUMMARY` 行、桩不进产品路径、q4 改 9 项、README 接入章节 + 差异表 ≥6 行 |
| T12 | 交付自检（**只自检，不写 QA/ACCEPTANCE**） | 发现即修（限本需求文件） | 全部 | 实跑 `make platform-up && make integration-verify && make quality-verify && make platform-verify && make platform-test-negative && make lint && make platform-unit && (cd web && npm run typecheck && npm run build)`；记录结论，不产出 QA 报告 |
| **T13** | **实现提交（最后）** | — | — | `git add -A && git commit -m "feat: M7 CI 集成对接（四适配器 + 准入 9 项）"`；不 rebase main、不新建分支 |

并行安全性：T3/T4/T5/T6/T7 修改的文件集两两不相交（T5 额外独占 deliver 的 trace 文件，T3 独占 deliver 的 defect 两个文件）；T2 先于 T3–T7；T8 先于 T9/T10；T11 依赖 T3–T7 的接口契约（可先写桩与骨架，按契约对接）。

---

## 12. 风险与取舍

| 风险 | 影响 | 处置 |
|---|---|---|
| 准入顺序 3/4/5 变更 | 既有 `q4` 顺序断言失败 | T11 同步改写；前端顺序由 `ORDER` 单点驱动 |
| `quality_gate_snapshot` 加列 + 放宽 NOT NULL | M6 第 6 项回放被污染 | 所有 Sonar 行 `gate_id/deploy_order_id` 为 NULL，M6 查询结构上排除；T12 实跑 `q5` 验证 5 项快照仍原样 |
| 500ms 预算 vs 三个内部读 | 超预算即 fail-closed 误伤 | `Deadline` 逐段收缩 + 本地读索引化；P95 在 `s5` 里量 20 次 |
| 重试发生在后台线程 | 线程饥饿 | 专用 `integration-task` 线程池（core 4 / max 8 / queue 200 / 拒绝时写 `RETRY_EXHAUSTED` 不抛给用户） |
| webhook 无 JWT | 伪造事件 | HMAC/Secret 常量时间比较 + 时间戳窗口 + 令牌桶 + 只写本租户行；验签失败 401 且不落业务表 |
| 密钥缺失导致 CI 红 | `mvn test` 全挂 | ADR-T7 回落 `PLATFORM_MASTER_KEY`；只有两者皆空才拒绝启动 |
| 9 项响应体变大 | 前端旧渲染假设 6 项 | `GateChecklist` 以 `items.length` 渲染；`counts` 由后端给，前端不自己算分母 |

---

## 13. AC → 实现 → 断言 对照（自查用）

| AC | 实现落点 | 断言脚本 |
|---|---|---|
| AC-1 | `integration/config/*`、`security/SensitiveMasker`、`V8` | `s0-config.sh` |
| AC-2 | `outbound/*`、`sync/SyncLogWriter`、`notify/*` | `s0`/`s1`/`s3` |
| AC-3 | `jenkins/*`、`WebhookSignatureVerifier` | `s1-build.sh` |
| AC-4 / AC-5 | `gitlab/*`、`release/ReleaseIntegrationSettingsService` | `s2-mr.sh` |
| AC-6 | `sonar/*`、`TraceabilityQuery` | `s3-sonar.sh` |
| AC-7 | `artifact/*`、`security/SsrfGuard` | `s4-artifact.sh` |
| AC-8 | `admission/*`、`deploy/*` | `s5-admission.sh` |
| AC-9 | `scripts/integration-verify.sh` + 桩 | 全量 + `s7` |
| AC-10 | `security/*`、`IntegrationCrypto` | `s6-security.sh` |
| AC-11 | `README.md` + `HelpDrawer.vue` | 文档评审（T11） |
