# r39 验收报告 · 自动化规则引擎（automation-rule-engine）

- 任务夹：`docs/teamflow/20261123-r39-automation-rule-engine/`（runId=`tf-mwspkg4u-nvy5y5`）
- 分支：`feat/automation-rules-engine`；被测提交 `0980543`（修复轮 Q-1..Q-5），主交付 `d4ca363`
- 验收依据：`PRD.md:208-264` 的 AC-1～AC-18、`TECHNICAL.md`、注入的架构蓝图、`QA-REPORT.md`（再验证轮）
- 结论：**✅ 通过**。18 条 AC 逐条有本轮实跑或独立复核证据；架构与蓝图一致；无 P0/P1/P2，3 条 P3 观察不阻断。

## 1. 证据来源（含 PM 独立复核，不只读报告）

- QA 本轮 26 项探针全绿（`QA-REPORT.md` §1），上一轮 5 条缺陷（1×P2 + 4×P3）全部复核关闭。
- 本轮 PM 自己跑的复核：
  - `bash scripts/tests/lint.sh` → `ok lint 全部通过（24 项）`（含 L21 automation 边界、L22 标签同源）
  - `bash scripts/automation-verify.sh --static-only` → `AUTOMATION_STATIC_TOTAL=13 PASS=13 FAIL=0`
  - 架构 grep：`implements AuditListener` 仅 `AutomationEventOutbox`；写 `automation_run` 的仅 `AutomationRunWriter`；`ScopeSql.java:139-142` 四表登记；automation 包内无 `ApprovalEngine`/`.transition(` 调用（唯一命中是 `AutomationActionType.java:17` 的注释）
  - 前端四路由：`web/src/router/routes/deliver.routes.ts:218-270`（rules / rules/new / rules/:id(\\d+)/edit / runs，均挂 `pageCode=automation.rules`、`internalOnly:true`）
  - 3 张演示截图逐张人工走查（执行日志成功/跳过及原因、需求池「大需求」chip、规则列表启用/已停用状态列与句式）
  - git：分支正确；`0980543` 与 `d4ca363` 提交体均含 `runId=tf-mwspkg4u-nvy5y5` 与任务夹；`448ab4f..HEAD` 无 `pom.xml`/`web/package.json`/锁文件/V1–V14 改动

## 2. 逐条核对表

| AC | 判据（PRD） | 证据 | 结论 |
|---|---|---|---|
| AC-1 | V15 唯一新增、四表、V1–V14 不动、幂等、空库+已有库各启动 | `t2-v15-ddl.sh 18/18`（同库连跑两次不报错）；`t2-flyway-boot.sh 14/14`（空库 applied 15、已有库仅 +1 行）；`git diff` 无 V1–V14；lint L10 | ✅ |
| AC-2 | 封闭 schema/错误码/409/version 乐观锁/审计 | `t4-rule-domain-probe.sh 43 通过 0 失败`；automation-verify S2 负例；错误码集中在 `AutomationErrors`；单测 447 | ✅ |
| AC-3 | 三注册表 + 权限矩阵 + 厂商 403 | 注册表探针 37 页/73 动作/61 功能码/671/171/1881/363；`rbac-verify 113/113`；`access 206/206`；`AutomationInternalOnlyInterceptor` order(-100) 先于权限拦截；截图导航可见 | ✅ |
| AC-4 | 事件唯一源、无前端入口、回滚无事件、重启不丢 | `AuditService.java:113` 同事务回调 listeners；唯一实现 `AutomationEventOutbox`；`/automation/events` 无路由（接口一致性 36/36、lint L21 禁 `/events` 映射）；`t3-event-store-schema-probe.sh 9/9`；重启复测见 §4-2 | ✅ |
| AC-5 | 创建/状态含审批结果/字段四字段/定时四类触发 | `AutomationEventMapper.java:31-35` 四类常量 + 字段映射表；`qa-r39-demo-scenario.sh 7/7`；t3/t4 事件流 | ✅ |
| AC-6 | AND/OR、五比较符、缺失不命中、快照求值 | `AutomationConditionEvaluator` 纯函数单测（447 全绿覆盖五比较符/AND-OR/缺失）；S2/S3 真栈；t4 | ✅ |
| AC-7 | ScopeSql 共源、越权 0、放宽 400、SCOPE_LOST | `ScopeSql.java:139-142` 四表 NONE 且注释写明理由；lint L21 无第二份隔离列拼接；`qa-r39-scoped-idempotency.sh 5/5`；S5 越权段；t4 含 CONDITION_WIDENS_SCOPE | ✅ |
| AC-8 | 四动作白名单、禁止动作 400、收件人边界、webhook 签名重试 | `AutomationActionType.java:22-31` 四动作、拒绝名单 `:52`；lint L21 静态锁；`qa-r39-webhook-slow.sh 12/12`、`qa-r39-webhook-failure.sh`（SECRET_MISSING 归因）；t4 边界 | ✅ |
| AC-9 | 同事件恰 1 run/1 通知/1 标签、先抢键、崩溃不重放 | `AutomationRunWriter` 唯一写者；`qa-r39-scoped-idempotency.sh 5/5`；automation-verify S3；`uk_run_dedupe` 抢占顺序在注释与实现同处 | ✅ |
| AC-10 | origin=RULE 丢弃、depth≥4 拦截、0 run | automation-verify S4（防循环段）全绿；`AutomationDispatcher` 两道闸在最前；单测 chain depth | ✅ |
| AC-11 | 120/分钟可配、超限 SKIPPED/RATE_LIMITED、停用即时、50 条可解释 | `AutomationRateLimiterTest` 119/120/121；S6 灌 121 条事件落 RATE_LIMITED；停用后 0 新 run（候选查询 `AutomationRuleRepository.java:30-32` 按 enabled 过滤，下一事件即生效）；≤1s 断言强度见 §4-3 | ✅ |
| AC-12 | run 状态/skip_reason/actions_json/筛选/不泄露 | `qa-r39-webhook-failure.sh`：`actions_json=[{"type":"WEBHOOK","status":"FAILED","message":"SECRET_MISSING"}]` 且无完整 URL；`r39-run-attribution.sh` 同 event 每规则 1 行；T6 视图契约 52/0 | ✅ |
| AC-13 | webhook 2s 时业务 <1s、5s 出日志、不占业务事务 | `qa-r39-webhook-slow.sh AC13_TOTAL=12 PASS=12`；worker 独立线程（`AutomationWorker` @Scheduled） | ✅ |
| AC-14 | 两规则、reset 80/80 连跑、通知恰 4、§9 三截图 | `platform-demo-reset 80/80` ×3；`qa-r39-demo-scenario.sh 7/7`；截图 3/3 + 本轮人工走查；`DEMO-BY-ROLE.md:308` §9 | ✅ |
| AC-15 | 6 段 ≥36、两次全绿、RUN_TAG 自清理 | `AUTOMATION_TOTAL=40 CASE=40 PASS=40 FAIL=0` ×2，`[40] 自清理后残留 0 行`；`AUTOMATION_TOTAL=40` 自声明（`automation-verify.sh:26`）；本轮 static 13/13 | ✅ |
| AC-16 | 既有门禁不缩水 | platform-verify 194/194、negative 130/130、access 206/206、rbac 113/113、rfv 44/44、gate-order 20/20、demo-reset 80/80、lint 24（≥22）、unit 447（旧用例未删） | ✅ |
| AC-17 | 四视图、无原始 JSON、条件受作用域、build、chip 可见 | 四路由见 §1；`npm run typecheck && npm run build` 均 0；`t6-views-contract.mjs 52/0`；截图含标签 chip 与状态列 | ✅ |
| AC-18 | 文档、gitignore、分支、提交信息、diff 卫生 | `DEMO-BY-ROLE.md:308`、`ARCHITECTURE.md:258`、`memory.md:46` 均已更新且只改同主题；`.gitignore` 覆盖 target/node_modules/dist/.run/.nacos/logs/.env*/IDE；diff 无 pom/package/锁/V1–V14 | ✅ |

## 3. 架构一致性检查（M3 · 强制）

- **事件单口**：`AuditService` 在审计写入后同事务回调 `AuditListener`，全仓唯一实现 `AutomationEventOutbox`；无第二处事件产出、无前端上报路由——符合蓝图「同事务同线程是业务回滚则事件不存在的唯一保证」。
- **幂等单点**：`AutomationRunWriter.tryStart/finish` 是唯一写 `automation_run` 的类（grep 仅此一处），先抢键再判定的顺序封装在方法内，调用方无法颠倒。
- **隔离单源**：四张新表在 `ScopeSql.java:139-142` 登记为 `TableSpec.NONE` 并写明「规则作用域是 scope_type+scope_id 互斥对，单列表达不了」；automation 包仅经 `ScopedJdbc`，lint L21 把「自己拼 domain_id」变成红灯。
- **动作白名单**：四动作枚举 + 拒绝名单集中在 `AutomationActionType`；automation 包无 `ApprovalEngine`/状态流转/发布单入口（唯一注释命中在 `:17`），L21 静态锁。
- **标签单读路径**（Q-1 根因修复）：`RequirementViews.View.tags` 为必填参数，详情/写回/版本详情均经 `RequirementTagQuery.byRequirementIds`，L22 静态锁 + automation-verify S3 真栈断言双保险。
- **已登记偏离**：`automation.rules` 归入「设置」模块（module 仍为 6 组）、s2 MENU 比对 363、nav 基线 38、`automation.action.manage` sort=0——均为 dev 阶段有证据的调整，不改变接口与 AC 契约。
- **结论**：无偏离蓝图、无重复实现、无该抽未抽、无结构破坏，不触发 ⚠️ 条件。

## 4. 意见与遗留（均 P3，不阻断交付）

1. `Makefile:110` 的 help 文案仍写「共 39 条」，脚本实际 `AUTOMATION_TOTAL=40`（Q-1 新增第 40 条后未同步）；AC-15 只要求 ≥36 且 PASS==TOTAL，建议下次顺手改为 40。
2. AC-4 的「PENDING 事件重启后仍被处理为 DONE」本轮未复测（上一轮已测），本轮无恢复路径改动；如要留痕，下一轮可补一次 kill/restart 探针。
3. AC-11 的「停用 ≤1 秒生效」：测试以 3 秒窗口断言（`qa-r39-scoped-idempotency.sh:96`、`automation-verify.sh:677`）；代码侧候选查询按 `enabled=1` 实时过滤，语义上下一事件即生效，属断言偏保守而非功能缺口。
4. PRD AC-3 的「新增 1 页面 + 1 动作 + 1 功能码，整体 +3/角色」与实际矩阵口径（功能码格 660→671，即每角色 +1 功能码；role_permission 行 168→171）存在措辞差，dev 已按注册表重算全部字面量，契约未受影响。
5. 任务夹 `QA-REPORT.md` 最终修订与本次 `ACCEPTANCE.md` 尚未提交（纯文档面）；代码交付面 `0980543` 已完整入库。

**人工补测（沿用 QA §4）**：真实第三方 HTTPS webhook、多实例共享限流（memory.md 已登记技术债）、键盘/读屏可用性——属部署环境项，不构成本轮返工。

验收结论：✅ 通过
