<!-- meta: summary="分支策略页的 GitLab 集成探测改为调用点声明 silentForbidden，403 降级为页面内 ENABLED/DISABLED/UNREADABLE 三态常驻提示，核验入口同态闸门；新增 access a5 段（14 运行时 + 5 静态 + 3 自检 = 22 断言）把『可选能力探测未标记』变成门禁可拦错误，access 总数 95→117（守卫 115）" -->
基线依赖：docs/teamflow/20261110-r7-role-page-access-fix（`silentForbidden` 机制、`client.ts` 403 默认跳转、导航准入、字典收敛、demo-reset 行为均不回归）
取代：docs/teamflow/20261110-r7-role-page-access-fix#AC-5 —— 页面可用性证据口径由「首屏主端点 200」升级为「主端点 + 页面内可选探测调用语义」；a2 既有 dev1=200 断言保留不减少，但单独通过它不再算 `/code-policies` 可用

# 分支策略页（/code-policies）非管理员 403 修复 · 技术方案

## 0. 一页结论

**一句话**：把 `/code-policies` 加载时那次 GitLab 集成配置读取，从「普通读」改成「显式声明的可选能力探测」——403 只降级为页面内三态提示（ENABLED / DISABLED / UNREADABLE），不升级为整页 `/403`；再用 `a5` 段把这个「漏标就整页不可用」的缺陷变成门禁可拦的错误。

改动面（**零后端、零 schema、零权限码**）：

| 类别 | 文件 | 性质 |
|---|---|---|
| 前端 API | `web/src/api/integration.ts` | `listConfigs` 增可选第二参（透传 `silentForbidden`），默认行为不变 |
| 前端页面 | `web/src/views/code/CodePolicyView.vue` | 调用点补标记 + 三态派生 + 常驻提示条 + 核验闸门 + 页头链接收口 |
| 门禁脚本 | `scripts/tests/access/a5-capability-probe.mjs`（新增） | 14 运行时 + 5 静态 + 3 自检 = 22 断言 |
| 门禁聚合 | `scripts/access-verify.sh` | 五处登记 a5（段清单/命令/文件/汇总/总数守卫 45→115） |
| 演示文档 | `docs/teamflow/DEMO-BY-ROLE.md` + `docs/teamflow/demo-shots/dev1-code-policies-fixed.png` | dev1 行补实测与「为什么是未核验」 |

**不在本轮**：`client.ts` 的 403 默认跳转（`:180`）一个字符都不改；不给 `http.get` 全局加 `silentForbidden`；不动 `PermissionRouteTable` / `PermissionMatrix` / Flyway；不加后端只读摘要端点（PRD §11 Q4 取推荐默认）。

### 0.3 与 r7 的口径关系（取代声明落地）

r7 的 AC-5 用「首屏主端点状态码」证明页面可用；本页正是那种「主端点 200、页面仍被踢走」的反例（`GET /code/policies` 200，但 `GET /integration/configs` 403 触发全局跳转）。因此本夹把可用性判据补成两段：**主端点（路由准入）** + **页面内可选探测（能力准入）**。落地方式：a2 不动（历史证据只减不加），新增 a5 承接第二段；`DEMO-BY-ROLE` 的 `/code-policies` 行注明「准入通过 ≠ 探测可读」。

## 1. 现状与根因（代码级）

```
CodePolicyView.vue:62   const configs = await listConfigs('GITLAB')          ← 未声明 silentForbidden
api/integration.ts:92   http.get(`${REQ}/integration/configs`, { system })   ← 走 request() 默认语义
client.ts:180           if (!options.silentForbidden) { navigate(`/403${query}`) }  ← 抛错前已跳转
PermissionRouteTable.java:59  rule(null, "^/integration/(configs|logs|admission-items)(/.*)?$", false, "integration:manage")
```

页面 `loadGitlabConfig()` 里写的 `try/catch`（注释「读不到配置不阻断列表」）**结构正确但无效**：`navigate()` 在 `throw error` 之前执行，整页已被路由切走，catch 里的 `gitlabConfig = null` 作用在一个已卸载的组件上。

角色事实（`PermissionMatrix.java:317-321`）：`PLATFORM_ADMIN` 经 catch-all 持有全部码（含 `integration:manage`）；`PMO`/`DEV` 只有 `CCODE_POLICY_VIEW`；`DEV` 无 `CCODE_POLICY_EDIT`（核验按钮对 dev1 恒 `:disabled`）。故本页的两类事实必须分开表达：**能不能进页**（`meta.functionCode`）与**能不能读集成配置**（`integration:manage`）。

## 2. 设计决策（含被否方案）

| # | 决策 | 理由 | 被否方案 |
|---|---|---|---|
| D1 | 标记落在**调用点**（`CodePolicyView.vue` 传 `{ silentForbidden: true }`），api 层只透传 | 与 r7 既有约定同形（`api/admin.ts:185-190` 的 `listApiClients` 第二参就是给「非管理页借读」用的）；审计器可**单跳**判定「这次调用有没有声明」，不必追进 api 层 | 在 `api/integration.ts` 里加一个专用 `probeGitlabConfig()` 并在函数内写标记：也能满足 AC-2，但审计要两跳（页面调用 → 函数体 → http 调用），且与 `listApiClients` 的既有约定分叉 |
| D2 | 三态用 `'ENABLED' \| 'DISABLED' \| 'UNREADABLE'`，外加 `null` 表示「探测中」 | 首屏三路并行，探测未回时若先渲染 UNREADABLE 会闪一条假黄条；`null` 只是生命周期占位，**不是第四种可渲染状态** | 单布尔 `gitlabReady` + 布尔 `enabled`：两布尔有 4 种组合，其中一种是非法态，QA 无法穷举 |
| D3 | 提示条复用既有 `.policy-notice--warning`，不新建组件 | 页内已有该样式的行内提示（`CodePolicyView.vue:476`），再抽组件只为一处使用，反而增加 import 面 | 新建 `CapabilityNotice.vue`；或复用 `ElAlert`（与页面既有 `dl-*` 视觉体系不一致） |
| D4 | 不加后端只读摘要端点 | 配置面含 `baseUrl` 与凭据末四位，收窄在 `integration:manage` 内更安全；加了要重算权限面 + req-service 单测 + a5 断言（半天范围扩大） | 给登录用户降级返回 `{system, enabled}`（PRD §11 Q4 备选，默认不做） |
| D5 | 页头那句「平台只登记规则……集成配置」里的**链接**同样按 `integration:manage` 收口 | 对 dev1 它是一条「点了就 403」的死链——同一类「指错方向」，与本轮要修的病同源（PRD 反面目标 5） | 保留无条件链接：dev1 点开被路由守卫送 `/403`，等于页面自己制造一次 403 体验 |

## 3. 数据模型与存储

### 3.1 存储层：无变更

无 Flyway 迁移、无表结构、无权限码、无接口契约变更（AC-7）。`integration_config` 表与 `^/integration/configs` 的权限规则原样保留——**本轮的「放开」只发生在浏览器侧的错误处理，不发生在服务端授权**。

### 3.2 页面态模型（唯一新增的「模型」）

| 字段 | 类型 | 取值 | 渲染含义 |
|---|---|---|---|
| `gitlabCapability` | `Ref<GitlabCapability \| null>` | `null` | 探测中：不渲染提示条，核验按钮按 `canEdit` 原样置灰 |
| | | `'ENABLED'` | 读到 `enabled=true` 的 GITLAB 配置：无黄条，核验链路照旧 |
| | | `'DISABLED'` | 读到但全部 `enabled=false`，或返回空数组：黄条「未启用」+ 有 `integration:manage` 时给「去集成配置」 |
| | | `'UNREADABLE'` | 403 或网络失败：黄条「无法读取（需 integration:manage）」，**不给**配置入口 |
| `gitlabConfig` | `Ref<IntegrationConfigView \| null>` | 仅存**已启用**的那条 | 语义收窄：从「列表首条」改为「已启用配置」，`null` 即不可核验 |

不变量：`gitlabCapability === 'ENABLED'` ⇔ `gitlabConfig !== null`。任何写入路径都必须同时更新两者（见 §6.4）。

### 3.3 API 层类型

```ts
// 复用既有 IntegrationConfigView；不新增类型
export function listConfigs(
  system?: IntegrationSystem | string,
  options?: { silentForbidden?: boolean },   // 形状对齐 api/admin.ts:185-190 的 listApiClients
): Promise<IntegrationConfigView[]>
```

## 4. API 设计

### 4.1 前端 API 层（唯一签名变更）

```ts
// web/src/api/integration.ts:91
export function listConfigs(
  system?: IntegrationSystem | string,
  options?: { silentForbidden?: boolean },
): Promise<IntegrationConfigView[]> {
  // 第二参只给「非管理页的可选能力探测」用：调用点声明后 403 只降级为页面内状态，
  // 不再由 client.ts 全局跳 /403；管理面自己不传，行为不变（同 listApiClients 的约定）。
  return http.get<IntegrationConfigView[]>(`${REQ}/integration/configs`, system ? { system } : undefined, options)
}
```

约束：**不得**给 `listConfigs` 的 `options` 设默认值 `{ silentForbidden: true }`——那会把管理页（`views/admin/integrations/ConfigTab.vue:96`）的 403 也吞掉，等于全局关小默认语义（PRD 反面目标 3）。

### 4.2 后端契约（不变，仅登记事实）

| 端点 | 方法 | `PermissionRouteTable` 所需码 | 返回 |
|---|---|---|---|
| `/api/req/code/policies` | GET | `CCODE_POLICY_VIEW`（`^/code/policies(/.*)?$`） | `PolicyRow[]`（既有） |
| `/api/req/integration/configs?system=GITLAB` | GET | `integration:manage`（`^/integration/(configs\|logs\|admission-items)(/.*)?$`，`:59`） | `IntegrationConfigView[]`，凭据只有 `hasCredential/credentialLast4` |
| `/api/req/code/policies/{id}/check` | POST | `CCODE_POLICY_EDIT` | `PolicyCheckView`；集成未启用 → 409 `INTEGRATION_DISABLED` |

`403` 的响应体形状由网关/服务端既有实现给出（`{code,message,detail}`），本轮不改；前端只按 `ApiError.status === 403` 判态。

### 4.3 AC-4 四步扫描 → 同类调用点清单（结论：未声明且未豁免 = 0）

口径（缺一步不算完成）：① `web/src/{views,components,api}` 全量 http / api 函数调用 → ② 解析端点字面量 → ③ 对 `PermissionRouteTable` 按**首个匹配**取所需码 → ④ 对比所在路由 `meta.functionCode`；凡「所需码 ⊄ 页面守卫码」的**读**调用，非管理页必须显式声明 `silentForbidden`。

| # | 位置 | 端点 | 所需码 | 页面守卫码 | 处置 |
|---|---|---|---|---|---|
| 1 | `web/src/views/code/CodePolicyView.vue:62` | `GET /integration/configs?system=GITLAB` | `integration:manage` | `CCODE_POLICY_VIEW` | **缺陷，本轮补标记**（a5 S1/S2 钉住） |
| 2 | `web/src/views/deliver/TestExecutorsView.vue:71` | `GET /admin/api-clients` | `apiclient:manage` | `TEST_EXECUTOR_VIEW` | 已声明（r7），a3 R9 钉住 |
| 3 | `web/src/layouts/AppShell.vue:29` | `GET /approvals/todo` | 仅认证 | 壳层（无守卫码） | 已声明（r7） |
| 4 | `web/src/api/dictionaries.ts:51/56/61` | 三个字典端点 | 仅认证 | — | 已声明（r7），a3 R5 钉住 |
| 5 | `web/src/views/deliver/TraceabilityView.vue:134`（`getSonar`） | `GET /requirements/{id}/sonar` | 无匹配规则（仅认证） | `gate:view` | 无需声明，登记即可 |
| 6 | `web/src/views/release/ReleaseDetailView.vue:243`（`getReleaseIntegration`） | `GET /releases/{id}/integration-settings` | **`release:plan`**（被 `^/releases(/.*)?$` 兜底命中） | `release:plan` | 所需码 == 守卫码，无需声明。**基线补正**：PRD AC-4 表记「仅认证」不准确，按 `PermissionRouteTable.java:178` 更正；结论不变 |
| 7 | `web/src/views/admin/integrations/ConfigTab.vue:96` | `GET /integration/configs` | `integration:manage` | `integration:manage` | 豁免：管理页调用自身守卫码对应端点（a5 S5 要求每条豁免带理由） |

预扫描结论：**新增缺陷仅第 1 行**（与 PRD 一致）；第 6 行的所需码有补正。复核方法为机械扫描（a5 S2/S4 覆盖第 1 类，a3 R9 覆盖第 2 类），不靠人眼。

## 5. 前端组件与页面切分

只改两个文件，不新建组件、不新增 store、不新增路由。

| 文件 | 改动点 | 契约 |
|---|---|---|
| `web/src/api/integration.ts` | `listConfigs` 增第二参并透传（§4.1） | 默认调用 `listConfigs()` / `listConfigs('GITLAB')` 行为完全不变 |
| `web/src/views/code/CodePolicyView.vue` script | ① `type GitlabCapability`；② `gitlabCapability` / `canManageIntegration` / `capabilityNotice` / `CHECK_BLOCKED_TEXT`；③ 重写 `loadGitlabConfig()`；④ `onCheck` 加闸门；⑤ 新增 `refreshAll()` | 见 §6，导出面为组件内部，无对外 API |
| 同上 template | ① `p.dl-hint` 之后插入常驻提示条；② 页头「集成配置」链接加 `v-if`；③ 刷新按钮改 `@click="refreshAll()"` | DOM 挂钩：`data-testid="policy-capability-notice"`（QA/截图脚本可定位）；必须保留 `el-table` 挂载（列表不因探测失败消失） |
| 同上 style | 仅补两条：`.policy-capability { margin: 0 0 12px; }`、`.policy-notice__action { margin-left: auto; }` | 复用既有 `.policy-notice` / `.policy-notice--warning` / `.policy-link` |

模板片段（按此形状落）：

```html
<!-- 位置：<p class="dl-hint policy-hint"> 之后、<div class="dl-filters"> 之前 -->
<div
  v-if="capabilityNotice"
  class="policy-notice policy-notice--warning policy-capability"
  role="status"
  data-testid="policy-capability-notice"
>
  <span>{{ capabilityNotice.text }}</span>
  <button v-if="capabilityNotice.withLink" type="button" class="policy-link policy-notice__action" @click="goIntegration">
    去集成配置
  </button>
</div>
```

页头提示行（`CodePolicyView.vue:444-447`）改为：

```html
<button v-if="canManageIntegration" type="button" class="policy-link" @click="router.push({ name: 'admin-integrations' })">
  集成配置
</button>
```

## 6. 状态管理与数据流

### 6.1 探测（唯一的写入口）

```ts
type GitlabCapability = 'ENABLED' | 'DISABLED' | 'UNREADABLE'
const gitlabCapability = ref<GitlabCapability | null>(null)
const gitlabConfig = ref<IntegrationConfigView | null>(null)
const canManageIntegration = computed(() => can('integration:manage'))

async function loadGitlabConfig(): Promise<void> {
  try {
    // 可选能力探测：没有 integration:manage 的账号这里必然 403，
    // 降级为页面内 UNREADABLE，绝不能升级为整页 /403（本页的第一条不变量）
    const configs = await listConfigs('GITLAB', { silentForbidden: true })
    const enabled = configs.find((item) => item.enabled) ?? null
    gitlabConfig.value = enabled
    gitlabCapability.value = enabled ? 'ENABLED' : 'DISABLED'
  } catch {
    // 403（无 integration:manage）与网络失败同态：都只能说明「读不到」，不能断言「未启用」
    gitlabConfig.value = null
    gitlabCapability.value = 'UNREADABLE'
  }
}
```

### 6.2 生命周期与刷新

- `onMounted`：`loadDomains()` / `loadGitlabConfig()` / `load()` 三路并行（保持现状，互不 await）。
- `refreshAll()`：`void loadGitlabConfig(); void load()` —— 手动「刷新」重探一次，管理员在别处启用集成后回本页刷新即可消黄条（无需整页 reload）。
- 并发：`loadGitlabConfig` 无重入保护，两次并发以最后一次结果为准（同一幂等读，无副作用）；不引入 abort/序号。

### 6.3 核验闸门（点「核验」不发请求）

```ts
const CHECK_BLOCKED_TEXT: Record<GitlabCapability, string> = {
  ENABLED: '',
  DISABLED: 'GitLab 集成未启用，核验不会发起请求；请先在「集成配置」启用 GITLAB。',
  UNREADABLE: '当前账号无法读取 GitLab 集成配置，核验不会发起请求；如需核验请联系集成管理员。',
}

async function onCheck(row: PolicyRow): Promise<void> {
  if (!canEdit.value || checkingId.value !== null) return
  rowNotice.value = null
  if (gitlabCapability.value === null) await loadGitlabConfig()
  if (gitlabCapability.value !== 'ENABLED') {
    rowNotice.value = { kind: 'warning', text: CHECK_BLOCKED_TEXT[gitlabCapability.value ?? 'UNREADABLE'] }
    return            // ← 必须早于 checkPolicy()：AC-3 用「不产生 POST」判定
  }
  /* 以下原样保留：checkingId / checkPolicy / ElMessage / load(true) / applyFailedCheck */
}
```

`applyFailedCheck` 的 409 `INTEGRATION_DISABLED` 分支与 `integrationDialog` 保留：配置可能在「加载后、点击前」被管理员关掉，服务端 409 仍要能解释。

### 6.4 提示条派生（页面级事实，不进 pill）

```ts
const capabilityNotice = computed<{ text: string; withLink: boolean } | null>(() => {
  if (gitlabCapability.value === 'DISABLED') {
    return { text: 'GitLab 集成未启用，核验结果将保持「未核验」。', withLink: canManageIntegration.value }
  }
  if (gitlabCapability.value === 'UNREADABLE') {
    return {
      text: '当前账号无法读取 GitLab 集成配置（需 integration:manage），核验结果将保持「未核验」；如需核验请联系集成管理员。',
      withLink: false,
    }
  }
  return null
})
```

文案是**判定条件**，逐字落库（QA 与 a5 都按字面核）：

| 态 | 契约文案 | 禁词 |
|---|---|---|
| DISABLED | `GitLab 集成未启用，核验结果将保持「未核验」。` | 「权限」「无权」 |
| UNREADABLE | `当前账号无法读取 GitLab 集成配置（需 integration:manage），核验结果将保持「未核验」；如需核验请联系集成管理员。` | 「未配置」「未启用」「尚未接入」「请配置」「Maintainer」 |
| ENABLED | 不渲染任何提示条 | — |

`rowNotice`（可关闭、一次性）与 `capabilityNotice`（常驻、按态派生）**不合并**：合并会让「刷新清页面级事实」或「探测重跑关掉行内报错」二选一。

## 7. 关键实现点与边界

- **K1 标记与实参同源**：`silentForbidden` 必须出现在 `listConfigs(...)` 这一次调用的实参里（`client.ts` 只在同一次 `request()` 内读 `options.silentForbidden`），写在别处或注释里都不算——a5 按调用点判定（§8.3 C3）。
- **K2 api 层不加默认标记**：`listConfigs` 的 `options` 无默认值；管理页 `ConfigTab.vue:96` 保持裸调用。
- **K3 探测失败不清列表**：`loadGitlabConfig` 的 catch 只写 `gitlabConfig/gitlabCapability`，**不得**触碰 `rows` / `error` / `loading`。列表 0 行只能来自「确实没数据」或「列表接口自身失败」。
- **K4 页面级事实不进 pill**：`PolicyCheckPill` 的 `reason` 仍只吃服务端派生结果；不得把「读不到配置」塞进 `row.latestCheck.reason`，也不得复用 `POLICY_REASON_LABEL.PERMISSION_INSUFFICIENT`（`api/code.ts:96` 的文案是「需 Maintainer 及以上」，语义是 GitLab 侧角色）。
- **K5 闸门早于请求**：`onCheck` 的三态判断必须在 `checkPolicy()` 之前 return；否则 admin 在 DISABLED 下点核验仍会发 POST 并吃 409（AC-3 判红）。
- **K6 401 不参与三态**：会话过期走 `client.ts` 的 401 分支跳登录，页面态怎么写都会被卸载；不要在 catch 里按 status 细分 401。
- **K7 空列表 = DISABLED**：`configs.find(enabled) ?? null` 同时覆盖「有配置但停用」与「一条都没有」；两种情况对页面都是「不可核验」。
- **K8 首屏不闪黄条**：`null` 态不渲染提示条（`capabilityNotice` 返回 null）。
- **K9 刷新重探**：「刷新」按钮改 `refreshAll()`；切入/切出筛选只调 `load()`，不重探（避免每次筛选打一次 403）。
- **K10 域裁剪不变**：探测不带 `domainId`，与列表的域/厂商裁剪正交；vendor1 的行裁剪仍由 `/code/policies` 的服务端派生决定（r7 已验，a5 只断言状态码与 `/code/policies` 200 共存）。
- **K11 页头链接收口**：`canManageIntegration` 同时管提示条链接与页头链接；不要为两处写两套判定。
- **K12 不改 `client.ts`**：`if (!options.silentForbidden)`、`navigate('/403'...)` 与 401/409 分支零改动（a5 S3 + a2 的 pm 深链共同钉住）。

## 8. a5 段设计（`scripts/tests/access/a5-capability-probe.mjs`）

单文件、零依赖、Node 原生 `fetch`（与 a4 同一登录口径：`POST /api/req/auth/login` 取 `token` + `tenantId`，请求头 `Authorization: Bearer` + `X-Tenant-Id`）。运行方式与 a3/a4 一致：`node scripts/tests/access/a5-capability-probe.mjs`，读 `GATEWAY_URL`，计数写 `ACCESS_COUNTS`（格式 `a5 <pass> <total>`）。

### 8.1 断言清单（22 条）

**a5-a 运行时 14 条**（7 账号 × 2 端点，逐条打印「账号 → 端点 期望 vs 实际」）：

| 账号 | `GET /api/req/code/policies` | `GET /api/req/integration/configs?system=GITLAB` | 该格证明的事实 |
|---|---|---|---|
| admin | 200 | 200 | 唯一持 `integration:manage` 的账号，探测真的能读到 |
| dev1 | 200 | 403 | 能进页 + 探测必 403 → 前端必须降级（本缺陷的同构条件） |
| tl1 | 200 | 403 | 同上 |
| vendor1 | 200 | 403 | 同上（且不因域裁剪变成别的码） |
| pm | 403 | 403 | 无 `CCODE_POLICY_VIEW` → 路由层就挡住 |
| po1 | 403 | 403 | 同上 |
| qa1 | 403 | 403 | 同上 |

**a5-b 静态 5 条**：

| # | 断言 | 失败信息要求 |
|---|---|---|
| S1 | `views/code/CodePolicyView.vue` 中 `listConfigs(` 调用点**恰好 1 处**且实参含 `'GITLAB'` 与 `silentForbidden` | 报 `文件:行` |
| S2 | `web/src/views/**`（排除 `views/admin/**`）中所有集成配置读调用点（`listConfigs(` / `http.*('/integration/configs'...)`）**逐点**都带标记；`scanned ≥ 1`（防空扫绿） | 列全部违规点 |
| S3 | `client.ts` 的 `response.status === 403` 分支 300 字符内仍有 `if (!options.silentForbidden)` 且仍有 `navigate(\`/403` | 指向 `client.ts:180` |
| S4 | `web/src/{views,components}` 中出现 `/integration/configs` 字面量 **0 次**（页面不得绕过 `api/integration.ts`） | 列违规文件 |
| S5 | 豁免台账 `EXEMPT_CALL_SITES`（初始 `[]`）每条必须含 `file/line/reason` 且 `file` 存在；「未声明且未豁免」数量 = 0 | 缺 reason 或文件不存在即红 |

**a5-c 审计器自检 3 条**（夹具在内存里，不落盘）：

| # | 夹具 | 期望 |
|---|---|---|
| C1 | `web/src/views/code/CodePolicyView.vue` 第 2 行 `const c = await listConfigs('GITLAB')` | 返回 1 条 offender，`line === 2` |
| C2 | 同形但实参带 `{ silentForbidden: true }` | 0 offender，`scanned === 1` |
| C3 | 坏调用 + 同文件另一处（另一调用/注释）出现 `silentForbidden` 字样 | **仍返回 1 条 offender**（证明按调用点判定，不是「文件里出现过关键字就过」） |

合计 14 + 5 + 3 = **22**；access 全量 95 + 22 = **117**（守卫下限 115）。

### 8.2 审计器契约（导出，供自检与未来复用）

```js
/**
 * 判定「非管理页的集成配置读调用点是否显式声明了 silentForbidden」。
 * @param {{ file: string, src: string }[]} sources  file 为相对仓库根路径（报错用）
 * @returns {{ scanned: number, offenders: { file: string, line: number, snippet: string }[] }}
 */
export function auditCapabilityCalls(sources)

/** 取 `callee(` 的平衡括号实参文本；注释行先剔除（复用 a3 的 stripCommentLines 口径） */
export function callArgs(src, callee)   // -> { index: number, args: string }[]
```

实现要点：用括号配平扫实参（正则 `callee\s*\([^)]*\)` 会被内层对象括号截断，`{ silentForbidden: true }` 正好在括号里 → 必须配平）；`line` 用调用起点前的 `\n` 计数 +1。

### 8.3 聚合器登记（`scripts/access-verify.sh` 五处，缺一不算接入）

1. `ALL_SEGMENTS=(a0 a1 a2 a3 a4 a5)`
2. `segment_cmd()`：`a3|a4|a5) printf 'node %s/%s-*.mjs' ...`
3. `segment_file()`：`a5) printf '%s/a5-capability-probe.mjs' ...`
4. 分发：`if [[ "$seg" == a3 || "$seg" == a4 || "$seg" == a5 ]]` 走 `node`
5. 总数守卫：`TOTAL < 45` → `TOTAL < 115`，文案改「PRD AC-5 下限；a0–a4 一条都不许减少」

另加**分段下限**守卫（比总数更早报错）：`declare -A SEG_MIN=([a0]=29 [a1]=8 [a2]=35 [a3]=9 [a4]=14 [a5]=20)`，只对本次实际跑过的段生效（调试子集不误伤）。

### 8.4 栈未起时

沿用聚合器的 `req_ready` / `web_up` 守卫；单独运行 a5 时，首个登录 `fetch` 失败 → `console.error('a5: 平台栈未就绪（先 make platform-up && make platform-seed）')` + `exit 1`，**不写计数行**（模拟「0 条断言也算过」是反目标）。

## 9. 测试策略

### 9.1 开发自检（本阶段交付前）

```bash
cd web && npm run typecheck && npm run build     # 类型 + 产物
bash scripts/access-verify.sh a5                 # 需 make platform-up + platform-seed
make access-verify                               # 全量 6 段，117 断言
```

### 9.2 QA 必测（与 AC 对应）

| AC | 证据形式 |
|---|---|
| AC-1 | 7 账号浏览器逐格实测（URL/标题/表格是否挂载）+ a5-a 14 条状态码 |
| AC-2 | 变异自检：见 §9.3；`client.ts` 本轮 diff 为空 |
| AC-3 | 三态文案逐字核对（含禁词 grep）；UNREADABLE/DISABLED 下点核验，网络面板无 `POST …/check`；三态下表格都渲染 |
| AC-4 | 本文件 §4.3 清单 + a5 S1/S2/S4/S5 全绿 |
| AC-5 | `make access-verify` 输出 a5 22 条、总数 117；栈停时非 0 退出 |
| AC-6 | 九条门禁全绿（含 unit 237 / quality 348 / integration 231 / collab 263 / negative 25 / lint 16 / verify 14） |
| AC-7 | `git diff --stat` 无 `**/db/migration/**`、无 `PermissionMatrix.java`、无后端源码 |
| AC-8 | `DEMO-BY-ROLE.md` dev1 行 + `dev1-code-policies-fixed.png` |

### 9.3 变异自检（AC-2 的硬证据）

```bash
# 1) 记录基线
bash scripts/access-verify.sh a5                                   # 期望：a5 22/22 绿

# 2) 删标记（改 CodePolicyView.vue:62 为 listConfigs('GITLAB')）
bash scripts/access-verify.sh a5                                   # 期望：红，且报 web/src/views/code/CodePolicyView.vue:<行>

# 3) 恢复并复绿
git diff --stat web/src/views/code/CodePolicyView.vue              # 确认恢复后无差异
bash scripts/access-verify.sh a5                                   # 期望：a5 22/22 绿
```

## 10. 任务拆分（3 并行 + 1 收尾）

| 任务 | 拥有文件（写入） | 只读 | 验收 |
|---|---|---|---|
| T1 探测降级 + 三态提示 | `web/src/api/integration.ts`、`web/src/views/code/CodePolicyView.vue` | `web/src/api/client.ts`、`web/src/api/code.ts`、`web/src/composables/usePermission.ts` | `npm run typecheck` 绿；dev1 打开页面停留 + 表格渲染 + 黄条文案逐字对；admin ENABLED 无黄条；点核验无 POST |
| T2 a5 门禁 + 聚合器 | `scripts/tests/access/a5-capability-probe.mjs`、`scripts/access-verify.sh` | `scripts/tests/access/a2-page-matrix.sh`、`a3-nav-audit.mjs`、`a4-nav-visibility.mjs`、`scripts/lib.sh`、`platform-common/.../PermissionRouteTable.java` | `bash scripts/access-verify.sh a5` 22/22；全量 117；栈停非 0 |
| T3 演示文档与截图 | `docs/teamflow/DEMO-BY-ROLE.md`、`docs/teamflow/demo-shots/dev1-code-policies-fixed.png` | `docs/teamflow/demo-shots/`（r7 命名习惯）、T1 的页面 | dev1 行含实测状态 + 「为什么是未核验」一句；截图 1440×900 且与既有 `*-fixed.png` 一致；不动工作台/报表章节 |
| T4 交付自检 + 收尾提交 | 无（返修时触碰 T1–T3 的文件） | 全部 | §9.1 全绿 + §9.3 变异三步 + §10.2 git 动作完成 |

T1/T2/T3 文件互不相交，可并行；T4 依赖三者完成。**不得**为本轮重建 a0–a4 或任何已存在的检查脚本；a5 缺覆盖时**就地扩展**本文件。

### 10.2 git 动作（PRD §10 工程约束落地，不得丢失）

- 分支：`feat/role-page-access-fix`（当前分支）上直接提交；**不新建分支、不 rebase、不切基线**。
- 现有未提交改动一并纳入收尾提交，**不 stash、不删除**：`M docs/teamflow/memory.md`、`?? docs/teamflow/20261110-r8-code-policies-403-fix/`、`?? docs/teamflow/demo-shots/{admin-requirements-fixed,pm-approvals-fixed,po1-requirements-fixed,qa1-testplans-fixed,vendor1-requirements-fixed}.png`，外加本轮新增的 `dev1-code-policies-fixed.png` 与本夹 `TECHNICAL.md`。
- 提交前 `git status --short` 必须确认**没有** `web/dist/`、`node_modules/`、`logs/`、`test-results/` 被带入（`.gitignore` 已覆盖，只需核对不新增规则）。
- 提交信息：`fix: 分支策略页可选集成探测 403 降级为页面内三态提示（runId=tf-mwa402ox-br1nqs，任务夹 docs/teamflow/20261110-r8-code-policies-403-fix）`
- 只做本地提交，**不 push、不打 tag**；提交流程不修改 `AGENTS.md`。

## 11. 风险与回滚

| 风险 | 影响 | 处置 |
|---|---|---|
| 有人「顺手」把 `client.ts` 默认改成不跳 | pm/po1/qa1 深链不再落 403（安全语义倒退） | a5 S3 + a2 的 5 格 ⛔ 双红；K12 明令零改动 |
| 静态审计按路径豁免管理页是启发式 | 页面搬家后漏判 | a5 S5 要求豁免带理由；S2 的 `scanned ≥ 1` 防空扫；清单在 §4.3 可复核 |
| 常驻黄条成为噪音 | ENABLED 用户被打扰 | `ENABLED` 不渲染；QA 实测 admin 开/关两态 |
| 文案把权限问题说成配置问题 | 误导排查 | 文案逐字表 + 禁词 grep（§6.4） |
| 探测失败拖垮列表 | 整页错误态 | K3 + a5-a 的 `code/policies` 200 断言（列表端点与探测端点分开判） |

回滚：单个提交 revert 即可——改动集中在 2 个前端文件 + 1 个新增脚本段 + 1 个聚合器守卫 + 1 篇文档 + 1 张截图，无数据/契约副作用；`silentForbidden` 是纯前端语义，回滚不影响任何服务端行为。

## 12. AC → 实现 / 证据映射

| AC | 实现落点 | 证据 |
|---|---|---|
| AC-1（14 格页面矩阵） | `CodePolicyView.vue`（探测降级 + 列表不受影响） | QA 7 账号浏览器实测 + a5-a 14 条 |
| AC-2（探测语义钉住） | `CodePolicyView.vue:62` 调用点 `{ silentForbidden: true }`；`client.ts` 零改动 | a5 S1/S3 + §9.3 变异三步 |
| AC-3（三态与文案） | `gitlabCapability` + `capabilityNotice` + `onCheck` 闸门（§6） | 文案逐字核对 + 无 `POST …/check` + 三态下列表均渲染 |
| AC-4（扫描归零） | §4.3 清单 | a5 S1/S2/S4/S5 |
| AC-5（a5 ≥20，总数 ≥115） | a5 22 条 + 聚合器五处 + 分段下限 | `make access-verify` = 117/117 |
| AC-6（既有门禁不回归） | 无后端/无 schema 变更 | 九条门禁全绿 |
| AC-7（零协议/零 schema/零权限码） | 仅前端 + 脚本 + 文档 | `git diff --stat` 仅 4 类文件 |
| AC-8（演示文档与截图） | T3 | `DEMO-BY-ROLE.md` dev1 行 + `dev1-code-policies-fixed.png` |
