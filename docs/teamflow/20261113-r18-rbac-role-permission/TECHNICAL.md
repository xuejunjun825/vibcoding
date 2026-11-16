# 技术方案 · 组织/角色/人员权限可配置化（r18）

- 基线依赖：`docs/teamflow/20261113-r17-biz-submit-action-authz/`（r17 自服务动作谓词、内部回调、403 就地提示继续有效）
- 需求：`docs/teamflow/20261113-r18-rbac-role-permission/PRD.md`（AC-1…AC-22）
- 设计：`docs/teamflow/20261113-r18-rbac-role-permission/DESIGN.md`（视觉/交互/几何，本文不重复）
- 分支：`feat/rbac-admin`（继续使用，不新建）；首个提交信息：`feat(rbac): 组织/角色/人员权限可配置化（V12 三层授权表 + PermissionService 单点）`

---

## 1. 方案一句话

把「角色能做什么」从编译期常量搬进 `sys_role_permission` 一张表（`FUNCTION | MENU | ACTION` 三层同一张表），
用 `PermissionService` 做**唯一判定点**（页面非 DENY → 动作档位 → 既有 DataScope），
契约层新增 `MenuRegistry`（36 页）/`ActionRegistry`（72 动作）作为**唯一注册表**，
V12 建表 + 幂等种子（每租户 1680 行 = 600 + 360 + 720），
并按「升级前后 360+720 个布尔逐格等价」验收——数据驱动，行为零变化。

### 1.1 三条不可动摇的边界

| 边界 | 含义 | 违反后果 |
|---|---|---|
| 单一判定点 | 运行时代码不得再调 `PermissionMatrix.accessOf/accessFor`；只能经 `PermissionService`（lint L19 拦截） | 常量与数据两份真相，改授权不生效 |
| 单一注册表 | 页面码只在 `MenuRegistry`、动作码只在 `ActionRegistry`；`sys_menu/sys_action` 由它们 upsert，**不手写 SQL 行** | DB/常量/前端三向漂移（s1 会红） |
| 行为零变化 | 种子由 `PermissionMatrix` 反推：`action.access = max(所需功能码档位)`、`page.access = max(绑定功能码档位)` | 升级即改权限，演示账号集体变异 |

### 1.2 与 r18 之前的关键差异

| 维度 | 之前（r17） | 现在（r18） |
|---|---|---|
| 判定输入 | `PermissionMatrix` 静态 600 格 | `sys_role_permission` 每租户 1680 行（可编辑） |
| 多角色合并 | 取最高档 `ALLOW > RESTRICTED > DENY` | **任一显式 DENY ⇒ DENY**，否则取最高档，无行 ⇒ DENY（§5.2） |
| 按钮 | 复用功能码 | 独立动作码 `canAction()`，页面为前置 |
| 路由准入 | 路径 → 功能码 | 路径 → **页面码**（+ 写端点动作码） |
| 角色 | 10 个内置、不可增删 | 可增删改停；内置不可删/停；防锁死 |

---

## 2. 现状盘点（改造落点，均已在代码中确认）

| 文件 | 现状 | 本方案动作 |
|---|---|---|
| `platform-contract/src/main/java/com/example/platform/contract/PermissionMatrix.java` | 402 行；`Access{ALLOW,RESTRICTED,DENY}`、`Cell`、`functions()`(60)、`roles()`(10)、`accessFor(role,function)`、`accessOf(function,roles)` | 只**增** `accessForAny(role, List<String>)`（max 语义）；不改 600 格 → `PermissionMatrixGridTest` 600 不动 |
| `platform-common/.../security/PermissionRouteTable.java` | 243 行；`record Requirement(List<String> codes, boolean anyOf)`，约 60 条 path→功能码规则 | 重写为 `record Requirement(List<String> pages, String action, String bodyPrefix)`，约 90 条规则（含动作） |
| `platform-common/.../security/PermissionInterceptor.java` | 113 行；`PermissionMatrix.accessOf(...) != DENY` 判 403 | 改调 `PermissionService`；403 detail 带 `page/action/access` |
| `platform-common/.../security/UserScopeResolver.java` | `roles(tenant,user)` 未过滤角色状态 | 加 `AND r.status = 'ACTIVE'`（停用角色下一次请求即失权） |
| `req-service/.../sys/AdminOrgController.java` | 136 行；只有 GET/POST/PUT | 加 `DELETE /{id}`、`POST /{id}/move-users` |
| `req-service/.../sys/AdminPermissionController.java` | 44 行；从 `PermissionMatrix` 渲染 600 格 | 数据源改 FUNCTION 授权行；严格只读 |
| `req-service/.../sys/AdminUserController.java` | 259 行；`replaceRoles/replaceDomains` 对未知 code **静默跳过** | 先全量校验再写；非法 400/404；写审计 |
| `req-service/.../auth/AuthService.java` | `permissions()` 遍历 `PermissionMatrix.functions()` | 改读 `PermissionService`；新增 `/auth/me/permissions` |
| `web/src/stores/auth.ts` | 只有 `permissions: string[]` + `can/canAny/isRestricted` | 增 `menus/actions/functionCodes/epoch`、`canMenu/canAction/refreshPermissions` |
| `web/src/layouts/nav.ts` | 36 项导航（含 `/notifications`），`functionCode` 必填 | 每项增 `pageCode`（36 个可授权页面；`notifications` 不参与授权） |
| `web/src/router/index.ts` | beforeEach 只按 `meta.functionCode` | 增页面码准入（`pageCodesFor(routeName)` any-of） |

---

## 3. 数据模型与存储

### 3.1 V12 迁移（`req-service/src/main/resources/db/migration/V12__rbac_role_permission.sql`）

```sql
-- 角色补两列（行数不变：ALTER 不插行，AC-1 断言迁移前后 COUNT(*) 相等）
ALTER TABLE sys_role
    ADD COLUMN description VARCHAR(200) NULL AFTER name,
    ADD COLUMN status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' AFTER builtin;

-- 全局页面注册表（无 tenant_id：页面由代码定义，不随租户变化）
CREATE TABLE IF NOT EXISTS sys_menu (
    code           VARCHAR(64)  NOT NULL,
    module         VARCHAR(32)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    route_name     VARCHAR(64)  NOT NULL,
    function_codes VARCHAR(255) NOT NULL DEFAULT '',   -- 逗号分隔；空串 = 登录即可见（AUTHENTICATED）
    sort_no        INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (code),
    KEY idx_menu_module (module, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 全局动作注册表
CREATE TABLE IF NOT EXISTS sys_action (
    code           VARCHAR(96)  NOT NULL,
    menu_code      VARCHAR(64)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    function_codes VARCHAR(255) NOT NULL DEFAULT '',
    high_risk      TINYINT      NOT NULL DEFAULT 0,
    sort_no        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (code),
    KEY idx_action_menu (menu_code, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 租户 × 角色 × 三层授权（600 + 360 + 720 = 1680 行/租户）
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT UNSIGNED NOT NULL,
    role_id      BIGINT UNSIGNED NOT NULL,
    subject_type VARCHAR(16)  NOT NULL,   -- FUNCTION | MENU | ACTION
    subject_code VARCHAR(96)  NOT NULL,
    access       VARCHAR(16)  NOT NULL,   -- ALLOW | RESTRICTED | DENY
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_by   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rp (tenant_id, role_id, subject_type, subject_code),
    KEY idx_rp_lookup (tenant_id, subject_type, subject_code),
    KEY idx_rp_role (tenant_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**为什么用 `role_id` 而不是 `role.code`**：code 不可改（AC-10），但 id 是稳定主键；`sys_user_role` 已是 id 关联，
判定时一次 `JOIN sys_role` 就能同时拿到 code 与 status（停用角色直接不参与合并）。

**为什么不做外键**：与 `sys_user_role`/`sys_user_domain` 保持一致；完整性由 `RoleAdminService`（删除角色同事务删授权）与 `RbacSeeder` 保证。

**为什么全局表不带 tenant_id**：页面/动作是**代码契约**（`MenuRegistry`/`ActionRegistry`），不是租户配置；
带 tenant_id 会退化成「每租户一份注册表」，s1 三向比对就失去意义。

### 3.2 种子策略：DDL 在 SQL，行数据由代码写

`V12` 只做 DDL（+ 注释说明行数据来源）。1680 行与 108 行注册表由 **`RbacSeeder`**（Java）幂等写入：

```java
public final class RbacSeeder {
    public static int seedRegistries(JdbcTemplate jdbc);                  // 36 页 + 72 动作 upsert
    public static int seedTenant(JdbcTemplate jdbc, long tenantId);       // 1680 行 INSERT IGNORE
    public static int seedAllTenants(JdbcTemplate jdbc);                  // 迁移后首启补齐所有租户
    public static Map<String, Integer> counts(JdbcTemplate jdbc, long tenantId);
}
```

理由（这是本方案唯一「迁移不含数据」的取舍，必须在验收时被理解）：

1. 行数据的唯一事实源是 `PermissionMatrix` + 两个 Registry（AC-2/AC-3），在 SQL 里手抄 1680 行必然与常量漂移；
2. 供需要复用同一套推导（`TenantProvisioner` 建租户、`DemoResetService` 重置），SQL 无法被 Java 调用；
3. 迁移与首启在同一进程内（`DatabaseMigrations.run()` 在 `ReqServiceApplication.main` 里先跑 Flyway，`RbacBootstrap` 后跑），
   库外观察不到「迁移完但没种子」的中间态；
4. 幂等方式：`INSERT IGNORE`（唯一键 `uk_rp`）+ 注册表 `ON DUPLICATE KEY UPDATE`。
   新增动作码后重启即自愈；管理员删掉的授权**不是删行而是写 DENY**，所以不会被 `INSERT IGNORE` 复活。

`RbacBootstrap implements ApplicationRunner`（req-service）：
`seedRegistries()` → `seedAllTenants()`，开关 `platform.rbac.seed`（默认 `true`），失败只记 ERROR 不阻断启动（demo 环境可继续排障；
s0 会在 3 秒内把「1680 不全」报出来）。

### 3.3 推导规则（AC-3 的「等价」正是由它保证）

| 层 | 行数/租户 | access 计算 |
|---|---|---|
| FUNCTION | 10 × 60 = 600 | `PermissionMatrix.accessFor(role, function)` 逐格复制 |
| MENU | 10 × 36 = 360 | `max(page.functionCodes)`；`functionCodes` 为空 ⇒ `ALLOW`（登录即可见）；两者都空 ⇒ `ALLOW` |
| ACTION | 10 × 72 = 720 | `max(action.functionCodes)`（多为单码；`req.action.create/submit/withdraw` 为 `[req:report:bus, req:report:dev]`） |

`max` 用 `PermissionMatrix.Access.max()`，顺序 `ALLOW > RESTRICTED > DENY`（既有语义，不新造）。
由此 **360 + 720 个布尔 == 升级前判定**：动作等价于「所需功能码 != DENY」，页面等价于「绑定功能码任一 != DENY」（= 旧 `canAny`）。

### 3.4 新租户、演示重置

- `TenantProvisioner.provision()`：`createRoles()` 之后调用 `RbacSeeder.seedTenant(db.jdbc(), tenantId)`（同一事务）。
- `DemoResetService`（`/internal/demo-reset`）：业务数据重建后追加 `rbac` 段——删除自定义角色（及授权行）、
  内置角色三层授权按 `RbacSeeder.seedTenant` 覆盖回预设（此处是 **upsert 覆盖**，因为重置的语义就是回到预设），
  最后 `permissionService.invalidate(tenantId)` 让 epoch+1。响应 `counts` 增 `role_permission`、`custom_roles` 两个键。

---

## 4. 注册表契约（单一来源）

### 4.1 `MenuRegistry`（36 页）

`platform-contract/.../MenuRegistry.java`

```java
public record MenuPage(String code, String module, String name, String routeName,
                       List<String> functionCodes, int sort)
public static List<MenuPage> pages();
public static List<String> modules();                 // 我的/需求域/交付域/协作/分析域/设置
public static Optional<MenuPage> page(String code);
public static boolean isRegistered(String code);
public static List<String> codes();
```

| 模块 | 页面码 | 名称 | 路由 name | 绑定功能码 |
|---|---|---|---|---|
| 我的 | `me.todo` | 我的待办 | approvals | （空=AUTHENTICATED） |
| 我的 | `me.tasks` | 我的任务 | requirements | （空） |
| 我的 | `me.workbench` | 工作台 | workbench | plan:execute, case:manage |
| 需求域 | `req.pool` | 需求池 | requirements | （空） |
| 需求域 | `req.create` | 提报需求 | requirement-create | req:report:bus, req:report:dev |
| 需求域 | `req.release` | 版本计划 | releases | release:plan |
| 需求域 | `req.iteration` | 迭代 | iterations | task:manage |
| 交付域 | `defect.list` | 缺陷 | defects | （空） |
| 交付域 | `test.cases` | 用例库 | testcases | case:manage |
| 交付域 | `test.plans` | 测试计划 | test-plans | plan:manage |
| 交付域 | `test.strategies` | 测试策略 | test-strategies | strategy:manage |
| 交付域 | `test.reports` | 测试报告 | test-reports | report:generate, report:sign |
| 交付域 | `test.envs` | 环境台账 | test-envs | env:manage |
| 交付域 | `test.executors` | 执行器 | test-executors | TEST_EXECUTOR_VIEW |
| 交付域 | `deploy.orders` | 发布单 | deploy-orders | deploy:create |
| 协作 | `collab.gantt` | 甘特 | gantt | GANTT_VIEW |
| 协作 | `collab.wiki` | Wiki | wiki | WIKI_VIEW |
| 协作 | `collab.value-stream` | 价值流 | value-stream | FLOW_VIEW |
| 分析域 | `qa.trace` | 追溯矩阵 | traceability | trace:view |
| 分析域 | `qa.gates` | 质量门总览 | quality-gates | gate:view |
| 分析域 | `qa.metrics` | 质量指标 | quality-metrics | qualitymetric:view |
| 分析域 | `defect.board` | 质量看板 | defect-board | defect:board |
| 分析域 | `code.policies` | 分支策略 | code-policies | CCODE_POLICY_VIEW |
| 分析域 | `ana.portal` | 岗位工作台 | portal | PORTAL_VIEW |
| 分析域 | `ana.dashboard` | 数据看板 | analytics-dashboard | PORTAL_VIEW |
| 分析域 | `ana.reports` | 自助报表 | report-list | REPORT_VIEW |
| 设置 | `admin.orgs` | 组织与租户 | admin-orgs | tenant:manage, org:manage |
| 设置 | `admin.users` | 用户与角色 | admin-users | org:manage |
| 设置 | `admin.vendors` | 厂商与域 | admin-vendors | org:manage |
| 设置 | `admin.permissions` | 权限总览 | admin-permissions | org:manage |
| 设置 | `admin.roles` | 角色与权限 | admin-roles | org:manage |
| 设置 | `admin.workflows` | 流程定义 | admin-workflows | workflow:manage |
| 设置 | `admin.api-clients` | API 客户端 | admin-api-clients | apiclient:manage |
| 设置 | `admin.integrations` | 集成配置 | admin-integrations | integration:manage |
| 设置 | `admin.metrics` | 指标字典 | admin-metrics | metric:manage |
| 设置 | `admin.audit` | 审计日志 | admin-audit | audit:view |

**计数口径**：36 = 我的 3 + 需求域 4 + 交付域 8 + 协作 3 + 分析域 8 + 设置 10。
`/notifications`（routeName `notifications`）**不是可授权页面**（登录即可读，无数据范围），因此不注册、不进 1680；
`nav.ts` 里它保留 `functionCode: AUTHENTICATED` 且 `pageCode` 为空串（s1 的允许集 = 导航项 − notifications + admin-roles）。

**共享路由**：`/requirements` 同时承载「我的任务」与「需求池」两页（`me.tasks` / `req.pool`），
路由准入用 **any-of**（两码任一即可），导航可见性用**条目自己的 pageCode**（两码分属不同模块，语义不同）。

### 4.2 `ActionRegistry`（72 动作）

`platform-contract/.../ActionRegistry.java`

```java
public record ActionDef(String code, String menuCode, String name,
                        List<String> functionCodes, boolean highRisk, int sort)
public static List<ActionDef> actions();
public static Optional<ActionDef> action(String code);
public static boolean isRegistered(String code);
public static ActionDef require(String code);          // 未注册抛 IllegalArgumentException（启动期用）
public static List<ActionDef> of(String menuCode);
public static List<String> codes();
public static Set<String> HIGH_RISK;                   // 3 个
```

| 动作码 | 页面 | 功能码 | 高危 |
|---|---|---|---|
| `req.action.submit` 提交审批 | req.pool | req:report:bus, req:report:dev | |
| `req.action.withdraw` 撤回 | req.pool | req:report:bus, req:report:dev | |
| `req.action.approve` 审批通过 | req.pool | req:review | |
| `req.action.reject` 审批驳回 | req.pool | req:review | |
| `req.action.review_start` 发起评审 | req.pool | req:review | |
| `req.action.review_pass` 评审通过 | req.pool | req:review | |
| `req.action.review_reject` 评审不通过 | req.pool | req:review | |
| `req.action.accept` 验收 | req.pool | req:review | |
| `req.action.close` 关闭 | req.pool | req:review | |
| `req.action.schedule` 排期 | req.pool | release:plan | |
| `req.action.start_dev` 开始开发 | req.pool | req:split | |
| `req.action.deliver` 交付 | req.pool | req:deliver | |
| `req.action.merge` 合入 | req.pool | req:deliver | |
| `req.action.finish` 完成 | req.pool | req:deliver | |
| `req.action.submit_test` 提交测试 | req.pool | req:deliver | |
| `req.action.test_pass` 测试通过 | req.pool | test:quick | |
| `req.action.decompose` 拆解 | req.pool | req:split | |
| `req.action.assign_vendor` 指派厂商 | req.pool | req:assign-vendor | |
| `task.action.create` 建任务 | req.iteration | task:manage | |
| `task.action.assign` 指派任务 | req.iteration | task:manage | |
| `task.action.update` 改任务 | req.iteration | task:manage | |
| `task.action.close` 关任务 | req.iteration | task:manage | |
| `sprint.action.create` 建迭代 | req.iteration | task:manage | |
| `sprint.action.close` 关迭代 | req.iteration | task:manage | |
| `test.action.case_edit` 编辑用例 | test.cases | case:manage | |
| `test.action.case_review` 评审用例 | test.cases | CASE_REVIEW_DECIDE | |
| `test.action.plan_create` 建测试计划 | test.plans | plan:edit | |
| `test.action.plan_approve` 审批计划 | test.plans | plan:approve | |
| `test.action.execute` 执行计划 | test.plans | plan:execute | |
| `test.action.report_sign` 报告签发 | test.reports | report:sign | |
| `test.action.gate_waive` 质量门豁免 | qa.gates | waiver:approve | |
| `defect.action.create` 报缺陷 | defect.list | defect:report | |
| `defect.action.assign` 派缺陷 | defect.list | defect:handle | |
| `defect.action.handle` 处理缺陷 | defect.list | defect:handle | |
| `defect.action.resolve` 修复完成 | defect.list | defect:handle | |
| `defect.action.verify` 验证关闭 | defect.list | defect:handle | |
| `defect.action.reopen` 重开 | defect.list | defect:handle | |
| `defect.action.close` 关闭 | defect.list | defect:handle | |
| `release.action.create` 建版本 | req.release | release:plan | |
| `release.action.update` 改版本 | req.release | release:plan | |
| `release.action.approve` 版本审批 | req.release | release:plan | |
| `release.action.close` 关闭版本 | req.release | release:plan | |
| `deploy.action.create` 建发布单 | deploy.orders | deploy:create | |
| `deploy.action.execute` 执行发布 | deploy.orders | deploy:execute | |
| `deploy.action.force_release` 强制发布 | deploy.orders | deploy:force | **是** |
| `wiki.action.edit` 编辑 Wiki | collab.wiki | WIKI_EDIT | |
| `wiki.action.admin` 管理 Wiki | collab.wiki | WIKI_ADMIN | |
| `gantt.action.edit` 编辑甘特 | collab.gantt | GANTT_EDIT | |
| `flow.action.config` 配置价值流 | collab.value-stream | FLOW_CONFIG | |
| `report.action.generate` 生成报表 | ana.reports | report:generate | |
| `report.action.sign` 报表签发 | ana.reports | report:sign | |
| `report.action.export` 导出报表 | ana.reports | report:export | |
| `report.action.share` 分享报表 | ana.reports | REPORT_SHARE | |
| `report.action.define` 定义报表 | ana.reports | report:generate | |
| `report.action.publish` 发布报表 | ana.reports | REPORT_SHARE | |
| `portal.action.config` 配置工作台 | ana.portal | PORTAL_CONFIG | |
| `org.action.create` 建组织 | admin.orgs | org:manage | |
| `org.action.update` 改组织 | admin.orgs | org:manage | |
| `org.action.delete` 删组织 | admin.orgs | org:manage | |
| `org.action.move_users` 迁移人员 | admin.orgs | org:manage | |
| `user.action.create` 建用户 | admin.users | org:manage | |
| `user.action.update` 改用户 | admin.users | org:manage | |
| `user.action.reset_password` 重置密码 | admin.users | org:manage | |
| `user.action.disable` 停用用户 | admin.users | org:manage | **是** |
| `role.action.create` 建角色 | admin.roles | org:manage | |
| `role.action.update` 改角色 | admin.roles | org:manage | |
| `role.action.status` 启停角色 | admin.roles | org:manage | |
| `role.action.grant` 改授权 | admin.roles | org:manage | **是** |
| `vendor.action.manage` 管理厂商 | admin.vendors | org:manage | |
| `workflow.action.manage` 管理流程 | admin.workflows | workflow:manage | |
| `integration.action.manage` 管理集成 | admin.integrations | integration:manage | |
| `apiclient.action.manage` 管理 API 客户端 | admin.api-clients | apiclient:manage | |

合计 72（18 + 4 + 2 + 7 + 7 + 4 + 3 + 2 + 1 + 1 + 6 + 1 + 4 + 4 + 4 + 1 + 1 + 1 + 1）；
高危 3 个（`deploy.action.force_release`、`user.action.disable`、`role.action.grant`）。

---

## 5. 判定单点：`PermissionService`

### 5.1 位置与装配

`platform-common/src/main/java/com/example/platform/common/security/PermissionService.java`（`@Service`）。
两个服务（req 8082 / deliver 8083）都 `scanBasePackages="com.example"`，因此判定逻辑**一份代码两处生效**。
数据源用 `JdbcTemplate`（`platform-common` 已依赖 `spring-boot-starter-data-jpa`，`AuditService`/`UserScopeResolver` 同款写法）。

```java
public enum Layer { FUNCTION, MENU, ACTION }

public record Snapshot(long tenantId, long epoch, long loadedAtMillis,
                       Map<String, Map<String, Access>> byRole)      // roleCode -> code -> access
public record Effective(long epoch,
                        Map<String, Access> functions,
                        Map<String, Access> menus,
                        Map<String, Access> actions)

public Effective effective();                                  // 按 (epoch, roles) 每请求记忆一次
public Access accessOf(Layer layer, String code);
public boolean canFunction(String code);
public boolean canMenu(String menuCode);
public boolean canAction(String actionCode);
public boolean decide(String menuCode, String actionCode);     // 三序判定，RESTRICTED 通过
public long epoch();
public void invalidate(long tenantId);                         // 写授权后调用：epoch+1、丢弃快照
public List<String> granted(Layer layer);                      // 非 DENY，按注册表顺序
```

`PermissionGrantsRepository`（同包）只做一件事：

```sql
SELECT r.code, p.subject_type, p.subject_code, p.access
  FROM sys_role_permission p
  JOIN sys_role r ON r.id = p.role_id AND r.tenant_id = p.tenant_id
 WHERE p.tenant_id = ? AND r.status = 'ACTIVE'
```

（停用角色在 SQL 层就被剔除，无需在 Java 里再判，也不会因为缓存里存了旧角色而复活。）

### 5.2 合并规则（AC-6）

```java
static Access merge(List<Access> present) {   // present = 该 subject 上、调用者各角色「存在的行」
    if (present.isEmpty()) return DENY;        // 未授权即 DENY
    if (present.contains(DENY)) return DENY;   // 任一显式 DENY ⇒ DENY
    return present.stream().reduce(Access.DENY, Access::max);  // ALLOW > RESTRICTED
}
```

- 「存在」= 该角色在该 subject 上**有行**。种子给每个内置角色写满 1680 行（含大量 DENY），
  因此内置角色组合的实际语义就是**交集**（多给一个角色只会更严）——这是 PRD 明确要的取舍，
  前端在用户分配抽屉里写明「权限取并集，任一 DENY 即 DENY」。
- 新建自定义角色默认**无行**（中性），不会因为「被多分了一个空角色」而清空既有权限；
  管理员一旦在授权页保存，就会写满 1680 行（含 DENY），此后参与交集。

### 5.3 三序判定（AC-5/AC-7）

```java
boolean decide(String menu, String action) {
    if (accessOf(MENU, menu) == DENY) return false;              // ① 页面非 DENY
    Access a = accessOf(ACTION, action);                          // ② 动作档位
    if (a == DENY) return false;
    return true;                                                  // ③ RESTRICTED 也放行，DataScope 收窄在 SQL 层
}
```

第③序**不改任何代码**：`DataScope`/`ScopedJdbc`/`UserScopeResolver.domains()` 原样保留；
`RESTRICTED` 的语义仍是「过检查 + 范围收窄」，与 r17 一致（AC-7 要求零改动）。

### 5.4 缓存与 epoch（AC-8）

| 项 | 值 | 说明 |
|---|---|---|
| 粒度 | 每租户一份快照 | 一次查询把该租户全部授权行读进 `byRole`（≤1680 行，约 120KB 堆） |
| TTL | 45s（≤60s） | 到点后下一次请求同步重载（单飞：`computeIfAbsent` + 每租户锁） |
| 失效 | `invalidate(tenantId)` → `epoch++`、丢快照 | 授权保存、角色增删改停、用户换角色、demo-reset 都要调 |
| `me/permissions` | 返回 `epoch` | 前端丢弃 `epoch` 更小的响应，防止旧响应回填菜单 |
| 跨服务 | req 即时；deliver ≤45s | 授权写端点只在 req-service；deliver 侧靠 TTL 收敛（非目标：多实例广播） |

每请求记忆：`PermissionService` 持有一个 `ThreadLocal<Memo(epoch, rolesKey, Effective)>`，
`PermissionInterceptor.afterCompletion` 调 `PermissionService.clearRequestMemo()`；
roles 变了（同请求内不会变）或 epoch 变了就重算。

### 5.5 性能预算

判定路径新增：1 次 `ThreadLocal` 命中 + ≤3 次 `Map.get`，**无额外 SQL**（快照命中时）。
快照未命中时 1 次查询（1680 行）≈ 6–10ms，45s 摊一次；`platform-perf` 的 p95 基线不动。

---

## 6. 路由准入、动作声明与 fail-fast（AC-9）

### 6.1 `PermissionRouteTable` 新形状

```java
public record Requirement(List<String> pages, String action, String bodyPrefix) {
    public static Requirement page(String... pages);
    public static Requirement action(String page, String action);
    public static Requirement bodyAction(String page, String prefix);   // 动作码取自请求体
    public boolean fromBody();
}
public static Requirement requirementFor(String method, String path);   // null = 不设防
public static List<String> guardedPatterns();
public static final Set<String> UNGATED;   // /auth/login、/internal/**、/actuator/**、/openapi/**、webhook、/v1/test-executions
```

规则示例（顺序首匹配）：

| 方法 | 路径 | page | action |
|---|---|---|---|
| GET | `^/requirements(/.*)?$` | req.pool, me.tasks | — |
| POST | `^/requirements$` | req.create | `req.action.create` |
| POST | `^/requirements/\d+/actions$` | req.pool | body `req.action.` |
| GET | `^/defects(/.*)?$` | defect.list | — |
| POST | `^/defects/\d+/actions$` | defect.list | body `defect.action.` |
| GET | `^/admin/roles(/.*)?$` | admin.roles | — |
| POST | `^/admin/roles$` | admin.roles | `role.action.create` |
| PUT | `^/admin/roles/\d+$` | admin.roles | `role.action.update` |
| PATCH | `^/admin/roles/\d+/status$` | admin.roles | `role.action.status` |
| DELETE | `^/admin/roles/\d+$` | admin.roles | `role.action.delete`?（无此码，用 `role.action.update`） |
| PUT | `^/admin/roles/\d+/permissions$` | admin.roles | `role.action.grant` |
| GET | `^/admin/permissions$` | admin.permissions | — |
| POST | `^/deploy-orders/\d+/force-release$` | deploy.orders | `deploy.action.force_release` |

**删除角色**没有独立动作码（72 个里不含 delete）：删除走 `role.action.update`，避免为一次低频操作再加码；
s9 的动作覆盖率因此允许「一个动作码对应多个端点」。

> 实现注意：规则表从 60 条涨到约 90 条，**保持「首匹配生效 + 具体路径在前」**的既有写法；
> 新增规则必须同步 `guardedPatterns()`（access a5 与 s9 都读它）。

### 6.2 注解（声明式，用于 `@RequiresPermission` 的同类场景）

```java
public @interface RequiresAction { String value(); }                       // 完整动作码
public @interface RequiresActionFromBody { String module(); String field() default "action"; }
public @interface PageOnly { String reason(); }                            // 显式声明「只受页面保护」
```

优先级：**注解 > 路由表**。`@RequiresActionFromBody(module="req")` ⇒ 动作码 = `req.action.` + body[`action`]。
请求体只读一次：新增 `RequestBodyCacheFilter`（`@Order(HIGHEST_PRECEDENCE + 25)`，仅对 `POST …/actions` 包装
`CachedBodyHttpServletRequest`，可重复读 `getInputStream()`），拦截器解析后控制器照常 `@RequestBody`。

### 6.3 启动期 fail-fast

`PermissionDeclarationValidator implements ApplicationRunner`（common）：

1. 遍历 `RequestMappingHandlerMapping.getHandlerMethods()`；
2. 写方法（POST/PUT/PATCH/DELETE）必须解析出 `(pages, action)`：注解 → 路由表；
3. 校验 `pages ⊆ MenuRegistry.codes()`、`action ∈ ActionRegistry.codes()` 且 `action.menuCode ∈ pages`；
4. `null` requirement 且无 `@PageOnly` ⇒ 违规；`@PageOnly` 必须命中白名单（`PERMISSION_PAGE_ONLY_WHITELIST`）；
5. 违规一次性抛 `IllegalStateException`（列出前 20 条：`METHOD path → 原因`），服务**起不来**。

开关 `platform.rbac.fail-fast`（默认 `true`；仅本地排障可置 false，s9 会断言默认值）。

### 6.4 403 与审计

```json
{"code":"FORBIDDEN","message":"无权执行该操作",
 "detail":{"page":"req.pool","action":"req.action.approve","access":"DENY","method":"POST","path":"/api/req/requirements/12/actions"}}
```

拦截器仍走 `auditService.recordInNewTx("PERMISSION_DENIED","permission",null,detail)`（r17 行为不变），
detail 增加 `page/action/access` 三个键（QA 用它定位「是页面还是动作拦的」）。

---

## 7. API 设计

统一错误体沿用 `ApiException`：`{"code","message","detail"}`；下表只列新增/变更端点。

### 7.1 角色（`req-service`，页面 `admin.roles`）

| 方法 | 路径 | 入参 | 出参 | 错误 |
|---|---|---|---|---|
| GET | `/admin/roles` | — | `RoleRow[]`：`{id,code,name,description,builtin,status,userCount,updatedAt}` | — |
| POST | `/admin/roles` | `{code,name,description?}` | `RoleRow` | 400 `INVALID_ROLE_CODE`；409 `ROLE_CODE_TAKEN` |
| PUT | `/admin/roles/{id}` | `{name,description?}` | `RoleRow` | 404 `ROLE_NOT_FOUND`；400 `CODE_IMMUTABLE`（body 带不同 code） |
| PATCH | `/admin/roles/{id}/status` | `{status:ACTIVE\|DISABLED}` | `RoleRow` | 409 `BUILTIN_ROLE_IMMUTABLE`；409 `ADMIN_LOCKOUT_RISK` |
| DELETE | `/admin/roles/{id}` | — | 204 | 409 `BUILTIN_ROLE_IMMUTABLE`；409 `ROLE_IN_USE {users}` |
| GET | `/admin/roles/{id}/permissions` | — | `RoleGrantView`（见下） | 404 |
| PUT | `/admin/roles/{id}/permissions` | `{grants:[{subjectType,subjectCode,access}],confirmHighRisk?}` | `GrantSaveResult` | 400 `UNKNOWN_SUBJECT`；409 `ADMIN_LOCKOUT_RISK`；409 `HIGH_RISK_REQUIRES_CONFIRM` |

`RoleGrantView`（一次取整棵树，DESIGN §5 要求「一次 GET」）：

```json
{"role":{"id":5,"code":"DEV","name":"研发工程师","builtin":true,"status":"ACTIVE"},
 "epoch":12,
 "modules":[{"code":"需求域","name":"需求域","pages":[
   {"code":"req.pool","name":"需求池","routeName":"requirements","access":"ALLOW",
    "functionCodes":[{"code":"req:report:bus","access":"ALLOW"}],
    "actions":[{"code":"req.action.approve","name":"审批需求","access":"DENY",
                "highRisk":false,"functionCodes":["req:review"]}]}]}],
 "warnings":[{"code":"PAGE_DENY_ACTION_GRANTED","page":"req.pool","actions":["req.action.approve"]}]}
```

`GrantSaveResult`：`{epoch, added, removed, changed, highRiskGranted:[...], warnings:[...], effective:{menus:[],actions:[]}}`。

语义（AC-11）：
- `grants` 是**全量覆盖**：未出现的 subject 视为 DENY（服务端按三类注册表补全，保证保存后恒为 1680 行）；
- 校验先做全量（注册表命中 + 角色存在 + 同租户），任一不合法 **400 且不落库**；
- 「动作已授但页面 DENY」允许保存：`warnings` 回带 `PAGE_DENY_ACTION_GRANTED`，`effective` 里该动作不出现；
- 一次保存恰好 1 条 `ROLE_PERMISSION_UPDATE` 审计：`{added,removed,changed,highRiskGranted,before,after}`（只记变更项，**无差异不写**）；
- 成功后 `permissionService.invalidate(tenantId)`（epoch+1，撤销动作下一次请求即 403）。

### 7.2 权限总览（只读，AC-17）

| 方法 | 路径 | 出参 |
|---|---|---|
| GET | `/admin/permissions` | `{roles:[{code,name}], functions:[{code,allow:[],restricted:[],deny:[]}]}` |

数据源改为 `sys_role_permission` 的 FUNCTION 行（按当前租户全部角色），**不再读 `PermissionMatrix`**；
`PUT/POST/DELETE` 一律不存在（lint L20 + s9 断言「控制器无写映射」）。

### 7.3 用户（AC-13）

| 方法 | 变更 |
|---|---|
| POST `/admin/users` | 校验前置：角色 code 必须存在（否则 400 `UNKNOWN_ROLE {codes}`）、组织/厂商必须属于本租户（否则 404 `ORG_NOT_FOUND`/`VENDOR_NOT_FOUND`）、域 id 必须存在（400 `UNKNOWN_DOMAIN`）；全部通过后才写，事务回滚不留半成品 |
| PUT `/admin/users` | 同上；`roles`/`domainIds` 做差集写（新增/删除 + 1 条 `USER_ROLES`/`USER_ORG` 审计，含 `added/removed`） |
| PATCH `/admin/users/{id}/status` | 停用写 `USER_STATUS` 审计；不允许停用最后一个启用管理员（409 `ADMIN_LOCKOUT_RISK`） |

### 7.4 组织（AC-14）

| 方法 | 路径 | 入参 | 出参 | 错误 |
|---|---|---|---|---|
| DELETE | `/admin/orgs/{id}` | — | `{deleted:1}` | 409 `ORG_HAS_CHILDREN {children}`；409 `ORG_HAS_USERS {users}`；404 |
| POST | `/admin/orgs/{id}/move-users` | `{userIds:[],targetOrgId}` | `{moved:n}` | 400 `EMPTY_SELECTION`/`INVALID_TARGET`；404 |

- 删除前同事务统计子组织数、直属人数（不递归，只算直接子节点与 `org_id` 命中），非零即 409 并给出人数；
- `move-users`：目标组织必须同租户且不是源组织；`UPDATE sys_user SET org_id=? WHERE tenant_id=? AND id IN (...)`；
  写 1 条 `ORG_MOVE_USERS` 审计（`{fromOrgId,toOrgId,userIds,count}`）；`userIds` 里跨租户 id ⇒ 404 `USER_NOT_FOUND`（整批回滚）。

### 7.5 认证（AC-16）

| 方法 | 路径 | 出参 |
|---|---|---|
| GET | `/auth/me/permissions` | `{epoch, functionCodes:[], menus:[], actions:[], restricted:{functionCodes:[],menus:[],actions:[]}}` |
| GET | `/auth/me` | 原形状不变（`permissions` 改由 `PermissionService` 生成，兼容旧前端） |

`menus`/`actions`/`functionCodes` 只含非 DENY；`restricted` 是其中 `RESTRICTED` 的子集（前端画范围 chip）。
`epoch` 供 `refreshPermissions` 防旧响应回填。

### 7.6 前端 API 封装（`web/src/api/admin.ts` + `session.ts`）

```ts
listRoles(): Promise<RoleRow[]>
createRole(p): Promise<RoleRow>; updateRole(id,p): Promise<RoleRow>
setRoleStatus(id,status): Promise<RoleRow>; deleteRole(id): Promise<void>
getRoleGrants(id): Promise<RoleGrantView>
saveRoleGrants(id, {grants, confirmHighRisk?}): Promise<GrantSaveResult>
deleteOrg(id): Promise<void>; moveOrgUsers(id,{userIds,targetOrgId}): Promise<{moved:number}>
fetchMyPermissions(): Promise<MyPermissions>   // GET /api/req/auth/me/permissions
```

---

## 8. 前端设计

### 8.1 文件清单（与 DESIGN §4 对齐）

| 类型 | 文件 | 职责 |
|---|---|---|
| 新 | `web/src/views/admin/AdminRolesView.vue` | 角色表 + 新建/编辑抽屉入口 + 停用确认 |
| 新 | `web/src/views/admin/RolePermissionsView.vue` | 三级授权页（左树 300 / 右栏 752 / 粘底保存条） |
| 新 | `web/src/views/admin/components/RoleFormDrawer.vue` | 新建/编辑角色（640 抽屉） |
| 新 | `web/src/views/admin/components/UserAssignDrawer.vue` | 用户四要素分配（640） |
| 新 | `web/src/views/admin/components/OrgMoveDrawer.vue` | 人员迁移（560） |
| 新 | `web/src/views/admin/components/rbac/PermissionTree.vue` | 模块→页面树，`aria-checked=mixed`，键盘导航 |
| 新 | `web/src/views/admin/components/rbac/PageGrantPanel.vue` | 页头 + 功能码折叠区 + 动作表 |
| 新 | `web/src/views/admin/components/rbac/GrantSeg.vue` | 三档控件（ALLOW/RESTRICTED/DENY，170×32） |
| 新 | `web/src/views/admin/components/rbac/RiskPill.vue` | 高危 pill（40×20） |
| 新 | `web/src/views/admin/components/rbac/SaveBar.vue` | 差量计数 + 保存/放弃（sticky 56） |
| 新 | `web/src/views/admin/components/rbac/useRoleGrants.ts` | snapshot/draft/dirty、三态公式、批量、diff、搜索 |
| 新 | `web/src/layouts/pageRegistry.ts` | `pageCodesFor(routeName)`（从 nav 条目派生） |
| 改 | `web/src/stores/auth.ts` | menus/actions/functionCodes/epoch + canMenu/canAction/refreshPermissions |
| 改 | `web/src/composables/usePermission.ts` | `canAction()`；`can()` 保持功能码语义 |
| 改 | `web/src/layouts/nav.ts` | 每项加 `pageCode`（36 项）+ 新增 admin.roles 条目 |
| 改 | `web/src/layouts/navAccess.ts` | `menus.has(pageCode)` 与功能码双条件 |
| 改 | `web/src/router/index.ts` | 页面码准入（any-of）+ 保留 functionCode 兼容 |
| 改 | `web/src/views/admin/AdminUsersView.vue` | 角色/域/组织/厂商改抽屉；错误码就地提示 |
| 改 | `web/src/views/admin/AdminOrgsView.vue` | 删除（409 给人数）+ 迁移抽屉 |
| 改 | `web/src/views/admin/AdminPermissionsView.vue` | 只读总览 + 「去角色授权」指路 |
| 改 | `web/src/views/admin/admin-shared.css` | `.rbac-*` 全部新样式（不改既有类） |
| 改 | `web/src/styles/tokens.css` | 17 个令牌（DESIGN §7） |
| 改 | `web/src/router/routes/deliver.routes.ts` | 两条新路由（`admin-roles`、`role-permissions`，`meta.pageCode`） |

### 8.2 状态管理（`stores/auth.ts`）

```ts
const functionCodes = ref<string[]>([])
const menus = ref<string[]>([])
const actions = ref<string[]>([])
const restricted = ref<{functionCodes:string[];menus:string[];actions:string[]}>({...})
const epoch = ref(0)

const menuSet = computed(() => new Set(menus.value))
const actionSet = computed(() => new Set(actions.value))

function canMenu(code?: string | null): boolean         // 空码 = true（AUTHENTICATED 页面）
function canAction(code?: string | null): boolean
function canAnyMenu(codes: string[]): boolean
async function refreshPermissions(): Promise<void>      // epoch 更大才覆盖，永不回填旧响应
```

- `loadMe()`：`GET /auth/me` + `refreshPermissions()` 并发；失败静默（沿用现状）。
- `apply()`（登录）：用登录响应的 `permissions` 先渲染，再 `void loadMe()` 补齐三集合。
- **不持久化** `menus/actions` 到 sessionStorage 之外：刷新页面从 `/auth/me/permissions` 重取，避免陈旧菜单；
  sessionStorage 只存 token/tenant/user（现状不变）。

### 8.3 三态与保存（DESIGN §6 的落地口径）

勾选态由 `useRoleGrants.ts` 纯函数给出（单测直接覆盖，不依赖 DOM）：

```ts
type Tri = 'unchecked' | 'indeterminate' | 'checked'
function pageTri(page: PageGrant): Tri   // 六行真值表，DESIGN §6 I1
function togglePage(page: PageGrant): void
function toggleModule(module: ModuleGrant): void
function diffCount(): {added:number;removed:number;changed:number}
```

保存 = 1 次 `PUT` 全量 + `await auth.refreshPermissions()`（不 reload、不重登）。
409 `ADMIN_LOCKOUT_RISK` → 480 弹窗列被拒码，提供「保留这两项」= 回滚本地 draft 后重试；
409 `HIGH_RISK_REQUIRES_CONFIRM` → 二次确认弹窗，确认后带 `confirmHighRisk:true` 重发。

### 8.4 导航与路由守卫

- `navAccess.navVisible(item, ctx)`：
  `item.pageCode` 非空 ⇒ 必须 `ctx.menus.has(pageCode)`；再叠加原 `canAny(functionCode)`（服务端已按两条件算过 menus，这里是第二道保险）。
  `pageCode` 为空（`notifications`）⇒ 退化为原 AUTHENTICATED 逻辑。
- `router.beforeEach`：`pageCodesFor(to.name)` 非空且 `!auth.canAnyMenu(codes)` ⇒ `/403?code=<codes>`；
  之后保留原 `meta.functionCode` 检查（未注册 pageCode 的路由不回归）。
- 深链 `/admin/roles/:id/permissions` 的 `to.name` 为 `role-permissions` ⇒ 映射 `admin.roles`。

### 8.5 按钮判据

- 新增/改造页面（角色、授权、用户、组织、需求详情动作区）一律 `canAction('xxx.action.yyy')`；
- 未迁移的历史页面继续用 `can()`（功能码）——功能码授权仍在，行为不变；
  这是 AC-22「改动面限于两页三视图」的直接后果，`docs/teamflow/memory.md` 记为后续收口项；
- 删除任何本地 `ACTION_META`/矩阵副本（若有）——lint L20 断言 `web/src` 内不出现 `PermissionMatrix` 字样。

---

## 9. 关键实现点与边界

| # | 场景 | 处理 |
|---|---|---|
| 1 | 内置角色 | `builtin=1`：可改 name/description、可改授权；**不可删、不可停**（409 `BUILTIN_ROLE_IMMUTABLE`）；code 永不可改 |
| 2 | 防锁死 | `RoleGuard.assertNotLockout(roleId, draft)`：`PLATFORM_ADMIN` 的 `org:manage`/`tenant:manage` 必须为 ALLOW；且改后仍存在 ≥1 个 `ACTIVE` 且持有 PLATFORM_ADMIN 的用户；违反 409 `ADMIN_LOCKOUT_RISK`，**事务回滚、库不变** |
| 3 | 撤销生效 | 保存成功 → `invalidate(tenantId)`；下一次请求 `effective()` 重载 → 403（AC-8 的「第 1 次请求」） |
| 4 | 角色停用 | `UserScopeResolver.roles()` 过滤 `status='ACTIVE'`；`sys_user_role` **保留**；重新启用后自动恢复 |
| 5 | 未注册码 | 保存前全量校验，400 `UNKNOWN_SUBJECT`，一条都不写（事务未开始写） |
| 6 | 动作授了页面 DENY | 允许保存；`effective` 不含该动作；返回 `PAGE_DENY_ACTION_GRANTED` 警示 |
| 7 | 高危动作 | 授予非 `PLATFORM_ADMIN` 角色且 `confirmHighRisk!=true` ⇒ 409 `HIGH_RISK_REQUIRES_CONFIRM {actions}`；确认后审计带 `highRiskGranted` |
| 8 | 并发保存 | `PUT /permissions` 单事务：`DELETE 该角色三类行` + 批量 `INSERT`；`uk_rp` 兜底；不做乐观锁（同角色两人同时改 = 后者覆盖，审计留两份 before/after） |
| 9 | 跨租户 | 所有 `WHERE tenant_id = TenantContext.tenantCode...`（沿用 `TenantContext.require().tenantId()`）；id 越租户 ⇒ 404 而非 403（不泄露存在性） |
| 10 | 组织路径 | 删除/迁移**不改 `path`**（移动人员不是移动组织）；新增组织时 `path` 逻辑不变 |
| 11 | 缓存与事务 | `invalidate` 必须在事务**提交后**（`TransactionSynchronizationManager.afterCommit`），否则回滚也会清缓存（只是多一次重载，可接受；但提交前清会让并发读到旧值再缓存 45s） |
| 12 | 空角色 | 新建自定义角色 0 行 ⇒ 该角色用户几乎无权限（页面也 DENY）；UI 在角色表标「未配置」并给「去配置」入口 |
| 13 | 演示数据 | `demo-reset` 恢复内置授权 + 清空自定义角色；`role_permission` 计数参与两次幂等比较 |
| 14 | 只读总览 | `/admin/permissions` 只 `@GetMapping`；lint L20 静态断言 + s9 运行时 405 断言 |

---

## 10. 测试策略

### 10.1 单元测试（`mvn -q -pl ... -am test`，278 → 目标 ≥ 300）

| 测试 | 位置 | 断言要点 |
|---|---|---|
| `RbacRegistryTest` | `req-service/src/test/java/com/example/req/rbac/` | 36 页 / 72 动作；码唯一；`action.menuCode ∈ pages`；`functionCodes ⊆ PermissionMatrix.functions()`；模块计数 3/4/8/3/8/10；高危恰好 3 |
| `RbacSeedEquivalenceTest` | 同上 | 每 role×action：`seedAccess == max(action.functionCodes)`；每 role×page：`seedAccess == max(page.functionCodes)`（空 ⇒ ALLOW）；并输出规范化 SHA-256（供 s2 与 DB 对比） |
| `PermissionUnionTest` | 同上 | 空 → DENY；含 DENY → DENY；ALLOW+RESTRICTED → ALLOW；RESTRICTED 单值 → RESTRICTED |
| `PermissionServiceTest` | 同上 | 三序（页面 DENY ⇒ 动作 ALLOW 也 false；页面 ALLOW + 动作 RESTRICTED ⇒ true）；TTL 到点重载；`invalidate` 后 epoch+1 且下一次读到新行 |
| `RoleGuardTest` | 同上 | 移除 `org:manage`/`tenant:manage` ⇒ 锁死；停用最后一个管理员 ⇒ 锁死；非 PLATFORM_ADMIN 不受限 |
| `PermissionMatrixGridTest` | 既有 | **600 不动**（AC-20） |

### 10.2 `make rbac-verify`（`scripts/rbac-verify.sh`，段文件 `scripts/tests/rbac/`）

前置：`make platform-up`（栈就绪）+ `make platform-seed`。聚合器沿用 `access-verify.sh` 的写法：
分段下限 + 总数下限（**≥60**）+ `PASS==TOTAL` 三道守卫。

| 段 | 文件 | 断言（条） | 内容 |
|---|---|---|---|
| s0 | `s0-schema-migration.sh` | 8 | 4 张表列齐；`sys_role` 行数迁移前后一致（备份计数对比）；`sys_menu=36`、`sys_action=72`；每租户 `sys_role_permission=1680`；三类分布 600/360/720 |
| s1 | `s1-registry-three-way.mjs` | 8 | DB = `MenuRegistry` = `nav.ts`（node 解析）− notifications + admin-roles；DB = `ActionRegistry`；动作码命名 `<模块>.action.<动作>`；动作所属页面已注册 |
| s2 | `s2-seed-equivalence.sh` | 8 | 每租户 1680 行；`SHA2(GROUP_CONCAT(...))` == Java 测试输出的规范化哈希（720+360 布尔等价的可执行证据）；`FUNCTION` 600 格逐格 == `PermissionMatrix`（由同一哈希覆盖） |
| s3 | `s3-decision-order.sh` | 6 | 7 角色真实登录：页面 DENY 时动作端点 403；页面 ALLOW + 动作 DENY 403；RESTRICTED 200 但列表被域收窄；未授权码 403 |
| s4 | `s4-cache-epoch.sh` | 5 | 撤销动作后第 1 次请求 403；恢复即 200；`me/permissions` epoch 单调增；TTL ≤ 60s（`platform.rbac.cache-ttl-seconds` 断言） |
| s5 | `s5-role-lockout.sh` | 8 | 角色 CRUD；code 不可改；内置不可删/停；停用后其用户下次请求失权且 `sys_user_role` 仍在；清空管理员 409 且库不变 |
| s6 | `s6-user-org.sh` | 6 | 用户角色/域差集生效；非法角色 400 且用户行无变化；组织删除 409 带人数；move-users 迁移 + 1 条审计 |
| s7 | `s7-negative-nine.sh` | 9 | AC-18 九例逐条（无权限查角色 403、非管理员改授权 403、清空管理员 409、角色停用 403、DENY 覆盖 ALLOW、跨租户 404、有动作无页面 403、有页面无动作按钮不渲染且直调 403、高危未授 403） |
| s8 | `s8-audit-reset.sh` | 6 | 四类前缀审计存在且含 before/after、`highRiskGranted`；无差异不写；`demo-reset` 后内置授权回预设、自定义角色为 0、两次 counts 相等 |
| s9 | `s9-declaration-coverage.mjs` | 5 | 扫描 `RequestMappingHandlerMapping` 不可行 → 静态扫描控制器写方法 + 路由表规则；未保护写端点 = 0；`@PageOnly` 仅在白名单；`/admin/permissions` 无写映射；`platform.rbac.fail-fast` 默认 true |

失败信息必须定位到「角色/页面/动作」三元组，不打印整表。

### 10.3 既有门禁（AC-20，一条不降）

`platform-verify 168` / `access 148` / `platform-test-negative 30` / `lint 20`（本方案 +4 → 24）/ `unit 278` /
`verify 14/14` / `analytics 74` / `collab s0–s8`。**access a2/a3/a4 需要同步页面码**（仍由 T6 维护同一脚本，不新建脚本）。

### 10.4 证据

`docs/teamflow/20261113-r18-rbac-role-permission/evidence/`：角色列表、三级授权页（含半选与「不生效」标）、
409 防锁死弹窗、用户分配抽屉，共 ≥4 张 PNG（`node` + Playwright，脚本放 `logs/teamflow/tf-mwelt3hx-n8t3zs/scripts/`）。

---

## 11. 任务拆分（按文件边界，可并行）

依赖顺序：T1 →（T2 ∥ T3）→ T4 → T5a → T5b；T6 可与 T2–T5 并行编写，验收需全部落地；T7 收口。

| 任务 | 责任 | 拥有文件（写） | 读取（不写） | 验收 |
|---|---|---|---|---|
| **T1 契约注册表** | 36 页 + 72 动作 + 推导助手 | `platform-contract/.../MenuRegistry.java`、`ActionRegistry.java`、`PermissionMatrix.java`（仅加 `accessForAny`）；`req-service/src/test/java/com/example/req/rbac/{RbacRegistryTest,RbacSeedEquivalenceTest}.java` | PRD §3、DESIGN §2 | `mvn -pl platform-contract,req-service -am test`；72/36 计数与高危 3 全绿 |
| **T2 数据与种子** | V12 DDL、`RbacSeeder`、供给/种子/重置 | `req-service/src/main/resources/db/migration/V12__rbac_role_permission.sql`、`req-service/.../rbac/RbacSeeder.java`、`.../rbac/RbacBootstrap.java`、`.../sys/TenantProvisioner.java`、`.../seed/SeedService.java`、`.../seed/DemoResetService.java` | T1 三文件、`SysDb`、`RoleCatalog` | 起栈后 `sys_role_permission=1680/租户`；连跑两次 seed 幂等；demo-reset 回预设 |
| **T3 判定单点** | `PermissionService` + 路由表 + 拦截器 + 声明校验 | `platform-common/.../security/{PermissionService,PermissionGrantsRepository,PermissionRouteTable,PermissionInterceptor,RequiresAction,RequiresActionFromBody,PageOnly,PermissionDeclarationValidator,RequestBodyCacheFilter,UserScopeResolver}.java` | T1 注册表、`TenantContext`、`AuditService` | `verify.sh` 14/14；access 148 全绿；未保护写端点 0（启动不炸） |
| **T4 管理面 API** | 角色/授权/用户/组织/总览/me | `req-service/.../sys/{AdminRoleController,RoleAdminService,RoleGuard,AdminPermissionController,AdminUserController,AdminOrgController,SysViews}.java`、`.../auth/{AuthController,AuthService,AuthViews}.java` | T3 `PermissionService` 签名 | s5–s8 全绿；审计四类前缀齐全 |
| **T5a 前端底座** | store / nav / 路由 / API 封装 | `web/src/stores/auth.ts`、`composables/usePermission.ts`、`layouts/{nav.ts,navAccess.ts,pageRegistry.ts}`、`router/index.ts`、`api/{admin.ts,session.ts,types.ts}`、`router/routes/deliver.routes.ts` | T4 出参形状（§7） | `vue-tsc --noEmit`；菜单随授权即时变化 |
| **T5b 前端页面** | 三页 + 三抽屉 + 六个 rbac 组件 | `web/src/views/admin/{AdminRolesView,RolePermissionsView,AdminUsersView,AdminOrgsView,AdminPermissionsView}.vue`、`views/admin/components/{RoleFormDrawer,UserAssignDrawer,OrgMoveDrawer}.vue`、`views/admin/components/rbac/*`、`views/admin/admin-shared.css`、`styles/tokens.css` | T5a store/API | 三态六行单测；1440 无横向滚动；四张证据截图 |
| **T6 门禁脚本** | rbac-verify s0–s9 + lint + 重置断言 + 既有 access 脚本同步 | `scripts/rbac-verify.sh`、`scripts/tests/rbac/s0…s9`、`Makefile`、`scripts/tests/lint.sh`、`scripts/platform-demo-reset.sh`、`scripts/tests/access/{a2-page-matrix.sh,a3-nav-audit.mjs,a4-nav-visibility.mjs}` | T1–T5 | `make rbac-verify` ≥60 全绿；`make lint`；access 148 |
| **T7 交付自检与 git 收口** | 自查 + 分支/提交/忽略项 | `.gitignore` | 全部 | 见 §11.1 |

### 11.1 git 动作（PRD「工程约束」，不得丢失）

1. 分支：继续 `feat/rbac-admin`（已存在，**不新建**）。
2. `.gitignore` 追加：`*.eslintcache`、`*.local`、`*.dump`、`*.sql.gz`；
   **禁止**新增 `*.sql`、`evidence/*.png` 忽略（V12 与截图必须入库）。
3. 首个提交信息（原样）：
   `feat(rbac): 组织/角色/人员权限可配置化（V12 三层授权表 + PermissionService 单点）`
4. 提交前四步全绿：`make lint` → `make unit`（`platform-unit`）→ `make verify` → `make rbac-verify`。

### 11.2 自检（T7 的边界，不做 QA 全量矩阵）

编译 + 单测 + `make verify` + `make rbac-verify` + 一次真实 UI 走查（保存授权→菜单/按钮即时变化）；
发现问题**当场修**，只记录结论；不写 `QA-REPORT.md`/`ACCEPTANCE.md`，不跑跨浏览器矩阵。

---

## 12. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 多角色变交集 | 既有「双角色用户」权限可能变少 | 这是 PRD 明确取舍（AC-6/AC-18）；用户抽屉文案明示；`s3` 覆盖 |
| `PermissionMatrix` 退出运行时 | 漏改一处即 403 或越权 | lint L19 静态拦截 + `verify.sh` 全链路 |
| 路由表重写 | access 148 可能回归 | T3 单独拥有该文件；改完立刻 `make access-verify` |
| fail-fast 过严 | 服务起不来 | 违规一次性列全；`platform.rbac.fail-fast` 仅本地可关；s9 断言默认 true |
| 种子/迁移顺序 | 1680 不全 | `RbacBootstrap` 在 Flyway 之后；s0 直接查计数 |
| 快照内存 | 多租户常驻 | 每租户 ≤1680 行；TTL 到期重载；租户数 ≤10（演示规模） |
| 回滚 | — | 代码回滚到 r17 提交即可；V12 为纯增量（不删列、不改既有列语义），旧代码忽略新表仍可运行 |

---

## 13. AC → 落点速查

| AC | 落点 |
|---|---|
| AC-1 V12 | §3.1；T2 |
| AC-2 注册表三向 | §4；T1 + s1 |
| AC-3 1680/等价 | §3.2–3.3；`RbacSeedEquivalenceTest` + s2 |
| AC-4 幂等/重置 | §3.4；T2 + s8 |
| AC-5 单点/三序 | §5；T3 + lint L19 |
| AC-6 并集 | §5.2；`PermissionUnionTest` + s3/s7 |
| AC-7 RESTRICTED | §5.3；s3（DataScope 断言复用 access a1） |
| AC-8 缓存 epoch | §5.4；s4 |
| AC-9 路由/声明/fail-fast | §6；T3 + s9 |
| AC-10 角色 CRUD | §7.1；T4 + s5 |
| AC-11 授权读写 | §7.1；T4 + s5/s8 |
| AC-12 防锁死/高危 | §9 第 2、7 条；`RoleGuard` + s5/s7 |
| AC-13 用户写入 | §7.3；T4 + s6 |
| AC-14 组织 | §7.4；T4 + s6 |
| AC-15 前端三级页 | §8；T5b + 证据截图 |
| AC-16 me/permissions | §7.5 + §8.2；T4/T5a + s4 |
| AC-17 只读总览 | §7.2；T4 + lint L20 |
| AC-18 负向 9 例 | s7 |
| AC-19 审计 | §7.1/7.3/7.4 + s8 |
| AC-20 门禁不降 | §10.3；T6 |
| AC-21 rbac-verify | §10.2；T6 |
| AC-22 改动面/证据 | §11；T7 |
