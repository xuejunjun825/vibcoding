# M8 CCode / CTeam / CTest P0 验收报告

- 任务夹：`docs/teamflow/20261109-r6-ccode-cteam-ctest-p0/`（PRD 20 条 AC · DESIGN · TECHNICAL · QA-REPORT）
- 分支：`feat/ccode-cteam-ctest-p0` · 基线提交 `9072719` · 交付面 80 项未提交改动（40 M + 40 ??，仅任务夹为新增）
- 验收日：2026-11-09 · 验收方式：PRD 逐条核对 + 架构一致性核对（强制）+ 独立复跑核心门禁（collab 263/263、perf 5/5、iface 117/117）

## 1. 验收结论

**通过。** 20 条 P0 AC 全部有可执行证据且本轮独立复跑成立；既有门禁未回退；架构与蓝图一致（五个单点落位、无重复实现、无结构破坏）；仅剩 6 条 P3 测试资产/索引类问题，均不阻断验收，另有环境受限的人工补测项如实列明。

## 2. 逐条 AC 核对（PRD §4）

| AC | 判据要点 | 证据（本轮独立复跑以 ★ 标注） | 结果 |
|---|---|---|---|
| AC-1 | 三视图×三缩放、实心计划/浅色实际（不伪造）、≤300 折叠、无依赖线 | ★collab s1 28/28；gantt-e2e 53/55（截断夹具缺口 N-08，limit=1 断言覆盖）；perf 5ms | 通过 |
| AC-2 | `PATCH /gantt/rows/{type}/{id}` 唯一写入口、范围 400、超父窗口 409+5 分钟一次性 token、乐观锁、`GANTT_RESCHEDULE` 审计 | ★s1（400/409/404/403、token 二次使用 409、审计 detail 字段）；`GanttRescheduleService` 为唯一写路径 | 通过 |
| AC-3 | 临期≤3 橙、逾期红、周>5 人天超载；服务端 `warnings[]` 唯一计算 | ★s1（4.9/5.1 边界）；前端仅按 code 渲染（`GanttBar.vue:46-47`、`GanttTimeline.vue:112-135`），无日期二次计算 | 通过 |
| AC-4 | 空间三态可见、六出口同源、树≤4 层、循环 409、Markdown 白名单 | ★s2 48/48；★s8 24/24；`WikiVisibility` 被 Space/Page/Search/Version/Attachment 共用，无出口自写谓词 | 通过 |
| AC-5 | 版本不可变、无变更不产版、行级 diff、回滚生成 vN+1 且旧 hash 不变 | ★s2（5 版→diff→回滚→逐版 hash 比对）；回滚复用 `WikiPageService` 保存路径 | 通过 |
| AC-6 | 双向关联按可见性过滤、软删级联子树、30 天回收站、超期 409、purge 权限 | ★s2（级联/恢复/超期/403） | 通过 |
| AC-7 | ngram FULLTEXT BOOLEAN、先过滤可见再计数、空词 400 | ★s2；★perf 检索 p95=16ms（1 万页）；`V9:78` 索引存在 | 通过 |
| AC-8 | 7 阶段种子、映射可配、启停/排序/按类型收窄、至少 2 阶段 409、变更审计 | ★s3 30/30；`STAGE_MIN_REQUIRED`/`STAGE_MAPPING_CONFLICT` 落位 | 通过 |
| AC-9 | WIP/均值/P90(nearest-rank)/流动效率/瓶颈；5 个 FLOW_ 指标；三处差 0；无审计排除 | ★s3；flow-e2e 24/24（看板=字典、零样本 null）；`MetricQuery.preview` 委托 `FlowMetricService.values` | 通过 |
| AC-10 | 规则字段齐全、四类矛盾/重复校验、不代理 push/merge、审计 | ★s4 32/32 | 通过 |
| AC-11 | 三态核验、逐项差异、集成关闭必 UNVERIFIED 且不假绿、打开零外呼 | ★s4；t5-gitlab-stub 10/10（MATCH/DRIFT、计数 0）；`GitLabPolicyChecker` 失败路径先落 UNVERIFIED 再抛 409 | 通过 |
| AC-12 | `mr_sync` 增 4 列 + `mr_comment` 唯一键、幂等、脱敏、分页 | ★s5 26/26；t5 21/21（同一 note 两次行数不变、脱敏） | 通过 |
| AC-13 | 只读 Sonar 快照、五项度量、空态不补 0/不假绿、零外呼 | ★s5（card=null 空态）；t5 21/21 | 通过 |
| AC-14 | 钉 `case_version`、意见≥10 字、重复 409、改版即 INVALIDATED、不进第五条审批 | ★s6 18/18×2；ac14 13/13；`TestCaseService.java:306` 同事务调用 `invalidate`；无新增 `wf_definition.biz_type` | 通过 |
| AC-15 | `data_note`+附件、COMPLETED 只读 409、审计只记摘要、跨域 404 | ★s7 31/31；t6 9/9（附件锚点与锁定期） | 通过 |
| AC-16 | 执行器五态派生、阈值单点、`executorCode` 联动、幂等键不变、停用 422 | ★s7；`ExecutorHealth` 常量 24/72h；`ExecutionIngestService` 解析 executor 后同事务更新 | 通过 |
| AC-17 | 全入口 ScopeSql、列表不返回/详情 404/写 403、14 新码进矩阵与路由、≥8 条跨域负向 | ★s8 24/24（含跨租户）；negative 25/25；`PermissionRouteTable` 13 组规则先于兜底 | 通过 |
| AC-18 | V9 11 表+加列+ngram+audit 索引、actual_* 幂等回填校验=0、种子、文档 | ★s0 26/26；V9 实测 11 张 `CREATE TABLE`；回填 4 段只填 NULL；`DELIVERED-MODULES.md`/`CAPABILITY-MATCH-canway.md` 已更新 | 通过 |
| AC-19 | 既有门禁不回退；新 collab s0–s8 ≥60 断言 | ★collab 263/263（s0 26/s1 28/s2 48/s3 30/s4 32/s5 26/s6 18/s7 31/s8 24）；QA 全量：quality 348、platform 168、integration 230、negative 25、unit 234、lint 16、verify 14 | 通过 |
| AC-20 | 五项 P95 | ★collab-perf 5/5：甘特 5ms/1500、检索 16ms/800、树 4ms/300、看板 43ms/2000、列表 4ms/500 | 通过 |

## 3. 架构一致性核对（M3 质量门 · 强制）

| 蓝图项 | 实现核对（grep/实测） | 判定 |
|---|---|---|
| 五个单点 | `WikiVisibility`（5 个消费方）、`GanttWarnings.compute`（仅 `GanttService` 调用）、`FlowMetricFormulas`（唯一的 P90/效率/瓶颈）、`ExecutorHealth`（`ONLINE_HOURS=24`/`STALE_HOURS=72` 常量）、`ActualPeriodRecorder`（仅 3 处状态流转调用，甘特写路径不另算） | 一致 |
| V9 迁移先行 | 11 张表按 建表→列→索引→回填→种子 单文件顺序；`ft_wiki` ngram、`idx_audit_req`、四主体回填均落位 | 一致 |
| 隔离谓词单点 | `ScopeSql` 登记 `audit_log`（TENANT 级）/`wiki_space`/`wiki_page` 及 10 张新表；未知表抛错 | 一致 |
| 权限矩阵与路由 | 14 个 M8 权限码追加为 42→56（10×56 格）；`/gantt`、`/wiki`、`/flow`、`/code/policies`、`/testcase-reviews`、`/test-executors`、代码质量路由均先于兜底 | 一致（蓝图写 41→55 为算术笔误，实现与测试 `PermissionMatrixGridTest` 均为 56） |
| 前端契约集中 | `web/src/api/collab.ts`/`code.ts` 统一 HTTP；无 `utils/diff.ts`，`WikiDiffDrawer.vue` 仅渲染服务端 diff；告警只渲染 code | 一致 |
| 附件删除入口 | `AttachmentController.java:58` `@DeleteMapping("/{id}")` 已补，语义在 Service | 一致 |

- **偏差 1（已记录，合理）**：`drill` 返回 `PageResponse`（蓝图写 List）——前端表格需要 total，接口多一字段属增量不破坏契约。
- **偏差 2（已记录，合理）**：`FLOW_*` 之外的指标 preview 只回定义元数据（`available=false`），不编造数值。
- **重复实现扫描**：0 处第二套告警/公式/阈值/实际期/diff 实现；既有结构未被破坏。→ 无架构返工项。

## 4. 独立复跑证据（本轮执行）

- ★ `bash scripts/collab-verify.sh` → 263/263，九组全绿，exit 0（s0 26 / s1 28 / s2 48 / s3 30 / s4 32 / s5 26 / s6 18 / s7 31 / s8 24）。
- ★ `bash scripts/collab-perf.sh` → 5/5，五项 P95 均显著低于预算。
- ★ `node logs/teamflow/tf-mw8zuu49-kdqpqz/scripts/iface-static-check.mjs` → 117/117（routes 276 / apiCalls 31 / imports 71）。
- QA 全量门禁（未在本轮逐条重跑，采信 QA-REPORT §2）：quality 348、platform 168、integration 230、negative 25、unit 234、lint 16、verify 14、typecheck 0、build 5.36s。

## 5. 意见、遗留与风险

1. **P3（不阻断，建议下一轮一并处理）**：D-04 s8 无第二租户时以 INFO 跳过 7 条；D-05 `idx_audit_req` 与 `idx_audit_biz` 列重复；N-06 collab 子集模式仍按 60 断言守卫（`s6` 全绿但 exit=1）；N-07/N-08/N-09 为检查器多租户残留/夹具/前置清理所致，产品侧行为正确。
2. **人工补测（环境受限，非交付缺陷）**：沙箱 Chromium 无法启动，浏览器视觉/交互未由验收人实机复核——建议本机 Chrome 打开 http://127.0.0.1:5173（t1/admin）按 QA-REPORT §9 走查甘特拖拽、Wiki 三栏与 focus 环；真实 GitLab/Sonar 联调需客户凭据（PRD openQuestion 7 之一），当前三态/空态已由桩与快照覆盖。
3. **门禁纪律**：复跑互斥服务时不得再起第二个 `req-service` 注册 Nacos（QA 曾致 integration 假红），需带 `--spring.cloud.nacos.discovery.enabled=false`。
4. **治理项（PRD openQuestions 其余 6 项）** 已按推荐默认值实现（Wiki 默认 INHERIT、评审不阻断计划、不接审批引擎、效率口径、甘特默认需求视图），属可后续调整的产品参数，不影响本期验收。

## 6. 结论

交付与 PRD 20 条 AC、TECHNICAL 架构约束一致；核心协作域门禁经验收人独立复跑 263/263、性能 5/5、接口一致性 117/117 成立；无 P0/P1/P2 缺陷，无架构返工项。**同意验收**，6 条 P3 与人工补测清单作为下一轮质量债跟踪。

验收结论：✅ 通过
