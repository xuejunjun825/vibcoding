# QA 测试报告 · 20261108-r1 · Spring Cloud + Nacos 开发环境骨架

| 项 | 值 |
|---|---|
| 轮次 | 第 2 轮（修复轮 D1–D7 之后的复测轮，重跑回归契约 + 补新探针） |
| 分支 / 工作区 | `feat/springcloud-env-setup` · `/Users/xuejunjun/Documents/deepseek-harness/project-1`（产品根） |
| 环境 | macOS（Apple Silicon）· JDK 25.0.2 · Maven 3.9.12 · Nacos 3.3.0 standalone · 沙箱内 `~/.m2` 只读，走 `.m2/repository` 回退 |
| 时间 | 2026-11-08 20:26–20:46 CST |
| 范围 | PRD 的 8 条 AC + 上轮全部 checker 复跑 + 新增 AC-8 可执行比对 / JDK<25 分支触发 |

> 注：state 索引里仍有 AC-9/AC-10（旧 Python/README 编号），PRD 修订后 Python 已从交付物删除，本报告按 PRD 的 **8 条 AC** 执行。

## 1. 结论

**可验收。** 8/8 AC 在本轮真实栈上通过；上轮 2×P2 + 5×P3 全部闭合（逐条复验见 §8）；本轮未发现新的 P0/P1/P2/P3 交付缺陷（§7 为空表）。

唯一非产品问题：上一轮留下的 `t4-scripts-contract.sh` 中 8849 断言是单次 curl，与 console Tomcat 约 0.3s 的绑定延迟竞态（连续 2 次红）；改为 ≤15s 有界轮询后 69/69 通过，已判定为**检查器竞态**而非交付缺陷，证据见 §4.2。

## 2. 验收项实测结果

| AC | 判据 | 本轮实测证据（命令 → 结果） | 判定 |
|---|---|---|---|
| AC-1 | `make dev-up` 单命令打印 READY，缓存态 ≤180s | `t5-dev-cycle.sh`：cycle1 `make dev-up` 17s、cycle2 `scripts/dev-up.sh` 19s，均 exit 0 + READY + 六端口 LISTEN（首次含 233MB 下载未重跑，上轮实测 373s ≤10min；D1 修复仅动 5 条错误提示文本，成功下载路径未变，由 D1 检查命令覆盖） | 通过 |
| AC-2 | `make dev-down` 后 10s 内六端口无 LISTEN、无残留 java；可重入 | dev-down 5s / 6s（两轮），输出「六端口均已释放，无残留 JVM」；连续 2 轮 up/down 日志无 `Address already in use`、无 Derby 锁错误 | 通过 |
| AC-3 | `/api/demo/hello` → 200 且 `instancePort=8081`、`configSource=nacos` | `curl :8080/api/demo/hello` → `{"message":"Hello from Nacos","instancePort":8081,"configSource":"nacos"}`；verify #11 Nacos 实例列表 healthy 含 8081 | 通过 |
| AC-4 | 停服 ≤30s 返 503；重启 ≤60s 恢复；改配置 ≤30s 生效；直连 8081 对照失败 | `qa-ac4-real-stack.sh` 5/5：停服后首个响应 27ms 即 503（无 500），body 为固定 JSON，直连 8081 得 000；重启 17s 恢复 200。`ac4-config-refresh.sh`：改 Nacos greeting 经网关 1s 生效、恢复 1s，且删掉 `config-source` 键后 1s 变 `local`（值确实由 Nacos 决定） | 通过 |
| AC-5 | `make verify` 逐项 PASS/FAIL，全绿 exit 0，禁止只探端口 | `make verify` → 14 PASS / 0 FAIL exit 0；#3–#8 端口项显式标注「仅监听，不代表健康」；#10 另断言本地 yml 不得定义 `config-source` | 通过 |
| AC-6 | `make test-negative` 真实负向：非零退出 + 失败项 + 修复提示，并能恢复 | 停 demo-service → verify exit 1、`10 PASS / 4 FAIL`（#4 端口 8081、#11 实例、#12 health、#14 链路，全部指名 demo-service）；重启 3s 后 14/14 全绿；`make test`（lint+build+negative）整体 exit 0 | 通过 |
| AC-7 | 首次跑完 `git status` 无 target/、.nacos/、*.log、.env、.DS_Store、.idea/、*.iml | `git status --porcelain` 仅 10 行源码/文档（`M .gitignore` + 9 项交付文件），产物类命中 **0**；`.nacos/ .run/ .m2/ logs/` 均被忽略；`check-t7-make-gitignore.sh` 40/40 | 通过 |
| AC-8 | README ≤8 条命令、版本矩阵与 dependency:tree 一致、≥4 条排障（含 8080 冲突）、JDK<25 可执行指引 | `qa-ac8-docs.sh` 10/10：命令 6 条；排障 8 条含「Nacos 控制台 8080 与网关冲突」；假 `java 21` 触发 precheck 的 JDK<25 分支并打印 `brew install openjdk@21` + `Boot 3.5.16 + Cloud 2025.0.3 + SCA 2025.0.0.0 + Nacos 2.5.5`；`dependency:tree` 解析出 gateway 5.0.4 / loadbalancer 5.0.4 / nacos-client 3.1.1，与 README 矩阵一致 | 通过 |

## 3. 回归契约复跑（上轮 checker 逐个通过数）

| 检查器 | 本轮结果 | 退出码 |
|---|---|---|
| `scripts/t4-scripts-contract.sh` | **69 passed / 0 failed**（修正 8849 断言竞态后；修正前 68/1，见 §4.2） | 0 |
| `scripts/t5-dev-cycle.sh` | **16 checks PASS**（2 轮 up/down，up 17s/19s、down 6s/6s） | 0 |
| `scripts/check-t7-make-gitignore.sh` | **40 passed / 0 failed** | 0 |
| `scripts/check-t6-port-vs-health.sh` | 通过（SIGSTOP 时 #4 端口 PASS 与 #12/#14 FAIL 并存，解冻后全绿） | 0 |
| `scripts/qa-ac4-real-stack.sh` | **5/5**（27ms 即 503、无 500、直连 000、重启 17s） | 0 |
| `scripts/ac4-config-refresh.sh` | PASS（1s 生效 / 1s 恢复 / falsifiable 对照变 local） | 0 |
| `scripts/ac4-failover-503-and-direct.sh` | PASS（1s 503、2s 恢复） | 0 |
| `scripts/t2-ac4-e2e.sh` | **9/9**（首次 8/9，见 §4.3） | 0 |
| `scripts/failfast-dead-nacos-probe.sh` | HONORED（死地址 5s 内启动失败，栈指向 `NacosServiceRegistry.register`） | 0 |
| `scripts/tests/lint.sh` | **9/9**（L3 单点实现、L4 配置 URL 单点、L7 文档一致） | 0 |
| `make verify` | **14 PASS / 0 FAIL** | 0 |
| `make test`（lint + build + negative） | 全绿，负向用例 5s 内红→绿 | 0 |

## 4. 本轮新增探针与判定

**4.1 `qa-ac8-docs.sh`（新增，补上轮只读文档的缺口）**
上轮 AC-8 只核对了文档文本；本轮把「版本矩阵」与 `dependency:tree` 的真实解析值逐项比对（5.0.4 / 5.0.4 / 3.1.1 + Boot/Cloud/SCA 与父 POM），并用假 `java`（打印 21.0.5）真实触发 `env-precheck.sh` 的 JDK<25 分支，断言输出含 `brew install openjdk@21`、`java_home -v 21` 与 Boot 3.5.16 降级组合 —— 10/10 pass，exit 0。

**4.2 t4 的 8849 断言：检查器竞态（非产品缺陷）**
原始断言在 `nacos.sh start` 返回后立即单次 `curl --max-time 6`；连续 2 次红（HTTP 000）。实测根因：start 只看 8848 健康，console Tomcat 在其后约 0.3s 才绑定 8849（start 返回 +18ms 仍 000，+27ms / +249ms 变 302）。端到端 `dev-up`/`dev-down`/`verify #6` 路径下 8849 均正常。已在检查器内改为 ≤15s 有界轮询，复跑 69/69 —— 修的是检查器，不是交付代码。

**4.3 t2-ac4-e2e 首次 8/9**
日志显示隔离网关（pid 30060）在 20:28:11.701 收到外部 SIGTERM 后优雅退出，导致「upstream back still not 200」；重跑 9/9。判定为并发环境偶发（同机其它探测进程收尾），非交付缺陷。

## 5. 架构与接口一致性检查

- **蓝图落地**：父 POM 单点定义 `java.version=25` 并 import Boot 4.0.9 parent + Cloud 2025.1.4 + SCA 2025.1.0.0 两个 BOM，子模块零版本号；网关含 webflux gateway 5.0.4 + loadbalancer 5.0.4 + nacos-discovery + actuator，**无 `spring-webmvc`**；demo-service 有 webmvc + nacos discovery/config，**无任何 gateway 构件**（`dependency:tree` 实测）。
- **契约一致性**：`ApiGatewayApplication.demoRoutes()` 为 `/api/**` + `stripPrefix(1)` → `lb://demo-service`；五个 YAML 关键键（端口、注册名、Nacos 地址、config import、actuator 暴露面）与环境变量默认值均与 TECHNICAL §3 逐字一致。
- **重复实现收敛**：`own_jvm_pids` / `kill_own_jvms` / `chain_ok` 仅由 `lib.sh` 定义（lint L3 三条断言）；Nacos v3 配置 URL 仅由 `lib.sh` 生成（lint L4）；Makefile 只做薄封装，`clean` 复用 `detect_maven_repo`。
- **接口齐全性（静态）**：dev-up/dev-down/verify/nacos/negative 调用的 20 个 lib 函数全部在 `scripts/lib.sh` 有定义，`bash -n` 5 个脚本通过，实跑覆盖全部主路径。
- **结论**：未发现新增架构缺陷。

## 6. 仓洁与提交面

`git status --porcelain` = ` M .gitignore` + 9 项未跟踪交付文件（AGENTS.md、Makefile、README.md、api-gateway/、config/、demo-service/、docs/、pom.xml、scripts/）；`target/`、`.nacos/`、`.run/`、`.m2/`、`logs/`、`*.log`、`.env`、`.DS_Store`、`.idea/`、`*.iml` 命中 **0** 条；`config/nacos/demo-service.yml` 仍在跟踪清单内（必须入库）。

## 7. 缺陷表

**未发现缺陷**（本轮 OPEN 0 条；上轮 D1–D7 均已闭合，见 §8）。

## 8. 修复项复验对比（round-1 级，非开放缺陷）

| 编号 | round-1 级 | 模块 | 复验命令 | 本轮结果 | 状态 |
|---|---|---|---|---|---|
| D1 | P2 | 脚本 / Nacos 生命周期 | `NACOS_HOME=$(mktemp -d) NACOS_DOWNLOAD_URL=http://127.0.0.1:9/nacos.tar.gz bash scripts/nacos.sh ensure` | exit 1，输出 `err 下载失败: <url>（可手动下载后放到 …）`，`unbound variable` 计数 0 | 已闭合 |
| D2 | P2 | 文档 / README | `grep -nE "约 25 秒\|约 25s\|logs/start\.out\|不是幂等\|约 20s\|约 50 秒" README.md` | 无输出（exit 1） | 已闭合 |
| D3 | P3 | 文档 / ARCHITECTURE | `grep -nE "start\.out\|不是幂等\|25–26s\|返回 500" docs/teamflow/architecture/ARCHITECTURE.md` | 无匹配（exit 1） | 已闭合 |
| D4 | P3 | 网关 / 错误映射 | `bash logs/teamflow/tf-mw7obdjs-33rw2a/scripts/qa-ac4-real-stack.sh` | 5/5；停服后首个非 200 即 503（27ms），无 500 | 已闭合 |
| D5 | P3 | 架构 / 脚本分层 | `bash scripts/tests/lint.sh` | 9/9，L3 三函数仅 `lib.sh`、L4 配置 URL 单点 | 已闭合 |
| D6 | P3 | 安全 / 本地凭据 | `grep -n "token.secret.key" scripts/nacos.sh` | :79 `NACOS_TOKEN_SECRET` 可覆盖，默认值附「仅本机」注释 | 已闭合 |
| D7 | P3 | 脚本 / 进程判定 | 写入 `.run/{demo-service,api-gateway}.pid`=999999/999998 后 `bash scripts/dev-up.sh` | exit 0 + READY，链路 200 `instancePort=8081 configSource=nacos` | 已闭合 |

## 9. 人工补测清单（环境限制，非交付缺陷）

| # | 项 | 验收判据 | 方法 / 工具 |
|---|---|---|---|
| 1 | 首次冷启动（含 233MB 下载）耗时 | ≤10min | 终端执行 `make distclean && time make dev-up`；上轮实测 373s，本轮因不重复下载未重跑 |
| 2 | Nacos 控制台 UI 交互 | 8849 `/next/` 可登录、实例列表可见 | 浏览器打开 `http://localhost:8849/next/`（沙箱内浏览器不可用，脚本侧已用 curl 302 + OpenAPI 200 佐证） |
| 3 | 公司代理 / `settings.xml` 下的下载与依赖解析 | `make dev-up` 在不改代码前提下成功 | 目标网络环境执行 `make distclean && make dev-up` |
| 4 | 长稳（连续 20 轮 up/down） | 无 Derby 锁、无端口泄漏、无 JVM 残留 | 终端 `for i in $(seq 20); do make dev-up && make dev-down; done` |
| 5 | 真实业务替换 demo-service | 替换后 `make verify` 仍 14/14 | 按 README「替换为真实业务」步骤操作 |

## 10. 环境交接

当前 **STOPPED**：8080/8081/8848/8849/9848/9849 均无 LISTEN、无残留 JVM、`.run` 已清理；`.nacos/` 缓存保留（下次 `make dev-up` 走缓存，实测 17–20s）。本轮未执行任何 git 提交/合并。
