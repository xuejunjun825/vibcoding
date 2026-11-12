<!-- meta: summary="为已注册却无视图文件的 /portal、/dashboard、/reports、/reports/:id 补齐可用前端页面，只消费 r11 后端契约，以 4 角色 × 4 页截图与 access 门禁为验收证据。" -->
基线依赖：docs/teamflow/20261110-r11-reports-portal-backend/（后端五区/9 模板/共享五档/run/export/pin/缓存契约，已验收）与 docs/teamflow/20261110-r9-workbench-reports/（前端 SDK `api/analytics.ts`、store、四条路由、`nav.ts` 骨架；本轮只补视图，不改单点）
需求与口径来源：docs/teamflow/20261110-r12-analytics-portal-views/PRD.md（同需求上一轮，前端未落任何代码，故无运行时行为可回归；本轮沿用其 AC 骨架并按已拍板设计决策收敛）
取代：docs/teamflow/20261110-r12-analytics-portal-views/#AC-13：图表规则改为「1 维→柱/饼 + 可选折线，2 维→堆叠柱/热力，>2 维禁用图表并说明原因」
取代：docs/teamflow/20261110-r12-analytics-portal-views/#AC-15：pin 409 弹层改为列出当前 4 张卡 + 「去工作台移除」链接，并明示「下轮支持移除」

# r13 · 分析域四页前端视图（岗位工作台 / 数据看板 / 自助报表 / 报表编辑器）

## 1. 背景与目标

r9 交付了前端骨架：`web/src/api/analytics.ts`（SDK + 类型 + 错误码字典 + `cardLimitCards`）、`web/src/stores/analytics.ts`（URL 唯一真相 + 300ms 防抖 + AbortController + `applyToRoute`/`resetFilter`）、`web/src/router/routes/analytics.routes.ts`（四条路由 + `lazyView`）、`web/src/layouts/nav.ts`（分析域三入口）。r11 把后端补齐并合入 main（f815830）。**但四个视图文件从未创建**：`lazyView` 在 `import.meta.glob('../../views/**/*.vue')` 中找不到文件时直接 reject，用户点进去是白页/导航错误。这不是权限、也不是接口问题。上一轮（r12）PRD 已写完 20 条 AC，设计阶段因上游断流全部失败，因此本轮跳过 design、以已拍板的设计决策直接进技术方案。

本轮目标：

1. **四个入口真正可用**：/portal 岗位工作台、/dashboard 数据看板、/reports 报表列表、/reports/:id 报表编辑器（含 /reports/new 新建），按角色、按状态可操作。
2. **只消费、不重算**：数字全部来自 r11 后端；前端只做格式化与布局。四页共享同一筛选控件、同一交叉表渲染、同一错误码字典。
3. **可验收**：`npm run build`（内含 `vue-tsc --noEmit`）0 错误、`make lint` 与 `make access-verify` 不回归，且有 4 角色 × 4 页 + 3 个负向场景的真实截图与逐项结论。

**完成判据**：一个有数据的角色能从 /portal 看到自己的待办与卡片，在 /dashboard 改筛选并分享深链，把当前筛选带进 /reports/new 保存成报表，再共享/导出/设为卡片；全程无控制台报错、无白屏、无「看起来是 0 其实是 null」。

## 2. 用户故事

| ID | 角色 | 故事 | 验收 |
|---|---|---|---|
| US-1 | 业务/研发/测试/运维 | 我打开 /portal，按我的角色模板看到待办、快捷入口、关键指标、最近报表、提示；顶部的域/厂商/时间窗筛选改完可以分享；某一块数据源失败时只有那块折叠并说明原因。 | AC-3、AC-4、AC-5、AC-7 |
| US-2 | 管理层（mgmt） | 我打开 /portal 只读查看，不出现可点的处理动作；打开 /dashboard 看五块经营数据。 | AC-3、AC-6 |
| US-3 | 产品经理/PMO | 我在 /dashboard 切换域/厂商/时间窗/需求类型，URL 可分享、可后退；点「在报表中打开」把当前筛选带进编辑器。 | AC-7、AC-8 |
| US-4 | 报表属主 | 我在 /reports 两段 Tab 里管理报表，复制/删除/按五档共享，默认导出带水印的 XLSX。 | AC-9、AC-10、AC-11 |
| US-5 | 报表作者 | 我在 /reports/:id 从白名单字段池拖到行/列/值/筛选，保存后看到交叉表，转置/切图，设为工作台卡片。 | AC-12、AC-13、AC-14、AC-15 |
| US-6 | 平台管理员 | 我在 /portal 打开卡片配置抽屉，按角色勾选模板卡显隐并保存，重复保存结果一致。 | AC-20（P1） |
| US-7 | 验收人 | 我用 4 个账号对 4 个页面截图，并看到空态、单区降级与 409 弹层的证据，而不是一句「已验证」。 | AC-16、AC-2 |

## 3. 范围与非目标

### 3.1 本轮范围

- 新建四个视图（文件名必须与路由 `lazyView('...')` 一致）：`web/src/views/portal/PortalView.vue`、`web/src/views/analytics/DashboardView.vue`、`web/src/views/reports/ReportListView.vue`、`web/src/views/reports/ReportEditorView.vue`；抽 `web/src/components/analytics/**`（`FilterBar.vue`、交叉表、图表、共享弹层、pin 上限弹层等复用小组件）。
- **仅类型/查询参数层**的最小对齐：`web/src/api/analytics.ts` 的 Dashboard/CrossTab/Quality 类型与真实 wire 对齐（只改类型/注释，不改请求路径）；`ReportScope` 增 `VENDOR`；`AnalyticsQuery` 与 store 的 filter schema 增 `reqType`（URL 键 `reqType`，默认 null），不碰防抖/取消/单源逻辑。
- /portal 内管理员专属「卡片配置」抽屉（P1）：`GET|PUT /portal/config`，按角色勾选模板卡。
- access 门禁扩展：a2 增 4 页 × 7 角色（28 条），a3 侧栏基线 33→36（关闭既有 D-8），总断言下限 115→143。
- 证据：`docs/teamflow/20261111-r13/evidence/` 下 20 张 1440×900 PNG + `EVIDENCE.md`；截图脚本放 `logs/teamflow/<runId>/`。
- `.gitignore` 审计（见 §12 第 10 条）。

### 3.2 非目标（本轮明确不做）

- 不做移动端适配、暗色主题、拖拽自定义布局、报表订阅推送、新数据集、后端公式改动、E2E 框架。
- 不改 `/workbench`（M6 质量工作台），不与 /portal 合并。
- 不改 deliver-service / req-service 的接口、迁移、SQL；发现缺口只登记到任务夹（G-1…G-7）。
- 不引入新 UI 框架/图表库/状态库：Element Plus + 已有 echarts 6 + dayjs，`web/package.json` 依赖零新增。
- 不做报表版本历史、评论、定时快照、匿名分享链接、报表重命名（见 A4）、全局搜索、导出 PDF、批量操作、跨租户视图。

## 4. 反面目标（anti-goals）

以下任一发生，即使构建全绿、接口全 200，也应被人工拒绝：

1. **构建绿、页面白**：四个入口仍抛 `[analytics.routes] 视图 ... 尚未生成`，或页面只剩空壳、控制台有未捕获异常/404 chunk；用户点进去不知道该干什么。
2. **筛选是装饰**：/portal 或 /dashboard 改筛选只改内存，URL 不变；刷新/分享/浏览器后退回到默认；深链打开后控件与请求不一致；两个页面各写一套筛选控件、行为不同。
3. **数字看着对、其实不同源**：前端自己算比率/环比，或把 `null/undefined` 显示成 `0`/`NaN`/空白，导致同一指标在 /portal 与 /analytics/metrics 差一位。
4. **编辑器能看不能存、宽表撑破页面**：拖完字段保存后重开丢失；新建页假装「即时预览」却没有数据来源；100+ 列把整页撑出横向滚动，首列/表头滚走。
5. **流程把用户卡死**：409 只弹「上限 4 张」却不列当前 4 张、不给出口；共享弹层出现 `TENANT` 或缺少 `VENDOR`；可选字典 403 把整页踢到 `/403`。

## 5. 已拍板的设计决策（10 条，不可重开）

> 这是本轮的 DESIGN 输入。技术方案阶段把本节精简引用落盘为 `DESIGN.md`，不再重开设计讨论。

| # | 决策 |
|---|---|
| D1 | **/portal**：单路由，竖向 5 区固定顺序 `todo → actions → metrics → list → messages`（按后端 `sections` 数组顺序，不重排）；顶部筛选条（域/厂商/时间窗 7d\|30d\|90d\|自定义）与 URL query 同步；metrics 为卡片网格，张数与标题由后端模板决定（前端不写死）；右上角 `dataAsOf` + 缓存 `HIT/MISS` 徽标；每区独立 loading/error，单区失败只在该区显示错误条 +「重试」，不整页白屏；mgmt 角色整页只读，actions 区隐藏。 |
| D2 | **/dashboard**：单路由，5 块纵向排布（需求量多维+环比 / 周期平均与 P90 / 人天预估-实际-偏差 / 厂商交付排名 / 交付质量）；筛选条复用 D1 的组件（`web/src/components/analytics/FilterBar.vue`），URL query 为唯一真相（复用 store 的 `applyToRoute`/`resetFilter`）；「在报表中打开」跳 `/reports/new?dataset=requirement&...`，带上当前筛选作为运行期参数，不写入 `definition`。 |
| D3 | **/reports**：两段 Tab「我的报表」「共享给我」；列固定为 名称 / 数据集 / 共享范围 / 更新时间 / 属主 / 操作（打开·复制·共享·删除）；新建按钮直接进 `/reports/new`；删除二次确认；他人 PRIVATE 报表的任何操作显示「不存在」（按 `REPORT_NOT_FOUND` 渲染，不显示「无权限」）。 |
| D4 | **/reports/:id**：三栏 = 左字段池（数据集选择 + 维度/度量列表，数据来自 `/reports/datasets`，不硬编码）· 中字段槽（行/列/值/筛选 四槽，HTML5 DnD 拖入、槽内可移除、可拖回）· 右预览（交叉表 + 「转置」「图表」切换）；顶栏：报表名、保存、另存、导出、pin；预览由「保存并运行」或 300ms 防抖后的 `POST /reports/{id}/run` 触发（未保存前不 preview）；宽表横向滚动 + 首列 sticky + 表头 sticky；图表规则严格：1 维度→柱/饼 + 折线可选，2 维度→堆叠柱/热力，>2 维度禁用图表并提示原因。 |
| D5 | **共享弹层**：5 档 `PRIVATE \| ROLE \| DOMAIN \| VENDOR \| PUBLIC`（UI 不出现 `TENANT` 字样，`TENANT` 仅是后端入参别名）；ROLE 档填角色数值 ID，其余档从字典下拉；弹层顶部固定一句「共享的是报表定义，数据始终按查看者的可见范围重新聚合」。 |
| D6 | **pin 上限**：`POST /reports/{id}/pin` 返回 409 `PORTAL_CARD_LIMIT` 时，用 `cardLimitCards(error)` 取当前 4 张卡并逐行列出，给「去工作台移除」链接；本轮后端无 unpin，不做移除动作，文案明示「下轮支持移除」。 |
| D7 | **工程复用（禁止重写）**：`web/src/api/analytics.ts` 全套函数与类型、`web/src/stores/analytics.ts` 的 URL 同步/防抖/AbortController、`web/src/router/routes/analytics.routes.ts` 的 `lazyView` 约定、`web/src/layouts/nav.ts` 已注册菜单。允许对 `analytics.ts` 做「类型与真实 wire 对齐」的修正 + 给 store 增加 `reqType` 字段，但这两个文件仍是唯一真相，视图内不得写第二套 fetch/防抖/URL 解析。 |
| D8 | **视觉**：`docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`（`#2B6FE5` 主色、白卡片 + 1px `#E5E6EB` 边框、行高 44–48、hover 出行操作、状态用浅底 pill）；Element Plus 组件优先；echarts 6 已装可直接用；不引入新依赖。 |
| D9 | **探测与路由**：字典、审批角标等探测类调用必须声明 `silentForbidden`，403 只做页内降级；新页面路由 `functionCode` 沿用现有 `PORTAL_VIEW` / `REPORT_VIEW`；`/workbench`（M6 质量工作台）零改动。 |
| D10 | **验收口径（务必自证）**：`web npm run build` 与 `vue-tsc --noEmit` 0 错误；`make access-verify` 不回归；无头 Chrome + CDP 注入 `sessionStorage('devops.session')`，对 admin/pm/vendor1/po1 截 /portal、/dashboard、/reports、/reports/new；空态、单区降级、409 pin 上限三个负向场景要有截图或断言；结论写进任务夹。 |

## 6. 交互流程摘要

### 6.1 /portal

1. 读 URL query（若有）→ 经 store 的 `filterFromQuery` 归一化为筛选；页面不自行造模板、不自行解析 query。
2. `GET /portal`（带 `domainId/vendorId/preset/from/to`）→ 顶部显示 `templateLabel`、`dataAsOf`、缓存 `HIT/MISS`；五区按后端 `sections` 顺序渲染。
3. 待办区用 `todoSources`：`count=null` 显示「—」+ 原因；`todoMode=READONLY`（mgmt）显示「只读」pill，且 actions 区整体隐藏。
4. 指标卡数字按 `digits/unit` 格式化；点击卡片跳 `link` 或 `reportId`；单区 `DEGRADED` 只折叠该区，其余照常，区头「重试」只重发该区所需请求。
5. 管理员在标题右侧看到「卡片配置」入口（无 `PORTAL_CONFIG` 不渲染），打开抽屉选角色、勾选卡片、保存。

### 6.2 /dashboard

1. 从 `route.query` 派生筛选（默认近 30 天）；筛选条为共享 `FilterBar`（fields 由 prop 决定：dashboard 含需求类型，portal 只含域/厂商/时间窗）。
2. 变更 → `applyToRoute` 写 URL（保留非筛选键）→ 300ms 防抖 → `GET /analytics/dashboard`；切筛选/离开页面 abort inflight，旧响应不覆盖新筛选。
3. 五块：需求量多维（交叉表 + 趋势/环比）、周期均值与 P90、人天预估/实际/偏差、厂商排名、交付质量。空值一律「—」，不补 0。
4. 「在报表中打开」→ `/reports/new?dataset=requirement&...` 并带当前筛选（见 AC-8 的持久化限制）。

### 6.3 /reports

1. 两段：我的（`segment=MINE`）/ 共享给我（`segment=SHARED`）；分页、关键词、空态；默认落「我的报表」（B2）。
2. 行 hover 才出现操作；无权限的操作不渲染（不是点了才 403）：非属主隐藏「共享」「删除」，保留「打开」「复制」。
3. 删除二次确认；复制后落到「我的」并高亮副本（「… 副本」，scope=PRIVATE）。
4. 共享弹层五档；保存后列表 scope 标签即时更新。
5. 直接访问他人 PRIVATE 报表 id → 统一「报表不存在」，不暴露「存在但无权限」。

### 6.4 /reports/:id（含 /reports/new）

1. `new` 或数字 id；加载 `GET /reports/datasets` 与（编辑时）`GET /reports/{id}`。
2. 左侧字段池按数据集分组（维度/时间/度量），搜索过滤；HTML5 DnD 拖入四槽（双击为键盘/触屏兜底），槽内可排序、可移除、可拖回。
3. 槽位变更 → 限额校验（行≤3、列≤2、合计≤4、值 1–3、筛选≤8）→ 已保存报表 300ms 防抖 `POST /reports/{id}/run`；新建报表未保存不发 run。
4. 交叉表：行/列表头、subtotal/total、宽表横向滚动 + 首列冻结 + 表头吸顶；转置只交换行列不重发请求；`truncated` 显示截断提示。
5. 图表按 D4 的维度规则启用/禁用并给出原因；保存/另存/导出/pin；409 弹层列出当前 4 张卡。

### 6.5 横切状态

统一四态：加载骨架、空态（一句话 + 一个主行动）、错误（可读原因 + 重试）、降级（局部折叠 + 原因）。403 只在页内提示；404 用「不存在」；409 用弹层列明细；网络失败保留已有数据并显示「上次成功时间」。

## 7. 验收标准

> 每条 AC 以「可复现命令 / 可观察 DOM / 可对比响应」为准；截图与命令结论写入 `docs/teamflow/20261111-r13/EVIDENCE.md`。测试账号：t1 租户，admin/`Admin@12345`，其余 `Passw0rd!`。

### AC-1 构建与改动边界（P0）

- `cd web && npm run build` 退出码 0（脚本先跑 `vue-tsc --noEmit -p tsconfig.json`）；单独 `npx vue-tsc --noEmit -p tsconfig.json` 亦 0 错误。
- 四个视图文件存在且 `import.meta.glob` 能解析；四个 URL 刷新后不再出现 `[analytics.routes] 视图 ... 尚未生成`。
- 改动文件仅限：`web/src/views/{portal,analytics,reports}/**`、`web/src/components/analytics/**`、`web/src/api/analytics.ts`（类型/注释/`ReportScope` 增 `VENDOR`）、`web/src/stores/analytics.ts`（`reqType`）、`scripts/tests/access/a2-page-matrix.sh`、`scripts/tests/access/a3-nav-audit.mjs`、`scripts/access-verify.sh`、`.gitignore`（如需）、`docs/teamflow/20261111-r13/**`；`deliver-service/`、`req-service/`、`platform-*/`、`web/src/router/**`、`web/src/layouts/nav.ts` 零 diff。
- `git diff -- web/src/views/workbench` 为空；`web/package.json` 依赖零新增。
- 证据：`git status --short`、`git diff --stat`、构建输出尾部。

### AC-2 四路由与角色可达（P0）

- admin、pm、vendor1、po1 四个账号分别打开 `/portal`、`/dashboard`、`/reports`、`/reports/new`：页面根节点（`[data-page="portal"|"dashboard"|"report-list"|"report-editor"]`）可见，16/16 无整页白屏。
- 每页控制台无 `error`/`unhandledrejection`；无 404 chunk、无重复请求风暴（同一 URL 连续 3 次以上视为风暴）。
- `/reports/abc` 落路由 404；`/reports/999999` 显示「报表不存在」并可返回列表。
- 证据：16 张基础截图 + 控制台日志（AC-16）。

### AC-3 /portal 五区顺序与角色模板（P0）

- DOM 中五区顺序严格等于后端 `sections` 数组顺序：待办、快捷入口、关键指标、最近报表、提示；不按标题/字典重排；每区有稳定 `data-testid`（`portal-section-todo|actions|metrics|list|messages`）。
- 顶部显示后端 `templateLabel`；前端源码中不存在 `template === '...'` 之类模板分支，也不存在角色→模板映射表（抽查 admin/pm/vendor1/po1 四账号，均渲染后端返回内容）。
- `todoMode=READONLY`（mgmt）时显示「只读」pill，待办区不渲染处理/跳转按钮，actions 区隐藏；`todoSources` 为空显示「暂无待办」而非空白。
- seed 无 MGMT 账号：证据轮用 admin 调 `POST /api/req/admin/users` 创建 `mgmt1`（角色 `MGMT`，密码 `Passw0rd!`）后登录验证；只造测试账号，不改 seed/后端。
- 证据：四账号 + `mgmt1` 截图 / `portal-mgmt.png`、`GET /portal` 响应对照。

### AC-4 卡片数字同源与 dataAsOf/cache（P0）

- 同一筛选下分别请求 `GET /api/deliver/portal` 与 `GET /api/deliver/analytics/metrics`：每张 `kind=TEMPLATE` 卡片上的每个 `Metric`，按 `digits` 格式化后的字符串与 metrics 同 `key` 的值逐位相等（单位一致）。
- 个人卡（`kind=PERSONAL`）的数字来自该报表 `GET /api/deliver/reports/{id}/run`，与 run 的聚合值逐位相等，不得张冠李戴到另一个端点。
- 视图对卡片数值只做 `formatNumber(value, digits)` + 单位拼接；不得出现 `prev/delta` 比率/环比计算，不得把 0.85 再乘 100。
- 指标区右上角显示 `dataAsOf`（`YYYY-MM-DD HH:mm`）与缓存 chip `HIT`/`MISS`，hover 说明「数据截至 … · 缓存命中/未命中」。
- 任何 `value/count` 为 `null` 时显示「—」并带原因 title，绝不显示 `0`/`NaN`/`undefined`。
- 证据：curl 三份响应 + 页面读数截图 + 代码 grep。

### AC-5 单区降级不白屏（P0）

- 用 CDP `Fetch` 拦截 `/api/deliver/portal`，把待办区改为 `state=DEGRADED, error="待办源超时（模拟）"`：页面仍渲染其余四区，待办区显示原因与「重试」，无控制台异常；证据 `portal-degraded.png`。
- 整个 `/portal` 返回 500：页面显示整页错误卡 + 「重试」，点击只重发一次 portal 请求；不清空导航与当前筛选。
- 各区独立：一区 loading 时只在该区显示骨架，不阻塞其他区；`count=null` 显示「—」。
- 证据：`portal-degraded.png` + 拦截脚本输出。

### AC-6 /dashboard 五块与 null 语义（P0）

- 五个区块标题固定为：需求量多维、周期与 P90、人天投入、厂商交付排名、交付质量；区块顺序不随数据为空变化。
- 各块消费 `GET /analytics/dashboard` 的真实字段（本轮先用 curl 固化响应样本，以样本为准；类型与 wire 不一致时只允许改 `api/analytics.ts` 的类型，见 A1）：多维交叉表取列头/行/total，趋势取 `bucket/current/previous/delta`（不假设 `period`）；周期取 `avg/p90/tail`；人天取 `estimate/actual/deviation`；排名按 `rank` 升序且 `mine=true` 高亮；质量卡 `level` 为 `NONE` 显示「无样本」（不是 `UNKNOWN`、不是「正常」）。
- `p90=null`、`deviation=null`、`value=null` 一律「—」，不补 0。
- 图表 canvas 存在、无控制台错误；窗口 1280→1440 不溢出。
- 证据：响应 JSON + 截图 + DOM 文本断言。

### AC-7 筛选条 URL 双向同步与深链（P0）

- **唯一实现**：`web/src/components/analytics/FilterBar.vue` 是全仓唯一筛选控件定义（`grep -rn "preset" web/src/views` 不出现第二套控件实现）；/portal 与 /dashboard 均 `import FilterBar` 并传 `fields`（dashboard 含 `reqType`，portal 不含），两页行为由同一份代码保证。
- 控件：业务域、厂商、时间窗（7d/30d/90d/自定义 from-to）、需求类型（全部/BUS/PDR/RD，仅 dashboard）。
- 变更后 URL query 精确出现 `domainId/vendorId/from/to/preset/reqType`（空值删除对应键）；请求参数与 URL 一致；两页都符合。
- 深链 `/dashboard?domainId=1&vendorId=2&from=2026-10-01&to=2026-10-31&preset=custom&reqType=RD&foo=1`：控件回显、请求带全参、改筛选后 `foo=1` 仍保留；`/portal` 深链同理（无 `reqType` 键时不发该参数）。
- 浏览器前进/后退恢复筛选与数据；「重置」回到近 30 天并移除筛选键（保留 `foo`）。
- 连续 3 次快速变更只发 ≤2 次请求（300ms 防抖，最后一次胜出）；切筛选/离开页面 abort inflight，旧响应不覆盖新状态。
- 证据：CDP Network 日志 + 两页 URL 截图。

### AC-8 「在报表中打开」带筛选（P0）

- 点击后路由到 `/reports/new`，query 含 `dataset=requirement` 与当前非空筛选；编辑器顶部显示「范围筛选」chips（域/厂商/时间窗/需求类型），并说明这些条件随每次运行传给后端。
- chips 可单独移除；移除后 URL 与运行参数同步。
- 已知限制：`definition` 只持久化行列值与字段筛选，**不持久化** `preset/from/to/reqType`；保存后重开不再带这些范围条件（G-5）。UI 在保存前提示「范围条件不随报表保存」。
- 证据：URL、editor 请求体、保存后重开截图。

### AC-9 /reports 两段、CRUD 与空态（P0）

- 「我的报表」/「共享给我」分别请求 `segment=MINE|SHARED`；列恰为 名称 / 数据集 / 共享范围 / 更新时间 / 属主 / 操作；分页总数正确。
- 操作列为 打开、复制、共享、删除；非属主或 `canShare=false` 时「共享」「删除」不渲染；`canWrite=false` 时「删除」不渲染；不得「点了才 403」。
- 复制生成「… 副本」且 scope=PRIVATE，列表刷新后可打开；删除二次确认，软删后行消失，同属主可用原名重建（后端语义）。
- 空态：我的为空显示「还没有报表」+「新建报表」；共享为空显示「还没有人共享给你」；关键词无结果显示「清空筛选」。
- 直接打开他人 PRIVATE 报表 id 显示「报表不存在」，不是「无权限」。
- 证据：四账号列表截图 + 操作后响应。

### AC-10 共享弹层五档（P0）

- 选项严格为 `PRIVATE | ROLE | DOMAIN | VENDOR | PUBLIC`，UI 文案不出现 `TENANT`（`TENANT→PUBLIC` 别名只允许出现在 SDK 注释/映射）；默认 `PRIVATE`；顶部固定显示「共享的是报表定义，数据始终按查看者的可见范围重新聚合」。
- `DOMAIN`/`VENDOR` 的 `scopeRef` 来自 `web/src/api/dictionaries.ts` 字典下拉的数值 id；字典 403/失败时下拉禁用并页内提示，不跳 `/403`。
- `ROLE` 档因后端无角色 id 字典（G-2），提供数字「角色 ID」输入 + 说明；非法/非数字在提交前拦截，后端 400 原样显示。
- 保存 `shareReport` 成功后列表 scope 标签更新；非属主/无 `REPORT_SHARE` 返回 403 时页内提示，不全局跳转；dev1 角色不显示共享入口。
- 五档均不改变报表属主。
- 证据：五档截图、`scopeRef` 请求体、403 响应。

### AC-11 导出 XLSX/CSV（P0）

- 编辑器顶栏「导出」默认 XLSX，可切 CSV；调用 `exportReport`，浏览器下载文件非空；文件名用 SDK 兜底 `report-<id>-<yyyyMMdd>.<ext>`（`client.ts` 的 `download()` 读不到响应头，服务端 `Content-Disposition` 暂不可用 → 登记 G-7），不得产出空文件。
- 小报表导出后行数与页面行数一致；XLSX 含首行元信息与页脚水印（解压 `xl/worksheets/sheet1.xml` 核对 r11 水印字段）；CSV 含 BOM 与首行元信息，并在 UI 提示「对外请用 XLSX」。
- 超过 10000 行返回 400 `REPORT_EXPORT_LIMIT`：页内提示上限与建议缩小筛选，编辑器状态不丢。
- 403/404 导出失败页内提示。
- 证据：下载目录文件、xlsx 解压片段、错误响应截图。

### AC-12 编辑器三栏、字段池与四槽（P0）

- 三栏布局存在且稳定：左字段池、中四槽、右预览（1440 宽下三栏同时可见，不折叠成抽屉）。
- 左侧字段池来自 `GET /reports/datasets`：4 个数据集、字段 key/label/kind 与响应一致；视图源码不硬编码字段 key（`grep -n "req.total\|cycle.p90" web/src/views` 为空）。
- 字段可拖拽/双击加入 行/列/值/筛选；度量只能进「值」，维度/时间只能进 行/列/筛选；非法投放给出原因并拒绝；槽内可移除、可排序。
- 限额即时校验并与后端一致：行≤3、列≤2、合计维度≤4、值 1–3、筛选≤8；超限提示具体上限，不发请求。
- 筛选算子白名单：`= != > >= < <= IN`；`IN` 值按逗号/多值输入。
- 证据：字段池与响应对照、超限截图、拖拽后 DOM 槽位。

### AC-13 交叉表、转置、图表与宽表（P0）

- 未保存（`/reports/new` 且未保存）时「运行/预览」禁用并提示「先保存以生成报表」；保存成功后自动运行，此后槽位变更 300ms 内只发一次 `POST /reports/{id}/run`。
- 表格行/列/单元格与响应一致；转置后行列互换且不重发请求；`truncated=true` 显示「结果超过 5000 行，仅展示前 5000 行」；`subtotal/total` 有独立样式。
- 宽表（≥100 列）：横向滚动发生在表格容器内，`document.scrollingElement.scrollWidth` 不超过视口；首列 `position: sticky; left: 0`，表头 `position: sticky; top: 0`，滚动后仍可见。
- 大结果（5000 行）下表格 DOM 单元格 ≤3000（分页或虚拟滚动），页面不冻结；分页页脚显示总数与当前页。
- 图表规则：`TABLE` 常驻；1 个行维且无列维 → 柱/饼可选、折线可选；2 个维度 → 堆叠柱/热力；>2 个维度 → 图表控件禁用并显示原因（如「3 个维度无法选择图表类型，请减少到 2 个以内」）；不满足的选项禁用并说明，切图不重新请求。
- 证据：宽表滚动截图、5000 行 DOM 计数、图表四态截图。

### AC-14 保存/另存/未保存守卫（P0）

- 新建：填名称 + 字段后点「保存并运行」→ `POST /reports` 201 → URL `replace` 到 `/reports/{id}` → 自动 `run`；列表出现新报表。
- 编辑：`PUT /reports/{id}` 后 `run`；刷新后 `definition` 的 rows/columns/measures/filters 深比较一致。
- 「另存为」以「… 副本」创建新报表并停留在新 id；重名返回 409 `REPORT_NAME_DUPLICATED` 行内提示，不丢编辑内容。
- 未保存离开（路由跳转/刷新）弹出确认；保存后离开不再提示；404 显示「报表不存在」+ 返回列表。
- 证据：创建/更新响应、刷新截图、409 截图。

### AC-15 pin 上限 409 与弹层（P0）

- 同一用户连续 pin 4 张不同报表均 200，第 5 张返回 409 `PORTAL_CARD_LIMIT`。
- 弹层用 `cardLimitCards(error)` 读取 `detail.cards`，恰好列出当前 4 张（title/reportId），每张可「打开」到 `/reports/{id}`；另给「去工作台移除」链接到 `/portal`。
- 弹层明示「本轮暂不支持移除，下轮支持移除」（G-1），不出现第 5 张卡；重复点击不产生无限请求/重复 toast；关闭弹层后页面状态不变。
- 证据：409 响应 `detail.cards`、`editor-409.png`、portal 仍 4 卡。

### AC-16 截图与视觉基线（P0）

- `docs/teamflow/20261111-r13/evidence/` 下 20 张 1440×900 PNG：16 张基础（`portal|dashboard|reports|editor` × `admin|pm|vendor1|po1`）+ 4 张补充（`portal-mgmt.png`、`portal-degraded.png`、`editor-409.png`、`reports-empty.png`）。
- 用无头 Chrome + CDP，`Page.addScriptToEvaluateOnNewDocument` 注入 `sessionStorage['devops.session']`（token/tenantId/tenantCode/user/permissions/defaultRoute 来自真实登录），再导航；不得截登录页或空白页。
- `EVIDENCE.md` 表格逐张记录：页面、账号、URL、期望、实际、控制台错误（必须为空）；vendor1/po1 无数据时必须是空态而非空白。
- 视觉抽查：主色 `#2B6FE5`、卡片白底 + `#E5E6EB` 1px 边框、无阴影、8px 圆角；状态 pill 浅底 6px；表格行高 44–48；正文 13px；每屏最多一个主按钮；浮层用 popover 而非整页跳转。
- 证据：截图文件 + 表格 + 至少一条 computed style 记录。

### AC-17 access 门禁覆盖新路由（P0）

- `scripts/tests/access/a2-page-matrix.sh` 增加 4 页 × 7 角色 = 28 条：`/api/deliver/portal`、`/api/deliver/analytics/dashboard`、`/api/deliver/reports?page=1&size=5`、`/api/deliver/reports/datasets`；a2 计数 35→63，`segment_min a2` 同步 63，注释「5 页→9 页」。若某角色按后端权限矩阵应得 403/404，按真实码写期望并在脚本注释说明，断言总数仍为 63，不得删项放宽。
- `scripts/tests/access/a3-nav-audit.mjs` 侧栏基线 33→36（实际已有 r9 三入口），R1 通过，关闭既有 D-8；顶栏 8 不变。
- `scripts/access-verify.sh` 总断言下限 115→143（29+8+63+9+14+20）；a0 29、a1 8、a4 14、a5 20 不减少。
- `make access-verify`（栈已启动）输出 `PASS==TOTAL=143`，新增 28 条全 PASS。
- 证据：脚本 diff + 完整输出尾部。

### AC-18 lint/回归与依赖（P0）

- `make lint` 16/16；`npm run build` 0；`make access-verify` 通过（AC-17）。
- 后端与 `/workbench` 零 diff（AC-1）；无新增依赖；新增文件无 `@ts-ignore`、无 `: any`（`grep -rn "@ts-ignore" web/src/views web/src/components/analytics` 为空）。
- 既有页面至少抽查 `/requirements`、`/workbench`、`/admin/permissions` 正常打开，无全局样式/路由副作用。
- 证据：命令输出、diff。

### AC-19 可选能力探测 silentForbidden（P0）

- 新视图中所有字典调用（`listDictionaryDomains/Vendors/Users` 或 `loadDictionaries`）走 `web/src/api/dictionaries.ts`，不直接 `http.get` 拼 URL；视图源码中不出现 `/dictionaries/` 字面量。
- CDP 对 `/dictionaries/domains` 返回 403：域下拉为空/禁用并页内提示「字典不可用」，URL 不变、不跳 `/403`，其余数据照常；厂商/人员同理。
- 本轮不新增执行器台账/集成配置/审批角标等可选探测；若新增必须同样 `silentForbidden`。
- `make access-verify` 的 a5 仍 20/20 起。
- 证据：403 模拟截图、a5 输出。

### AC-20 管理员卡片配置抽屉（P1）

- admin 在 /portal 看到「卡片配置」；pm/vendor1 不显示（无 `PORTAL_CONFIG`，不是点了才 403）。
- 抽屉可选 10 个角色（code+中文名），读取 `GET /portal/config?roleCode=...` 的 `cards` 复选；保存 `PUT /portal/config`（`user_id=0` 角色级），重复保存结果一致（幂等）。
- 保存后目标角色重新打开 /portal，被取消的模板卡不出现、保留的出现；个人 pin 卡不受影响。
- 证据：保存前后目标角色截图、两次 PUT 响应。

## 8. 优先级

- **P0（本轮必须通过）**：AC-1 ~ AC-19。没有它们，页面仍不可用或不可验收。
- **P1（本轮尽量做，独立可回退）**：AC-20 管理端卡片配置抽屉。若排期不足，必须在 `EVIDENCE.md` 明确记录「未做及原因」，不得悄悄消失。

## 9. 依赖与风险

| 风险 | 影响 | 应对 |
|---|---|---|
| R1 Dashboard/CrossTab/Quality 类型落后于真实 wire（真实质量等级为 `GOOD/WARN/BAD/NONE`，SDK 写 `UNKNOWN`） | 视图按旧类型写会全空或 TS 报错 | 先做**只改类型**的对齐（AC-1 允许），以 curl 响应为准；补一条 dashboard 响应字段断言 |
| R2 无 unpin 接口（G-1） | 409 后无法「先移除再加」 | 弹层列 4 张 + 打开链接 + 去工作台链接 + 明示下轮支持；不伪造移除按钮 |
| R3 无角色 id 字典（G-2） | ROLE 共享无法用下拉 | 数字输入 + 说明；不新增后端接口；由 B3 决策 |
| R4 新建报表无匿名运行端点（G-3） | 新建页不能真正「即时预览」 | 先保存再自动运行；按钮文案与提示说清楚 |
| R5 看板筛选无法写进 definition（G-5） | 「在报表中打开」只能运行期生效，保存后丢失 | UI 明示；domain/vendor 可转 definition 特殊筛选，时间/reqType 不转 |
| R6 a3 既有 D-8（侧栏 36 vs 33） | access 聚合 8/9，看起来「不绿」 | 本轮同步基线到 36 并关闭 D-8（AC-17）；若团队拒绝改脚本，保留 8/9 并在 EVIDENCE 标注 |
| R7 5000 行 × 多列渲染 | 浏览器卡顿/截图超时 | 分页或虚拟滚动，DOM 单元格 ≤3000；导出仍全量 |
| R8 截图账号数据不足（vendor1/po1） | 截图空白，无法证明 | 先用 seed 数据；空态也截；脚本等待网络空闲与选择器 |
| R9 误改后端/路由/nav | 回归与 scope 失控 | AC-1 diff 白名单；只加类型与 `reqType`；routes/nav 不改 |
| R10 本轮跳过 design | 技术方案缺设计输入，易自由发挥 | 以 §5 十条决策为唯一设计输入，tech 阶段先落 `DESIGN.md` 引用本节 |

## 10. 里程碑建议

- **M0（0.5 天）契约对齐**：`analytics.ts` 类型对齐 + store `reqType` + 用 curl 固化四份响应样本；四个空视图先可渲染（避免并行时路由仍 reject）；`FilterBar.vue` 骨架。
- **M1（1 天）/portal**：五区、模板、筛选条、卡片同源、单块降级、dataAsOf/cache；管理员抽屉可后置。
- **M2（1 天）/dashboard**：五块、筛选复用、深链、跳报表。
- **M3（1.5 天）/reports + 编辑器**：列表两段/CRUD/共享/导出；三栏、字段池/DnD/交叉表/宽表/图表/保存/pin 409。
- **M4（0.5 天）门禁与证据**：access a2/a3、lint、20 张截图、EVIDENCE、.gitignore 审计、最终构建。
- 并行建议：M1/M2/M3 文件互不重叠，可在 M0 后并行；`analytics.ts`/`stores/analytics.ts`/`FilterBar.vue` 由一人先改，避免冲突。

## 11. 假设与待澄清

### (a) 未定事实（`kind: fact`）

- **A1 · 类型对齐边界**：真实 wire 与 `web/src/api/analytics.ts` 的 Dashboard/CrossTab/Quality 类型不一致（如质量等级 `NONE` vs `UNKNOWN`），且 store 的 filter schema 没有 `reqType`、`ReportScope` 没有 `VENDOR`。本轮按「只改类型/查询键，不改请求路径与 store 单源逻辑」处理。若要求 SDK 完全冻结，则 Dashboard 五块无法按真实字段渲染，只能改为视图内适配层（重复类型，不推荐）。
- **A2 · mgmt 账号不存在于 seed**：证据轮由 admin 调 `POST /api/req/admin/users` 造 `mgmt1`（角色 `MGMT`，密码 `Passw0rd!`），只造测试账号，不改 seed。
- **A3 · 四个后端缺口（G-1/G-2/G-3/G-5）**：无 unpin、无角色 id 字典、无匿名 run、范围条件不入 definition；本轮均以「不改后端 + UI 明示」处理，只在 EVIDENCE 登记。
- **A4 · 列表不含重命名**：上一轮 AC-9 有「重命名」，本轮 D3 的操作列只有 打开/复制/共享/删除；后端 `PUT /reports/{id}` 支持改名，但本轮不做入口（登记 G-6），避免与 D3 冲突。
- **A5 · 导出文件名**：`client.ts` 的 `download()` 读不到 `Content-Disposition`，只能用 `report-<id>-<yyyyMMdd>.<ext>` 兜底（登记 G-7）；若要服务端名，需要改 client 回调（超出本轮白名单）。
- **A6 · 截图数量**：D10 要求 4 角色 × 4 页 + 3 个负向场景；本 PRD 取 16 + 4 = 20（额外 `portal-mgmt.png`），与产品记忆里「20 张角色截图」一致。

### (b) 用户取舍（`kind: taste`）

- **B1 · /portal 筛选条的作用半径**：默认「域/厂商/时间窗作用于指标卡、最近报表与个人卡；待办与快捷入口是行动清单，不随筛选变化，筛选条旁一句话说明」。备选「筛选只作用于指标区，列表与个人卡保持全量」。两种都自洽，差别在用户对「改了筛选待办没变」的容忍度。
- **B2 · /reports 默认落地哪一个 Tab**：默认「我的报表」；备选「共享给我优先」（偏协作）或「按上次选择记忆（写 localStorage）」。影响用户第一眼看到什么。
- **B3 · ROLE 共享档怎么选角色**：默认「保留五档，ROLE 用数字角色 ID 输入 + 说明（G-2）」；备选「本轮隐藏 ROLE 档，只暴露四档并在弹层注明待角色字典」。数字 ID 对非工程用户不友好，但隐藏会少一档能力。
- **B4 · 无数据时是否保留空区**：默认「保留区标题 + 一句话空态」（顺序稳定、可预期，vendor1/po1 页面会较长）；备选「整区隐藏，只留指标区」（页面更短，但不同角色看到的区块数不同）。

## 12. 工程约束

1. **分支与工作树**：本轮在 `feat/analytics-portal-views` 上完成；不新开分支、不 rebase、不 `git clean`。当前未提交改动 2 处，均为未跟踪目录：`docs/teamflow/20261110-r12-analytics-portal-views/`、`docs/teamflow/20261111-r13/`——保留并随本轮提交。
2. **design 阶段本轮跳过**（上游长输出断流 26 次）：tech 阶段开头先把本文件 §5 的十条决策精简引用落盘为 `docs/teamflow/20261111-r13/DESIGN.md`（引用本文件，不重写长文），再进技术方案；不得重开设计讨论。
3. **只做前端视图，不改后端语义**：`不改 deliver-service / req-service 的接口与迁移；如发现接口缺口，只记录到任务夹，不擅自改后端`。G-1…G-7 写入本文件与 `EVIDENCE.md`。
4. **复用单点，禁止重写**：`web/src/api/analytics.ts`、`web/src/stores/analytics.ts`、`web/src/router/routes/analytics.routes.ts`、`web/src/layouts/nav.ts` 均不得重写；只允许 A1 所述类型/`reqType` 最小改动。四视图文件名必须与路由 `lazyView(...)` 一致；视图内不得出现第二套 fetch/防抖/URL 解析。
5. **保留 /workbench（M6 质量工作台）零改动；不要与 /portal 合并**。路由与导航已注册，不改 nav 顺序/条目、不改 `functionCode`。
6. **视觉**：以 `docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md` 为准：`#2B6FE5` 主色、白卡片 + 1px `#E5E6EB`、状态浅底方角 pill、行高 44–48、hover 出行操作、浮层用 popover；可借用 `docs/teamflow/20261108-r2-devops-platform/prototype/style.css` 的 `.page/.card/.kpi/.pill/.table`。`Element Plus 为主，不引入新 UI 框架`；图表用已有 echarts 6。
7. **可选能力探测**：字典（域/厂商/人员）及任何集成配置/执行器台账/审批角标探测必须 `silentForbidden`，403 只做页内降级，不触发全局 `/403` 跳转（r8 约定）。
8. **门禁**：`web npm run build` 与 `vue-tsc --noEmit` 必须 0 错误；`make lint` / `make access-verify` 不回归；4 条新路由用现有 `PORTAL_VIEW` / `REPORT_VIEW` 码纳入 a2 矩阵；a3 基线同步 33→36；总断言下限 115→143。
9. **自验**：`用无头 Chrome + CDP 注入 sessionStorage('devops.session') 截图 4 个页面（至少 admin、pm、vendor1、po1 四个角色）`；空态、单区降级、409 pin 上限三个负向场景必须有截图或断言；截图与逐项结论写进任务夹，不要只写「已验证」。脚本放 `logs/teamflow/<runId>/`（run 结束由 host 归档），证据 PNG 放任务夹 `evidence/`。
10. **.gitignore 审计**：现有规则已覆盖 `node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`target/`、`.env*`、`logs/`、`*.xlsx`、`*.csv`、IDE、`.DS_Store`；本轮若产生 `pnpm-debug.log*`/`npm-debug.log*`/`.eslintcache` 则补规则；**确认不要** 忽略 `docs/teamflow/**/evidence/*.png`（截图必须入库），导出测试件落临时目录。
11. **提交**：收尾只做一次断点提交，只纳入 AC-1 白名单文件；不提交 `logs/teamflow/`、导出 xlsx/csv、临时截图脚本。
12. **不做**：`不做移动端适配、不做暗色主题、不做拖拽自定义布局、不做报表订阅推送、不加新数据集、不改后端公式、不做 E2E 框架（截图自验即可）`。

## 13. 交付物

- 本文件：`docs/teamflow/20261111-r13/PRD.md`。
- 设计输入：`docs/teamflow/20261111-r13/DESIGN.md`（tech 阶段按 §5 落盘）。
- 代码：四个视图 + `components/analytics/**`、`analytics.ts` 类型对齐、store `reqType`。
- 门禁：a2/a3 脚本更新与 `access-verify.sh` 下限更新。
- 证据：`docs/teamflow/20261111-r13/evidence/*.png`、`EVIDENCE.md`（后续 QA/ACCEPTANCE 同夹）。
- 缺口登记：G-1（无 unpin）、G-2（无角色 id 字典）、G-3（无匿名运行端点）、G-4（Dashboard/CrossTab/Quality 类型漂移）、G-5（看板筛选不入 definition）、G-6（列表无重命名入口）、G-7（导出文件名读不到响应头）。
