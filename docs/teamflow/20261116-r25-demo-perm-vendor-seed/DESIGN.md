<!-- meta: summary="r25 设计增量：§0 先钉「禁用+原因」的手感基线（type 随可用性降级、禁禁用态 pointer-events:none、三种可见原因通道），再给 7 个写入口的线框与交互、silentForbidden 降级台账、厂商视角两页的数据态，以及 a6 静态断言与实现的接口约定（/requirements/new 计数=2 的取舍）。" -->
基线依赖：`docs/teamflow/20261116-r24-vendor-pm-role`（VENDOR_PM 授权面与 vpm1 账号）、`docs/teamflow/20261116-r22-action-matrix-datadriven`（动作码即按钮判据）
视觉基线：`docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md` + `web/src/styles/tokens.css` + `web/src/styles/theme.css`。**本轮新增令牌 0、新增组件 0、新增依赖 0**；与旧规范冲突时以本文数值为准，且只复用不改旧值。
对应 PRD：`docs/teamflow/20261116-r25-demo-perm-vendor-seed/PRD.md`（AC-1/2/3 的 UI 部分、AC-5/6/7 的数据态表现、AC-12 的改动面）。

# DESIGN：写入口收敛为「禁用 + 原因」+ 厂商演示视角可见

> **阅读顺序即优先级**：§0 是施工图（dev 只看到这一节也必须能做出对的按钮），§1 起是范围、线框与规则。
> 本轮**不新增页面**、**不改布局骨架**、**不动服务端**。改动面只有 3 个视图（`RequirementPoolView.vue`、`DefectListView.vue`、`IterationsView.vue`）+ AC-3 涉及的探测调用点，其余是「把已存在的形态补齐」。

## 0. 视觉与手感基线（look & feel，先读这节）

### 0.1 意图（一句话）

**安静的闸门**：受限的写入口不是一块骗人的热区，而是一扇关着、并且贴着说明的门——按钮安静地退到次要层，原因一句话就能读到，任何一次点击都不会把人送进 `/403`。

### 0.2 表面处理（全部写死，禁止自由发挥）

| 元素 | 规格 |
|---|---|
| 按钮基准 | 高 **32**（`size="small"` 为 28）、圆角 `--r-ctl` **6px**、文字 13/500、左右内边距 **12**、图标 14、图标与文字间距 4 |
| 主按钮（可用） | 底 `--color-primary #2B6FE5`、白字、**无阴影**、无渐变、无边 |
| 次按钮（可用） | 白底 + 1px `--border #E5E6EB`、字 `--text-body #4E5969` |
| **禁用按钮** | 底 `#F2F3F5`、字 `#A9AEB8`、边 `#E5E6EB`（均来自 `theme.css:53-55` 既有变量）、`cursor: not-allowed`（`theme.css:82` 已实现，不改）、**`opacity` 保持 1**、**不加红色/黄色描边**、**不加阴影** —— 禁用不是错误，也不是加载中 |
| **主按钮降级规则（本轮新增约定）** | 同一按钮的 `type` 随可用性切换：可用 `primary` / 不可用 `default`。这是既有 4 处的一致做法（`ReleaseListView.vue:139`、`TestPlansView.vue:341`、`TestCaseLibraryView.vue:484`、`ReleaseDetailView.vue:481`），本轮沿用并写进规范。理由：UI-STYLE §5「一屏最多 1 个主按钮」——一个蓝色却点不动的主按钮是屏幕上最刺眼的东西，它会把视线从真正能做的事上抢走 |
| 页头范围 chip | **复用 `web/src/components/ScopeChip.vue`**（不新建）：高 22、12px、`tone="info"`、浅底 `#F2F3F5` + `#4E5969`；出现在标题计数之后（需求池）或 actions 之前（缺陷/迭代）。文案「**N 个操作需要权限**」，`scope` 给码列表 |
| 空态主按钮 | 沿用 `EmptyState` 内的 `el-button`（32 高、`primary`）；禁用态同上。**不换文案**——受限时仍显示「提报需求」而不是「清空筛选」，避免布局与语义同时跳变 |
| 内联降级提示（探测 403 后） | 12px `--text-secondary #86909C`、行高 18、前缀一个 12px `!` 线性图标；**不套背景色**（不加黄条：可选依赖缺失不是故障，黄条会被误读成"页面坏了"）；与下拉间距 4 |
| 下拉降级 | 保持渲染、`disabled`、placeholder 改为「无可用选项」、`title` 给原因码 |

### 0.3 状态反馈（hover / active / focus / pressed / disabled）

| 交互面 | 反馈 | 时长/曲线 |
|---|---|---|
| 可用·主按钮 hover | 底 `--color-primary-hover #1F5FD0` | 120ms `--ease` |
| 可用·次按钮 hover | 底 `--bg-hover #F7F9FC`、边 `--border-strong` | 120ms `--ease` |
| **禁用按钮 hover** | **颜色一律不变**，只 `cursor: not-allowed`；原因由浏览器原生 `title` 气泡给出（约 500ms 延迟，属浏览器行为，不自行改造） | 无动画 |
| focus-visible | 2px `--color-primary` outline，按钮 `outline-offset: 2px` | 即时 |
| pressed | 主按钮 `#1A54BC`；次按钮 `#EAF1FE`；**不做位移、不做缩放** | 80ms |
| 禁用→可用（权限集合变更后） | 只换颜色与 cursor，**无过渡动画** | 0ms |
| **点击禁用按钮** | **零反馈**：无 toast、无抖动、无请求、URL 不变、不出现 `/403`。原因只在悬停里，不在点击后 | — |
| 权限受限 chip | hover/focus 出 `ScopeChip` 自带 tooltip；不新增动效 | 既有 |

### 0.4 动效、加载与空态

- 只允许 `color` / `background-color` / `border-color` / `opacity` 过渡；**禁止** scale、bounce、spring、渐变、位移。
- 不做骨架屏：路由守卫在进入页面前已完成权限集合加载，按钮首帧即正确（若实测出现「先可点、后变灰」，就地补 32×72 的 skeleton，**不新增 auth store 字段**——见 §4.5）。
- 空态按「是否筛选」分流，**只有非筛选态**受权限约束（见 §4.4）；筛选态的主操作是「清空筛选」，恒可用。
- 错误态（`ErrorState`）与加载态本轮零改动。

### 0.5 字体角色与文案规则

| 角色 | 规格 |
|---|---|
| 按钮文字 | 13 / 500 |
| 空态标题 / 说明 | 14 / 500 ； 12 / 400 `#86909C` |
| chip 与 `title` 文案 | 12 / 400 |
| 数字（计数徽标、「3 个操作」） | `font-variant-numeric: tabular-nums` |
| **权限码书写** | 一律**半角原样**（`req:report:bus`），不全角化、不改大小写、不加书名号——码要能直接对照 `/admin/permissions` 与 grep 结果 |
| **原因文案唯一模板** | `需要权限：<码>`（全角冒号，码与码之间用 ` / `）。多码场景：`需要权限：req:report:bus / req:report:dev` |
| **前置条件文案** | 非权限类阻塞用中文短句，同样不带前缀：`请先选择版本` |

### 0.6 几何自洽（按最大取值核算，不许出现"放不下"）

- 按钮**不定宽**：3–4 个汉字文案在 13px 下宽约 52，加上下 12 内边距 → **76**；`导出 CSV` 约 88。`primary → default` 降级**不改 padding**，宽度**不变**，因此 admin 与 vpm1 看到同一页头时按钮位置一致（AC 的 A2「收敛过头」可由肉眼直接排除）。
- 页头 actions 行高 32；chip 22 垂直居中、与按钮间距 12。**宽度预算（1440）**：导出 88 + chip 132 + 主按钮 76 + 间距 24 ≈ **320 ≤ 420**，不换行。
- **≤1280 降级（写死）**：chip 省略 `scope`（只留 label「N 个操作需要权限」≤12 字 ≈ 132px），不换成图标、不折两行——`ScopeChip` 本身无折叠能力，本轮**不改造该组件**，只控制传入内容。
- `title` 气泡按浏览器原生渲染：最长文案 `需要权限：req:report:bus / req:report:dev` 约 44 字符，允许自然折成两行，**不依赖任何宽度计算**。
- 空态按钮居中，与说明文字间距沿用 `.empty` 的 `gap: 6px`（不改）。

### 0.7 本产品的反模式（出现即返工）

- ❌ 无权限就 `v-if` 隐藏按钮——与 AC-2 冲突，演示时讲解人无法指着它说「这个角色不能建版本」
- ❌ 禁用后仍保持 `primary` 蓝
- ❌ 给 `.el-button.is-disabled` 加 `pointer-events: none`（会**杀死 title 气泡** → A6 直接失败；`theme.css` 现在是干净的，谁加谁回滚）
- ❌ 点击禁用按钮再弹 toast「你没有权限」——把说明推迟到错误发生的时刻
- ❌ 任何不带「需要权限：」前缀的自造文案，或把码写成中文名（`你没有新建缺陷的权限` 不满足 A6 的可观察口径）
- ❌ 用红/黄错误色表达"没权限"（与 P0 缺陷、驳回状态撞色）
- ❌ 为这 7 处抽 `GuardButton` 公共组件（PRD §3 非目标：不抽公共组件、不动 `usePermission` 契约）
- ❌ 探测请求 403 后仍 `navigate('/403')`

## 1. 范围与改动面

| 文件 | 本轮动作 | 说明 |
|---|---|---|
| `web/src/views/req/RequirementPoolView.vue` | 改 | 页头「提报需求」+ 空态「提报需求」收敛；页头加 chip |
| `web/src/views/deliver/DefectListView.vue` | 改 | 「新建缺陷」收敛；页头加 chip |
| `web/src/views/deliver/IterationsView.vue` | 改 | 「新建迭代」收敛（补动作码维度）；页头加 chip |
| `web/src/components/EmptyState.vue` | **不改** | 已支持 `#action` 具名插槽（`:47-50`），空态按钮由调用方传入即可，零改动 |
| `web/src/composables/usePermission.ts` | **不改** | 已有的 `denyActionReason` 文案是「你没有「X」的操作权限」，**不带** `需要权限：` 前缀，不满足 A6 观察口径；本轮各页用页面内 3 行 computed，不引入新契约 |
| `web/src/styles/theme.css`、`tokens.css` | **不改** | 禁用态变量齐备 |
| `web/src/api/deliver.ts`（探测取数） | 改（仅加可选 options） | 见 §4.4 台账 #3/#4 |
| 新增组件 / 令牌 / 依赖 | **0 / 0 / 0** | |

## 2. 信息架构与模块清单

不改 IA。仅在既有页面头部与空态上增加「受限表达」，模块清单：

| # | 页面 | 受限写入口 | 判据 | 可见原因通道 | 现状 |
|---|---|---|---|---|---|
| 1 | `/requirements` 需求池 | 提报需求（页头） | `can('req:report:bus') \|\| can('req:report:dev')` | chip + 悬停 | 裸跳，须改 |
| 2 | `/requirements` 需求池 | 提报需求（空态） | 同上（同一个 `canReport`） | 悬停 | 裸跳，须改 |
| 3 | `/releases` 版本计划 | 新建版本 | `canAction('release.action.create')` | 悬停 | 已符合 |
| 4 | `/releases/:id` | 提交发布审批 | `canAction('release.action.approve')` | 悬停 | 已符合 |
| 5 | `/testcases` 用例库 | 新建用例 | `canAction('test.action.case_edit')` | 悬停 + 只读 banner（`:647`） | 已符合 |
| 6 | `/test-plans` | 新建计划 | `canAction('test.action.plan_create')` | 悬停 | 已符合 |
| 7 | `/defects` 缺陷 | 新建缺陷 | `canAction('defect.action.create')` | chip + 悬停 | 裸 `openCreate`，须改 |
| 8 | `/iterations` 迭代 | 新建迭代 | `canAction('sprint.action.create')` + 前置 `releaseId` | chip + 悬停 | 只看 `releaseId`，须改 |

**三种"可见原因"通道，按页面形态分工（本轮定型）**：
1. **页头 chip**（列表页，无其它说明位）：需求池 / 缺陷 / 迭代 —— 因为这三个页面此前**没有任何范围提示**，只靠悬停等于把原因藏在鼠标里（AC-1 原文即要求「可见原因」）。
2. **只读 banner**（有详情/编辑态）：用例库 `:644-651` 已有，不动。
3. **逐按钮悬停 title**：所有 7 处必须有，这是兜底通道。

## 3. 关键页面线框

### 3.1 `/requirements` 需求池 · 页头（vpm1）

```
┌ page-head ────────────────────────────────────────────────────────────┐
│ 需求池 [3]  ⚪ 本厂商 · 3 条            [3 个操作需要权限] [导出 CSV] [提报需求] │
│                                              ↑ ScopeChip(info)   ↑ 次按钮  ↑ 灰·禁用 │
└───────────────────────────────────────────────────────────────────────┘
  · chip: label「1 个操作需要权限」，scope「req:report:bus / req:report:dev」
  · 「提报需求」: type=default、disabled、title=需要权限：req:report:bus / req:report:dev
  · 点击: 无请求、无路由、无 toast、不出现 /403
```

admin / pm / po1：chip 不渲染（`deniedCount === 0`），「提报需求」为主按钮且可点——**页头布局与 vpm1 完全同宽同位**。

### 3.2 `/requirements` 需求池 · 空态（vpm1，非筛选）

```
        ┌──────────── EmptyState(variant=empty) ────────────┐
        │                   [inbox 图标]                     │
        │                 需求池还是空的                      │
        │        从「提报需求」开始，业务、产品、研发三类需求… │
        │              [ 提报需求 ]  ← 灰·禁用·title 有原因   │
        └────────────────────────────────────────────────────┘
```
- 筛选态（`isFiltered`）走 `variant="filtered"`：主操作固定为「清空筛选」，**恒可用、不受权限约束**，`@action="reset"`。
- 实现要点：两个 `EmptyState` 分支（`v-if="isFiltered"` / `v-else`），不要用 `#action` + `v-if` 混合——避免 `$slots.action` 恒真导致空的 `empty__actions` 容器占位（`EmptyState.vue:46-50`）。

### 3.3 `/defects` 缺陷 · 页头（vpm1）

```
┌ page-head ──────────────────────────────────────────────────────────────┐
│ 缺陷 [12] ⚪ 金额与处理人字段受限     [1 个操作需要权限] [摘要视图] [导出 CSV] [新建缺陷]│
└─────────────────────────────────────────────────────────────────────────┘
  · 「新建缺陷」: type=default、disabled、title=需要权限：defect.action.create
  · handler 首行兜底：`if (!canCreate) return`
```

### 3.4 `/iterations` 迭代 · 页头（vpm1 / admin 未选版本）

```
┌ page-head ────────────────────────────────────────────────────────────┐
│ 迭代  按版本组织的冲刺，看板按人泳道        [1 个操作需要权限] [新建迭代] │
└───────────────────────────────────────────────────────────────────────┘
```
- title 判定**两段式**（见 §4.2）：未选版本 → `请先选择版本`；选了版本但无动作码 → `需要权限：sprint.action.create`。

### 3.5 `/releases` 版本计划（vpm1，夹具修好后）

```
┌ page-head ───────────────────────────────────────────────────────────┐
│ 版本计划 [1]                              [新建版本]  ← 灰·禁用       │
└──────────────────────────────────────────────────────────────────────┘
┌ release-card ─────────────────────────┐
│ DEMO-REL-1            需求 11 · 进度 …  │  ← 厂商承接链已纳入，卡片与 admin 同款
│ 期段 …                                │
└───────────────────────────────────────┘
```
- **不新增卡片字段、不变卡片样式**；本轮只让数据出现。
- 空态（真实空范围时）沿用 `ReleaseListView.vue:158-165` 现有文案与布局，本轮不改。

### 3.6 `/testcases` 用例库（vpm1，夹具修好后）

```
┌ page-head ────────────────────────────────────────────────────────┐
│ 用例库 3 条用例                    [导入 灰] [导出 CSV] [新建用例 灰] │
├ dl-split ─────────────────────────────────────────────────────────┤
│ 模块树(左)              │ 表格：3 行（DEMO-C-09/10/11）             │
│                         │ ─────────────────────────────────────    │
│                         │ 只读 · 当前账号没有用例编辑能力            │
│                         │ （test.action.case_edit）；可查询、可导出 │
└───────────────────────────────────────────────────────────────────────┘
```
- 只读 banner 已存在（`:644-651`），本轮**不改文案**；「新建用例」「导入」改用统一形态（`primary→default` 降级 + title），语义不变。
- 非目标提醒：`TestCaseLibraryView.vue:552` 的空态在无权限时**直接隐藏**主操作（`:action-text="… : canEdit ? '新建用例' : undefined"`），与 R1 的"统一形态"不一致；但它不在本轮改动面（AC-12），**本轮不改，记入 memory 待办**。

## 4. 交互与状态

### 4.1 判定顺序（一处定义，三处复用）

```
① 前置条件（数据/上下文：如是否选了版本）→ ② 权限（canAction / can）→ ③ 服务端二次校验（不变）
```
前端只用 ② 的既有结果，**不复刻任何规则**（R1 风险的对策）。

### 4.2 `denyTitle` 计算规则（三行，逐页内联）

```ts
const canReport = computed(() => can('req:report:bus') || can('req:report:dev'))
const reportDenyTip = computed(() =>
  canReport.value ? '' : '需要权限：req:report:bus / req:report:dev',
)
```
- **顺序固定：前置条件优先于权限**。有数据阻塞时即使无权限，也显示数据原因（`请先选择版本`）——因为那时"先选版本"才是用户能执行的下一步。
- `title` 为空串（不是 `undefined`）时 Element Plus 仍会渲染空 `title` 属性，无气泡，可接受；统一写 `''` 便于 a6 静态断言。

### 4.3 点击链路

| 情形 | 行为 |
|---|---|
| 可用 | 原逻辑不变（`router.push` / `openCreate`），服务端仍二次校验 |
| 禁用 | `el-button` 原生阻断 → **无请求、无 URL 变化、无 `/403`**；handler 首行 `if (!canX) return` 作为兜底（防 `disabled` 被其他路径绕过，也是 a6 的断言目标） |
| 深链 `/requirements/new` | 仍由路由守卫送 `/403` —— 这是"入口不可达"的正确表达，本轮不改（AC-3 明写） |

### 4.4 探测调用点降级台账（silentForbidden）

规则：**页面内为主表单提供"可选依赖"（字典/下拉/可选项）而发的 GET，必须带 `silentForbidden: true`**；403 时就地降级（空选项 + §0.2 内联提示），**绝不 navigate('/403')**。主查询（列表页主体）不带标记，403 仍走全局页。

| # | 调用点 | API 函数（证据） | 标记 | 结论 |
|---|---|---|---|---|
| 1 | 新建缺陷弹窗：域/厂商/用户下拉 | `api/dictionaries.ts:51/56/61` | **已有** | 覆盖，无需改 |
| 2 | 缺陷页「摘要视图」能力判定 | 页面内 `can` | 不适用 | 豁免：纯前端判定，不发请求 |
| 3 | 用例库新建弹窗：模块下拉 | `api/deliver.ts:217-226 listTestModules` | **无** | **补**：加可选 `opts`，弹窗内传 `{ silentForbidden: true }` |
| 4 | 用例库新建弹窗 / 测试计划抽屉：需求下拉 | `api/req.ts:93-101 listRequirements` | **无** | **补（仅调用点）**：函数加可选 `opts` 参数，**默认值不变**，仅弹窗调用点传标记——否则会把需求池主体的 403 语义改掉 |
| 5 | 新建迭代弹窗：版本上下文 | `api/req.ts:245-254 listReleases` | 无 | **豁免**：版本列表对本栈所有厂商账号均返回 200（空集而非 403，AC-7），且它是迭代页主体查询；理由登记入 a6 台账 |
| 6 | 版本创建弹窗：域下拉 | `api/dictionaries.ts:51` | **已有** | 覆盖 |
| 7 | 测试计划三步抽屉：执行人下拉 | `api/dictionaries.ts:61` | **已有** | 覆盖 |

- **取证方式（给 QA）**：以 vpm1 / vendor1 / po1 三个会话分别打开上述弹窗，观察是否发生 `location.pathname === '/403'`。台账每条都要有"已有 / 补 / 豁免 + 理由"，a6 断言段逐条对应。

### 4.5 权限首帧与「先可点后变灰」

- 现状：路由守卫在进入页面前完成登录与权限集合装配，按钮首帧即正确 → **不需要骨架屏**。
- 若实测出现抖动，只允许在该页 `page-head__actions` 内渲染 32×76 的静态灰块（`--bg-hover`），**不新增 store 字段、不改守卫**。

### 4.6 a6 静态断言与实现的接口约定（**重要，dev 与 tech 必须一致**）

| 约定 | 内容 |
|---|---|
| 需求池跳转点数 | `RequirementPoolView.vue` 中 `router.push('/requirements/new')` **出现 2 次**（页头 + 空态），分别位于两个具名守卫函数内，函数首行为 `if (!canReport.value) return`；模板内**不得**出现 `@click="router.push('/requirements/new')"`。AC-1 的"路径数 = 2"按此实现，**不做单点收敛** |
| 为什么不做单点 | 单点收敛会让字面量只出现 1 次，与 AC-1 的静态断言数目冲突；两者不能各说各话。若 tech 决定收敛，必须同步把 AC-1 与 a6 的计数改为「1 个跳转点 + 2 个绑定」，属改 PRD 口径，需先与 PM 确认 |
| 静态断言目标 | 每个入口的 `<el-button>` 标签内必须同时含 `:disabled="!`、`:title="`（值引用含 `需要权限：` 的 computed/字面量）、以及 `@click` 指向含首行 `return` 守卫的处理器 |
| 不得命中注释 | a6 扫描前须剥离注释与 `<style>`，沿用 a3/a5 的「注释先清空 + 扫描数 ≥1」做法（R4 对策） |

## 5. 视觉规范（全部复用，零新增）

| 用途 | 令牌 | 值 |
|---|---|---|
| 禁用底 / 禁用字 / 禁用边 | `--el-disabled-bg-color` / `--el-disabled-text-color` / `--el-disabled-border-color` | `#F2F3F5` / `#A9AEB8` / `#E5E6EB`（`theme.css:53-55`） |
| 主按钮 / hover / pressed | `--color-primary` / `--color-primary-hover` / `--color-primary-active` | `#2B6FE5` / `#1F5FD0` / `#1A54BC` |
| 正文 / 次要 / 描边 | `--text-body` / `--text-secondary` / `--border` | `#4E5969` / `#86909C` / `#E5E6EB` |
| 圆角 | `--r-ctl` | 6px |
| 过渡 | `--t-color` / `--ease` | 120ms / `cubic-bezier(.2,0,0,1)` |
| 焦点环 | `--focus-ring` | `0 0 0 2px var(--color-primary)` |

字体栈、字号阶梯、间距 4 基准：沿用 UI-STYLE §4，逐字不改。

## 6. 无障碍要点

1. **不隐藏**：受限入口保持渲染（`disabled` 而非 `display:none`），读屏仍能读到按钮名与"不可用"状态——这既是 AC-2，也让"能力边界"对辅助技术可见。
2. **原因可达**：`title` 是鼠标通道；**键盘通道由页头 chip 承担**（`ScopeChip` 是 `tabindex="0"` 的可聚焦 span，见 `ScopeChip.vue:31-40`），focus 即出 tooltip。因此 3 个列表页 `deniedCount ≥ 1` 时必须渲染 chip——否则键盘用户只能看见"灰按钮"，读不到原因。
3. **文案同源**：按钮 `title` 与 chip 的 `scope` 共用同一个 computed 常量，不允许两处各写一份。
4. **禁止 `pointer-events: none`**：会让 `title` 气泡彻底失效（见 §0.7）。
5. **对比度**：禁用字 `#A9AEB8` 对 `#F2F3F5` 约 2.0:1，低于正文标准——**这是刻意的**（禁用态按 WCAG「非活动组件」豁免），但"原因"不能只靠这段低对比文字承载，必须同时存在 chip / banner / title 中的至少一条高对比通道。

## 7. 厂商视角的数据态（AC-5/6/7 在界面上的表现）

| 页面 | vpm1 期望 | 若夹具未生效（回归信号） |
|---|---|---|
| `/releases` | 1 张卡片 `DEMO-REL-1`，需求 11 | 空态「还没有版本计划」+ 灰「新建版本」→ 说明 `release_requirement` 未纳入 |
| `/testcases` | 3 行（`DEMO-C-09/10/11`）+ 只读 banner + 灰「新建用例/导入」 | 空态「用例库还没有内容」→ 说明 `test_case.vendor_id` 未派生 |
| `/requirements` | 3 条（不变） | — |
| `/defects`、`/iterations` | 本轮不验数据，只验按钮形态 | — |

- 空态文案**不改**：真实空范围时「还没有版本计划」仍是事实陈述；改为"当前授权范围内没有版本"会引入新的条件分支（不在 AC-12 改动面），**记入 memory 待办**。
- 已知不一致（本轮登记、不修）：① 用例库空态无权限时隐藏主操作（`TestCaseLibraryView.vue:552`）；② 版本计划空态不提供创建 CTA（`ReleaseListView.vue:162` 传空 `action-text`）。两者都与 R1 的"统一形态"有偏差，但都在本轮改动面之外。

## 8. AC → 设计对照

| AC | 设计落点 |
|---|---|
| AC-1 | §3.1 §3.2、§4.2、§4.6（两处跳转均受 `canReport` 约束、2 个字面量） |
| AC-2 | §0.2（禁用样式 + type 降级）、§0.3、§4.3、「7 入口表」§2 |
| AC-3 | §4.4 台账 + §0.2 内联降级样式 |
| AC-4 | §4.6 接口约定（供 tech 写 a6） |
| AC-5/6/7 | §3.5 §3.6 §7（只表现数据，不改样式） |
| AC-9/10 | 本文件不涉及（服务端零改动的视觉面即"什么都不动"） |
| AC-12 | §1 改动面表（组件/令牌/依赖均 0） |

## 9. 待确认（均有默认，默认即施工口径）

1. **页头 chip 是否必做**：默认**需求池必做**（AC-1 要求"可见原因"），缺陷/迭代**建议同做**以保证一致性；若用户认为多余，删 chip 不影响 a6（a6 不断言 chip），但 §6.2 的键盘通道需要另找承载。
2. **chip 的计数口径**：默认按"本页受当前账号限制的写入口条数"计（需求池 = 1；缺陷 = 1；迭代 = 1），不是按权限码个数。
3. **用例库空态隐藏主操作的偏差**：默认本轮不修、只登记 memory；若要修，需把 `TestCaseLibraryView.vue` 纳入改动面并同步 a6 增加 1 条断言。
