<!-- meta: summary="把编译期权限矩阵改为可配置 RBAC：三层授权（功能码/页面码/动作码）+ 单一 PermissionService 解析（用户→启用角色→并集，DENY 优先，页面为动作前置）+ 角色/授权/人员/组织管理页，V12 按现有行为反推种子（600 功能码格 + 360 页面格 + 720 动作格），升级后任何角色可用能力与今天完全一致。" -->
基线依赖：docs/teamflow/20261113-r17-biz-submit-action-authz/（动作级授权、submit/withdraw 自服务谓词、silentForbidden 就地提示不得回归）
取代：docs/teamflow/20261108-r2-devops-req-platform/#AC-5（另见 docs/teamflow/20261108-r2-devops-platform/#AC-17）：授权判定由「编译期 PermissionMatrix 常量」改为「PermissionService 读数据」，一人多角色由「取最高档 ALLOW>RESTRICTED>DENY」改为「任一角色 DENY 即 DENY」；按钮准入由「复用 60 个功能码」改为「动作码独立授权，页面授权为前置条件」。

# PRD：组织 / 角色 / 人员的菜单与按钮权限分配（RBAC 可配置化）

## 1. 背景与目标

### 1.1 现状（已核实，作为改造基线）

| 现状 | 证据 |
|---|---|
| 权限矩阵是**编译期常量**：10 角色 × 60 功能码，`Access = ALLOW | RESTRICTED | DENY`，600 格逐格断言 | `platform-contract/src/main/java/com/example/platform/contract/PermissionMatrix.java`（ROLES 132-142、FUNCTIONS 60 项、`cells()/accessOf()/accessFor()`）；`req-service/src/test/java/com/example/req/sys/PermissionMatrixGridTest.java` |
| 判定与投影都直连常量：拦截器、租户上下文、登录响应、管理页 | `platform-common/src/main/java/com/example/platform/common/security/PermissionInterceptor.java:49-53,88,108-110`；`platform-common/src/main/java/com/example/platform/common/tenant/TenantContext.java:106-107`；`req-service/src/main/java/com/example/req/auth/AuthService.java:123-132` |
| `/admin/permissions` 是只读投影，注释明写「不落库」 | `req-service/src/main/java/com/example/req/sys/AdminPermissionController.java`（"Nothing is stored in the database"） |
| 路径准入是「路径 → 功能码」映射，首条命中生效 | `platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java`：`^/admin/(orgs|vendors|domains|users|dicts)(/.*)?$`→`org:manage`、`^/admin/permissions$`→`org:manage` |
| **按钮权限没有注册表**：页面各自按功能码 / `ACTION_META.permissions` 本地判断，按钮与功能码是多对一硬编码 | `web/src/views/req/platform.ts:59` `ACTION_META`；`web/src/views/req/RequirementCreateView.vue` 本地 `hasPermission` 过滤；`platform-contract/src/main/java/com/example/platform/contract/RequirementActionMatrix.java` 16 动作 |
| `sys_role` 存在但只有 `tenant_id/code/name/builtin`，**无 description、无 status** | `req-service/src/main/resources/db/migration/V1__platform_schema.sql:105-117`；V3 只补了 10 个内置角色行 |
| `sys_user` 已有 `org_id/vendor_id/status`；`sys_user_role`、`sys_user_domain`、`sys_org(parent_id,path,status)`、`biz_domain`、`sys_vendor` 均存在 | `V1__platform_schema.sql:36-52,70-100,119-134` |
| **`sys_role_permission`、`sys_menu`、`sys_action` 不存在**（全仓 0 命中） | `grep -rn 'sys_role_permission\|sys_menu\|sys_action' --include=*.sql --include=*.java` = 0 |
| 组织管理只有 `POST /admin/orgs` 与 `PUT /admin/orgs`，**没有删除端点、没有人员迁移端点** | `req-service/.../sys/AdminOrgController.java`（仅 Post/Put） |
| 前端权限矩阵页只读（166 行）；用户页 349 行、组织页 288 行，用户 `roles` 为字符串数组 | `web/src/views/admin/AdminPermissionsView.vue`、`AdminUsersView.vue`、`AdminOrgsView.vue` |
| 菜单是静态声明，`functionCode` 必填；`admin-permissions` 路由 **meta 缺 functionCode** | `web/src/layouts/nav.ts`（35 个 distinct routeName）；`web/src/router/routes/deliver.routes.ts:127-130` |
| 现有门禁基线 | access 148、negative 30、platform-verify 168、lint 20、unit 278、r1 verify 14/14、analytics 74、`PermissionMatrixGridTest` 600 |

**结论**：今天「谁能做什么」写死在代码里，管理员只能看不能改；按钮权限尤其没有独立身份——它只是功能码的副产物，所以「给某个角色的某个按钮单独开关」在现有模型里根本表达不出来。本需求把授权从常量搬到数据，并把可授权对象扩成三层：**功能码（页面准入的内涵）→ 页面（菜单码）→ 动作（按钮）**，三层共用一张授权表与一个 `PermissionService`；`PermissionMatrix` 退化为种子与动作常量的唯一来源。

### 1.2 目标

1. **可配置**：管理员能在页面上新增/编辑/停用角色，逐项勾选「页面」与「动作（按钮）」，保存即生效；动作可单独勾选，页面授权是动作生效的前置条件。
2. **单一判定**：所有授权查询收敛到 `PermissionService`，判定顺序固定为 **页面码非 DENY → 动作码 ALLOW/RESTRICTED/DENY → 既有 `DataScope` 收窄**；`PermissionMatrix` 只作种子与动作常量来源，`ActionRegistry` 只作动作码定义，二者都不参与运行时判定。
3. **升级零行为变化（硬验收）**：V12 按「今天的实际行为」反推初始化——600 功能码格逐格等于矩阵常量，360 页面格 = 其绑定功能码的最高档，720 动作格 = 其所需功能码的档位。任何角色升级后的实际可用能力与今天**完全一致**。
4. **可预设、可复现**：10 个内置角色、60 功能码、36 页面、72 动作由迁移脚本初始化，新租户同样开箱可用；`make platform-demo-reset` 后回到预设且幂等。
5. **不锁死、不越权**：`PLATFORM_ADMIN` 的 `org:manage`/`tenant:manage` 不可被清空且至少保留 1 个启用管理员；DENY 优先于 ALLOW；角色停用后其用户立即失去该角色权限；跨租户不可见；高危动作默认只有管理员有。
6. **人员与组织闭环**：用户 CRUD 支持分配角色/组织/业务域/厂商；组织树支持增删改与人员迁移。

### 1.3 成功判据（一句话）

管理员在 `/admin/roles/:id/permissions` 取消一个**动作**（例如 `req.action.assign_vendor`）并保存后，同一账号的**下一次请求**里该按钮消失、直调对应端点 403；而该账号的其他按钮与菜单不受影响，无需重启、无需重新登录。

## 2. 用户故事

| 编号 | 角色 | 故事 | 验收标准 |
|---|---|---|---|
| US-1 | 平台管理员 | 我要不改代码就裁剪内置角色（例如让 PMO 不能再「转派厂商」但仍能评审），因为按钮粒度的差异每天都在发生。 | AC-9、AC-10、AC-11、AC-14 |
| US-2 | 平台管理员 | 我要新建「外部审计员」这类自定义角色，只给它看报表页和「导出」按钮，因为临时岗位不值得改代码。 | AC-2、AC-9、AC-10、AC-14 |
| US-3 | 平台管理员 | 我要给一个人配角色、组织、业务域、厂商，因为一个人的可见范围由这四项共同决定。 | AC-12、AC-17 |
| US-4 | 平台管理员 | 我要改组织树并把人员迁到新组织，因为组织重组后旧组织要能删掉。 | AC-13、AC-17 |
| US-5 | 业务/研发/测试用户 | 我要登录后只看到我被授权的菜单和按钮，且点下去不再莫名 403；被授权后也不用重新登录。 | AC-5、AC-7、AC-8、AC-15 |
| US-6 | QA / 审计 | 我要能逐项复算「角色 × 功能码 / 页面 / 动作」三层是否与预设一致，并能一键回到预设，因为权限配错是最高危的回归。 | AC-1、AC-2、AC-3、AC-18、AC-20 |

## 3. 验收标准（AC-1 … AC-22）

> 每条 AC 的「验证」都指向可执行检查（脚本段 / 接口断言 / DB 断言），不接受人工目测作为唯一证据。

### 数据模型与预设

**AC-1（P0）迁移与建模（三层授权一张表）**
- 新增 Flyway **V12**（当前最高 V11），只增不改：
  - `sys_role_permission(tenant_id, role_id, subject_type ENUM('FUNCTION','MENU','ACTION'), subject_code VARCHAR(64), access ENUM('ALLOW','RESTRICTED','DENY') NOT NULL, created_at, updated_at, created_by, updated_by)`，唯一键 `(tenant_id, role_id, subject_type, subject_code)`，索引 `(tenant_id, role_id)`；
  - `sys_menu(menu_code VARCHAR(64) PK, parent_code VARCHAR(64) NULL, name VARCHAR(100), function_code VARCHAR(64) NULL, route_path VARCHAR(200) NULL, sort_no INT, enabled TINYINT DEFAULT 1)`——**全局注册表，不带 tenant_id**（页面由代码声明，逐租户复制必然漂移）；
  - `sys_action(action_code VARCHAR(64) PK, menu_code VARCHAR(64) NOT NULL, name VARCHAR(100), description VARCHAR(200) NULL, required_function_code VARCHAR(64) NULL, high_risk TINYINT DEFAULT 0, sort_no INT, enabled TINYINT DEFAULT 1)`——同样全局，`menu_code` 外键指向 `sys_menu`；
  - `sys_role` 增列 `description VARCHAR(200) NULL`、`status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'`；既有 `(tenant_id, code)` 唯一键与 10 行数据不变。
- 验证：`make platform-up` 后 `flyway_schema_history` 最新版本 = 12 且 `validate` 通过；`SHOW COLUMNS` 命中上述列/表；`sys_role` 行数迁移前后相等（t1 = 10）。

**AC-2（P0）页面与动作注册表（代码为唯一来源，可三向比对）**
- `platform-contract` 新增两张**纯常量**表（不含判定逻辑）：`MenuRegistry`（36 个页面：`menu_code` / 父级 / 绑定 `function_code`（可多个）/ route）与 `ActionRegistry`（**72 个动作码**，见 §3.1 全表）；`PermissionMatrix` 保持 10 角色 × 60 功能码不动。
- 注册表三向一致（脚本解析源码生成期望值，禁止手抄）：
  - `sys_menu` 行集合 == `MenuRegistry` == `web/src/layouts/nav.ts` 的 distinct routeName ∪ `{admin-roles}`（本轮新增角色管理页，页面数 35 → **36**），差集为空；
  - `sys_action` 行集合 == `ActionRegistry` == 服务端 `@RequiresAction` 注解声明的动作集合，差集为空；
  - 每个 `sys_action.menu_code` 必须存在于 `sys_menu`；每个动作的 `required_function_code` 必须存在于 `PermissionMatrix.functions()`。
- 验证：`make rbac-verify` s1 逐行打表，失败输出 `页面码/动作码 + 期望 vs 实际 + 差集方向`。

**AC-3（P0）种子 = 今天的实际行为（升级零行为变化，硬验收）**
- V12 为 `tenant_id=t1` 的 10 个内置角色写入三层授权，共 **1680** 行：
  - **600 FUNCTION 行**：逐格等于 `PermissionMatrix.accessFor(role, code)`（600/600，缺失与多余均为 0）；
  - **360 MENU 行**（10 × 36）：页面档位 = 其绑定功能码在该角色下的**最高档**（`max` 语义不变，多码页面取最高）；无 functionCode 的页面（`AUTHENTICATED`）记 ALLOW；
  - **720 ACTION 行**（10 × 72）：动作档位 = 其 `required_function_code` 在该角色下的档位；未映射到功能码的动作（纯自服务类，见 §3.1 注）记 ALLOW 并由动作矩阵的条件（本人提报）收窄。
- **行为等价断言（不可只对数据对常量）**：对全部 720 个「角色 × 动作」组合，计算 `can(role, action)` 的布尔值，与升级前代码的判定结果逐一相等（720/720）；页面 360 组合同理（360/360）。等价基准由 `rbac-verify` 用升级前的常量推导函数生成（`expectedFromCode(role, subject)`），不允许人工填表。
- 新租户不存在「空库无权限」：走 `TenantProvisioner` 新建租户后同样 1680 行。
- 验证：`make rbac-verify` s1/s2 打表；升级前后各跑一次 `access-verify`，用例结论集合完全相同。

**AC-4（P0）幂等与演示重置还原**
- `scripts/rbac-verify.sh` 连跑两次、`make platform-demo-reset` 连跑两次，1680 行数、`sys_menu` 36 行、`sys_action` 72 行、内置角色 10 行均不变（幂等）。
- 重置后：自定义角色被清空；内置角色被改过的单元恢复为预设（把 `PLATFORM_ADMIN` 的 `audit:view` 改成 DENY 后重置 → 恢复 ALLOW）；`sys_role.status` 回到 ACTIVE。
- 还原失败必须变红：服务端不变量 + 脚本层断言双层守卫（沿用 r15/r16 做法），不得静默通过。

### 授权解析与生效

**AC-5（P0）单一判定入口与固定判定顺序**
- 新增 `PermissionService` 为唯一授权查询入口；`PermissionInterceptor`、`TenantContext.access(...)`、`AuthService.permissions(...)`、`AdminPermissionController` 全部改为调用它。
- 判定顺序（三处共用同一实现，顺序不可调换）：
  1. **页面**：请求所映射的 `menu_code` 在 `MENU` 行中必须存在且 ≠ `DENY`；为 `DENY`/缺行 ⇒ 403（即便动作已授权，防绕过页面准入）；
  2. **动作**：写端点声明的 `action_code` 在 `ACTION` 行中必须为 `ALLOW` 或 `RESTRICTED`；`DENY`/缺行 ⇒ 403；
  3. **数据范围**：动作档位为 `RESTRICTED` 时，交由既有 `DataScope` 收窄（语义与今天一致）。
- `PermissionMatrix` 只保留 `roles()/functions()/cells()/accessFor()` 供种子与打表；**不再被运行时判定直接调用**（lint 静态断言：非种子/非测试代码中 `PermissionMatrix.accessOf(`、`PermissionMatrix.accessFor(` 命中数为 0，命中即失败并列出 文件:行）。

**AC-6（P0）用户 → 启用角色 → 并集，DENY 优先**
- 解析规则：取该用户所有 `sys_role.status='ACTIVE'` 的角色 → 对同一 `(subject_type, subject_code)`：任一角色 DENY ⇒ **DENY**；否则任一 ALLOW ⇒ ALLOW；否则任一 RESTRICTED ⇒ RESTRICTED；否则 DENY（未授权即拒绝）。
- 验证：用户同时持角色 A(ALLOW `org.action.create`) 与角色 B(DENY `org.action.create`) → `POST /admin/orgs` 403 且登录响应 `actions` 不含该码；相邻正例：单角色 ALLOW 时同请求 200（防「一刀切全拒」）。

**AC-7（P0）RESTRICTED 语义不回归**
- `RESTRICTED` 仍是「通过检查、由 `DataScope` 收窄数据范围」，不得被当作拒绝，也不得被降级为 ALLOW；对页面/动作/功能码三层取值一致。
- 验证：把某角色的 `defect.action.verify_pass` 保存为 RESTRICTED 后，该用户接口返回 2xx 但只能看到本厂商/本域数据；platform-verify 的厂商隔离段全绿，`DataScope` 实现 0 改动。

**AC-8（P0）缓存与失效（下一次请求即生效）**
- `PermissionService` 带内存缓存（`ConcurrentHashMap` + **每租户 epoch 版本号**，不引新依赖；TTL 兜底 ≤ 60s）。写操作（角色 CRUD、授权保存、用户角色变更、角色停用）在事务提交后 `epoch++` 再返回；`GET /auth/me/permissions` 响应携带 `epoch`，前端据此丢弃旧响应。
- 验证：`PUT /admin/roles/{id}/permissions` 撤销 `req.action.approve` → 同一 JWT 的**下一次**请求 403（不重启、不重登、不等 TTL）；恢复后下一次请求 200。并发单测：写入期间的读旧版本不得覆盖新版本（epoch 比较），且 s3 段断言「撤销后第 1 次」与「第 3 次请求」结论一致。

**AC-9（P0）路径准入与写端点动作声明**
- `PermissionRouteTable` 保持「路径 → 页面码」唯一映射（新增 `/admin/roles*`、`/admin/actions` 两条 `org:manage` 规则），但判定改为查 `PermissionService`；不再单独评估功能码。
- 写端点逐一声明所需动作码：方法注解 `@RequiresAction("req.action.assign_vendor")`；一对多动作端点用 `@RequiresActionFromBody(param="action", prefix="req.action.")`（`POST /requirements/{id}/actions`、`POST /defects/{id}/actions`）。启动期校验：注解声明的动作码不在 `sys_action`/`ActionRegistry` ⇒ **应用启动失败**（fail-fast，不允许静默放行）。
- 验证：DB 给 `BUSINESS` 授予 `org:manage` 页面与 `org.action.create`（矩阵常量为 DENY）→ `POST /admin/orgs` 200；删该动作行 → 下一次请求 403（数据优先于常量）。

### 管理能力（服务端）

**AC-10（P0）角色 CRUD 与停用**
- `GET /admin/roles`（code/name/description/builtin/status/userCount）、`POST /admin/roles`、`PUT /admin/roles/{id}`、`PATCH /admin/roles/{id}/status`。
- `code` 创建后不可改、租户内唯一；内置角色不可删除、不可停用、不可改 code（可改 name/description/权限，见 AC-11）。
- 停用角色后：其用户下一次请求即不再获得该角色权限（`permissions`/`actions`/`menus` 不含、接口 403），`sys_user_role` 关联保留；重新启用即恢复。
- 验证：停用/启用同一角色各一次，断言接口结论与三集合前后翻转且关联行数不变。

**AC-11（P0）角色授权读写（页面与动作同表、分开展示）**
- `GET /admin/roles/{id}/permissions` 返回按模块分组的树：模块 → 页面（含 `grant` 与绑定功能码）→ 动作子节点（含 `grant`、`high_risk`）；`GET /admin/actions` 返回动作注册表全集。
- `PUT /admin/roles/{id}/permissions` 全量覆盖保存（事务内先删后插，仅允许已注册的 FUNCTION/MENU/ACTION 码；未注册码 → 400 且不落库）；**允许**保存「动作已勾选但所属页面为 DENY」的组合（配置合法、生效为 DENY），响应回带 `effective: []` 与警示项，不做硬拒。
- 每次保存写 **1 条** `audit_log`（`action=ROLE_PERMISSION_UPDATE`，`detail_json` 含 `added/removed/changed[{subjectType,code,from,to}]` 与 `highRiskGranted[]`），差异项数必须与请求前后真实差异相等。
- 验证：一次保存产生 1 条审计且 `jq` 统计的差异数 == 脚本预算值；保存「动作有、页面 DENY」后 s2 断言该动作 403 且页面不可见。

**AC-12（P0）防锁死与高危动作默认收口**
- `PLATFORM_ADMIN` 的 `org:manage` 与 `tenant:manage` 两个 FUNCTION 行不可被移除或置 DENY；任何租户必须至少保留 1 个 `status=ACTIVE` 且经其角色持有 `org:manage` 的用户。
- 违规保存返回 **409** `code=ADMIN_LOCKOUT_RISK`，body 含被拒的码与原因；库内授权行数与角色状态不变。
- 高危动作（`deploy.action.force_release`、`role.action.grant`、`user.action.disable`）的**预设**只给 `PLATFORM_ADMIN`；授予其他角色时允许但需二次确认，并写入 `highRiskGranted` 审计字段。
- 验证：清空 `PLATFORM_ADMIN` 全部权限 → 409；只保留上述两码 → 200；停用/降权唯一管理员用户 → 409；三个用例后行数不变；给 `DEV` 授予 `role.action.grant` → 200 且审计含 `highRiskGranted:["role.action.grant"]`。

**AC-13（P0）人员授权（角色/组织/业务域/厂商）**
- `/admin/users` 写入路径支持：角色多选 → `sys_user_role` 差集增删；业务域多选 → `sys_user_domain` 差集增删；`org_id`、`vendor_id` 单值；同时写 `audit_log`（`action=USER_PERMISSION_UPDATE`，含 before/after）。
- 非法入参（跨租户/不存在的 role/org/domain/vendor、`org_id` 指向非 ACTIVE 组织）→ 400/404 且**不落库、不部分写入**（事务回滚）。
- 验证：提交「合法角色 + 跨租户组织」混合请求 → 4xx 且 `sys_user_role` 行数不变。

**AC-14（P1）组织树增删改与人员迁移**
- `/admin/orgs`：新增子组织、改名、停用、**删除空组织**（`DELETE /admin/orgs/{id}` 本轮新增）；有子组织或有人员的组织删除 → **409** 且给出「先迁移 N 人」。
- `POST /admin/orgs/{id}/move-users`（目标组织）：事务内批量改 `org_id` 并写 1 条 `ORG_MOVE_USERS` 审计；目标组织必须 ACTIVE 且同租户；`path` 唯一性、父级 ACTIVE 校验保持。
- 验证：删除带 1 人的组织 → 409 且人数提示为 1；迁移后删除 → 200，审计 1 条。

### 前端

**AC-15（P0）角色列表与三级授权页**
- `/admin/roles`：表格（角色名/编码/内置标记/状态/用户数/操作）+ 新建/编辑抽屉 + 停用二次确认；空态与错误态复用现有 admin 样式。
- `/admin/roles/:id/permissions`：**模块分组 → 页面（勾选）→ 动作（子勾选）**三级结构：
  - 左树为「模块 → 页面」两级，页面节点带勾选框与 `indeterminate`（部分动作已授 ⇒ 半选）；
  - 右面板为当前页面的动作表（动作码 / 名称 / 高危标记 / 三档 ALLOW-RESTRICTED-DENY），支持本页全选、按动作码或名称过滤；
  - 顶部全局搜索（页面名 / 页面码 / 动作码 / 动作名）与「仅看已授权」开关；单模块全选；勾满转全选；
  - 保存 → 成功 toast + **立即刷新当前用户菜单与按钮**（不整页 reload）；保存「动作已授但页面 DENY」时给出黄色警示条（不阻塞保存）。
- 验证：前端单测覆盖「半选→全选→取消」三态、搜索过滤、页面 DENY 时仍显示动作已授但标注「不生效」；保存后 effective 计数变化。

**AC-16（P0）菜单与按钮读服务端授权结果**
- `GET /auth/me/permissions`（登录响应同构）返回 `{ epoch, functionCodes: [{code, access}], menus: [{code, access}], actions: [{code, access}] }`；前端会话 store 持有三集合，`can(actionCode)` 为按钮唯一判据。
- `nav.ts` 保留路由/图标/分组/routeName 声明，**可见性改读服务端**：页面可见 = `menu_code` 已授权 **且** 其绑定 `functionCode` 非 DENY（双条件，避免「菜单看得见、点开 403」）。
- 前端删除按本地 `PermissionMatrix`/`ACTION_META.permissions` 的二次过滤；`RequirementActionMatrix` 的服务端 `allowedActions` 仍是需求详情页按钮可用性的服务端来源，但前端不再自算。
- 补齐 `web/src/router/routes/deliver.routes.ts:127-130` 缺失的 `meta.functionCode`（`admin-permissions` → `org:manage`），新增 `admin-roles` 路由同样带 `meta.functionCode`。
- 验证：DB 撤销某页面码 → 刷新后该菜单消失；撤销 `req.action.review_start` → 需求详情不渲染「评审」按钮且直调 403；深链 `/admin/permissions` 无权限时客户端守卫先拦。

**AC-17（P1）只读总览保留，且不是第二写入口**
- `/admin/permissions` 保留为 600 格只读总览（10 角色 × 60 功能码），数据源改为 `FUNCTION` 授权行（与运行时同一张表），页内明示「编辑请到角色详情」。
- 验证：lint 静态断言该视图无保存/提交调用；页面无任何可写控件；其 600 格与 s1 打表结果同源（改 DB 后该页显示随之变化）。

### 安全、审计与门禁

**AC-18（P0）负向用例 9 例（拒绝且状态/授权不变）**
1. 无 `org:manage` 的用户 `GET /admin/roles` → 403；
2. 非管理员 `PUT /admin/roles/{id}/permissions` → 403；
3. 清空 `PLATFORM_ADMIN` 权限 → 409 且 `sys_role_permission` 行数不变；
4. 角色停用后其用户访问原授权接口 → 403，三集合不含该码；
5. DENY 覆盖 ALLOW（AC-6 同例，接口级）；
6. 跨租户：t1 令牌访问 t2 的 `role_id` → 404，响应体不泄露角色名/权限；
7. **有动作码无页面码**：只授 `defect.action.close`、页面 `defects` 为 DENY → 403；
8. **有页面无动作码**：页面 `defects` ALLOW、动作未授 → 按钮不渲染，直调 `POST /defects/{id}/actions` → 403；
9. **高危动作**：仅 `DEV` 角色、未授 `deploy.action.force_release` → `POST /deploy-orders/{id}/force-release` 403。
- 每条都断言「HTTP 状态 + DB 行数/状态不变」，并写 `PERMISSION_DENIED` 审计（沿用 r17 约定：`code=FORBIDDEN`，管理面额外带 `menuCode`/`actionCode`）。

**AC-19（P0）审计可追溯**
- 四类变更（角色、授权、人员授权、组织迁移）全部写 `audit_log`，`action` 前缀 `ROLE_`/`USER_`/`ORG_`，`detail_json` 含 before/after；审计页可按 `biz_type=ROLE|USER|ORG` 过滤。
- 验证：每类操作各 1 次 → 审计各 1 条且差异字段可解析；无变更的重复保存不写审计。

**AC-20（P0）既有门禁不回归**
- `make platform-verify`（168 → ≥168）、`make access-verify`（148 → ≥148）、`make platform-test-negative`（30 → ≥30）、`make lint`（20 → ≥20）、`make unit`（278 → ≥278 + 本轮新增）、`make verify`（r1：14/14）、`make analytics-i18n-verify`（74）、`make collab-verify`（s0–s8）全绿。
- `PermissionMatrixGridTest` 的 **600 格断言保留不动**（不迁移、不弱化）；DB 侧等价断言由 `rbac-verify` s1/s2 承担。
- r17 行为不回归：`RequirementActionMatrix` 与 `POST /requirements/{id}/actions` 的 submit/withdraw 自服务谓词、厂商不得评审、`source=APPROVAL/GITLAB` 内部回调零改动，相关单测全绿。

**AC-21（P0）新门禁 `make rbac-verify`**
- 新增 `scripts/rbac-verify.sh` + Makefile 目标，分段：s0 迁移、s1 注册表三向比对、s2 种子 1680 格打表 + 行为等价（720 布尔）、s3 解析（页面前置 / 并集 / DENY 优先 / RESTRICTED）、s4 缓存失效时效、s5 角色 CRUD 与防锁死、s6 人员/组织授权、s7 负向 9 例、s8 审计与 reset 幂等、s9 动作声明覆盖率（未声明动作的写端点 = 0）。
- 每段 `PASS==TOTAL` 守卫 + 全量断言总数下限（首版 ≥ 60），段级下限守卫；失败输出「角色/页面码/动作码 + 期望 vs 实际 + 修复提示」。
- 纳入 `make test`；可单独跑（不依赖 UI）。

**AC-22（P0）改动面、依赖零新增与 UI 证据**
- 允许改动：`req-service`（sys/auth 包、V12 迁移）、`platform-common`（security/tenant）、`platform-contract`（`MenuRegistry`/`ActionRegistry` 常量）、`web/src`（admin 视图、nav/navAccess、api、router meta）、`scripts/rbac-verify.sh`、`Makefile`、`docs/teamflow/`。
- `pom.xml` / `package.json` diff 为空（不引新依赖，缓存自实现）；不改需求状态机、审批流模板、演示夹具业务语义。
- UI 按 `docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`：白卡片、1px 边框、蓝色只用于主操作/当前项、状态浅底方 pill、表格行高 44；Element Plus，不做暗色/移动端。
- 证据：任务夹 `evidence/` 存 ≥ 4 张截图（角色列表、三级授权页含半选与搜索态、动作表含高危标记、用户分配抽屉），1440 宽下 `/admin/permissions` 不出现整页横向滚动（容器内滚动允许），首屏 ≤ 2s（本地冷启动一次）。

## 3.1 动作码全表（72 个）

> 动作码命名空间 `<模块>.action.<动作>`，全小写下划线。`高危` 列仅 3 个为 `是`（默认只给 `PLATFORM_ADMIN`）。`所需功能码` 是种子推导与行为等价的依据，也是「页面授权为前置」之外的第二道来源。

| # | 动作码 | 名称 | 所属页面 | 判定端点 | 所需功能码 | 高危 |
|---|---|---|---|---|---|---|
| 1 | `req.action.submit` | 提交审批 | requirements | `POST /requirements/{id}/actions#submit` | req:report:bus / req:report:dev（本人提报条件见矩阵） | 否 |
| 2 | `req.action.withdraw` | 撤回 | requirements | `POST /requirements/{id}/actions#withdraw` | 同上（本人提报） | 否 |
| 3 | `req.action.approve` | 审批通过 | requirements | `POST /requirements/{id}/actions#approve` | req:review | 否 |
| 4 | `req.action.reject` | 审批驳回 | requirements | `POST /requirements/{id}/actions#reject` | req:review | 否 |
| 5 | `req.action.review_start` | 开始评审 | requirements | `POST /requirements/{id}/actions#review_start` | req:review | 否 |
| 6 | `req.action.review_pass` | 评审通过 | requirements | `POST /requirements/{id}/actions#review_pass` | req:review | 否 |
| 7 | `req.action.review_reject` | 评审不通过 | requirements | `POST /requirements/{id}/actions#review_reject` | req:review | 否 |
| 8 | `req.action.accept` | 验收 | requirements | `POST /requirements/{id}/actions#accept` | req:review | 否 |
| 9 | `req.action.close` | 关闭需求 | requirements | `POST /requirements/{id}/actions#close` | req:review | 否 |
| 10 | `req.action.schedule` | 排期 | requirements | `POST /requirements/{id}/actions#schedule` | req:review | 否 |
| 11 | `req.action.start_dev` | 开始开发 | requirements | `POST /requirements/{id}/actions#start_dev` | req:review | 否 |
| 12 | `req.action.deliver` | 交付提测 | requirements | `POST /requirements/{id}/actions#deliver` | req:review | 否 |
| 13 | `req.action.merge` | 合并代码 | requirements | `POST /requirements/{id}/actions#merge` | req:review | 否 |
| 14 | `req.action.finish` | 完成 | requirements | `POST /requirements/{id}/actions#finish` | req:review | 否 |
| 15 | `req.action.submit_test` | 提交测试 | requirements | `POST /requirements/{id}/actions#submit_test` | req:review | 否 |
| 16 | `req.action.test_pass` | 测试通过 | requirements | `POST /requirements/{id}/actions#test_pass` | req:review | 否 |
| 17 | `req.action.decompose` | 拆解子需求 | requirements | `POST /requirements/{id}/decompose` | req:review | 否 |
| 18 | `req.action.assign_vendor` | 转派厂商 | requirements | `POST /requirements/{id}/assign-vendor` | req:review | 否 |
| 19 | `task.action.create` | 新建任务 | iterations | `POST /tasks` | task:manage | 否 |
| 20 | `task.action.assign` | 指派任务 | iterations | `PATCH /tasks/{id}#assigneeId` | task:manage | 否 |
| 21 | `task.action.move` | 移动任务 | iterations | `POST /tasks/{id}/move` | task:manage | 否 |
| 22 | `task.action.close` | 关闭任务 | iterations | `PATCH /tasks/{id}#status=DONE` | task:manage | 否 |
| 23 | `sprint.action.create` | 新建迭代 | iterations | `POST /sprints` | plan:manage | 否 |
| 24 | `sprint.action.close` | 关闭迭代 | iterations | `POST /sprints/{id}/close` | plan:manage | 否 |
| 25 | `test.action.case_edit` | 编辑用例 | testcases | `POST/PUT/DELETE /testcases`、`POST /testcases/{id}/copy` | case:manage | 否 |
| 26 | `test.action.case_review` | 用例评审 | testcases | `POST /testcases/{id}/reviews`、`POST /testcase-reviews/{id}/decide` | case:manage | 否 |
| 27 | `test.action.plan_create` | 新建测试计划 | test-plans | `POST /test-plans` | plan:manage | 否 |
| 28 | `test.action.plan_approve` | 计划审批 | test-plans | `POST /test-plans/{id}/submit-review|approve|reject` | plan:manage | 否 |
| 29 | `test.action.execute` | 执行测试 | test-plans | `POST /test-plans/{id}/execute`、`/rounds`、`/rounds/{id}/close`、`/{id}/start|complete|close|archive` | plan:execute | 否 |
| 30 | `test.action.report_sign` | 报告签发 | test-reports | `POST /test-reports`、`POST /test-reports/{id}/versions/{no}/signoff` | report:sign | 否 |
| 31 | `test.action.gate_waive` | 质量门禁豁免 | quality-gates | `POST /quality-gates/waivers`、`/waivers/{id}/approve|reject|revoke` | gate:waive | 否 |
| 32 | `defect.action.create` | 提缺陷 | defects | `POST /defects`、`POST /defects/from-execution/{executionId}` | defect:handle | 否 |
| 33 | `defect.action.assign` | 指派缺陷 | defects | `POST /defects/{id}/actions#assign` | defect:handle | 否 |
| 34 | `defect.action.start` | 开始处理 | defects | `POST /defects/{id}/actions#start` | defect:handle | 否 |
| 35 | `defect.action.submit_verify` | 提交验证 | defects | `POST /defects/{id}/actions#submit_verify` | defect:handle | 否 |
| 36 | `defect.action.verify_pass` | 验证通过 | defects | `POST /defects/{id}/actions#verify_pass` | defect:handle | 否 |
| 37 | `defect.action.close` | 关闭缺陷 | defects | `POST /defects/{id}/actions#close` | defect:handle | 否 |
| 38 | `defect.action.reopen` | 重新打开 | defects | `POST /defects/{id}/actions#reopen` | defect:handle | 否 |
| 39 | `release.action.create` | 新建发布单 | releases | `POST /releases` | release:plan | 否 |
| 40 | `release.action.lock` | 锁定发布 | releases | `POST /releases/{id}/lock` | release:plan | 否 |
| 41 | `release.action.approval` | 发起发布审批 | releases | `POST /releases/{id}/approval` | release:plan | 否 |
| 42 | `release.action.milestone` | 维护里程碑 | releases | `POST /releases/{id}/milestones` | release:plan | 否 |
| 43 | `deploy.action.submit` | 提交部署单 | deploy-orders | `POST /deploy-orders/{id}/submit` | deploy:create | 否 |
| 44 | `deploy.action.approve` | 部署审批 | deploy-orders | `POST /approvals/tasks/{taskId}/actions`（bizType=DEPLOY_ORDER） | deploy:execute | 否 |
| 45 | `deploy.action.force_release` | 强制发布 | deploy-orders | `POST /deploy-orders/{id}/force-release` | deploy:force | **是** |
| 46 | `wiki.action.page_edit` | 编辑/移动/回滚页面 | wiki | `POST/PUT /wiki/pages`、`/pages/{id}/move|restore|rollback|links|comments` | WIKI_VIEW | 否 |
| 47 | `wiki.action.page_delete` | 删除/彻底删除页面 | wiki | `DELETE /wiki/pages/{id}`、`DELETE /wiki/pages/{id}/purge` | WIKI_VIEW | 否 |
| 48 | `wiki.action.space_manage` | 空间管理 | wiki | `POST /wiki/spaces`、`PUT /wiki/spaces/{id}` | WIKI_VIEW | 否 |
| 49 | `gantt.action.drag_reschedule` | 拖拽改期 | gantt | `PATCH /gantt/rows/{type}/{id}` | GANTT_VIEW | 否 |
| 50 | `flow.action.stage_config` | 价值流阶段配置 | value-stream | `PUT /flow/stages` | FLOW_VIEW | 否 |
| 51 | `report.action.create` | 新建报表 | report-list | `POST /reports` | REPORT_VIEW | 否 |
| 52 | `report.action.edit` | 编辑/复制报表 | report-list | `PUT /reports/{id}`、`POST /reports/{id}/copy` | REPORT_VIEW | 否 |
| 53 | `report.action.delete` | 删除报表 | report-list | `DELETE /reports/{id}` | REPORT_VIEW | 否 |
| 54 | `report.action.share` | 分享报表 | report-list | `POST/PUT /reports/{id}/share` | REPORT_SHARE | 否 |
| 55 | `report.action.export` | 导出/运行报表 | report-list | `POST /reports/{id}/run` | REPORT_VIEW | 否 |
| 56 | `report.action.pin` | 报表置顶 | report-list | `POST /reports/{id}/pin` | REPORT_VIEW | 否 |
| 57 | `portal.action.configure` | 门户配置 | portal | `PUT /portal/config` | PORTAL_CONFIG | 否 |
| 58 | `org.action.create` | 新建组织 | admin-orgs | `POST /admin/orgs` | org:manage | 否 |
| 59 | `org.action.edit` | 编辑组织 | admin-orgs | `PUT /admin/orgs` | org:manage | 否 |
| 60 | `org.action.delete` | 删除组织 | admin-orgs | `DELETE /admin/orgs/{id}`（本轮新增） | org:manage | 否 |
| 61 | `user.action.create` | 新建用户 | admin-users | `POST /admin/users` | org:manage | 否 |
| 62 | `user.action.edit` | 编辑用户 | admin-users | `PUT /admin/users` | org:manage | 否 |
| 63 | `user.action.disable` | 停用用户 | admin-users | `PATCH /admin/users/{id}/status` | org:manage | **是** |
| 64 | `user.action.assign_role` | 分配角色 | admin-users | `PUT /admin/users#{roles}` | org:manage | 否 |
| 65 | `role.action.create` | 新建角色 | admin-roles | `POST /admin/roles` | org:manage | 否 |
| 66 | `role.action.edit` | 编辑角色 | admin-roles | `PUT /admin/roles/{id}` | org:manage | 否 |
| 67 | `role.action.disable` | 停用角色 | admin-roles | `PATCH /admin/roles/{id}/status` | org:manage | 否 |
| 68 | `role.action.grant` | 保存角色授权 | admin-roles | `PUT /admin/roles/{id}/permissions` | org:manage | **是** |
| 69 | `vendor.action.manage` | 厂商维护 | admin-vendors | `POST/PUT /admin/vendors` | org:manage | 否 |
| 70 | `workflow.action.publish` | 流程发布/停用 | admin-workflows | `POST/PUT /admin/workflows`、`/{id}/publish|disable` | workflow:manage | 否 |
| 71 | `integration.action.configure` | 集成配置 | admin-integrations | `POST /integration/configs`、`PATCH/DELETE /integration/configs/{id}`、`/{id}/test` | integration:manage | 否 |
| 72 | `apiclient.action.manage` | API 客户端管理 | admin-api-clients | `POST /admin/api-clients`、`/{id}/rotate`、`PATCH /{id}/status` | apiclient:manage | 否 |

**注（覆盖规则，避免「漏一个按钮」的争议）**：
- 动作码覆盖「需要按角色区分的用户可见写操作」；以下页面**不拆动作**，只受页面级准入（`AUTHENTICATED` 或页面功能码）保护，并在 s9 中显式登记为 `@PageOnly`：通知已读、附件上传/删除、测试环境、测试策略、执行机、代码策略（含分支策略）、指标定义、审计日志页、租户新建、业务域维护、字典维护、`/internal/**` 内部回调。
- `req.action.submit/withdraw` 的「本人提报」属**条件**而非档位，仍由 `RequirementActionMatrix` 常量表达；授权表只决定「该角色是否具备此类动作资格」。
- `release.action.release`（实际发布）不由用户按钮触发，由审批通过后服务端推进，故不设动作码。

## 4. 范围与非目标

### 4.1 范围内

- V12 迁移：`sys_role_permission`（FUNCTION/MENU/ACTION）、`sys_menu`、`sys_action`、`sys_role.description/status`。
- 预设种子：600 功能码格 + 360 页面格 + 720 动作格 = 1680 行；36 页面、72 动作注册表；幂等与重置还原。
- 服务端：`PermissionService`（三序判定 + 缓存 + epoch 失效）、`MenuRegistry`/`ActionRegistry` 常量、`@RequiresAction` 与启动期校验、角色 CRUD/停用、授权读写、人员分配、组织删除与人员迁移、审计、防锁死、`PermissionRouteTable` 判定切换、`GET /auth/me/permissions`。
- 前端：`/admin/roles`、`/admin/roles/:id/permissions`（三级勾选）、`/admin/users` 扩展、`/admin/orgs` 树操作、`nav.ts` 可见性改读服务端、按钮改判动作码、`/admin/permissions` 只读化。
- 门禁：`scripts/rbac-verify.sh` + `make rbac-verify`（s0–s9），既有门禁基线同步上移。

### 4.2 非目标

- 不做 SCHEMA/DATABASE 级租户隔离（仍为 ROW，`tenant_id` 行级）。
- 不做用户级/组织级的直接授权（授权只挂角色，人员经角色获得）；不做临时授权（带有效期的授权）。
- 不做字段级权限/脱敏配置（`field_visibility` 属既有规划，另案）。
- 不做数据范围（`DataScope`）的可视化配置：`RESTRICTED` 只表达「范围收窄」，范围本身仍由角色的既有语义（厂商/域/组织）决定。
- 不做动作级**条件表达式编辑**（如「金额 > 10 万才需要二审」）：条件仍由 `RequirementActionMatrix` 等常量表达。
- 不做 OAuth/LDAP/SSO、不做用户自助注册、不做密码策略改造（沿用 `POST /admin/users/{id}/reset-password`）。
- 不做高危动作的审批流（保留二次确认 + 审计标记）。
- 不做暗色主题、移动端、国际化（沿用现有 i18n 基线断言）。
- 不做多实例缓存广播（本机单实例；多实例方案留待部署形态确定后另案）。

## 5. 反面目标 / anti-goals

> 即使 AC 全绿、门禁全绿，出现以下任一情形，人也会认为这次交付失败。

1. **升级后按钮悄悄变了**：某角色原本能点的「转派厂商」在升级后消失，或多出一个原本没有的「强制发布」；没人报错，因为没有任何测试比对「升级前能做什么」。→ 观测点：10 角色 × 72 动作的可用性打表 720/720 与升级前一致，且 `access-verify` 用例结论集合不变。
2. **保存了权限却要重新登录才生效**：管理员取消某角色的「评审」动作后，同一账号刷新仍看到按钮，点下去才 403；或必须退出重登。→ 观测点：保存后不重登，刷新即变，且直调接口同步 403。
3. **菜单、按钮、接口三处结论不一致**：菜单按服务端、按钮仍按前端常量，于是出现「按钮在、接口 403」或「菜单进不去、按钮却在别的页面显示」。→ 观测点：对同一动作码做「菜单可见 / 按钮渲染 / 接口状态」三次比对。
4. **动作授权页把 36 页面 × 72 动作平铺成一张大表**：为找「缺陷页的验证通过」要横向滚动十几屏、翻页十几次，或首屏超过 2s。→ 观测点：从进入页面到勾中任一动作 ≤ 3 次点击，横向滚动 ≤ 1 屏。
5. **防锁死做成「内置角色一律不可改」**：打开 `PRODUCT` 或 `PLATFORM_ADMIN` 的授权页，除少数项外全部置灰，「按需裁剪内置角色」这个主要诉求做不了。→ 观测点：能否在 UI 上改内置角色除两个冻结码外的任意页面/动作并保存成功。
6. **动作码与端点漂移**：新增了一个写端点却忘了声明动作码（或声明了一个注册表里没有的码），于是该按钮对所有人可用、或永远点不动，而 CI 全绿。→ 观测点：`rbac-verify` s9 报「未被动作码保护的写端点 = 0」与「声明了但未注册的动作码 = 0」。

## 6. 交互流程摘要

1. **建角色 → 授权**：`/admin/roles`「新建角色」（code/name/描述）→ 落 `sys_role(builtin=0,status=ACTIVE)` → 跳 `/admin/roles/:id/permissions` → 左树选模块/页面（页面勾选决定"能不能进"），右面板勾动作（决定"能不能点"）→ 每项可切 ALLOW/RESTRICTED/DENY → 保存 → 服务端事务写 `sys_role_permission`（三类 subject）+ 1 条审计 + `epoch++` → 前端 toast + 重新拉 `/auth/me/permissions`，当前用户菜单与按钮即刻变化。
2. **配人员**：`/admin/users` 行内「分配」→ 抽屉内 角色多选 / 组织单选（树选择）/ 业务域多选 / 厂商单选 → 保存 → 差集写 `sys_user_role`/`sys_user_domain`/`sys_user.org_id`/`vendor_id` + 审计 → 该用户下一次请求按新授权。
3. **登录与渲染**：登录 → `functionCodes`/`menus`/`actions` 三集合随会话返回 → 侧栏按「页面码已授权 且 绑定功能码非 DENY」渲染 → 按钮按 `can(actionCode)` 渲染 → 任何未授权接口仍由拦截器按「页面 → 动作 → DataScope」三序 403（前端隐藏不是防线）。
4. **改组织**：`/admin/orgs` 树内新增子组织 → 人员勾选「迁移到…」→ `POST /admin/orgs/{id}/move-users` → 事务改 `org_id` + 审计 → 空组织删除（有人员/子组织时 409 并给出人数）。
5. **回滚到预设**：`make platform-demo-reset` → 清自定义角色、内置角色三类授权 upsert 回预设 → `rbac-verify` s1/s2 重新打表全绿。

## 7. 优先级

| 优先级 | AC | 说明 |
|---|---|---|
| P0 | AC-1、AC-2、AC-3、AC-4、AC-5、AC-6、AC-8、AC-9、AC-10、AC-11、AC-12、AC-13、AC-15、AC-16、AC-18、AC-19、AC-20、AC-21、AC-22 | 数据模型、注册表、行为等价、单一判定、生效时效、防锁死、负向与门禁——缺任一条整体不可验收 |
| P0 | AC-7 | RESTRICTED 不回归是既有能力，回归即事故 |
| P1 | AC-14、AC-17 | 组织人员迁移、只读总览；缺失不阻断主流程但演示会缺环 |
| P2 | — | 本轮不排 P2；移动端/暗色/多实例广播见非目标 |

## 8. 依赖与风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 升级等价性做成了「数据对常量」而非「能力对能力」 | 数据全对但用户实际能点的按钮变了（anti-goal 1） | s2 强制 720 布尔 + 360 布尔等价断言，期望值由升级前推导函数生成；`access-verify` 前后结论集合比对 |
| 动作码清单与写端点漂移 | 漏保护的按钮对所有人开放，或声明了不存在的码导致启动失败（anti-goal 6） | 注解声明 + 启动期 fail-fast；s9 双向比对「未声明端点数 = 0」「未注册动作码 = 0」；`@PageOnly` 必须显式标注 |
| 注册表三源漂移（常量 / DB / nav.ts） | 页面永远不可见或出现幽灵菜单 | s1 三向比对，期望值从源码解析生成，禁止手抄 |
| 缓存失效漏点（漏掉某个写路径） | 改完权限不生效或短暂越权 | 写操作统一走 `PermissionService.invalidate(tenantId)` 单点；AC-8 断言「撤销后第 1 次请求」；lint 断言写路径必须经过该单点 |
| 防锁死误伤可用性（锁在门外或把管理页锁成只读） | 交付被拒（anti-goal 5） | 只冻结 `PLATFORM_ADMIN` 两个 FUNCTION 码 + 保留 1 个启用管理员；页面/动作档位可改；UI 说明「为何这两项不可改」 |
| 前端本地权限表残留（按钮仍按功能码/ACTION_META 过滤） | 三处不一致（anti-goal 3） | 删除二次过滤 + lint 静态断言（视图内 `permissions[` 直查命中 = 0）；AC-16 用 DB 撤销验证 |
| 动作数（72）高于澄清预估的 45–65 | 授权页信息量偏大、实施工期偏长 | 见 §9 待确认项；页面已按模块/页面分组 + 搜索 + 「仅看已授权」；必要时按候选合并，代价是牺牲按钮粒度 |
| 门禁基线计数上移幅度过大 | 交付节奏被门禁拖住 | 只上移下限（≥），不追求精确等值；新断言集中在 `rbac-verify` 单点 |
| 多实例部署时缓存不广播 | 多节点结论不一致 | 本轮明确单实例（非目标）；`epoch` + TTL ≤ 60s 兜底 |
| 迁移在已运行的演示库上执行（V1–V11 已有数据） | 迁移失败或角色行丢失 | V12 只增不改、条件插入（`INSERT ... SELECT ... WHERE NOT EXISTS`）；迁移前后 `sys_role` 计数断言相等 |

## 9. 假设与待澄清

> 已批准项不再询问：Q1 防锁死只冻结 `PLATFORM_ADMIN` 的 `org:manage` + `tenant:manage` 且保留 ≥1 启用管理员；Q2 菜单可见性双条件；Q3 保留 `/admin/permissions` 600 格只读总览；Q4 组织必填、业务域可空、厂商仅厂商账号必填；Q5 角色停用后允许登录、导航仅「我的」并顶部提示。以下每条进入状态块 `openQuestions`，`default` 为本轮推荐值。

### (a) 事实类（`kind: "fact"`）

- 无未决事实：本轮相关事实均已从仓库核实（60 功能码 / 10 角色 / 16 动作矩阵 / 35 个 nav 页面 / `sys_role` 无 status / 无三张新表 / 当前 V11 / 门禁基线 148·30·168·20·278·14·74 / `AdminOrgController` 无删除端点）。若实现期发现与上述不符，以代码为准并在技术方案中记录偏差。

### (b) 用户偏好类（`kind: "taste"`）

- **动作码粒度（72 个 vs 澄清预估 45–65）**：按「每个用户可见写操作一个码」盘点得 72 个（含 16 个需求流转动作 + `decompose`/`assign_vendor`）。default：**接受 72**（漏一个码就等于漏一个按钮的开关，粒度只能事后合并、不能事后补齐）；若坚持 ≤65，可合并候选为：`task.assign/move/close` → `task.action.manage`、`sprint.create/close` → `sprint.action.manage`、`org.create/edit/delete` → `org.action.manage`、`wiki.page_edit/page_delete` → `wiki.page_manage`（代价：这 4 组内无法再单独开关）。
- **高危动作能否授予非管理员**：default：**允许授予，但保存时二次确认并写 `highRiskGranted` 审计**（保住「可配置」，同时留痕）；备选：硬禁止（更安全，但会让"把强制发布临时交给某运维负责人"做不了）、或不做任何特殊处理（最灵活，但误授无痕迹）。
- **页面与动作不匹配时是否硬拒保存**：default：**允许保存、生效判 DENY，UI 给黄色警示**（配置可以先搭结构后补页面，且与「页面为前置」的判定一致）；备选：保存时直接 400（更防错，但管理员必须先勾页面再回填动作，操作打断感强）。
- **未声明动作码的写端点如何处置**：default：**启动期 fail-fast（声明了未注册码即启动失败）+ CI 扫描要求每个写端点要么 `@RequiresAction` 要么显式 `@PageOnly`**（防漏保护）；备选：仅告警不阻断（升级更快，但漏保护会静默上线）。

## 10. 里程碑建议

| 里程碑 | 内容 | 退出判据 |
|---|---|---|
| M1 数据与注册表 | V12 迁移、`MenuRegistry`/`ActionRegistry`、种子生成（1680 行）、`rbac-verify` s0/s1 | AC-1、AC-2、AC-3、AC-4 绿；三向比对与 1680 格打表全绿 |
| M2 单一判定与生效 | `PermissionService`（三序 + 并集 + DENY 优先 + epoch 缓存）、拦截器/上下文/登录响应切换、`@RequiresAction` + 启动校验、路由准入切换、`/auth/me/permissions` | AC-5、AC-6、AC-7、AC-8、AC-9 绿；s2/s3/s9 段绿 |
| M3 管理 API | 角色 CRUD/停用、授权读写（三类 subject）+ 审计、人员授权、组织删除与迁移、防锁死、高危标记 | AC-10…AC-14、AC-18、AC-19 绿；s5/s6/s7/s8 段绿 |
| M4 前端 | `/admin/roles`、三级授权页、用户分配抽屉、组织树、nav 可见性、按钮改动作码、只读总览 | AC-15、AC-16、AC-17、AC-22 绿；截图入 `evidence/` |
| M5 门禁与收口 | 既有门禁基线复跑与上移、`make test` 纳入 rbac-verify、文档与 memory 更新 | AC-20、AC-21 绿；全量 `make test` 绿 |

## 11. 工程约束

**版本控制（工程动作，按现状执行）**
- 当前分支 `feat/rbac-admin`，未提交改动仅 1 处：`?? docs/teamflow/20261113-r18-rbac-role-permission/`（`meta.json`，host 创建）。**在该分支上继续**，不切分支、不 rebase 已推送历史；本需求的 `PRD.md`/`TECHNICAL.md`/代码/门禁脚本都在此分支提交。
- 首个实现提交把任务夹（`meta.json` + `PRD.md`）与 V12 迁移一起提交，避免「代码已提交、文档丢失」：`feat(rbac): 组织/角色/人员权限可配置化（V12 三层授权表 + PermissionService 单点）`。
- `.gitignore` 审计（本栈真实工具，交付前补齐）：现有已覆盖 `target/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`.env*`、`*.log`、`*.xlsx`、`*.csv`、`test-results/`、`playwright-report/`、`.idea/`、`.vscode/`、`*.pid`、`*.hprof`。本轮补：`*.eslintcache`（ESLint/Vite 本地缓存）、`*.local`（Vite env 覆盖）、`*.dump` 与 `*.sql.gz`（本机 MySQL 导出）。
- **禁止**把 `*.sql` 整体忽略——V12 迁移就是 `.sql`；也禁止忽略 `docs/teamflow/**/evidence/*.png`（验收证据必须入库）与 `config/nacos/` 种子。

**迁移与数据**
- 新增表走 Flyway 新版本（当前 V11）→ **V12**；不得修改 V1–V11 已执行脚本。
- 种子必须幂等：`INSERT ... SELECT ... WHERE NOT EXISTS`（可重复执行，第二次运行行数不变）。
- `make platform-demo-reset` 后角色与权限配置回到预设（可重复执行、幂等）。

**不改动面**
- 不改需求状态机、不改审批流模板、不改演示数据夹具的业务语义。
- `RESTRICTED` 语义与 `DataScope` 实现零改动；`RequirementActionMatrix` 的 16 动作与其条件表达式零改动，内部回调（`source=APPROVAL/GITLAB`）不进入用户授权矩阵。

**新增写端点的规约**
- 每个写端点必须声明 `@RequiresAction(<注册表内动作码>)`，或显式 `@PageOnly`（仅页面级保护，并在 s9 白名单登记）；两者都没写 ⇒ `make lint` 失败。
- 一对多动作端点用 `@RequiresActionFromBody`，动作码前缀必须落在 §3.1 表内。

**UI**
- 按 `docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`（白卡片、1px 边框、蓝色只用于主操作/当前项、状态浅底方 pill、表格行高 44）、Element Plus，不引新依赖、不做暗色主题/移动端。

**门禁（必须全绿并同步更新基线）**
- `platform-verify`、`access-verify`、`platform-test-negative`、`lint`、`unit`、r1 `verify 14/14`、`analytics-i18n-verify`、`collab-verify`。
- `PermissionMatrixGridTest` 的 600 格断言保留不动；等价断言由 `scripts/rbac-verify.sh` + `make rbac-verify` 承担（s1 注册表、s2 1680 格 + 720 布尔等价、逐项打表、失败指到具体角色/页面码/动作码）。
- 负向用例至少覆盖：无 `org:manage` 的人打不开角色管理接口（403）、非管理员改不动角色权限、把 `PLATFORM_ADMIN` 权限清空被拒（409/422 且给出原因）、角色停用后其用户访问被拦、DENY 覆盖 ALLOW、跨租户不可见角色、有动作无页面（403）、有页面无动作（按钮不渲染 + 直调 403）、高危动作未授（403）。
