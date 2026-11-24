# r43 需求字段可编辑性 + 变更记录 · 验收报告

- 任务夹：`docs/teamflow/20261124-r43-req-field-editability/`（runId `tf-mwtzd1oa-p2j7xu`）
- 分支：`feat/req-editability-rules`；HEAD `eb15750`；改动 33 项未提交（24 M / 2 D / 7 新增 + 任务夹）
- 被测栈：网关 8080 / req-service 8082 / web 5173；`.run/req-service.jar.fp = 12608897:94882749:1795485178` 与磁盘 jar 一致（QA 复核，验收沿用）
- 验收人：产品经理（acceptance lead）；日期 2026-11-24
- 结论：**✅ 通过**（P3 遗留 4 条，见 §6，均不阻塞）

## 1. 验收依据与范围

依据：PRD 18 条 AC（AC-1~AC-16、AC-18 为 P0，AC-17 为 P1）、TECHNICAL 模块蓝图、QA-REPORT（修复后复验轮）。
验收动作：逐条核对 AC、独立复跑关键门禁与单测、架构一致性（M3）、改动面与版本库卫生、遗留确认。
视觉观感项受沙箱无浏览器限制（QA §8 已说明为环境限制），按人工补测清单移交，不据此判缺陷。

## 2. 逐条 AC 核对

| AC | 判据摘要 | 证据（本轮核验） | 结论 |
|---|---|---|---|
| AC-1 | 矩阵唯一实现于 platform-contract，req-service 只委托 | `platform-contract/src/main/java/com/example/platform/contract/RequirementEditability.java` 为唯一矩阵；`req-service` 内 `EARLY_STATUSES` 0 处、change 包状态字面量 0 处；`RequirementChangeFields` 仅委托 + 7 字段旧键投影；矩阵单测反射断言无第二份状态表 | ✅ |
| AC-2 | BUS/PDR/RD 各早期 EDIT、受控 CHANGE_REQUIRED、终态 LOCKED，遍历全状态 | 矩阵单测对 `StateTables.statuses` × `FIELDS` 全遍历并含 `CLOSED/ACCEPTED_CLOSED/DONE` 终态；`editability-verify` S2 三组对照 9 条通过 | ✅ |
| AC-3 | 人天：BUS/PDR 锁定 PARENT_ROLLUP，RD TODO 可改、RD 中间需变更，PUT 错误码对应 | 单测字段特例 + 门禁 [10]–[16]：BUS/PDR `estimateDays` LOCKED、RD TODO `EDIT/EARLY_STAGE`、RD 中间 `CHANGE_REQUIRED`；PUT 分别返回 `FIELD_LOCKED`/`CHANGE_REQUIRED` | ✅ |
| AC-4 | type IMMUTABLE、owner ASSIGN_FLOW、domain/sonar 开放态可改、parentId 按类型 | 单测全表断言 `type` 全状态 LOCKED/IMMUTABLE；`ownerUserId`→`ASSIGN_FLOW`；`domainIds/sonarProjectKey` 开放态 `EDIT/OPEN_STATE`；BUS `parentId` `NO_PARENT` 锁定 | ✅ |
| AC-5 | Detail 三数组互斥、并集 11、规范序、两次一致，旧字段 deprecated | `RequirementViews.java:72` `FieldRule`、`:81-85` 三数组 + `domainIds`/`sonarProjectKey`；`detail()` 单次 `rules()` 分组投影；门禁 S5 两次 GET 逐字一致、互斥并集断言通过；`permissions.editableFields` 保留为同源投影。三数组纯计算零查询；新增 `domainIds` 只读字段按需一次 `domainsOf(id)`（TECH §4 明确详情按需装配、列表不引入 N+1，见 P3-1） | ✅ |
| AC-6 | 受控字段真实变化 → 409 CHANGE_REQUIRED，fields 全量，同值不拦 | `RequirementEditGuard` 先算 changed；`RequirementErrors.changeRequired` 返回 `{requirementId, fields, changeEndpoint}`；门禁 S4 多字段受控用例与同值 200 用例通过 | ✅ |
| AC-7 | 锁定字段 → 409 FIELD_LOCKED（field+reasonCode），LOCKED 优先，终态同值 200 无副作用 | `RequirementEditGuard.reject` 先收 locked 再 controlled；`RequirementErrors.fieldLocked` 409 detail 含 `field/reasonCode/fields`；门禁 [17]–[21]：混合只报 LOCKED、终态同值 200 且审计 0 条 | ✅ |
| AC-8 | 既有父子/排期/精度/版本/RELEASE_LOCKED 与变更 apply 不放宽 | `change-verify` 42/42、`platform-test-negative` 132/132、`platform-verify` 199/199；父需求变更单闭环探针 P1–P4 通过；`RequirementChangeValidator` 仅对真实 daysChanged 判人天（5 单测随行，TECH §7.1） | ✅ |
| AC-9 | 抽屉/详情只按三数组渲染，删 children.length，非 EDIT 禁用值可见 | `RequirementEditDrawer.vue` 内 `children` 0 次、`status` 仅用于子需求标签展示；`editabilityOf` 缺失即 fail-closed；`vue-tsc` 与构建通过；静态断言 15/15（7 控件禁用、fail-closed 无入口） | ✅ |
| AC-10 | editability.ts 单点 ReasonCode/文案/解析，未知 code 中文兜底，无模板拼接 | `web/src/views/req/editability.ts` 导出 9 句中文、6 个短标记、未知码兜底「该字段当前不可修改」；静态检查断言每句原因中文仅 1 处且只在 editability.ts | ✅ |
| AC-11 | CHANGE_REQUIRED 一键预填抽屉；FIELD_LOCKED 不给发起变更入口 | `RequirementDetailView.vue:381` `handleActionError`：FIELD_LOCKED → 原因横幅 + `refreshAll` 无 CTA；CHANGE_REQUIRED → `changeFields` 预填；抽屉同语义 | ✅ |
| AC-12 | 变更列表行含 fields[]、审批结果、时间，默认全部状态 | `RequirementChangeViews.Row` 含 `List<String> fields`；`ChangeTab.vue` 7 列两行制、`fieldsOf` chips、`verdictText`、`appliedAt || createdAt`；`:107` 默认无 status 过滤；门禁 [27]/[28] 规范序与空快照 `[]` | ✅ |
| AC-13 | 详情头与字段行共用抽屉并按组预填 | `openChangeDrawer(:136)` 与 `onFieldRequestChange(:160)` 共用同一抽屉；`FieldFlag` 的 `requestChange(field)` 经 `changeTypeOfField` 预填；详情头/字段行两处入口均指向同一组件 | ✅ |
| AC-14 | editability-verify 36 条 6 段，PASS==TOTAL，--static-only/--twice，接入 make test | 独立复跑 `bash scripts/editability-verify.sh --twice`：两轮均 `EDITABILITY_TOTAL=36 CASE=30 PASS=30 FAIL=0`，「两次连跑结果一致」；`--static-only` 6/6；`ck(30)+sck(6)=36` 自证；退出 TAG 足迹 0、基线 12；`scripts/test-all.sh:45` 接在 change-verify 之后，`Makefile:119-120` 薄目标 | ✅ |
| AC-15 | EDITABILITY_MUTATE=always-edit 必红，产品代码无 MUTATE | 独立复跑 `EDITABILITY_MUTATE=always-edit ... --once`：`PASS=19 FAIL=11`，明确「有 11 条断言失败」（断言非空转）；产品代码 `MUTATE` 0 处（门禁 S1⑤ 反向断言） | ✅ |
| AC-16 | 8 项既有门禁 + lint/unit 全绿并连跑两次 | QA 实跑 199/132/206/113/44/21/42/80 全绿；`mvn test` 独立复跑 430/430；lint 24/24；typecheck/build 绿；demo-reset ×2、新门禁 ×2 一致（其余只读门禁本轮单跑，见 P3-2） | ✅ |
| AC-17 | memory 基线与约定同主题替换、REQUIREMENT-FLOW 增可编辑性小节 | `memory.md` 基线行同主题追加 r43 数字并新增「需求可编辑性矩阵」约定行；`REQUIREMENT-FLOW.md` §5.6 可编辑性判定 + 依据表，r40 条目改为 `RequirementEditGuard`；未新建版本文档；该两项已被门禁 S1⑥ 静态守护 | ✅ |
| AC-18 | 改动面受控、pom 与 web/package.json diff 为空、提交信息含 runId 与任务夹、gitignore 复核 | 33 项改动均属本需求（3 项越界小文件已在 TECHNICAL §7.1 登记：`web/src/api/req.ts` +2 类型、`web/src/styles/tokens.css` +10 令牌、`scripts/platform-verify.sh` 两处期望值 400→409 且仍 199）；`pom.xml`/`web/package.json` diff 为空；无迁移；`.gitignore` 未改；提交待收口（P3-3） | ✅ |

## 3. 架构一致性（M3）

- **单一矩阵**：`RequirementEditability.java` 承载 3 类型 × 全状态 × 11 字段与 9 个 reasonCode，判定顺序固定、未知 fail-closed；`FIELDS` 为规范序唯一来源；`requirement/change` 包已无第二份状态→字段表。与蓝图「协议放 contract、服务只委托」一致。
- **装配顺序**：`RequirementService.update`（`:205`）固定为 可见性 → 11 字段快照/比较 → 同值 no-op → `RequirementEditGuard.reject`（LOCKED 优先）→ 既有业务校验 → 落库/审计；`detail`（`:410`）一次 `rules()` 后投影三数组，`changePermissions` 同源，无两套计算。
- **无双守卫/无重复实现**：`RequirementChangeGuard.java` 已删除（文件不存在），逻辑并入 `RequirementEditGuard`；`RequirementChangeFields` 仅保留七字段快照/比较与旧键投影；`RequirementViews.Detail` 是三数组唯一出口。
- **前端单点**：`web/src/views/req/editability.ts` 是文案/标记/解析/决策唯一实现，`FieldFlag.vue` 是字段级标记唯一渲染点；抽屉、详情、概览、变更 Tab 均消费同一函数，无组件内自拼 reasonCode 文案；抽屉中 `status` 仅作子需求标签展示，不参与可编辑性判断。
- **与蓝图的一处事实修正**：`ChangeRow` 类型声明位于 `web/src/api/req.ts`（非 `types.ts`），TECHNICAL §7.1 已登记，属声明位置修正而非结构偏离。
- **判定**：实现与蓝图一致，未发现重复实现、适配漂移或既有结构破坏，无需架构返工。

## 4. 独立复跑证据（验收侧）

| 检查 | 命令 | 结果 |
|---|---|---|
| 新门禁连跑两次 | `bash scripts/editability-verify.sh --twice` | ✅ 两轮 36/36，输出「两次连跑结果一致」 |
| 负向自检 | `EDITABILITY_MUTATE=always-edit bash scripts/editability-verify.sh --once` | ✅ PASS=19 / FAIL=11（按预期变红） |
| 后端单测 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service -am test` | ✅ 430/430，BUILD SUCCESS |
| 前端类型 | `cd web && npm run typecheck` | ✅ rc=0，无报错 |
| 8 项既有门禁 | QA 本轮实跑（platform/negative/access/rbac/release/gate-order/change/demo-reset） | ✅ 199/132/206/113/44/21/42/80；demo-reset ×2 |
| 代码与文档抽检 | 矩阵/守卫/Service/前端单点/变更 Tab/文档 grep | ✅ 与 AC 判据逐项对应，无第二份实现 |

## 5. 改动面与版本库卫生

- 33 项：24 M / 2 D / 7 新增源码与前端文件 + 任务夹；`target/`、`dist/`、`node_modules/`、`.run/`、`.nacos/`、`.env*`、`.idea/` 命中 0。
- 依赖与迁移：`pom.xml`、`web/package.json` diff 为空；无数据库迁移。
- 越界但已声明：`web/src/api/req.ts`（仅 `ChangeRow.fields?` 类型，+2）、`web/src/styles/tokens.css`（4 个只增令牌，+10）、`scripts/platform-verify.sh`（父人天/环断言 400→409，断言数仍 199）；均在 TECHNICAL §7.1 逐行登记，无业务语义放宽。
- 收口提交尚未发生（P3-3），提交信息须含 `tf-mwtzd1oa-p2j7xu` 与任务夹路径，并在说明中写明 `.gitignore` 复核结论。

## 6. 意见与遗留（均 P3，不阻塞验收）

- **P3-1（AC-5 口径）**：PRD 字面「不新增 DB 查询」指三数组本身零查询；新增只读展示字段 `domainIds` 需要一次 `domainsOf(id)`（详情单条，列表路径未变、无 N+1）。TECHNICAL §4 已按此设计，建议下一轮把该句改为「三数组零额外查询」以免歧义。
- **P3-2（AC-16 证据范围）**：本轮 `demo-reset` 与新门禁各连跑两次，其余 7 项只读门禁单跑；与 r40「有状态复位门禁跑两次」口径一致。若后续要求全部 8 项逐一双跑，建议在门禁脚本内固化。
- **P3-3（收口提交）**：33 项改动仍未提交，属既有流程（r40/r42 均由 host 在验收收口统一提交）；提交信息需含 runId 与任务夹，提交前复核 `.gitignore`。
- **P3-4（视觉人工补测）**：沙箱无浏览器（Chromium crashpad 受 host 限制），QA §8 的 4 项（字段标记无胶囊/tooltip、变更 Tab 列宽、抽屉对齐、变更单全链路语感）保留为人工补测，不构成本轮缺陷。
- **P3-5（越界文件）**：`api/req.ts`、`tokens.css`、`platform-verify.sh` 三项超出 PRD 声明的改动清单，但均为类型/令牌/期望值层面的必要修正并已登记，按 P3 记录。

## 7. 验收结论

18 条 AC 逐条核对通过；矩阵单点、服务委托、前端单点与蓝图一致，未发现重复实现或结构破坏；新增门禁 36/36 连跑一致且负向必红，既有 8 项门禁与单测/lint/typecheck 全绿；无 P0/P1/P2 缺陷，4 条 P3 遗留均为口径/流程/环境事项。

验收结论：✅ 通过
