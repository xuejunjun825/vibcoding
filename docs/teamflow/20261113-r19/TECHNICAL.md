# TECHNICAL · 20261113-r19 · RBAC 三层授权收尾

> 基线：[docs/teamflow/20261113-r18-rbac-role-permission/](../20261113-r18-rbac-role-permission/)
> 设计输入：[DESIGN.md](DESIGN.md)（几何/交互以设计为准，本文只写实现与契约）
> 分支：`feat/rbac-admin`（继续使用，不切、不 rebase、不重写 `5b7ba9d`）
> 基线订正（r20 实测，2026-11-14）：§6.3 的 `platform-verify 168` / `platform-test-negative 30` 高于实测，真实基线 **167** / **28**；`access 148` 是当时下限（实测 **152**）；`platform-unit 278` 同为下限（上轮记录 306，本轮未复跑）；`rbac` 101（r20 加 `s2r` 收尾段后 **111**，r21 实测订正：`s2r` 10 条）。只订正数字，契约与 AC 文本不变。

## 1. 结论先行

r18 的骨架已经在仓库里，本轮**不是重画，是补 4 个缺口**：V12 迁移与种子、种子三入口（启动/建租户/重置）、两个前端页面与导航接线、门禁跑绿与证据。

| 层 | 实测现状 | 本轮动作 |
|---|---|---|
| `PermissionService` / `PermissionGrantsRepository` / `PermissionInterceptor` | 已实现，判定三序 + epoch 缓存完整 | **不改**，只由种子供数 |
| `PermissionRouteTable` | 161 个写映射全部有声明，`s9` 8/8 绿 | **不改** |
| 迁移 | 最新 `V11__normalize_severity_codes.sql` | 新增 `V12__rbac_role_permission.sql` |
| 种子 | `RbacSeeder` / `RbacBootstrap` **不存在**；`TenantProvisioner.createRoles` 只插 10 个角色、不写授权行 | 新建 + 接线 |
| 演示重置 | `DemoResetService` 未清理授权行/自定义角色 | 补两步 |
| 角色/授权 API | `/admin/roles` 全套（含 PUT permissions）已实现 | **不改** |
| 前端 | 路由、类型、API、`auth` store、`navAccess` 就绪；两个 `.vue` 缺失；`SideNav`/`TopNav` 未传 `hasMenu`；`AdminLayout` 仍用 `canAny` | 新增 5 个文件、改 5 个文件 |
| 门禁脚本 | `s0–s9`、`a2/a3/a4`、`lint`、`rbac-verify.sh`、`platform-demo-reset.sh` 的工作区改动已就位 | **不再新建检查器**，只跑绿并补证据 |

### 1.1 AC-7 的实现口径（与 PRD 文案的差异，实测修正）

PRD 写「91 个写映射注解 0 处，需一次性补齐」。实测（`node scripts/tests/rbac/s9-declaration-coverage.mjs`）为：**161 个写映射、0 个注解、违规 0 条**——因为声明渠道是 `PermissionRouteTable`（`RULES` 覆盖全部写端点），注解是可选通道而非唯一通道。

本轮据此交付，**不为了凑数字给 91 个方法加注解**：

- 判据改为「每个写端点必须有声明」：注解 **或** 路由表 **或** `PermissionInterceptor.isOpen` 豁免；
- `platform.rbac.fail-fast=true` 保持默认，`PermissionDeclarationValidator` 启动期把未声明端点打成一整条错误并拒绝启动；
- 新端点若无法用现有 `page/action/bodyAction` 表达，才允许补 `@RequiresAction`（本轮新增 0 个写端点，故为 0）。

这不是放宽：`s9` 同时断言 fail-fast 默认 true、`@PageOnly` 必须带 reason、路由表引用的页面/动作码必须已注册、`/admin/permissions` 无写映射。

---

## 2. 数据模型与存储

### 2.1 V12 迁移

文件：`req-service/src/main/resources/db/migration/V12__rbac_role_permission.sql`（Flyway 已断言的版本号 = 12，文件名不可改）

写法规格（与 `V2`/`V11` 同风格）：中文块注释写「背景 / 为什么 / 幂等手法」；建表 `CREATE TABLE IF NOT EXISTS`；加列先查 `information_schema.COLUMNS` 再 `PREPARE/EXECUTE`；不写存储过程；可重放。

**只做 DDL，不插数据**——1680 行由 `RbacSeeder` 从常量反推（`s0` 的失败提示也指向 `RbacBootstrap`/`RbacSeeder.seedTenant`）。

```sql
-- sys_role：已有 code/name/builtin，补 description 与 status
-- status 是「停用即时失权」的开关：PermissionGrantsRepository 的 SQL 里 JOIN 了 r.status='ACTIVE'
ALTER TABLE sys_role ADD COLUMN description VARCHAR(200) NULL AFTER name;
ALTER TABLE sys_role ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' AFTER builtin;

-- 全局注册表快照（无 tenant_id：产品常量，复制到每租户只会漂移）
CREATE TABLE IF NOT EXISTS sys_menu (
  code           VARCHAR(64)  NOT NULL,
  module         VARCHAR(32)  NOT NULL,
  name           VARCHAR(64)  NOT NULL,
  route_name     VARCHAR(64)  NOT NULL,
  function_codes VARCHAR(512) NOT NULL DEFAULT '',
  sort_no        INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页面注册表快照（MenuRegistry 36 行）';

CREATE TABLE IF NOT EXISTS sys_action (
  code           VARCHAR(64)  NOT NULL,
  menu_code      VARCHAR(64)  NOT NULL,
  name           VARCHAR(64)  NOT NULL,
  function_codes VARCHAR(512) NOT NULL DEFAULT '',
  high_risk      TINYINT      NOT NULL DEFAULT 0,
  sort_no        INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (code),
  KEY idx_action_menu (menu_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动作注册表快照（ActionRegistry 72 行）';

CREATE TABLE IF NOT EXISTS sys_role_permission (
  tenant_id    BIGINT      NOT NULL,
  role_id      BIGINT      NOT NULL,
  subject_type VARCHAR(16) NOT NULL COMMENT 'FUNCTION|MENU|ACTION',
  subject_code VARCHAR(64) NOT NULL,
  access       VARCHAR(16) NOT NULL COMMENT 'ALLOW|RESTRICTED|DENY',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rp (tenant_id, role_id, subject_type, subject_code),
  KEY idx_rp_role (tenant_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三层授权：行存在即已配置，DENY 也是行';
```

**形状是硬契约**（`s0` 逐项断言，不能多一列少一列）：

| 表 | 列数 | 断言要点 |
|---|---|---|
| `sys_role` | 增 2 列 | `description`、`status` |
| `sys_menu` | 6 | code/module/name/route_name/function_codes/sort_no |
| `sys_action` | 6 | code/menu_code/name/function_codes/high_risk/sort_no |
| `sys_role_permission` | 7 | tenant_id/role_id/subject_type/subject_code/access/created_at/updated_at |
| `uk_rp` | 4 列 | `non_unique=0`，列序 `tenant_id,role_id,subject_type,subject_code` |

注意：**不加自增 `id`**（会变成 8 列，`s0` 直接红）。`uk_rp` 同时承担主键职责，InnoDB 会用它做聚簇索引。

### 2.2 行数等式（`s2` 逐格比对的就是这三行公式）

```
每租户 1680 = FUNCTION 600 (10 角色 × 60 功能码)
            + MENU     360 (10 × 36 页面)
            + ACTION   720 (10 × 72 动作)
每角色 168 = 60 + 36 + 72
```

| 层 | 取值公式（`RbacSeeder` 必须逐字使用） | 空集合语义 |
|---|---|---|
| FUNCTION | `PermissionMatrix.accessFor(role, functionCode)` | 不适用 |
| MENU | `PermissionMatrix.accessForAny(role, page.functionCodes())` | **ALLOW**（页面不绑功能码 = 登录可读） |
| ACTION | `PermissionMatrix.accessForAny(role, action.functionCodes())` | **ALLOW** |

`accessForAny` 的并集语义是「任一 DENY ⇒ DENY，否则取 max（ALLOW > RESTRICTED > DENY）」，空集合返回 ALLOW（`PermissionMatrix` 已有实现，不要在本轮重写）。空绑定页面恰好 4 个：`me.todo`、`me.tasks`、`req.pool`、`defect.list`，它们的 MENU 行 40 条必须全 ALLOW（`s2` 单独断言）。

**每角色每层都写满行**：`access='DENY'` 也是一行。不能靠「缺行 = DENY」——`s2` 的规范化哈希与 `s3` 的并集判定都要求行存在。

### 2.3 `RbacSeeder`（新建）

`req-service/src/main/java/com/example/req/seed/RbacSeeder.java`

```java
@Component
public class RbacSeeder {
    public RbacSeeder(SysDb db)
    /** 全局注册表：36 页面 + 72 动作，UPSERT（码是常量，名称/排序可变）。 */
    public void seedRegistries()
    /** 单租户 10 个内置角色的 1680 行，INSERT IGNORE；返回本次实际写入行数。 */
    public int seedTenant(long tenantId)
    /** 纯函数：某角色的 168 行预设（供单测/排障断言，不碰库）。 */
    public static List<Row> preset(String roleCode)
    public record Row(String subjectType, String code, String access)
}
```

实现要点：

1. `seedRegistries()` 用 `INSERT ... ON DUPLICATE KEY UPDATE`（`sys_menu` 以 `code` 为主键），把 `MenuRegistry.pages()` / `ActionRegistry.actions()` 落库；`function_codes` 用 `String.join(",", list)`，空列表写 `''`。
2. `seedTenant(tenantId)` 先 `SELECT id, code FROM sys_role WHERE tenant_id=?` 建 `code → id` 映射；只处理 `RoleCatalog.ROLES` 里的 10 个码（自定义角色不预置授权，符合 AC-9「新建角色 0 行全 DENY」）。
3. 批量 `INSERT IGNORE INTO sys_role_permission (...) VALUES (...),(...)`，`CHUNK = 200`（与 `RoleAdminService.CHUNK` 一致，避免超过 4MB 包体）。
4. 使用 `INSERT IGNORE` 而不是 `REPLACE`：**已存在的行永远不覆盖**，否则每次重启都会把管理员改过的档位重置回预设。
5. 日志一行汇总：`[rbac] seed tenant=1 roles=10 rows=1680 menus=36 actions=72`。

### 2.4 三个写入口（同一份预设，三条路径）

| 入口 | 文件 | 时机 | 事务 |
|---|---|---|---|
| 启动补齐 | `req-service/.../seed/RbacBootstrap.java`（新建，`ApplicationRunner`） | 每次启动 | 每租户一个事务（`@Transactional` 方法内循环会失效，用 `TransactionTemplate` 或让 `seedTenant` 自持事务） |
| 建租户 | `req-service/.../sys/TenantProvisioner.java`（改造） | `provision()` 内、`createRoles()` 之后 | 与建租户同事务 |
| 演示重置 | `req-service/.../seed/DemoResetService.java`（改造） | `POST /admin/demo/reset` | 与 reset 同事务 |

`RbacBootstrap` 逻辑：

```java
@Component
public class RbacBootstrap implements ApplicationRunner {
    public RbacBootstrap(SysDb db, RbacSeeder seeder)
    @Override public void run(ApplicationArguments args)   // seedRegistries → 遍历 sys_tenant → seedTenant
}
```

- 遍历 `SELECT id FROM sys_tenant`，**所有租户**都要补齐（`s0` 断言所有租户 1680 行）；
- 失败不吞异常：启动失败好过带着空授权跑（与 fail-fast 同一取向）；
- 空库（无租户）时只 seed 注册表，日志说明 0 租户。

`TenantProvisioner` 改造：构造器加 `RbacSeeder seeder`，`createRoles(tenantId)` 之后立刻 `seeder.seedTenant(tenantId)`；返回的 `Provisioned` 不变（避免动 `SysViews` 与前端类型）。

### 2.5 演示重置

`DemoResetService` 增加两步（脚本 `scripts/platform-demo-reset.sh` 已在等这两个键）：

```java
counts.put("rolePermission", db.count("SELECT COUNT(*) FROM sys_role_permission WHERE tenant_id = ?", tenantId));
counts.put("customRoles",    db.count("SELECT COUNT(*) FROM sys_role WHERE tenant_id = ? AND builtin = 0", tenantId));
```

`reset()` 的 RBAC 步骤（放在 `purge()` 之后、返回前）：

1. 删自定义角色的用户绑定：`DELETE ur FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE r.tenant_id=? AND r.builtin=0`；
2. 删自定义角色的授权行：`DELETE p FROM sys_role_permission p JOIN sys_role r ON r.id=p.role_id WHERE r.tenant_id=? AND r.builtin=0`；
3. 删自定义角色：`DELETE FROM sys_role WHERE tenant_id=? AND builtin=0`；
4. 内置角色授权整体重置：`DELETE FROM sys_role_permission WHERE tenant_id=?`，再 `seeder.seedTenant(tenantId)`；
5. `counts` 里补 `rolePermission=1680`、`customRoles=0`。

这样「污染 `PLATFORM_ADMIN` 的 `audit:view` → reset → 必须回 ALLOW」由第 4 步保证；连跑两次幂等（第二次删 1680 再写 1680）。

---

## 3. 后端 API（已实现，本文固化契约）

### 3.1 端点总表

| 方法 | 路径 | 请求 | 响应 | 主要错误 |
|---|---|---|---|---|
| GET | `/api/req/admin/roles` | — | `RoleRow[]` | 403 |
| POST | `/api/req/admin/roles` | `{code,name,description?}` | `RoleRow` | 400 `INVALID_ROLE_CODE`、409 `ROLE_CODE_TAKEN` |
| PUT | `/api/req/admin/roles/{id}` | `{name,description?}` | `RoleRow` | 400 `CODE_IMMUTABLE`、404 |
| PATCH | `/api/req/admin/roles/{id}/status` | `{status:'ACTIVE'\|'DISABLED'}` | `RoleRow` | 409 `BUILTIN_ROLE_IMMUTABLE`、404 |
| DELETE | `/api/req/admin/roles/{id}` | — | `{deleted:true}` | 409 `BUILTIN_ROLE_IMMUTABLE`、409 `ROLE_IN_USE` |
| GET | `/api/req/admin/roles/{id}/permissions` | — | `RoleGrantView` | 404 |
| PUT | `/api/req/admin/roles/{id}/permissions` | `{grants:[GrantInput], confirmHighRisk?}` | `GrantSaveResult` | 400 `UNKNOWN_SUBJECT`/`INVALID_ACCESS`、409 `ADMIN_LOCKOUT_RISK`/`HIGH_RISK_REQUIRES_CONFIRM`、404 |
| GET | `/api/req/admin/permissions` | — | 600 格只读矩阵 | 403 |
| GET | `/api/req/auth/me/permissions` | — | `{epoch,functionCodes,menus,actions,restricted}` | 401 |

`GrantInput = {subjectType:'FUNCTION'|'MENU'|'ACTION', subjectCode, access:'ALLOW'|'RESTRICTED'|'DENY'}`。后端同时接受 `type/code/layer` 别名（`AdminRoleController.grants(Map)`），**前端只用 `subjectType/subjectCode`**。

`RoleGrantView = {role, epoch, modules:[{code,name,pages:[{code,name,routeName,access,functionCodes:[{code,access}],actions:[{code,name,access,highRisk,functionCodes}]}]}], warnings:[{code,page,actions}]}`。

`GrantSaveResult = {epoch, added, removed, changed, highRiskGranted:string[], warnings, effective:{menus:string[],actions:string[]}}`。

### 3.2 保存授权的事务语义（不可改）

`RoleAdminService.saveGrants(roleId, grants, confirmHighRisk)` 顺序固定：

```
校验 subject 已注册 + access 合法（400，此时未落库）
→ 与库内对比出 diff（added/removed/changed）
→ PLATFORM_ADMIN 锁死校验（409 ADMIN_LOCKOUT_RISK，未落库）
→ 高危校验（409 HIGH_RISK_REQUIRES_CONFIRM，未落库）
→ DELETE 该角色全部行 → 分块 INSERT 168 行
→ diff.total()>0 才写 1 条 ROLE_PERMISSION_UPDATE（无差异不写）
→ permissions.invalidate(tenantId)（afterCommit）
```

- **全量覆盖**而不是增量：168 行是注册表的函数，新增页面不会被旧角色「继承缺行」；`s2` 的哈希相等也依赖它。
- 409 必须在任何写之前抛，**库不变**（`s3`/`s5` 用行数断言）。
- `warnings` 只表达一种情况：`PAGE_DENY_ACTION_GRANTED`（页面 MENU=DENY 但该页存在非 DENY 的 ACTION）。这是**允许保存**的状态，靠 `effective` 与页面黄警告知。
- `effective` 只列 `menus`/`actions` 的非 DENY 码（不含 functions）——它是「这份授权实际能做什么」的预览，页面 DENY 的动作不会出现在里面。

### 3.3 判定与缓存

`PermissionService` 是唯一判定点（禁止业务代码直连 `PermissionMatrix.accessOf/accessFor`，`lint` 有静态断言）。

```
decide(pageCode, actionCode):
  menu = union(MENU 行 of pageCode)       // 多角色：任一 DENY ⇒ DENY，否则 max
  if (menu == DENY) return DENY           // 页面 DENY 时动作一律不生效
  if (actionCode == null/blank) return menu
  act = union(ACTION 行 of actionCode)
  if (act == DENY) return DENY
  return narrower(menu, act)              // ordinal 越大越窄：ALLOW < RESTRICTED < DENY
```

- 快照按租户缓存，`platform.rbac.cache-ttl-seconds` 默认 45s（`s4` 断言 ≤60）；
- `invalidate` 在事务提交后执行 `epoch++` 并丢弃快照 ⇒ **保存后第一次请求即生效**，不重启、不重登；
- 请求内 `ThreadLocal` memo，一次请求只解析一次；
- 403 契约（`PermissionInterceptor`）：`code=FORBIDDEN`、`message=无权执行该操作`、`detail={path,method,page,pages,action,access:'DENY',roles}`，同时写 `PERMISSION_DENIED` 审计。前端 `silentForbidden` 就地提示，**不跳 /403**。

### 3.4 审计（AC-14）

| action | 时机 | detail 必含 |
|---|---|---|
| `ROLE_CREATE` / `ROLE_UPDATE` / `ROLE_STATUS` / `ROLE_DELETE` | 角色增改停删 | 操作者、目标 code、before/after |
| `ROLE_PERMISSION_UPDATE` | 授权保存且 `diff.total()>0` | `roleCode`、`added/removed/changed`、`highRiskGranted`、before/after |
| `USER_CREATE` / `USER_UPDATE` / `USER_STATUS` | 用户写 | 角色/域差集 |
| `ORG_CREATE` / `ORG_UPDATE` / `ORG_DELETE` / `ORG_MOVE_USERS` | 组织写 | 人员数 / 目标组织 |
| `PERMISSION_DENIED` | 每次 403 | `page/action/access/method/path` |

无差异不写（`s8` 重复保存同一份授权断言审计条数不变）。

---

## 4. 前端

### 4.1 路由（已就绪，不要改）

`web/src/router/routes/deliver.routes.ts:139-160` 已声明：

- `admin-roles` → `roles` → `admin/AdminRolesView.vue`，meta `{title:'角色与权限', pageCode:'admin.roles', functionCode:'org:manage'}`
- `role-permissions` → `roles/:id(\d+)/permissions` → `admin/RolePermissionsView.vue`，meta `{title:'角色授权', parentPath:'/admin/roles', pageCode:'admin.roles'}`

两个组件文件不存在时构建直接失败——本轮补齐即可，路由文件不动。

### 4.2 导航接线（3 个文件，一处判定）

**`AdminLayout.vue`**：把本地 `{to,label,codes,hint}` 改为 `NavGuard` 形状，并统一走 `navVisible`：

```ts
interface AdminNavItem extends NavGuard { to: string; label: string; hint?: string }
const items: AdminNavItem[] = [
  { to:'/admin/orgs', routeName:'admin-orgs', label:'组织与租户', functionCode:'org:manage', pageCode:'admin.orgs' },
  { to:'/admin/users', routeName:'admin-users', label:'用户与角色', functionCode:'org:manage', pageCode:'admin.users' },
  { to:'/admin/vendors', routeName:'admin-vendors', label:'厂商与域', functionCode:'org:manage', pageCode:'admin.vendors' },
  { to:'/admin/roles', routeName:'admin-roles', label:'角色与权限', functionCode:'org:manage', pageCode:'admin.roles' },
  { to:'/admin/permissions', routeName:'admin-permissions', label:'权限总览', functionCode:'org:manage', pageCode:'admin.permissions' },
  // workflows / api-clients / integrations / metrics / audit 保持原语义，补 routeName + pageCode
]
const ctx: NavContext = {
  hasRoute: (name) => router.hasRoute(name),
  canAny: (codes) => perm.canAny(codes),
  hasMenu: (code) => perm.canMenu(code),
  vendorScoped: auth.user?.vendorId != null,
}
const visibleItems = computed(() => items.filter((i) => navVisible(i, ctx)))
```

- 顺序把「角色与权限」放在「权限总览」之前（设计稿的子导航顺序）；
- 文案「权限矩阵」→「权限总览」（`a4` 已按新标签重写）。

**`SideNav.vue:29-37` / `TopNav.vue:20-28`**：给 `navVisible` 的 `ctx` 补一行 `hasMenu: (code) => auth.canMenu(code)`。这是 AC-11 双条件的**唯一**开关——`navAccess.ts:45` 的注释已写明「缺省时跳过页面码」，不传等于没接。

**`nav.ts`**：已含 `pageCode:'admin.roles'`，`s1` 的三向比对依赖它，本轮不改（改了反而可能让 s1 红）。

### 4.3 页面文件拆分

| 文件 | 职责 | 依赖 |
|---|---|---|
| `web/src/views/admin/AdminRolesView.vue` | 角色列表：搜索/仅看启用/新建/编辑/停用/删除/进授权 | `@/api/admin` role 系列、`ElMessage`、`EmptyState`/`ErrorState`/`StatusPill`、`admin-shared.css` |
| `web/src/views/admin/RolePermissionsView.vue` | 授权编排：读 grants、默认选中页、保存链路、高危/锁死/离开对话框 | 下三个文件 + `useAuthStore` |
| `web/src/views/admin/role-permissions/useRoleGrants.ts` | 草稿模型：baseline/draft、diff、warnings、effective、load/reset/save | `@/api/admin`、`@/api/types` |
| `web/src/views/admin/role-permissions/RoleGrantTree.vue` | 左树：模块 tri-state、页面 tri-state、搜索、仅看已授权 | `AccessSegmented`（只读态可不用） |
| `web/src/views/admin/role-permissions/AccessSegmented.vue` | 三档控件（164×30，`role=radiogroup`） | 无 |

页面内部**不引第三方组件库之外的新依赖**；表格/抽屉/对话框用 Element Plus（与既有 admin 页一致）。

### 4.4 `useRoleGrants` 契约（下游按名字调用，禁止自创）

```ts
export interface GrantDiff { added: number; removed: number; changed: number }

export function draftKey(type: SubjectType, code: string): string   // `${type}|${code}`

export function useRoleGrants(roleId: Ref<number>): {
  view: Ref<RoleGrantView | null>
  draft: Ref<Record<string, AccessLevel>>
  loading: Ref<boolean>
  error: Ref<{ message: string; code?: string; status?: number } | null>
  saving: Ref<boolean>
  dirty: ComputedRef<boolean>
  dirtyCount: ComputedRef<number>
  diff: ComputedRef<GrantDiff>
  warnings: ComputedRef<GrantWarning[]>
  effective: ComputedRef<{ menus: string[]; actions: string[] }>

  load(): Promise<void>
  accessOf(type: SubjectType, code: string): AccessLevel
  setAccess(type: SubjectType, code: string, access: AccessLevel): void
  setPage(pageCode: string, access: AccessLevel): void          // 只写 MENU 格
  setModule(moduleCode: string, access: AccessLevel): void      // 写该模块所有页面 MENU 格
  setAllActions(pageCode: string, access: AccessLevel): void    // 写该页所有 ACTION 格
  reset(): void                                                 // draft = baseline
  save(confirmHighRisk?: boolean): Promise<GrantSaveResult>
}
```

- 草稿键：`FUNCTION|<code>` / `MENU|<pageCode>` / `ACTION|<actionCode>`；`draft` 必须在 `load()` 时**完整物化 168 项**（页面 DENY 的页也要有，否则保存时漏行会被后端按 DENY 覆盖，等价但会让「未保存计数」失真）。
- `save()` 内部：`grants = Object.entries(draft).map(([k,v]) => ...)`（按 `FUNCTION→MENU→ACTION`、码升序稳定输出，便于审计 diff 可读）；成功后 `baseline = {...draft}`、`view.epoch = result.epoch`、`warnings = result.warnings`、`effective = result.effective`，**不重新 GET**（一次保存只发 1 个 PUT）。
- 抛错不吞：`ApiError` 直接向上抛，由页面按 `code` 分支渲染。

### 4.5 `RolePermissionsView` 结构与链路

```
页头：← 返回角色列表 | 角色名 + code（mono）+ 状态 pill（DISABLED 时整页顶部琥珀条）
两栏：RoleGrantTree(320) | 右栏(692，内容 660)
右栏：页头 60（页面名 + 页面码 + 页面准入 AccessSegmented + 功能码 chips + 「高级」折叠）
      黄警示条（页面 DENY 且存在非 DENY 动作时）
      动作表工具行（全选本页动作 / 已授 x/y / 动作搜索）
      动作表（列 240/136/44/168；行 52；端点列 <1440 隐藏）
      保存条 56（N 项未保存 / 重置 / 保存并生效，按钮定宽 96）
```

- **默认选中页**：`modules.flatMap(m=>m.pages)` 里第一个「存在非 DENY 动作」的页面；没有则第一个模块第一页。这样「进入 → 点档位」= 1 击，满足 ≤3 击。
- **未保存计数**：`dirtyCount` 逐格比对 baseline；0 时保存按钮 disabled。
- **保存链路**：
  1. `save(false)`；`saving=true`；>2.5s 保存条追加「保存较慢，仍在处理」；
  2. 成功 → `ElMessage.success('已保存 · 新增 a / 移除 b / 调整 c')`；**总是** `await auth.refreshPermissions()`（不 reload、不重登）；
  3. `409 HIGH_RISK_REQUIRES_CONFIRM` → 打开高危对话框，列出 `detail.actions`（每个码 + 名称 + 「高危」pill）；确认后 `save(true)` 重放同一份草稿；取消则保持 dirty；
  4. `409 ADMIN_LOCKOUT_RISK` → 对话框列出 `detail.codes`（被拒的冻结码），只读，不重试；
  5. `400` → 内联错误条（不 toast），保留草稿；
  6. `403` → 内联「无权修改该角色授权」，不跳 /403。
- **离开拦截**：`onBeforeRouteLeave` + `beforeunload`，`dirtyCount>0` 时 480 确认框（「放弃未保存的 N 项变更？」）。
- 页面 DENY 但动作非 DENY：页头黄警 + 行内 11px `#9A5B00`「不生效」+ `effective` 预览不含该动作（`I4`）。

### 4.6 两个组件的 props/emits

```ts
// AccessSegmented.vue
props: { modelValue: AccessLevel; disabled?: boolean; ariaLabel?: string }
emits: { 'update:modelValue': [AccessLevel] }

// RoleGrantTree.vue
props: {
  modules: ModuleGrant[]
  draft: Record<string, AccessLevel>
  selected: string                 // 当前 pageCode
  onlyGranted: boolean
}
emits: {
  'update:selected': [pageCode: string]
  'update:onlyGranted': [boolean]
  'set-page': [pageCode: string, access: AccessLevel]
  'set-module': [moduleCode: string, access: AccessLevel]
}
```

树的三态是**页面 MENU 格**的聚合：模块 = 其下页面 MENU 全 ALLOW→ALLOW；全 DENY→DENY；其余→半选。页面行在三态之外，当「页面 ALLOW 但绑定功能码存在 DENY」时右侧加 6px 琥珀点（提示按钮仍可能被拒）。勾选框与行点击分离：点行=选中页面，点框=整页置 ALLOW（`I1` 覆盖 RESTRICTED）。

### 4.7 只读总览 `AdminPermissionsView.vue`

- 标题「权限矩阵（DESIGN P12 / AC-5）」→「权限总览」；副标题「只读视图：授权请在『角色与权限』中修改」；
- 顶部 40 高的只读条 + 「去角色授权」按钮（`router.push('/admin/roles')`）；
- 保留 600 格矩阵与搜索；不新增任何写控件（该控制器没有写映射，`s9` 断言）；
- 数据源仍是 `listPermissions()`（后端从 FUNCTION 行走库，授权页改完档位后同会话刷新即可见新值）。

### 4.8 令牌与几何

`web/src/styles/tokens.css` **只增不改**（旧变量一律不动）：

```css
--w-perm-tree: 320px;      --w-perm-panel: 692px;
--w-perm-act: 240px;       --w-perm-endpoint: 136px;
--w-perm-risk: 44px;       --w-perm-level: 168px;
--w-three-state: 164px;    --h-three-state: 30px;
--h-action-row: 52px;      --h-save-bar: 56px;
--perm-card-h: calc(100vh - 288px);
--w-role-code: 180px;      --perm-half: #C77700;   --perm-lock: #86909C;
```

几何依据（**修正 r18 的 1064**）：`AppShell.vue:102` 左右各 20px + `admin-shared.css:9` 子导航 160+16 ⇒ 1440 下正文 `1200 - 160 - 16 = 1024`；左树 320 + gap 12 + 右栏 692，右栏内容 660；动作表 `240+136+44+168+3×12 = 624 ≤ 660` ⇒ **1440 无横向滚动**（不是"看起来够"）。<1440 时端点列 `display:none`。三档控件 `3×52 + 2×3 + 1 = 163 ≈ 164`。

过渡只允许 `opacity/background/border-color/transform:translateY`，120/160/240ms；禁 `scale`、禁渐变。

---

## 5. 关键实现点与边界

| 场景 | 期望行为 | 落点 |
|---|---|---|
| 新建角色 | 无任何授权行（列表 userCount=0，授权页全 DENY 168 项） | `RoleAdminService.create` 不写授权；`useRoleGrants` 全 DENY 物化 |
| 停用角色 | 下一次请求即 403（快照 SQL 过滤 `status='ACTIVE'`），`sys_user_role` 保留，可再启用 | `PermissionGrantsRepository.SQL` + `setStatus` 后 `invalidate` |
| 删除内置角色/停用内置角色 | 409 `BUILTIN_ROLE_IMMUTABLE`；前端按钮 disabled + tooltip | `RoleAdminService` + `AdminRolesView` |
| 删除在用角色 | 409 `ROLE_IN_USE`（detail.users），前端对话框提示人数 | `RoleAdminService.delete` |
| 清空 PLATFORM_ADMIN 的 `org:manage`/`tenant:manage` | 409 `ADMIN_LOCKOUT_RISK`，库不变；前端锁图标 + 禁用该格 | `RoleGuard.assertNoLockout` + `AccessSegmented disabled` |
| 高危授予非管理员 | 首次 409 `HIGH_RISK_REQUIRES_CONFIRM`，二次确认后同 PUT 重放并记 `highRiskGranted` | `RoleGuard.highRiskGrants` + 高危对话框 |
| 页面 DENY、动作 ALLOW | 允许保存；`warnings=[{code:'PAGE_DENY_ACTION_GRANTED'}]`；直调接口 403 detail.page | `RoleAdminService.warnings` + 页头黄警 |
| 页面 ALLOW、动作 DENY | 按钮不渲染（`canAction` 单判），直调 403 detail.action | `auth.canAction` + 拦截器 |
| 多角色并集 | 任一 DENY ⇒ DENY（不是"最宽者胜"） | `PermissionService.union`（已实现） |
| 跨租户 roleId | 404，不泄露角色名 | 各 SQL 带 `tenant_id=?` |
| 未注册 subjectCode | 400 `UNKNOWN_SUBJECT`，整单不落库 | `RoleGuard.validateSubject` |
| 保存无差异 | 不写审计，返回 `added=removed=changed=0` | `RoleAdminService.saveGrants` |
| 保存后会话 | 立即生效（epoch++），前端总是刷新 `/auth/me/permissions` | `permissions.invalidate` + `auth.refreshPermissions()` |
| 非管理员打开授权页 | 403 → 内联提示，不渲染树 | 路由守卫 + 页面错误态 |

---

## 6. 测试策略

### 6.1 单测

| 测试 | 断言 | 归属 |
|---|---|---|
| `RbacSeedEquivalenceTest`（已存在） | 逐角色×页面/动作 = `accessForAny`；1680 行；canonical SHA-256 稳定 | 保留不动 |
| `RbacSeederTest`（新增） | `preset("QA")` 168 行（60/36/72）；空绑定页面 MENU=ALLOW；抽查 3 格 = 矩阵；`preset` 与 `seedTenant` 同一数据源 | T1 |
| `PermissionServiceTest` / `PermissionUnionTest` / `RoleGuardTest`（已存在） | 三序、并集、锁死 | 保留不动 |
| `PermissionMatrixGridTest` 600 断言 | 原地保留，不改不删 | 门禁基线 |

### 6.2 脚本门禁（`make rbac-verify`，s0–s9）

| 段 | 覆盖 AC | 本轮依赖 | 断言数下限 |
|---|---|---|---|
| s0 迁移 | AC-2/3 | V12 + 种子 | 8 |
| s1 注册表三向 | AC-3/11 | 注册表 = nav = 库 | 8 |
| s2 种子等价 | AC-4 | 1680 逐格 + 哈希 | 8 |
| s3 判定顺序 | AC-5/6 | 页面 DENY/动作 DENY/并集/403 契约 | 6 |
| s4 缓存与 epoch | AC-6 | 撤销后第 1 次 403、恢复后第 1 次 200 | 5 |
| s5 角色与防锁死 | AC-8/10 | CRUD/停用/409 | 8 |
| s6 人员与组织 | AC-13 | org 409/move-users/审计 | 6 |
| s7 负向 9 例 | AC-15 | 9 条全绿 | 9 |
| s8 审计与重置 | AC-14/17 | 1 条审计、reset 幂等 | 6 |
| s9 声明覆盖 | AC-7 | 161 写端点 0 违规 | 5 |

总数 ≥60（脚本内置，**禁止下调 `segment_min` 或跳过 s9**）。

### 6.3 回归门禁（一个都不能红）

```bash
mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile
mvn -o -pl req-service test -Dtest='Rbac*Test,Permission*Test,RoleGuardTest'
make rbac-verify
make platform-verify          # 168
make access-verify            # 148
make platform-test-negative   # 30
make lint                     # ≥20
make platform-unit            # 278
make verify                   # 14/14
make analytics-i18n-verify    # 74
bash scripts/collab-verify.sh # s0–s8
cd web && npm run typecheck && npm run build
```

### 6.4 证据（AC-18）

`docs/teamflow/20261113-r19/evidence/`：`01-roles-list.png`、`02-permission-tree-warning.png`、`03-vendor1-no-actions.png`、`04-save-then-403.png`，1440×900、无缩放、整页可读、不裁切不 P 图。

---

## 7. 任务拆分（并行按文件边界，`files` 互斥）

| # | 任务 | files（owns） | reads | 依赖 | 完成判据 |
|---|---|---|---|---|---|
| T1 | 迁移 + 种子 + 启动/建租户接线 | `req-service/src/main/resources/db/migration/V12__rbac_role_permission.sql`、`req-service/src/main/java/com/example/req/seed/RbacSeeder.java`、`.../seed/RbacBootstrap.java`、`.../sys/TenantProvisioner.java`、`req-service/src/test/java/com/example/req/rbac/RbacSeederTest.java` | `MenuRegistry`、`ActionRegistry`、`PermissionMatrix`、`SysDb`、`RoleCatalog`、`s0`/`s2` | — | 编译过；`s0`、`s2` 绿；新库首启 1680/租户；`RbacSeederTest` 绿 |
| T2 | 演示重置回预设 | `req-service/src/main/java/com/example/req/seed/DemoResetService.java` | `RbacSeeder`（用 `seedTenant`）、`DemoFixture`、`scripts/platform-demo-reset.sh` | 契约由 T1 固定（签名见 §2.3），可并行 | reset 响应含 `rolePermission=1680`、`customRoles=0`；连跑两次幂等 |
| T3 | 导航接线 + 令牌 + 只读总览 | `web/src/styles/tokens.css`、`web/src/views/admin/AdminLayout.vue`、`web/src/layouts/SideNav.vue`、`web/src/layouts/TopNav.vue`、`web/src/views/admin/AdminPermissionsView.vue` | `navAccess.ts`、`nav.ts`、`usePermission.ts`、`Deliver routes` | — | 侧栏出现「角色与权限」「权限总览」且双条件生效；`a3`/`a4` 绿；`s1` 的 nav pageCode 比对绿 |
| T4 | 角色列表页 | `web/src/views/admin/AdminRolesView.vue` | `api/admin.ts`、`api/types.ts`、`StatusPill`、`admin-shared.css`、DESIGN §0 | T3（tokens 可先缺，用 `var(--x, fallback)`） | 增改停删全通；409 内联；停用显示影响人数；1440 不横滚 |
| T5 | 授权页 + 草稿模型 | `web/src/views/admin/RolePermissionsView.vue`、`.../role-permissions/useRoleGrants.ts`、`.../RoleGrantTree.vue`、`.../AccessSegmented.vue` | T4 产物、`api/admin.ts`、`api/types.ts`、`stores/auth.ts`、DESIGN §0/§4 | T3、T4（只读其文件） | 默认页 1 击改档；保存 1 次 PUT；黄警/生效预览正确；高危与锁死对话框；离开拦截 |
| T6 | 门禁收口 + 交付自检 | `.gitignore`、`docs/teamflow/20261113-r19/evidence/*.png` | 全部 | T1–T5 | §6.3 全绿；`pom.xml`/`package.json` diff 为空；4 张证据；单次提交 |

**并行口径**：T1 与 T2 文件互斥可并行（API 由 §2.3 固定）；T3 与 T4/T5 文件互斥可并行；T6 必须最后跑，且**只跑现成门禁、不新建检查器**。

**T6 的 git 动作（来自 PRD「工程约束」，不可丢）**：

```bash
# 工作区已有 16 处改动（含 RoleAdminService 两处编译修复、门禁脚本增强、Makefile），全部保留并随本次提交
git status --short          # 只允许增加本次的 V12/seed/两个 .vue/组件/tokens/.gitignore/evidence
git add -A
git commit -m "feat(rbac): r18 收尾——V12 三层授权迁移、角色/授权页与门禁全绿"
# 禁止：checkout -- . / stash drop / rebase / 重写 5b7ba9d / 切换分支
```

`.gitignore` 追加 `*.eslintcache`、`*.local`、`*.dump`、`*.sql.gz`；**不得**忽略 `*.sql` 与 `evidence/*.png`。

---

## 8. 风险与取舍

| 风险 | 判断 | 处理 |
|---|---|---|
| `seedTenant` 每次启动重写 1680 行/租户 | 可接受（INSERT IGNORE 不覆盖已改行），单租户 <50ms | 只补不写；日志汇总行数 |
| req-service 未启动时其他服务读不到授权行 | 与 r18 相同的启动顺序约束 | `RbacBootstrap` 绑在 req-service（迁移所有者）；其它服务 fail-closed（DENY） |
| 授权页 1680 项全量 PUT ~40KB | 单事务 + 分块 200，实测 <2.5s | 保存条 >2.5s 提示，不阻断 |
| `<1440` 端点列隐藏导致"看不到路由" | 设计取舍（AC-9 只要求 1440 无横滚） | 媒体查询；列宽令牌可调 |
| `a3` 基线 36→37 | 只有 `s1` 三向一致后才成立 | 已在工作区改为「以 MenuRegistry 为准 + R10 逐字比对」，T6 校验 |
| 工作区改动被误丢 | 16 处含两处编译修复，丢了 AC-1 直接红 | T6 明确禁止 `checkout -- .`/`stash drop`；提交前 `git status` 自查 |

---

*本文与 [DESIGN.md](DESIGN.md) 的分工：设计管"长什么样、点几下"，本文管"落到哪个文件、调用哪个方法、失败怎么表现"。冲突时以实现契约（§3/§4.4）为准，几何以设计（§4.8 引用）为准。*
