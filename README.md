# Spring Cloud + Nacos 开发环境骨架

一条命令拉起 **Nacos + 网关 + 示例服务**；一条 URL 打通 **网关 → Nacos 注册 → 服务 → Nacos 配置**；一条命令体检，且用负向用例证明它不会假绿。

- JDK **25** / Spring Boot **4.0.9** / Spring Cloud **2025.1.4** / Spring Cloud Alibaba **2025.1.0.0**
- Nacos **3.3.0** standalone：HTTP `8848`、控制台 `8849`、gRPC `9848/9849`
- 网关 `8080`、示例服务 `8081`
- **无 Docker、无 Python 交付物**（本机 Python 3.14.3 仅作事实记录，不参与构建/验收）

## 从零到绿灯（≤8 条命令）

```bash
# 0) 仅当本机没有 JDK 25 时执行（已有可跳过）
brew install openjdk@25

# 1) 一条命令：前置检查 → 下载/启动 Nacos → 发布配置 → 构建 → 起两个服务 → 冒烟
make dev-up

# 2) 亲手打开链路（应返回 instancePort=8081、configSource=nacos）
curl -s http://localhost:8080/api/demo/hello

# 3) 逐项体检
make verify

# 4) 负向用例：停服务后 verify 必须变红，恢复后必须变绿
make test-negative

# 5) 停干净（六个端口全部释放）
make dev-down
```

首次运行会下载约 233MB 的 Nacos 发行包（打印进度与 sha256），缓存在 `.nacos/` 后不再下载；缓存命中时 `make dev-up` 约 21–26 秒（2026-11-08 多次实测 21.5–26s，预算 ≤180s）。
`make dev-down` 约 5 秒（实测 5s）：两个服务已设 `spring.cloud.nacos.discovery.graceful-shutdown-wait-time: 0`，不再有默认 10s 的优雅下线等待，脚本会打印实测耗时。

## 目录结构

```
.
├── pom.xml                  # 父 POM：java 25 + Boot/Cloud/SCA 三个 BOM
├── api-gateway/             # 8080，/api/** -> lb://demo-service（Java 声明路由）
├── demo-service/            # 8081，Nacos discovery + config，GET /demo/hello
├── config/nacos/            # Nacos 种子配置（dev-up 幂等发布）
├── scripts/
│   ├── env-precheck.sh      # JDK/端口/磁盘/网络前置检查
│   ├── nacos.sh             # ensure/start/stop/status
│   ├── dev-up.sh            # 一条命令起环境
│   ├── dev-down.sh          # 逆序停 + 端口校验
│   ├── verify.sh            # 全链路体检
│   └── tests/verify-negative.sh
├── Makefile
├── docs/teamflow/           # 需求产物 / 架构 / 产品记忆
└── .nacos/                  # Nacos 发行包与数据（gitignore，不入库）
```

## 版本矩阵（实测基线）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 25.0.2 | Boot 4 支持 17–25；本机 Homebrew openjdk |
| Spring Boot | 4.0.9 | 父 POM |
| Spring Cloud BOM | 2025.1.4 | `spring-cloud-dependencies` |
| Spring Cloud Alibaba | 2025.1.0.0 | Nacos discovery/config starter |
| nacos-client | 3.1.1 | 由 SCA BOM 传递 |
| Nacos server | 3.3.0 | standalone + 内嵌 Derby |
| 网关 starter | `spring-cloud-starter-gateway-server-webflux` 5.0.4 | 2025.1 线的正式 artefact |
| Maven | 3.9.12 | 本机 |

> 版本不匹配是这套环境最大的坑：**Boot 3.5.x 官方只支持 JDK 17–24**。若必须留在 JDK 21/24，请整套降级，见排障第 4 条。
>
> 上表由 `mvn dependency:tree` 实测核对（2026-11-08）：网关只有 webflux（无 `spring-webmvc`），demo-service 无任何 gateway 构件。改版本必须同时改本表、`docs/teamflow/architecture/ARCHITECTURE.md` §5 与父 POM，三处缺一即视为漂移。

## 常用命令

| 命令 | 作用 |
|---|---|
| `make dev-up` | 起 Nacos + 两个服务，打印 `READY` |
| `make dev-down` | 逆序停止并校验 8080/8081/8848/8849/9848/9849 释放（约 5s，无优雅下线等待） |
| `make verify` | 逐项 PASS/FAIL；端口项明确标注“仅监听”，健康判定基于 HTTP 内容 |
| `make lint` | 静态门禁（不启动服务）：语法、`${VAR}` 插值、公共函数单点、错误提示、文档一致性 |
| `make test` | lint + 构建 + 负向用例（需环境已 up） |
| `make test-negative` | 停 demo-service → verify 必红且指名失败项 → 重启 → 必绿 |
| `make nacos-status` | 查看 Nacos 进程/端口/健康 |
| `make clean` | 清理 Maven 产物与 `.run/`（保留 Nacos 缓存） |
| `make distclean` | 额外删除 `.nacos/`，下次重新下载 |

环境变量覆盖（写入 `.env` 即自动加载）：`GATEWAY_PORT`、`DEMO_PORT`、`NACOS_HTTP_PORT`、`NACOS_CONSOLE_PORT`、`NACOS_GRPC_PORT`、`NACOS_GRPC_TLS_PORT`、`NACOS_SERVER_ADDR`、`NACOS_HOME`、`NACOS_VERSION`、`NACOS_DOWNLOAD_URL`、`NACOS_SHA256`、`NACOS_TOKEN_SECRET`（默认值是**仅本机开发**用的固定串，共享环境请自行覆盖）。

## 排障（按发生概率排序）

**1. Nacos 控制台 8080 与网关端口冲突（必看）**
Nacos 3.x 控制台默认 `8080`，正好撞上网关。`scripts/nacos.sh ensure` 会幂等写入 `nacos.console.port=8849`；如果你手工执行过 `startup.sh`，请先 `make dev-down`，再让脚本重新写配置。控制台地址是 <http://localhost:8849>，不是 8080。

**2. Nacos 没起来 / 第二次启动报数据目录锁**
```bash
tail -n 80 .nacos/logs/startup.log
tail -n 80 .nacos/logs/nacos.log
```
确认 `JAVA_HOME` 指向 JDK 25；若 Derby 数据目录损坏，`make distclean && make dev-up` 重建（会重新下载 233MB）。

**3. Maven 依赖下载失败**
`env-precheck.sh` 会做一次轻量连通性探测。公司网络请在 `~/.m2/settings.xml` 配置镜像；离线但 `~/.m2` 已有依赖时可 `PRECHECK_SKIP_NETWORK=1 make dev-up`。

**4. JDK 版本不符（<25）**
脚本会在启动任何进程前报错，而不是让 Maven 抛裸异常。两条路：
- 推荐：`brew install openjdk@25`，`export JAVA_HOME=$(/usr/libexec/java_home -v 25)`；
- 必须用 JDK 21/24：**整套降级**为 Boot 3.5.16 + Spring Cloud 2025.0.3 + Spring Cloud Alibaba 2025.0.0.0 + Nacos 2.5.5，同时改父 POM、`scripts/nacos.sh` 的 `NACOS_VERSION` 和本文版本矩阵，并注意网关 artefact 名会变。

**5. 端口被占用 / 停不干净**
`make dev-down` 会逆序优雅停止并兜底 `SIGKILL`，最后校验六个端口；仍占用时输出 PID，按提示 `lsof -nP -iTCP:<port> -sTCP:LISTEN` 处理。端口被其他项目长期占用时用环境变量整体偏移。

**6. 体检“全绿”但调用链断了？**
不会。`verify.sh` 的端口项只负责“监听”，服务健康必须命中 `/actuator/health` 的 `UP`，链路项必须同时命中 `instancePort` 与 `configSource=nacos`；`make test-negative` 会停掉 demo-service 验证它确实变红。

**7. 网关返回 503**
先看网关是否含有 `spring-cloud-starter-loadbalancer`（`lb://` 必需，缺失时直接 503），再确认 `demo-service` 在 Nacos 中有健康实例（`make verify` 的对应项）。实测（2026-11-08）：停掉 `demo-service` 后 **178ms** 内同一 URL 即返回 **503**（body 固定为 `{"status":503,...}`）；连接被拒（`ConnectException`）与连接被重置（`PrematureCloseException`）都由网关映射为 503。恢复服务后约 **17s** 回到 200（≤60s）。Nacos 3.3 的配置运维 API 是 `/nacos/v3/admin/cs/config`；`dev-up` 已在 `nacos.sh ensure` 中关闭 admin API 鉴权并写入本地 token/identity，仅限本机开发。

**8. 环境已经在跑，又执行了一次 `make dev-up`**
可重入：阶段 0 会识别 `.run/*.pid` 对应的本项目进程并放行其端口，环境已 READY 时再跑一次 `make dev-up` 仍是 exit 0 + READY（实测）。想重新拉起干净的进程用 `make dev-down` 再 `make dev-up`；想只确认状态用 `make verify` 或 `make nacos-status`。

## 设计要点

- **路由用 Java 声明**（`RouteLocator`）而非 YAML：Gateway 5 的属性命名空间发生过迁移，Java API 是更稳的契约。
- **配置 fail-fast**：`spring.config.import=nacos:demo-service.yml?group=DEFAULT_GROUP` 非 optional，Nacos 不可用则启动失败，避免“假绿”。
- **配置可证伪**：本地 `application.yml` 不定义 `demo.config-source`，默认值 `local`；只有 Nacos 里的种子配置能把它变成 `nacos`。
- **Nacos 仅监听本机**：`nacos.core.auth.enabled=false` 只用于本地开发，请勿暴露公网。
- 更完整的方案与取舍见 [ARCHITECTURE.md](docs/teamflow/architecture/ARCHITECTURE.md)。
