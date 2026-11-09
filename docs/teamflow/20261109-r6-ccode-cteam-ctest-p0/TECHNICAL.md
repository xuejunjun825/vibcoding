# TECHNICAL · M8 CCode / CTeam / CTest 一期 P0 技术方案

> 基线：`main@9072719`（M7 集成适配层已合入），分支 `feat/ccode-cteam-ctest-p0`。
> 上游：`PRD.md`（AC-1…AC-20）、`DESIGN.md`（路由/尺寸/组件/裁决）。
> 本文只写"怎么落地"：服务归属、表与索引、每个接口的入参/出参/错误码、关键算法、文件清单、任务边界与证据脚本。
> 冲突时优先级：PRD 的 AC > DESIGN 的裁决 > 本文的实现手法；本文与 AC 不一致处集中列在 §8。

---

## 0 落点与总体判断

### 0.1 为什么这样放（服务归属）

`req-service` 与 `deliver-service` **共用同一个 MySQL 库** `devops_platform`（Flyway 迁移只在 `req-service/src/main/resources/db/migration/`，deliver 无独立 migration），因此"跨服务"只是代码边界，不是数据边界。归属按**领域所有者**划分，不按"谁方便"：

| 能力 | 服务 | 新包/扩展点 | 理由 |
|---|---|---|---|
| 甘特（AC-1/2/3） | req-service | `com.example.req.collab.gantt` | 三个视图的对象（release/sprint/requirement/task）都在 req 域；改期要写 `audit_log`，req 已有 `AuditService` |
| Wiki（AC-4/5/6/7） | req-service | `com.example.req.collab.wiki` | 双向关联对端是 requirement/defect/release；附件服务 `AttachmentService` 在 req |
| 价值流（AC-8/9） | **deliver-service** | `com.example.deliver.flow` | `metric_definition` + `MetricQuery` 在 deliver；AC-9 要求看板/指标字典/报表**同一实现**，同进程才能保证；deliver 已有 `ReqReadModel` 读需求 |
| 分支策略（AC-10/11） | req-service | `com.example.req.collab.policy` | 复用 M7 的 `integration_config` 凭据与 `OutboundHttpClient`（都在 req） |
| CR 评论（AC-12） | req-service | 扩 `integration/gitlab` | `mr_sync` 与 `GitLabAdapter` 在 req |
| 代码质量（AC-13） | req-service | 扩 `integration/sonar` | `SonarSnapshotService` 已按 `source='SONAR'` 读写 `quality_gate_snapshot` |
| 用例评审（AC-14） | deliver-service | `com.example.deliver.review` | `test_case.current_version` 在 deliver，改版失效钩子必须在同一事务 |
| 测试数据（AC-15） | deliver-service | 扩 `testplan` | `test_plan` 在 deliver |
| 执行器（AC-16） | deliver-service | `com.example.deliver.executor` | 上报入口 `POST /v1/test-executions` 在 deliver 的 openapi 包 |

**跨服务零新增 RPC**：`test_plan` 已在 `ScopeSql.TABLES` 登记，`AttachmentService` 扩展白名单后直接用 `ScopedJdbc` 读 `test_plan`/`wiki_page` 做锚点校验（§3.9）。不新增 internal 端点。

### 0.2 三条贯穿全局的技术约束

1. **打开页面零外呼**：GitLab/Sonar 只在 `POST .../check`、`POST .../mr/refresh` 两个按钮后调用；`s4/s5` 用桩计数直接断言（AC-11/13）。
2. **号码只算一次**：`FlowMetricFormulas` 是价值流五个指标的**唯一**数值实现；`GanttWarnings` 是临期/逾期/超载的**唯一**实现；`WikiVisibility` 是六个出口的**唯一**谓词。任何第二份实现都会被 s3/s1/s2 的数字对照打成红灯。
3. **只增不改**：`mr_sync`、`quality_gate_snapshot`、`branch_policy_check`、`wiki_page_version`、`audit_log` 五张表对本轮而言是 append-only；既有接口字段只加不改名。

---

## 1 数据模型与存储（Flyway V9）

### 1.1 迁移文件

`req-service/src/main/resources/db/migration/V9__ccode_cteam_ctest_p0.sql`（单文件，顺序：建表 → 判空加列 → 索引 → 回填 → 种子 → 校验）。

### 1.2 新建 11 表

| 表 | 关键列 | 约束/索引 |
|---|---|---|
| `wiki_space` | id, tenant_id, domain_id NOT NULL, vendor_id NULL, name, visibility ENUM('INHERIT','DOMAIN','VENDOR'), editor_policy ENUM('DOMAIN_EDITORS','ADMIN_ONLY'), owner_id, status, created_at/updated_at/created_by/updated_by | `UNIQUE uk_space_name (tenant_id, domain_id, name)`；`KEY idx_space_scope (tenant_id, domain_id, vendor_id)` |
| `wiki_page` | id, tenant_id, space_id, parent_id NULL, title, content_md LONGTEXT, status ENUM('DRAFT','PUBLISHED','DELETED'), depth TINYINT (1..4), sort_no, current_version INT, editor_id, deleted_at NULL, **deleted_root_id NULL**, created_at/updated_at | `UNIQUE uk_page_sibling (space_id, parent_id, title)`；`KEY idx_tree (tenant_id, space_id, parent_id, sort_no)`；`KEY idx_trash (tenant_id, status, deleted_at)`；**`FULLTEXT KEY ft_wiki (title, content_md) WITH PARSER ngram`** |
| `wiki_page_version` | id, tenant_id, page_id, version_no, title, content_md, editor_id, content_hash CHAR(64), created_at | `UNIQUE uk_page_ver (page_id, version_no)`；**无 UPDATE/DELETE 代码路径**（append-only，只有 purge 级联删） |
| `wiki_comment` | id, tenant_id, page_id, user_id, content VARCHAR(2000), created_at | `KEY idx_cmt_page (tenant_id, page_id, created_at)` |
| `wiki_page_link` | id, tenant_id, page_id, biz_type ENUM('REQUIREMENT','DEFECT','RELEASE'), biz_id, created_at | `UNIQUE uk_link (page_id, biz_type, biz_id)`；`KEY idx_link_biz (tenant_id, biz_type, biz_id)` |
| `stage_definition` | id, tenant_id, code VARCHAR(24), name, order_no, mapping_json JSON, req_types VARCHAR(64) NULL, enabled TINYINT, created_at/updated_at/updated_by | `UNIQUE uk_stage (tenant_id, code)`；`KEY idx_stage_order (tenant_id, order_no)` |
| `branch_policy` | id, tenant_id, domain_id, gitlab_project VARCHAR(200), branch_pattern VARCHAR(200), pattern_type ENUM('REGEX','GLOB'), protected TINYINT, merge_method ENUM('MERGE','SQUASH','FF_ONLY'), approvals_required TINYINT (0–5), push_roles_json JSON, merge_roles_json JSON, enabled TINYINT, version INT, created_at/updated_at/created_by/updated_by | `UNIQUE uk_policy (tenant_id, gitlab_project, branch_pattern)`；`KEY idx_policy_domain (tenant_id, domain_id)` |
| `branch_policy_check` | id, tenant_id, policy_id, result ENUM('MATCH','DRIFT','UNVERIFIED'), reason VARCHAR(48) NULL, diff_json JSON, evidence_json JSON, checked_at, checked_by | `KEY idx_check_policy (tenant_id, policy_id, checked_at)`；**只 INSERT** |
| `mr_comment` | id, tenant_id, mr_sync_id, gitlab_note_id BIGINT, author, body VARCHAR(2000), file_path VARCHAR(500) NULL, line_no INT NULL, resolved TINYINT, note_created_at DATETIME(3) | `UNIQUE uk_note (tenant_id, mr_sync_id, gitlab_note_id)`；`KEY idx_mrc (mr_sync_id, note_created_at)` |
| `test_case_review` | id, tenant_id, case_id, case_version INT, conclusion ENUM('PASS','NEED_CHANGE','REJECT') NULL, comment VARCHAR(2000) NULL, reviewer_id, status ENUM('SUBMITTED','DONE','INVALIDATED'), invalid_reason VARCHAR(32) NULL, created_at, decided_at NULL, decided_by NULL | `KEY idx_review_case (tenant_id, case_id, case_version, status)`；**"同一用例同一版本只允许一条有效评审"** 用条件唯一无法在 MySQL 表达 → 服务层 `SELECT ... FOR UPDATE` + 事务内校验（§3.7） |
| `test_executor` | id, tenant_id, domain_id NULL, code VARCHAR(32), name, base_url VARCHAR(200), api_client_id BIGINT NULL, enabled TINYINT, last_report_at DATETIME(3) NULL, report_count INT DEFAULT 0, created_at/updated_at | `UNIQUE uk_exec_code (tenant_id, code)`；`KEY idx_exec_client (tenant_id, api_client_id)` |

**外键一律不建**（与 V1 一致，跨域删除由服务层与幂等任务处理）。

### 1.3 增列（全部判空，MySQL 无 `ADD COLUMN IF NOT EXISTS`）

用 `information_schema.COLUMNS` 判空 + `PREPARE/EXECUTE` 包裹，保证在已有 V8 数据的库上重复执行不报错：

| 表 | 列 |
|---|---|
| `mr_sync` | `reviewers_json JSON NULL`、`review_state VARCHAR(24) NULL`、`changed_files INT NULL`、`comments_count INT NULL` |
| `requirement` / `release` / `sprint` / `task` | `actual_start_date DATE NULL`、`actual_end_date DATE NULL` |
| `test_plan` | `data_note TEXT NULL`，并加 `revision INT NOT NULL DEFAULT 0`（测试数据锁 + 并发保存用，避免与 `updated_at` 混淆） |
| `test_execution` | **已存在 `executor_id BIGINT NULL`（V1 建表即有此列）→ V9 不再 ALTER，只在 s0 断言列存在**（见 §8.1） |
| `audit_log` | 不增列，只加索引 `KEY idx_audit_req (tenant_id, biz_type, biz_id, created_at)`（判空创建；AC-20 价值流 100 万行依赖它） |

### 1.4 历史回填（幂等，只填 NULL）

新增 `platform-common/src/main/java/com/example/platform/common/audit/ActualPeriodRecorder.java`，两个职责：

- `static void record(String bizType, long id, String from, String to)`：状态流转时维护 `actual_start_date/actual_end_date`（只写一次开始、一次结束；重复 transition 不覆盖）。
- V9 的 SQL 回填与它同口径（同一张映射表在 Java 常量与 SQL 注释里各写一遍，s0 用"改后重算=0 差异"校验）。

口径（`audit_log.action='STATE_CHANGE'`，取 `detail_json.to`）：

| biz_type → 表 | START 状态 | END 状态 |
|---|---|---|
| REQUIREMENT → `requirement` | `IN_DEV` | `DELIVERED`, `DONE`, `ACCEPTED_CLOSED`, `CLOSED` |
| SPRINT → `sprint` | `ACTIVE` | `CLOSED` |
| TASK → `task` | `IN_PROGRESS` | `DONE`, `CANCELLED` |
| RELEASE → `release` | `IN_PROGRESS`（无则 `PUBLISHED`） | `RELEASED`, `CLOSED` |

回填 SQL 骨架（每个 biz_type 一段，`MIN(created_at)` 取首次进入）：

```sql
UPDATE requirement r
JOIN (SELECT biz_id, MIN(created_at) t FROM audit_log
      WHERE tenant_id = 1 AND biz_type='REQUIREMENT' AND action='STATE_CHANGE'
        AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.to')) = 'IN_DEV'
      GROUP BY biz_id) a ON a.biz_id = r.id
SET r.actual_start_date = DATE(a.t)
WHERE r.actual_start_date IS NULL;
```

校验行（写进 V9 末尾 `SELECT` 注释并在 s0 断言）：

```sql
-- 期望 0：有状态审计但实际期仍为空的需求
SELECT COUNT(*) FROM requirement r
WHERE r.actual_start_date IS NULL
  AND EXISTS (SELECT 1 FROM audit_log a WHERE a.biz_type='REQUIREMENT'
              AND a.biz_id=r.id AND a.action='STATE_CHANGE'
              AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.to'))='IN_DEV');
```

### 1.5 种子（V9 内 SQL，`WHERE NOT EXISTS` 幂等，不经过 `/internal/seed`）

- `stage_definition` 7 行：提报 1 / 评审 2 / 排期 3 / 开发 4 / 测试 5 / 发布 6 / 交付 7，`enabled=1`，映射：
  - 提报 `{"enterEvents":["REQUIREMENT_CREATED"]}`
  - 评审 `{"enterStatuses":["APPROVING","PENDING_REVIEW"],"leaveStatuses":["POOLED"]}`
  - 排期 `{"enterStatuses":["POOLED","SCHEDULED"],"enterEvents":["RELEASE_ATTACHED"]}`
  - 开发 `{"enterStatuses":["IN_DEV"]}` / 测试 `{"enterStatuses":["PENDING_TEST"]}`
  - 发布 `{"enterStatuses":["TEST_PASSED","MERGED","DELIVERED"]}` / 交付 `{"enterStatuses":["DONE","ACCEPTED_CLOSED","CLOSED"]}`
- `metric_definition` 5 行：`FLOW_WIP / FLOW_DWELL_AVG / FLOW_DWELL_P90 / FLOW_EFFICIENCY / FLOW_BOTTLENECK`，`datasource='req.audit'`，`version=1`，`formula` 存 PRD §7 的文本。
- `wiki_space` 1 行（`visibility='DOMAIN'`）+ `wiki_page` 3 行（2 级树）+ `wiki_page_version` 2 版（v1/v2 内容不同，供 s2 造 5 版继续用）。
- `branch_policy` 1 行（示例项目 + `protected=1`）——**不种 `branch_policy_check`**，页面派生状态因此是 `UNVERIFIED`（"无核验记录即未核验"，§3.4）。
- `test_executor` 2 行（`enabled=1` 且 `last_report_at=NULL` → `NEVER`；`enabled=0` → `DISABLED`）。
- 权限码：**不进 SQL**，见 §5.1（`PermissionMatrix` 是唯一来源，管理页是投影）。

### 1.6 与 V8 的兼容

- 只加表/列/索引，不改既有列类型；`mr_sync` 既有 12 个字段的 JPA 映射保持原样，新 4 列用 `@Column` 追加并允许 null（老行读出 null，视图层做默认值）。
- `V9` 不依赖 V8 的 `source` 维度之外任何 M7 结构；M7 的 230 条集成断言不受影响。

---

## 2 后端契约

### 2.1 通用约定

- 错误统一 `com.example.platform.common.error.ApiException(status, code, message, detail)`，由 `GlobalExceptionHandler` 渲染成 `{code, message, detail}`；**`detail` 承载机器字段**（确认码、父窗口、当前版本），前端只读 `code` 分支。
- 鉴权：列表不返回不可见行；**详情一律 404**（不用 403 泄露存在性）；写操作 403。
- 分页统一 `page/size`（默认 20，上限 100）；列表响应 `{items, total, page, size}`。
- 所有对外时间用 `yyyy-MM-dd`（日期）或 ISO-8601（时间戳），沿用既有序列化配置。

### 2.2 甘特（req-service，网关前缀 `/api/req`）

**`GET /gantt/rows`**

| 参数 | 说明 |
|---|---|
| `view` | `RELEASE` \| `SPRINT` \| `REQUIREMENT`（默认 `RELEASE`） |
| `zoom` | `DAY` \| `WEEK` \| `MONTH`（默认 `MONTH`） |
| `domainId` / `vendorId` / `releaseId` / `sprintId` | 筛选；越权筛选值不报错，只是结果为空（不泄露存在性） |
| `from` / `to` | 可选窗口，缺省由 view 推导（当前版本窗口 / 当前迭代 / 今天前后 45 天） |
| `expanded` | 逗号分隔的已展开行 id（`REQ:12,REL:3`）；缺省按 view 折叠 |
| `limit` | 默认 300，上限 300 |

返回 `GanttViews.Board`：

```java
record Board(String view, String zoom, LocalDate rangeStart, LocalDate rangeEnd,
             List<Row> rows, int total, boolean truncated,
             int capacityTenths, List<Warning> boardWarnings) {}

record Row(String type, long id, Long parentId, int level, String code, String title,
           Long ownerId, String ownerName,
           LocalDate planStart, LocalDate planEnd,
           LocalDate actualStart, LocalDate actualEnd,
           String status, boolean done, boolean milestone, LocalDate plannedDate,
           String version, List<Warning> warnings) {}

record Warning(String code, String level, String message,
               Integer days, Double overDays, List<String> items) {}
```

- `version` = `updated_at` 的 epoch millis 字符串（乐观锁令牌，见 §3.1）。
- `capacityTenths` = 周容量 ×10（默认 50 = 5.0 人天），前端只展示不计算。
- `truncated=true` 时 `rows` 只含折叠层（`level<=1`），`total` 是可见总数。

**`PATCH /gantt/rows/{type}/{id}`**（`type ∈ RELEASE|SPRINT|REQUIREMENT|TASK`）

```json
{ "planStart": "2026-03-01", "planEnd": "2026-03-08",
  "version": "1731234567890", "confirmToken": null }
```

| 情形 | 状态 | code |
|---|---|---|
| 日期非法/倒挂 | 400 | `GANTT_INVALID_RANGE` |
| 不可见或不存在 | 404 | `NOT_FOUND` |
| 无 `GANTT_EDIT` 或非本域 | 403 | `FORBIDDEN` |
| 父窗口外且无 token | 409 | `GANTT_OUT_OF_PARENT_WINDOW`（`detail={parentWindow:{start,end}, violatingDays, confirmToken, expiresAt}`） |
| token 过期/已用/绑定不符 | 409 | `GANTT_CONFIRM_INVALID` |
| version 不匹配 | 409 | `GANTT_VERSION_CONFLICT`（`detail={currentVersion}`） |
| 成功 | 200 | `{id, type, planStart, planEnd, auditId, version}` |

父窗口规则：`REQUIREMENT` → 所属 release 的 `start_date/end_date`；`SPRINT` → 所属 release 窗口（无 release 则不限）；`TASK` → 所属 requirement 的 `plan_start_date/plan_end_date`；`RELEASE` → 不限（AC-2 只要求"需求/迭代/任务"）。

### 2.3 Wiki（req-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/wiki/spaces` | 空间列表（按可见性过滤） |
| POST | `/wiki/spaces` | 建空间（`WIKI_ADMIN`），默认 `visibility=DOMAIN` |
| PUT | `/wiki/spaces/{id}` | 改可见范围/编辑策略/负责人（`WIKI_ADMIN`） |
| GET | `/wiki/spaces/{id}/tree` | 整棵树（一次返回，≤500 节点；不含正文） |
| POST | `/wiki/pages` | 建页 `{spaceId,parentId,title,contentMd}` → 同时落 v1 |
| GET | `/wiki/pages/{id}` | 详情 `{page{id,spaceId,title,contentMd,currentVersion,canEdit,visibility}, breadcrumb[], links[], attachments[]}` |
| PUT | `/wiki/pages/{id}` | 保存 `{title,contentMd,baseVersion}` → `{noChange,versionNo,pageId}`；`baseVersion` 落后 409 `WIKI_EDIT_CONFLICT` |
| POST | `/wiki/pages/{id}/move` | `{parentId}`；400 `WIKI_DEPTH_EXCEEDED`、409 `WIKI_CYCLE` |
| DELETE | `/wiki/pages/{id}` | 软删整棵子树（同一 `deleted_at` + `deleted_root_id`） |
| GET | `/wiki/trash?spaceId=` | 回收站（以 `deleted_root_id` 一行呈现，含剩余天数） |
| POST | `/wiki/pages/{id}/restore` | 409 `WIKI_RESTORE_EXPIRED`；原父已删则挂根 |
| DELETE | `/wiki/pages/{id}/purge` | 管理员物理删除（`WIKI_ADMIN`） |
| GET | `/wiki/pages/{id}/versions` | 版本列表（不含正文） |
| GET | `/wiki/pages/{id}/versions/diff?from=&to=` | `{lines[{kind:SAME|ADD|DEL,leftNo,rightNo,text}],added,removed,changed}` |
| POST | `/wiki/pages/{id}/rollback` | `{versionNo}` → 生成 vN+1，审计 `WIKI_ROLLBACK` |
| GET | `/wiki/search?q=&page=&size=` | `{items[{pageId,title,snippet,spaceName,updatedAt}],total,degraded,page,size}` |
| POST/DELETE | `/wiki/pages/{id}/links` | 挂/解挂 `{bizType,bizId}` |
| GET | `/wiki/links?bizType=&bizId=` | 某需求/缺陷/版本关联的**可见**页面 |
| GET/POST | `/wiki/pages/{id}/comments` | 页面评论（可选能力，表已建） |

错误码：`WIKI_DEPTH_EXCEEDED`(400)、`WIKI_CYCLE`(409)、`WIKI_RESTORE_EXPIRED`(409)、`WIKI_EDIT_CONFLICT`(409)、`WIKI_SEARCH_EMPTY`(400，空词)、`WIKI_FORBIDDEN`(403)。

### 2.4 价值流（deliver-service，网关前缀 `/api/deliver`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/flow/stages` | 7 阶段定义（含 `mappingJson`、`reqTypes`、`enabled`） |
| PUT | `/flow/stages` | 全量保存 `{stages:[{id?,code,name,orderNo,enabled,reqTypes,mappingJson}]}`；同一类型启用 <2 → 409 `STAGE_MIN_REQUIRED`；写 `STAGE_DEFINITION_UPDATE` |
| GET | `/flow/board?domainId=&vendorId=&from=&to=&reqType=` | `{window{from,to}, stages:[{code,name,orderNo,applicable,wip,dwellAvg,dwellP90,sampleSize,noDataCount,bottleneck}], efficiency{value,included,excluded}, generatedAt}` |
| GET | `/flow/drill?stageCode=&domainId=&vendorId=&from=&to=&page=&size=` | 阶段需求列表（权限过滤） |
| GET | `/flow/requirements/{id}/timeline?from=&to=` | 阶段时间线 `[{stageCode,enteredAt,leftAt,dwellDays,sourceEvent}]`，缺口标 `noData` |
| GET | `/metrics/{code}/preview?from=&to=&domainId=&vendorId=` | 指标字典/报表预览：`FLOW_*` 委托 `FlowMetricService.values()`，其余走既有 `MetricQuery` |

### 2.5 分支策略与核验（req-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/code/policies?domainId=&project=&enabled=` | 规则列表 + 每条的最新核验态（无记录 → `UNVERIFIED`） |
| POST | `/code/policies` | 建规则 |
| PUT | `/code/policies/{id}` | 改规则（`version` 自增） |
| DELETE | `/code/policies/{id}` | 删除（软：`enabled=0`；保留核验历史） |
| POST | `/code/policies/{id}/check` | 唯一外呼入口，见下 |
| GET | `/code/policies/{id}/checks?limit=20` | 核验历史（只读） |

`POST .../check` 三态：

- 集成未启用/不可达 → **409 `INTEGRATION_DISABLED`**（或 `INTEGRATION_UNREACHABLE`），**同时**写一条 `branch_policy_check(result='UNVERIFIED', reason=<原因>)`；前端把 409 渲染为黄色"未核验"，不当 toast。
- 可达 → 逐项比对，200 返回 `{result:'MATCH'|'DRIFT', items:[{item,expected,actual,ok,source}], checkedAt}`，写 `branch_policy_check`。
- 原因枚举（唯一）：`INTEGRATION_DISABLED` / `AUTH_FAILED` / `TIMEOUT` / `API_UNSUPPORTED` / `PERMISSION_INSUFFICIENT`。

校验错误：400 `POLICY_CONTRADICTION`（`approvals_required>0 && !protected`；`push_roles` 与 `merge_roles` 含未知角色码）、400 `POLICY_PATTERN_INVALID`（正则编译失败）、409 `POLICY_DUPLICATE`（同 tenant+project+pattern）。

### 2.6 CR 评审信息（req-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/requirements/{id}/integration` | **既有**，`MrCard` 增 4 字段 + 评论预览 3 条 |
| POST | `/requirements/{id}/integration/mr/refresh` | 唯一外呼：拉 MR 详情 + notes，幂等落库，返回 `{card, added, updated}` |
| GET | `/requirements/{id}/integration/mr-comments?cursor=&limit=50` | 行级评论分页（`file_path` 分组由前端做） |

`MrCard` 追加字段：`reviewers`（`[{id,name,state}]`）、`reviewState`（`PENDING|APPROVED|CHANGES_REQUESTED`）、`changedFiles`、`commentsCount`。**既有 13 个字段不改名**（AC-19 老断言）。

### 2.7 代码质量（req-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/requirements/{id}/code-quality` | 需求维度最新 Sonar 快照 `{card, emptyReason}`；无快照时 `card=null`、`emptyReason='NO_SNAPSHOT'` 或 `'INTEGRATION_DISABLED'` |
| GET | `/releases/{id}/code-quality` | 版本聚合：按 `sonar_project_key` 去重取每项目最新一条，`{projects:[{projectKey,requirementId,requirementCode,card}], emptyReason}` |

**不返回 0 兜底**：`card=null` 就是空态；五项度量原样来自 `metrics_json`（缺失的键不补 0，前端显示 `—`）。

### 2.8 用例评审（deliver-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/testcases/{id}/reviews` | 该用例全部评审（含已过期），按版本倒序 |
| POST | `/testcases/{id}/reviews` | 发起评审 `{reviewerId, conclusion?, comment?}`，**钉 `test_case.current_version`** |
| POST | `/testcase-reviews/{id}/decide` | 下结论 `{conclusion, comment}`，仅 `reviewer_id` 本人（403） |
| GET | `/testcase-reviews?status=&page=&size=` | 审批中心只读列表（跨用例） |

错误码：400 `CASE_REVIEW_COMMENT_REQUIRED`（`NEED_CHANGE/REJECT` 且 `comment.trim().length()<10`）、409 `CASE_REVIEW_DUPLICATE`、409 `CASE_REVIEW_VERSION_STALE`（提交时 `case_version != current_version`）、409 `CASE_REVIEW_INVALIDATED`（已过期再结论）、403 `CASE_REVIEW_NOT_REVIEWER`。

**不新增 `wf_definition.biz_type`**（s6 断言 `SELECT COUNT(*) FROM wf_definition WHERE biz_type LIKE '%CASE%' = 0`）。

### 2.9 测试数据说明（deliver-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/test-plans/{id}/data-note` | `{dataNote, attachments[], locked, revision}` |
| PUT | `/test-plans/{id}/data-note` | `{dataNote, revision}`；>2000 字 400 `DATA_NOTE_TOO_LONG`；`COMPLETED` 409 `PLAN_DATA_LOCKED`；revision 冲突 409 `DATA_NOTE_CONFLICT` |
| POST | `/attachments`（req-service，既有） | 新增 `bizType ∈ {TEST_PLAN, WIKI_PAGE}` |
| DELETE | `/attachments/{id}`（req-service，新增） | 同一锁规则 |

审计 `TEST_DATA_NOTE_UPDATE`，`detail={length, attachmentCount}`（**不落正文**）。

### 2.10 执行器（deliver-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/test-executors` | `{items:[{id,code,name,baseUrl,apiClientId,enabled,lastReportAt,reportCount,health}], total}` |
| POST | `/test-executors` | 新建（`code` 不可改） |
| PUT | `/test-executors/{id}` | 改名/地址/绑定 client/启停 |
| GET | `/test-executors/{id}/reports?days=7` | 近 7 天上报数（按天分桶） |

健康派生（`ExecutorHealth.of(enabled, lastReportAt, now)`，阈值常量在 `ExecutorHealth` 单处）：`DISABLED` / `NEVER` / `ONLINE`(≤24h) / `STALE`(24–72h) / `OFFLINE`(>72h)。

### 2.11 开放 API 变更（`POST /v1/test-executions`）

`OpenApiViews.IngestCommand` 追加可选 `executorCode`：

- 有 code：按 `(tenant, code)` 解析，未知 → 422 `EXECUTOR_UNKNOWN`（不落库）；`enabled=0` → 422 `EXECUTOR_DISABLED`。
- 无 code：按 `api_client_id = clientId` 反查（唯一或空）；空则 `executor_id=NULL`（兼容既有 CI）。
- 不参与幂等键：唯一键仍是 `(tenant_id, client_id, run_id, case_id)`。
- 同事务更新 `last_report_at=MAX(now, executed_at)`、`report_count+1`。

---

## 3 关键实现与边界

### 3.1 甘特：父窗口、确认码、乐观锁、告警

**文件**：`req-service/src/main/java/com/example/req/collab/gantt/` —— `GanttController`、`GanttService`、`GanttQuery`、`GanttWarnings`、`GanttRescheduleService`、`GanttConfirmTokens`、`GanttViews`。

**读取**：`GanttQuery` 用 `ScopedJdbc`（SQL 带 `/*scope:x*/`），按 view 分三条语句取行 + 一条语句取相关任务（`requirement_id IN (...)`），Java 侧组树、算告警。行数上限 300，先 `COUNT(*)` 判 `truncated`，再取 `LIMIT 300`。

**告警**（`GanttWarnings.compute(rows, today, capacityTenths)`）：

- `DUE_SOON`：`!done && planEnd != null && 0 <= Days.between(today, planEnd) <= 3`
- `OVERDUE`：`!done && planEnd < today`
- `OVERLOAD`：按 `ownerId + ISO 周` 汇总 `estimate_hours/8`；无任务的需求用 `estimate_days / 计划天数` 均摊到每个工作日，归到 `owner_id`；`合计 > capacity` 时生成，`items` 列出该周内超过的条目（最多 5 条）。
- 同一行多警情按 `RED > ORANGE` 取 `level`，其余进 tooltip（前端只渲染，不判断）。

**改期**（`GanttRescheduleService.reschedule`，唯一写入口）：

1. 按 `type` 取行（scoped，404）；
2. `planStart<=planEnd`（400）；
3. `version != updated_at` → 409 `GANTT_VERSION_CONFLICT`；
4. 解析父窗口，越窗则 `violatingDays = 越界天数`；
5. 无 token → `GanttConfirmTokens.issue(...)` 返回 409（不写库、不写审计）；
6. 有 token → `verifyAndConsume`（绑定 type/id/起止/5 分钟/一次性）；
7. `UPDATE ... SET plan_start_date=?, plan_end_date=?, updated_at=NOW(3) WHERE id=? AND updated_at=?`（0 行 → 409 冲突）；
8. 同事务 `auditService.record("GANTT_RESCHEDULE", type, id, {before, after, parentWindow, confirmed, operator})`；
9. 返回新 `version`。

**确认码实现**：`token = base64url(payload) + "." + HMAC-SHA256(payload, secret)`，payload=`{type,id,start,end,exp,jti}`；secret 取 `collab.gantt.confirm-secret`（缺省用 `${platform.jwt.secret}:gantt` 派生）；一次性用进程内 `ConcurrentHashMap<jti,exp>` 消费表（10 分钟清扫）。**单实例前提**：重启后旧 token 全部失效（fail-closed，用户重拖一次即可），不为此新建表（AC-18 的 11 表是硬约束）。多实例部署时改为 Redis/表，本轮不做并在 §8.4 记录。

**超父窗口的"确认后仍超"**：确认只是"用户知情"，落库后 `warnings` 里仍会出现 `OUT_OF_PARENT_WINDOW`（新增 code），页面用黄条提示而不是阻止——PRD 只要求二次确认，不要求硬拒绝。

### 3.2 Wiki：可见性、树、版本、检索、回收站

**可见性单点** `WikiVisibility`：

```java
public final class WikiVisibility {
    public static String predicate(DataScope scope, String spaceAlias);  // 返回 "AND (...)" 片段
    public boolean visibleSpace(long spaceId);
    public boolean visiblePage(long pageId);
    public void assertVisiblePage(long pageId);   // 不可见 → 404
}
```

谓词三态（六出口共用：列表/树/详情/搜索/回收站/附件）：

- `INHERIT` → 走 `ScopeSql` 对 `wiki_space`（`domain_id`/`vendor_id` 已登记）的标准谓词；
- `DOMAIN` → `space.domain_id = :scopeDomainId`；
- `VENDOR` → `space.vendor_id = :scopeVendorId AND space.vendor_id IS NOT NULL`。

`ScopedJdbc` 的 `/*scope:alias*/` 标记仍必须在 SQL 里出现（`ScopeSql` 新增 `wiki_space`/`wiki_page` 的 `TableSpec`），否则启动期单测 `ScopeSqlTest` 直接红。

**树深**：`depth` 落库（根=1），`move` 时算新深度：`newDepth = parent.depth + 1`，子树的 `depth` 批量 `+delta`；`>4` → 400；`parentId` 在自身子树内 → 409 `WIKI_CYCLE`（用 `deleted_root_id` 无关的祖先链查询）。

**版本**：`PUT` 在一个事务里 `SELECT ... FOR UPDATE` 页面 → 比对 `title+content_md` 的 sha256 → 未变返回 `noChange=true`（**不产版**）→ 变则 `INSERT wiki_page_version(version_no=current+1)` + `UPDATE wiki_page SET current_version, title, content_md`。`wiki_page_version` 无 update/delete 方法（Repository 只暴露 `save/find`）。

**回滚**：读旧版本 → 走同一个保存路径（生成 N+1）→ 审计 `WIKI_ROLLBACK{fromVersion,toVersion}`。旧版本行逐字节不变（s2 比 hash）。

**diff**：`WikiDiff.compute(oldText, newText)` —— 行级 LCS（Java，`int[][]` DP 或 Myers 简化版），输出 `SAME/ADD/DEL` 与 `leftNo/rightNo`；**只有服务端一份实现**（前端只渲染），DESIGN §9.1 的 `utils/diff.ts` 因此取消（§8.2）。

**检索**：`WikiSearchService` 启动时探测 `SELECT @@ngram_token_size`：

- 有 ngram → `WHERE MATCH(p.title,p.content_md) AGAINST (? IN BOOLEAN MODE) AND <可见性>`，`q` 按空白切词、每词 `+term`；`total` 用同 WHERE 的 `COUNT(*)`（**先过滤再计数**，AC-7 的 `total=0` 靠这条）。
- 无 ngram（部署实例不支持）→ 降级 `LIKE`，响应 `degraded=true`，页面显示"降级检索"，**不静默**。
- 片段：取首个命中词前后各 60 字，`≤120` 字，命中词用 `\u0001..\u0002` 包裹（前端切分渲染，避免 XSS）。

**回收站/恢复/清理**：删除写 `status='DELETED'`、`deleted_at=now`、`deleted_root_id=根页 id`（子树内所有行同值）；恢复检查 `now - deleted_at <= 30d`；`WikiPurgeJob`（`@Scheduled(cron="0 10 3 * * *")`，开关 `collab.wiki.purge.enabled` 默认 true）物理删除超期子树并审计 `WIKI_PURGE`。

**Markdown 安全**：存原文；`MarkdownSanitizer.toSafeHtml` 服务端白名单（`p,br,h1-h6,ul,ol,li,blockquote,pre,code,table,thead,tbody,tr,th,td,a,img,strong,em,del`；属性只留 `href,src,alt,title`，`href` 只允许 `http/https/mailto` 与站内 `/`）。前端 `MarkdownView` **不解析原始 HTML**（DESIGN 裁决 11），HTML 片段按纯文本转义显示。

### 3.3 价值流：从 `audit_log` 派生（不按 0 兜底）

**唯一事件源**：`audit_log` 中 `action='STATE_CHANGE'` 且 `biz_type='REQUIREMENT'` 的行，`detail_json->>'$.to'` 为目标状态。`audit_log` 需在 `ScopeSql.TABLES` 登记为 TENANT 级（`TableSpec.NONE`），否则 `ScopedJdbc` 抛 `unknown scoped table`。

**一条 SQL 出七列**（`FlowQuery.board(window)`，MySQL 9 窗口函数）：

```sql
WITH ev AS (
  SELECT a.biz_id, a.created_at,
         JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.to')) AS st
  FROM audit_log /*scope:audit*/ a
  WHERE a.tenant_id = :tenant AND a.biz_type='REQUIREMENT'
    AND a.action='STATE_CHANGE' AND a.created_at >= :from
),
iv AS (
  SELECT biz_id, st AS stage, created_at AS entered_at,
         LEAD(created_at) OVER (PARTITION BY biz_id ORDER BY created_at) AS left_at
  FROM ev
)
SELECT stage,
       SUM(left_at IS NULL)                                        AS wip,
       COUNT(left_at)                                              AS sample,
       ROUND(AVG(TIMESTAMPDIFF(SECOND, entered_at, left_at))/86400,1) AS avg_days,
       ... P90 via ROW_NUMBER() OVER (PARTITION BY stage ORDER BY dwell) ...
FROM iv JOIN requirement r ON r.id = iv.biz_id /*scope:r*/
GROUP BY stage;
```

P90 用 `ROW_NUMBER() OVER (PARTITION BY stage ORDER BY dwell_sec)` 取 `rn = CEIL(0.9*n)`（nearest-rank，与 `FlowMetricFormulas.p90` 的单元测试同口径）。

**排除规则**：无任何 `STATE_CHANGE` 的需求 → 不计 WIP、不计停留，计入 `noDataCount` 并在页面注明"无数据（已排除）"。**不得**用 `created_at` 或 0 兜底（AC-9 反面目标 5）。

**同源**：`FlowMetricService.board(q)` 与 `MetricQuery.preview("FLOW_*", q)` 都调 `FlowMetricService.values(q)`；`FlowMetricFormulas` 只做纯函数（`p90`/`avg`/`efficiency`/`bottleneck`）。三处读数（看板、指标字典预览、报表导出）由此必然相同。

**瓶颈**：`sample>=5` 的阶段里 `avgDays` 最大者；不足 5 标"样本不足"且不参与排名。

**流动效率**：`(开发停留 + 测试停留) / (提报进入 → 交付离开)`，仅已交付需求；分母 ≤0 或任一段缺失 → 该需求排除，响应给 `included/excluded` 计数（页面常驻口径说明）。

**性能**：默认窗口 90 天；`idx_audit_req(tenant,biz_type,biz_id,created_at)` 支撑；不做汇总表（AC-18 表数固定），若实测超 2s，先在 `collab-perf` 记录并在 §8.5 升级为"派生表 + 重算校验"。

### 3.4 分支核验三态（不得假绿）

`GitLabPolicyChecker.check(policy)`：

1. 读 `integration_config`（`system=GITLAB`、`enabled=1`、租户内唯一）→ 无则 `UNVERIFIED/INTEGRATION_DISABLED` 并抛 409（同时落 check 行）。
2. `GitLabAdapter` 新增四个只读方法（均经 `OutboundHttpClient.exchange` 记 `integration_sync_log`，token 不落日志）：
   - `ProjectSettings fetchProjectSettings(config, project)` → `merge_method`、`approvals_before_merge`、`only_allow_merge_if_pipeline_succeeds`
   - `List<ProtectedBranch> protectedBranches(config, project)` → `name`、`push_access_levels`、`merge_access_levels`
   - `List<String> recentBranches(config, project, int limit=50)`
   - `List<MrNote> fetchMrNotes(config, project, long iid)`
3. 判定：分支名匹配 `branch_pattern`（`REGEX`/`GLOB` 各自编译）→ 未匹配且 `protected=1` 视为 `MATCH`（"没有这条分支说明还没建，不算漂移"，写进 `diff_json.note`）；合并方式/审批数/推送角色逐项比对，任一 `ok=false` → `DRIFT`。
4. 落 `branch_policy_check`（只 INSERT）；页面态 = 最新一条，否则 `UNVERIFIED`。

**打开页面零外呼**：`GET /code/policies` 只读本地表；s4 断言桩计数在 `GET` 前后不变。

### 3.5 MR 评论：幂等、单调、脱敏

- 刷新：`fetchMergeRequest` 已有；新增 `fetchMrNotes` 拉 `/projects/:id/merge_requests/:iid/notes`，只保留 `position != null` 的行级评论（普通评论计入 `commentsCount` 但不入 `mr_comment`）。
- 幂等：`INSERT ... ON DUPLICATE KEY UPDATE body=VALUES(body), resolved=VALUES(resolved)`（唯一键 `tenant+mr_sync_id+gitlab_note_id`）。
- 单调：`mr_sync.last_event_at = GREATEST(COALESCE(last_event_at,'1970-01-01'), :eventAt)`。
- 合并/关闭后：`mr_sync.state ∈ {merged, closed}` 时刷新只更新评论的 `resolved`，不覆盖 `reviewState/reviewers`。
- 脱敏：`SecretMasker`（正则 `(?i)(token|password|secret|authorization)\s*[=:]\s*\S+` 与 `glpat-[A-Za-z0-9_-]{16,}`）→ `***`；正文截断 2000 字。
- 评论列表随需求可见性（`ScopedJdbc` 走 requirement 谓词），跨域 404。

### 3.6 代码质量空态

- 需求：`SELECT ... FROM quality_gate_snapshot WHERE source='SONAR' AND requirement_id=? ORDER BY fetched_at DESC LIMIT 1`。
- 版本：先取版本下需求（`ScopedJdbc`），再按 `project_key` 分组取每组最新，`card.source` 保留 `requirementCode` 供 tooltip。
- 无行 → `card=null`（**不构造 0 值卡**）；集成关闭且从未同步 → `emptyReason='INTEGRATION_DISABLED'`。
- 打开页面不触发 `SonarAdapter`；"刷新"复用 M7 的 `POST /integration/sonar/{requirementId}/sync`。

### 3.7 用例评审：钉版本、改版即失效

- 提交：事务内 `SELECT current_version FROM test_case WHERE id=? FOR UPDATE`；`cmd.caseVersion` 若给出且 ≠ current → 409 `CASE_REVIEW_VERSION_STALE`；同 `(case_id, case_version)` 已存在 `status IN ('SUBMITTED','DONE')` → 409 `CASE_REVIEW_DUPLICATE`。
- 结论：`reviewer_id == scope.userId()`，否则 403；`NEED_CHANGE/REJECT` 意见 `<10` 字 → 400；已 `DONE/INVALIDATED` → 409。
- 失效钩子：`TestCaseService.update`（生成新版本的那个事务）里调 `caseReviewRepository.invalidate(caseId, oldVersion, "NEW_VERSION")`（`UPDATE ... SET status='INVALIDATED', invalid_reason='NEW_VERSION' WHERE case_id=? AND case_version=? AND status IN ('SUBMITTED','DONE')`）+ `auditService.record("CASE_REVIEW_INVALIDATED", ...)`；**同一事务**，避免"用例已升版、评审仍有效"的中间态。
- 显示：`review.status='INVALIDATED'` 时结论保留但置灰，文案 `v{caseVersion} 已过期（当前 v{current}）`。

### 3.8 测试数据：锁与审计

- 编辑权限：`plan.owner_id == userId` 或本域测试角色（`CASE_REVIEW_VIEW` 之外用 `TEST_EXECUTOR_VIEW` 不合适 → 用既有 `test:execute`/`test:manage` 码判定，沿用 M6 计划权限）。
- `status='COMPLETED'` → 409 `PLAN_DATA_LOCKED`（文本、附件增删都拦）。
- 审计只记摘要：`detail={length, attachmentCount, changed}`。
- 附件：`AttachmentService` 白名单加 `TEST_PLAN`、`WIKI_PAGE`；锚点校验按 `biz_type` 分支（`TEST_PLAN` → `SELECT domain_id,vendor_id FROM test_plan WHERE id=?`；`WIKI_PAGE` → 走 `WikiVisibility`），下载同样过锚点 → 跨域 404。

### 3.9 执行器：健康派生与上报联动

- `ExecutorHealth`：`ONLINE_HOURS=24`、`STALE_HOURS=72`（单处常量），`of(enabled,lastReportAt,now)` 纯函数（冻结时钟单测）。
- 上报：`ExecutionIngestService.ingest` 解析 `executorCode` → 设置 `entity.executorId` → 同事务 `UPDATE test_executor SET last_report_at=GREATEST(COALESCE(last_report_at, ?), ?), report_count=report_count+1 WHERE id=?`。
- 冻结时钟验边界：23:59 `ONLINE` / 24:01 `STALE` / 72:01 `OFFLINE`（单测 + s7 用 `now` 参数注入）。

---

## 4 前端

### 4.1 路由与导航（文件即契约）

| 文件 | 内容 |
|---|---|
| `web/src/router/routes/collab.routes.ts`（新） | `gantt` `/gantt`、`wiki` `/wiki`、`value-stream` `/value-stream`、`value-stream-stages` `/value-stream/stages`；`meta.functionCode` 取 §5.1 常量字面量 |
| `web/src/router/routes/code.routes.ts`（新） | `code-policies` `/code-policies` |
| `web/src/router/routes/deliver.routes.ts`（改） | 追加 `test-executors` `/test-executors`（不改既有条目） |
| `web/src/layouts/nav.ts`（改） | 新增「协作」分组（甘特/Wiki/价值流）、分析域「分支策略」、交付域「执行器」；`routeName` 与上表 `name` 完全一致 |

`web/src/router/index.ts` 已用 `import.meta.glob('./routes/*.ts')` 自动汇总，新文件无需改索引。路由守卫按 `meta.functionCode`（string|string[]）判权限，无权限 → 入口自动隐藏（nav 只渲染已注册且有权重者）。

### 4.2 页面与组件

| 文件 | 说明 |
|---|---|
| `web/src/views/collab/GanttView.vue` | 三视图 × 三缩放、拖拽、告警、折叠记忆、只读态 |
| `web/src/views/collab/WikiView.vue` | 空间 › 树 › 阅读/编辑 › 搜索 › 回收站 › 关联 |
| `web/src/views/collab/ValueStreamView.vue` | 七列看板 + KPI + 下钻 |
| `web/src/views/collab/StageConfigView.vue` | 阶段启停/排序/映射/类型 |
| `web/src/views/code/CodePolicyView.vue` | 规则 + 三态核验 + 差异表 |
| `web/src/views/deliver/TestExecutorsView.vue` | 执行器台账 |
| `web/src/components/collab/GanttTimeline.vue` / `GanttBar.vue` / `RescheduleDialog.vue` / `RewriteNotice.vue` | 甘特自绘（DOM+CSS+pointer events，无第三方库） |
| `web/src/components/collab/WikiTree.vue` / `MarkdownView.vue` / `MarkdownEditor.vue` / `WikiVersionPanel.vue` / `WikiDiffDrawer.vue` | Wiki |
| `web/src/components/collab/FlowColumn.vue` / `FlowDrillDrawer.vue` | 价值流 |
| `web/src/components/code/PolicyCheckPill.vue` / `PolicyDiffTable.vue` | CCode |
| `web/src/components/req/MrReviewCard.vue`（新）、`CodeQualityCard.vue`（修订） | 需求详情集成 tab |
| `web/src/components/deliver/CaseReviewPanel.vue` / `DataNoteCard.vue` / `ExecutorHealthPill.vue` | CTest |
| `web/src/views/req/tabs/DocumentsTab.vue`（新） | 需求详情「文档」tab（挂/解挂 Wiki） |
| `web/src/views/req/tabs/IntegrationTab.vue`（改） | 通栏 MR 卡 + 代码质量卡 |
| `web/src/views/req/RequirementDetailView.vue`（改） | 注册 `documents` tab |
| `web/src/views/deliver/TestCaseLibraryView.vue`（改） | 用例详情抽屉加「评审」tab |
| `web/src/views/deliver/TestPlanDetailView.vue` / `TestPlanExecuteView.vue`（改） | 测试数据区/折叠区 |
| `web/src/views/approval/*`（改） | 审批中心新增只读 `case-reviews` tab（无待办） |
| `web/src/stores/dict.ts`（改） | 新增 5 个 kind：`policyCheck`/`executorHealth`/`caseReview`/`caseReviewConclusion`/`wikiVisibility`（另含 `mrReviewState`） |
| `web/src/utils/markdown.ts`（新） | 安全渲染：先整体转义 `<`/`>`，再按块级/行内规则生成节点；**不解析原始 HTML** |
| `web/src/utils/ganttScale.ts`（新） | 缩放→px/天（36/12/3.6）、窗口对齐、今天线位置 |

### 4.3 状态与缓存

- 甘特折叠集合 + 缩放 + 视图：`localStorage`，键 `collab:gantt:{tenantId}:{userId}`；`composables/useCollabPrefs.ts` 统一读写（切换账号不串）。
- Wiki 树展开态、最近访问空间：同样按 `tenant:user`。
- 页面数据不缓存在 store，全部走组件本地 `ref` + `api/*`；翻页保留旧数据（DESIGN §9.3）。

### 4.4 API 模块（新增函数签名）

`web/src/api/collab.ts`（新）：

```ts
export function fetchGantt(q: GanttQuery): Promise<GanttBoard>
export function reschedule(type: GanttRowType, id: number, body: RescheduleBody): Promise<RescheduleResult>
export function fetchSpaces(): Promise<WikiSpace[]>
export function fetchTree(spaceId: number): Promise<WikiNode[]>
export function fetchPage(pageId: number): Promise<WikiPageDetail>
export function savePage(pageId: number, body: SavePageBody): Promise<SavePageResult>
export function movePage(pageId: number, parentId: number | null): Promise<void>
export function deletePage(pageId: number): Promise<void>
export function fetchTrash(spaceId: number): Promise<WikiTrashRow[]>
export function restorePage(pageId: number): Promise<void>
export function fetchVersions(pageId: number): Promise<WikiVersion[]>
export function diffVersions(pageId: number, from: number, to: number): Promise<WikiDiff>
export function rollbackPage(pageId: number, versionNo: number): Promise<void>
export function searchWiki(q: string, page: number): Promise<WikiSearchResult>
export function fetchFlowBoard(q: FlowQuery): Promise<FlowBoard>
export function fetchStages(): Promise<StageDefinition[]>
export function saveStages(stages: StageDraft[]): Promise<StageDefinition[]>
export function drillStage(q: FlowDrillQuery): Promise<FlowDrillResult>
export function fetchFlowTimeline(requirementId: number, q: FlowQuery): Promise<FlowTimelineRow[]>
```

`web/src/api/code.ts`（新）：`fetchPolicies`、`savePolicy`、`deletePolicy`、`checkPolicy`、`fetchChecks`。

`web/src/api/req.ts`（追加）：`refreshMr(requirementId)`、`fetchMrComments(requirementId, cursor)`、`fetchCodeQuality(requirementId)`、`fetchReleaseCodeQuality(releaseId)`、`linkWikiPage(pageId, bizType, bizId)`、`unlinkWikiPage(pageId, bizType, bizId)`、`fetchWikiLinks(bizType, bizId)`。

`web/src/api/deliver.ts`（追加）：`fetchCaseReviews(caseId)`、`submitCaseReview(caseId, body)`、`decideCaseReview(reviewId, body)`、`fetchCaseReviewList(q)`、`fetchExecutors()`、`saveExecutor(body)`、`fetchDataNote(planId)`、`saveDataNote(planId, body)`。

### 4.5 错误码 → 界面映射（唯一表）

| code | 界面 |
|---|---|
| `GANTT_OUT_OF_PARENT_WINDOW` | 640 弹窗 + 倒计时；确认后带 token 重提 |
| `GANTT_VERSION_CONFLICT` | 480 冲突条「已被他人修改，刷新后重试」 |
| `GANTT_CONFIRM_INVALID` | 弹窗内联「确认码已失效」，允许重拖 |
| `WIKI_DEPTH_EXCEEDED` / `WIKI_CYCLE` | 移动操作的 inline 错误，不关闭对话框 |
| `WIKI_EDIT_CONFLICT` | 编辑器顶部常驻条「页面已被他人保存到 vN」 |
| `CASE_REVIEW_COMMENT_REQUIRED` | 意见框下方行内错误 + 字数计数 |
| `CASE_REVIEW_DUPLICATE` / `VERSION_STALE` | 面板顶部黄条，不弹 toast |
| `PLAN_DATA_LOCKED` / `DATA_NOTE_CONFLICT` | 卡片锁定条 / 保存失败内联 |
| `INTEGRATION_DISABLED`（分支核验） | **不弹错**：三态 pill 显示「未核验」+ 原因 |
| `EXECUTOR_DISABLED` / `EXECUTOR_UNKNOWN`（上报，非页面） | 422 原样返回 CI |

---

## 5 权限与隔离

### 5.1 权限码（14 个，进 `PermissionMatrix`）

`WIKI_VIEW`、`WIKI_EDIT`、`WIKI_ADMIN`、`GANTT_VIEW`、`GANTT_EDIT`、`FLOW_VIEW`、`FLOW_CONFIG`、`CCODE_POLICY_VIEW`、`CCODE_POLICY_EDIT`、`CASE_REVIEW_VIEW`、`CASE_REVIEW_SUBMIT`、`CASE_REVIEW_DECIDE`、`TEST_EXECUTOR_VIEW`、`TEST_EXECUTOR_MANAGE`。

- 追加在 `platform-contract/src/main/java/com/example/platform/contract/PermissionMatrix.java` 尾部（既有 41 格偏移不变，单测断言 55 格）。
- **命名说明**：既有码是 `metric:manage` 这类小写冒号风格，本轮按 AC-17/DESIGN 原文使用大写常量；不建别名，`meta.functionCode` 与后端常量是同一字符串（§8.3）。
- 角色矩阵按族授权：平台管理员全开；域负责人 `WIKI_*`/`GANTT_*`/`FLOW_VIEW`/`CCODE_POLICY_*`；研发 `GANTT_VIEW|EDIT`、`WIKI_VIEW|EDIT`、`CCODE_POLICY_VIEW`；测试 `CASE_REVIEW_*`、`TEST_EXECUTOR_VIEW`、`WIKI_VIEW`；PMO `FLOW_VIEW|CONFIG`、`GANTT_VIEW`。具体行以 `PermissionMatrix` 的 10 个角色码为准，逐格写死并由 `PermissionMatrixGridTest` 覆盖（同角色不得出现 `DENY` 与 `ALLOW` 并存）。

### 5.2 路由表（`PermissionRouteTable`）

按"首匹配即胜"顺序插入（**放在既有兜底之上**）：

| 规则 | 码 |
|---|---|
| `PATCH ^/gantt/rows/.*$` | `GANTT_EDIT` |
| `^/gantt(/.*)?$` | `GANTT_VIEW` |
| `(POST|PUT|DELETE) ^/wiki/.*$` | `WIKI_EDIT` |
| `^/wiki/spaces(/.*)?$` + 写方法 | `WIKI_ADMIN`（先于上一条） |
| `^/wiki(/.*)?$` | `WIKI_VIEW` |
| `^/flow/stages(/.*)?$` + 写方法 | `FLOW_CONFIG` |
| `^/flow(/.*)?$` | `FLOW_VIEW` |
| `^/code/policies/.*/check$` 与写方法 | `CCODE_POLICY_EDIT` |
| `^/code/policies(/.*)?$` | `CCODE_POLICY_VIEW` |
| `^/testcase-reviews(/.*)?$`、`^/testcases/\d+/reviews.*$` | `CASE_REVIEW_VIEW`（写由服务层判 `SUBMIT/DECIDE`） |
| `^/test-executors(/.*)?$` | `TEST_EXECUTOR_VIEW`（写 `MANAGE`） |
| `^/metrics/FLOW_[A-Z_]+/preview$` | `FLOW_VIEW`（先于 `metric:manage`） |

`POST /v1/test-executions` 走 HMAC 过滤器，**不进**路由表（保持 M6 契约）。

### 5.3 隔离验证（s8，≥8 条跨域/跨厂商）

1 甘特列表不返回他域版本；2 甘特详情改期 404；3 `DOMAIN` 空间的页面详情跨域 404；4 `VENDOR` 空间对其他厂商搜索 `total=0`；5 回收站恢复他域页 404；6 分支策略跨域列表为空、写 403；7 MR 评论跨域 404；8 用例评审跨域列表为空；9 测试计划附件跨域下载 404；10 执行器跨域 403。**全部断言不泄露条数**（不得出现 `total>0` 但列表空）。

---

## 6 测试与证据

### 6.1 新增门禁脚本

| 文件 | 作用 |
|---|---|
| `scripts/collab-verify.sh`（新） | 复用 `scripts/tests/quality/_lib.sh`（`require_stack`/`section`/`expect`/`expect_jq`），依次跑 s0–s8，末尾 `#SUMMARY total= pass= fail=`，任一 FAIL 退出 1 |
| `scripts/tests/collab/s0-schema.sh` … `s8-isolation.sh`（新 9 个） | 每组 ≥6 条断言，总计 ≥70 |
| `scripts/tests/collab/stub/server.mjs`（新） | GitLab/Sonar 四合一桩 @`127.0.0.1:19091`（**避开 M7 的 19090**），提供 protected_branches / project settings / MR notes / sonar measures 四种响应与调用计数 `/__stub/count` |
| `scripts/collab-perf.sh`（新） | AC-20 五项 P95（300 行甘特 / 1 万页检索 / 500 节点树 / 10 万需求价值流 / 1 万行列表），种子由脚本内 `--perf-fixture` 造，跑完清理 |
| `Makefile`（改） | `collab-verify`、`collab-perf` 两个薄封装；`test` 追加 `collab-verify` |

s 组覆盖：s0 表/列/索引/种子/回填校验行 + 权限码单测；s1 甘特三视图×三缩放、改期 4 条负向、`audit_log` 断言；s2 Wiki 空间/4 层边界/循环/5 版 diff/回滚 hash/搜索 3×2 用户；s3 7 阶段种子/启停/排序/类型收窄 + WIP/均值/P90 直算对照 + 三处读数；s4 分支 CRUD/四类校验/三态/零外呼；s5 MR 3 note 幂等 + 代码质量字段/空态/聚合去重；s6 用例评审钉版本/改版失效/重复/意见长度/`wf_definition` 未新增；s7 测试数据文本/附件/锁/审计摘要 + 执行器 5 态与上报联动；s8 隔离 10 条。

### 6.2 单元测试（Java）

| 测试 | 断言 |
|---|---|
| `GanttWarningsTest`（req） | 4.9 人天不告警 / 5.1 告警；3 天临期边界；逾期优先于临期 |
| `GanttConfirmTokensTest`（req） | 一次性、绑定 type/id/日期、5 分钟过期 |
| `WikiVisibilityTest`（req） | 三态 × 3 用户 × 3 空间 = 9 格；六出口同一谓词（同一 SQL 片段引用） |
| `WikiDiffTest`（req） | 行级 LCS：增/删/改/空输入 |
| `FlowMetricFormulasTest`（deliver） | P90 nearest-rank（n=1/2/10/11）；效率分母 0 排除；瓶颈样本 <5 不参与 |
| `ExecutorHealthTest`（deliver） | 23:59 / 24:01 / 72:01 四态 |
| `CaseReviewRulesTest`（deliver） | 意见 <10 字、重复、过期版本、非评审人 |
| `BranchPolicyValidatorTest`（req） | 四类矛盾与重复 |
| `PermissionMatrixGridTest`（既有，改） | 55 格、角色不冲突 |
| `ScopeSqlTest`（既有，改） | `audit_log`/`wiki_space`/`wiki_page` 可 `forTable` |

### 6.3 门禁不回退（AC-19）

`make verify`(14/14)、`platform-verify`(≥167)、`quality-verify`(≥348)、`platform-test-negative`(≥25)、`platform-unit`(≥178 + 新增 ≥30)、`lint` 16/16、`web typecheck` 0 error、`build` 成功、`integration-verify`(≥230)、`collab-verify`(≥60)。ACCEPTANCE 表逐条记录命令/断言数/耗时。

---

## 7 任务拆分（按文件边界，可并行）

> 单分支 `feat/ccode-cteam-ctest-p0`。T1 必须先合入（schema 是所有任务的前提）；T2–T6 之间文件不相交可并行；T7/T8 前端可与后端并行（按 §2 契约 mock 到真实接口）；T9 依赖 T1–T8 的接口存在；T10 收尾。

| # | 任务 | 修改的文件（own） | 读取（read） | 验收 |
|---|---|---|---|---|
| T1 | 迁移 V9 + 回填 + 种子 + 权限码与路由 | `req-service/src/main/resources/db/migration/V9__ccode_cteam_ctest_p0.sql`、`platform-common/.../scope/ScopeSql.java`、`platform-common/.../security/PermissionRouteTable.java`、`platform-contract/.../PermissionMatrix.java`、`platform-common/.../audit/ActualPeriodRecorder.java`、`req-service/src/main/java/com/example/req/attachment/AttachmentService.java`、相关单测 | `V1__platform_schema.sql`、`V5`、`V8`、`ScopeSqlTest`、`PermissionMatrixGridTest` | s0 全绿；空库/已有库各迁移一次行数一致 |
| T2 | 甘特后端 | `req-service/src/main/java/com/example/req/collab/gantt/*`、`GanttWarningsTest`、`GanttConfirmTokensTest` | `RequirementRepository`、`ReleaseRepository`、`SprintRepository`、`AuditService` | s1；改期 4 负向 + 审计 |
| T3 | Wiki 后端 | `req-service/src/main/java/com/example/req/collab/wiki/*`、`WikiVisibilityTest`、`WikiDiffTest` | `AttachmentService`、`ScopeSql` | s2 |
| T4 | 价值流后端 | `deliver-service/src/main/java/com/example/deliver/flow/*`、`deliver-service/.../metric/MetricQuery.java`、`FlowMetricFormulasTest` | `ReqReadModel`、`MetricDefinitionRepository`、`ScopeSql` | s3；三处读数差 0 |
| T5 | CCode 后端 | `req-service/.../collab/policy/*`、`req-service/.../integration/gitlab/{GitLabAdapter,MrSyncService,MrViews,MrComment*}.java`、`req-service/.../integration/sonar/CodeQualityService.java`、`CodeQualityViews.java`、单测 | `OutboundHttpClient`、`SyncLogWriter`、`SonarSnapshotService` | s4/s5；零外呼 |
| T6 | CTest 后端 | `deliver-service/.../review/*`、`deliver-service/.../executor/*`、`deliver-service/.../testplan/{TestPlanEntity,TestPlanController,TestPlanService}.java`、`deliver-service/.../testcase/TestCaseService.java`、`deliver-service/.../openapi/{OpenApiViews,ExecutionIngestService}.java`、单测 | `AttachmentService`、`AuditService` | s6/s7 |
| T7 | 前端协作域 | `web/src/router/routes/collab.routes.ts`、`web/src/layouts/nav.ts`、`web/src/api/collab.ts`、`web/src/views/collab/*`、`web/src/components/collab/*`、`web/src/utils/{markdown,ganttScale}.ts`、`web/src/stores/dict.ts`、`web/src/composables/useCollabPrefs.ts` | §2 契约、`web/src/api/client.ts` | `npm run typecheck` + 手测三态 |
| T8 | 前端 CCode/CTest/详情改造 | `web/src/router/routes/{code,deliver}.routes.ts`、`web/src/api/{code,deliver,req}.ts`、`web/src/views/code/*`、`web/src/views/deliver/{TestExecutorsView,TestCaseLibraryView,TestPlanDetailView,TestPlanExecuteView}.vue`、`web/src/components/{code,deliver,req}/*`、`web/src/views/req/tabs/{IntegrationTab,DocumentsTab}.vue`、`web/src/views/req/RequirementDetailView.vue`、`web/src/views/approval/*` | T5/T6 接口、`components/StatusPill.vue` | typecheck + 截图 |
| T9 | 证据脚本与文档 | `scripts/collab-verify.sh`、`scripts/collab-perf.sh`、`scripts/tests/collab/**`、`Makefile`、`docs/teamflow/DELIVERED-MODULES.md`、`docs/teamflow/CAPABILITY-MATCH-canway.md` | `scripts/tests/quality/_lib.sh`、`scripts/integration-verify.sh` | `make collab-verify` ≥60、`make collab-perf` 5 项 |
| T10 | 交付自检（非 QA） | 仅修 T1–T9 暴露的问题 | — | 十项门禁全绿；只记录结论，不写 QA-REPORT/ACCEPTANCE |

**Git 约束（随任务提交，不得丢失）**：当前分支即 `feat/ccode-cteam-ctest-p0`（从 `main@9072719` 拉出），**不新建第二条特性分支**；本轮唯一未提交改动 `?? docs/teamflow/20261109-r6-ccode-cteam-ctest-p0/` 必须随本轮一起提交，**不 stash、不回滚**；提交前 `git status` 确认无 `target/`、`logs/`、`.run/`、`node_modules/` 噪音；提交信息带 runId 与任务夹路径。

---

## 8 与 PRD/DESIGN 的偏差（已决，附理由）

1. **`test_execution.executor_id` 已存在**：PRD/AC-18 把它列为 V9 增列，但 `V1__platform_schema.sql` 建表即有该列，实体也已映射。**V9 不重复 ALTER**（重复会报 Duplicate column），只在 s0 断言列存在；`test_executor` 的关联改由业务层保证。
2. **`utils/diff.ts` 取消**：DESIGN §9.1 计划前端 LCS，但 AC-5 要求接口"返回逐行内容"，两处实现会产生第二份 diff 口径。改为服务端 `WikiDiff` 唯一实现，前端只渲染（`WikiDiffDrawer` 用接口数据）。
3. **权限码大写 vs 既有小写冒号**：按 AC-17/DESIGN 原文保留大写常量，既有 `metric:manage` 等不动；两类码在矩阵中并列，管理页按 `family` 分组显示。
4. **确认码不落库**：AC-18 的 11 表是硬约束，5 分钟一次性 token 用"HMAC 签名 + 进程内 jti 消费表"实现，单实例成立；重启即失效（fail-closed）。多实例部署需换 Redis/表，记入 memory 待办。
5. **不做价值流汇总表**：PRD 允许但 AC-18 表数固定；先用 `audit_log` 索引 + 窗口函数，`collab-perf` 若超 2s 再按 PRD 的"可重算校验"升级（届时 V10）。
6. **`gantt.capacity.week` 不建参数表**：库中无 `sys_param` 表。改为配置项 `collab.gantt.capacity.week`（默认 5）+ `collab.gantt.capacity.by-domain.<domainId>` 覆盖；响应回传生效值，页面只展示。
7. **分支核验 409 与 UNVERIFIED 并存**：AC-11 写"集成关闭 409 `INTEGRATION_DISABLED`"，同时要求状态 `UNVERIFIED`。实现为：HTTP 409 + 同事务落一条 `result='UNVERIFIED'` 的核验记录；前端把 409 渲染成黄色"未核验"而非错误。

---

## 9 交付自检清单

1. 11 表 + 增列 + ngram 索引 + `idx_audit_req` 全部存在，且 V9 在已有 V8 库上重跑不报错。
2. 回填校验行 `有审计但未回填=0`；`actual_*` 在状态流转后继续被 `ActualPeriodRecorder` 维护。
3. 甘特：拖拽成功必有 `GANTT_RESCHEDULE`；越窗必须二次确认；刷新后日期不回退。
4. Wiki：六出口同一谓词；`wiki_page_version` 无更新路径；回滚旧版 hash 不变；搜索 `total` 只计可见。
5. 价值流：看板 / 指标字典 preview / 报表 三处同值；无审计需求被排除而非计 0。
6. CCode：`UNVERIFIED` 永不为绿；打开页面桩计数 0；drift 不改规则。
7. CTest：改版即 `INVALIDATED` 同事务；`wf_definition` 无新 biz_type；停用执行器上报 422 且不落 `test_execution`。
8. 权限：14 码进矩阵（55 格）与路由表；s8 十条负向 404/403 且不泄露条数。
9. 门禁：§6.3 十项全绿；`make collab-verify` ≥60、`make collab-perf` 五项。
10. 仓洁与提交：无 `target/`/`logs/`/`.run/` 噪音；任务夹随本轮提交；未新增第二条分支。
