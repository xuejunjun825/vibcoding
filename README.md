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

## 平台层（DevOps 研发管理，M1–M5）

在 r1 骨架之上叠一层多租户研发管理：需求状态机与审批流、需求池/版本/发布准入、缺陷闭环、追溯矩阵、用例版本与测试计划、外部 CI 上报与测试门禁。r1 的四条命令与 `/api/demo/**` 链路**一行不改**，平台命令在其之上叠加。

```bash
# 1) 幂等拉起：Nacos+网关+demo（r1 的 dev-up）→ 建库 → 两服务 → 前端
make platform-up          # req-service 8082 / deliver-service 8083 / web 5173

# 2) 平台全量验收：AC-1..AC-20 逐项断言 HTTP + DB 计数
make platform-verify

# 3) 负向用例：15 条"必须被拒且不落库"
make platform-test-negative

# 4) 种子幂等：连跑两次计数不变，10 个账号都能登录
make platform-seed

# 5) 单元测试（状态机全表 / 权限矩阵 / JWT / HMAC / 指标公式）
make platform-unit

# 6) 性能基线（10 万需求、六项 P95 阈值），结论写 logs/teamflow/<runId>/perf-baseline.md
make platform-perf

# 7) 平台栈停止（只关本项目的进程）
make platform-down
```

`make test` = 静态门禁 → 单元测试 → r1 四步 → 平台 up/verify/negative/seed（跑两遍），任一环节红即整体红。

- 端口：网关 `8080`、demo `8081`、**req `8082`**、**deliver `8083`**、Nacos `8848/8849`、**web `5173`**
- 数据库：MySQL `127.0.0.1:3306/devops_platform`（可用 `PLATFORM_DB_*` 覆盖）
- 登录：`t1/admin/Admin@12345`（种子创建；其余 9 个账号见 `make platform-seed` 输出）
- 开放 API：`POST /openapi/v1/test-executions`，`X-App-Key` + `X-Timestamp` + `X-Signature`（HMAC-SHA256，canonical 见下）

签名串（逐字节一致，脚本与 CI 客户端共用）：

```
METHOD\nPATH\nAPP_KEY\nTIMESTAMP\nsha256_hex(body)
```

```bash
BODY='{"run_id":"run-1","case_id":1,"result":"PASS","duration_ms":12,"executed_at":"2026-11-09T00:00:00+08:00"}'
TS=$(date +%s); DIGEST=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $NF}')
SIG=$(printf 'POST\n/openapi/v1/test-executions\n%s\n%s\n%s' "$APP_KEY" "$TS" "$DIGEST" \
      | openssl dgst -sha256 -hmac "$APP_SECRET" -hex | awk '{print $NF}')
curl -s -X POST http://localhost:8080/openapi/v1/test-executions \
  -H "X-App-Key: $APP_KEY" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H 'Content-Type: application/json' -d "$BODY"
```

## 目录结构

```
.
├── pom.xml                  # 父 POM：java 25 + Boot/Cloud/SCA 三个 BOM + 六个模块
├── api-gateway/             # 8080，/api/** -> lb://demo-service（Java 声明路由）
│                            #        + /api/req/**、/api/deliver/**、/openapi/** 与 JWT 过滤器
├── demo-service/            # 8081，Nacos discovery + config，GET /demo/hello
├── platform-contract/       # 纯 Java 契约：权限矩阵/状态表/指标公式/JWT/HMAC/门禁判定（零 Spring）
├── platform-common/         # Servlet 运行时：租户上下文/鉴权/强制 scope 的 ScopedJdbc/审计/编号
├── req-service/             # 8082，需求/审批/版本/发布单 + Flyway 迁移（唯一迁移者）+ 种子
├── deliver-service/         # 8083，缺陷/任务迭代/用例/计划/门禁/追溯/指标/开放 API
├── web/                     # 5173，Vue 3 + Element Plus + Vite（需求域/交付域/分析域/设置）
├── config/nacos/            # Nacos 种子配置（dev-up 幂等发布）
├── scripts/
│   ├── env-precheck.sh      # JDK/端口/磁盘/网络前置检查
│   ├── nacos.sh             # ensure/start/stop/status
│   ├── dev-up.sh            # 一条命令起环境
│   ├── dev-down.sh          # 逆序停 + 端口校验
│   ├── verify.sh            # 全链路体检（r1 14 项）
│   ├── lib.sh               # 唯一公共库（进程/端口/HTTP/DB/登录/内部接口）
│   ├── platform-up.sh       # 平台栈拉起（叠加在 dev-up 之上，幂等）
│   ├── platform-down.sh     # 平台栈停止（只关本项目的进程）
│   ├── platform-verify.sh   # AC-1..AC-20 分组断言（HTTP + DB 计数）
│   ├── platform-test-negative.sh  # §8.3 的 15 条负向用例
│   ├── platform-seed.sh     # 种子幂等：连跑两遍 + 10 账号登录
│   ├── platform-unit.sh     # 四个模块的单元测试（真实计数，拒绝假绿）
│   ├── platform-perf.sh     # AC-20 六项性能基线（p50/p95 + EXPLAIN）
│   ├── platform-web.sh      # 前端 install/build/preview
│   ├── test-all.sh          # make test 的唯一编排入口
│   └── tests/               # verify-negative.sh + lint.sh + platform/
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
| `make quality-verify` | M6 质量域验收 q0..q7（需 `make platform-up`） |
| `make integration-verify` | M7 集成对接验收 s0..s7：本地四合一桩 + ≥48 断言（需 `make platform-up`） |
| `make clean` | 清理 Maven 产物与 `.run/`（保留 Nacos 缓存） |
| `make distclean` | 额外删除 `.nacos/`，下次重新下载 |

环境变量覆盖（写入 `.env` 即自动加载）：`GATEWAY_PORT`、`DEMO_PORT`、`NACOS_HTTP_PORT`、`NACOS_CONSOLE_PORT`、`NACOS_GRPC_PORT`、`NACOS_GRPC_TLS_PORT`、`NACOS_SERVER_ADDR`、`NACOS_HOME`、`NACOS_VERSION`、`NACOS_DOWNLOAD_URL`、`NACOS_SHA256`、`NACOS_TOKEN_SECRET`（默认值是**仅本机开发**用的固定串，共享环境请自行覆盖）。

## 集成对接（M7：Jenkins / GitLab / Sonar / Nexus）

四个外部系统通过**同一出站客户端**接入（SSRF 白名单、超时、指数退避重试、脱敏、每次尝试一行 `integration_sync_log`），适配器只做协议映射。**默认全部关闭**：关闭时准入后三项为 `CLOSED`、后台不产生任何外呼；测试桩只在 `make integration-verify` 里存在，产品代码不知道它的存在。

### 四系统接入要点

| 系统 | `baseUrl` 示例 | 最小权限 | `extra` 字段 | 回调 | 出站白名单 |
|---|---|---|---|---|---|
| Jenkins | `https://jenkins.example.com` | Job/Read（轮询）+ 目标 Job 的 Build（触发） | `{"jobName":"demo-job"}` | `POST /api/integration/webhooks/jenkins/{configId}`，头 `X-Jenkins-Signature: sha256=<hex>`、`X-Jenkins-Timestamp`（±300s） | `jenkins.example.com` |
| GitLab | `https://gitlab.example.com` | `read_api`（读 MR/审批；`api` 亦可） | `{"projectPath":"group/project"}` | `POST /api/integration/webhooks/gitlab/{configId}`，头 `X-Gitlab-Token`（Webhook Secret） | `gitlab.example.com` |
| Sonar | `https://sonar.example.com` | 项目 Browse + 质量门读取 | `{"projectKey":"demo-key"}`（可被版本的默认 key 覆盖） | 无回调，后台每 30 分钟刷新 | `sonar.example.com` |
| Nexus | `https://nexus.example.com` | 仓库读（`nx-repository-view-*-*-read`） | `{"repository":"releases"}` | 无回调，登记/晋级时校验 sha256 | `nexus.example.com` |

- 凭据**只写不读**：写入后接口只回 `hasCredential` 与末四位；空串表示“不修改”，清除需按系统名二次确认。
- 加密密钥来自 `INTEGRATION_CREDENTIAL_KEY`（`make platform-up` 首次生成 `.run/integration.key`，已 gitignore），未设置时回落 `PLATFORM_MASTER_KEY`；两者皆空则 req-service 拒绝启动。
- 回调路径是网关唯一匿名前缀（`/api/integration/webhooks/**`），按 configId 限流 20 req/s；验签失败 401 且不写业务行。
- 出站白名单用逗号分隔主机（可带端口），**回环地址默认被拒绝**——本地桩必须显式登记 `127.0.0.1:19090`。

### 桩与真实环境的差异（本地桩仅用于验收，不代表真实系统行为）

| 维度 | 本地桩 `127.0.0.1:19090` | 真实系统 |
|---|---|---|
| 传输与证书 | 明文 HTTP、回环地址 | HTTPS + 证书链/企业 CA 校验 |
| 认证 | 固定 token，服务端不校验凭据 | PAT / API Token / Jenkins crumb / Basic，权限随账号 |
| Jenkins 构建 | 立即返回 queueId，控制面 `POST /__stub/jenkins/build` 改状态 | 真实排队、执行、日志与不稳定网络 |
| Jenkins 回调签名 | 测试脚本用共享 secret 现算 HMAC | Jenkins 插件/共享库签名，时钟可能漂移 |
| GitLab 审批数 | 固定 `approved_by`，不跑审批规则 | 需 approvals API 与真实审批规则/CODEOWNERS |
| Sonar 度量 | 控制面注入固定五度量与质量门状态 | 真实扫描结果，覆盖率随提交变化 |
| Nexus 制品 | 固定 3 条搜索结果与固定 sha256 | 真实仓库元数据、checksum 与代理缓存 |
| 故障注入 | `mode 500/429/timeout/slow` 精确可控 | 限流、超时、5xx 由网络与对端决定 |

### 本地开启步骤

```bash
make platform-up                 # 生成密钥、注入 INTEGRATION_CREDENTIAL_KEY 与回环白名单
make integration-verify          # 起桩 → 串跑 s0..s7，末行 #SUMMARY integration pass/total/fail
```

手工验证时：`export INTEGRATION_CREDENTIAL_KEY=$(cat .run/integration.key)`、`export INTEGRATION_EGRESS_ALLOW_HOSTS=127.0.0.1:19090`，再 `POST /api/req/integration/configs` 建档、`POST /api/req/integration/configs/{id}/test` 测连通，最后在 `/admin/integrations` 打开对应系统。真实环境请把白名单改成实际主机并把凭据换成最小权限账号。

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
