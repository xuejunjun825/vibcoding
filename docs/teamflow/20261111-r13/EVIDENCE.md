# r13 交付证据（T10 · 分析域四页 portal / dashboard / reports / editor）

- 分支：`feat/analytics-portal-views`（本轮不提交，收尾提交由 host 按 §11.1 白名单执行）
- 环境：网关 `http://127.0.0.1:8080`、前端 `http://127.0.0.1:5173`（`web/dist` 预览）、Chrome CDP `127.0.0.1:9222`、viewport 1440×900
- 生成脚本：`logs/teamflow/tf-mwc1mure-iyojpx/scripts/shots.mjs`（截图）、`capture-contract.sh`（契约）、`editor-limits-probe.mjs`、`dict-forbidden-probe.mjs`
- 截图明细台账：`logs/teamflow/tf-mwc1mure-iyojpx/captures.json`（每张图的 DOM 事实 + 控制台错误数）

## 1. 门禁结果

| 检查 | 命令 | 结果 |
|---|---|---|
| 类型 | `cd web && npx vue-tsc --noEmit` | 0 错误 |
| 构建 | `cd web && npm run build` | exit 0（含 `ChartView-*.js`、`ReportEditorView-*.js` 分包） |
| 代码规范 | `bash scripts/tests/lint.sh` | `ok lint 全部通过（16 项）` |
| 访问门禁 | `bash scripts/access-verify.sh` | `a0 29/29 a1 8/8 a2 63/63 a3 9/9 a4 14/14 a5 22/22` → `145/145（下限 143）` PASS |
| 契约字段 | `bash logs/teamflow/tf-mwc1mure-iyojpx/scripts/capture-contract.sh` | `ok contract-capture: 28/28`，六份响应落 `evidence/*.json` |
| 编辑器限额 | `node logs/teamflow/tf-mwc1mure-iyojpx/scripts/editor-limits-probe.mjs` | 4 维时图表 4/4 禁用且给原因；第 4 个行字段只提示；run 请求 0；控制台错误 0 |
| 字典 403 | `node logs/teamflow/tf-mwc1mure-iyojpx/scripts/dict-forbidden-probe.mjs` | 拦截 3 个字典请求全 403；降级提示出现；URL 仍 `/dashboard`；控制台错误 0 |
| 筛选防抖 | `node logs/teamflow/tf-mwc1mure-iyojpx/scripts/filter-debounce-probe.mjs` | 连续 3 次快改时间窗只发 **1** 次 `/analytics/dashboard` 请求（AC-7 要求 ≤2）；URL `preset=30d`；控制台错误 0 |
| 截图 | `node logs/teamflow/tf-mwc1mure-iyojpx/scripts/shots.mjs` | 20/20 PNG，1440×900，**每张图控制台错误 0** |

## 2. 契约证据（wire 字段名逐字对齐）

`capture-contract.sh` 用 admin 会话拉取六份真实响应，写进 `docs/teamflow/20261111-r13/evidence/`，并用 jq 断言 28 条：

- `portal.json`：`templateLabel` / `todoMode` / `sections[].state` / `todoSources` / `dataAsOf` / `cache` 全部存在；区顺序与页面渲染顺序一致。
- `dashboard.json`：`breakdowns[].label|colHeaders|subtotal|total`、`trend.points[].bucket`（旧字段 `period` 已不存在）、`trend{unit,points}`、`segments[].avg|p90|tail`、`effort{estimate,actual,deviation,missingEstimate,unit}`、`vendorRank[].rank|completed|avgCycle|mine`（旧 `done`/`avgCycleDays` 不存在）、`quality.cards[].level∈{GOOD,WARN,BAD,NONE}|sample|digits`（旧 `items` 不存在）。
- `datasets.json`：≥4 个数据集，`fields[].key|kind|measure` 齐全（`requirement` 17 个字段）。
- `run.json`：`rowHeaders` / `colHeaders` / `rows` / `groups`，无 `colGroups`。
- `portal-config.json`：`roleCode=admin`、`maxCards=4`、`cardKeys<=4`。
- `reports.json`：`ownerName` / `scope` / `updatedAt` 存在，`scope` 五档。

## 3. 20 张截图（页面 / 账号 / URL / 期望 / 实际）

> 所有截图的「控制台错误」= 0（`captures.json` 的 `shots[].consoleErrors` 全为空数组）。「实际」列取自截屏瞬间的 DOM 事实，不是事后补写。

| # | 文件 | 页面 | 账号 | URL | 期望 | 实际 |
|---|---|---|---|---|---|---|
| 1 | `portal-admin.png` | 门户 | admin | `/portal` | 五区顺序 = 后端数组；模板名「平台管理工作台」；指标区有 dataAsOf + HIT/MISS | 区序列 `todo,actions,metrics,list,messages` 全 `OK`；`templateLabel=平台管理工作台`；指标区「数据截至 2026-11-12 15:19 HIT」 |
| 2 | `dashboard-admin.png` | 数据看板 | admin | `/dashboard` | 五块固定顺序 + 标题；canvas 存在；口径戳 | `breakdown,cycle,effort,vendor,quality`；标题「需求量多维/周期与 P90/人天投入/厂商交付排名/交付质量」；1 个 canvas；HIT |
| 3 | `reports-admin.png` | 报表中心 | admin | `/reports` | 两段 Tab + 六列 + 操作按属主/权限 | 表头 `名称,数据集,共享范围,更新时间,属主,操作`；20 行；Tab「我的报表/共享给我」；操作 打开/复制/共享/删除 |
| 4 | `editor-admin.png` | 编辑器 | admin | `/reports/new?dataset=requirement` | 三栏；四槽；未保存预览禁用运行 | 四槽 `rows,columns,measures,filters`；字段池 17 个；预览「先保存以生成报表」；图表五档（表格/柱状图/折线图/饼图/热力（本地）） |
| 5 | `portal-pm.png` | 门户 | pm | `/portal` | 产品工作台模板，五区 | `todo,actions,metrics,list,messages` 全 OK；`templateLabel=产品工作台`；HIT |
| 6 | `dashboard-pm.png` | 数据看板 | pm | `/dashboard` | 同上五块 | 五块顺序与标题一致；canvas 1；HIT |
| 7 | `reports-pm.png` | 报表中心 | pm | `/reports` | 六列 + 仅属主可见的共享/删除 | 六列表头；11 行；操作按属主判定 |
| 8 | `editor-pm.png` | 编辑器 | pm | `/reports/new?dataset=requirement` | 三栏 + 未保存提示 | 四槽齐备；17 字段；「先保存以生成报表」 |
| 9 | `portal-vendor1.png` | 门户 | vendor1 | `/portal` | 厂商工作台，五区 | 五区全 OK；`templateLabel=厂商工作台`；HIT |
| 10 | `dashboard-vendor1.png` | 数据看板 | vendor1 | `/dashboard` | 厂商视角五块（mine 高亮） | 五块顺序一致；厂商行 `mine` 高亮；canvas 1 |
| 11 | `reports-vendor1.png` | 报表中心 | vendor1 | `/reports` | 我的报表为空 → 空态（不是错误） | 0 行，空态「还没有报表」+「新建报表」 |
| 12 | `editor-vendor1.png` | 编辑器 | vendor1 | `/reports/new?dataset=requirement` | 厂商可建报表（字段池不因角色为空） | 四槽齐备；17 字段；未保存预览提示 |
| 13 | `portal-po1.png` | 门户 | po1 | `/portal` | 子业务工作台 | 五区全 OK；`templateLabel=子业务工作台`；HIT |
| 14 | `dashboard-po1.png` | 数据看板 | po1 | `/dashboard` | 五块 | 五块顺序一致；canvas 1；HIT |
| 15 | `reports-po1.png` | 报表中心 | po1 | `/reports` | 六列 | 六列表头；12 行（含 r11 共享样例） |
| 16 | `editor-po1.png` | 编辑器 | po1 | `/reports/new?dataset=requirement` | 三栏 | 四槽齐备；17 字段 |
| 17 | `portal-mgmt.png` | 门户（只读） | mgr1 | `/portal` | 只读 pill；**隐藏快捷入口区**；其余四区照常 | 区序列 `todo,metrics,list,messages`；`readonly=true`；`hasActions=false`；`templateLabel=管理驾驶舱` |
| 18 | `portal-degraded.png` | 门户（单区降级） | admin | `/portal` | 待办区 `DEGRADED` + 区内原因与重试；其余四区 `OK` | `todo:DEGRADED`，`actions/metrics/list/messages:OK`；区内「本区数据读取失败，其他区域不受影响」+「重试」；页面不白屏 |
| 19 | `editor-409.png` | 编辑器（pin 上限） | admin | `/reports/228` | 第 5 张 pin → 409 `PORTAL_CARD_LIMIT`，弹层只列当前 4 张 | 弹层标题「工作台卡片已满」；`.pin__item`=4（r11-ev-pin1..4）；「工作台最多 4 张卡片，本次未添加第 5 张」；「本轮暂不支持移除，下轮支持移除。」；「去工作台移除」→`/portal`；预览 6 行 × 2 列（含小计/总计） |
| 20 | `reports-empty.png` | 报表中心（关键词空态） | admin | `/reports?keyword=r13-no-such-report` | 关键词无果 →「清空筛选」 | 0 行；「没有匹配的报表」+「清空筛选」；共 0 张 |

降级图的做法：`Fetch.enable`（Response 阶段）匹配 `*api/deliver/portal*`，只把待办区改写成 `state=DEGRADED` 并加 `error`，其余区原样返回——所以它证明的是页面自己的分区状态机，而不是拼图。

## 4. 负向六项结论

| # | 场景 | 结论 | 证据 |
|---|---|---|---|
| ① | 单区降级 | 待办区失败只影响本区，其余四区照常渲染，区内给原因 + 重试 | `portal-degraded.png`；`captures.json` 中 `todo:DEGRADED` 且另外四区 `:OK` |
| ② | pin 第 5 张 409 | 弹层由 `cardLimitCards(error)` 取 `detail.cards`，只列 4 张可打开；文案与「去工作台移除」齐全；不出现第 5 张 | `editor-409.png`；断言 `.pin__item === 4` |
| ③ | 他人 PRIVATE 报表 | 非属主读私有报表 → `REPORT_NOT_FOUND`，页面显示「报表不存在」，不泄露内容 | `pm GET /reports/228 → 404 {"code":"REPORT_NOT_FOUND"}`；页面侧 `/reports/999999 → 报表不存在=true` |
| ④ | 字典 403 | 下拉降级为「字典不可用，相关筛选已降级（不影响数据读取）」，页面不跳转、控制台无错误 | `dict-forbidden-probe.mjs`（拦截 3 个请求全 403，URL 仍 `/dashboard`，筛选控件仍在） |
| ⑤ | 3+ 维选图表 | 超过 2 维时除「表格」外全部禁用，禁用原因写明「4 个维度无法选择图表类型，请减少到 2 个以内」，切图不发请求 | `editor-limits-probe.mjs` 输出 `chart-buttons` 四档 disabled + reason |
| ⑥ | 行第 4 个字段超限 | 纯前端拦截：「行最多 3 个（当前已满）」，槽位仍 3/3，**不发 run 请求** | `editor-limits-probe.mjs`（`run 请求 0`） |

另：`/reports/abc` 命中路由 404（`{"path":"/reports/abc","notFound":true}`）；同名保存 → 行内 409「同名报表已存在」，URL 保持 `/reports/228`（`captures.json.negative.duplicateName`）。

并发口径（AC-7）：`useAnalyticsFeed` 单点实现「300ms 防抖 + AbortController 丢弃旧请求 + seq 丢弃过期响应」。探针实测 3 次快改只落 1 次请求；`/portal`、`/dashboard`、编辑器三处取数都走这一层，视图内没有第二套 fetch。

## 5. 视觉口径（DESIGN / AC-16）

- 主色 `#2B6FE5`，白卡 1px `#E5E6EB`、8px 圆角、无阴影；表格行高 44px、正文 13px；表头与首列 sticky（宽表在容器内滚动，`.an-table__scroll`）。
- 筛选条统一为一行 `FilterBar`（业务域 / 厂商 / 时间窗 / 需求类型按页面传 `fields`）；`/portal` 无需求类型，`/dashboard` 有。
- 空值一律「—」（`formatNumber`），不补 0；空态统一 `EmptyState`（`.empty__title`），错误统一 `ErrorState`；指标区右上 `DataStamp`（`YYYY-MM-DD HH:mm` + HIT/MISS）。
- 图表为 echarts 按需注册（Bar/Line/Pie/Heatmap + Grid/Tooltip/Legend/VisualMap + Canvas），无新依赖。

## 6. 遗留（只记录，不改后端）

- G-1 无 unpin 接口：pin 上限弹层只能「去工作台移除」，文案如实写「下轮支持移除」。
- G-2 无角色字典端点：`components/analytics/portalRoles.ts` 的 10 个角色 code 镜像后端 `PortalTemplates.ROLE_ORDER`（重复点已登记）。
- G-3/G-8 后端 `chartType` 白名单只有 `TABLE|BAR|LINE|PIE`：堆叠柱 = `BAR+stacked`；热力为本地视图不落库。
- G-4 列表无 `canWrite/canShare`：用 `ownerId` + `REPORT_SHARE` 权限码预判操作可见性。
- G-5 范围筛选不随报表保存：编辑器顶部 chips 仅作用本次运行。
- G-6 导出文件名后端兜底 `report-<id>-<yyyyMMdd>.<ext>`；G-7 缓存态读 body 的 `cache` 字段而非响应头。

## 7. 复现

```bash
cd web && npx vue-tsc --noEmit && npm run build
cd .. && bash scripts/tests/lint.sh && bash scripts/access-verify.sh
bash logs/teamflow/tf-mwc1mure-iyojpx/scripts/capture-contract.sh
node logs/teamflow/tf-mwc1mure-iyojpx/scripts/editor-limits-probe.mjs
node logs/teamflow/tf-mwc1mure-iyojpx/scripts/dict-forbidden-probe.mjs
node logs/teamflow/tf-mwc1mure-iyojpx/scripts/shots.mjs   # 20 张 → docs/teamflow/20261111-r13/evidence/
```
