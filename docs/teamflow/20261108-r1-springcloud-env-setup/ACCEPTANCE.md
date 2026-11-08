# 验收报告 · Spring Cloud + Nacos 开发环境骨架

| 项 | 值 |
|---|---|
| 需求 | 20261108-r1-springcloud-env-setup（PRD 8 条 AC） |
| 任务夹 | `docs/teamflow/20261108-r1-springcloud-env-setup/` |
| 分支 / 基线 | `feat/springcloud-env-setup` · 基线提交 `6fec40f` |
| 验收环境 | macOS (Apple Silicon) · JDK 25.0.2 · Maven 3.9.12 · Nacos 3.3.0 standalone · 沙箱内 `~/.m2` 只读，走 `.m2/repository` 回退 |
| 验收时间 | 2026-11-08 20:47–21:00 CST |
| 验收方式 | 不采信 QA 结论，独立重跑交付路径 + 逐条手测 AC-4 + 静态复核架构蓝图 |

## 1. 结论

**通过。** PRD 的 8 条 AC 全部在真实栈上由验收人独立复验通过（缓存态冷启动 34s、`make verify` 14/14、负向用例停服必红且指名、恢复 4s、`make dev-down` 5s、热配置 1s 生效）。实现与注入的架构蓝图一致：三模块边界、依赖面、Java 路由、配置键、单点函数均落地，未发现重复实现、适配漂移或应抽未抽。QA 报告 OPEN 缺陷 0 条，验收环节未新增 P0–P2。

## 2. 独立复验证据（本轮实跑）

| 命令 / 动作 | 实测结果 |
|---|---|
| `make dev-up`（`.nacos/` 已缓存） | exit 0，34.3s，输出 `READY`；两个 `/actuator/health` UP；Nacos 实例列表含 `demo-service:8081`；链路 `{"message":"Hello from Nacos","instancePort":8081,"configSource":"nacos"}` |
| `make verify` | exit 0，**14 PASS / 0 FAIL**；#3–#8 标注「仅监听，不代表健康」，#9–#14 均为 HTTP body 判定 |
| `make test-negative` | exit 0，总耗时 4s；停 demo-service 后 verify exit 1、**4 个失败项逐一指名 demo-service** 并给修复命令，重启后 14/14 恢复 |
| AC-4 手测（验收人自建探针） | 停服后**首个请求 0s 即 503**；直连 8081 得 `000`；重启后 **2s** 恢复 200；改 Nacos `greeting` 后 **1s** 响应变为 `Acceptance Probe`；回滚种子后 **1s** 恢复 `Hello from Nacos` |
| `make dev-down` | 5.4s（≤10s），六端口 8080/8081/8848/8849/9848/9849 全无 LISTEN，无残留 java（含 Nacos） |
| `make dev-up` 重入 → `make dev-down` | 39.3s 再次 READY、无 `Address already in use`/Derby 锁；二次 down 5.4s 再次清空六端口 |
| `make lint` | 9/9 通过（语法、`$VAR` 插值、`own_jvm_pids`/`kill_own_jvms`/`chain_ok` 单点、Nacos 配置 URL 单点、token 可覆盖、下载失败提示、文档一致） |
| `qa-ac8-docs.sh` | 5/5 文档断言 + 版本比对全中：gateway 5.0.4 / loadbalancer 5.0.4 / nacos-client 3.1.1 / Boot 4.0.9 / Cloud 2025.1.4 / SCA 2025.1.0.0 |
| `git status --porcelain` | 10 行（` M .gitignore` + 9 项源码/文档未跟踪）；`target/`、`.nacos/`、`.run/`、`.m2/`、`logs/`、`*.log`、`.env`、`.DS_Store`、`.idea/`、`*.iml` 命中 **0**；种子 `config/nacos/demo-service.yml` 未被忽略 |

## 3. AC 逐条验收

| AC | 判据 | 证据 | 判定 |
|---|---|---|---|
| AC-1 单命令冷启动 | 一条命令起 Nacos+两服务并 `READY`；缓存 ≤180s，首次含下载 ≤10min | 缓存态本轮 34.3s（重入 39.3s）；首次含 233MB 下载 QA 轮实测 373s（D1 修复仅改错误提示文本，成功路径未变） | 通过 |
| AC-2 停干净可重入 | down 后 10s 内六端口无 LISTEN、无残留 java；再 up 无端口/Derby 错误 | down 5.4s/5.4s；两轮 up/down 无 `Address already in use`、无 Derby 锁；`.run` 陈旧 PID 已被 `drop_stale_pids` 处理 | 通过 |
| AC-3 链路真实 | `:8080/api/demo/hello` 200 且 `instancePort=8081`、`configSource=nacos`；Nacos 可见健康实例 | 响应逐字命中；verify #10 断言本地 yml 不定义 `config-source`，verify #11 读 OpenAPI 健康实例 8081 | 通过 |
| AC-4 可证伪 | 停服 ≤30s 503、重启 ≤60s 恢复、改配置 ≤30s 生效、直连对照失败 | 0s 得 503（非 500）、2s 恢复 200、1s 热更新、直连 `000`；回滚后 1s 复原 | 通过 |
| AC-5 体检全链路 | 逐项表覆盖 JDK/Maven/六端口/Nacos 健康与配置/两 health/调用链，全绿 exit 0，禁止只探端口 | 14/14，端口项显式声明「不代表健康」，健康/配置/实例/链路全部命中 HTTP body | 通过 |
| AC-6 负向用例 | 停服→verify 非零且含失败项与修复提示→重启→恢复全绿；纳入 `make test` | 4 个失败项均指名 demo-service 并打印修复命令；恢复 14/14；QA 轮 `make test`（lint+build+negative）exit 0 | 通过 |
| AC-7 忽略规则 | 全流程后 `git status` 无 target/.nacos/*.log/.env/.DS_Store/.idea/*.iml | 产物均在盘上而 status 命中 0；`git check-ignore` 8/8 命中，种子配置未被误伤 | 通过 |
| AC-8 文档闭环 | README ≤8 条命令、版本矩阵与 dependency:tree 一致、≥4 条排障（含 8080 冲突）、JDK<25 可执行指引 | 从零 6 条命令；排障 8 条含「Nacos 控制台 8080 与网关冲突」；假 `java 21` 触发 precheck 输出 `brew install openjdk@21` + `java_home -v 21` + Boot 3.5.16 完整降级组合；依赖解析值与矩阵一致 | 通过 |

## 4. 架构与蓝图一致性（M3 质量门）

- **父 POM**：单点定义 `java.version=25`、Boot parent `4.0.9`，import Cloud `2025.1.4` 与 SCA `2025.1.0.0` 两个 BOM；子模块零版本号，版本矩阵无漂移入口。
- **模块边界**：`api-gateway` = webflux gateway + loadbalancer + nacos-discovery + actuator（不含 webmvc）；`demo-service` = webmvc + nacos discovery/config + actuator（不含任何 gateway 构件）。`lb://` 的运行时前提 loadbalancer 已显式声明。
- **路由与配置**：`ApiGatewayApplication.demoRoutes()` 为 `/api/**` + `stripPrefix(1)` → `lb://demo-service`（Java RouteLocator）；端口/注册名/Nacos 地址/config import/actuator 暴露面/`graceful-shutdown-wait-time: 0` 与 TECHNICAL §3 逐字一致；`DemoController(String greeting, String configSource, int instancePort)` 与蓝图签名一致，`configSource` 缺省 `local` 且本地 YAML 不定义该键 —— 假绿通道被结构性关闭。
- **重复实现收敛**：`own_jvm_pids`/`kill_own_jvms`/`chain_ok` 仅 `scripts/lib.sh` 定义（lint L3）；Nacos v3 配置 URL 仅 `lib.sh:nacos_config_base` 生成，`verify.sh`/`dev-up.sh` 均调 `nacos_config_url`（lint L4）；Makefile 无内联复杂逻辑，`clean` 复用 `detect_maven_repo`。
- **结论**：符合蓝图，未发现应抽未抽的重复实现或适配漂移，无架构返工项。

## 5. 缺陷与观察

- QA 轮 D1–D7 全部闭合复验通过（下载失败提示、README/ARCHITECTURE 旧耗时、503 错误映射、脚本分层、token 可覆盖、陈旧 PID），本轮 OPEN 0。
- QA 报告的 8849 单次 curl 竞态已在其检查器内改为 ≤15s 有界轮询（69/69），属检查器问题，非交付缺陷；t2-ac4-e2e 一次偶发外部 SIGTERM 重跑 9/9，非产品问题。
- **P3（文档一致性，非阻塞）**：`docs/teamflow/memory.md:27` 写「从零到绿灯五条命令」，README 实际为 6 条（均 ≤ AC-8 的 8 条）。建议下次维护记忆时改为「六条」，不影响验收。
- 运行日志偶见 JDK 25 `Restricted methods will be blocked...` WARNING（Netty native access），不影响启动/健康/链路，属 JDK 迁移提示而非缺陷。

## 6. 人工复核清单（环境限制，非阻塞）

| # | 项 | 判据 | 说明 |
|---|---|---|---|
| 1 | 首次冷启动含 233MB 下载 | ≤10min | 验收未删除 `.nacos/` 重跑；QA 轮实测 373s，同一代码路径 |
| 2 | Nacos 控制台 UI | 8849 `/next/` 可登录、实例可见 | 沙箱无浏览器；脚本侧 302 + OpenAPI 200 已佐证 |
| 3 | 公司代理 / `settings.xml` | 不改代码仍可 `make dev-up` | 需目标网络环境执行 |
| 4 | 连续 20 轮 up/down 长稳 | 无 Derby 锁/端口泄漏/JVM 残留 | 建议发版前在目标机压测 |
| 5 | 替换 demo-service 为真实业务 | `make verify` 仍 14/14 | 按 README 步骤操作 |

## 7. 编号说明

state 索引仍残留 AC-9/AC-10（Python/Eureka 时代编号）。PRD 修订后交付物不含 Python，本报告与 QA 报告一致按 PRD 的 **8 条 AC** 验收，编号从 AC-1 起。

## 8. 交接

环境当前 **STOPPED**：六端口无 LISTEN、无残留 JVM；`.nacos/` 缓存保留（下次 `make dev-up` 约 34s）。本轮验收未执行 git 提交/合并；工作区 10 项改动为预期交付面。

验收结论：✅ 通过
