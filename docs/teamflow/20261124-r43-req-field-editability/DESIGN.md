<!-- meta: stage=design; task=20261124-r43-req-field-editability; summary=可编辑性按服务端三数组渲染：详情字段行标记+字段级发起变更、编辑抽屉逐字段原因、两类 409 分流、变更列表新增目标字段/审批结果列并把原因与影响并入次行 -->

# 需求字段可编辑性 · UI/UX 设计（r43）

- **基线依赖**：`docs/teamflow/20261123-r40-req-change-order`（变更单闭环）；本设计只在其 UI 上**加标记与列**，不重画信息架构。
- **视觉基线**：`docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md` §7 交互约定 / §8 反例 + r40 `DESIGN.md` §0（圆角 ≤ 8、页面卡片不投影、不用图标字体、状态只用 pill + 文字）——全部沿用，不新增视觉语言。
- **结构模板**：沿用 r40 DESIGN.md 章节序（§0 观感优先）。
- **阅读顺序**：§0 → §1 改动面 → §3 线框 → §5 视觉规格 → §7 AC 对照。
- **数量统计**：表面 5（详情头部 / 详情字段行 / 编辑抽屉 / 409 横幅 / 变更列表）；新增文件 2（`views/req/editability.ts`、`views/req/FieldFlag.vue`）；改写文件 6；新增令牌 5（只增不改）；详情页新增只读行 2（父需求、Sonar 项目键）；覆盖 AC 7 条（AC-3/4/9/10/11/12/13）。
- **新增 / 修订标注**：全文凡标注「〔新增〕」为本次新建，标注「〔修订〕」为改动既有实现，未标注即为沿用现状。

---

## §0 观感与手感（先读，前端照此实现）

### 0.1 一句话意图

这一版的主角是**规则本身**，不是控件：字段的可编辑性要像表格里的一列数据——**值永远在**，锁定只说一句短话，需要变更的地方给一条真能走的路，其余一律安静。所有新表面沿用「白卡 + 1px 边 + 无投影」的既有语言，**不引入任何新色彩、新圆角、新阴影**；新增的两种标记（锁定标记 / 需变更标记）总宽不超过「12px 文本 + 4px 间隙」。

### 0.2 表面处理（按组件类）

| 组件类 | 圆角 | 边 / 描边 | 底 / 阴影 | 具体写法 |
|---|---|---|---|---|
| 字段行标记 `.flag`（锁定 / 需变更） | `0`（**不画胶囊**） | 无 | 无底、无影 | 12px/18px，字重 500；锁定时色 `--text-muted-aa`，需变更时色 `--warning-ink`；🔒 为 12px 文本 emoji（沿用 OverviewTab 现有 `🔒 汇总` 先例，不引入图标字体）；与值的间隙 `--flag-gap: 4px` |
| 标记旁的行动作 `.flag__cta`（发起变更） | `0` | 无 | 无底 | 12px/18px，色 `--color-primary`；hover 才加 `underline`（`text-underline-offset: 2px`）；`focus-visible` 用 `--focus-ring` |
| 变更表「目标字段」chips `.field-chip` | `--r-cell`(4) | `1px solid transparent` | `--bg-hover` | 12px/20px，`padding: 0 6px`，色 `--text-body`，chips 间距 4px，**不可点**（`cursor: default`，无 hover 变化） |
| 变更表「审批结果」 | — | — | 无底（**不做 pill**，状态列已有 pill） | 12px/18px，字重 500，色按 §5.4：通过 `--success-ink` / 审批中 `--warning-ink` / 驳回 `--danger-ink` / 撤回 `--text-muted-aa` |
| 变更表次行（原因 · 影响） | — | — | — | 12px/18px，`--text-muted-aa`，单行 `line-clamp: 1`，完整文本进 `title` |
| 409 横幅 `.action-banner`〔修订〕 | `--r-cell`(4) | `1px solid` 同语气色 | 浅底（`--color-warning-bg` / `--color-info-bg` / `--color-danger-bg`） | 13px/20px，`padding: 8px 12px`；**只有横幅允许彩色大面积填充**，页面其余位置禁止 |
| 抽屉字段原因行 `.edit-field__hint`〔沿用〕 | — | — | — | 12px/18px，`--text-muted-aa`，控件下 4px，占控件列宽（不缩进到 label 列） |
| 抽屉锁定控件〔修订〕 | 沿用 | 沿用 | `--bg-hover`（由「组锁定」下沉为「字段锁定」） | 不加 `opacity`、不加删除线，**值必须完整可读** |
| 详情页字段行 | — | — | — | 完全沿用 `dl.grid / dt / dd`，本次只在 `dd` 末尾追加标记，行高变化 ≤ 2px |

### 0.3 状态反馈

| 元素 | hover | active / pressed | focus-visible | disabled | 过渡 |
|---|---|---|---|---|---|
| `.flag`（锁定标记） | 无位移、无变色（安静是特性）；`120ms` 后出 tooltip | — | `box-shadow: var(--focus-ring)`，同时出 tooltip（见 §6） | — | `color 120ms var(--ease)` |
| `.flag__cta` | 出现 `underline` | `color: var(--color-primary-active)` | `box-shadow: var(--focus-ring)` | 沿用 r25 三色（`--el-disabled-bg-color/-border-color/-text-color`），**不叠 opacity、不置 `pointer-events:none`** | `color 120ms var(--ease)` |
| 变更表行 | 整行 `--bg-hover` | `--bg-selected` | 行首 `box-shadow: inset 2px 0 0 var(--color-primary)` | — | `background-color 120ms var(--ease)` |
| chips | 无 | 无 | 不可聚焦 | — | — |
| 编辑 / 发起变更 按钮（详情头部） | 沿用 el-button | 沿用 | 沿用 | r25 三色 + 紧邻**可见**短句（§3.1） | 沿用 |
| 409 横幅出现 | — | — | — | — | `opacity 0→1` + `translateY(-2px)→0`，`160ms var(--ease)`，**禁止 scale/bounce** |

### 0.4 动效、加载、空态、错误

- **抽屉**：沿用 `el-drawer` `240ms var(--t-overlay)`，`--shadow-drawer`，无圆角、无 spring。
- **标记刷新**：数据刷新后标记只做 `color/background 120ms` 过渡，**不做淡入**（每次保存都闪会让页面像出错）。
- **加载**：详情骨架沿用 `.skeleton__row`；抽屉在未拿到三数组前显示提示条（不转圈）；变更列表 loading 行沿用 `colspan` 占位（列数改 7）。
- **空态**：变更列表沿用 `EmptyState`（`inbox` +「暂无变更」+ 发起变更）；`fields` 为空 → 「—」（12px `--text-muted-aa`）；`reason` 为空 → 次行只显示「影响：…」。
- **错误**：400/409 **一律内联**（横幅 / 字段行 / 抽屉 banner），**永不 toast**（`web/src/api/client.ts` 既有约定）；列表网络失败用 `ErrorState`（既有）。
- **fail-closed**：三数组缺失时抽屉只读 + 详情页一条 info 提示条「未获取到可编辑性规则」，**不得**本地推断矩阵。

### 0.5 排版角色

沿用 r40 角色，只新增两条：

| 角色 | 规格 |
|---|---|
| 页面标题 / 块标题 / 正文 / 辅助 | 沿用：详情 `h1`、14/600、13、12 且只用 `--text-muted-aa` 或语义 ink |
| 标记文本 | 12 / **500**（不 600，避免与块标题争层级） |
| 编号 / 时间 / 人天 | `--font-mono` + `tabular-nums`（`#41`、`2026-11-24 09:01`、`8.0`） |
| 变更表次行 | 12 / 400 / `--text-muted-aa`，`line-clamp 1` |
| 审批结果 | 12 / 500 / 语义 ink |

### 0.6 反模式（本产品本轮明确禁止）

1. 用彩色整行底、彩色卡片或色块区分 `EDIT / CHANGE_REQUIRED / LOCKED`（只允许 12px 标记，字段值不变色）。
2. 为了「只展示能改的」把 `LOCKED` 字段的值藏起来——**任一终态仍要看到全部 11 个字段的值**。
3. 在 `h1` 标题、描述段落上挂锁标（阅读主线不加控件噪声）。
4. 对 `FIELD_LOCKED` 显示「发起变更」入口（把用户引进死路）。
5. 用 toast 报 400/409。
6. 抽屉里给 `LOCKED` 字段画看起来可输入的控件（必须 `disabled` + 灰底 + 原因行）。
7. 三数组缺失时自行推断矩阵（fail-open）。
8. 原因码英文进正文（`PARENT_ROLLUP` 只允许出现在 mono code chip 里）。
9. 把多个目标字段用 `、` 拼成一个字符串（必须 chips，便于逐字段发起）。
10. 变更列表默认只显示 `APPLIED`（默认无状态过滤）。
11. 新增投影/发光/渐变、圆角 > 8、卡片投影、图标字体。

### 0.7 几何自洽校验（写死数字，改一处必须同步）

- 抽屉：`560 − 2×20 = 520` 内容宽；`label 96 + gap 8 + 控件 416`；最长原因句 21 字 × 12px = 252 ≤ 416 ✓（超长时换行，不截断原因句）。
- 抽屉 footer：取消 56 + 发起变更 88 + 保存 56 + 2×8 gap = 216 ≤ 520 ✓。
- Tooltip：`--tooltip-max: 320`；21 字 = 252 + 16 padding = 268 ≤ 320 ✓。
- 变更表：固定列 `96+112+88+88+96+140 = 620`，弹性列最小 320 → 表最小宽 940；1280 视口内容 1040 → 弹性列 420；1440 视口内容 1200 → 580 ✓。
- 变更行高：`8(pad) + 20(chips) + 4 + 18(次行) + 8 = 58` → `--h-change-row: 60`；chips 折两行时 `8+44+4+18+8 = 82`（最大，不会出现第三行：单条变更最多 4 个字段）。
- 目标字段列 420 时：4 个内容 chip ≈ `48+48+48+72 + 3×4 = 228` ≤ 420 → 常见情况单行，行高 60 ✓。
- 详情 grid：`minmax(180px,1fr)`，内容 1168 → 6 列需 `6×180+5×20 = 1180 > 1168`，实际 5 列（`(1168−80)/5 = 217.6`），新增 2 行只增加行数、不改列宽 ✓。
- 头部：编辑禁用短句 ≤ 12 字 = 144 + gap 6 + 按钮 56 = 206；与既有 4–5 个动作按钮（≈ 420）合计 626 ≤ 1040 ✓（1280 下仍单行）。

---

## §1 改动面

| 文件 | 类型 | 说明 |
|---|---|---|
| `web/src/views/req/editability.ts` | 〔新增〕 | 原因码联合、原因文案表、短标记词表、`reasonText()`、`editabilityOf()`、`canOpenEdit()`、`editDisabledReason()/editDisabledHint()`、`EDIT_UI_FIELDS`。**所有原因中文只在这里** |
| `web/src/views/req/FieldFlag.vue` | 〔新增〕 | 12px 标记 + 可选「发起变更」，8 个字段行共用；只 import `editability.ts` / `change.ts` |
| `web/src/components/req/RequirementEditDrawer.vue` | 〔修订〕 | `isEditable` 改为读三数组；删 `children.length` 判断与 `permissions.editableFields` 读取；逐字段原因行；组锁定下沉为字段锁定；两类 409 banner；fail-closed |
| `web/src/views/req/tabs/OverviewTab.vue` | 〔修订〕 | 字段行加 `FieldFlag`；删 `isParent` 锁标记；〔新增〕父需求行、Sonar 项目键行 |
| `web/src/views/req/RequirementDetailView.vue` | 〔修订〕 | 「编辑」按钮三态；禁用短句；两类 409 横幅；`openChangeDrawer(presetType, presetFields)` |
| `web/src/views/req/tabs/ChangeTab.vue` | 〔修订〕 | 列改为 7 列（新增目标字段、审批结果；原因/影响并入次行）；`fields` chips；提出人用 `userName()` |
| `web/src/views/req/change.ts` | 〔修订〕 | `FIELD_LABELS` 扩到 11 字段；`verdictText()/verdictTone()`；`changeErrorText()` 补 `field/reasonCode` |
| `web/src/api/req.ts` | 〔修订〕 | `ChangeRow.fields?: string[]` |
| `web/src/api/types.ts` | 〔修订〕 | `FieldDecision`；`RequirementDetail` 增三数组（可选，缺失即 fail-closed） |
| `web/src/styles/tokens.css` | 〔修订·只增〕 | 5 个新令牌（§5.1），不改既有值 |

不做：不新增路由/菜单、不改 `ChangeCreateDrawer` 的四步结构、不改变更详情页、不改 Sonar/门禁卡片、不新增业务单号。

---

## §2 信息架构

5 个表面，全部挂在既有详情页 `/requirements/:id` 下，**无新路由**：

```
需求详情页
├─ 头部动作区（§3.1）      编辑按钮三态 + 禁用短句 + 两类 409 横幅（§3.4）
├─ Tab=概览
│   └─ 基本信息 dl.grid（§3.2）  8 行，非 EDIT 行带标记/发起变更
├─ 编辑抽屉 560（§3.3）     4 组 7 字段，逐字段按数组渲染
├─ Tab=变更（§3.5）         7 列两行：目标字段 + 审批结果
└─ 变更单抽屉 720（r40，不改） ← 预填入口：头部 / 字段行 / 抽屉组头 / 409 横幅
```

信息层级原则：**值（事实）> 标记（规则）> 行动（入口）**。所以标记永远跟在值后面、小一号、不加底；行动只在 `CHANGE_REQUIRED` 出现。

---

## §3 关键页线框与组件

### §3.1 详情页头部动作区〔修订〕

```
┌ 需求标题（h1）                    [状态pill] [●优先级] [范围] ┐
│ [提交] [指派] [关闭] [发起变更] [编辑] 字段均需变更            │   ← actions 行，flex + gap 6 + wrap
│                                          [门动作提示 · 独占整行] │   ← 既有 detail__gate-hint
└──────────────────────────────────────────────────────────────┘
```

- 「编辑」按钮〔修订〕：`class="btn--disabled-reason"`、`data-test="requirement-edit"`、`:disabled="!canOpenEdit(detail)"`、`:title="editDisabledReason(detail)"`、`@click` 首行 guard（`if (!canOpenEdit(detail)) return`）。
- 禁用时紧邻渲染 `span.detail__edit-hint`（12px、`--text-muted-aa`、`nowrap`、`data-test="edit-disabled-hint"`），文本取 `editDisabledHint(detail)`：
  | 场景 | 短句（可见） | title / aria（完整） |
  |---|---|---|
  | 三数组缺失 | 缺少可编辑性规则 | 未获取到可编辑性规则，暂不可编辑 |
  | 7 个 UI 字段至少一个 `CHANGE_REQUIRED`、无 `EDIT` | 字段均需变更 | 当前状态不能直接修改，需发起变更单 |
  | 7 个 UI 字段全 `LOCKED` | 字段均只读 | 需求已结束或字段均被锁定，无法直接修改 |
- 「发起变更」按钮**不动**：仍按 `permissions.changeRequest`（`changeRequestDenied`），因为它是独立通路，不受字段矩阵影响。
- 触发 `openChangeDrawer(presetType?, presetFields?)`：无参 = 头部入口（r40 行为）。

### §3.2 详情页字段行（概览 · 基本信息）〔修订 + 2 行新增〕

```
需求类型    产品需求        🔒 不可改
业务域      支付域
负责人      张三            🔒 指派变更
期望交付    2026-12-01      需变更  发起变更
所属版本    V1.2.0          需变更  发起变更
预估人天    8.0             🔒 汇总
实际人天    6.0             汇总
父需求      REQ-120 支付改造            〔新增行，无父显示「顶层需求」〕
Sonar 项目键 pay-svc                   〔新增行，mono；空显示「—」〕
创建时间    2026-11-20 10:12
最近更新    2026-11-24 09:01
```

- 三态渲染（`FieldFlag`）：`EDIT` → 不渲染任何标记；`CHANGE_REQUIRED` → `需变更`（`--warning-ink`）+ `发起变更` CTA；`LOCKED` → `🔒 {短标记词}`（`--text-muted-aa`）+ tooltip 原因句。
- 短标记词（`REASON_MARKER`，唯一定义在 `editability.ts`）：`TERMINAL` 已结束 / `PARENT_ROLLUP` 汇总 / `NO_PARENT` 顶层 / `ASSIGN_FLOW` 指派变更 / `IMMUTABLE` 不可改 / `UNKNOWN_STATUS` 状态未知；`EARLY_STAGE`、`OPEN_STATE` 不产生标记。
- CTA 权限：`permissions.changeRequest` 为假时 `disabled` + `title="无发起变更权限（需要需求管理权限）"`，**不隐藏**（隐藏会让用户以为该字段可直接改）。
- 删除：`v-if="isParent"` 的两处 `🔒 汇总`（`children.length` 判断）。`预估人天` 改由 `estimateDays` 的 `reasonCode` 驱动；`实际人天` 只保留静态 `汇总` 12px 文本（它不是矩阵字段，不再依赖 `children`）。
- 字段值永不隐藏：`LOCKED` 行 `dd` 内容与可编辑时完全一致，只在末尾多一个标记。
- 覆盖范围声明：`title`（h1）、`description`（段落）、`priority`（头部优先级点）**不挂标记**——它们是阅读主线；其不可编辑性由头部「编辑」禁用短句 + 抽屉逐字段原因承载，值始终可见（满足 PRD anti-goal ②）。矩阵 11 字段在详情页的落点：h1 / 描述段 / 头部点 / 6 个 `dl` 行 + 2 个新增行。
- 〔新增行〕父需求：`router-link` 到父详情（mono `code`）+ 13px 标题，`title` 给全文；无父 → 「顶层需求」（13px `--text-muted-aa`）。
- 〔新增行〕Sonar 项目键：`--font-mono` 13px；空 → 「—」。该行**只读、无输入控件**（QC：AC-9 只读展示条款）。

### §3.3 编辑抽屉 560〔修订〕

```
┌ 编辑需求 ─────────────────────────────────────── ✕ ┐
│ [banner 区] 409 CHANGE_REQUIRED / FIELD_LOCKED / fail-closed │
│ 内容                                               │
│   标题      [input ..............]                 │
│            当前状态不能直接修改，需发起变更单        │  ← 逐字段原因行（仅非 EDIT）
│   描述      [textarea ............]                │
│   优先级    [select]                               │
│   期望交付  [date]                                 │
│   人天                              需发起变更  发起变更 │  ← 组整组锁定时保留组头
│   预估人天  [number]  （disabled 灰底）             │
│            父需求人天由子需求汇总，不可手工填写      │
│   版本      [select]                               │
│   父需求    [select]                               │
│ 被锁字段分属不同变更类型时：edit-note（沿用 r40 文案） │
├────────────────────────────────────────────────────┤
│ [取消]                        [发起变更]  [保存]    │
└────────────────────────────────────────────────────┘
```

- 数据源〔修订〕：`editabilityOf(detail)`；`isEditable(field)` = 该字段在 `editable` 集合内。**删除** `children.length` 判断与 `permissions.editableFields` 读取。
- 逐字段原因行〔新增〕：非 `EDIT` 字段在控件下渲染 `reasonText(reasonCode)`（12px `--text-muted-aa`）；`estimateDays` 的人天说明由 `PARENT_ROLLUP` 原因句替代旧的硬编码 hint（文案一字不差取自 `editability.ts`）。
- 锁定样式〔修订〕：由 `.edit-group.is-locked` 改为 `.edit-field.is-locked`（控件底 `--bg-hover`）——同一组可能部分可改。
- 组锁定块（`需发起变更` + `发起变更`，r40 既有）：仅当整组无 `EDIT` 时出现，行为不变（预填整组字段）。
- footer：`allLocked` 时「发起变更」为主键（r40 既有）；有 `EDIT` 时显示「保存」。
- 三数组缺失（fail-closed）：所有控件 `disabled` + 顶部 `.edit-banner.is-locked`「未获取到可编辑性规则，暂不可编辑」；footer 只有「取消」，**不给**预填入口（避免猜错变更类型；头部入口仍在）。
- 409 分流〔修订〕：
  | 错误 | banner 语气 | 文本 | 行动 |
  |---|---|---|---|
  | `CHANGE_REQUIRED` | warning（`--color-warning-bg` / `--warning-ink`） | 「这些字段已不能直接修改：标题、描述」 | 「发起变更（预填）」链接（既有） |
  | `FIELD_LOCKED` | info（`--color-info-bg` / `--info-ink`） | 「{reasonText}：{fieldLabel(field)}」 | **无**「发起变更」；额外 emit `refresh` 让父级重取详情（数组可能已变），抽屉保持打开 |

### §3.4 两类 409 横幅（详情页顶部）〔修订〕

```
CHANGE_REQUIRED:  ⚠ 需要走变更单：〔被拦字段说明〕  [发起变更（已预填）] [关闭]
FIELD_LOCKED:     ⓘ 字段已锁定：需求已结束，字段只读（类型）      FIELD_LOCKED  [关闭]
其他 400/409:     ✕ 提交被拒绝：〔message〕                       CODE          [关闭]
```

- 前缀逻辑：`FIELD_LOCKED → '字段已锁定：'`；`CHANGE_REQUIRED → '需要走变更单：'`；其余 `'提交被拒绝：'`。
- `FIELD_LOCKED` 的正文取 `reasonText(detail.reasonCode)`，其后用 `fieldLabel(detail.field)` 点名字段；**不渲染** `data-test="change-banner-cta"`（AC-11 死路条款）。code chip 保留 mono（它是码，不是文案）。
- 语气修饰类：`.action-banner--danger`（默认）/`--change`（warning）/`--locked`（info）。
- 关闭后不再自动重现；下次提交再出现。

### §3.5 变更 Tab 列表（7 列，两行制）〔修订〕

```
┌ 状态[全部 ▾]  4 张变更单 ───────────────────────────────────────────────┐
│ 变更单  状态      类型   目标字段 / 原因·影响                审批结果 提出人 时间 │
│ #41    [已应用]  内容  [标题][描述]                          通过    张三  11-24 09:01│
│                       原因：标题笔误 影响：子需求 2 · 人天 +3                     │
│ #40    [待审批]  人天  [预估人天]                            审批中  李四  11-23 18:20│
│                       原因：评估偏差                                             │
└────────────────────────────────────────────────────────────────────┘
```

- 列宽（`table-layout: fixed`，`colgroup`）：变更单 `--w-change-code`(96) / 状态 `--w-change-status`(112) / 类型 `--w-change-type`(88) / 目标字段 **弹性列（无 width）** / 审批结果 `--w-change-verdict`(88) / 提出人 `--w-change-applicant`(96) / 时间 `--w-change-time`(140)。
- 目标字段单元：chips（`fields` 顺序即规范序，`fieldLabel()`）+ 次行 `原因：{reason}` + `影响：{impactSummaryLines().line1}`，单行 `line-clamp`，`title` 拼全文（`原因… ｜影响…`）。`fields` 空 → 「—」；`reason` 空 → 次行只显示影响。
- 审批结果：`verdictText(status)` → `PENDING` 审批中 / `APPROVED`、`APPLIED` 通过 / `REJECTED` 驳回 / `WITHDRAWN` 撤回；未知状态回退 `statusLabel(status)`。
- 提出人：`userName(createdBy)`（`views/req/platform.ts:202`），字典未命中回退 `#id`（mono 12px）。
- 行高 `--h-change-row: 60`（两行内容不低于 60，chips 折行时自然到 82）。
- 保持：整行可点（`tabindex=0` + Enter）、`#id` 链到变更详情、`StatusPill kind="reqChange"`、空态、分页 20、默认无状态过滤。
- 移除：`原因`、`影响摘要` 两个独立列（内容并入次行；AC-12 允许「悬停或次行查看」）。

### §3.6 变更单抽屉的四个预填入口（r40 抽屉不改）

| 入口 | 预填类型 | 预填字段 |
|---|---|---|
| 详情头部「发起变更」 | 无（用户选） | 无 |
| 字段行 CTA（`CHANGE_REQUIRED`） | `changeTypeOfField(field)` | `[field]` |
| 抽屉组头「发起变更」 | `changeTypeOfField(组首字段)` | 整组字段 |
| 409 横幅「发起变更（已预填）」 | `changeTypeOfField(detail.fields[0])` | `detail.fields` |

`ChangeCreateDrawer` 的 `presetType/presetFields` props 已存在，本次不改其内部实现；`dayTypeDisabled`（`children.length > 0` 禁 DAYS）**保留**——它约束的是变更单类型，不是字段可编辑性，与 AC-3 的「删除 children 判断」不冲突（AC-3 限定在编辑抽屉的字段判定）。

---

## §4 交互与状态

### 4.1 矩阵 → UI 映射（唯一映射表）

| 矩阵结果 | 详情字段行 | 编辑抽屉控件 | 头部「编辑」 | 409 引导 |
|---|---|---|---|---|
| `EDIT` | 无标记，值正常 | 可编辑 | 至少一个 `EDIT` 才可用 | — |
| `CHANGE_REQUIRED` | `需变更` + 发起变更 CTA | `disabled` + 原因行 + 组头入口 | 同上 | warning 横幅 + 预填 CTA |
| `LOCKED` | `🔒 短标记` + 原因 tooltip | `disabled` 灰底 + 原因行 | 同上 | info 横幅，**无 CTA** |
| 数组缺失 | 无标记（不猜） | 全 `disabled` + 提示条 | 禁用 + 「缺少可编辑性规则」 | 不产生（前端不发请求） |

### 4.2 关键序列

1. 打开详情 → 读 `detail.editableFields / changeRequiredFields / lockedFields` → 头部按钮态 + 字段行标记一次性渲染（无二次请求）。
2. 点「编辑」→ `canOpenEdit` 为真才开抽屉 → 抽屉按数组渲染 7 字段（同步，无 loading）。
3. 抽屉保存 → PUT 只带真实变化字段 → 成功 `ElMessage.success` + 关闭 + `changed` 重取详情；失败按 §3.3 分流。
4. 字段行 CTA / 409 CTA / 组头 → 同一个 `ChangeCreateDrawer`，只差 `presetType/presetFields`。
5. 变更单创建成功 → 详情重取（`AC-11`：字段仍不变，改为「待审批」路径）。
6. 变更 Tab 打开 → 默认全状态、每页 20 → 行内 7 项 + 次行。

### 4.3 竞态与一致性

- 详情刷新期间（`loading`）不重渲染标记，避免闪烁；刷新完成一次性替换。
- 抽屉保存中按钮 `loading` + `disabled`，防重复提交；`FIELD_LOCKED` 后 emit `refresh`，父级重取后 `detail` prop 更新 → 控件自动按新数组重算。
- 换角色 / 权限变化：`permissions.changeRequest` 为假时所有 CTA `disabled`（不隐藏），前端不做本地权限推断。
- 数组顺序即权威：chips 与字段行标记的渲染顺序取自数组，不排序（规范序由后端保证）。

---

## §5 视觉规格

### 5.1 新令牌（只增不改，加在 `tokens.css` 末尾）

```css
/* r43 · 需求字段可编辑性 */
--w-change-code: 96px;      /* 变更列表「变更单」列（原为内联 96） */
--w-change-verdict: 88px;   /* 变更列表「审批结果」列 */
--w-change-target: 320px;   /* 变更列表「目标字段」弹性列最小宽 */
--h-change-row: 60px;       /* 变更列表行最小高（两行内容） */
--flag-gap: 4px;            /* 字段行标记与值的间隙 */
--tooltip-max: 320px;       /* 原因 tooltip 最大宽 */
```

复用（不新增）：`--w-change-status/type/applicant/time`、`--r-cell`、`--font-mono`、`--focus-ring`、`--bg-hover`、`--bg-selected`、`--warning-ink`、`--success-ink`、`--danger-ink`、`--color-*-bg`、`--ease`、`--t-color`、`--t-overlay`、`--shadow-pop`。

### 5.2 宽高算式

见 §0.7（抽屉 label 96/控件 416、footer 216、tooltip 320、变更表 620+320=940、行高 58→60、目标字段列 420/580、详情 grid 5 列 217.6、头部 206）。所有数字在 1280 / 1440 两个内容宽下都成立；窄于 1280（内容 < 940）时变更表面 `overflow-x: auto`，表格 `min-width: 940px`，不隐藏任何列。

### 5.3 断点

只有 `--change-bp: 1280`（沿用）。本设计不在断点处隐藏列、不折叠标记；唯一表现是变更表在内容 < 940 时横向滚动。

### 5.4 颜色语义

| 语义 | 令牌 | 用途 |
|---|---|---|
| 需变更 | `--warning-ink` / `--color-warning-bg` | `需变更` 标记、`CHANGE_REQUIRED` 横幅底 |
| 已锁定 | `--text-muted-aa` / `--color-info-bg` | `🔒` 标记、`FIELD_LOCKED` 横幅底 |
| 可执行 | `--color-primary` / `--color-primary-hover` / `--color-primary-active` | 发起变更 CTA |
| 通过 | `--success-ink` | 审批结果「通过」 |
| 驳回 | `--danger-ink` | 审批结果「驳回」、其他 400/409 横幅 |
| 撤回 | `--text-muted-aa` | 审批结果「撤回」 |

**颜色不是唯一通道**：每个标记同时有文字（`需变更` / `🔒 汇总`）与 `aria-label`，不允许只靠色差区分三态。

---

## §6 无障碍

- 标记 `span.flag` 一律 `tabindex="0"` + `role="note"` + `:aria-label="reasonText(reasonCode)"`，并用 `el-tooltip`（`hover`，`show-after: 120ms`，popper 类 `flag-tip { max-width: var(--tooltip-max) }`）承载完整原因；键盘聚焦时同样出提示（tooltip 绑定 `focus`；若实现受限，退化为原生 `title` + `aria-label`，并在 PR 里注明 r25 既有债同类）。
- 禁用按钮不可聚焦（r25 已知债）：本轮新增的「编辑」禁用态一律配**可见短句**（§3.1），不依赖 `title`；字段行 CTA 禁用时该行仍有可见的 `需变更` 文本，原因不丢。
- 「发起变更」CTA 的 `disabled` 与 `aria-disabled="true"` 同时给，`title` 说明所需权限码 `changeRequest`。
- 变更表行保持 `tabindex="0"` + `Enter` 打开；chips 不可聚焦（纯信息），整行 `aria-label` 拼「编号 / 状态 / 目标字段」便于读屏。
- 横幅 `role="alert"`（既有）；`FIELD_LOCKED` 横幅同样 `role="alert"`，避免只靠视觉。
- 焦点环统一 `--focus-ring`（2px），不使用 `outline: none` 裸奔；`prefers-reduced-motion` 下横幅淡入、行高亮过渡降为 `0.01ms`。
- 对比度：标记 12px 用 `--text-muted-aa`（≈4.8:1）或语义 ink（≥4.5:1）；`--text-weak` 不用于任何本设计新增文本。

---

## §7 AC 对照

| AC | 设计落点 | 可验证点 |
|---|---|---|
| AC-3 人天 | §3.2 预估人天行 `FieldFlag(estimateDays)`；§3.3 抽屉 `estimateDays` 控件 + 原因行；删除 `children.length` | 静态：`views/req` 与抽屉内 `children` 判断为 0；BUS/PDR 显示 `🔒 汇总`，RD TODO 可编辑 |
| AC-4 结构/身份字段 | §3.2 需求类型/负责人/Sonar 行；§3.3 未新增输入控件 | 类型恒 `🔒 不可改`；负责人 `🔒 指派变更`；域/Sonar 无标记（`OPEN_STATE`） |
| AC-9 按数组渲染 | §3.2 + §3.3 + §3.1 按钮态 | 抽屉只读三数组；非 EDIT 控件 `disabled` + 原因；值不隐藏；数组缺失 fail-closed |
| AC-10 文案单点 | §1 `editability.ts`；§3.2 `REASON_MARKER`；§3.3 `reasonText()` | `views/req` 与 `components/req` 内原因句出现次数 0；未知 code 走兜底中文 |
| AC-11 409 引导 | §3.4 两条横幅；§3.3 抽屉 banner | `FIELD_LOCKED` 无 `change-banner-cta`；`CHANGE_REQUIRED` 有且预填 `detail.fields` |
| AC-12 变更记录 | §3.5 七列 + 次行 | 行内可见编号/状态/类型/目标字段/审批结果/提出人/时间；默认全状态；分页 20 |
| AC-13 从详情发起 | §3.2 字段行 CTA；§3.6 四入口 | 字段行 CTA 与头部打开同一抽屉；类型由 `changeTypeOfField` 推导；无权限 `disabled` + 原因 |
| anti-goal ①（说能改但被拒） | §4.1 唯一映射表 | `EDIT` 才可编辑，`CHANGE_REQUIRED`/`LOCKED` 均 `disabled` |
| anti-goal ②（藏值） | §3.2 值永不隐藏 | 终态 11 字段值全可见（含新增父需求/Sonar 行） |
| anti-goal ③（死路） | §3.4 / §3.3 | `FIELD_LOCKED` 两处都无「发起变更」 |
| anti-goal ④（答不出改过什么） | §3.5 | 目标字段 chips + 审批结果 + 时间列内可见 |
| anti-goal ⑤（文案漂移） | §1 `editability.ts` | 原因句只在 `editability.ts`；组件零硬编码 |

> 注意：AC-10 的静态断言只覆盖**原因句**；`审批结果`词（通过/审批中/驳回/撤回）与字段标签属 `change.ts` 既有单点，不算「原因中文」，避免 QA 误判。

---

## §8 待确认

1. **`sonarProjectKey` 值是否随 Detail 下发**：若详情响应不含该值，§3.2 的 Sonar 行显示「—」（可编辑性仍由数组体现）；若要显示真值，需后端在 Detail 只读暴露该字段（不新增查询）。
2. **`FIELD_LOCKED` 横幅语气**：本设计定为 info（解释而非报错）；若团队要求「409 一律 danger」，只改 §5.4 一处映射。
3. **变更列表去掉「原因」「影响摘要」独立列**：AC-12 允许「悬停或次行查看」；若 QA 断言旧列存在，需回退为 8 列并在 <1280 横向滚动（预算 620+248+320 = 1188 > 1040）。
4. **「编辑」禁用短句占位**：新增约 206px，1280 视口下按钮可能换行到第二行（`flex-wrap` 已允许），设计接受；若要求绝不换行，则删短句、只留 `title`（会退回 r25 键盘债）。
