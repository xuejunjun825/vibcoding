<!-- meta: summary="M7 以统一适配层接入 Jenkins 构建、GitLab MR、Sonar 质量门与 Nexus 制品，配置/日志/重试/审计一处收口，并把发布准入由 6 项扩为 9 项，以本地桩完成正负向验收。" -->
基线依赖：docs/teamflow/20261109-r4-m6-r1-r2-rework（M6 质量门、准入第 6 项、legacy 快照语义均不得回归）；实现规格输入 docs/teamflow/20261108-r2-devops-platform/INTEGRATION-ci.md
取代：docs/teamflow/20261109-r3-qa-quality-management#AC-12：发布单准入由「5 项（M6 后为 6 项）」扩展为 9 项，新增构建/Sonar/制品三项，界面文案与准入快照结构随之更新。

# M7 CI 集成对接 PRD（Jenkins / GitLab / Sonar / Nexus）

## 1. 背景与目标

平台已具备需求 → 版本 → 任务 → 测试 → 缺陷 → 发布单的闭环，但发布准入的关键证据仍靠人工填写：构建结果在 Jenkins 看、代码合入在 GitLab 看、质量门在 Sonar 看、制品在 Nexus 看，发布单里只留一个自由文本的「制品版本」。结果是**准入能过，但过得不值得信任**：没人能证明这次发布的构建是成功的那一次、制品是登记过的那一份、质量门当时是什么结论。

本轮 M7 把四个外部系统接进来，目标有三个，按重要性排序：

1. **证据可信**：构建、MR、Sonar、制品四类外部事实进平台、可追溯、可复算，发布准入的第 7/8/9 项直接引用它们；跳过与失败必须是两种可见状态，不能混成「通过」。
2. **失败可控**：集成不可用不能拖垮需求、审批、发布主流程；但**准入校验项**必须 fail-closed——拿不到结论就是没过，绝不因超时放行。
3. **可验证**：没有演示环境、也不允许触发真实构建，因此本轮用仓库内本地桩完成成功/失败/超时/幂等/重试/签名/未知事件的完整断言，并写清「接真实系统还要填什么」。

已确认的环境事实（用户澄清，不再假设）：制品库为 **Nexus**（Maven/npm 为主；Harbor 容器镜像本轮只留适配位，不实现）；**没有演示环境，本地即开发测试环境**；验证方式为**本地桩**，不连真实实例、不触发真实构建，四类集成**默认全部关闭**。

现状基线（写 PRD 前已核对代码，不是推测）：

- 准入当前为 **6 项**：`req-service/src/main/java/com/example/req/deploy/AdmissionPolicy.java:83-88` 依次 add 测试通过 / 无未关闭 P0P1 / MR 已填 / 人天已填 / 需求级审批 / 质量门；发布单详情标题也是「发布准入检查（6 项）」（`web/src/views/deploy/DeployOrderDetailView.vue:358`）。
- `deploy_order` 已有 `quality_gate_id`、`quality_gate_snapshot_id`、`report_id`（V5 迁移增列），本轮**只新增** `build_id`、`artifact_id`。
- `requirement` 目前只有自由文本 `mr_url`，没有 `gitlab_project` / `mr_iid` / `sonar_project_key`。
- `quality_gate_snapshot` 已存在（M6，`gate_id` 与 `deploy_order_id` 为 NOT NULL），按需求「已有则复用」处理，但必须新增来源维度，不能把 Sonar 快照伪装成 M6 质量门快照。
- 仓库内没有任何 `integration_config` / `integration_sync_log` / `build_record` / `mr_sync` / `artifact_record`，本轮为全新表。

> **项数口径说明（重要）**：原始需求写「准入从 5 项扩到 8 项」。5 项是 M1–M5 的基线；M6 已加入「质量门」成为第 6 项，且本轮明确要求「不破坏 M6 的质量门第 6 项语义」。因此本轮实际为 **6 + 3 = 9 项**，第 6 项仍是 M6 质量门，第 7/8/9 项才是构建、Sonar、制品。下文一律按 9 项验收；若用户坚持总数 8，只能合并既有项，那会破坏 M6 语义——已列入「假设与待澄清」第 1 条。

## 2. 用户故事与验收标准索引

| 编号 | 角色 | 故事 | 对应 AC |
|---|---|---|---|
| US-1 | 平台管理员 | 我在一个页面里配置 Jenkins/GitLab/Sonar/Nexus，凭据写进去后只看到「已配置·末四位」，换人接手也看不到明文 | AC-1、AC-10 |
| US-2 | 平台管理员 | 我关掉某个集成后，相关按钮立刻不可用并说明原因，而不是点了报 500 | AC-1.4、AC-3.1 |
| US-3 | 发布经理 | 我在发布单上点「触发构建」，能看到排队→运行→成功/失败、耗时和日志链接；失败能一键转缺陷并带上 buildId | AC-3 |
| US-4 | 研发 | 我填了 MR 链接后，需求详情能看到 MR 标题/状态/审批人/变更行数；MR 合入后需求按配置自动或经我确认推进到「已合入」 | AC-4、AC-5 |
| US-5 | 质量工程师 | 需求绑了 Sonar 项目后，质量门结论和覆盖率进快照；没配置时准入写「跳过：未配置」，而不是假装通过 | AC-6 |
| US-6 | 发布经理 | 发布单的制品版本从 Nexus 检索选取，sha256 可校验，RELEASE→PROD 晋级有记录 | AC-7 |
| US-7 | 审批人 | 我打开发布单，9 项准入逐项可见：通过几项、失败几项、跳过几项、为什么跳过；适配器挂了就是失败，不是跳过 | AC-8 |
| US-8 | 开发/QA | 我跑 `make integration-verify` 就能在本地验证四个适配器的正负向，退出码可判，且不影响既有门禁 | AC-9 |
| US-9 | 运维/新同事 | 我要接真实 Jenkins/GitLab/Sonar/Nexus 时，文档告诉我每个系统填什么、给什么权限、桩和真实的差异在哪 | AC-11 |

## 3. 范围与非目标

### 3.1 本轮范围内

1. **通用集成底座**：`integration_config`（四类型、base_url、凭据密文、webhook_secret、启停、超时、重试、extra_json）、`integration_sync_log`、AES-GCM 凭据只写不读、失败降级与指数退避（最多 5 次重试）、全链路审计。
2. **Jenkins**：发布单/需求页触发构建（开关关闭时禁用并提示）、`build_record`、轮询+回调双通道状态机、失败转缺陷（带 buildId）、最近构建展示。
3. **GitLab**：MR 结构化（`gitlab_project` + `mr_iid`）、webhook（MR/pipeline/approval）+ 定时兜底、`mr_sync`、MR merged 推进需求（Release 级自动/人工）。
4. **SonarQube**：需求/版本绑定 `sonar_project_key`、拉取质量门与五项度量入快照、未配置/不可用的可见语义、快照进追溯链。
5. **Nexus**：制品检索选取（保留手工兜底）、`artifact_record`、sha256 校验、RELEASE→PROD 晋级记录与审计、出站白名单防 SSRF。
6. **准入 9 项**：逐项开关、跳过≠通过、fail-closed、同源可复算、快照固化；M6 第 6 项语义不动。
7. **本地桩与一键验证**：`make integration-verify`，四系统关键端点，正负向断言，桩不进入产品运行路径。
8. **接入文档**：真实系统要填的基址/凭据/开关/网络白名单，以及桩与真实的差异。

### 3.2 非目标（本轮明确不做）

- **不连真实系统、不触发真实构建**：默认 `enabled=false`；验收只用本地桩。真实联调另立需求。
- **不做 Harbor 容器镜像**：只在 `integration_config.type` 与适配器接口上留 `HARBOR` 位，不建表、不做页面、不做准入项。
- **不做制品写操作**：不上传、不删除、不改元数据；Nexus 只读检索 + 晋级登记（登记是平台内记录，不是 Nexus 写操作）。
- **不做 CI/CD 编排**：只触发单个已配置 job，不做多 job 流水线、不做参数模板市场、不做并发构建池。
- **不做多环境配置矩阵**：每租户每类型一条配置；环境作为触发参数/字段，不拆多套凭据。
- **不替换既有 JWT/ScopeSql/审计体系**：webhook 入口是唯一的免 JWT 例外，且必须验签。
- **不做 MR 代码评审、diff 浏览、Sonar 问题列表钻取**：只取结论与度量，不做代码浏览器。

## 4. anti-goals / 反面目标

以下任意一条发生，即使所有断言变绿，本轮也应被判为失败：

1. **开关关着还能点「触发构建」，转圈十秒后弹 500**：用户分不清「没配置」「关闭了」「Jenkins 挂了」，只能去翻日志。
2. **凭据明文出现在任何一个用户能看到的地方**：接口响应、页面 DOM、导出文件、审计详情、异常消息，或日志里被拼进 URL 的 token。
3. **已配置的适配器超时，准入把该行标成「跳过」并放行**：跳过被当成通过，三项新门禁形同虚设——这正是 fail-closed 要防的事。
4. **同一个 MR merged 事件重复投递，需求被推进两次，或从「已合入」退回「开发中」**：外部事件不可信，平台自己必须幂等且单调。
5. **本地桩全绿，接真实 Jenkins 只因 job 名/参数名不同就 100% 失败，错误只有 `integration failed`**：桩证明了契约，却没证明可诊断性，真实接入等于重新开发。
6. **发布单详情打开要 8 秒**：因为页面加载时同步串行调了四个外部系统；用户感知是「平台变慢了」，而不是「集成了」。

## 5. 交互流程摘要

**流程 A｜首次接入（管理员）**
管理端 › 集成配置 → 选类型 → 填 base_url / 凭据 / webhook secret / 超时重试 / extra（job 名、project path、repo）→「测试连接」（只读探针，失败不影响已保存配置）→ 打开启用开关 → 审计留痕。凭据输入框一旦保存即变「已配置 · 末四位」，无「查看明文」。

**流程 B｜触发构建（发布经理）**
发布单详情 →「触发构建」（开关关闭则禁用 + 原因）→ 202 + QUEUED（queueId）→ 平台每 10s 轮询 + Jenkins 回调双通道 → RUNNING → SUCCESS/FAILURE/ABORTED → 详情展示 buildId、耗时、阶段、日志链接；失败行出现「转缺陷」。

**流程 C｜MR 合入（研发）**
需求填 MR 链接（自动解析 project + iid）→ GitLab webhook 或 10 分钟兜底轮询 → `mr_sync` 更新 → 需求详情「代码」卡可见 → merged 后：Release 配了自动则直接推进「已合入」，否则显示确认按钮，人工点击才推进。

**流程 D｜质量门与制品（质量/发布经理）**
需求绑 sonar_project_key →「刷新质量门」或 30 分钟定时 → 快照（OK/ERROR + 五项度量）进追溯链。发布单选制品 → Nexus 检索 → 选中回填 artifact_id/version → 校验 sha256 →（可选）RELEASE→PROD 晋级并审计。

**流程 E｜准入与提交（审批人）**
发布单详情打开 → 后端读本地同步结果（不外呼）→ 9 行清单（通过/失败/跳过/关闭 + 原因）→ 提交时服务端重算 → 有 FAIL 则 409 + 清单；仅 SKIP 可提交，但页面与提交响应都显示「跳过 z 项」，不出现「全部通过」。

## 6. 验收标准

> 所有断言均可用 HTTP 状态码、DB 行数、固定文案、脚本退出码复核。数值型上限在本机（开发测试环境）测得。

### AC-1 集成配置底座：四类可管、凭据只写不读、启停生效

- **1.1** 管理端「集成配置」页有 JENKINS / GITLAB / SONAR / NEXUS 四张卡，每租户每类型一条；字段至少含 `base_url`、`enabled`、`timeout_ms`、`max_retries`、凭据、`webhook_secret`、`extra_json`；保存后刷新仍存在。
- **1.2** `GET /api/integration/configs` 对凭据只返回 `credentialConfigured=true` 与 `credentialLast4`（末 4 位），响应 JSON 任意层级、任意嵌套字段都不含明文；`PUT` 传空串**不得**清空凭据，清空必须走独立动作 `DELETE /api/integration/configs/{id}/credential` 并二次确认。
- **1.3** 凭据以 AES-GCM 密文落库，带密钥版本前缀（如 `v1:`）；同一明文两次加密结果不同（随机 nonce）；密钥只来自环境变量/配置中心，缺失时应用启动失败且错误消息含变量名（不加解密后门、不落明文副本）。
- **1.4** `enabled=false` 时：相关业务接口返回 **409 `INTEGRATION_DISABLED`**（不是 500、不是超时）；页面触发入口禁用并显示「Jenkins 未启用（管理端 › 集成配置）」这类具体原因；准入对应项为 `SKIP(NOT_CONFIGURED)`。
- **1.5** `base_url` 仅接受 `http/https`、长度 ≤500、不得含 userinfo；非法值 400 且不落库。「测试连接」失败返回 200 + `ok=false` + 脱敏原因（不含凭据），不改变已保存配置。
- **1.6** 每次配置新增/修改/停用/凭据轮换写 `audit_log`（`action=INTEGRATION_CONFIG_UPDATED`），`detail_json` 中凭据字段固定为 `***`。

### AC-2 同步日志、重试上限与失败降级

- **2.1** 每次出站尝试写 `integration_sync_log`：`type`、`direction(OUT/IN)`、`operation`、`request_digest`（脱敏摘要 ≤500，不含 token/Authorization）、`http_status`、`result(SUCCESS/FAILED/IGNORED/RETRY_EXHAUSTED)`、`error_code`、`duration_ms`、`retry_count`、`correlation_id`、`created_at`；同一次业务动作的多次尝试共享 `correlation_id`，可按它聚合。
- **2.2** 仅对连接失败/超时/5xx/429 重试；401/403/404/422 立即失败**不重试**；指数退避 1/2/4/8/16s（±20% 抖动），**重试上限 5 次（总尝试 ≤6）**；达到上限写 `RETRY_EXHAUSTED` 并产生一条站内告警（notification）。
- **2.3** 非准入路径的集成失败不阻断主流程：需求保存、发布单列表、追溯查询等接口仍返回其业务结果（200），页面只显示「集成暂不可用（已降级）」+ 重试入口；异常栈不得返回前端。
- **2.4** 所有对外 HTTP 有显式超时（connect ≤2s，read=`timeout_ms`，默认 5s）；桩 sleep 10s 时调用在 ≤7s 内失败并记 `TIMEOUT`，请求线程不挂起。
- **2.5** 多实例/重复轮询：同一外部对象的重复回调或并发轮询只产生一次状态迁移（唯一键或条件更新），第二次 200 且无副作用。
- **2.6** 审计事件齐备且可反查：`BUILD_TRIGGERED` / `MR_SYNCED` / `SONAR_PULLED` / `ARTIFACT_SELECTED` / `ARTIFACT_PROMOTED`，均含 `biz_type`/`biz_id`。

### AC-3 Jenkins：触发、双通道回写、失败转缺陷

- **3.1** 发布单详情有「触发构建」，展示 job 名与目标环境；`enabled=false` 时按钮禁用 + 具体原因；直接 POST 接口返回 **409 `INTEGRATION_DISABLED`**，`build_record` 行数不变。
- **3.2** `enabled=true` → 返回 **202** + `build_record(status=QUEUED, queue_id, job_name, triggered_by)`；同一发布单已有 QUEUED/RUNNING 构建 → **409 `BUILD_IN_PROGRESS`** + 现有 `build_id`，不新增行（触发幂等）。
- **3.3** 状态机 `QUEUED → RUNNING → SUCCESS / FAILURE / ABORTED`；非法迁移返回 **409 `INVALID_BUILD_TRANSITION`** 且原值不变；终态记录 `duration_ms`、`console_url`、`stages_json`（至少 `compile`、`unit-test` 两个阶段名与各自结果）。
- **3.4** 双通道：轮询默认 10s（可配 5–60s），回调 `POST /api/integration/webhooks/jenkins/{configId}`；验签失败 **401 且不落库**；同 `buildId + status` 重复回调幂等 200；乱序（先 RUNNING 后 QUEUED）拒绝回退，记 `OUT_OF_ORDER`。
- **3.5** 2h 未到终态 → `FAILURE` + `error_code=BUILD_TIMEOUT`，UI 显示「超时未回写」并可重试，不静默卡在 RUNNING。
- **3.6** 失败转缺陷：生成 defect（title 含 `job #buildId`，description 含 console_url 与阶段摘要，`source=BUILD`，关联需求/版本），返回 defect code；重复点击返回同一 defect（200），不重复建。
- **3.7** 发布单详情展示最近 5 条构建（状态/耗时/触发人/时间/日志链接）；`console_url` 仅允许与配置 `base_url` 同源或在白名单内，否则置空并记 `CONSOLE_URL_REJECTED`。

### AC-4 GitLab：MR 结构化与同步

- **4.1** 保存 MR 链接时解析 `https://host/group/sub/project/-/merge_requests/123`（嵌套 group）与 `group/project!123` 两种形式，落 `gitlab_project` + `mr_iid`；无法解析时仍 200 保存原 `mr_url`，结构化字段 NULL，UI 标「未结构化（不参与 MR 准入）」。
- **4.2** 需求详情「代码」卡展示 MR 标题、状态（opened/merged/closed）、源→目标分支、审批人、变更行数（`+x/−y`）、最后同步时间；数据只来自 `mr_sync`，页面不直连 GitLab。
- **4.3** Webhook `POST /api/integration/webhooks/gitlab/{configId}` 接受 merge_request / pipeline / approval 事件；验签失败 **401 且 `mr_sync` 行数不变**；未知事件（push、note 等）→ **200 `{"ignored":true}`** + `sync_log(IGNORED)`，不 500；同一事件重复投递幂等（不重复写、不重复推进）。
- **4.4** 兜底轮询默认 10 分钟（可配 5–60），只拉「OPENED 且 7 天内有活动」的 MR；GitLab 不可用时只记日志，需求详情其他区域正常渲染。
- **4.5** `mr_sync` 唯一键 `(tenant_id, gitlab_project, mr_iid)`；旧事件按事件时间拒绝覆盖新状态；`state`、`pipeline_status`、`approvals_json`、`changed_lines`、`last_event_at` 均落库。
- **4.6** 隔离：跨租户/域/厂商查询 MR 或关联需求返回 404，不泄露存在性。

### AC-5 MR merged 推进需求

- **5.1** Release 级配置 `mrAutoAdvance`，默认 **false（人工确认）**。
- **5.2** `true`：merged 事件后研发需求（RD）状态推进到 `MERGED`（「已合入」），写 `audit(REQ_MR_MERGED_ADVANCE)` 并记录 `mr_iid`；同一事件重复投递第二次返回 200 且状态不变。
- **5.3** `false`：需求详情显示「MR 已合入，推进到已合入？」；人工确认后才推进；取消不改变任何状态。
- **5.4** 只推进 RD；BUS/PDR 只展示不推进；状态机不允许时返回 409 + 具体原因，不出现半更新。

### AC-6 Sonar：门禁快照与「跳过」语义

- **6.1** 需求可填 `sonar_project_key`（≤200，字符集 `[A-Za-z0-9_.:-]`），未填则继承 Release 默认 key；非法字符 400；清空后回退继承。
- **6.2** 手动「刷新质量门」+ 30 分钟定时拉取 gate status 与五项度量（coverage、bugs、vulnerabilities、code_smells、duplicated_lines_density），写快照（`source=SONAR`、`status=OK/ERROR`、metrics、project_key、fetched_at、hash）；同 hash 幂等不重复插入。
- **6.3** 准入第 8 项三态：未配置（无 key / Sonar 关闭）→ `SKIP(NOT_CONFIGURED)`；已配置但无快照或拉取失败 → `FAIL(ADAPTER_UNAVAILABLE / SNAPSHOT_MISSING)`；快照 OK → PASS；ERROR → FAIL 并列出至少 1 条未达标阈值（metric / actual / threshold）。
- **6.4** 跳过原因必须具体：行内显示「跳过：Sonar 未配置」/「跳过：Sonar 已关闭」/「跳过：需求未绑定 project key」，**不得只显示「跳过」**。
- **6.5** 快照进需求追溯链（`SONAR_GATE` 节点可跳快照详情）；追溯覆盖以「存在快照」判定，空记录不得算已覆盖。
- **6.6** 快照只增不改；历史版本详情回放看到当时的结论，不随重算变化。

### AC-7 Nexus：检索、sha256、晋级、SSRF

- **7.1** 发布单创建/编辑的「制品版本」支持从 Nexus 检索（repo + 关键字，返回 ≤50 条），选中回填 `artifact_version` + `artifact_id`；保留手工输入（`source=MANUAL`）。
- **7.2** `artifact_record` 字段完整：`repo`、`group_id`、`artifact_name`、`version`、`packaging`、`sha256`(64 hex)、`size_bytes`、`uploader`、`download_url`、`source(NEXUS/MANUAL)`；唯一键防重复登记。
- **7.3** 准入第 9 项：有记录且 sha256 与 Nexus 元数据一致 → PASS；不一致 → FAIL，detail 给期望/实际（前 12 位，完整值放 evidence）；`artifact_id` 为空 → `SKIP(MANUAL_ARTIFACT)`；Nexus 未配置 → `SKIP(NOT_CONFIGURED)`。
- **7.4** 晋级 RELEASE→PROD：sha256 一致才允许，写新记录（`promoted_from_id`）+ `audit(ARTIFACT_PROMOTED)`；不一致 **409 `CHECKSUM_MISMATCH`** 且不写；重复晋级幂等返回已有记录。
- **7.5** SSRF：检索/下载 URL 主机必须在 `base_url` 主机或显式白名单（host:port）内；任意 URL（`http://169.254.169.254/`、`file://`、非 http(s)）→ **400 `URL_NOT_ALLOWED`**，目标主机收到 **0 次**请求；重定向跨主机同样拒绝。
- **7.6** 权限：跨租户 `artifact_id` 返回 404；上传人/大小等字段不泄露其他租户信息。

### AC-8 准入 9 项：逐项开关、跳过≠通过、fail-closed、同源复算

- **8.1** 固定顺序九项：① 关联需求测试通过 ② 无未关闭 P0/P1 ③ 需求级审批通过 ④ MR 链接已填 ⑤ 人天已填 ⑥ 质量门（M6 报告+门禁）⑦ 最近一次构建成功 ⑧ Sonar 质量门通过 ⑨ 制品在库且 sha256 一致；接口与页面同一份数据。
- **8.2** 逐项开关 `admission_item_config(tenant_id, item_code, enabled)`：①②③⑥ 必开（关闭 → **409 `ITEM_LOCKED`**）；④⑤⑦⑧⑨ 可关；关闭行状态 `CLOSED`，不计 PASS/FAIL/SKIP；分母恒为 9，结论行显示「通过 x / 失败 y / 跳过 z / 关闭 w」。
- **8.3** 跳过≠通过：SKIP 不阻断提交，但**绝不计 PASS**；页面与接口必须给出跳过数与每行原因；断言「3 项跳过时界面不出现 9/9 或 100%」。
- **8.4** fail-closed：已启用且已配置但取数失败/超时/无快照 → 该项 **FAIL**（带 `error_code`），提交返回 **409 `ADMISSION_FAILED`**；只有「未配置/关闭」才允许 SKIP。
- **8.5** 快照固化：`admission_result_json` 含 9 行（code/name/status/detail/evidence/skipReason/checkedAt）+ `signature`；证据变化 → `stale=true` 且页面提示「数据已变化，请重新校验」；提交前服务端强制重算。
- **8.6** 性能：准入 GET P95 ≤ **500ms**；评估路径不直接外呼四个系统（只读本地同步结果），外呼由刷新/轮询异步完成。
- **8.7** M6 第 6 项语义不变：仍从 deliver 只读端口取「报告 + 门禁规则结果」；legacy 单据 `gate=null / legacy=true` 的渲染保持；第 7/8/9 项不得覆盖或合并第 6 项。

### AC-9 本地桩与一键验证

- **9.1** `make integration-verify` 一键执行：启动本地桩（默认 `127.0.0.1:19090`，Node 实现、无外网依赖），`trap` 清理进程；全绿 **exit 0**，任一失败 **exit 1**；输出 `#SUMMARY integration-verify pass=N total=N fail=N`，`TOTAL==0` 视为失败。
- **9.2** 断言 ≥ **40** 条，四个适配器各 ≥6 条（正/负向都有）；覆盖成功、非 2xx（401/500）、超时、签名错误、重复事件、乱序事件、未知事件、重试上限（桩记录请求次数 = 6 后停止）、触发幂等、SSRF 拒绝。
- **9.3** 桩不进入产品运行路径：产品代码不依赖桩包，桩只在测试脚本生命周期内启停，不注册 Nacos、不改产品配置；停桩后平台仍可正常启动（四类集成关闭态）。
- **9.4** 既有门禁不回归：`make verify` 14/14、`make platform-verify` 167/167、`make platform-test-negative` 25/25、`make quality-verify` 347/347、`make platform-unit`、`make lint`、web `typecheck`/`build` 全绿；**断言数只增不减**。
- **9.5** 本地默认关闭：四类 `enabled=false`，平台启动后 60s 内 `integration_sync_log` 无 `direction=OUT` 行（不偷偷外呼）。

### AC-10 安全红线：凭据、验签、隔离

- **10.1** 用固定测试凭据（如 `S3cr3t-IT-2026`）全仓检索：接口响应、页面源码、`audit_log`、`integration_sync_log`、导出文件、异常消息、控制台日志——**0 命中**。
- **10.2** Webhook 验签失败或时间戳超出 ±300s → **401**，不落库，`sync_log(SIGNATURE_INVALID)`；重放同一请求同样 401。
- **10.3** 跨租户/跨厂商访问配置、构建、MR、制品 → 404/403 且不泄露存在性；导出不含凭据、不含带 token 的 URL。
- **10.4** AES 密钥不出现在源码与仓库配置中；`.env.example` 只放占位符；密钥轮换后旧密文仍可解（版本前缀）。
- **10.5** 所有出站（Nexus 检索/下载、console_url 校验）只允许 http(s) + 主机白名单；白名单外请求在发起前拒绝。

### AC-11 真实环境接入文档

- **11.1** README 新增「集成对接」一节，配置页内提供「如何接入」抽屉；逐系统列出：`base_url` 示例、凭据类型与**最小权限**（Jenkins API Token + Job/Build 权限；GitLab bot token `api`/`read_api`；Sonar user token + Browse；Nexus 只读 + 晋级所需权限）、必填 `extra`（job 名、project path、repo 名）、回调地址与 secret 配置方法、网络白名单。
- **11.2** 桩与真实差异表 ≥6 行：鉴权方式、分页、时间字段格式、webhook 签名、速率限制、错误码；明确「桩通过 ≠ 真实系统已联调」。
- **11.3** 写清本地开启步骤：填配置 → 测试连接 → 打开开关 → 触发；以及密钥环境变量名与默认关闭的事实。

## 7. 优先级

| 优先级 | 内容 | 说明 |
|---|---|---|
| **P0** | AC-1 底座、AC-2 日志/重试/降级、AC-3 Jenkins、AC-8 准入 9 项、AC-9 桩与回归、AC-10 安全、AC-11 文档 | 缺任何一项本轮验收不成立；先做底座与 Jenkins，再做准入 |
| **P1** | AC-4/AC-5 GitLab、AC-6 Sonar、AC-7 Nexus | 同一轮内并行开发，允许晚于 P0 半天合入；三项都必须在合并前有正负向断言 |
| **P2** | Sonar 门禁趋势图、制品批量检索优化、构建阶段耗时对比、MR 审批人提醒 | 本轮不做，不阻塞验收；接口与数据先留好 |

## 8. 依赖与风险

| 风险/依赖 | 影响 | 缓解 |
|---|---|---|
| 无演示环境，四个真实系统都不可连 | 桩只能证明契约，不能证明真实兼容（分页、字段大小写、鉴权头、时间格式） | 适配器接口 + 契约测试 + 接入文档差异表；真实联调单列需求，首个真实接入必须留 1 天缓冲 |
| 准入新增 3 项，M6 取数预算 500ms 已被门禁/缺陷/质量门占满 | 串行外呼会让准入超时，或逼出「跳过」的偷懒实现 | 准入只读本地快照；外呼由刷新/轮询异步做；AC-8.6 以 P95 断言锁住 |
| `quality_gate_snapshot` 被 M6 质量门独占（`gate_id`/`deploy_order_id` NOT NULL） | 直接塞 Sonar 快照会污染第 6 项回放，或被迫重复建表 | 加 `source` 维度（QUALITY_GATE/SONAR）、放宽绑定列、历史行 `source=QUALITY_GATE` 不变；技术方案需给出回放断言 |
| Webhook 入口免 JWT | 公网暴露面增加，可能被打爆或伪造 | 统一 HMAC + 时间戳、GitLab 原生 Secret Token、验签失败不落库、入口限流；不依赖 IP 白名单（GitLab 出口不固定）|
| AES 密钥只在本机 env | 换机/换人后密文不可解，误配会让集成全挂 | 启动即校验密钥存在并 fail-fast；密钥版本前缀支持轮换；`.env.example` 说明 |
| 外部事件乱序/重复 | 需求状态被倒退、构建记录错乱 | `mr_sync`/`build_record` 唯一键 + 事件时间单调 + 状态机拒绝回退（AC-3.4/4.5）|
| 既有 6 项文案散落在页面/脚本/文档 | 漏改会出现「6 项」与「9 项」并存 | 文案单点化（常量/组件），`grep "6 项"` 纳入 lint 或验收断言 |
| 未提交的任务夹目录与 20+ 实现文件混在一个提交 | 评审困难、历史不可读 | 工程约束里要求先单独提交任务夹，再提交实现（见 §9）|

## 9. 工程约束（原始指令逐字保留）

- **分支**：`feat/ci-integration-adapters`（当前已在该分支）。工作区当前未提交改动只有新增任务夹 `docs/teamflow/20261109-r5-ci-integration-adapters/`；**开发前先单独提交该目录**，实现提交保持独立，不要让文档与 20+ 实现文件混在一个 commit。不新建第二个分支，不 rebase 已合并的 main。
- **技术栈与分层**：沿用 Spring Cloud 骨架与既有分层——req-service 8082 / deliver-service 8083 / gateway 8080 / Vue3+Vite 5173 / MySQL + Flyway / JWT + ScopeSql；网关路由仍用 Java `RouteLocator`，不写 YAML 路由；不引入 Docker、Gradle、Kotlin、MQ、Redis。
- **数据**：新增 `integration_config`、`integration_sync_log`、`build_record`、`mr_sync`、`artifact_record`；`quality_gate_snapshot` **复用不重复建表**（按 §8 风险项加来源维度）；`requirement` 增 `gitlab_project`、`mr_iid`、`sonar_project_key`；`deploy_order` 增 `build_id`、`artifact_id`（`quality_gate_id`/`quality_gate_snapshot_id`/`report_id` M6 已有，不重复加）。迁移只写在 `req-service/src/main/resources/db/migration/`，必须可重复执行。
- **适配层**：所有对外调用走单一适配层接口（四实现 + 一个 HTTP 客户端统一超时/重试/日志），业务代码不得散落 HTTP；不得直连外部数据库（只经既有只读模型/接口）。
- **凭据**：不得出现在日志/审计/导出/前端响应里；AES-GCM 加密只写不读；页面只回显「已配置/末四位」。
- **降级**：除准入校验项外不阻断主流程；指数退避最多 5 次重试；全链路审计。
- **验证**：本地桩只在测试脚本里启动，**不进入产品运行路径**；触发开关默认关闭，本轮不连真实实例、不触发真实构建。
- **不破坏既有门禁**：`make verify` 14/14、`platform-verify` 167/167、`platform-test-negative` 25/25、`quality-verify` 347/347、`platform-unit`、`lint`、web `typecheck`/`build` 全绿。
- **仓洁（.gitignore）**：现有 `.gitignore` 已覆盖 `target/`、`node_modules/`、`dist/`、`.env*`、`logs/`、`.run/` 等；本轮新增的桩运行态与测试产物必须补忽略——`scripts/tests/integration/.stub-run/`、`*.pid`（桩进程）、`test-results/`、`playwright-report/`（若引入）、`*.hprof` 已有；**不得**把 `.env`、证书、抓包/日志文件提交。提交前 `git status` 只应出现源码、迁移、脚本、文档与 `.env.example` 占位。
- **提交时机**：实现与 `make integration-verify` 全绿后提交；提交信息写明「M7 集成对接」与四适配器范围；不提交构建产物与运行态。

## 10. 假设与待澄清

> 每条都是需要用户拍板或本轮先按推荐值执行的口径；括号内为 `openQuestions` 的 kind。

- **准入到底是 8 项还是 9 项（fact）**：需求写「5 项扩到 8 项」，但 M6 已把质量门做成第 6 项（代码与页面均为 6 项），且本轮要求不破坏第 6 项。推荐按 **9 项**（6 + 构建 + Sonar + 制品）；若坚持 8 项，只能合并既有项，会破坏 M6 语义与 AC-12。默认：9 项。
- **「已配置但不可用」算跳过还是失败（taste）**：需求原文说 Sonar「未配置或不可用时…跳过且不计 FAIL」，但同一份验收又要求 fail-closed。推荐：**未配置/关闭 → SKIP；已配置但取数失败/超时/无快照 → FAIL**；否则门禁在系统挂掉时自动放行，等于没有门禁。若选「不可用也跳过」，改一处枚举与断言即可，但需要在验收报告里单列风险。默认：fail-closed。
- **MR 合入是否自动推进需求（taste）**：自动省事但会绕过人工确认；推荐默认 `mrAutoAdvance=false`（人工确认），按 Release 打开。默认：人工确认。
- **手工制品是否允许通过准入（taste）**：保留手工兜底意味着第 9 项无法校验，推荐允许填写但该项 `SKIP(MANUAL_ARTIFACT)` 并在页面明示；若要求强制 Nexus，则历史/应急单据永远过不了第 9 项。默认：允许手工 + SKIP 明示。
- **准入提交前是否强制实时刷新四系统（taste）**：强制最新会击穿 500ms 预算并把外部故障带进发布流程；推荐 TTL 10 分钟 + `stale` 提示 + 手动刷新，提交只重算本地快照。默认：不强制实时刷新。
- **晋级目标仓库名（fact）**：`PROD` 在 Nexus 中的真实仓库名无法从仓库内推断（如 `maven-releases-prod`）；推荐做成配置项 `prodRepo`，未配置时晋级按钮禁用并提示「先配置生产仓库」。默认：配置项，未配置禁用。
- **（无其它待澄清）**：Harbor 本轮不实现、制品库为 Nexus、本地即测试环境、默认不触发真实构建，均已在用户澄清中确认，不再列为问题。

## 11. 里程碑建议

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| M7.1 底座 | integration_config / sync_log / 加密 / 统一客户端 / 重试 / 审计 / 配置页 / 桩框架 | AC-1、AC-2、AC-9.1/9.3 通过；四类默认关闭 |
| M7.2 Jenkins + 准入 7 项 | 触发/轮询/回调/转缺陷 + 构建项接入准入 | AC-3 全绿；准入第 7 项三态正确 |
| M7.3 GitLab + Sonar + Nexus | MR 结构化与同步、merged 推进、Sonar 快照、制品检索/sha256/晋级/SSRF | AC-4~AC-7 全绿；准入 9 项 |
| M7.4 收口 | 9 项准入全量断言、`make integration-verify`、既有门禁回归、接入文档 | AC-8/9/10/11 全绿；`make test` 与四套既有门禁不回归 |

（完）
