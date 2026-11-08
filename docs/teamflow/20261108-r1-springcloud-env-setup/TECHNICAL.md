# TECHNICAL · Spring Cloud + Nacos 开发环境骨架

> 需求基线：[PRD.md](PRD.md)（20261108-r1）。产品架构：[../architecture/ARCHITECTURE.md](../architecture/ARCHITECTURE.md)。
> 分支：`feat/springcloud-env-setup`（基线提交 `6fec40f`）。
> 本文是**施工图 + 契约清单**：类名、方法名、脚本函数名、配置键、端点、退出码以此为准；dev/QA 直接引用，不得自创同义名。

## 0. 技术判断

这不是一个 Web 应用，而是**一套可执行的开发环境**。所以本需求没有数据库、没有前端、没有业务状态机；技术设计的重心是四件事：**版本矩阵真能构建、Nacos 生命周期可控、调用链可证伪、体检不假绿**。

三条不可退让的性质（后续任何改动都不得破坏）：

| 编号 | 性质 | 结构性保证 |
|---|---|---|
| P1 | `configSource=nacos` **只可能**来自 Nacos | `demo-service/src/main/resources/application.yml` 完全不定义 `demo.config-source`；`DemoController` 的缺省值是 `local`；只有 Nacos 种子里是 `nacos` |
| P2 | `make verify` 的端口项只是“监听”证据 | `scripts/verify.sh` 中端口项显式标注“仅监听”；健康判定必须命中 HTTP body；`make test-negative` 证明它会变红 |
| P3 | `make dev-down` 的成功判据是端口释放 | `scripts/dev-down.sh` 以六个端口无 LISTEN 为唯一成功条件，而不是“信号已发送” |

**现状**：脚手架已在工作区落地（父 POM、2 个模块、8 个脚本、Makefile、README、种子配置、`.gitignore`）。脚手架阶段实测记录：`make dev-up` 打印 `READY`、`make verify` 14 PASS / 0 FAIL、`make test-negative` 退出 0、`make dev-down` 六端口释放。本阶段**不改写已绿实现**，职责是固化契约、标出仍需实测的时序风险、给出可并行任务与 Git 动作。QA 阶段必须复跑，不以本文或脚手架记录代替实测。

---

## 1. 版本矩阵与依赖边界

| 组件 | 版本 | 引入方式 | 选它的理由 |
|---|---|---|---|
| JDK | 25.0.2 | 本机 Homebrew | Boot 3.5.x 官方只支持 17–24，锁 Boot 4 才能不降 JDK |
| Spring Boot | 4.0.9 | `spring-boot-starter-parent`（`pom.xml:7-11`） | 2025.1 线唯一与 JDK 25 同线的 Boot |
| Spring Cloud | 2025.1.4 | `spring-cloud-dependencies` BOM（`pom.xml:37-39`） | 与 Boot 4.0.9 同代 |
| Spring Cloud Alibaba | 2025.1.0.0 | `spring-cloud-alibaba-dependencies` BOM（`pom.xml:44-46`） | 提供 Nacos discovery/config starter；BOM 内 `nacos-client 3.1.1` |
| 网关 starter | 5.0.4 | `spring-cloud-starter-gateway-server-webflux` | 2025.1 的正式 artefact 名（旧 `spring-cloud-starter-gateway` 已不是这条线） |
| Nacos server | 3.3.0 | 官方 tar.gz，`scripts/nacos.sh` 下载 | standalone 一个进程覆盖注册 + 配置；3.3 根 pom 已是 Boot 4.0.6，可在 JDK 25 跑 |
| Maven | 3.9.12 | 本机 | — |

依赖边界（越界即缺陷）：

- **父 POM 只做 `dependencyManagement`**：两个 BOM import，子模块不写版本号；`java.version=25`、UTF-8（`pom.xml:26-31`）。
- **`api-gateway` 不得引 webmvc**：`gateway-server-webflux` 与 MVC 同栈会打架；`spring-cloud-starter-loadbalancer` **必须显式引**（`api-gateway/pom.xml:23-26`），否则 `lb://` 直接 503。
- **`demo-service` 不得引 gateway**：它只需要 `webmvc + nacos-discovery + nacos-config + actuator`。
- **版本号只允许在父 POM 的 properties 出现一次**；README、ARCHITECTURE 的矩阵必须与 `mvn dependency:tree` 一致。

---

## 2. 运行拓扑与启动时序

```
curl :8080/api/demo/hello
        │
        ▼
 api-gateway :8080 ── lb://demo-service（Nacos 发现，StripPrefix=1）──▶ demo-service :8081
        │                                                                   │
        └──────────── 注册 / 拉取配置（nacos-client 3.1.1）─────────────────┘
                                    │
                         Nacos standalone 3.3.0
              HTTP :8848 · 控制台 :8849 · gRPC :9848 / :9849
```

`make dev-up`（`scripts/dev-up.sh`）五阶段，任一阶段失败即 `exit 1` 并 tail 服务日志（`dev-up.sh:5-15`）：

| 阶段 | 动作 | 成功判据 | 失败时的可见行为 |
|---|---|---|---|
| 0 前置 | `env-precheck.sh`：JDK≥25 / Maven / 六端口空闲 / 磁盘≥1.5GB / 网络（仅告警） | 全部通过，**未启动任何进程** | 逐项报错 + JDK<25 的两套可执行方案（`env-precheck.sh:10-30`） |
| 1/5 Nacos | `nacos.sh start` → `ensure` + standalone + 120s 健康轮询 | 8848 健康 OpenAPI 200 | tail `.nacos/logs/start.out`、`nacos.log` |
| 2/5 配置 | `publish_nacos_config` → v3 admin POST → **回读校验** | 回读内容含 `config-source:` | 报 HTTP 码与响应，提示鉴权/端口 |
| 3/5 构建 | `mvn -q -DskipTests package` | 两个可执行 jar 产出 | Maven 原始输出 + 仓库回退告警 |
| 4/5 服务 | 先 demo-service 后 api-gateway，各等 `/actuator/health` = UP（90s） | 两个 health UP | tail 对应 `logs/*.log` |
| 5/5 就绪 | Nacos 实例列表含健康 `demo-service:8081` + 网关链路冒烟 | 实例命中 + `instancePort=8081` + `configSource=nacos` | 打印实际响应体，指明断点 |

只有第 5 阶段全过才打印 `READY`（`dev-up.sh:48-60`）。**顺序不可交换**：网关先起会因无实例而 503；服务先起必须等 Nacos 就绪，否则 `spring.config.import` 非 optional 会直接启动失败。

---

## 3. 代码、配置与脚本契约

### 3.1 Java 类（逐字）

```java
// api-gateway/src/main/java/com/example/gateway/ApiGatewayApplication.java
public static void main(String[] args)
@Bean RouteLocator demoRoutes(RouteLocatorBuilder builder)
//   route id = "demo-service"
//   path("/api/**") + filters(f -> f.stripPrefix(1)) + uri("lb://demo-service")

// demo-service/src/main/java/com/example/demo/DemoServiceApplication.java
public static void main(String[] args)

// demo-service/src/main/java/com/example/demo/DemoController.java
@RestController @RefreshScope
public DemoController(@Value("${demo.greeting:Hello}") String greeting,
                      @Value("${demo.config-source:local}") String configSource,
                      @Value("${server.port:8081}") int instancePort)
@GetMapping("/demo/hello") public Map<String, Object> hello()
//   返回顺序固定：message → instancePort → configSource（LinkedHashMap）
```

路由声明在 Java 而不是 YAML：Gateway 5 的属性命名空间在 2025.1 迁移过，`RouteLocatorBuilder` API 稳定，骨架宁可少一个配置面（`ApiGatewayApplication.java:10-16`）。

### 3.2 配置键

| 配置文件 | 键 | 默认 / 值 | 语义 |
|---|---|---|---|
| `api-gateway/.../application.yml` | `server.port` | `${GATEWAY_PORT:8080}` | 网关端口 |
| | `spring.application.name` | `api-gateway` | 注册名 |
| | `spring.cloud.nacos.discovery.server-addr` | `${NACOS_SERVER_ADDR:127.0.0.1:8848}` | 发现中心 |
| `demo-service/.../application.yml` | `server.port` | `${DEMO_PORT:8081}` | 服务端口 |
| | `spring.application.name` | `demo-service` | **网关按此名负载均衡，不得改** |
| | `spring.config.import` | `nacos:demo-service.yml?group=DEFAULT_GROUP` | 非 optional = fail-fast（`demo-service/.../application.yml:8-10`） |
| | `spring.cloud.nacos.config.file-extension` | `yml` | 与 dataId 后缀一致 |
| | `demo.greeting` | `Hello` | 本机缺省；Nacos 覆盖为 `Hello from Nacos` |
| | `demo.config-source` | **本地不定义**（Bean 缺省 `local`） | 证伪开关，只能来自 Nacos |
| 两者 | `management.endpoints.web.exposure.include` | `health,info` | 只暴露只读端点 |
| | `management.endpoint.health.show-details` | `always` | 便于排障；健康判定仍只看 `status` |

可覆盖环境变量：`GATEWAY_PORT`、`DEMO_PORT`、`NACOS_HTTP_PORT`、`NACOS_CONSOLE_PORT`、`NACOS_GRPC_PORT`、`NACOS_GRPC_TLS_PORT`、`NACOS_SERVER_ADDR`、`NACOS_VERSION`、`NACOS_HOME`、`NACOS_SHA256`。`.env` 会被 `lib.sh:33-36` 自动 source（已 gitignore）。

### 3.3 `scripts/lib.sh` 公共函数（唯一实现，其他脚本只调用）

| 函数 | 签名 | 返回 / 副作用 |
|---|---|---|
| `port_pids` | `port_pids <port>` | 空格分隔 PID；**无监听也返回 0**（`|| true`，规避 `set -e`） |
| `port_listening` | `port_listening <port>` | 0 = 有 LISTEN，1 = 无 |
| `app_pid` / `app_alive` | `<name>` | 读 `.run/<name>.pid`；`kill -0` 判活（处理陈旧 pid） |
| `http_ok` / `http_body` | `<url>` | curl `-f`；body 失败时输出空串 |
| `app_health_up` | `app_health_up <port>` | 命中 `"status":"UP"` 才为真 |
| `wait_until` | `wait_until <desc> <timeout> <cmd...>` | 每秒轮询；超时报错并返回 1 |
| `wait_http_ok` / `wait_app_health` | `<url> <desc> <timeout>` / `<name> <port>` | 分别封装上面两种等待（health 90s） |
| `nacos_health_path` | 无参 | 依次试 `v1/console/health/readiness`、`liveness`、`v1/ns/operator/metrics`，回显可用路径 |
| `nacos_healthy` | 无参 | 上述任一 200 |
| `publish_nacos_config` | 无参 | POST v3 admin，**非 200 即失败**并给修复提示 |
| `nacos_has_instance` | 无参 | v3 实例列表含健康 `port=8081` |
| `maven_build` | 无参 | `-DskipTests package`；先 `detect_maven_repo` |
| `detect_maven_repo` | 无参 | `~/.m2` 不可写则改用 `.m2/repository`（沙箱兜底，`lib.sh:157-167`） |
| `app_jar` | `app_jar <module>` | 取 `target/*.jar`（排除 sources/javadoc），找不到即失败 |
| `start_app` | `start_app <name> <port> <module>` | 已活则跳过（幂等）；`nohup java -jar ... --server.port=<port>`；写 pid；启动即死则 tail 日志 |
| `stop_app` | `stop_app <name>` | TERM → 20s 轮询 → KILL；清 pid |
| `port_free_after` | `port_free_after <port> <seconds>` | 轮询端口释放，供 dev-down/nacos stop 用 |

### 3.4 脚本 CLI 与退出码（退出码即门禁）

| 命令 | 成功 | 失败 | 关键行为 |
|---|---|---|---|
| `scripts/env-precheck.sh` | 0 | 1 | 启动前检查；失败计数并打印“未启动任何进程” |
| `scripts/nacos.sh ensure\|start\|stop\|status` | 0 | 1 | `ensure` 幂等；`start` 120s 健康轮询；`stop` shutdown → TERM → KILL |
| `scripts/dev-up.sh` | 0 且输出 `READY` | 1 | 五阶段；ERR trap 打日志 |
| `scripts/dev-down.sh` | 0（六端口释放） | 1 | 逆序停 + 端口兜底 kill |
| `scripts/build.sh` | 0 | 1 | 薄封装：复用 `maven_build` 的仓库回退 |
| `scripts/verify.sh` | 0（全绿） | 1 | 14 项逐项 PASS/FAIL，失败项汇总 |
| `scripts/tests/verify-negative.sh` | 0 | 1 | 停服 → verify 必红且指名 → 恢复 → 必绿；EXIT trap 保证恢复 |
| `make test` | 0 | 1 | `build.sh` + 负向用例 |

`nacos.sh` 的 `ensure` 必须幂等写入四类配置（`nacos.sh:61-74`）：`nacos.console.port=8849`、`nacos.core.auth.enabled=false`、`nacos.core.auth.admin.enabled=false`、非空的 token secret 与 server identity；`server.port` 仅在 `NACOS_HTTP_PORT≠8848` 时改。原因见 §7.3、§7.4。

---

## 4. 数据模型与存储

本需求**没有关系型数据库、没有 ORM、没有业务实体**（PRD 非目标，MySQL 9.6 已装但不接入）。这里的“数据”只有三类：配置中心种子、运行态文件、本地缓存。

### 4.1 Nacos 配置实体（唯一业务数据）

| 属性 | 值 |
|---|---|
| dataId | `demo-service.yml`（`lib.sh:21`） |
| group | `DEFAULT_GROUP` |
| type | `yaml` |
| 来源文件 | `config/nacos/demo-service.yml`（**必须入库**） |
| 写入方式 | `dev-up` 阶段 2 通过 v3 admin API 幂等覆盖；不做“存在即跳过”，保证种子可演进 |
| 读取方 | `demo-service` 的 `spring.config.import`；`verify.sh` 的“配置可读”项 |

字段约束：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `demo.greeting` | string | 非空 | 链路响应里的 `message`，改它可现场演示热更新 |
| `demo.config-source` | enum | 有效值 `nacos` | 证伪开关：本地 YAML 无此键，若响应为 `nacos` 则值只可能来自 Nacos |

存储不变式（QA 可直接断言）：

- **I1** `grep -r "config-source" demo-service/src/main/resources/` 必须无结果（本地不得定义）。
- **I2** 种子文件必须含 `config-source: nacos`。
- **I3** `dev-up` 发布后回读内容必须含 `config-source:`（`dev-up.sh:24-27`）。
- **I4** 运行时响应必须 `configSource=nacos` 且 `instancePort=8081`。
- **I5** Nacos 中改 `demo.greeting` 后，响应 `message` 随之变化（AC-4②，时序风险见 §10.2）。

### 4.2 运行态文件

| 路径 | 内容 | 生命周期 | 失效处理 |
|---|---|---|---|
| `.run/api-gateway.pid`、`.run/demo-service.pid`、`.run/nacos.pid` | 单行 PID | `start_app`/`nacos.sh start` 写；`stop_*` 删 | `app_alive` 用 `kill -0` 判活，陈旧文件视为未运行并重启 |
| `logs/api-gateway.log`、`logs/demo-service.log` | 应用 stdout/stderr | 追加写，gitignore | 启动失败时 tail 最后 20/30 行 |
| `.nacos/logs/start.out`、`nacos.log` | Nacos 启动日志 | Nacos 管理 | 120s 未就绪时 tail 40 行 |

`.run/*.pid` 是**唯一**的进程状态持久化；不引入数据库/状态文件格式（避免再一个需要同步的真相源）。

### 4.3 缓存与发行包

| 路径 | 内容 | 是否入库 |
|---|---|---|
| `.nacos/cache/nacos-server-3.3.0.tar.gz` | 官方发行包（约 233MB） | 否 |
| `.nacos/bin|conf|data|logs` | 解压目录、`application.properties`、内嵌 Derby 数据、日志 | 否（整目录忽略） |
| `.m2/repository` | `~/.m2` 不可写时的仓库回退 | 否 |
| `target/` | Maven 产物 | 否 |

sha256 固定为 `18d5ec3067e9e310401028ecc35e6e9c3a854c9b14d084f48b83bfea53208107`（`nacos.sh:11-15`）；校验失败删除损坏包并报期望/实际值。

---

## 5. API 设计

### 5.1 交付端点（这是“产品的 API”）

| 方法 | 路径 | 端口 | 入参 | 成功响应 | 失败语义 |
|---|---|---|---|---|---|
| GET | `/api/demo/hello` | 8080（网关） | 无 | `200`，JSON 三字段 | 无健康实例 → `503`；后端超时 → `504`；路径不匹配 → `404` |
| GET | `/demo/hello` | 8081（直连） | 无 | 同上 | AC-4③ 的**对照组**：服务停止后必然连接失败，证明 8080 的成功不是端口假象 |
| GET | `/actuator/health` | 8080 / 8081 | 无 | `200`，body 含 `"status":"UP"` | 服务未起 → 连接失败；组件异常 → `503` + `DOWN` |

成功响应（字段顺序即实现契约，正则断言不依赖顺序）：

```json
{"message":"Hello from Nacos","instancePort":8081,"configSource":"nacos"}
```

字段来源：`message` ← `demo.greeting`（Nacos）；`instancePort` ← `server.port`；`configSource` ← `demo.config-source`（Nacos，缺省 `local`）。三个字段缺一不可——少了 `instancePort` 就分不清请求落到了哪个实例。

### 5.2 Nacos OpenAPI（v3 admin，脚本内部契约）

| 方法 | 路径 | 参数 | 用途 |
|---|---|---|---|
| POST | `/nacos/v3/admin/cs/config` | form：`dataId`、`groupName`、`type=yaml`、`content@file` | 幂等发布种子配置（`lib.sh:124-140`） |
| GET | `/nacos/v3/admin/cs/config` | query：`dataId`、`groupName` | 回读校验（`dev-up` 与 `verify` 共用） |
| GET | `/nacos/v3/admin/ns/instance/list` | query：`serviceName=demo-service`、`groupName`、`healthyOnly=true` | 断言健康实例与端口（`lib.sh:142-146`） |
| GET | `/nacos/v1/console/health/{readiness,liveness}`、`/nacos/v1/ns/operator/metrics` | 无 | 健康探测，按顺序降级（`lib.sh:107-122`） |

**为什么是 v3**：Nacos 3.3 已移除 v1 配置 API，`/nacos/v1/cs/configs` 返回 404；发布/读取必须走 v3 admin，且参数名是 `groupName` 而非 `group`。这是脚手架阶段真实踩到的坑，写进契约防止回退。

### 5.3 错误语义（排障定位表）

| 现象 | 首查 | 结论 |
|---|---|---|
| 网关 `503` | Nacos 实例列表 + `demo-service` health | 无健康实例（服务没起/没注册/端口不符） |
| 网关 `504` | `logs/demo-service.log` | 实例在但处理超时 |
| 网关 `404` | 路由声明 | 路径不在 `/api/**` 或 StripPrefix 写错 |
| `configSource=local` | Nacos 配置是否发布 + 启动日志 | 配置没读到（但 fail-fast 下通常起不来） |
| 应用启动即退出 | `logs/*.log` 首行 | 多为 Nacos 不可达 / 端口占用 |
| 8848 200 但控制台打不开 | `.nacos/conf/application.properties` | 控制台端口未写 8849 或被 8080 占用 |

---

## 6. 前端与状态管理

### 6.1 前端：明确为空（不是遗漏）

本需求**没有前端**：无页面、无组件树、无路由、无浏览器状态管理。浏览器只用于 Nacos 控制台 `http://127.0.0.1:8849`（排障视图，不是交付 UI）。理由：需求要验证的是“网关 → 注册中心 → 服务 → 配置中心”这条后端链路，加一层 UI 只会增加一个不参与验收的失败面。若后续要做管理台，应作为新需求评审，不在本骨架里塞半成品。

### 6.2 等价的状态管理：环境生命周期状态机

没有前端状态，但有**进程/环境状态**，它由 `.run/*.pid` + 端口 + HTTP 健康共同决定。状态与转移如下（这就是 `dev-up`/`dev-down` 的行为契约）：

| 当前状态 | 事件 | 下一状态 | 判据 / 副作用 |
|---|---|---|---|
| `STOPPED` | `dev_up` | `PRECHECK_OK` 或 `FAILED` | 前置检查失败 → `FAILED`，**不启动任何进程** |
| `PRECHECK_OK` | Nacos 健康 | `NACOS_READY` | 8848 健康 OpenAPI 200；否则 `FAILED` |
| `NACOS_READY` | 配置发布 + 回读 | `CONFIG_READY` | 回读含 `config-source:`；否则 `FAILED` |
| `CONFIG_READY` | 构建成功 | `BUILT` | 两个 jar 存在；否则 `FAILED` |
| `BUILT` | 两服务 health UP | `SERVICES_READY` | 各 90s 内 UP；否则 `FAILED` |
| `SERVICES_READY` | 实例 + 链路断言 | `READY` | 实例列表命中且三字段正确 |
| `READY` / 任意中间态 | `dev_down` | `STOPPING` → `STOPPED` | 逆序停止；六端口释放才算成功 |
| `FAILED` | `dev_down` | `STOPPED` | 允许“半启动”状态被清理（Nacos 已起、服务未起） |
| `FAILED` | 直接重跑 `dev_up` | 幂等重入 | Nacos 已健康则跳过；配置覆盖；已活进程跳过 |

不变式：

- **S1** `READY` ⇒ 六端口 LISTEN ∧ 两个 health UP ∧ Nacos 实例健康 ∧ 链路三字段正确。
- **S2** `STOPPED` ⇒ 六端口无 LISTEN。
- **S3** 任一状态都可由 `dev_down` 收敛到 `STOPPED`（dev-down 不假设 pid 文件存在）。
- **S4** `dev_up` 在任意状态可重入，不得因“已存在”而失败。

幂等规则（`start_app`/`ensure_nacos`）：进程活着就跳过启动；Nacos 已解压就跳过下载；配置永远覆盖发布。**只有配置发布是“覆盖”而非“跳过”**，因为种子文件会随需求演进。

---

## 7. 关键实现点与边界

1. **`lb://` 必须配 loadbalancer**：缺 `spring-cloud-starter-loadbalancer` 时网关能启动、但路由 503（`api-gateway/pom.xml:23-26`）。这是脚手架阶段真实故障。
2. **Nacos 3.3 移除 v1 配置 API**：`/nacos/v1/cs/configs` 404；统一走 `/nacos/v3/admin/cs/config`，参数 `groupName`（`lib.sh:127-131`）。
3. **控制台端口冲突**：Nacos 3.x 控制台默认 8080，与网关撞车；`ensure` 幂等写 `nacos.console.port=8849`（`nacos.sh:61-63`）。
4. **startup.sh 交互阻塞**：空 token secret / identity 时 `startup.sh` 会 `read` 死循环；`ensure` 预写本地值并关闭 admin 鉴权，保证非交互（`nacos.sh:64-72`）。这些值是**仅本机**的假值，不得用于任何共享环境。
5. **fail-fast 而非 optional**：`spring.config.import=nacos:...` 不带 `optional:`，Nacos 不可达则服务启动失败。这是反目标“Nacos 形同虚设”的结构性对策——宁可起不来，也不静默用本地默认值（`demo-service/.../application.yml:8-10`）。
6. **`lsof` 无匹配返回 1**：`set -e` 下会让脚本静默退出；`port_pids` 用 `|| true` 兜底（`lib.sh:51-54`）。
7. **`~/.m2` 沙箱不可写**：`detect_maven_repo` 探测写入失败后回退仓库内 `.m2/repository`，构建仍可复现（`lib.sh:157-167`）。
8. **健康判定看 body 不看状态码**：`/actuator/health` 在 DOWN 时也可能是 200（取决于组件），必须 grep `"status":"UP"`（`lib.sh:78-83`）。
9. **配置热更新链路**：`@RefreshScope`（`DemoController.java:20`）+ SCA 配置监听；改 Nacos 后 Bean 重建、`@Value` 重新注入。**风险**：若 `spring.config.import` 路径下未自动触发 `RefreshEvent`，则手动 `/actuator/refresh` 也未暴露（只暴露 health,info），此时 AC-4② 不成立——必须修实现，不能用“重启服务”冒充热更新。
10. **503 的时限风险**：停服后 503 依赖 Nacos 实例摘除 + 网关 LoadBalancer 缓存（默认 TTL 约 35s），可能压线超过 AC-4 的 30s。若实测超时，显式调低 `spring.cloud.loadbalancer.cache.ttl` 并记录，不得放宽 AC。
11. **平台边界**：脚本依赖 bash 3.2+、`lsof`、`shasum`、`curl`、`tar`；目标是 macOS（本机）。Linux 需把 `shasum -a 256` 换成 `sha256sum`，这是已知移植点，不在本需求验收内。
12. **单实例假设**：同一工作区只能跑一套环境（固定 `.run/`、固定日志、固定端口）；不支持并行多实例。需要多套时用 `NACOS_HOME` + 端口环境变量整体偏移，且 README 需说明。

---

## 8. 测试策略

| 层 | 手段 | 覆盖 | 是否自动 |
|---|---|---|---|
| 静态 | `mvn -q -DskipTests package` | 版本兼容、编译、可执行 jar | 是（`make build`） |
| 版本 | `mvn dependency:tree` 对照 README 矩阵 | Boot/Cloud/SCA/nacos-client/gateway | 手工（验收时留证） |
| 环境 | `make verify`（14 项） | 工具链、端口、Nacos 健康/配置/实例、两 health、链路 | 是 |
| 负向 | `make test-negative` | 体检不会假绿 | 是 |
| 可证伪 | 停服 503 / 重启恢复 / 改配置生效 / 直连对照组 | AC-4 | 手工（QA 记录时间戳） |
| 文档 | README 从零到绿灯 ≤8 条命令、≥4 条排障 | AC-8 | 人工走查 |

`verify.sh` 的 14 项清单（顺序即输出顺序）：

| # | 项 | 通过判据 |
|---|---|---|
| 1 | JDK ≥25 | 解析 `java -version` 主版本 |
| 2 | Maven | `mvn -v` 可执行 |
| 3–8 | 六端口 | `lsof` 有 LISTEN（**仅监听，不承担健康判定**） |
| 9 | Nacos 健康 OpenAPI | 三个候选路径任一 200 |
| 10 | Nacos 配置可读 | 回读 body 含 `config-source:` |
| 11 | Nacos 健康实例 | 实例列表含 `port=8081` |
| 12 | demo-service health | body 含 `"status":"UP"` |
| 13 | api-gateway health | 同上 |
| 14 | 调用链 | `200` + `instancePort=8081` + `configSource=nacos` |

负向用例的三条断言（缺一不可，`verify-negative.sh:23-34`）：退出码非 0；输出含 `demo-service`；输出含 `修复`。随后重启并断言恢复全绿（EXIT trap 兜底恢复）。**纳入 `make test`**，失败即本需求不通过。

执行顺序与预算：`make dev-up`（缓存后 ≤180s，首次含下载 ≤10min）→ `make verify`（秒级）→ `make test-negative`（含停服/重启，约 1–2min）→ `make dev-down`（≤10s）。QA 必须在同一台机器上按这个顺序跑一遍并留输出。

**不做的测试**：JUnit/单测（骨架无业务逻辑，Boot test starter 已就位但不强求）、CI、性能/压测、跨平台。这些是明确非目标，不写进验收。

---

## 9. 任务拆分（可并行）

本轮未收到 `[PIPELINE-DISPATCHED TASKS]`，按**文件边界**给出任务；若后续下发任务清单，以其为准合并，不另起一套。文件集合不相交即可并行，`reads` 不阻塞并行。

```
T1 ──┬─▶ T2 ─┐
     ├─▶ T3 ─┼─▶ T8（文档一致性 + 交付自查）
     ├─▶ T4 ─┴─▶ T5 ─▶ T6
     └─▶ T7
```

| 任务 | 归属文件（只有这些） | 读取 | 规格要点 | 完成判据 |
|---|---|---|---|---|
| **T1 版本矩阵与模块边界** | `pom.xml`、`api-gateway/pom.xml`、`demo-service/pom.xml` | `README.md` 版本矩阵 | 两个 BOM import、`java.version=25`；网关引 loadbalancer、不引 webmvc；服务不引 gateway | `mvn -q -DskipTests package` 成功；`dependency:tree` 与矩阵一致 |
| **T2 网关入口与路由** | `ApiGatewayApplication.java`、`api-gateway/.../application.yml` | T1 的 `api-gateway/pom.xml` | `demoRoutes` 逐字契约；`/api/**` + StripPrefix(1) + `lb://demo-service`；端口/注册地址走环境变量 | `curl :8080/api/demo/hello` 200；停服后 ≤30s 503、重启 ≤60s 恢复（AC-4①） |
| **T3 示例服务与 Nacos 配置** | `DemoServiceApplication.java`、`DemoController.java`、`demo-service/.../application.yml`、`config/nacos/demo-service.yml` | T1 的 `demo-service/pom.xml` | 三字段 + `@RefreshScope`；本地**不得**定义 `demo.config-source`；import 非 optional；种子含 `config-source: nacos` | AC-3 字段正确；AC-4② 改配置 ≤30s 生效、AC-4③ 直连对照组失败 |
| **T4 公共库与 Nacos 生命周期** | `scripts/lib.sh`、`scripts/nacos.sh`、`scripts/env-precheck.sh`、`scripts/build.sh` | — | §3.3 函数逐字；ensure 幂等写 8849/auth=false/token/identity；stop 三级降级；precheck 不启动进程 | `nacos.sh start` 后 8848 可读、8849 可开、stop 后端口清空；JDK<25 时给两套可执行方案 |
| **T5 一键启停** | `scripts/dev-up.sh`、`scripts/dev-down.sh` | T4 的 `lib.sh`、`nacos.sh` | 五阶段顺序与 READY；逆序停止 + 六端口兜底 | AC-1、AC-2；连续两次 dev-up/down 无端口与 Derby 锁错误 |
| **T6 体检与负向用例** | `scripts/verify.sh`、`scripts/tests/verify-negative.sh` | T4 的 `lib.sh` | 14 项；端口项标注“仅监听”；失败项 + 修复提示；负向三条断言 | AC-5 全绿 exit 0；AC-6 停服必红、恢复必绿；纳入 `make test` |
| **T7 Makefile 与仓洁** | `Makefile`、`.gitignore` | — | 目标：dev-up/dev-down/verify/build/test/test-negative/nacos-status/clean/distclean；忽略 `target/`、`.nacos/`、`*.log`、`.env`、`.DS_Store`、`.idea/`、`*.iml`、`.run/`、`.m2/`；**保留** `logs/teamflow/` 忽略规则；**不忽略** `config/nacos/` | AC-7：首跑 dev-up + 构建 + 体检后 `git status --porcelain` 无上述产物 |
| **T8 文档一致性与交付自查** | `README.md`、`../architecture/ARCHITECTURE.md`、`../memory.md` | 全部代码与脚本 | README ≤8 条命令、版本矩阵、≥4 条排障（含 8080 冲突）、JDK<25 指引；ARCHITECTURE §10 的“未验证”改为实测结论（附日期/结果）；memory 只改同主题行，不追加变更日志 | 文档与实现零矛盾；**自查**：重跑 `dev-up → verify → test-negative → dev-down`，修掉发现的问题，只记录结论，**不写** QA-REPORT/ACCEPTANCE |

### 9.1 Git 动作（来自 PRD §9，必须执行，不得改写）

| 编号 | 动作 | 落点 |
|---|---|---|
| G1 | 在 `feat/springcloud-env-setup` 上开发；**不新建分支、不切回 main** | 全部任务（T1–T8） |
| G2 | `docs/` 未跟踪目录随本需求一并提交；**不 stash、不回滚、不删除** | T8 / 收尾 |
| G3 | `.gitignore` 按 PRD 清单补全，且不得删除 `logs/teamflow/` 规则、不得忽略 `config/nacos/` | T7 |
| G4 | 验收通过后由收尾阶段统一提交；信息 `feat: springcloud dev environment skeleton with nacos (JDK 25 + Boot 4 + Cloud 2025.1)`；提交前必须跑 `make verify` + `make test-negative` 并附结果 | 验收/收尾（不在 T1–T8 内提前提交） |
| G5 | 不引入 Docker、不创建 Python 交付物、不改 AGENTS.md 非托管区 | 全部任务 |

---

## 10. 风险与降级

### 10.1 版本兼容（PRD §7.1 的三级方案，禁止静默更换）

| 级别 | 组合 | 触发条件 | 动作 |
|---|---|---|---|
| 1（当前） | Boot 4.0.9 + Cloud 2025.1.4 + SCA 2025.1.0.0 + Nacos 3.3.0 + JDK 25 | 默认 | 已实测可构建/启动；不改 |
| 2 | 服务端换 Nacos 3.2.x/3.1.2，客户端仍 nacos-client 3.1.1 | nacos-client 与服务端协议不兼容（连接/注册失败） | 改 `NACOS_VERSION`，**显式告知**并同步 README/ARCHITECTURE |
| 3 | Boot 3.5.16 + Cloud 2025.0.3 + SCA 2025.0.0.0 + Nacos 2.5.5 + **JDK 21/24** | Boot 4 与 SCA 出现无法绕过的字节码/自动配置冲突 | 整套降级；README 命令数放宽到 ≤9；同步改 pom、`NACOS_VERSION`、矩阵与排障；**必须显式告知** |

任何一级生效，都要同时改 `pom.xml`、`scripts/nacos.sh` 的默认版本、`README.md` 矩阵、`ARCHITECTURE.md`，并在提交信息里写明原因。只改一处视为缺陷。

### 10.2 其他风险

| 风险 | 影响 | 对策 |
|---|---|---|
| AC-4② 热更新不触发 | 配置改了响应不变 | 先确认 SCA 监听是否在 `spring.config.import` 路径生效；不生效则修实现（不得用重启冒充） |
| AC-4① 503 超过 30s | LoadBalancer 缓存 TTL（≈35s）压线 | 实测超时则显式调低 `spring.cloud.loadbalancer.cache.ttl` 并记录 |
| 首次下载 233MB 失败 | 冷启动不可复现 | sha256 固定 + 失败给 URL 与手动放置路径；缓存命中可离线 |
| 8849/9848/9849 被占 | Nacos 起不来 | precheck 六端口检查；环境变量整体偏移 |
| Derby 数据目录锁 | 第二次 dev-up 失败 | dev-down 兜底 kill；顽固时 `make distclean` 重建 |
| Nacos 控制台无鉴权 | 仅本机可接受，公网不可 | 默认 `auth=false` 且监听本机；README 标注禁止暴露公网 |
| 沙箱禁写 `~/.m2` | 构建失败 | 自动回退 `.m2/repository`（gitignore） |

---

## 11. 交付自查（本阶段边界）

本阶段只做**技术方案与契约**。dev 完成后由收尾自查执行：`make dev-up` → `make verify` → `make test-negative` → `make dev-down`，全绿即交付；发现问题当场修，只记录结论。**不写** `QA-REPORT.md` / `ACCEPTANCE.md`（证据链属于 QA 与验收阶段），不跑跨浏览器/多环境矩阵。

---

## 12. 契约证据索引

| 契约 | 证据 |
|---|---|
| 版本矩阵 | `pom.xml:7-11`、`pom.xml:27-31`、`pom.xml:37-46` |
| 网关路由 | `ApiGatewayApplication.java:25-31` |
| loadbalancer 依赖 | `api-gateway/pom.xml:23-26` |
| 三字段与缺省 `local` | `DemoController.java:20-41` |
| fail-fast 配置导入 | `demo-service/src/main/resources/application.yml:8-10` |
| 本地无 `config-source` | 同文件 `:20-22` |
| 种子配置 | `config/nacos/demo-service.yml` |
| 端口/超时/健康函数 | `scripts/lib.sh:8-24`、`:51-122` |
| Nacos v3 发布/回读 | `scripts/lib.sh:124-146` |
| 控制台 8849 与鉴权 | `scripts/nacos.sh:61-75` |
| 启动五阶段 | `scripts/dev-up.sh:17-46` |
| 六端口释放 | `scripts/dev-down.sh:12-39` |
| 14 项体检 | `scripts/verify.sh:19-103` |
| 负向三断言 | `scripts/tests/verify-negative.sh:23-45` |
