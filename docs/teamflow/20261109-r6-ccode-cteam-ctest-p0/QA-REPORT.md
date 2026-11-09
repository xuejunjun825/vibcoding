# M8 CCode / CTeam / CTest P0 验收测试报告（复验轮）

- 任务夹：`docs/teamflow/20261109-r6-ccode-cteam-ctest-p0/`（PRD 20 条 AC、DESIGN、TECHNICAL）
- 分支：`feat/ccode-cteam-ctest-p0` · 基线提交 `9072719` · 未提交改动 80 项（40 M + 40 ??，交付主体）
- 结论：**可验收** —— 上轮 1×P1 + 2×P2 全部关闭并经原命令复验；无 P0/P1/P2；保留 6 条 P3（测试资产/索引/门禁纪律），不阻断验收。

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 被测能力 | 甘特（AC-1～3）、Wiki（4～7）、价值流（8～9）、分支策略（10～11）、CR（12）、代码质量（13）、用例评审（14）、测试数据（15）、执行器（16）、隔离（17）、迁移/种子（18）、门禁（19）、性能（20） |
| 运行时 | JDK 25.0.2 · Spring Boot 4.0.9 · MySQL 9.6.0 · Node 22.22.0 · Vue 3 + Vite |
| 服务 | 网关 8080（404 根）· req 8082（401）· deliver 8083（401）· web 5173（200）；req jar 19:01 含修复 |
| 数据库 | `devops_platform` 为修复轮全新库（Flyway V1→V9 installed_on 2026-11-09 19:02:03，`platform-seed` 幂等两遍）；本轮全部门禁在该全新库上复跑。辅助库 `devops_platform_t5` 供 T5 探针 |
| 浏览器 | 沙箱内 Chromium 无法启动（host token 缺 PROCESS_SET_INFORMATION/PROCESS_SUSPEND_RESUME）→ **环境限制，非交付缺陷**；改用真实 HTTP 栈 + node:vm/SSR 桩 |
| 库状态说明 | 残留租户 2/3（`t2-190239`、`t2-190459`）由 `platform-verify` 的建租户用例产生，`TenantProvisioner` 不播种协作域数据；它们是 N-07 的成因。T5 探针库曾用 API/人工清理污染行（见 N-09） |

## 2. 门禁总览（本轮全部实际执行）

| 门禁 | 命令 | 结果 |
|---|---|---|
| lint | `bash scripts/tests/lint.sh` | 16/16 PASS |
| typecheck | `cd web && npm run typecheck` | exit 0，0 error |
| build | `cd web && npm run build` | exit 0，5.36s |
| unit | `bash scripts/platform-unit.sh` | 234/234 PASS |
| platform | `bash scripts/platform-verify.sh` | **168/168 PASS**（含 `V5 步 7 的 5 条质量指标在全新库可重放（D-03）`） |
| quality | `bash scripts/quality-verify.sh` | 348/348 PASS |
| integration | `bash scripts/integration-verify.sh` | 230/230 PASS（s0 33/s1 39/s2 43/s3 19/s4 32/s5 28/s6 26/s7 10） |
| r1 verify | `bash scripts/verify.sh` | 14/14 PASS |
| negative | `bash scripts/platform-test-negative.sh` | 25/25 PASS |
| collab | `bash scripts/collab-verify.sh` | 263/263 PASS（s0 26/s1 28/s2 48/s3 30/s4 32/s5 26/s6 18/s7 31/s8 24） |
| perf | `bash scripts/collab-perf.sh` | 5/5 PASS（甘特 300 条 5ms/1500、Wiki 检索 9ms/800、树 5ms/300、看板 33ms/2000、列表 4ms/500） |

## 3. 上轮 checker 逐条复跑（回归契约）

| checker | 结果 | 说明 |
|---|---|---|
| `iface-static-check.mjs` | 117/117 PASS | routes 276 / apiCalls 31 / imports 71 |
| `t7-collab-contract-check.mjs` | 28/28 PASS | 刻度、路由、词典、409 分派 |
| `t8-ssr-smoke.mjs` | 43/43 PASS | CCode/CTest 页 SSR 状态 |
| `ac1-release-actual-probe.sh` | 2/2 PASS | D-01 原命令，探针单已清理 |
| `ac14-review-probe.sh` | 13/13 PASS | 独立夹具，天然可重复 |
| `t6-data-note-attachment.sh` | 9/9 PASS | AC-15 附件锚点（req 侧） |
| `t5-ccode-api-check.sh` | 21/21 PASS | 清掉自身历史残留配置后（N-09） |
| `t5-gitlab-stub-e2e.sh` | 10/10 PASS | MATCH/DRIFT、零外呼、幂等、脱敏 |
| `flow-e2e.sh` | 24/24 PASS | 看板=字典、零样本不补 0、无审计排除、行级隔离 |
| `gantt-e2e.sh` | 53/55 | 2 条依赖 T2 期 612 条夹具（N-08） |
| `collab s1` 连跑 3 次 | 3×28/28 | 上轮「s1 计数器竞态致 3 跑 1 红」未复现 |
| `t1-v9-schema-check.sh` | 34/42 | 8 条为「= N×TENANTS」断言，受残留租户影响（N-07） |

## 4. 修复复验对比（round-2 级，非开放缺陷）

| 编号 | round-2 级 | 原判定 | 本轮复验 | 判定 |
|---|---|---|---|---|
| D-01 发布单实际期 | P1 | publish 未接 ActualPeriodRecorder | `ac1-release-actual-probe.sh` 2/2（release=40 PUBLISHED actual_start=2026-11-09）；`s1` 新增真实审批链路段亦绿（ReleaseService.java:299） | **关闭** |
| D-02 s6 门禁不可复跑 | P2 | 第二次 11/18 | `collab-verify.sh s6` 连跑两次均 `pass=18 total=18 fail=0`（自建 `s6评审-${RUN_TAG}-$$` 并软删自清） | **关闭** |
| D-03 全新库缺 5 条指标 | P2 | platform 166/167 | `platform-verify.sh` 168/168；`metric_definition` t1=14 distinct（4 内置+5 FLOW+5 质量），由 SeedService.replayQualityMetricSeed 重放 V5 步 7 | **关闭** |
| D-04 s8 跨租户断言缩水 | P3 | 无 tenant 2 时 INFO 跳过（17/17） | 有 tenant 2 时 24/24；跳过仍为 INFO（未计 SKIP），断言数随库状态变化 | **仍开放** |
| D-05 audit_log 冗余索引 | P3 | idx_audit_req 与 idx_audit_biz 列相同 | 仍相同（4 行索引），Flyway 启动仍报 Duplicate index | **仍开放** |
| 上轮 P3 观察：s1 计数器竞态 | P3 | 3 跑 1 红 | 本轮 3 跑 3 绿（28/28），未复现 | **关闭（未复现）** |
| 上轮 P3 观察：三组件无 focus 样式 | P3 | 无焦点环 | `web/src/components/collab` 与 `views/collab` 共 8 个文件有 `focus-visible` | **关闭** |
| 上轮 P3 观察：`--gantt/--wiki/--flow` 未入 tokens | P3 | 局部声明 | `web/src/styles/tokens.css:103-128` 已集中登记 22 个变量 | **关闭** |

## 5. 用例与结果（按 AC）

| AC | 证据 | 结果 |
|---|---|---|
| 1 甘特三视图/缩放/≤300/实际条 | s1 28 + gantt-e2e 53/55 + ac1 2/2 + perf 5ms | 通过（截断夹具缺口见 N-08） |
| 2 改期唯一入口/审计/乐观锁/token | s1（400/409/404/403、一次性确认码、GANTT_RESCHEDULE） | 通过 |
| 3 服务端 warnings | s1 + t7（前端只读 warnings 码，无日期二次计算） | 通过 |
| 4 Wiki 六出口同源/树≤4/Markdown 净化 | s2 48 + s8 24 + iface | 通过 |
| 5 版本不可变/diff/回滚 vN+1 | s2（版本、回滚、行级 diff） | 通过 |
| 6 关联/软删级联/回收站/超期 409 | s2 | 通过 |
| 7 ngram 检索先过滤可见 | s2 + perf 9ms | 通过 |
| 8 价值流 7 阶段可配/审计 | s3 30 + flow-e2e 24 | 通过 |
| 9 指标同源/P90/瓶颈三处差 0 | s3 + flow-e2e（FLOW_WIP 389=389、零样本 null） | 通过 |
| 10 分支策略四类校验 | s4 32 | 通过 |
| 11 三态核验/零外呼/关闭不假绿 | s4 + t5-gitlab-stub 10 + t5-ccode 21 | 通过 |
| 12 mr_sync 4 列 + 评论幂等 | s5 26 + t5 21 | 通过 |
| 13 Sonar 快照空态不补 0 | s5 + t5 21（card=null） | 通过 |
| 14 评审钉版本/改版失效/重复 409 | s6 18×2 + ac14 13 | 通过 |
| 15 测试数据说明 + 锁定 + 审计摘要 | s7 31 + t6 9 | 通过 |
| 16 执行器五态 + executorCode 幂等 | s7 31 | 通过 |
| 17 全入口隔离/权限码/路由表 | s8 24 + negative 25 + iface 117 | 通过 |
| 18 11 表/回填=0/种子/文档 | t1-v9 34/42 + s0 26 + platform 168 | 通过（门禁口径见 N-07） |
| 19 既有门禁不回退 | 第 2 节全绿（collab 263 / quality 348 / integration 230 / negative 25 / unit 234 / lint 16） | 通过 |
| 20 五项 P95 | collab-perf 5/5（最大 33ms/2000ms） | 通过 |

## 6. 架构与接口一致性核对（0a）

| 单点 | 落位（grep 实证） |
|---|---|
| WikiVisibility 唯一谓词 | 8 处调用（WikiSpaceService/WikiPageService/WikiSearchService/WikiVersionService），无出口自写 domain 过滤 |
| GanttWarnings 唯一告警 | 计算只在 req 侧；`GanttBar.vue:46-47`、`GanttTimeline.vue:112-135` 仅按 code 渲染 |
| FlowMetricFormulas 唯一公式 | FlowQuery.java:104-105,178、FlowMetricService.java:63-64,75；`MetricQuery.preview` 委托 values（flow-e2e 看板=字典） |
| ExecutorHealth 阈值单点 | `ONLINE_HOURS=24` / `STALE_HOURS=72` 常量，页面不判 |
| ActualPeriodRecorder | 3 处调用：RequirementService.java:345、ReleasePublishCascade.java:94、ReleaseService.java:299；全仓无直接 setActual* |
| PermissionMatrix / RouteTable | 14 新码（42→56 格）；`/gantt`、`/wiki`、`/flow`、`/code/policies`、`code-quality`（含 `/releases/{id}`）、`mr(-comments)`/`mr/refresh`、`/testcase-reviews`、`/test-executors` 均先于兜底登记（PermissionRouteTable.java:71-125） |
| 附件删除 | `AttachmentController.java:58` 已有 `@DeleteMapping("/{id}")`（上轮 out-of-scope 项已补） |

- 偏差 1：PermissionMatrix 蓝图写 41→55，实际 42→56（蓝图算错，实现正确）。
- 偏差 2：`drill` 返回 `PageResponse`（蓝图写 List），前端表格需要 total；`FLOW_*` 预览走 FlowMetricService，非 FLOW 指标只回定义元数据（不编造数值）。
- 重复实现：无 `web/src/utils/diff.ts`；告警/公式/阈值/实际期均单点，前端无第二套计算。
- 接口一致性（0a）：`iface-static-check.mjs` 117 断言（15 组跨模块成员 + 31 个前端 HTTP 调用对 276 条后端路由 + 71 个 `@/api/*` 导入符号）0 失败；`typecheck` 0 error 进一步证明成员与参数形状成立。
- 提交面（0c）：`git status --porcelain` 80 项，无 node_modules/target/dist/.idea/.env/*.log/__pycache__；`.gitignore` 覆盖完整；仅任务夹为 `??`（预期交付物）。
- 门禁纪律教训（非缺陷）：本轮一度在 18082 起第二个 `req-service` 并注册到 Nacos，网关 `lb://req-service` 随机路由到该实例（指向 t5 库），导致 `integration-verify` 假红 129/173；停掉实例并等负载均衡缓存过期后复跑 230/230。复跑互斥服务时必须带 `--spring.cloud.nacos.discovery.enabled=false`。

## 7. 缺陷表（仅列仍 OPEN）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-04 | P3 | 隔离门禁（s8） | 库中无第二租户时执行 `bash scripts/collab-verify.sh s8` | 跨租户断言执行，或显式 SKIP 且不计入通过 | 以 INFO 跳过 7 条，17/17 全绿（有 tenant 2 时 24/24），断言总数随库状态变化 | AC-17 | `bash scripts/collab-verify.sh s8` | 干净库同样 24/24，或输出 `SKIP=7` 且门禁不把 SKIP 记作通过 |
| D-05 | P3 | 数据库/索引 | `mysql -uroot -N -B devops_platform -e "select index_name,group_concat(column_name order by seq_in_index) from information_schema.statistics where table_schema='devops_platform' and table_name='audit_log' group by index_name;"` | audit_log 只有一套等价索引 | `idx_audit_req` 与 `idx_audit_biz` 列完全相同（tenant_id,biz_type,biz_id,created_at），写放大且 Flyway 报 Duplicate index | AC-18 | 同左 | 输出 3 行（PRIMARY/idx_audit_biz/idx_audit_user），无 idx_audit_req |
| N-06 | P3 | 门禁（collab-verify 子集模式） | `bash scripts/collab-verify.sh s6; echo exit=$?` | 子集模式按所选脚本缩放最小断言数，全绿即 exit 0（脚本用法第 7 行明示支持只跑指定脚本） | 断言 18/18 全绿但 `exit=1`：`断言总数 18 < 60`（scripts/collab-verify.sh:152 无条件套用 MIN_ASSERTS=60） | AC-19 | 同左 | 输出 `pass=18 total=18 fail=0` 且 `exit=0` |
| N-07 | P3 | 检查器（t1-v9-schema-check） | 库中存在未播种租户（`platform-verify` 连跑留下的 t2-*）时执行 `bash logs/teamflow/tf-mw8zuu49-kdqpqz/scripts/t1-v9-schema-check.sh` | 断言按「已播种租户」计数，或只对 t1 断言 | 34/42：8 条「= N×TENANTS」断言失败（stage 7×3、wiki 3×3、policy 1×3、executor 2×3、FLOW 5×3、version 4×3、两级树、v2 页），实际只有 t1 一份种子 | AC-18 | 同左 | 42/42（可改为按 tenant_code=t1 过滤，或输出 `SKIP: tenant not provisioned` 并说明） |
| N-08 | P3 | 检查器（gantt-e2e） | 重建库后执行 `bash logs/teamflow/tf-mw8zuu49-kdqpqz/scripts/gantt-e2e.sh`（需 8092 实例） | 脚本自建 ≥301 条需求，或断言改为 total>300 触发 | 53/55：`612 条需求 → truncated=true` 实际 false；`截断后只保留 level≤1` 实际 52（T2 期夹具随重建库消失；AC-1 截断仍由 s1 的 limit=1 断言覆盖） | AC-1 | 同左 | 55/55 |
| N-09 | P3 | 检查器（t5-ccode-api-check） | 先 `bash .../t5-gitlab-stub-e2e.sh`（启用 stub-gitlab 配置），再 `bash .../t5-ccode-api-check.sh`（需 18082 实例） | 脚本开头禁用/清理 GitLab 集成配置，保证「集成关闭」用例成立 | 19/21：[10] 期望 409 INTEGRATION_DISABLED 实际 GITLAB_UNREACHABLE（残留 enabled 配置指向 19090）；清掉后 21/21。产品侧行为正确（失败核验落 UNVERIFIED，未假绿） | AC-11、AC-13 | 同左 | 21/21 且无需人工清库 |

## 8. craft 观察

本轮未发现新的 craft 缺口：tokens（22 个协作域变量）与 `focus-visible`（8 个文件）均已补齐；甘特计划/实际条、Wiki 三栏 220/700/240、价值流 7×160 列均由 SSR 桩与契约检查覆盖结构，视觉细节留人工走查。

## 9. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 / 工具 | 判据 |
|---|---|---|
| 浏览器视觉与交互（甘特拖拽跟手、周末底纹、弹窗遮挡；Wiki 三栏在 1280/1920 的溢出；focus 环视觉） | 沙箱 Chromium 受 host token 限制无法启动；本机 Chrome 打开 http://127.0.0.1:5173，t1/admin，按 DESIGN.md §7 走查 | 无横向滚动条、拖拽跟手、焦点环可见、无遮挡 |
| 真实 GitLab 联调（MATCH/DRIFT、MR notes 分页/脱敏、集成关闭 409） | 当前仅桩（10/10）；需客户 GitLab 与 Token 权限（PRD openQuestion 7） | 三态判定与逐项差异正确，关闭时 UNVERIFIED 且无绿色 |
| 真实 Sonar 快照（代码质量卡有数据态） | 库内仅空态/种子快照；接入 Sonar 后核对五项度量与空态不补 0 | 有快照显示数值，无快照显示空态 |
| 读屏/纯键盘（Wiki 树 4 级、甘特条键盘改期） | NVDA / VoiceOver 实机 | 可完整操作且焦点顺序合理 |
| 大屏甘特 300 条渲染帧率 | 2560×1440 真机拖动 | 拖动无掉帧（HTTP 侧 p95 已测 5ms） |

## 10. 结论

- **功能主体成立**：collab 263、quality 348、platform 168、integration 230、negative 25、unit 234、lint 16、verify 14、perf 5/5、iface 117/117、typecheck 0 error、build 5.36s；20 条 AC 均有可执行证据。
- **修复复验**：D-01（ac1 2/2 + s1 真实审批链路）、D-02（s6 两次 18/18）、D-03（platform 168/168 含 5 条质量指标重放）全部关闭；D-04/D-05 与 4 条新增 P3 均不阻断验收。
- **判定：可验收**。P3 建议下一轮与 D-04/D-05 一并处理（N-06 只需把 MIN_ASSERTS 按所选脚本缩放，N-07/N-08/N-09 为检查器自建夹具/清理前置）。
- 复跑清单（验收后回归）：`bash scripts/collab-verify.sh` → 263/263；`bash scripts/platform-verify.sh` → 168/168；`bash scripts/quality-verify.sh` → 348/348；`bash scripts/integration-verify.sh` → 230/230（勿并起第二个 req-service 实例）；`bash scripts/collab-perf.sh` → 5/5。
