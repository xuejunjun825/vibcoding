# TECHNICAL — 厂商项目经理（VENDOR_PM）角色

- 任务夹：`docs/teamflow/20261116-r24-vendor-pm-role/`
- 分支：`feat/vendor-pm-role`（已存在、已切换；本需求不新建、不 rebase）
- 基线：`docs/teamflow/20261116-r22-action-tier` + `docs/teamflow/20261116-r23-flow-control-gates`
- 取代：`20261026-r7-vendor-access#AC-5`（厂商账号隐藏用例库 → 仅 VENDOR_PM 开只读例外）
- 状态：技术方案定稿，待开发

## 0. 结论与口径修正

**一句话**：不新增表、不新增页面码、不新增动作码、不改判定引擎；VENDOR_PM 是 `PermissionMatrix` 的第 11 行 + `RoleCatalog` 的第 11 个内置角色 + `RbacSeeder` 的一处 ACTION 覆盖 + `ScopeSql` 的一条 release 派生谓词 + 前端三处 `canAction` 与一处 `internalOnly` 例外。

实现面 20 个文件，其中生产代码 11 个：契约 1、迁移 1、种子 2、作用域 1、路由表 1、版本服务 1、账号控制器 1、前端 5。

动手前必须先接受 5 条**与 PRD 字面不一致**的口径，否则开发会照着错的数字改脚本：

| # | PRD 字面 | 实际情况 | 本方案口径 |
|---|---|---|---|
| C1 | MENU「11 个页面非 DENY」 | 空绑定页（`me.todo`/`me.tasks`/`req.pool`/`defect.list`）与公共页（`me.workbench`/`ana.*`）也自动 ALLOW | **11 是「必须非 DENY 的目标页集合」，不是全集**；VENDOR_PM 实际 MENU 非 DENY = 17、DENY = 19（admin 10 + 9 个功能页） |
| C2 | AC-10「`me.approvals` 不渲染」 | `me.approvals` 不是 `MenuRegistry` 页面（无 MENU 行），路由 meta 也无 `internalOnly`；它是所有账号的个人待办 | **不改**：vpm1 可打开自己的待办中心，但 `approve/review_*` 全部 403。为一个个人页引入第 12 个 DB 覆盖会破坏「168 = preset + 11 ACTION」的门禁口径 |
| C3 | AC-9 列了 `PATCH /releases/{id}` | `ReleaseController` 没有该映射，只有 `PATCH /releases/{id}/quality-settings` | 写边界按 `quality-settings` 验证；PRD 的该行记为口径修正 |
| C4 | 版本页「看到本厂商版本」 | 演示唯一版本只含 8 条内部需求（VEN-A 的 R-09/10/11 均 `inRelease=0`） | 严格派生后 **vendor1 版本列表 1→0、vpm1 为 0**；这正是 PRD 的「vendor1 收窄属预期」。AC-8 的正例（详情 200）由负向脚本自建 VEN-A 版本验证，**不改演示夹具** |
| C5 | AC-9 未列 `POST /releases/{id}/reviews` | 该写口当前只挂 `page(req.release)`，VENDOR_PM 会通过 | **补洞**：改为 `action(release.action.update)`。其它角色由 `release:plan` 派生同值，行为不变；只有 VENDOR_PM 变 403 |

另外两处数字要对齐：`SeedService` 演示账号 **13→14**（新增 vpm1）；`platform-verify.sh` 的账号集合是 12 条（不含 mgr1），**12→13**；`platform-seed.sh` 的 `SEEDED_USERS` 12→13、登录循环 13→14。

## 1. 数据模型与存储

### 1.1 不新增表、不改列

三张 RBAC 表（V12 已建）足够：

| 表 | 本轮变化 | 说明 |
|---|---|---|
| `sys_role` | +1 行/租户（`VENDOR_PM`，`builtin=1`） | 由 V13 给存量租户补；新租户由 `TenantProvisioner.createRoles` 补 |
| `sys_role_permission` | +168 行/租户（FUNCTION 60 + MENU 36 + ACTION 72） | 由 `RbacSeeder.seedTenant` 用 `INSERT IGNORE` 写；唯一键 `uk_rp(tenant_id, role_id, subject_type, subject_code)` 保证幂等 |
| `sys_menu` / `sys_action` | 不变（36 / 72） | 全局注册表，本轮不加页面、不加动作 |

> 计数字典：1680 → **1848**（11 角色 × 168）；FUNCTION 660、MENU 396、ACTION 792。

### 1.2 V13 迁移

文件：`req-service/src/main/resources/db/migration/V13__vendor_pm_role.sql`（当前最大 V12，禁止改历史迁移）。

```sql
-- r24: 第 11 个内置角色（只建角色行；168 行授权由 RbacSeeder 启动自愈补齐）
INSERT INTO sys_role (tenant_id, code, name, builtin, created_by, updated_by)
SELECT t.id, 'VENDOR_PM', '厂商项目经理', 1, 0, 0
FROM sys_tenant t
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role s WHERE s.tenant_id = t.id AND s.code = 'VENDOR_PM'
);
```

要点：

- **只插角色行**，不在 SQL 里内联 168 行。理由：ACTION 覆盖的真相在 `RbacSeeder`，SQL 内联会形成第二份预设；且启动时 `RbacBootstrap` 无论如何都会补齐，V13 内联只会增加漂移面。
- 可重复执行（`NOT EXISTS`），符合 V3 的既有模式。
- `builtin=1`：内置角色不可删/停（`RoleGuard`/`RoleAdminService` 既有约束），`demo-reset` 只删 `builtin=0`。

### 1.3 启动与重置时序

```
Flyway V13（建角色行）
      ↓
RbacBootstrap.run()  ── seedRegistries()      // sys_menu 36 / sys_action 72，全局 upsert
      │                └ seedAllTenants()     // 遍历 sys_tenant，每租户一事务
      │                       └ RbacSeeder.seedTenant(tenantId)
      │                              ├ 只遍历 PermissionMatrix.roles() 中「该租户已存在」的角色
      │                              └ preset(role) → 168 行 INSERT IGNORE
      ↓
SeedService.ensure()（演示租户）── 角色定义 + vpm1 用户 + 7 个授权域
```

`demo-reset` 的顺序（已存在，不改）：删自定义角色的 `sys_user_role` → `DELETE FROM sys_role_permission WHERE tenant_id=?` → 删 `builtin=0` 角色 → `rbacSeeder.seedTenant(tenantId)`。因此重置后仍为 1848 行，VENDOR_PM 作为 builtin 不会被删。

### 1.4 幂等要求（AC-4/AC-17）

| 动作 | 期望 |
|---|---|
| 启动两次 | `sys_role_permission` 仍 1848/租户；无重复（唯一键） |
| `make platform-seed` 两次 | 账号仍 14 条；vpm1 的 `sys_user_role` 恰 1 条 |
| `make platform-demo-reset` 两次 | 授权 1848、内置角色 11、自定义角色 0；两次快照逐项相等 |
| 存量租户 | 升级启动后 VENDOR_PM 存在且 168 行（不是「等下一次 seed」） |
| 新租户 | `TenantProvisioner` 建 11 角色后同流程 `seedTenant`，一次到位 |

## 2. 权限模型（168 格）

### 2.1 四处同源注册

| 位置 | 改动 |
|---|---|
| `platform-contract/.../PermissionMatrix.java` | `ROLES` 末尾追加 `VENDOR_PM`；新增一行 12 ALLOW / 1 RESTRICTED / 47 DENY |
| `req-service/.../sys/RoleCatalog.java` | `ROLES` 末尾追加 `RoleDef("VENDOR_PM","厂商项目经理")`（**append**，保既有 code→id 顺序稳定） |
| `req-service/.../seed/SeedService.java` | `roleDefs` 加 VENDOR_PM（演示租户自愈用）；`userDefs` 加 vpm1 |
| `V13__vendor_pm_role.sql` | 存量租户补角色行 |

`PermissionMatrix` 的公共签名不变（`roles()`/`accessFor`/`accessForAny`/`cells()`/`rowOf`），只在 `build()` 里多一段 allow/restrict。

### 2.2 FUNCTION：12 ALLOW + 1 RESTRICTED

| 档位 | 功能码 |
|---|---|
| ALLOW（12） | `release:plan`、`task:manage`、`case:manage`、`plan:manage`、`defect:handle`、`defect:board`、`qualitymetric:view`、`WIKI_VIEW`、`GANTT_VIEW`、`PORTAL_VIEW`、`REPORT_VIEW`、`notification:read` |
| RESTRICTED（1） | `trace:view`（页面可见，数据仍按域/厂商收窄） |
| DENY（47） | 其余全部，含 `org:manage`、`tenant:manage`、`release:approve`（若存在）、`strategy:manage`、`env:manage`、`report:generate/sign`、`FLOW_VIEW`、`CCODE_POLICY_VIEW`、`audit:view` 等 |

4 个「页面准入键」必须 ALLOW 的理由：`MenuRegistry` 的页面准入是 **MENU 非 DENY 且绑定 FUNCTION 非 DENY** 双条件。`req.release`/`req.iteration`/`test.cases`/`test.plans` 分别绑定 `release:plan`/`task:manage`/`case:manage`/`plan:manage`，这 4 个码若 DENY，页面直接消失。写能力不靠 FUNCTION 收，靠下一节的 ACTION 覆盖。

### 2.3 MENU：17 非 DENY

| 类别 | 页面 | 结果 |
|---|---|---|
| 目标 11 页 | `req.pool`、`req.release`、`req.iteration`、`defect.list`、`test.cases`、`test.plans`、`qa.trace`、`collab.gantt`、`collab.wiki`、`defect.board`、`qa.metrics` | 非 DENY |
| 自动放行 6 页 | `me.todo`、`me.tasks`、`me.workbench`（`case:manage` ALLOW）、`ana.portal`、`ana.dashboard`、`ana.reports` | 非 DENY |
| DENY 19 页 | `admin.*` 10 + `req.create`、`test.strategies`、`test.reports`、`test.envs`、`test.executors`、`deploy.orders`、`collab.value-stream`、`qa.gates`、`code.policies` | DENY |

不加 MENU 覆盖：`RbacSeeder.preset` 继续用 `accessForAny(page.functionCodes())`，`me.workbench` 因 `case:manage` 自动可见（可接受：工作台只读）。

### 2.4 ACTION：7 ALLOW + 11 条显式 DENY 覆盖

派生规则（不改）：ACTION 档位 = 其 `functionCodes` 在 `PermissionMatrix` 的 max。VENDOR_PM 因此自动获得 4 个版本动作、6 个任务/迭代动作、1 个用例编辑动作（共 11 个不该有的写口）。

**覆盖必须只发生在种子派生层，不能改 `PermissionMatrix`**：`PermissionMatrix` 的契约是「角色 × 功能码」，运行时不读它；ACTION 覆盖是「角色 × 动作」的第二个维度，放在 `RbacSeeder` 才不会污染网格测试与 `s2` 的等价公式。

文件：`req-service/src/main/java/com/example/req/seed/RbacSeeder.java`

```java
private static final Map<String, Set<String>> ACTION_DENY_OVERRIDES = Map.of(
        "VENDOR_PM", Set.of(
                "release.action.create", "release.action.update",
                "release.action.approve", "release.action.close",
                "task.action.create", "task.action.assign",
                "task.action.update", "task.action.close",
                "sprint.action.create", "sprint.action.close",
                "test.action.case_edit"));

/** 角色 × 动作的最终档位：先看显式覆盖，再回落到 functionCodes 派生。 */
public static PermissionMatrix.Access actionAccessFor(
        String roleCode, ActionRegistry.ActionDef action) {
    if (ACTION_DENY_OVERRIDES.getOrDefault(roleCode, Set.of()).contains(action.code())) {
        return PermissionMatrix.Access.DENY;
    }
    return PermissionMatrix.accessForAny(roleCode, action.functionCodes());
}
```

`preset(String roleCode)` 的 ACTION 行由 `PermissionMatrix.accessForAny(...)` 改调 `actionAccessFor(roleCode, action)`；MENU/FUNCTION 两行不动。`driftCells` 仍按 `preset` 比较，无需改。

11 条清单与 PRD 一致：`release.action.create/update/approve/close`、`task.action.create/assign/update/close`、`sprint.action.create/close`、`test.action.case_edit`。

保留的 7 个动作：`req.action.schedule`；`defect.action.assign/handle/resolve/verify/reopen/close`。

`test.action.plan_create/plan_approve/execute` 不需要列进覆盖：它们绑定 `plan:edit`/`plan:approve`/`plan:execute`，VENDOR_PM 这三码本身 DENY，派生已是 DENY。

### 2.5 判定链（零改动）

```
HTTP → PermissionRouteTable（页面码 + 动作码守卫）
     → PermissionService 三序（角色并集 → 域 → 动作矩阵）
     → RequirementActionMatrix（需求动作，r22 起读 sys_role_permission 的 req.action.* 单元格）
     → DataScope（VENDOR tier 收窄 SQL）
```

本轮只改两处**声明**（V13 与 ACTION 覆盖）、一处**路由声明**（C5 补洞），判定引擎一行不改。

## 3. 数据隔离与可见性

### 3.1 厂商账号必须绑定 vendor_id

- `RoleCatalog` 新增：

```java
public static final String VENDOR_PM = "VENDOR_PM";

public static boolean requiresVendor(Collection<String> roleCodes) {
    return roleCodes != null && roleCodes.contains(VENDOR_PM);
}
```

- `AdminUserController` 在写库前统一校验（create 与 update 共用）：

```java
private void validateVendorBinding(List<String> effectiveRoles, Long vendorId) {
    if (RoleCatalog.requiresVendor(effectiveRoles) && vendorId == null) {
        throw new ApiException(400, "VALIDATION_FAILED",
                "VENDOR_PM 用户必须绑定厂商（vendorId）",
                Map.of("field", "vendorId"));
    }
}
```

- create：`roleCodes = resolveRoles(...)` 后立即校验。
- update：**必须用「将生效角色」**——`touchesRoles ? resolveRoles(body) : roles(tenantId, id)`；校验通过才 `UPDATE`，否则原值不变。
- 非 VENDOR_PM 用户不传 vendorId 不受影响；VENDOR_PM 与其它角色混用时，只要显式覆盖命中就 DENY（AC-6 已接受该收窄）。

### 3.2 版本可见性：`ScopeSql` 派生谓词

`release` 表没有 `vendor_id`，语义是「版本可见 ⇔ 版本里至少一条需求由本厂商承接」。在 `ScopeSql` 为 `release` 增加派生分支，列表与详情共用：

文件：`platform-common/src/main/java/com/example/platform/common/scope/ScopeSql.java`

```java
private static final Map<String, String> DERIVED_VENDOR = Map.of(
    "release",
    "EXISTS (SELECT 1 FROM release_requirement __rr "
  + "JOIN `requirement` __rq ON __rq.id = __rr.requirement_id "
  + "                       AND __rq.tenant_id = __rr.tenant_id "
  + "WHERE __rr.tenant_id = {a}tenant_id AND __rr.release_id = {a}id "
  + "  AND (__rq.owner_vendor_id = :__scopeVendor OR __rq.assignee_vendor_id = :__scopeVendor))");
```

在 `forTable` 的 VENDOR 分支末尾：

```java
if (spec.vendorColumn() == null && spec.vendorColumnAlt() == null) {
    String derived = DERIVED_VENDOR.get(normalize(table));
    if (derived != null) {
        return predicate.and(derived.replace("{a}", prefix));   // prefix 为空或 "r."
    }
    // 没有厂商列也没有派生规则 ⇒ 维持现状（tenant(+domain) 已生效）
}
```

- 只在 `scope.tier() == VENDOR && scope.vendorId() != null` 时进入该分支，TENANT/DOMAIN/ORG/BIZ/SELF 全部不受影响。
- `release_requirement` 主键 `(release_id, requirement_id)` 天然覆盖 `release_id` 前缀，无需加索引。
- 列表 SQL 已带 `/*scope:r*/`（`ReleaseService.page` 第 143 行 `" 1 = 1 AND /*scope:r*/"`），改 `ScopeSql` 即生效。

### 3.3 版本详情：一处判定关掉全部写口

`ReleaseService.requireVisible(long id)` 是所有版本读/写的唯一入口（detail、加/移需求、锁、审批、里程碑）。在既有 `requireDomainVisible` 之后补：

```java
if (scope.tier() == ScopeTier.VENDOR) {
    long visible = jdbc.count(
        "SELECT COUNT(*) FROM `release` r WHERE r.id = :id AND /*scope:r*/",
        Map.of("id", id));
    if (visible == 0) {
        // 与域越界同一审计口径，避免出现「列表看不到但详情能读」的探测面
        auditDenied("release", id, scope);
        throw RequirementErrors.outOfScope("版本", release.getDomainId());
    }
}
```

- 先按租户查 `findByIdAndTenantId`：不存在仍 404；存在但厂商不匹配 → **403 `OUT_OF_SCOPE`**（AC-8 要求的码）。
- 与列表共用同一条 `ScopeSql` 谓词，不存在第二份 SQL 漂移。
- 因此 `POST /releases/{id}/approval`、`/lock`、`/requirements`、`PATCH quality-settings` 即使路由守卫被绕过，也会在服务层 403。

### 3.4 用例库例外（取代 r7 AC-5）

- 后端不动：`test_case` 仍由 `ScopeSql` 按 `vendor_id` 收窄，空厂商行按内部行可见。
- 前端两处（路由 + 导航）引入**按角色的窄例外**，不是「厂商账号一律放行」：

```ts
// web/src/stores/auth.ts
const isVendorPm = computed(() => (user.value?.roles ?? []).includes('VENDOR_PM'))
```

```ts
// web/src/router/index.ts
if (to.meta.internalOnly && auth.user?.vendorId != null
    && !(to.meta.vendorPmAllowed && auth.isVendorPm)) {
  return { path: '/403', query: { from: to.fullPath, code: 'INTERNAL_ONLY' } }
}
```

```ts
// web/src/layouts/navAccess.ts
if (item.internalOnly && ctx.vendorScoped && !(item.vendorPmAllowed && ctx.vendorPm)) return false
```

- 只在 `deliver.routes.ts` 的用例库 meta 与 `nav.ts` 的用例库项加 `vendorPmAllowed: true`。
- 其余 `internalOnly` 页面（如审批中心的内部视图）对 vpm1 仍 403。
- 页面内所有写按钮改读 `allowedActions`（见 5.2），只读语义由 ACTION 矩阵保证。

### 3.5 演示数据现状（必须写进 DEMO-BY-ROLE）

演示唯一版本只关联 8 条内部需求，VEN-A 的三条（`DEMO-R-09/10/11`）`inRelease=0`。因此严格派生后：

| 账号 | 版本列表 | 说明 |
|---|---|---|
| admin / pm / po1 | 1 | 基线不变 |
| vendor1（PMO+VEN-A） | **0**（原 1） | 收窄属预期，AC-14 记录 |
| vpm1（VENDOR_PM+VEN-A） | 0 | 同上 |
| vendor2（VEN-B） | 0 | 不变 |

不在本轮改 `DemoFixture`：把 VEN-A 需求塞进演示版本会改变内部账号的版本内容基线（8→9 条），而 AC-8 只要求内部**版本总数**不变。AC-8 的「详情 200 / 他厂商 403」由负向脚本自建两条版本夹具验证（见 7.3）。

## 4. API 设计

不新增端点。变化集中在「同一端点对新角色返回 403/400」与两个响应字段的扩容。

### 4.1 版本

| 方法 | 路径 | 守卫 | VENDOR_PM 结果 |
|---|---|---|---|
| GET | `/releases?domainId&status&page&size` | MENU `req.release` | 200，仅含「有本厂商需求」的版本 |
| GET | `/releases/{id}` | MENU + 数据域 | 可见 200；他厂商版本 403 `OUT_OF_SCOPE` |
| POST | `/releases` | `release.action.create` | 403 `FORBIDDEN` |
| POST | `/releases/{id}/approval` | `release.action.approve` | 403 |
| POST | `/releases/{id}/reviews` | **改为** `release.action.update` | 403（补洞） |
| POST | `/releases/{id}/reviews/{reviewId}/confirm` | `release.action.approve` | 403 |
| PATCH | `/releases/{id}/quality-settings` | `release.action.update` | 403 |
| PUT | `/releases/{id}/integration-settings` | `release.action.update` | 403 |
| POST/DELETE | `/releases/{id}/requirements[/{rid}]` | `release.action.update` | 403 |
| POST | `/releases/{id}/lock` | `release.action.close` | 403 |
| POST | `/releases/{id}/milestones` | `release.action.update` | 403 |

错误体沿用平台格式：

```json
{ "code": "OUT_OF_SCOPE", "message": "无权访问版本（域 3）", "status": 403 }
{ "code": "FORBIDDEN", "message": "缺少动作权限: release.action.approve", "status": 403 }
```

### 4.2 需求/任务/用例/测试计划（不新增端点）

| 端点 | VENDOR_PM |
|---|---|
| `GET /requirements`、`GET /requirements/{id}` | 200，列表只含 VEN-A 与空厂商行；他厂商 403 |
| `GET /requirements/{id}` 的 `allowedActions` | 只含 `schedule`（状态允许时；否则空数组） |
| `POST /requirements/{id}/actions/{action}` | `schedule` 200；`approve/review_*/decompose/assign_vendor/submit/deliver/...` 403 |
| `GET /defects`、`GET /tasks`、`GET /testcases`、`GET /attachments` | 200，按厂商收窄 |
| `POST/PUT/DELETE` 缺陷处理动作 | 200（`defect:handle` 的 6 个动作） |
| `POST /defects`、`POST /tasks`、`POST /sprints`、`POST /testcases`、`POST /test-plans`、`POST /test-plans/{id}/execute` | 403 |

### 4.3 账号与授权

| 方法 | 路径 | 变化 |
|---|---|---|
| GET | `/admin/permissions` | `roles[]` 10→11（含 `VENDOR_PM/厂商项目经理`）；`functions`、`cells` 结构不变 |
| GET | `/admin/roles` | 内置角色 10→11 |
| GET | `/admin/roles/{id}/grants` | VENDOR_PM 返回 168 格：FUNCTION 12 ALLOW/1 RESTRICTED/47 DENY；ACTION 7 ALLOW/65 DENY（11 条显式 DENY） |
| PUT | `/admin/roles/{id}/grants` | 不改；全量覆盖 168 行，审计 diff>0 才写 |
| POST | `/admin/users` | 新增 400：`roles` 含 VENDOR_PM 且 `vendorId` 缺失/为空 |
| PUT | `/admin/users/{id}` | 新增 400：将生效角色含 VENDOR_PM 且将生效 vendorId 为空；原值不变 |
| GET | `/auth/me/permissions` | `allowedActions` 只含 7 个动作（schedule + 6 缺陷） |

校验失败响应（前端按 `detail.field` 定位表单项）：

```json
{ "code": "VALIDATION_FAILED", "message": "VENDOR_PM 用户必须绑定厂商（vendorId）",
  "status": 400, "detail": { "field": "vendorId" } }
```

> 说明：`vendorId` 为 `0`/空白同样按缺失处理（`longOrNull` 已有归一化）；先校验后写库，`sys_user` / `sys_user_role` 零副作用。

## 5. 前端设计

### 5.1 状态与能力来源（不新增 store）

| 数据 | 来源 | 用途 |
|---|---|---|
| `user.roles` | `/auth/me` profile | `isVendorPm` 判定（只用于 `internalOnly` 例外） |
| `allowedActions` | `/auth/me/permissions` 快照，`auth.canAction(code)` | 所有写按钮的唯一开关 |
| `can(code)` | 功能码（页面准入键） | **只用于页面级/只读能力，不再用于写按钮** |
| 授权页 168 格 | `/admin/roles/{id}/grants`（DB） | 角色页展示，无需前端硬编码角色 |

新增：`web/src/stores/auth.ts`

```ts
const isVendorPm = computed(() => (user.value?.roles ?? []).includes('VENDOR_PM'))
```

`NavContext` 增加 `vendorPm: auth.isVendorPm`，三处构造同步：`web/src/layouts/SideNav.vue:39`、`TopNav.vue:31`、`AdminLayout.vue:44`。

### 5.2 页面与组件改动

| 文件 | 改动 |
|---|---|
| `web/src/views/release/ReleaseListView.vue:135` | 新建按钮 `:disabled="!canAction('release.action.create')"`，title 同步 |
| `web/src/views/release/ReleaseDetailView.vue:77-78` | `canPlan` 拆成 `canEdit=canAction('release.action.update')`、`canApprove=canAction('release.action.approve')`；`canEditScope=canEdit && !scopeLocked` |
| 同上 `:458/:468` | 「添加里程碑」→ `canEdit`；「提交发布审批」→ `canApprove` |
| 同上 `:670/:683/:692/:697` | 集成设置读写 → `canEdit` |
| `web/src/views/deliver/TestCaseLibraryView.vue:34` | `canEdit = canAction('test.action.case_edit')`（导入/新建/复制/删除/保存新版本随之收敛） |
| `web/src/views/deliver/TestPlansView.vue:55-56` | `canCreate=canAction('test.action.plan_create')`、`canApprove=canAction('test.action.plan_approve')`、`canExecute=canAction('test.action.execute')` |
| 同上 `:331/:400/:444` | 新建→`canCreate`；审批→`canApprove`；执行按钮加 `:disabled="!canExecute"`，`executePlan()` 内先 `if (!canExecute.value) return` |
| `web/src/views/admin/AdminUsersView.vue` | 角色选择含 VENDOR_PM 时厂商必填：`vendorRequired = form.roles.includes('VENDOR_PM')`，表单项 `:required`、提示「VENDOR_PM 必须绑定厂商」，提交前拦截并提示；服务端 400 `detail.field=vendorId` 回填表单错误 |
| `web/src/layouts/nav.ts:146-152` | 用例库项加 `vendorPmAllowed: true` |
| `web/src/router/routes/deliver.routes.ts:52-53` | 用例库 meta 加 `vendorPmAllowed: true` |
| `web/src/router/index.ts:116` | `internalOnly` 判定加 `vendorPmAllowed && auth.isVendorPm` 反条件 |
| `web/src/layouts/navAccess.ts:50` | 同上；`NavGuard` 类型加 `vendorPmAllowed?: boolean`，`NavContext` 加 `vendorPm: boolean` |

不改：需求池与需求详情（`allowedActions` 已是唯一真相，`RequirementPoolView.vue:209`、`RequirementDetailView.vue:80`）；授权页 `RolePermissionsView.vue`（角色行来自 DB，168 格自动出现；11 列需横向滚动，`.adm-table-wrap` 已有）。

### 5.3 交互细节

- 按钮被禁用时 title 写「需要权限：<动作码>」而不是旧的 `release:plan`，便于演示时区分「页面可见但不可写」。
- 用例库只读态：隐藏「新建/导入/复制/删除/保存新版本」，保留查询/详情/导出；顶部空状态动作文案按 `canEdit` 收敛。
- 版本页空态：文案维持既有「暂无版本」，不额外写「无权限」——空白是派生可见性的结果，不是错误。

## 6. 关键实现与边界

| 场景 | 行为 |
|---|---|
| 版本无任何需求 | VENDOR 不可见（`EXISTS` 为假）；内部账号可见 |
| 版本只含他厂商需求 | 列表不出现，详情 403 |
| 版本含本厂商 + 他厂商需求 | 可见；详情内需求列表仍按 `ScopeSql` 只回本厂商行 |
| 需求 `owner_vendor_id` 与 `assignee_vendor_id` 不同 | 任一命中即可见（与需求列表同口径） |
| VENDOR_PM + PMO 混用 | 11 条显式 DENY 仍生效（覆盖与角色并集无关），AC-6 已接受 |
| 清空 vpm1 的 vendorId | PUT 400，DB 原值不变（校验在 UPDATE 前） |
| 把普通用户改成 VENDOR_PM 但无厂商 | PUT 400，角色不变 |
| 非 VENDOR_PM 用户传 vendorId | 允许（既有行为），不新增限制 |
| 新建租户 | 11 角色 + 1848 行一次到位；无需等重启 |
| 存量租户升级 | V13 建角色行 → 同一次启动 `RbacBootstrap` 补 168 行 |
| `demo-reset` | 授权清空后按 preset 重写 1848；VENDOR_PM 保留；vpm1 保留（用户不删） |
| 角色授权页保存 | 全量 168 行，11 条 DENY 可被管理员改回 ALLOW（平台管理员有权），审计带 `highRiskGranted` |
| 权限缓存 | 角色/授权变更走既有 epoch 失效；新增角色不引入新缓存键 |
| 审计 | 跨厂商详情拒绝记 `PERMISSION_DENY`（与域越界同口径），不写成功读取 |

## 7. 测试策略

### 7.1 单元测试（`mvn -o -pl platform-contract,platform-common,req-service -am test`）

| 测试类 | 断言 |
|---|---|
| `req-service/src/test/java/com/example/req/sys/PermissionMatrixGridTest.java` | 600→660 格；旧 10×60 子集逐格不变；VENDOR_PM 12 ALLOW/1 RESTRICTED/47 DENY；无重复 |
| `req-service/src/test/java/com/example/req/rbac/RbacSeedEquivalenceTest.java` | 168→168 每角色、总 1848；ACTION 期望改走 `actionAccessFor`；11 条覆盖 = DENY；其余角色 10 行不变 |
| `req-service/src/test/java/com/example/req/rbac/RbacSeederTest.java` | 每角色 168；DENY 行也落库；`preset("VENDOR_PM")` 的 11 条 DENY |
| `req-service/src/test/java/com/example/req/sys/ScopeSqlRegistryTest.java` | `release` + VENDOR 生成 `EXISTS`；TENANT/DOMAIN 不含 `EXISTS`；其它表不变 |
| `req-service/src/test/java/com/example/req/sys/PermissionRouteTableTest.java` | `POST /releases/1/reviews` 需 `release.action.update`；confirm 仍需 approve |
| `TenantProvisionerTest` | 新租户 11 角色 |

单元基线 329 → ≥335（新增 6 条以上）。

### 7.2 门禁脚本

| 脚本 | 改动 |
|---|---|
| `scripts/tests/rbac/s0-schema-migration.sh` | 10→11、1680→1848、600/360/720→660/396/792、每角色 168、Flyway 12→13、全租户 1848 |
| `scripts/tests/rbac/s2-seed-equivalence.sh` | `"10 168"`→`"11 168"`；FUNCTION `600 10 60`→`660 11 60`；空绑定 `4 40 40`→`4 44 44`；MENU 396/比对 352；ACTION 792；新增 `action_override` CTE：11 个 (role,action) 期望 DENY，其余仍用 max 公式；断言例外格恰 11 |
| `scripts/tests/rbac/s5-role-lockout.sh` | 文案 1680→1848（两处），行为断言不变 |
| `scripts/tests/rbac/s8-audit-reset.sh` | 内置角色 10→11；快照正则 `.../11/0`；重置后 1848 |
| `scripts/tests/rbac/s1-registry-three-way.mjs` | 注释与总量 1848 |
| `scripts/tests/access/a2-page-matrix.sh` | `ROLES` 加 vpm1（8 角色）；`expected_code` 加 `3:vpm1`、`4:vpm1` 为 403；其余 200；标题计数同步 |
| `scripts/platform-verify.sh` | 新租户 11 角色；`/admin/permissions` 11 角色；账号名单 12→13（加 vpm1，不含 mgr1） |
| `scripts/platform-seed.sh` | `SEEDED_USERS` 12→13；登录循环 14（含 mgr1）；文案改「14 个账号」 |
| `scripts/platform-test-negative.sh` | 新增第 17 段（见 7.3）：TOTAL 49→≥60 |
| `scripts/platform-demo-reset.sh` | `TOTAL_CHECKS` 57→61；`KEPT_USERS` 13→14（加 vpm1）；新增 vpm1 角色/厂商、vpm1 授权 168、租户总授权 1848、演示账号数 14 共 4 条 |

### 7.3 负向用例（第 17 段：VENDOR_PM 边界）

前置：vpm1 登录；用 admin 造两条夹具（`RUN_TAG` 命名）：
- `NEG-VPM-A-<tag>`：VEN-A 需求 + 关联版本（AC-8 正例：详情 200）；
- `NEG-VPM-B-<tag>`：VEN-B 需求 + 关联版本（AC-8 反例：详情 403，列表不出现）。

| # | 请求 | 期望 |
|---|---|---|
| 1 | vpm1 登录取 token | 200，角色含 VENDOR_PM，vendorId=VEN-A id |
| 2 | `GET /requirements` | 200；所有行 `owner_vendor_id/assignee_vendor_id ∈ {null, VEN-A}`；总数 = vendor1 总数、< admin |
| 3 | `GET /requirements/{VEN-B 需求}` | 403 `OUT_OF_SCOPE` |
| 4 | `POST /requirements/{VEN-A 需求}/actions/approve` | 403；状态与 `wf_instance` 不变 |
| 5 | `POST /requirements/{VEN-A 需求}/actions/schedule` | 200（正例） |
| 6 | `POST /releases`、`/{id}/approval`、`/{id}/reviews`、`/{id}/lock`、`PATCH quality-settings` | 403；release 状态不变 |
| 7 | `GET /releases` | 200；集合 = DB 中「含 VEN-A 需求」的版本；`GET /releases/{VEN-B 版本}` 403 `OUT_OF_SCOPE`；`GET /releases/{VEN-A 版本}` 200 |
| 8 | `POST /admin/roles`、`POST /admin/users`、`PUT /admin/users/{id}` | 403 |
| 9 | admin `POST /admin/users`（roles=[VENDOR_PM]，无 vendorId） | 400 `VALIDATION_FAILED`，`detail.field=vendorId`；`sys_user` 计数不变 |
| 10 | admin `PUT /admin/users/{vpm1}`（vendorId=null） | 400；DB `vendor_id` 仍 VEN-A |
| 11 | `POST /testcases`、`POST /test-plans`、`POST /sprints`、`POST /tasks` | 403 |
| 12 | `GET /testcases` | 200（用例库只读例外）；`POST /testcases` 仍 403 |

每条拒绝都配 `same_count`：被拒绝的请求不得落库。

### 7.4 前端与人工证据

- `cd web && npm run typecheck`（0 error）与 `npm run build`。
- `node scripts/tests/access/a3-nav-audit.mjs`（R10 仍要求 nav 的 pageCode 属于 36 页）。
- `node scripts/tests/access/a4-nav-visibility.mjs`（8 角色；vpm1 侧栏 18 项，其中含用例库只读例外的 1 项；admin.* 不出现；vendor1 深链用例库仍 403）。
- `docs/teamflow/20261116-r24-vendor-pm-role/evidence/` ≥4 张：授权页 168 格（VENDOR_PM 11 条 DENY 可见）、vpm1 需求池（仅 VEN-A 3 条）、他厂商详情 403、版本审批 403 / 排期 200、`demo-reset` 后 1848 行与 vpm1 账号。

### 7.5 全量门禁

```
mvn -o -q -DskipTests compile
mvn -o -pl platform-contract,platform-common,req-service -am test
make rbac-verify           # ≥111
make access-verify         # ≥152
make platform-verify       # ≥168
make platform-test-negative# ≥54
make platform-demo-reset   # ≥60
bash scripts/lint.sh       # ≥22
bash scripts/verify.sh     # 14/14
bash scripts/analytics-i18n-verify.sh
bash scripts/collab-verify.sh
cd web && npm run typecheck && npm run build
```

## 8. 任务拆分（3 个串行任务）

PRD 工程约束：≤3 个任务且串行；每步先 `mvn -o -DskipTests compile`；分支已存在，不新建/不切/不 rebase；未提交的任务夹不得 stash/回滚；收尾一次提交，含代码、脚本数字、任务夹，提交信息带 `runId=tf-mwiuzjpm-poecia`。

### T1 — 契约与数据（角色、168 格、种子、迁移）

- 文件：`platform-contract/.../PermissionMatrix.java`；`req-service/.../sys/RoleCatalog.java`；`req-service/.../seed/RbacSeeder.java`；`req-service/.../seed/SeedService.java`；`req-service/src/main/resources/db/migration/V13__vendor_pm_role.sql`；`PermissionMatrixGridTest`、`RbacSeedEquivalenceTest`、`RbacSeederTest`、`TenantProvisionerTest`；`scripts/tests/rbac/s0,s1,s2,s5,s8`。
- 读：`ActionRegistry.java`、`MenuRegistry.java`、`V3__role_catalog_prd.sql`、`RbacBootstrap.java`、`RoleAdminService.java`。
- 验收：`compile` 通过；4 个单测类绿；`make rbac-verify` 全绿且 ≥111；启动后 t1 为 11 角色/1848 行；VENDOR_PM 的 168 格逐格符合 §2。
- 关键点：`actionAccessFor` 唯一派生点；`preset` 只改 ACTION 一行；`ROLES` append；V13 幂等。

### T2 — 隔离、账号与系统脚本

- 文件：`platform-common/.../scope/ScopeSql.java`；`platform-common/.../security/PermissionRouteTable.java`；`req-service/.../release/ReleaseService.java`；`req-service/.../sys/AdminUserController.java`；`ScopeSqlRegistryTest`、`PermissionRouteTableTest`；`scripts/platform-verify.sh`、`platform-seed.sh`、`platform-test-negative.sh`、`platform-demo-reset.sh`、`scripts/tests/access/a2-page-matrix.sh`。
- 读：`DataScope.java`、`ScopedJdbc.java`、`RequirementErrors.java`、`ReleaseReviewController.java`。
- 验收：`make access-verify` ≥152、`make platform-verify` ≥168、`make platform-test-negative` ≥54、`make platform-demo-reset` ≥60；vpm1 列表/详情/写边界符合 §7.3；`POST /releases/{id}/reviews` 对 vpm1 403；无 vendorId 建号 400 且不落库。
- 关键点：`ScopeSql` 只对 release 加派生；`requireVisible` 用同一条 marker SQL；`validateVendorBinding` 用「将生效角色」；负向夹具用 `RUN_TAG` 唯一命名并 `same_count`。

### T3 — 前端、文档与全量门禁

- 文件：`web/src/stores/auth.ts`；`web/src/router/index.ts`；`web/src/router/routes/deliver.routes.ts`；`web/src/layouts/navAccess.ts`、`nav.ts`、`SideNav.vue`、`TopNav.vue`、`AdminLayout.vue`；`web/src/views/release/ReleaseListView.vue`、`ReleaseDetailView.vue`；`web/src/views/deliver/TestCaseLibraryView.vue`、`TestPlansView.vue`；`web/src/views/admin/AdminUsersView.vue`；`docs/teamflow/DEMO-BY-ROLE.md`；`docs/teamflow/memory.md`；`docs/teamflow/20261116-r24-vendor-pm-role/evidence/`。
- 读：`web/src/api/types.ts`、`web/src/stores/auth.ts` 现有 `can`、`RolePermissionsView.vue`。
- 验收：`npm run typecheck` 0；`npm run build` 通过；vpm1 侧栏 18 项（含用例库例外）、admin 不出现；版本/用例/测试计划写按钮由 `canAction` 关闭；建号弹窗厂商必填且失败时聚焦厂商字段；`node web/e2e/vendor-pm.spec.mjs` 全绿；`bash scripts/lint.sh` ≥22；`make verify` 14/14；analytics/collab 全绿；`DEMO-BY-ROLE` 含 vpm1 行/列/小节与「厂商账号版本为空」说明；`memory.md` 两处同主题替换（10→11、1680→1848）；AGENTS.md 托管区外零改动；一次提交。

## 9. 风险与未决

| 风险 | 影响 | 应对 |
|---|---|---|
| 演示版本对厂商账号为空 | 验收现场「版本页空白」被误判为缺陷 | `DEMO-BY-ROLE.md` 明写原因；负向脚本提供 VEN-A 版本 200 的正例；PRD 已把 vendor1 收窄列为预期 |
| `me.approvals` 仍可见 | 与 AC-10 字面不符 | 记为口径修正 C2；审批动作全部 403；若验收坚持隐藏，再评估「新增第 12 条 DB 覆盖」的成本 |
| 11 条 ACTION 覆盖破坏「168 = preset」公式 | s2 与等价性测试 | s2 增加 11 条例外 CTE；单测改 `actionAccessFor`；例外集合只在 `RbacSeeder` 一处 |
| `ScopeSql` 派生拖慢版本列表 | 大租户分页变慢 | `release_requirement` 主键前缀已覆盖 `release_id`；EXISTS 走半连接；必要时后续加 `(tenant_id, release_id)` 覆盖索引（本轮不做） |
| vendor1 版本列表 1→0 | 既有账号行为变化 | AC-14 明确记录；`DEMO-BY-ROLE` 矩阵同步；不静默改夹具 |
| 路由补洞影响其它角色 | 评审批次创建被 403 | 仅改守卫声明；所有拥有 `release:plan` 的角色 `release.action.update` 派生同档；跑 `make verify` 与 release e2e 回归 |
| 种子与授权页 11 列 | 授权页横向溢出 | 既有 `.adm-table-wrap` 横向滚动；截图确认 |
