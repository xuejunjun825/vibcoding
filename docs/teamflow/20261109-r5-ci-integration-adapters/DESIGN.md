# DESIGN · M7 CI 集成对接（Jenkins / GitLab / Sonar / Nexus）

> **基线**：视觉与骨架沿用 `docs/teamflow/20261108-r2-devops-req-platform/DESIGN.md` 与 `docs/teamflow/20261109-r3-qa-quality-management/DESIGN.md`（令牌、三区骨架、结论条、证据行、抽屉）；本文件只扩展「集成」域页面与组件，不另起视觉语言。
> **冲突裁决**：功能与断言以本任务夹 `PRD.md` 为准；样式与交互以本文件为准。
> **标记**：`[沿用]` 不改基线；`[新增]` 本轮新增；`[修订]` 替换基线并写明原因。
> **技术落点**：`web/`（Vue 3 + Element Plus + Vite）。只覆盖 Element Plus CSS 变量 + 一层主题；不引入第二套 UI 框架；不做暗色主题；**不嵌 iframe**（Jenkins 控制台只给外链）。
> **准入项数**：本文件一律按 **9 项**（①-⑨，第 6 项仍是 M6 质量门）。任何「5 项 / 6 项 / 8 项」文案、任何跳过却显示「9/9」的渲染，均为缺陷。

---

## 1 观感与视觉意图（实现前必读，先于信息架构）

### 1.1 一句话

**「接线台」+「证据台」**：管理端像一台**工业接线面板**——四个插槽、每槽一句「通/断/未接」、凭据只露末四位；业务端像一张**证据台**——每个外部事实只回答「哪一次、什么结论、证据在哪、下一步点什么」。底色、1px 线、留白沿用 r2「安静的企业工作台」，**不引入任何第三方系统的主色**（没有 Jenkins 蓝、GitLab 紫、Sonar 青、Nexus 绿），四个系统只用同一套中性图标 + 语义色。

语义色纪律：绿色**只**用于「服务端判定通过/已同步」；红色**只**用于「FAIL / 校验不一致 / 阻断提交」；黄色**只**用于「需要人判断或配置」（已关闭、跳过、手工制品、待人工推进）；灰色用于「未配置 / 未绑定 / 无数据」。**跳过永远不是绿色。**

### 1.2 表面处理（逐组件类）

| 组件 | 圆角 | 描边 | 阴影 | 底色 |
| --- | --- | --- | --- | --- |
| 集成配置卡 `[新增]` | 8px | 1px `#E5E6EB`；未配置卡 1px **虚线** `#C9CDD4` | 无（hover 仅描边 `#C9D6EE`） | `#FFFFFF`；未配置 `#FAFBFC` |
| 已关闭 / 未配置状态 pill `[新增]` | 999px | 1px 同语义浅边 | 无 | 已关闭 `#FFF7E6`；未配置 `#F2F3F5`；已启用 `#E8F7EC` |
| 凭据只读行 `[新增]` | 6px | 1px `#E5E6EB` | 无 | `#FAFBFC`；等宽末四位 chip 白底 1px `#C9CDD4` |
| 连接测试结果条 `[新增]` | 6px | 0（左侧 2px 语义竖条） | 无 | success `#E8F7EC` / danger `#FDECEC` / 进行中 `#F2F3F5` |
| 准入清单 `[修订 GateChecklist · mode=admission]` | 8px | 1px `#E5E6EB`；第 1-9 行之间 1px `#F2F3F5` | 无 | `#FFFFFF` |
| 准入状态 pill `[新增]` | 4px | 1px 同语义浅边（PASS `#CBEBD5` / FAIL `#F7D4D5` / SKIP `#E9C989` / CLOSED `#E5E6EB`） | 无 | PASS `#E8F7EC` / FAIL `#FDECEC` / SKIP `#FFF7E6` / CLOSED `#F2F3F5` |
| 构建状态卡 `[新增]` | 8px | 1px `#E5E6EB`；RUNNING 左 2px `#2B6FE5`；FAILURE 左 2px `#D9363E` | 无 | `#FFFFFF`；失败头部 `#FFFBFB` |
| 阶段 chip `[新增]` | 4px | 1px `#E5E6EB` | 无 | 通过 `#E8F7EC` / 失败 `#FDECEC` / 未执行 `#F2F3F5` |
| 代码卡 / Sonar 卡 `[新增]` | 8px | 1px `#E5E6EB` | 无 | `#FFFFFF` |
| sha256 / buildId chip `[新增]` | 4px | 1px `#E5E6EB` | 无 | `#F7F8FA`，mono 12 |
| 制品卡 `[新增]` | 8px | 1px `#E5E6EB` | 无 | `#FFFFFF`；手工来源顶条 2px `#C77700` |
| 同步日志表 `[新增]` | 0（容器 8px） | 表头底 1px `#E5E6EB`，行 1px `#F2F3F5` | 无 | 表头 `#FAFBFC`；聚合父行 `#F7F8FA` |
| 抽屉（配置/制品/接入文档/快照）`[沿用]` | 0 | 左 1px `#E5E6EB` | `-8px 0 24px rgba(0,0,0,.06)` | `#FFFFFF` |
| 按钮 / 输入 / switch / 下拉 `[沿用]` | **关闭态不用红色边框**，不用渐变 | 同 r2 §1.2 | 无 | 同 r2 |

> **禁止**：渐变、玻璃拟态、彩色整卡、圆角 > 8px、把四个系统各染一种品牌色、给集成卡加投影「浮起来」、用 emoji 表示状态。

### 1.3 状态反馈（每个可交互元素都必须有）

| 元素 | hover | active / pressed | focus-visible | selected / current | disabled |
| --- | --- | --- | --- | --- | --- |
| 配置卡（整卡可点开编辑） | 描边 `#C9D6EE`，右下「编辑 ›」显形（opacity 0→1，120ms） | 底 `#FAFBFC` | 2px `#2B6FE5` outline，offset 2px | 编辑中：描边 `#2B6FE5` + 左上 2px 主色条 | 无权限：卡可读，字段 disabled，底部说明「只读：需要 integration:manage」 |
| switch（启用/准入项） | 滑轨 `#B9C6DC` | 拖动中 knob 不变形（**禁止 scale**） | ring 包住 32×18 | on：`#2B6FE5`；off：`#C9CDD4` | 必开项：`#C9CDD4` + 锁图标 + tooltip「必开项，关闭返回 409 ITEM_LOCKED」 |
| 凭据「更换」 | 文字 `#1F5FD0` | 文字 `#1A54BC` | ring | — | 保存中：按钮 loading，输入框只读 |
| 「清除凭据」 | 文字 `#B3262E`，底 `#FDECEC` | 底 `#F7D4D5` | ring | — | 未配置：不渲染 |
| 准入行（点击展开） | 底 `#F7F9FC`；右侧「详情」出现 | 底 `#F2F3F5` | ring 包住整行 | 展开：底不变，证据区展开（160ms） | CLOSED 行不可展开（无详情），不置灰名称 |
| 「触发构建」主按钮 | `#1F5FD0` | `#1A54BC` | ring | — | 关闭/未配置：disabled + 同行原因文字；RUNNING：文案「构建运行中」禁点 |
| 「转缺陷」 | 文字 `#1F5FD0` | 同 | ring | — | 已转缺陷：变「已建缺陷 BUG-123 ›」链接 |
| 「刷新质量门」 | 同主按钮 | 同 | ring | loading 时按钮内 16px 旋转图标，文字不变 | 未绑定 key：disabled + 「先绑定 project key」 |
| 制品行（检索抽屉） | 底 `#F7F9FC` | 底 `#F2F3F5` | ring | 选中：底 `#EAF1FE` + 左侧 2px 主色 | 无 sha256 的行仍可选，但选中后提示「该制品无校验值」 |
| 同步日志聚合行 | 底 `#F7F9FC`；chevron 转 90°（120ms） | 底 `#F2F3F5` | ring | 展开：chevron 向下 | — |
| 复制 chip（sha256 / buildId / 回调地址） | 底 `#EAF1FE`，图标显形 | 底 `#DCE8FD` | ring | 复制成功 1.2s 变「已复制 ✓」（文字，非动画） | 只读模式仍可复制 |

- 全局过渡沿用：颜色/边框 **120ms**，展开 **160ms**，抽屉/弹窗 **240ms**，缓动 `cubic-bezier(.2,0,0,1)`；**禁止** scale、bounce、spring、数字滚动。
- 焦点一律 **2px 实心 outline + offset 2px**；不使用颜色渐变模拟焦点。
- 危险动作（清除凭据、关闭准入项、晋级生产、确认推进需求）一律二次确认 + 影响范围逐条列出。

### 1.4 动效、加载、空态与错误态

- **保存配置**：按钮 loading，成功就地改状态 pill 与「最后修改」，仅 toast「已保存」；失败常驻卡内错误条（错误码 + 原因 + 重试），不清空已填内容。
- **测试连接**：按钮 loading ≤6s；结果就地出现在凭据行下方（`#E8F7EC`/`#FDECEC` 条），**不改变**已保存配置；超时文案「未在 6s 内响应（已脱敏）」。
- **触发构建**：点击 → 按钮 loading → 202 后卡内切到 `QUEUED`（queueId 显示）；**不整页刷新、不弹成功框**。轮询状态变化时只更新状态 pill、阶段 chip 与耗时，卡高不变。
- **RUNNING 呼吸点**：8px 圆点 `#2B6FE5`，opacity 1→0.35，1.6s 循环；仅 RUNNING 用，`prefers-reduced-motion` 下静止。
- **准入刷新**：按钮 loading，清单原地更新；若 `stale=true`，顶部出现黄条「数据已变化，请重新校验」，**不自动重算**（防止审核中被换数）。
- **加载**：配置页 4 张卡骨架（140px 高）；准入清单 9 行骨架（每行 48px）；构建卡状态区骨架；日志表 8 行；抽屉表格 6 行。翻页保留旧数据 + 顶部 2px 进度条。
- **空态**（必须区分「还没有」「未配置」「筛选无结果」）：
  - 集成未配置：卡内虚线区「尚未配置 · 填写 base_url 与凭据后启用」，附该系统的 `extra` 必填提示。
  - 集成关闭：卡内黄条「已关闭：业务入口禁用，准入对应项记 SKIP(NOT_CONFIGURED)」。
  - 无构建：`还没有构建记录` + [触发构建]；Jenkins 关闭时按钮 disabled + 「Jenkins 未启用（管理端 › 集成配置）」。
  - 无制品：`尚未选择制品` + [从 Nexus 检索]；手工来源黄标。
  - Sonar 未绑定：`未绑定 Sonar 项目` + [绑定]；已配置但拉取失败：红条「拉取失败 · 准入第 8 项计为失败」+ [重试]。
  - 无 MR：`未填写 MR 链接` + 输入入口；未结构化：黄标 `未结构化（不参与 MR 准入）`。
  - 日志空：`还没有同步记录（四类集成默认关闭）` + [去集成配置]；筛选无结果：`没有符合筛选的记录` + [清空筛选]。
- **错误态**：403 整页；404 页面；409 一律**常驻清单/条**（不用 toast），逐条 `项目 + 原因 + 修复入口`；5xx/网络错误只降级当前卡，不把整页变错误页（AC-2.3）。
- `prefers-reduced-motion`：关闭呼吸点、chevron 旋转、骨架 shimmer，只保留即时显隐。

### 1.5 字体与数字角色

系统字体栈与 `font-variant-numeric: tabular-nums` `[沿用]`。新增/修订角色：

| 角色 | 字号 / 字重 | 颜色 | 备注 |
| --- | --- | --- | --- |
| 集成卡名称 | 15 / 600 | `#1F2329` | 后跟 12/400 一句话用途 |
| 凭据末四位 / buildId / sha256 | 12 / 500 mono | `#1F2329` | 末四位 `letter-spacing:.04em`；sha256 只显示 **前 12 位** |
| 字段标签 | 12 / 400 | `#6B7280` | 行高 20，不做 11px |
| 准入项名 | 13 / 500 | `#1F2329` | 被 CLOSED 时不划线、不置灰 |
| 准入项 code | 11 / 500 mono | `#A9AEB8` | `ADM-07`，列宽固定 64 |
| 准入计数 | 13 / 600 tabular | `#1F2329` | `通过 4 · 失败 2 · 跳过 2 · 关闭 1`；数字与词之间不断行 |
| 准入详情 / 跳过原因 | 12 / 400 | `#4E5969` | **单行省略**，`title` 给全文；禁用 tooltip-only 表达原因 |
| 构建耗时 | 13 / 600 tabular | `#1F2329` | `1m 42s`；未知 `—` |
| Sonar 度量值 | 16 / 600 tabular | `#1F2329` | 标签 12/400；达标阈值 11/400 `#6B7280` |
| 日志时间 / 耗时 | 12 / 400 tabular | `#4E5969` | `11-09 12:30:04`；耗时右对齐 |
| 日志请求摘要 | 11 / 400 mono | `#6B7280` | 截断 48 字符 + tooltip，**不得含 token** |

### 1.6 几何自洽（按最大取值验算，改数必须重算）

| 构件 | 定稿尺寸 | 验算 |
| --- | --- | --- |
| 集成配置卡 `[新增]` | 宽 **424**（admin 内容 864：2 列 (864−16)/2），min-height **452**，padding 16 | header 40 + 5 行字段 5×58=290 + gap 4×12=48 + footer 40 + padding 32 = **450** ≤452；<960 视口切 1 列（卡宽 100%） |
| 配置卡字段行 | 标签 20 + 控件 32 + gap 6 = **58** | `https://gitlab.example.com` 20 字符 12px ≈150 < 392 内容宽 |
| 凭据只读行 | **392×32**：文案 flex + 末四位 chip **92×22** + 两个文字按钮 | chip 4 字 mono 12 ≈29 + padding 16 + 边 2 = 47 ≤92，留余量；`已配置 · 末四位` 12px ≈132 |
| 准入清单 `[修订]` | 行高 **48**（展开 **96**）；9 行 432；header 44；计数条 **40**；skip 横幅 **36**；脚注 32；总 **584**（有 skip）/548（无） | 主列 704 = 状态 64 + code 64 + 名称 150 + 详情 flex 314 + 展开按钮 48 + 5 个 8 gap + padding 24 ≈ 682；行内 18+2+16+padding 12 = 48；展开 18+32+18+gap 8+padding 16 = 92 ≤96 |
| 准入 pill | **64×20**（PASS/FAIL/SKIP/CLOSED 同一宽，防跳动） | `CLOSED` mono 11 六字 ≈40 + padding 16 + 边 2 = 58 ≤64；中文两字 ≈22 + 16 = 38 |
| 构建状态卡 `[新增]` | 宽 **320**（side 固定列），高 ≈ **476**（按内容，不固定） | header 40 + 状态区 64 + 阶段 2 行 56 + 触发 32 + 最近 5 条 5×44=220 + 分隔/脚 32 + padding 32 = 476；`#1284` 5 mono ≈36 + pill 64 + 耗时 56 + gap 24 = 180 < 296 |
| 最近构建行 | **296×44**：第一行 20（id + 状态 + 耗时），第二行 16（触发人 · 时间 · 日志） | 时间截断为 `11-09 12:30`；日志链接固定行尾，不换行 |
| 制品卡 `[新增]` | 宽 704，高 ≈ **192** | header 40 + 主行 56 + sha 行 26 + 晋级行 32 + gap 8 + padding 32 = 194 |
| sha256 chip | **160×22** | 前 12 位 mono 12 ≈87 + 省略号 8 + 复制 16 + padding 16 = 127 ≤160；完整值进 tooltip + 复制 |
| 代码卡 `[新增]` | 宽 704（主列），高 ≈ **280** | header 40 + 标题 2 行 40 + 3 行字段 3×44=132 + 脚 36 + padding 32 = 280 |
| Sonar 卡 `[新增]` | 宽 704，高 ≈ **268** | header 40 + 状态行 56 + 度量 3 列 × 2 行（每格 52）=104 + 脚 36 + padding 32 = 268；第 6 格留空不放装饰 |
| 制品检索抽屉 `[新增]` | 宽 **720**；表格可视 **8 行**（表头 40 + 8×48=384）；搜索行 36；footer 56 | 720 − padding 48 = 672：名称 180 + 版本 120 + 打包 64 + 大小 80 + 时间 96 + 操作 72 = 612 ≤672 |
| 接入文档抽屉 `[新增]` | 宽 **640** | 差异表 6 行 ×44 = 264 + 系统块 4×96 = 384，内滚；不横向滚动 |
| 快照抽屉 `[沿用 560]` | 560 | 与 M6 报告抽屉同宽，度量 5 行 5×40=200 |
| 计数条 | 高 **40**，padding 0 12 | `通过 4 · 失败 2 · 跳过 2 · 关闭 1` 13px ≈ 236 + padding 24 < 704 |

### 1.7 反模式（出现即返工）

1. 凭据出现明文、出现「查看」按钮、或可被复制到剪贴板；日志/摘要里带 `Authorization`、token、query 中的 secret。
2. 跳过（SKIP）渲染成绿色勾、计入通过，或页面出现「9/9」「100%」。跳过一律黄 + 文字原因。
3. 仍出现「6 项准入」；或分母写死 9 但关闭项仍算通过。
4. 集成关闭时按钮仍可点、点了转圈再报 500；正确做法是 disabled + 具体原因（含去配置的入口）。
5. 构建中再点一次产生第二行构建记录；或 RUNNING 被迟到回调改成 QUEUED。
6. 打开发布单详情才同步外呼四个系统（白屏/8 秒）；准入只读本地快照，页面渲染不依赖任何外部请求。
7. 409 只弹一个 toast；准入失败必须常驻清单，逐项给差距与修复入口。
8. sha256 只给前 8 位且没有完整值入口；或大小写混排（统一小写）。
9. 手工填写的制品被渲染成「已校验」；手工来源必须黄标 + 准入第 9 项 SKIP。
10. MR merged 后无提示地自动推进需求；必须显式显示「已按版本配置自动推进」或人工确认条。
11. 适配器挂掉把需求详情/发布单整页变成错误页；只允许对应卡降级 + 重试。
12. 用 iframe 嵌 Jenkins 控制台或 GitLab 页面；只允许新窗口外链，且域名需通过白名单校验。
13. 日志页自动刷新导致正在读的行跳动（默认关闭，开启后保留滚动位置）。

---

## 2 设计原则

1. **状态先于动作**：每个入口都先回答「未配置 / 已关闭 / 可用 / 运行中」，再决定是否渲染动作；不可用给原因与去配置的链接。
2. **跳过 ≠ 通过**：四态（PASS/FAIL/SKIP/CLOSED）用四种不同文字与颜色，计数与结论同时出现，禁止把 SKIP 折进 PASS。
3. **fail-closed 可见**：已配置但取数失败一律红，并且页面明说「该项按失败计，提交会被拒绝」，不留给用户猜。
4. **证据一处、口径一处**：构建、MR、Sonar、制品事实只从本地同步结果读；页面不直连外部系统；同一数字不在两处用两套算法。
5. **降级不扩散**：外部不可用只影响自己的卡与准入对应项，主流程（需求保存、发布单列表、提交）照常可用。
6. **管理端密而稳**：配置页以「四槽」组织，改动有审计；凭据只写不读。

---

## 3 信息架构

### 3.1 站点地图（M7 增量）

```
设置（顶栏 admin）
└─ 集成配置 /admin/integrations          [新增] 三 tab：集成配置 / 准入项 / 同步日志
发布（顶栏 deploy）
├─ 发布单列表 /deploy-orders             [修订] 新建对话框的制品检索
└─ 发布单详情 /deploy-orders/:id         [修订] 准入 9 项 + 构建卡 + 制品卡
需求（顶栏 requirements）
└─ 需求详情 /requirements/:id            [修订] 第 8 个 tab「集成」：代码卡 + Sonar 卡 + 同步记录
版本（顶栏 releases）
└─ 版本详情 /releases/:id                [修订] 概览侧栏「集成设置」卡
追溯（顶栏 trace）
└─ 追溯矩阵 /traceability                [修订] SONAR_GATE / BUILD 节点与证据
```

### 3.2 页面可达路径（不允许死链）

| 入口 | 路径 | 说明 |
| --- | --- | --- |
| 侧栏 › 管理 › 集成配置 | `/admin/integrations` | 新增 nav 项 `admin-integrations`，icon `plug`，`functionCode: 'integration:manage'` |
| 准入项 | `/admin/integrations?tab=admission` | 同页 tab，无新路由 |
| 同步日志 | `/admin/integrations?tab=logs&correlationId=` | 同页 tab，可从准入失败行、构建卡、需求集成 tab 深链 |
| 发布单 › 触发构建 | `/deploy-orders/:id#build` | 卡锚点，点击不跳页 |
| 需求 › 集成 | `/requirements/:id?tab=integration` | tab 深链，通知/缺陷可直达 |
| 集成失败告警 | 通知中心 → `/admin/integrations?tab=logs&correlationId=` | 站内通知复用既有中心，kind `integration` |

### 3.3 角色落点与可见性 `[新增]`

| 角色 | 可见/可操作 | 不可见 |
| --- | --- | --- |
| 平台管理员 | 集成配置全部字段、测试连接、启停、凭据轮换、准入项开关、同步日志 | — |
| 发布经理 | 发布单：构建卡（触发/转缺陷）、制品检索与晋级、准入清单 | 配置页只读（无 `integration:manage` 不渲染 nav） |
| 研发 | 需求集成 tab：MR 链接、MR 只读信息、Sonar 项目 key（若有 `req:edit`） | 制品晋级、准入项开关 |
| 质量工程师 | Sonar 卡刷新、快照详情、追溯节点 | 配置页、凭据 |
| 审批人 | 准入 9 项与证据、提交（跳过确认） | 触发构建（除非同时是发布经理） |

---

## 4 页面 / 模块清单

| # | 页面/模块 | 路由 | 类型 | 主要 AC |
| --- | --- | --- | --- | --- |
| I1 | 集成配置（四槽） | `/admin/integrations` | 新增 | AC-1、AC-10、AC-11 |
| I2 | 准入项开关 + 同步日志（同页两 tab） | `?tab=admission` / `?tab=logs` | 新增 | AC-2、AC-8.2 |
| I3 | 发布单详情：准入 9 项 / 构建卡 / 制品卡 | `/deploy-orders/:id` | 改造 | AC-3、AC-7、AC-8 |
| I4 | 需求详情 › 集成 tab（代码 / Sonar / 同步记录） | `/requirements/:id?tab=integration` | 新增 tab | AC-4、AC-5、AC-6 |
| I5 | 发布单列表 › 新建/编辑：制品检索 | `/deploy-orders` | 改造 | AC-7.1 |
| I6 | 制品检索抽屉 + 晋级确认 | 抽屉 | 新增 | AC-7.1/7.4 |
| I7 | 版本详情 › 集成设置卡 | `/releases/:id` | 改造 | AC-5.1、AC-6.1 |
| I8 | 追溯：SONAR_GATE / BUILD 节点 | `/traceability` | 改造 | AC-6.5 |
| I9 | 「如何接入」抽屉 + 桩差异表 | 抽屉 | 新增 | AC-11 |

---

## 5 关键页面线框描述

### I1 集成配置 `/admin/integrations`

```
┌ 系统管理 ────────────────────────────────────────────────────────────┐
│ 集成配置        CI 对接：Jenkins · GitLab · Sonar · Nexus            │
│ 左侧 admin 子菜单（＋集成配置）   右：四类集成 · 已启用 1 · 已关闭 1 │
│                                    · 未配置 2          [本地桩模式]  │
├──────────────────────────────────────────────────────────────────────┤
│ ┌ 集成卡 424×452 ────────────┐  ┌ 集成卡 424×452 ────────────────┐ │
│ │ [J] Jenkins    ● 已启用 [switch] │ │ [G] GitLab    ○ 未配置 [switch] │ │
│ │ 用途：触发构建、回写状态         │ │ 用途：MR / pipeline 同步          │ │
│ │ base_url  [https://jenkins…]     │ │ base_url  [                    ] │ │
│ │ 凭据      [已配置 · 末四位 8f2a] │ │ 凭据      [设置凭据 ▸]           │ │
│ │           [更换] [清除]           │ │                                  │ │
│ │ Webhook   [已配置 · 末四位 91c0] │ │ Webhook   [设置 secret ▸]        │ │
│ │ 超时/重试 [5000 ms] [5 次]       │ │ 超时/重试 [5000 ms] [5 次]       │ │
│ │ 扩展      job 名 [order-build]   │ │ 扩展      project [group/app]    │ │
│ │ ─────────────────────────────    │ │ ─────────────────────────────    │ │
│ │ [保存] [测试连接]   如何接入 ›   │ │ [保存] [测试连接]   如何接入 ›   │ │
│ └──────────────────────────────────┘ └──────────────────────────────────┘ │
│ ┌ Sonar 卡 ─────────────────────┐  ┌ Nexus 卡 ──────────────────────┐ │
│ │ 开关 / base_url / token / 扩展 │  │ 开关 / base_url / 凭据 /        │ │
│ │ 扩展：默认 project key         │  │ repo / prodRepo / 白名单主机    │ │
│ └───────────────────────────────┘  └────────────────────────────────┘ │
│ 脚注：凭据 AES-GCM 加密只写不读；每次保存/停用/轮换写审计，详情中凭据显示 *** │
└──────────────────────────────────────────────────────────────────────┘
```

- **卡头**：左侧 28×28 中性方形图标（不是品牌 logo），名称 + 12px 用途；右侧状态 pill（已启用/已关闭/未配置）+ switch。
- **字段**：标签在上（12px），控件 32 高；`extra` **不提供 JSON 文本框**，按系统渲染具名字段：
  - Jenkins：`jobName`（必填）· `jobParams`（可选，key=value 每行一条）
  - GitLab：`projectPath`（兜底轮询默认项目）· `pollMinutes`（5–60，默认 10）
  - Sonar：`defaultProjectKey`（版本默认 key）
  - Nexus：`repo`（默认仓库，必填）· `prodRepo`（晋级目标，未填则晋级按钮 disabled）· `allowedHosts`（host:port，逗号分隔，出站白名单）
- **Webhook 行**（Jenkins/GitLab）：secret 只写不读；下方只读回调地址 chip `POST /api/integration/webhooks/gitlab/{configId}`，带复制；说明「在 GitLab 项目 Settings › Webhooks 粘贴此地址并填同一 secret」。
- **测试连接**：只读探针；结果显示在该卡凭据行下方（成功给耗时与版本号；失败给脱敏原因），不写库、不改保存配置；探针期间不锁整卡。
- **状态与动作**：未配置时 [保存] 仅在 `base_url` 合法时可点；`base_url` 非法 400 时就地标红（「只接受 http/https、≤500、不含账号密码」）；关闭 switch 立即 PATCH，黄条说明影响。
- **权限**：无 `integration:manage` 时整页只读（字段 disabled、按钮不渲染），不展示配置 nav。

### I2 准入项 `?tab=admission` 与同步日志 `?tab=logs`

```
[集成配置] [准入项] [同步日志]
┌ 准入项（分母恒为 9）──────────────────────────────────────────────┐
│ ① 关联需求测试通过      ADM-01  [必开 🔒]  —                      │
│ ② 无未关闭 P0/P1        ADM-02  [必开 🔒]  —                      │
│ ③ 需求级审批通过        ADM-03  [必开 🔒]  —                      │
│ ④ MR 链接已填           ADM-04  [可关]  switch   关闭后记 CLOSED   │
│ ⑤ 人天已填              ADM-05  [可关]  switch                    │
│ ⑥ 质量门（M6）          ADM-06  [必开 🔒]  —                      │
│ ⑦ 最近一次构建成功      ADM-07  [可关]  switch                   │
│ ⑧ Sonar 质量门通过      ADM-08  [可关]  switch                   │
│ ⑨ 制品在库且 sha256 一致 ADM-09  [可关]  switch                   │
│ 注：关闭 ≠ 跳过；关闭项不计 PASS/FAIL/SKIP，分母仍为 9。            │
└──────────────────────────────────────────────────────────────────┘
```

- 必开项：锁图标 + switch disabled + tooltip「必开项：关闭会返回 409 ITEM_LOCKED」；行底 `#FAFBFC`。
- 可关项：switch 即时保存；关闭 ⑦⑧⑨ 前先 popconfirm，文案列影响（「关闭后构建结果不再参与准入，已有发布单此项显示「已关闭」」）。
- 保存中 switch 旁 12px 转圈；失败回滚 switch + 行内红字。

**同步日志**：筛选行（类型 / 方向 / 结果 / 时间范围默认近 7 天 / 关键字）+ 「自动刷新 30s」开关（默认关）。表格列：时间 140 · 类型 88 · 方向 72 · 操作 160 · 结果 96 · HTTP 64 · 耗时 80 · 重试 56 · 请求摘要 flex · 关联 80。同 `correlation_id` 聚合为父行（`3 次尝试 · 总耗时 7.2s`），展开为子行；`RETRY_EXHAUSTED` 默认展开。结果列用四态 pill（SUCCESS 绿 / FAILED 红 / IGNORED 灰 / RETRY_EXHAUSTED 黄）。摘要列只读、可复制，**不得**含 `Authorization`、token、secret。空态与筛选空态分开；点击「关联」跳构建卡或需求集成 tab。

### I3 发布单详情 `/deploy-orders/:id` `[修订]`

```
主列 704                                       侧列 320
┌ 准入结论条 56 ────────────────────────┐   ┌ 构建 · Jenkins 320×476 ─────┐
│ 未通过 / 可提交（跳过 2 项）/ 通过      │   │ ● 已启用  #1284  [触发构建]  │
│ 通过 4 · 失败 2 · 跳过 2 · 关闭 1      │   │ QUEUED→RUNNING→SUCCESS      │
└───────────────────────────────────────┘   │ 阶段 compile ✓ unit-test ✗  │
┌ 发布准入检查（9 项）───────────────────┐   │ 最近构建                     │
│ ① 测试通过            PASS  [详情]     │   │ #1284 SUCCESS 1m42s          │
│ …                                      │   │   张三 · 11-09 12:30 · 日志  │
│ ⑦ 最近构建成功        FAIL  [详情]     │   │ #1283 FAILURE  48s [转缺陷]  │
│    构建 #1284 失败 · exit 2            │   └─────────────────────────────┘
│ ⑧ Sonar 质量门        SKIP  [详情]     │   ┌ 发布基本信息（沿用）────────┐
│    跳过：Sonar 未配置                   │   │ …                           │
│ ⑨ 制品 sha256         SKIP  [详情]     │   └─────────────────────────────┘
│    跳过：制品为手工填写                 │   ┌ 执行记录（沿用）────────────┐
│ 黄条：本次提交不校验 2 项，不等于全部通过│   │ …                           │
│ [重新校验]        [提交发布（跳过 2 项）]│   └─────────────────────────────┘
└────────────────────────────────────────┘
┌ 制品 · Nexus 704×192 ──────────────────────────────────────────────┐
│ 制品版本 order-service:2.4.0  [NEXUS]  sha256 [a1b2c3d4e5f6… ⧉]     │
│ 校验：一致（11-09 12:31）   [更换制品] [晋级至 PROD]                │
└────────────────────────────────────────────────────────────────────┘
```

- 结论条沿用 M6 组件：有 FAIL → 红「准入未通过」；仅 SKIP → 黄「可提交（跳过 z 项）」；全 PASS → 绿「通过」；`legacy` 单据 → 灰「质量门未启用（历史单据）」，第 7/8/9 项按缺数据如实显示 SKIP/FAIL，不伪造。
- 每行结构：序号（①..⑨，固定 20 宽）· 状态 pill 64 · code 64 · 名称 150 · 详情 flex（单行省略，`title` 全文）· [详情] 48。点击行展开（96 高）：完整 detail、`error_code`、`checkedAt`、证据链接（构建 → 构建卡锚点；Sonar → 快照抽屉；制品 → 制品卡）。
- 跳过原因必须带前缀且具体：`跳过：Sonar 未配置` / `跳过：Sonar 已关闭` / `跳过：需求未绑定 project key` / `跳过：制品为手工填写` / `跳过：Nexus 未配置`。
- 提交：仅 SKIP 可提交；按钮文案带跳过数；确认弹窗逐条列跳过项 + 必勾「我已知晓 z 项未校验」；提交 409 时把清单留在页面，顶部结论条变红，逐项给修复入口。
- 构建卡：`QUEUED/RUNNING/SUCCESS/FAILURE/ABORTED` 五态；2h 超时显示 `超时未回写`（黄条 + [重新触发]）；FAILURE 行出现 [转缺陷]，成功后变「已建缺陷 BUG-xxx ›」；`console_url` 被拒绝时日志链接替换为「日志链接不可用」灰字 + tooltip「域名不在白名单」。
- 制品卡：`source=MANUAL` 时顶部 2px 黄条 + 「手工填写 · 准入第 9 项跳过」；`sha256` 不一致红条给期望/实际前 12 位 + [查看完整值]；晋级按钮在 `prodRepo` 未配置时 disabled + 「先在 Nexus 配置填写生产仓库」。

### I4 需求详情 › 集成 tab `/requirements/:id?tab=integration` `[新增 tab]`

```
[概览] [子需求] [任务] [测试] [缺陷] [审批] [追溯] [集成 ②]
┌ 代码 · GitLab ──────────────────────────────────────────────────────┐
│ MR 链接 [https://gitlab…/merge_requests/123            ] [保存]      │
│ MR 标题  feat: 订单拆单支持多仓           [merged]  已批准 2/2       │
│ 分支    feature/order-split → main        变更 +128 −34              │
│ 最后同步 11-09 12:30（webhook）    [刷新]                            │
│ 黄条：MR 已合入，推进需求到「已合入」？ [确认推进] [暂不]            │
└─────────────────────────────────────────────────────────────────────┘
┌ 质量门 · Sonar ─────────────────────────────────────────────────────┐
│ 项目 key [order-service            ] 继承：版本默认  [刷新质量门]    │
│ 结论 [OK] 覆盖率 82.4%  Bugs 0  漏洞 0  异味 12  重复率 1.2%        │
│ 快照 11-09 12:10 · a1b2c3d4 [查看快照]                              │
└─────────────────────────────────────────────────────────────────────┘
┌ 同步记录（该需求，最近 10 条，[全部日志 ›]）──────────────────────────┐
│ 时间          类型    方向  操作          结果    耗时  摘要          │
└─────────────────────────────────────────────────────────────────────┘
```

- 代码卡：MR 链接可内联编辑；解析失败仍保存 `mr_url` 并标「未结构化（不参与 MR 准入）」；结构化字段只展示 `mr_sync` 数据；`changed_lines` 用 `+x/−y` 双色（绿/红文字，不加底色）；审批人显示 `已批准 2/2`，hover 出名单。
- merged 推进：`mrAutoAdvance=true` 显示信息条「已按版本配置自动推进到「已合入」（12:31）」；`false` 显示黄条与 [确认推进]/[暂不]；只对 RD 渲染，BUS/PDR 显示灰字「该需求类型不参与自动推进」；409（状态机不允许）就地红字 + 当前状态，不留「已推进」假象。
- Sonar 卡：key 未填时显示「继承版本默认：order-service」；清空后回退继承；非法字符 400 就地红字。结论 `OK` 绿 / `ERROR` 红 / 未绑定灰 / 拉取失败红条（**明确写「准入第 8 项计为失败」**）+ [重试] + [查看日志]。五度量 3 列网格（第 6 格空），值 + 阈值 + 达标标记；`fetched_at` 与 hash 前置 mono chip。
- 同步记录：MR / 构建 / Sonar 三类合并按时间倒序 10 条，行内 `correlation_id` 可跳日志页。

### I5 发布单列表 › 新建对话框 `[修订]`

- 制品版本行改为「输入 + 检索」：`[order-service:2.4.0-…] [从 Nexus 检索]`；
- 检索选中后：只读展示 `group:artifact:version` + 来源 pill `NEXUS` + sha256 chip + [更换]；
- 手工输入时：来源 pill `MANUAL` 黄 + 行下提示「手工填写：准入第 9 项将跳过，不计通过」；
- Nexus 关闭：检索按钮 disabled + 「Nexus 未启用」；检索失败：抽屉内降级条，**不阻断**手工提交；
- 原有必填校验不变（制品版本仍必填）。

### I6 制品检索抽屉 + 晋级确认 `[新增]`

- 抽屉 720，标题「从 Nexus 选择制品」；顶部：仓库 select（默认 `repo`）+ 搜索框（300ms 防抖）+ 搜索。
- 表格 8 行可视：名称 / 版本 / 打包 / 大小 / 上传时间 / sha256 前 12 / 选择；底部「共 N 条（最多返回 50）」；选中行高亮 + footer 摘要 + [确认选择]。
- 空态：`没有匹配的制品（试试放宽关键字或换仓库）`；错误：条内「Nexus 暂不可用（已降级）」+ [重试]，不关闭抽屉。
- 晋级弹窗（从制品卡打开）：目标仓库（`prodRepo` 只读）、源版本 → 目标版本、sha256 期望/实际前 12 位、校验中骨架；一致 → [确认晋级]（二次确认 + 审计说明）；不一致 → 红条 `CHECKSUM_MISMATCH` + 按钮 disabled；已晋级 → 「已于 11-09 晋级至 PROD」+ 记录链接（幂等）。

### I7 版本详情 › 集成设置卡 `[修订]`

- 位置：概览 tab 侧栏最后一张卡（宽 320，高约 180）。
- 两个控件：`MR 合入自动推进需求` switch（默认关，说明「关闭时 merged 后需人工确认」）；`默认 Sonar project key` 输入（需求未填时继承）。
- [保存]；无权限只读；保存成功只 toast，不重排页面。

### I8 追溯 `[修订]`

- 节点新增 `BUILD`（构建）与 `SONAR_GATE`（Sonar 门禁），200×64：图标 + 名称 + 状态 pill + 一行证据（`#1284 SUCCESS · 11-09` / `OK · 覆盖率 82.4%`）+ 时间。
- 无快照：灰节点 + 「无快照」，**不计入覆盖**；点击构建节点跳发布单构建卡锚点，Sonar 节点开快照抽屉（560）。
- 图例补两项；既有节点与筛选不变。

### I9 「如何接入」抽屉 `[新增]`

- 从每张配置卡的「如何接入 ›」打开，宽 640，按系统折叠（默认展开当前系统）。
- 每系统：字段表（字段/示例/必填）、凭据与最小权限（Jenkins API Token + Job/Build；GitLab bot `read_api`；Sonar user token + Browse；Nexus 只读 + 晋级）、回调地址 + secret 设置步骤（带复制）、网络白名单、验证顺序（测试连接 → 开启 → 触发）。
- 底部「桩与真实差异」表（≥6 行）：鉴权方式 / 分页 / 时间格式 / webhook 签名 / 速率限制 / 错误码 / 字段大小写；顶部黄条「桩通过 ≠ 真实系统已联调」。

---

## 6 组件规范

### 6.1 `IntegrationConfigCard`（新，`components/integration/IntegrationConfigCard.vue`）

props：`type`（JENKINS|GITLAB|SONAR|NEXUS）、`config`（含 `credentialConfigured/last4`、`webhookConfigured/last4`、`extra`）、`readonly`；emits：`save`、`toggle`、`test`、`credential-replace`、`credential-clear`、`open-help`。
内部状态：`dirty`（[保存] 仅 dirty 可点）、`testing`（≤6s 超时）、`savedAt`。保存成功用返回体覆盖本地，不整页 reload。

### 6.2 `CredentialField`（新）

- 未配置：`type=password` + `autocomplete=new-password` + 「保存后不可查看」提示；空串保存 **不**清空。
- 已配置：只读行 + 末四位 chip + [更换]（展开密码输入，保存后收起）+ [清除]（二次确认，要求输入系统名确认，如 `JENKINS`）。
- 任何情况下不渲染 `value`、不提供复制、不在 DOM 上挂 `data-*` 存凭据。

### 6.3 `AdmissionChecklist`（`GateChecklist.vue` 修订，保留 `mode=gate`）

props 增量：`status: 'PASS'|'FAIL'|'SKIP'|'CLOSED'`（`passed` 兼容保留，`status` 优先）、`skipReason`、`errorCode`、`evidence`（链接/抽屉）、`locked`。
行为：计数条 `通过 x · 失败 y · 跳过 z · 关闭 w`（分母 9 来自 `items.length`）；`aria-live="polite"`；CLOSED 不可展开；FAIL 默认色红但**不整行铺红**；SKIP 黄；结论文案只在组件外由结论条给，组件内不再写「全部通过」。

### 6.4 `BuildStatusCard`（新）

五态 pill + 阶段 chip（compile / unit-test，各 88×24）+ 最近 5 条列表（行 44）+ [触发构建]。
- 关闭/未配置：按钮 disabled + 原因行（`Jenkins 未启用（管理端 › 集成配置）` / `未配置 Jenkins`）。
- 409 `BUILD_IN_PROGRESS`：卡内黄条「已有构建 #1284 正在进行」+ 「查看构建」；不新增行。
- 回写：每 10s 轮询（页面可见时），`RUNNING` 有呼吸点；卸载时清定时器；终态停止轮询。
- 时长格式 `1m 42s`；未知 `—`；终态补 `console_url` 外链（`target=_blank rel=noopener`，域名不合规时置灰）。

### 6.5 `ArtifactSummaryCard` + `ArtifactPickerDrawer` + `PromoteDialog`（新）

- SummaryCard 见 I3；`sha256` 复制与「查看完整值」；来源 pill（NEXUS/MANUAL）。
- PickerDrawer 见 I6；只读接口失败不阻断手工输入。
- PromoteDialog：三步内联（校验 → 确认 → 结果）；`CHECKSUM_MISMATCH` 常驻；幂等返回已有记录时显示「已晋级」。

### 6.6 `CodeCard` / `SonarGateCard` / `SonarSnapshotDrawer`（新）

- CodeCard：见 I4；`approvals` 用 `已批准 n/m`，名单 tooltip；事件乱序时显示「已忽略更早事件（OUT_OF_ORDER）」灰字。
- SonarGateCard：结论、五度量、`fetched_at`、hash、刷新、绑定；失败态必须出现 `ADAPTER_UNAVAILABLE / SNAPSHOT_MISSING` 用户可读文案 + 日志链接。
- SnapshotDrawer：560；只读；保留历史回放（`source=SONAR` 才展示 Sonar 维度，`QUALITY_GATE` 仍走 M6 报告抽屉），不混淆第 6 项。

### 6.7 `SyncLogTable`（新）

聚合父行 + 子行；结果四态 pill；摘要列截断 48 + tooltip；`retry_count` 列显示 `3/5`；`duration_ms` 右对齐 tabular；行操作「关联」仅在存在 `biz_type/biz_id` 时渲染。

### 6.8 `HelpDrawer`（新）

见 I9；表格行 44，`th` 12/600；代码/字段名 mono 12；复制按钮与 chip 一致；底部差异表固定 6 列，窄屏横向滚动仅限表格内部。

### 6.9 状态词典增量（`stores/dict.ts`）

| kind | 值 | 文案 | 色 |
| --- | --- | --- | --- |
| `integration` | ENABLED / DISABLED / UNCONFIGURED | 已启用 / 已关闭 / 未配置 | success / warning / info |
| `build` | QUEUED / RUNNING / SUCCESS / FAILURE / ABORTED | 排队中 / 运行中 / 成功 / 失败 / 已中止 | info / primary / success / danger / info |
| `mr` | OPENED / MERGED / CLOSED | 打开 / 已合入 / 已关闭 | info / success / info |
| `sync` | SUCCESS / FAILED / IGNORED / RETRY_EXHAUSTED | 成功 / 失败 / 已忽略 / 重试耗尽 | success / danger / info / warning |
| `admission` | PASS / FAIL / SKIP / CLOSED | 通过 / 失败 / 跳过 / 已关闭 | success / danger / warning / info |
| `sonar` | OK / ERROR / UNBOUND / UNAVAILABLE | 通过 / 未达标 / 未绑定 / 不可用 | success / danger / info / danger |
| `artifactSource` | NEXUS / MANUAL | Nexus / 手工 | info / warning |

### 6.10 通知与告警 `[沿用通知中心]`

`RETRY_EXHAUSTED` 产生站内通知：标题「Jenkins 同步失败（已重试 5 次）」，正文含操作与 correlationId，动作跳 `/admin/integrations?tab=logs&correlationId=…`；kind `integration`；不发送邮件（本轮无邮件通道）。

---

## 7 视觉令牌

### 7.1 复用 `[沿用 tokens.css]`

主色 `#2B6FE5`、页面底 `#F5F7FA`、卡 `#FFFFFF`、边框 `#E5E6EB`、语义四组、`--r-card 8px`、`--r-ctl 6px`、`--t-color/--t-expand/--t-overlay`、`--ease`、`--focus-ring`、`--w-sidebar 200`、`--h-topnav 48`、`--text-muted-aa #6B7280`。

### 7.2 本轮新增令牌（只增不改）

```css
/* M7 集成域 · DESIGN §7.2 */
--w-intg-card: 424px;        /* 集成卡宽（2 列 @864 内容宽） */
--h-intg-card: 452px;
--w-admission-pill: 64px;    /* 四态 pill 同宽，防跳动 */
--h-admission-row: 48px;
--h-admission-row-open: 96px;
--h-count-strip: 40px;
--w-drawer-artifact: 720px;
--w-drawer-help: 640px;
--w-drawer-config: 560px;
--h-build-row: 44px;
--w-sha-chip: 160px;
--w-buildid-chip: 88px;
--intg-unconfigured-bg: #fafbfc;
--intg-unconfigured-border: #c9cdd4;   /* 仅虚线描边 */
--intg-code-bg: #f7f8fa;
--intg-running: #2b6fe5;
```

### 7.3 语义映射（唯一）

| 语义 | 色 | 用在哪 |
| --- | --- | --- |
| 已启用 / 通过 / 已同步 / 校验一致 | `--color-success` + `--color-success-bg` | 状态 pill、结论条、校验行 |
| 失败 / 不一致 / 阻断提交 | `--color-danger` + `--color-danger-bg` | FAIL 行、`CHECKSUM_MISMATCH`、409 |
| 跳过 / 已关闭 / 手工制品 / 待人工推进 | `--color-warning` + `--color-warning-bg` | SKIP、CLOSED switch 提示、MANUAL、merged 确认条 |
| 未配置 / 未绑定 / 忽略 / 无数据 | `--color-info` + `--color-info-bg` | 未配置卡、UNBOUND、IGNORED、`—` |
| 运行中 | `--color-primary` | RUNNING pill、呼吸点、进度条 |

> 只用 `var(--…)`，禁止在页面里写死十六进制；新令牌写进 `tokens.css` 注释段 `M7 集成域`。

---

## 8 无障碍要点

1. **不靠颜色**：状态 pill 一律「文字 + 图标形状」（✓ / ✕ / — / 锁），跳过与通过的颜色不同且文字不同；日志结果列同。
2. **switch**：`role="switch"` + `aria-checked` + 可见 label 关联；必开项 `aria-disabled="true"` + `aria-describedby` 说明「必开项」。
3. **准入清单**：`<ul role="list">`，每行 `li` 内状态文字可读；计数条 `aria-live="polite"`；展开按钮 `aria-expanded` / `aria-controls`；跳过的完整原因放在可见文本（单行省略时 `title` + 详情区全文）。
4. **凭据**：`autocomplete="new-password"`、`aria-describedby` 提示「保存后不可查看」；清除确认框焦点落输入框；不把凭据放进 `aria-label`。
5. **构建状态更新**：状态区 `aria-live="polite"`，更新不抢焦点；`console_url` 外链 `aria-label="在新窗口打开构建日志"`；禁用按钮用 `aria-describedby` 指向原因行（disabled 按钮无 tooltip 依赖）。
6. **抽屉/弹窗**：焦点陷阱，Esc 关闭，关闭后焦点回到触发按钮；标题 `aria-labelledby`。
7. **键盘**：卡整卡可点时内部有真实 `button`（整卡点击只作便利），Tab 顺序为 头 → 字段 → footer 动作；日志聚合行用 `button` 展开。
8. **对比度**：12px 文本用 `#6B7280`（≥4.5:1）；`#A9AEB8` 只用于 11px code 等非关键装饰；黄底上的文字用 `--warning-ink #9A5B00`。
9. **动效**：`prefers-reduced-motion` 关闭呼吸点、shimmer、chevron 旋转；不做闪烁告警。
10. **表格**：`<caption class="sr-only">` 说明范围（如「同步日志，近 7 天」）；排序/筛选结果变化用 `aria-live` 报条数。

---

## 9 实现映射与性能

### 9.1 路由与导航

- 新增 `web/src/router/routes/integration.routes.ts`（glob 自动挂载）：
  - `/admin/integrations` → `admin-integrations`，`AdminIntegrationsView.vue`，`meta.functionCode='integration:manage'`
- `layouts/nav.ts`：管理组新增 `{ label: '集成配置', to: '/admin/integrations', icon: 'plug', routeName: 'admin-integrations', functionCode: 'integration:manage' }`；顶栏 8 项不变。
- `AdminLayout.vue` 子菜单新增一项（同上 codes/hint）。`NAV_ICONS` 增 `plug`。
- 需求详情 `TAB_KEYS` 增 `integration`；发布单详情、需求详情、追溯、版本详情均为既有路由内改造，不新增页面。

### 9.2 组件与文件

| 文件 | 动作 |
| --- | --- |
| `views/admin/AdminIntegrationsView.vue` | 新（三 tab 容器） |
| `components/integration/IntegrationConfigCard.vue` `CredentialField.vue` `ConnectionTestBar.vue` `AdmissionItemTable.vue` | 新 |
| `components/integration/SyncLogTable.vue` `HelpDrawer.vue` | 新 |
| `components/integration/BuildStatusCard.vue` `ArtifactSummaryCard.vue` `ArtifactPickerDrawer.vue` `PromoteDialog.vue` | 新 |
| `components/req/CodeCard.vue` `SonarGateCard.vue` `SonarSnapshotDrawer.vue` | 新 |
| `components/GateChecklist.vue` | 修订（四态 + 计数条，`mode=gate` 不变） |
| `views/deploy/DeployOrderDetailView.vue` `DeployOrderListView.vue` | 修订 |
| `views/req/RequirementDetailView.vue` `tabs/OverviewTab.vue`（可选摘要） | 修订 |
| `views/release/ReleaseDetailView.vue` | 修订（集成设置卡） |
| `views/traceability/TraceabilityView.vue` | 修订（两节点） |
| `stores/dict.ts` `styles/tokens.css` | 增量 |
| `api/integration.ts`（新建） | 配置/日志/构建/制品/Sonar 接口封装 |

### 9.3 性能与状态

- **准入不阻塞**：发布单详情并发发起 `detail`、`admission`、`builds`、`artifact` 四个本地请求（`Promise.allSettled`），各自骨架独立；任一失败只降级对应卡；准入 GET 单请求 ≤500ms（AC-8.6），页面不串行外呼。
- **轮询**：构建 10s、Sonar 30min 由服务端负责；前端只在页面可见时轮询构建状态，终态停止；`document.hidden` 时暂停。
- **日志页**：默认 20 条/页，自动刷新默认关；开启后保留滚动位置。
- **长文本**：MR 标题 2 行 clamp，摘要 48 字符截断，准入详情 1 行省略 + 详情区全文；禁止撑破布局。
- **导出/打印**：本轮不新增导出；浏览器打印沿用 M6 规则（隐藏侧栏与按钮，不打印抽屉）。

---

## 10 AC 映射与自检

| AC | 设计落点 | 可复核点 |
| --- | --- | --- |
| AC-1.1/1.2 | I1 四卡；CredentialField | 四卡存在；凭据只显示末四位；空串不清空；清除需二次确认 |
| AC-1.4 | I1 关闭黄条；I3 按钮 disabled + 原因；准入 SKIP(NOT_CONFIGURED) | 无 500、无转圈；原因含「管理端 › 集成配置」 |
| AC-1.5 | I1 base_url 就地 400；测试连接 200 + ok=false | 不写库、不改已保存配置 |
| AC-2.1/2.2 | I2 日志表：correlation 聚合、retry_count、四态 | 父行「n 次尝试 · 总耗时」；`RETRY_EXHAUSTED` 展开 |
| AC-2.3 | 各卡降级条 | 需求/发布单主流程仍 200 渲染 |
| AC-2.6 | 日志「关联」列 | 跳构建卡/需求集成 tab |
| AC-3.1-3.5 | I3 构建卡、阶段 chip、超时黄条 | 关闭态 disabled + 原因；进行中 409 黄条；超时「超时未回写」 |
| AC-3.7 | 最近 5 条 + console 外链 | 域名不合规置灰「日志链接不可用」 |
| AC-4.1/4.2 | I4 代码卡、未结构化标 | 保存原 URL；结构化字段只读 |
| AC-5.1-5.4 | I4 merged 条；I7 开关 | 默认 off；自动推进有信息条；409 不留假象 |
| AC-6.1-6.6 | I4 Sonar 卡、I8 追溯、快照抽屉 | 跳过原因具体；失败红条写「计为失败」；无快照不计覆盖 |
| AC-7.1-7.5 | I5/I6 检索、sha、晋级、白名单 | MANUAL 黄标 + 第 9 项 SKIP；`CHECKSUM_MISMATCH` 常驻；`URL_NOT_ALLOWED` 不重试 |
| AC-8.1-8.7 | I3 准入 9 项、结论条、计数条、提交确认 | 无「6 项」；SKIP 不计 PASS；分母 9；FAIL 阻断；stale 黄条；第 6 项仍质门 |
| AC-9 | 不涉及页面；配置页脚注「本地桩模式」 | 不新增可视化假数据 |
| AC-10 | 凭据/secret 只写不读；日志摘要脱敏 | DOM/接口/导出 0 命中 |
| AC-11 | I9 接入抽屉 | 4 系统最小权限 + 差异表 ≥6 行 |

**自检**：① 9 项全部有状态与原因；② 跳过/关闭/失败三者在颜色与文字上都不同；③ 每个禁用按钮都有可见原因；④ 每个外部失败都有降级条与重试；⑤ 配置页无 JSON 文本框与明文凭据；⑥ 页面首屏不触发任何外部请求。

---

## 11 设计裁决（不再回问，按此实施）

1. **准入按 9 项**（6 + 构建 + Sonar + 制品），PRD §1 口径为准；第 6 项语义与 legacy 渲染不动。
2. **需求侧用第 8 个 tab「集成」**承载代码卡 + Sonar 卡 + 同步记录，而不是把三张卡塞进概览侧栏；概览不加新卡，避免首屏过长。
3. **构建卡放发布单详情侧列 320**（触发高频、状态短），制品卡放主列（含 sha/晋级，需要宽度）；二者都带自己的系统状态，不另设「集成状态」卡。
4. **准入项开关与同步日志都放 `/admin/integrations` 的 tab**，不新增第二个管理页；tab 由 query 控制，便于深链。
5. **`extra` 用具名字段而非 JSON 文本域**，`prodRepo` 未配置时晋级禁用（PRD §10 默认值）。
6. **手工制品允许通过表单创建，但第 9 项 SKIP + 页面黄标**（PRD 假设第 4 条默认值）。
7. **失败一律 fail-closed 且页面明说**：已配置但取数失败 = FAIL；只有未配置/关闭才 SKIP；SKIP 永不显示为通过。
8. **不做暗色主题、不做集成大屏、不做 iframe 嵌入**；控制台/日志一律外链。
