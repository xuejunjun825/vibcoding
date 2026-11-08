<!-- meta: summary="交付一套开箱即跑、可自检的 Spring Cloud + Nacos（JDK 25 / Boot 4.0.9 / Cloud 2025.1.4 / SCA 2025.1.0.0）最小开发环境骨架：Nacos 单机 standalone 由脚本拉起，父工程 + 网关 + 示例服务三模块，一条命令起停、一条 URL 打通 网关→Nacos 注册→服务→Nacos 配置。" -->
基线依赖：无

# PRD · Spring Cloud + Nacos 开发环境（JDK 25 / Boot 4 / Cloud 2025.1）

> 已按用户确认单修订：注册与配置中心改为 **Nacos 单机 standalone**（脚本化下载/启动/停止），不再使用 Eureka + Spring Cloud Config Server；Python 从交付物中**完全移除**（原 Python 相关验收项删除，等价校验改由 Java/Maven + bash 承担）；技术栈与其余默认保持不变。

## 1. 背景与目标

工作区 `/Users/xuejunjun/Documents/deepseek-harness/project-1` 目前只有一个空仓库（基线提交 `6fec40f`）。用户要求“配置完整的开发环境”，技术栈为 **Java Spring Cloud 体系**，交付范围是**“可跑的最小 Spring Cloud 骨架”**，注册/配置中心指定为 **Nacos**。

本需求不是“装一堆软件”，而是交付一个**能被别人从零复现、能自己证明自己是活的**工程基线：一条命令拉起 Nacos + 两个服务、一条 URL 打通“网关 → Nacos 注册 → 服务 → Nacos 配置”、一条命令体检并给出红灯原因、一条负向用例证明体检不会说谎。

已实测的机器现状（2026-11-08）：

| 类别 | 现状 |
| --- | --- |
| JDK | Oracle OpenJDK **25.0.2**（arm64），`JAVA_HOME` 指向 Homebrew `openjdk/25.0.2` |
| 构建 | Maven **3.9.12**（Homebrew）；无 Gradle |
| 运行时 | MySQL 9.6.0；**无 Docker、无 Redis、无 Nacos**、无 SDKMAN |
| 其他 | git 2.54.0、Homebrew 5.0.16、GNU Make 3.81；Python 3.14.3 已装（**仅事实记录，非交付物、非验收依赖**） |
| 端口 | 8080 / 8081 / 8848 实测空闲（8849 / 9848 / 9849 亦需检查） |

### 1.1 版本对齐（本需求的核心约束）

本机 JDK 为 25，而 **Spring Boot 3.5.x + Spring Cloud 2025.0.x 的官方支持范围是 JDK 17–24**；要留在 JDK 25 上，必须使用 **Spring Boot 4.0.x + Spring Cloud 2025.1.x**（Spring Framework 7，支持 JDK 17–25）。已逐项在 Maven Central / GitHub 探测到下列版本存在且互相同线：

| 组件 | 版本 | 依据（实测） |
| --- | --- | --- |
| JDK | 25.0.2（不新增安装） | 本机；Boot 4 官方支持 17–25 |
| Spring Boot | **4.0.9** | Maven Central 4.0.x 最新补丁 |
| Spring Cloud BOM | **2025.1.4** | Maven Central 最新发布 |
| Spring Cloud Alibaba | **2025.1.0.0** | BOM 的 parent 为 `spring-cloud-dependencies-parent:5.0.0`（= Cloud 2025.1.0 线），starter 显式依赖 `spring-boot-actuator-autoconfigure:4.0.0` |
| Nacos 客户端 | **3.1.1** | SCA 2025.1.0.0 BOM 中 `nacos.client.version=3.1.1` |
| Nacos 服务端 | **3.3.0** | GitHub Release（2026-10-29）；其根 POM 为 Spring Boot **4.0.6**、`java.version=17`，可在 JDK 25 运行 |
| 网关 | `spring-cloud-starter-gateway-server-webflux` **5.0.4** | 2025.1.x 线，可下载 |
| Maven | 3.9.12 | 本机 |

参考：[Spring Framework JDK 版本范围](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions)、[Spring Boot / Spring Cloud 版本映射](https://github.com/Azure/azure-sdk-for-java/blob/main/docs/spring/Spring-Versions-Mapping.md)、[Nacos 控制台端口说明](https://nacos.io/en/docs/latest/manual/admin/console/)。

### 1.2 拓扑与端口（3 个 Maven 模块 + 1 个 Nacos 外部进程）

```
api-gateway (8080)  --/api/**  lb://demo-service-->  demo-service (8081)
        |                                                   |
        +----------- 服务注册 / 配置读取 --------------------+
                              |
                        Nacos standalone
                 HTTP 8848 · 控制台 8849 · 客户端 gRPC 9848 · 服务端 gRPC 9849
```

| 端口 | 组件 | 说明 |
| --- | --- | --- |
| 8080 | api-gateway | 用户唯一入口 |
| 8081 | demo-service | 被网关调用的示例服务 |
| 8848 | Nacos HTTP/OpenAPI | 注册与配置的主入口 |
| **8849** | Nacos 控制台 | **Nacos 3.x 控制台默认 8080，与网关冲突** → 启动脚本必须显式改为 8849 |
| 9848 / 9849 | Nacos 客户端 / 服务端 gRPC | 9848 = 8848+1000，Nacos 3.x 客户端必需；停服/体检必须覆盖 |

模块：`pom.xml`（父，`packaging=pom`）+ `api-gateway` + `demo-service`。Nacos 发行包解压到仓库内 **`.nacos/`**（整目录 gitignore），由脚本管理。

### 1.3 目标

1. `make dev-up` 一条命令拉起 Nacos + 网关 + 示例服务并打印可用地址（首次运行自动下载 Nacos 发行包）。
2. `GET http://localhost:8080/api/demo/hello` 经 **网关 → Nacos 注册 → demo-service → Nacos 配置** 返回 200。
3. `make verify` 一条命令体检全环境，红灯时指出**是哪一项**、怎么修；并用负向用例证明它不会假绿。
4. `make dev-down` 停干净（含 Nacos JVM 与 gRPC 端口），可立刻重入。
5. 仓库干净到可直接提交：Nacos 发行包/数据、构建产物、日志、IDE 文件不进版本库。

## 2. 用户故事与验收标准

> AC 编号在本需求内从 AC-1 起，独立于其他需求。

### US-1（P0）一条命令把环境拉起来

作为接手项目的新人，我只想执行一条命令，就得到一个 Nacos + 两个服务全部就绪的环境，不必记住“先 cd 到 nacos/bin 再 startup.sh -m standalone”，也不必手工改控制台端口。

- **AC-1 单命令冷启动**：在 `mvn clean` 后的干净状态执行 `make dev-up`（等价 `scripts/dev-up.sh`），单条命令完成：必要时下载并解压 Nacos 发行包（打印进度与校验和）→ 以 standalone 启动 Nacos → 通过 OpenAPI 幂等写入配置 → 启动 demo-service 与 api-gateway。脚本输出 `READY`，且满足：Nacos 匿名健康检查 HTTP 正常、demo-service 与 api-gateway 的 `/actuator/health` 均 `200/UP`、Nacos 服务列表中存在健康实例 `demo-service`。**耗时**：Nacos 包已缓存时 ≤180s；首次运行（需下载约 233MB 发行包）≤10min。不得要求手工 `export`、手工改端口或手工按顺序敲命令。
- **AC-2 停干净且可重入**：`make dev-down` 后 **10s 内** 8080、8081、8848、8849、9848、9849 无 LISTEN 进程，且无残留 java 进程（含 Nacos）；随后再次 `make dev-up` 成功，日志无 `Address already in use`、无 Nacos/Derby 数据目录锁错误。

### US-2（P0）最小调用链真的走通了 Nacos

作为开发者，我要亲眼看到“网关 → 注册中心 → 服务 → 配置中心”这条链路，而不是三个互不相干的进程各自健康。

- **AC-3 链路真实**：`GET http://localhost:8080/api/demo/hello` 返回 `200`，响应 JSON 同时包含 `instancePort: 8081` 与 `configSource: nacos`；该 `configSource` 的值**只能**来自 Nacos 配置中心（dataId `demo-service.yml`，group `DEFAULT_GROUP`）；同时 Nacos OpenAPI 的服务实例列表中可见 `demo-service` 的健康实例（IP:8081）。
- **AC-4 路由与配置可被证伪**：① 停掉 demo-service 后 **≤30s** 内同一 URL 返回 `503`，重新启动后 **≤60s** 内恢复 `200`；② 只修改 Nacos 中 `demo-service.yml` 的配置值并发布，**≤30s** 内同一 URL 的响应字段随之变化（证明配置来自 Nacos 而非本地 `application.yml`）；③ 对照组：直连 `http://localhost:8081/demo/hello` 在服务停止后必然失败。

### US-3（P0）一条命令体检，红灯说人话，且不说谎

作为环境使用者，服务挂了的时候我要知道挂的是哪个、为什么；更要知道体检本身不是摆设。

- **AC-5 体检覆盖全链路**：`make verify`（bash：`scripts/verify.sh`）输出逐项检查表，至少覆盖：JDK ≥25、Maven 可用、六个端口监听、Nacos 健康与配置可读（OpenAPI 读回 `demo-service.yml` 内容）、两个服务健康端点、AC-3 调用链字段；全绿 `exit 0`。**禁止**仅探测端口 TCP 可达就判定该项通过，健康判定必须基于 HTTP 响应内容。
- **AC-6 负向用例证明不假绿**：`make test-negative`（`scripts/tests/verify-negative.sh`）自动执行：停 demo-service → 断言 `make verify` 返回非零 → 断言输出包含失败项名称（`demo-service`）与修复提示 → 重启并断言恢复全绿。该用例纳入 `make test`，失败即视为本需求不通过。

### US-4（P0）仓库干净，可提交

作为维护者，我不希望第一次提交就把 Nacos 发行包（233MB）、Derby 数据、`target/` 写进历史。

- **AC-7 忽略规则完备**：完整执行“首次 dev-up（含下载解压 Nacos）+ 构建 + 体检”后，`git status --porcelain` 只出现源码与文档改动，不含 `target/`、`.nacos/`（含发行包、`data/`、`logs/`）、`*.log`、`.env`、`.DS_Store`、`.idea/`、`*.iml`。

### US-5（P1）文档能让人从零走通，版本切换有路

- **AC-8 文档闭环**：`README.md` 含“从零到绿灯”步骤（**≤8 条命令**，含首次下载 Nacos 那一条）、实测版本矩阵（与 `mvn dependency:tree`/实际发行包一致）、≥4 条排障（**Nacos 控制台 8080 与网关端口冲突** / Nacos 未就绪 / Maven 依赖下载失败 / JDK 版本不符）；当脚本检测到 JDK <25 时输出**可执行的**切换指引（含 `brew install openjdk@21` + 降级 Boot 3.5 的完整备选组合），而不是让 Maven 抛裸异常。

## 3. 范围与非目标

### 范围（P0，本需求必须交付）

- Maven 父工程 `pom.xml`（`packaging=pom`，统一 `java.version=25`，导入 Boot 4.0.9 / Cloud 2025.1.4 / SCA 2025.1.0.0 三个 BOM）。
- `api-gateway`（8080）：`spring-cloud-starter-gateway-server-webflux` + Nacos discovery，路由 `/api/**` → `lb://demo-service`（StripPrefix=1），暴露 `/actuator/health`。
- `demo-service`（8081）：`spring-cloud-starter-alibaba-nacos-discovery` + `spring-cloud-starter-alibaba-nacos-config`，`spring.config.import=nacos:`（fail-fast），提供 `GET /demo/hello` 返回 `{message, instancePort, configSource}`。
- Nacos 单机：`scripts/nacos.sh`（`ensure` 下载+校验+解压+改控制台端口、`start`、`stop`、`status`），发行包与数据固定在 `.nacos/`。
- `scripts/`：`dev-up.sh`、`dev-down.sh`、`verify.sh`、`env-precheck.sh`、`tests/verify-negative.sh` + `Makefile`（`dev-up / dev-down / verify / test / test-negative / clean`）。
- `config/nacos/demo-service.yml`：配置中心种子数据（由 dev-up 通过 OpenAPI 幂等发布）。
- `README.md`、`.gitignore`、`docs/teamflow/memory.md` 的技术栈约定。

### 非目标

- 不实现业务功能（无用户/订单/数据库表）；MySQL 已安装但**不接入**。
- **不交付任何 Python 工程**：不建 `python/`、不要求 uv/pyproject/pytest，不做 Python 版体检脚本；README 可在“本机环境”表中提及 Python 3.14.3 作为事实，但不得作为验收依赖或“从零到绿灯”的步骤。
- 不做 Nacos 集群/MySQL 持久化（standalone + 内嵌 Derby 即可）、不做鉴权体系、不做灰度/命名空间规划。
- 不做生产部署、K8s、CI/CD、监控告警、限流熔断。
- 不引入 Docker / docker-compose；不安装新的 JDK；不使用 Gradle。

## 4. 反面目标（anti-goals）

**技术上都跑通了、测试都是绿的，但用户仍然会判定这次交付失败的情形：**

1. **假绿灯**：`make verify` 一片 PASS，但把 demo-service 杀掉之后它依然 PASS——因为它只 `nc -z` 探了端口，或只 curl 了网关自己的 `/actuator/health`。用户按绿灯以为环境健康，实际调用链早已断。
2. **冷启动不可复现**：在 A 终端跑通，新开终端或 `mvn clean` 后按 README 走就失败；必须手工 `cd .nacos/bin && sh startup.sh -m standalone`、手工 `export JAVA_HOME`、或每次重新下载 233MB 发行包。用户拿到的不是环境，是一次性的运气。
3. **停不干净**：`make dev-down` 只杀了两个 Spring 进程，Nacos 的 JVM 与 9848 gRPC 还在，第二次 `make dev-up` 报 `Address already in use` 或 Derby 数据目录被锁，脚本却打印“启动成功”。用户学到的教训是“这个环境得重启电脑”。
4. **版本地狱**：pom 里写着 Boot 4 + SCA，第一次构建就炸出 `NoSuchMethodError` 或“找不到 Nacos 自动配置”，README 只说“已支持 Boot 4”。用户花两小时怀疑自己装错了 Java，而真实原因是没有一个被实际验证过的版本组合。
5. **Nacos 形同虚设**：环境“跑起来了”，但示例服务的配置仍从本地 `application.yml` 读、网关把地址写死成 `http://localhost:8081` 而没走 `lb://`；停掉 Nacos 后调用依旧 200，改 Nacos 里的配置毫无反应。用户看到的是一个假装用了注册/配置中心的玩具。

> 这 5 条均可由第三方在 10 分钟内复现检查：杀 demo-service 跑 verify、换终端重跑、dev-down 后 `lsof -i`、用本机 JDK 构建、停 Nacos 或改配置观察调用链。

## 5. 交互流程摘要

```
make dev-up
  ├─ env-precheck：JDK(≥25) / Maven / 端口 8080,8081,8848,8849,9848,9849 / 磁盘 ≥1.5GB
  ├─ nacos ensure：未缓存则下载 nacos-server-3.3.0.tar.gz（≈233MB，打印进度 + sha256 校验）→ 解压到 .nacos/
  │                 幂等写入 conf/application.properties：nacos.console.port=8849
  ├─ nacos start：bin/startup.sh -m standalone → 轮询 Nacos 健康 OpenAPI（≤90s）
  ├─ 配置种子：OpenAPI 幂等发布 demo-service.yml（DEFAULT_GROUP）
  ├─ demo-service：java -jar（spring.config.import=nacos:，注册到 Nacos）→ 轮询 /actuator/health
  ├─ api-gateway：java -jar（lb://demo-service）→ 轮询 /actuator/health
  ├─ 就绪判定：两个 health=UP + Nacos 实例列表含 demo-service + 冒烟一次 /api/demo/hello
  └─ 打印 READY + 网关地址 / Nacos 控制台(8849) / 三条可试命令

make verify   → 表格 + exit code（任何一项红则非零）
make test-negative → 停 demo-service → 断言 verify 非零且指名失败项 → 恢复 → 断言全绿
make dev-down → 逆序停止（gateway → demo-service → nacos stop.sh）→ 兜底 SIGTERM → 校验六个端口已释放
```

关键行为约定：
- **前置检查早于一切**：JDK 版本、端口占用、磁盘空间在拉起任何进程之前报错，且报错文本给出修复命令。
- **就绪判定用 HTTP 语义**，不用 `sleep 30 && 假设好了`；Nacos 必须用匿名可访问的健康/配置 OpenAPI，**绝不把 302 登录页当 READY**。
- **配置读取 fail-fast**：demo-service 用非 optional 的 `nacos:` 导入，Nacos 未就绪就直接失败并打印最近日志——宁可红，不可假绿。
- **停机优先优雅**：先让服务发送 deregister，再 `nacos stop.sh`，最后兜底 kill 并校验端口。

## 6. 优先级

| 优先级 | 内容 | 说明 |
| --- | --- | --- |
| **P0** | 父工程 + 2 模块可构建；Nacos standalone 脚本化；AC-1、AC-2 | 没有它就没有“环境” |
| **P0** | 链路贯通 Nacos 注册 + 配置；AC-3、AC-4 | 决定这是不是 Spring Cloud 环境 |
| **P0** | `make verify` + 负向用例；AC-5、AC-6 | 决定“完整性可验证”是否成立 |
| **P0** | `.gitignore` 完备 + README 从零步骤；AC-7、AC-8 | 决定别人能否复现 |
| **P1** | `make test` 聚合（构建 + 负向用例）；失败时自动 tail 相关日志到 `logs/`（已被忽略） | 提升可用性，不阻塞验收 |
| **P2** | Nacos 集群/MySQL 持久化、命名空间与鉴权、可选 docker-compose 变体 | 后续需求，不做验收 |

## 7. 依赖与风险

### 7.1 Spring Cloud Alibaba / Nacos 客户端 与 Boot 4 的兼容性（必须显式声明，不得静默更换）

- **事实**：`com.alibaba.cloud:spring-cloud-alibaba-dependencies:2025.1.0.0` 的 parent 是 `spring-cloud-dependencies-parent:5.0.0`（Spring Cloud 2025.1.0 线），其 Nacos starter 显式依赖 `spring-boot-actuator-autoconfigure:4.0.0`，BOM 内 `nacos-client 3.1.1`。即：**存在面向 Boot 4.0 / Cloud 2025.1 的 SCA 版本**，与 Cloud BOM 2025.1.4 同线可用。
- **服务端**：Nacos **3.3.0** 根 POM 为 Spring Boot 4.0.6、`java.version=17`，因此 JDK 25 在其支持区间（Boot 4 支持 17–25）。
- **风险**：SCA 2025.1.0.0 是较新版本，Boot 4 的自动配置/Jackson 3 适配仍可能有缺口（例如 Nacos 配置刷新、`spring.config.import` 解析）。**若实测失败，按以下顺序降级，并在 README 与提交信息中显式写明，禁止静默改用其他版本**：
  1. 首选（不降 Boot）：SCA 2025.1.0.0 + Nacos 3.3.0 + Boot 4.0.9 + Cloud 2025.1.4；
  2. 次选（不降 Boot）：SCA 2025.1.0.0 + Nacos 3.2.x/3.1.2（与客户端 3.1.1 更贴近）；
  3. 兜底（必须降 Boot）：Boot 3.5.16 + Cloud 2025.0.3 + SCA 2025.0.0.0 + Nacos 2.5.5，**同时需要安装 JDK 21/24**（本机没有）。选择此路必须：PRD/README 显式标注、`brew install openjdk@21` 写入从零步骤、AC-8 的命令数放宽到 ≤9 条。

### 7.2 其他风险

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| Nacos 控制台默认 8080，与网关冲突 | 启动即端口冲突或控制台不可用 | `nacos.console.port=8849` 由脚本幂等写入；README 置顶排障第一条 |
| 首次需下载 233MB 发行包 | 首次体验慢/网络失败 | 打印进度与 sha256；`.nacos/` 缓存命中则不下载；提供手动放置说明（校验和固定） |
| 本机无 Docker，Nacos 只能 standalone + 内嵌 Derby | 数据目录锁、重启异常 | 数据固定在 `.nacos/data`；dev-down 必须优雅停；异常时提示删除 data 重建并记录 |
| 六个端口被其他项目占用 | 启动随机失败 | 前置检查明确报错 + 列出占用 PID/进程 + 支持 `GATEWAY_PORT` 等环境变量覆盖 |
| Maven Central 可达性（今日探测 200，可能受公司代理影响） | 依赖下载失败 | `env-precheck` 做一次轻量依赖探测，失败提示配置 `~/.m2/settings.xml` 镜像 |
| Nacos 3.3 默认开启控制台登录 | 体检误判“控制台没起来” | 体检只断言匿名可访问的健康/配置 OpenAPI；登录页 302 不算 READY，也不断言登录态 |
| demo-service 关闭后 Nacos 实例剔除延迟 | AC-4 的 30s 窗口 | 优雅关闭触发 deregister；必要时调小心跳/剔除间隔并在脚本中等待实例消失 |

## 8. 里程碑建议

| 里程碑 | 内容 | 验收信号 |
| --- | --- | --- |
| M1 Nacos 可控（0.5d） | `scripts/nacos.sh`：下载/校验/解压/改端口/start/stop/status | 手工 `nacos.sh start` 后 8848 OpenAPI 可读、8849 控制台可开、`stop` 后端口清空 |
| M2 骨架可构建（0.5d） | 父 POM + 2 模块 + 三个 BOM | `mvn -q -DskipTests package` 成功，`mvn dependency:tree` 中 Boot/Cloud/SCA 版本与矩阵一致 |
| M3 链路贯通（0.5d） | 注册 + 配置 + 网关路由 | 手工验证 AC-3、AC-4（含改配置生效、停服 503） |
| M4 一键化（0.5d） | `dev-up/dev-down/env-precheck` + Makefile | AC-1、AC-2 通过；连续 3 次冷启动稳定 |
| M5 体检与收口（0.5d） | `verify.sh` + 负向用例 + README + `.gitignore` | AC-5~AC-8 通过；`git status` 干净；提交前附 verify 输出 |

## 9. 工程约束

> 以下为工程动作，按原样执行，不得改写或省略。

- **分支**：当前分支为 `feat/springcloud-env-setup`（基线提交 `6fec40f chore: baseline before teamflow run`）。本需求**在该分支上开发**，不新建分支、不切回 `main`。
- **未提交改动**：`docs/` 为未跟踪目录（本需求产物夹），处理方式：**随本次需求一并纳入提交**，不 stash、不回滚、不删除。
- **提交时机**：验收通过后由收尾阶段统一提交，提交信息建议 `feat: springcloud dev environment skeleton with nacos (JDK 25 + Boot 4 + Cloud 2025.1)`；提交前必须跑一次 `make verify` 与 `make test-negative` 并附结果。
- **`.gitignore` 补全（开发阶段必须完成，按本项目真实工具链）**：Java/Maven：`target/`、`*.class`、`*.jar`、`.flattened-pom.xml`；Nacos 发行包与运行数据：**`.nacos/`**（含解压目录、`data/`、`logs/`、下载的 `*.tar.gz`）；IDE：`.idea/`、`*.iml`、`*.ipr`、`.vscode/`；本地环境与日志：`.env`、`.env.local`、`*.log`、`logs/`（`logs/teamflow/` 已忽略，不得删除该规则）；macOS：`.DS_Store`。**不得**忽略 `config/nacos/` 下的种子配置（必须入库）。
- **不引入 Docker 依赖**：P0 链路的任何一步不得要求 Docker；Nacos 以 standalone 发行包运行。
- **不交付 Python**：不得创建 `python/`、`pyproject.toml`、`.venv/` 等交付物（`.gitignore` 也无需为此扩充）。
- **不改动 AGENTS.md**：团队记忆写入 `docs/teamflow/memory.md`（替换同主题行，不追加变更日志）。

## 10. 假设与待澄清

- **（事实）Maven Central / GitHub Release 可直连**：今日探测 `repo1.maven.org` 与 Nacos GitHub Release 均返回 200。若公司网络需代理/私有镜像，需在 TECH 阶段前提供 `~/.m2/settings.xml` 与 Nacos 包内网地址，否则 AC-1 会因下载失败而不达成。
- **（事实）端口长期空闲**：假设 8080/8081/8848/8849/9848/9849 不被其他项目占用（8080/8081/8848 已实测空闲）。若被占用，脚本支持环境变量覆盖，但 README 默认值不变。
- **（事实）Nacos 3.3 控制台登录**：假设 3.3.0 的控制台鉴权不阻塞匿名健康检查与 OpenAPI 读写；若实测必须登录才能读写配置，则 dev-up 需先完成管理员密码初始化并携带 token，或改用 `nacos.core.auth.enabled=false`（仅本机）——该取舍需在 TECH 阶段实测后确认。
- **（口味）示例服务的业务语义**：默认 `GET /api/demo/hello` 返回 `{message, instancePort, configSource}`，只证明链路，不接数据库。若希望顺带验证 MySQL 9.6（已装），则 demo-service 需加 JPA/MyBatis + 建库脚本，AC-3 增加“数据可读”，范围约 +1d。
- **（口味）Nacos 鉴权开关**：默认**本地关闭鉴权**（`nacos.core.auth.enabled=false`，仅监听本机），换取开箱即跑；若你更看重“环境即演练”，则开启鉴权并把 token 写入 `.env`（已被忽略），AC-1 增加一步凭证初始化、README 增加一条命令。
- **（口味）Nacos 发行包获取方式**：默认 **dev-up 自动下载 + sha256 校验 + `.nacos/` 缓存**；备选是仓库只放脚本、由用户手动下载后放入 `.nacos/`（首次体验更慢但仓库更“干净”），或把固定版本的包放进内网制品库。
