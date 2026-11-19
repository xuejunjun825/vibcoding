# 验收报告 · r32 演示夹具到 PUBLISHED（发版链路端到端）

- 需求：把演示夹具补成一条能真跑到 PUBLISHED 的需求，并把 r30 AC-18/AC-19 从「文档写法」补成事实：8 步真跑、两把梯子逐级留痕、负向 409 指名、门禁 S7 八条（32→40）。
- 任务夹：`docs/teamflow/20261119-r32-release-flow-e2e/`（PRD / TECHNICAL / QA-REPORT / 本报告）
- 基线：r30 审批链（`docs/teamflow/20261118-r30-...`）+ r31 提交区间判据；本轮不声明取代、不改业务语义、无迁移、无 pom 与前端依赖改动。
- 环境：macOS，JDK 25，Maven 离线仓 `.m2/repository`；演示栈在跑（网关 8080 / req-service 8082 / MySQL）。分支 `feat/demo-release-green`，HEAD `a03be22`，交付改动**尚未提交**（按团队 Git 纪律交 host 统一提交，见 §5 M-3）。
- 验收方式：**不采信 QA 报告的自述**——全部门禁与断言由我独立复跑（含连跑两次验可重复性），关键项另做**落库直查**与**动态探针**（发布审计、提交后卫生干跑），文档项做结构清点，两张证据图逐张目视。

## 1. PRD AC 逐条核对

| AC | 判据 | 我的验证证据 | 结论 |
|---|---|---|---|
| AC-1 | reset 后 DEMO-R-04 = RD / PENDING_TEST / 属 DEMO-REL-1，mr_url 非空、estimate=2.0、actual 在 1.5–2.0，经 deliver 写入且有 DELIVER 审计 | 直查：`RD\|PENDING_TEST\|release_id=2530\|https://git.example.com/mr/r32-e2e\|2.0\|2.0`，release 2530 = `DEMO-REL-1\|PLAN`；DELIVER 审计 `before={actualDays:null,estimateDays:2.0} after={mrUrl:…,estimateDays:2.0}`；`platform-demo-reset.sh` [28] 六字段全 1 | ✅ |
| AC-2 | DEMO-R-01 的 REQ_SUBMIT/REQ_REVIEW 均 APPROVED，由 submit→approve→review_start→approve 真实链产生，净态 POOLED，不直插 `wf_*` | 直查：两实例 `REQ_SUBMIT\|APPROVED\|definition_id=1`、`REQ_REVIEW\|APPROVED\|definition_id=2`；各 4 条 wf_history、各含 1 条 NODE_APPROVED；R-01 净态 `POOLED`；seed 包 `INSERT INTO wf_` 计数 **0**；reset [29]/[30] 全 1 | ✅ |
| AC-3 | 计划覆盖 DEMO-C-03/04；demo-reset 70→≥74 且 PASS==TOTAL，既有计数口径不降；两次 reset 幂等 | `platform-demo-reset.sh` 连跑两次均 **74/74** exit 0，[31] = `DEMO-C-03,DEMO-C-04`；`TOTAL_CHECKS=74` 且末尾漂移守卫在；计数表逐键与非 0 退出保留 | ✅ |
| AC-4 | 从 reset 态按文档 8 步全绿（创建 201 / 动作 200），含版本测试、R-04 test_pass→TEST_PASSED、三签 3/3、质量门 PASS | 门禁 S7 用同一组 helper 真跑：`[33]` 夹具就绪、`[34]` `TEST_PASSED 2/2` 且覆盖 1/1、`[35]` 三签 3/3、`[38]` 质量门快照 `QG-01/02/03 PASS`；两步门禁全绿 | ✅ |
| AC-5 | 提交 200 生成 round=1 快照（payloadHash 64hex）、changedSinceSubmit=false；n1(tl1/vendor1)→n2(admin) 逐级待办；两级批完 `APPROVED,APPROVED` 且每级 NODE_APPROVED | `[37]` 提交 200、`round=1`、`payloadHash=56479a71b0dc…`（64 hex）、四段同源；`[39]` 两级 `APPROVED,APPROVED`、n2 任务真实创建；`changedSinceSubmit` 由 `FlowSnapshotService.stale()` 逐段 digest 派生（提交即刻四段 digest 相同），S4-4 已证报告改动后页面转 stale，307 单测覆盖 | ✅ |
| AC-6 | 版本 n1 pm→n2 tl1/vendor1→n3 mgr1，仅末级后 PUBLISHED + releasedAt + RELEASE_PUBLISH 审计，前两级不得提前发布 | `[40]` `before=LOCKED → after=PUBLISHED`、`releasedAt=2026-11-19T11:12:43.883`、三级全 APPROVED（`s7_release_levels:1368` 同时断言 before 为 LOCKED，即前两级批完仍未发布）；审计动态探针实测命中 92 行 `RELEASE_PUBLISH\|RELEASE\|biz_id=2512` | ✅ |
| AC-7 | flowChain 四段同源（计划 COMPLETED / 三签 3/3 / 门 PASS / 准入 9 项 0 FAIL），缺段 MISSING，round=1 可回放且与提交时一致 | `[37]`/`--static-only` 与门禁静态段校验四段同源；`[38]` 质量门快照与 `[36]` 准入结果一致；snapshot 不可变（S4 段 `payloadHash` 在报告变更后不变，仅 segmentStale 翻转） | ✅ |
| AC-8 | LOCKED 后 deliver 清空 MR → 提交 409 `ADMISSION_FAILED`，failed 恰 `["MR_FILLED"]`，无新快照；恢复 MR 后 200 且 round=1 | `[36]`：409、`failedRules` 恰 `[MR_FILLED]`、快照数 0→0；`[37]`：恢复后 200、`round=1`；截图 `r32-admission-409-mr.png` 同屏可见「通过 5 / 失败 1（MR_FILLED）/ 关闭 3」与「提交快照：尚未提交」 | ✅ |
| AC-9 | 既有负向不退化：未锁定 409 `RELEASE_NOT_LOCKED`、缺原因 400、越级 403 且任务仍 PENDING，被拦链四段仍在 | 门禁 S1–S6（r30 的 32 条）全绿 + `platform-test-negative.sh` 130/130；`DEMO-RELEASE-FLOW.md`「被拦链（四条真拦，不是灰按钮）」（行 237）四子节 A/B/C/D 齐 | ✅ |
| AC-10 | A.3 改为全绿跑通（逐步响应码 / 两阶梯逐级待办与留痕 / PUBLISHED 落库证据 / 负向实测），A.1/A.2/A.4 同步，8 步标题与被拦链保留 | A.3 关键字命中 **14**（≥3 判据），含 REL-1444 四段全通过、三级全通过、`releasedAt=2026-11-19T10:17:36.943`、负向 409；`## 1.`–`## 8.` 八标题齐（行 71/93/104/129/144/157/176/206）；A.1 已收敛并回指 A.3，A.2 四条实操事实、A.4 图位齐 | ✅ |
| AC-11 | ≥2 张本轮实测图（PUBLISHED 四段 / 409 指名），路径可打开，不覆盖 r30 旧图 | `docs/teamflow/demo-shots/r32-published-chain.png`（REL-1444 已发布 + 四段已通过 + 三级全通过 + R-04 测试通过 2/2）、`r32-admission-409-mr.png`（失败 1 = MR_FILLED）；两图均 1600×1100 真 UI、无遮挡；r30 旧图未被覆盖（mtime 08:40 vs 10:14/10:17） | ✅ |
| AC-12 | release-flow-verify 新增 S7 八条，TOTAL 32→40，PASS==TOTAL 与非 0 退出保留，静态自检同步（分段 7 / 分母 40） | `RELEASE_FLOW_TOTAL=40`（`scripts/release-flow-verify.sh:39`），S1–S7 七段；连跑两次均 `CASE=40 PASS=40 FAIL=0` exit 0；`--static-only` 9/9（含「S7 个函数齐备」「分段 7」「分母 40」） | ✅ |
| AC-13 | 四门禁断言不减且全绿；第 32 条仍 merge-base 区间纯判据；提交后复跑全绿；diff 仅 seed/scripts/docs/Makefile 帮助，不加迁移/第 13 条需求/放行开关 | 四门禁：platform 194/194、negative 130/130、access 206/206、rbac 113/113；`[32]` 为 `a03be22..a03be22` 空区间自愈 + 判据自检 4/4（正例绿，改 V13 / 新增 V15 / 改模块 pom 均红）；提交后卫生**干跑**（临时 index + commit-tree，`a916e890`）diff 仅 13 个 seed/scripts/docs 文件、pom/package/lockfile 空、迁移空 | ✅（提交后复跑见 M-3） |

### 继承 AC（产品级索引 AC-14..AC-23）

r30 交付项的判据本轮未放宽：AC-14 驳回回 PLAN／重入 409、AC-15 紧急/回滚缺字段 400 且整体回滚、AC-16 越级/越权 403 且任务仍 PENDING —— 由门禁 S2/S3 与 `platform-test-negative.sh` 130/130 覆盖；AC-17 手册内容、AC-18 ≥6 张截图、AC-20 ≥30 断言 6 段、AC-21 既有门禁下限与 `FORCE_RELEASE_RETIRED` 409 仍成立；AC-22 依赖面：`'*pom.xml' 'web/package.json' 'web/pnpm-lock.yaml'` diff 为空、迁移 0 条、PNG 未被 `.gitignore` 命中（可入库）；AC-23 分支与提交信息：本轮分支由 host 指定为 `feat/demo-release-green`（PRD 写的是 r30 的 `feat/release-multilevel-approval`），提交由 host 执行、信息含 runId `tf-mwmvwpj7-6kmkas` 与任务夹名 —— 分支名偏差为 host 环境决定，不影响判据。

## 2. 架构一致性（M3 强制项）

| 蓝图项 | 落地 | 判定 |
|---|---|---|
| 常量收口 `DemoFixture.MR_URL` / `ACTUAL_DAYS` | `DemoFixture.java:45-46` 公开常量，文档/门禁同值引用（注释声明须同改） | 符合 |
| `DemoApprovalFixture` 独立 `@Service`，与 Tx1 裸 JDBC 分行事务 | 新增类，`seed()/approveAll(long)/notificationBaseline()/cleanupNotifications(long)` 与蓝图签名一致，`assertSeeded` 失败即抛让 Tx3 回滚 | 符合 |
| 单入口 `reset()`，Tx1/Tx2 之后追加 Tx3 | `DemoResetService.java:145` 为唯一调用点，三段事务范式沿用 `DemoEstimateFixture` | 符合 |
| 不直插 `wf_*` | seed 包 `INSERT INTO wf_` = 0，审批走引擎、流水含 SUBMIT/APPROVE/NODE_APPROVED | 符合 |
| 运维入口唯一（scripts/ 承载、Makefile 薄封装） | 新逻辑全在 `scripts/*.sh`，S7 复用既有建版本 helper，未新增第二个入口脚本 | 符合 |
| 门禁分母与自愈 | `RELEASE_FLOW_TOTAL=40`、七段、S7 起跑自愈 reset，建链失败仍补齐 8 条 fail（断言数恒为 40） | 符合 |
| 重复实现 / 该抽未抽 | 仅两处可接受重复：`approve-until-empty` 的 Bash/Java 双份（跨运行时无法共享，蓝图已声明以同一断言口径约束）；MR 字面量分布在 Java/两脚本/文档且同值同注释 | 可接受（P3 风险，不计返工） |
| 既有结构破坏 | 四门禁 + 307 单测全绿，未改业务代码、无迁移、无依赖变更 | 无 |

结论：**与蓝图一致，无偏离、无该抽未抽、无结构破坏** → 不触发 ⚠️。

## 3. 我的独立验证清单（全部亲自复跑）

| # | 命令 | 结果 |
|---|---|---|
| 1 | `bash scripts/release-flow-verify.sh` ×2 | 40/40 exit 0（第二次复跑同样 40/40，可重复） |
| 2 | `bash scripts/release-flow-verify.sh --static-only` | 9/9 exit 0 |
| 3 | `bash scripts/platform-demo-reset.sh` ×2 | 74/74 exit 0（含幂等计数表逐键一致） |
| 4 | `bash scripts/platform-verify.sh` / `platform-test-negative.sh` / `access-verify.sh` / `rbac-verify.sh` / `tests/lint.sh` | 194/194、130/130、206/206、113/113、22/22，全 exit 0 |
| 5 | `mvn -o -pl req-service test`（QA 轮已跑，我核 surefire 汇总） | 307 tests, 0 failures / 0 errors |
| 6 | 落库直查（`db_q`）：R-04 六字段、R-01 两实例+流水、通知 4 条种子口径、DELIVER 审计 | 与 AC-1/AC-2 判据逐项一致 |
| 7 | 审计动态探针（门禁运行中轮询 `audit_log`） | 命中 92 行 `RELEASE_PUBLISH`，样例 biz_id=2512 @ 11:12:43.883 |
| 8 | 提交后卫生干跑（不改 HEAD：临时 `GIT_INDEX_FILE` + `write-tree` + `commit-tree`） | 仅 13 个允许路径；pom/package/lockfile 空；迁移空 |
| 9 | 文档结构清点（八步标题、被拦链、A.3 关键字、附 A/附 B） | 全部在位 |
| 10 | 两张证据图目视 | 内容与 AC-11 描述一致，无遮挡 |

## 4. 意见

- 交付把「演示」从文案变成了可复现事实：reset 一键回到可演示态，门禁自身能重跑整条链并断言到 PUBLISHED，负向是**真拦**（409 指名 `MR_FILLED`）而不是灰色按钮。
- PRD §10 的三个假设全部按最保守口径落地：MR 用示例地址（M-2 记明换真实地址需同改三处）、负向选「清空 MR」且用公开 `deliver` 写入 `mrUrl:""`（可无损恢复）、证据用真 UI 截图而非伪造界面。
- 三处与设计措辞不符的地方实现按**真实库结构**修正（`wf_task` 无 `assignee_id`，实为 `approver_id`），属正确取舍，不构成缺陷。

## 5. 遗留（均不阻塞签收）

| 编号 | 级别 | 内容 | 处置 |
|---|---|---|---|
| N-1 | P3 | `release-flow-verify.sh` 的 S7 注释编号错位（`:1347` 标 S7-7、`:1367` 标 S7-8、`:1277` 标 S7-8 ⇒ 两个 S7-8、缺 S7-6）；行为与 40 条断言不受影响 | 建议 r33 顺手对齐 TECHNICAL §8.1 |
| N-2 | P3 | 发布单详情「发布步骤」显示 `1. [object Object]`（门禁造单 payload 为对象数组，`DeployOrderDetailView.vue:407-412` 直接 String() 元素）；**r30 遗留**，本轮 `git diff` 无 `steps:` 行 | 建议 r33 修（前端渲染或统一 payload 形态） |
| O-1 | P3 | 质量门 PASS 但卡片副标题「1 条未通过」（`SKIPPED` 计入 `failedRules`，`FlowEvidenceService.java:278-295` 与 `QualityGateService.java:468` 口径不一致） | 与 M-4 同源，接外部质量源后统一口径 |
| O-2 | P3 | `TECHNICAL.md:72` 写 `assignee_id`，库列实为 `approver_id` | 任务夹不可变，仅记录 |
| M-1 | 人工 | 真浏览器视觉复核（遮挡/动画/hover） | 宿主沙箱无法拉起浏览器（crashpad 限制，非交付缺陷），需桌面环境看 |
| M-2 | 人工 | MR 链接可点性：`https://git.example.com/mr/r32-e2e` 为示例域名 | 换真实地址时同改 `DemoFixture.MR_URL`、两脚本 `DEMO_MR_URL`、文档示例 |
| M-3 | 交接 | 提交后区间判据：`[32]` 当前是 `a03be22..a03be22` 空区间自愈 | host 提交后跑 `bash scripts/release-flow-verify.sh`，确认 `[31]/[32]` 变为 `a03be22..<new>` 且仍全绿（干跑已证明提交内容不会让 `[32]` 变红） |
| M-4 | 人工 | QG-05 当前 SKIPPED（未接外部质量源） | 接入后看准入第 6 项与质量门文案是否与 `QualityGateService` 一致 |

## 6. 结论

13 条 PRD AC 逐条成立，继承 AC 判据未放宽；架构与蓝图一致（常量收口、独立夹具类、单入口 reset、禁直插 `wf_*`、scripts 唯一入口）；端到端终态 `PUBLISHED`（`releasedAt=2026-11-19T11:12:43.883`）+ 发布单两级/版本三级逐级留痕 + 负向 409 指名全部有落库与截图双证据。4 条 P3 遗留（N-1/N-2/O-1/O-2）均不改变可演示性与判据强度，列入 r33 建议。交付改动留在工作树待 host 统一提交，提交后按 M-3 复跑一次区间判据即闭环。

验收结论：✅ 通过
