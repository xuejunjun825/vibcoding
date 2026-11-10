# TECHNICAL — 角色×页面准入修复（非管理员角色打不开五个核心页面）

> 任务夹：`docs/teamflow/20261110-r7-role-page-access-fix/`（本文件只写一次，不升版）
> 基线：`main @ ea877fa`（PRD §8）｜需求：本夹 `PRD.md`（17 条 AC）｜上游记忆：`docs/teamflow/memory.md`
> 结论一句话：**一份只读字典端点 + 一个前端字典单一入口 + 一套「导航/路由同源」的准入判定**，不加权限码、不动 56 格矩阵、不改 schema/Flyway。

---

## 0. 设计结论速览

| # | 决策 | 理由（承重） |
|---|---|---|
| D1 | 新增 `GET /dictionaries/{domains,vendors,users}` 到 req-service，**不进 `PermissionRouteTable`**（仅认证即可读） | 非管理页当前借 `/admin/*`（`org:manage`）读字典，`PermissionInterceptor` 直接 403，且 403 触发全局跳转 → 页面打不开。字典是"读选项"不是"管理面"，加权限码会改矩阵（PRD 非目标）。 |
| D2 | 前端新增 `web/src/api/dictionaries.ts` 作为**唯一入口**，16 个非管理文件改走它；管理页仍用 `api/admin.ts` | 16 处各写各的 `listUsers({page,size})` 才会"少一处就漏一处"；收敛后准入修复只依赖一个模块，且能静态审计。 |
| D3 | 导航函数 `navVisible()` 是**侧栏与顶栏唯一判定**；`functionCode` 变为**必填**，登录可读页写哨兵 `AUTHENTICATED` | 现在的三态（省略=公共 / 有码=判定 / 顶栏特判）就是"菜单显示点开 403"的来源；必填 + 哨兵让漏写编译期就红。 |
| D4 | 路由 `meta.functionCode` 与 nav 同源：nav 码必须是该路由 meta 码的子集 | 入口守卫与菜单判定同源，才不会一边隐藏一边可开（AC-8）。 |
| D5 | 厂商账号**不渲染也不放行**"用例库"（`internalOnly`），不改任何权限码 | 用例库对厂商永远为空（厂商不作用例），空页面即谎；用展示层策略而非新权限码解决 AC-9 的 vendor1 断言。 |
| D6 | 演示数据重置走 req-service 内部端点 `POST /internal/demo-reset`（`X-Internal-Token` + `PLATFORM_SEED_ENABLED`），单事务、失败回滚 | 与 `SeedService` 同域（seed 已经写 testPlan/testCase），复用 `SysDb` 避开 lint L9；SQL 脚本方式会复制一套种子逻辑并与外键顺序耦合。 |
| D7 | `Makefile` 只做薄封装：`platform-demo-reset` / `access-verify` 两个 target 归**同一个任务**（T9） | 一个文件两个属主必冲突；两个 target 都是一行 `@./scripts/...`。 |

**显式不做**：不加/删权限码；不改 `PermissionMatrix`；不放宽 `/admin/*`；不动 Flyway（无 V10）；不引第三方依赖/前端测试框架（导航审计用 Node 脚本，仓内已有 Node e2e 先例）。

---

## 1. 现状与复用面（证据）

| 位置 | 事实 | 对本设计的作用 |
|---|---|---|
| `platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java` | first-match-wins；`^/admin/(orgs\|vendors\|domains\|users\|dicts)` → `org:manage`；`^/test-plans` → `plan:manage`；`^/code/policies` → `CCODE_POLICY_VIEW`；`^/releases` → `release:plan`；`^/(testcases\|test-modules)` → `case:manage`；`^/(tasks\|sprints)` → `task:manage`；`/dictionaries/*` 无任何规则 | 字典端点天然"仅认证"，无需改路由表；5 格 ⛔ 由此表 + 矩阵决定 |
| `platform-common/.../security/PermissionInterceptor.java` | `requirement == null` → 放行；DENY → `ApiException(403,"FORBIDDEN")`（带 functions/path/method） | 字典端点匿名仍 401（由 `AuthFilter` 拦），登录即 200 |
| `req-service/.../sys/AdminDomainController.java` | `SysDb`（EntityManager）读 `biz_domain`（`tenant_id/code/name/enabled/sort_no`） | 字典 SQL 同表同列，只裁字段 |
| `req-service/.../sys/AdminVendorController.java` | `sys_vendor(tenant_id, code, name, contact, status)` | 启用厂商 = `status='ACTIVE'` |
| `req-service/.../sys/AdminUserController.java` | `sys_user(tenant_id, username, name, org_id, vendor_id, status)`；分页 1/20 | 字典不分页，改 `ORDER BY id LIMIT 1000` |
| `req-service/src/main/resources/db/migration/V1__platform_schema.sql:127` | `sys_user_domain(tenant_id, user_id, domain_id, ...)` | 厂商域的授权域来源（不猜 scope 推导） |
| `web/src/api/client.ts` | 403 分支已由 `!options.silentForbidden` 守卫；401 逻辑独立 | AC-4 **不改代码**，只做静态断言 + 字典模块声明选项 |
| `web/src/stores/auth.ts` | `normalizePermissions` 收 `access !== 'DENY'`（含 RESTRICTED）→ `permissionSet`；`canAny` 任一命中 | 菜单可见性 = 非 DENY，与后端放行口径一致 |
| `web/src/layouts/nav.ts` | 33 条侧栏（10 条无 functionCode）+ `TOP_NAV` 8 项（0 条有码）；`TopNav.vue` 内联 admin 特判 | 必填改造的直接对象 |
| `web/src/router/index.ts` | guard 只看 `meta.functionCode`；无码路由人人可开 | 路由 meta 不补齐 = 菜单与守卫不同源 |

---

## 2. 数据模型与存储

**零迁移。** 本轮不建表、不加列、不建索引：

- 字典三查询命中的都是既有小表（`biz_domain` 7 行 / `sys_vendor` 3 行 / `sys_user` 12 行），`WHERE tenant_id` 前缀走既有索引，`ORDER BY id` 对 7/3/12 行无排序成本，因此**不新增索引**（AC-1 的 1000 上限已把最坏行数钉住）。
- 演示数据重置只做行级 DELETE/INSERT（§6.2），不改 schema、不动 Flyway 版本。
- `DemoFixture` 的数据是**代码常量**（沿用 `SeedService` 的 `String[][]` 风格），不引入 SQL 种子文件。

读取关系（只读）：

```
biz_domain(tenant_id, enabled) ──┐
sys_user_domain(user_id,domain_id)┴─→ 厂商账号可见域
sys_vendor(tenant_id, status='ACTIVE') ─→ 内部账号可见厂商 / 厂商账号仅自身
sys_user(tenant_id, status='ACTIVE', vendor_id) ─→ 人员选项
```

---

## 3. 后端设计

### 3.1 新增端点（`req-service`，路径挂在 `/dictionaries` 下，网关前缀 `/api/req`）

| 方法/路径 | 鉴权 | 返回 | 备注 |
|---|---|---|---|
| `GET /api/req/dictionaries/domains` | 登录即可（无功能码） | `[{"id":1,"code":"DOM-A","name":"…"}]` | 数组、按 `id` 升序 |
| `GET /api/req/dictionaries/vendors` | 同上 | `[{"id":1,"code":"VEN-A","name":"…"}]` | 数组、按 `id` 升序 |
| `GET /api/req/dictionaries/users` | 同上 | `[{"id":1,"username":"admin","name":"平台管理员"}]` | 数组、按 `id` 升序、`LIMIT 1000` |

字段白名单由 **record 形状**保证（Jackson 只序列化 record 分量），不做 `Map` 拼装：

```java
// req-service/src/main/java/com/example/req/sys/DictionaryViews.java
public final class DictionaryViews {
    public record DomainOption(long id, String code, String name) {}
    public record VendorOption(long id, String code, String name) {}
    public record UserOption(long id, String username, String name) {}
}
```

错误语义（不新增错误码）：

| 场景 | 期望 | 由谁产生 |
|---|---|---|
| 匿名 / 过期 token | `401 UNAUTHORIZED` | `AuthFilter`（既有） |
| `X-Tenant-Id` 与 JWT 租户不一致 | `403` | 网关 `JwtGatewayFilter`（既有） |
| `/dictionaries/unknown` | `404` | 无映射，Boot 默认（不写 catch-all，避免吞掉真实 404） |
| 非管理员访问 `GET /api/req/admin/domains` | `403 FORBIDDEN`（原样） | `PermissionInterceptor`（不改） |

### 3.2 裁剪口径（`DataScope` 单一来源）

`DataScope scope = TenantContext.require()`；**厂商边界 = `scope.vendorId() != null`**（与 `DataScope` 的构造契约一致；`tier()` 只作日志/断言辅助，不作为分支依据，避免两套判据漂移）。

| 端点 | 内部账号（`vendorId == null`） | 厂商账号（`vendorId != null`） |
|---|---|---|
| domains | `WHERE d.tenant_id=? AND d.enabled=1 ORDER BY d.id` | 同左 + `JOIN sys_user_domain ud ON ud.domain_id=d.id AND ud.user_id=? AND ud.tenant_id=d.tenant_id`（授权域 ∩ 启用域） |
| vendors | `WHERE tenant_id=? AND status='ACTIVE' ORDER BY id` | 同左 + `AND id=?`（`scope.vendorId()`） |
| users | `WHERE tenant_id=? AND status='ACTIVE' ORDER BY id LIMIT 1000` | 同左 + `AND vendor_id=?` |

要点：

1. 租户**只取 JWT**（`scope.tenantId()`），绝不读请求参数/头；`X-Tenant-Id` 只做网关一致性校验。
2. 厂商域用 `sys_user_domain` 显式 join 而不是 `scope.domainIds()`：前者是 AC-2 点名的表，后者是派生结果，join 让"授权域∩启用域"在一个 SQL 里可读可测。
3. `domainIds` 为空 → 返回 `[]`（不是 500）；前端按空态降级（§4.2）。
4. `users` 上限 1000 是**契约**：超限时按 `id` 截断并在日志留一条 `warn`，不静默分页。

### 3.3 路由表：不改，但加断言

`PermissionRouteTableTest` 追加（同文件，不新建测试类）：

```java
assertNull(table.requirementFor("GET", "/dictionaries/domains"));
assertNull(table.requirementFor("GET", "/dictionaries/vendors"));
assertNull(table.requirementFor("GET", "/dictionaries/users"));
assertEquals(List.of("org:manage"), table.requirementFor("GET", "/admin/domains").codes());
```

理由：`/dictionaries/*` 的"仅认证"是**故意**的，必须有一条单测钉住——否则后人补一条 `^/dictionaries` 规则就静默把演示打回原形。

### 3.4 类结构

```java
@RestController
@RequestMapping("/dictionaries")
@Transactional(readOnly = true)
public class DictionaryController {
    private final SysDb db;
    public DictionaryController(SysDb db) { this.db = db; }

    @GetMapping("/domains") public List<DictionaryViews.DomainOption> domains() { … }
    @GetMapping("/vendors") public List<DictionaryViews.VendorOption> vendors() { … }
    @GetMapping("/users")   public List<DictionaryViews.UserOption> users()   { … }
}
```

不使用 `JdbcTemplate`（lint L9 只允许 `ScopedJdbc`/`SeedService` 家族）；与 `Admin*Controller` 同包同风格。

---

## 4. 前端设计

### 4.1 `web/src/api/dictionaries.ts`（唯一入口）

```ts
export interface DictionaryDomain { id: number; code: string; name: string }
export interface DictionaryVendor { id: number; code: string; name: string }
export interface DictionaryUser   { id: number; username: string; name: string }
export interface DictionaryBundle {
  domains: DictionaryDomain[]; vendors: DictionaryVendor[]; users: DictionaryUser[]
}

export function listDictionaryDomains(): Promise<DictionaryDomain[]>   // http.get(`${DICTS}/domains`, undefined, { silentForbidden: true })
export function listDictionaryVendors(): Promise<DictionaryVendor[]>   // http.get(`${DICTS}/vendors`, undefined, { silentForbidden: true })
export function listDictionaryUsers():   Promise<DictionaryUser[]>     // http.get(`${DICTS}/users`,   undefined, { silentForbidden: true })
export function loadDictionaries(): Promise<DictionaryBundle>          // Promise.all，三个各自 catch(() => [])
```

- `const DICTS = '/req/dictionaries'`，与 `api/admin.ts` 的 `ADMIN = '/req/admin'` 同前缀；`a3-nav-audit.mjs` 断言两者前缀一致（防止网关前缀改动只改一处）。
- **每个请求都带 `silentForbidden: true`**：字典 403 只能 reject，不能把用户甩到 `/403`（那正是本需求要修的故障形态）。
- `loadDictionaries()` 是**降级点**：三项独立 catch，任一失败只丢该项；页面拿到空数组后渲染空态/隐藏该筛选，绝不升级为页面失败。

### 4.2 迁移清单（16 文件 / 18 调用点）

| 文件 | 现在 | 改为 |
|---|---|---|
| `web/src/views/req/platform.ts` | 3 个 ref + `Promise.all([listDomains, listVendors, listUsers({page,size})])` | `loadDictionaries()`，ref 类型换 `Dictionary*` |
| `web/src/views/req/RequirementCreateView.vue` | `listDomains()` | `listDictionaryDomains()` |
| `web/src/views/req/RequirementDetailView.vue` | `listDomains/listVendors` | `listDictionaryDomains/listDictionaryVendors` |
| `web/src/views/req/tabs/ApprovalTab.vue` | `listUsers({page,size})` | `listDictionaryUsers()` |
| `web/src/views/approval/ApprovalCenterView.vue:17,372` | `import { listUsers } from '@/api/admin'` + `listUsers({page:1,size:100})` | `listDictionaryUsers()` |
| `web/src/views/release/ReleaseListView.vue` | `listVendors/listUsers` | 同名替换 |
| `web/src/views/deliver/DefectListView.vue` | `listVendors/listUsers` | 同上 |
| `web/src/views/deliver/DefectDetailView.vue` | `listUsers` | 同上 |
| `web/src/views/deliver/DefectBoardView.vue` | `listVendors/listUsers` | 同上 |
| `web/src/views/deliver/TestPlansView.vue` | `listUsers` | 同上 |
| `web/src/views/deliver/TestPlanDetailView.vue` | `listUsers/listDomains` | 同上 |
| `web/src/views/deliver/TestPlanExecuteView.vue` | `listUsers` | 同上 |
| `web/src/components/deliver/CaseReviewPanel.vue` | `listUsers` | 同上 |
| `web/src/views/quality/TestEnvsView.vue` | `listUsers` | 同上 |
| `web/src/views/quality/QualityMetricsView.vue` | `listVendors/listDomains` | 同上 |
| `web/src/views/code/CodePolicyView.vue` | `listUsers/listDomains` | 同上 |

调用形状变化只有两种：`listUsers({page:1,size:200}).then(r => r.items)` → `listDictionaryUsers()`（已是数组）；`listDomains()` → `listDictionaryDomains()`。**渲染与降级逻辑一行不动**。

`web/src/views/admin/{AdminUsersView,AdminVendorsView}.vue` 保持 `api/admin.ts`（分页/管理字段/权限都不该被字典污染）。

静态断言（`access-verify` 的 a3 段）：

1. `listDomains|listVendors|listUsers` 的出现文件 ⊆ `web/src/api/admin.ts`、`web/src/api/dictionaries.ts`、`web/src/views/admin/*`；
2. `web/src/api/admin.ts` 之外，`views|components|api` 下不得出现 `from '@/api/admin'` 中的 `list*` 导入；
3. `dictionaries.ts` 中三个 `http.get` 均带 `silentForbidden: true`（正则计数 = 3）；
4. `client.ts` 的 403 分支仍是 `if (!options.silentForbidden)`（AC-4 唯一开关）。

### 4.3 导航单一判定：`web/src/layouts/navAccess.ts`（新增）

```ts
export const AUTHENTICATED = 'AUTHENTICATED'
export type FunctionCode = string | string[]
export interface NavGuard { routeName?: string; functionCode: FunctionCode; internalOnly?: boolean }
export interface NavContext { hasRoute(name: string): boolean; canAny(codes: string[]): boolean; vendorScoped: boolean }

export function navVisible(item: NavGuard, ctx: NavContext): boolean {
  if (item.routeName && !ctx.hasRoute(item.routeName)) return false
  if (item.internalOnly && ctx.vendorScoped) return false
  const codes = Array.isArray(item.functionCode) ? item.functionCode : [item.functionCode]
  if (codes.length === 0) return false
  if (codes.includes(AUTHENTICATED)) return true
  return ctx.canAny(codes)
}
```

- `SideNav.vue` 的 `permitted()` 与 `TopNav.vue` 的 `navItems` **都改为调用它**（TopNav 的 admin 特判整段删除）。
- `AUTHENTICATED` 必须在 `canAny` **之前**处理：`canAny(['AUTHENTICATED'])` 恒 false，顺序错了就是"所有公共页消失"。
- `NavItem.functionCode` 改为**必填**（`NavGuard & { label; to; icon; … }`），`TopNavItem` 增加 `functionCode` + 可选 `internalOnly` → 漏写即 `vue-tsc` 报错（AC-7 的编译期保险）。

### 4.4 `nav.ts` 全量函数码（33 侧栏 + 8 顶栏）

| 分组 | 条目 | routeName | functionCode |
|---|---|---|---|
| 我的 | 我的待办 | approvals | `AUTHENTICATED` |
| 我的 | 我的任务 | requirements | `AUTHENTICATED` |
| 我的 | 工作台 | workbench | `['plan:execute','case:manage']`（不变） |
| 我的 | 通知 | notifications | `AUTHENTICATED` |
| 需求域 | 需求池 | requirements | `AUTHENTICATED` |
| 需求域 | 提报需求 | requirement-create | `['req:report:bus','req:report:dev']`（不变） |
| 需求域 | 版本计划 | releases | `release:plan` |
| 需求域 | 迭代 | iterations | `task:manage` |
| 交付域 | 缺陷 | defects | `AUTHENTICATED` |
| 交付域 | 用例库 | testcases | `case:manage` + `internalOnly: true` |
| 交付域 | 测试计划 | test-plans | `plan:manage` |
| 交付域 | 测试策略 | test-strategies | `strategy:manage`（不变） |
| 交付域 | 测试报告 | test-reports | `['report:generate','report:sign']` |
| 交付域 | 环境台账 | test-envs | `env:manage`（不变） |
| 交付域 | 执行器 | test-executors | `TEST_EXECUTOR_VIEW`（不变） |
| 交付域 | 发布单 | deploy-orders | `deploy:create`（不变） |
| 协作 | 甘特 / Wiki / 价值流 | gantt / wiki / value-stream | `GANTT_VIEW` / `WIKI_VIEW` / `FLOW_VIEW`（不变） |
| 分析域 | 追溯矩阵 | traceability | `trace:view`（不变） |
| 分析域 | 质量门总览 | quality-gates | `gate:view`（不变） |
| 分析域 | 质量指标 | quality-metrics | `qualitymetric:view`（不变） |
| 分析域 | 质量看板 | defect-board | `defect:board`（不变） |
| 分析域 | 分支策略 | code-policies | `CCODE_POLICY_VIEW`（不变） |
| 设置 | 组织与租户 | admin-orgs | `['tenant:manage','org:manage']`（不变） |
| 设置 | 用户与角色 / 厂商与域 / 权限矩阵 | admin-users / admin-vendors / admin-permissions | `org:manage`（不变） |
| 设置 | 流程定义 | admin-workflows | `workflow:manage` |
| 设置 | API 客户端 | admin-api-clients | `apiclient:manage` |
| 设置 | 集成配置 | admin-integrations | `integration:manage` |
| 设置 | 指标字典 | admin-metrics | `metric:manage` |
| 设置 | 审计日志 | admin-audit | `audit:view` |

顶栏 8 项（各自绑**落地路由**的码，宁窄不宽，避免"点了才 403"）：

| key | to | routeName | functionCode |
|---|---|---|---|
| requirements | /requirements | requirements | `AUTHENTICATED` |
| releases | /releases | releases | `release:plan` |
| approvals | /approvals | approvals | `AUTHENTICATED` |
| deploy | /deploy-orders | deploy-orders | `['deploy:create','deploy:execute','deploy:force']` |
| defects | /defects | defects | `AUTHENTICATED` |
| testing | /test-plans | test-plans | `plan:manage` |
| trace | /traceability | traceability | `trace:view` |
| admin | /admin/orgs | admin-orgs | `['tenant:manage','org:manage']` |

> 顶栏「设置」原特判含 `workflow:manage`，而落地页 `/admin/orgs` 只要 `org:manage` → PMO 看见「设置」点开 403。改成与落地页一致是 AC-8 的必修项（PMO 仍可从侧栏「设置」组进流程定义）。

### 4.5 路由 meta 对齐（5 条改 + 1 条新增判定）

| 文件 | 路由 | 追加 `meta.functionCode` |
|---|---|---|
| `web/src/router/routes/flow.routes.ts` | `/releases` | `'release:plan'` |
| `web/src/router/routes/flow.routes.ts` | `/approvals` | 不写（= `AUTHENTICATED` 等价） |
| `web/src/router/routes/deliver.routes.ts` | `/test-plans` | `'plan:manage'` |
| `web/src/router/routes/deliver.routes.ts` | `/testcases` | `'case:manage'` + `meta.internalOnly: true` |
| `web/src/router/routes/deliver.routes.ts` | `/iterations` | `'task:manage'` |
| `web/src/router/routes/quality.routes.ts` | `/test-reports` | `['report:generate','report:sign']` |

`web/src/router/index.ts` 的 `beforeEach` 增加一条（放在函数码判定之前）：

```ts
if (to.meta.internalOnly && auth.user?.vendorId != null) {
  return { path: '/403', query: { from: to.fullPath, code: 'INTERNAL_ONLY' } }
}
```

**行为变化（有意）**：这 5 个入口的 403 从"接口 403 后被全局跳转"提前到"入口守卫 403"，用户看到的页面与深链行为不变（仍是全局 403 页），但少一次必然失败的请求；`/test-plans` 对 po1/dev1 的 ⛔ 语义保持（AC-5）。

`/defects`、`/notifications`、`/requirements` 等 `AUTHENTICATED` 页**不写** meta 码——静态审计把"无 meta 码"与哨兵视为同一类。

### 4.6 状态管理

- **不新增 store**：字典仍是"页面级缓存"（`views/req/platform.ts` 模块级 ref + `loaded/pending` 去重，`usePlatformDict().ensure()`），换租户 = 重新登录 = 整页刷新，缓存自然失效（不做跨租户缓存失效逻辑，避免引入第二套租户态）。
- **auth store 不动**：`permissionSet` 已含 RESTRICTED（`access !== 'DENY'`），与后端"RESTRICTED 放行、ScopeSql 收窄"一致；`navVisible` 只消费 `canAny`。
- 导航判定是**纯函数 + 每次渲染计算**（`computed`），不做事件订阅，避免权限刷新后菜单不更新。

---

## 5. 准入矩阵与 AC-9 核对

5 页 × 7 角色（GE = 200，403 = 403），探测端点取各页首屏请求：

| 页面 | 探测端点 | admin | pm | tl1 | po1 | dev1 | qa1 | vendor1 | 触发码 |
|---|---|---|---|---|---|---|---|---|---|
| 需求池 | `GET /api/req/requirements` | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 无（仅认证） |
| 版本计划 | `GET /api/req/releases` | 200 | 200 | 200 | 200 | 200 | 200 | 200 | `release:plan`（非 DENY 即放行） |
| 审批中心 | `GET /api/req/approvals/todo?page=1&size=5` | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 无 |
| 测试计划 | `GET /api/deliver/test-plans?page=1&size=5` | 200 | 200 | 200 | **403** | **403** | 200 | 200 | `plan:manage` |
| 分支策略 | `GET /api/req/code/policies` | 200 | **403** | 200 | **403** | 200 | **403** | 200 | `CCODE_POLICY_VIEW` |

= 30 ✅ / 5 ⛔，与 PRD AC-5 完全一致，**新增 0 个权限码**。

AC-9 逐角色核对（由 `navVisible` + 矩阵推导，a4 段逐条断言）：

| 角色 | 隐藏（推导） | 与 AC-9 一致性 |
|---|---|---|
| po1 | 用例库、测试计划、测试报告、分支策略、价值流、管理后台（6） | ✅ 全一致 |
| dev1 | 测试计划、测试报告（+ 迭代/用例库？见下） | ⚠️ 1 处偏差 |
| qa1 | 甘特、分支策略、价值流、管理后台（4） | ✅ |
| pm | 分支策略、管理后台（2） | ✅ |
| tl1 / vendor1 | 管理后台；vendor1 另隐藏用例库 | ✅（`internalOnly`） |
| admin | 无 | ✅ |

**唯一偏差（写进 §10 待澄清，不硬编码补救）**：AC-9 写"dev1 隐藏用例库"，但矩阵里 `DEV` 对 `case:manage` 是 **RESTRICTED**（`PermissionMatrix.java` DEV 行 `restrict(... "case:manage" ...)`），语义就是"入口保留、数据范围收窄"——若隐藏，就变成"有权限但菜单不显示"（同样违反 AC-8），且 `GET /testcases` 对 dev1 是 200。**本设计按矩阵执行：dev1 可见用例库（仅自己范围）**，`access-verify` 断言推导值，QA 侧以矩阵为准。

---

## 6. 种子与演示数据

### 6.1 两个新账号（`SeedService`，T7）

`userDefs` 追加两行（4 字段 `{username, name, role, vendorCode}`）：

```java
{"biz1",   "陈业务（业务部门）",     "BUSINESS",           null},
{"suite1", "许二级（二级企业业务）", "SUB_BUSINESS_LEADER", null},
```

- 循环体已把每个账号 `link("sys_user_domain", …)` 绑到**全部 7 域**，故 biz1/suite1 与 po1 的域授权天然同源、同租户（t1）、同组织（supplyOrg）→ 不写额外代码。
- `SeedResult.loginUsers()` 由 10 条改 12 条（`biz1:Passw0rd!`、`suite1:Passw0rd!`）；`counts.loginUsers` 随之为 12，`platform-seed.sh` 的 `C1 == C2` 仍成立（列表是常量，不随数据变）。
- `scripts/platform-seed.sh`：`SEEDED_USERS` 10 → 12，文案改"12 个种子账号齐备（tenant 内共 N 个用户）"，**保留**"只校验种子账号存在、不校验租户用户总数"的口径（`WHERE username IN (...)` 名单加两人）。
- biz1 能提交 BUSINESS 需求：`POST /requirements` 的 anyOf（`req:report:bus`/`req:report:dev`）+ BUSINESS 的 `req:report:bus` ALLOW，已满足；需求池列表无码，写入后立即可见（BIZ 范围）。

### 6.2 `platform-demo-reset`（T8）

**入口**：`POST /internal/demo-reset`（`DemoResetController`，`X-Internal-Token` + `PLATFORM_SEED_ENABLED`，关闭时 404，与 `SeedController` 同款守卫）+ `scripts/platform-demo-reset.sh` + `make platform-demo-reset`。

**算法**（`DemoResetService.reset()`，`@Transactional`，失败整体回滚）：

1. 解析租户（默认 `t1`，可用 `PLATFORM_TENANT` 覆盖）。
2. **删子表 → 删父表**：按 `DemoFixture.DELETE_ORDER`（单一常量列表，含审批实例/任务、需求时间线/关联、测试计划-用例、执行记录、缺陷、甘特行/组、通知、需求、版本、迭代、用例、测试计划、缺陷）。每条 `DELETE … WHERE tenant_id = ?`，绝不 `TRUNCATE`/`DROP`。删除顺序以 V1–V9 迁移的**外键引用**为准；漏表会由外键错误在事务内暴露（回滚 + 非 0 退出，正是 AC-13 要求的"失败原因明确"）。
3. **插入固定小数据集**（`DemoFixture` 常量）：需求 12（8 属 po1 组织、3 挂 `VEN-A`、1 挂 `VEN-C`，code 前缀 `DEMO-R-`）、版本 1、迭代 1、用例 12（`DEMO-C-`）、缺陷 3（`DEMO-D-`）、测试计划 1（≥10 用例）、甘特 1 组 8 行、通知 ≤5。全部用 `ensure/insert` 以 `(tenant_id, code)` 为自然键，**连跑两次结果一致、无唯一键冲突**。
4. **不变量自检**（服务内，违反即抛 409 `DEMO_RESET_INVARIANT` 触发回滚）：需求总数 12、`po1` 可见 8、`vendor1` 可见 3、`vendor2` 可见 0、夹具痕迹计数全 0（`code LIKE 's1-%'|'s5-%'|'s7-%'|'neg-sm-%'`、`actual_days > 30`、甘特逾期、`99+` 通知）。
5. **响应**：`{"tenantCode":"t1","counts":{…表名→行数…},"resetAt":"…"}`；脚本把它渲染成"表名 行数"对齐表打印（AC-13 的计数表），并用 `db_q` 复核 3 条隔离基线。

**约束**：不进 `make test` / `platform-up` / `platform-seed`（AC-14）；只删行、不动 `sys_user/sys_role/sys_user_role/sys_user_domain/sys_vendor/biz_domain/sys_org/流程定义/指标定义/字典`；不碰 Flyway。

---

## 7. 关键实现点与边界

1. **字典端点绝不能加功能码**：AC-1 的"登录可读"就是修复本身；单测钉住（§3.3）。
2. **`silentForbidden` 是字典的护栏，不是全局默认**：全局默认一改，AC-4（默认跳 403）就破；只在字典/能力探测声明。
3. **`AUTHENTICATED` 先于 `canAny` 判定**（§4.3），且**禁止**把哨兵塞进权限码集合参与 `canAny`。
4. **空分组消失**沿用现有 `visibleGroups` 过滤；`internalOnly` 只作用于单条，不影响分组标题。
5. **厂商账号 domains 可能为空**：`RequirementCreateView` 至少选一个域 → 空态应禁用提交并提示"当前账号未授权任何业务域"（不新增错误码，用本地校验）。
6. **users 截断 1000**：超限时被截断的人员不在下拉里；文档标注为已知边界（本轮不做搜索型人员选择器）。
7. **深链不回归**：新增 5 条 meta 码后，`/test-plans/:id` 等详情页保持 `parentPath`，无权限仍落全局 403 页并有"返回工作台"。
8. **403 页不循环**：`ForbiddenView` 的返回目标用 `auth.homeRoute`，若 `homeRoute` 本身无权限（极端配置）则退到 `/requirements`（`AUTHENTICATED`，必然可开）。
9. **顶栏「测试」对 dev1 消失**：其落地页是 `/test-plans`（dev1 DENY）。dev1 从侧栏进用例库，属"顶栏按落地页准入"的必然结果，不视为缺陷。
10. **`meta.internalOnly` 只放 `/testcases`**：厂商账号深链 → 403（`code=INTERNAL_ONLY`），不产生新权限码。
11. **演示重置与并行开发互斥**：脚本开头检测 `req_ready`，栈未起直接非 0 退出（不 SKIP、不隐式起栈）。
12. **seed 幂等与 demo-reset 的顺序**：`platform-demo-reset` 后 `platform-seed` 仍须幂等通过（`ensure` 命中即跳过）——AC-14 的门禁由 T9 在 access-verify 之外手工/脚本验证一次并留结论。

---

## 8. 测试策略

### 8.1 `make access-verify`（新门禁，≥45 断言，实际 ~85）

`scripts/access-verify.sh` 为聚合器（复用 `lib.sh` 的 `section/pass/fail/expect/platform_login/db_q`，末尾 `PASS == TOTAL` 守卫），先做栈检查：

```bash
req_ready && web_up || die "平台栈未就绪：先执行 make platform-up"
```

| 段 | 文件 | 断言 | 内容 |
|---|---|---|---|
| a0 | `scripts/tests/access/a0-dictionary-contract.sh` | 29 | 匿名 401 ×3；7 角色 ×3 端点 200 ×21；三端点 JSON 键集合 `{id,code,name}`/`{id,username,name}` ×3；未知子路径 404 ×1；vendor1 调 `/api/req/admin/domains` 403 ×1 |
| a1 | `scripts/tests/access/a1-isolation.sh` | 8 | admin 域集合 == `/admin/domains` 启用集；po1 域 ⊆ 启用集；vendor1 vendors == `[VEN-A]`；vendor2 users 全属 `VEN-B`；po1 users 无跨租户；`X-Tenant-Id` 不符 403；域/厂商/人员条数 >0；厂商 domains ⊆ admin 域集 |
| a2 | `scripts/tests/access/a2-page-matrix.sh` | 35 | §5 的 5 页 ×7 角色（GE/403 精确匹配，⛔ 必须 403） |
| a3 | `scripts/tests/access/a3-nav-audit.mjs` | 1（内含 6 规则） | nav/TOP_NAV 每项有 `functionCode`；值域 ⊆ 56 码 ∪ `{AUTHENTICATED}`；nav 码 ⊆ 对应路由 meta 码；`DICTS` 与 `ADMIN` 前缀一致；三个 `http.get` 带 `silentForbidden`；`client.ts` 403 分支仍受 `!options.silentForbidden` 守卫 |
| a4 | `scripts/tests/access/a4-nav-visibility.mjs` | 14 | 7 角色登录取 `permissions`，用 `navVisible` 的同一算法算可见集：每角色 ≥2 条（关键隐藏 + 关键可见），含 po1 隐藏 6 条、qa1 隐藏 4 条、vendor1 隐藏用例库/管理后台、admin 五页入口全可见 |

部署：`Makefile` 加 `access-verify: ## …` 一行封装；`scripts/test-all.sh` 在 `platform-verify` 之后、`platform-test-negative` 之前插入 `run_step "角色×页面准入（access-verify）" bash "$ROOT/scripts/access-verify.sh"`（AC-16："在 platform-up 后执行"）。

负向（AC-16 明确）：未登录调字典 401（a0）、非管理员调 `/admin/domains` 仍 403（a0）。

### 8.2 回归门禁（一条不许红）

`make lint`（16 项）→ `make platform-unit`（≥234）→ `make verify`（14）→ `make platform-verify` → `make platform-test-negative`（25）→ `make quality-verify`（348）→ `make integration-verify`（230）→ `make collab-verify`（263）→ `make access-verify`（新）→ `make platform-seed` ×2 → `cd web && npm run typecheck`（0 错）→ `npm run build`。

其中 lint 的 L2（`$VAR` 后不接宽字符）、L3（`own_jvm_pids/kill_own_jvms/chain_ok` 单点）、L9（`JdbcTemplate` 出 `ScopedJdbc`/`SeedService`）直接影响新脚本与新 Java 类，写的时候就避。

### 8.3 手工抽检（QA 阶段）

7 角色 × 5 页登录点一遍（`admin/pm/tl1/po1/dev1/qa1/vendor1` + 新增 `biz1/suite1`），断言"页面能开且筛选器有选项"（需求池 7 域 / 3 厂商）；无权限深链落 403 页并能返回；有权限深链刷新仍正常。

---

## 9. 任务拆分（文件边界，可并行）

> **Git 约束（PRD §8，必须随任务落地）**：基线 `main @ ea877fa`；开发分支由 host 分支策略从最新 main 起 `feat/role-page-access-fix`；提交信息 `feat: 修复非管理员角色页面准入（runId=tf-mw9yzocw-5kv3pt，任务夹 docs/teamflow/20261110-r7-role-page-access-fix）`；4 处既有未提交改动（本任务夹、`docs/teamflow/DEMO-BY-ROLE.{md,html}`、`docs/teamflow/demo-shots/`）随本需求一并提交，**不夹带无关产物**；日志/截图只落 `logs/teamflow/tf-mw9yzocw-5kv3pt/`。

| # | 任务 | 文件（独占写） | 依赖 | 验收 |
|---|---|---|---|---|
| T1 | 后端字典端点 | `req-service/.../sys/DictionaryController.java`、`DictionaryViews.java`、`PermissionRouteTableTest.java` | — | 3 端点 200/401/404、字段白名单、裁剪 4 条、单测绿 |
| T2 | 前端字典单一入口 | `web/src/api/dictionaries.ts`、`web/src/views/req/platform.ts` | — | `loadDictionaries` 三路降级；`typecheck` 绿 |
| T3 | 需求/版本/审批视图迁移 | `RequirementCreateView.vue`、`RequirementDetailView.vue`、`tabs/ApprovalTab.vue`、`ApprovalCenterView.vue`、`ReleaseListView.vue` | T2 | 无 `list*` 残留；页面渲染不变 |
| T4 | 交付域视图迁移 | `DefectListView.vue`、`DefectDetailView.vue`、`DefectBoardView.vue`、`TestPlansView.vue`、`TestPlanDetailView.vue`、`TestPlanExecuteView.vue`、`components/deliver/CaseReviewPanel.vue` | T2 | 同上 |
| T5 | 质量/代码视图迁移 | `TestEnvsView.vue`、`QualityMetricsView.vue`、`CodePolicyView.vue` | T2 | 同上 |
| T6 | 导航与路由准入 | `layouts/navAccess.ts`、`layouts/nav.ts`、`layouts/SideNav.vue`、`layouts/TopNav.vue`、`router/index.ts`、`router/routes/{flow,deliver,quality}.routes.ts` | — | 33+8 项有码；5 条 meta 对齐；`internalOnly` 生效 |
| T7 | 种子两账号 | `seed/SeedService.java`、`seed/SeedResult.java`、`scripts/platform-seed.sh` | — | 12 账号可登录；seed 两遍一致 |
| T8 | 演示数据重置 | `seed/DemoFixture.java`、`seed/DemoResetService.java`、`seed/DemoResetController.java`、`scripts/platform-demo-reset.sh` | T7（读） | 12 需求小链路；连跑两次；不变量 4 条；失败回滚 |
| T9 | access-verify 门禁 | `scripts/access-verify.sh`、`scripts/tests/access/{a0-dictionary-contract.sh,a1-isolation.sh,a2-page-matrix.sh,a3-nav-audit.mjs,a4-nav-visibility.mjs}`、`scripts/test-all.sh`、`Makefile` | T1/T2/T6/T7 | ≥45 断言全绿；栈未起非 0 |
| T10 | 演示文档 | `docs/teamflow/DEMO-BY-ROLE.md`（§1/§3/§6）、`DEMO-BY-ROLE.html`、`demo-shots/` | T7 | 账号表 12 行；菜单/准入描述与实现一致；不动工作台/报表段 |
| T11 | 交付自查（最后跑） | 无独占（发现即修，记结论） | 全部 | lint/unit/typecheck/build + `platform-up` 后 `access-verify` 冒烟 |

并行度：T1、T2、T6、T7 同时起；T3/T4/T5 在 T2 落文件后并行；T8 在 T7 后；T9 可在 T1/T6 契约确定后并行编写、最后执行；T11 收口。

---

## 10. 风险与待澄清

| # | 风险/分歧 | 处置 |
|---|---|---|
| R1 | **AC-9 dev1 隐藏用例库**与矩阵 `DEV: case:manage=RESTRICTED` 冲突（隐藏即"有权限菜单不显示"） | 按矩阵执行（dev1 可见），a4 断言推导值；QA 若按字面判 AC-9 需与产品确认（建议回写 PRD：dev1 的隐藏清单去掉用例库） |
| R2 | 顶栏「设置」收窄为 `org:manage` 后，PMO 不再从顶栏进设置（原先点开必 403） | 有意修复；侧栏「设置」组仍可按各自码进入流程定义等 |
| R3 | 厂商账号 `internalOnly` 属展示层新概念（非权限码） | 只用于用例库一条；`meta.internalOnly` + nav 同源，双处一致；不写入矩阵 |
| R4 | users 1000 上限在超大租户下截断 | 记入已知边界；后续用"搜索型人员选择器"解决，不在本轮 |
| R5 | demo-reset 的删除顺序依赖 V1–V9 外键拓扑 | 顺序收敛在一个常量；运行期 FK 错误 → 回滚 + 非 0；T8 首次执行即在真实库验证 |
| R6 | `/releases`、`/iterations` 等新增入口守卫后，RESTRICTED 角色仍可进（非 DENY） | 与后端一致；a2 断言的正是 "非 DENY → 200" |
| R7 | 既有 4 处未提交改动会混进本轮 diff | 明确随本需求提交（PRD §8），提交前 `git status` 复核不含其他产物 |

---

## 11. 文件清单

**新增（10）**：`DictionaryController.java`、`DictionaryViews.java`、`DemoFixture.java`、`DemoResetService.java`、`DemoResetController.java`、`web/src/api/dictionaries.ts`、`web/src/layouts/navAccess.ts`、`scripts/access-verify.sh`、`scripts/tests/access/a{0,1,2}-*.sh` + `a{3,4}-*.mjs`、`scripts/platform-demo-reset.sh`

**修改（22）**：`PermissionRouteTableTest.java`、`SeedService.java`、`SeedResult.java`、`platform-seed.sh`、`test-all.sh`、`Makefile`、`nav.ts`、`SideNav.vue`、`TopNav.vue`、`router/index.ts`、`flow.routes.ts`、`deliver.routes.ts`、`quality.routes.ts`、`platform.ts`、15 个视图（§4.2）、`DEMO-BY-ROLE.md`

**不动**：`PermissionMatrix.java`、`PermissionRouteTable.java`（只加测试）、`client.ts`、`api/admin.ts`、`views/admin/*`、Flyway 迁移、`docs/teamflow/architecture/*`、历史任务夹。
