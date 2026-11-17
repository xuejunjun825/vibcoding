<!-- meta: summary="修复需求池列表 allowedActions 绕过权限与工时评估门、与详情不一致的契约缺陷，补齐列表/详情一致性断言与 r26 验收证据及基线订正" -->
基线依赖：docs/teamflow/20261117-r26-wbs-estimate-gate/（r26 的第五类单据与工时评估门是全量基线：六态派生、±20% 严格大于、409 三键 detail、判定顺序、动作行「渲染看权限、禁用看门」均不得回归）
取代：docs/teamflow/20261117-r26-wbs-estimate-gate/#AC-11：原 AC 只约束 PDR/RD **详情**的 allowedActions，本需求把同一约束扩展到池列表与搜索（列表/搜索/详情逐字符串相等），判定语义不变、不放宽

# r27 需求池列表 allowedActions 与详情同源

## 1. 背景与目标

**事实（本轮自验复现）**：`GET /api/req/requirements` 返回的每一行 `allowedActions` 直接取自状态机的类型/状态合法集，未经过调用者权限与 r26 工时评估门的过滤；而 `GET /api/req/requirements/{id}` 的 `view.allowedActions` 经过过滤。实测 PDR-0255（PENDING_ANALYSIS、无已通过评估）：

- 列表行：`allowedActions: ["schedule"]`
- 详情：`view.allowedActions: []`

同一需求、同一调用者、同一时刻，两个读接口给出互相矛盾的"接口会不会放行"。r26 §5.7 已把 `allowedActions` 定为"接口放行的唯一真相"（前端渲染不看它，但对外契约以它为准），列表这一路却是一个未被覆盖的旁路。

**根因**：`req-service/src/main/java/com/example/req/requirement/RequirementPoolQuery.java` 走 `ScopedJdbc` 行映射，行里既没有实体也没有 `reporter_id`，`toView(Map row, Rollup)` 末尾用 `stateMachine.allowedActions(type, status).stream().toList()` 直接产出视图。唯一判定谓词在 `RequirementService` 内（`allowedActions(type,status,reporterId)` + `gateFiltered(entity, actions)`），池查询没有复用它。

**影响**：现在前端动作条取的是详情接口，所以页面尚未出现"列表能点、点开没有"；一旦任何消费者（导出预览、批量操作、开放接口）按列表行渲染按钮，就会得到必然 403/409 的死按钮。r26 刚验收、分支尚未合回 main，正是修契约的时点。

**目标（可测）**：

1. 同一调用者、同一需求、同一时刻，列表行、搜索结果、详情三处的 `allowedActions` **原始数组逐字符串、逐顺序相等**（AC-1）。
2. 列表读路径不再有第二套判定，唯一判定源仍是 `RequirementService` 的同包入口（AC-2）。
3. 四条一致性/负向用例进入现有门禁并可重复运行（AC-3～AC-7）。
4. 200 行分页的 p95 不变差（≤修复前 1.1× 且 ≤1s），评估状态查询次数不随行数增长（AC-8）。
5. r26 的 `evidence/` 补齐 ≥3 张可判读截图，memory 的 r26 实测基线订正为复跑值（AC-11、AC-12）。

## 2. 用户故事

- **US-1 列表消费者（前端 / 集成方）**：我按列表行决定"能不能操作"时，拿到的动作集必须和详情一致，不能出现列表说能排期、详情却没有按钮。→ AC-1、AC-2、AC-7
- **US-2 业务人员 biz1（BUSINESS，无 `req:review`）**：我在需求池列表里不应看到任何评审类动作；但我自己提报、还在 SUBMITTED 的需求应能看到 `submit`。→ AC-3、AC-6
- **US-3 PDR/RD 负责人**：评估未通过时列表与详情都不给 `schedule`/`start_dev`；评估通过后两处都给；绕过页面直接调接口仍被 409 拦住且 `detail` 三键不变。→ AC-4、AC-5、AC-9
- **US-4 QA / 验收人**：我能用一条命令复跑四组一致性用例；把断言改松（排序 / 只看长度）或用例被"两侧都空"式修复糊弄时，用例必须变红；门禁数字只增不减。→ AC-7、AC-8、AC-10、AC-13
- **US-5 交付 / 演示者**：我能打开 r26 的 evidence 看到"未评估时按钮禁用+原因""审批中心待办""驳回原因在动态里"三张图，且 memory 里的基线数字与本轮实测一致。→ AC-11、AC-12、AC-14

## 3. 范围与非目标

**范围内**

- `req-service` 的池查询读路径：行集补齐 `reporter_id` 等判定所需字段，按页批量取评估状态，逐行调用 `RequirementService` 的同源判定。
- `scripts/platform-test-negative.sh` 新增 4d 段（四组一致性/负向断言 + 变异自检说明）。
- `scripts/platform-perf.sh` 或等价采样补 `size=200` 的 before/after 记录。
- r26 任务夹 `evidence/` 新增 3 张截图；`docs/teamflow/memory.md` 同主题行订正。
- 本任务夹 TECHNICAL / QA-REPORT / ACCEPTANCE。

**非目标**

- 不在列表行上新增动作按钮，不改前端信息架构（当前前端动作条只消费详情；本轮只修接口契约）。
- 不新增/调整权限码、动作码、角色、状态表、审批类型；16 动作矩阵与授权数据逐字不变。
- 不改导出 CSV 的列（导出不含 `allowedActions`），不动导出流式写逻辑。
- 不做跨请求缓存（Caffeine/Redis），不把动作集物化进列表 SQL 或视图。
- 不改 r26 的六态派生、±20% 阈值、409 三键、403→ILLEGAL_TRANSITION→既有门→评估门的判定顺序。
- 不修改 r26 既有文档内容（evidence 仅新增文件，见 §10 的显式例外）。

## 4. anti-goals / 反面目标

> 即使测试全绿，出现以下任一情况即视为本需求失败。

- **AG-1 「两侧都空」式一致**：把列表修成永远返回 `[]`，四条一致性用例因"列表=详情=空"全绿，但已通过评估的 PDR 列表里也没有 `schedule`，列表消费者失去入口。可观测：PDR 评估初审通过后，列表该行仍为 `[]`（AC-5 必须红）。
- **AG-2 只修 `/requirements`、漏 `/search`**：同一 id 在 `/requirements` 里已过滤，在 `/requirements/search` 里仍返回未过滤动作，用户一用搜索框按钮又冒出来。可观测：同一 id 在 page 与 search 两端的 `allowedActions` 不相等（AC-1 必须红）。
- **AG-3 断言口径太松**：用"长度相等"或"排序后相等"比较，一侧 `["schedule"]` 被换成 `["submit"]` 仍绿；"同源"从未被真正验证。可观测：一次变异自检（人为换顺序/换等价长度）用例不变红（AC-7 必须红）。
- **AG-4 为对齐引入 N+1**：逐行查权限或逐行查评估状态，`size=200` 时一次列表打 200+ 次 SQL，页面肉眼可见变慢。可观测：200 行 20 次采样 p95 超过修复前 1.1×（或超过 1s），或 SQL 计数随行数线性增长（AC-8 必须红）。
- **AG-5 证据不可判读**：截图截的是已评估需求（按钮可用）、或缩略到看不清文案、或只有卡片没有动态，验收人打开后无法独立确认"未评估时按钮禁用且写明原因"。可观测：把图交给未参与开发的人，他说不出当前评估单状态与原因文案（AC-11 抽检不通过）。

## 5. 交互流程摘要

1. **列表读路径（修复后）**：`GET /api/req/requirements` → 行查询（含 `id/type/status/reporter_id/estimate_days`）→ 请求内权限快照（每请求 1 次，`ActionPermissionResolver` 已 memo）→ 按本页 id **一次**批量查评估状态 → 逐行 `allowedActions = 状态机合法集 ∩ 授权（含 reporter 自服务规则）∩ 评估门`，顺序与详情一致。
2. **详情读路径（不变）**：`GET /api/req/requirements/{id}` → 同一 `allowedActions(type,status,reporterId)` + `gateFiltered(entity, actions)`。两条路径共用同一谓词，只是取数方式不同（单实体 vs 按页批量）。
3. **一致性可观测面**：`/requirements`、`/requirements/search`、`/requirements/{id}` 同 id 同数组；导出不含该字段，不在本轮。
4. **4d 门禁用例时序**：建 `GATE_PDR`（PENDING_ANALYSIS、无评估单）→ 比较列表与详情均为 `[]`；提交评估（PENDING）→ 仍 `[]`；pm 初审通过 → 两处均含 `schedule` 且完全相等；biz1 自建 BUS（SUBMITTED）→ 两处均含 `submit`；biz1 看自己的 PENDING_REVIEW 需求 → 两处均不含 `approve`/`review_*`。
5. **证据采集时序**：`platform-demo-reset` → 建一条无评估单的 PDR → admin 详情截图（按钮禁用+原因）→ 提交评估 → pm 审批中心待办截图（「工时评估」卡片）→ pm 带原因驳回 → 详情动态截图（原因原文 + REJECTED 卡片）→ 三张写入 r26 `evidence/`。

## 6. 优先级

| 级别 | 内容 | 判定 |
|---|---|---|
| P0 | AC-1～AC-10：同源契约、唯一判定源、四组用例、比较口径、性能、r26 不回归、门禁不缩水 | 不满足即不得合回 |
| P1 | AC-11、AC-12：r26 证据截图、memory 基线订正 | 交付完整性 |
| P2 | AC-13、AC-14：文档留痕、改动面与仓库卫生 | 收尾检查 |

## 7. 依赖与风险

**依赖**

- r26 的 `WbsEstimateService`（六态、`applyGate`、`statusOf`）与 `RequirementService` 的唯一谓词；本需求只复用，不改语义。
- 演示夹具（11 条 PDR/RD 均有 APPROVED 单）与 r26 的 `DemoReset`；4d 的未评估夹具须新建，不能指望种子里有。
- Playwright 1.58.2 已全局安装（r26 时"浏览器不可用"的前提已不成立）；演示栈可 `make platform-up` + `make platform-demo-reset` 拉起。

**风险与缓解**

- **R1 循环依赖**：`WbsEstimateService` 反向依赖 `RequirementService`，后者已用 `ObjectProvider` 断开；`RequirementPoolQuery` 注入 `RequirementService` 本身无环（Service 不依赖 PoolQuery），但不得让 PoolQuery 直接依赖 `WbsEstimateService` 再引第三处判定。缓解：只走 Service 同包入口。
- **R2 可见性调整**：`gateFiltered(entity, actions)` 现为 `private`。同包内复用需提升为 package-private（不改变外部 API、不新增公共面）。若评审不接受放宽可见性，改为在 Service 内提供 `Map<Long,List<String>>` 的批量重载，语义仍只有一份。
- **R3 评估状态 N+1**：`statusOf(entity)` 每题至少 2 次查询，200 行即 400+ 次。缓解：按页 id 批量取 active/effective 实例与当前人天，内存里逐 id 派生六态；单测断言"批量结果 == 逐条 `statusOf` 结果"（同夹具逐 id 相等）。
- **R4 自服务误伤**：行里缺 `reporter_id` 会把 biz1 自己的 `submit`/`withdraw` 也过滤掉，形成另一种"一致但错"。缓解：AC-6 强制覆盖；`reporter_id` 进 COLUMNS。
- **R5 顺序漂移**：批量组装/并发 map 可能打乱动作顺序，"同源"在集合意义上成立但数组不等。缓解：AC-7 要求原始数组（含顺序）比较，顺序以动作声明顺序为准。
- **R6 数字再次漂移**：memory 若抄旧报告而非复跑取数，下一轮继续对不上。缓解：AC-12 要求 QA 报告给出本轮命令与输出，memory 只写本轮实测。
- **R7 截图环境**：若演示栈起不来，截图无法伪造。缓解：把"栈不可用"作为显式阻塞上报，不以旧图/合成图充数。

## 8. 验收标准

**AC-1（P0）列表 / 搜索 / 详情同源、逐字符串相等**
同一调用者、同一需求 id：`GET /api/req/requirements` 行内 `allowedActions`、`GET /api/req/requirements/search` 行内 `allowedActions`、`GET /api/req/requirements/{id}` 的根 `allowedActions` 与 `.view.allowedActions`，五处**原始数组逐字符串、逐顺序相等**（`jq -c` 后字符串相等）。至少覆盖 PDR（未评估 / 已通过评估）、RD、BUS 各 1 条。

**AC-2（P0）唯一判定源、不得二次实现**
`RequirementPoolQuery.java` 中直接产出 `allowedActions` 的状态机调用为 0 处（`grep -n "stateMachine.allowedActions"` 无输出）；列表与详情都经 `RequirementService.allowedActions(type,status,reporterId)` + `gateFiltered(entity, actions)`。新增 ≥1 条单测：按页批量派生的评估状态与逐条 `statusOf` 对同一批 id 结果完全一致。

**AC-3（P0）biz1 无 `req:review`：列表不出现评审动作**
biz1 登录，按唯一标题关键字过滤自己的 PENDING_REVIEW 需求：该行 `allowedActions` 不含 `approve`，且不含任何 `review_` 前缀动作；详情同样不含；两侧数组完全相等（允许出现 `withdraw` 等自服务动作，不做"必须为空"的过度断言）。

**AC-4（P0）未过评估门：列表与详情都为空**
`GATE_PDR`（PENDING_ANALYSIS、无评估单）列表行与详情 `allowedActions` 均为 `[]`（长度 0 且原始数组相等）。

**AC-5（P0）过门后：列表与详情都含 `schedule`**
同一 `GATE_PDR` 提交评估并由 pm 初审通过后，列表行与详情均含 `"schedule"`，且两侧数组完全相等；不得出现"列表有、详情没有"或反之。

**AC-6（P0）自服务不误伤：`submit` 仍在**
biz1 自建 BUS（停在 SUBMITTED）列表行与详情均含 `"submit"`，两侧数组完全相等。

**AC-7（P0）比较口径与变异自检**
上述一致性断言一律用 `jq -c` 取原始数组做字符串相等（含顺序），禁止只用 `length > 0` / `contains` / `index` 做存在性判断，禁止排序后比较。QA 报告记录一次变异自检：把断言改成"排序后相等"或把一侧动作顺序互换，必须至少有 1 条用例变红；改回后复绿。

**AC-8（P0）200 行分页性能不变差**
`GET /api/req/requirements?page=1&size=200&status=POOLED` 连续 20 次采样：p95 ≤ 修复前同条件 p95 × 1.1，且 ≤1000ms；该请求的评估状态查询次数为常数（与 200 行无关，≤2 次），不得随行数线性增长。`make platform-perf` 既有 req-list 项仍绿。

**AC-9（P0）r26 判定语义零回归**
`make platform-test-negative` 的 4c 段全部保持绿：六态（NONE/PENDING/REJECTED/SUPERSEDED/STALE）一律拦、通过才放行、`10→12` 放行 / `10→12.5` 拦、409 `detail` 三键（`requirementId/status/required`）、403→ILLEGAL_TRANSITION→既有门→评估门的顺序不变；`make platform-verify` ≥192。

**AC-10（P0）门禁不缩水**
verify ≥192、negative ≥111、access ≥206、rbac 113、demo-reset 69、lint 22、unit ≥367、typecheck/build 绿。新增断言后 negative/access 的实测只增不减，QA 报告给出 before/after 数字；不删除、不弱化任何既有断言。

**AC-11（P1）r26 evidence ≥3 张可判读截图**
在 `docs/teamflow/20261117-r26-wbs-estimate-gate/evidence/` 新增：
① `estimate-gate-pdr-disabled.png`——未评估 PDR 详情，`schedule` 按钮存在且 `disabled`，同时可见"需先通过工时评估（当前评估单：无）"；
② `estimate-todo-approval-center.png`——pm 审批中心待办含「工时评估」卡片（`bizType=WBS_ESTIMATE`）；
③ `estimate-reject-reason.png`——驳回后需求动态含驳回原因原文，评估卡片为 REJECTED。
每张 ≥1280×720、文字可读、场景与登录身份自洽；r27 的 ACCEPTANCE.md 逐张引用。

**AC-12（P1）memory 基线订正 + 同源约定**
`docs/teamflow/memory.md` 的 r26 实测值订正为：`platform-verify 192`、`platform-test-negative 111`、`access 206`、`rbac 113`、`platform-demo-reset 69`；同段"r26 起门禁下限"同步为 verify ≥192、negative ≥111、access ≥206、rbac 113、demo-reset ≥69，保留"下限只增不减"表述。若本轮新增断言抬高实测，下限写最终实测并注明 r27。同时在 r26 约定行补一句"列表/搜索/详情 `allowedActions` 同源，禁止在池查询二次实现"。两处均为**同主题行替换**，不新增 changelog 行。

**AC-13（P2）技术留痕**
TECHNICAL.md 写明批量取数方案（每页几次查询、复杂度）、AC-2 的静态检查点、性能前后数字；QA-REPORT 记录四组用例的命令与输出摘要、变异自检、门禁 before/after；ACCEPTANCE 链接 evidence 并给出提交哈希。

**AC-14（P2）改动面与仓库卫生**
`pom.xml`、`web/package.json` diff 为空；未新增第三方依赖；改动限于 `req-service`（池查询/服务同包入口）、`scripts`（negative/perf 或 access）、`docs/teamflow/`；`git status` 无 `target/`、无临时截图、无日志文件。

## 9. 假设与待澄清

- **F1（fact）「169」与「69」冲突**：澄清句写 `169/192/111/206/113`，但同段需求写 `demo-reset 69`，且 169 不对应任何门禁的量级。**假设**：169 是 69 的笔误，memory 写 demo-reset 69。**若你确认是 169**：AC-12 与下限改 169，AC-10 的 `≥69` 相应提高，QA 需给出 169 的来源命令。
- **T1（taste）一致性断言落点**：放 `platform-test-negative.sh` 新增 4d 段，还是 `scripts/tests/access/` 新段？**推荐默认：negative 4d**——4b/4c 已建好 `GATE_PDR`/`GATE_RD` 与 biz1/pm 登录，接口负向也属 negative 语义，代价最小。**若放 access**：negative 总数不变、access 增加，AC-10 的 before/after 归属与 4d 段编号需调整。
- **T2（taste）"同源"的严格度**：原始数组含顺序相等，还是排序后集合相等？**推荐默认：含顺序**——同一谓词 + 同一动作声明顺序，顺序漂移本身就是契约缺陷；排序比较会放过它。**若只要集合相等**：AC-1/AC-7 放宽为 `sort` 后比较，误报更少，但"列表与详情由同一判定产出"的证明力下降。
- **T3（taste）性能门槛**：双门槛（≤修复前 1.1× **且** ≤1s）还是只看绝对 ≤1s？**推荐默认：双门槛**——绝对 1s 对 10 万行规模太宽，修复前若已 600ms，2× 退化仍会绿。**若只看 1s**：AC-8 去掉相对门槛，采样记录仍保留。

## 10. 工程约束

- **分支**：在 `feat/wbs-estimate-approval` 上继续开发（该分支领先 main 1 个提交 `0d91715`，即 r26）；不新建分支、不切回 main、不合回 main。
- **未提交改动**：开工前唯一未提交项是 `?? docs/teamflow/20261117-r27-allowed-actions-parity/`（本任务夹），保留、不 stash、不丢弃；其余改动由本需求产生。
- **提交**：完成后一次提交，message 必须写清 `runId=tf-mwk3086h-ojufgx` 与任务夹 `docs/teamflow/20261117-r27-allowed-actions-parity/`；不 amend/rebase r26 的 `0d91715`，不 force push。
- **依赖**：不改 `pom.xml` 与 `web/package.json`，不新增任何第三方依赖。
- **写盘边界**：只写 `docs/teamflow/` 与 `logs/teamflow/`；r26 任务夹只**新增** `evidence/` 下的截图文件，不改其既有文档（任务夹不可变原则的显式例外，由本需求授权，且仅限新增 evidence）。
- **.gitignore 审计（开发阶段动作）**：确认覆盖 `target/`、`node_modules/`、`dist/`、`.vite/`、`coverage/`、`*.tsbuildinfo`、`.env`/`.env.*`、`.DS_Store`、`.idea/`、`.vscode/`、`*.log`、`logs/teamflow/`、`.nacos/`、`.pnpm-store/`；**不得**用 `*.png` 一类通配把 evidence 截图忽略掉；截图脚本的临时产物写 `logs/teamflow/<runId>/` 且不入库。
- **每步先编译**：每个任务开始先 `mvn -o -DskipTests compile`，再改代码/脚本。
- **门禁命令（逐条跑，数字进 QA 报告）**：`make lint`、`make test`（含 unit）、`make platform-verify`、`make platform-test-negative`、`make access-verify`、`make rbac-verify`、`make platform-demo-reset`、`make platform-perf`。
- **禁止**：删除/弱化既有断言凑数；在 `RequirementPoolQuery` 里写第二套权限或评估门判定；用 `sleep` 掩盖未就绪；伪造截图或抄旧报告数字。

## 11. 里程碑与任务拆分建议

串行 4 步（≤4，任务边界按文件所有权互斥）：

1. **T1 服务端同源（0.5d）**：`RequirementPoolQuery` 行集补 `reporter_id`、按页批量取评估状态、逐行走 `RequirementService` 同包入口；`gateFiltered` 可见性调整或批量重载；新增单测。出口：AC-1、AC-2、AC-4～AC-6 手工 curl 可见，`mvn -o -DskipTests compile` 绿。
2. **T2 4d 断言（0.5d）**：`platform-test-negative.sh` 新增 4d 段四组用例 + 变异自检；重复运行幂等、失败不留脏数据。出口：AC-3～AC-7 绿。
3. **T3 性能与证据（0.5d）**：`size=200` before/after 采样；Playwright 采集三张截图并写入 r26 `evidence/`。出口：AC-8、AC-11。
4. **T4 全量门禁与交付（0.5d）**：跑齐 §10 全部命令；订正 memory；写 TECHNICAL/QA-REPORT/ACCEPTANCE；提交（含 runId 与任务夹）。出口：AC-9、AC-10、AC-12～AC-14。
