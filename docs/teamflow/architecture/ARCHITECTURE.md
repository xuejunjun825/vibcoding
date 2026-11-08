# 架构总览 · Spring Cloud + Nacos 开发环境骨架

> 产品级长期文档。需求基线：`docs/teamflow/20261108-r1-springcloud-env-setup/PRD.md`（20261108-r1）。
> 本文描述**已落地**的脚手架与契约，不写未来愿景；版本变更必须先改本文与 README 的版本矩阵。

## 1. 决策摘要与取舍

| 决策 | 选择 | 理由与放弃项 |
|---|---|---|
| 形态 | **可复现的最小 Spring Cloud 骨架**，不是“装一堆软件” | 用户目标是“配置完整开发环境”；可验证、可重入比软件清单更重要 |
| 语言/栈 | **Java 25 + Maven 多模块** | 需求指定 Spring Cloud 体系；TypeScript + React + Node 启动更快，但无法验证 Nacos 注册/配置这一核心诉求，且团队现有栈为 Java。故不采用 TS/Node 全栈模板 |
| Boot/Cloud | **Boot 4.0.9 + Cloud 2025.1.4** | 本机 JDK 25；Boot 3.5.x 官方只支持 17–24。Boot 4 / Cloud 2025.1 是唯一不降 JDK 的同线组合 |
| 注册/配置中心 | **Nacos 3.3.0 standalone**（内嵌 Derby） | 单进程即可覆盖注册 + 配置；Eureka + Config Server 需要两个进程且无配置热更新。放弃 Docker：本机没有 |
| 网关 | `spring-cloud-starter-gateway-server-webflux` + **Java `RouteLocator`** | Gateway 5 的 YAML 属性命名空间发生过迁移；Java API 稳定，避免“版本对了但路由不生效” |
| 配置获取 | `spring.config.import=nacos:demo-service.yml?group=DEFAULT_GROUP`（非 optional） | fail-fast：Nacos 不可用即启动失败，杜绝“本地默认值假绿” |
| 交付脚本 | **bash + Makefile** | 无 Python 交付物（用户 20261108-r1 确认移除）；bash 足够覆盖下载/校验/健康轮询/负向用例，且零额外运行时 |
| Python | **不是交付物、不是验收依赖** | README 仅记录本机 Python 3.14.3 这一事实；不建 `python/`、`pyproject.toml`、`.venv/`。若后续要做 Python 工具链，应作为独立需求重新评审 |
| CI | **非目标**（PRD §3） | 不新增 CI 文件；`make test` 即本地等价门禁，未来接 CI 只需调用相同命令 |

## 2. 拓扑

```
浏览器 / curl
     │  GET /api/demo/hello
     ▼
api-gateway :8080  ── lb://demo-service（Nacos 服务发现）──▶  demo-service :8081
     │                                                              │
     └──────────── 注册 / 读取配置（nacos-client 3.1.1）────────────┘
                                    │
                          Nacos standalone 3.3.0
             HTTP :8848 · 控制台 :8849 · gRPC :9848/:9849
```

请求链路的三个可证伪点：
1. 网关必须经 `lb://demo-service` 找到实例（不是写死 `localhost:8081`）；
2. demo-service 必须出现在 Nacos 健康实例列表；
3. 响应里的 `configSource=nacos` 只能来自 Nacos 的 `demo-service.yml`（本地 YAML 无此键，默认值为 `local`）。

## 3. 目录树（已落地）

```
.
├── AGENTS.md                     # 团队守则 + 文档索引 + teamflow 托管区
├── README.md                     # 从零到绿灯 / 版本矩阵 / 排障
├── Makefile                      # 薄封装：dev-up/dev-down/verify/test/clean
├── pom.xml                       # 父 POM（packaging=pom, java 25, 三个 BOM）
├── .gitignore                    # target/.nacos/logs/.run/IDE/macOS
├── api-gateway/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/gateway/ApiGatewayApplication.java   # RouteLocator: /api/** -> lb://demo-service
│       └── resources/application.yml                            # port 8080 + nacos discovery
├── demo-service/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/demo/DemoServiceApplication.java
│       ├── java/com/example/demo/DemoController.java            # GET /demo/hello（@RefreshScope）
│       └── resources/application.yml                            # port 8081 + config.import=nacos:
├── config/nacos/demo-service.yml # 种子配置（必须入库）
├── scripts/
│   ├── lib.sh                    # 公共函数：端口/PID/HTTP 轮询/启停/构建
│   ├── env-precheck.sh           # JDK≥25 / Maven / 六端口 / 磁盘 / 网络
│   ├── nacos.sh                  # ensure|start|stop|status
│   ├── dev-up.sh                 # 五阶段启动 + READY
│   ├── dev-down.sh               # 逆序停止 + 六端口校验
│   ├── verify.sh                 # 14 项体检，逐项 PASS/FAIL + 修复提示
│   └── tests/verify-negative.sh  # 停服→必红→恢复→必绿
├── docs/teamflow/
│   ├── memory.md
│   ├── architecture/ARCHITECTURE.md
│   └── 20261108-r1-springcloud-env-setup/PRD.md
├── logs/                         # 运行日志（gitignore）
└── .nacos/                       # Nacos 发行包 + 数据（gitignore）
```

## 4. 模块契约

### 4.1 `api-gateway`（8080）

- 依赖：`spring-cloud-starter-gateway-server-webflux`、`spring-cloud-starter-loadbalancer`（`lb://` 必需，缺失时链路由 503）、`spring-cloud-starter-alibaba-nacos-discovery`、`spring-boot-starter-actuator`。
- 路由：`path("/api/**")` + `StripPrefix=1` → `lb://demo-service`，声明在 `ApiGatewayApplication#demoRoutes`。
- 暴露：`GET /actuator/health`；不承载业务逻辑。
- 环境变量：`GATEWAY_PORT`（默认 8080）、`NACOS_SERVER_ADDR`（默认 `127.0.0.1:8848`）。

### 4.2 `demo-service`（8081）

- 依赖：`spring-boot-starter-webmvc`、`spring-cloud-starter-alibaba-nacos-discovery`、`spring-cloud-starter-alibaba-nacos-config`、`spring-boot-starter-actuator`。
- 配置：`spring.config.import=nacos:demo-service.yml?group=DEFAULT_GROUP`（非 optional）；本地 YAML **不定义** `demo.config-source`。
- 接口：`GET /demo/hello` → `{"message": "...", "instancePort": 8081, "configSource": "nacos"}`；`@RefreshScope` 使 Nacos 配置变更在刷新后生效。
- 注册名固定 `demo-service`，网关按此名做负载均衡。

### 4.3 脚本契约（退出码即门禁）

| 脚本 | 成功 | 失败 | 关键行为 |
|---|---|---|---|
| `env-precheck.sh` | 0 | 1 | 启动任何进程前完成检查；JDK<25 打印可执行降级方案 |
| `nacos.sh ensure` | 0 | 1 | 下载 → sha256 → 解压 → 幂等写 `nacos.console.port=8849` / `auth=false` / `admin.enabled=false` / token 与 identity（可用 `NACOS_TOKEN_SECRET` 覆盖，默认仅本机；避免 3.3 启动脚本交互阻塞） |
| `nacos.sh start` | 0（健康 OpenAPI 200） | 1 | 120s 轮询；失败 tail `.nacos/logs/startup.log`（3.x）与 `nacos.log` |
| `nacos.sh stop` | 0（8848/9848/9849 释放） | 1 | `shutdown.sh` → SIGTERM → SIGKILL |
| `build.sh` | 0 | 1 | `mvn -q -DskipTests package`；Maven 仓库回退统一走 `lib.sh:detect_maven_repo`（`~/.m2` 不可写时改用 `.m2/repository`） |
| `dev-up.sh` | 0 且输出 `READY` | 1 | 前置检查 → Nacos → 发布配置 → 构建 → 起服务 → 链路冒烟（失败报阶段名并只 tail 该阶段日志） |
| `dev-down.sh` | 0（六端口释放） | 1 | 逆序停止，兜底 SIGTERM→SIGKILL，并报告实测耗时 |
| `verify.sh` | 0（全绿） | 1 | 14 项：工具链 2 + 端口 6 + Nacos 3 + 服务 2 + 链路 1 |
| `tests/verify-negative.sh` | 0 | 1 | 停 demo-service → verify 非零且含 `demo-service`+`修复` → 重启 → 全绿 |

`verify.sh` 的端口项只断言 **LISTEN**，并明确打印“仅监听”；健康项必须命中 HTTP body（`/actuator/health` 的 `UP`、Nacos OpenAPI 内容、链路 JSON 字段）。这是“不假绿”的结构性保证。

## 5. 依赖与版本矩阵

```xml
<!-- 父 POM -->
<parent>org.springframework.boot:spring-boot-starter-parent:4.0.9</parent>
<spring-cloud.version>2025.1.4</spring-cloud.version>
<spring-cloud-alibaba.version>2025.1.0.0</spring-cloud-alibaba.version>
<java.version>25</java.version>
```

| 组件 | 版本 | 引入方式 |
|---|---|---|
| JDK | 25.0.2 | 本机（Homebrew） |
| Spring Boot | 4.0.9 | parent |
| Spring Cloud | 2025.1.4 | `spring-cloud-dependencies` BOM |
| Spring Cloud Alibaba | 2025.1.0.0 | `spring-cloud-alibaba-dependencies` BOM |
| nacos-client | 3.1.1 | SCA BOM 传递 |
| gateway starter | 5.0.4 | `spring-cloud-starter-gateway-server-webflux` |
| Nacos server | 3.3.0 | 官方 tar.gz（`scripts/nacos.sh` 下载，`.nacos/` 缓存） |
| Maven | 3.9.12 | 本机 |

**版本纪律**：不得静默更换版本。实测失败时按 PRD §7.1 的三级降级顺序执行，并在 README/提交信息中显式记录。

## 6. 构建、测试与 CI

### 6.1 构建

```bash
mvn -q -DskipTests package      # 父工程 + 两个模块；各模块产出可执行 Boot jar
```

- 统一 `java.version=25`，编码 UTF-8。
- 无 `maven-enforcer` 额外约束；JDK 版本由 `env-precheck.sh` 前置门禁兜底。

### 6.2 测试策略

| 层 | 手段 | 覆盖 |
|---|---|---|
| 构建 | `mvn package`（当前骨架无单测，Boot test starter 已就位） | 版本兼容、编译 |
| 环境 | `make verify`（14 项） | 工具链、端口、Nacos 健康、配置可读、注册实例、两个服务健康、端到端字段 |
| 反向 | `make test-negative` | verify 的“不会假绿”性质：停服务必红、恢复必绿 |
| 聚合 | `make test` = 构建 + 负向用例 | PRD P1 门禁 |
| 手工 | `curl :8080/api/demo/hello`；改 Nacos 配置观察 `message` 变化；停服务观察 503 | AC-4 的可证伪行为 |

### 6.3 CI 要点（非目标，仅预留）

PRD 明确不做 CI/CD，本骨架不提交 workflow 文件。未来接入时，CI 作业只需等价执行：

```bash
make dev-up && make verify && make test-negative && make dev-down
```

注意 CI 环境需提供 JDK 25、可下载 Nacos 的网络与 `8080/8081/8848/8849/9848/9849` 空闲端口。

## 7. 失败模式与对策

| 失败模式 | 检测点 | 对策 |
|---|---|---|
| Nacos 控制台 8080 撞网关 | `nacos.sh ensure` 写 `nacos.console.port=8849`；verify 端口项 | 控制台 URL 固定 8849；README 排障第 1 条 |
| Nacos 未就绪 | `nacos.sh start` 120s 轮询健康 OpenAPI；失败 tail 日志 | 不把 302/登录页当 READY |
| Nacos 数据目录锁 / 残留 JVM | `dev-down.sh` 校验 8848/9848/9849，兜底 SIGKILL | `make distclean` 重建 |
| 服务启动失败 | `start_app` 检查 pid；`wait_app_health` 90s；失败 tail `logs/*.log` | 先看 Nacos 连接，再看端口 |
| 配置未生效 | verify 读回 `demo-service.yml` 且链路断言 `configSource=nacos` | `dev-up` 幂等重发 |
| 体检假绿 | 负向用例停 demo-service 后必须非零且指名失败项 | 端口项明确不承担健康判定 |
| JDK <25 | `env-precheck.sh` 首项门禁，打印 `brew install openjdk@25` 与整套降级组合 | 不让 Maven 抛裸异常 |
| 网络不可达 | 下载失败给出 URL 与手动放置路径；Maven 失败提示 `settings.xml` 镜像 | 缓存命中可离线 |

## 8. 验收映射

| AC | 落地位置 |
|---|---|
| AC-1 单命令冷启动 | `scripts/dev-up.sh` 五阶段；输出 `READY`；Nacos 健康 + 两 health + 实例列表 + 冒烟 |
| AC-2 停干净可重入 | `scripts/dev-down.sh`：逆序停止 + 六端口校验 + 兜底 kill |
| AC-3 链路真实 | `DemoController` 三字段 + `ApiGatewayApplication` `lb://` 路由 + Nacos 种子配置 |
| AC-4 可证伪 | `@RefreshScope`；`verify.sh` 链路项；README 手工步骤 |
| AC-5 体检覆盖 | `scripts/verify.sh` 14 项，HTTP 语义判定，红灯给修复提示 |
| AC-6 负向用例 | `scripts/tests/verify-negative.sh`，纳入 `make test` |
| AC-7 仓库干净 | `.gitignore`：`target/`、`.nacos/`、`logs/`、`.run/`、IDE、`.env` |
| AC-8 文档闭环 | `README.md`：从零到绿灯 6 条命令（≤8）、版本矩阵（与 §5 一致）、8 条排障（含 8080 冲突、JDK<25 降级）、JDK<25 两套方案 |

## 9. 已知边界与后续

- 当前无业务单测（骨架阶段），Boot test starter 已引入；开发阶段可补 `DemoController` 的 slice 测试。
- Nacos 为 standalone + 内嵌 Derby，**仅限本机**；`nacos.core.auth.enabled=false` 不得暴露公网。
- 端口固定默认值可用环境变量覆盖，但不支持多实例并行（同一工作区一套环境）。
- `make dev-up` **可重入**：阶段 0 识别 `.run/*.pid` 对应的本项目进程并放行其端口，环境已 READY 时再执行仍 exit 0 + READY（实测）。
- 已闭合的时延项：`dev-down` 约 5s（两个服务 `graceful-shutdown-wait-time: 0`）；停服后网关 **178ms** 内返回 503（连接被拒与 `PrematureCloseException` 均映射为 503）；重启后约 17s 恢复 200。
- 后续候选（P2）：Nacos 集群/MySQL 持久化、命名空间与鉴权、可选 docker-compose 变体、CI 流水线。

## 10. 本次脚手架完成度（实测清单 2026-11-08）

> 本节只写**本机实测**结论；`dependency:tree`、四命令的原始输出由 dev/QA 阶段复跑可得，不是设计推演。

### 10.1 已闭合（✅ 实测通过）

| 实测项 | 命令 / 证据 | 结果 |
|---|---|---|
| 构建 | `mvn -DskipTests package`（JDK 25.0.2 / Maven 3.9.12） | 父工程 + 两模块 `BUILD SUCCESS`，各产可执行 Boot jar |
| 版本矩阵对齐 | `mvn dependency:tree -Dincludes=…` | Boot 4.0.9、gateway starter 5.0.4、loadbalancer 5.0.4、SCA 2025.1.0.0、nacos-client 3.1.1 —— 与 §5 逐行一致 |
| 模块边界 | 同一命令限定 webmvc/webflux/gateway | 网关只含 webflux（无 `spring-webmvc`）；demo-service 无任何 gateway 构件 |
| 一键启动（AC-1） | `make dev-up` | exit 0 + `READY`；六端口 LISTEN；链路 `200` + `instancePort=8081` + `configSource=nacos`；缓存命中 21.5–26s（≤180s） |
| 可重入（AC-2 后半） | READY 状态下再跑 `make dev-up` | exit 0 + `READY`（阶段 0 识别本项目 pid 并放行端口）；`make dev-down` 后再 up 同样成功，无 `Address already in use`、无 Derby 锁 |
| 体检（AC-5） | `make verify` | 14/14 PASS（端口项仅监听、健康/链路项均命中 HTTP body） |
| 负向用例（AC-6） | `make test-negative` | exit 0：停 demo-service → 10 PASS / 4 FAIL（4 个 FAIL 均指名 demo-service）→ 恢复后 14/14 |
| 配置可证伪（AC-4 ②） | 改 Nacos `demo.greeting` 后轮询网关 URL | **1s** 内响应变为新值，恢复种子同样 1s；直连 8081 同步变化，证明 `@RefreshScope` 在 `spring.config.import` 路径下生效 |
| 停服可证伪（AC-4 ③） | 停 demo-service 后直连 8081 | 连接失败（`000`），对照组成立 |

### 10.2 已修复的差距（2026-11-08 修复轮，实测复核）

| 项 | 修复前 | 现状（实测） |
|---|---|---|
| AC-2 时延（≤10s） | `make dev-down` 实测 26s（SCA 默认 10s 优雅下线 × 2） | 两个模块设 `spring.cloud.nacos.discovery.graceful-shutdown-wait-time: 0`，实测 **5s**，六端口释放 |
| AC-4 ① 状态码 | 停服瞬间先出现 500（`PrematureCloseException`），178ms 后转 503 | 网关把 `ConnectException` 与 `PrematureCloseException` 都映射为固定 503；首个非 200 即 503 |
| dev-up 幂等 | READY 时重跑 → 端口占用 exit 1 | 阶段 0 放行本项目 pid 持有的端口，重跑 exit 0 + READY |
| `nacos.sh` 错误提示 | 中文全角标点紧跟裸变量名，`set -u` 把全角字节并入变量名 → `unbound variable`，下载/sha256 失败提示全部失效 | 5 处改为 `${VAR}`；`make lint` 静态门禁防回归 |

### 10.3 边界与明确不做

- ⛔ 不做：Python 交付物、Docker、CI、业务功能、MySQL 接入（PRD 非目标，本期不变）。
- 其余边界（无业务单测、单实例端口、Nacos 仅本机）见 §9。
