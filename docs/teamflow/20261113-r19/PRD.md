<!-- meta: summary="收口 r18 三层授权（功能码/页面/动作）：补齐编译、V12 迁移与 1680 行种子、角色与授权两页及导航接入、写端点动作声明，让 rbac-verify s0–s9 与既有 8 条门禁全绿，并留下验收证据。" -->
基线依赖：docs/teamflow/20261113-r18-rbac-role-permission/（其 AC-1…AC-22 是本轮交付对象；r17 的动作级授权/自服务谓词/silentForbidden 与 r2 起形成的 RESTRICTED/DataScope 语义不得回归）

# PRD：r18 RBAC 收尾 —— 三层授权落地与门禁全绿

## 1. 背景与目标

### 1.1 这次是什么（不是从零开始）

r18 已经把「编译期权限矩阵」改成「数据驱动的三层授权」：`FUNCTION`（60 功能码）/ `MENU`（36 页面）/ `ACTION`（72 动作码）共用 `sys_role_permission`，由 `PermissionService` 按 **页面非 DENY → 动作档位 → DataScope** 三序单点判定。骨架已在分支 `feat/rbac-admin` 的断点提交 `5b7ba9d`，但**交付没有完成**：迁移缺失、两个管理页缺失、写端点动作声明未铺开、门禁红。

本轮只做收口：不重写骨架、不新增能力，把「已写了一半」补成**可编译、可迁移、可操作、可验收**，并且**不得靠放宽门禁换绿**。

### 1.2 现状核实（2026-11-13，以工作区为准）

| 检查项 | 现状 | 证据 |
|---|---|---|
| 编译 | 工作区 9 处未提交修复后 `mvn -o -q -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` 实测 **exit 0**；HEAD `5b7ba9d` 的 `RoleAdminService` 两处类型错误仍红 | 本轮实测；`req-service/src/main/java/com/example/req/sys/RoleAdminService.java:212,253` |
| V12 迁移 | **不存在**，迁移目录止于 V11 | `req-service/src/main/resources/db/migration/`（`V10__workbench_reports.sql`、`V11__normalize_severity_codes.sql`） |
| 注册表 | `MenuRegistry` 36 页、`ActionRegistry` 72 码已存在；`HIGH_RISK` 3 码已定义 | `platform-contract/src/main/java/com/example/platform/contract/MenuRegistry.java:49`、`ActionRegistry.java:47-56` |
| 服务端 | `PermissionService` / `PermissionGrantsRepository` / `PermissionRouteTable` / `PermissionDeclarationValidator` / `RoleAdminService` 已改；但 **91 个写映射中 `@RequiresAction`/`@PageOnly` 使用数 = 0** | `grep -rn "RequiresAction" req-service/src/main/java` = 0；`grep -c "@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping"` = 91 |
| 前端 | `/admin/roles`、`/admin/roles/:id/permissions` 两个视图**不存在**；`nav.ts` 两项入口已在，`AdminLayout.vue` 未接 | `web/src/views/admin/AdminRolesView.vue`、`RolePermissionsView.vue` 均缺失；`web/src/layouts/nav.ts:334-348` |
| 门禁 | `rbac-verify` 的 s0–s9 脚本齐备，s0 明确期待 V12 与 36/72/1680，当前必红；a3 侧栏基线仍是 36；a4 分组标签仍是「权限矩阵」 | `scripts/tests/rbac/s0-schema-migration.sh:104-160`；`scripts/tests/access/a3-nav-audit.mjs:23`；`scripts/tests/access/a4-nav-visibility.mjs:23-26` |
| 任务夹 | 仅有 host 创建的 `meta.json` | `docs/teamflow/20261113-r19/` |

**9 处未提交改动**（断点后的续修，必须保留并随本次提交，不得丢弃）：`Makefile`、`PermissionDeclarationValidator.java`、`AdminOrgController.java`、`AdminUserController.java`、`RoleAdminService.java`、`platform-demo-reset.sh`、`rbac-verify.sh`、`lint.sh`、`PermissionRouteTable.java`。

### 1.3 目标

1. **能构建、能启动**：编译 exit 0；`fail-fast=true` 下服务正常启动，而不是靠关闭校验回避未声明端点。
2. **数据落得下、算得对**：V12 建三表 + 补 `sys_role` 两列；1680 行种子（600+360+720）按旧行为反推，升级后可用能力**逐格不变**。
3. **管理员能配、配完即生效**：角色列表 + 三级授权树可完成「建角色 → 勾页面/动作 → 保存」；撤销动作后同一账号**下一次请求**即 403，不重登、不重启。
4. **门禁可信地全绿**：`rbac-verify` s0–s9 全绿且断言数不缩水；既有 8 条门禁不回归；`ACCEPTANCE.md` + `evidence/` 留下可复核证据。

### 1.4 成功判据（一句话）

管理员在 `/admin/roles/:id/permissions` 取消 `req.action.assign_vendor` 并保存后，同一账号不重新登录，下一次请求里该按钮消失、直调对应端点返回 403，其他菜单与按钮不受影响；`bash scripts/rbac-verify.sh` 与既有门禁全绿，且断言数没有减少。

## 2. 用户故事

| # | 故事 | 可测验收锚点 |
|---|---|---|
| US-1 | 作为平台管理员，我要新建角色并逐页、逐动作授权，保存后立即生效，而不是改代码再发版 | AC-8、AC-9；新建后出现在列表，保存 1 次 PUT 后 `epoch` 递增，刷新会话看到新菜单 |
| US-2 | 作为安全责任人，我要撤销某角色的高危动作后立刻阻断，而不是等缓存过期或重启 | AC-6、AC-10；撤销后第 1 次请求 403，恢复后第 1 次 200；高危授予需二次确认并留审计 |
| US-3 | 作为升级负责人，我要确认 V12 升级没有悄悄改变任何内置角色能点/能看的东西 | AC-2、AC-3、AC-4；600 格等于矩阵、360+720 布尔与升级前等价 |
| US-4 | 作为管理员，我要能停用角色、迁移组织人员，且不会把最后一个管理员锁在门外 | AC-8、AC-10、AC-13；停用后其用户下一次请求失权；清空管理员被 409 且库不变 |
| US-5 | 作为交付负责人，我要一条命令看到 RBAC 是否真的做完，而不是靠人肉点页面 | AC-16、AC-17、AC-18；`make rbac-verify` 全绿、重置幂等、证据截图入库 |

## 3. 验收标准（AC-1 … AC-18）

### 3.1 构建与数据（先决条件）

**AC-1 编译与启动（P0）**
- `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` **exit 0**；`RoleAdminService.java:212`（管理员计数 `long → int`）与 `:253`（按 `subject_type` 取图层）的错误以工作区现有修复为准并入提交。
- `make platform-up` 在 `platform.rbac.fail-fast=true`（默认）下启动成功；启动日志报告已检查的写端点数量且 violation = 0。
- **禁止**以 `platform.rbac.fail-fast=false`、删校验或注释 validator 换取启动成功；交付配置中该项必须为默认 true。

**AC-2 V12 迁移形状（P0）**
- 新增 `req-service/src/main/resources/db/migration/V12__rbac_role_permission.sql`（澄清文本中的 `V12__rbac_three_layer.sql` 为示意名；门禁 s0 已按前者断言，改名须同步 s0 与全部引用）。
- `sys_role_permission` 含 `tenant_id, role_id, subject_type, subject_code, access, created_at, updated_at` 七列，唯一键 `uk_rp(tenant_id, role_id, subject_type, subject_code)`；`subject_type` 仅允许 `FUNCTION|MENU|ACTION`。
- `sys_menu` 六列（`code/module/name/route_name/function_codes/sort_no`，全局表无 `tenant_id`）；`sys_action` 六列（含 `menu_code`、`high_risk`）；`sys_role` 增 `description/status`。
- V1–V11 零改动；迁移前后 `t1` 的 `sys_role` 行数均为 **10**；`flyway_schema_history` 最新成功版本 = **12**。

**AC-3 种子数量与幂等（P0）**
- 每租户 **1680 行** = 600 `FUNCTION` + 360 `MENU` + 720 `ACTION`；每个角色恰 **168 行** = 60 + 36 + 72，缺行即视为失权，不允许。
- 所有租户均补齐 1680 行：存量租户由 `RbacBootstrap.seedAllTenants` 遍历，新租户由 `TenantProvisioner` 同事务种子。
- 种子 `INSERT IGNORE` 幂等：迁移/种子/`make platform-demo-reset` 连跑两次，行数与内容不变，无唯一键冲突。

**AC-4 升级行为等价（P0）**
- 600 个功能码格逐格等于 `PermissionMatrix`；360 个页面格 = 绑定功能码的**最高档**（ALLOW > RESTRICTED > DENY）；720 个动作格 = 所需功能码的档位（多码取最高）。
- **720 + 360 个布尔**（是否非 DENY）与升级前判定逐格相等；`PermissionMatrixGridTest` 的 600 格断言原地保留、不得改写。
- 任何不等价失败必须打印到「角色 / 页面码 / 动作码」三元组，而不是只报一个总数。

### 3.2 判定与生效

**AC-5 单一判定与三序（P0）**
- 授权查询唯一入口是 `PermissionService`；固定三序：**页面码非 DENY → 动作 ALLOW/RESTRICTED/DENY → 既有 DataScope 收窄**。
- 并集规则：任一角色 DENY ⇒ DENY；否则 `ALLOW > RESTRICTED`；无授权行 ⇒ DENY。页面 DENY 时动作一律**不生效**。
- 运行时代码对 `PermissionMatrix.accessOf/accessFor` 的直连命中 = **0**（lint 静态断言）；`RESTRICTED` 仍只收窄 `DataScope`，`DataScope` 实现零改动。

**AC-6 缓存、epoch 与生效时效（P0）**
- 每租户快照缓存 TTL **≤ 60s** + `epoch`；授权保存 `epoch++`，使旧快照立即失效。
- 撤销动作后**第 1 次**请求即 403；恢复后**第 1 次**请求即 200；全程不重启、不重新登录。
- `GET /auth/me/permissions` 带 `epoch` 与 `functionCodes/menus/actions` 三集合；旧 `epoch` 的响应不得回填前端 store。

**AC-7 写端点动作声明（P0）**
- 每个 `POST/PUT/PATCH/DELETE` 必须有 `@RequiresAction`、`@RequiresActionFromBody` 或显式 `@PageOnly(reason)`，或被 `PermissionRouteTable` 规则覆盖；当前 91 个写映射、注解使用 0 处，按 s9 报告**一次性补齐**。
- `@PageOnly` 仅限真正只读或公共端点，且必须带可审计的 `reason`；不得把业务写端点标成 `PageOnly` 规避动作码。
- 声明未注册的页面码/动作码 ⇒ 启动失败（fail-fast=true）。
- s9 全部为 0：未被保护的写端点、悬空码、死动作、无 `reason` 的 `PageOnly`、`/admin/permissions` 中的写映射。

### 3.3 管理面

**AC-8 角色 CRUD 与停用（P0）**
- `GET/POST /admin/roles`、`PUT /admin/roles/{id}`、`PATCH /admin/roles/{id}/status`、`DELETE /admin/roles/{id}` 可用；`code` 创建后不可改。
- 内置角色不可删除、不可停用；停用后其用户的**下一次请求**失去该角色权限，`sys_user_role` 关联保留。
- 角色 `status` 仅 `ACTIVE|DISABLED`；停用/启用均写 1 条审计。

**AC-9 授权读写与管理页（P0）**
- `GET/PUT /admin/roles/{id}/permissions` 覆盖 `FUNCTION + MENU + ACTION` 三类；PUT 为全量覆盖写（1680 行口径），单事务，失败不部分写入。
- 未注册码 400 且不落库；允许保存「动作已授但页面 DENY」，响应回带 `effective` 与 `warnings`。
- 每次保存写 **1 条**审计，含 `added/removed/changed` 与 `highRiskGranted`；无差异不写。
- `/admin/roles`：角色列表 + 新建/编辑/停用；`/admin/roles/:id/permissions`：模块 → 页面（勾选/半选）→ 动作（ALLOW/RESTRICTED/DENY 三档），本页全选、搜索、仅看已授权。
- 页面 DENY 时其动作标「不生效」黄警；保存为 **1 次 PUT** + 成功 toast + 刷新当前会话。
- 从进入授权页到勾中任一动作 **≤ 3 次点击**，1440 宽下无横向滚动。

**AC-10 防锁死与高危（P0）**
- `PLATFORM_ADMIN` 的 `org:manage` 与 `tenant:manage` 不可移除、不可置 DENY；至少保留 1 个启用管理员。
- 违规返回 **409 `ADMIN_LOCKOUT_RISK`**，且数据库不变（角色授权行与用户状态均保持原值）。
- 高危 3 动作（`deploy.action.force_release`/`role.action.grant`/`user.action.disable`）默认仅管理员拥有；授予非管理员时需二次确认，并在审计中记 `highRiskGranted`。

### 3.4 前端与总览

**AC-11 导航、按钮与会话（P0）**
- `AdminLayout.vue` 补「角色与权限」「权限总览」；`SideNav.vue`/`TopNav.vue` 的 `NavContext` 接 `hasMenu: (code) => auth.canMenu(code)`。
- 菜单可见性为双条件：`pageCode` 非 DENY **且** 绑定功能码非 DENY；按钮只判 `can(actionCode)`，删除本地 `ACTION_META`/`PermissionMatrix` 二次过滤。
- `admin-permissions` 与 `admin-roles` 路由的 `meta.functionCode` 补齐；`a3` 侧栏基线按新增入口上移（澄清口径 36→37），但该数字必须同时等于 a3 解析结果、`MenuRegistry` 与库中 `sys_menu`（s1 三向比对），**不得只改常量凑绿**。
- `a4` 分组标签改「权限总览」，并按 `pageCode` 双条件重写断言；无权限动作不渲染 + 直调 403；动作 403 仍 `silentForbidden` 就地提示，不跳 `/403`。

**AC-12 只读权限总览（P1）**
- `/admin/permissions` 保留 600 格只读总览，数据源为 `FUNCTION` 授权行，页面内**无任何写控件**、控制器内无写映射。
- 在授权页修改 `FUNCTION` 档位后，总览在同一会话刷新即可见新值（同源，而非第二份数据）。

### 3.5 人员、组织、审计与负向

**AC-13 人员与组织闭环（P1）**
- 用户写入先校验后写：角色/业务域按差集更新，组织/厂商写 `org_id/vendor_id`；跨租户或非法 id 返回 400/404，事务回滚且**不部分写入**。
- 组织树支持新增/编辑/删除；有子组织或人员时删除返回 **409** 并给出子组织数/人数。
- `POST /admin/orgs/{id}/move-users` 单事务迁移 + **1 条**审计；空组织删除成功。

**AC-14 审计（P0）**
- 四类变更写 `audit_log`：`ROLE_*`（含 `ROLE_PERMISSION_UPDATE`）、`USER_*`、`ORG_*`，以及授权保存的 `highRiskGranted`。
- 每条含操作者、目标、`before/after`（授权变更含 added/removed/changed）；无差异不写。

**AC-15 负向 9 例（P0）**
1. 无 `org:manage` 者查角色/授权 → 403；
2. 非管理员改授权 → 403；
3. 清空 `PLATFORM_ADMIN` 冻结码 → 409 且库不变；
4. 角色停用后其用户访问 → 403；
5. 同角色 `DENY` 覆盖 `ALLOW`（含多角色并集）；
6. 跨租户读/改角色或授权 → 404；
7. 有动作授权但页面 `DENY` → 直调 403（动作不生效）；
8. 有页面授权但动作未授 → 按钮不渲染 + 直调 403；
9. 高危动作未授 → 403（授予后需二次确认才生效）。

### 3.6 门禁与交付

**AC-16 门禁全绿且不缩水（P0）**
- `bash scripts/rbac-verify.sh` 的 **s0–s9** 全绿；总断言 **≥ 60**，每段不低于 `segment_min`；失败必须定位到角色/页面/动作。
- 既有门禁不回归：`platform-verify 168`、`access 148`、`platform-test-negative 30`、`lint ≥ 20`、`platform-unit 278`、`verify 14/14`、`analytics-i18n-verify 74`、`collab s0–s8`；`PermissionMatrixGridTest` 600 保留。
- r17 契约不回归：`submit/withdraw` 自服务谓词、厂商不得评审/入池/验收、内部回调不进用户矩阵、动作 403 就地提示。
- 禁止通过删除断言、下调 `segment_min`、跳过 s9、给失败段加 `skip` 来变绿；门禁 diff 中出现上述任一即视为未完成。

**AC-17 重置与预设幂等（P0）**
- `scripts/rbac-verify.sh` 与 `scripts/platform-demo-reset.sh` 连跑两次均幂等。
- 重置后：自定义角色清空、10 个内置角色的三层授权回到预设 1680 行；服务端不变量与脚本断言**两层**守卫，漏删/漏补必须变红。
- 既有重置断言不回归（`portalCards/portalPins/apiClients=0`、`executors=2`、通知=4、报表=2）。

**AC-18 改动面与验收证据（P0）**
- 改动面限于 `platform-contract` / `platform-common` / `req-service` / `web`（两个新页面及导航接线）/ `scripts` / `Makefile`；`pom.xml` 与 `package.json` 的 diff 为空；不引新前端依赖。
- 落 `docs/teamflow/20261113-r19/ACCEPTANCE.md`；`evidence/` 至少 **4 张**截图：①角色列表；②授权树（含页面 DENY 的「不生效」黄警）；③`vendor1`/无权限账号看不到动作按钮；④撤销动作后直调 403 或保存前后对比。
- UI 按 `docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`，不做暗色/移动端/新设计系统。

## 4. 范围与非目标

### 4.1 范围内

- 修编译的两处类型错误（并入未提交修复），保证 `fail-fast=true` 可启动。
- V12 迁移 + 种子（1680 行）+ 全租户补齐 + 幂等。
- 授权判定/缓存/epoch 的收尾验证；写端点注解一次性补齐（91 个写映射）。
- 角色 CRUD/停用、授权读写、防锁死/高危、人员与组织、审计、`/auth/me/permissions`。
- 两个前端页面、`AdminLayout` 入口、`SideNav/TopNav` 的 `hasMenu`、导航双条件与按钮动作码。
- `rbac-verify` s0–s9、a3/a4 基线、既有门禁复跑、`ACCEPTANCE.md` 与 `evidence/`。

### 4.2 非目标

- 不做 SCHEMA/DATABASE 级租户隔离（仍为 ROW）。
- 不做用户级/组织级直接授权、临时授权、字段级脱敏。
- 不做 `DataScope` 的可视化配置；`RESTRICTED` 只表达范围收窄。
- 不做动作级条件表达式编辑；条件仍由 `RequirementActionMatrix` 等常量表达。
- 不做 OAuth/LDAP/SSO、自助注册、密码策略改造。
- 不做高危动作审批流（保留二次确认 + 审计）。
- 不做暗色主题、移动端、国际化扩展、多实例缓存广播。
- 不重写 `5b7ba9d` 已存在的骨架文件；不改需求状态机/审批模板/演示数据业务语义；不换 UI 设计系统。

## 5. 反面目标 / anti-goals

> 即使 AC 全绿、命令全过，出现以下任一情形，人也会认为这次交付失败。

1. **门禁是「放宽」出来的绿**：为了让 s0–s8 过，删掉断言、把 `segment_min` 调小、跳过 s9，或把 `fail-fast` 关掉；报告里写着 PASS，但保护面积比 r18 还小。→ 观测点：`rbac-verify` 总断言仍 ≥60、各段 ≥ 下限；diff 中无删除断言/下调下限/关闭校验；启动日志仍显示 fail-fast 检查。
2. **保存了权限却要重登才生效**：管理员取消某角色的动作后，同一账号刷新仍看到按钮，点下去才 403，或必须退出重登。→ 观测点：保存后不重登，下一次请求即 403；恢复后下一次即 200。
3. **授权页把「不生效」显示成「已生效」**：页面 DENY 但动作已授时没有黄警，`effective` 里也含该动作；管理员以为给了权限，用户实际点不到。→ 观测点：黄警出现、`effective` 不含该动作、直调 403。
4. **升级悄悄改变了现有角色**：V12 后某个内置角色多出「强制发布」或少掉「转派厂商」，没有任何测试发现。→ 观测点：720 动作 + 360 页面布尔打表与升级前逐格一致；任一差异能定位到角色/页面/动作。
5. **授权页技术上能用、实际上难用**：36 页 × 72 动作平铺，1440 宽要横向滚动，保存 1680 格超过 3 秒没有任何反馈。→ 观测点：进入页面到勾中任一动作 ≤3 次点击、无横向滚动、保存 3 秒内出现成功 toast。

## 6. 交互流程摘要

1. **建角色 → 授权**：`/admin/roles` 新建（code/name/描述）→ 落 `sys_role(builtin=0, ACTIVE)` → 跳授权页 → 左树选模块/页面（决定「能不能进」），右面板勾动作（决定「能不能点」）→ 保存 1 次 PUT → 事务写三类 subject + 1 条审计 + `epoch++` → toast + 重新拉 `/auth/me/permissions` → 菜单/按钮立即变化。
2. **撤销 → 阻断**：管理员把某动作改为 DENY 并保存 → 该角色用户下一次请求的菜单/按钮消失；直调端点 403（detail 带 page/action/access）→ 无需重启或重登。
3. **停用 / 防锁死**：停用角色 → 确认框显示受影响用户数 → 其用户下一次请求失权、关联保留；若操作会使 `PLATFORM_ADMIN` 失去冻结码或没有启用管理员 → 409 `ADMIN_LOCKOUT_RISK`，页面提示原因且不落库。
4. **人员与组织**：用户行内分配角色/组织/域/厂商 → 差集写 + 审计 → 下一次请求按新授权；组织树删除有人员/子组织时 409 并给出数量，`move-users` 迁移后再删。
5. **回滚预设**：`make platform-demo-reset` → 清自定义角色、内置角色三层授权回 1680 行预设 → `rbac-verify` s1/s2 重新打表全绿。

## 7. 优先级

| 优先级 | AC | 说明 |
|---|---|---|
| P0 | AC-1、AC-2、AC-3、AC-4、AC-5、AC-6、AC-7、AC-8、AC-9、AC-10、AC-11、AC-14、AC-15、AC-16、AC-17、AC-18 | 构建、迁移、等价、单点判定、声明覆盖、管理面、门禁与证据——缺任一条不可验收 |
| P1 | AC-12、AC-13 | 只读总览、组织人员迁移；缺失不阻断主流程，但演示会缺环 |

## 8. 依赖与风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 迁移在已有 V1–V11 数据的演示库上执行 | 迁移失败或角色行丢失 | V12 只增不改、`INSERT IGNORE`；迁移前后 `sys_role` 计数断言相等；先在 `make platform-demo-reset` 后复跑 |
| 为让门禁变绿而放宽断言 | 保护面积缩小，事故静默上线（anti-goal 1） | 断言下限写死在 `segment_min`；AC-16 明确 diff 审查；s9 不得跳过 |
| a3 基线 36→37 与 `MenuRegistry` 36 页/`sys_menu` 36 行口径打架 | 只改数字会让 s1 三向比对失败，或出现幽灵菜单 | 先跑 s1 对齐「解析口径」，再上移 a3；数字必须由三源同时成立，禁止新增无效入口 |
| 91 个写端点一次性补注解 | 动作码挂错页面、启动大面积失败 | 用 s9 报告逐个端点对齐；`@PageOnly(reason)` 只给只读/公共端点；fail-fast 在本地先跑通再提交 |
| 1680 格授权页信息量与性能 | 管理员找不到动作、保存卡顿（anti-goal 5） | 按模块折叠 + 页面 tri-state + 搜索 + 仅看已授权；保存单次 PUT；先渲染当前模块 |
| 未提交的 9 处续修被覆盖 | 编译回到红、门禁回退 | 开工前 `git diff` 留档；提交时逐文件核对，不 `checkout -- .`、不 `stash` 后丢弃 |
| 证据依赖运行中的平台 | 交付时缺截图 | 门禁全绿后统一起 `platform-up + platform-seed`，按 AC-18 四场景截图并入库 |

## 9. 假设与待澄清

> 以下每条同时进入状态块 `openQuestions`；`default` 为本轮推荐值。

### (a) 事实类（`kind: "fact"`）

- **V12 文件名以哪个为准**：澄清文本写 `V12__rbac_three_layer.sql`，仓库门禁 `scripts/tests/rbac/s0-schema-migration.sh:134` 与全部错误提示写 `V12__rbac_role_permission.sql`。两者只能留一个。default：**用 `V12__rbac_role_permission.sql`**（门禁已按此断言，改名成本在脚本与文档侧，迁移内容不变）。

### (b) 用户偏好类（`kind: "taste"`）

- **新建角色的初始授权**：default：**全 DENY（0 行，打开授权页按未授权渲染）**——最小权限，避免「新建即越权」；备选：复制某个现有角色的 168 行（上手快，但可能把高危动作一并带过去）。
- **授权页「仅看已授权」默认状态**：default：**默认关**——先看完整 36 页骨架，便于发现漏配；备选默认开（角色授权少时更聚焦，但未授权项被藏起来）。
- **停用角色时是否提示影响用户数**：default：**确认框显示「将影响 N 名用户」**——多一次查询，换掉「停用后一片 403」的意外；备选：直接停用（快，但管理员不知道影响面）。
- **保存授权后是否总是刷新当前登录者会话**：default：**总是刷新**（一次 `/auth/me/permissions`）——保存者可能改的不是自己的角色，但「保存即生效」的直觉要一致；备选：仅当角色包含自己时刷新（少一次请求，但当前页面可能停留在旧菜单）。

## 10. 里程碑建议

> 开发任务 ≤ 4 个，按序串行；每一步先编译/类型检查再进入下一步。

| 里程碑 | 内容 | 退出判据 |
|---|---|---|
| M1 构建与数据 | 并入 `RoleAdminService` 修复；V12 DDL；`RbacSeeder` 1680 行；全租户补齐 | AC-1、AC-2、AC-3 绿；`mvn compile` exit 0；s0 绿 |
| M2 服务端收口 | 91 个写端点声明；角色/授权 API；防锁死/高危；人员组织；审计；缓存/epoch | AC-4…AC-8、AC-10、AC-13…AC-15 绿；s2–s9 绿 |
| M3 前端两页与导航 | `AdminRolesView`、`RolePermissionsView`；`AdminLayout` 入口；`hasMenu`；按钮 `canAction`；a3/a4 基线 | AC-9（UI 部分）、AC-11、AC-12 绿；a3/a4 解析一致 |
| M4 门禁与证据 | 全量门禁复跑；重置幂等；四场景截图；`ACCEPTANCE.md` | AC-16、AC-17、AC-18 绿；`bash scripts/rbac-verify.sh` 全绿 |

## 11. 工程约束

**版本控制（按现状执行，不重写历史）**
- 当前分支 `feat/rbac-admin`，断点提交 `5b7ba9d`；**在该分支继续**，不切分支、不 rebase 已推送历史、不重写 `5b7ba9d`。
- 工作区已有 **9 处未提交改动**（见 §1.2），是断点后的续修：**保留并随本次实现一并提交**，不得 `git checkout -- .`、不得 stash 后丢弃。
- 建议提交（实现完成、门禁全绿后）：`feat(rbac): r18 收尾——V12 三层授权迁移、角色/授权页与门禁全绿`；任务夹 `PRD.md`/`ACCEPTANCE.md` 与迁移同一提交，避免文档丢失。
- `.gitignore` 审计（本栈真实工具，交付前补齐）：现有覆盖 `target/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`.env*`、`*.log`、`*.xlsx`、`*.csv`、`test-results/`、`playwright-report/`、`.idea/`、`.vscode/`、`*.pid`、`*.hprof`、`logs/`。本轮补：`*.eslintcache`（Vite/ESLint 本地缓存）、`*.local`（Vite env 覆盖）、`*.dump` 与 `*.sql.gz`（本机 MySQL 导出）。
- **禁止**忽略 `*.sql`（V12 就是 `.sql`）与 `docs/teamflow/**/evidence/*.png`（验收证据必须入库），也不得忽略 `config/nacos/` 种子。

**迁移与数据**
- 新表走 Flyway 新版本（当前 V11）→ **V12**；不得修改 V1–V11 已执行脚本。
- 种子必须可重复执行：第二次运行行数不变；`make platform-demo-reset` 后回到预设。
- 不改需求状态机、审批流模板、演示数据业务语义；`RESTRICTED` 与 `DataScope` 零改动；`RequirementActionMatrix` 的 16 动作与内部回调不进用户授权矩阵。

**写端点规约**
- 每个写端点必须 `@RequiresAction(<注册表动作码>)`，或 `@RequiresActionFromBody`（一对多动作），或显式 `@PageOnly(reason)`（仅只读/公共并登记白名单）；漏声明由 s9 与 lint 拦截。
- `platform.rbac.fail-fast` 交付值必须为 `true`；不得以关闭校验作为启动手段。

**UI**
- 按 `docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`（白卡片、1px 边框、蓝色只用于主操作/当前项、状态浅底方 pill、表格行高 44）、Element Plus；不引新依赖，不做暗色/移动端。

**门禁（必须全绿并同步更新基线）**
- `bash scripts/rbac-verify.sh`（s0–s9，≥60 断言）；`make platform-verify`、`make access-verify`、`make platform-test-negative`、`make lint`、`make platform-unit`、`make verify`、`make analytics-i18n-verify`、`make collab-verify`。
- `PermissionMatrixGridTest` 600 格保留不动；等价断言由 `rbac-verify` s2 承担，失败指到角色/页面码/动作码。
- 完成后落 `docs/teamflow/20261113-r19/ACCEPTANCE.md` 与 `evidence/` 四场景截图。
