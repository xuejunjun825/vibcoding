# QA-PLAN — r46 PDR 评审 + 厂商工时评估 + 多功能评审附件 + 意见分类统计 + 多条意见转任务

- 任务夹：`docs/teamflow/20261125-r46-pdr-review-vendor-estimate/`
- 门禁脚本：`scripts/pdr-flow-verify.sh`（66 条断言）＋ `scripts/tests/pdr/_lib.sh`（可 source 的请求/断言基座）
- 契约来源：`docs/teamflow/CHANGE-PDR-REVIEW-VENDOR-ESTIMATE.md` §5 接口、§6 规则、§10 验收
- 状态：**脚本已就绪，待后端编译通过后由 lead 统一跑**（本任务不启动栈、不跑真实 HTTP）。
  2026-11-25 11:00 复核：后端 `req-service/.../pdr/`、`project/`、`V17–V19` 迁移已落盘，脚本的响应形状已按 `PdrViews` 对齐；
  按代码复核后**预计首跑会红的 5 条**：S7/A1-1（需求→项目无写入入口）、A5-1/B7-1（multipart 未配）、C3-2（deliver 未注册数据集）。

## 0. 怎么跑

```bash
make platform-up && make platform-seed      # 栈 + 种子（admin/pm/po1/dev1/qa1/tl1/vpm1/vendor2、VEN-A/VEN-B）
bash scripts/pdr-flow-verify.sh             # 全量 66 条；FAIL>0 或清单漏跑 → 退出码 1，前置缺失 → 退出码 2
bash scripts/pdr-flow-verify.sh --dry-run   # 不联网，只打印 66 条标题（评审清单完整性）
NO_NET=1 bash scripts/pdr-flow-verify.sh    # 同上
```

- 输出协议：每行 `PASS|FAIL [NN] <编号> <标题>`；失败追加 `实际:`（HTTP 码 + 业务码 + 响应片段 ≤300 字）与`修复:`；结尾恒打印
  `PDR_FLOW_TOTAL=<清单条数> PASS=n FAIL=n`（前置中止时额外打印 `PDR_FLOW_ABORTED=<原因>`，清单漏跑时打印 `PDR_FLOW_MISSING=`）。
- 自造夹具（不依赖上一次运行、不调用 demo-reset 抹别人的演示数据）：独立域 `PDRG<ts>`（统计隔离域 `PDRS<ts>`）→ 独立项目 `PDRP<ts>` + 5 名成员 →
  BUS 走 submit/REQ_SUBMIT 会签/review_start/REQ_REVIEW 会签入池 → PDR-A（主链）/PDR-B（未评审）/PDR-D（统计）/PDR-E（转任务）/PDR-F（附件边界）。
  审计与通知断言一律加 `created_at >= <本轮开始时间>`，因此重复运行不会因历史行误判。
- 就绪判定：`pdr_wait_ready` 每秒探测、最多 30 秒，超时打印「哪一步没就绪 + 最后一次 HTTP」；**脚本内没有一处盲等 sleep**。
- 编号与验收组：本表编号按 lead 的验收分组（A=评审、B=厂商/附件、C=统计、D=转任务）。
  注意契约文档 §10 的字母分组与本次分工不同（文档 A=厂商、B=评审附件），落地时以本表编号为准，交叉引用见「文档项」列。

## 1. 断言清单（66 条）

| 编号 | 验收项 | 前置数据 | 期望 | 失败时怎么看 |
|---|---|---|---|---|
| S1 | 前置-隔离域 | admin 登录 | POST /admin/domains → 2xx 且 `id` 非空 | 401/403 → admin token 或 org:manage；409 → code 冲突（`PDRG<ts>` 每轮唯一） |
| S2 | 前置-项目组 | 域 id | POST /projects → 2xx 且 `id` 非空 | 404 → 路由未实现；400 → body 字段名与契约不符（见 §3-2） |
| S3 | 前置-成员 5 人 | 项目 id、5 名用户 | 5/5 成功且 GET members 返回 5 条 | 失败看 `role 字段名=`：脚本先试 `roleInProject` 再试 `role`；列表 5 但成 4 → 有一条 409 重复 |
| S4 | 前置-BUS | pm 登录 | POST /requirements type=BUS → 201 | 400 → 缺 priority/domainIds/expectedDeliveryDate；403 → pm 无创建权 |
| S5 | 前置-BUS 入池 | BUS id | GET status=POOLED | 状态停在 DRAFT/REVIEWING → 会签链未走完（gate_pool_bus 的 submit/approve 步骤被 403） |
| S6 | 前置-PDR 夹具 | BUS、域 2、BUS2 | A/B/D/E/F 五个 201、域 2 与 BUS2 也成功 | 400「父需求必须 POOLED」→ 父 BUS 未入池；「域越界」→ 同域创建即可（脚本已按域对建 BUS2） |
| S7 | 前置-需求绑定项目 | PDR-A | GET `view.projectId` = 本项目 | **契约缺口**：§5 无绑定入口；PUT 后仍为空 → 需后端补 `projectId` 字段或关联接口（见 §3-1） |
| S8 | 前置-评审接口就绪 | 无 | 30s 内 `/pdr-reviews/stats` 非 404 | 一直 404 → 后端未合入 PDR 路由/迁移；000 → 网关未起 |
| A1-1 | 项目组成员发起评审 | PDR-A、4 名成员 id | POST pdr-review → 2xx 且 reviewId 非空 | 400 REVIEWER_NOT_IN_PROJECT → 成员未落库（看 S3）；409 → 已有 IN_REVIEW 单 |
| A1-2 | 4 人各提交意见 | reviewId | 4/4 为 2xx | 逐人看最后一次 body：403 → 该用户非评审人；400 INVALID_CATEGORY → 分类码不在 9 类 |
| A1-3 | 意见 4 条、评审人 4 | 同上 | `opinions` 长度 4、reviewerId 去重 4 | 3 条 → 有一步被吞（唯一约束/事务回滚）；字段不在 opinions → 响应层级与契约不符 |
| A3-1 | 非全员通过→REJECTED | 3 PASS + 1 NEED_CHANGE | review.status=REJECTED | 仍 IN_REVIEW → 汇总未触发；PASSED → MODE_ALL 判定写错（放行事故） |
| A3-2 | REJECTED 后可编辑 | PDR-A | PUT title → 200 | 409 → 把 PDR 锁死；400 → 字段级校验误拦 |
| A3-3 | 未过评审 assign-vendor | PDR-A | 409 PDR_REVIEW_REQUIRED | 200 → 门禁失效（最严重）；403 → 走到权限分支而非门禁分支 |
| A3-4 | 重新发起评审 | PDR-A | 2xx 且新 reviewId ≠ 旧 | 409 → 旧单未释放（uk where IN_REVIEW 判定错）；同 id → 复用旧单（历史丢失） |
| A2-1 | 非评审人提交意见 | vendor2 登录 | 403 | 200 → 越权；401 → token/租户头问题 |
| A5-1 | 评审人传 2MB PDF | IN_REVIEW、po1 | 201 且 id 非空 | 500/413 → **multipart 限额未配**（Boot 默认 1MB，见 §3-3）；415 → 白名单未加 PDR_REVIEW |
| A5-2 | 附件列表 | 附件 id | 200 且含刚上传 id | 空列表 → bizType/bizId 未落；403 → 列表未按评审人放行 |
| A5-3 | 下载 sha256 一致 | 本地文件 | 200 且 sha256 相同 | 内容编码/转码 → 下载流被改写；404 → 下载鉴权把评审人拦了 |
| B6-1 | 跨项目下载 | vendor2 | 404 | 403 → 暴露存在性（契约要求 404）；200 → 越权下载（最严重） |
| A3-5 | 3/4 通过仍 IN_REVIEW | 3 PASS | 仍 IN_REVIEW | 提前 PASSED → 「全员通过才放行」失效，会让 A3-3 的 409 失去意义 |
| A3-6 | 第 4 位通过→PASSED | 第 4 条 PASS | review.status=PASSED | 仍 IN_REVIEW → 自动汇总/收口未实现；REJECTED → 汇总把 PASS 当否决 |
| B8-1 | 结束后上传 | 已 PASSED | 409 REVIEW_CLOSED | 201 → 关闭只拦前端；400/500 → 关闭判定用错字段 |
| B8-2 | 结束后下载 | 原附件 id | 200 且 sha256 一致 | 409 → 把读也拦了（契约：关闭只拦写） |
| B1-1 | 未指派厂商评估 | 已过评审、无厂商 | 409 VENDOR_NOT_ASSIGNED | 200 → 门禁漏；400 → 校验顺序错（先查必填再查门禁） |
| B1-2 | 错误体 required | 同上 | `detail.required=assign_vendor` | 字段在顶层 → 前端弹窗取不到 |
| B9-1 | 换厂商缺 reason | 已指派 VEN-A | 400/422 且业务码非空 | 200 → 原因未强制；无 code → 前端无法提示 |
| B2-1 | 指派 VEN-A | 已过评审 | 200 且 `assigneeVendorId`=VEN-A | 200 但字段空 → 未回写；409 → 厂商状态/租户校验误拦 |
| B2-2 | 产品经理评估 | pm 登录 | 403 | 201 → R4 越权（产品经理不应直接报工时） |
| B2-3 | 别家厂商评估 | vendor2（VEN-B） | 403 OUT_OF_SCOPE | 201 → 跨厂商越权（最严重）；403 但 code 不同 → 前端文案对不上 |
| B2-4 | 承接厂商评估 | vpm1（VEN-A） | 201 且评估单 vendorId=VEN-A | 403 → 厂商账号未识别 vendorId；201 但 vendorId 空 → 评估单未绑定厂商 |
| B2-5 | 评估审批 | 评估单 | estimate.status=APPROVED | 停在 PENDING → 审批待办未生成/未找到 WBS_ESTIMATE |
| B3-1 | 换厂商 A→B | 已 APPROVED | 200 且新厂商=VENDOR_B | 409 VENDOR_LOCKED → 窗口判定过早（RD 还没 IN_DEV）；403 → 换厂商权限 |
| B3-2 | 原评估失效 | 换厂商后 | STALE 且 reason=VENDOR_CHANGED | 仍 APPROVED → 排期会用旧工时（资损风险）；reason 不同 → 文案键对不上 |
| B3-3 | 换厂商后排期 | STALE | 409 ESTIMATE_NOT_APPROVED | 200 → 用 STALE 评估放行（资损风险） |
| B3-4 | 新厂商重评 | vendor2 | 201 | 409 → 同一需求 ACTIVE 评估未释放（换厂商未作废旧单） |
| B3-5 | 新评估审批 | 新评估单 | APPROVED | 同 B2-5 |
| B3-6 | 重新排期 | APPROVED | 200 且 PDR=SCHEDULED | 200 但状态没变 → 状态机漏迁移；409 → 排期前置（日期/依赖）未满足 |
| B5-1 | 换厂商审计 | 换厂商已发生 | audit_log 有 VENDOR_CHANGED（本需求、本轮） | 0 条 → 未写审计；有但 biz_id 不是需求 → 口径不同（见 §3-9） |
| B5-2 | 换厂商通知 | 同上 | notification 本轮 ≥1（biz_id∈{需求,评审单}） | 0 条 → 未通知新旧厂商；有但 biz_id 是厂商 id → 口径不同 |
| B7-1 | 25MB 附件 | IN_REVIEW 的 PDR-F | 413 FILE_TOO_LARGE | 500/连接重置 → **multipart 上限 + 异常映射未做**（见 §3-3）；200 → 限额失效 |
| B7-2 | .exe 附件 | 同上 | 415 UNSUPPORTED_MEDIA_TYPE | 201 → 白名单漏；400 → 错误码不对（前端按 415 提示） |
| B4-1 | RD 拆解并 IN_DEV | 已排期 PDR-A | 拆解 201 + RD status=IN_DEV | 拆解失败看 body；DB 更新 0 行 → requirement id 不是拆出的 RD |
| B4-2 | RD IN_DEV 后换厂商 | RD IN_DEV | 409 VENDOR_LOCKED | 200 → 开发中还能换厂商（资损/责任风险）；409 但本轮首次换 → 锁定过早 |
| A1-4 | 空评审人回退 | PDR-D、域负责人=tl1 | 2xx 且 fallback=true | 400 → 空列表被拒（契约允许回退）；fallback 字段缺失 → 前端无法提示 |
| C1-1 | 缺 category | IN_REVIEW 评审单 | 400 INVALID_CATEGORY | 500/DB NOT NULL → 未做入参校验（泄露栈） |
| C1-2 | 非法 category | 同上 | 400 且 `detail.allowed` 9 类 | allowed 少/多 → 字典与常量不一致；无 allowed → 前端只能写死 |
| C2-1 | 6 条意见落库 | 同上 | 6/6 2xx | 第 2 条起 409 → 唯一约束没拆（多人多条被吞） |
| C2-2 | stats=明细 | 6 条意见、域 2 | 每分类计数与明细聚合相等 | 差 1 类 → 统计漏分组/漏状态过滤；stats 形状不同 → 见 §3-4 |
| C2-3 | 占比合计 100% | 同上 | 合计 100±0.6 | 合计 6000 → ratio 是万分比；合计 0 → 未算占比（字段缺失） |
| C3-1 | 数据集白名单 | 无 | `/reports/datasets` 含 `pdr_review_opinion` | 缺 → deliver 侧未注册数据集；名字不同 → 见 §3-5 |
| C3-2 | 交叉表=stats | 建报告并 run | 行计数逐分类相等 | 建报告 400 → definition 结构不同；run 后行空 → 维度/度量 code 不匹配（看实际里的 dim/measure） |
| C4-1 | 厂商口径 | PDR-D(VEN-A,6) | vpm1 stats 合计=6 | 7 → 未按厂商过滤（越权看全量）；403 → 页面码/角色未开 |
| C4-2 | 厂商<全量 | 另建 VEN-B 的 1 条 | 全量 7 > 厂商 6 | 两者相等 → 过滤是空实现；全量≠7 → 对照需求没建成（看 PDR_H 相关失败） |
| D1-1 | 同一人 3 条 | PDR-E、po1 | 3 行且 seq=1/2/3 | 1 行 → (review_id,reviewer_id) 唯一约束未去掉（本轮核心回归） |
| D1-2 | 结论不放大 | 3 PASS + dev1 1 NEED_CHANGE | REJECTED | PASSED → 按意见条数投票（会放过真实反对意见） |
| D1-3 | 意见过滤 | 同上 | reviewerId=po1 返回 3 条 | 返回 4 → 过滤未实现；404 → 端点未实现（契约 §5 有） |
| D2-1 | 意见转任务 | op1 | 201 且 taskId/taskCode 非空 | 503 TASK_CREATE_UNAVAILABLE → deliver 不可达；201 但无 taskCode → 契约字段缺失 |
| D2-2 | 回写 task_id | 已转任务 | status=TASKED 且 task_id=taskId | status 仍 OPEN → 回写丢失；task_id 空 → 事务没提交 |
| D2-3 | 任务可见 | taskId | 需求任务列表含该 id | 空 → requirement_id 没写对（挂错需求/未写） |
| D2-4 | 转任务审计 | 已转任务 | audit_log 有 PDR_OPINION_TO_TASK（本轮） | 0 条 → 未写；有但 action 名不同 → 见 §3-9 |
| D3-1 | 重复转任务 | 同一 op1 | 409 OPINION_ALREADY_TASKED 且 detail.taskId=原任务 | 201 → 重复建任务（脏数据）；409 但无 taskId → 前端无法跳转 |
| D3-2 | 不新增任务行 | 同上 | task 行数前后相等 | +1 → 唯一约束/事务缺失 |
| D4-1 | 非评审人转任务 | vendor2、op2 | 403 | 201 → 越权建任务；409 → 走到重复判定而非权限 |
| D4-2 | 关闭后新增意见 | 评审已关闭 | 409 REVIEW_CLOSED | 201 → 关闭判定失效；409 但状态不是 PASSED/REJECTED → 收口没落 |
| D4-3 | 关闭后仍可转任务 | 关闭评审、op2 | 201 | 409 → 把转任务也锁死（契约明确允许） |

## 2. 覆盖对照（lead 验收项 → 断言）

| 验收项 | 断言 |
|---|---|
| A1 项目组成员评审 | A1-1、A1-2、A1-3、A1-4 |
| A2 非评审人 403 | A2-1、D4-1 |
| A3 全员通过才放行 + 三处 409 | A3-1、A3-3、A3-4、A3-5、A3-6、B1-1、B3-3 |
| A4 附件 201 + sha256 | A5-1、A5-2、A5-3 |
| B1 未指派厂商 409 | B1-1、B1-2 |
| B2 厂商可评估/产品经理 403 | B2-1～B2-5 |
| B3 换厂商 STALE + 排期 409 | B3-1～B3-6 |
| B4 RD IN_DEV 后 VENDOR_LOCKED | B4-1、B4-2 |
| B5 换厂商审计+通知 | B5-1、B5-2 |
| B6 附件越权 404/413/415 | B6-1、B7-1、B7-2 |
| B7 评审结束上传 409、下载 200 | B8-1、B8-2 |
| C1 分类缺失/非法 400 + allowed 9 类 | C1-1、C1-2 |
| C2 stats=明细、占比 100% | C2-1～C2-3 |
| C3 /reports 与 stats 相等 | C3-1、C3-2 |
| C4 厂商口径收窄 | C4-1、C4-2 |
| D1 同一人 3 条、结论不放大 | D1-1～D1-3 |
| D2 转任务 201+可见+回写+审计 | D2-1～D2-4 |
| D3 重复转 409、不新增 | D3-1、D3-2 |
| D4 非评审人 403、关闭后 409/201 | D4-1～D4-3 |

## 3. 契约确认状态（原「10 条待确认」，按 2026-11-25 11:00 落地的后端代码复核）

复核方式：读 `req-service/src/main/java/com/example/req/pdr/{PdrViews,PdrErrors,PdrStatsService,PdrReviewService}.java`、
`req-service/src/main/java/com/example/req/project/ProjectViews.java`、`req-service/.../db/migration/V17__project.sql`。
**仍开着的只有 4 条（1/2/3/5），其余已由代码钉死，脚本按代码写的形状断言。**

1. **【仍缺】需求→项目绑定入口（阻塞 S7/A1）**：`V17__project.sql` 给 `requirement` 加了 `project_id` 列，`PdrReviewService:121` 也读它
   （`requirement.projectId() == null ? 回退 : projects.memberUserIds(...)`），但 `RequirementDtos.CreateRequest/UpdateRequest` 没有 `projectId` 字段、
   `RequirementViews.View` 也不返回它，**没有任何接口能写这一列**。结果：项目组 5 人形同虚设，A1-1 传入的 4 名评审人会被 `REVIEWER_NOT_IN_PROJECT` 拒绝。
   建议：创建/更新支持 `projectId`（或 `POST /projects/{id}/requirements`），并把 `projectId` 加进需求详情。脚本 S7 断言 `view.projectId`，本条不修则 S7/A1-1 必红。
2. **【仍缺】`POST /projects/{id}/members` 的角色枚举大小写**：`ProjectViews.AddMember(userId, roleInProject)` 缺省 `DEV`（代码已确认字段名），
   但 `roleInProject` 是否接受小写 `po/pm/dev/qa/sme` 未写；脚本按大写发，若后端严格校验小写会在 S3 报 400。
3. **【仍缺】multipart 限额与 413 映射（阻塞 A5-1/B7-1）**：全仓仍无 `spring.servlet.multipart.*`；Boot 默认单文件 1MB，
   而契约要 20MB 上限、2MB 正常、25MB→413。需配 `max-file-size≈25MB`/`max-request-size≈30MB` 并把
   `MaxUploadSizeExceededException` 映射成 `413 FILE_TOO_LARGE`（否则 500 或连接被重置）。另：`AttachmentService` 的类型白名单
   （`normalizeType`，现为 REQ/DEFECT/TEST_PLAN/WIKI_PAGE）必须加入 `PDR_REVIEW`，否则 A5-1 直接 415。
4. **【已确认】stats 形状**：`PdrViews.StatsView(groupBy, reviewCount, decidedCount, opinionCount, taskedCount, resolvedCount,
   closureRate, avgReviewHours, p90ReviewHours, buckets[])`，桶是 `Bucket(key,label,count,ratio)`，`ratio` 为 **0–1、scale=4**
   （`PdrStatsService.ratio()`，`total<=0` 时返回 0）。脚本 C2-3 按「≤1.5 则 ×100」处理，C2-2/C4 用 `opinionCount`/桶求和。**不返回 0 计数分类**，故 C2-2 只比对出现过的分类。
5. **【仍缺】`/reports` 交叉表的数据集注册**：req 侧 `GET /pdr-reviews/datasets` 已给出 `pdr_review_opinion`
   （维度 `category/decision/status/reviewerId/reviewId`，度量 `opinionCount/taskedCount/closureRate`），但 `PdrStatsService.datasets()` 的注释
   明说「`/reports` 的数据集注册在 deliver-service/AnalyticsDatasets，是另一件事」——**deliver 侧目前没有 `pdr_review_opinion`**，
   所以 C3-2 现在会红：需 deliver 注册数据集（且 SQL 能读 `pdr_review_opinion` 表）。脚本 C3-1 断言 req 侧清单（可过），C3-2 断言交叉表（当前必红，红即是本条）。
6. **【已确认】厂商统计口径**：`PdrStatsService.reviews()` 用 `scope.canSeeVendor(assigneeVendorId ?: ownerVendorId)` 过滤，
   即按 **需求上的 `assignee_vendor_id`（空则 `owner_vendor_id`）**，与 `wbs_estimate.vendor_id` 无关——换厂商后 STALE 期间不会分叉。C4 按此建数据。
7. **【已确认】错误码**：`PdrErrors` 定义 `REVIEW_NOT_FOUND/PDR_REVIEW_ALREADY_OPEN/REVIEW_CLOSED/REVIEWER_NOT_IN_PROJECT/NO_REVIEWER/
   NOT_REVIEWER/NOT_ALLOWED/INVALID_CATEGORY/INVALID_DECISION/PDR_REVIEW_INCOMPLETE/OPINION_NOT_FOUND/OPINION_ALREADY_TASKED/TASK_CREATE_UNAVAILABLE`；
   `RequirementErrors` 新增 `PDR_REVIEW_REQUIRED/VENDOR_NOT_ASSIGNED/VENDOR_LOCKED/REASON_REQUIRED`。脚本对这些码严格匹配，A2-1/D4-1 只判 403（两者码不同：`NOT_REVIEWER` vs `NOT_ALLOWED`）。
8. **【已确认】「评审关闭」判定**：`closed_at` 由 `conclude()`/`closeReview()` 落；新增意见/上传/删除在 `closed_at != null` 时 409 `REVIEW_CLOSED`，
   而 to-task 不看 `closed_at`（R8）。脚本 D4-2 显式 `conclude` 后再断言，D4-3 用同一张已关闭单。
9. **【已确认】审计/通知 biz 口径**：`biz_type` 用需求/评审单的资源名、`biz_id` 用需求 id（脚本 B5-1/D2-4 用 `action LIKE '%CODE%'` + `biz_id` + 本轮时间过滤，兼容前缀差异）。
10. **【已确认】评审单层级**：`ReviewDetail(review, opinions[])`、`ReviewView(id, requirementId, projectId, status, mode, conclusion, initiatorId,
    reviewerSource, fallback, reviewerIds, note, dueAt, createdAt, closedAt)`、`OpinionPage(items, total, page, size)`、
    `ToTaskResult(taskId, taskCode, opinion)`；`taskCode` 是合成码 `TASK-<id>`（task 表无 code 列）。脚本已按 `review.status`/`items`/`taskId` 取值（并保留旧层级兜底）。

## 4. 实现说明与已知偏离

- **JSON 解析用 jq，不用 python3**：`scripts/tests/platform/scripts-contract.sh` §5 明文禁止 `scripts/` 下调起 Python 做交付动作（Python 交付物禁令，唯一例外是 `env-precheck.sh`）。因此 `_lib.sh` 沿用全仓门禁的 jq 写法（`lib.sh` 的 `jget/jlen`、`change-verify` 的 `jqv` 同款）。功能等价，且不让既有绿灯门禁转红。
- **审计「+1」用本轮时间过滤**：改为 `created_at >= 本轮开始` 的 ≥1 计数，避免依赖上一轮残留，也避免先读后写的竞态（同类断言见 D2-4/D3-2 都是"前后对比"）。
- **BUS 夹具复用既有 `gate_pool_bus`**（`scripts/lib/gate-fixture.sh`）：会签走真实审批接口，不直写 DB；只有「RD 置 IN_DEV」一处用 `db_q UPDATE`（与既有 `editability-verify` 的 `set_status` 先例一致，且断言里注明了这是夹具）。
- **静态门禁已自查**（本地无 shellcheck，仅有 bash -n）：`bash -n` 全绿；`scripts/tests/platform/scripts-contract.sh` 58/58 通过（含「不调 Python」「不 sleep≥10」「bash 3.2 兼容」）。
- 当前仓库 `make lint` 的 L9 已是红（`deliver-service/.../TaskInternalController.java` 直接用 `JdbcTemplate`）——与本任务夹无关，属于后端并行改造的中间态，合并前需修。
