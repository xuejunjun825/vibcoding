<!-- meta: summary="两个演示层残留：无权限的写按钮不再把人点进全局 403（按动作/功能码收敛 + 新增门禁段），演示夹具把 VEN-A 承接链挂进演示版本并给用例补厂商归属，使 vpm1 的版本计划与用例库非空。" -->
基线依赖：docs/teamflow/20261116-r24-vendor-pm-role（VENDOR_PM 的 168 行授权、厂商隔离、用例库窄例外与 vpm1 演示账号不得回归）；docs/teamflow/20261116-r22-action-matrix-datadriven（动作码三档判定、1848 格等价与 11 条显式 DENY 覆盖不得回归）
取代：docs/teamflow/20261116-r24-vendor-pm-role#AC-8：仅把「演示数据里的版本对厂商不可见」这一演示预期改为「本厂商承接的演示版本可见」——release 的派生可见性谓词（ScopeSql 的 release EXISTS）与授权语义不改，改的只是夹具数据；种子数据集（R2026-11）口径不动。

# PRD · r25 演示层权限收敛与厂商演示数据

## 0. 一页结论

两个问题都出在「演示」这一层，而不是授权语义：

1. **按钮没按权限收敛**。`web/src/views/req/RequirementPoolView.vue:333` 的「提报需求」是无条件渲染的裸 `router.push('/requirements/new')`；`/requirements/new` 的路由 meta 声明了 `functionCode: ['req:report:bus','req:report:dev']`（`web/src/router/routes/req.routes.ts:11-17`），vpm1 两个都没有，于是路由守卫（`web/src/router/index.ts:102-136`）把人送到 `/403?code=…`，页面只显示「无权访问该单据」。**服务端没错，错在把一个自己没有权限的入口摆在了他面前。** 同族还有两处同类裸按钮：`web/src/views/deliver/DefectListView.vue:313`「新建缺陷」、`web/src/views/deliver/IterationsView.vue:189`「新建迭代」（只按 `releaseId` 禁用，不按动作码）。其余四处（新建版本、提交发布审批、新建用例、新建计划）已是「禁用 + 悬停原因」范式，本轮把它定成全站唯一形态并补齐缺口。
2. **厂商视角没有可看的数据**。`make platform-demo-reset` 之后 vpm1/vendor1 的 `GET /api/req/releases` 与 `GET /api/deliver/testcases` 都是 0：演示版本 `DEMO-REL-1` 的 `release_requirement` 只挂内部 8 条，VEN-A 承接的 `DEMO-R-09/10/11`（`req-service/src/main/java/com/example/req/seed/DemoFixture.java:355-359`）`inRelease` 全是 `"0"`；`test_case` 插入语句（`req-service/src/main/java/com/example/req/seed/DemoResetService.java:435-446`）压根没写 `vendor_id`，而线上建用例时是 `entity.setVendorId(requirement.vendorId())`（`deliver-service/src/main/java/com/example/deliver/testcase/TestCaseService.java:709`）。于是 ScopeSql 的两条 VENDOR 谓词（`platform-common/src/main/java/com/example/platform/common/scope/ScopeSql.java:59-65` 的 release 派生 EXISTS、`:82` 的 test_case.vendor_id）把厂商筛成了空集。

一句话产品语义：**没权限的按钮要自己说明白，而不是把用户骗进 403；演示数据要真的被厂商承接，而不是让厂商账号看一座空库。** 两件事都不许靠放松服务端鉴权来解决。

## 1. 背景与目标

r24 交付了第 11 个内置角色 VENDOR_PM，并把演示账号 `vpm1` 发给了团队。演示与验收过程中暴露出两个「功能都对、演示就崩」的残留：一个入口级 403，一个空数据页。它们都不影响真实客户路径的授权正确性，但直接决定「厂商视角这个故事能不能讲」。

**目标**

- G1 任何「当前账号没有权限」的写按钮，一律在**点击之前**就表达清楚：禁用 + 一句可读的原因，点击不产生请求、不改变 URL、不出现 `/403`。
- G2 该判定与授权同源：一律读 `canAction` / `can`（前端已有的权限集合），不在前端复制判定规则，不放宽服务端任何一格。
- G3 演示夹具让 VEN-A 承接链进入演示版本，并让用例带厂商归属，使 vpm1 登录后版本计划 ≥1、用例库 ≥1。
- G4 两条都用门禁钉住：新增前端准入断言段（静态 + HTTP 探针），演示重置脚本断言只增不减。

**不在目标内的**：真实客户数据的厂商归属修正、种子数据集（R2026-11）的内容重做、任何后端授权代码变更。

## 2. 用户故事与验收标准

> 口径说明（写在最前，免得下游误解）：本需求的「演示数据」指 `make platform-demo-reset` 重建出的那一套（`DemoFixture` + `DemoResetService`）。`platform-verify` 用的种子数据（`SeedService` 的 `R2026-11`）是另一套，本轮不动。

**US-1（厂商项目经理 vpm1）** 我打开需求池，看不到（或点不动）「提报需求」，也不需要被扔到全局 403 才知道自己没这个权限。
**US-2（admin / pm / po1）** 我有权限的按钮一个都不能少、一个都不能变灰，点击照旧工作。
**US-3（演示讲解人）** 我用 vpm1 登录，版本计划里能看到本厂商承接的演示版本，用例库里能看到本厂商的用例。
**US-4（QA / 维护者）** 这两个问题再复发，门禁必须自己变红；断言只许增不许减。

### AC-1 需求池「提报需求」按创建需求权限收敛

vpm1（`VENDOR_PM`）打开 `/requirements` 时，「提报需求」按钮处于收敛态（形态见 AC-2），可见原因指向 `req:report:bus` / `req:report:dev`；点击**不发起任何请求、URL 不变、不出现 `/403`**。同一页面空态里的「提报需求」主按钮（`web/src/views/req/RequirementPoolView.vue:456-458` 的 `EmptyState` `@action`）同样收敛，不允许存在第二条裸 `router.push('/requirements/new')` 路径。admin / pm / po1 的按钮不变灰、点击仍进入提报页并创建成功。静态断言：`RequirementPoolView.vue` 中通往 `/requirements/new` 的路径数 = 2（页头 + 空态），且两处都受同一个权限判定约束；无权限时不产生导航。

### AC-2 全站写按钮统一为「禁用 + 原因」形态（写死，不再二选一）

统一规则 R1：**页面可见但写动作受限的按钮 = 渲染 + `:disabled="!canX"` + `:title="canX ? '' : '需要权限：<动作码或功能码>'"` + handler 首行兜底 `if (!canX) return`**。理由：现有 4 处（新建版本 / 提交发布审批 / 新建用例 / 新建计划）已经是这个形态，改成隐藏会让 6 个页面一起返工，且演示时讲解人无法向观众解释「这里本来能做什么」。

必须逐条落到位（7 个入口）：

| 页面 | 入口 | 判据 | 现状 |
|---|---|---|---|
| `/requirements` | 提报需求（页头 + 空态） | `can('req:report:bus') \|\| can('req:report:dev')` | 裸跳，须收敛 |
| `/releases` | 新建版本 | `release.action.create` | 已符合 R1 |
| `/releases/:id` | 提交发布审批 | `release.action.approve` | 已符合 R1 |
| `/testcases` | 新建用例 | `test.action.case_edit` | 已符合 R1 |
| `/test-plans` | 新建计划 | `test.action.plan_create` | 已符合 R1 |
| `/defects` | 新建缺陷 | `defect.action.create` | 裸 `openCreate`，须收敛 |
| `/iterations` | 新建迭代 | `sprint.action.create` | 只看 `releaseId`，须收敛 |

对 vpm1 而言，上表七个入口**全部为禁用态**（其 `allowedActions` 只有 `schedule` 与缺陷处理动作，见 AC-10）。已排查确认无独立创建入口、因此不新增改动的：发布单 / 上线单（无列表页创建按钮）、任务（看板内新建入口不存在，`task.action.create` 无前端落点）。

### AC-3 403 不落全局页（探测类调用一律 `silentForbidden`）

需要「探测账号可选能力」的调用点必须显式 `silentForbidden`（`web/src/api/client.ts:21-22` 已在 180-186 行实现「带标记则不 navigate('/403')」），页面就地降级。本轮必须核对并覆盖的调用点：新建缺陷弹窗内的字典/可选依赖、新建迭代弹窗、用例库新建弹窗的需求/模块下拉、版本创建弹窗、测试计划三步抽屉。不满足的逐点补标记，并在断言段登记「豁免台账」（每条带理由）。

深链 `/requirements/new` 仍按路由守卫 403（`code=req:report:bus,…`）——这是**入口不可达**的正确表达，本需求不改；本需求只保证「页面内点击」不再走到那里。

### AC-4 新增前端准入门禁段 a6（静态 + 探针，纳入 access-verify）

在 `scripts/tests/access/` 新增 `a6-write-button-gate.mjs`，由 `scripts/access-verify.sh` 编排，计数经 `ACCESS_COUNTS` 汇总（格式沿用 `a6 <pass> <total>`）。合计 **≥24 条**，分四组：

- 静态收敛 ≥12：AC-2 表的 7 个入口 × 至少 1 条（按钮存在、disabled 绑定到权限判定、click 不外露）+ 需求池两条创建路径各自 1 条。
- 403 降级 ≥4：AC-3 的调用点逐点断言带 `silentForbidden`；另加 `web/src/api/client.ts` 403 分支未被改成无条件跳转。
- 运行时探针 ≥4：vpm1 登录后 `POST /api/req/requirements`、`POST /api/req/defects`、`POST /api/deliver/test-cases`、`POST /api/req/releases` 均 403（语义不变的证明，不是本需求要改的东西）。
- 自检 ≥2：内存夹具证明「缺收敛必红」「已收敛必绿」——防空扫假绿。

`a6` 单独运行失败必须非零退出；栈未起时不得写计数行（沿用 a5 的约定）。

### AC-5 演示夹具：VEN-A 承接链挂进 DEMO-REL-1

`DemoFixture` 里 VEN-A 承接链（PDR `DEMO-R-09`「外协订单对账（VEN-A 承接）」+ RD `DEMO-R-10`/`DEMO-R-11`）`inRelease` 置为纳入，`DemoResetService` 依既有同源路径同时写 `requirement.release_id` 与 `release_requirement` 行（`DemoResetService.java:381-415` 已有该分支，不新增 UPDATE）。`DEMO-R-12`（VEN-C）与 8 条内部需求保持不纳入。需求总数仍 12、版本总数仍 1。

### AC-6 演示夹具：用例带上厂商归属

VEN-A 需求下的用例写入 `test_case.vendor_id = VEN-A`，派生方式与线上一致（用例的厂商取所属需求的厂商，`TestCaseService.java:709` 同源），不得硬编码厂商 id、不得直接 UPDATE 已有行。重置后 VEN-A 用例 ≥1（当前夹具口径为 3 条：`DEMO-C-09/10` → `DEMO-R-10`、`DEMO-C-11` → `DEMO-R-11`）；用例总数与 admin/pm 视角不减少。

### AC-7 演示结果口径（demo-reset 之后）

- vpm1：`GET /api/req/releases` ≥1 且含 `DEMO-REL-1`；`GET /api/deliver/testcases` ≥1。
- vendor1：`GET /api/req/releases` ≥1（用例库口径见「假设与待澄清」）。
- admin / pm：版本 =1、需求 =12、用例 =12，均不减少。
- 厂商隔离边界不变：vpm1 需求列表仍 =3、vendor2 仍 =0、po1 仍 =8。

### AC-8 `scripts/platform-demo-reset.sh` 断言只增不减

现有 61 条一条不删，新增 ≥4 条：vpm1 releases ≥1、vpm1 testcases ≥1、vendor1 releases ≥1、admin/pm 版本与用例数不减少（可拆 2 条）。总数 61 → **≥65**（硬下限 63）。新增断言必须走真实登录 + API，不得只查库；幂等断言（连跑两次逐键一致）保留并覆盖新增维度。

### AC-9 `platform-verify` 相关断言显式核对

版本 / 用例 / 厂商可见性相关断言逐条复核并留下结论。已知事实：`scripts/platform-verify.sh:996-1007` 的 C4 读取 `release.code='R2026-11'`（种子数据集，`req-service/src/main/java/com/example/req/seed/SeedService.java:287`），与演示夹具的 `DEMO-REL-1` 不是同一条，本轮不动 SeedService，故该断言语义不变。若实测发现某条断言因演示数据变化而红，只能**修正预期值或读取方式**（例如按夹具 code 常量取值，避免空值退化成永真），不得删除断言、不得改可见性谓词。断言总数 ≥173 不回退。

### AC-10 服务端鉴权与授权数据零改动

`PermissionMatrix` / `RbacSeeder` / `ScopeSql` / 各 Controller 一行不改：VENDOR_PM 仍 168 行、全量仍 1848 格、11 条 ACTION DENY 覆盖不变；vpm1 的 `allowedActions` 仍只含 `schedule` 与缺陷处理动作；`POST /api/req/requirements` 对 vpm1 仍 403 `FORBIDDEN`。前端收敛只读权限集合，不新增判定规则。

### AC-11 门禁不缩水

`rbac-verify` ≥113、`access-verify` ≥189（165 + a6 ≥24）、`platform-verify` ≥173、`platform-test-negative` ≥92、`platform-demo-reset` ≥65、`lint` ≥22、`unit` ≥338、`verify` 14/14、`analytics` 74、`collab` s0–s8、`typecheck` 0、`npm run build` 绿、`mvn -o -DskipTests compile` 0 错。禁止删断言、禁止降基线、禁止把断言改成恒真。

### AC-12 改动面与证据

改动仅限：`web/src/views/req/RequirementPoolView.vue`、`web/src/views/deliver/DefectListView.vue`、`web/src/views/deliver/IterationsView.vue`（+ AC-3 涉及的弹窗与 api 调用点）、`scripts/tests/access/a6-write-button-gate.mjs`、`scripts/access-verify.sh`、`req-service/src/main/java/com/example/req/seed/DemoFixture.java`、`req-service/src/main/java/com/example/req/seed/DemoResetService.java`、`scripts/platform-demo-reset.sh`、文档。`pom.xml` 与 `web/package.json` diff 为空。UI 沿用 `docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md` 的禁用态与文字规范，不引入新组件。`evidence/` ≥3 张截图：vpm1 需求池按钮收敛态、vpm1 版本计划非空、vpm1 用例库非空（附 a6 输出摘要）。

## 3. 范围与非目标

**范围内**：前端 7 个写入口的收敛、探测调用点降级、a6 断言段、演示夹具的版本挂载与用例厂商归属、demo-reset 断言、platform-verify 核对、相关文档同主题更新。

**非目标**

- 不改服务端鉴权语义，不给 VENDOR_PM 增删任何授权格；不为了「按钮能点」放松任何一格。
- 不做前端权限判定重构（不抽公共组件、不动 `usePermission` 的契约）。
- 不重做种子数据集（`SeedService` 的 R2026-11 内容不动）。
- 不新增发布单/上线单页面或入口（当前不存在）。
- 不把全局 `/403` 页面本身改版（它仍是深链与真越权的正确落点）。
- 不引入 E2E 运行器（`web/package.json` 没有测试运行器，且硬约束禁止新依赖）。

## 4. 反面目标 / anti-goals

即使门禁全绿，出现下列任一情形即判**失败**：

- **A1 用授权换按钮**：为了让「提报需求」可点，给 VENDOR_PM 加了 `req:report:*`（或任何服务端放行）。可观察：`/admin/permissions` 上 VENDOR_PM 出现 `req:report:bus` ALLOW，或 vpm1 直接 `POST /api/req/requirements` 返回 200。
- **A2 收敛过头**：admin/pm/po1 的写按钮也变灰或消失，或点击无反应。可观察：admin 打开需求池/缺陷页，按钮不可点。
- **A3 靠 UPDATE 造数据**：夹具用 `UPDATE test_case SET vendor_id=…` / 手工改 `release_id` 造「好看」的数据。可观察：连续两次 `make platform-demo-reset` 后，第二次的 vpm1 版本/用例数又回到 0，或两次计数表不一致。
- **A4 删断言换绿**：把「厂商视角看不到演示版本」「用例 12 条」这类断言删掉或改成恒真（例如在空 id 上也能通过）。可观察：断言总数下降，或断言在夹具未生效时依然通过。
- **A5 演示好看但内部缩水**：admin/pm 的版本数、需求数、用例数任何一项变少。可观察：admin 登录，版本计划 <1、需求池 <12、用例库 <12。
- **A6 禁用但不说原因**：按钮变灰、悬停没有任何说明，或说明文案是不可读的内部代号而不带「需要权限：」前缀。可观察：鼠标悬停 vpm1 的「新建缺陷」，看不到一句中文原因。

## 5. 交互与判定流程摘要

```
用户进入页面 → 登录态权限集合（permissions / actions）已就绪
  ├─ 有该动作/功能码 → 按钮可点 → 正常提交（服务端仍二次校验，不变）
  └─ 无               → 按钮禁用 + title「需要权限：<码>」
                        └─ 点击：el-button 原生阻断 → 无请求、无路由变化、无 /403
探测类依赖（字典/下拉/可选能力）→ 请求带 silentForbidden
  └─ 403 → 页面内降级（空表/禁用下拉/内联提示），绝不 navigate('/403')

演示：make platform-demo-reset
  → DEMO-REL-1 的 release_requirement 含 VEN-A 承接链（09/10/11）
  → test_case.vendor_id = VEN-A（按需求派生）
  → vpm1 登录：版本计划 1 条（DEMO-REL-1）、用例库 3 条
```

判定顺序与授权同源：**页面/功能码准入 → 动作码 → 数据域**，前端只用前两步的既有结果，不复刻规则。

## 6. 优先级

两款均为 **P2**（不影响授权正确性，影响演示可信度与验收体验）。相对顺序：AC-1/AC-2/AC-4（403 体验）先于 AC-5/AC-6/AC-8（数据），因为前者是「用户被送到错误页」，后者是「页面为空」。

## 7. 依赖与风险

**依赖**：r24 的 VENDOR_PM 授权与 `vpm1` 账号；r22 的动作码注册表（`platform-contract/src/main/java/com/example/platform/contract/ActionRegistry.java:94/98/102/113/122/124`）；`scripts/access-verify.sh` 的计数汇总契约（`aN pass total` 行）。

**风险 / 对策**

- R1（高）「收敛」被实现成新增前端判定分支，与服务端漂移 → 对策：只读 `canAction`/`can`，a6 断言按钮判据必须是这两个函数，且 AC-10 用「vpm1 写端点仍 403」兜底。
- R2（中）版本挂载改变 release 详情的需求计数（8 → 11）→ 对策：AC-9 要求逐条核对并按新预期修正，禁止删断言；QA 报告须写明差异来源。
- R3（中）夹具厂商归属写错列（`owner_vendor_id` vs `assignee_vendor_id`）→ 对策：AC-6 要求与 `TestCaseService.java:709` 同源派生，demo-reset 后同时断言 API 与库内两列。
- R4（中）静态断言写得太松（正则命中注释、空扫也绿）→ 对策：a6 必须带内存自检（缺守卫必红 / 有守卫必绿），沿用 a3/a5 的「注释先清空、扫描数 ≥1」做法。
- R5（低）`platform-verify` 的 `R2026-11` 查询在演示数据上取到空值而退化为永真 → 对策：AC-9 明确「空值不得通过」，改为按夹具 code 常量取值或显式判空失败。

## 8. 里程碑建议

- M1（P2）前端收敛 + a6 断言段：7 个入口到位、a6 ≥24 条、`access-verify` ≥189、typecheck/build 绿。
- M2（P2）演示夹具 + demo-reset 断言：VEN-A 链入版本、用例带厂商、61 → ≥65、连跑两次幂等。
- M3（P2）回归与证据：platform-verify 核对、全门禁复跑、evidence ≥3 张、DEMO-BY-ROLE 与 memory 同主题更新。

## 9. 工程约束（逐条照做，不得改写）

- **分支**：当前已在 `feat/vendor-demo-polish`，**不新建分支、不切主分支、不自行 merge**；收尾的合并动作由用户确认。
- **未提交改动**：工作区当前只有 `?? docs/teamflow/20261116-r25-demo-perm-vendor-seed/`（本任务夹）。随本次改动一并提交，**不得 stash、不得丢弃、不得回滚他人改动**。
- **任务拆分**：≤3 个任务且**串行**执行；每个任务开始前先跑通 `mvn -o -DskipTests compile` 与 `npm run build`，改完再各跑一次。
- **不做顺手重构、不引入新依赖**；`pom.xml` 与 `web/package.json` 的 diff 必须为空。
- **服务端鉴权一律不改**：默认拒绝的语义不得为了让按钮能点而放松（见 AC-10）。
- **门禁不缩水**：rbac-verify ≥113、access-verify ≥189、platform-verify ≥173、platform-test-negative ≥92、platform-demo-reset ≥65、lint/unit/compile/typecheck 全绿；**禁删断言、禁降基线、禁改恒真**。
- **`.gitignore` 审计（提交前）**：现有规则已覆盖 `target/`、`dist/`、`node_modules/`、`logs/`、`.env*`、`.idea/`、`.DS_Store`；提交前用 `git status` 核对暂存面，若出现本栈新的构建产物（如 `*.tsbuildinfo`）才补一条，否则零改动。**禁止把构建产物、日志、`node_modules` 提交入库。**
- **文档边界**：只写 `docs/teamflow/<任务夹>/`、`docs/teamflow/memory.md`、`logs/teamflow/<runId>/`；不写宿主 `docs/<角色>/`、不散落根目录日志文件；`AGENTS.md` 除托管区外一字不动。

## 10. 假设与待澄清

- **按钮收敛形态（选「禁用 + 悬停原因」）**：曾想过无权限直接不渲染（与导航一致）。选禁用的理由：现有 4 个页面已经是这个形态，改成隐藏要 6 页一起返工；且演示时讲解人能指着灰按钮说明「这个角色能做/不能做什么」。若用户要「不渲染」，则 AC-2 表格里的 4 处既有实现要一并改成 `v-if`，a6 的静态断言也要从「disabled 绑定」改为「不存在该元素」。
- **vendor1 的用例库口径**：澄清要求「vpm1 与 vendor1 的版本计划、用例库 ≥1」，但 r24 已把用例库定为「仅 VENDOR_PM 开只读例外，vendor1 深链 403」（`vendorPmAllowed`）。默认维持 r24 页面权限，vendor1 只断言版本 ≥1、用例按 API 口径可见 ≥1；若用户要 vendor1 也能进用例库，需要显式加第二个例外并同步 a3 的 R11 与 r24 的断言。
- **用例补齐方式（选「补字段、不新增行」）**：夹具里 VEN-A 需求下已有 3 条用例（`DEMO-C-09/10/11`），只是 `vendor_id` 没写。默认给这 3 条补厂商归属（条数仍 12，零计数变更）；若用户坚持「新增 1–2 条 VEN-A 专属用例」，用例数会变 13/14，需同步 demo-reset 的 `testCases` 与 platform-verify 的相关计数。
- **版本挂载范围（选「09 + 10/11 整链」）**：澄清只点名 `DEMO-R-09`，但它是产品需求、下辖 `DEMO-R-10/11` 两条研发需求（同为 VEN-A）。默认整链挂进 `DEMO-REL-1`，这样版本详情对厂商也是自洽的；若只挂 09，厂商在版本详情里只看到一条父需求。
- **种子数据集是否也要非空（事实项）**：`platform-verify` 的 C4 读的是 `SeedService` 的 `R2026-11`，与演示夹具的 `DEMO-REL-1` 是两套数据。默认**不动 SeedService**（用户澄清只提 DemoFixture，且动种子会扩大改动面、牵动 platform-verify 多条计数）；若希望「只跑 seed 不起 demo-reset」时厂商视角也非空，需要另立需求。
