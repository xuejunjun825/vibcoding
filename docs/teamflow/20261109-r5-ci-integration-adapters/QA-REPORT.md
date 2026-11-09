# M7 CI 集成对接 · QA 报告（第 3 轮 · 复核轮）

> 任务夹：`docs/teamflow/20261109-r5-ci-integration-adapters/` · 分支 `feat/ci-integration-adapters` · 日期 2026-11-09
> 轮次性质：**对 D-04 修复的复核轮**（规则 6b）。先逐条重跑上轮留在 `logs/teamflow/tf-mw8nnjn0-dte37u/scripts/` 的检查器，再补上轮未覆盖的面。

## 0. 结论

**可验收（建议放行）**。上轮唯一未关闭缺陷 D-04（需求详情「集成」tab 返回租户全局日志）已修复并经“正向 + 反向”双向验证；本轮未发现 P0/P1/P2 缺陷；遗留 3 条 P3 观察（1 条测试脚本竞态、1 条历史日志无锚点、1 条焦点样式）。

## 1. 范围与环境

| 项 | 值 |
| --- | --- |
| 被测对象 | 工作区未提交改动（83 个路径），含 req-service 集成域、deliver 追溯/缺陷、网关路由、web 集成页与 M7 验证脚本 |
| 真实栈 | MySQL + Flyway V8；req-service `:8082`（`{"status":"UP","db":"up","flyway":"8"}`）、deliver-service `:8083`、api-gateway `:8080`、四合一桩 `:19090`（`{"status":"UP","stub":"m7-integration"}`） |
| 技术栈 | JDK 25 · Spring Boot 4.0.9 · Vue 3 + Vite |
| 浏览器 | 系统 Chrome 可用（headless `--dump-dom`），本轮管理端 31 条真实浏览器断言全绿 |
| 未覆盖 | 真实 Jenkins/GitLab/Sonar/Nexus 联调（无环境）、10 万需求满载性能基线 |

## 2. 上轮缺陷 D-04 复核（按原判定命令重跑）

**原缺陷**：需求详情「集成」tab 调 `configService.logs(null×6)`，返回租户全局最近 10 条，静默需求与活跃需求载荷相同。
**修复机制（已读源码确认）**：写侧打业务锚点（`GitLabAdapter` biz("MR", requirementId)、`SonarAdapter` biz("SONAR", requirementId)、`JenkinsAdapter` biz("BUILD", buildId)、`GitlabWebhookController` 经 `requirementIdOf(tenantId, project, iid)` 解析，未命中落 NULL）；读侧 `IntegrationSyncLogRepository.search` 增 `:requirementId` 维度（MR/SONAR 按 biz_id，BUILD 按 biz_id 落在该需求的 build_record 或 correlation_id），`IntegrationConfigService.logsForRequirement` 复用同一 JPQL，`RequirementIntegrationService.java:60` 改为按需求取。

| 复核项 | 命令 | 结果 |
| --- | --- | --- |
| 原判定命令（正向） | `bash logs/teamflow/tf-mw8nnjn0-dte37u/scripts/qa-integration-endpoints.sh scope` | `#SUMMARY qa-integration-endpoints[scope] pass=3 fail=0`，exit 0（静默需求 sync=[]；活跃需求 100323 sync=1 id=1503；两者集合不同） |
| 反向回归（本轮新增） | `... qa-integration-endpoints.sh no-regress` | `pass=2 fail=0`，exit 0（管理页 `/integration/logs` total=1833 > 0，且 1833 > 单需求 sync=1） |
| 合并全量 | `... qa-integration-endpoints.sh all` | `#SUMMARY qa-integration-endpoints[all] pass=9 fail=0`，exit 0 |
| 永久门禁 | `make integration-verify`（s2-mr 内 3 条锚点门禁） | s2 43/43（含：活跃需求 sync 非空 / 每行有锚点 / 静默需求为空） |

**结论：D-04 关闭。** 反向回归补齐了修复可能带来的新风险——`requirementId` 维度加进同一条 JPQL 后，若管理页调用点被顺手传了需求，全局面会静默收窄且既有断言全绿；本轮已用数据对账锁死（管理页 1833 条 vs 单需求 1 条）。

## 3. 本轮检查清单（逐条重跑，数值逐字）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 集成验收主套 | `make integration-verify` | **230/230** 全绿：s0 33 / s1 39 / s2 43 / s3 19 / s4 32 / s5 28 / s6 26 / s7 10；套内 `q4-gate-admission 39/39`；exit 0 |
| 质量域回归 | `make quality-verify` | 348/348（q0 31 / q1 67 / q2 39 / q3 47 / q4 39 / q5 54 / q6 26 / q7 45） |
| 平台级 AC 回归 | `make platform-verify` | `ok platform-verify: 167/167 项通过（AC-1..AC-20）` |
| 负向用例 | `make platform-test-negative` | `25/25 条负向用例全部按预期被拒且不落库` |
| 单元测试 | `make platform-unit` | `178 个单元测试全部通过` |
| 静态规范 | `make lint` | `lint 全部通过（16 项）` |
| 前端类型/构建 | `cd web && npm run typecheck` / `npm run build` | 0 个 `error TS`；`✓ built in 4.72s` |
| 前端契约探针 | `node .../integration-frontend-contract.mjs` | 51/51 |
| 前端 AC 探针 | `node .../t10-ac-check.mjs` | 15/15 |
| 构建轮询（真定时器） | `cd web && node .../build-polling-e2e.mjs` | 13/13 |
| 路由可达性 | `node .../integration-route-e2e.mjs` | 11/11 |
| **管理端真实浏览器** | `node .../intg-admin-browser-smoke.mjs` | `#SUMMARY integration-admin-browser total=31 pass=31 fail=0`（凭据仅末四位、清除需逐字输入系统名、锁定项渲染为锁章、关闭准入项需二次确认且 PUT 载荷为单条 `{code,enabled}`、日志按 correlation 聚合） |
| 数据底座/网关/密钥 | `bash .../t1-integration-base.sh` | `#SUMMARY t1-base passed=33 failed=0` |
| 桩自检 | `bash .../stub-selfcheck.sh` | `#SUMMARY stub-selfcheck pass=15 total=15 fail=0` |
| 需求 tab 端点 | `bash .../qa-integration-endpoints.sh all` | 9/9（requirement 3 / logs 1 / scope 3 / no-regress 2） |

## 4. 架构与接口一致性核查（规则 0 / 0a）

| 核查点 | 方法 | 结论 |
| --- | --- | --- |
| 单一出站口 | `grep -rn "new RestClient\|new RestTemplate\|HttpClient.newBuilder"` req 集成域 | 无自建客户端，四适配器均经 `OutboundHttpClient`（重试/脱敏/SSRF/日志只实现一次） |
| 密码学单点 | `grep -rn "Cipher.getInstance"` 全仓 | 仅 `platform-common/.../crypto/AesGcmCodec.java`（2 处调用、1 个类）；`deliver-service/.../ClientSecretCodec.java:26,29,37,50` 改为委托，公开签名未变 |
| 准入项单一源 | `AdmissionItemPolicy.ORDER` + `admissionItem` 字典 | Java 侧顺序/名称/锁定集合只有一处常量；前端只做展示映射 |
| 网关入口 | `api-gateway/.../PlatformRoutes.java:33` | `req-integration` route `order(-95)` 且 `path("/api/integration/**")` stripPrefix(1)，位于 `/api/**` 兜底之前；webhook 匿名前缀在 `JwtGatewayFilter` |
| 跨模块调用存在性 | `grep` 调用点 vs 目标签名 | `RequirementIntegrationService.java:60 → logsForRequirement(long,int)` ✅；`IntegrationConfigController.java:92 → logs(...)` 8 参 ✅；`web/src/api/integration.ts:16 REQ='/api/req'` 与网关前缀一致 ✅；`search()` 全部 4 个调用点均传 9 参 ✅ |
| 重复实现/漂移 | 对照注入的架构蓝图 | 未发现第二套 AES-GCM、未发现第二套重试/脱敏、未发现前端自造状态字符串；蓝图模块全部落地 |

## 5. 缺陷表（仅列复核后仍未关闭项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| QA-R3-01 | P3 | M7 验收脚本（s1-build） | 连续执行 `make integration-verify` 3 次：1 次 s1-build 38/39，失败项「重试耗尽写 RETRY_EXHAUSTED 日志 (期望 ge 1，实际 0)」；同一脚本单跑与紧接的整轮重跑均 39/39 | 同一产品行为下脚本稳定 39/39；「最多 6 次尝试」只统计本构建的提交数 | `scripts/tests/integration/s1-build.sh:133` 用累计计数器 `i_stub_count jenkins.submit` 判定退出，前两次成功提交也计入，循环可提前约 2 次尝试退出（失败那轮 s1 仅 5s，绿的那轮 14s），随即 :138 立即查库时 RETRY_EXHAUSTED 行尚未提交；同时 :137 的 `eq 6` 可能因累计计数而“假通过” | AC-2.2 / AC-9.1 | `bash scripts/integration-verify.sh s1` | 连续 5 次均 39/39，且 `TRIES` 改为「本构建提交数 ≥ 6」后仍绿（建议触发前快照 `BASE=$(i_stub_count jenkins.submit)`，并轮询 DB 至 60s） |
| QA-R3-02 | P3 | 需求详情「集成」tab（历史数据） | 打开修复前已有同步记录的需求 → 集成 tab | 历史日志也能归位到需求 | 修复只对写侧新增锚点生效，无 Flyway 回填：修复前的 MR/BUILD/SONAR/NEXUS 历史行（`biz_id` 为 NULL 或旧值）不会出现在需求 tab；`recentMergeRequests`、Jenkins 预解析入站、Nexus 手工登记仍落 `biz("…", null)` | AC-2.1 / AC-4.2 | `bash logs/teamflow/tf-mw8nnjn0-dte37u/scripts/qa-integration-endpoints.sh scope` | 当前 3/3 通过（新数据面正确）；若要求历史可见，需补一次幂等回填脚本后复跑 |
| QA-R3-03 | P3 | 管理端集成页（工艺观察） | 键盘 Tab 遍历 `CredentialField.vue` / `ConnectionTestBar.vue` / `PromoteDialog.vue` | 交互元素有显式焦点反馈 | 三个组件未声明 `:focus`/`:focus-visible` 样式，焦点依赖 UA 默认 outline（可用，未破坏无障碍） | AC-9（工艺观察，不阻塞） | `grep -c ":focus" web/src/components/integration/{CredentialField,ConnectionTestBar,PromoteDialog}.vue` | 三处均 ≥1（当前均 0） |

**无 P0/P1/P2 未关闭缺陷。**

## 6. 同类缺陷命中数复算（D-04 缺陷类：需求作用域被租户全局查询污染）

| 面 | 修复前 | 修复后 | 证据 |
| --- | --- | --- | --- |
| 需求详情集成 tab 同步记录 | 1（命中） | 0 | scope 探针 3/3：静默需求 `[]`、活跃需求 1 条 |
| 管理页全局面（反向） | 未探测 | 0（未被收窄） | no-regress 2/2：total=1833 > 单需求 1 |
| 其它 `logs(`/`logsForRequirement(` 调用点 | — | 0 残留 | `grep -rn "\.logs(\|logsForRequirement("` → 仅 `RequirementIntegrationService:60`（按需求）与 `IntegrationConfigController:92`（管理页） |

## 7. 提交面卫生（规则 0c）

`git status --porcelain` = **83** 项，无 `node_modules/`、`target/`、`dist/`、`.m2/`、`.run/`、`.idea/`、`.env`、`*.pid`、`test-results/` 等构建/运行/IDE 噪声；`.gitignore` 已覆盖桩运行态（t1-base 第 29–33 条断言）。`logs/teamflow/<runId>/` 为宿主托管的暂存区，不进提交。

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收判据 | 方法 / 工具 |
| --- | --- | --- |
| 真实 Jenkins 联调 | 触发 202、回调改态、console_url 同源 | 按 `README.md`「集成对接（M7）· 本地开启步骤」接入试用实例，`curl` 逐接口 |
| 真实 GitLab MR / webhook 重投 | 三类事件、验签 401、未知事件 200、重投幂等 | 真实 GitLab + 内网暴露回调；停 req 30s 观察重投 |
| 真实 Sonar / Nexus | 五度量、质量门三态、sha256 三方一致 | 同上，凭据用最小权限 token |
| 10 万需求满载性能（AC-20） | 列表 P95≤1s、准入≤500ms | `make platform-perf`（本轮只跑了 platform-verify 的冒烟 0.247s） |
| 屏幕阅读器与视觉走查 | 三 tab 可朗读、对比度达标 | VoiceOver + Tab 遍历；对照 `DESIGN.md` §6.9 |
| 需求 tab 跨租户隔离 | 跨租户 404 | 需先造一条 `tenant_id≠1` 的需求夹具（当前库内需求全为 tenant 1）；`requireVisible` 路径已由 platform-verify 覆盖 |

## 9. 放行意见

D-04 已闭环且回归面已扩展到反向与同类调用点扫描；产品侧 230/230 + 348/348 + 167/167 + 25/25 + 178 单测全绿，真实浏览器 31/31。3 条 P3 均为脚本稳定性/历史数据/样式观察，不阻塞验收。**建议验收并提交。**
