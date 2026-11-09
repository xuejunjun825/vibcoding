<!-- meta: summary="一期补齐 CCode 分支策略/CR/代码质量、CTeam 甘特/Wiki/价值流、CTest 用例评审/测试数据/执行器九项 P0 能力，全部复用既有隔离、审计与 M7 集成适配层" -->
基线依赖：`docs/teamflow/20261109-r5-ci-integration-adapters`（M7 四适配层，`mr_sync` / `quality_gate_snapshot` / `integration_config` / 出站客户端为其既定基线）、`docs/teamflow/20261108-r2-devops-req-platform`（需求主流程、审批、域×厂商隔离、用例与执行）、`docs/teamflow/20261109-r4-m6-r1-r2-rework`（质量门与计划七态）；上述行为不得回退。

# PRD · CCode / CTeam / CTest 一期 P0 补齐

## 1. 背景与目标

### 1.1 现状（差距来源：`docs/teamflow/CAPABILITY-MATCH-canway.md`，基线 main=9072719）

| 能力域 | 当前匹配度 | 本轮要补的缺口 |
|---|---|---|
| CTeam 敏捷协同 | 约 60% | **甘特图、Wiki、价值流看板**三项均未交付（r2 PRD §4.21–4.23 有设计、无实现） |
| CCode 代码管理 | 约 25% | 分支策略/保护 ⛔；CR 行级评论与评审人 ⛔（现有 MR 卡只有状态/增删行数）；代码质量只有门禁卡，无 issue 分布与覆盖率摘要 |
| CTest 测试管理 | 约 85% | 用例评审未落地（r2 设计为「第五类单据、默认关闭」，实际不可用）；测试数据说明无；外部执行器不可见 |

### 1.2 本轮目标

一期（P0）九项一次交付：**计划可视化（甘特）、知识沉淀（Wiki）、流动度量（价值流）、代码治理（分支策略 + CR + 代码质量）、测试治理（用例评审 + 测试数据 + 执行器接入）**，全部落在既有「req-service 8082 / deliver-service 8083 / 前端 5173 / MySQL + Flyway / JWT + ScopeSql / audit_log 单点」之内，不新建平台、不自建执行面。

成功判据（缺一不可）：

1. 九项能力在页面上可完整走通，关键写操作（拖拽改期、Wiki 回滚、用例评审、执行器上报联动）落库且可审计。
2. 每条能力至少有接口级自动化证据，四类强制负向（拖拽改期、Wiki 回滚、用例评审绑版本、跨域/跨厂商可见性）全部覆盖。
3. 既有门禁一项不回退（见 AC-19）。

### 1.3 范围边界

本轮只做一期 P0。二期（自动化规则引擎、风险与基线、敏感信息扫描）明确不在本轮，也不为本轮预留半成品入口。

## 2. 用户与角色

| 角色 | 本轮主要场景 | 关键能力 |
|---|---|---|
| 产品经理 / 域负责人 | 排期、看计划偏差、沉淀评审与交付资料 | 甘特（版本视图）、Wiki 空间管理、价值流瓶颈 |
| 项目/域负责人 | 迭代排期、任务改期、查看本域流动效率 | 甘特（迭代/需求视图）、价值流下钻 |
| 研发（厂商） | 看自己的任务条、MR 评审意见、分支规则 | 甘特（本厂商）、CR 区、分支策略只读 |
| 测试 | 组织用例评审、写测试数据说明、对接执行器 | 用例评审单、测试数据、执行器配置 |
| PMO / 管理层 | 跨版本流动效率、分支合规总览、代码质量趋势 | 价值流、分支策略核验、代码质量摘要 |
| 平台管理员 | 配置阶段、执行器、权限码 | 价值流阶段配置、执行器管理、权限矩阵 |

## 3. 用户故事

| 编号 | 故事（角色 → 诉求 → 价值） | 验收 |
|---|---|---|
| US-1 | 作为产品经理，我要在甘特里按版本/迭代/需求看计划与实际，拖一下就能改期且留痕，以便排期会议当场定稿 | AC-1、AC-2、AC-3 |
| US-2 | 作为域负责人，我要一个按域隔离的 Wiki，页面有版本、能对比回滚、能和需求缺陷互链，以便评审资料不再散落 | AC-4、AC-5、AC-6、AC-7 |
| US-3 | 作为管理层，我要看到需求在七个阶段的 WIP、停留与瓶颈，且数字与指标字典同源，以便判断该改哪里 | AC-8、AC-9 |
| US-4 | 作为研发负责人，我要登记分支命名/保护/合并/审批规则，并看到它与 GitLab 现状的差异，以便把规范落到 GitLab | AC-10、AC-11 |
| US-5 | 作为评审人，我要在需求详情直接看到 MR 的评审人、评审状态、变更行数与行级评论，以便不跳转也能处理意见 | AC-12 |
| US-6 | 作为产品/PMO，我要在需求与版本详情看到 Sonar 的 issue 分布与覆盖率，以便判断能不能发 | AC-13 |
| US-7 | 作为测试负责人，我要对用例发起独立评审、结论绑版本，改版后自动过期，以便评审不流于形式 | AC-14 |
| US-8 | 作为测试，我要在计划下写测试数据说明并传附件，以便执行人知道怎么准备数据 | AC-15 |
| US-9 | 作为测试/运维，我要登记外部执行器并看到最近上报与健康状态，以便知道自动化还在不在跑 | AC-16 |

## 4. 验收标准（本任务夹内编号，AC-1…AC-20）

> 全部 P0。每条 AC 的「证据」指 `make collab-verify` 对应分组（s0–s8）与 `ACCEPTANCE.md` 的记录位置；金额/时长类阈值均为服务端计算值。

### AC-1 甘特三视图与时间轴（P0）

- **主体**：版本（`release`）/ 迭代（`sprint`）/ 需求（BUS/PDR/RD 三类）三种视图，同一时刻只展示一种；切换保留域/厂商/版本筛选。
- **缩放**：日 / 周 / 月三档，切换不改变数据口径，只改变刻度与聚合；时间轴可横向滚动。
- **条**：实心=计划期（需求 `plan_start_date/plan_end_date`，版本 `start_date/end_date`，迭代 `start_date/end_date`，任务 `start_date/due_date`）；浅色=实际期（`actual_start_date/actual_end_date`，缺失时只画计划条，**不得用今天或创建时间伪造**）。
- **从属关系**：需求视图支持「父需求→子需求」「需求→任务」缩进；**不画 FS/SS 依赖线、不算关键路径**，接口不返回 `dependsOn` 类字段。里程碑用菱形（`milestone.planned_date`）。
- **规模**：单视图 ≤300 条；>300 返回 `total` 与 `truncated=true`，默认只展开版本/迭代层，展开按需分页；折叠状态按「用户+视图」记忆，刷新保持。
- **负向**：无权限的版本/迭代/需求不出现在响应体（不是前端隐藏）；跨域/跨厂商请求 404。
- **证据**：s1 断言 3 视图 × 3 缩放的字段、条数与颜色枚举；浏览器探针校验条位置与缩放刻度。

### AC-2 拖拽改期：落库 + 审计 + 超窗二次确认（P0）

- **写入口唯一**：`PATCH /gantt/rows/{type}/{id}`，`type ∈ RELEASE/SPRINT/REQUIREMENT/TASK`，只接受 `planStart/planEnd`（任务映射到 `start_date/due_date`）。
- **基础校验**：`planStart ≤ planEnd`（400 `GANTT_INVALID_RANGE`）；日期格式非法 400；越权 403；不可见/不存在一律 404。
- **父窗口**：需求/迭代超出所属版本窗口、任务超出所属需求计划期 → 409 `GANTT_OUT_OF_PARENT_WINDOW`，响应体给 `parentWindow`、`violatingDays`、一次性 `confirmToken`（5 分钟、绑定 type/id/日期）。带 token 重提才写入。
- **审计**：成功写入必须写 `audit_log`：`action=GANTT_RESCHEDULE`，`detail_json` 含 `type/id/before/after/parentWindow/confirmed/operator`；无审计即视为未完成。
- **并发**：`version` 乐观锁，冲突 409 `GANTT_VERSION_CONFLICT`，前端提示刷新后重试。
- **拖右边缘**：只改 `planEnd`，`planStart` 不变。
- **负向**：① 不确认直接越窗 → 409 且数据不变；② 同一 token 二次使用 → 409；③ 越权改他域条 → 403 且无审计记录；④ 把 end 改到 start 之前 → 400。
- **证据**：s1 负向 4 条 + 数据库断言（改期前后行值、`audit_log` 行数与 detail 字段）。

### AC-3 超载与临期标色（P0）

- **临期**：未完成且 `planEnd - today ≤ 3` 天 → 橙；`planEnd < today` 且未完成 → 红；已完成/已交付不标。
- **超载**：同一负责人同一自然周计划工作量合计 > 5 人天 → 橙。工作量口径：优先任务 `estimate_hours/8`，无任务用需求 `estimate_days` 在计划区间均摊；容量默认 5 人天/周，系统参数 `gantt.capacity.week` 可按域覆盖。
- **原因由服务端给**：响应 `warnings[{code,level,message}]`，前端不自行推断；同一行多警情按 红 > 橙 取色，tooltip 必须说明「哪几天超、超多少、涉及哪几条」。
- **负向**：把某负责人当周工作量加到超载 → 必须出现 `OVERLOAD`；减回不超载 → 同一数据告警消失。
- **证据**：s1 断言 warning code/数值边界（4.9 人天不告警、5.1 告警）；截图入 ACCEPTANCE。

### AC-4 Wiki 空间与页面树（P0）

- **空间**：`name`、`domain_id`（必填）、`vendor_id`（可空）、`visibility ∈ INHERIT/DOMAIN/VENDOR`、`editor_policy ∈ DOMAIN_EDITORS/ADMIN_ONLY`、`owner_id`、`status`。
- **可见范围**：`INHERIT` 走 §7 统一 `ScopeSql`；`DOMAIN` 仅本域；`VENDOR` 仅同厂商。列表、树、详情、搜索、回收站、附件六个出口同源过滤（禁止只在列表过滤）。
- **树深**：根=第 1 层，最多 4 层；创建/移动超深 400 `WIKI_DEPTH_EXCEEDED`；移动到自身子孙 409 `WIKI_CYCLE`。
- **Markdown**：存原文；渲染做 HTML 白名单净化（禁 `script/iframe/style/on*`），表格、代码块、图片、内链可用；图片/附件走既有 `attachment`（`biz_type=WIKI_PAGE`）。
- **编辑策略**：`DOMAIN_EDITORS`=本域拥有 `WIKI_EDIT` 者；`ADMIN_ONLY`=空间负责人 + 平台管理员。
- **负向**：跨域空间列表不出现、详情 404；只读角色 `PUT` 403。
- **证据**：s2 断言空间 CRUD、4/5 层边界、循环移动、3×3 可见性矩阵。

### AC-5 页面版本、对比与回滚（P0）

- **版本**：每次保存生成不可变快照（`title + content_md`），`wiki_page_version` 无更新/删除路径；`current_version` 与最新版本行在同一事务内一致。
- **无变更不产版**：标题与正文都未变时返回 `noChange=true`，不新增版本（避免版本噪音）。
- **对比**：任意两版按行 diff，返回新增/删除/修改行数与逐行内容；跨多版可跳选。
- **回滚**：取旧版内容生成 **vN+1**（不删历史、不改旧版），写 `audit_log action=WIKI_ROLLBACK`（`fromVersion/toVersion`）。
- **负向**：回滚后 `current_version = N+1` 且 v1…vN 的正文 hash 全部不变（逐版比对）；对已删除页面回滚 409。
- **证据**：s2 造 5 版 → diff → 回滚 → 旧版 hash 不变。

### AC-6 双向关联与软删回收站（P0）

- **关联**：`wiki_page_link(page_id, biz_type ∈ REQUIREMENT/DEFECT/RELEASE, biz_id)`。需求/缺陷详情可挂/解挂 Wiki 页；页面侧展示「被引用」清单，两侧都按可见性过滤。
- **不泄露**：无权限的引用**不计数、不展示**（不得出现「1 条不可见引用」这类可推断信息）。
- **软删**：`status=DELETED` + `deleted_at`；删父页面连带整棵子树（同一事务、同一 `deleted_at`），回收站以「子树根」一条呈现。
- **恢复**：30 天内可恢复，恢复父即恢复子树；原父已删则挂根并提示；超期恢复 409 `WIKI_RESTORE_EXPIRED`；每日任务物理清除超期页并写 `WIKI_PURGE`。
- **负向**：第 31 天恢复 → 409；非空间管理员 purge → 403；恢复后搜索与树立即可见（无缓存残留）。
- **证据**：s2 断言级联删/恢复/超期/权限。

### AC-7 Wiki 全文检索（P0）

- **实现**：MySQL `FULLTEXT(title, content_md) WITH PARSER ngram`，BOOLEAN MODE；中文 2 字词可命中。
- **结果**：返回标题、高亮片段（服务端截取 ≤120 字）、空间名、更新时间；分页 20；空词 400；已删除页不出现。
- **权限**：先按可见性过滤再匹配，`total` 只统计可见命中。
- **负向**：无权限用户搜某空间独有关键词 → `total=0` 且列表空（不是 `total>0` 列表空）。
- **证据**：s2 用 3 个关键词 × 2 个用户断言命中与 `total`。

### AC-8 价值流阶段配置（P0）

- **默认七阶段**：提报 1 / 评审 2 / 排期 3 / 开发 4 / 测试 5 / 发布 6 / 交付 7（`stage_definition`，`enabled=1`）。
- **映射**：每条含 `mapping_json`（进入/离开条件，状态或事件）。默认种子：提报=创建；评审=`APPROVING|PENDING_REVIEW → POOLED`；排期=`POOLED | SCHEDULED | 事件 RELEASE_ATTACHED`；开发=`IN_DEV`；测试=`PENDING_TEST`；发布=`TEST_PASSED→MERGED | DELIVERED`；交付=`DONE | ACCEPTED_CLOSED | CLOSED`。
- **可配**：启停、排序、按 `req_types` 收窄；某类型无映射的阶段显示为「不适用」灰列并排除出指标；同一类型至少保留 2 个启用阶段，否则 409 `STAGE_MIN_REQUIRED`。
- **审计**：任何变更写 `audit_log action=STAGE_DEFINITION_UPDATE`（before/after）。
- **负向**：停用阶段后看板列消失且指标不再统计；重新启用后历史数据自动回算（数据从 `audit_log` 派生，不补写业务表）。
- **证据**：s3 断言 7 条种子、启停、排序、按类型过滤。

### AC-9 价值流指标与同源（P0）

- **WIP**：当前停留于该阶段的需求数（按最新状态映射去重）。
- **平均 / P90 停留**：样本=窗口内已离开该阶段的需求；P90 用 nearest-rank（升序第 `ceil(0.9×n)` 个），保留 1 位小数；仍在阶段内的只计 WIP。
- **流动效率**：`(开发停留 + 测试停留) / (提报→交付总时长)`，仅已交付需求；分母为 0 或审计缺口排除。
- **瓶颈**：平均停留最长阶段；样本 <5 标「样本不足」不参与排名。
- **同源**：编码 `FLOW_WIP / FLOW_DWELL_AVG / FLOW_DWELL_P90 / FLOW_EFFICIENCY / FLOW_BOTTLENECK` 写入 `metric_definition`（`datasource=req.audit`、`formula` 文本、`version=1`）；看板、指标字典、报表共用同一实现（`FlowMetricFormulas`），同参数三处数值差为 0。
- **只取 audit_log**：无状态流转审计的需求标「无数据」并排除，**不得按 0 或创建时间兜底**。
- **下钻**：点阶段→需求列表（权限过滤）；点需求→阶段时间线（进入/离开时间与来源事件）。
- **负向**：造一条无审计需求，样本数不变且不进入平均；厂商用户下钻只看本厂商。
- **证据**：s3 用固定种子直算 SQL 对照 7 阶段 WIP/均值/P90；三处读数一致。

### AC-10 分支策略规则登记（P0）

- **规则字段**：`domain_id`、`gitlab_project`（group/repo）、`branch_pattern`（正则或 glob，规则上显式声明类型）、`protected`、`merge_method ∈ MERGE/SQUASH/FF_ONLY`、`approvals_required(0–5)`、`push_roles_json`、`merge_roles_json`、`enabled`、`version`。
- **约束**：`approvals_required>0` 时 `protected` 必须为真（400 `POLICY_CONTRADICTION`）；`FF_ONLY` 与 `SQUASH` 互斥；(tenant, project, pattern) 唯一，重复 409。
- **不代理**：平台只登记规则与限制配置，不提供 push/merge/建分支接口，不存仓库内容；实际拦截由 GitLab 承担（页面必须写明）。
- **审计**：变更写 `BRANCH_POLICY_UPDATE`（before/after/operator）。
- **负向**：重复规则 409；矛盾规则 400；非本域项目 403。
- **证据**：s4 断言 CRUD、四类校验、审计。

### AC-11 与 GitLab 现状对照核验（P0）

- **读取**：复用租户唯一启用的 GitLab `integration_config` 凭据，只读调用项目信息（merge method / approvals）、`protected_branches`、最近 50 个分支抽样。
- **三态**：`MATCH` / `DRIFT`（逐项差异：分支名、权限、合并方式、审批数）/ `UNVERIFIED`（未启用、鉴权失败、超时、API 不支持）。每项带期望值、实际值、脱敏来源、检查时间。
- **不得假绿**：集成关闭（409 `INTEGRATION_DISABLED`）时必须 `UNVERIFIED` 且页面显著标「未核验」，不得显示绿色、不得计入合规率。
- **只增不改**：`branch_policy_check` 保留历史；drift 不自动改规则；打开页面不自动外呼，「重新核验」是唯一外呼入口（与 M7 一致）。
- **负向**：① 桩返回保护分支缺失 → `DRIFT` 且定位到具体分支；② 断开 GitLab → `UNVERIFIED` 且不残留上次 `MATCH` 的绿色；③ 打开页面时桩计数为 0（无外呼）。
- **证据**：s4 用桩造三种响应；断言三态与无外呼。

### AC-12 CR 评审信息同步与展示（P0）

- **沿用 `mr_sync`**：不新建 MR 主表；增列 `reviewers_json`、`review_state`、`changed_files`、`comments_count`；行级评论入子表 `mr_comment`（唯一键 `tenant+mr_sync_id+gitlab_note_id`，正文脱敏并截断 2000 字）。
- **拉取**：经 M7 GitLab 适配层只读拉 MR 详情与 `notes`（含 diff note 的 `position`）；仅在用户点「刷新」或 webhook 到达时拉取，不做定时轮询。
- **展示**：需求详情 MR 区显示评审人、评审状态徽标、`+additions/-deletions`、变更文件数、行级评论列表（`文件:行号`、作者、内容、是否已解决），默认 50 条 + 加载更多。
- **幂等与单调**：同一 note 重复同步不新增；`last_event_at` 单调不回退；MR 合并/关闭后旧评论仍可读但不再被覆盖。
- **权限与脱敏**：MR 区随需求可见性，跨租户 404；正文中的 token/密码形态打码。
- **负向**：同一 note 同步两次行数不变；越权拉他域 MR 404；凭据串不出现于响应。
- **证据**：s5 桩返回 3 条 note（2 行级 1 普通）+ 2 名评审人，断言字段与幂等。

### AC-13 代码质量摘要（P0）

- **只读快照**：读 `quality_gate_snapshot` 中 `source=SONAR` 的最新记录；需求详情按 `requirement_id` 取最新；版本详情按版本下需求聚合，按 `sonar_project_key` 去重取每个项目最新一条。
- **展示**：五项度量（bugs / vulnerabilities / code_smells / coverage / duplicated_lines_density）+ 门禁结论 + 快照时间 + 项目 key；issue 分布=三类计数，覆盖率=百分比；「较上次」仅同项目两次快照可比时展示。
- **空态**：无快照显示「尚未同步 Sonar 或集成未启用」，**不得显示 0 / 100% / 绿色**；页面打开不外呼 Sonar，刷新按钮走 M7 既有同步。
- **不伪造归属**：版本页聚合时 tooltip 标明「来自需求 XXX」；快照只增不改。
- **负向**：无快照版本为空态而非全 0；跨域需求快照 404。
- **证据**：s5 断言字段映射、空态、聚合去重与 0 外呼。

### AC-14 用例评审单（P0）

- **表 `test_case_review`**：`case_id`、`case_version`（提交时钉住 `test_case.current_version`）、`conclusion ∈ PASS/NEED_CHANGE/REJECT`、`comment`、`reviewer_id`、`status ∈ SUBMITTED/DONE/INVALIDATED`、`created_at/decided_at`。
- **规则**：`NEED_CHANGE/REJECT` 必填意见（≥10 字，否则 400 `CASE_REVIEW_COMMENT_REQUIRED`）；同一用例同一版本只允许一条有效评审（409 `CASE_REVIEW_DUPLICATE`）；仅被指派评审人可下结论（403）；提交过期版本 409 `CASE_REVIEW_VERSION_STALE`。
- **改版即过期**：用例保存新版本时，指向旧版本的评审置 `INVALIDATED`（`invalid_reason=NEW_VERSION`），页面显示「v2 已发布，v1 评审已过期」；历史保留可查，不物理删除。
- **不进第五条审批单据**：不新增 `wf_definition.biz_type`；状态变更写 `audit_log`（`CASE_REVIEW_SUBMIT/DECIDE`）；审批中心只增「用例评审记录」只读列表，不产生待办、不阻塞既有流程。
- **负向**：改版后旧结论不得显示有效；用 v1 结论给 v2 背书 → 409；同一版本重复提交 → 409。
- **证据**：s6 断言钉版本、改版失效、重复提交、意见长度、审批单据类型未新增。

### AC-15 测试数据说明（P0）

- **载体**：`test_plan.data_note`（≤2000 字）+ 附件（复用 `attachment`，`biz_type=TEST_PLAN`，`biz_id=plan_id`）。
- **权限**：计划 owner 与本域测试角色可编辑；计划进入 `COMPLETED` 后只读（改文本/加删附件 409 `PLAN_DATA_LOCKED`）。
- **审计**：写 `TEST_DATA_NOTE_UPDATE`（只记摘要与附件数，不落正文）。
- **展示**：计划详情「测试数据」区；执行页可见（执行人需要按说明准备数据）；下载走既有水印与权限通道。
- **负向**：非 owner 编辑 403；关计划后编辑 409；跨域附件下载 404。
- **证据**：s7 断言文本/附件/锁定期/审计。

### AC-16 执行器配置与上报增强（P0）

- **表 `test_executor`**：`code`（租户内唯一）、`name`、`base_url`、`api_client_id`（可空）、`enabled`、`last_report_at`、`report_count`。
- **健康派生**（不手工维护）：`enabled=0 → DISABLED`；从未上报 `→ NEVER`；≤24h `ONLINE`；24–72h `STALE`；>72h `OFFLINE`。阈值集中在单处常量，页面同时显示「最近上报时间」。
- **上报增强**：`POST /v1/test-executions` 增加可选 `executorCode`；缺省按 AppKey 绑定执行器归属；未知 code 422 不落库；上报同事务更新 `last_report_at/report_count`。
- **既有契约不变**：HMAC + 时间戳 + IP + scope、`run_id+case_id` 幂等、未知用例/版本 422 全部保持；`executorCode` 不参与幂等键。
- **页面**：执行器列表（名称/地址/最近上报/健康/近 7 天上报数）+ 新增/编辑/停用；停用后上报 422 `EXECUTOR_DISABLED`（不静默丢弃）。
- **负向**：停用执行器上报 422；未知 code 422 且 `test_execution` 行数不变；冻结时钟验证 4 种状态边界（23:59/24:01/72:01）。
- **证据**：s7 断言状态机、上报联动、幂等键不变。

### AC-17 隔离与权限（横向，P0）

- **全入口走 ScopeSql**：Wiki（空间/页面/搜索/回收站/附件）、甘特三视图、价值流下钻、分支策略、MR 评论、代码质量、用例评审、测试数据、执行器。
- **判定**：列表不返回；详情 404（不用 403 泄露存在性）；写操作 403；搜索/导出/下钻/附件同源。
- **权限码**：`WIKI_VIEW/EDIT/ADMIN`、`GANTT_VIEW/EDIT`、`FLOW_VIEW/CONFIG`、`CCODE_POLICY_VIEW/EDIT`、`CASE_REVIEW_VIEW/SUBMIT/DECIDE`、`TEST_EXECUTOR_VIEW/MANAGE` 进 `PermissionMatrix` 与 `RouteTable`，后端拦截而非隐藏按钮。
- **负向**：≥8 条跨域/跨厂商用例（四类能力各 ≥2），含搜索、下钻、附件、评论；断言 404/403 且不泄露条数。
- **证据**：s8 全组。

### AC-18 迁移、种子与文档（P0）

- **Flyway `V9__ccode_cteam_ctest_p0.sql`**：新建 11 表——`wiki_space`、`wiki_page`、`wiki_page_version`、`wiki_comment`、`wiki_page_link`、`stage_definition`、`branch_policy`、`branch_policy_check`、`mr_comment`、`test_case_review`、`test_executor`；增列——`mr_sync` 4 列、`requirement/release/sprint/task` 各 2 列实际起止、`test_plan.data_note`、`test_execution.executor_id`；`wiki_page` 建 `FULLTEXT(title, content_md) WITH PARSER ngram`。
- **历史回填**：四个主体的 `actual_start_date/actual_end_date` 从 `audit_log` 一次性回填（幂等、只填空值），回填后输出「有审计但未回填=0」的校验行。
- **种子**：7 个阶段、5 条价值流指标、1 个示例 Wiki 空间 + 3 页（含 2 级树与 2 个版本）、1 条分支策略示例（默认 `UNVERIFIED`）、2 个执行器、新增权限码与角色授权。
- **幂等**：空库与已有库各跑一次，行数/约束一致；种子重复执行不重复插入。
- **文档**：更新 `docs/teamflow/DELIVERED-MODULES.md` 与 `docs/teamflow/CAPABILITY-MATCH-canway.md` 对应条目（CTeam/CCode/CTest 三行状态与占比、附本任务夹链接）；历史任务夹只读不改。
- **证据**：s0 断言表/列/索引存在、种子行数、回填校验行。

### AC-19 既有门禁不回归（P0）

- `make verify` 14/14；`platform-verify` ≥167；`quality-verify` ≥348；`platform-test-negative` ≥25；unit ≥178；lint 16/16；web typecheck 0 error；build 成功；`make integration-verify` ≥230 全绿。
- 新增 `make collab-verify`：s0–s8 九组，每组 ≥6 条，总计 ≥60 条；末尾输出 `#SUMMARY total= pass= fail=`，失败退出码 1；桩只在本轮脚本内起停，不进入产品路径。
- 既有契约只增不减：用例/计划/准入/追溯接口响应新增字段不得改名或改类型，老断言不改也能过。
- **证据**：CI/本地日志尾部 + `ACCEPTANCE.md` 门禁表（命令、断言数、时间）。

### AC-20 性能基线（P0）

| 场景 | 数据量 | 目标 |
|---|---|---|
| 甘特单视图 300 条 | 300 条含权限过滤与告警计算 | 服务端 P95 ≤1.5s；前端可交互 ≤2.5s |
| Wiki 全文检索 | 1 万页 / 10 万版本 | P95 ≤800ms |
| 页面树 | 500 节点 | P95 ≤300ms |
| 价值流 7 阶段 | 10 万需求 / 100 万 `audit_log` 行 | P95 ≤2s（允许派生汇总表，但须可从 `audit_log` 全量重算并校验一致） |
| 用例评审列表 / 执行器列表 / MR 评论首屏 | 各 1 万行 | P95 ≤500ms |

- **证据**：`make collab-perf` 输出 5 项数值；若做汇总表，另附「重算与直算差 0」的校验行。

## 5. 交互流程摘要

1. **甘特改期**：打开视图 → 拖动条 → 服务端算父窗口/告警 → 越窗弹二次确认（显示父窗口与超出天数）→ 确认 API 带 token → 落库 + 审计 → 局部刷新条色。
2. **Wiki 编辑回滚**：进入空间 → 选页面 → 编辑（Markdown 预览）→ 保存生成 vN → 版本列表选两版对比 → 回滚生成 vN+1 → 审计可查。
3. **Wiki 关联**：需求详情「关联 Wiki」选页 → 页面侧出现「被引用：需求 BUS-0001」；解挂双向同步消失。
4. **价值流**：选域/厂商/时间窗 → 7 列阶段卡（WIP/均值/P90）→ 点阶段看列表 → 点需求看阶段时间线；管理员在阶段配置里改映射，保存后看板即时按新映射重算。
5. **分支策略**：新建规则（项目 + 命名 + 保护 + 合并方式 + 审批数）→ 保存 → 点「核验」→ 三态结果与逐项差异；不可达时显示未核验。
6. **CR**：需求详情 → 集成 tab → MR 卡 → 点刷新（唯一外呼）→ 评审人/状态/行数/评论列表出现；已解决评论打勾。
7. **代码质量**：需求/版本详情「代码质量」小节 → 读本地快照；无数据空态提示去集成配置。
8. **用例评审**：用例详情 → 发起评审（钉当前版本）→ 评审人填结论与意见 → 通过/需修改/驳回；用例改版后旧评审标过期。
9. **执行器**：管理页新增执行器（名称/地址/绑定 AppKey）→ 外部 CI 上报带 `executorCode` → 列表显示最近上报与健康色。

## 6. 数据模型与迁移要点

| 表 / 变更 | 关键字段 | 说明 |
|---|---|---|
| `wiki_space` | tenant_id, domain_id, vendor_id, name, visibility, editor_policy, owner_id, status | 空间；可见范围三态 |
| `wiki_page` | space_id, parent_id, title, content_md, status, deleted_at, depth, sort_no, current_version, editor_id | 树 ≤4 层；软删；FULLTEXT(ngram) |
| `wiki_page_version` | page_id, version_no, title, content_md, editor_id, created_at | 不可变快照，唯一 (page_id, version_no) |
| `wiki_comment` | page_id, user_id, content, created_at | 评论（@提及走既有通知，不在本轮强约束） |
| `wiki_page_link` | page_id, biz_type, biz_id, created_at | 双向关联，唯一 (page_id,biz_type,biz_id) |
| `stage_definition` | tenant_id, code, name, order_no, mapping_json, req_types, enabled | 阶段映射与顺序 |
| `branch_policy` | tenant_id, domain_id, gitlab_project, branch_pattern, pattern_type, protected, merge_method, approvals_required, push_roles_json, merge_roles_json, enabled, version | 规则登记（不代理操作） |
| `branch_policy_check` | policy_id, result, diff_json, evidence_json, checked_at, checked_by | 三态核验历史，只增不改 |
| `mr_comment` | tenant_id, mr_sync_id, gitlab_note_id, author, body, file_path, line_no, resolved, note_created_at | 行级评论；唯一 (tenant, mr_sync_id, gitlab_note_id) |
| `test_case_review` | tenant_id, case_id, case_version, conclusion, comment, reviewer_id, status, invalid_reason, decided_at | 版本绑定的独立评审单 |
| `test_executor` | tenant_id, code, name, base_url, api_client_id, enabled, last_report_at, report_count | 健康状态派生 |
| `mr_sync` +4 列 | reviewers_json, review_state, changed_files, comments_count | 沿用既有 MR 主表 |
| `requirement/release/sprint/task` 各 +2 列 | actual_start_date, actual_end_date | 实际期；由状态流转维护，可回填 |
| `test_plan` +1 列 | data_note TEXT | 测试数据说明 |
| `test_execution` +1 列 | executor_id BIGINT NULL | 上报来源执行器（不改幂等键） |

- 所有新表带 `tenant_id`；Wiki/评审/执行器带 `domain_id`（执行器按域归属），`ScopeSql` 单点生成谓词。
- 旧表只加列不删列、不改既有列类型；`V9` 必须可在已有 V8 数据的库上重复执行（`IF NOT EXISTS` + information_schema 判空）。

## 7. 指标口径（价值流，注册进 `metric_definition`）

| code | 名称 | 公式（文本入字典） | 数据源 |
|---|---|---|---|
| `FLOW_WIP` | 阶段在制品数 | 最新状态映射到阶段 S 的需求数 | req.audit |
| `FLOW_DWELL_AVG` | 阶段平均停留 | AVG(离开-进入)，天，1 位小数 | req.audit |
| `FLOW_DWELL_P90` | 阶段停留 P90 | nearest-rank 第 ceil(0.9n) 个样本 | req.audit |
| `FLOW_EFFICIENCY` | 流动效率 | (开发停留+测试停留)/(提报→交付)，仅已交付 | req.audit |
| `FLOW_BOTTLENECK` | 瓶颈阶段 | argmax(平均停留)，样本≥5 | req.audit |

同名指标在看板、指标字典、自助报表三处必须取同一实现；差异 >0 即 AC-9 不通过。

## 8. 范围与非目标

**本轮范围**：上述九项 + 迁移/种子 + 权限码 + 文档更新 + 自动化证据。

**非目标（本期不做）**：
- 二期能力：自动化规则引擎（事件→条件→动作）、风险与基线、敏感信息扫描。
- 甘特 FS/SS 强依赖、关键路径、资源平滑；>300 条的虚拟滚动分片（先折叠）。
- Wiki 实时协同编辑、ES 检索、跨空间移动、页面模板市场。
- 代码托管、分支创建/推送/合并代理、commit 规范校验、四种合并策略的实现（只登记与核验）。
- 自建测试执行器/流水线、数据造数平台、测试数据版本快照。
- 新增第 5/6 类审批单据（案例评审走独立评审单）。

## 9. 反面目标（技术通过但会被退货的情形）

1. **拖完就回退**：甘特上把条拖到新日期，界面显示成功，刷新或换人打开又回到原日期；或日期改了但 `audit_log` 里没有 `GANTT_RESCHEDULE`，事后无法追责。
2. **没核验却显示合规**：GitLab 未启用或鉴权失败时，分支策略页仍是绿色「合规」或把 UNVERIFIED 计进合规率——把「没检查」当成「通过」。
3. **搜索成了权限旁路**：Wiki 检索用无权限账号能通过标题片段、命中数或「1 条不可见结果」推断出受限空间的内容与规模。
4. **评审形同虚设**：用例改了版，旧评审结论仍显示「已通过」，计划照旧引用，且没有任何「已过期」提示。
5. **两个价值流数字**：看板说某阶段平均停留 6.2 天，指标字典/报表算出 3.9 天，或把没有审计记录的需求按 0 天计入，导致瓶颈结论翻转。

## 10. 假设与待澄清

> 以下 7 项是本轮唯一需要拍板的事项，其余参数（如容量 5 人天/周、健康阈值 24h/72h、临期 3 天、评论截断 2000 字）已在 AC 内写成可配置默认值，不改变产品语义。

- **Wiki 空间默认可见范围**：假设默认「仅本域（DOMAIN）」而非继承 §7 全域；理由是知识库默认应收窄，跨域共享由空间负责人显式放开。若改为继承全域，空间创建默认值与种子空间可见性随之改变。
- **用例评审结论是否阻断计划引用**：假设「需修改/驳回」不阻断，只在计划与执行页标「未评审/需修改」并计入计划风险提示；理由是阻断会卡住既有计划七态与门禁脚本。若改为阻断，计划编辑与执行入口需加 409 校验。
- **用例评审是否走通用审批引擎**：假设不接入，采用独立评审单 + `audit_log` + 审批中心只读列表；理由是新增第 6 类 `wf_definition.biz_type` 会牵动审批中心、通知与权限矩阵，收益不抵成本。若接入，需补第 6 类单据的流程种子与待办语义。
- **流动效率的活跃阶段**：假设活跃时间=开发+测试两阶段停留之和，总时长=提报→交付；若改为「仅开发」或「除排期外全部」，`FLOW_EFFICIENCY` 公式与历史对比口径都会变。
- **甘特默认视图**：假设首屏为「版本 + 月」，理由是管理层视角；若改为「迭代 + 周」，默认筛选与种子示例需调整。
- **集成不可达时是否允许人工判定合规**：假设不允许改状态，只能附「线下核验备注 + 审计」，状态仍为 `UNVERIFIED`；若允许人工置绿，AC-11 的「不得假绿」需改为「人工置绿必须带原因、审计与到期时间」。
- **GitLab 只读 Token 权限级别**：需确认客户环境能提供 `api` scope 且达 Maintainer（读取 `protected_branches` 与 approvals 设置）；若只有 Developer，对照核验只能 `UNVERIFIED`，AC-11 的 MATCH/DRIFT 无法在真实环境演示，只能用桩。

## 11. 依赖与风险

| 依赖/风险 | 影响 | 应对 |
|---|---|---|
| M7 适配层与 `integration_config`（单租户仅一条启用配置） | GitLab 对照核验与 CR 同步都依赖它 | 复用既有凭据与同步日志，不新建配置；不可达一律 UNVERIFIED |
| `audit_log` 历史完整性 | 价值流停留与甘特实际期回填 | 缺失标「无数据」并排除；回填输出「有审计未回填=0」校验行；不按 0 兜底 |
| MySQL ngram 解析器 | Wiki 中文检索召回 | 建表即建 FULLTEXT(ngram)；若部署实例不支持，降级 LIKE 并在接口与页面标「降级检索」，不得静默 |
| 300 条上限与聚合查询 | 甘特/价值流性能 | 折叠 + 服务端分页；价值流允许派生汇总表但须可重算校验 |
| 新权限码与既有 410 格矩阵 | 可能打破权限单测 | 权限码集中登记，补矩阵与单测；不改既有格 |
| 时间字段回填并发 | 拖拽与状态流转同时写 | 乐观锁 + 以状态流转时间为准，冲突重算 |
| 二期能力被误以为已交付 | 验收争议 | 页面与文档明确「二期：自动化规则/风险基线/敏感信息扫描」 |

## 12. 里程碑建议（单分支 feat/ccode-cteam-ctest-p0 内顺序推进）

| 批次 | 内容 | 退出条件 |
|---|---|---|
| M8.1 | 迁移 V9 + 甘特（AC-1–3）+ Wiki（AC-4–7） | s0/s1/s2 全绿；拖拽与回滚负向通过 |
| M8.2 | 价值流（AC-8–9）+ 指标注册 | s3 全绿；三处读数一致 |
| M8.3 | CCode（AC-10–13） | s4/s5 全绿；三态核验与空态通过 |
| M8.4 | CTest（AC-14–16） | s6/s7 全绿；版门与执行器负向通过 |
| M8.5 | 隔离（AC-17）、种子/文档（AC-18）、门禁与性能（AC-19–20） | s8 全绿；既有门禁不回退；文档两条目更新 |

## 13. 工程约束（原样保留本轮工程指令）

- **技术栈与端口**：沿用 `req-service` 8082 / `deliver-service` 8083 / `api-gateway` 8080 / 前端 Vue3 + Element Plus + Vite 5173 / MySQL 9.6 + Flyway / JWT + `ScopeSql` 域×厂商行级隔离单点。
- **视觉基线**：新页面遵循 `docs/teamflow/20261108-r2-devops-platform/UI-STYLE.md` 与 `docs/teamflow/20261108-r2-devops-platform/prototype/`。
- **门禁**：不破坏既有门禁——r1 `verify` 14/14、`platform-verify`、`quality-verify`、`neg`、`unit`、`lint` 全部保持通过（目标值见 AC-19）。
- **迁移**：新增表须进 Flyway 迁移并有种子数据（AC-18）。
- **文档**：更新 `docs/teamflow/DELIVERED-MODULES.md` 与 `docs/teamflow/CAPABILITY-MATCH-canway.md` 的对应条目。
- **分支与提交**：从最新 `main`（9072719）拉出的 `feat/ccode-cteam-ctest-p0` 上实现，不新建第二条特性分支；当前唯一未提交改动是 `?? docs/teamflow/20261109-r6-ccode-cteam-ctest-p0/`（本任务夹），**不 stash、不回滚**，随本轮一起提交；提交前 `git status` 确认没有 `target/`、`logs/`、`.run/` 等噪音。
- **版本控制卫生**：实现前核对 `.gitignore`（本栈已覆盖 Maven `target/`、Node `node_modules/` `dist/` `.vite/`、IDE、`logs/`、`.env*`、`.nacos/`、`.run/`、`.m2/`、`test-results/`、`playwright-report/`）；本轮若新增前端产物（如 `web/coverage/`、`.eslintcache`、性能样本 PNG）须先补规则；`docs/`、`scripts/`、`config/nacos/` 必须保持 tracked。
- **文档边界**：本需求全部产物写入 `docs/teamflow/20261109-r6-ccode-cteam-ctest-p0/`；不写宿主 `docs/<role>/`，不在项目根散落日志；不修改 `AGENTS.md`（托管区之外亦不得改）。

## 14. 验收证据要求（写进后续 `ACCEPTANCE.md`）

1. 每条 AC 一行：断言组（s0–s8）、命令、通过数、负向用例编号。
2. 四类强制负向单列：拖拽改期（越窗未确认、token 复用、越权、非法区间）、Wiki 回滚（旧版 hash 不变、超期恢复、非管理员 purge）、用例评审绑版本（改版过期、过期版本背书、重复提交）、跨域/跨厂商（≥8 条，覆盖列表/详情/搜索/下钻/附件/评论）。
3. 门禁表：`verify / platform-verify / quality-verify / negative / integration-verify / unit / lint / typecheck / build` 的命令与结果。
4. 性能表：5 项 P95 实测值 + 数据量。
5. 交互证据：甘特拖拽前后截图、Wiki 版本对比与回滚截图、价值流看板与下钻截图、分支核验三态截图、MR 评论列表截图、用例评审过期提示截图、执行器健康状态截图。
6. 文档证据：`DELIVERED-MODULES.md` 与 `CAPABILITY-MATCH-canway.md` 的 diff 摘要。
