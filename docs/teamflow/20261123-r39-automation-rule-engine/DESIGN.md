<!-- meta: stage=design; task=r39-automation-rule-engine; summary=四个自动化视图收进「平台管理 → 自动化规则」页内双 tab；规则渲染为可读因果句（触发/作用域/条件/动作 token），执行日志人话在前、枚举码可查；零新依赖、零新色板、4 组件 / 3 令牌 / 1 断点（1280）。 -->

# DESIGN · r39 CTeam 自动化规则引擎

> 基线依赖：`docs/teamflow/20261123-r39-automation-rule-engine/PRD.md`（AC-2 / AC-3 / AC-7 / AC-8 / AC-11 / AC-12 / AC-13 / AC-17 是本文件的硬约束）
> 视觉基线：`docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md` + `web/src/styles/tokens.css` + `web/src/styles/base.css` + `web/src/views/admin/admin-shared.css`（`.adm*` / `.surface` / `.t-*` / `.filter-bar` / `.row-actions` / `.skeleton`）
> 阅读顺序即优先级：§0 观感与手感 → §1 改动面 → §2 IA → §3 线框 → §4 交互与状态 → §5 视觉 → §6 无障碍 → §7 AC 对照 → §8 待确认
> 新增依赖 **0**、新色板 **0**、新路由 **4**（`/automation/rules`、`/automation/rules/new`、`/automation/rules/:id/edit`、`/automation/runs`）、新组件 **4**、新令牌 **3**、新断点 **1**（1280）。**不新增页面级设计体系**，全部沿用 Element Plus + 既有 `.surface` 语言。

---

## 0. 观感与手感（施工口径，先读）

### 0.1 一句话意图

**规则引擎的界面不是一张表单，而是一句能被读出来的因果句**：「当什么 · 在哪个范围 · 满足什么 · 就做什么」。列表、表单摘要、执行日志三处用**同一套 token 语言**渲染同一句话——用户不必在四个下拉框之间自己拼语义，也不必在日志里把 `SKIPPED/CONDITION_NOT_MET` 翻译成中文。

执行日志是**研判记录**，不是流水账：一行一句结论（成功 / 部分成功 / 失败 / 跳过 + 人话原因），逐动作明细与枚举码收在展开区（mono 可复制），既给 PMO 看，也给值班 grep。

情绪：安静、可核对、因果清晰。全页的重量只给「做什么」（动作 token 与结果结论）；颜色只承担状态语义，不承担装饰。**没有渐变、没有发光、没有大圆角、没有插画、没有任何自动播放的动效。**

### 0.2 表面处理（按组件类）

| 组件 | 规格 |
|---|---|
| 管理子导航（既有 `.adm-nav`） | **不改样式**，只追加一项「自动化规则」（36 高，图标沿用既有 16 线性图标位）。当前项仍为 `--bg-selected` + 主色文字，无左侧色条 |
| 页内 tab（规则 / 执行日志） | 文字 tab：13/500、高 36、间距 24；未选 `--text-body`，选中 `--text-title` + 2px 主色下划线（`box-shadow:inset 0 -2px 0`，不占位）；hover 文字转 `--text-title`，`--t-color` 120ms |
| 规则表 | 复用 `el-table`：表头 40 高、12/`--text-secondary`、底 `--bg-table-head`；行底 `--bg-card`、分隔 1px `--divider`、hover `--bg-hover`；**行高 64–80**（见 §5.2 算式），不做固定行高 |
| 规则名 | 14/500 `--text-title`，单行 `text-overflow:ellipsis`（名 ≤100 字符，全文进 `title`）；它是进入编辑页的链接，hover 仅加 `text-decoration:underline`，**不改颜色** |
| 版本 chip | `v3`，mono 12、高 18、`padding:0 4`、圆角 `--r-cell`(4)、底 `--bg-hover`、字 `--text-secondary`；**紧贴规则名右侧 6px**，不单独成列 |
| 句式 `.rule-sentence` | 一行 flex + `flex-wrap`，`gap:6`，12/400，行高 18；关键字「当 / 且 / 则」12/400 `--text-muted-aa`；token chip 高 18、`padding:0 6`、圆角 `--r-cell`(4)、12/400；连接用字符 `→`（12 `--text-secondary`），**不画箭头 SVG、不用图标库** |
| token · 触发（句子起点） | 底 `--color-primary-light`、字 `--color-primary`、**无边框**——全句唯一有底色的 token，用来一眼定位「什么时候」 |
| token · 作用域 | 底 `--bg-card`、1px `--border`、字 `--text-body`（中性：它只是范围，不是结论） |
| token · 条件 | 1px `--border`、字 `--text-body`、无底色；条件 >3 个时第三枚起折叠为 `+N`（title 列全） |
| token · 动作（落点） | 1px `--border-strong`、字 `--text-title`——同为 chip，靠**更深的边与更重的字**承担全句重量，不加底色不加粗到 500 以上 |
| 停用行的句式 | **不整行降透明度、不 `opacity`、不灰掉**：仅句内所有 token 字色降为 `--text-muted-aa`（对白底 ≈4.8:1 仍可读），规则名保持 `--text-title`。停用后仍必须能核对「它到底配了什么」 |
| 状态单元 | `el-switch`（40×20，开启为主色）+ 右侧 20 高 pill：启用 `--color-success-bg`/`--success-ink`，停用 `--color-info-bg`/`--info-ink`；**开关常驻可见**（止血入口，不做 hover 才出现） |
| 优先级单元 | mono 12 `tabular-nums`，直接显示数值（`100`），`title="数值小者先执行；同优先级按创建先后"`；**不翻译成高/中/低**（档位是伪语义，PRD 里就是可调数字） |
| 最近执行单元 | 12px：「2 分钟前 · 成功」，相对时间在前、结论在后（成功 `--success-ink` / 部分 `--warning-ink` / 失败 `--danger-ink` / 跳过 `--text-muted-aa`），`title` 放绝对时间；整格是**链接**到带 `ruleId` 筛选的日志页 |
| 行操作 | 常驻两个文字链接「编辑」「日志」（`el-button link type="primary" size="small"`），**不做 hover 才出现**（本页操作涉及启停与排障，隐藏操作等于增加误操作） |
| 页面主操作 | 「新建规则」`el-button type="primary"`（默认 32 高），位于页面标题行右侧；行内/表单内次级操作 26（`size="small"`） |
| 筛选条 | 复用 `.filter-bar`；搜索框 240 宽，两个 `el-select` 各 132/120；右侧 12px「共 12 条 · 启用 10」计数 |
| 表单 | 单列、label 上下堆叠（本页统一）：label 12/500 `--text-body`，控件 32 高、圆角 `--r-ctl`(6)、focus `--focus-ring`；helper 12 `--text-muted-aa`；段间距 20，段标题 14/600 + 12px 段说明 |
| 触发卡（4 选 1） | `grid-template-columns:repeat(2,minmax(0,1fr))`、gap 12；每卡 **76 高**、`padding:12 14`、1px `--border`、圆角 `--r-card`(8)、无阴影；标题 13/500 + 描述 12/`--text-muted-aa`（≤16 字，单行）；选中：1px `--color-primary` + 底 `--bg-selected` + 标题转主色，**不加阴影、不加勾选角标** |
| 条件行 | 高 44、行间 1px `--divider`（最后一行无）；左起 16px 复选框（未勾选 = 该键不出现，即 AND 里的省略）、96px 固定标签、控件 flex、右端「清除」文字按钮 24 宽 |
| 动作卡 | 最小高 92：`padding:12`、1px `--border`、圆角 `--r-card`；首行 = 类型 4 选 1 的分段控件（28 高）+ 右侧 ↑ ↓ 与「移除」；次行 = 该类型参数控件（32 高）。**不做拖拽排序**（键盘不可达、误触率高） |
| 摘要栏 | 320 宽、底 `--bg-card`、1px `--border`、圆角 `--r-card`、`padding:16`、无阴影、`position:sticky;top:16`；内部即同一枚 `.rule-sentence`（12px，可折行）+ 12px 执行语义说明 |
| 执行日志表 | 行高 44；结果列 20 高印章 + 一行 12px 人话原因（原因超长 1 行 ellipsis，全文进展开区）；展开行底 `--bg-hover`、`padding:12 16`，内含「逐动作结果」小表（表头 32、行 32）+ mono 环境行 |
| 停用提示条 | 编辑页顶部，复用既有 `.readonly-banner` 语言：底 `--color-warning-bg`、字 `--warning-ink`、左侧 16px「!」字形（无图标库）、`role="status"`，不可关闭（停用是持续事实，不是一次性提示） |
| 弹窗 | 停用/启用二次确认 `el-dialog` 宽 440，圆角 `--r-card`、`--shadow-pop`，标题 16/600；**不做 drawer**（表单是整页，不叠加浮层） |

**没有的东西**：无渐变、无发光、无毛玻璃、无大圆角、无卡片阴影、无左侧色条、无插画、无数字滚动、无骨架呼吸（沿用 `.skeleton` 既有实现）、无新图标。

### 0.3 状态反馈

| 状态 | 规格 |
|---|---|
| hover · 行 | 底 `--bg-hover`，`--t-color` 120ms；行内不动任何尺寸 |
| hover · 链接/文字按钮 | 加 `text-decoration:underline`，颜色不变；120ms |
| hover · 触发卡 / 动作卡 | 边框仍 `--border`（未选中卡不是可点目标，**不给可点暗示**），仅标题文字转 `--text-title` |
| focus-visible | 统一 `--focus-ring`（2px 主色 + 2px offset，`base.css` 既有）；开关、tab、卡片内的每个可点元素、条件复选框、动作的 ↑↓ 全部键盘可达 |
| active / pressed | 仅颜色到 `--color-primary-active`；**不做位移、缩放、弹簧** |
| disabled（无 `automation.action.manage`） | 沿用 r25/r26 口径：底 `#F2F3F5`、字 `#A9AEB8`、边 `#E5E6EB`、`opacity:1`、无阴影、`cursor:not-allowed`，`type` 由 primary 降为 default；**禁止 `pointer-events:none`**（会杀死 `title` 原因通道）；点击零反馈 |
| 启停请求中 | 开关 `loading` + 该行其余操作按钮 disabled；成功后开关落位、该行 `.flash-ok` 600ms 渐隐；失败则**开关回弹到原值**并出现 `role="alert"` 常驻 banner（不用 toast 报告失败） |
| 保存成功 | 返回列表，目标行 `.flash-ok` 600ms；`ElMessage.success` 一句「规则已保存并启用 / 已保存（停用状态）」 |
| 保存冲突（409） | 编辑器顶部 `.action-banner` 常驻：「规则已被他人修改（你基于 v3，服务端已到 v4）。刷新后可重新编辑。」+ 「刷新」按钮；**不自动覆盖、不弹二次框** |
| 条件宽于作用域（前端预判） | 摘要栏底部 12px `--danger-ink` 一行说明 + 保存按钮 disabled（服务端仍以 400 `CONDITION_WIDENS_SCOPE` 兜底，前端只是提前说人话） |
| 展开 / 收起日志行 | **无高度动画**，立即展开；箭头用 `▸ / ▾` 字符切换，无旋转过渡（减少动效预算，`prefers-reduced-motion` 无需第二套样式） |

### 0.4 动效、加载、空态、错误

- **动效总预算**：只允许颜色/背景 120ms（`--t-color`/`--ease`）、既有弹窗 240ms 遮罩、成功后 600ms `--flash-ok` 渐隐。**不做位移、缩放、骨架呼吸、进度条动画、列表入场、规则句式打字机效果。** `prefers-reduced-motion:reduce` 下仅取消闪光，其余本来无动画。
- **加载**：规则列表 → 4 行 skeleton（64 高，底 `--divider`，圆角 4，不写「加载中」）；日志 → 8 行 44 高 skeleton；编辑页 → 3 块段状 skeleton（基础信息 / 条件 / 动作）；摘要栏 → 2 行 18 高 skeleton。**加载中禁用保存按钮**。
- **空态（区分四种，绝不混用）**：
  - 规则为空（`variant="empty"`，icon `inbox`）：「还没有自动化规则」+「规则 = 触发 + 条件 + 动作；保存即生效。可以先看两条预置规则的写法：P0/P1 新建通知域负责人、人天 > 10 打标。」+ 主操作「新建规则」（无权限时不渲染 action，仅文案）。**不显示假数据占位行。**
  - 筛选无结果（`variant="filtered"`，icon `search`）：「没有匹配的规则」+「清除筛选」。
  - 日志为空（`variant="empty"`，icon `chart`）：「规则命中后会在这里出现」+「这里只记录进入判定的候选规则——**条件没命中也有一行**，并写明原因。」
  - 无权限（`variant="forbidden"`，icon `lock`）：「仅平台管理员与内部 PMO 可管理自动化规则」+「厂商账号不能作为规则的操作者；需要平台侧自动化请联系 PMO。」
- **错误**：整页接口失败 → `ErrorState`（既有组件）+ 重试，**不渲染半张表**；单行启停失败 → 行内不改 + 顶部 banner；日志展开明细失败 → 展开区内 1 行 12px「逐动作明细暂不可用 · 重试」，不收起行。
- **禁止用 toast 报告「规则已触发 / 已执行」**：执行是异步的（AC-13），toast 会在业务提交瞬间说谎。执行结果只出现在日志页，保存/启停成功才 toast。

### 0.5 排版角色与反模式

| 角色 | 规格 |
|---|---|
| 页面标题 | 16/600 `--text-title`（沿用 `.page-head`） |
| 段标题 | 14/600（「基础信息 / 什么时候 / 作用域 / 条件 / 动作」） |
| 表内主文本（规则名） | 14/500，单行 ellipsis |
| 句式与辅助说明 | 12/400；说明类用 `--text-muted-aa`（≥4.5:1） |
| 数值（优先级 / 耗时 / 计数） | `font-variant-numeric:tabular-nums`；环境值（event id / run id / 哈希）用 `.mono` 12 |
| 结论词 | 12/500：成功 `--success-ink`、部分 `--warning-ink`、失败 `--danger-ink`、跳过 `--text-muted-aa` |

**本产品的反模式（施工时逐条对照）**：

1. **不给一句「原始 JSON」预览框**——规则的可读形态就是句式，JSON 是服务端契约，露出来等于邀请用户写坏 schema。
2. **不用颜色单独表达「命中了没有」**：结论必须是一个词（成功/跳过）+ 一句原因，色只是辅助。
3. **不把停用规则灰掉/半透明**：停用是最需要被核对配置的时刻。
4. **不出现裸枚举码**：`REQUIREMENT_CREATED`、`CONDITION_NOT_MET` 只允许出现在 `title`、展开区 mono 行、日志复制区。
5. **不把四个触发类型做成一个下拉框**：这是个需要「理解后果」的选择（创建 / 状态 / 字段 / 定时），四张卡各带一句人话。
6. **不在条件为空时显示「全部需求」大字**：写 12px「无条件：作用域内的全部需求（每次触发都会执行）」。
7. **不给日志页做自动刷新/跳动**：只提供「刷新」+「更新于 11:02:13」，避免用户把轮询当成实时因果。

---

## 1. 改动面（新增 / 修订，逐项标注）

| 类型 | 文件 | 改动 |
|---|---|---|
| 新增 | `web/src/views/admin/automation/RulesListView.vue` | `/automation/rules` 列表页（含空/加载/错误态、启停、筛选、排序） |
| 新增 | `web/src/views/admin/automation/RuleEditorView.vue` | 新建与编辑共用（`route.name` 判模式；`/new` 与 `/:id/edit`） |
| 新增 | `web/src/views/admin/automation/RunsView.vue` | `/automation/runs` 执行日志（筛选 + 展开明细） |
| 新增 | `web/src/views/admin/automation/automation.css` | 本域独有结构（`.rule-sentence`、触发卡、条件行、动作卡、摘要栏、日志展开）；**通用表面一律不复制** |
| 新增 | `web/src/components/AutomationRuleSentence.vue` | **唯一句式渲染器**：入参 = 规则对象 + `size`（`list`/`preview`），列表、摘要、编辑回显三处共用，杜绝文案漂移 |
| 新增 | `web/src/components/AutomationRunVerdict.vue` | 执行结果印章（5 态 + 跳过原因人话），20 高、6px 方角、tone 对；**不改 `StatusPill`**（避免污染 dict 词表） |
| 新增 | `web/src/components/TagChips.vue` | 需求标签 chip（列表 `max=3` + `+N`，详情不限；`title` 说明来源） |
| 新增 | `web/src/api/automation.ts` | 四个接口封装（规则 CRUD/启停、日志分页），沿用 `api/client.ts` 错误规范化 |
| 修订 | `web/src/views/admin/AdminLayout.vue` | 子导航追加 1 项「自动化规则」（其余项不动） |
| 修订 | `web/src/layouts/nav.ts` | 平台管理组追加 1 项 `routeName: 'automation-rules'`、`pageCode: 'automation.rules'`、`functionCode: 'automation:manage'` |
| 修订 | `web/src/router/routes/deliver.routes.ts`（admin 分支） | 追加 4 条路由；`meta.pageCode` 均为 `automation.rules`（日志页继承同一页面码，PRD 3.8 只定义了一个） |
| 修订 | `web/src/layouts/pageRegistry.ts` | `automation-runs` 与 `automation-rule-*` 显式注册到 `automation.rules`（any-of 守卫） |
| 修订 | `web/src/views/req/RequirementPoolView.vue`、`RequirementDetailView.vue`（+ `tabs/OverviewTab.vue`） | 在既有域/厂商 chip 之后插入 `<TagChips>`；**只加不改**，不改既有 chip 的几何与顺序 |
| 修订 | `docs/teamflow/.../DESIGN.md` 之外的产品文档 | 不在本阶段改动（`DEMO-BY-ROLE.md` / `ARCHITECTURE.md` 归开发与验收） |

**不改**：`StatusPill.vue`、`EmptyState.vue`、`ErrorState.vue`、`admin-shared.css`、`tokens.css` 既有令牌值、`web/package.json`（零新依赖）。

---

## 2. 信息架构

```
平台管理（AdminLayout，左 160 子导航）
└── 自动化规则            pageCode=automation.rules  functionCode=automation:manage
    ├── tab「规则」      /automation/rules            name=automation-rules
    │   ├── 新建         /automation/rules/new         name=automation-rule-create
    │   └── 编辑         /automation/rules/:id/edit    name=automation-rule-edit
    └── tab「执行日志」  /automation/runs              name=automation-runs
```

| 路径 | name | 页面码 | 动作码 | 说明 |
|---|---|---|---|---|
| `/automation/rules` | `automation-rules` | `automation.rules` | `automation.action.manage` | 列表；nav 唯一入口 |
| `/automation/rules/new` | `automation-rule-create` | 同上 | 同上 | 整页表单 |
| `/automation/rules/:id/edit` | `automation-rule-edit` | 同上 | 同上 | 同组件，带 `version` |
| `/automation/runs` | `automation-runs` | 同上（继承） | 同上 | 日志；从列表「日志」链接带 `ruleId` 进入 |

- **导航只加一项**：页面码只有一个，若在侧栏放两项会与 `s1` 三向比对（`MenuRegistry` / `sys_menu` / `nav.ts`）对不上；两个视图用过页内 tab 承载，符合 UI-STYLE §2「文字 tab + 2px 主色下划线」。
- **厂商账号**：`me.vendorId != null` 时 nav 不渲染该项、路由守卫直接拦到 `forbidden` 页面（不是白屏、不是 404）；服务端 403 `INTERNAL_ONLY` 时页面把 `ErrorState`/`forbidden` 文案按服务端 `code` 原样呈现。
- **面包屑**：`平台管理 / 自动化规则 / 新建`；编辑页追加规则名（超长 ellipsis）。

---

## 3. 线框（布局 / 组件 / 状态）

### 3.1 规则列表 `/automation/rules`

```
平台管理 · 自动化规则                                  [ 新建规则 ]   ← 页面标题 16/600 + 主按钮
────────────────────────────────────────────────────────────────────
 规则          执行日志                                   ← tab 36 高，选中项 2px 主色下划线
────────────────────────────────────────────────────────────────────
[搜索规则名…] [触发 ▾] [启停 ▾]              共 12 条 · 启用 10     ← .filter-bar
┌────────────────────────────────────────────┬──────┬────────────┬────────────────┬──────────┐
│ 规则                                       │ 优先级│  状态      │  最近执行      │ 操作     │
├────────────────────────────────────────────┼──────┼────────────┼────────────────┼──────────┤
│ P0/P1 新建通知域负责人  v3                  │ 100  │ (●) 已启用 │ 2 分钟前·成功  │编辑 日志 │
│ [需求创建][订单域] 且 [优先级 P0/P1] → [通知 域负责人]                                     │
├────────────────────────────────────────────┼──────┼────────────┼────────────────┼──────────┤
│ 大需求自动打标  v1                          │ 110  │ (○) 已停用 │ 昨天·已跳过    │编辑 日志 │
│ [字段变更][订单域] 且 [人天 ≥10] → [打标 大需求]                    ← 停用：token 字色 muted │
└────────────────────────────────────────────┴──────┴────────────┴────────────────┴──────────┘
                                                ← 默认排序：启用在前 → 优先级升序 → id 升序
```

- 列宽（视口 1440，内容区 1024）：规则 = 自适应（570）、优先级 76、状态 96、最近执行 150、操作 132。`≤1280` 视口隐藏「最近执行」列，该信息并入句式行尾（12px muted，如 `· 2 分钟前 成功`），**不丢信息**。
- 行高：名称 20 + 4 + 句式 1 行 18（含底 padding 20）= **64**；句式折 2 行 = **80**。禁止 `line-clamp` 截断句式——规则必须能被完整读出。
- 规则名为链接（进编辑）；「日志」链接带 `?ruleId=<id>` 跳日志页；若最近一次是 `SKIPPED`，该单元格文案为「2 分钟前 · 已跳过 · 查看原因」。
- 状态列：开关左、pill 右；开关点击 → 二次确认（停用文案：「停用后，下一次事件起不再执行；已产生的执行日志与审计保留。」/ 启用文案：「启用后，作用域内触发类型匹配的事件将立即开始执行。」）。

### 3.2 新建 / 编辑 `/automation/rules/new`、`/:id/edit`

```
平台管理 / 自动化规则 / 新建自动化规则            v3(编辑态)      ← 标题 + 版本 chip
⚠ 这条规则已停用：下次事件起不会执行。启用后立即生效。            ← 仅停用态，.readonly-banner
┌──────────────────────────────────────────┬─────────────────────┐
│ 基础信息                                  │  摘要预览            │
│  规则名称  [________________________]      │  当 [需求创建]       │
│  优先级    [100]  数值小者先执行           │  · [订单域]          │
│  启用      (●)                             │  且 [优先级 P0/P1]   │
│                                          │  → [通知 域负责人]    │
│ ① 什么时候（4 选 1，卡 76 高，2×2）        │  ─────────────      │
│  [需求创建]  [状态流转]                    │  作用域内、触发类型   │
│  [字段变更]  [每天定时]                    │  匹配的每个事件都会   │
│                                          │  留下一条执行记录；   │
│ ② 作用域   (域 | 厂商)  [订单域 ▾]         │  动作按顺序执行，     │
│   规则只能看见并作用于这个范围              │  最多 10 个。         │
│                                          │                     │
│ ③ 条件（全部满足，可留空）                 │  （sticky, top:16）  │
│  ☑ 需求类型   [BUS][PDR][RD]              └─────────────────────┘
│  ☑ 优先级     [P0][P1]
│  ☐ 状态       [变更为… ▾]
│  ☐ 审批结果   [通过][驳回]        ← 仅「状态流转」显示
│  ☐ 预估人天   [≥ ▾] [10]
│  ☐ 域 / 厂商  [只读：= 作用域]     ← 只能收窄，超出即前端报错
│                                          ← 「字段」行仅「字段变更」显示
│ ④ 动作（按顺序执行，最多 10 个）           [+ 添加动作]
│  ┌────────────────────────────────────┐
│  │ 通知 改负责人 打标签 Webhook   ↑ ↓ 移除│
│  │ 收件人 [域负责人 ▾]                  │
│  └────────────────────────────────────┘
├──────────────────────────────────────────┴─────────────────────┤
│ 取消                                             [ 保存 ]        ← sticky footer
└────────────────────────────────────────────────────────────────┘
```

- **摘要栏与表单同源**：摘要用 `AutomationRuleSentence.vue`（`size="preview"`），列表用同一组件，三处文案永不漂移（组件化是硬要求，不许三处各写一份拼接）。
- 宽度算式见 §5.2；`≤1280` 摘要栏落到表单下方（320 → 全宽），**同一断点**同时触发列表隐藏「最近执行」列。
- 条件区**没有 JSON 文本域**；未知键无从输入，服务端的 400 只剩「服务端版本比前端新」这种真异常。

### 3.3 执行日志 `/automation/runs`

```
平台管理 · 自动化规则
 规则          执行日志
──────────────────────────────────────────────────────────────────
[规则 ▾] [结果 ▾] [需求编号 ______] [时间: 最近 7 天 ▾]  [刷新]  更新于 11:02:13
┌──────────┬──────────────┬──────────┬────────┬────────────────────┬──────┬──┐
│ 时间      │ 规则          │ 需求      │ 触发    │ 结果 / 原因         │ 耗时  │  │
├──────────┼──────────────┼──────────┼────────┼────────────────────┼──────┼──┤
│ 11:01:42 │ P0/P1 新建…   │ REQ-0142 │ 需求创建│ 成功 · 通知 2 人    │ 38ms │▸ │
│ 11:00:10 │ 大需求自动打标 │ REQ-0139 │ 字段变更│ 已跳过 · 条件未命中 │312ms │▸ │
│ 10:58:03 │ 大需求自动打标 │ REQ-0138 │ 字段变更│ 部分成功 · 通知失败 │5012ms│▾ │
├──────────┴──────────────┴──────────┴────────┴────────────────────┴──────┴──┤
│ 展开：                                                                    │
│   # 动作          结果    说明                                             │
│   1 打标「大需求」 成功    新增标签                                        │
│   2 通知 域负责人  失败    NO_RECIPIENT → 收件人 0 人                      │
│   ── mono 环境行：run 8841 · rule v3 · event evt_9f2c · 触发 10:58:03 · 深度 1 │
└──────────────────────────────────────────────────────────────────────────┘
```

- 结果列文案固定五套：`运行中`（`--color-info`）/ `成功`（success）/ `部分成功`（warning）/ `失败`（danger）/ `已跳过`（muted）+ 12px 原因。原因表见 §4.3。
- 默认时间范围 = **最近 7 天**，并且**必须**在筛选条左侧显示为可移除 chip「时间：最近 7 天」——不允许静默筛选（用户看到空表时必须知道是筛掉了还是真没有）。
- 无自动刷新：`刷新` 按钮 + 「更新于 hh:mm:ss」12px；页面 `loading` 时按钮 `loading`。
- 行展开：无动画，`aria-expanded` 同步；展开区只读，不提供「重跑」（至多一次，重放是服务端语义禁区）。

### 3.4 需求标签 chip（既有页面内的插入）

```
需求池卡片                         需求详情 · 概要
┌────────────────────────┐        ├ 域 订单域 · 厂商 — · 人天 12
│ 会员中心改版            │        ├ [大需求] [跨端]        ← 18 高 chip，最多换行
│ [业务需求] [订单域] +1  │        └ …
│ [大需求] 人天 12  11-23 │
│ 子 2/3        ← footer │        title="自动化规则添加" / "手动添加"
└────────────────────────┘
```

- chip 18 高、`padding:0 6`、1px `--border`、圆角 `--r-cell`(4)、12/`--text-body`，与既有 `.req-card__tag` 同族几何，**不改既有域/厂商 chip**。
- 来源（`source=RULE|MANUAL`）**不用颜色区分**，只进 `title`，避免用户把「自动化打的」误读成一种状态。
- 列表最多 3 枚 + `+N`（`title` 列全）；详情不限，换行。
- 数据来源：需求列表/列表响应直接带 `tags[]`；若 tech 未在列表响应带 tags，**必须用一次批量接口**（按当前页 requirementIds），**禁止逐行请求**。

---

## 4. 交互与状态

### 4.1 列表

- 筛选：关键词（名称，300ms 去抖或回车）、触发类型、启停；筛选参数同步到 URL query（`?q=&trigger=&enabled=`），刷新/回跳不丢。
- 排序：状态（启用优先，固定）→ 优先级升序 → id 升序；表头仅「优先级」可点排序（升/降），排序状态写入 query。
- 启停：点击开关 → `ConfirmDialog`（复用既有组件）→ `PATCH /automation/rules/:id/enabled` 带当前 `version`；成功回填新 version（chip 立即从 `v3` 变 `v4`）；409 → 开关回弹 + banner「规则已被他人修改，列表已为你刷新」，并静默 reload。
- 行「编辑」→ `/:id/edit`；行「日志」→ `/automation/runs?ruleId=<id>`（保留 7 天默认范围）。
- 空/加载/错误见 §0.4；无动作码时「新建规则」disabled + `title`（`denyReason` 文案），开关与「编辑」同样 disabled + title，**仍可点「日志」**（只读）。

### 4.2 编辑表单

- 分段：基础信息 → ① 什么时候 → ② 作用域 → ③ 条件 → ④ 动作 → 摘要（右侧/下方）。
- **触发联动**：
  - 切换触发类型时，**保留与之无关的条件值但不提交**（灰显 + tooltip「当前触发类型不使用该条件」），避免用户来回切换时丢配置；提交前按触发类型裁剪。
  - 「审批结果」仅在「状态流转」出现；「字段」行仅在「字段变更」出现；「定时」不展示参数，改为只读信息行「每天 08:30（Asia/Shanghai）· 单规则单日最多扫描 200 条 · 由平台配置」。
- **作用域联动**：选域后，条件里的「域」行变为只读 `= 订单域`（可勾选启用，取值固定）；选厂商同理。用户无法输入比自己作用域更大的范围 → 前端先拦，服务端 400 `CONDITION_WIDENS_SCOPE` 只做兜底。
- **动作**：`[+ 添加动作]` 追加；类型分段 4 项（通知 / 改负责人 / 打标签 / Webhook）；每型参数：
  - 通知：收件人三类（用户 / 角色 / 域负责人）可多选，超过 50 人时**即时**提示「超过 50 位收件人的通知会被拒绝执行」；
  - 改负责人：用户单选（候选限作用域内用户，服务端 `TARGET_INVALID` 兜底）；
  - 打标签：输入 ≤32 字符，实时计数 `12/32`；
  - Webhook：只列**已启用**集成配置的选择器 + `path` 输入；下方 12px「域名与密钥来自集成配置，规则里不保存 URL 与密钥」；未配置时该项 disabled + 「先到 平台管理 → 集成配置 登记」链接。
  - 每个动作卡右上角 ↑ ↓（首/末置灰）与「移除」；移除最后一个动作时禁用保存并提示「至少一个动作」。
- **校验**：失焦即校验（名称必填 ≤100、优先级 0–999 整数、至少 1 个动作、≤10 个动作、标签 ≤32、Webhook 必须选配置）；提交时服务端错误按 `field` 回显（映射表：`name`→名称、`condition.*`→对应条件行、`actions[i].*`→第 i 张动作卡），首个错误 `scrollIntoView({block:'center'})` + 聚焦，**不弹 toast 代替字段错误**。
- 保存：`POST /automation/rules` 或 `PUT /automation/rules/:id`（带 `version`）；成功回列表并高亮该行；409 见 §0.3。
- 编辑页进入：先 GET 规则，`404` → `EmptyState variant="forbidden"`「规则不存在或已被移除」+「返回列表」（不提供硬删除，PRD 5）。

### 4.3 执行日志（人话映射表 · 唯一来源）

| `status` | 中文 | tone |
|---|---|---|
| `RUNNING` | 运行中 | info（12px 后附「执行中…」不转圈） |
| `SUCCESS` | 成功 | success |
| `PARTIAL` | 部分成功 | warning |
| `FAILED` | 失败 | danger |
| `SKIPPED` | 已跳过 | muted |

| `skip_reason` / 动作结果码 | UI 文案（人话） | 展开区 mono 码 |
|---|---|---|
| `CONDITION_NOT_MET` | 条件未命中 | 同左 |
| `RATE_LIMITED` | 超过每分钟执行上限 | 同左 |
| `SCOPE_LOST` | 需求已移出规则作用域 | 同左 |
| `NO_RECIPIENT` | 没有能收到通知的人 | 同左 |
| `TOO_MANY_RECIPIENTS` | 收件人超过 50 人，未发送 | 同左 |
| `TARGET_INVALID` | 目标用户不可指派（不在作用域/已停用） | 同左 |
| `SCHEDULE_TRUNCATED` | 定时扫描达到单日 200 条上限 | 同左 |
| `RULE_ORIGIN` / `CHAIN_DEPTH` | 不出现 run 行（事件级丢弃），在日志页顶部 12px 说明 | 说明文案：「规则自身产生的事件不会再次触发规则，因此这里不会出现它们。」 |

- 列：时间 140 / 规则 180（链接回编辑页）/ 需求 120（mono，链接到需求详情）/ 触发 96 / 结果 自适应（min 220，原因 1 行 ellipsis）/ 耗时 64（mono 右对齐）/ 展开 36。
- 展开区三段：① 逐动作小表（# / 动作 / 结果 / 说明，结果用同一组 tone）；② mono 环境行（run id / rule version / event id / 时间 / 深度）；③ 若 `actions_json` 为空 → 12px「未执行动作（判定阶段结束）」。
- 响应不含密钥与完整 URL（PRD AC-12），UI 也不得把 `path` 拼成可见完整地址（只显示配置名 + path）。

### 4.4 权限与异常

| 场景 | 表现 |
|---|---|
| 页面码 DENY | 路由守卫拦截 → `EmptyState variant="forbidden"`（icon lock），**不白屏、不 404** |
| 厂商账号（`me.vendorId`） | nav 不渲染；直接输 URL 被守卫拦；服务端 403 `INTERNAL_ONLY` 时文案「厂商账号不能作为规则的操作者」 |
| 无动作码 | 写操作 disabled + `title`（`denyReason('automation.action.manage')`），只读可浏览 |
| 规则 404 | 见 §4.2 |
| 网络/500 | `ErrorState` + 重试；已填表单数据保留在内存（不因错误清空） |

### 4.5 演示路径的界面提示（服务 AC-14/18，不做额外 UI）

`make platform-demo-reset` 后列表应有 2 行（R1 `P0/P1 新建通知域负责人`、R2 `大需求自动打标`），且**句式必须一行读完**：`[需求创建][订单域] 且 [优先级 P0/P1] → [通知 域负责人]`。停用 R1 → 开关落位、pill 转「已停用」、句式 token 转 muted、日志页下一次事件 0 新行（演示时用「刷新」证明，而不是等自动刷新）。

---

## 5. 视觉规格

### 5.1 令牌（只增不改，3 个）

```css
/* tokens.css 追加（M10 自动化域） */
--w-rule-preview: 320px;   /* 摘要栏宽（≤1280 落到底部，全宽） */
--h-rule-row-min: 64px;    /* 规则行最小高（句式 1 行）；2 行时自然到 80 */
--h-trigger-tile: 76px;    /* 触发卡高：12 + 20 + 4 + 18 + 12 = 66，留 10 余量 */
```

其余全部引用既有令牌：`--bg-card/--bg-hover/--bg-selected/--bg-table-head`、`--text-title/--text-body/--text-secondary/--text-muted-aa`、`--border/--border-strong/--divider`、`--color-primary(-light/-active)`、`--success-ink/--warning-ink/--danger-ink/--info-ink` 与对应 `-bg`、`--r-ctl/--r-card/--r-cell`、`--t-color/--ease/--focus-ring/--flash-ok`、`--shadow-pop`。**新色板 0、新阴影 0。**

### 5.2 宽度与高度算式（自洽性核对，施工按此写死）

- 内容区宽 = 视口 − 200（全局侧栏）− 40（shell padding）− 160（管理子导航）− 16（间距）= **视口 − 416**。1440 → **1024**；1280 → 864；1600 → 1184。
- 表单页：`摘要 320 + gap 24 + 表单 flex`，1440 时表单 = **680**（≥ 双列触发卡 (680−12)/2 = 334 每卡，卡片描述 ≤16 字 ×12px = 192 < 334−28 padding，**单行必成立**）；`≤1280` 摘要落底部，表单 = 864（限 `max-width: 880`）。
- 规则行：`padding 10/14` + 名称 20 + gap 4 + 句式行 n×18；n=1 → 62 → 取 `--h-rule-row-min: 64`；n=2 → 80。表内 8 个 PRD 字段全部可见（触发/作用域/条件/动作以句式 token 呈现，不省略）。
- 表格列和：规则 auto ≥ 380（1440 时 570）+ 76 + 96 + 150 + 132 = 1024 ✓；`≤1280` 去掉 150 列 → auto ≥ 380 ✓（864 时 452）。
- 日志列和：140 + 180 + 120 + 96 + 64 + 36 = 636 + 结果列（1440 时 388 ≥ 220 ✓；864 时 228 ✓）。
- 触发卡：76 高内含 13/20 标题 + 4 + 12/18 描述 + 12×2 padding = 66 ≤ 76 ✓；描述**单行 ellipsis**，不得写第二行（写到 2 行会溢出 76）。
- 动作卡 92 高：12 + 28（分段控件）+ 8 + 32（参数控件）+ 12 = 92 ✓（参数控件换行时卡片自然增高，不裁切）。
- 印章/pill：20 高含 12px 文本（line-height 20），`padding 0 8`，**最小宽 44**（四字结论「部分成功」= 48 + 16 = 64，无需缩字）。

### 5.3 唯一断点 1280（三处同时变化，别有第四套）

| 视口 ≤1280 | 表现 |
|---|---|
| 列表 | 隐藏「最近执行」列，信息并入句式行尾（12px muted） |
| 编辑器 | 摘要栏从右侧 320 改为表单下方全宽卡片（`position:static`，不再 sticky） |
| 触发卡 | 保持 2×2（334 宽已够），**不降为 1 列** |

### 5.4 颜色语义（沿用，不新增）

| 语义 | 前景 | 底 |
|---|---|---|
| 成功 / 已启用 / 主操作 | `--success-ink` / `--color-primary` | `--color-success-bg` / `--color-primary-light` |
| 部分成功 / 停用提示 | `--warning-ink` | `--color-warning-bg` |
| 失败 / 越权 / 条件超范围 | `--danger-ink` | `--color-danger-bg` |
| 已跳过 / 已停用 / 中性 | `--text-muted-aa` / `--info-ink` | `--color-info-bg` / `--bg-hover` |

**状态永不只靠颜色**：结论永远是词（成功/已跳过/已停用），色只是第二通道。

---

## 6. 无障碍要点

1. **句式可读**：token 之间保留空格与「当/且/则」关键字，屏幕阅读器读到的是完整中文句；`→` 加 `aria-label="则执行"`（视觉仍显示字符）。
2. **开关**：`el-switch` 必须带 `aria-label="启用规则 <名称>"`；状态 pill 是纯文本（不是仅有色点）。
3. **tab 语义**：页内 tab 用 `role="tablist"/"tab"` + `aria-selected`，键盘 ←/→ 切换；tab 与页面标题不重复读两遍。
4. **判据可达**：禁用按钮必须能聚焦或被 `title` 解释——沿用 `ScopeChip` 的 `tabindex="0"` 原因通道；`pointer-events:none` 禁用。
5. **展开**：日志行展开用 `aria-expanded` + `aria-controls`；展开区标题作为 `aria-labelledby`。
6. **错误播报**：字段错误用 `aria-describedby` 绑定；页面级冲突 banner `role="alert"`；停用提示条 `role="status"`。
7. **不依赖动效**：`prefers-reduced-motion:reduce` 下取消 600ms 闪光；所有信息在无动效时仍完整（展开本来就无动画）。
8. **对比度**：12px 辅助文字只用 `--text-muted-aa`（≈4.8:1）或更深；禁用态文字为既有 r25 口径（不达 4.5 但仅禁用且非信息载体）；虚线与描边 ≥3:1 用 `--border-strong`（不用 `--border` 画唯一分界）。

---

## 7. AC 对照

| AC | 设计落点 |
|---|---|
| AC-2 封闭校验 | §3.2 结构化条件（无 JSON 框）+ §4.2 字段级服务端错误映射与首个错误定位 |
| AC-3 权限 | §2 页面码/动作码 + §4.4 四种异常表现；nav 只加一项（s1 三向一致） |
| AC-7 Scope | §3.2 作用域联动（条件只能收窄，前端先拦）+ §4.3 `SCOPE_LOST` 文案 |
| AC-8 动作白名单 | §3.2 动作分段控件仅 4 型 + Webhook 只列已启用配置 + 禁止动作无处可写 |
| AC-11 限流/止血 | §0.3 开关常驻 + §4.1 二次确认与 409 回弹 + §4.3 `RATE_LIMITED` 人话 |
| AC-12 日志语义 | §4.3 五态 + 原因映射表 + 展开三段 + `duration_ms`/`rule_version` mono |
| AC-13 不阻塞 | §0.4 禁止「已触发」toast；保存成功即返回列表，执行结果只在日志页 |
| AC-17 四视图 | §2 路由表 + §3 四张线框 + §0.4 四类空态/加载/错误 + 标签 chip（§3.4） |
| AC-14 演示 | §4.5 两行句式一行读完 + 停用可观察 |
| AC-4/5/6/9/10/15/16/18 | 后端契约与门禁，本文件不承担（§8 语义问项除外） |

---

## 8. 待确认（设计口径，3 条）

1. **状态条件按 `toStatus` 求值**：文案写作「变更为 [状态]」。若 tech 确认取 `fromStatus`，文案改为「从…变更为」，条件行需拆两个下拉（来源/目标）。
2. **列表首列合并触发/作用域/条件/动作为句式**：PRD 3.9 的 8 个字段仍全部可见，但「触发」「作用域」从独立列变成句式 token。若 QA 按列断言，回退方案是恢复两列（句式保留在名称下方，行高不变）。
3. **默认时间范围「最近 7 天」按服务端时区（Asia/Shanghai）计算**，并以可移除 chip 显式展示；若产品要求默认全量，去掉默认值但保留 chip 机制。

<!-- 设计完成 · 本文件为唯一设计交付物；实现阶段不得改 §0 的令牌与几何数字，需改先改本文件 -->
