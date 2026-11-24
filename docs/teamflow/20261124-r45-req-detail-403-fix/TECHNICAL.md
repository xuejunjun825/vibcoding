# 技术方案 · 需求详情页非审批参与人 403 误跳修复（r45）

基线：`docs/teamflow/20261124-r44-null-safe-enums`（main=`e842192`）· 分支 `feat/detail-approval-silent` · runId `tf-mwues0su-fpi3ea`
PRD：[PRD.md](PRD.md)（15 条 AC）· 任务夹：`docs/teamflow/20261124-r45-req-detail-403-fix/`

## 0. 架构判断（一句话）

**不新增模块、不改依赖方向、服务端零 diff**：把"审批实例详情"这条请求从「主数据准入」降级为「页内可选探测」——请求层透传已存在的 `silentForbidden`，页面把 403 收敛成一个**布尔**并交给审批页签做内联降级；同一类残留用 lint L23（静态、可自检变红）+ access a8（13 条）双重钉死。

三层责任划分（改动只落在第 2、3 层）：

| 层 | 位置 | 本轮动作 |
|---|---|---|
| 服务端可见性 | `req-service/.../ApprovalCenterService.java:151-163` `visibleInstance()` | **零改动**（发起人/审批人/抄送人/管理员，否则 403 `无权查看该审批实例`） |
| 请求层 | `web/src/api/client.ts:174-186`、`web/src/api/req.ts:213-222` | 透传 `silentForbidden`（默认 false）+ 约定注释升级 |
| 页面层 | `web/src/views/req/RequirementDetailView.vue:292-313 / 345-360`、`web/src/views/req/tabs/ApprovalTab.vue` | 3 处调用 + 待办探测静默；403 → 页签内逐字内联文案 |

## 1. 现状事实（勘察结论，带行号）

- `web/src/api/client.ts:174-186`：`request()` 对 `status === 401` 先 `clearSession()` 再按 `silentAuth` 决定跳登录；对 `status === 403` 在 `!options.silentForbidden` 时 `navigate('/403?...')` **然后才 `throw error`**——页面即使 catch 住，URL 已经被替换。这是"整页被踢"的机制来源。
- `web/src/api/client.ts:18-28`：`RequestOptions.silentForbidden` 已存在，但注释只有一句"403 不跳 /403，交回调用方（用于"可选能力探测"）"，没有写"哪些必须声明、哪些禁止声明"。
- `web/src/api/req.ts:213-222`：`listApprovalMine(params)`、`getApprovalInstance(id)` 都**不收** `RequestOptions`，没有声明点 → 详情页无法静默。对照：`listApprovalTodo(params, extra?)`（:197-203）已经是可选透传形态，`listRequirementChanges`（:160-167）已内建 `silentForbidden: true`。
- `web/src/views/req/RequirementDetailView.vue:292-313` `loadApproval()`：先取 `(detail as {approvalInstanceId?}).approvalInstanceId` 直取实例（:296-300），否则 `listApprovalMine({page:1,size:50})` 里按 `bizId===id && bizType.includes('REQ')` 找命中再取实例（:301-305）。共 **3 处** `getApprovalInstance`（:298 / :305 / :356）。
- `web/src/views/req/RequirementDetailView.vue:345-360` `loadEstimateApproval()`：按 `detail.estimate.instanceId` 直取（:356）——**这是真实触发路径**（WBS_ESTIMATE 的 bizType 不含 `REQ`，待办过滤永远匹配不到）。
- `web/src/views/req/RequirementDetailView.vue:595-600`：`watch(() => route.params.id, () => void refreshAll())`；`onMounted` 也是 `load()` 后并行两个 loader —— 切换需求/刷新都会重跑，**403 标记必须在每次 loader 开头复位**。
- `web/src/views/req/tabs/ApprovalTab.vue:368-372`：主审批区 `v-if=loading` → `v-else-if=error`（`ErrorState`「审批信息暂不可用」+ retry）→ `v-else-if=!instance`（`EmptyState`）；评估块 :357-372 同构。**403 一旦写进 `error`，就变成了"加载失败/请重试"的技术错误态**（AG-1）。
- 其它 `getApprovalInstance` 调用点：`web/src/views/approval/ApprovalCenterView.vue:278`、`web/src/views/req/RequirementChangeDetailView.vue:115`（均为参与人页面，**保留默认 403 跳转**）；`listApprovalMine` 另有 `ApprovalCenterView.vue:214`（默认行为不变）。
- 服务端错误原文：`ApprovalCenterService.java:160` `throw ApprovalException.forbidden("无权查看该审批实例")` → 403 + `code="FORBIDDEN"` + `message` 逐字；`ApprovalException.forbidden` 不带 detail。
- `approvalInstanceId` 全仓只出现在 `RequirementApprovalCallback.java:68` 的审计 detail，详情 DTO 无该字段 → 该 `direct` 分支是**疑似死路径**。门禁只钉"所有调用点静默"，**不依赖**该分支（PRD §7 风险行）。

## 2. 设计决策与取舍

**D1（关键）403 收敛成布尔，而不是把文案塞进 `error` 字符串。**
`approvalError` / `estimateError` 继续承载"真正的技术错误"（网络、500），403 走独立的 `approvalForbidden` / `estimateForbidden`。理由：① 文案只有一份（AC-2"只能出现 1 次"），不必在两处 catch 里各写一遍；② 403 与"加载失败"在数据层就分叉，`ErrorState`（带 retry）永远不会被 403 命中 → AG-1 结构上不可能发生；③ a8 S3 可以静态断言"字面量唯一 + 只在 forbidden 分支渲染"，比 grep 两处 catch 更硬。

**D2 文案常量落在 `ApprovalTab.vue`（唯一一处，页面内两处引用）。**
主审批区与评估块都在同一个组件里渲染，常量放在该组件的 `<script setup>` → 字面量全 `web/src` 恰好 1 次、同一常量供两处。**被拒**：新建 `web/src/views/req/approval.ts`（更"领域化"，但 AC-14 的 diff 白名单只含 `RequirementDetailView.vue` 与 `views/req/tabs/*`，新文件越界，不值得为一行常量冒红线风险）。

**D3 `getApprovalInstance(id, extra?: Partial<RequestOptions>)`，默认值不写死。**
与既有 `listApprovalTodo` 同形；`extra` 不传 → `http.get` 的 `extra` 为 `undefined` → `silentForbidden` falsy → **旧调用点行为逐字不变**（审批中心/变更详情仍跳 /403）。同理 `listApprovalMine(params, extra?)`。不接受"给 `getApprovalInstance` 加 `silentForbidden = true` 默认值"：那会把审批中心也变成静默，等于偷偷放宽参与人页面的越权表达。

**D4 服务端零 diff。** 不给 `visibleInstance()` 加 owner/assignee 例外、不改 403 code/message/detail、不新增只读接口（PRD §9-b 默认不新增，列 P2）。页面降级只影响"审批区显示什么"，不影响"能不能看审批内容"——**非参与人拿不到任何审批内容**（AG-2）。

**D5 门禁双钉且必须能变红。**
lint L23（静态、含负向自检）+ access a8（静态 4 + 运行时 6 + 负向 3）。只 grep 一个字符串的门禁视为无效：L23 的核心判据是**计数相等**（裸调用数 == 带 silent 数），a8 S2 独立再数一遍，两者任一被绕过另一条仍红（AG-4：手工抽掉一处 `silentForbidden: true`，两条必须同时变红）。

**D6 不改概览/页头。** 概览卡片在 403 时仍显示"还没有审批记录"（`approval` 为 null），本轮**不加**提示（PRD §9-b 默认"仅审批页签内"）；风险记为已知取舍，见 §7-R6。

**D7 不新增前端测试框架。** 本仓库 web 侧无单测运行器（`web/package.json` 只有 `dev/typecheck/build`），页面逻辑靠 `vue-tsc` + a8 静态/运行时断言 + 截图证据。禁止为这条需求引入 Vitest/Playwright 新依赖。

## 3. 数据模型与存储

**零改动**：无 DDL、无 Flyway 迁移、无实体/DTO 字段、无种子数据变更；`db/migration/**`、`req-service/src/main/java/**` diff 必须为空（AC-14 红线）。审批实例 403 响应形状（`{status,code,message,detail?}`）不变。

前端新增的**只有两个布尔**（页面级瞬时状态，不落盘）：

| 状态 | 类型 | 初值 | 复位点 | 消费点 |
|---|---|---|---|---|
| `approvalForbidden` | `ref<boolean>` | `false` | `loadApproval()` 开头 | `ApprovalTab` 的 `:forbidden` |
| `estimateForbidden` | `ref<boolean>` | `false` | `loadEstimateApproval()` 开头（含 `instanceId == null` 的早退分支） | `ApprovalTab` 的 `:estimate-forbidden` |

审批区的四态（互斥，模板按序判定）：

| `loading` | `forbidden` | `error` | `instance` | 渲染 |
|---|---|---|---|---|
| true | — | — | — | 骨架屏 |
| false | true | '' | null | `EmptyState`：标题「无权查看审批进度」+ 逐字说明（**无重试按钮、无 toast**） |
| false | false | 非空 | null | `ErrorState`「审批信息暂不可用」+ 原文（既有行为，保留 retry 能力不变） |
| false | false | '' | null | `EmptyState`（可提交时带「提交审批」按钮，既有行为） |
| false | false | '' | 非空 | 正常卡片 + 时间线 |

## 4. API 设计

不新增路由、不改返回结构；只给两个既有封装加**可选**请求参数。

`web/src/api/req.ts:213-222` 改为（逐字，dev 照抄）：

```ts
export function listApprovalMine(
  params: { page?: number; size?: number },
  extra?: Partial<RequestOptions>,
): Promise<PageResponse<ApprovalTodoRow>> {
  return http.get<PageResponse<ApprovalTodoRow>>(`${REQ}/approvals/mine`, params, extra)
}

/**
 * 审批实例详情。**页内可选探测**：详情页把它当辅助信息加载时，调用点必须传
 * `{ silentForbidden: true }`（约定见 client.ts；静态断言 lint L23、access a8 S2）。
 * 不传时行为不变：403 由 client.ts 统一跳 /403 —— 审批中心 / 变更详情依赖这一点。
 */
export function getApprovalInstance(
  id: number | string,
  extra?: Partial<RequestOptions>,
): Promise<ApprovalInstanceDetail> {
  return http.get<ApprovalInstanceDetail>(`${REQ}/approvals/instances/${id}`, undefined, extra)
}
```

约束：
- `RequestOptions` 已在 `web/src/api/req.ts:5` 以 `import type` 导入，不要重复引入。
- 第三参**必须是 `undefined`**（`http.get(url, params?, extra?)` 的第二参是查询参数），a8 S1 会断言 `instances/${id}`, `undefined, extra` 这一形态。
- 既有调用点不改：`ApprovalCenterView.vue:214`、`:278`、`RequirementChangeDetailView.vue:115`。

服务端契约（不变，作为负向断言的判据）：

| 场景 | HTTP | body |
|---|---|---|
| 参与人/管理员读实例 | 200 | `ApprovalInstanceDetail` |
| 同租户非参与人 | 403 | `{"status":403,"code":"FORBIDDEN","message":"无权查看该审批实例"}`，**不含** `instance`/`timeline`/`document` |
| 跨租户 | 403 | `code="TENANT_MISMATCH"`，message「审批实例不属于当前租户」 |
| 实例不存在 | 404 | 既有语义 |

## 5. 前端改造清单（4 个文件）

### 5.1 `web/src/api/client.ts`（仅注释，逻辑零改动）

`RequestOptions.silentForbidden` 的注释升级为约定（保留原句，追加）：

```ts
  /**
   * 403 不跳 /403，交回调用方（**仅限页内可选探测**）。
   * 约定（lint L23 / access a8 钉住）：页面内「读不到也不阻断」的探测——审批实例/评估审批、
   * 审批待办探测、变更计数、字典、集成配置、执行器台账——必须在**同一次 http 调用**上声明；
   * 主数据请求（如 getRequirement）**不得**声明：越权必须落到全局 /403，不能被静默吞成空壳。
   */
  silentForbidden?: boolean
```

锚点（a8 S4 断言）：同一段注释里必须同时出现 `lint L23`、`access a8` 与 `getRequirement`。

### 5.2 `web/src/views/req/RequirementDetailView.vue`

新增两个 ref（紧邻 `approvalError`，:81 之后）：

```ts
/** 403 = 无权看审批进度：页签内降级，绝不进 approvalError（那会渲染成"加载失败+重试"） */
const approvalForbidden = ref(false)
const estimateForbidden = ref(false)
```

`loadApproval()`（:292-313）改为 —— 注意**调用点必须单行**（L23 逐行计数）：

```ts
async function loadApproval(): Promise<void> {
  if (!detail.value) return
  approvalLoading.value = true
  approvalError.value = ''
  approvalForbidden.value = false
  try {
    const direct = (detail.value as unknown as { approvalInstanceId?: number }).approvalInstanceId
    if (direct != null) {
      approval.value = await getApprovalInstance(direct, { silentForbidden: true })
      return
    }
    const mine = await listApprovalMine({ page: 1, size: 50 }, { silentForbidden: true })
    const hit = mine.items.find(
      (row: ApprovalTodoRow) => Number(row.bizId) === id.value && String(row.bizType).toUpperCase().includes('REQ'),
    )
    approval.value = hit ? await getApprovalInstance(hit.instanceId, { silentForbidden: true }) : null
  } catch (error) {
    approval.value = null
    const status = (error as { status?: number }).status
    approvalForbidden.value = status === 403
    approvalError.value = approvalForbidden.value ? '' : messageOf(error)
  } finally {
    approvalLoading.value = false
  }
}
```

`loadEstimateApproval()`（:345-360）改为：早退分支里 `estimateForbidden.value = false`；try 前复位；`getApprovalInstance(instanceId, { silentForbidden: true })`；catch 同构（`estimateForbidden` / `estimateError`）。

模板（:778-793）加两个 prop：

```html
<ApprovalTab
  v-else-if="activeTab === 'approval'"
  :instance="approval"
  :loading="approvalLoading"
  :error="approvalError"
  :forbidden="approvalForbidden"
  :estimate-forbidden="estimateForbidden"
  ...
/>
```

不改：`getRequirement(id.value)`（:279，**禁止**加 silent，AC-7）、`listRequirementChanges`（已 silent）、字典（已 silent）、`actOnRequirement`（已 silent）、`allowedActions`/动作矩阵、概览卡片。

### 5.3 `web/src/views/req/tabs/ApprovalTab.vue`

常量（`<script setup>` 内，`auth` 附近）：

```ts
/** 403 的唯一文案（AC-2：全 web/src 只出现 1 次，主审批区与评估块共用） */
const FORBIDDEN_HINT = '无权查看审批进度（仅发起人/审批人/抄送人可见）'
```

props（`withDefaults` 内追加，默认 `false` 保证旧父级/测试不受影响）：

```ts
    forbidden?: boolean
    estimateForbidden?: boolean
```
```
    forbidden: false,
    estimateForbidden: false,
```

评估块（:357-372）分支顺序改为 —— 403 必须在 `estimateError` **之前**，且不落到 `ErrorState`：

```html
      <div v-if="estimateLoading" class="skeleton skeleton__row" />
      <p
        v-else-if="estimateForbidden"
        class="estimate-block__reason estimate-block__reason--warning"
        data-test="estimate-forbidden"
      >{{ FORBIDDEN_HINT }}</p>
      <ErrorState v-else-if="estimateError" compact title="工时评估单暂不可用" :message="estimateError" @retry="emit('retry-estimate')" />
```

主审批区（:368-372）在 `error` 之前插入 forbidden 分支：

```html
    <div v-if="loading" class="skeleton skeleton__row" />
    <EmptyState
      v-else-if="forbidden"
      title="无权查看审批进度"
      :description="FORBIDDEN_HINT"
      icon="lock"
      compact
      data-test="approval-forbidden"
    />
    <ErrorState v-else-if="error" compact title="审批信息暂不可用" :message="error" />
```

不改：`EmptyState`/`ErrorState` 组件本体、时间线、评估块其余渲染、加签/减签/转办对话框、样式（复用 `.estimate-block__reason--warning`，**不新增 CSS**）。

### 5.4 编译/类型约束

`props` 用 `withDefaults` + 可选字段，`ApprovalTab` 的既有 5 个必填 props 不动；`:estimate-forbidden` 是 kebab-case 对应 `estimateForbidden`。验收 `cd web && npm run typecheck && npm run build` 0 error（`vue-tsc` 会把模板里的 prop 名拼错直接判红，这是改名的第一道防线）。

## 6. 门禁设计

### 6.1 lint L23（`scripts/tests/lint.sh`，24 → 25）

新增两个函数 + 一条 `run_check`（放在 L22 之后，`run_check` 调用列表末尾）。**逐字**实现：

```bash
# ---- L23 详情页辅助探测必须 silentForbidden（r45）----
# 缺陷类（不是那一个点位）：详情页把「审批实例 / 评估审批 / 审批待办探测」当辅助信息加载，
# 却没在同一次 http 调用上声明 silentForbidden；client.ts 对任何 403 先 navigate('/403')
# 再抛错，于是非审批参与人（dev1/qa1/vendor1）一打开详情就被整页替换。
# 三个判据：
#   (a) getApprovalInstance( / listApprovalMine( 的调用点数 == 带 { silentForbidden: true } 的数量；
#   (b) 页面不得绕过 api 层裸调 http.get/http.post（否则 api 层的声明点被旁路）；
#   (c) 负向自检：同一套计数逻辑喂「含裸调用」的夹具必须判红——否则这条断言只会 grep 字符串。
# 约束：调用点必须写在同一行；纯注释行（// * /*）在计数前被剔除，注释里提到函数名不算调用。
_page_optional_probe_scan() {
    local f="$1" code call silent bad=0
    [[ -f "$f" ]] || { echo "文件不存在: $f"; return 1; }
    code="$(grep -vE '^[[:space:]]*(//|\*|/\*)' "$f" 2>/dev/null || true)"
    call="$(printf '%s\n' "$code" | grep -oE 'getApprovalInstance\(' | wc -l | tr -d ' ' || true)"
    silent="$(printf '%s\n' "$code" | grep -oE 'getApprovalInstance\([^)]*\{[[:space:]]*silentForbidden:[[:space:]]*true' | wc -l | tr -d ' ' || true)"
    if [[ "$call" != "$silent" ]]; then
        echo "getApprovalInstance 裸调用：调用 ${call} 处，带 silentForbidden 仅 ${silent} 处"
        printf '%s\n' "$code" | grep -nE 'getApprovalInstance\(' | sed 's/^/    /'
        bad=1
    fi
    call="$(printf '%s\n' "$code" | grep -oE 'listApprovalMine\(' | wc -l | tr -d ' ' || true)"
    silent="$(printf '%s\n' "$code" | grep -oE 'listApprovalMine\([^)]*\{[[:space:]]*silentForbidden:[[:space:]]*true' | wc -l | tr -d ' ' || true)"
    if [[ "$call" != "$silent" ]]; then
        echo "listApprovalMine 裸调用：调用 ${call} 处，带 silentForbidden 仅 ${silent} 处"
        printf '%s\n' "$code" | grep -nE 'listApprovalMine\(' | sed 's/^/    /'
        bad=1
    fi
    if printf '%s\n' "$code" | grep -qE '(^|[^.[:alnum:]_])http\.(get|post|put|patch|del)(<|\()'; then
        echo "详情页出现裸 http 调用（必须走 api 层，否则 silentForbidden 无法统一声明）："
        printf '%s\n' "$code" | grep -nE '(^|[^.[:alnum:]_])http\.(get|post|put|patch|del)(<|\()' | sed 's/^/    /'
        bad=1
    fi
    return "$bad"
}

detail_optional_probes_are_silent() {
    local view="$ROOT/web/src/views/req/RequirementDetailView.vue"
    local bad=0 tmp
    _page_optional_probe_scan "$view" || bad=1
    tmp="$(mktemp "${TMPDIR:-/tmp}/l23-negative.XXXXXX")" || { echo "无法创建负向夹具"; return 1; }
    printf '%s\n' \
        'const a = await getApprovalInstance(1)' \
        'const b = await listApprovalMine({ page: 1 })' > "$tmp"
    if _page_optional_probe_scan "$tmp" >/dev/null 2>&1; then
        echo "负向自检失败：含裸调用的夹具未被判红（L23 退化成 grep 字符串）"
        bad=1
    fi
    rm -f "$tmp"
    return "$bad"
}
```

注册行：

```bash
run_check "L23 详情页辅助探测一律 silentForbidden（裸调用计数==带 silent 计数 + 无裸 http + 负向自检必红）" detail_optional_probes_are_silent
```

同步改：文件头注释的清单在 L22 后加一行 L23 说明；`make lint` 输出 25/25。

### 6.2 access 新段 `scripts/tests/access/a8-detail-optional-probe.mjs`（13 条）

骨架照抄 `scripts/tests/access/a7-estimate-gate.mjs`：`check(id, ok, detail)` 打印 PASS/FAIL、结尾汇总写 `a8 <pass> <total>` 到 `${ACCESS_COUNTS}`、`pass !== total` → `process.exit(1)`。常量：`GATEWAY = process.env.GATEWAY_URL || 'http://127.0.0.1:8080'`，账号 `A8_DEV_USER||dev1`、`A8_QA_USER||qa1`、`A8_VENDOR_USER||vendor1`，口令 `Passw0rd!`（admin 用 `Admin@12345`）；登录体 `{tenantCode:'t1', username, password}`（与 a4/a7 同源）。

静态 4 条（读文件 + 正则，文件路径用 `resolve(HERE,'../../..')` 拼，和 a7 一致）：

| ID | 断言 |
|---|---|
| S1 | `web/src/api/req.ts` 中 `getApprovalInstance` 签名含 `extra?: Partial<RequestOptions>`（`/export function getApprovalInstance\([\s\S]{0,160}?extra\?: Partial<RequestOptions>/`），且函数体是 `` `${REQ}/approvals/instances/${id}`, undefined, extra `` |
| S2 | `RequirementDetailView.vue`（先剔纯注释行）：`getApprovalInstance(` 出现 3 次、其中带 `{ silentForbidden: true }` 的 3 次；`listApprovalMine(` 1 次且带 silent；`getRequirement(id.value)` 出现且**不带** `silentForbidden` |
| S3 | 403 判定与文案唯一：`web/src` 递归遍历 `.ts/.vue`，含字面量 `无权查看审批进度（仅发起人/审批人/抄送人可见）` 的文件恰好 1 个且为 `web/src/views/req/tabs/ApprovalTab.vue`；该字面量在该文件内出现 1 次；`RequirementDetailView.vue` 含 `status === 403`（2 处：两个 loader） |
| S4 | `client.ts` 注释锚点齐（`lint L23`、`access a8`、`getRequirement` 出现在 `silentForbidden` 注释块内）；详情页无 `navigate('/403')`、无 `/403` 字符串 |

运行时 6 条：

| ID | 断言 |
|---|---|
| R1 | dev1 登录后 `GET /api/req/requirements?page=1&size=50` → 200 且 `items.length ≥ 1`（否则判红并打印「夹具缺失：先 make platform-demo-reset」） |
| R2 | dev1 取 R1 第一条的 `id`，`GET /api/req/requirements/{id}` → 200 且 body 有 `id`（主数据不因审批 403 而失败）；打印 `estimate.instanceId` 供 N 段复用 |
| R3/R4 | qa1 同 R1/R2 |
| R5/R6 | vendor1 同 R1/R2（厂商链 VEN-A；若 `items` 为 0 → 判红，不 skip） |

负向 3 条（N1–N3）：候选实例 = 管理员视角遍历 `requirements?page=1&size=100` 前 40 条详情的 `estimate.instanceId` 去重（最多取 10 个真实实例 id）；对每个角色依次 GET，**第一个 403 即命中**，断言：`status===403` 且 `body.code==='FORBIDDEN'` 且 `body.message==='无权查看该审批实例'` 且 body **不含** `instance`/`document`/`timeline`（不泄漏内容）；10 个候选全都不是 403 → 判红「没有找到 <role> 非参与的实例（夹具缺失：先 make platform-demo-reset）」。三份断言共用 `negativeFor(role, auth, ids, id)` 帮助函数，只有账号不同。

### 6.3 聚合器 `scripts/access-verify.sh`（五处）

1. `ALL_SEGMENTS=(a0 a1 a2 a3 a4 a5 a6 a7 a8)`；
2. `segment_cmd` 的 node 分支加 `a8`（`a3|a4|a5|a6|a7|a8) node ...`）；
3. `segment_file` 加 `a8) printf '%s/a8-detail-optional-probe.mjs' "$ACCESS_DIR" ;;`；
4. `segment_min` 加 `a8) printf 13 ;;`（静态 4 是硬底线，运行时 3 角色 ×2 + 负向 3 同为必需 → 13 就是分母，不接受退化）；
5. 分派处 `[[ "$seg" == a3 || ... || "$seg" == a8 ]]`；总数下限 `TOTAL < 206` → `TOTAL < 219`，注释与头部说明同步（206 → 219 = +13，八段 → 九段）。

### 6.4 分母总账（本轮只增不减）

| 门禁 | 基线 | 本轮 |
|---|---|---|
| `make lint` | 24 | **25**（+L23） |
| `make access-verify` | 206 | **219**（+a8 13，九段） |
| `make platform-verify` | 199 | ≥199 |
| `make platform-test-negative` | 132 | ≥132 |
| `make rbac-verify` | 113 | 113 |
| `make gate-order-verify` | 21 | ≥21 |
| `make release-flow-verify` | 44 | 44 |
| `make platform-demo-reset` | 80 | 80（连跑两次逐键一致） |
| `make editability-verify` | 36 | 36 |
| `make change-verify` | 46 | 46 |
| `make platform-unit` | 437 | ≥437 |
| `web` typecheck+build | 0 error | 0 error |

## 7. 边界与风险

- **R1 `approvalInstanceId` 死路径**：不围绕它设计门禁；3 个调用点一律静默，`estimate.instanceId` 为实际触发路径。若将来后端补了该字段，静默仍在（同一 api 封装）。
- **R2 复位**：`refreshAll()` 在动作成功后重跑两个 loader，若忘记在 loader 开头复位，403 会"粘住"——审批动作完成后仍显示无权。已在 §5.2 的代码里固定。
- **R3 非 403 不得降级**：`status === 403` 之外一律走 `messageOf(error)` + 既有 `ErrorState`（评估块保留 retry）。
- **R4 `canSubmit` 与 forbidden**：forbidden 分支在 `!instance` 之前，避免非参与人看到「提交审批」按钮（虽然能提交的人必是参与人，顺序仍按此写，防御夹具异常）。
- **R5 文案换行**：1280×800 下文案 24 个全角字，评估块内会折成两行，`.estimate-block__reason` 已是 `line-height:1.6`；1024 宽度目检一次（PRD §7）。
- **R6 概览卡片**：403 时概览仍显示「还没有审批记录」（D6 已知取舍，不在本轮 AC 内）。
- **R7 夹具**：dev1/qa1 是否同看一条需求、三角色是否各有"非参与实例"由 demo-reset 后实测决定；不足时**只调归属、不改断言计数**，验收报告注明（PRD §9-a）。
- **R8 单行约束**：L23 逐行计数，代码格式化（含 IDE 自动换行）把 `getApprovalInstance(` 的实参折行会误判——`lint` 会立刻红并打印行号，按提示改回单行。
- **R9 不得扩面**：不给 `getRequirement`、`listRequirementChanges`、`actOnRequirement` 改语义；不动 `RequirementChangeDetailView.vue:115`、`ApprovalCenterView.vue:278`（P2）。

## 8. 测试策略

| 层 | 手段 | 判据 |
|---|---|---|
| 静态（免栈） | `bash scripts/tests/lint.sh`（L23 a/b/c） | 25/25；把任一处 `{ silentForbidden: true }` 删掉 → 立即红 |
| 静态（免栈） | `node scripts/tests/access/a8-detail-optional-probe.mjs`（S1–S4） | 4/4；`R45_MUTATE=bare-call` 等价操作（手工抽掉一处 silent）→ S2 红 |
| 编译 | `cd web && npm run typecheck && npm run build` | 0 error（props 名/签名错都会红） |
| 运行时 | `bash scripts/access-verify.sh a8` | 13/13（R1–R6 依赖栈 + 演示夹具，N1–N3 依赖真实实例） |
| 回归 | `make platform-verify` / `platform-unit` / `rbac-verify` / `gate-order-verify` / `release-flow-verify` / `platform-test-negative` / `editability-verify` / `change-verify` / `platform-demo-reset` ×2 | 见 §6.4，两次连跑一致 |
| 证据 | 两张 1280×800 截图（同一需求，dev1/qa1 本人）+ `negative-403.txt` 三角色 curl 原文 | AC-12 / AC-13 |
| 负向自检 | 抽掉 `loadEstimateApproval` 的 `{ silentForbidden: true }` → lint L23 与 a8 S2 必须红；恢复后绿 | AC-9(c) / AG-4 |

## 9. 任务拆分（按文件互斥，可并行）

接口契约（§4/§5）在本文件已冻结，T2/T3 可以**先写**断言的期望形态，与 T1 并行；T1 落地后一起复跑。

| 任务 | 文件（所有权） | 只读 | 验收 |
|---|---|---|---|
| **T1 前端修复** | `web/src/api/req.ts`、`web/src/api/client.ts`、`web/src/views/req/RequirementDetailView.vue`、`web/src/views/req/tabs/ApprovalTab.vue` | `web/src/api/types.ts`、`web/src/views/req/platform.ts`、`web/src/views/approval/ApprovalCenterView.vue` | `npm run typecheck && npm run build` 0 error；3 处调用 + 待办探测带 silent；403 只落 forbidden 分支；文案字面量全仓 1 次 |
| **T2 lint L23** | `scripts/tests/lint.sh` | `RequirementDetailView.vue` | `bash scripts/tests/lint.sh` 25/25；抄掉一处 silent 必须红 |
| **T3 access a8** | `scripts/tests/access/a8-detail-optional-probe.mjs`、`scripts/access-verify.sh` | `scripts/tests/access/a7-estimate-gate.mjs`、`scripts/lib.sh`、`web/src/api/req.ts` | 栈就绪时 `bash scripts/access-verify.sh a8` = 13/13；`make access-verify` = 219/219 |
| **T4 文档与记忆** | `docs/teamflow/REQUIREMENT-FLOW.md`、`docs/teamflow/DELIVERED-MODULES.md`、`docs/teamflow/memory.md` | 本文件、`PRD.md` | REQUIREMENT-FLOW 增小节「审批可见性 ≠ 页面准入」；DELIVERED-MODULES 补 r45 一行；memory **同主题替换** r8 那行（把审批实例/评估审批纳入必静默清单，登记 L23 与 a8），不新增 changelog 行；`AGENTS.md` 托管区外 0 diff |
| **T5 证据**（依赖 T1 + 栈） | `docs/teamflow/20261124-r45-req-detail-403-fix/evidence/dev1-detail.png`、`qa1-detail.png`、`negative-403.txt` | `scripts/platform-demo-reset.sh`、a8 帮助函数 | 同一需求、页面正常 + 审批页签逐字文案、账号可辨、1280×800；curl 三段含状态行 + `"code":"FORBIDDEN"` + 原文，token 脱敏 |
| **T6 交付自查 + 提交**（最后） | 无产品文件（仅 git 动作） | 全部 | §6.4 全表复跑两次一致；`git diff --stat` 仅 AC-14 白名单；`pom.xml`/`web/package.json`/lock/`db/migration`/`req-service/src/main/java` diff 为空；`.gitignore` 复核（`target/`、`.nacos/`、`node_modules/`、`dist/`、`.env*`、`logs/`、`*.log` 覆盖，`evidence/*.png` 不被忽略）；提交信息含 runId `tf-mwues0su-fpi3ea` 与任务夹路径，不 amend、不 force push |

**Git 动作（PRD §10 原样保留，落在 T6）**：留在 `feat/detail-approval-silent`，不另起分支、不改写历史；现有未提交项 `?? docs/teamflow/20261124-r45-req-detail-403-fix/` 随本轮一并提交；提交信息形如 `fix(req): 需求详情页非审批参与人不再整页 403（r45）`，正文含 `runId=tf-mwues0su-fpi3ea` 与任务夹路径。

## 10. AC → 实现 → 验证

| AC | 实现点 | 验证 |
|---|---|---|
| AC-1 不跳 403 | client.ts 跳过导航（既有）+ 3 处探测 silent | a8 S2 + 截图 |
| AC-2 内联降级 | `FORBIDDEN_HINT` + `forbidden` / `estimateForbidden` 两分支 | a8 S3 + 截图 |
| AC-3 非 403 不降级 | `status === 403` 才置 forbidden，其余 `messageOf` + ErrorState | a8 S3 + 代码审查 |
| AC-4 动作不误伤 | 动作链零改动（`actOnRequirement` 已 silent） | 既有 action 套件 |
| AC-5 厂商同策略 | 同一 api 封装 + 同一页签 | a8 R5/R6 + N3 |
| AC-6 服务端零改动 | 不动 `visibleInstance()` | `git diff -- req-service` 为空 + N1–N3 |
| AC-7 主数据不静默 | `getRequirement` 无 silent | a8 S2 |
| AC-8 探测清零 + 注释 | 4 处调用点 + client.ts 注释 | lint L23 + a8 S4 |
| AC-9 lint L23 | §6.1 | `make lint` 25/25 + 负向变红 |
| AC-10 a8 13 条 | §6.2 / §6.3 | `make access-verify` 219/219 |
| AC-11 门禁不缩水 | §6.4 | 全表两次连跑 |
| AC-12 截图 | T5 | 人工核验 |
| AC-13 curl | T5 | 人工核验 |
| AC-14 改动面/提交 | §9 T6 | `git diff --stat` 白名单 |
| AC-15 文档与记忆 | T4 | grep 小节标题 / 行数 |
