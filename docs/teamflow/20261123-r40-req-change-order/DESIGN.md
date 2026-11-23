<!-- meta: stage=design; task=20261123-r40-req-change-order; summary=变更单四步抽屉720+变更详情页+编辑只读分组；APPROVED 与 APPLIED 措辞/视觉分离；第4步影响预览依赖 dry-run 端点（P1 待定） -->
# r40 需求变更单 · UI/UX 设计

- 基线依赖：`docs/teamflow/20261123-r40-req-change-order/PRD.md`（AC-3/7/9/12/13/14/15/17/18）
- 视觉基线：`docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`（§7 交互约定、§8 反例）
- 结构模板：`docs/teamflow/20261123-r39-automation-rule-engine/DESIGN.md`
- 阅读顺序：§0 观感 → §1 改动面 → §2 IA → §3 线框 → §4 交互 → §5 规格 → §6 无障碍 → §7 AC 对照 → §8 待确认
- 数量统计：新路由 1 · 新组件 5 · 新纯函数模块 1 · 新令牌 9 · 新色板 0 · 新依赖 0 · 新菜单码 0 · 新断点 1（1280）

---

## 0. 观感与手感（先读这一节）

### 0.1 一句话意图

这组界面是一张**留痕单据**，不是编辑表单：任何一屏都要能一眼回答三件事——**改了什么（before → after）、批到哪一步、到底生效没有**。视觉重量只给两处：顶部状态横幅、前后对照里的「变更后」值；其余（申请人、时间、字段名、审计码）一律退到 12px 辅助灰。整页不出现渐变、彩色卡片、投影（抽屉/弹窗除外），与平台既有单据页同材质。

### 0.2 表面处理（按组件类）

| 组件类 | 圆角 | 描边 | 阴影 | 填充 |
|---|---|---|---|---|
| 状态横幅 | `--r-ctl` 6 | 1px tone 边框 + 左侧 3px tone 实边（同既有 `.action-banner`） | 无 | primary-light / success-bg / warning-bg / danger-bg / info-bg |
| 内容卡（原因、diff、影响、时间线） | `--r-card` 8 | 1px `--color-border` | 无 | `--bg-card` |
| 类型单选卡（抽屉 ①） | `--r-ctl` 6 | 1px `--color-border`；选中 1px primary + `inset 0 0 0 1px primary` | 无 | 选中 `--bg-selected`，未选 `--bg-card` |
| 影响卡（抽屉 ④ / 详情） | `--r-card` 8 | 1px `--color-border` | 无 | `--bg-card`；「无法评估」态 `--bg-hover` |
| 抽屉 | 沿用既有 `.el-drawer`（无圆角） | 无 | `--shadow-drawer` | `--bg-card` |
| 步骤圆号 | `--r-pill` 999 | 无 | 无 | 未达 `--bg-page` / 当前 `--color-primary` / 完成 `--color-success-bg` |
| 待审批角标 | `--r-pill` 999 | 无 | 无 | `--color-warning-bg` 底 + `--color-warning-ink` 字 |
| 列表容器 | `--r-card` 8 | 容器 1px `--color-border`，行间 1px `--color-divider` | 无 | 表头 `--bg-table-head`，行 `--bg-card` |
| 留痕 chip | `--r-cell` 4 | 无 | 无 | `--bg-hover`，hover `--bg-selected` |

硬约束：圆角 ≤ 8；页面内卡片一律不投影；除「横幅」外不出现彩色大面积填充；不使用图标字体（无新图标资产，选中态用 14px 文本 ✓）。

### 0.3 状态反馈

| 元素 | 默认 | hover | active / pressed | focus-visible | 禁用 | 时长/缓动 |
|---|---|---|---|---|---|---|
| 类型卡 | 1px border | 边框转 `--color-border-strong` + `--bg-hover` | `--bg-selected` | 2px primary 外环（offset 2） | `opacity .5` + `not-allowed` + `title` 原因 | 120ms / `--ease` |
| 列表行 | 透明 | `--bg-hover` | `--bg-selected` | 行内单号链接取 `--focus-ring` | — | 120ms |
| 变更单号链接 | mono 12/500 `--text-secondary` | 转 `--color-primary`（只改色，不加下划线，避免行抖动） | `--color-primary-active` | `--focus-ring` | — | 120ms |
| 步骤圆号 | 见 0.2 | — | — | 随所在 `li` 的 `:focus-within` 出环 | `opacity .45` | 120ms |
| 冲突字段行 | `--color-danger-bg` + 左 3px danger 边 | — | — | — | — | 出现 160ms 淡入 |
| 应用成功行 | 整行 `--flash-ok` 600ms 后淡出 | — | — | — | — | 600ms（`--t-status`） |
| 应用/提交按钮 | 既有按钮 | 既有 | 既有 | 既有 | loading 时禁用并换文案 | 120ms |

### 0.4 动效、加载、空态、错误

- **抽屉**：240ms（`--t-overlay`）右侧滑入 + scrim 淡入；不做回弹。
- **步骤切换**：只做 120ms 透明度淡入，不做横向滑动（672px 位移会晕），也不改变滚动位置（每次切换回到抽屉顶部）。
- **提交/应用**：按钮内 spinner + 文案「提交中…」「应用中…」，按钮禁用；不弹全屏遮罩、不关抽屉、不跳页。
- **加载**：列表 3 条 `.skeleton__row`；详情页 2 张卡骨架；第 ④ 步 4 个 120 高骨架块；影响卡数据到位前不显示任何 0。
- **空态**：列表 → `EmptyState`（icon `inbox`，标题「暂无变更」，说明「已入池需求的改动从这里发起，审批通过后需显式应用」，动作「发起变更」）；影响块缺失 → 卡内「无法评估」+「重试」；审批时间线无事件 → 「等待审批人处理」。
- **错误**：一律区域/字段内联，`409` 绝不 toast（`web/src/api/client.ts` 既有约定）；提交失败保留当前步骤、全部输入与滚动位置。
- **reduced-motion**：淡入、flash、抽屉过渡全部降为 0.01ms。

### 0.5 排版角色与反模式

- 角色：页面标题沿用需求详情页 h1 的既有字号字重（不新增标题层级）；块标题 14/600；正文 13；辅助 12 且只用 `--text-muted-aa`；单号/时间/人天/百分比用 `--font-mono` + `font-variant-numeric: tabular-nums`；动作码 mono 12/500。
- 数字：数值列右对齐；人天 1 位小数（0.5 步进）；百分比固定 1 位小数，阈值带内改 2 位（见 §4.3）。
- 本产品反模式（出现即判设计缺陷）：
  1. `APPROVED` 写成「已生效 / 已变更 / 已应用」；
  2. 审批通过后在前端先写需求字段「让页面看起来生效」；
  3. 影响评估缺数据时用 `0`、`—`、`无影响` 兜底；
  4. `应用变更` 按钮在非 `APPROVED` 时常驻置灰（应整颗不渲染）；
  5. 用 toast 报告 400/409；
  6. before/after 值用 ellipsis 截断（证据必须换行显示，行高可变）；
  7. 人天只给一个百分比——必须把「本次改动」与「对已批评估」分开；
  8. 无权限时「发起变更」静默消失（AC-13 要禁用 + 给原因）；
  9. 待审批数量用红点代替数字角标；
  10. 第 ④ 步在前端自行拼「受影响发布单数」；
  11. 在变更详情页再放一个「通过审批」按钮（审批动作只在审批中心）；
  12. 用 5 种彩色卡片区分状态（状态只能是 pill + 文字）。

---

## 1. 改动面

| 文件 | 类型 | 说明 |
|---|---|---|
| `web/src/views/req/RequirementDetailView.vue` | 改 | `TAB_KEYS` 在 `approval` 后插入 `changes`（Tab 数量 9→10，位置第 6）；挂载 `ChangeTab`；头部动作区新增 `编辑`（次要）与 `发起变更`（次要，无权限禁用）；持有两个抽屉的开关与预填入参 |
| `web/src/views/req/tabs/ChangeTab.vue` | 新 | 变更列表 + 状态筛选 + 空态 + 分页 + 角标取数 |
| `web/src/views/req/RequirementChangeDetailView.vue` | 新 | 深链单据页（横幅 / 对照 / 影响 / 时间线 / 留痕 / 应用） |
| `web/src/components/req/ChangeCreateDrawer.vue` | 新 | 四步申请抽屉（720） |
| `web/src/components/req/ChangeDiffTable.vue` | 新 | before/after 对照表（详情页与确认弹窗共用，`compact` 变体） |
| `web/src/components/req/ChangeImpactCards.vue` | 新 | 影响四卡（`mode="preview"` 抽屉内 / `"readonly"` 详情页） |
| `web/src/components/req/RequirementEditDrawer.vue` | 新 | 编辑抽屉（560），承载 AC-13 的只读分组与「需发起变更」 |
| `web/src/views/req/change.ts` | 新 | 纯函数：状态元数据、逐类型早期态表、字段白名单与标签、`formatDelta`/`formatRatio`、错误码→文案映射、可见列裁剪 |
| `web/src/router/routes/req.routes.ts` | 改 | +1 路由：`/requirements/:id/changes/:changeId`，`meta: { title: '需求变更', parentPath: '/requirements', pageCode: 'req.pool' }` |
| `web/src/api/req.ts` | 改 | +5 方法：`listChanges` / `getChange` / `createChange` / `applyChange` / `previewChange`（预览端点见 §8-P1） |
| `web/src/stores/dict.ts` | 改 | 新增 `StatusKind = 'reqChange'`，5 个状态（见 §4.1） |
| `web/src/styles/tokens.css` | 改 | +9 令牌（§5.1），**只增不改** |
| `web/src/views/approval/ApprovalCenterView.vue` | 改 | `REQ_CHANGE` 单据在详情头加「查看变更单」按钮（点击先 `GET /changes/{id}` 取 `requirement.id` 再跳转，零契约改动） |

不改：`nav.ts`（零新菜单码）、审批引擎 `document` 结构、需求详情其它 8 个 Tab 的既有结构与顺序。

> 位置说明：PRD 写「第 10 个 Tab」指数量。设计取「插在『审批』之后」——变更与审批同族，且不把入口推到 Tab 条最远端。若要求严格追加到末尾，仅 `TAB_KEYS` 一处顺序差异。

---

## 2. 信息架构

```
需求池 /requirements
└─ 需求详情 /requirements/:id?tab=changes        ← Tab「变更」，PENDING 角标
   ├─ 发起变更（四步抽屉，720）
   │   ① 类型 → ② 原因 → ③ 字段 → ④ 影响 → 提交审批
   └─ 变更详情 /requirements/:id/changes/:changeId   （meta.pageCode = req.pool，无新菜单码）
        └─ 应用变更（二次确认弹窗）

审批中心 /approvals  →  待办 biz_type=REQ_CHANGE  →  「查看变更单」  →  变更详情
```

四个入口，同一抽屉：① Tab 右上「发起变更」；② 详情页头部「发起变更」；③ 编辑抽屉里被锁分组的「发起变更」；④ `409 CHANGE_REQUIRED` 的 banner CTA（预填被拦字段所属类型）。

返回：变更详情面包屑「需求池 / RQ-0012 / 变更 #7」中段可点回详情并落在 `?tab=changes`；应用成功后停留本页，离开时详情页 `onActivated` 重取。

---

## 3. 关键页线框与组件

### 3.1 需求详情「变更」Tab

```
┌ 需求池 / 业务需求 / 设计中                              [编辑] [发起变更] ┐
│ RQ-0012 结算对账批处理改造       状态pill 优先级 范围chip                │
│ 概览  子需求3  任务2  测试  缺陷  审批  变更②  追溯  集成  文档          │ 34px 高，active 2px 下边
├──────────────────────────────────────────────────────────────────────────┤
│ 状态 [全部 ▾]        共 6 条                            [发起变更]        │
│ ┌ 容器 .surface（r-card，表头 --bg-table-head）────────────────────────┐ │
│ │ 变更单  状态            类型   原因            影响摘要        申请人 时间│ │
│ │ #7     已通过·待应用   内容   交付提前两周…    子需求 3 · 人天 8→10  张三  11-23 18:20│ │
│ │                                              已锁定 · 将转 STALE · 2 张发布单│ │
│ │ #6     已应用          人天   联调工作量…      子需求 3 · 人天 8→10  李四  11-22 09:10│ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│                                                    每页 20 ▾  ‹ 1 2 ›     │
└──────────────────────────────────────────────────────────────────────────┘
```

- 列宽（最小内容宽 1000）：单号 76 / 状态 `--w-change-status` 112 / 类型 `--w-change-type` 88 / 原因 `minmax(240px,1fr)` / 影响 `--w-change-impact` 248 / 申请人 `--w-change-applicant` 96 / 时间 `--w-change-time` 140。合计 76+112+88+240+248+96+140 = 1000 ≤ 1040（1280 视口内容宽）。
- 行高 min 48（影响摘要两行时正合适），行 `td` padding 8 12；点击整行进详情；单号是 `<router-link>`（键盘可达），行点击只是便捷层。
- 影响摘要两行 12px：行一 `子需求 N · 人天 <before>→<after>`；行二 `已锁定/未锁定 · 将转 STALE · 影响 N 张发布单`（`becomesStale=false` 时不渲染该段，不写「不会转」）。任一分量为 `null` → 该行显示「无法评估」（`--text-muted-aa`），绝不显示 `0`。
- 空态、筛选态、错误态：`EmptyState` / `ErrorState`（`compact`）；筛选状态写 URL：`?tab=changes&status=PENDING`，刷新可复现。
- 角标：`<span class="detail__badge">` 高 16、`padding 0 5`、`min-width 16`、11/600、`--r-pill`、warning 底 + warning-ink；仅 `PENDING total > 0` 渲染；取数 `GET .../changes?status=PENDING&size=1` 的 `total`，失败静默（不显示、不阻塞页面）。

### 3.2 四步申请抽屉（720）

```
┌ 发起需求变更                                                    RQ-0012  ✕ ┐ 720，padding 24
│ ① 选类型 ──── ② 原因 ──── ③ 字段 ──── ④ 影响与提交                       │ 4 × 168
│ ┌ 330×76 ────────────┐ ┌ 330×76 ────────────┐                            │
│ │ 内容变更         ✓  │ │ 人天变更            │   ← 2×2，gap 12           │
│ │ 标题/描述/优先级/交付 │ │ 预估人天，0.5 天步进 │                            │
│ └────────────────────┘ └────────────────────┘                            │
│ ┌────────────────────┐ ┌────────────────────┐                            │
│ │ 版本变更            │ │ 父需求变更          │                            │
│ └────────────────────┘ └────────────────────┘                            │
├──────────────────────────────────────────────────────────────────────────┤
│ 步骤 ③（CONTENT）：每字段一行                                              │
│ 标题         [当前值最多两行 216]  →   [input 420]                       │
│ 描述         [当前值 216]          →   [textarea 420×96]                  │
│ 优先级       [P1 中 216]           →   [select 220]                       │
│ 期望交付     [2026-12-01 216]      →   [date 220]                         │
│                                                                          │
│ 步骤 ④：2×2 影响卡（330，min-height 120）+ 审批链路说明 + 冲突预警        │
├──────────────────────────────────────────────────────────────────────────┤
│ 将提交 2 项字段                            [上一步] [下一步 / 提交审批]    │
└──────────────────────────────────────────────────────────────────────────┘
```

- 步骤头：4 项各 168 宽，圆号 24（12/600）+ 8 + 标签 13/500；已完成圆号显示 `✓` 并置 success；当前项 `aria-current="step"`；连接线 1px `--color-divider`。
- 类型卡 330×76：`padding 12 14`；标题 13/500 + 描述 12/`--text-muted-aa`（两行内），选中右上 `✓`；`role="radiogroup"`，卡片 `role="radio"` + `aria-checked`，roving tabindex（当前项 0，其余 -1，方向键移动、Space 选中）。
- `DAYS` 在**父需求**（子需求数 > 0）时禁用，卡内描述改为「父需求人天由子需求汇总」+ `title` 说明；服务端 `PARENT_DAYS_READONLY` 只是兜底。
- 字段行算式：`216 + 8 + 20 + 8 + 420 = 672`。当前值 chip：`--bg-hover`、`--r-cell`、`padding 6 10`、12px、`line-clamp 2` + `title` 全文；箭头 20 宽居中 `→`（`aria-hidden`，行级 sr-only 文案替代）。控件 420：input/textarea 满宽；select/date 固定 220（不铺满，避免误读为长文本字段）。
- 只渲染该类型白名单字段（§4.2 白名单表），未变化的字段不提交；每个改动行尾 12px primary「已修改」。
- 第 ③ 步底部校验：0 项变化时 `下一步` 禁用并提示「没有任何字段变化」，不发请求（对应 `VALIDATION_FAILED` 的第一类）。
- 第 ④ 步：4 卡（子需求数 / 版本状态与锁定 / 人天 Δ 与阈值 / 受影响发布单），每卡 label 12 + 数值 20/600 + 副行 12（≤2 行）；卡下一条 13px 说明「审批将走两级：产品负责人 → 域负责人」；若存在覆盖同字段的 PENDING 单，插一条 info 横幅（见 §4.4）。
- 页脚：左「将提交 N 项字段」12px；右 `上一步`（ghost）/ `下一步`（primary）/ 第 ④ 步换 `提交审批`。全屏仅一个 primary。
- 关闭：点 ✕、Esc、点 scrim 均先判脏（类型/原因/字段任一已填）→ `el-messageBox` 确认「放弃已填写的内容？」；确认后清空。
- 提交成功：关抽屉 → 列表插到顶部（或用返回值重取第 1 页）→ Tab 角标 +1 → `el-message`「已提交审批，尚未生效」→ 留在 Tab（不自动跳详情，避免误以为已生效）。

### 3.3 变更详情页（/requirements/:id/changes/:changeId）

```
┌ 需求池 / RQ-0012 结算对账批处理改造 / 变更 #7                    [应用变更] ┐
│ 变更 #7 · 内容变更    [已通过 · 待应用]  申请人 张三 · 11-23 14:02 · 审批实例 #88│
├──────────────────────────────────────────────────────────────────────────┤
│ ▌ 审批已通过，尚未应用                                                     │ ← 横幅 3px 左边
│   下面是申请内容；点「应用变更」才会写入需求字段，并留下 before/after 基线。 │
├──────────────────────────────────────────────────────────────────────────┤
│ 申请原因                                                                  │
│   客户要求把交付日期提前两周，涉及联调窗口调整……（保留换行）               │
├──────────────────────────────────────────────────────────────────────────┤
│ 字段变更（仅列实际变化项）                                                 │
│ 字段      变更前                         →   变更后                        │
│ 期望交付  2026-12-15                     →   2026-12-01                    │
│ 标题      结算对账批处理改造             →   结算对账批处理改造（二期）      │
├──────────────────────────────────────────────────────────────────────────┤
│ ┌ 影响评估（4 卡 2×2，330/498 自适应）──────────┐ ┌ 审批进度 320 ────────┐ │
│ │ 子需求 3 ｜ 版本 v1.4.0·LOCKED ｜ 人天 …     │ │ ● 提交  张三 11-23    │ │
│ └──────────────────────────────────────────────┘ │ ● 产品负责人 已通过    │ │
│                                                  │ ○ 域负责人 处理中      │ │
│                                                  └───────────────────────┘ │
├──────────────────────────────────────────────────────────────────────────┤
│ 留痕  CHANGE_REQUESTED  张三 11-23 14:02 ｜ REQUIREMENT_CHANGE_APPLIED …  │
└──────────────────────────────────────────────────────────────────────────┘
```

- 头部右动作只有一颗：`APPROVED` **且** `allowedActions` 含 `change_apply` 时渲染 `应用变更`（primary）；其余状态整颗不渲染（AC-18 口径，与 AC-13 的「禁用 + 原因」不同，见 §4.5）。
- 横幅按状态取色与文案（§4.1），`role="status"`；`APPLIED` 时第二行含应用人与时间，并在「留痕」区加锚点链接。
- 对照表列算式（卡内 1008）：`112 + 16 + 372 + 24 + 372 + 16 + 96 = 1008`；`grid-template-columns: var(--w-change-field) minmax(0,1fr) var(--w-change-arrow) minmax(0,1fr) 96px`。值**换行不截断**（12/13px，`word-break: break-word`），长描述最多展开 6 行 + 「展开全文」。
- 变更前 13/400 `--text-body`；变更后 13/600 `--text-title`；箭头 `--text-weak`。人天行在「变更后」单元格追加 12px 偏差 chip（§4.3），不单独占列。
- 影响卡 `readonly`：同组件复用；版本卡显示 `impact.release.name`（缺失退 `需求详情.releaseName`，再缺 `版本 #<releaseId>`）+ 状态 pill + 锁定标记 `postLock.value`；`postLock.lockedReleaseIds` 非空时副行「应用将额外写 CHANGE_AFTER_RELEASE_LOCK」。
- 时间线：`ApprovalTimeline`（`dense`），事件取 `GET /approvals/instances/{instanceId}`；加签/减签节点按既有 `labels` 映射；实例取数失败 → 时间线区 `ErrorState compact` 可重试，不影响其余区块。
- 留痕行：mono 11 chips，顺序 `CHANGE_REQUESTED` / `REQUIREMENT_CHANGE_APPLIED` / `CHANGE_AFTER_RELEASE_LOCK`（按实际存在的渲染）；点击复制 action 码（成功用既有 toast）。

### 3.4 编辑抽屉（560，AC-13）

```
┌ 编辑需求                                          RQ-0012 设计中   ✕ ┐ 560
│ 内容                                        [需发起变更]            │ ← 分组状态 chip
│ 标题      [disabled input 504]       需发起变更  [发起变更]          │
│ 优先级    [disabled select 220]                                       │
│ 人天                                        [可编辑]                 │
│ 预估人天  [number 0.5 步进 120 ]                                      │
│ 版本 / 父需求                               [需发起变更]              │
├──────────────────────────────────────────────────────────────────────┤
│                              [取消] [发起变更] [保存]                │
└──────────────────────────────────────────────────────────────────────┘
```

- 四个分组（内容 / 人天 / 版本 / 父需求）各自按**该需求类型的早期态表**判定（§4.2）；过了早期态的分组：控件 `disabled` + `--bg-hover`，组头右侧 12px `--color-warning-ink`「需发起变更」，组内一颗 ghost「发起变更」预填该类型。
- 父需求的人天**永远**只读（子需求汇总），组内文案「父需求人天由子需求汇总」。
- 页脚：`保存`（primary，仅当存在可编辑且已改动时可用，`PUT /requirements/{id}` 只发改动字段）；`发起变更`（ghost，无权限禁用 + `title`）；全锁时 `保存` 不渲染，`发起变更` 升为 primary。
- `409 CHANGE_REQUIRED` 返回（服务端比前端表更新时）：抽屉内顶部 `.action-banner`（danger）「这些字段已不能直接修改：标题、期望交付」+ 按钮「发起变更（预填）」；被拦字段跨类型时提示「本单只覆盖内容变更，其中『预计人天』需另开一张人天变更单」，并按 `changeEndpoint` 只预填第一类。
- 抽屉内任何 `PUT` 成功：关抽屉 → 详情页重取 → toast「已保存」。

### 3.5 审批中心入口

`ApprovalCenterView.vue` 的详情头（`biz-tag` 行右侧）对 `REQ_CHANGE` 增加 ghost 按钮「查看变更单」：点击 `GET /api/req/changes/{bizId}` → `router.push({ name: 'requirement-change', params: { id: requirement.id, changeId } })`；取数失败 toast「变更单不存在或已删除」。不新增审批动作按钮（通过/驳回仍走既有节点操作）。

---

## 4. 交互与状态

### 4.1 五个状态 → 界面行为

| status | pill 文案 / tone | 横幅 | 应用按钮 | 列表角标计入 |
|---|---|---|---|---|
| `PENDING` | 待审批（warning） | 「等待审批」+ 当前节点与「第 x/2 级」 | 不渲染 | 是 |
| `APPROVED` | 已通过 · 待应用（primary） | 「审批已通过，尚未应用」+「需求字段仍是申请前的值」 | `APPROVED` 且有 `change_apply` 时渲染 | 否 |
| `APPLIED` | 已应用（success） | 「已应用」+「由 X 于 Y 应用，写入 N 个字段」 | 不渲染 | 否 |
| `REJECTED` | 已驳回（danger） | 「已驳回」+ 驳回原因 | 不渲染 | 否 |
| `WITHDRAWN` | 已撤回（neutral） | 「已撤回」+「申请人于 Y 撤回，未写入任何字段」 | 不渲染 | 否 |

「已生效 / 已变更 / 已完成」在任何位置都不出现；`APPROVED` 的动词只有「应用」。
`REJECTED` 原因取审批实例末节点 comment，取不到显示「审批人未填写驳回原因」（不编造）。

### 4.2 白名单与早期态表（前端只读取自 `change.ts`，与服务端同源校验）

| changeType | 白名单字段 | 卡片描述 | 早期可直改状态（PUT 不拦） |
|---|---|---|---|
| `CONTENT` | title / description / priority / expectedDeliveryDate | 标题、描述、优先级、期望交付 | `BUS: SUBMITTED, APPROVING, PENDING_REVIEW`；`PDR: PENDING_ANALYSIS`；`RD: TODO` |
| `DAYS` | estimateDays（0.5 精度；父需求只读） | 预估人天 | 同上 |
| `RELEASE` | releaseId（可 null） | 移入/移出发布版本 | 同上 |
| `PARENT` | parentId（可 null） | 调整父子层级 | 同上 |

一次变更单只承载**一个类型**：跨类型的改动要分成多张单（审批各走）。编辑抽屉与第 ③ 步都要明示这一点，不能让用户以为一张单能改完。

非四类字段（`domainIds` / `sonarKey`）不在本抽屉暴露，仍走既有直改通道（AC-12 末条），不影响本设计。

### 4.3 人天口径（必须两个数，不能混）

- **本次改动**：`Δ = after − before`，`formatDays` 输出（如 `+2 天`、`-1.5 天`），副标「相对提交时」。
- **对已批评估偏差**：`r = (after − approvedDays) / approvedDays`，显示 1 位小数带符号（`+25.0%`）；`|r|` 落在 `(0.200, 0.201]` 时改显 2 位（`+20.04%`），避免「显示 20.0% 却转 STALE」。
- **判定**：`|r| > 0.2` **严格**，用未舍入值；恰好 `0.2` → 显示 `+20.0%` + chip「恰好 20%，不转 STALE」。
- 无 `APPROVED` 工时评估单（`approvedDays` 为 null）：显示「无已批工时评估，不触发 STALE」——这是事实，不写 `0%`。
- 已经 `STALE`：「已转 STALE」（warning 文字，非 pill）。
- 前端自算上述三值，`impact.days.deltaRatio` 仅用于对账；不一致时不显示该字段（§8-P2）。

### 4.4 错误码 → 界面落点

| 错误码 | 落点 | 文案与行为 |
|---|---|---|
| `CHANGE_FIELD_NOT_ALLOWED` | 第 ③ 步对应字段行 | 「『预计人天』不属于内容变更」+ 行尾「切换到人天变更」快捷；保留输入 |
| `REASON_REQUIRED` | 第 ② 步原因框 | 红边 + `aria-invalid` + 「请填写变更原因」，聚焦该框并回到 ② |
| `VALIDATION_FAILED` | 当前步顶部 banner | 透传 `message`；「没有任何字段变化」时同时禁用下一步 |
| `PARENT_DAYS_READONLY` | 第 ③ 步人天行 | 「父需求人天由子需求汇总」；理论上已被类型卡禁用挡住 |
| `INVALID_PARENT` | 父需求选择行 | 「不能选择自身或子需求」 |
| `INVALID_DAY_PRECISION` | 人天输入行 | 「按 0.5 天步进」；输入框本身步进 0.5，前端先挡 |
| `CHANGE_REQUIRED` | 编辑抽屉 banner | 「这些字段已不能直接修改」+ 发起变更 CTA（预填） |
| `CHANGE_NOT_APPROVED` | 应用 banner | 「单据状态已变为 <status>，不能应用」+ 刷新详情 |
| `CHANGE_ALREADY_APPLIED` | 应用 banner | 「该变更已由 X 于 Y 应用」+ 自动刷新（不重复提交） |
| `CHANGE_CONFLICT` | 对照表 + banner | 冲突字段行 danger 底 + 左 3px 边；表增「现在」列显示 `actual`；CTA「重新发起变更」（预填同类型、当前值作为 before） |
| `APPROVAL_UNAVAILABLE` | 第 ④ 步 banner | 「未找到审批人（产品负责人/域负责人），请联系管理员」+ 复制动作码 |
| `403` | 整页 | `ErrorState`（`forbidden`），不渲染任何字段；与需求详情同口径 |
| `404` | 整页 | `ErrorState`（`status=404`，`retryable=false`），面包屑保留回详情 |

- 应用前**主动预检**（非必需请求）：详情页 `GET /requirements/{id}` 后逐字段比对 `before`，不一致则显示 warning 横幅「需求已在申请后被修改（标题、人天），现在应用很可能被拒绝」，但不禁用按钮（服务端才是权威）。
- 二次确认弹窗（480）：标题「应用变更到需求」；正文列出将写入字段（字段 + 新值，最多 6 行，超出「等 N 项」）；有 `postLock` 时加一行「该需求在锁定版本 v1.4.0 中，应用会额外写一条审计」；预计转 STALE 时加一行「工时评估单将转 STALE」；按钮 `取消`（默认焦点）/ `确认应用`（primary，loading 时禁用）。确认后不关弹窗直到成功。
- 并发：提交/应用按钮在请求未返回前 `disabled`；重复点击由禁用兜住，服务端二次 apply 的 409 走「已应用」分支并刷新。

### 4.5 两处权限口径不同（按 AC 各自实现，别统一）

| 位置 | 无权限时 | 依据 |
|---|---|---|
| 详情页头部「发起变更」 | **渲染但禁用**，`title`/`aria-describedby` 给原因「需要「发起需求变更」权限（req.action.change_request）」 | AC-13 明确要求 |
| 编辑抽屉「发起变更」 | 同上（禁用 + 原因） | AC-13 |
| 变更详情「应用变更」 | **整颗不渲染** | AC-18 明确「仅在 APPROVED 且有权限时出现」 |

两处都只读 `detail.allowedActions` 的短码（`change_request` / `change_apply`），**不在前端**复刻 `req:deliver + req:split` 的双依赖（r27 同源口径）。

---

## 5. 视觉规格

### 5.1 新令牌（只增，不改既有值）

```css
:root {
  --w-drawer-change: 720px;
  --w-change-status: 112px;
  --w-change-type: 88px;
  --w-change-impact: 248px;
  --w-change-applicant: 96px;
  --w-change-time: 140px;
  --w-change-field: 112px;
  --w-change-current: 216px;
  --w-change-arrow: 20px;
}
```

新色板 0：全部复用既有 tone（banner / pill / chip）。新增 `StatusKind = 'reqChange'` 只是字典，不是颜色。

### 5.2 宽高算式（各节自洽）

- 抽屉内容 = `720 − 24×2 = 672`；步骤头 `4 × 168 = 672`；类型卡 `(672 − 12)/2 = 330`，高 76（`12 + 20 + 4 + 18 + 12 = 66 ≤ 76`）。
- 字段行 `216 + 8 + 20 + 8 + 420 = 672` ⇒ 控件 420（select/date 内缩 220）。
- 影响卡 `(672 − 12)/2 = 330`；高 120 = `14 + 18 + 6 + 28 + 4 + 36 + 14`（副行按 2 行预留），**不裁切**、不省略；1 行副行时仍保持 120 对齐。
- 对照表（详情卡内 1008）= `112 + 16 + 372 + 24 + 372 + 16 + 96`；≤1280 时结果列并入 after 单元格，`2×420`，before/after 各 `min-max(240px, 1fr)`。
- 列表最小宽 1000（§3.1）；≤1280 隐藏申请人列 ⇒ 904；<1024 容器 `overflow-x: auto`，不再压缩列宽。
- 详情页第 4 行 = 主栏 `1fr` + 320 + `gap 20`，在 1040 下主栏 700 ⇒ 影响卡 2×344。

### 5.3 唯一断点

`@media (max-width: 1280px)`：① 变更列表隐藏「申请人」列（信息仍在时间列 `title` 与详情页）；② 对照表结果列并入 after（§5.2）；③ 详情页右栏 320 → 288。除此之外不新增断点，不做移动端布局（平台既有口径）。

### 5.4 颜色语义

| 用途 | 令牌 |
|---|---|
| 待审批 / 将转 STALE / 锁定 | warning 三件套（`-bg` / 正常 `--color-warning` / `warning-ink` 文字） |
| 已通过待应用 / 主链路 | `--color-primary-light` 底 + `--color-primary` 边/字 |
| 已应用 / 成功 | success 三件套 |
| 已驳回 / 冲突 | danger 三件套 |
| 已撤回 / 无法评估 | `--bg-hover` / `--bg-page` + `--text-muted-aa` |

状态永远 pill + 文字；颜色只是第二通道。

---

## 6. 无障碍

- **抽屉**：`el-drawer` 自带 focus trap 与 Esc；`aria-labelledby` 指向标题；打开后焦点落到类型卡组（`role="radiogroup"`，`aria-label="变更类型"`），roving tabindex；关闭时焦点回到触发按钮；脏状态确认弹窗出现后焦点在「取消」。
- **步骤头**：`<ol>` + 每项 `aria-current="step"`，完成项视觉 `✓` 且 `aria-label="已完成"`；跳过步骤由代码控制，不提供点击步骤头跳转。
- **字段**：`aria-invalid` + `aria-describedby` 指向错误文案；提交失败聚焦第一个错误字段；错误摘要区 `role="alert"`。
- **对照表**：每行 `sr-only` 摘要「标题：由 A 改为 B」；箭头 `aria-hidden`；表头 `scope="col"`。
- **应用确认**：弹窗 `role="dialog"` + `aria-modal`，标题与正文关联；字段清单用 `<dl>`，不用纯视觉列表。
- **状态与角标**：pill 文本自解释；角标 `aria-label="2 项待审批变更"`，纯装饰时 `aria-hidden`。
- **对比度**：12px 辅助文本只用 `--text-muted-aa`（#6b7280 于白底 4.83:1）；`warning-ink` 于 `warning-bg`、`danger-ink` 于 `danger-bg` 均 ≥4.5:1；不新增低对比灰。
- **键盘可达**：列表行点击是便捷层，单号链接必须可 Tab 聚焦；抽屉内控件按视觉顺序 Tab；日期/下拉遵循 Element Plus 既有键盘行为。
- **reduced-motion**：全部过渡/淡入/flash 置 0.01ms，状态变化靠文案与静态颜色表达。

---

## 7. AC 对照（怎么验收）

| AC | 落点 | 验收动作 |
|---|---|---|
| AC-12 | §4.2 表 + 编辑抽屉 | 在 `BUS=SUBMITTED` 需求上编辑内容 → 可直接保存；切成 `DESIGNING` → 四组变只读 + 「需发起变更」；只改「业务域」仍可保存 |
| AC-13 | §3.4 + §4.5 | 只读分组截图；无权限账号看到「发起变更」禁用且 `title` 有原因；构造 `409 CHANGE_REQUIRED` → banner 列出字段 + CTA 预填，已填内容未丢 |
| AC-14 | §4.4 403/404 | 换租户账号直连深链 → `ErrorState`，不泄露标题/字段 |
| AC-15 | §4.5 | 产品经理账号（有 `req:split`）能看到申请入口；域负责人（`req:deliver`）能看到应用按钮；越权账号两处分别按要求禁用/隐藏 |
| AC-17 | §3.1 + §3.2 | 空态→发起→四步→提交，列表与角标 +1；`size` 上限、状态筛选进 URL；提交中按钮禁用；成功提示为「已提交审批，尚未生效」 |
| AC-18 | §3.3 | 深链可直达且 `pageCode=req.pool`；`APPROVED` 横幅写「尚未应用」且无「已生效」字样；应用后横幅/时间线/留痕三处一致；`409 CHANGE_CONFLICT` 高亮字段且整体未写 |
| AC-9 | §4.3 | `approvedDays=10` 分别输入 12 / 12.1 / 8：显示 `+20.0%`（不转）/ `+21.0%`（转）/ 「恰好 20%，不转」；`8` 显示 `-20.0%` |
| 反面目标 | §0.5 清单 | 全文检索「已生效」「无影响」；抽查影响卡在 `impact=null` 时显示「无法评估」而非 0 |
| UI-STYLE §7/§8 | §0.2/§0.3/§5.4 | 无渐变/无彩色卡片/无 >1 主按钮/状态均 pill+文字/圆角 ≤8 |

---

## 8. 待确认

| 级别 | 事项 | 默认取向 |
|---|---|---|
| P1 | **影响预览缺 dry-run 端点**。PRD 接口表只有创建（会落库），第 ④ 步无法真实预览 | 建议加 `POST /api/req/requirements/{id}/changes/preview`（同 body、同校验、只返回 `impact_json` 形态、不写库）。若后端拒绝：退化为「第 ④ 步只展示可本地确定的项（子需求数 / 版本锁定 / 人天 Δ）并明示『受影响发布单以服务端冻结结果为准』，提交后到详情页查看完整影响」——但会削弱 AC-17 ④ 与反面目标 2，需 tech 明示接受 |
| P1 | **本轮新增编辑入口**（现状全站无编辑 UI，`updateRequirement` 零调用）。AC-13 的「编辑态」没有载体 | 默认纳入本轮（560 抽屉）。若判定超出范围，AC-13 只能靠「409 后引导」满足，需 PRD 追认 |
| P2 | `impact.days.deltaRatio` 分母（相对 `approvedDays` 还是相对 `before`） | UI 自算三值并展示，后端字段仅对账；不一致时不展示该字段 |
| P2 | 审批中心 `document` 是否带 `requirementId` | 不改契约，点击时 `GET /changes/{id}` 反查 |
| P2 | `impact.release` / `targetRelease` 的字段集（名称 key 未在 AC-3 固定） | 优先 `name`，退需求详情 `releaseName`，再退 `版本 #<id>`；只强依赖 `status` 与 `postLock` |
| P2 | 目标版本候选来源 | 复用既有发布版本列表，排除 `CANCELLED`，首项「移出当前版本（不排期）」（= null） |
| P2 | `REJECTED` 驳回原因来源 | 审批实例末节点 comment，缺失给中性文案，不编造 |
| P2 | 「变更」Tab 位置（第 6 位 vs 末尾） | 默认第 6 位（审批之后）；如需严格追加到末尾，只改 `TAB_KEYS` 一处 |
