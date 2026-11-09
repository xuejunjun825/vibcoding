# TeamFlow 产品记忆（约定 / 待办）

> 跨需求共享的**约定与决策**，不是变更日志。同一主题只保留一行，改约定时替换该行。

## 技术栈（20261108-r1 确立，按用户确认单修订）

- 运行时：本机 JDK **25.0.2**（Oracle OpenJDK arm64，Homebrew）。Boot 3.5.x 官方仅支持 JDK 17–24，故锁定 **Spring Boot 4.0.9 + Spring Cloud 2025.1.4**；不要引入 Boot 3.5 教程代码。
- 构建：**Maven 多模块**（父 `packaging=pom` + `api-gateway` + `demo-service`），不用 Gradle。
- 注册/配置中心：**Nacos 单机 standalone（服务端 3.3.0，客户端 nacos-client 3.1.1 via SCA 2025.1.0.0）**；发行包解压到仓库内 `.nacos/`（整个目录 gitignore），由 `scripts/nacos.sh` 下载/校验/启动/停止。不使用 Eureka、不使用 Spring Cloud Config Server。
- 网关：`spring-cloud-starter-gateway-server-webflux`（Cloud 2025.1 的正式 artefact 名），路由 `lb://demo-service`。
- 端口约定：**8080 网关 / 8081 示例服务 / 8848 Nacos HTTP / 8849 Nacos 控制台（3.x 默认 8080，必须改，否则与网关冲突）/ 9848 客户端 gRPC / 9849 服务端 gRPC**；允许环境变量覆盖，脚本默认值不变。
- 交付形态：**Makefile + bash 脚本**（`dev-up / dev-down / verify / lint / test / test-negative / clean`），**不用 docker-compose**（本机无 Docker）。
- **本产品线 P0 交付不含 Python 工程**（用户 20261108-r1 确认移除）：`make verify` 与负向用例由 bash 承担；README 可把本机 Python 3.14.3 作为事实记录，但不得作为验收依赖。
- 仓洁：`target/`、`.nacos/`、`*.log`、`.env`、`.DS_Store`、`.idea/` 一律忽略；`logs/teamflow/` 为插件运行日志，已被忽略且不得从 `.gitignore` 删除；`config/nacos/` 种子配置必须入库。

## DevOps 研发管理平台（20261108-r2 起，本轮 M1–M5）

- 服务与端口：复用 `api-gateway`(8080) + Nacos；新增 `req-service`(8082：组织/需求/版本/审批/发布)、`deliver-service`(8083：缺陷/任务/测试/追溯/开放 API)、`web/`(Vue 3 + Element Plus + Vite + ECharts)；**8081 demo-service 保留不动**。
- 数据：本机 **MySQL 9.6**（brew service，已运行）+ Flyway，库 `devops_platform`；本轮只做 **ROW 行级**隔离（`tenant_id`/`domain_id`/`vendor_id`），SCHEMA/DATABASE 形态留 M9。
- 认证与集成：JWT + 租户上下文（`X-Tenant-Id` 必须与 JWT 一致，缺失/不一致 403 且不落库）；开放 API 用 AppKey/Secret + HMAC-SHA256 + 时间戳 ±5min + IP 白名单 + scope，**自动化测试结果只接收上报、不自建执行器**。
- 质量铁律：执行记录必须绑定 `case_version` 与 `round_id`/`env_id`（改用例、换轮次都不改历史结论）；计划完成、测试报告、质量门三处共用同一准出/聚合实现（数字必须可复算、界面与接口同源）；发布单准入第 6 项为质量门，P0/P1 规则不可豁免；已会签报告与门禁快照不可变（历史发布单可回放）；缺陷/测试数据与需求同受 §7 隔离。发布单写库前必须按需求校验覆盖测试计划的批准状态（唯一闸门：未批准 409 不落单，取数不可用 503 fail-closed，无计划不阻塞）。
- 验证：新增 `make platform-up / platform-verify / platform-test-negative / platform-seed` 并纳入 `make test`；r1 的 `make dev-up / verify / test-negative / dev-down` 必须继续全绿。
- 禁止：Docker、Gradle、Python 交付物（示例脚本用 bash/curl）、Flowable/Camunda、Redis/MQ；不替换版本矩阵而不改 README/ARCHITECTURE。
- 仓洁新增：`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`.env.*`（`.env.example` 入库）必须忽略。

## 已解决（20261108-r1 修复轮实测复核，勿按旧结论排障）

- `make dev-down` 实测 **4–5s**（≤10s）：两个模块已设 `spring.cloud.nacos.discovery.graceful-shutdown-wait-time: 0`，不再有 SCA 默认 10s × 2 的等待。
- 停掉 demo-service 后网关**首个非 200 即 503**（固定 JSON `downstream instance unreachable`）：`ApiGatewayApplication.connectRefusedIs503` 同时映射 `ConnectException` 与 `PrematureCloseException`；重启恢复实测 2–17s（≤60s）。
- `make dev-up` **可重入**：环境 READY 时重跑 exit 0 + `READY`（阶段 0 识别本项目 PID），不再需要先 `make dev-down`。
- 回归防线：`make lint`（`scripts/tests/lint.sh`）用 9 条静态/实测断言锁住上述缺陷类（`${VAR}` 插值、公共函数单点、错误提示、文档一致性），`make test` 已包含它。

## 文档位置

- 需求产物：`docs/teamflow/<yyyyMMdd-rN-slug>/`（创建后不可变、不升版）。
- 跨需求架构文档：`docs/teamflow/architecture/ARCHITECTURE.md`（模块契约、脚本契约、版本矩阵、验收映射）。
- 脚手架入口：`README.md` 的“从零到绿灯”五条命令；`make dev-up / verify / test-negative / dev-down`（加静态门禁 `make lint`）为唯一验证路径。
