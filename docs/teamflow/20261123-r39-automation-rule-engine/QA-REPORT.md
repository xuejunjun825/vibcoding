# r39 自动化规则引擎 · QA 报告

- 任务夹：`docs/teamflow/20261123-r39-automation-rule-engine/`
- 分支：`feat/automation-rules-engine`（交付代码尚未提交：43 改 + 12 新增）
- 环境：JDK 25 / Spring Boot 4 / Maven 多模块；真栈 `make platform-up`（req 8082 / gateway 8080 / deliver 8083 / web 5173），MySQL 本地实例
- 测试时间：2026-11-23 13:04–13:45（Asia/Shanghai）
- 结论：**不可验收**。产品主路径（事件→规则→动作→日志、幂等、防循环、越权、停用止血）实测成立；但 **4 条既有门禁被本次交付改红**（automation-verify 34/39、gate-order 19/20、platform-verify 192/194、rbac-verify 112/113），AC-15 与 AC-16 不成立，另有 1 条与 PRD 不一致的限流口径。

---

## 1. 范围与方法

| 维度 | 做法 | 结果 |
|---|---|---|
| 编译/单测 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository test` | exit 0；req-service 370 条、deliver-service 73 条，0 失败（含 Automation* 47 条） |
| 前端 | `cd web && npm run typecheck` / `npm run build` | 均 exit 0（✓ built in 8.05s） |
| 真栈门禁 | demo-reset / automation-verify / access / negative / rfv / platform-verify / rbac / gate-order / lint | 见 §2 |
| 独立探针 | `logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/qa-r39-scoped-idempotency.sh`（按 rule_id 收窄的幂等与止血） | 5/5 PASS |
| 静态/架构 | 蓝图 22 类逐点 grep、automation 包边界、接口一致性、卫生检查 | 见 §4 |
| 浏览器 | 沙箱内 Chromium 不可启动（host 限制） | 脚本断言替代；截图进人工清单 |

## 2. 门禁实测（同一台栈，13:07–13:45）

| 门禁 | 命令 | 期望 | 实测 |
|---|---|---|---|
| demo-reset | `bash scripts/platform-demo-reset.sh` | 80/80 | **80/80**（连跑 3 次，含诊断后复位） |
| automation-verify | `bash scripts/automation-verify.sh` | 39/39，连跑两次 | **34/39**（两次复现；失败项 [16][17][19][20][33]） |
| lint | `bash scripts/tests/lint.sh` | ≥22 | **23/23** 通过（L21 边界在列） |
| access-verify | `bash scripts/access-verify.sh` | 206/206 | **206/206** |
| platform-test-negative | `bash scripts/platform-test-negative.sh` | 130/130 | **130/130** |
| release-flow-verify | `bash scripts/release-flow-verify.sh` | 44/44 | **44/44** |
| platform-verify | `bash scripts/platform-verify.sh` | 194/194 | **192/194**（2 失败） |
| rbac-verify | `bash scripts/rbac-verify.sh` | 113/113 | **112/113**（1 失败） |
| gate-order-verify | `bash scripts/gate-order-verify.sh` | 20/20 | **19/20**（1 失败） |

> 栈上时间线：13:06 重建 fat jar 后重启（含 V15 迁移与 T5/T6 最新代码）；13:07 起全部真栈证据均产自该实例。

## 3. 用例与结果（按 AC）

| AC | 判定 | 证据 |
|---|---|---|
| AC-1 V15 四表 / 幂等 | 通过 | `mvn test`（V15 幂等 DDL 用例）；`flyway_schema_history` 最高版本 15；`automation_rule/event/run/requirement_tag` 齐备 |
| AC-2 CRUD 封闭 schema / 409 / 乐观锁 / 审计 | 通过 | automation-verify S2 八条全绿；`AUTOMATION_RULE_CREATE/UPDATE/ENABLE/DISABLE` 四审计动作在 `AutomationRuleService` |
| AC-3 页面/功能/动作 + 厂商 403 | 通过 | access 206/206；automation-verify [25][26][27] 厂商 `403 INTERNAL_ONLY`；`PermissionRouteTable` 已登记 `/automation/**` |
| AC-4 事件唯一源 / 无上报入口 | 通过 | [31] `GET /automation/events` → 404；`AuditListenerContractTest`（事务回滚 0 事件、auditId 透传）；无前端上报调用 |
| AC-5 四类触发 | 部分通过 | 创建/字段触发真栈覆盖（S1、S3）；定时触发仅单测（`AutomationRuleMatcherTest`），08:30 cron 未 E2E |
| AC-6 条件 AND/OR/五比较符/快照 | 通过 | `AutomationConditionEvaluatorTest` + S1（P0 命中、状态未被动） |
| AC-7 Scope 共源 / 越权 / 放宽 400 | 通过 | [28] `CONDITION_WIDENS_SCOPE`、[29] 跨域 SKIPPED、[30] `SCOPE_LOST`；ConditionSpec 走 `StateTables` |
| AC-8 四动作白名单与边界 | 通过 | [S2] `SET_STATUS`→400 `ACTION_NOT_ALLOWED`、11 动作→400、超长名→400；L21 静态四条 |
| AC-9 幂等至多一次 | **产品通过 / 门禁失败** | 独立探针 5/5：同 event_id 两插 → 1 事件行、本规则 1 条 run；ADD_TAG 跨事件 → 标签恰 1 行。automation-verify [16][17][20] 因租户级计数误报 |
| AC-10 origin=RULE / depth≥4 | 通过 | [S4] 两组事件均 DROPPED，`drop_reason=RULE_ORIGIN` / `CHAIN_DEPTH`，0 run |
| AC-11 限流 120/分钟 + 停用 ≤1s | **部分不通过** | 停用止血：探针 5/5（本规则 0 新 run）；限流键为 `(tenant, rule)`，非 AC-11 的「按租户/分钟」（见 P2-1） |
| AC-12 run 状态 / skip_reason / actions_json | 通过 | [38] 日志不含密钥与完整 URL；`actions_json` 逐动作落库；[29][30][35][36] 四类 skip 语义正确 |
| AC-13 webhook 慢对端 2s | 未覆盖 | 异步结构成立（S1 建需求 <1s + worker 执行）；慢对端端到端未做 → 人工清单 |
| AC-14 demo-reset 80/80 + 两规则 + 通知 4 | 部分通过 | 80/80 三次；`automation_rule` 两条（R1/R2）；通知断言 4 条在 80 项内通过；**§9 三张截图缺失**（浏览器不可用，非交付缺陷） |
| AC-15 automation-verify ≥36、两次全绿、残留 0 | **不通过** | 39 条声明、自清理 [39] 通过；但 34/39，两次一致（P1-1） |
| AC-16 既有门禁不缩水 | **不通过** | gate-order 19/20、platform-verify 192/194、rbac 112/113（P1-2/3/4）；其余门禁达标 |
| AC-17 四视图 / 无原始 JSON / build | 通过 | `deliver.routes.ts:217-271` 四路由 + `pageRegistry` 注册；`RuleEditorView` 无 JSON 编辑框（触发=四卡、动作=四型分段）；typecheck+build 通过；句式仅 `AutomationRuleSentence.vue` 一处渲染 |
| AC-18 文档 / 提交卫生 / 无 pom-lock-V1..V14 改动 | **部分不通过** | DEMO-BY-ROLE §9、ARCHITECTURE §11.5、memory r39 行齐全；pom/package/lock diff 为空、V1–V14 零改动、gitignore 覆盖 logs/target/.run；**但 r39 无提交**（P2-2） |

## 4. 架构与接口一致性（强制项）

- 蓝图对照：`AuditListener`/`AuditService`（同事务回调、`recordInNewTx` 亦回调）、`ScopeSql`（四表 `TableSpec.NONE` + 注释写明理由）、`AutomationEventStore`（唯一碰 `automation_event`，`INSERT IGNORE` + `FOR UPDATE SKIP LOCKED` 在 `REQUIRES_NEW`）、`AutomationRunWriter`（唯一写 run，先抢键）、`AutomationDispatcher`（origin=RULE → chainDepth≥4 → 抢键 → Scope → 条件 → 限流 → 动作）、`AutomationOwnerAssigner`（唯一 `assignOwner` 调用点）均按蓝图落地。
- 无重复实现：句式渲染单点（`AutomationRuleSentence.vue`，两个调用点）、错误码单点（`AutomationErrors`）、隔离列单点（`ScopeSql`）、审计旁路单点。L21 四段静态断言（禁改状态/审批/发布单、禁拼 `domain_id`、禁动作码、assignOwner 单点）通过。
- 接口一致性（静态）：`web/src/api/automation.ts` 8 个方法 ↔ `AutomationRuleController`/`AutomationRunController` 端点一一对应（GET/POST/PUT/PATCH `/rules`、`/rules/{id}/enabled`、`/runs`、`/runs/{id}`、`/meta`）；`RuleView`/`RunView`/`MetaView` 字段名与 TS 类型一致（`lastRun`、`actions`、`webhookConfigs` 等）；`AutomationRuleSentence` 的 props 与两处调用点一致；网关 `/api/req/**` 统一由 `PlatformRoutes` 覆盖，无需逐路径登记。
- 偏差两处：限流键（P2-1）、`SKIP_SCHEMA_INVALID` 第 8 个原因（P3-1）。

## 5. 缺陷表

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-1 | P1 | 门禁-automation-verify | `make platform-demo-reset && bash scripts/automation-verify.sh` | 39 条全绿，连跑两次一致 | 34/39，两次复现；[16][17][19][20][33] 红，[33] 报「停用后仍产生 5–6 条 run」 | AC-9/AC-11/AC-15 | `bash scripts/automation-verify.sh` | 输出 `AUTOMATION_TOTAL=39 CASE=39 PASS=39 FAIL=0` 且 exit 0，连跑两次一致 |
| D-2 | P1 | 门禁-gate-order | `bash scripts/gate-order-verify.sh` | 20/20 全绿 | 19/20：`FAIL demo-reset 分母/断言缺失`（脚本断言 `TOTAL_CHECKS=76`，交付已成 80） | AC-16 | `bash scripts/gate-order-verify.sh` | 输出 `gate-order-verify: 20/20`，exit 0 |
| D-3 | P1 | 门禁-platform-verify | `bash scripts/platform-verify.sh` | 194/194 全绿 | 192/194：`权限矩阵暴露 60 个函数码（实际 61）`、`VENDOR_PM 授权格 = 168（实际 171）`（`scripts/platform-verify.sh:1133`） | AC-16 | `bash scripts/platform-verify.sh` | 输出 `platform-verify: 194 项全部通过`（0 失败） |
| D-4 | P1 | 门禁-rbac-verify | `bash scripts/rbac-verify.sh` | 113/113 全绿 | 112/113：`Flyway 最新成功版本 = 14（实际 15）`（`scripts/tests/rbac/s0-schema-migration.sh:136`） | AC-16 | `bash scripts/rbac-verify.sh` | 输出 `PASS=113 / TOTAL=113`，exit 0 |
| D-5 | P2 | 限流口径 | 读 `AutomationDispatcher.java:162` 与 `AutomationRateLimiter.java:61` | 按租户/分钟（AC-11 原文），租户上限 120 | 键为 `(tenantId, ruleId)`，租户实际上限 = 120 × 启用规则数 | AC-11 | `grep -n "rateLimiter.tryAcquire" req-service/src/main/java/com/example/req/automation/engine/AutomationDispatcher.java` | 命令无输出（改为租户级调用），或 AC-11/PRD 文本与实现同步改为「按规则/分钟」并经产品确认 |
| D-6 | P2 | 提交卫生 | `git log --oneline --grep="tf-mwspkg4u-nvy5y5"` | 交付提交存在，信息含 runId 与任务夹 | 无 r39 提交；43 改 + 12 新增仍未提交 | AC-18 | `git log --oneline --all --grep="tf-mwspkg4u-nvy5y5"` | 命中 1 条提交，正文含 `tf-mwspkg4u-nvy5y5` 与 `docs/teamflow/20261123-r39-automation-rule-engine` |
| D-7 | P3 | 执行日志语义 | 构造 schema 非法的规则（直接改库）后投事件 | 只出现 PRD 3.7 的 7 个 skip_reason | 多出第 8 个 `SCHEMA_INVALID`，前端 `SKIP_LABELS` 无该码，显示裸码 | AC-12 | `grep -c "SCHEMA_INVALID" web/src/components/AutomationRunVerdict.vue` | 返回 ≥1（前端补人话标签）或该常量的使用点被移除 |
| D-8 | P3 | 门禁文案 | `grep -n "76/76" scripts/platform-demo-reset.sh` | 文案与 80 一致 | 794/801 行错误提示仍写 76/76 | AC-16 | `grep -n "76/76" scripts/platform-demo-reset.sh` | 无输出 |
| D-9 | P3 | 前端注释 | `grep -n "168" web/src/views/admin/role-permissions/useRoleGrants.ts` | 注释与 171（61+37+73）一致 | 注释仍写 168（60+36+72） | AC-16 | `grep -n "168" web/src/views/admin/role-permissions/useRoleGrants.ts` | 无输出 |

### D-1 根因补充（供修复直接照做）

1. `scripts/automation-verify.sh` 的 [16][17][20][33] 用**租户/需求/事件**维度计数，未按 `rule_id` 收窄：同一事件只要匹配到第二条启用规则（demo-reset 的 R1/R2，或脚本 S2/S3/S5 自己留下的规则）就多计。文件头声明「断言只认自己造的数据」，与实现不一致。
2. [19] 断言的审计动作名是 `ASSIGN_OWNER`，代码写的是 `OWNER_ASSIGN`（`RequirementService.java:677`，与 TECHNICAL.md:214 映射表一致）→ 恒为 0。
3. 对照证据：把两条演示规则临时停用后复跑仍是 5 FAIL（仅 [33] 由 6 降到 5），说明矛盾主要来自脚本自身的多规则共存；按 `rule_id` 收窄的独立探针 **5/5 PASS**，即引擎侧 AC-9/AC-11 成立，**门禁判据错而不是产品错**。
4. 建议：[16][17][19][20][33] 全部加 `AND rule_id=<本段规则>`；[19] 改断言 `action='OWNER_ASSIGN'`；[33] 只数被停用那条规则。

## 6. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 工具 |
|---|---|---|
| AC-14 §9 三张截图（`r39-automation-r1-success/r2-tag/r1-disabled.png`） | 按 DEMO-BY-ROLE §9.2 三步操作，1440×900 截图落 `docs/teamflow/demo-shots/` | Chrome + CDP（沙箱内 Chromium 无法启动：PROCESS_SET_INFORMATION 被拒） |
| AC-13 webhook 慢对端 2s | 本地起 `sleep 2` stub → 建 `integration_config`（`active_key` 非空）→ WEBHOOK 规则 → 建需求：量 HTTP `time_total` <1s、5s 内出日志 | curl + 本地 stub + MySQL |
| 视觉复核（停用不降透明度、句式三处同源、空/错态样式） | 真浏览器目视 + 对比 DESIGN §0.5 七条反模式 | Chrome 1440×900 |
| 定时触发 08:30 | 临时把 cron 改成近时刻跑一次，核对 event_id 含 requirementId、截断单独一行 run | 改 `platform.automation.schedule-cron` 重启 |
| 断点/多实例限流 | 单实例内存计数；多实例上限翻倍（已在 memory 登记技术债），部署形态确认 | 部署评审 |

## 7. 结论

- 产品功能面**基本成立**：事件唯一源、条件求值、Scope 收窄、四类动作、幂等、防循环、停用止血、日志可解释，均以真栈证据通过（含独立收窄探针 5/5）。
- **AC-15 与 AC-16 不成立**：automation-verify 34/39，且 gate-order / platform-verify / rbac 三条既有门禁被本次交付改红 —— 这是「门禁自身的可信度」问题，必须修复后重跑。
- 另有 1 条 PRD 口径不一致（限流键）与 1 条未提交，需产品与 dev 收口。
- **结论：不可验收**；修复 D-1～D-4 后重跑本报告 §2 全部门禁，再判验收。
