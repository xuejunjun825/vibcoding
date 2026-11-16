# 需求全流程 · 管控点核对与补齐规划

> 基线：`main` = `535939a`（r22 合回后）。核查方式：读状态机/权限/审批/门禁代码 + 用真实账号与岗位跑 HTTP 探针；每条结论都带证据。
> 目的：回答两个问题——① 这条全流程**是否真的被管住**（不是"有页面"而是"绕不过去"）；② 哪些缺口需要补、怎么排。

## 1. 结论先说

- **主干是通的、且大多数环节有强制力**：提报 → 审批（可配流程）→ 入池 → 拆解 → 排期 → 任务/工时 → 提测 → 版本+测试计划 → 质量门 → 发布单审批。8 个管控点里 **5 个有硬约束**、**2 个只有"能填"没有"必须"**、**发现 1 个接口级缺陷**。
- **两个真缺口**：① **未审批的需求可以直接拆解**（我实测 201 成功）；② **工时评估没有独立环节，也没有任何前置门**——没有评估人天也能排期、也能开始开发。
- **一个缺陷**：提报时漏填业务域直接 **500**（数据库非空约束裸奔），应该是 400 `VALIDATION_FAILED`。
- **一个岗位缺口**：厂商侧"项目管理"没有独立角色，目前只能借 `PMO`（域负责人）或厂商负责人身份，分权不干净。

## 2. 八个管控点核对表

判定口径：**强制** = 不满足条件时系统直接拒绝（409/403/400），不依赖操作者自觉；**提示** = 只有展示或校验格式，不拦流程。

| # | 管控点 | 平台机制 | 强制力 | 证据 |
|---|---|---|---|---|
| 1 | 业务需求提报 | `POST /requirements`（type=BUS），业务方 `biz1` 可自提（r17 修复） | **强制**（类型/父级/权限），但**缺域漏校验会 500** | r22 后实测：`biz1` 自提 → `SUBMITTED`、`allowedActions=["submit"]`；缺 `domainIds` → **500 `Column 'domain_id' cannot be null`** |
| 2 | **需求工时评估初审** | 仅 `estimateDays` 字段 + 0.5 天精度校验 + 父需求只读汇总 | **提示**（无环节、无门） | `RequirementRules.checkDays` 只校验精度/父子；状态机无 `ESTIMATING/ESTIMATED`；实测**无工时**的 RD `start_dev` → **200**、无工时的 PDR `schedule` → **200** |
| 3 | 需求审批（提报/评审入池） | 自研审批流：`REQ_SUBMIT`（产品评审）→ `REQ_REVIEW`（评审入池），会签/或签/条件分支/加签减签/催办 | **强制** | `StateTables`：`SUBMITTED →submit→ APPROVING →approve→ PENDING_REVIEW →review_pass→ POOLED`；`review_pass` 前必须有 `REQ_REVIEW` 实例 APPROVED（否则 409 `REVIEW_APPROVAL_REQUIRED`） |
| 4 | 拆解产品/研发需求 | `POST /requirements/{id}/decompose`，只允许 `BUS→PDR→RD`；动作码 `req.action.decompose` | **弱**（只有类型约束，**没有状态前置**） | 实测：`BUS-0391` 处于 **SUBMITTED（未审批）**，`pm` 直接 `decompose` → **201 生成 PDR-0149**，并可立即 `schedule` |
| 5 | 任务执行与工时填报 | 任务 `POST /deliver/tasks` + 看板 `move`；工时 `worklog` 0.5h 粒度、实际人天=工时÷8 | **强制**（状态流转），工时填报本身非门 | 状态机：`RD TODO→IN_DEV→PENDING_TEST→TEST_PASSED→MERGED→DONE`；工时回写 `actualDays` 与手填二选一 |
| 6 | 版本计划 | `Release` + 里程碑 + 需求挂版本 + `schedule` 动作（`release:plan`） | **强制**（状态机 `PENDING_ANALYSIS→SCHEDULED`） | 动作档位 `req.action.schedule` 由 `sys_role_permission` 数据驱动（r22），改 DENY 后下一次请求即 403 |
| 7 | 版本测试 | 测试计划挂 Release、用例版本化、执行结果、通过率/覆盖率、失败转缺陷、报告三签、质量门 | **强制** | 发布单创建时校验测试计划状态：非 APPROVED → 409 `PLAN_NOT_APPROVED`；M6 六条质量门规则 + QG-04 不可豁免 |
| 8 | 发版审核 | 版本审批 `RELEASE` + 发布单审批 `REQ_DEPLOY` + 9 项准入 + 质量门/豁免 | **强制**（强发已退役） | `DeployOrderService.FORCE_RELEASE_RETIRED`：强制放行**恒 409**，只能走质量门或规则级豁免；准入 9 项 = 需求全测过 / 无未关闭 P0-P1 / MR 已合 / 人天已填 / 审批已过 / 构建成功 / Sonar 门禁 / 制品校验（未配置→SKIP，已配置不可用→FAIL） |

## 3. 缺口清单（按优先级）

### G1 · 未审批即可拆解（P1，管控缺口）
- 现象：`SUBMITTED`/`APPROVING` 状态的需求可被直接拆出产品需求，等于**绕过审批入池**就能进入交付。
- 根因：`RequirementService.decompose` 只用 `requireWritable()`（仅拦终态），没有校验父需求已入池；`decompose` 不是状态转移，状态机管不到它。
- 建议：拆 `PDR` 要求父 `BUS` 处于 `POOLED`；拆 `RD` 要求父 `PDR` 处于 `SCHEDULED/IN_DEV`（产品或研发负责人已认领）。拒绝用 409 + 稳定错误码 `PARENT_NOT_POOLED / PARENT_NOT_SCHEDULED`。

### G2 · 工时评估初审没有环节、也没有门（P1，管控缺口）
- 现象：研发填个 `estimateDays` 就完了；没填也能排期、也能开始开发。业务方看到的"预估人天"因此可能长期为空或被事后补。
- 根因：状态机三类需求都没有"评估中/已评估"状态，审批流 4 类单据里也没有工时单据（`REQ_SUBMIT / REQ_REVIEW / RELEASE / REQ_DEPLOY`）。
- 建议：新增第五类审批单据 `WBS_ESTIMATE`（可配、默认开启），流程为「研发提评估 → 项目/产品初审 →（可选）PMO 复核」；`schedule` 与 `start_dev` 增加前置门：`estimateDays` 为空或未初审通过 → 409 `ESTIMATE_NOT_APPROVED`；父需求汇总口径不变（仍只读）。

### G3 · 提报缺业务域返回 500（P2，缺陷）
- 现象与证据：`POST /requirements` 不带 `domainIds` → 500，日志 `DataIntegrityViolationException: Column 'domain_id' cannot be null`。
- 建议：入口校验必填，返回 400 `VALIDATION_FAILED` 并指明字段；同时补一条负向用例进 `platform-test-negative`。

### G4 · 厂商"项目管理"岗没有独立角色（P2，岗位缺口）
- 现状：只有 `PMO`（域负责人）与"厂商负责人"（靠 `vendor_id` 归属判定），厂商项目经理要管本厂商排期/交付，只能借 `PMO`。
- 建议：新增角色 `VENDOR_PM`（厂商项目经理），默认授权：本厂商需求查看、排期建议、交付物登记、测试计划参与；**不给**跨厂商查看与版本审批。走授权页配置，不改代码里的硬编码矩阵（r20 起权限已数据驱动，新增角色成本低）。

## 4. 补齐规划（3 轮，逐轮可验收）

| 轮次 | 范围 | 关键判据（负向为主） | 门禁要求 |
|---|---|---|---|
| **R-A 前置门** | G1（拆解前置）+ G3（必填校验） | 未入池 BUS 拆解 → 409 `PARENT_NOT_POOLED`；未排期 PDR 拆 RD → 409；缺域提报 → 400；正常链路不放宽 | rbac 111 / access 152 / platform-verify / negative 30 / lint / unit 不降 |
| **R-B 工时评估初审** | G2（新审批单据 + 前置门 + 工作台待办 + 列表列） | 无工时 `schedule`/`start_dev` → 409 `ESTIMATE_NOT_APPROVED`；初审驳回后不可排期；父需求汇总不变；业务方视图显示"预估/已投入/偏差" | 新增 `estimate-verify.sh` 接入门禁；既有 9 套不降 |
| **R-C 角色与体验** | G4（`VENDOR_PM` 角色 + 授权页预设）+ 提报表单必填提示 | 新角色登录后只看本厂商；无跨厂商数据；版本审批不可见 | access-verify 角色矩阵扩一列；隔离负向用例 |

排期建议：R-A 与 R-C 可并行（互不碰文件），R-B 单独一轮（动状态机与审批引擎，改动面最大）。

## 5. 附：本次核查跑过的探针

```bash
# G1：未审批需求直接拆解
POST /api/req/requirements            # biz1 建 BUS → SUBMITTED
POST /api/req/requirements/{id}/decompose   # pm → 201 PDR-0149（应 409）

# G2：无工时也能推进
POST /api/req/requirements/{rd}/actions {"action":"start_dev"}  # → 200 IN_DEV
POST /api/req/requirements/{pdr}/actions {"action":"schedule"}  # → 200 SCHEDULED

# G3：缺域提报
POST /api/req/requirements {"type":"BUS","title":"…"}  # → 500（应 400）
```

> 复跑前建议 `make platform-demo-reset` 清掉探针数据；本次探针产生的 `BUS-0389/0391`、`PDR-0149`、`RD-1013/1014` 等已在演示库中，重置即清。
