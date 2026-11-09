# 已交付功能模块清单

> 生成时间：2026-11-09 · 口径：**已验收并合入 `main`** 的能力；进行中的 M7 单列在 §12，不计入已交付。任务夹路径见各轮目录 `docs/teamflow/20261*-r*`。
> 分支 `main` 当前 = `5640227`（M6 合入后）；证据见各任务夹的 `ACCEPTANCE.md`。

## 0. 版本台账

| 轮次 | 里程碑 | 范围要点 | 验收 | 合入 |
|---|---|---|---|---|
| r1 | M0 环境骨架 | 网关/示例服务/一键启停/体检 | 8/8 AC · `verify` 14/14 | `0741e6a` |
| r2 | M1–M5 需求平台 | 组织权限·需求池·审批·版本发布·缺陷任务·用例执行·指标报表·开放 API | 20/20 AC · `platform-verify` 167/167 | `5f1bb66` |
| r3+r4 | M6 质量闭环 | 计划生命周期·轮次·报告三签·质量门与豁免·生产回流·Go/No-Go | 10/10 AC · `quality-verify` 347/347 | `5640227` |
| r5 | M7 集成对接 | Jenkins/GitLab/Sonar/Nexus 适配层·准入扩到 9 项 | 进行中 | 未合入 |

**规模**：已交付 REST 映射 **196** 个（req-service 81 / deliver-service 115），前端 **54** 个页面/组件，数据库 **53** 张业务表（V1 39 + V5 14，另有 5 次增列/字典迁移）。

## 1. 平台基座（r1）

- **注册与配置**：Nacos 3.3 standalone；`demo-service` 通过 `spring.config.import=nacos:` 拉配置，`@RefreshScope` 热更新。
- **统一入口**：`api-gateway` 8080，Java `RouteLocator` 声明路由，`/api/**` StripPrefix + `lb://`，JWT 网关校验。
- **运维脚本**：`scripts/{nacos,dev-up,dev-down,verify,build,env-precheck}.sh` + `make dev-up / verify / test-negative / dev-down`。
- **体检**：`make verify` 14 项逐条打表、`make test-negative` 真负向（4 类故障逐一指名）。
- 页面/端口：网关 8080 · req 8082 · deliver 8083 · 前端 5173 · Nacos 8848/8849/9848/9849。

## 2. 组织、租户与权限（r2）

- **多租户**：`tenant` 三形态（ROW 同库行级默认 / SCHEMA / DATABASE）+ 开通即预设 10 角色、7 域；缺/错租户头 403 不落库。
- **组织树**：集团 → 二级管理企业 → 部门 → 人员；用户属厂商 + 被授权若干域。
- **权限**：10 角色 × 权限矩阵（`PermissionMatrix` 26→41 码、410 格），后端 `PermissionInterceptor` 拒绝而非隐藏按钮；多角色并集。
- **隔离**：域 × 厂商双维度，`ScopeSql`/`ScopedJdbc` 单点生成谓词，覆盖列表/搜索/导出/附件/看板 8 个入口；导出带水印、行数与页面同源。
- **审计**：`audit_log` 全量记录状态流转、权限拒绝、加签/减签、门禁放行、集成调用。
- 接口：`/sys/admin/*`（用户/组织/域/厂商/权限/租户/审计，7 个控制器）；页面：`admin/*` 10 页。

## 3. 需求全流程（r2）

- **三类需求**：业务 BUS / 产品 PDR / 研发 RD，编号全局自增 + 类型前缀；三层父子拆解（一父多子可跨 Release，父需求只读汇总）。
- **状态机**：按类型差异化（`StateTables` 单点定义，非法流转 409 + `allowedTargets`，直写 status 400）；每次流转写审计。
- **需求池**：类型 Tab + 域/厂商/状态/版本/负责人/优先级筛选，聚合/平铺双视图，keyset 分页（10 万行同源验证）。
- **提报与评审入池**：二级企业业务人员可提报并跟踪自己提报、只读浏览全集团已入池业务需求。
- **版本计划**：`release` + 里程碑，可挂三类需求并按父需求聚合；延期标红。
- **人天口径**：0.5 天精度，子需求汇总，父需求只读；实际人天可由工时（÷8）回写。
- 接口：`/requirements`、`/releases`；页面：需求池/创建/详情 + 8 个 Tab。

## 4. 自定义审批引擎（r2，自研轻量）

- **流程定义**：四类单据（提报 / 评审入池 / 需求版本 / 发布）+ 需求类型 × 域绑定、默认回退；发布后固化版本，在途实例按启动版本走。
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

- **迁移**：`V1__platform_schema.sql`（39 表）→ `V2` 列类型/`V3` 角色目录/`V4` 追溯索引/`V5__quality_management.sql`（14 表 + 5 表增列 + 5 指标）/`V6` 状态宽度/`V7` 环境字典。
- **架构单点**（每条只有一份实现，验收时逐项核对）：`StateTables`、`ScopeSql`、`PermissionMatrix/RouteTable`、`MetricFormulas`、`GateEvaluator`、`QualityAggregator`、`AuditService`、`JwtCodec`、`HmacSigner`。
- **测试套件**：`make verify` 14 · `platform-verify` 167 · `platform-test-negative` 25 · `platform-unit` 140 · `quality-verify` 347 · `lint` 16 · `web typecheck/build` 0 error；`platform-perf` 6 项（列表 P95 0.216s、准入 0.019s、上报 20 并发 p95 0.020s）。

## 12. 进行中 / 明确不做

- **进行中（M7，r5）**：Jenkins 构建、GitLab MR 与 webhook、Sonar 质量门禁、Nexus 制品检索与晋级；带来准入 9 项、`integration_config`/`integration_sync_log`/`build_record`/`mr_sync`/`artifact_record` 等表与 3 个管理页；本地桩验收（`make integration-verify`）。
- **明确不做（本期非目标）**：CPack/CCode/CAgent/CTest/CWiki/CTeam/CMeas/CCI/CFlow 其余九平台、CPack 制品平台本体、自建 CI 执行器、移动端、AI 辅助、表单自定义引擎、金额折算、多级会签、SSO、多语言、ES、甘特 FS/SS 依赖。
- **遗留 P3（不阻塞）**：`PlanLifecycleService.java:165` 状态字面量收敛、`QualityGateService` 规则证据 upsert 需补文档、q4 脚本过时注释、横幅样式与键盘可达性人工补测。
