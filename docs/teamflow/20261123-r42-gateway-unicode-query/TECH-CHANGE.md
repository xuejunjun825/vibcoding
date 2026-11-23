# 技术变更单 · 网关非 ASCII 查询断连（r42）

- 任务夹：`docs/teamflow/20261123-r42-gateway-unicode-query/`　分支：`feat/gateway-unicode-query`　基线：`main@d2ad988`
- 类型：技术驱动返工（P0 缺陷修复 + 门禁固化）。**不新增功能 AC**，不新增用户可见能力；唯一可见变化是"中文搜索从断连变可用"。

## 1. 背景与目标（一句话）

经 `8080` 的请求只要查询参数含非 ASCII（如 `keyword=%E6%BC%94%E7%A4%BA`），网关即断连（curl `000`）：本轮给出**可复核的根因**、**服务端修到真通**（不许前端 encode 绕过），并把这类故障**固化进回归门禁**。

## 2. 根因（先定性，再改）

**结论：三选一全部否证——不是 SCG 5.0.4 与 Spring Framework 7.0.10 不兼容，不是类初始化失败，也不是链路上有第二份 `spring-web`。真正原因是构建在服务运行时"就地覆盖"了正在运行的 fat jar，运行中的 JVM 再也读不出尚未加载的类。**

失败机理（按发生顺序）：

1. `mvn package` 的 jar 插件用截断写（同一 inode）重写 `api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar`，随后 Spring Boot repackage 把这个 thin jar 改名成 `.jar.original`、再写新的 fat jar。
2. 运行中的 JVM 启动时打开的就是那个 inode，内容已被 thin jar 覆盖；**已加载的类仍在内存里可用，未加载的类读不出来**。
3. 非 ASCII 查询先走 `RouteToRequestUrlFilter` → `ServerWebExchangeUtils.containsEncodedParts` → `UriComponentsBuilder.build()` → `HierarchicalUriComponents.verify()`，这条链**第一次**需要 `HierarchicalUriComponents$Type`（纯 ASCII 请求不会加载它）→ `NoClassDefFoundError` → Netty 关闭连接 → `curl 000`。ASCII 请求照旧 200，正是"只有中文炸"的解释。

证据（可复核）：

| # | 证据 | 命令 / 位置 | 结论 |
|---|---|---|---|
| A | 依赖层只有一份 spring-web | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl api-gateway dependency:tree` → `spring-cloud-gateway-server-webflux:5.0.4` → `spring-web:7.0.10`，BUILD SUCCESS；`~/.m2` 里的 5.3.31 不在 classpath | 否证"第二份 spring-web" |
| B | 类确实在包里 | `spring-web-7.0.10.jar` 内 `BOOT-INF/lib/spring-web-7.0.10.jar` md5 `b378481a10aad2fed0b11802c87a962c`（与 `.m2` 一致）；含 13 个 `HierarchicalUriComponents$Type*` 条目，`javap` 可读出 `enum Type` | 否证"jar 缺类/打包丢类" |
| C | 同一 fat jar 起新进程即正常 | `java -jar <同一 jar> --server.port=18080`，同一 token 请求 `keyword=%E6%BC%94%E7%A4%BA` → **200**，`search=%E4%BE%9B` → 200 | 否证"版本不兼容/类初始化失败" |
| D | 坏进程打开的是被覆盖后的 inode | `lsof -p 10931`（故障网关）→ fd 4r/5r 指向 `api-gateway-0.1.0-SNAPSHOT.jar.original`，**11793 字节**、inode 12463828；磁盘 fat jar 为 **72,057,350 字节**（今日 20:39 构建） | 进程的 jar 已被 thin jar 顶替 |
| E | 受控复现（决定性） | `/tmp/gwtest2/app.jar` 起新网关 18082：ASCII 先 200；用 `cp` 就地覆盖成 thin jar（11793B，模拟 jar 插件截断写）→ **首个中文请求 000（Empty reply）**，ASCII **仍 200**；日志 `java.lang.NoClassDefFoundError: reactor/core/Exceptions` / `Caused by: java.lang.ClassNotFoundException: reactor.core.Exceptions` | 故障族复现；先触发哪个类是"该进程还没加载哪个类"，生产是 `HierarchicalUriComponents$Type` |
| F | 生命周期必然触发 | `scripts/lib.sh:319` `start_app()` 只要 `app_alive` 就 `ok` 返回；`scripts/platform-up.sh:44-46` STAGE 1 先调 `dev-up.sh`（`dev-up.sh:140` 构建后启动网关），`:59-70` STAGE 3 又 `mvn package` | 每次在已起的栈上 `make platform-up` 都会覆盖在跑 jar，"重启后仍必现"即此 |
| G | 历史同族 | `logs/api-gateway.log:26826` 首现同类栈，时间 `2026-11-09T18:01:23`、pid 78570 | 同一机制反复发生过，不是本轮新引入 |

> 结论落到运维语义：**"重启栈"若走 `make platform-up`（幂等、跳过在跑进程），进程根本没换，缺陷照旧。**只有 `make platform-down && make platform-up` 才换进程。

## 3. 影响面（改动清单）

| 文件 | 改动 | 说明 |
|---|---|---|
| `scripts/lib.sh` | `start_app()` 增加 jar 指纹（inode:size:mtime，`.run/<name>.jar.fp`）记录与"指纹变化即强制重启" | 单点修复：`dev-up`/`platform-up`/手工调用全部受益 |
| `scripts/platform-up.sh` | STAGE 3 构建后追加"让 r1 进程用上刚构建的 jar"：再调 `start_app api-gateway`、`start_app demo-service` | 消除 STAGE 1 启动 → STAGE 3 覆盖 的窗口 |
| `scripts/platform-verify.sh` | 新增 `section "AC-21 网关非 ASCII 查询（r42）"` 5 条断言 | 194 → **199** |
| `scripts/platform-test-negative.sh` | 第 18 段新增 2 条反证 | 130 → **132** |
| `docs/teamflow/memory.md` | 新增一条事实行 + 新门槛 | 同主题行替换，幂等 |

**不动**：`api-gateway/**`（零代码改动）、父 `pom.xml`/BOM/版本矩阵、业务服务（req/deliver/demo）行为、`web/**`、Flyway 迁移。前端**不做** encode 规避（r42 约束 3）。

## 4. 技术方案（要点）

1. **R1 指纹重启**：`file_fingerprint()`＝`stat -f '%i:%z:%m'`（Darwin）/ `stat -c '%i:%s:%Y'`（Linux）。`start_app` 启动后写 `$RUN_DIR/<name>.jar.fp`；再次调用时若"进程存活且指纹不一致（或缺失）"→ `stop_app` + 重启，并打印一行原因（旧/新指纹）。指纹缺失按"未知"处理、重启一次并补写，安全侧优先。
2. **R2 顺序补齐**：`platform-up.sh` STAGE 3 构建后重跑 `start_app api-gateway $GATEWAY_PORT api-gateway`、`start_app demo-service $DEMO_PORT demo-service`（幂等；R1 自动完成重启），再进 READY。
3. **R3 正向门禁**（`platform-verify.sh` 新段，5 条）：① 中文 keyword 经 8080 → 200 且 `total ≥ 1`（keyword 从池中标题动态取前 2 个汉字，**不写死"演示"**——实测种子无该词、`total=0`）；② 中文 + ASCII 混排（`keyword=<中文>&page=1&size=5&status=ALL`）→ 200；③ 逐字边界三连（单字"供"、多字"供应商"、含空格"中 文"以 `%20` 编码）→ 全 200；④ **陈旧进程自证**：`.run/api-gateway.jar.fp` 与磁盘 jar 当前指纹一致（红即"构建覆盖了在跑 jar"，直指本轮根因）；⑤ 中文 path 段 `GET /api/req/requirements/<中文>` 返回 400/404 而非 `000`（边界，见 §7-2）。
4. **R4 反证**（`platform-test-negative.sh` 第 18 段）：同一中文请求经 `8080` 与直连 `8082`（`/requirements?keyword=%E4%BE%9B`）响应体 `cmp` **逐字节一致**，且两边 `total` 相同——证明网关没有改写业务语义（实测 40 字节全等）。

## 5. 行为兼容性影响

- API 契约、错误码、响应体：**零变化**；唯一可观察变化＝中文查询由 `000` 变 `200`。
- 运维行为变化（预期、需在 README/memory 记一句）：`make platform-up` / `make dev-up` 在"jar 比进程新"时会**重启**对应进程（原先静默沿用旧进程），耗时约 +10–20s，幂等性不变。
- 无 schema、无迁移、无数据变更；回滚不需要清库。

## 6. 回归与验证计划（门槛）

| 命令 | 门槛（本单修订后） | 说明 |
|---|---|---|
| `bash scripts/platform-verify.sh` | **≥199**（194+5） | 含 AC-21 新段；`PASS==TOTAL` |
| `bash scripts/platform-test-negative.sh` | **≥132**（130+2） | 含字节一致反证 |
| `bash scripts/access-verify.sh` | ≥206 | 不降 |
| `bash scripts/rbac-verify.sh` | ≥113 | 不降 |
| `bash scripts/release-flow-verify.sh` | 44 | 不降 |
| `bash scripts/gate-order-verify.sh` | ≥21 | 不降 |
| `bash scripts/change-verify.sh` | 42 | 不降 |
| `bash scripts/platform-demo-reset.sh` | 80，连跑两次一致 | 不降 |
| `bash scripts/lint.sh` | 22 | 不降 |

自测（dev 必做，写进提交说明）：修复前 8080 中文 `000`、直连 8082 `200`；修复后 8080 中文 `200` 且 `total` 与直连一致；ASCII 不回归。新增断言数（5/2）同步进 `memory.md` 基线。

## 7. 风险与回滚

- R1 误重启：jar 时间戳变而内容不变也会重启（可接受，幂等）；`stat` 都不可用时按"指纹缺失"处理，最坏多一次重启。
- R2 增加起栈时间约 10–20s（仅当 jar 变新时）；不改变 `platform-up` 的幂等语义。
- 门禁 ① 命中数依赖种子标题：故动态派生，避免换种子即假红。
- **回滚**：`git revert` 单个提交即可（仅 4 个脚本 + 2 个文档）；无迁移、无数据、无版本矩阵变更，回滚后旧栈照常运行。

## 8. 假设与待澄清

1. **"重启栈后仍必现"按 `make platform-up` 理解**（幂等跳过在跑进程）。若用户实际执行的是 `down`+`up` 仍必现，本结论需重开——但证据 C/E 已否证版本不兼容，届时优先查"是否有第二个进程/第二份 jar"。
2. **中文 path 变量边界**：本产品网关只路由 `/api/req/**`、`/api/integration/**`、`/api/deliver/**`、`/openapi/**`，path 变量均为数字 ID，**无中文 path 变量端点**（`/wiki/pages/<中文标题>` 在本产品不存在）。故只验"不再断连"（400/404 属正常业务响应），不新增端点。
3. 门禁断言条数：若 QA 把"逐字边界三连"合并为 1 条，`platform-verify` 变 **197**，门槛随之改为 ≥197（须在 QA-REPORT 记录口径）。
4. 是否把"指纹重启"扩到 `web`（Vite 进程无 jar）：不扩，web 用热更新，语义不同。
