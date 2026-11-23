# r39 技术方案 · CTeam 自动化规则引擎

> 任务夹：`docs/teamflow/20261123-r39-automation-rule-engine/`（PRD.md 18 条 AC / DESIGN.md 9 节）
> 基线：r38（`docs/teamflow/20261123-r38-orphan-join-gate/`）
> 分支：`feat/automation-rules-engine`（既有分支，不新建、不合并）
> 阅读顺序：开发先读 §0 + §13（任务），再按任务号读对应章节；QA 读 §7 + §12。

---

## 0. 结论摘要与五项硬裁决

一句话：**把「事件」做成审计的附属物、把「求值」压进一个纯函数、把「执行」收进一个抢占唯一键的 worker**，于是幂等、防循环、越权为零、不阻塞业务这四件事都不是"注意一下"，而是结构上做不到别的结果。

- **事件只有一个产生点**：`platform-common` 新增 `AuditListener` SPI，`AuditService.record(...)` 在**同一事务、同一线程**里回调它；`req-service` 的 `AutomationEventOutbox` 实现该接口，把审计动作翻译成 0..n 条 `automation_event`。前端没有任何上报入口，`POST/PUT /automation/events` 天然 404（没有这个映射）。
- **求值只读快照**：`AutomationConditionEvaluator.evaluate(ConditionSpec, Map snapshot)` 是纯静态函数（无 Spring、无 JDBC），条件键封闭、缺失即不命中，单测不需要容器。
- **作用域只有一份**：规则作用域 = 在 worker 线程临时 `TenantContext.set(rule.getScope())`（`AutomationScopes.withScope`），之后所有候选查询、复核都走既有的 `ScopedJdbc` + `/*scope:r*/`。automation 包里**不允许出现** `domain_id` / `owner_vendor_id` 字面量拼接，由 lint L21 静态断言。
- **幂等先于判定**：先 `INSERT automation_run (status='RUNNING')` 抢 `(tenant, rule, requirement, event)` 唯一键，抢不到就跳过；抢到之后再复核 Scope → 条件 → 限流。首次即使 `SKIPPED` 也占位，语义至多一次。
- **动作是白名单、且顺序固定**：DB 动作（ASSIGN_OWNER/ADD_TAG）先提交 → 再通知/外呼。automation 包不得 import/call 审批引擎、状态流转入口、发布单创建（lint L21 + `automation-verify` S8 双保险）。

五项需要拍板的技术口径（PRD §11 的 6 问已在 §15 逐条裁决，这里只列会改代码形状的 5 条）：

| # | 裁决 | 理由 |
|---|---|---|
| D1 | 事件表/运行表**严格按 PRD 3.1 的列**建，一列不多 | AC-1 要"列如 3.1"；多加 `rule_name`/`finished_at` 会让 AC-1 无法逐字核对。日志页要的规则名用 `LEFT JOIN automation_rule`，结束时间由 `created_at + duration_ms` 推导 |
| D2 | `automation_run.requirement_id` 允许 NULL，唯一键对 NULL 不生效；因此**唯一不靠键的只有一条**：SCHEDULE 截断行（每规则每天至多一条，写前 `INSERT ... WHERE NOT EXISTS`） | MySQL 唯一键把 NULL 视为彼此不同；把 `requirement_id` 改成 `NOT NULL DEFAULT 0` 会污染"需求"语义。只给一条已知单写的路径用显式存在性检查，其余全部吃唯一键 |
| D3 | 定时事件的 `event_id` 是 `SCHEDULE:<date>:<requirementId>` | PRD 3.2 写 `SCHEDULE:<yyyy-MM-dd>`，但 `(tenant, event_id)` 唯一键会把同一天同租户的 N 条扫成 1 条。加需求后缀既保留可读前缀，又让"每需求每规则每天至多一次"可被键约束 |
| D4 | `trigger_type` 只有 4 个枚举，**触发参数不另开列**，全部落在 `condition_json`（`statusIn` / `fields` / 时间走全局配置） | PRD 3.1 的列里没有 `trigger_json`；再开一列等于 AC-1 不过。DESIGN 说的"触发类型及参数"在表单上仍是两段，落库合并 |
| D5 | WEBHOOK 的 `configId` 允许指向**任意已启用的 `integration_config`**，不新增 `IntegrationSystem.WEBHOOK` | 新增枚举值要动 V8 的列注释、集成配置页四槽与 `integration-verify`，收益只是"语义更贴"。URL 仍只由 `base_url + path` 组成、密钥不入规则 JSON，SSRF 由既有 `SsrfGuard` 承担 |

---

## 1. 变更面总览

### 1.1 新增文件

| 类别 | 路径 | 作用 |
|---|---|---|
| 迁移 | `req-service/src/main/resources/db/migration/V15__automation_rule.sql` | 四张表 + 索引 |
| 契约 | `platform-common/src/main/java/com/example/platform/common/audit/AuditListener.java` | 审计旁路 SPI（同事务） |
| 后端 | `req-service/src/main/java/com/example/req/automation/**`（8 个子包，见 §8） | 规则域 / 事件 / 引擎 / 动作 / 定时 |
| 前端 | `web/src/api/automation.ts`、`web/src/views/admin/automation/{RulesListView,RuleEditorView,RunsView}.vue`、`automation.css` | 四视图 + 端点封装 |
| 前端 | `web/src/components/{AutomationRuleSentence,AutomationRunVerdict,TagChips}.vue` | 句式单点 / 结论渲染 / 标签 chip |
| 门禁 | `scripts/automation-verify.sh` | 6 段 ≥36 条（§12.2） |

### 1.2 修改文件（逐条给理由，超出此表的改动视为越界）

| 文件 | 改什么 | 为什么非改不可 |
|---|---|---|
| `platform-common/.../audit/AuditService.java` | 构造器多一个 `List<AuditListener>`，`insert(...)` 之后回调 | 事件的唯一来源必须是审计写入点 |
| `platform-common/.../scope/ScopeSql.java` | `tables()` 增 4 行 `TableSpec.NONE` | 未注册表上的 `/*scope:*/` 会抛异常（fail-closed） |
| `platform-common/.../security/PermissionRouteTable.java` | 增 `/automation/**` 规则块 | 页面码/动作码的唯一判定入口 |
| `platform-contract/.../{MenuRegistry,ActionRegistry,PermissionMatrix}.java` | 各 +1 条目 | AC-3 |
| `req-service/.../requirement/RequirementService.java` | 新增 `assignOwner(...)`；`transition(...)` 增一个带 extra detail 的重载；`update/applyApprovedEstimate` 的 detail 补 `before` | ASSIGN_OWNER 的唯一入口；审批结果/字段 before 是事件的原料 |
| `req-service/.../requirement/RequirementApprovalCallback.java` | 调用时带 `instanceId/taskId/approvalResult` | `STATUS_CHANGE` 要能分辨审批通过与驳回 |
| `req-service/.../requirement/RequirementViews.java` | `View` 增 `List<TagView> tags`，新增 `record TagView` | 标签要在既有需求视图可见（AC-17） |
| `req-service/.../requirement/RequirementPoolQuery.java` | 分页后批量补 tags | 列表 chip 不能 N+1 |
| `req-service/.../release/ReleaseService.java` | 两条成员审计的 detail 增 `changed[]`（before/after） | `FIELD_CHANGE release_id` 的唯一可观测点（bizId 是 releaseId，事件钩子拿不到需求 id 与旧值） |
| `req-service/.../seed/DemoFixture.java` | `DELETE_ORDER` 增 4 表（在 `requirement` 之前）+ 种 2 条规则 | AC-14 |
| `req-service/.../seed/DemoResetService.java` | 末尾（Tx3 之后）插规则并标记重置前的 PENDING 事件为 `DONE` | 否则重置期的事件会被新规则重放，污染通知=4 |
| `req-service/src/main/resources/application.yml` | `platform.automation.*` 10 个键 | AC-11/13 可配 |
| `web/src/layouts/nav.ts` | 设置组 +1 项（`automation-rules`，`internalOnly: true`） | 导航是页面码的日常来源 |
| `web/src/layouts/pageRegistry.ts` | 注释 36→37；无需改逻辑（自动从 NAV 汇总） | 文档一致性 |
| `web/src/router/routes/deliver.routes.ts` | +4 条路由，均 `meta.pageCode='automation.rules'` | DESIGN §2 指定；四条路由同一个页面码 |
| `web/src/views/admin/AdminLayout.vue` | 管理页签列表 +1 | 进入规则页的第二入口 |
| `web/src/views/req/RequirementPoolView.vue`、`RequirementDetailView.vue`、`tabs/OverviewTab.vue` | 渲染 `tags` chip | AC-17 |
| `web/src/api/req.ts` | `View` 接口增 `tags` | 类型同步 |
| `scripts/tests/lint.sh` | L9 白名单 + `automation/`；新增 L21 `automation_boundary()` | §12.4 |
| `scripts/tests/rbac/{s0,s1,s2,s5,s7,s8}.sh/*.mjs`、`scripts/tests/rbac/_lib.sh` | 计数常量 36/60/72/168/1848/660/396/792/352 → 37/61/73/171/1881/671/407/803/353 | §10.2 一张表 |
| `scripts/tests/access/{a3-nav-audit,a4-nav-visibility}.mjs` | 36 → 37（nav 项、页面码集合） | 同上 |
| `scripts/platform-demo-reset.sh` | `TOTAL_CHECKS` 76→80；`168`→`171`（两处）；新增 4 条断言 | AC-14 |
| `scripts/rbac-verify.sh` | 仅段注释里的数字 | 读的人不迷路 |
| `Makefile` | `.PHONY` + `automation-verify` 目标 + `help` 可见 | AC-15 |

### 1.3 禁区（改了就红）

- `pom.xml` / `web/package.json` / 锁文件：**零新增依赖**（AC-18）；唯一的"新东西"是 JDK/Spring 自带的 `FOR UPDATE SKIP LOCKED` 与 `@Scheduled`。
- V1–V14 迁移：字节级不变（lint L10 比对）。
- 既有九条门禁的断言**只许增不许减不许跳**；`scripts/tests/access/*`、`rbac/*` 里只改数字，不改判定逻辑。
- `AGENTS.md`：除托管区外一字不动；`memory.md` 只改 r39 那一行（已改好）。

---

## 2. 数据模型（V15）

### 2.1 DDL 全文

```sql
-- V15__automation_rule.sql — r39 自动化规则引擎。四张表，幂等 DDL，不加外键。
-- 为什么幂等：空库首启与已有库升级要跑同一份文件；重复执行不报错是 AC-1 的原文要求，
-- 也是 r30/r33 事故（半途失败后重跑改不动）的教训。
-- 为什么不用外键：本仓库全库无 FK（r1 起），隔离靠 tenant_id + ScopeSql，删除顺序靠
-- DemoFixture.DELETE_ORDER 与业务侧事务；引入 FK 会让既有 demo-reset 的 delete 顺序变成雷区。

CREATE TABLE IF NOT EXISTS `automation_rule` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `name`           VARCHAR(100) NOT NULL COMMENT '去首尾空格后唯一',
  `enabled`        TINYINT(1)   NOT NULL DEFAULT 1,
  `trigger_type`   VARCHAR(32)  NOT NULL COMMENT 'REQUIREMENT_CREATED/STATUS_CHANGE/FIELD_CHANGE/SCHEDULE',
  `condition_json` JSON         NOT NULL COMMENT '封闭 schema，见 TECHNICAL §4.1',
  `actions_json`   JSON         NOT NULL COMMENT '白名单数组，≤10 条，见 §5.1',
  `scope_type`     VARCHAR(16)  NOT NULL COMMENT 'DOMAIN/VENDOR',
  `scope_id`       BIGINT       NOT NULL COMMENT 'biz_domain.id 或 vendor.id',
  `priority`       INT          NOT NULL DEFAULT 100 COMMENT '数值小者先执行',
  `version`        INT          NOT NULL DEFAULT 1 COMMENT '乐观锁 + 执行日志回溯',
  `created_by`     BIGINT       NOT NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`     BIGINT       NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_name` (`tenant_id`, `name`),
  KEY `idx_rule_dispatch` (`tenant_id`, `enabled`, `trigger_type`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动化规则（r39）';

CREATE TABLE IF NOT EXISTS `automation_event` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `event_id`       VARCHAR(120) NOT NULL COMMENT '服务端生成，见 §3.2',
  `event_type`     VARCHAR(32)  NOT NULL,
  `requirement_id` BIGINT       NULL COMMENT 'SCHEDULE 允许为空',
  `origin`         VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER/SYSTEM/RULE',
  `chain_depth`    INT          NOT NULL DEFAULT 1 COMMENT '≥4 一律 DROPPED/CHAIN_DEPTH',
  `payload_json`   JSON         NOT NULL COMMENT '事件快照，条件只在这个快照上求值',
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/DROPPED',
  `drop_reason`    VARCHAR(32)  NULL COMMENT 'RULE_ORIGIN/CHAIN_DEPTH/MALFORMED',
  `attempts`       INT          NOT NULL DEFAULT 0,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `processed_at`   DATETIME(3)  NULL,
  `locked_at`      DATETIME(3)  NULL COMMENT 'PROCESSING 超过 60s 可被重新领取',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_dedupe` (`tenant_id`, `event_id`),
  KEY `idx_event_pending` (`tenant_id`, `status`, `id`),
  KEY `idx_event_requirement` (`tenant_id`, `requirement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动化事件 outbox（r39）';

CREATE TABLE IF NOT EXISTS `automation_run` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `rule_id`        BIGINT       NOT NULL,
  `rule_version`   INT          NOT NULL,
  `requirement_id` BIGINT       NULL,
  `event_id`       VARCHAR(120) NOT NULL,
  `event_type`     VARCHAR(32)  NOT NULL,
  `status`         VARCHAR(16)  NOT NULL COMMENT 'RUNNING/SUCCESS/PARTIAL/FAILED/SKIPPED',
  `skip_reason`    VARCHAR(32)  NULL COMMENT '见 §6.4；事件级原因不占本列',
  `actions_json`   JSON         NULL COMMENT '逐动作 {type,status,message,target}',
  `duration_ms`    BIGINT       NOT NULL DEFAULT 0,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_dedupe` (`tenant_id`, `rule_id`, `requirement_id`, `event_id`),
  KEY `idx_run_rule_time` (`tenant_id`, `rule_id`, `created_at`),
  KEY `idx_run_req` (`tenant_id`, `requirement_id`, `created_at`),
  KEY `idx_run_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则执行日志（r39）';

CREATE TABLE IF NOT EXISTS `requirement_tag` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT      NOT NULL,
  `requirement_id` BIGINT      NOT NULL,
  `tag`            VARCHAR(32) NOT NULL,
  `source`         VARCHAR(16) NOT NULL COMMENT 'RULE/MANUAL',
  `created_by`     BIGINT      NOT NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_req_tag` (`tenant_id`, `requirement_id`, `tag`),
  KEY `idx_tag_req` (`tenant_id`, `requirement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求标签（r39）';
```

### 2.2 三个必须写进代码注释的坑

1. **`uk_run_dedupe` 里的 NULL**：MySQL 唯一索引把 NULL 视为互不相等。因此除 `SCHEDULE` 截断行（`requirement_id IS NULL`，每规则每天至多一条，写前 `INSERT ... SELECT ... WHERE NOT EXISTS`）之外，**任何** run 都必须带真实 `requirement_id`；`AutomationRunWriter` 的入口对 `requirement_id == null` 只允许 `skipReason == SCHEDULE_TRUNCATED`，其余抛 `IllegalArgumentException`（开发期即炸，而不是线上悄悄失去幂等）。
2. **`JSON` 列不是字符串列**：`condition_json`/`actions_json` 用 `JSON` 类型存，但**校验只在 Java 侧**（封闭 schema）；DB 不做 CHECK——MySQL 的 JSON CHECK 表达式在各版本行为不一致，写进迁移会变成"能存不能查"。
3. **`uk_event_dedupe` 是"至多一条"而不是"恰好一条"**：审计回调重放（如审批引擎重试回调）写同一 `event_id` 时走 `INSERT IGNORE`，只留一行，`attempts` 不自增。

### 2.3 与 `DELETE_ORDER` 的关系

`DemoFixture.DELETE_ORDER` 追加四张表，**必须排在 `requirement` 之前**（`requirement_tag`/`automation_run` 有 `requirement_id`，虽无 FK，但先删子表能保证"重置后新表 0 行"这条断言不受删除顺序影响）：

```java
TableReset.purge("automation_run"),
TableReset.purge("automation_event"),
TableReset.purge("automation_rule"),
TableReset.purge("requirement_tag"),
// ...既有 ...，requirement 仍在最后
```

---

## 3. 事件管线

### 3.1 审计旁路 SPI（同事务）

```java
// platform-common/src/main/java/com/example/platform/common/audit/AuditListener.java
public interface AuditListener {
    /** 在审计行已落库之后、外层业务事务提交之前同步调用；异常必须自己吞掉并记日志。 */
    void onAudit(String action, String bizType, Long bizId, Map<String, Object> detail);
}
```

`AuditService` 的改动只有三处：构造器接 `List<AuditListener>`（Spring 注入空表时退化为 `List.of()`）、`insert(...)` 之后 `for (l : listeners) l.onAudit(...)`、`recordInNewTx` **同样**回调（403 拒绝也会产生事件，`origin=SYSTEM`，但事件映射表里没有 `PERMISSION_DENY`，所以 0 条）。

为什么用 SPI 而不是把映射表塞进 `platform-common`：动作→事件的映射是**需求域知识**（`CREATE`/`STATE_CHANGE`/`RELEASE_MEMBER_ADD` 的字段含义），放进公共层会让公共层反向依赖业务语义；SPI 让依赖方向保持 `req-service → platform-common`。

### 3.2 事件 id 与映射表

`event_id` 全部由服务端拼，前端拿不到（`POST /automation/events` 没有映射 → 404）：

| 来源 | event_id | event_type |
|---|---|---|
| `CREATE`(REQUIREMENT) | `REQ:<reqId>:CREATED:<auditId>` | `REQUIREMENT_CREATED` |
| `STATE_CHANGE`(REQUIREMENT) | `REQ:<reqId>:STATUS:<auditId>` | `STATUS_CHANGE` |
| `UPDATE`/`ESTIMATE_APPLIED`/`VENDOR_TRANSFER`/`DELIVER`/`OWNER_ASSIGN` | `REQ:<reqId>:FIELD:<field>:<auditId>`（每变更字段一条） | `FIELD_CHANGE` |
| `RELEASE_MEMBER_ADD`/`REMOVE`(RELEASE) | `REQ:<reqId>:FIELD:release_id:<auditId>`（按 `detail.changed[]` 展开） | `FIELD_CHANGE` |
| 定时扫描 | `SCHEDULE:<yyyy-MM-dd>:<reqId>` | `SCHEDULE` |

`<auditId>` = `audit_log.id`（插入后的自增主键，`AuditService` 用 `GeneratedKeyHolder` 拿到）；没有它，两次"把优先级从 P2 改成 P1"会生成同一个 `event_id`，第二条就会被键吞掉。

`origin` / `chain_depth` 的推导（`AutomationEventMapper.map(...)` 纯函数）：

```java
// detail 里没有这几个键时按默认值——审计调用方不需要知道自动化的存在
String origin = str(detail, "automationOrigin", "USER");   // RULE 只由 AutomationActionExecutor 写
int depth     = intVal(detail, "automationChainDepth", 1); // 动作内产生的审计：父深度 + 1（上限 9）
```

### 3.3 需要扩的审计 detail（只加键，不改旧键）

| 位置 | 现状 | 补什么 |
|---|---|---|
| `RequirementService.create()` | `{type,code,status,parentId}` | 再带完整快照键（见 §4.2 的 8 个字段），事件钩子直接搬运 |
| `RequirementService.update()` | `before{title,priority,expectedDeliveryDate}` / `after{title,priority}` | before/after 补齐 `ownerUserId`、`estimateDays`、`releaseId`（即使为 null 也要有键，代表"未变"） |
| `RequirementService.transition(...)` | `{from,to,action,source,comment,operator}` | 新重载 `transition(id, action, comment, source, Map<String,Object> extra)`，审批路径传 `approvalInstanceId/approvalTaskId/approvalBizType/approvalResult` |
| `RequirementService.applyApprovedEstimate()` | `{instanceId, estimateDays}` | 增 `before:{estimateDays}` |
| `RequirementService.assignOwner(...)`（新） | — | `{ownerUserId:{before,after}, source:"RULE", ruleId, runId}` + `automationOrigin=RULE` |
| `ReleaseService.addRequirement/removeRequirement` | `{requirementIds,count}` / `{requirementId}` | 增 `changed:[{id,before,after}]`（只列 `release_id` 真的变了的） |

`RequirementApprovalCallback` 三处调用改为传 extra：`onApproved` → `approvalResult=APPROVED`，`onRejected` → `REJECTED`，`onWithdrawn` → `WITHDRAWN`；`approvalBizType` 取 `"REQ_SUBMIT"`（复核链 `RequirementReviewCallback` 传 `"REQ_REVIEW"`，WBS 评估 `"WBS_ESTIMATE"`）。**Q1 的答案**：现状不含 `approvalInstanceId/approvalResult`，本轮必须扩（见 §15）。

### 3.4 outbox 状态机与领取

```
PENDING ──claim──▶ PROCESSING ──done──▶ DONE
   │                    │
   │                    ├─ origin=RULE ──▶ DROPPED/RULE_ORIGIN
   │                    ├─ chain_depth≥4 ─▶ DROPPED/CHAIN_DEPTH
   │                    └─ payload 坏 ────▶ DROPPED/MALFORMED
   └──attempts≥5 / 超过 24h──▶ DROPPED/MALFORMED（并告警）
```

领取 SQL（`AutomationEventStore.claim(int limit)`，在自己的事务里跑完即提交，**不在事务里执行动作**）：

```sql
SELECT id, tenant_id, event_id, event_type, requirement_id, origin, chain_depth, payload_json, attempts
  FROM automation_event
 WHERE status = 'PENDING'
   AND created_at <= NOW(3)
 ORDER BY id
 LIMIT :limit
   FOR UPDATE SKIP LOCKED;
-- 随后：UPDATE automation_event SET status='PROCESSING', locked_at=NOW(3), attempts=attempts+1 WHERE id IN (:ids)
```

崩溃恢复：`UPDATE automation_event SET status='PENDING', locked_at=NULL WHERE status='PROCESSING' AND locked_at < NOW(3) - INTERVAL :stale SECOND`（默认 60s），由同一个 poller 在每轮开头执行。`automation_run` 侧对应地把超时 `RUNNING` 置 `FAILED`（`UPDATE automation_run SET status='FAILED', duration_ms=GREATEST(duration_ms, :staleMs) WHERE status='RUNNING' AND created_at < ...`），**不重放动作**——这就是 AC-9"进程在动作后、状态更新前被终止，恢复后不重放"的实现。

### 3.5 worker 与业务隔离

```java
@Scheduled(fixedDelayString = "${platform.automation.worker-poll-ms:1000}")
public void poll() { dispatcher.drain(); }
```

- 单 poller 线程 + `Semaphore(workerConcurrency=2)` 控制"同时在飞的事件数"；同一条事件内的候选规则**串行**（PRD 3.6.7）。
- 动作在 `TransactionTemplate`（`REQUIRES_NEW`）里执行 DB 动作并提交，通知/外呼在提交之后同步发（但仍在这个 worker 线程内）——业务 HTTP 线程早已返回，AC-13 的"<1s 返回"由此保证。
- 事务模板里绑定 `TenantContext.set(DataScope.system(tenantId))`，动作执行时再 `AutomationScopes.withScope(ruleScope, ...)`（见 §4.3），`finally` 里两段都还原。

### 3.6 定时扫描

```java
@Scheduled(cron = "${platform.automation.schedule-cron:0 30 8 * * *}", zone = "Asia/Shanghai")
public void dailyScan() { scanner.scanAllTenants(); }
```

- 逐租户：`TenantContext.set(DataScope.system(tenantId))` → 取 `enabled=1 AND trigger_type='SCHEDULE'` 的规则 → 对每条规则 `withScope(ruleScope)` 跑候选查询（`LIMIT 201`）。
- 命中 ≤200 条：每需求一条 `SCHEDULE:<date>:<reqId>` 事件（`INSERT IGNORE`，`chain_depth=1`）。
- 命中 >200 条：前 200 条照常入事件，另写一条 `requirement_id IS NULL`、`event_id='SCHEDULE_TRUNCATED:<date>'` 的事件 + 对应 run（`SKIPPED/SCHEDULE_TRUNCATED`），并 `log.warn` 带 ruleId/命中数。**不静默截断**。
- 停机错过不补（PRD 非目标）：不维护 lastRunDate 水位，只按当天日期生成事件 id。

---

## 4. 条件与作用域

### 4.1 封闭 schema（`automation_rule.condition_json`）

```json
{
  "types": ["BUS"],
  "priorityIn": ["P0", "P1"],
  "statusIn": ["SUBMITTED"],
  "approvalResults": ["APPROVED"],
  "estimateDays": { "op": "gte", "value": 10 },
  "domainIds": [12],
  "vendorIds": [5],
  "fields": ["estimate_days"]
}
```

- 键集合**闭合**：出现任何未列出的键 → `400 AUTOMATION_SCHEMA_INVALID`，`detail.field` 指到键名。空对象 `{}` 合法（= 全命中）。
- `types` ∈ `{BUS,PDR,RD}`（`RequirementRules.isKnownType`）；`priorityIn` ∈ `{P0,P1,P2,P3}`；`statusIn` 取自 `StateTables.statuses("BUS"/"PDR"/"RD")` 的并集（**不写字面量**，状态表是唯一来源）；`approvalResults` ∈ `{APPROVED,REJECTED,WITHDRAWN}`；`estimateDays.op` ∈ `{gt,gte,lt,lte,eq}`，`value` ∈ `[0,1000]` 且最多两位小数；`fields` ⊂ `{priority,owner_user_id,estimate_days,release_id}`。
- 数组内 OR、键间 AND；**字段缺失 = 不命中**（`Verdict(false, "field_missing:<key>")`），不抛异常、不 500。
- 单条规则 ≤10 个动作、`condition_json+actions_json` ≤8KB、`name` ≤100 字符（去空格后非空）——超限分别是 `TOO_MANY_ACTIONS` / `PAYLOAD_TOO_LARGE` / `NAME_TOO_LONG`。

解析入口（唯一）：

```java
public record ConditionSpec(List<String> types, List<String> priorityIn, List<String> statusIn,
                            List<String> approvalResults, EstimateDays estimateDays,
                            List<Long> domainIds, List<Long> vendorIds, List<String> fields) {
    public static ConditionSpec parse(JsonNode node); // 抛 AutomationErrors.schema(...)
}
public record EstimateDays(String op, BigDecimal value) {}
```

### 4.2 求值（纯函数）

```java
public record Verdict(boolean matched, String reason) {}
public static Verdict evaluate(ConditionSpec spec, Map<String, Object> snapshot);
```

快照键（`AutomationEventMapper` 保证齐全，缺就用 `null` 占位）：`type, priority, status, approvalResult, estimateDays, domainId, ownerVendorId, assigneeVendorId, ownerUserId, releaseId, field, before, after`。

- `FIELD_CHANGE` 的 `fields` 命中用 `snapshot.field`（一条事件只带一个字段，见 §3.2）。
- `SCHEDULE` 用扫描时的当前行做快照（PRD 3.3）。
- 求值顺序固定为 `types → priorityIn → statusIn → approvalResults → estimateDays → fields`，第一个不命中即为 `reason`，让日志能解释"为什么没命中"。

### 4.3 作用域：一份谓词，两种身份

```java
// req-service/.../automation/engine/AutomationScopes.java
public static <T> T withScope(DataScope scope, Supplier<T> work) {
    DataScope prevScope = TenantContext.isSet() ? TenantContext.require() : null;
    Principal prevPrincipal = TenantContext.principal();
    TenantContext.set(scope); // 不设 principal：动作的 actor 由审计自己记 SYSTEM
    try { return work.get(); }
    finally { if (prevScope == null) TenantContext.clear(); else { TenantContext.set(prevScope); TenantContext.setPrincipal(prevPrincipal); } }
}
```

规则作用域就是一个 `DataScope`：

| scope_type | DataScope | 候选谓词（由 `ScopeSql.forTable("requirement","r",scope)` 生成，**代码里不出现列名**） |
|---|---|---|
| `DOMAIN` | `DataScope.of(tenantId, DOMAIN, Set.of(scopeId), null, 0, Set.of())` | `r.domain_id IN (:__scopeDomains)` |
| `VENDOR` | `DataScope.of(tenantId, VENDOR, Set.of(), scopeId, 0, Set.of())` | `(r.owner_vendor_id = :__scopeVendor OR r.assignee_vendor_id = :__scopeVendor)` |

候选 SQL 只有一条（`AutomationDispatcher.candidates`）：

```sql
SELECT r.id FROM requirement r
 WHERE /*scope:r*/
   AND (:triggerType <> 'SCHEDULE' OR r.status NOT IN ('CLOSED','REJECTED'))
 ORDER BY r.priority, r.id
```

执行前复核（`SCOPE_LOST`）用同一个谓词 `SELECT r.* FROM requirement r WHERE r.id = :id AND /*scope:r*/`：0 行即 `SKIPPED/SCOPE_LOST`，**动作不执行**，`actions_json` 为空，日志里不出现标题（只有需求编号）。

### 4.4 条件不许放宽

`AutomationRuleValidation.checkConditionScope(scopeType, scopeId, ConditionSpec)`：

- `DOMAIN` 规则：`domainIds` 必须为空或恰为 `[scopeId]`；`vendorIds` 任意值都算放宽（域规则不该按厂商收窄到域外）→ `400 CONDITION_WIDENS_SCOPE`。
- `VENDOR` 规则：`vendorIds` 必须为空或 `[scopeId]`；`domainIds` 非空即放宽 → 同上。
- 收窄（`domainIds=[scopeId]`）允许，语义等价，不为难用户。

---

## 5. 动作

### 5.1 白名单与 JSON

```json
[{"type":"NOTIFY","recipients":[{"kind":"DOMAIN_OWNER"}]},
 {"type":"ASSIGN_OWNER","userId":42},
 {"type":"ADD_TAG","tag":"大需求"},
 {"type":"WEBHOOK","configId":3,"path":"/hooks/req"}]
```

- `type` 必须 ∈ `{NOTIFY,ASSIGN_OWNER,ADD_TAG,WEBHOOK}`，否则 `400 ACTION_NOT_ALLOWED`（`SET_STATUS`/`APPROVE`/`SKIP_APPROVAL`/`CREATE_RELEASE`/`EDIT_ROLLUP` 各自单独报，message 明说"自动化不允许改变状态/审批/人天汇总/发布单"）。
- `NOTIFY.recipients[]`：`{kind:USER|ROLE|DOMAIN_OWNER, id?, code?}`；`ASSIGN_OWNER.userId` 必填；`ADD_TAG.tag` 1..32 字符、去空格后非空；`WEBHOOK.configId` 必填且必须存在且 `active_key` 非空（已启用），`path` 必须以 `/` 开头、不含 `://`、不含 `..`（URL 不得来自规则 JSON）。
- 多动作按 JSON 数组顺序执行；DB 动作（`ASSIGN_OWNER`/`ADD_TAG`）先全做完再提交，`NOTIFY`/`WEBHOOK` 在提交后发（PRD 3.5）。

### 5.2 收件人解析与边界

`AutomationNotifier.resolveRecipients(spec, requirementSnapshot, ruleScope)`：

| kind | 解析方式 | 过滤 |
|---|---|---|
| `USER` | `id` 直取 | `sys_user.status='ACTIVE'` 且同租户 |
| `ROLE` | `UserScopeResolver.identitiesByRole(code)` | 同上 |
| `DOMAIN_OWNER` | `ApproverResolver.resolve("DOMAIN_OWNER", tenantId, 0, Set.of(domainId))` | 去重后同上 |

再对每个候选人做一次"你自己能不能看见这条需求"的判定（`ScopeGuard`/`ScopeSql` 单行探测），不可见即剔除——这是 AC-8"收件人必须按自身 Scope 能看到该需求"的落地。剔除后 0 人 → `SKIPPED/NO_RECIPIENT`；>50 人 → `SKIPPED/TOO_MANY_RECIPIENTS` 且**一条都不发**（避免"发一半"）。通知类型固定 `"AUTOMATION_RULE"`，`bizType="REQUIREMENT"`、`bizId=requirementId`，正文只放规则名 + 需求编号，不放正文。

### 5.3 四类动作的落地

| 动作 | 实现 | 失败语义 |
|---|---|---|
| `ASSIGN_OWNER` | `RequirementService.assignOwner(id, userId, "AUTOMATION")`（新方法：校验目标 ACTIVE、同租户、在规则作用域内，写 `OWNER_ASSIGN` 审计与 `FIELD_CHANGE` 事件，**不走** `transition`） | 目标非法 → `TARGET_INVALID`（SKIPPED）；已是负责人 → `SUCCESS` + `message="noop"` |
| `ADD_TAG` | `INSERT IGNORE INTO requirement_tag ...` + 审计 `REQUIREMENT_TAG_ADD` | 重复 → `SUCCESS` + `message="exists"`（幂等，不报错） |
| `NOTIFY` | `NotificationWriter.send(userId, "AUTOMATION_RULE", ...)` | 单发失败不回滚，逐项记 `FAILED`，整条 run 记 `PARTIAL` |
| `WEBHOOK` | `OutboundHttpClient.exchangeWithRetry(OutboundRequest)`，`system=config.system`、`operation="AUTOMATION_WEBHOOK"`、`retry=RetryPolicy.standard()`、`correlationId=eventId`、`bizType="AUTOMATION"`、`bizId=requirementId` | 2xx 前 3 次成功即 `SUCCESS`（500→500→200 → 第 3 次成功）；重试耗尽 → `FAILED` + `IntegrationNotifier.retryExhausted(request, response)` 告警 |

webhook 签名（复用 `HmacSigner`，不新写）：

```
X-App-Key:    <config.appKey>
X-Timestamp:  <epoch millis>
X-Signature:  HmacSigner.sign(HmacSigner.canonical("POST", path, appKey, timestamp, bodyBytes), secret)
X-Event-Id:   <event_id>          // 对端去重
Content-Type: application/json
body:         {"eventId","eventType","ruleId","ruleVersion","requirementId","requirementCode","occurredAt"}
```

`path` 进的是 canonical 的 path 段（不含域名/query），对端用同一个 `HmacSigner.canonical` 复算即可验签——AC-8 的"签名可被对端用 HmacSigner 验证"。

### 5.4 代码级边界（静态锁）

lint 新增 **L21 `automation_boundary()`**，扫 `req-service/src/main/java/com/example/req/automation/**`：

1. 禁止出现 `ApprovalEngine`、`ApprovalStarter`、`ReleaseService`、`DeployOrderService`、`.transition(`；
2. 禁止出现 `domain_id` / `owner_vendor_id` / `assignee_vendor_id` 字面量（作用域只能来自 `ScopeSql`）；
3. 禁止 `SET_STATUS` 等被禁动作码出现在**执行**分支（允许出现在拒绝名单常量里，因此断言"拒绝名单常量文件之外不出现"）；
4. 只允许 `AutomationOwnerAssigner` 一处调用 `RequirementService.assignOwner`，且 `RequirementService` 不反向依赖 automation 包。

这条断言与 `automation-verify` S8 的行为断言互为兜底：拦住了"将来有人顺手加一个改状态的动作"。

---

## 6. 幂等、限流、防循环

### 6.1 抢占顺序（唯一正确的顺序）

```
1. INSERT INTO automation_run (tenant, rule_id, rule_version, requirement_id, event_id, event_type, status='RUNNING', created_at)
      → DuplicateKeyException ⇒ 记 log.debug("DUPLICATE_EVENT rule={} event={}") 并继续下一条规则（不写第二行）
2. Scope 复核            失败 ⇒ UPDATE status='SKIPPED', skip_reason='SCOPE_LOST'
3. 条件求值              失败 ⇒ UPDATE status='SKIPPED', skip_reason='CONDITION_NOT_MET'
4. 限流                  失败 ⇒ UPDATE status='SKIPPED', skip_reason='RATE_LIMITED'
5. DB 动作（新事务提交）  失败 ⇒ status='FAILED'
6. 通知/外呼             有失败 ⇒ status='PARTIAL'；全成功 ⇒ 'SUCCESS'
7. UPDATE automation_run SET status, skip_reason, actions_json, duration_ms
```

为什么不先求值再抢占：先求值就会在 `SKIPPED` 时不占位，同一事件重投会重算一遍并可能发两次通知。**先占位再判定**是"至多一次"的唯一实现方式，代价是"一次都没算过的事件也可能占位"（不可能：抢占是第一步）。

`AutomationRunWriter.tryStart(...)` 的返回类型是 `Optional<Long>`：`empty` = 抢不到，`present(runId)` = 抢到。签名固定：

```java
public Optional<Long> tryStart(long tenantId, long ruleId, int ruleVersion, Long requirementId,
                               String eventId, String eventType);
public void finish(long runId, String status, String skipReason,
                   List<ActionOutcome> outcomes, long durationMs);
```

### 6.2 限流

`AutomationRateLimiter`：`ConcurrentHashMap<Long tenantId, Window>`，`Window{AtomicInteger count; volatile long epochMinute;}`，`tryAcquire(tenantId)` 在分钟翻转时重置。上限 = `platform.automation.max-runs-per-minute`（默认 120）。单实例内存计数（PRD 风险项已登记为技术债，代码注释写明"多实例需改 DB 计数"）。

单测要覆盖 `119 → 允许`、`120 → 允许`、`121 → 拒绝`（AC-11），并覆盖"跨分钟重置"。

### 6.3 防循环

- 动作里任何写操作（`assignOwner`、`ADD_TAG` 的审计）在 detail 里带 `automationOrigin=RULE` 且 `automationChainDepth=<父+1>`。`AutomationEventMapper` 照抄进事件行。
- `AutomationDispatcher` 对每个事件先做两道闸：`origin=RULE` → `DROPPED/RULE_ORIGIN`；`chain_depth>=4` → `DROPPED/CHAIN_DEPTH`。两者**都不产生 run**（AC-10 的"0 run"）。
- 阈值的理由：正常链路 1（业务）→2（规则动作）→3（动作又触发）已属罕见；4 层只可能是环，宁可误拦也不让环形链路继续烧。

### 6.4 枚举全集（前后端、文档、脚本共用一张表）

`automation_run.status`：`RUNNING / SUCCESS / PARTIAL / FAILED / SKIPPED`
`skip_reason`：`CONDITION_NOT_MET / RATE_LIMITED / SCOPE_LOST / NO_RECIPIENT / TOO_MANY_RECIPIENTS / TARGET_INVALID / SCHEDULE_TRUNCATED`
`automation_event.status`：`PENDING / PROCESSING / DONE / DROPPED`
`drop_reason`：`RULE_ORIGIN / CHAIN_DEPTH / MALFORMED`

前端只在 `AutomationRunVerdict.vue` 里做 code→人话映射（`CONDITION_NOT_MET → 条件未命中`），未知 code 一律渲染原文（不吞）。

---

## 7. API 契约

基址：网关前缀 `/api/req`（前端 `web/src/api/automation.ts` 唯一拼），服务端映射 `/automation/**`（`req-service` 的 controller，`server.servlet.context-path` 不涉及）。分页统一 `page`（1 起）/`size`（≤100），响应 `{items:[], total, page, size}`（沿用 `PageResponse`）。

### 7.1 端点表

| # | 方法 | 路径 | 页面码 / 动作码 | 请求 | 响应 |
|---|---|---|---|---|---|
| 1 | GET | `/automation/rules` | `automation.rules`（页） | `keyword, triggerType, enabled, page, size` | `PageResponse<RuleView>` |
| 2 | GET | `/automation/rules/{id}` | `automation.rules` | — | `RuleView` |
| 3 | POST | `/automation/rules` | `automation.action.manage` | `RuleCmd` | `201 RuleView` |
| 4 | PUT | `/automation/rules/{id}` | `automation.action.manage` | `RuleCmd`（带 `version`） | `200 RuleView` |
| 5 | PATCH | `/automation/rules/{id}/enabled` | `automation.action.manage` | `{"enabled":true,"version":3}` | `200 RuleView` |
| 6 | GET | `/automation/runs` | `automation.rules` | `ruleId, status, requirementCode, from, to, page, size` | `PageResponse<RunView>` |
| 7 | GET | `/automation/runs/{id}` | `automation.rules` | — | `RunView` |
| 8 | GET | `/automation/meta` | `automation.rules` | — | `MetaView` |

**没有** `DELETE /automation/rules/{id}`（PRD 非目标：不提供硬删除，停用即永久保留）；**没有** `/automation/events`（AC-4 要求 404/405）。

### 7.2 DTO

```java
public record RuleCmd(String name, Boolean enabled, String triggerType, String scopeType, Long scopeId,
                      Integer priority, JsonNode condition, JsonNode actions, Integer version) {}

public record RuleView(long id, String name, boolean enabled, String triggerType,
                       JsonNode condition, JsonNode actions,
                       String scopeType, Long scopeId, String scopeLabel,
                       int priority, int version,
                       long createdBy, String createdByName,
                       LocalDateTime createdAt, LocalDateTime updatedAt,
                       RunBrief lastRun) {}

public record RunBrief(LocalDateTime at, String status, String skipReason) {}

public record RunView(long id, long ruleId, String ruleName, int ruleVersion,
                      String eventType, String eventId, Long requirementId, String requirementCode,
                      String status, String skipReason,
                      List<ActionOutcome> actions, long durationMs, LocalDateTime createdAt) {}

public record ActionOutcome(String type, String status, String message, String target) {}

public record MetaView(List<CodeLabel> triggers, List<CodeLabel> actions,
                       List<String> conditionKeys, List<String> estimateOps,
                       List<String> fields, List<String> statuses,
                       List<WebhookOption> webhookConfigs) {}
public record CodeLabel(String code, String label) {}
public record WebhookOption(long id, String system, String name, String host) {} // host 只有主机名
```

不返回的东西：密钥（`integration_config` 的 credential/secret 本来就只有 `last4`）、完整 webhook URL（只给 `host`）、需求正文。`RuleView.condition/actions` 原样回显（用户的输入），但其中不含 URL 与密钥。

### 7.3 错误码

| HTTP | code | 触发 |
|---|---|---|
| 400 | `AUTOMATION_SCHEMA_INVALID` | 未知条件键/未知字段/JSON 形状错（`detail.field` 给键名） |
| 400 | `INVALID_TRIGGER` | `triggerType` 不在四类 |
| 400 | `INVALID_SCOPE` | `scopeType` 不在 `{DOMAIN,VENDOR}` 或 `scopeId` 不存在/不可见 |
| 400 | `ACTION_NOT_ALLOWED` | 未知或禁止的动作类型 |
| 400 | `TOO_MANY_ACTIONS` | >10 |
| 400 | `PAYLOAD_TOO_LARGE` | condition+actions >8KB |
| 400 | `NAME_TOO_LONG` | 名称 >100 |
| 400 | `CONDITION_WIDENS_SCOPE` | 条件域/厂商超出规则作用域 |
| 409 | `AUTOMATION_RULE_NAME_TAKEN` | 同名（去空格后） |
| 409 | `AUTOMATION_RULE_VERSION_CONFLICT` | PUT/PATCH 的 `version` 不是当前值（`detail.currentVersion`） |
| 403 | `INTERNAL_ONLY` | 厂商账号（`principal.vendorId != null`）访问任何 `/automation/**` |
| 403 | `FORBIDDEN` | 内部账号无页面码/动作码（既有拦截器产出） |
| 404 | `AUTOMATION_RULE_NOT_FOUND` | id 不存在或不属于本租户 |

### 7.4 权限登记（`PermissionRouteTable`）

```java
// ---------------------------------------------------------------- automation (r39)
rules.add(rule("POST",   "^/automation/rules$", action("automation.rules", "automation.action.manage")));
rules.add(rule("PUT",    "^/automation/rules/\\d+$", action("automation.rules", "automation.action.manage")));
rules.add(rule("PATCH",  "^/automation/rules/\\d+/enabled$", action("automation.rules", "automation.action.manage")));
rules.add(rule(null,     "^/automation.*$", page("automation.rules")));
```

页面判定先于动作（既有顺序）：`automation.rules` 为 DENY 时，即使动作码侥幸 ALLOW 也进不来。

### 7.5 厂商 403 `INTERNAL_ONLY`（服务端唯一新增的拦截点）

现状：全仓服务端**没有** `INTERNAL_ONLY`，它只是前端的路由守卫（`web/src/router/index.ts:127`）。本轮新增一个只挂 `/automation/**` 的拦截器，排在权限拦截器之前：

```java
// req-service/src/main/java/com/example/req/automation/AutomationWebConfig.java
@Configuration
public class AutomationWebConfig implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AutomationInternalOnlyInterceptor())
                .addPathPatterns("/automation/**")
                .order(-100);   // 必须早于 PermissionInterceptor（默认 order=0），否则厂商先吃到 FORBIDDEN
    }
}

// AutomationInternalOnlyInterceptor.preHandle
if (TenantContext.isSet() && TenantContext.principal() != null
        && TenantContext.principal().vendorId() != null) {
    throw new ApiException(403, "INTERNAL_ONLY", "仅内部账号可访问自动化规则");
}
```

为什么不用 `@ModelAttribute` 守卫：`preHandle` 先于模型绑定，只有它能让 `INTERNAL_ONLY` 抢在 `FORBIDDEN` 前面。

---

## 8. 后端类结构（`com.example.req.automation`）

```
automation/
├── AutomationWebConfig.java            注册 internal-only 拦截器（order -100）
├── api/
│   ├── AutomationRuleController.java    /automation/rules + /automation/meta
│   ├── AutomationRunController.java     /automation/runs
│   ├── AutomationDtos.java              RuleCmd/RuleView/RunView/RunBrief/ActionOutcome/MetaView
│   └── AutomationInternalOnlyInterceptor.java
├── rule/
│   ├── AutomationRuleEntity.java        JPA 实体（@Table automation_rule）
│   ├── AutomationRuleRepository.java    JpaRepository<AutomationRuleEntity, Long>
│   ├── AutomationRuleService.java       CRUD + 启停 + 审计 + version 乐观锁
│   ├── AutomationRuleViews.java         entity → RuleView（含 lastRun 批量补充）
│   └── AutomationRuleValidation.java    名称/体积/scope/条件收窄/动作白名单
├── spec/
│   ├── AutomationTriggerType.java       REQUIREMENT_CREATED/STATUS_CHANGE/FIELD_CHANGE/SCHEDULE
│   ├── AutomationActionType.java        NOTIFY/ASSIGN_OWNER/ADD_TAG/WEBHOOK + FORBIDDEN 集合
│   ├── AutomationSpecs.java             ConditionSpec/ActionSpec/Recipient/parse
│   └── AutomationErrors.java            稳定错误码工厂
├── event/
│   ├── AutomationEventOutbox.java       implements AuditListener
│   ├── AutomationEventMapper.java       static List<AutomationEventDraft> map(...)
│   ├── AutomationEventDraft.java        record
│   └── AutomationEventStore.java        claim/recover/markDone/markDropped/insertIgnore
├── engine/
│   ├── AutomationWorker.java            @Scheduled poll + drain()
│   ├── AutomationDispatcher.java        单事件：闸门 → 候选规则 → 抢占 → 复核 → 求值 → 限流 → 动作 → 收尾
│   ├── AutomationConditionEvaluator.java  static Verdict evaluate(ConditionSpec, Map)
│   ├── AutomationRuleMatcher.java         static boolean matches(String triggerType, AutomationEventRow)
│   ├── AutomationRunWriter.java          Optional<Long> tryStart(...); void finish(...)
│   ├── AutomationRateLimiter.java        boolean tryAcquire(long tenantId)
│   ├── AutomationScopes.java             static <T> T withScope(DataScope, Supplier<T>)
│   └── AutomationSnapshot.java           payload → typed snapshot（含 field/before/after）
├── action/
│   ├── AutomationActionExecutor.java     按序执行 + DB 先行 + 汇总 ActionOutcome
│   ├── AutomationNotifier.java           收件人解析/过滤/发送
│   ├── AutomationTagWriter.java          INSERT IGNORE + 审计
│   ├── AutomationOwnerAssigner.java      调 RequirementService.assignOwner
│   └── AutomationWebhookSender.java      签名 + 重试 + 告警
└── job/
    └── AutomationScheduleScanner.java    @Scheduled(cron, zone) 每日扫描
```

关键签名（开发必须逐字使用；成员名写错是 P1）：

```java
// rule
public RuleView create(RuleCmd cmd);
public RuleView update(long id, RuleCmd cmd);
public RuleView setEnabled(long id, boolean enabled, int version);
public PageResponse<RuleView> list(String keyword, String triggerType, Boolean enabled, int page, int size);
public RuleView view(long id);

// event
public void onAudit(String action, String bizType, Long bizId, Map<String, Object> detail); // overrides AuditListener
public static List<AutomationEventDraft> map(String action, String bizType, Long bizId, Map<String, Object> detail);
public int insertIgnore(List<AutomationEventDraft> drafts);          // INSERT IGNORE，返回实际插入行数
public List<AutomationEventRow> claim(long tenantId, int limit);
public void markDone(long id);
public void markDropped(long id, String dropReason);
public int recoverStale(long tenantId, int staleSeconds);

// engine
public void drain();                                                  // 由 @Scheduled 调；返回 void
public void dispatch(AutomationEventRow event);
public static Verdict evaluate(ConditionSpec spec, Map<String, Object> snapshot);
public static boolean matches(String triggerType, AutomationEventRow event);
public Optional<Long> tryStart(long tenantId, long ruleId, int ruleVersion, Long requirementId,
                              String eventId, String eventType);
public void finish(long runId, String status, String skipReason, List<ActionOutcome> outcomes, long durationMs);
public boolean tryAcquire(long tenantId);
public static <T> T withScope(DataScope scope, Supplier<T> work);

// action
public List<ActionOutcome> execute(AutomationRuleEntity rule, ConditionSpec spec,
                                   List<ActionSpec> actions, AutomationEventRow event, DataScope ruleScope);

// requirement（新增）
public void assignOwner(long requirementId, long ownerUserId, String reason);
public Map<Long, List<RequirementViews.TagView>> tagsByRequirementIds(Collection<Long> ids);
```

`AuditService` 的构造签名变化（唯一被改的公共类）：

```java
public AuditService(JdbcTemplate jdbc, List<AuditListener> listeners)
```

---

## 9. 前端

### 9.1 路由与页面码

四条路由全部 `meta.pageCode = 'automation.rules'`、`navKey='automation-rules'`、`internalOnly: true`（厂商深链 → `/403?code=INTERNAL_ONLY`）：

| path | name | 组件 |
|---|---|---|
| `/automation/rules` | `automation-rules` | `RulesListView.vue` |
| `/automation/rules/new` | `automation-rule-create` | `RuleEditorView.vue`（create 模式） |
| `/automation/rules/:id(\\d+)/edit` | `automation-rule-edit` | `RuleEditorView.vue`（edit 模式） |
| `/automation/runs` | `automation-runs` | `RunsView.vue` |

一个页面码 + 页内双 tab（DESIGN §2）：`自动化规则 / 执行日志`，2px 主色下划线；**不新增第二个页面码**——否则 `s1` 三向比对、`nav`（37 项）、`a3/a4` 的页面码集合要各再改一轮。

### 9.2 组件树

```
RulesListView.vue
├── PageHeader（标题 + 新建按钮：无 automation.action.manage → disabled + title）
├── 筛选行（keyword / triggerType / enabled；全部写进 URL query，刷新可复现）
├── el-table
│   ├── AutomationRuleSentence  ← 首列：把 触发+作用域 渲染成一句中文（DESIGN §0）
│   ├── StatusPill（启用/停用）
│   └── 行操作：编辑 / 启停开关（ConfirmDialog + version）
└── EmptyState / ErrorState

RuleEditorView.vue
├── AutomationRuleSentence（顶部常驻预览，随表单实时变化）
├── §1 基础信息（name / priority）
├── §2 触发（4 张卡片的 segmented）
├── §3 作用域（DOMAIN/VENDOR + 选择器）
├── §4 条件（类型/优先级/状态/审批结果/人天/域/厂商/字段；域与厂商行在作用域选定后只读 = 作用域）
├── §5 动作（4 型分段控件 + 最多 10 条 + ↑↓ 排序；WEBHOOK 只列 meta.webhookConfigs）
└── §6 摘要 + 保存（409 → 顶部常驻冲突条 + "刷新"按钮，不做 toast）

RunsView.vue
├── 筛选（规则/状态/需求编号/时间范围，默认"最近 7 天" chip，可移除）
├── el-table：时间 / 规则 / 事件 / 需求 / AutomationRunVerdict（五态结论 + 人话原因）
└── 展开行：逐动作 {type,status,message,target}（mono 字体）
```

### 9.3 状态管理

- **不新建 Pinia store**：规则草稿是页面局部 `reactive`；列表筛选在 URL query；权限读既有 `useAuthStore()`（`canAnyMenu('automation.rules')` / `canAny('automation:manage')`）。
- `web/src/api/automation.ts` 内部缓存 `meta`（模块级 `Promise`，会话内只拉一次），四视图共用一份枚举与 webhook 选项——前端**不硬编码**触发/动作/条件键/状态码，全部来自 `GET /automation/meta`，这条保证 `statusIn` 的候选永远等于 `StateTables` 的真值。
- 文案（code→人话）只有一处：`AutomationRunVerdict.vue` 的 `SKIP_LABELS`；`AutomationRuleSentence.vue` 的触发/动作标签表也在这里（从 meta 取 label，不自己写第二份）。

### 9.4 交互与边界（对齐 DESIGN §0.5 的五条反模式）

1. 触发/动作/条件键的 label 全部来自 meta；前端只拥有"句式"（`[触发][作用域] 且 [条件] → [动作]`）。
2. 作用域选定后，条件的域/厂商选择器 `disabled` 且显示 `= 作用域`，而不是"允许填再报错"——服务端 `CONDITION_WIDENS_SCOPE` 只是兜底。
3. 停用行**不降透明度**，只降字色；开关常驻可见（止血是最高频动作）。
4. 字段级错误就近渲染（`detail.field` → 表单行下方），409/400 不弹 toast；`ApiError.isConflict` 走常驻冲突条。
5. 执行日志默认 7 天必须显示为可移除 chip；不自动轮询，只给「刷新 + 更新于 hh:mm:ss」。

### 9.5 标签 chip

`TagChips.vue`：`props: { tags: TagView[], max?: number }`，超出折叠为 `+N`（`el-tooltip` 列全）。挂两处：`RequirementPoolView` 标题列（DESIGN 允许并入句式列）、`RequirementDetailView` 的 `OverviewTab` 标题下方。`source === 'RULE'` 的 chip 带 4px 左侧主色竖线（可区分人工/规则来源），不加新色板。

---

## 10. 权限与注册表（含格数迁移）

### 10.1 三处注册表各 +1

```java
// MenuRegistry.PAGES — 平台管理模块尾部
new MenuPage("automation.rules", "平台管理", "自动化规则", "automation-rules",
             List.of("automation:manage"), 90),

// ActionRegistry.ACTIONS
new ActionDef("automation.action.manage", "automation.rules", "管理规则",
              List.of("automation:manage"), false, 10),

// PermissionMatrix.FUNCTIONS 末尾
"automation:manage",
// allow/restrict 段
allow(matrix, "PLATFORM_ADMIN", "automation:manage");
allow(matrix, "PMO", "automation:manage");
restrict(matrix, "PMO", ...);   // 其余角色保持默认 DENY，不写 restrict
```

`highRisk=false`：HIGH_RISK 三条是 r18 冻结的语义（force_release/disable/grant），加第四条会改 `s1` 的高危断言口径。

### 10.2 格数迁移表（**必须同批机械改，否则六套门禁同时红**）

| 常量 | 旧 | 新 | 出现位置（逐行改） |
|---|---|---|---|
| 功能码总数 | 60 | 61 | `scripts/tests/rbac/s2-seed-equivalence.sh:102`（"660 11 60"）、`s0` 注释、`_lib.sh:71` 注释 |
| 页面码总数 | 36 | 37 | `s0-schema-migration.sh:105`、`a3-nav-audit.mjs:90/190/235/395/408-412`、`a4-nav-visibility.mjs:259-269`、`platform-demo-reset.sh`（若断言 36） |
| 动作码总数 | 72 | 73 | `s1-registry-three-way.mjs:162`、`s0` 注释、`ActionRegistry` 类注释"72" |
| 每角色格数 | 168 | 171 | `s0:128-129`、`s2:96`、`s5:110/179/211`、`s7:45-48`（快照长度守卫）、`s8:50`、`platform-demo-reset.sh`（`VPM_ROWS` 与 `BAD_ROLE_ROWS` 两处） |
| 每租户行数 / 分布 | 1848 / 660 / 396 / 792 | 1881 / 671 / 407 / 803 | `s0:113/120-124/142-143`、`s2:96/102/133/152/159`、`s8:114/190`（正则 `^[0-9]+/37/73/11/0$` 与 `/61/73/`） |
| MENU 逐格比对数 | 352 | 353 | `s2:140`（396−44 空格，新页绑定了功能码 ⇒ 不再是空格） |
| nav 项数 | 36 | 37 | `a3:90/235`（顶栏 8 不变） |
| 每角色格数注释 | 168 | 171 | `scripts/rbac-verify.sh:28/38`、`_lib.sh:68-77` |

> 这不是"顺手改数字"：`s8:190` 的正则、`s7:48` 的快照长度守卫都是**运行时守卫**，漏改会让 `rbac-verify` 直接红或让负向用例静默通过。§13 把这一整批归给 **T1**（文件边界：三张注册表 + 上述脚本），不与任何前端任务并行在同一文件上。

### 10.3 前端三处一致

`nav.ts` 项（`pageCode:'automation.rules'`, `functionCode:'automation:manage'`, `internalOnly:true`）→ `pageRegistry` 自动收录 → 路由 `meta.pageCode` 显式声明。三者由 `a3`（nav↔MenuRegistry↔DB）与 `s1`（三向）各查一遍；漏一处就是"导航能看到、直链进不去"。

---

## 11. 演示种子与 `platform-demo-reset` 80

### 11.1 种什么（Tx4，放在既有 Tx1/Tx2/Tx3 之后）

| 规则 | trigger | scope | condition | actions | priority | enabled |
|---|---|---|---|---|---|---|
| `P0/P1 新建通知域负责人` | `REQUIREMENT_CREATED` | `DOMAIN`/`ORD` | `{"priorityIn":["P0","P1"]}` | `[{"type":"NOTIFY","recipients":[{"kind":"DOMAIN_OWNER"}]}]` | 100 | 1 |
| `大需求自动打标` | `FIELD_CHANGE` | `DOMAIN`/`ORD` | `{"estimateDays":{"op":"gte","value":10},"fields":["estimate_days"]}` | `[{"type":"ADD_TAG","tag":"大需求"}]` | 110 | 1 |

顺序与去污染（三步，缺一不可）：

1. Tx1/Tx2/Tx3 照旧（Tx1 直插不发审计 → 0 事件；Tx2/Tx3 走引擎会发审计）；
2. **Tx4 开头**：`UPDATE automation_event SET status='DONE', processed_at=NOW(3) WHERE tenant_id=? AND status='PENDING'` —— 重置期间的历史事件不参与规则求值（否则 Tx3 的 `deliver` 会触发 R2 打标，`requirement_tag` 不再是 0 行）；
3. Tx4 再插两条规则并提交。之后 worker 只会看到"重置之后"的新事件。

### 11.2 `platform-demo-reset.sh` 76 → 80

| 断言 | 期望 | 为什么需要它 |
|---|---|---|
| 规则存在且启用 | `SELECT COUNT(*) FROM automation_rule WHERE tenant_id AND enabled=1` = 2 | AC-14"两条规则存在且启用" |
| R1 触发/作用域 | 规则 1 的 `trigger_type='REQUIREMENT_CREATED'`、`scope_type='DOMAIN'`、`condition_json->>'$.priorityIn'` 含 P0/P1 | 只数条数不看内容 = 形同虚设 |
| R2 条件/动作 | 规则 2 的 `condition_json->>'$.estimateDays.value'`=10、`actions_json` 含 `ADD_TAG` | 同上 |
| 末尾复位断言 | `automation_run/automation_event/requirement_tag` 全 0 行 | 重置后必须"干净" |

`TOTAL_CHECKS=76` → **80**；`168` → **171** 两处（`VPM_ROWS`、`BAD_ROLE_ROWS`），注释里的 `11 × 168 = 1848` → `11 × 171 = 1881`。`NOTIFY -eq 4` 不动（这是本轮最容易被规则污染的一条）。

### 11.3 `DEMO-BY-ROLE.md` §9（脚本 + 3 张截图）

1. 登录 admin → 自动化规则 → 新建 P0 需求（`ORD` 域）→ 5 秒内执行日志出现 `SUCCESS` + 域负责人收到 1 条通知（截图 1）；
2. 把某需求 `estimate_days` 改为 12 → 日志出现 `ADD_TAG`，需求列表出现「大需求」chip（截图 2）；
3. 停用 R1 → 再建 P0 需求 → 日志无新行、无新通知（截图 3）。

截图落 `docs/teamflow/demo-shots/`（`.gitignore` 不得误忽略，lint L13 复核）。

---

## 12. 测试策略

### 12.1 单测（`req-service/src/test/java/com/example/req/automation/`，纯 JUnit5 + AssertJ，不起容器）

| 测试类 | 覆盖 | 约 |
|---|---|---|
| `AutomationSpecsParseTest` | 未知键/未知动作/空动作/>10 动作/name 超长/tag 超长/path 非 `/` 开头/`SET_STATUS` → 各自错误码 | 14 |
| `AutomationConditionEvaluatorTest` | 键间 AND、数组内 OR、`estimateDays` 五比较符、字段缺失不命中（带 reason）、`fields` 只对 FIELD_CHANGE 生效、空条件全命中 | 16 |
| `AutomationEventMapperTest` | CREATE / STATE_CHANGE（APPROVED、REJECTED）/ UPDATE 多字段展开 / ESTIMATE_APPLIED 带 before / RELEASE_MEMBER_ADD 按 changed[] 展开 / 无关键返回 0 条 / `origin=RULE` 透传 / `chain_depth` 递增 | 12 |
| `AutomationRateLimiterTest` | 119 允许 / 120 允许 / 121 拒绝 / 跨分钟重置 | 4 |
| `AutomationRuleMatcherTest` | 四类触发匹配、`SCHEDULE` 不匹配需求事件 | 5 |
| `AutomationTriggerTypeTest` | 枚举与 PRD 3.1 字面量一致（防止改名） | 1 |

`platform-unit` 断言数只增（AC-16）。DAO/worker 的集成行为交给 `automation-verify` 在真栈上验。

### 12.2 `scripts/automation-verify.sh`（6 段 ≥36 条，独立 `RUN_TAG`）

风格照 `scripts/release-flow-verify.sh`：`AUTOMATION_TOTAL=39`、`PASS/FAIL`、缺前置直接 `die` 不 skip、`--static-only` 只跑 S2/S5 的静态断言。

| 段 | 条数 | 断言（摘要） |
|---|---|---|
| S1 正例 | 6 | 建规则 201/version=1；列表可见；建 P0 需求后 5s 内 run=SUCCESS；`actions_json` 含 NOTIFY SUCCESS；通知 +1；需求 `status`/`estimate_days` 未变 |
| S2 负例 | 8 | 未知条件键 400；`SET_STATUS` 400 ACTION_NOT_ALLOWED；空动作 400；动作 >10 400；name >100 400；payload >8KB 400；同名 409；旧 version 409 |
| S3 幂等 | 6 | 同 event_id 重投两次 → run 恰 1 行；通知恰 1；标签恰 1；负责人变更恰 1 次审计；同键第二条 run 不存在；日志无第二条 SUCCESS |
| S4 防循环 | 5 | 动作产生的事件 origin=RULE；该事件 DROPPED/RULE_ORIGIN；该事件 0 run；手工 `chain_depth=4` → DROPPED/CHAIN_DEPTH；`chain_depth=3` 仍执行 |
| S5 越权 | 6 | 厂商 A 规则对 B 需求 0 run；0 通知；日志无 B 编号；放宽条件 400 CONDITION_WIDENS_SCOPE；失去可见性 → SCOPE_LOST；vendor1 → 403 INTERNAL_ONLY |
| S6 停用+动作边界 | 8 | 停用后 1s 内 0 新 run；停用 version+1；121 条至少 1 条 RATE_LIMITED；NO_RECIPIENT；TARGET_INVALID；WEBHOOK 未登记 configId 400；run 响应无密钥/完整 URL；清理后 RUN_TAG 残留 0 |

共 **39** 条 ≥36。连续两次运行第二次仍全绿（脚本内不复用一次性夹具，清理段按 `RUN_TAG` 前缀删 run/event/tag/notification/requirement）。运行后 `make platform-demo-reset` 仍 80/80。

### 12.3 既有九条门禁（不缩水口径）

`platform-verify ≥194` / `platform-test-negative ≥130` / `access-verify ≥206` / `rbac-verify 113` / `release-flow-verify 44` / `gate-order-verify 20` / `platform-demo-reset 80` / `lint 23`（22 + 新增 L21）/ `platform-unit` 只增。任何一条变红都不是"新代码还不稳定"，而是 §10.2 的格数迁移漏了某一行。

### 12.4 lint 两处

- **L9**：`allow_re` 增 `automation/`，并在文件头白名单注释里写明理由——"outbox/run/tag 需要在同一事务里读改写 + 唯一键抢占（ScopedJdbc 只有读入口）；worker/定时任务按 tenant 遍历，请求里没有 TenantContext；所有直连语句显式 `tenant_id = ?` 绑定"。
- **L21 `automation_boundary()`（新增）**：见 §5.4，四条静态断言，扫描 glob 固定为 `req-service/src/main/java/com/example/req/automation/**`。

### 12.5 AC-13 的测法

`automation-verify` S1 里用对端 `sleep 2` 的 stub（`scripts/lib/stub-http.py` 不引入新依赖：用 `nc`/`python3 -m http.server` 的方式与 r13 的 stub 一致），断言：创建需求接口 `time_total < 1s`；5s 内 run 出现。这与 S6 的 webhook 断言共用同一个 stub，不另起文件。

---

## 13. 任务拆分（并行执行，文件边界互斥）

依赖关系：`T1 ∥ T2 → T3 ∥ T4 → T5 → T7 → T8`，`T6` 在 T4/T5 的**契约**确定后即可开工（契约已在本文件 §7 冻结，可并行）。

| # | 任务 | 拥有（files） | 读 | 验收 |
|---|---|---|---|---|
| **T1** | 注册表与格数迁移 | `platform-contract/.../{MenuRegistry,ActionRegistry,PermissionMatrix}.java`、`scripts/tests/rbac/{s0-schema-migration.sh,s1-registry-three-way.mjs,s2-seed-equivalence.sh,s5-role-lockout.sh,s7-negative-nine.sh,s8-audit-reset.sh,_lib.sh}`、`scripts/tests/access/{a3-nav-audit.mjs,a4-nav-visibility.mjs}`、`scripts/rbac-verify.sh`（仅注释） | `TECHNICAL §10.2` | `rbac-verify` 113/113（格数 171/1881/671-407-803）；`s0/s1/s2` 全绿。**注**：`a3/a4` 要等 T6 加完 nav 项才转绿——两者必须同批合入 |
| **T2** | 迁移与作用域注册 | `req-service/src/main/resources/db/migration/V15__automation_rule.sql`、`platform-common/.../scope/ScopeSql.java`、`req-service/src/main/resources/application.yml` | PRD 3.1、V14 风格 | 空库/已有库各启动一次，四表齐、V15 一行；`make lint` L10 绿 |
| **T3** | 审计钩子与四类触发 | `platform-common/.../audit/{AuditListener.java,AuditService.java}`、`req-service/.../automation/event/**`、`req-service/.../requirement/{RequirementService.java,RequirementApprovalCallback.java}`、`req-service/.../release/ReleaseService.java` | §3、§8 | 单测 `AutomationEventMapperTest` 12 条；事务回滚 0 事件；审批通过/驳回各一条 STATUS_CHANGE |
| **T4** | 规则域与权限 | `req-service/.../automation/{rule/**,spec/**,api/AutomationRuleController.java,api/AutomationDtos.java,api/AutomationInternalOnlyInterceptor.java,AutomationWebConfig.java}`、`platform-common/.../security/PermissionRouteTable.java` | §4.1、§7、§10.1 | S2 8 条负例 + S5 的 403 INTERNAL_ONLY；`GET /automation/meta` 可用 |
| **T5** | 执行引擎与动作 | `req-service/.../automation/{engine/**,action/**,job/**}`、`req-service/.../requirement/{RequirementViews.java,RequirementPoolQuery.java,RequirementTagQuery.java}`、`req-service/.../automation/api/AutomationRunController.java` | §4.2-4.4、§5、§6、§8 | S1/S3/S4/S6 的非前端断言；单测 5 个类全绿 |
| **T6** | 四视图与标签 | `web/src/api/{automation.ts,req.ts}`、`web/src/views/admin/automation/**`、`web/src/components/{AutomationRuleSentence,AutomationRunVerdict,TagChips}.vue`、`web/src/layouts/{nav.ts,pageRegistry.ts}`、`web/src/router/routes/deliver.routes.ts`、`web/src/views/admin/AdminLayout.vue`、`web/src/views/req/{RequirementPoolView.vue,RequirementDetailView.vue,tabs/OverviewTab.vue}` | DESIGN 全文、UI-STYLE、§7 | `npm run typecheck && npm run build`；四视图可访问；无原始 JSON 编辑器；无权限按钮 disabled+title |
| **T7** | 演示种子与门禁 | `req-service/.../seed/{DemoFixture.java,DemoResetService.java}`、`scripts/platform-demo-reset.sh`、`scripts/automation-verify.sh`、`Makefile`、`scripts/tests/lint.sh` | §11、§12 | demo-reset **80/80** 连跑两次；`make automation-verify` 39/39 连跑两次；`make lint` 23 项 |
| **T8** | 文档与提交卫生 | `docs/teamflow/DEMO-BY-ROLE.md`、`docs/teamflow/architecture/ARCHITECTURE.md`、`docs/teamflow/memory.md`（只核 r39 行） | PRD §10 | `.gitignore` 复核（7 类产物 + `demo-shots/` 不被忽略）；提交信息含 `runId=tf-mwspkg4u-nvy5y5` 与任务夹路径；提交后九条门禁全绿 |

**git 约束（逐字进 T8，也不许丢）**：在既有 `feat/automation-rules-engine` 分支开发，**不新建分支、不合并**；工作区已有的未提交任务夹 `docs/teamflow/20261123-r39-automation-rule-engine/` 与 `docs/teamflow/memory.md` 必须**一并提交、不丢弃**；提交信息含 `runId=tf-mwspkg4u-nvy5y5` 与任务夹路径；`pom.xml`/`web/package.json`/锁文件 diff 必须为空；V1–V14 字节不变。

**冲突预防**：`platform-demo-reset.sh` 只归 T7（T1 不碰它的 168，由 T7 一起改）；`AutomationRunController` 归 T5（T4 只做 rule 侧）；`RequirementService` 归 T3（T5 只加 `RequirementTagQuery` 与视图字段，`assignOwner` 由 T3 落地——T5 读它）。

> 若并行度需要降低，合并顺序建议 `T1+T2 → T3+T4 → T5 → T6+T7 → T8`；**T3 与 T5 不可交换**（worker 没有事件可派发）。

---

## 14. 风险与应对

| 风险 | 现场 | 应对 |
|---|---|---|
| 格数迁移漏一处 → 六套门禁同红 | §10.2 的 9 处字面量分散在 10 个文件 | 全归 T1；提交前 `grep -rn "168\|1848\|660\|396\|792\| 36 \| 72 " scripts/tests/rbac scripts/tests/access` 逐条对表 |
| 演示通知不再是 4 条 | 规则在 Tx2/Tx3 之前就存在 → 历史事件被重放 | Tx4 先 `UPDATE ... status='DONE'` 再插规则（§11.1）；`automation-verify` 不碰演示租户的规则开关 |
| 门禁互相污染 | `gate-fixture.sh` 经 API 造数会触发演示规则（`DOMAIN=ORD`） | 演示规则只对 `ORD` 生效；`automation-verify` 用独立 `RUN_TAG` + 独立域/需求；结束时清理自己造的 notification |
| webhook 拖住 worker | 重试 6 次、每次 5s 超时 → 单事件最长 ~30s | 并发 2、单事件串行；`duration_ms` 进日志；超出即 `FAILED` + 告警，不阻塞其它事件 |
| `RUNNING` 卡死 | 进程在动作后、状态更新前被杀 | 60s 后 `PROCESSING→PENDING`、`RUNNING→FAILED`，不重放动作（§3.4） |
| 多实例限流放大 | 内存计数 | 单实例部署；代码注释 + `memory.md` 同主题行登记技术债 |
| `event_id` 撞车 | 同一审计动作被重试 | `audit_log.id` 进 event_id；`INSERT IGNORE` 只留一行 |
| 前端枚举漂移 | 手写状态/动作候选 | 全部来自 `GET /automation/meta`（§9.3） |

---

## 15. PRD §11 六问的裁决

| # | 性质 | 结论 | 依据 |
|---|---|---|---|
| Q1 | 事实 | **不够，必须扩**：`transition` 的 detail 只有 `{from,to,action,source,comment,operator}`，没有 `approvalInstanceId/approvalTaskId/approvalResult` | `RequirementApprovalCallback` 只在文案里塞了 instanceId；`§3.3` 的扩展表已列出改法（只加键） |
| Q2 | 口味 | 采纳默认：**超限丢弃 + `RATE_LIMITED`**，不排队 | 与"至多一次"一致；排队会引入积压与延迟，且需要新的 run 语义 |
| Q3 | 口味 | 采纳默认：**只允许已启用 `integration_config`**，且不新增 `WEBHOOK` 系统枚举（D5） | 免 SSRF、免密钥入 JSON；代价是接收方先登记 |
| Q4 | 口味 | 采纳默认：**仅 PLATFORM_ADMIN + 内部 PMO**，不给产品/域负责人只读 | 规则条件会暴露筛选口径与需求编号；只读入口要再走一轮页面码/矩阵 |
| Q5 | 口味 | 采纳默认：**08:30 Asia/Shanghai / 单规则单日 200 条**，超限记 `SCHEDULE_TRUNCATED` 并告警 | 可配（`platform.automation.schedule-cron` / `schedule-max-per-rule`） |
| Q6 | 事实 | "版本"= **`release_id`**；`version` 只作规则自身的乐观锁 | `requirement` 同时有 `release_id` 与 `version`（行版本），事件字段必须选一个；`FIELD_CHANGE.fields` 只列 `release_id` |

---

## 附：本方案对 PRD 的 1 处事实性修正

PRD 3.2 写定时事件 id 为 `SCHEDULE:<yyyy-MM-dd>`，但 `automation_event` 的唯一键是 `(tenant_id, event_id)`，同一天同租户的 N 条会被折叠成 1 条。本方案取 `SCHEDULE:<yyyy-MM-dd>:<requirementId>`（D3），前缀保持可读、语义从"每天一条"修正为"每需求每天一条"，并在 `automation-verify` S4 里断言"同一天第二次扫描不产生第二条事件"。
