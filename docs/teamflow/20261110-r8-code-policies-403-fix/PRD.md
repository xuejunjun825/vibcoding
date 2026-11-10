<!-- meta: summary="修复分支策略页加载时「GitLab 集成探测」403 触发全局跳转：有 CCODE_POLICY_VIEW 的角色不再被踢出页面，探测失败降级为页面内三态提示，并新增 a5 段把「可选能力探测未标 silentForbidden」变成门禁可拦的错误" -->
基线依赖：docs/teamflow/20261110-r7-role-page-access-fix（其 403 全局跳转语义、silentForbidden 机制、导航准入、字典收敛、demo-reset 行为均不得回归）
取代：docs/teamflow/20261110-r7-role-page-access-fix#AC-5：页面可用性证据口径由「首屏主端点状态码 200」升级为「主端点 + 页面内可选探测调用语义」——/code-policies 一行的判定以本夹 AC-1/AC-2 为准；a2 既有的 dev1=200 断言保留且不减少，但单独通过它不再算该页可用。

# 分支策略页（/code-policies）非管理员 403 修复 · PRD

## 0. 一页结论

r7 修复轮（提交 `1a61e90`）已把需求池、版本计划、审批中心、测试计划四页与字典、导航修好；`/code-policies` 是同一族最后一处残留：有 `CCODE_POLICY_VIEW` 的 dev1/tl1/vendor1/admin 打开页面即被整体跳去 `/403?from=%2Fcode-policies&code=FORBIDDEN`。

已定位的唯一失败请求：`GET /api/req/integration/configs?system=GITLAB`。服务端按 `integration:manage` 拦截（`platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java:59` 的 `^/integration/(configs|logs|admission-items)(/.*)?$` 规则）。调用点在 `web/src/views/code/CodePolicyView.vue:62` 的 `loadGitlabConfig()`：代码本身写了 `try/catch` 降级（注释「读不到配置不阻断列表」），但**没有声明 `silentForbidden`**，于是 `web/src/api/client.ts:180` 的 `if (!options.silentForbidden) { navigate('/403?...') }` 在抛错之前已经执行——页面里的 catch 永远救不回来。

本轮交付的语义只有一句：**「可选能力探测」的 403 只能降级为页面内状态，不能升级为整页导航；降级后要告诉用户真实原因，而不是一句无解释的黄标签。**

## 1. 背景与目标

上一轮把「全局 403 跳转」的默认语义保留了下来（这是对的：无权限深链必须落 `/403`），只对**页面主动声明为可选**的探测请求开了口子（`silentForbidden`）。本次残留说明：口子靠调用方自觉，漏一处就整页不可用。

目标（可检验）：

1. 有 `CCODE_POLICY_VIEW` 的 4 个账号（admin/dev1/tl1/vendor1）打开 `/code-policies` 后停留在该页，策略列表照常渲染。
2. 探测读不到配置（无 `integration:manage`）或 GitLab 未启用时，页面给出**可读且不误导**的说明，行内核验态保持「未核验」，点「核验」不产生核验请求。
3. 无 `CCODE_POLICY_VIEW` 的账号（pm/po1/qa1）仍按路由守卫落 `/403?code=CCODE_POLICY_VIEW`，侧栏不出现入口——现状正确，不得被「顺手关掉 403」破坏。
4. 把这类缺陷变成门禁可拦的错误：新增 `access-verify` 的 a5 段（运行时角色矩阵 + 调用点静态审计 + 审计器自检），而不是等下一次人工复现。
5. 既有门禁一条不红。

## 2. 用户故事（每条带可测验收点）

**US-1（dev1，开发者，只有 VIEW 无 EDIT）** 想确认本域仓库的合并策略是否符合要求。
- 打开 `/code-policies` 后 URL 仍为 `/code-policies`，表格渲染（有数据时 ≥1 行），没有整页错误态。
- 页面顶部有一句常驻说明：当前账号无法读取 GitLab 集成配置（需 `integration:manage`），核验结果将保持「未核验」，需要核验请联系集成管理员。
- 「核验」按钮保持既有 `disabled` 样式（`CodePolicyView.vue:563` 的 `:disabled="!canEdit"`），不新增可点入口、不新增弹窗。
- 刷新/切筛选/换域不再出现整页 403。

**US-2（tl1 / vendor1，只读）** 行为同 US-1；vendor1 的域裁剪结果不因本修复改变（仍只见本厂商行）。

**US-3（admin，管理员）** GitLab 已启用时打开该页：无黄条，「核验」可点，核验链路与今天一致；未启用时看到「GitLab 集成未启用」而不是「权限不足」，并能一键去集成配置。

**US-4（pm / po1 / qa1，无 `CCODE_POLICY_VIEW`）** 深链 `/code-policies` 被路由守卫送到 `/403?code=CCODE_POLICY_VIEW`，侧栏不出现入口。

**US-5（QA，回归者）** 跑 `make access-verify` 时，若有人把探测调用的 `silentForbidden` 删掉，a5 立刻红并指出文件:行；若有人把全局 403 跳转关掉，a5 与 a2 同时红。

## 3. 范围与非目标

**范围内**
- `CodePolicyView` 的集成配置探测调用语义 + 页面级三态提示（ENABLED / DISABLED / UNREADABLE）。
- 全仓同类排查：`web/src` 中映射到 `*:manage` 路由规则的**读端点**，被非管理页当「可选能力」调用却未声明 `silentForbidden` 的一律补齐（预扫描结果见 AC-4 表，已知遗漏仅 1 处）。
- 新增 `scripts/tests/access/a5-*.mjs`，登记进 `scripts/access-verify.sh` 的段清单/分发/计数汇总/总数守卫。
- `docs/teamflow/DEMO-BY-ROLE.md` 的 dev1 行补 `/code-policies` 实测状态；截图存 `docs/teamflow/demo-shots/dev1-code-policies-fixed.png`。

**非目标**
- 不放宽后端权限：不给 `integration:manage` 加「登录可读」例外，不把 `integration/configs` 变成字典类端点。
- 不重构 403 拦截器、不改 `client.ts` 默认语义、不给 `http.get` 全局加 `silentForbidden`。
- 不做分支策略功能增强（冲突合并、批量核验、GitLab 写回），不改核验判定口径与服务端状态机。
- 不改表结构、不新增权限码、不动 Flyway、不改接口契约。

## 4. 反面目标（anti-goals）

以下任一发生，即使门禁全绿也应判本轮失败：

1. **列表被探测失败拖垮**：dev1 看到的是整页错误态或「暂无数据」，一行策略都没有——把可选探测的失败当成了页面失败。
2. **文案把权限问题说成配置问题**：dev1 看到「GitLab 未配置/未启用」，去催管理员配置，而管理员那边配置一直是启用状态；一次排查被误导一个来回。（可判定：UNREADABLE 态渲染出的文案里出现「未配置/未启用/尚未接入/请配置」这类断言，即失败。）
3. **用关小全局 403 换绿灯**：为了让这页不跳，把 `client.ts` 默认从「跳转」改成「不跳」，或给 `http.get` 全局加 `silentForbidden`。可观察：pm 深链 `/code-policies` 不再落 `/403`，而是停在半渲染页面。
4. **门禁只认字符串**：a5 只要文件里出现 `silentForbidden` 字样就判过（注释里写一个、别的调用带一个都算），把真实调用的参数删掉后门禁仍然绿。
5. **提示成为噪音或指错方向**：无论集成是否可用都显示同一句黄条；或把平台权限问题写成「需 GitLab Maintainer 及以上」（`POLICY_REASON_LABEL.PERMISSION_INSUFFICIENT` 现有文案的语义是 GitLab 侧角色，复用即误导）。

## 5. 交互流程摘要

页面加载并行三路：域字典（已 `silentForbidden`）→ 策略列表 → GitLab 配置探测（本轮补语义）。探测结果只决定**提示条**与**核验入口的可用性**，不阻塞、不清空列表。

| 探测结果 | 判定 | 页面表现 | 点「核验」 |
|---|---|---|---|
| ENABLED | 读到 `enabled=true` 的 GITLAB 配置 | 无黄条；有 `CCODE_POLICY_EDIT` 时可点 | 正常发请求（行为不变） |
| DISABLED | 读到但 `enabled=false`，或返回空列表 | 黄条「GitLab 集成未启用，核验结果将保持未核验」；仅当账号有 `integration:manage` 时给「去集成配置」链接 | 不发请求，就地说明 |
| UNREADABLE | 403（无 `integration:manage`）或网络失败 | 黄条「当前账号无法读取 GitLab 集成配置（需 integration:manage），核验结果将保持未核验，如需核验请联系集成管理员」；**不给**配置入口链接 | 不发请求，就地说明 |

行内核验态仍由服务端派生结果决定（无记录/不可达 = UNVERIFIED，永不为绿）；行内 pill 的 reason 只做 tooltip，**不得**被本页用来承载「读不到配置」这一页面级事实（否则又变成 hover 才可见）。

## 6. 验收标准（本夹内编号，从 AC-1 起）

**AC-1 页面级准入矩阵 14 格（7 账号 × 2 事实）**
- ✅ 组 admin/dev1/tl1/vendor1：打开 `/code-policies` 后 `location.pathname === '/code-policies'`、无整页 ErrorState、策略表格已挂载（DOM 中存在表格或表头，有数据时 ≥1 行）。
- ⛔ 组 pm/po1/qa1：落 `/403` 且 query 带 `code=CCODE_POLICY_VIEW`；侧栏无「分支策略」入口。
- 证据：QA 浏览器逐账号实测记录（账号、URL、页面标题、截图），加 a5 的 14 条 HTTP 断言；不得只给状态码。

**AC-2 探测调用语义钉住（静态可变异）**
- 对 `GET /api/req/integration/configs?system=GITLAB` 的这次调用，`silentForbidden` 出现在**同一次 http 调用表达式**上（`CodePolicyView.vue` 调用点或 `web/src/api/integration.ts` 的专用探测函数二者其一，选定后在 a5 审计器里按同一口径判定）。
- `web/src/api/client.ts` 的 403 默认跳转分支（`:180`）在本轮 diff 中逻辑未改；pm 深链仍落 `/403`。
- 变异自检：临时删除该标记跑 a5，必须红并报出文件:行；恢复后绿。命令与输出摘录进 QA-REPORT。

**AC-3 三态与文案（可判定）**
- 三态判定与 §5 表格一致；UNREADABLE 文案不含「未配置/未启用/尚未接入/请配置」与「Maintainer」；DISABLED 文案不含「权限/无权」。
- UNREADABLE 与 DISABLED 下点击「核验」（能点的只有 admin）不产生 `POST /api/req/code/policies/{id}/check`；ENABLED + admin 下无黄条。
- 三态任一情况下策略列表都照常渲染：列表为 0 行只能因为「确实没有数据」或「列表接口自身失败」，不能因为探测失败。

**AC-4 同类调用点扫描归零（含清单）**
- 扫描口径固定为四步，缺一步不算完成：`web/src` 全量 http 调用 → 解析端点 → 对 `PermissionRouteTable.java` 的规则取所需码 → 对比所在路由的 `meta.functionCode`。凡「端点所需码 ⊄ 页面守卫码」的**读**调用，非管理页必须显式声明 `silentForbidden`。
- 产出清单落任务夹（TECHNICAL 或 QA-REPORT）：文件:行 → 端点 → 所需码 → 页面守卫码 → 处置（补标记 / 豁免理由）。
- 未声明且未豁免的数量 = 0；豁免只允许「管理页调用自身守卫码对应的端点」一类，且必须写理由。
- 预扫描基线（开发需复核并补正，允许变多不允许静默遗漏）：

  | 位置 | 端点 | 所需码 | 页面守卫码 | 现状 |
  |---|---|---|---|---|
  | `web/src/views/code/CodePolicyView.vue:62` | `GET /integration/configs?system=GITLAB` | `integration:manage` | `CCODE_POLICY_VIEW` | **缺陷，本轮修** |
  | `web/src/views/deliver/TestExecutorsView.vue:71` | `GET /admin/api-clients` | `apiclient:manage` | `TEST_EXECUTOR_VIEW` | 已声明（r7） |
  | `web/src/layouts/AppShell.vue:29` | `GET /approvals/todo` | 仅认证 | — | 已声明（r7） |
  | `web/src/api/dictionaries.ts:51-61` | 三个字典端点 | 仅认证 | — | 已声明（r7） |
  | `web/src/views/deliver/TraceabilityView.vue:134`（`getSonar`） | `GET /requirements/{id}/sonar` | 仅认证 | `gate:view` | 无风险，登记即可 |
  | `web/src/views/release/ReleaseDetailView.vue:243`（`getReleaseIntegration`） | `GET /releases/{id}/integration-settings` | 仅认证 | `release:plan` | 无风险，登记即可 |

**AC-5 新增 a5 段：≥20 断言，接入聚合器，总数 ≥115**
- a5-a 运行时 14 条：7 账号 ×（`GET /api/req/code/policies` 期望码 + `GET /api/req/integration/configs?system=GITLAB` 期望码）。view-only 账号在 configs 端点必须 403（同时钉住「后端确实拦」与「前端必须降级」两个事实）；admin 在 configs 端点 200；pm/po1/qa1 在 code/policies 端点 403。
- a5-b 静态审计 ≥4 条：调用点标记存在且落在同一调用表达式；豁免清单非空时每条带理由；`client.ts` 403 默认分支未被改成「不跳」；`views/admin/**` 之外的 raw `listConfigs` 调用数为 0。
- a5-c 审计器自检 ≥2 条：用临时夹具（一条故意缺标记的调用 + 一条带标记的调用）跑审计，缺标记必须判红并报出文件:行，带标记必须判绿——证明门禁不是「匹配到关键字就过」。
- 登记完整：`ALL_SEGMENTS`、`segment_cmd`、`segment_file`、`ACCESS_COUNTS` 汇总、总数守卫（≥45 的既有下限上调为全量 ≥115）五处都要有 a5；a0–a4 断言数一条不减少（现状 95）。
- 栈未起时仍非 0 退出（沿用 `req_ready/web_up` 守卫），不允许 a5 在栈未起时「0 条断言也算过」。

**AC-6 既有门禁不回归**
`make access-verify`（既有 95 + 新增 ≥20）、`platform-verify` 167、`quality-verify` 348、`integration-verify` 231、`collab-verify` 263、`platform-test-negative` 25、unit 237、lint 16、`make verify` 14、typecheck、build 全绿；`make platform-demo-reset` 与 r7 的 4 条不变量自检不受影响。

**AC-7 零协议 / 零 schema / 零权限码**
无 Flyway 迁移、无表结构变更、`PermissionMatrix` 函数码数量不变、无新增权限码、无后端接口契约变更（本轮默认纯前端修复；若最终采纳 §11 Q4 的后端只读摘要方案，本条必须按该方案改写并说明偏差，不得默默扩大范围）。

**AC-8 演示文档与截图同步**
`DEMO-BY-ROLE.md` 的 dev1 行补 `/code-policies` 的实测状态与一句「为什么是未核验」；新增截图 `docs/teamflow/demo-shots/dev1-code-policies-fixed.png`（与既有 `*-fixed.png` 命名一致）；不改工作台/报表章节。

## 7. 优先级

- **P0**：AC-1、AC-2、AC-3（演示阻断；dev1 当天要演示分支策略）。
- **P1**：AC-5、AC-6（防复发与证据）、AC-8。
- **P1**：AC-4（扫描清单；即使结果为空也要留清单，证明扫过）。
- **P2**：AC-7（声明性验收，随 AC-6 一并核验）。

## 8. 依赖与风险

依赖：r7 的 `silentForbidden` 机制与 `client.ts:180` 语义、导航准入（`layouts/navAccess.ts`）、a0–a4 段与 `ACCESS_COUNTS` 聚合器、`make platform-up/platform-seed` 的 12 账号夹具、既有 demo-shots 命名习惯。

风险与对策：
1. 静态审计按路径豁免管理页是启发式，页面搬家会漏判 → a5-c 自检 + 清单人工复核，豁免必须写理由。
2. 常驻提示可能给正常用户带来噪音 → ENABLED 不渲染；QA 需实测 admin 开/关两态。
3. 若改为后端只读摘要端点，权限面与 a5 断言都要重算，属范围扩大 → 默认不做（§11 Q4）。
4. 只修这一处会留下「下次别处再犯」→ 这正是 AC-4/AC-5 存在的理由；没有 a5 拦住新调用点，本轮不算完成。

## 9. 里程碑建议

单轮微改，不拆并行：实现（前端调用语义 + 三态提示）→ a5 脚本 + 清单 → QA 一轮（7 账号浏览器实测 + 三态文案核对 + 全门禁）→ 验收。改动面 <1 个页面 + 1 个脚本段 + 1 张截图。

## 10. 工程约束

- 基线分支 `feat/role-page-access-fix`（已有提交 `1a61e90`），本轮**直接在其上改**：不新建分支、不 rebase、不切换基线。
- 工作区现有未提交改动：`?? docs/teamflow/20261110-r8-code-policies-403-fix/`（本需求产物夹）、`?? docs/teamflow/demo-shots/{admin-requirements-fixed,pm-approvals-fixed,po1-requirements-fixed,qa1-testplans-fixed,vendor1-requirements-fixed}.png`（r7 演示截图）。处置：一并纳入本轮收尾提交，**不 stash、不删除**；提交前 `git status` 确认没有带入 `web/dist/`、`node_modules/`、`logs/`。
- 不改协议/表结构；只动前端调用语义（必要时才动后端，且仅限「集成配置只读降级」一种，默认不做）。
- `.gitignore` 本轮做一次针对本栈的核对（不新增规则除非发现真实新产物）：现有规则已覆盖 `target/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`.env*`（保留 `.env.example`）、`logs/`、`*.log`、`test-results/`、`playwright-report/`、`.idea/`、`.DS_Store`。a5 若产生临时夹具/报告，写 `logs/teamflow/<runId>/`（run 结束宿主归档）或已忽略的 `test-results/`，不得在项目根散落文件。

## 11. 假设与待澄清

**事实类**
- **同类残留只有 1 处（预扫描结论）**：`web/src` 中对 `@/api/admin`、`@/api/integration` 的引用已逐条核对，非管理页未声明 `silentForbidden` 的仅 `CodePolicyView.vue:62`；`getSonar`、`getReleaseIntegration` 对应端点无权限规则（仅认证），不构成风险。若你已知其他「打开即 403」的复现路径，请给账号 + 页面，否则以 AC-4 的全量扫描兜底。默认：按 1 处修 + 全仓扫描复核。
- **dev1 只有 `CCODE_POLICY_VIEW`、没有 `CCODE_POLICY_EDIT`**（`platform-contract/.../PermissionMatrix.java:319-320` 的 DEV 行只授 VIEW），因此「核验」按钮对他本来就不显示（`:disabled` 置灰、非隐藏），提示必须**加载即显示**、不能依赖点击。此项已由源码确认，不列为待澄清。

**口味类**
- **提示形态**：默认「页面顶部常驻黄条，按三态换文案；DISABLED 且账号有 `integration:manage` 时带『去集成配置』链接」，备选是「只在点核验时弹窗说明」。推荐常驻——只读角色点不到核验按钮，行内 reason 又只在 tooltip 里，弹窗式解释对他们不可达。
- **UNREADABLE 文案是否点名权限码**：默认写明「需 integration:manage」，备选是更含糊的「当前账号无权读取集成配置」。推荐写明——内部平台，点名权限码能让管理员一次定位。
- **是否顺带升级 a2 的口径**：默认不动 a2（只新增 a5，风险最低），备选是把 a2 的 5 页也改成「主端点 + 探测语义」双断言。推荐前者，a2 保持只减不加的历史证据不变。
- **是否给后端加只读摘要端点**（默认不加）：备选是在 `integration/configs` 上给登录用户一个只返回 `{system, enabled}` 的降级读法，让所有角色都能看到真实启用状态；代价是新增路由规则 + req-service 单测 + a5 重算，约多半天的范围扩大。推荐不加：配置面含 baseUrl 与凭据末四位，收窄在 `integration:manage` 内更安全。
