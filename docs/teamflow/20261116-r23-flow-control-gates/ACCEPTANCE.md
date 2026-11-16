# 验收报告 · r23 需求流转前置门与提报必填校验

- 任务夹：`docs/teamflow/20261116-r23-flow-control-gates/` · 分支：`feat/flow-precondition-gates`（基线 `main=535939a`，r22 合并点）
- runId：`tf-mwisc832-uxfle0` · 验收日期：2026-11-16 · 验收依据：本夹 `PRD.md`（AC-1～AC-15）、`TECHNICAL.md`、`QA-REPORT.md`
- 结论摘要：**可验收**。P0/P1/P2 共 15 条 AC 全部通过；三条写入口共用同一判定函数、409 与详情 `decomposeGate` 同源、`domainIds` 必填按 AC-10 九入口收敛为 400/403；既有门禁不缩水，无重复实现。P3 观察 5 条，均不阻断（见 §6）。

## 1. 验收侧独立复跑（不引用 QA 结论的部分）

| 复跑项 | 命令 | 实测 |
|---|---|---|
| 真实 HTTP 前置门证据 | `bash logs/teamflow/tf-mwisc832-uxfle0/scripts/t4-gate-evidence.sh` | **27/27**，`captures.json` 33 条原始载荷（含本次新增） |
| 负向门禁 | `bash scripts/platform-test-negative.sh` | **49/49**（30→49，既有 30 条零删除） |
| 平台主门禁 | `bash scripts/platform-verify.sh` | **168/168**（≥167；168 为条件断言命中，见 P3-2） |
| lint | `bash scripts/tests/lint.sh` | **22/22**（含改后的 L9 自检） |
| AC-3 同门静态断言 | `node …/t4-ac3-same-gate.mjs` | **21/21** |
| AC-8 两入口 | `node …/ac8-decompose-gate-check.mjs` | **25/25**（静态 11 + 真 SFC/真 Pinia SSR 14） |
| 目标单测 | `mvn -o -pl req-service test -Dtest=RequirementParentGateTest,RequirementDomainRulesTest` | **13/13**（8+5，0 failure/0 error） |
| AC-10 入口核查 | `bash …/qa-r23-ac10-entry-audit.sh` | **7/7**，9 行核查表与 QA 报告一致 |
| 前端类型 | `cd web && npm run typecheck` | exit 0，无错误输出 |
| **AC-1 审计补充探针** | SUBMITTED BUS 6487 拆解 → 409 | `audit_log` DECOMPOSE 0→0 行、子需求 0→0（AC-1 的「无 DECOMPOSE 记录」由验收补证） |
| **AC-5 继承补充探针** | POOLED BUS 拆解不带 `domainIds` → 201 | 子域 `[3]`=父域 `[3]`、`type=PDR`、`parent_id` 正确、审计 3→4、rollup 已重算 |

## 2. 逐条 AC 验收

| AC | 结论 | 验收证据（节选） |
|---|---|---|
| AC-1 拆 PDR 父 BUS 非 POOLED → 409 `PARENT_NOT_POOLED` | 通过 | 真栈：`detail` 恰 `{parentId,parentStatus,required:"POOLED"}`，requirement 计数与子需求不变，audit_log 0 行 |
| AC-2 拆 RD 父 PDR 非 SCHEDULED/IN_DEV → 409 `PARENT_NOT_SCHEDULED` | 通过 | 真栈：`detail` 恰 `{parentId,parentStatus,allowed:["SCHEDULED","IN_DEV"]}`，不落库 |
| AC-3 直建入口同门（PDR/RD 两条） | 通过 | 直建与拆解 409 的 code/message/detail **逐字相等**；静态断言证明门只在 `RequirementRules.parentGate`、`decompose` 委托 `create` |
| AC-4 PUT 改父同门、失败 `parent_id` 不变 | 通过 | 负向 4b：RD 改挂未排期 PDR → 409 且 `parent_id=6476` 未变；合法改挂 200。类型/祖先链先于状态门（`RequirementService.java:200-207`）、父未变早退（`:196-199`） |
| AC-5 正常链路不误伤 | 通过 | POOLED→PDR 201、SCHEDULED/IN_DEV→RD 201、直建 RD 201、改挂/回写 200；继承域、审计、rollup、手填人天 400、非法组合 400 均在本轮实测或 platform-verify:204/207 断言内 |
| AC-6 判定顺序与既有守卫不变 | 通过 | 终态 400、类型 400、无 `req:split` 403 均先于 409；`StateTables` 零 diff、`allowedActions` 无 `decompose`、16 动作契约未动（QA 探针） |
| AC-7 详情 `decomposeGate` 与 409 同源 | 通过 | `reason` 与 409 `message` 逐字一致；单测遍历 BUS/PDR 全状态（`StateTables.statuses`）断言 allowed⇔不 409、required 一致 |
| AC-8 前端两入口按门禁用 | 通过 | `ChildrenTab.vue` 两处均 `:disabled="!canDecompose"`、共用 `onDecompose`、空态无 `@action` 绕过；SSR 四态 + typecheck |
| AC-9 `domainIds` 缺/[]/null → 400 指名字段 | 通过 | 三种变体均 400 `VALIDATION_FAILED`、`detail.field="domainIds"`、不落库；非正整数/含 null → 400 `invalidIds`（`RequirementRules.checkDomainIds`） |
| AC-10 同类必填九入口逐条核查 | 通过 | 见 §3 九行表；无裸 500；存在性 400 `invalidIds`、越权 403 `OUT_OF_SCOPE` |
| AC-11 PUT `domainIds:[]` 维持现状 | 通过（有记录） | 单域需求 200 且域关系不变（净 no-op）；多域需求回退主域（既有行为，见 P3-1） |
| AC-12 负向 ≥6 条、30→≥36 | 通过 | 49/49；4b 段覆盖 6 条门负向（含直建 RD 409、PUT 改父 409 且 `parent_id` 未变）+ 正向回归，每条带计数/父 id 断言 |
| AC-13 门禁不缩水 | 通过 | §4；验收复跑 6 项全绿，QA 另复跑 rbac/access/unit/verify/analytics/collab 等 |
| AC-14 证据与提交面 | 通过 | ≥4 份证据（409、409 detail、400、201 回归 + 前端断言）齐备，`captures.json` 带 runId；`pom.xml`/`web/package.json`（含 lock）diff 为空；改动面在 req-service 需求域/release 视图、web 两文件、scripts、memory |
| AC-15 文档一致性 | 通过 | `memory.md` +2/-1（新增 r23 门约定行 + `negative` 28→30 订正）；`AGENTS.md` 零改动 |

## 3. AC-10 九入口实测结论（验收复跑，2026-11-16）

| # | 请求语义 | 实测 | 期望 | 本轮改 | 不落库 |
|---|---|---|---|---|---|
| 1 | `type=BOGUS` | 400 `VALIDATION_FAILED` | 400 | 否 | 68=68 |
| 2 | `priority=P9` | 400 `VALIDATION_FAILED` | 400 | 否 | 68=68 |
| 3 | `title` 空白 | 400 `VALIDATION_FAILED` | 400 | 否 | 68=68 |
| 4 | `estimateDays=-5` | 400 `INVALID_DAY_PRECISION` | 400 | 否 | 68=68 |
| 5 | `parentId=999999` | 404 `NOT_FOUND` | 404 | 否 | 68=68 |
| 6 | `domainIds` 含不存在域 | 400 `VALIDATION_FAILED`，`invalidIds=[…]` | 400 | 是（新增） | 不落库 |
| 7 | `domainIds` 含越权域 | 403 `OUT_OF_SCOPE`，`domainIds=[…]` | 403 | 是（新增） | 不落库 |
| 8 | `decompose` 缺 title | 400 `VALIDATION_FAILED` | 400 | 否 | 68=68 |
| 9 | 任一裸 500 | 0 条 5xx | 无 | — | — |

补充：`tier==TENANT` 不受域集合限制；DOMAIN 档用已授权域提报仍 201（403 按域判定，非整档封死）。

## 4. 门禁矩阵（AC-13）

| 门禁 | 实测 | 来源 | 基线 |
|---|---|---|---|
| compile | exit 0 | QA | 通过 |
| platform-verify | 168/168（验收）/167/168（QA 两次） | 验收复跑 | 167 |
| negative | 49/49 | 验收复跑 | ≥36 |
| rbac | 111/111 | QA | 111 |
| access | 152/152 | QA | 152 |
| lint | 22/22 | 验收复跑 | 22 |
| platform-unit | 329 | QA | ≥316 |
| verify | 14/14 | QA | 14 |
| analytics | 74/74 | QA | 74 |
| collab | 256/256（s0–s8） | QA | s0–s8 |
| collab-perf / integration | 5/5 · 231/231（R7 夹具） | QA | 原基线 |
| 前端 typecheck | 0 error | 验收复跑 | 0 |

夹具改造核对：`platform-verify.sh` 仅 1 条 `expect` 标题改为「父=BUS[POOLED]」+ 1:1 替换父选取并补两处 `warn`，**断言数 167 不变、零删除**——改夹具是为适配更严的门（AC-2 段原夹具 SUBMITTED BUS→PDR→RD 在门生效后必红），不是放宽门禁。

## 5. 架构一致性（M3）

| 蓝图条目 | 实现 | 判定 |
|---|---|---|
| 门收敛为 `RequirementRules.parentGate` 纯函数 | `RequirementRules.java:59/80/99`，无 Spring/DB；BUS/PDR 全状态可遍历 | 符合 |
| `RequirementService.requireParentGate` 唯一调用点 | `:219`；`create:110`（拆解经 create 同源）、`applyParentChange:210` 复用 | 符合（1 helper、2 写入口调用） |
| 详情 `decomposeGate` 单映射 | `RequirementViews.DecomposeGate.from` 唯一，`toView` 单点调用；列表/版本构造传 null | 符合 |
| `domainIds` 必填只在直建入口 | `RequirementController.java:105` → `RequirementDomainValidator.requireForCreate`；decompose 不经过 | 符合 |
| 纯函数可脱 Spring 单测 | `unseen`/`outOfScope` 静态；5 例单测 | 符合 |

重复实现核查：`PARENT_NOT_POOLED`/`PARENT_NOT_SCHEDULED` 字面量仅在 `RequirementRules`；`DecomposeGate.from` 仅 1 处生产调用；前端只读 `decomposeGate.allowed`，无按状态二次推导。未发现重复安全包装/适配层，也无该抽未抽的抽象。

**偏离 1 处（判定：可接受的实现级适配，非返工项）**：蓝图写 `RequirementDomainValidator(JdbcTemplate)`，实际注入 `RequirementService.knownDomainIds`。原因是既有 L9（读路径单点）禁止白名单外直连 JdbcTemplate，而该查询必须按 tenant 全量读（按调用者域裁剪会把越权域误报成 400）。职责仍归 validator（`requireForCreate` 签名与蓝图一致），语义未变，且在 `lint.sh` 的 L9 白名单注释中登记了理由、另加自检探针防止白名单被放宽。判定：遵循既有架构约束优于蓝图签名，**不需架构返工**；内聚性改进（抽 `DomainLookup`）列入 P3-3。

## 6. 意见与遗留（均 P3，不阻断）

| 编号 | 问题 | 处置建议 |
|---|---|---|
| P3-1 | AC-11 措辞「回退原域、净 no-op」只对单域成立：多域需求 `PUT domainIds:[]` 会回退到主域、丢次级域（既有行为，本轮未改） | 按「维持现状」验收；后续要么保留全部原域，要么把 AC 措辞改为「单域净 no-op、多域回退主域」 |
| P3-2 | `platform-verify` 断言数在 167/168 间浮动（`:240-245` 既有条件断言：转派 200 才 pass，否则 warn），r22 起即存在 | 基线写「≥167 并注明该条件断言」，或把转派夹具做成确定性前置 |
| P3-3 | 域存在性查询挂在 `RequirementService`（内聚性次优，为过 L9 的合理取舍，代码已注释） | 下轮抽独立 `DomainLookup` 读服务 |
| P3-4 | QA 报告 §8 改动面写「5 脚本」，实际 6 个（含 `scripts/tests/lint.sh` 的 L9 注释与自检） | 提交说明按 `git status` 实际 17 文件登记；`lint.sh` 改动在 scripts 范围内且是收紧，不是豁免 |
| P3-5 | 本需求未产出 `DESIGN.md`（无独立 UI 设计阶段，视觉沿用 UI-STYLE） | AC-8 以 SSR 真渲染 + 静态断言覆盖；视觉走查列入人工补测（§7） |

## 7. 未覆盖项 / 人工补测（环境限制，非交付缺陷）

| 项 | 方法 | 判据 |
|---|---|---|
| 拆解禁用态视觉（灰化、hover 原因、不遮挡） | 真实浏览器打开 5173 需求详情 → 子需求 tab，SUBMITTED 与 POOLED BUS 各一次 | 禁用且原因可见；POOLED 可点 |
| 空态动作按钮 | 同上选无子需求 SUBMITTED BUS | EmptyState 动作禁用、同一句原因、点击无请求 |
| 屏幕阅读器播报原因行 | VoiceOver 读 `role=note` | 可读出 |
| 动效/布局美学 | 人眼 | 与 UI-STYLE 一致 |

原因：沙箱内 Chromium 无法启动（crashpad 需 `PROCESS_ALL_ACCESS`）；可脚本化部分已用真 SFC SSR + 静态断言替代。

## 8. 收尾

- 交付未提交（按 ADR-2026-08-27 由 host 收口）。提交须含 `runId=tf-mwisc832-uxfle0` 与任务夹路径，并把 `scripts/tests/lint.sh` 一并登记进改动面。
- 子需求与证据：`logs/teamflow/tf-mwisc832-uxfle0/`（`captures.json` 33 条、4 个 checker 脚本）随 run 归档，不入库。
- 本轮验收独立复跑 6 类检查全绿，未发现 AC 倒退、纸门、两套语义或门禁缩水。

验收结论：✅ 通过
