# M7 CI 集成对接 · 验收报告

> 任务夹：`docs/teamflow/20261109-r5-ci-integration-adapters/` · 分支 `feat/ci-integration-adapters` · 验收日期 2026-11-09
> 验收依据：`PRD.md` 的 AC-1..AC-11（11 组 67 条）+ 架构蓝图 + `TECHNICAL.md` + `QA-REPORT.md`
> 验收方式：**独立复跑，不沿用 QA 数字**；代码/蓝图逐项比对；数据面抽查；视觉与交互以真实浏览器脚本为准（本轮已独立复跑 31/31）。

## 0. 结论

- **功能验收：全部通过。** 20 条平台级 AC 与 M7 的 11 组 AC 均有可复现证据；未发现 P0/P1/P2 功能缺陷。
- **架构验收：通过。** 蓝图 33 个模块全部落地；单一出站口、单一密码学实现、准入常量单一源、前端字典单一源均成立；未发现重复实现或适配器漂移。
- **唯一放行条件 R1：** AC-9.1 的 `make integration-verify` 存在**脚本竞态**（即 QA-R3-01）。本轮 2 次全量复跑中 1 次 `s1-build` 38/39、退出码 1；根因在断言脚本（累计计数器提前 break + 立即查库），产品行为本身正确。修复后连续 5 次 230/230 即转为无条件通过。

## 1. 验收范围与方法

| 维度 | 做法 |
| --- | --- |
| 功能 AC | 逐条对照 PRD；以 `make integration-verify`（230 条）、`make quality-verify`（348 条）、`make platform-verify`（167 条）、`make platform-test-negative`（25 条）为证据 |
| 架构一致性 | 与注入的架构蓝图逐模块比对；grep 全仓查重复实现（密码学、HTTP 客户端、重试、脱敏） |
| 数据面 | 直连 MySQL 抽查审计/同步日志凭据命中数、集成配置密文、准入 9 项与快照签名 |
| 接口面 | 真实栈（8082/8083/8080/19090）用管理员 JWT 实测配置、409 关闭、准入响应与耗时 |
| 前端 | `npm run typecheck`/`build`；独立复跑管理端真实 Chrome 冒烟 31 条 |
| 未覆盖 | 真实 Jenkins/GitLab/Sonar/Nexus 联调、10 万需求满载性能（环境不可得，列入人工补测） |

## 2. 逐条 AC 核对

| AC | 关键判据 | 独立证据 | 结论 |
| --- | --- | --- | --- |
| AC-1 配置底座 | 四类可管、凭据只回末四位、空串不清、独立清除、关闭→409、审计 | s0 33/33；实测 `GET /integration/configs` 仅 `hasCredential/credentialLast4="2026"`、无明文、`enabled=false`；`POST /deploy-orders/499/builds` → 409 `INTEGRATION_DISABLED`（含系统名）；audit 明文 0 | 通过 |
| AC-2 日志/重试/降级 | 每次出站一行、仅 5xx/429/超时重试、上限 6 次、降级不阻断、超时、幂等、审计 | s1 39/39（含 429/500/timeout 重试与 4xx 不重试、RETRY_EXHAUSTED、双通道幂等）；s6 26/26；`RetryPolicy` 常量为 1/2/4/8/16s、±20%、6 次 | 通过 |
| AC-3 Jenkins | 202+QUEUED、并发 409、状态机、验签、2h 超时、转缺陷、console 同源 | s1 39/39（覆盖 409 BUILD_IN_PROGRESS、非法迁移、BUILD_TIMEOUT、缺陷去重、CONSOLE_URL_REJECTED） | 通过 |
| AC-4 GitLab | 两种 URL、代码卡、三类事件、401 不落库、未知 200、兜底轮询、唯一键/单调、跨租户 | s2 43/43（含验签失败行数不变、MERGED 不被回退、跨租户 404） | 通过 |
| AC-5 mrAutoAdvance | 默认 false、true 才推进 RD、幂等、只动 RD、非法 409 | s2 43/43；V8 `release.mr_auto_advance DEFAULT 0` | 通过 |
| AC-6 Sonar | key 校验与继承、刷新/定时、五度量、三态、跳过原因、追溯、快照只增 | s3 19/19（hash 幂等、SKIP 原因具体、sonar 进追溯 cells） | 通过 |
| AC-7 Nexus | 检索≤50、字段/唯一键、sha256 三态、晋级审计/409、SSRF 0 外呼、跨租户 | s4 32/32（含 `CHECKSUM_MISMATCH`、跨主机重定向拒绝、目标 0 请求） | 通过 |
| AC-8 准入 9 项 | 顺序/同源、锁定 ①②③⑥、SKIP≠PASS、fail-closed、快照+签名+stale、P95≤500ms、第 6 项不变 | s5 28/28、q4 39/39；实测 9 项顺序与 PRD 一致、locked 恰为 ①②③⑥、counts total=9/closed=3、signature 存在、`stale=false`、响应 28.9ms | 通过 |
| AC-9 一键验证 | 桩 19090、trap、退出码、≥40 断言、四适配器各≥6、既有门禁全绿、默认关闭 | 全量第 2 次 230/230（s0 33/s1 39/s2 43/s3 19/s4 32/s5 28/s6 26/s7 10）；quality 348/348、platform 167/167、negative 25/25、unit 178、lint 16、verify 14/14、typecheck/build 0 error；**第 1 次 229/230（s1 竞态）** | **部分通过（9.1 竞态，见 R1）** |
| AC-10 安全 | 凭据全链路 0 命中、验签/时间戳 401 不落库、跨租户、密钥不入源码、白名单 | s6 26/26；审计 0 命中、同步日志 2140 行（OUT 789）0 命中、密文列 0 命中、源码 0 命中；`Cipher` 仅 `AesGcmCodec` | 通过 |
| AC-11 接入文档 | 逐系统基址/最小权限/extra/回调/白名单、差异表≥6 行、本地开启步骤 | `README.md` 集成章节四系统表 + 桩差异表 8 行 + 本地开启步骤 | 通过 |

## 3. 架构一致性（强制项）

| 核查点 | 方法 | 结论 |
| --- | --- | --- |
| 蓝图模块落地 | 逐文件核对蓝图 33 个类 | 33/33 存在，职责与蓝图一致 |
| 单一出站口 | 全仓搜 `new RestClient` / `new RestTemplate` / `HttpClient.newBuilder` | 仅 `OutboundHttpClient.java`；四适配器无自建客户端、无 ad-hoc baseUrl 拼接 |
| 密码学单点 | 全仓 `Cipher.getInstance` + `AesGcmCodec` 使用者 | 仅 `AesGcmCodec.java`（2 处）；`ClientSecretCodec` 已委托且公开签名未变，无第三份实现 |
| 重试/脱敏/日志单点 | 对照蓝图 + s1/s6 断言 | `RetryPolicy`/`RetryClassifier`/`SensitiveMasker`/`SyncLogWriter` 各自唯一；适配器内无重复 if |
| 准入单一源 | `AdmissionItemPolicy` vs 接口 vs 前端字典 | Java 侧顺序/锁定集合唯一；前端只做展示映射；实测 9 项与常量逐字一致 |
| 前端结构 | 文件与路由存在性 + 浏览器 | `api/integration.ts`、7 个字典 kind、`AdminIntegrationsView`、`BuildStatusCard`、`GateChecklist` 均在；浏览器 31/31 |

**架构返工项：无。** 未发现偏离蓝图、重复实现或该抽未抽。

## 4. 独立复跑数据（本轮实测）

| 命令 | 结果 |
| --- | --- |
| `make integration-verify`（第 1 次） | **229/230，exit 1**；唯一失败 `s1-build`「重试耗尽写 RETRY_EXHAUSTED 日志 (期望 ge 1，实际 0)」，该轮 s1 仅 12s |
| `s1-build.sh` 单独连跑 3 次 | 39/39、39/39、39/39 |
| `make integration-verify`（第 2 次） | **230/230，exit 0** |
| `make quality-verify` | 348/348（PRD 要求 ≥347，只增不减） |
| `make platform-verify` | 167/167（AC-1..AC-20） |
| `make platform-test-negative` | 25/25 按预期拒绝且不落库 |
| `make platform-unit` | 178/178 |
| `make lint` | 16/16 |
| `make verify` | 14/14 |
| `cd web && npm run typecheck` / `npm run build` | exit 0 / 0 error；构建成功 |
| 管理端真实 Chrome 冒烟 | 31/31（凭据末四位、清除需输入系统名、锁定项锁章、二次确认、PUT 单条载荷） |
| 准入实测 | 200、28.9ms、9 项、total=9、closed=3、signature 三段、stale=false |
| 凭据 0 命中 | audit 0 / sync_log 0（2140 行含 789 OUT）/ config 密文 0 / 源码 0 |

**R1 根因（已定位到行）**：`scripts/tests/integration/s1-build.sh:133` 用 `i_stub_count jenkins.submit` 的**累计**计数判定退出，前序成功提交被计入，循环提前 break；随即 `:138` 只查一次库，异步提交线程尚未写完 `RETRY_EXHAUSTED`。反证：失败轮 s1 仅 12s，而真 6 次退避需 1+2+4+8+16≈31s。产品侧重试上限、退避序列与耗尽日志均已由单独 3 次 39/39 证实。

## 5. 未关闭项与遗留

| 编号 | 级别 | 内容 | 处置 |
| --- | --- | --- | --- |
| R1 | **放行条件** | `s1-build.sh` 竞态使 AC-9.1 一键门禁约 1/3 概率假红 | 改为触发前快照 `BASE=$(i_stub_count jenkins.submit)`、以「本构建提交数 ≥6」判定，并轮询 DB 至 60s 等 `RETRY_EXHAUSTED` 落库；修复后连续 5 次 `make integration-verify` 均 230/230 |
| QA-R3-02 | P3 | 修复前历史同步日志无 `biz_id` 回填，旧数据不出现在需求 tab | 不阻塞（AC-2.1 只约束本轮起的新写入；如需历史可见，另立幂等回填任务） |
| QA-R3-03 | P3 | 三个集成组件无显式 `:focus` 样式 | 不阻塞，纳入后续无障碍打磨 |
| 流程 | 观察 | 工作区 83 项未提交，T0 文档提交尚未与实现分离 | 提交时拆两个 commit（任务夹先行、实现随后），按 PRD 工程约束执行；`.gitignore` 已覆盖桩运行态 |
| 人工补测 | 环境限制 | 真实 Jenkins/GitLab/Sonar/Nexus、10 万需求性能、屏幕阅读器 | 按 `QA-REPORT.md` §8 与 `README.md` 接入步骤在试用环境执行 |

## 6. 放行意见

产品功能、架构与安全均达到 PRD 要求，证据可复现；唯一缺口是一键门禁脚本自身的确定性，属验证资产缺陷而非产品行为缺陷。**建议按 R1 做一次脚本修复（不动产品代码），复跑连续 5 次全绿后转无条件通过。**

验收结论：⚠️ 有条件通过
