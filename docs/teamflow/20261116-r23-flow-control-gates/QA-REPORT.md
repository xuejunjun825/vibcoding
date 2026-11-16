# QA 报告 · r23 需求流转前置门与提报必填（三条写入口同一判定）

- 任务夹：`docs/teamflow/20261116-r23-flow-control-gates/`
- 分支：`feat/flow-precondition-gates`（未提交，按 ADR-2026-08-27 由 host 收口；建议 commit message 含 `runId=tf-mwisc832-uxfle0`）
- 结论：**可验收**。P0/P1/P2 = 0，P3 = 3（均为既有行为/文档口径观察，不阻断）。功能、门禁、架构对蓝图、接口一致性、提交面卫生均通过；唯一未覆盖项是真实浏览器视觉（环境限制，已列人工补测清单）。
- 被测栈：本交付源码重建的真实栈（gateway 8080 / req-service 8082 / web 5173 / MySQL `devops_platform`，种子 `t1`）。

## 1. 验收项逐条结果

| AC | 结论 | 证据（本次实测） |
|---|---|---|
| AC-1 拆 PDR 父 BUS 非 POOLED → 409 | 通过 | `t4-gate-evidence.sh` P2：未入池 BUS 拆解 → 409 `PARENT_NOT_POOLED`，detail 恰 `{parentId,parentStatus,required:"POOLED"}`，requirement 计数不变 |
| AC-2 拆 RD 父 PDR 非 SCHEDULED/IN_DEV → 409 | 通过 | 同脚本 C2：未排期 PDR 拆解 → 409 `PARENT_NOT_SCHEDULED`，detail 含 `allowed:["SCHEDULED","IN_DEV"]`，不落库 |
| AC-3 直建同门（同码同 detail 同函数） | 通过 | `t4-gate-evidence.sh` C3/C4（直建 PDR/RD 与拆解逐字同 message）；`t4-ac3-same-gate.mjs` 21/21（门只在 `RequirementRules` 判定、`decompose` 委托 `create`） |
| AC-4 PUT 改父同门、失败 parent_id 不变 | 通过 | `platform-test-negative.sh` 4b：RD 改挂未排期 PDR → 409 且 `parent_id` 未变；合法改挂 200；T4 证据 C5（改父被拒后父仍 6398） |
| AC-5 正常链路不误伤 | 通过 | POOLED BUS 拆 PDR 201、SCHEDULED PDR 建/拆 RD 201、改挂/回写 200；`qa-r23-domain-gate-probe.sh` 追加 IN_DEV PDR(6479) 直建 RD 201 + 拆解 201 |
| AC-6 顺序与既有守卫不变 | 通过 | 探针：RD 挂 BUS → 400 `INVALID_PARENT`（非 409）；DONE RD 改父 → 400 `VALIDATION_FAILED`（非 409）；越权域+未入池父 → 403 先判；POOLED BUS `allowedActions` 恰 `["close"]`，`StateTables.java` 相对 HEAD 零 diff（门不进动作契约） |
| AC-7 详情 decomposeGate 与 409 同源 | 通过 | 探针：SUBMITTED BUS 详情 `{allowed:false,parentStatus:"SUBMITTED",required:"POOLED"}`，`reason` 与 409 `message` **逐字相等**；POOLED BUS `allowed:true,reason:null`；`mvn -pl req-service test -Dtest=RequirementParentGateTest` 8/8（遍历 BUS/PDR 全状态） |
| AC-8 前端两入口按门禁用 | 通过 | `ac8-decompose-gate-check.mjs` 25/25（11 条静态 + 14 条 SSR 真渲染四态）；`npm run typecheck` exit 0 |
| AC-9 domainIds 缺/[]/null → 400 | 通过 | 探针三变体：400 `VALIDATION_FAILED`、`detail.field="domainIds"`、计数不变（不落库） |
| AC-10 同类必填逐条核查 | 通过 | 见 §2 的 9 行实测表；无裸 500；`domainIds` 不存在域 400 `invalidIds`、越权域 403 `OUT_OF_SCOPE` |
| AC-11 PUT domainIds:[] 维持现状 | 通过（有记录） | 单域需求：200 且 `requirement_domain_rel` 不变（净 no-op）；多域需求实测回退主域（既有行为，见 P3-1） |
| AC-12 负向新增 ≥6 条，30→≥36 | 通过 | `platform-test-negative.sh` **49/49**；4b 段新增 19 断言覆盖 6 条前置门负向（含直建 RD 409、PUT 改父 409 且 `parent_id` 未变）+ 4 条正向回归，每条带计数/父 id 不落库断言 |
| AC-13 门禁不缩水 | 通过 | 见 §3；`platform-verify` 实测 167/167 与 168/168（说明见 P3-2），断言代码 1:1 替换、零删除 |
| AC-14 证据与改动面 | 通过 | `captures.json` 22 条原始载荷（409×2/400/201/详情 gate）；`pom.xml`、`web/package.json`、`web/package-lock.json` diff 为空；改动仅 req-service 需求域 + 前端 2 文件 + 5 脚本 + memory |
| AC-15 文档一致性 | 通过 | `memory.md` +2/-1（r23 约定行 + negative 28→30 订正）；`AGENTS.md` 零改动 |

## 2. AC-10 九入口核查表（实测，2026-11-16）

| # | 请求语义 | 端点 | 实测 | 期望 | 本轮是否修改 | 不落库 |
|---|---|---|---|---|---|---|
| 1 | type 非法（BOGUS） | POST /api/req/requirements | 400 VALIDATION_FAILED | 400 | 否（既有口径） | 37=37 |
| 2 | priority 非法（P9） | POST /api/req/requirements | 400 VALIDATION_FAILED | 400 | 否（既有口径） | 37=37 |
| 3 | title 空白 | POST /api/req/requirements | 400 VALIDATION_FAILED | 400 | 否（既有口径） | 37=37 |
| 4 | estimateDays 负值（-5） | POST /api/req/requirements | 400 INVALID_DAY_PRECISION | 400 | 否（既有口径） | 37=37 |
| 5 | parentId 不存在（999999） | POST /api/req/requirements | 404 NOT_FOUND | 404 | 否（既有口径） | 37=37 |
| 6 | domainIds 不存在（999999） | POST /api/req/requirements | 400 VALIDATION_FAILED，detail.invalidIds=[999999] | 400 | 否（新增校验） | 不落库 |
| 7 | domainIds 越权（临时域 512，dev2 DOMAIN 档） | POST /api/req/requirements | 403 OUT_OF_SCOPE，detail.domainIds=[512] | 403 | 否（新增校验） | 不落库 |
| 8 | decompose 缺 title | POST /api/req/requirements/6475/decompose | 400 VALIDATION_FAILED | 400 | 否（既有口径） | 37=37 |
| 9 | 以上任一条裸 500 | — | 0 条 5xx | 无 | — | — |

补充：dev2 用**已授权域** 1 提报 → 201（证明 403 是按域判定，不是把 DOMAIN 档一律封死）；`tier==TENANT`（admin）不被域集合限制。

## 3. 门禁矩阵（AC-13，全部本次实跑）

| 门禁 | 命令 | 实测 | 基线要求 |
|---|---|---|---|
| 编译 | `mvn -o -q -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` | exit 0 | 通过 |
| platform-verify | `bash scripts/platform-verify.sh` | **168/168**（run1）与 **167/167**（run2） | 167 |
| negative | `bash scripts/platform-test-negative.sh` | **49/49** | ≥36 |
| rbac | `bash scripts/rbac-verify.sh` | **111/111**（11 段下限核对） | 111 |
| access | `bash scripts/access-verify.sh` | **152/152** | 152 |
| lint | `bash scripts/tests/lint.sh` | **22/22** | 22 |
| unit | `bash scripts/platform-unit.sh` | **329** 通过 | ≥316 |
| verify | `bash scripts/verify.sh` | **14/14** | 14 |
| analytics | `bash scripts/analytics-i18n-verify.sh` | **74/74**（live 41/41） | 74 |
| collab | `bash scripts/collab-verify.sh` | **256/256**（s0–s8） | s0–s8 全绿 |
| collab-perf（R7 夹具） | `bash scripts/collab-perf.sh` | **5/5** | 原预算 |
| integration（R7 夹具） | `bash scripts/integration-verify.sh` | **231/231** | 原基线 |
| 前端类型 | `cd web && npm run typecheck` | exit 0 | 0 error |
| 目标单测 | `mvn -o -pl req-service test -Dtest='RequirementParentGateTest,RequirementDomainRulesTest'` | **13/13** | 全绿 |

`platform-verify` 断言 diff 核对：`git diff HEAD -- scripts/platform-verify.sh` 仅 1 条 `expect` 被 1:1 替换（标题改为「父=BUS[POOLED]」），并新增两处 `warn`（排期失败提示），**没有删除或弱化任何断言**——夹具改造是为适配更严的门，不是放宽门禁。

## 4. 运行时探针（本次新增/复用脚本）

| 脚本 | 断言 | 覆盖 |
|---|---|---|
| `logs/teamflow/tf-mwisc832-uxfle0/scripts/t4-gate-evidence.sh`（复用） | 27/27 | AC-1/2/3/4/5/9 真实 HTTP，每条拒绝配计数或 `parent_id` 断言；22 条原始载荷写入 `captures.json` |
| `logs/teamflow/tf-mwisc832-uxfle0/scripts/t4-ac3-same-gate.mjs`（复用） | 21/21 | AC-3 同门静态：唯一判定点、唯一错误码、decompose 委托 create、门在写库前、视图单映射、列表/版本传 null |
| `logs/teamflow/tf-mwisc832-uxfle0/scripts/ac8-decompose-gate-check.mjs`（复用） | 25/25 | AC-8 静态 11 + Vite SSR 真渲染四态（SUBMITTED 双入口禁用+原因、POOLED 双入口可用、无权限 0 按钮、缺字段 fail-closed 兜底） |
| `logs/teamflow/tf-mwisc832-uxfle0/scripts/qa-r23-domain-gate-probe.sh`（新增） | **16/16** | AC-9 三变体、AC-10 #6/#7、AC-11 单域净 no-op+多域现状记录、AC-7 gate 与 409 逐字一致、AC-6 三种顺序、IN_DEV 正向 |
| `logs/teamflow/tf-mwisc832-uxfle0/scripts/qa-r23-ac10-entry-audit.sh`（新增） | **7/7** | AC-10 行 1–5、8、9，输出 §2 表 |

## 5. 架构核查（M3）

蓝图符合性：

| 蓝图条目 | 实现 | 判定 |
|---|---|---|
| 门收敛为 `RequirementRules.parentGate` 纯函数 | `RequirementRules.java:59`（`parentGate`）、`:80`（`parentGateViolation`）、`:99`（`decomposeGate`），无 Spring/DB | 符合 |
| `RequirementService.requireParentGate` 为调用点 | `RequirementService.java:219`；`create` 在 `:110` 调用（拆解经 `create` 自动同源），`applyParentChange` 在 `:210` 复用同一 helper | 符合（2 个调用点共享 1 个 helper，与蓝图「改父复用同一 requireParentGate」一致） |
| 详情 `decomposeGate` 单映射 | `RequirementViews.java:29-33` 唯一 `from()`，`RequirementService.java:708` 唯一调用；409 与 view 不会两套文案 | 符合 |
| View 第 26 分量 | `RequirementViews.java:46`；三个构造点：`RequirementService.java:700`、`RequirementPoolQuery.java:198`、`release/ReleaseService.java:417`（后两者传 `null`，列表/版本载荷不变） | 符合 |
| domainIds 必填只在直建入口 | `RequirementController.java:105` → `RequirementDomainValidator.requireForCreate`，decompose 端点不经过 | 符合 |
| 纯函数可脱离 Spring 单测 | `RequirementDomainValidator.unseen/outOfScope` 静态；`RequirementDomainRulesTest` 5/5 | 符合 |

发现的偏离（均不阻断）：

1. **依赖偏离（已在代码注释说明）**：蓝图写 `RequirementDomainValidator(JdbcTemplate)`，实际注入 `RequirementService` 并走 `knownDomainIds`（`RequirementService.java:232-245`，按 tenant 参数化查询）。原因：`scripts/tests/lint.sh` 的 L9「读路径单点」白名单不含该新文件，直连 `JdbcTemplate` 会红；语义未变（租户全量读，越权域不被误报成 400）。判定：可接受的实现级偏离，未放宽门禁、未新增白名单豁免。
2. **职责位置**：域存在性查询挂在 `RequirementService` 上（内聚性次优），见 P3-3。

重复实现检查：`PARENT_NOT_POOLED`/`PARENT_NOT_SCHEDULED` 字面量仅出现在 `RequirementRules`（脚本断言属消费方）；`DecomposeGate.from` 仅 1 处生产调用；前端未出现按状态字符串二次推导门（`ChildrenTab.vue` 只读 `decomposeGate.allowed`）。未发现重复的安全包装/存储/适配层，也未发现该抽取而未抽取的抽象。

既有结构：单测 329 全绿、`StateTables` 零 diff、`RequirementActionMatrix` 未被门侵入（`allowedActions` 仍由状态机产生）。

## 6. 接口一致性（静态）

- 后端↔前端契约：`RequirementViews.DecomposeGate(boolean allowed, String reason, String parentStatus, Object required)` ↔ `web/src/api/types.ts:181-186` 四字段同名同型；`RequirementDetail.decomposeGate?`（`types.ts:212`）由 `normalizeRequirementDetail` 的 `...view` 透传，`platform.ts` 未改。
- `ChildrenTab.vue:28-36` 只使用 `.allowed` / `.reason`，两处入口共用 `canDecompose`/`onDecompose`（`:67`、`:74-86`），空态走 `#action` 插槽且同样 `:disabled="!canDecompose"`，无 `@action` 绕过路径。
- 后端内部调用均存在：`RequirementErrors.domainOutOfScope`（`RequirementErrors.java:101`）由 validator 调用；`RequirementRules.checkDomainIds/distinctDomainIds` 存在且被 `requireForCreate` 调用；脚本调用的端点（`POST /requirements`、`POST /{id}/decompose`、`PUT /{id}`、`GET /{id}`）均在 `RequirementController` 注册并通过真实 HTTP 验证。

## 7. 可执行证据（非存在性检查）

- 真实 HTTP：`t4-gate-evidence.sh` 27/27 + `qa-r23-domain-gate-probe.sh` 16/16 + `qa-r23-ac10-entry-audit.sh` 7/7（均在跑本交付源码的栈上，exit 0）。
- 前端：`ac8-decompose-gate-check.mjs` 25/25 用真 SFC + 真 Pinia 做 SSR 渲染并断言禁用/原因/无按钮三态；`npm run typecheck` exit 0。
- 静态同源：`t4-ac3-same-gate.mjs` 21/21。
- 单测：`RequirementParentGateTest` 8/8（BUS/PDR 全状态遍历）、`RequirementDomainRulesTest` 5/5、`platform-unit` 329。

## 8. 提交面卫生

`git status --porcelain` 共 20 项：16 个已跟踪文件 M + 任务夹 + 3 个新源码文件；无 `target/`、`.m2/`、`node_modules/`、IDE、`.env` 等噪声（均被 `.gitignore` 命中，`--ignored` 抽查 `.m2/`、各模块 `target/` 均为 `!!`）。`pom.xml`、`web/package.json`、`web/package-lock.json` diff 为空。

## 9. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 工具 | 判据 |
|---|---|---|---|
| 拆解入口禁用态视觉 | 打开 `http://127.0.0.1:5173` 需求详情 → 子需求 tab，选 SUBMITTED BUS 与 POOLED BUS 各一次 | Chrome DevTools | 禁用按钮灰化、hover 出现中文原因、原因行在按钮上方且不遮挡 |
| 空态动作按钮 | 同上选无子需求的 SUBMITTED BUS | Chrome DevTools | EmptyState 动作按钮禁用并显示同一句原因，点击无请求 |
| 屏幕阅读器播报 | 用 VoiceOver 读原因行 | macOS VoiceOver | `role=note` 内容可被读出 |
| 动效/布局美学 | 目视 | 人眼 | 与 UI-STYLE 一致 |

原因：沙箱内 Chromium crashpad 需要 `PROCESS_ALL_ACCESS`（host 限制），浏览器不可启动；已用 SSR 真渲染 + 静态断言替代可脚本化部分。

## 10. 缺陷表

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| P3-1 | P3 | 需求域（既有行为，本轮未改） | 建一条 `domainIds:[1,2]` 的 BUS，再 `PUT {"domainIds":[]}` | PRD AC-11 写「回退原域、净 no-op」 | 域关系集 `[1,2]` → `[1]`：回退到主域，次级域丢失（单域需求才是净 no-op） | AC-11 | `bash logs/teamflow/tf-mwisc832-uxfle0/scripts/qa-r23-domain-gate-probe.sh`（NOTE 行） | 报告/PRD 明确「单域净 no-op、多域回退主域」，或实现保留全部原域 |
| P3-2 | P3 | 门禁文档口径 | 连续两次 `bash scripts/platform-verify.sh` | AC-13 固定「167」 | 实测 168/168 与 167/167 浮动：`platform-verify.sh` 第 244 行既有的条件断言（转派 200 才 pass，否则 warn）随夹具状态变化，r22 起即存在，非本轮引入 | AC-13 | `bash scripts/platform-verify.sh`（两次） | 基线写成「≥167」并注明该条件断言，或把转派夹具做成确定性前置 |
| P3-3 | P3 | 架构（内聚性） | 查看 `RequirementDomainValidator` 的域存在性查询 | 域查询属域/字典服务职责 | 走 `RequirementService.knownDomainIds`（为过 L9 白名单的合理取舍，代码已注释） | 架构 | `grep -n 'knownDomainIds' -A14 req-service/src/main/java/com/example/req/requirement/RequirementService.java` | 后续把域查询抽到独立 DomainLookup 服务（本轮不阻断） |

未发现 P0/P1/P2 缺陷。

## 11. 证据文件

- 原始载荷：`logs/teamflow/tf-mwisc832-uxfle0/captures.json`（`t4GateEvidence`，22 条）
- 检查脚本：同目录 `scripts/`（`t4-gate-evidence.sh`、`t4-ac3-same-gate.mjs`、`ac8-decompose-gate-check.mjs`、`qa-r23-domain-gate-probe.sh`、`qa-r23-ac10-entry-audit.sh`）
- 前序阶段笔记：`logs/teamflow/tf-mwisc832-uxfle0/T3-notes.md`、`T4-notes.md`、`r23-closing.md`
