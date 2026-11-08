# TECHNICAL · DevOps 研发管理平台（本轮 M1–M5）技术方案

> 基线：`docs/teamflow/20261108-r1-springcloud-env-setup`（r1 四步必须继续全绿）
> 输入：`docs/teamflow/20261108-r2-devops-req-platform/PRD.md`、`DESIGN.md`、`docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md`
> 目标：把 20 条 AC 落成可实现的模块、表、接口、脚本与任务边界；下游开发按本文件逐字实现，不得自行改名。
> 环境实测（2026-11-09）：JDK 25.0.2 / Maven 3.9.12 / Node 22.22.0 / pnpm 10.30.0 / MySQL 9.6.0（brew，`root@localhost` 可连）/ Nacos 3.3.0 standalone。

## 0. 结论摘要

- **工程形态**：父 POM + `platform-contract`（纯 Java 契约，零 Spring）+ `platform-common`（Servlet/JPA 运行时）+ `req-service`(8082) + `deliver-service`(8083) + `api-gateway`(8080，复用) + `web`(5173，Vite 预览)。`demo-service`/8081 与 r1 脚本一行不改。
- **持久化**：Spring Data JPA(Hibernate 7) 负责写，`ScopedJdbc` 负责读。**不选 MyBatis-Plus**：其 starter 面向 Boot 3 / mybatis-spring 3，Boot 4.0.9 无兼容承诺；JPA 随 Boot 4 发布，版本风险为零。复杂列表/看板/矩阵用命名参数 JDBC，避免 ORM 生成不可控 SQL。
- **隔离**：每张业务表冗余 `tenant_id/domain_id/vendor_id`，读路径统一走 `ScopedJdbc`（SQL 必须带 `/*scope:alias*/` 标记，缺失即抛异常 = fail-closed），按 ID 读走 `ScopeGuard.requireVisible`。**禁止只在列表接口过滤**由静态门禁（lint）+ 单一读入口保证。
- **鉴权**：网关校验 JWT（HS256）与 `X-Tenant-Id` 一致性，服务内再次校验并建立 `TenantContext`；`/api/demo/**` 保持 r1 匿名，保证基线四步不回退。
- **审批**：自研轻量引擎，定义 JSON 版本化，实例钉住 `definition_version`；会签按「剩余待办为 0」推进，加签后新人全过才流转。
- **门禁**：唯一实现在 `deliver-service/TestGateService`；`req-service` 的 RD「测试通过」通过 `GatePort` 调用，界面按钮与接口拒绝同源。
- **数据库**：`devops_platform`（utf8mb4/0900_ai_ci），39 张表，Flyway 仅在 `req-service` 启用（唯一迁移者），`deliver-service` `flyway.enabled=false` + `ddl-auto=validate`。
- **验证**：`make platform-up / platform-verify / platform-test-negative / platform-seed / platform-unit / platform-perf`，并全部纳入 `make test`；r1 的 `dev-up/verify/test-negative/dev-down` 保持原样并通过同一脚本回归。

### 0.1 与 PRD/DESIGN 的偏差（均已给出理由，需 QA 按此验收）

| # | 偏差 | 理由 | 影响 |
| --- | --- | --- | --- |
| D1 | `api_client` 同时存 `secret_hash`(PBKDF2) 与 `secret_enc`(AES-GCM) | HMAC-SHA256 验签**必须持有原密钥**，只存哈希在数学上无法验签；PRD"密钥哈希存储"按"静态不可明文可读"落地：密文用 `PLATFORM_MASTER_KEY` 加密，哈希用于展示/比对与审计，明文只在创建/轮换时返回一次 | AC-17 的 401/403 可验；验收报告需标注本偏差 |
| D2 | 门禁只在「存在关联测试计划」时生效；无计划时 RD 用 §4.7「快速测试记录」放行 | §4.7 明确快速测试通过即进「测试通过」，§4.14 门禁针对"关联计划"；AC-16 的"计划未关联用例"指**计划存在但为空**（按不满足门禁处理），不是"没有计划" | AC-16 断言需覆盖两种输入：无计划→快速记录可过；有计划且空→409 |
| D3 | 发布单驳回 → 单据回 `DRAFT`（可修订重提），**不回退需求状态** | PRD §3.3 写"回待测试/开发中"，但回退需求会让已产生的执行记录与门禁结论失效，与 AC-8「不跳步」冲突；AC-10 的"按节点回退"解释为单据自身回退 | AC-10 的发布单分支按 DRAFT 断言 |
| D4 | 10 角色 × 功能码的矩阵写在 `platform-contract/PermissionMatrix`（代码常量），不做 DB 可编辑 | 矩阵必须逐格可断言（AC-5）；DB 可编辑会引入配置漂移且本轮无权限配置界面需求 | 角色增删仍经 `/admin/roles`（只读预置集）；M8 再开配置 |
| D5 | 版本发布的"级联推进"= `MERGED` 的 RD → `DONE`，全部 RD 完成的 PDR → `DELIVERED` | 把"推进"落成可断言的状态变化，且不伪造未发生的动作（不把未合入的 RD 直接置完成） | AC-10 版本分支按此断言 |
| D6 | 提交 scope 使用 `feat(req\|approval\|release\|defect\|test)`；前端归入 `req`/`defect`，脚本归入 `test` | PRD 只给 5 个 scope；不新增 `web/platform` 以免破坏格式 | 提交历史可读性略降 |

## 1. 技术选型与关键决策

| 决策点 | 选择 | 理由 | 被否方案 |
| --- | --- | --- | --- |
| 持久层 | Spring Data JPA(Hibernate 7) 写 + `ScopedJdbc` 读 | 随 Boot 4 发布、零兼容风险；读路径需精确控制 SQL 与 scope 注入 | MyBatis-Plus（Boot 4 无承诺）；纯 JPA（聚合 SQL 不可控、100k 列表风险） |
| 认证 | 自研 HS256 JWT（JDK `Mac` + Base64URL）+ PBKDF2 口令 | 避免 Spring Security 7 在 Boot 4 的配置迁移；JWT 与 HMAC 共用一套 `platform-contract`，网关与两服务零重复实现 | jjwt（额外依赖且需版本验证）、Spring Security（过滤器链与 r1 无鉴权链路冲突） |
| 审批引擎 | 自研（线性节点 + 会签/或签 + 条件 + 抄送） | PRD 明确禁止 Flowable/Camunda；场景是固定单据线性流 | Flowable、Camunda、BPMN 画布 |
| 服务拆分 | req-service（组织/需求/版本/审批/发布）+ deliver-service（缺陷/任务/测试/追溯/指标/开放 API） | PRD 工程约束；读写边界与团队并行边界一致 | 单体（与 PRD 冲突）、按表拆（过度） |
| 共享代码 | `platform-contract`（纯 Java）+ `platform-common`（Servlet/JPA） | 网关是 WebFlux，若直接依赖 Servlet 模块会把 MVC 拖进网关；契约模块保持零框架 | 单一 common（网关依赖污染） |
| 前端托管 | `vite build` + `vite preview`（5173，代理 `/api`、`/openapi` 到 8080） | 本机无 Docker；同源代理避免 CORS；SPA history 回退由 Vite 处理 | 网关托管静态资源（WebFlux SPA 回退脆弱）、另起 nginx |
| 数据库迁移 | Flyway 仅在 req-service；deliver 只 `validate` | 两服务同库，双迁移器会争抢 `flyway_schema_history` | 独立迁移模块（多一个启动单元） |
| 编号 | `code_sequence(tenant_id, code_type)` 行锁自增 | 按租户独立、按类型前缀；不依赖表内 `MAX` | `AUTO_INCREMENT` 后拼前缀（跨租户不独立） |
| 跨服务调用 | `RestClient` + `@LoadBalanced`，内部令牌 `X-Internal-Token` | 只需 2 个内部读接口；不引 Feign/Resilience4j | Feign（Boot 4 兼容待验）、共享事务（跨服务事务不可取） |

### 1.1 代码风格约束（与 r1 一致）

- 构造器注入、字段 `final`、无 Lombok/MapStruct（不新增依赖）；DTO 用 `record`；包按功能分层（`requirement/`、`approval/`、`defect/`…）。
- 控制器薄（参数校验 + 调服务 + 组装响应）；事务只在 service；错误一律抛 `ApiException(status, code, message, detail)`，由 `GlobalExceptionHandler` 统一出 JSON。
- 用户可见文案中文，稳定错误码英文大写下划线；两者同时出现在响应里，脚本断言 `code`。
- 注释只解释"为什么"（与 r1 的 `ApiGatewayApplication` 风格一致），不复述代码。

## 2. 工程结构与构建

### 2.1 目录树（新增部分）

```
pom.xml                              # modules: platform-contract, platform-common, api-gateway, demo-service, req-service, deliver-service
platform-contract/                   # 纯 Java：枚举/状态表/权限矩阵/JWT/HMAC/指标公式/端口接口
  src/main/java/com/example/platform/contract/
    Role.java Access.java PermissionMatrix.java
    JwtCodec.java JwtPayload.java PasswordHasher.java
    HmacSigner.java
    DataScope.java ScopeTier.java
    RequirementType.java RequirementStatus.java DefectStatus.java ApprovalStatus.java StateTables.java
    GateDecision.java GateMode.java GateBlocker.java GatePort.java ApprovalPort.java ApprovalCallback.java
    MetricFormulas.java MetricCode.java
    ApiError.java ErrorCode.java PageResponse.java CodePrefix.java
platform-common/                     # Servlet/JPA 运行时，仅被两个服务依赖
  src/main/java/com/example/platform/common/
    tenant/TenantContext.java
    security/AuthFilter.java UserScopeResolver.java InternalTokenFilter.java
    scope/ScopeAware.java ScopeGuard.java ScopeSql.java ScopedJdbc.java SqlPredicate.java
    audit/AuditService.java
    code/CodeGenerator.java
    error/ApiException.java GlobalExceptionHandler.java
    notify/NotificationWriter.java
    jpa/TenantAwareEntity.java BaseEntity.java
    id/IdempotencyKey.java
req-service/
  src/main/java/com/example/req/
    ReqServiceApplication.java config/{WebConfig,ClientConfig}.java
    auth/{AuthController,AuthService,LoginRequest,LoginResponse}.java
    sys/{tenant,org,vendor,domain,user,role,dict}/...
    requirement/{RequirementService,RequirementController,RequirementRepository,RequirementEntity,
                 RequirementStateMachine,RequirementRollup,RequirementPoolQuery,RequirementExport,
                 RequirementSummaryProvider,RequirementApprovalCallback}.java
    release/{ReleaseService,ReleaseController,ReleaseEntity,ReleasePublishCascade}.java
    approval/{ApprovalEngine,ApprovalController,WorkflowDefinition,WorkflowDefinitionValidator,
              ApproverResolver,ApprovalTaskRepository,WorkflowRepository}.java
    deploy/{DeployOrderService,DeployOrderController,AdmissionEvaluator,AdmissionItem}.java
    integration/{DeliverGateClient,InternalTokenClient}.java
    seed/SeedService.java
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__platform_schema.sql
deliver-service/
  src/main/java/com/example/deliver/
    DeliverServiceApplication.java
    reqread/ReqReadModel.java
    defect/{DefectService,DefectController,DefectEntity,DefectStateMachine,DefectBoardQuery,DefectSummaryProvider}.java
    task/{TaskService,SprintService,IterationBoardQuery}.java
    testcase/{TestCaseService,TestCaseController,TestCaseVersionRepository,TestModuleService}.java
    testplan/{TestPlanService,TestPlanController,ExecutionService,PlanSummaryQuery}.java
    gate/{TestGateService,GateController}.java
    trace/{TraceabilityQuery,TraceController}.java
    metric/{MetricQuery,MetricController,MetricDefinitionService}.java
    openapi/{OpenApiController,HmacAuthFilter,ExecutionIngestService,RateLimiter,ApiClientService}.java
web/                                 # Vue 3 + Vite + Element Plus + Pinia + ECharts
  src/{main.ts,App.vue,styles,api,stores,router,layouts,components,composables,views}
scripts/platform-*.sh  scripts/tests/platform/*  Makefile
```

### 2.2 模块依赖与构建顺序

```
platform-contract  (无 Spring)
      ▲        ▲
      │        └──────── api-gateway (WebFlux，仅用 JwtCodec/HmacSigner)
platform-common (spring-web, spring-data-jpa, jdbc)
      ▲        ▲
 req-service  deliver-service   (spring-boot-maven-plugin repackage)
      ▲
 demo-service / api-gateway（r1 原样保留，仅新增路由与 JWT 过滤器类）
```

根 `pom.xml` 的 `<modules>` 顺序即构建顺序：`platform-contract → platform-common → api-gateway → demo-service → req-service → deliver-service`。新模块继承父 POM，`java.version=25`；两个服务加 `spring-boot-maven-plugin`。新增受管依赖：`mysql-connector-j`、`flyway-core`、`flyway-mysql`（版本由 Boot 4.0.9 BOM 决定，**不手写版本号**）。`platform-common` 额外引 `spring-boot-starter-web`、`spring-boot-starter-data-jpa`、`spring-boot-starter-validation`；服务模块再引 `spring-cloud-starter-alibaba-nacos-discovery`、`spring-cloud-starter-loadbalancer`、`actuator`。

### 2.3 网关路由（新增 `PlatformRoutes.java`，r1 文件零改动）

```java
@Bean
RouteLocator platformRoutes(RouteLocatorBuilder b) {
    return b.routes()
        .route("req-service", r -> r.order(-100).path("/api/req/**")
            .filters(f -> f.stripPrefix(2)).uri("lb://req-service"))
        .route("deliver-service", r -> r.order(-90).path("/api/deliver/**")
            .filters(f -> f.stripPrefix(2)).uri("lb://deliver-service"))
        .route("deliver-openapi", r -> r.order(-80).path("/openapi/**")
            .filters(f -> f.stripPrefix(1)).uri("lb://deliver-service"))
        .build();
}
```

- 现有 demo 路由 `/api/**` 的默认 order=0，被 -100/-90 精确前缀优先命中；`/api/demo/hello` 仍落到 demo-service。
- **不得**把新路由写进 `ApiGatewayApplication.demoRoutes()`，避免改动 r1 已验证代码。
- `JwtGatewayFilter`（GlobalFilter，order=-200）：放行 `/api/req/auth/login`、`/openapi/**`、`/actuator/**`、`/api/demo/**`；其余缺 token→401，token 无效/过期→401，`X-Tenant-Id` 缺失或与 JWT `tenantId` 不一致→403（且不转发）。`/api/demo/**` 匿名是基线契约：r1 的 verify 不带 token，鉴权会把 r1 四步打红。

### 2.4 配置与环境变量（`.env.example` 入库，`.env` 忽略）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PLATFORM_DB_URL` | `jdbc:mysql://127.0.0.1:3306/devops_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8` | 库名固定 `devops_platform`；**必须写 `UTF-8`**：Connector/J 9.7 会以 `Unsupported character encoding 'utf8mb4'` 拒绝启动（2026-11-09 实测） |
| `PLATFORM_DB_USER` / `PLATFORM_DB_PASSWORD` | `root` / 空 | 本机 brew MySQL 实测可连 |
| `PLATFORM_JWT_SECRET` | 开发默认值（≥32 字符，`application.yml` 内注明 dev-only） | 网关与两服务必须一致 |
| `PLATFORM_INTERNAL_TOKEN` | `dev-internal-token` | `/internal/**` 校验，网关不路由该前缀 |
| `PLATFORM_MASTER_KEY` | 开发默认 32 字节 Base64 | AES-GCM 加密 API Secret |
| `PLATFORM_SEED_ENABLED` | `false`；`platform-up` 置 `true` | 关闭时 `/internal/seed` 返回 404 |
| `REQ_PORT` / `DELIVER_PORT` / `WEB_PORT` | 8082 / 8083 / 5173 | 允许覆盖 |
| `PLATFORM_OPENAPI_QPS` | 100 | 客户端未配时的默认限流 |

`spring.jpa.hibernate.ddl-auto=validate`（两服务）；`spring.jpa.open-in-view=false`；`spring.jackson.time-zone=Asia/Shanghai`；`management.endpoint.health.show-details=always`。

### 2.5 启动顺序与就绪判定（禁止 `sleep`）

1. `Nacos`（r1 `dev-up` 已保证，platform-up 先调 `./scripts/dev-up.sh`，幂等）。
2. MySQL 可连：`mysqladmin ping` + `SELECT 1`；缺库则 `CREATE DATABASE IF NOT EXISTS devops_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`。
3. `req-service` 启动（Flyway 迁移在此发生）；就绪判据 `GET http://127.0.0.1:8082/internal/health` 返回 `{"status":"UP","db":"up","flyway":"V1"}`。
4. `deliver-service` 启动（`ddl-auto=validate` 校验迁移结果）；就绪判据 `GET 8083/internal/health` 含 `"db":"up"`。
5. 网关路由生效：`GET /api/req/internal/health` 经网关 200（证明 lb 注册与路由同时就绪；LoadBalancer 缓存最长 35s，等待上限 90s，超时打印"检查 Nacos 实例与负载均衡缓存"）。
6. `web`：`pnpm -C web install --frozen-lockfile`（首跑无 lock 时退化为 `install`）→ `pnpm -C web build` → `vite preview --port ${WEB_PORT}`；就绪判据 `curl -fsS http://127.0.0.1:${WEB_PORT}/` 命中 `<!doctype html>`。

任一步失败：打印「哪一步 + 缺什么 + 怎么修」，退出码非 0，不残留半启动进程（EXIT trap 逆序回收）。

## 3. 数据模型与存储

### 3.1 通用约定

- 主键 `id BIGINT UNSIGNED AUTO_INCREMENT`；每张业务表 `tenant_id BIGINT NOT NULL` 且**唯一键一律包含 `tenant_id`**（`api_client.app_key` 例外：验签前还不知道租户，全局唯一，见 §3.5）。
- 审计列：`created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)`、`updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)`、`created_by BIGINT NULL`、`updated_by BIGINT NULL`；JPA 侧由 `BaseEntity` 统一维护。
- 乐观锁：`version INT NOT NULL DEFAULT 0`（`@Version`）用于可编辑业务行；审批推进另用悲观锁（§5.6）。
- 金额/人天：`DECIMAL(6,1)`，只允许 0.5 的整数倍（服务端校验，`INVALID_DAY_PRECISION`）；工时 `DECIMAL(6,1)`。
- 枚举：`VARCHAR(24)` 存英文码，中文文案只在前端/字典，避免 MySQL ENUM 迁移锁表。
- 软删仅 `test_case.deleted_at`；其余业务行不物理删除（状态机表达关闭）。
- "派生隔离字段"：`defect/task/test_execution` 的 `domain_id/vendor_id` 由关联需求派生写入，**请求体里的同名字段忽略**；需求转派厂商时按 §5.2.4 级联更新。

### 3.2 表清单（`V1__platform_schema.sql`，39 张）

> 下列"键/索引"中未标注的普通索引均以 `(tenant_id, ...)` 开头；`uk_` 唯一、`idx_` 普通。

**组织与账号**

| 表 | 列（类型） | 键/索引 |
| --- | --- | --- |
| `sys_tenant` | id, code VARCHAR(32), name, isolation_mode VARCHAR(16) 默认 ROW, datasource_key VARCHAR(64) NULL, status VARCHAR(16), 审计列 | `uk_tenant_code(code)`；本表无 `tenant_id`（自身即租户，例外） |
| `sys_org` | id, tenant_id, parent_id BIGINT NULL, type VARCHAR(16) GROUP/SUB_ORG/DEPT, name, path VARCHAR(512), sort_no INT, 审计列 | `uk_org_path(tenant_id, path)`；`idx_org_parent(tenant_id, parent_id)` |
| `sys_vendor` | id, tenant_id, code VARCHAR(32), name, contact, status, 审计列 | `uk_vendor_code(tenant_id, code)` |
| `biz_domain` | id, tenant_id, code, name, owner_user_id, enabled TINYINT, sort_no, 审计列 | `uk_domain_code(tenant_id, code)` |
| `sys_user` | id, tenant_id, username VARCHAR(64), name, password_hash VARCHAR(200), org_id, vendor_id NULL, status, last_login_at, 审计列 | `uk_user(tenant_id, username)`；`idx_user_vendor(tenant_id, vendor_id)` |
| `sys_role` | id, tenant_id, code VARCHAR(32), name, builtin TINYINT, 审计列 | `uk_role(tenant_id, code)`；预置 10 条 |
| `sys_user_role` | tenant_id, user_id, role_id | `pk(user_id, role_id)`；`idx_ur_role(tenant_id, role_id)` |
| `sys_user_domain` | tenant_id, user_id, domain_id | `pk(user_id, domain_id)` |
| `sys_dict` | id, tenant_id, type VARCHAR(32), code, label, sort_no, 审计列 | `uk_dict(tenant_id, type, code)` |
| `code_sequence` | tenant_id, code_type VARCHAR(16), next_val BIGINT | `pk(tenant_id, code_type)` |

**需求、版本、发布**

| 表 | 列（类型） | 键/索引 |
| --- | --- | --- |
| `requirement` | id, tenant_id, code VARCHAR(24), type VARCHAR(8) BUS/PDR/RD, parent_id NULL, domain_id, origin_org_id, reporter_id, owner_user_id NULL, owner_vendor_id NULL, assignee_vendor_id NULL, title VARCHAR(200), description TEXT, priority VARCHAR(4), status VARCHAR(24), estimate_days DECIMAL(6,1) NULL, actual_days DECIMAL(6,1) NULL, mr_url VARCHAR(500) NULL, release_id NULL, expected_delivery_date DATE NULL, plan_start_date DATE NULL, plan_end_date DATE NULL, closed_at DATETIME(3) NULL, test_passed_execution_id BIGINT NULL, 审计列, version | `uk_req_code(tenant_id, code)`；`idx_req_list(tenant_id, type, status, domain_id, updated_at)`；`idx_req_parent(tenant_id, parent_id)`；`idx_req_vendor(tenant_id, assignee_vendor_id, status)`；`idx_req_release(tenant_id, release_id)`；`idx_req_title(tenant_id, title(64))` |
| `requirement_domain_rel` | tenant_id, requirement_id, domain_id | `pk(requirement_id, domain_id)` |
| `release` | id, tenant_id, code VARCHAR(24), name, domain_id, status VARCHAR(16) PLAN/LOCKED/PUBLISHED/CANCELLED, start_date, end_date, planned_release_date, gate_mode VARCHAR(8) STRICT/LOOSE 默认 STRICT, locked_at NULL, released_at NULL, 审计列, version | `uk_release_code(tenant_id, code)`；`idx_release_domain(tenant_id, domain_id, status)` |
| `milestone` | id, tenant_id, release_id, name, planned_date, status, 审计列 | `idx_ms_release(tenant_id, release_id)` |
| `release_requirement` | tenant_id, release_id, requirement_id, added_by, added_at | `pk(release_id, requirement_id)`；`idx_rr_req(tenant_id, requirement_id)` |
| `attachment` | id, tenant_id, biz_type VARCHAR(24), biz_id, file_name, path VARCHAR(500), size BIGINT, content_type, sha256 CHAR(64), uploader_id, domain_id, vendor_id NULL, 审计列 | `idx_att_biz(tenant_id, biz_type, biz_id)` |

**审批**

| 表 | 列（类型） | 键/索引 |
| --- | --- | --- |
| `wf_definition` | id, tenant_id, name, biz_type VARCHAR(24) REQ_SUBMIT/REQ_REVIEW/RELEASE/REQ_DEPLOY, req_type VARCHAR(8) 默认 `ANY`, domain_id BIGINT 默认 `0`（0=不限，避免 NULL 破坏唯一性）, version INT, definition_json JSON, status VARCHAR(16) DRAFT/PUBLISHED/DISABLED, is_default TINYINT, published_at, published_by, 审计列 | `uk_wf_def(tenant_id, biz_type, req_type, domain_id, version)`；`idx_wf_lookup(tenant_id, biz_type, status, is_default)` |
| `wf_instance` | id, tenant_id, definition_id, definition_version INT, biz_type, biz_id, initiator_id, status VARCHAR(16) PENDING/APPROVED/REJECTED/WITHDRAWN, current_node VARCHAR(32), active_flag TINYINT NULL, started_at, ended_at NULL, 审计列 | `uk_wf_instance_active(tenant_id, biz_type, biz_id, active_flag)`（`active_flag`：PENDING=1，终态=NULL；MySQL 唯一索引允许多个 NULL → 同一单据同时只有一个在途实例）；`idx_wf_i_todo(tenant_id, status, initiator_id)` |
| `wf_task` | id, tenant_id, instance_id, node_key, node_name, approver_id, mode VARCHAR(8) ANY/ALL, status VARCHAR(16) PENDING/APPROVED/REJECTED/CANCELLED, result VARCHAR(16) NULL, comment, origin VARCHAR(8) INITIAL/ADD_SIGN, add_sign_type VARCHAR(8) NULL BEFORE/AFTER, parent_task_id NULL, sign_no INT 默认 0, sort_no INT, handled_at NULL, 审计列 | `uk_wf_task(instance_id, node_key, approver_id, sign_no)`；`idx_wf_task_todo(tenant_id, approver_id, status)` |
| `wf_history` | id, tenant_id, instance_id, task_id NULL, action VARCHAR(24), operator_id, comment, detail_json JSON NULL, created_at | `idx_wf_h(tenant_id, instance_id, created_at)` |
| `wf_cc` | id, tenant_id, instance_id, node_key, receiver_id, read_at NULL, created_at | `idx_wf_cc(tenant_id, receiver_id, read_at)` |

**缺陷、任务、迭代**

| 表 | 列（类型） | 键/索引 |
| --- | --- | --- |
| `defect` | id, tenant_id, code VARCHAR(24), requirement_id NULL, release_id NULL, test_case_id NULL, test_case_version INT NULL, test_execution_id NULL, title, description TEXT, repro_steps TEXT, severity VARCHAR(4) P0–P3, priority VARCHAR(4), status VARCHAR(16) NEW/IN_PROGRESS/PENDING_VERIFY/CLOSED, reporter_id, handler_id NULL, domain_id, vendor_id NULL, reopen_count INT 默认 0, expected_fix_date DATE NULL, found_at DATETIME(3), closed_at DATETIME(3) NULL, 审计列, version | `uk_defect_code(tenant_id, code)`；`idx_defect_list(tenant_id, domain_id, vendor_id, status, severity)`；`idx_defect_req(tenant_id, requirement_id)`；`idx_defect_closed(tenant_id, closed_at)` |
| `defect_history` | id, tenant_id, defect_id, action VARCHAR(24), from_status, to_status, operator_id, comment, created_at | `idx_dh(tenant_id, defect_id, created_at)` |
| `sprint` | id, tenant_id, release_id, name, goal VARCHAR(500), start_date, end_date, capacity_days DECIMAL(6,1), status VARCHAR(16) PLANNING/ACTIVE/CLOSED, 审计列 | `idx_sprint(tenant_id, release_id, status)` |
| `task` | id, tenant_id, requirement_id, sprint_id NULL, title, assignee_id NULL, estimate_hours DECIMAL(6,1), start_date DATE NULL, due_date DATE NULL, status VARCHAR(8) TODO/DOING/DONE/BLOCKED, sort_no INT, domain_id, vendor_id NULL, 审计列, version | `idx_task_req(tenant_id, requirement_id, status)`；`idx_task_board(tenant_id, sprint_id, status, sort_no)` |

**测试**

| 表 | 列（类型） | 键/索引 |
| --- | --- | --- |
| `test_module` | id, tenant_id, parent_id NULL, name, path VARCHAR(512), level TINYINT 1–3, domain_id, vendor_id NULL, sort_no, 审计列 | `uk_module_path(tenant_id, path)`；`idx_module_parent(tenant_id, parent_id)` |
| `test_case` | id, tenant_id, code VARCHAR(24), module_id, title, type VARCHAR(8) FUNC/API/PERF/COMPAT, priority VARCHAR(4), precondition TEXT, tags VARCHAR(200), automated TINYINT, script_ref VARCHAR(300) NULL, status VARCHAR(12) ACTIVE/DISABLED, current_version INT, requirement_id NULL, domain_id, vendor_id NULL, deleted_at NULL, 审计列, version | `uk_case_code(tenant_id, code)`；`idx_case_module(tenant_id, module_id, deleted_at)` |
| `test_case_step` | id, tenant_id, case_id, version_no INT, step_no INT, action VARCHAR(500), expected VARCHAR(500) | `uk_step(case_id, version_no, step_no)` |
| `test_case_version` | id, tenant_id, case_id, version_no INT, snapshot_json JSON, editor_id, created_at | `uk_case_ver(case_id, version_no)` |
| `test_plan` | id, tenant_id, release_id, name, window_start DATETIME(3) NULL, window_end NULL, owner_id, status VARCHAR(12) DRAFT/RUNNING/DONE, gate_mode VARCHAR(8) NULL（继承 release）, domain_id, vendor_id NULL, 审计列 | `idx_plan(tenant_id, release_id, status)` |
| `test_plan_case` | id, tenant_id, plan_id, case_id, case_version INT, requirement_id NULL, executor_id NULL, result VARCHAR(12) NOT_RUN/PASS/FAIL/BLOCKED/SKIP, execution_id NULL, last_executed_at NULL, note VARCHAR(500) NULL | `uk_plan_case(plan_id, case_id)`；`idx_plan_case_req(tenant_id, requirement_id, result)` |
| `test_execution` | id, tenant_id, plan_id NULL, case_id NULL, case_version INT NULL, requirement_id NULL, source VARCHAR(8) MANUAL/AUTO, run_id VARCHAR(64) NULL, result VARCHAR(12), duration_ms INT NULL, log_url VARCHAR(500) NULL, evidence_file VARCHAR(300) NULL, executor_id NULL, executed_at DATETIME(3), client_id NULL, ip VARCHAR(45) NULL | `uk_exec_auto(tenant_id, client_id, run_id, case_id)`；`idx_exec_case(tenant_id, case_id, executed_at)`；`idx_exec_plan(tenant_id, plan_id, executed_at)`；`ck_exec_auto CHECK (source='MANUAL' OR (client_id IS NOT NULL AND run_id IS NOT NULL AND case_id IS NOT NULL))` |

> `uk_exec_auto` 的 3 个可空列 + MySQL「NULL 互不相等」= 手工执行（三列全 NULL）不参与去重，自动上报按 `(client, run_id, case)` 去重。`ck_exec_auto` 把自动行钉成三列非空，堵住"漏传 run_id 逃逸唯一键"的口子。

**指标、开放 API、审计、通知**

| 表 | 列（类型） | 键/索引 |
| --- | --- | --- |
| `metric_definition` | id, tenant_id, code VARCHAR(32), name, topic VARCHAR(32), formula VARCHAR(500), datasource VARCHAR(200), caliber_note VARCHAR(500), owner_id, version INT, effective_at DATETIME(3), 审计列 | `uk_metric(tenant_id, code, version)` |
| `api_client` | id, tenant_id, name, app_key VARCHAR(64), secret_hash VARCHAR(200), secret_enc VARBINARY(512), scopes VARCHAR(300), ip_whitelist VARCHAR(500), rate_limit_qps INT 默认 100, status VARCHAR(12) ACTIVE/DISABLED, 审计列 | `uk_app_key(app_key)`（全局唯一）；`idx_client_tenant(tenant_id)` |
| `api_call_log` | id, tenant_id, client_id NULL, path VARCHAR(200), method VARCHAR(8), status_code INT, result_code VARCHAR(48), duration_ms INT, ip VARCHAR(45), request_id VARCHAR(64), created_at | `idx_acl(tenant_id, client_id, created_at)` |
| `audit_log` | id, tenant_id, user_id NULL, action VARCHAR(48), biz_type VARCHAR(24), biz_id BIGINT NULL, detail_json JSON NULL, ip VARCHAR(45), created_at | `idx_audit_biz(tenant_id, biz_type, biz_id, created_at)`；`idx_audit_user(tenant_id, user_id, created_at)` |
| `notification` | id, tenant_id, user_id, type VARCHAR(32), title VARCHAR(200), content VARCHAR(1000), biz_type, biz_id, read_flag TINYINT 默认 0, created_at | `idx_notify(tenant_id, user_id, read_flag, created_at)` |

### 3.3 索引与 10 万级查询策略（AC-20）

- 需求列表：`type/status/domain/vendor/priority/release/parent/org` 全部有组合索引前缀；排序固定 `updated_at DESC, id DESC`（`idx_req_list` 覆盖前缀，避免 filesort 到磁盘）。`page*size > 5000` 时改为 keyset（`id < lastId`），接口返回 `nextCursor`。
- 关键字：只支持 `code` 精确与 `title` 前缀（`title LIKE 'kw%'` 走 `idx_req_title`）；**不做 `%kw%`**（10 万行全表扫）。中文分词检索留 M6。
- 看板/矩阵：单条聚合 SQL + `EXISTS`，禁止逐行查询；矩阵 1 万需求目标 ≤2s，用 `EXPLAIN` 在 `platform-perf` 里留证据。
- 导出：`StreamingResponseBody` + JDBC `setFetchSize(Integer.MIN_VALUE)` 流式；不把结果集读进内存。
- 计数：列表分页总数走 `COUNT(*)` 同 WHERE；`platform-verify` 用同一筛选比对"页面行数 = CSV 行数 = 明细接口行数"。

### 3.4 迁移与数据生命周期

- `V1__platform_schema.sql` 一次建齐 39 张表 + 预置字典（10 角色在租户开通时按租户插入，不在此文件）。
- 只追加：后续修复用 `V2__...sql`，禁止改 V1（lint 断言 `git diff` 中 `db/migration` 只新增文件）。
- 本地数据不落仓库：`var/`（附件）与 `.run/` 忽略；种子数据由 `SeedService` 生成，不提交导出文件。
- 迁移由 req-service 启动执行；deliver-service 只 `validate`。`platform-up` 若发现 `flyway_schema_history` 不存在而库非空 → 报错并提示"库不是空库，先 make platform-reset 或人工确认"。

### 3.5 存储层边界（谁写哪张表）

| 表域 | 唯一写入方 | 读取方 |
| --- | --- | --- |
| sys_*/biz_domain/code_sequence/requirement/release/wf_*/deploy_order/attachment | req-service | req-service；deliver-service **只读**（`ReqReadModel`） |
| defect/task/sprint/test_*/metric_definition/api_*/api_call_log | deliver-service | deliver-service；req-service 经 `GatePort`/内部接口读，不直连 |
| audit_log/notification | 两个服务经 `AuditService`/`NotificationWriter` 追加 | req-service `/admin/audit` 统一查询 |

> deliver-service 对需求/版本表的只读访问是**契约**：列名变更必须同步 `ReqReadModel` 的 4 条 SQL（TECHNICAL 锁定列表），代码评审检查；lint 断言 deliver 侧不出现 `INSERT/UPDATE/DELETE` 到这些表（`scripts/tests/lint.sh` 第 12 条）。

## 4. API 设计

### 4.1 通用约定

- 前缀：`/api/req/**` → req-service（网关 strip 2 段），`/api/deliver/**` → deliver-service，`/openapi/v1/**` → deliver-service，`/internal/**` 不经过网关。
- 头：`Authorization: Bearer <jwt>`、`X-Tenant-Id: <tenantId>`；内部调用另加 `X-Internal-Token`。开放 API 用 `X-App-Key`/`X-Timestamp`/`X-Signature`，**不需要** JWT 与租户头（租户由 case/plan 反查）。
- 分页：`page`（从 1 起）、`size`（默认 20，上限 200）；响应 `{"items":[],"total":0,"page":1,"size":20,"nextCursor":null}`。
- 错误体（所有非 2xx）：`{"status":409,"code":"ILLEGAL_TRANSITION","message":"…中文…","detail":{...},"traceId":"..."}`。
- 时间：ISO-8601 带偏移（`2026-11-09T10:00:00+08:00`）；日期 `yyyy-MM-dd`。
- 幂等：`POST` 支持 `Idempotency-Key` 头的接口（发布单提交、执行上报）按 key 缓存 24h；其余靠唯一键 + 状态机。
- 面向前端的"可执行动作"由服务端下发：详情响应带 `allowedActions`，前端只渲染；接口仍独立校验（按钮置灰≠防线）。

### 4.2 错误码表（稳定，脚本按 `code` 断言）

| HTTP | code | 触发 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段缺失/格式错，`detail.fields[]` |
| 400 | `STATUS_NOT_WRITABLE` | 请求体出现 `status` 字段（唯一入口是动作/审批回调） |
| 400 | `INVALID_PARENT` | 父子类型非法/环/跨层（含 PDR 挂 PDR） |
| 400 | `PARENT_DAYS_READONLY` | 对 BUS/PDR 手填 `estimateDays/actualDays` |
| 400 | `INVALID_DAY_PRECISION` | 人天不是 0.5 的整数倍 |
| 400 | `MODULE_DEPTH_EXCEEDED` | 用例模块第 4 层 |
| 400 | `REASON_REQUIRED` | 强制放行/减签原因为空 |
| 400 | `APPROVER_UNRESOLVED` | 节点解析不出审批人（发布校验或运行时） |
| 400 | `WORKFLOW_INVALID` | 定义 JSON 结构错误，`detail.nodeKey` |
| 401 | `UNAUTHORIZED` / `SIGNATURE_INVALID` / `TIMESTAMP_EXPIRED` | 缺 token / 签名错 / 时间戳超 ±5min |
| 403 | `TENANT_MISMATCH` | 缺 `X-Tenant-Id` 或与 JWT 不一致（不落库、不写审计） |
| 403 | `OUT_OF_SCOPE` | 行级越权（写审计） |
| 403 | `FORBIDDEN` | 功能权限不允许（写审计） |
| 403 | `CLIENT_FORBIDDEN` | 开放 API：IP 不在白名单 / scope 不含 `test:report` / 客户端停用 |
| 404 | `NOT_FOUND` | 不存在（存在但无权见返回 403，见 AC-4） |
| 409 | `ILLEGAL_TRANSITION` | 状态机不允许，`detail.allowedTargets[]` |
| 409 | `GATE_BLOCKED` | 门禁拒绝，`detail.blockers[]` |
| 409 | `ADMISSION_FAILED` | 准入 FAIL，`detail.items[]` |
| 409 | `TASK_ALREADY_HANDLED` | 审批任务已处理 |
| 409 | `LAST_APPROVER` | 减签后节点无人 |
| 409 | `WORKFLOW_PUBLISHED_IMMUTABLE` | 编辑已发布流程版本 |
| 409 | `RELEASE_LOCKED` | 已发布/锁定版本增删需求 |
| 409 | `CASE_REFERENCED` | 被计划或执行引用的用例物理删除 |
| 409 | `SPRINT_HAS_UNFINISHED_TASKS` | 关闭迭代仍有未迁移任务 |
| 422 | `TEST_CASE_NOT_FOUND` / `CASE_VERSION_NOT_FOUND` | 上报未知用例/版本 |
| 429 | `RATE_LIMITED` / `URGE_TOO_FREQUENT` | 限流 / 催办冷却 |

### 4.3 req-service 端点

**认证与管理员**

| 方法 | 路径 | 入参 | 返回 |
| --- | --- | --- | --- |
| POST | `/auth/login` | `{tenantCode, username, password}` | `{token, expiresIn, user{id,name,roles[],domainIds[],vendorId}, permissions[], defaultRoute}` |
| GET | `/auth/me` | — | 同 `user` + `scope{type,domainIds,vendorId,orgIds}` |
| POST | `/admin/tenants` | `{code,name}` | 201 `{tenantId, provisioned:{roles:10,domains:7,dicts:N,adminUser}}` |
| GET | `/admin/tenants` | `page,size` | 分页 |
| POST/PUT | `/admin/orgs`、`/admin/vendors`、`/admin/domains`、`/admin/users`、`/admin/dicts` | 各实体字段 | 201/200 实体 |
| PATCH | `/admin/users/{id}/status` | `{status}` | 200；停用后其待办审批转交提示（不自动转） |
| POST | `/admin/users/{id}/reset-password` | `{password}` | 204 |
| GET | `/admin/permissions` | — | `{functions:[{code, allow[], restricted[]}], roles[]}`（AC-5 证据接口） |
| GET | `/admin/audit` | `bizType,bizId,userId,from,to,page,size` | 分页 |
| GET/POST/PUT | `/admin/workflows` | 定义 JSON（§5.6） | 定义/版本列表（`PUT` 必须带 id 走 `/admin/workflows/{id}`） |
| GET | `/admin/workflows/{id}` | — | 单个定义（含 `definition` JSON，编辑回填用） |
| POST | `/admin/workflows/{id}/publish` | — | 200 `{version}`；校验审批人可解析 |
| POST | `/admin/workflows/{id}/disable` | — | 200 |
| GET/POST | `/admin/api-clients` | `{name,scopes,ipWhitelist,rateLimitQps}` | 201 `{appKey, secret}`（**仅此一次返回明文**） |
| POST | `/admin/api-clients/{id}/rotate` | — | 200 `{appKey, secret}` |
| PATCH | `/admin/api-clients/{id}/status` | `{status}` | 200 |

**需求**

| 方法 | 路径 | 入参 | 返回 |
| --- | --- | --- | --- |
| GET | `/requirements` | `type,keyword,parentId,releaseId,ownerId,priority,status,domainId,vendorId,orgId,dateFrom,dateTo,view=agg\|flat,page,size` | `PageResponse<RequirementRow>`；`view=agg` 时行内含 `childCount,doneCount,estimateDays,actualDays,risk` |
| GET | `/requirements/{id}` | — | `{view:{id,code,type,status,priority,title,description,domain,parent,rollup{childCount,doneCount,estimateDays,actualDays},delivery{mrUrl,estimateDays,actualDays,owner},release,allowedActions[],permissions{fields:[...]}}, children[], allowedActions[], permissions{fields:[...]}}`（`RequirementViews.Detail` 包装层；页面用 `normalizeRequirementDetail` 摊平） |
| POST | `/requirements` | `{type,title,description,domainIds[],priority,expectedDeliveryDate,parentId?,attachments[]}` | 201 `{id,code,status,allowedActions}`；`status` 字段→400 |
| PUT | `/requirements/{id}` | `{title,description,priority,expectedDeliveryDate}` | 200（父不可改；BUS/PDR 人天字段出现→400） |
| POST | `/requirements/{id}/actions` | `{action,comment?}` | 200 `{status,allowedActions}`；非法→409 |
| POST | `/requirements/{id}/decompose` | 同创建（type 由服务端推导） | 201 子需求 |
| POST | `/requirements/{id}/assign-vendor` | `{vendorId,ownerUserId?,reason}` | 200；级联更新 task/defect 的 vendor |
| POST | `/requirements/{id}/deliver` | `{estimateDays,actualDays,mrUrl,ownerUserId}` | 200（仅 DEV，且仅 RD） |
| POST | `/requirements/{id}/quick-test` | `{result:PASS\|FAIL,note}` | 201 `{executionId}`（写 `test_execution` source=MANUAL，plan/case 空） |
| GET | `/requirements/search` | `q` | 跨域搜索（同一 scope，业务方仅摘要字段） |
| GET | `/requirements/export.csv` | 与列表同参 | `text/csv`，首行水印，行数=列表 total |
| GET | `/attachments/{id}/download` | — | 文件流（先 `ScopeGuard`） |
| POST | `/attachments` | multipart（bizType,bizId） | 201 `{id}`；扩展名/大小白名单 |

**审批**

| 方法 | 路径 | 入参 | 返回 |
| --- | --- | --- | --- |
| GET | `/approvals/todo` / `done` / `mine` | `page,size` | 分页（含单据摘要与节点名） |
| GET | `/approvals/instances/{id}` | — | `{instance{definitionVersion,status,currentNode}, document{summary}, nodes[], tasks[], timeline[]}` |
| POST | `/approvals/tasks/{taskId}/actions` | `{action:APPROVE\|REJECT,comment}` | 200 `{instanceStatus,nodeStatus}`；重复→409 |
| POST | `/approvals/tasks/{taskId}/add-sign` | `{type:BEFORE\|AFTER,userIds[],comment}` | 200 |
| POST | `/approvals/tasks/{taskId}/remove-sign` | `{targetUserId,reason}` | 200；空原因→400；末位→409 |
| POST | `/approvals/tasks/{taskId}/transfer` | `{toUserId,comment}` | 200 |
| POST | `/approvals/instances/{id}/withdraw` | `{comment?}` | 200；已处理→409 |
| POST | `/approvals/tasks/{taskId}/urge` | — | 204；60s 内重复→429 |
| GET | `/approvals/instances/{id}/history` | — | 按版本分组的流转记录 |

**版本与发布单**

| 方法 | 路径 | 入参 | 返回 |
| --- | --- | --- | --- |
| GET | `/releases` | `domainId,status,page,size` | 分页（含需求数、完成率、风险数） |
| POST | `/releases` | `{name,domainId,startDate,endDate,plannedReleaseDate,gateMode}` | 201（code 自动 `REL-xxxx`） |
| GET | `/releases/{id}` | — | `{release, requirements[], stats{total,done,estimateDays,actualDays}, riskItems[]}` |
| POST | `/releases/{id}/requirements` | `{requirementIds[]}` | 200；锁定→409 |
| DELETE | `/releases/{id}/requirements/{reqId}` | — | 204；锁定→409 |
| POST | `/releases/{id}/approval` | — | 201 `{instanceId}`（走 `RELEASE` 流程） |
| POST | `/releases/{id}/lock` | — | 200 `ReleaseView`；直接锁定（PLAN→LOCKED），非 PLAN→409 |
| POST | `/releases/{id}/milestones` | `{name,plannedDate}` | 201 |
| GET | `/deploy-orders` | `releaseId,env,status,page,size` | 分页 |
| POST | `/deploy-orders` | `{releaseId,env,windowStart,windowEnd,artifactVersion,steps,rollbackPlan,impact,ownerId}` | 201 自动带出需求清单 |
| GET | `/deploy-orders/{id}` | — | `{…, admission{items[],checkedAt,stale}, forcedRelease{reason,by,at}, execution}` |
| POST | `/deploy-orders/{id}/evaluate` | — | 200 `{items[5],checkedAt}`（快照落库） |
| POST | `/deploy-orders/{id}/submit` | — | 200；任一 FAIL→409 `ADMISSION_FAILED` |
| POST | `/deploy-orders/{id}/force-release` | `{reason}` | 200；空→400；非 PMO/管理员→403 |
| POST | `/deploy-orders/{id}/execution` | `{result:SUCCESS\|FAILED\|ROLLED_BACK,startedAt,endedAt,note,defectId?}` | 200（审批通过后） |
| GET | `/deploy-orders/stats` | `releaseId,env,from,to` | `{successRate,avgDurationMinutes,failReasons[]}` |
| GET | `/notifications` | `unreadOnly,page,size` | 分页 |
| POST | `/notifications/{id}/read` / `/notifications/read-all` | — | 204 |

### 4.4 deliver-service 端点

| 方法 | 路径 | 入参 | 返回 |
| --- | --- | --- | --- |
| GET | `/defects` | `domainId,vendorId,releaseId,severity,status,handlerId,requirementId,dateFrom,dateTo,page,size` | 分页（业务方=摘要投影） |
| POST | `/defects` | `{title,description,reproSteps,severity,priority,requirementId?,releaseId?,testCaseId?,testCaseVersion?,testExecutionId?,handlerId?,expectedFixDate,attachments[]}` | 201；有需求时 `domain/vendor` 派生并忽略请求值 |
| GET | `/defects/{id}` | — | 详情（字段级脱敏） |
| POST | `/defects/{id}/actions` | `{action:START\|SUBMIT_VERIFY\|VERIFY_PASS\|VERIFY_REJECT\|REOPEN\|ASSIGN,comment?,assigneeId?}` | 200；非法→409 |
| POST | `/defects/from-execution/{executionId}` | — | 201（带用例/版本/需求/执行 id） |
| GET | `/defects/board` | 同列表筛选 | `{metrics{openHigh,density,reopenRate,mttrMinutes}, definitions[], drill{path,params}}` |
| GET | `/defects/board/items` | 同筛选 + `metric` | 明细行（同一 SQL 片段，保证复算一致） |
| GET | `/defects/export.csv` | 同列表 | CSV + 水印 |
| GET | `/requirements/{id}/tasks` | — | 任务列表（含完成率） |
| POST | `/tasks` | `{requirementId,title,assigneeId,estimateHours,startDate,dueDate}` | 201 |
| PATCH | `/tasks/{id}` | 可编辑字段 | 200 |
| POST | `/tasks/{id}/move` | `{status,sortNo}` | 200（看板拖拽，写审计） |
| GET/POST | `/sprints` | `releaseId` / `{releaseId,name,goal,startDate,endDate,capacityDays}` | 列表 / 201 |
| GET | `/sprints/{id}/board` | — | `{columns[],lanes[],tasks[]}` |
| POST | `/sprints/{id}/close` | `{migrateToSprintId?}` | 200；有未完成任务且未迁移→409 |
| GET | `/test-modules` / `POST /test-modules` / `PUT /test-modules/{id}` / `DELETE /test-modules/{id}` | 树/节点 | 第 4 层→400 |
| GET | `/testcases` | `moduleId,keyword,priority,type,page,size` | 分页 |
| POST | `/testcases` | `{moduleId,title,requirementId?,type,priority,precondition,steps[],tags,automated,scriptRef}` | 201（v1） |
| PUT | `/testcases/{id}` | 同创建 | 200（生成 v+1） |
| GET | `/testcases/{id}` | `version?` | 指定版本快照（缺省 current） |
| GET | `/testcases/{id}/versions` | — | 版本列表 |
| DELETE | `/testcases/{id}` | — | 204 软删；被引用→409 |
| POST | `/testcases/{id}/copy` | `{title}` | 201 |
| POST | `/testcases/import` | multipart CSV | 200 `{ok,failed[],errors[{row,message}]}`；1000 行 ≤10s |
| GET | `/testcases/export.csv` | 同列表 | CSV + 水印 |
| GET/POST | `/test-plans` | `releaseId` / `{releaseId,name,ownerId,windowStart,windowEnd,caseIds[]}` | 列表 / 201 |
| GET | `/test-plans/{id}` | — | `{plan,cases[{caseCode,caseVersion,result,executedAt}],summary}` |
| POST | `/test-plans/{id}/cases` | `{caseIds[]}` | 200（钉住当前版本） |
| DELETE | `/test-plans/{id}/cases/{caseId}` | — | 204 |
| POST | `/test-plans/{id}/execute` | `{caseId,result,note,evidence?}` | 201 `{executionId}` |
| GET | `/test-plans/{id}/summary` | — | `{planned,executed,passed,failed,blocked,skip,notRun,passRate,executedPassRate,coverage,complete}` |
| GET | `/test-gate` | `requirementId` | `GateDecision{passed,mode,blockers[],reason}` |
| GET | `/traceability` | `type,domainId,vendorId,gap,page,size` | `PageResponse<TraceRow>`（6 列状态 + `gaps[]` + `complete`） |
| GET | `/traceability/{requirementId}` | — | 链路明细（需求/任务/MR/执行/缺陷/发布单/版本） |
| GET | `/metrics/defects` | 同缺陷筛选 | `{metrics, definitions[]}` |
| GET | `/metrics/test-pass` | `releaseId,planId` | `{passRate,executedPassRate,coverage,definition}` |
| GET | `/metrics/definitions` | — | 4 条指标定义 |
| POST | `/admin/metric-definitions` | `{code,name,formula,caliberNote,ownerId}` | 201 新版本（旧版可查） |

### 4.5 内部接口（不经网关）

| 服务 | 方法/路径 | 返回 |
| --- | --- | --- |
| req | `GET /internal/health` | `{status,db,flyway}` |
| req | `POST /internal/seed` | `{seeded,skipped,counts{}}`；`PLATFORM_SEED_ENABLED=false` 时 404 |
| deliver | `GET /internal/health` | `{status,db}` |
| deliver | `GET /internal/gates/{requirementId}` | `GateDecision` |
| deliver | `POST /internal/gates/batch` | `{requirementId: GateDecision}`（准入一次批量，≤300ms 预算） |
| deliver | `GET /internal/defects/open-high?requirementIds=1,2` | `{requirementId: count}` |
| deliver | `POST /internal/attachments` | 预留（附件落 req，不走此口） |

内部接口统一校验 `X-Internal-Token`，缺失/不符→403 `FORBIDDEN`。

### 4.6 开放 API

`POST /openapi/v1/test-executions`

```
X-App-Key: ak_xxx
X-Timestamp: 1762650000            # 秒；±300s
X-Signature: hex(HMAC-SHA256(secret, canonical))
canonical = method + "\n" + path + "\n" + appKey + "\n" + timestamp + "\n" + hex(sha256(rawBody))
body = {run_id, case_id, case_version?, result, duration_ms?, log_url?, executed_at, plan_id?}
```

成功：`200 {"id":123,"idempotent":false}`；重复：`200 {"id":123,"idempotent":true}`。校验顺序固定：签名 → 时间戳 → 客户端状态 → IP → scope → 载荷枚举 → 用例/版本存在性 → 计划归属 → 幂等插入。**任一失败不落 `test_execution`**（只落 `api_call_log`，`REQUIRES_NEW`）。`result=NOT_RUN` 或非法枚举→400；未知 case→422；显式未知 version→422。归属：`plan_id` 给出时按计划推导租户/域/厂商，否则按 case 推导；忽略请求体里的任何归属字段。

### 4.7 关键响应示例

```json
// POST /api/req/requirements/12/actions  {"action":"TEST_PASS"}
// 409
{"status":409,"code":"GATE_BLOCKED","message":"测试门禁未通过：3 条用例未通过",
 "detail":{"mode":"STRICT","blockers":[
   {"caseId":91,"caseCode":"TC-0007","caseVersion":2,"result":"FAIL","note":null},
   {"caseId":92,"caseCode":"TC-0008","caseVersion":1,"result":"NOT_RUN","note":null},
   {"caseId":93,"caseCode":"TC-0009","caseVersion":1,"result":"BLOCKED","note":"环境不可用"}]}}

// GET /api/deliver/test-gate?requirementId=12 → 200
{"passed":false,"mode":"STRICT","reason":"3 条用例未通过","blockers":[...]}
```

## 5. 核心实现

### 5.1 鉴权与租户上下文

1. 网关 `JwtGatewayFilter`：解 `JwtCodec.parse` → 校验 `exp`、`X-Tenant-Id == payload.tenantId`；失败 401/403，不转发。
2. 服务 `AuthFilter`（`OncePerRequestFilter`，order=HIGHEST）：再次 `parse`，从 DB 读**当前**角色与授权域（`UserScopeResolver`，两条索引查询，不缓存 → AC-4「撤销授权后下一次请求 ≤1s 生效」），构造 `DataScope` 放入 `TenantContext`，请求结束 `clear()`。
3. 租户头缺失/不一致：`403 TENANT_MISMATCH`，**不写任何业务行、也不写 audit**（AC-1 断言"数据库无新增行"）。越权访问（租户合法、资源不在范围）才写审计（`REQUIRES_NEW`，保证 403 不回滚审计）。
4. 服务内部调用：显式传 `DataScope`/租户 id，禁止依赖 ThreadLocal 跨线程；`/internal/**` 不走 `AuthFilter`，由 `InternalTokenFilter` 校验共享令牌。
5. 登录：`AuthService.login(tenantCode, username, password)`，PBKDF2 校验，签发 8h JWT，`permissions[]` 由 `PermissionMatrix` 生成。

### 5.2 数据权限引擎（单一实现）

```java
record DataScope(long tenantId, ScopeTier tier, Set<Long> domainIds, Long vendorId,
                 Long userId, Set<Long> orgSubtreeIds) {}
```

| tier | 判定 |
| --- | --- |
| `SYSTEM` | 管理层/PMO/平台管理员：全量（管理层默认只读，由权限矩阵限制写接口） |
| `DOMAIN` | 产品/域负责人：`domain_id IN (:domainIds)` |
| `VENDOR` | 研发/测试/厂商负责人：`domain_id IN (:domainIds) AND (vendor 列命中)` |
| `BIZ` | 业务部门/二级企业：`origin_org_id IN (:orgSubtreeIds) OR reporter_id = :userId`，且字段投影为 `SUMMARY` |
| `SELF` | 仅本人提报（二级企业业务人员的默认档） |

**ScopeSql 按表生成谓词**（`ScopeSql.forTable(table, alias, scope)`），三类模板：

```
VENDOR_TWO_COL  : (r.owner_vendor_id = :vendorId OR r.assignee_vendor_id = :vendorId)   -- requirement
VENDOR_ONE_COL  : t.vendor_id = :vendorId                                              -- defect/task/test_*/attachment
DOMAIN_ONLY     : 1=1  （仅 domain_id 过滤）                                            -- release/test_plan(无 vendor)
```

**ScopedJdbc 契约**：SQL 必须包含 `/*scope:a*/`（a 为表别名），内部替换成上述谓词并注入参数；缺失标记抛 `IllegalStateException("scope marker missing in SQL")`（fail-closed）。示例：

```java
String sql = """
    SELECT r.id, r.code, r.title, r.status, d.name AS domain_name
      FROM requirement r LEFT JOIN biz_domain d ON d.id = r.domain_id
     WHERE /*scope:r*/ AND r.type = :type AND r.status = :status
     ORDER BY r.updated_at DESC, r.id DESC
    """;
```

- 8 个入口（列表/详情/搜索/导出/附件/缺陷看板/用例/矩阵）全部复用同一 `ScopeSql`；附件下载与按 ID 详情在实体加载后 `ScopeGuard.requireVisible`（不可见→403+审计，不是 404）。
- 字段级脱敏：`SummaryProjector.forViewer(scope)` 决定列集合；列表、详情、导出、看板四个出口共用，禁止各写一套 if。
- 转派级联：更新 `requirement.assignee_vendor_id` 后，同一事务 `UPDATE task/defect SET vendor_id=? WHERE requirement_id=?`，写 `audit_log`（before/after 厂商），通知新厂商负责人。
- 审批中的单据摘要：`DocumentSummaryProvider` 按 `scope` 返回完整或摘要（摘要不含附件、成本人天、内部备注）。

### 5.3 权限矩阵（AC-5 逐格断言）

矩阵在 `PermissionMatrix` 中以 `Map<String, Map<Role, Access>>` 声明；`Access = ALLOW | RESTRICTED | DENY`。`@RequiresPermission("requirement:review")` 由 `PermissionInterceptor` 在控制器方法执行前判定：`DENY → 403 FORBIDDEN`（写审计），`RESTRICTED` 交给服务层按范围裁剪。一人多角色取并集，取最高档（ALLOW > RESTRICTED > DENY）。

| 函数码 | 接口（前缀省略） | ALLOW | RESTRICTED |
| --- | --- | --- | --- |
| `tenant:manage` | POST `/admin/tenants` | 平台管理员 | — |
| `org:manage` | `/admin/{orgs,vendors,domains,users,dicts}` | 平台管理员 | — |
| `req:report:bus` | POST `/requirements`(BUS) | 业务部门、二级企业业务、二级企业负责人、产品 | — |
| `req:report:dev` | POST `/requirements`(PDR/RD) | 产品、研发 | 项目（本域） |
| `req:review` | `/requirements/{id}/actions`(review_*) | 产品 | 项目（本域复核） |
| `req:split` | `/requirements/{id}/decompose` | 产品 | 研发 |
| `release:plan` | `/releases`、`/releases/{id}/requirements` | 产品、项目 | 管理层（只读） |
| `req:assign-vendor` | `/requirements/{id}/assign-vendor` | 项目 | 产品 |
| `req:deliver` | `/requirements/{id}/deliver` | 研发 | — |
| `test:quick` | `/requirements/{id}/quick-test` | 测试 | — |
| `deploy:create` | POST `/deploy-orders` | 运维 | 项目（本域） |
| `deploy:execute` | `/deploy-orders/{id}/execution` | 运维 | — |
| `deploy:force` | `/deploy-orders/{id}/force-release` | 管理层(PMO)、平台管理员 | — |
| `defect:report` | POST `/defects` | 研发、测试、运维 | — |
| `defect:handle` | `/defects/{id}/actions` | 研发、测试 | 项目（本域）、管理层（只读例外：可重开） |
| `defect:board` | `/defects/board`、`/board/items` | 产品、项目、测试、管理层、平台管理员 | 业务部门/二级企业/研发/运维（本厂商或摘要） |
| `task:manage` | `/tasks`、`/sprints` | 项目 | 研发（本厂商） |
| `case:manage` | `/testcases`、`/test-modules` | 测试 | 产品、项目、研发 |
| `plan:manage` | `/test-plans`、`/test-plans/{id}/execute` | 测试 | 项目（本域） |
| `trace:view` | `/traceability` | 产品、项目、管理层 | 研发、测试、运维（本厂商/本域）、业务方（摘要） |
| `metric:manage` | POST `/admin/metric-definitions` | 管理层、平台管理员 | — |
| `workflow:manage` | `/admin/workflows*` | 管理层、平台管理员 | 产品、项目（只读） |
| `apiclient:manage` | `/admin/api-clients*` | 平台管理员 | — |
| `export:detail` | `*/export.csv` | 按对应视图角色 | 按对应视图范围 |
| `audit:view` | `/admin/audit` | 平台管理员、管理层 | 项目（本域） |

`GET /admin/permissions` 返回同一份矩阵（含 `Access`），`platform-verify` 的 AC-5 组读取它生成"角色 × 函数"探测矩阵，逐个调用对应接口断言 2xx/403，避免脚本再抄一份矩阵。

### 5.4 状态机（唯一入口 + 审计）

`StateTables`（contract）声明三张表 + 缺陷表；`RequirementStateMachine`/`DefectStateMachine` 只做 `assertAllowed` + `target`，**不写库**：

```
BUS: SUBMITTED --submit--> APPROVING --approve--> PENDING_REVIEW --review_pass--> POOLED --close--> CLOSED
     APPROVING --reject|withdraw--> SUBMITTED    PENDING_REVIEW --review_reject--> PENDING_REVIEW
     PENDING_REVIEW --close--> CLOSED
PDR: PENDING_ANALYSIS --schedule--> SCHEDULED --start_dev--> IN_DEV --deliver--> DELIVERED --accept--> ACCEPTED_CLOSED
RD : TODO --start_dev--> IN_DEV --submit_test--> PENDING_TEST --test_pass--> TEST_PASSED --merge--> MERGED --finish--> DONE
DEF: NEW --start--> IN_PROGRESS --submit_verify--> PENDING_VERIFY --verify_pass--> CLOSED
     PENDING_VERIFY --verify_reject--> IN_PROGRESS (reopen+1)
     CLOSED --reopen--> IN_PROGRESS (reopen+1)     NEW --close_duplicate--> CLOSED
```

- 非法流转：`409 ILLEGAL_TRANSITION` + `detail.allowedTargets`（例：RD 从 `PENDING_TEST` 到 `DONE` 只允许 `TEST_PASSED`）。
- 直接写 `status`：所有创建/更新 DTO 继承 `StatusForbiddenDto`（`@JsonProperty("status")` 的 setter 抛 `400 STATUS_NOT_WRITABLE`），并有单测。
- 每次合法流转同一事务写 `audit_log(action=STATE_CHANGE, detail={from,to,action,source,comment})`；详情页时间线直接读 `audit_log`，保证"页面与审计一致"。
- RD→`TEST_PASSED` 前调用 `GatePort.evaluate(id)`；`passed=false` → `409 GATE_BLOCKED`（含 blockers），无论调用方是界面还是 curl。
- 父需求人天：创建/更新/删除子需求后重算 `parent.estimate_days/actual_days`（0.5 舍入）；BUS 取 PDR 之和，PDR 取 RD 之和；手填字段出现在 BUS/PDR 请求体 → `400 PARENT_DAYS_READONLY`。

**自校验证据（logicprobe_verify）**：RD 6 态 6 迁移、BUS 5 态 8 迁移，S1–S8 无 error（仅 S5/A1 提示未定义事件对，属预期：非法事件由 409 处理）。缺陷机 4 态 6 迁移，S3 报「CLOSED→IN_PROGRESS→PENDING_VERIFY→CLOSED 闭环无终态」——这是业务允许无限重开的必然结果，**显式接受**：不以"存在终态"作断言，改为「CLOSED 只能经 `reopen` 离开」，并由 `reopen_count` 每次 +1 承担可追溯性。

### 5.5 编号与汇总

`CodeGenerator.next("RD", tenantId)`：`SELECT next_val FROM code_sequence WHERE tenant_id=? AND code_type=? FOR UPDATE` → `UPDATE ... SET next_val=next_val+1` → 格式化 `RD-%04d`。前缀：`BUS/PDR/RD/BUG/TC/REL/DEP`。事务内调用，与业务写入同提交/回滚（不留空号可接受，不允许重号）。

### 5.6 审批引擎

**定义 JSON（schemaVersion=1）**

```json
{"schemaVersion":1,"start":"n1",
 "nodes":[
  {"key":"n1","type":"APPROVAL","name":"企业负责人","mode":"ANY",
   "approvers":[{"type":"SUPERVISOR","scope":"INITIATOR_ORG"},{"type":"ROLE","value":"SUB_ENTERPRISE_OWNER"}],
   "next":"n2"},
  {"key":"n2","type":"CONDITION",
   "branches":[{"when":{"reqType":["RD"],"priority":["P0"],"estimateDays":{"gte":5}},"next":"n3"}],
   "defaultNext":"n4"},
  {"key":"n3","type":"APPROVAL","name":"PMO 会签","mode":"ALL",
   "approvers":[{"type":"ROLE","value":"PROJECT"}],"next":"n4"},
  {"key":"n4","type":"CC","receivers":[{"type":"ROLE","value":"DOMAIN_OWNER"}],"next":"end"}
 ]}
```

- 解析顺序：`biz_type + (req_type 精确 > ANY) + (domain_id 精确 > 0) + status=PUBLISHED`，取最高版本；无匹配取 `is_default=1`；仍无 → `400 NO_WORKFLOW`。实例写 `definition_id + definition_version`，**读取该版本行**（已发布版本不可编辑，因此无需快照拷贝）。
- 发布校验 `WorkflowDefinitionValidator`：节点 key 唯一、`next` 可达、条件分支必须有 `defaultNext`、每个 APPROVAL 节点用 `ApproverResolver.validate` 至少解析出 1 个启用用户，否则 `400 APPROVER_UNRESOLVED` + `detail.nodeKey`。已发布版本 `PUT` → `409 WORKFLOW_PUBLISHED_IMMUTABLE`；修改 = 新建行、version+1。
- 节点推进算法（伪代码）：

```
onApprove(task):
  lock instance; assert task.PENDING else 409
  task.status=APPROVED; write history
  if node.mode==ALL:  nodeRemaining = count(PENDING in node)
  if node.mode==ANY:  if exists PENDING ADD_SIGN task with sort_no > task.sort_no -> nodeRemaining=1
                      else nodeRemaining=0   # 任一通过即过，其余 PENDING 置 CANCELLED
  if nodeRemaining>0: return
  next = advance(node)   # CONDITION 命中第一个 when，否则 defaultNext；CC 发通知不阻塞
  if next==END: instance.APPROVED; callback.onApproved(bizId)
  else: create tasks for next node (approver 解析为空 -> 409 APPROVER_UNRESOLVED + 通知 PMO，实例保持 PENDING)
```

- 加签：`BEFORE` 新任务 `sort_no = min-1`、先于本人处理；`AFTER` 新任务 `sort_no = max+1`，本人处理后实例停在节点等新任务（会签自然满足"新人全过"）。已处理任务加签 → `409 TASK_ALREADY_HANDLED`。`sign_no` 每次加签自增，配合唯一键防重复任务。
- 减签：仅节点负责人/管理员；必填原因；对最后一名的减签 → `409 LAST_APPROVER`（防卡死）；被减签人收通知。
- 转办：改 `approver_id` + 历史 + 通知；撤回：仅当实例内所有任务 PENDING 且发起人本人；催办：只发通知，60s 冷却。
- 并发：`@Lock(PESSIMISTIC_WRITE)` 锁 `wf_instance`；同一任务重复同意第二次 `409`；`wf_instance.active_flag` 唯一键保证同一单据不会有两个在途实例（驳回重提、撤回重提都先置 NULL 再建新实例）。
- 回调 `ApprovalCallback`（contract）：`onApproved/onRejected/onWithdrawn` 由单据方实现（需求/版本/发布单），在**同一事务**内改单据状态并写审计；实现方必须幂等（若单据已是目标状态则 no-op）。回调失败 → 整个审批动作回滚（不允许"审批过了单据没动"）。
- 抄送：写 `wf_cc` + 通知，不产生任务、不阻塞。
- 驳回：实例 `REJECTED`、其余任务 `CANCELLED`，回调按 D3/§5.6 映射回退；历史保留，可重新发起新实例。

### 5.7 发布单与 5 项准入

`AdmissionEvaluator.evaluate(orderId)` 按固定顺序返回 5 项（每项 `{code,name,passed,detail,fixHint}`）：

| code | 判定 | 数据来源 |
| --- | --- | --- |
| `REQ_TEST_PASSED` | 关联 RD 状态 ∈ {TEST_PASSED, MERGED, DONE}；且 `GatePort.evaluateAll(ids)` 全 passed（无计划时按 D2 放行） | req + deliver 批量门禁 |
| `NO_OPEN_HIGH_DEFECT` | `severity∈{P0,P1} AND status<>CLOSED` 计数 = 0 | deliver `/internal/defects/open-high` |
| `MR_FILLED` | 每条 RD `mr_url` 非空 | req |
| `MAN_DAYS_FILLED` | 每条 RD `estimate_days>0 AND actual_days>0` | req |
| `REQ_APPROVALS_DONE` | 每条需求的评审入池实例 APPROVED，且版本审批实例 APPROVED | wf_instance |

- 提交：先 `evaluate` 落 `admission_result_json` 快照，再判 `allPassed || forced_release=1`；否则 `409 ADMISSION_FAILED`（`detail.items` 原样给前端，禁止只返一句话）。
- **不可绕过**：`submit` 内部重新计算，不接受客户端传 PASS/FAIL；直接 POST 同样 409。
- 强制放行：独立接口，PMO/管理员 + 原因非空；写 `forced_release/force_reason/force_by/force_at` + 审计 + 通知域负责人；单据详情常驻展示"已强制放行（原因）"，且快照里保留 FAIL 项。
- 执行记录：仅 `APPROVED` 后可登记；结果 SUCCESS/FAILED/ROLLED_BACK，`duration_minutes` 由开始/结束计算（服务端算，忽略入参）；失败可关联缺陷。
- 超时预算：准入 ≤500ms。批量门禁一次 HTTP（300ms 超时）+ 两条计数 SQL；超时按 FAIL 处理并给 `fixHint="缺陷/门禁服务不可用，请稍后重试"`（fail-closed，不静默放行）。

### 5.8 测试门禁（唯一实现）

`TestGateService.evaluate(requirementId)`（deliver）逻辑：

```
release = requirement.release_id;  gateMode = release.gate_mode (缺省 STRICT)
plans = test_plan where release_id = release.id and exists plan_case.requirement_id = requirementId
if plans.isEmpty(): return passed=true, reason="无关联测试计划，按快速测试记录放行"   // D2
cases = plan_case rows of plans
if cases.isEmpty(): return passed=false, reason="计划未纳入任何用例，不满足门禁"       // AC-16
if STRICT: blockers = cases where result != PASS
else(LOOSE): blockers = cases where result == FAIL  or (result in (BLOCKED,SKIP) and note blank)
return blockers.isEmpty()
```

- 结果取值：`test_plan_case.result` 由最新一条 `test_execution`（按 `executed_at`，同刻按 id）回写；手工与自动并存，来源只作展示。
- 同一 `GateDecision` 供三处使用：`GET /test-gate`（前端置灰依据）、RD 流转接口（409）、发布准入（批量）。**禁止**在 req-service 重写一套判定。
- 宽松模式需 Release 显式设置 `gate_mode=LOOSE`；阻塞/跳过必须填写 `note`，否则同样阻断（PRD §4.14）。

### 5.9 用例版本与执行

- 保存：`test_case_version` 追加 `snapshot_json`（含 title/steps/precondition/type/priority），`current_version+1`；`test_case_step` 按 `(case_id, version_no)` 复制新版本行，历史只读。
- 计划纳入用例时写 `test_plan_case.case_version = test_case.current_version`（钉住）；重新纳入用 `POST /test-plans/{id}/cases` 覆盖并留痕。
- 执行详情读 `snapshot_json`（不是主表），正面回应反面目标 1；UI 徽标 `TC-0007 · v2`。
- 汇总：`planned=count(*)`，`executed=count(result<>'NOT_RUN')`，`passed=count(result='PASS')`，`passRate=passed/planned`，`executedPassRate=passed/executed`（executed=0 时为 null），`coverage=executed/planned`，`complete = notRun=0`。
- 失败转缺陷：`POST /defects/from-execution/{executionId}`，事务内建缺陷 + 回填 `defect.test_execution_id`，带 `case_id/case_version/requirement_id/release_id`。

### 5.10 开放 API 实现

- 签名：`HmacSigner.canonical(method, pathWithoutQuery, appKey, timestamp, rawBody)`；时间戳 `|now - ts| <= 300`，否则 401；`secret` 解密自 `secret_enc`（AES-GCM，nonce 存列前缀）。
- 限流：每客户端令牌桶（内存 `ConcurrentHashMap<Long, Bucket>`，容量=qps，按秒补充）；单实例部署，无需 Redis；超限 429 + `Retry-After`。
- 幂等：`INSERT` 捕获唯一键冲突 → 查回原行 → 200 `idempotent=true`；**不更新**计划结果。
- 合并：插入成功后，若 `plan_id` 非空且 `executed_at >= test_plan_case.last_executed_at`，回写 `result/execution_id/last_executed_at`；否则只存历史。
- 隔离：租户/域/厂商由 `case_id`（或 `plan_id`）反查，忽略请求体归属；跨租户 case 视为未知 → 422。
- 调用日志：`api_call_log` 在 filter 的 `finally` 用 `REQUIRES_NEW` 写，记录 `status_code/result_code/duration_ms/ip/request_id`；日志失败不影响响应。
- 失败隔离：单次上报的异常只回滚本次事务；不共享连接池事务、不阻塞手工执行。

### 5.11 指标与追溯

- 公式只在 `MetricFormulas`（contract），SQL 只取原始计数（`MetricQuery`）：`DEFECT_DENSITY=defects/requirements`、`REOPEN_RATE=reopened/total`、`TEST_PASS_RATE=passed/planned`（另给已执行通过率）、`MTTR=avg(closed_at-found_at)`（仅 CLOSED）。
- 同一筛选对象 `DefectFilter` 同时用于看板卡、明细、导出、矩阵列；`platform-verify` 用同一筛选比对三处数字（容差 0）。
- 指标定义 4 条种子；修改 = 新增版本行（`version+1`），`GET /metrics/definitions` 默认返回最新版，`?all=true` 返回全部版本。
- 追溯：单条 SQL，6 个 `EXISTS`（任务/MR/测试/缺陷/发布/版本）+ 缺口布尔；`complete = gaps.length == 0`，`gaps` 由同一行计算，**不可能出现"complete=true 且 gaps 非空"**（单测 + 脚本双重断言）。BUS/PDR 用后代 RD 汇总（递归深度 3 固定：BUS→PDR→RD）。
- 矩阵缓存：本轮不做缓存（10k 需求目标 2s，索引可满足）；若 `platform-perf` 超标，再加 30s 缓存并以 `requirement.updated_at` 最大值作版本键（已写入风险表）。

### 5.12 审计与通知

- `AuditService.record(action,bizType,bizId,detail)`：同事务写；403/限流等"无业务事务"场景用 `REQUIRES_NEW`。动作字典：`STATE_CHANGE, APPROVAL_ACTION, APPROVAL_ADD_SIGN, APPROVAL_REMOVE_SIGN, APPROVAL_TRANSFER, APPROVAL_WITHDRAW, APPROVAL_URGE, ADMISSION_FORCE, DEFECT_REOPEN, CASE_VERSION, EXECUTION_REPORT, API_CLIENT_CHANGE, EXPORT, PERMISSION_DENY, VENDOR_TRANSFER`。
- `NotificationWriter.send(...)`（common，JDBC 直写 `notification`）：审批待办/结果、被减签、强制放行、缺陷指派/重开、门禁拒绝；同一事务写，通知失败只记日志不阻断业务（`REQUIRES_NEW` + try/catch）。
- 通知中心 `GET /notifications` 在本轮只读 + 标记已读，不做 WebSocket 推送。

### 5.13 导出与导入

- 导出首行：`# 导出人=<name> 时间=<ISO> 筛选=<k=v&...> 行数=<n>`；第二行表头。用 `ScopeSql` + `SummaryProjector` 生成同一条 SQL，**行数 = 列表 total**（同一 WHERE，禁用 limit）。
- 导入 CSV：表头模板校验（module,title,type,priority,precondition,step_action,step_expected），按 200 行一批事务，失败行返回 `{row,message}`；最多 5000 行；1000 行 ≤10s。

## 6. 前端

### 6.1 技术栈与目录

Vue 3.5 + Vite 7 + TypeScript 5.9 + Element Plus 2.9 + Pinia 3 + vue-router 4.5 + ECharts 6（按需）+ dayjs。Node 22.22 / pnpm 10.30（实测）。`web/vite.config.ts`：`base:'/'`，`build.outDir:'dist'`，`preview.port=5173`，`server.proxy` 与 `preview.proxy` 均把 `/api`、`/openapi` 代理到 `http://127.0.0.1:8080`。

```
web/src/
  api/{client.ts,types.ts,req.ts,deliver.ts,admin.ts}
  stores/{auth.ts,dict.ts}
  router/{index.ts,types.ts,routes/{base,req,flow,deliver}.routes.ts}
  layouts/{AppShell.vue,SideNav.vue,TopNav.vue}
  components/{StatusPill,PriorityDot,ScopeChip,GateChecklist,ApprovalTimeline,MetricCard,
              TraceMatrix,PassRateRing,EmptyState,ErrorState,ConfirmDialog,ImportWizard,CaseVersionSwitcher}.vue
  composables/{useListQuery.ts,usePermission.ts}
  views/{base,req,release,approval,deploy,deliver,admin}/*.vue
  styles/{tokens.css,theme.css,base.css}
```

### 6.2 路由（14 页）与模块化注册

`router/index.ts` 用 `import.meta.glob('./routes/*.ts', { eager: true })` 汇总各域路由数组，因此**前端任务的文件边界天然不重叠**：基座建 `base.routes.ts`，需求域建 `req.routes.ts`，审批/发布建 `flow.routes.ts`，交付/管理建 `deliver.routes.ts`。

| 路由 | 页面 | 域文件 |
| --- | --- | --- |
| `/login`、`/403`、`/404`、`/notifications` | 登录/异常/通知 | `base.routes.ts` |
| `/requirements`、`/requirements/new`、`/requirements/:id` | 需求池/提报/详情 7 tab | `req.routes.ts` |
| `/releases`、`/releases/:id` | 版本计划 | `req.routes.ts` |
| `/approvals` | 审批中心 | `flow.routes.ts` |
| `/deploy-orders`、`/deploy-orders/:id` | 发布单与准入 | `flow.routes.ts` |
| `/defects`、`/defects/:id`、`/defects/board` | 缺陷 | `deliver.routes.ts` |
| `/iterations/:id/board` | 迭代看板 | `deliver.routes.ts` |
| `/traceability` | 追溯矩阵 | `deliver.routes.ts` |
| `/testcases`、`/test-plans`、`/test-plans/:id/execute` | 用例库/计划执行 | `deliver.routes.ts` |
| `/admin/*`（组织/厂商/域/用户/角色/字典/流程/客户端/指标/审计） | 系统管理 9 页 | `deliver.routes.ts` |

路由 meta 自带 `{functionCode, requiresAuth}`；`router.beforeEach` 只做**展示层**拦截（无 token → login），真正判定在服务端。

### 6.3 状态管理

- `useAuthStore`（Pinia）：`token`（`sessionStorage`，不进 localStorage）、`tenantId`、`user`、`permissions:Set<string>`、`scope`；`login()` → `/api/req/auth/login`；`can(code)` 读服务端下发的 `permissions`。
- **服务端是唯一事实源**：业务规则（可执行动作、门禁、准入、权限）一律来自响应字段（`allowedActions`、`gate`、`admission`、`permissions`），前端不复制状态表；按钮隐藏只是体验，接口仍校验。
- 列表状态全部进 URL：`useListQuery` 把 `filters/page/size/sort` 双向同步 `route.query`，刷新/分享可复现（AC-5 逐格验收可直接贴 URL）；组件内只保留 `loading/rows/total`。
- `useDictStore`：启动拉一次字典（状态/优先级/角色名），变更时失效；不做轮询。
- 不引入 Vuex / TanStack Query；列表请求用 `useListQuery` 的 `fetch/refresh` 两个方法即可。

### 6.4 API 客户端与错误处理

```ts
// web/src/api/client.ts
export class ApiError extends Error { code: string; status: number; detail?: unknown }
export async function request<T>(opt: {method, url, body?, params?, headers?}): Promise<T>
```

- 自动附加 `Authorization` 与 `X-Tenant-Id`；`401` → 清 store 并跳 `/login?redirect=`；`403` → 跳 `/403` 并展示服务端 message；`409` → **不弹 toast**，抛出 `ApiError` 由页面渲染常驻清单（门禁/准入），符合 DESIGN §6.1；网络错误 → `ErrorState` + 重试。
- 文件下载用 `fetch` + `blob`（携带鉴权头），不拼 URL 免 token 泄漏。

### 6.5 组件契约（关键 5 个）

| 组件 | props | emits | 说明 |
| --- | --- | --- | --- |
| `GateChecklist` | `items: {code,name,passed,detail,fixHint}[]`、`mode:'admission'\|'gate'`、`readonly?` | `force` | FAIL 左侧 3px 红条 + 修复指引；受理 5 项与门禁 blockers 共用 |
| `ApprovalTimeline` | `events:{action,operator,comment,at,origin?}[]` | — | 加签/减签独立条目，节点版本徽标 |
| `TraceMatrix` | `rows:{requirement,gaps,complete,cells}[]`、`loading` | `open` | 单元 `✓齐备 / !缺口 / 阻塞 / –不适用`；`gaps` 非空时**强制**渲染缺口样式（不信任后端 complete 单值） |
| `PassRateRing` | `passed,planned,coverage` | — | 96/8 环，内净空 80（DESIGN §1.6） |
| `StatusPill` | `kind:'requirement'\|'defect'\|'approval'\|'execution'`、`value` | — | 颜色+文字双编码，不只靠颜色 |

### 6.6 页面数据流（要点）

- **需求详情 7 tab**：一次 `GET /requirements/{id}` 拿主档 + `allowedActions` + `permissions.fields`；子需求/任务/测试/缺陷/审批/追溯各自按 tab 懒加载（`/requirements/{id}` 的子资源或 `deliver` 接口）。动作区由 `allowedActions` 渲染；409 的 `detail.allowedTargets` 直接展示为"允许的下一步"。
- **审批中心**：左列表（todo/done/mine）+ 右详情；详情响应已含 `document.summary` 与 `timeline`；加签/减签/转办/撤回/催办按钮按 `tasks[].canXxx` 布尔渲染（服务端下发）。
- **发布单**：`GET /deploy-orders/{id}` 的 `admission.items` 常驻渲染；`submit` 前先 `POST evaluate` 刷新快照；409 后不跳页，原地展示 FAIL 项。
- **执行页**：左用例树（`test-modules` + `testcases`），右执行表；结果下拉四态 + 备注；失败行出现"转缺陷"；底部 `summary` 三个数字来自同一接口，不做前端加减。
- **追溯矩阵**：服务端分页 50 行 + 缺口筛选；行点击进需求详情追溯 tab；缺口单元直接跳创建页并预填 `requirementId`。

### 6.7 视觉与性能落地

- 令牌与表面处理完全按 `DESIGN.md §1/§7`：1px `#E5E6EB` 描边、白卡片、浅灰底、6px 圆角、状态 pill；覆盖 `--el-*` 变量于 `styles/theme.css`。
- 需求池 >200 行切 `el-table-v2` 虚拟滚动；矩阵 50 行/页；ECharts 按需注册；路由懒加载；首屏骨架屏。
- 键盘可达：所有动作按钮 `:focus-visible` 用 `--focus-ring`；门禁清单可 Tab 读到每一项。

## 7. 边界与异常

| 场景 | 行为 | 证据 |
| --- | --- | --- |
| 缺/错租户头 | 403 `TENANT_MISMATCH`，不落业务行、不写审计 | AC-1 脚本比对表计数前后不变 |
| 越权按 ID 取详情 | 403 `OUT_OF_SCOPE` + 审计（不是 404） | AC-4 |
| 撤销域授权 | 下一次请求即生效（每请求查库，无缓存） | AC-4 ≤1s |
| 转派厂商 | 同事务级联 task/defect vendor，B 立即可见 | AC-4 |
| 重复点击审批 | 第二次 409 `TASK_ALREADY_HANDLED`；实例行锁 | AC-9 |
| 同一单据并发发起 | `active_flag` 唯一键，第二次 409/复用现有实例 | AC-8 |
| 驳回后重提 | 旧实例保留（active_flag=NULL），新实例新版本 | AC-8/AC-10 |
| 审批人全部被停用 | 动作返回 `400 APPROVER_UNRESOLVED`，通知 PMO；不静默跳过 | AC-6 |
| 上报重放 | 200 `idempotent=true`，不新增行、不改计划结果 | AC-17 |
| 上报未知用例/版本 | 422，`test_execution` 计数不变 | AC-17 |
| 上报超限流 | 429 + `Retry-After`，不影响手工执行 | AC-17 |
| deliver 不可用 | 门禁/准入 fail-closed（409/FAIL + 修复提示），不误放行 | AC-12/16 |
| 用例改版后看历史 | 读 `test_case_version.snapshot_json` | AC-16 |
| 计划空用例 | 门禁不通过（`GATE_BLOCKED`，提示先纳入用例） | AC-16 |
| 无计划只有快速测试 | 按 D2 放行 | AC-16（D2） |
| 关闭迭代有未完成任务 | 409 `SPRINT_HAS_UNFINISHED_TASKS` + 迁移去向 | AC-14 |
| 编辑已发布流程 | 409 `WORKFLOW_PUBLISHED_IMMUTABLE` | AC-6 |
| 人天 0.3 | 400 `INVALID_DAY_PRECISION` | AC-2 |
| 第 4 层模块 | 400 `MODULE_DEPTH_EXCEEDED` | AC-16 |
| 导出内存 | 流式 + fetchSize，10 万行不 OOM | AC-20 |
| 时区 | 全链路 `Asia/Shanghai`；`DATETIME(3)`；ISO 输出带偏移 | 明细可复算 |
| 幂等种子 | 标记行存在则 skip，重复执行计数不变 | AC-19 |
| 审批流程种子 | `SeedService.seedWorkflow` 为 `REQ_SUBMIT/REQ_REVIEW/RELEASE/REQ_DEPLOY` 各补一条 `PUBLISHED` 默认流程（`req_type=BUS/ANY/ANY/ANY`）；重跑只补缺不重复，版本/发布单提交不再 `NO_WORKFLOW`/`APPROVAL_NOT_CONFIGURED` | AC-6 / AC-11 / AC-12 |
| 端口占用 | platform-up 直接报占用进程与 `lsof` 命令，不 sleep | r1 文化 |
| Flyway 拒绝 MySQL 9.6 | T0 先跑空库迁移；若报 unsupported，显式覆盖 `flyway.version`（只改 Flyway），记入 §10 | 风险 R1 |

## 8. 测试策略

### 8.1 分层

| 层 | 命令 | 内容 |
| --- | --- | --- |
| 静态门禁 | `make lint` | r1 9 条 + 新增 6 条：①路由不覆盖 r1 `/api/demo`；②`ScopedJdbc` 之外无 `JdbcTemplate`（`SeedService` 白名单）；③`db/migration` 只增不改；④deliver 不写 req 表（DDL 关键字扫描）；⑤前端不复制状态表（`views/` 不出现 `'待测试'` 等状态字面量 → 只允许通过 `dict`/`StatusPill`）；⑥`.gitignore` 覆盖前端产物 |
| 单元 | `make platform-unit` | 状态机全表、`PermissionMatrix` 逐格、JWT/HMAC 向量、指标公式边界（0 分母）、`ScopeSql` 三类模板、`GateDecision` 序列化、`StatusForbiddenDto` |
| 集成（AC 组） | `make platform-verify` | 起真栈 + 真 MySQL，按 AC-1..AC-20 分组，逐项 PASS/FAIL |
| 负向 | `make platform-test-negative` | 见 8.3 |
| 种子 | `make platform-seed` | AC-19，幂等跑两遍断言计数不变 |
| 性能 | `make platform-perf` | AC-20，10 万需求 + 1 万矩阵 + 20 并发上报 |
| 基线回归 | `./scripts/test-all.sh` 内的 r1 段 | `dev-up → verify → test-negative → dev-down` 原样 |

`make test` = `scripts/test-all.sh`：`lint → platform-unit → dev-up → verify → test-negative → platform-up → platform-verify → platform-test-negative → platform-seed ×2 → platform-down → dev-down`，EXIT trap 兜底停进程，绝不把环境留在半启动。

### 8.2 `platform-verify.sh` 分组（每项打印 PASS/FAIL + 修复提示，末尾 IDX==TOTAL 守卫）

| 组 | 关键断言（每条都是 HTTP/DB 计数，不接受"界面看着对"） |
| --- | --- |
| AC-1 | 建 T2 → 返回 roles=10/domains=7；T2 登录列表只含 T2；缺头/错头 403 且 `requirement` 计数不变；跨租户同名 code/username 可建 |
| AC-2 | 三类创建成功；PDR→PDR、BUS→RD 400；环 400；父人天=子之和（0.5）；手填 BUS 人天 400 |
| AC-3 | 三张状态表逐条合法流转 2xx；RD 跳步 409 且 `allowedTargets` 正确；`PATCH status` 400；`audit_log` 每次 +1 且 from/to 正确 |
| AC-4 | 厂商 A 在 8 入口看不到 B（列表 0 行、详情 403、搜索 0、CSV 行数 0、附件 403、看板数字不含 B、用例 0、矩阵 0 行）；撤销域授权后下一请求即 403；转派后 B 可见 A 不可见 |
| AC-5 | `GET /admin/permissions` 生成逐格探测：允许 2xx，不允许 403；多角色并集；403 有审计 |
| AC-6 | 四类流程发布；类型×域绑定命中；未匹配走默认；发布后再 PUT 409；无审批人发布 400 + nodeKey |
| AC-7 | 会签全过才过；或签一人过；条件命中第一个分支（构造两个都满足的分支断言取第一个）；抄送产生通知且不阻塞；驳回实例 REJECTED 且单据回退 |
| AC-8 | 发布 v2 后新单走 v2；v1 在途（含会签+加签）走完；无重复任务（`wf_task` 计数）；无卡死（实例终态） |
| AC-9 | 前/后加签顺序正确；会签加签后新人未过则不流转；减签空原因 400、被减签人收到通知、末位 409；转办/撤回/催办留痕 |
| AC-10 | 四类单据回调后状态正确（含 D3/D5 口径）；驳回回退；重提新实例旧实例保留 |
| AC-11 | Tab×8 筛选组合；聚合视图行数=平铺子集；CSV 行数=total 且首行水印；版本挂需求、锁定后增删 409；过期风险标红并可筛 |
| AC-12 | 5 项逐项 FAIL/PASS 组合；FAIL 409 且 `items` 完整；直接 curl 同样 409；空原因强制放行 400；放行后审计+通知+随单展示；成功率可复算 |
| AC-13 | 缺陷创建派生 domain/vendor（请求伪造值被忽略）；执行失败转缺陷带 case/version/execution；驳回与关闭重开各 +1；非法流转 409；看板 4 数字与明细复算一致；业务方摘要仅 3 字段；B 看不到 A |
| AC-14 | 任务拆解、完成率回写；拖拽后 GET 一致；关闭有未完成任务 409，迁移后可关；任务小时不计入 actual_days |
| AC-15 | 6 列齐备性：空 MR 显示缺 MR、无执行显示缺执行、未关闭 P0/P1 显示阻塞、无发布单显示未发布；`gaps` 与 `complete` 同步；缺口筛选；厂商只见自己 |
| AC-16 | 第 4 层 400；保存生成 v2 且执行历史仍显示 v1 快照；执行结果四态；通过率/覆盖率与明细一致；失败转缺陷；STRICT 有 NOT_RUN 即 409；LOOSE 仅 FAIL 阻断且 BLOCKED 无 note 阻断；空计划 409；直接改状态 409 |
| AC-17 | 无签名/错签名/时间戳超时 401；IP/scope/停用 403；未知 case 422、未知 version 422、NOT_RUN 400，且 `test_execution` 计数不变；重复上报 200 idempotent 且只 1 行；最新结果合并；429；`api_call_log` 每次一条 |
| AC-18 | 4 条定义含公式/版本/生效日；看板数字点开有定义；同一筛选看板/矩阵/发布统计一致；改口径生成新版本旧版可查 |
| AC-19 | 7 域/3 厂商/10 账号/完整链路/1 计划≥10 用例；10 账号可登录且只见权限内；跑两遍计数不变 |
| AC-20 | 10 万需求 P95≤1s；看板≤2s；准入≤500ms；矩阵≤2s；上报 P95≤300ms（20 并发）；1000 行导入≤10s（脚本记录 p50/p95 与 EXPLAIN） |

### 8.3 负向清单（`platform-test-negative.sh`，每条断言"拒绝 + 不落库"）

1. 缺 `X-Tenant-Id` 调创建接口 → 403 且表计数不变；2. 厂商 B 直连 A 的需求/缺陷/附件 → 403；3. `PATCH` 带 `status` → 400；4. RD 跳步 → 409；5. 无审批人的流程发布 → 400；6. 编辑已发布流程 → 409；7. 减签空原因 → 400；8. 已处理任务加签 → 409；9. 准入 FAIL 直接 POST `/submit` → 409；10. 空原因强制放行 → 400；11. 第 4 层模块 → 400；12. 重复/未知/`NOT_RUN` 上报 → 200 幂等 / 422 / 400 且计数不变；13. 关闭有未完成任务迭代 → 409；14. 导出越权 → 0 行（不是 500）；15. 已发布 Release 增删需求 → 409。

### 8.4 证据与状态机自校验

- 每条 AC 在 QA-REPORT/ACCEPTANCE 里给：命令、关键输出片段、DB 计数 SQL、必要时截图（仅补充）。
- 本方案的状态机与数据模型已用 logicprobe 预检：RD/BUS 状态机 22 项检查 0 error（仅 S5/A1 的"未定义事件对"警告，属预期，非法事件由 409 处理）；缺陷机 S3 的闭环无终态为业务允许重开的必然结果，已改为"CLOSED 只能经 reopen 离开"的断言；数据模型 16 项检查 0 error，DA3 的 4 条可空唯一键警告对应 §3.2 的有意设计（NULL 不参与唯一 + CHECK 约束兜底）。报告为 UNCONFIRMED（自动模式，未人工确认模型）。

## 9. 任务拆分（12 个任务 / 7 个批次；文件边界互斥）

> 规则：`files` = 该任务**修改/新建**的文件（拥有权）；`reads` = 只读引用，不阻塞并行。两个任务只有 files 互斥才能并行。所有任务都不得改写 `docs/teamflow/20261108-r2-devops-platform/`（上一轮任务夹不可变）。

### 批次 0（先做，阻塞全部）

**T0 工程骨架、契约模块与数据库迁移**
- files：`pom.xml`、`platform-contract/**`、`platform-common/pom.xml`、`req-service/pom.xml`、`req-service/src/main/resources/application.yml`、`deliver-service/pom.xml`、`deliver-service/src/main/resources/application.yml`、`api-gateway/src/main/java/com/example/gateway/PlatformRoutes.java`、`api-gateway/src/main/java/com/example/gateway/JwtGatewayFilter.java`、`api-gateway/src/main/resources/application.yml`、`req-service/src/main/resources/db/migration/V1__platform_schema.sql`、`.gitignore`、`.env.example`
- 内容：六个模块 POM（依赖集合一次到位，后续任务不再改 POM）；39 张表 DDL；`JwtCodec/HmacSigner/PermissionMatrix/StateTables/MetricFormulas/DataScope/GateDecision`；网关两条路由 + JWT 过滤器（**不动** `ApiGatewayApplication.java`）；`.gitignore` 补 `node_modules/ dist/ .vite/ .pnpm-store/ coverage/ *.tsbuildinfo .env.* var/ *.hprof`。
- 验收：`mvn -q -DskipTests package` 通过；空库启动 req-service 迁移成功（Flyway 若拒绝 MySQL 9.6，按 R1 处理并记录）；`GET /api/demo/hello` 仍 200。
- Git：`git add -A && git commit -m "chore(docs): 归档上一轮 PRD 产物与本轮任务夹"`（**第一步，先于任何代码**）→ 完成后 `git commit -m "feat(req): 工程骨架、契约模块与平台数据库迁移"`。

### 批次 1

**T1 租户上下文、鉴权、组织/域/账号与权限矩阵**
- files：`platform-common/**`；`req-service/src/main/java/com/example/req/ReqServiceApplication.java`、`config/**`、`auth/**`、`sys/**`；`req-service/src/test/java/com/example/req/auth/**`、`sys/**`
- reads：`platform-contract/**`、`V1__platform_schema.sql`
- 验收：登录/`/auth/me`；建租户自动预置 10 角色/7 域/字典；缺头 403 且不落库；`PermissionMatrix` 单测逐格；`ScopedJdbc` 缺标记抛异常。
- Git：`feat(req): 租户上下文、JWT 鉴权、组织域账号与权限矩阵`

### 批次 2（三路并行）

**T2 三类需求、拆解、状态机、需求池、版本计划**
- files：`req-service/src/main/java/com/example/req/requirement/**`、`req-service/src/main/java/com/example/req/release/**`、`req-service/src/test/java/com/example/req/requirement/**`
- reads：`platform-common/**`、`platform-contract/**`、`ApprovalPort`、`GatePort`
- 验收：AC-2/3/11 的 HTTP 断言通过；父汇总；导出流式；版本锁定；`RequirementApprovalCallback` 实现 `ApprovalCallback`（BUS/PDR/RD 的审批回退）。
- Git：`feat(req): 三类需求、拆解、状态机、需求池与版本计划`

**T3 自研审批引擎与审批中心**
- files：`req-service/src/main/java/com/example/req/approval/**`、`req-service/src/test/java/com/example/req/approval/**`
- reads：`platform-common/**`、`platform-contract/**`、`ApprovalCallback`（通过 Spring 收集实现，不 import 单据类）
- 验收：AC-6/7/8/9 全部断言；定义发布校验；v1/v2 并行；加签减签顺序；并发第二次 409。
- Git：`feat(approval): 自研审批引擎、审批中心与四类单据联动`

**T5 交付服务底座、缺陷闭环、任务迭代、指标与通知**
- files：`deliver-service/src/main/java/com/example/deliver/DeliverServiceApplication.java`、`reqread/**`、`defect/**`、`task/**`、`sprint/**`、`metric/**`、`deliver-service/src/main/resources/application.yml`、`deliver-service/src/test/java/**`（defect/metric）
- reads：`platform-common/**`、`platform-contract/**`、`V1` 中 deliver 表
- 验收：AC-13/14/18 断言；缺陷派生字段与状态机；看板与明细同源；任务完成率回写；`/internal/defects/open-high`。
- Git：`feat(defect): 缺陷闭环、任务迭代、指标与通知`

### 批次 3

**T4 版本发布单与五项准入**
- files：`req-service/src/main/java/com/example/req/deploy/**`、`req-service/src/main/java/com/example/req/integration/**`、`req-service/src/test/java/com/example/req/deploy/**`
- reads：T2 `ReleaseService`、T3 `ApprovalEngine`、`GatePort`
- 验收：AC-12 全部断言；5 项组合；强制放行；执行记录；统计复算。
- Git：`feat(release): 发布单、五项准入与强制放行`

**T6 用例版本、计划执行、测试门禁、追溯矩阵、开放 API**
- files：`deliver-service/src/main/java/com/example/deliver/testcase/**`、`testplan/**`、`gate/**`、`trace/**`、`openapi/**`、`deliver-service/src/test/java/**`（test/gate/openapi）
- reads：T5 `ReqReadModel`、`DefectService`、`platform-contract/**`
- 验收：AC-15/16/17 全部断言；门禁 STRICT/LOOSE；版本快照；HMAC 幂等；追溯缺口。
- Git：`feat(test): 用例版本、计划执行、测试门禁与开放 API`

**T7 前端基座、鉴权、共享组件与设计令牌**
- files：`web/**`（除 `src/views/{req,release,approval,deploy,deliver,admin}/**` 与 `src/router/routes/{req,flow,deliver}.routes.ts`）
- reads：TECHNICAL §4 接口契约、`DESIGN.md`、`UI-STYLE.md`
- 验收：`pnpm -C web build` 通过；登录→需求池空态可渲染；共享组件有独立 props 单测（可选 vitest，不强制）；404/403 页；URL 即状态。
- Git：`feat(req): Web 前端基座、鉴权与共享组件`

### 批次 4

**T8 前端需求/版本/审批/发布页面**
- files：`web/src/views/req/**`、`web/src/views/release/**`、`web/src/views/approval/**`、`web/src/views/deploy/**`、`web/src/router/routes/req.routes.ts`、`web/src/router/routes/flow.routes.ts`
- reads：`web/src/components/**`、`web/src/api/**`、`web/src/composables/**`
- 验收：需求池双栏+聚合/平铺+导出；详情 7 tab；审批中心动作；发布单 5 项清单与 409 常驻；与真实后端联调通过。
- Git：`feat(req): 需求池、详情、版本、审批与发布页面`

**T9 前端缺陷/迭代/矩阵/用例/计划/管理页面**
- files：`web/src/views/deliver/**`、`web/src/views/admin/**`、`web/src/router/routes/deliver.routes.ts`
- reads：同 T8
- 验收：缺陷看板 4 卡可下钻、摘要视图字段裁剪；迭代拖拽；矩阵缺口样式；用例版本切换；执行页通过率环；管理 9 页 CRUD。
- Git：`feat(defect): 缺陷、迭代、追溯、用例、计划与管理页面`

### 批次 5

**T10 平台脚本、make 门禁、种子与性能**
- files：`Makefile`、`scripts/lib.sh`、`scripts/platform-*.sh`、`scripts/test-all.sh`、`scripts/tests/lint.sh`、`scripts/tests/platform/**`、`req-service/src/main/java/com/example/req/seed/**`、`README.md`、`docs/teamflow/architecture/ARCHITECTURE.md`
- reads：全部服务接口、`V1`、`logs/teamflow/<runId>/scripts/`（若已有检查脚本，**原地扩展而非重建**）
- 验收：`make platform-up/verify/test-negative/seed/unit/perf` 全绿；`make test` 串起 r1 四步；种子跑两遍计数不变；性能报告落 `logs/teamflow/<runId>/`（仅结论与必要检查脚本，不 dump 命令输出）。
- Git：`feat(test): 平台脚本、make 门禁、种子数据与性能基线`

### 批次 6

**T11 交付自检（不写 QA 报告）**
- files：无固定（发现缺陷就地修，可能触及任何文件）
- 内容：`mvn package` + `platform-unit` + `platform-up` + `platform-verify` + `platform-test-negative` + `platform-seed ×2` + 一次 `make dev-up/verify/dev-down`；修复发现的问题；只记录结论（问题清单 + 修复提交），**不产出 QA-REPORT/ACCEPTANCE**，不做跨浏览器/压测全矩阵。
- Git：`fix(<scope>): 自检发现的问题`（如有）。

### 9.1 Git 动作汇总（必须执行）

1. **开工前**：把工作区 4 处未跟踪/已修改改动（`memory.md`、上一轮 3 个 HTML、本草任务夹）单独提交 `chore(docs): 归档上一轮 PRD 产物与本轮任务夹`；不删除、不改写上一轮任务夹。
2. 每个任务一次提交，格式 `feat(req|approval|release|defect|test): <做了什么>`；修复用 `fix(...)`。**不合并 main**（验收后由用户决定）。
3. 分支固定 `feat/devops-req-platform`，不新建分支、不改名。
4. 提交前本地必须 `make lint` 通过；不得提交 `target/`、`node_modules/`、`dist/`、`.env`、日志。

## 10. 风险与待验证

| # | 风险 | 触发点 | 处置 |
| --- | --- | --- | --- |
| R1 | Flyway（Boot 4 管理版本）可能拒绝 MySQL 9.6 | T0 首次迁移 | 先跑空库；若 unsupported，显式覆盖 `flyway.version` 到支持 9.x 的版本，仅改 Flyway，不动 Boot/Cloud；记录在 §10 与 README 版本矩阵 |
| R2 | Hibernate 7 对 `JSON` 列/`DECIMAL` 映射差异 | T0/T1 | JSON 列用 `@JdbcTypeCode(SqlTypes.JSON)` + `String` 读写；迁移验证 `ddl-auto=validate` 通过 |
| R3 | 需求列表 10 万 P95 | T2 | 组合索引 + keyset 深页；`platform-perf` 出 EXPLAIN；超标先降为 `size=50` 上限 |
| R4 | 矩阵 10k 行 2s | T6 | 单 SQL + EXISTS；超标加 30s 缓存（版本键 = `max(requirement.updated_at)`） |
| R5 | 准入 500ms（跨服务） | T4 | 批量门禁一次调用 + 300ms 超时 fail-closed；两条计数 SQL |
| R6 | HMAC 密钥可逆存储（D1） | T6 | `secret_enc` AES-GCM + `PLATFORM_MASTER_KEY`；明文只在创建/轮换返回；审计记录轮换人 |
| R7 | 审批并发/重复任务 | T3 | 实例悲观锁 + `wf_task` 唯一键 + `active_flag` 唯一键；负向用例覆盖 |
| R8 | 前端 5173 预览与网关代理 | T7/T10 | `vite preview` 的 `preview.proxy` 与 dev 同配；platform-up 用 HTML 探针判就绪 |
| R9 | 两服务共享 schema 的契约漂移 | T5/T6 | `ReqReadModel` 只读列清单写死在 TECHNICAL §3.5；lint 扫 deliver 侧写 req 表 |
| R10 | 种子 10 万行耗时 | T10 | `SeedService` 批量 1000 行/批 + 关闭自动提交；只跑一次，重复执行走 skip |

## 11. AC → 实现 → 验证映射

| AC | 主要实现 | 验证（脚本组） |
| --- | --- | --- |
| AC-1 | `TenantProvisioner`、`AuthFilter`、`sys_*` | AC-1：建租户/登录/缺头 403 不落库/跨租户同名 |
| AC-2 | `RequirementService`、`RequirementRollup`、`StateTables` | AC-2：父子/环/人天只读与汇总 |
| AC-3 | `RequirementStateMachine`、`StatusForbiddenDto`、`AuditService` | AC-3：合法/非法/直写/审计计数 |
| AC-4 | `ScopeSql`、`ScopedJdbc`、`ScopeGuard`、`SummaryProjector` | AC-4：8 入口 + 撤权 + 转派 |
| AC-5 | `PermissionMatrix`、`PermissionInterceptor`、`/admin/permissions` | AC-5：逐格探测 |
| AC-6 | `WorkflowDefinitionValidator`、`wf_definition` | AC-6：发布/绑定/默认/无审批人 |
| AC-7 | `ApprovalEngine`（ANY/ALL/CONDITION/CC） | AC-7：四语义 + 驳回 |
| AC-8 | `definition_version` 钉住、`active_flag` 唯一键 | AC-8：v2 新单、v1 走完、无重复任务 |
| AC-9 | 加签/减签/转办/撤回/催办 + 通知 | AC-9：顺序/原因/末位/留痕 |
| AC-10 | `ApprovalCallback` 实现（需求/版本/发布单） | AC-10：四类回调 + D3/D5 |
| AC-11 | `RequirementPoolQuery`、`RequirementExport`、`ReleaseService` | AC-11：筛选/聚合/CSV/锁定/标红 |
| AC-12 | `AdmissionEvaluator`、`DeployOrderService`、`DeliverGateClient` | AC-12：5 项组合/409/强制放行/统计 |
| AC-13 | `DefectService`、`DefectBoardQuery`、`DefectSummaryProvider` | AC-13：派生/重开/看板复算/摘要/跨厂商 |
| AC-14 | `TaskService`、`SprintService`、`IterationBoardQuery` | AC-14：完成率/拖拽/关闭拦截 |
| AC-15 | `TraceabilityQuery` | AC-15：6 列缺口/complete 一致/厂商隔离 |
| AC-16 | `TestCaseService`、`TestPlanService`、`TestGateService` | AC-16：版本快照/四态/门禁/空计划 |
| AC-17 | `HmacAuthFilter`、`ExecutionIngestService`、`RateLimiter` | AC-17：签名/拒收/幂等/合并/日志 |
| AC-18 | `MetricFormulas`、`MetricQuery`、`MetricDefinitionService` | AC-18：定义/同源/版本 |
| AC-19 | `SeedService`、`platform-seed.sh` | AC-19：幂等两遍 + 10 账号可见范围 |
| AC-20 | 索引、`ScopedJdbc` 分页、流式导出、`vite` 懒加载 | AC-20：`platform-perf` 六项基线 |

---

**文档边界**：本文件及本需求全部产物只写 `docs/teamflow/20261108-r2-devops-req-platform/`；跨需求约定写 `docs/teamflow/memory.md`（同主题替换）；运行期检查脚本与结论写 `logs/teamflow/<runId>/`。
