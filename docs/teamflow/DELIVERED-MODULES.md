# 已交付功能模块清单

> 生成时间：2026-11-09，**最后更新 2026-11-23**（r41 轮内台账订正）· 口径：**已验收并合入 `main`** 的能力（r39 起含已在分支内交付、尚未合 `main` 的轮次）；任务夹路径见各轮目录 `docs/teamflow/20261*-r*`。
> 分支 `main` 当前 = `d99530d`；每轮证据见对应任务夹的 `ACCEPTANCE.md`，门禁基线以 [memory.md](memory.md) 的「门禁基线以实测为准」条为唯一口径（行号会随轮次漂移，只按条目标题引用）。

## 0. 版本台账

| 轮次 | 里程碑 | 范围要点 | 验收 | 合入 |
|---|---|---|---|---|
| r1 | M0 环境骨架 | 网关/示例服务/一键启停/体检 | 8/8 AC · `verify` 14/14 | `0741e6a` |
| r2 | M1–M5 需求平台 | 组织权限·需求池·审批·版本发布·缺陷任务·用例执行·指标报表·开放 API | 20/20 AC · `platform-verify` 167/167 | `5f1bb66` |
| r3+r4 | M6 质量闭环 | 计划生命周期·轮次·报告三签·质量门与豁免·生产回流·Go/No-Go | 10/10 AC · `quality-verify` 347/347 | `5640227` |
| r5 | M7 集成对接 | Jenkins/GitLab/Sonar/Nexus 适配层 + 准入扩到 9 项 | 22/22 AC · 9 项准入落地 | `9072719` |
| r6 | M8-A CCode/CTeam/CTest P0 | 甘特三视图·Wiki·价值流·分支策略登记·CR 行级评论·用例评审·测试数据 | 20/20 AC · `collab-verify` 263/263 | `ea877fa` |
| r7+r8 | 角色×页面准入修复 | 字典只读端点·403 语义收敛·导航按权限隐藏·业务方账号·演示数据重置 | 17 AC · `access` 117→152 | `2c9df53` |
| r9→r13 | 分析域（工作台/看板/报表） | 后端 analytics/portal/reports API + 四页前端 + FilterBar/交叉表/图表 | `platform-verify` 174/174 · 四页可渲染 | `f815830` → `234f9f9` |
| r14 | 表头中文化 | 维度/度量枚举统一中文、导出与页面同源、`analytics-i18n-verify` 33/33 | `analytics-i18n-verify` 33/33 | `92e071b` |
| r15+r16 | 演示数据治理 | 重置覆盖 `report_definition`/`portal_card_config`、预置 2 张演示报表 | `demo-reset` 49→52 | `75e8864` / `d806027` |
| r17 | 业务方提交权限 | 动作级判定 `RequirementActionMatrix`、403 带 `action`/`allowedActions` | `access` 148/148 · neg 30/30 | `a02332d` |
| r18→r20 | 三层 RBAC | 角色可配（功能码/页面/动作 × 168 格）、授权页三级树、`rbac-verify` s0–s9 | 14/14 AC · `rbac` 111/111 | `6f93922` |
| r21+r22 | 判定入口收口 | 删除 `TenantContext.can/access`、动作判定改走 `PermissionService`/契约层解析器 | 15 判定点 · `rbac` 111 · `access` 152 | `211db80` / `535939a` |
| r23 | 流程前置门 | 拆解父状态门（`PARENT_NOT_POOLED`/`PARENT_NOT_SCHEDULED`）、必填域 400 | neg 49/49 · verify 168/168 | `3faf190` |
| r24+r25 | 厂商项目经理 | `VENDOR_PM` 第 11 角色 + `vpm1` 账号 + 厂商演示数据 + 写按钮禁用收敛 | `access` 190/190 · `rbac` 113/113 | `20120f1` / `d5cd301` |
| r26 | 工时评估门 | 第五类单据 `WBS_ESTIMATE`、`schedule`/`start_dev` 前置门、±20% 派生 `STALE` | `verify` 192 · neg 111 · `access` 206 | `7144d30` |
| r27 | 列表/详情同源 | 池查询不再直出状态机、`allowedActions` 单一条判定链、4d 段 19 条一致性断言 | `verify` 193 · neg 130 | `7144d30` |
| r28 | 门禁自带夹具 | 负向 7/8 段自造待审批任务，恒定 130；夹具失败 `fail` 不 `warn` | 干净重置 130/130 | `0127ca4` |
| r29 | 存量工时回填 | `backfill-wbs-estimate.sh`（dry-run/`--apply`、走真实引擎、幂等）+ `wbs-backfill-verify` 18 条 | 回填 2 条 · 二次 0 变更 | `79e1f1c` |
| r30 | 发版分级审批 | RELEASE 三级 / REQ_DEPLOY 两级 / 紧急两级 / 回滚两级、`flowChain` 四段同源、提交快照不可变、`release-flow-verify` | 13 阶段 · `rfv` 32 条 | `177389a` |
| r31 | 门禁区间判据 | `release-flow-verify` 提交卫生改用 `merge-base..HEAD` 区间 + 变异自检 4/4（不再依赖脏树） | 32/32 · 自检 4/4 | `177389a` |
| r32 | 演示链到 PUBLISHED | `DemoApprovalFixture`（真实动作链补审批/MR/实际人天）、`reset` 70→74、`rfv` 32→40 | 四段全绿 + 已发布 `REL-1444` | `b48d179` |
| r33 | 质量门跳过口径 | `GateRuleBuckets` 四态分桶单一实现（通过/跳过/未通过/关闭），三消费点同源 | 卡片「跳过 1 · 未通过 0」 | `4d9f5b6` |
| r34 | 门禁顺序与残留 | `scripts/lib/gate-fixture.sh`（GATE_TAG + teardown 断言）、`gate-order-verify` 15 条、`reset` 75 | A/B 双序列全绿 | `d715944` |
| r35 | S7 自造夹具 | 演示链不再依赖演示种子（自造 BUS→PDR→RD），`rfv` 44 条、静态 `[12]` 禁种子引用 | 44/44 · `S7-` 痕迹 0 | `fdaeac8` |
| r36 | 临时主体幂等 | `tests/rbac/_lib.sh` 造名/清理原语 + trap，s3/s5/s6/s7/s8 接入，`gate-order` 18 条 | `rbac` 113×2 | `81967c4` |
| r37 | 遗留探针清理 | `lib/temp-accounts.sh`（4 前缀白名单 + 14 名保护 + 只删 DISABLED）、`legacy-probe-purge.sh`、`gate-order` 19、`reset` 76 | purge 首跑 4 · 二跑 0 | `8c39a3a` |
| r38 | 孤儿 join 行判定 | 谓词单点 `temp_orphan_join_where()`、`orphan-join-purge.sh`、`gate-order` 20（含负向自检）、清 413 条历史域孤儿 | 20/20 · 孤儿 role/domain 0 | `d99530d` |
| r39 | 自动化规则引擎 | `automation_rule`/`automation_event`/`automation_run`/`requirement_tag` 四表（V15）、outbox 事件、幂等抢占、`automation-verify` 6 段 40 条 | 6/6 AC · `automation` 40/40 | 分支内（未合 main） |
| r40 | 需求变更单 | `req_change`/`req_change_item`（V16）、第 6 类 `REQ_CHANGE` 两级审批、显式 apply、`CHANGE_REQUIRED` 收口、第 10 个 Tab + 720 四步抽屉 + 深链、`change-verify` 6 段 42 条 | 20/21 AC（另 1 条文档 P1）· `change-verify` 42/42 | 分支内（未合 main） |

> r6 任务夹：[docs/teamflow/20261109-r6-ccode-cteam-ctest-p0/](20261109-r6-ccode-cteam-ctest-p0/)（PRD 20 条 AC · DESIGN · TECHNICAL · QA-REPORT · ACCEPTANCE）。

**规模（2026-11-23 实测，排除 `src/test`）**：Java **495** 个类（另测试 69）、Vue **140** 个页面/组件、TS **51** 个、REST 映射 **371** 处、Flyway 迁移 **16** 个（V1–V16）、顶层运维/自测脚本 **31** 个（`scripts/tests/` 下另有 45 个）。门禁（r40 实测，条数口径以 [memory.md](memory.md)「门禁基线以实测为准」条为准；本行为快照，只做同源引用、不另立数字）：`platform-verify` **194**、`platform-test-negative` **130**、`access-verify` **206**、`rbac-verify` **113**、`platform-demo-reset` **80**、`release-flow-verify` **44**、`gate-order-verify` **21**（r41 起：20 条 + 第 21 条判据不许退回硬编码的静态自证）、`wbs-backfill-verify` **18**、`change-verify` **42**、`automation-verify` **40**、`unit` **475**、`lint` 24、`verify` 14。

## 1. 平台基座（r1）

- **注册与配置**：Nacos 3.3 standalone；`demo-service` 通过 `spring.config.import=nacos:` 拉配置，`@RefreshScope` 热更新。
- **统一入口**：`api-gateway` 8080，Java `RouteLocator` 声明路由，`/api/**` StripPrefix + `lb://`，JWT 网关校验。
- **运维脚本**：`scripts/{nacos,dev-up,dev-down,verify,build,env-precheck}.sh` + `make dev-up / verify / test-negative / dev-down`。
- **体检**：`make verify` 14 项逐条打表、`make test-negative` 真负向（4 类故障逐一指名）。
- 页面/端口：网关 8080 · req 8082 · deliver 8083 · 前端 5173 · Nacos 8848/8849/9848/9849。

## 2. 组织、租户与权限（r2）

- **多租户**：`tenant` 三形态（ROW 同库行级默认 / SCHEMA / DATABASE）+ 开通即预设 10 角色、7 域；缺/错租户头 403 不落库。
- **组织树**：集团 → 二级管理企业 → 部门 → 人员；用户属厂商 + 被授权若干域。
- **权限（r18→r20 改为三层可配）**：功能码 / 页面 / 动作三层授权，落 `sys_role_permission`（11 角色 × 168 格 = **1848 行**，`PermissionMatrix` 退为种子），管理员可在 `/admin/roles` 与 `/admin/roles/:id/permissions` 逐格改（含「未绑定页面的功能码」分组）；判定三序「页面码非 DENY → 动作码 ALLOW/RESTRICTED → 既有 DataScope」，**唯一入口 `PermissionService`**（r21 删除了 `TenantContext.can/access`，r22 把动作判定收进契约层 `ActionPermissionResolver`），后端拒绝而非隐藏按钮；多角色并集；写端点 `@RequiresAction` 漏声明启动即失败。
- **隔离**：域 × 厂商双维度，`ScopeSql`/`ScopedJdbc` 单点生成谓词，覆盖列表/搜索/导出/附件/看板 8 个入口；导出带水印、行数与页面同源。
- **审计**：`audit_log` 全量记录状态流转、权限拒绝、加签/减签、门禁放行、集成调用。
- 接口：`/sys/admin/*`（用户/组织/域/厂商/权限/租户/审计，7 个控制器）；页面：`admin/*` 10 页。

## 3. 需求全流程（r2）

- **三类需求**：业务 BUS / 产品 PDR / 研发 RD，编号全局自增 + 类型前缀；三层父子拆解（一父多子可跨 Release，父需求只读汇总）。
- **状态机**：按类型差异化（`StateTables` 单点定义，非法流转 409 + `allowedTargets`，直写 status 400）；每次流转写审计。
- **需求池**：类型 Tab + 域/厂商/状态/版本/负责人/优先级筛选，聚合/平铺双视图，keyset 分页（10 万行同源验证）。
- **提报与评审入池**：二级企业业务人员可提报并跟踪自己提报、只读浏览全集团已入池业务需求。
- **版本计划**：`release` + 里程碑，可挂三类需求并按父需求聚合；延期标红。
- **人天口径与评估门（r26）**：0.5 天精度，子需求汇总，父需求只读；实际人天可由工时（÷8）回写。工时评估是**第五类审批单据** `WBS_ESTIMATE`（PDR/RD 各绑一张，或签 = 域负责人 / 产品经理）：未通过时 `schedule`（PDR）与 `start_dev`（RD）一律 **409 `ESTIMATE_NOT_APPROVED`**（detail `{requirementId,status,required}`）；评估通过后人天被改动 **> ±20%**（严格大于）自动派生 `STALE`、门重新生效；六态 `NONE/PENDING/APPROVED/REJECTED/SUPERSEDED/STALE` 中 `STALE` 是**读时派生**，两条写路径（`PUT` / `deliver`）都不必各自加判。前端「渲染看权限、禁用看门」：按钮在、置灰、文案由 `web/src/views/req/estimate.ts` 的 `estimateGateTip` 三处共用。
- 接口：`/requirements`、`/releases`；页面：需求池/创建/详情 + 8 个 Tab。

## 4. 自定义审批引擎（r2，自研轻量）

- **流程定义**：五类单据（提报 / 评审入池 / 需求版本 / 发布 / **工时评估**，r26 新增）+ 需求类型 × 域绑定、默认回退；发布后固化版本，在途实例按启动版本走；`wbs_estimate` 零 schema 变更（复用 `wf_instance/wf_task/wf_history`，单据人天与说明存 SUBMIT 快照）。
- **节点**：审批（会签全员 / 或签一人）、条件分支（类型/域/优先级/人天）、抄送（不阻塞）。
- **审批人来源**：指定人 / 角色 / 发起人直属主管 / 域负责人 / 厂商负责人；无审批人不允许发布（400 带 `nodeKey`）。
- **运行期操作**：前加签 / 后加签（可多人）、减签（必填原因 + 通知）、转办、撤回、催办；已办任务加签 409。
- **审批中心**：我的待办 / 已办 / 我发起 + 超时提醒；单据状态联动（提交→PENDING_REVIEW、评审通过→POOLED、发布级联）。
- 接口：`/approvals`、`/admin/workflows`；页面：审批中心、流程管理。

## 5. 版本发布与准入（r2 + M6 加强）

- **发布单**：挂 Release、需求关联、准入 **9 项**（M7 落地中；当前交付为 M6 的 6 项：需求全测过、无未关闭 P0/P1、MR 已填、人天已填、审批已过、**质量门通过**）。
- **准入服务端计算**：FAIL 返回 409 `ADMISSION_FAILED` 不可绕过；逐项快照固化 `admission_result_json`，可回放/diff，不写库伪造 PASS。
- **强制放行退役**：改为规则级豁免 + PMO 审批，保留原因/期限/审计/通知。
- **发布执行登记**：审核后登记执行、发布结果与上线记录。
- 接口：`/deploy-orders`；页面：发布单列表/详情。

## 6. 研发交付（r2）

- **缺陷**：BUG 编号、P0–P3、状态机 + 重开计数、来源（执行失败/手工/生产回流）、看板 4 指标（缺陷密度/重开率/MTTR 等）同源。
- **任务与迭代**：任务挂需求、完成率回写父需求；迭代挂 Release，看板拖动持久化，未迁移任务禁止关迭代（409）。
- **追溯矩阵**：需求 → 任务 → MR → 测试执行 → 缺陷 → 发布单 六环互跳，缺口实时计算（不假绿）、可筛选。
- **工时**：0.5h 粒度填报、周填报、月度锁定；实际人天 = 工时 ÷ 8，与手填二选一。
- 接口：`/defects`、`/tasks`、`/sprints`、`/trace`；页面：缺陷列表/详情/看板、迭代、迭代看板、追溯矩阵。

## 7. 测试管理（r2 用例库与执行 + M6 补全）

- **用例库**：模块树 ≤ 3 层、TC 编号、**用例版本化**（执行记录钉 `case_version`），四种执行结果，失败一键转缺陷（带用例/版本/需求/执行记录）。
- **测试策略**：版本级策略文档、版本化（M6）。
- **测试计划**：草稿→待评审→已批准→执行中→已完成→关闭/归档七态；评审接审批流（第五类单据）、变更留痕 `plan_change_log`；结构化准出条件（未满足 409 带 `unmet[{key,actual,expected,fixHint}]`）。
- **测试轮次**：R1 新功能 / R2 回归 / R3 缺陷验证，历史不可覆盖，关轮未执行 409。
- **执行分配与环境台账**：执行人 × 模块 × 用例数（差额 400）、环境字典（DEV/TEST/STAGING/PROD）。
- **自动化上报**：开放 API `POST /v1/test-executions`（AppKey + HMAC + 时间戳 + IP + scope），run_id+case_id 幂等，未知用例/版本 422 不落库；不自建执行器。
- 接口：`/test-cases`、`/test-modules`、`/test-plans`、`/v1/test-executions`；页面：用例库、计划列表/详情/执行。

## 8. 质量保证闭环（M6，`deliver-service/.../quality/*`）

- **测试报告**：8 段结构自动生成、版本化不可变、XLSX(8 sheet)/PDF 带水印导出、三级会签（测试负责人→产品→PMO），驳回整份作废出 v(N+1)。
- **版本质量门**：规则集（报告 PASS 且 7 天内、CONDITIONAL 需豁免、通过率 ≥95%/严格 100%、未关闭 P0/P1=0），提交前实时计算并固化 `quality_gate_snapshot`，409 列失败项 + 修复指引，直连旁路同拒（fail-closed）。
- **豁免**：规则级豁免（原因/影响/有效期 1–30 天），QG-04（P0/P1）硬红线不可豁免，到期自动失效。
- **生产回流**：`defect.source=PROD`、72h 观察窗逃逸判定（INSERT 一次判定，窗口变更不改史），回写版本质量统计。
- **指标**：FPY（R1 通过数/已执行数）、回归通过率、缺陷逃逸率、发布后 72h 缺陷数、回滚率，统一走 `MetricFormulas`。
- **Go/No-Go 评审**：评审记录 + NO-GO 需 PMO 二次确认（409 `NO_GO_CONFIRM_REQUIRED`）。
- 页面：质量门列表/详情、测试报告列表/详情、测试策略、环境台账、质量指标、Go/No-Go（8 页）。

## 9. 度量、看板与报表（r2 + M6）

- **指标字典**：定义/公式/负责人/版本/生效日；公式单点 `MetricFormulas`（查询层只取计数）。
- **多视角看板**：需求（类型/状态/域/厂商/企业）、周期（提报→入池→交付，含 P90）、人天三列（预估/实际/偏差）、厂商排名（准时交付率 + 承接量）。
- **岗位工作台**：待办中心 + 快捷动作 + 数据卡片 + 我的列表 + 消息，按角色预置模板。
- **自助报表**：4 数据集（需求/交付/审批效率/质量）+ 维度度量白名单，拖拽行/列/值/筛选出交叉表→图表，保存/共享/设为工作台卡片，导出 CSV/XLSX（默认私有，共享需手动）。
- 页面：报表、价值流、工作台、指标管理。

## 10. 通知与平台化（r2）

- **通知**：站内 + SMTP 邮件（审批待办/超时、发布结果、缺陷指派/重开、周报）+ 出站 Webhook（订阅/投递日志/签名）。
- **开放 API**：`api_client`（AppKey/HMAC/重试 5 次/调用日志/限流）；**字段级脱敏** `field_visibility` 四出口统一。
- **系统管理**：用户/组织/域/厂商/租户/权限/指标字典/集成配置（M7）/审计。
- 页面：管理后台 10 页 + 通知中心。

## 11. 数据模型与工程质量

- **迁移**：`V1__platform_schema.sql`（39 表）→ `V2` 列类型/`V3` 角色目录/`V4` 追溯索引/`V5__quality_management.sql`（14 表 + 5 表增列 + 5 指标）/`V6` 状态宽度/`V7` 环境字典/`V8`–`V12` 集成·协同·分析域/`V13__vendor_pm_role.sql`/`V14__release_flow_chain.sql`/`V15__automation_rule.sql`（自动化四表）/`V16__requirement_change.sql`（需求变更单两表）；共 **16** 个，历史迁移校验和不许变。
- **架构单点**（每条只有一份实现，验收时逐项核对）：`StateTables`、`ScopeSql`、`PermissionMatrix/RouteTable`、`MetricFormulas`、`GateEvaluator`、`QualityAggregator`、`AuditService`、`JwtCodec`、`HmacSigner`。
- **测试套件**：`make verify` 14 · `platform-verify` 167 · `platform-test-negative` 25 · `platform-unit` 140 · `quality-verify` 347 · `lint` 16 · `web typecheck/build` 0 error；`platform-perf` 6 项（列表 P95 0.216s、准入 0.019s、上报 20 并发 p95 0.020s）。

## 12. 进行中 / 明确不做

- **进行中（M7，r5）**：Jenkins 构建、GitLab MR 与 webhook、Sonar 质量门禁、Nexus 制品检索与晋级；带来准入 9 项、`integration_config`/`integration_sync_log`/`build_record`/`mr_sync`/`artifact_record` 等表与 3 个管理页；本地桩验收（`make integration-verify`）。
- **本轮（M8，r6，分支未合入）**：任务夹 [20261109-r6-ccode-cteam-ctest-p0/](20261109-r6-ccode-cteam-ctest-p0/)，20 条 AC，新增 11 张表（`wiki_space`/`wiki_page`/`wiki_page_version`/`wiki_comment`/`wiki_page_link`/`stage_definition`/`branch_policy`/`branch_policy_check`/`mr_comment`/`test_case_review`/`test_executor`）与 `mr_sync` 4 列、四主体 `actual_*`、`test_plan.data_note`；权限矩阵 42→56 格（14 个新码）；五个单点 `WikiVisibility`/`GanttWarnings`/`GanttConfirmTokens`/`FlowMetricFormulas`/`ExecutorHealth`。证据：`make collab-verify`（s0–s8，本地 GitLab 桩 127.0.0.1:19091）与 `make collab-perf`（AC-20 五项 P95）。
- **明确不做（本期非目标）**：CPack/CAgent/CWiki/CMeas/CCI/CFlow 其余六平台、CPack 制品平台本体、自建 CI 执行器、移动端、AI 辅助、表单自定义引擎、金额折算、多级会签、SSO、多语言、ES、甘特 FS/SS 依赖与关键路径、仓库托管。
- **遗留 P3（不阻塞）**：`PlanLifecycleService.java:165` 状态字面量收敛、`QualityGateService` 规则证据 upsert 需补文档、q4 脚本过时注释、横幅样式与键盘可达性人工补测。

## 13. 权限体系与判定同源（r18–r22、r27）

- **三层授权**：FUNCTION（功能码）/ MENU（页面）/ ACTION（动作码）三层写在同一张 `sys_role_permission`，按角色 × 租户；动作码 **72 个**、页面 **36 个**，种子 11 角色 × 168 格。
- **唯一判定**：`PermissionService`（45s 快照 + epoch 失效）三序判定；`RequirementService.allowedActions/ gateFiltered` 是动作集合的唯一来源，池/搜索/导出/详情**共用**（r27：`RequirementPoolQuery` 只许外调 `allowedActionsByRow`，静态检查 + 4d 段 `jq -c` 逐字符断言守护）。
- **防锁死**：`PLATFORM_ADMIN` 的 `org:manage`/`tenant:manage` 不可冻结，且必须保留 ≥1 名管理员；高危动作仅 3 个（强发、授权授予、停用用户），二次确认 + `highRiskGranted` 审计。
- **客户端**：`web/src/stores/auth.ts` 维护三层集合 + `canMenu`/`canAction`；写按钮统一「禁用 + 悬停原因」，探测调用走 `silentForbidden` 不跳全局 403（r25：`a6-write-button-gate` 24 条）。

## 14. 流程管控强化（r23、r26、r27）

| 管控点 | 机制 | 负向证据 |
|---|---|---|
| 拆解前置 | 拆 PDR 要求父 BUS `POOLED`；拆 RD / 直建 RD / PUT 改父 要求父 PDR `SCHEDULED/IN_DEV` | 409 `PARENT_NOT_POOLED` / `PARENT_NOT_SCHEDULED`，detail 带 `allowed[]`；被拒不落库 |
| 提报必填 | 缺 `domainIds` → 400 `VALIDATION_FAILED` | detail.field=domainIds（原为 500） |
| 工时评估 | 第五类单据 + `schedule`/`start_dev` 门 + ±20% `STALE` | 六态拦截、驳回原因留痕、改人天失效 |
| 动作集合同源 | 列表/搜索/详情 `allowedActions` 同一条判定链 | 4d 段 19 条 `jq -c` 原始数组逐字符相等 + 变异必红 |

## 15. 门禁与基线纪律

- 门禁脚本：`platform-verify` / `platform-test-negative` / `access-verify` / `rbac-verify` / `collab-verify` / `quality-verify` / `platform-demo-reset` / `analytics-i18n-verify`，全部 `make` 目标化。
- **下限只增不减**，实测值写入 `docs/teamflow/memory.md:36`；基线漂移时在任务夹顶部加「基线订正」行，**不改 AC 文本**。
- 负向脚本禁止条件跳过：r28 起需要前置数据的段必须自带夹具，夹具造不出来 **fail 不 warn**（避免门禁静默缩水）。

## 16. 门禁自身的可信度（r28、r31、r33–r38）

这一节记的不是产品功能，是「门禁说绿算不算绿」的几条硬约定——每一次都是被假绿咬出来的。

- **不许依赖脏工作树**（r31）：`release-flow-verify` 的提交卫生判据从 `git status --porcelain` 改成 `merge-base(main,HEAD)..HEAD` 区间，并内置变异自检（改旧迁移必红、新增未登记迁移必红）。门禁必须对「已提交」与「未提交」两种状态给同一个结论。
- **不许条件跳过**（r28、r36）：需要前置数据的负向段必须段内自造夹具，造不出来就 `fail`，禁止 `warn` 放过，也禁止 `>/dev/null` 吞掉建号 409 再靠登录 403 兜底。
- **不许顺序敏感**（r34）：写演示租户的门禁统一 `source scripts/lib/gate-fixture.sh`，用 `GATE_TAG` 打标、`trap` 自愈、teardown 断言残留 0；`gate-order-verify` 用 A/B 两种顺序各跑一遍证明互不污染。
- **不许依赖演示种子**（r35）：门禁自己造 BUS→PDR→RD 全链，静态检查 `[12]` 禁 `DEMO-R-|DEMO-C-|DEMO-REL-` 出现在 S7 区段。
- **不许有隐形残留**（r37、r38）：临时账号清理收敛到 `lib/temp-accounts.sh` 单一真源（4 前缀白名单 + 14 名保护 + 只删 DISABLED）；孤儿 join 行判定用 `temp_orphan_join_where()` 单一谓词覆盖 `sys_user_role` 与 `sys_user_domain`，「同租户无主」即红。
- **每条零断言都要有负向自检**：插入一条应当被数到的脏数据，证明计数 0→1→0，避免「断言退化成永不命中」的空跑（`gate-order` 第 19、20 条即此形态）。
- **口径只有一个数**：`Makefile` 帮助行、`memory.md`、任务夹文档里的门禁条数必须同批更新；已发生过三次漂移（`negative` 128/130、权限码 56、`gate-order` 15/19），现在由 PRD 的 AC-9/AC-10 强制核对。

## 17. 需求变更单（r40，2026-11-23）

**定位**：变更单是**留痕单据**，不是编辑表单——冻结 `before/after` 与影响快照、同事务起第 6 类审批、显式 `apply` 才写字段、应用后逐字段留证据。任务夹 [20261123-r40-req-change-order/](20261123-r40-req-change-order/)，流程见 [CHANGE-REQUEST-FLOW.md](CHANGE-REQUEST-FLOW.md)。

| 面 | 交付物 |
|---|---|
| 表 | `req_change`（状态/原因/两份 JSON 快照/impact/审批实例/apply 元数据，**无 FK**）、`req_change_item`（复合主键 `(change_id, field)`，主键即幂等约束）；`V16__requirement_change.sql`，V1–V15 不动、**items 只在 apply 落库** |
| 端点 | `POST /requirements/{id}/changes`（201）、`POST .../changes/preview`（200，不落库）、`GET .../changes?status=&page=&size=`、`GET /changes/{id}`、`POST /changes/{id}/apply`（200，`APPLIED`） |
| 领域单点 | `RequirementChangeFields`（白名单/7 键快照/归一/差异/早期态表 `editableFields`）、`RequirementChangeImpactAssessor`（8 键 impact）、`RequirementChangeValidator`（创建即拦 + apply 复验）、`RequirementChangeGuard`（PUT 收口） |
| 事务 | `RequirementChangeService`（创建：校验+快照+impact+同事务起审批+审计）、`RequirementChangeApplyService`（12 步单事务：状态闸→需求行锁→before 比对→复验→条件更新→RELEASE 双写→写字段→items→1 条审计→锁审计） |
| 审批 | `WorkflowVersion.BIZ_REQ_CHANGE` 第 6 类，默认 `PRODUCT → DOMAIN_OWNER` 两级；`RequirementChangeCallback` 只做 `PENDING→APPROVED/REJECTED/WITHDRAWN`，**绝不写需求字段**；`RequirementChangeSummaryProvider` 供审批中心「需求变更」摘要与深链 |
| 权限 | 动作码 `req.action.change_request` / `req.action.change_apply`（73→**75**）、`PermissionRouteTable` 5 条路由规则、`VENDOR_PM` 显式 DENY 两条；**不进** 16 条状态迁移矩阵，零新菜单码 |
| 前端 | 第 10 个 Tab「变更」+ 720 四步抽屉（第 ④ 步必须真 preview）+ 560 编辑抽屉（按 `editableFields` 锁定）+ 深链 `/requirements/:id/changes/:changeId` + `409 CHANGE_REQUIRED` banner；`APPROVED` 文案「已通过 · 待应用」与 `APPLIED` 彻底分开 |
| 事件 | apply 只写 1 条 `REQUIREMENT_CHANGE_APPLIED` 审计，`AutomationEventMapper` 派生每字段 1 条 `FIELD_CHANGE`；规则字段词表扩到 8 个（7 可变更 + `owner_user_id`），title-only 规则可保存 |
| 门禁 | `make change-verify` = `scripts/change-verify.sh`（S1 迁移与结构 5 / S2 申请与影响 8 / S3 审批映射 6 / S4 应用正负 8 / S5 人天与锁 7 / S6 收口与审计查询 6 = **40 条**；`--static-only` 12 条免栈、`--twice` 连跑两次比对）；`make test` 在 `platform-verify` 之后接一步 |
| 计数迁移 | 动作 73→**75**、每角色 171→**173**、`sys_role_permission` 1881→**1903**、ACTION 格 803→**825**（FUNCTION 671 / MENU 407 不变）；`s0/s1/s2/s5/s7/s8/_lib/rbac-verify/platform-verify/platform-demo-reset` 同批更新，Flyway 断言 15→**16** |
| 本轮不做 | 变更回滚（再开一张单，`before` 冻结成当前值形成审计链）、跨类型合并单、批量应用、变更单评论/附件 |
