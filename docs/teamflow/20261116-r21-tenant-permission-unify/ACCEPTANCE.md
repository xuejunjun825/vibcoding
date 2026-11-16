# r21 三层授权判定收口 · 验收报告

| 项 | 值 |
|---|---|
| 需求 | r21：8 文件判定收口到 `PermissionService`、删除 `TenantContext.access/can`、a5 的 D3 判据改为「动作码来自请求体」+ 两项 P3 订正 |
| 需求类型 | 技术驱动变更（任务夹无独立 PRD.md，`meta.json` mode=tech）⇒ 验收基准为 `TECH-CHANGE.md` §9 完成定义（DoD）与需求原文中的两项订正 |
| 分支 / 基线 | `feat/rbac-p3-followup` @ `6f93922`（r20 已合回 main） |
| 交付面 | 17 个修改文件（16 个声明的 scopedFiles + `PermissionService.java` 1 行 javadoc）+ 任务夹；`pom.xml` / `package.json` diff 为空 |
| 被测栈 | r21 源码构建（T3 重启后未再变更功能代码；本次验收仅新增编译产物） |
| 验收时间 | 2026-11-16 |
| 结论 | **通过**：P0/P1/P2 = 0，P3 观察 5 项（均不阻断） |

## 1. 逐条验收（DoD → 证据 → 判定）

| AC | 判据 | 验收证据 | 判定 |
|---|---|---|---|
| AC-1 唯一入口（删第二套 oracle） | 业务代码 0 处 `TenantContext.can/access`；`TenantContext` 只留身份/DataScope/clear | `grep -rn "TenantContext\.\(can\|access\)("` 在 deliver/req/platform-common 的 main+test = **0**；`TenantContext.java` 删除两方法及 `PermissionMatrix` import，只剩上下文职责 | PASS |
| AC-2 判定点全量收口 | 8 文件全部注入 `PermissionService`，码→层映射与 TECH §3 一致 | 逐文件核对：Portal 2（`allows(ana.portal, portal.action.config)`）、TestPlan 3（`plan_create` / `canFunction(plan:manage)` / `execute`）、CaseReview 2（decide→`allows(test.cases, test.action.case_review)` + legacy detail；submit→`canFunction(CASE_REVIEW_SUBMIT)`）、Executor（`canFunction(TEST_EXECUTOR_MANAGE)`）、Report（`allows(ana.reports, report.action.share)`）、Defect（`canFunction(proddefect:report)`）、Wiki 4（admin/edit 分落）、Gantt（`allows(collab.gantt, gantt.action.edit)`）。TECH 的「11 处」是估算，实际逐点核对为 **13 行旧表达式 / 15 个逻辑判定点**，无遗漏 | PASS |
| AC-3 业务侧不再直连矩阵 | deliver/req main 0 处 `PermissionMatrix.accessOf` | `grep` = **0**；剩余 `accessOf` 只在 `platform-contract/.../RequirementActionMatrix.java:135,144`（TECH 明确本轮范围外，r22 立项）与测试断言；`accessFor` 仅 `PermissionInterceptor.rolesWith`（测试辅助）与 `RbacSeeder`（种子派生） | PASS（范围外 1 条已登记） |
| AC-4 403 契约逐字不变 | 5 种 403 形状 code/message/detail 不改 | Java diff 中唯一被删的中文行是 `requireFunction("CASE_REVIEW_DECIDE", …)`，新行 `requireMenuAction(..., "CASE_REVIEW_DECIDE", …)` 保留同一 message 与 legacy `detail.function`；其余中文报文零变更；Portal 仍 `AnalyticsErrors.forbidden(PORTAL_CONFIG, GET/PUT, /portal/config)`、Executor/CaseReview 仍 `FORBIDDEN+{function}`、TestPlan 仍 `{planId}`、Defect 仍 `SCOPE_DENIED+{function}`、Wiki 仍 `WIKI_FORBIDDEN` | PASS |
| AC-5 a5 D3 新判据 + 反例自检 | 判据为「动作码来自请求体」；固定单码仍红；access 计数不变 | 独立复跑 `a5-capability-probe.mjs` = **25/25**；独立判据复验 `qa-r21-a5-d3-judge.mjs` = **6/6**（真实规则 body-derived、变异 fixed-single-code 红、仅页面规则 no-action-rule、注解优先、紧邻识别两向准确）；D3 自证仍内联在同一条断言，access 152 未变 | PASS |
| AC-6 两项订正 | r19 `PRD.md:3` / `TECHNICAL.md:6` 的 109→111；memory 补 r21 一行 | `git diff` 确认两处数字为 **111**（注 `s2r` 10 条）、AC 文本未动；`memory.md` 新增 r21 判定入口收口一行，同主题幂等 | PASS（P3-4） |
| AC-7 既有门禁不缩水 | compile + rbac/access/platform/negative/lint/unit/verify/s2r 全绿 | QA 于本交付实测：rbac 111/111、access 152/152（a5 25）、platform-verify 167/167、negative 30/30（地板 28）、lint 22/22、unit 307/307（地板 306）、verify 14/14、s2r 10/10、compile exit 0。本次验收独立复跑 compile（exit 0）、lint（22/22）、a5（25/25）、s2r（10/10，`CANONICAL_HASH=ea21db0efe203777…`） | PASS |
| AC-8 真实翻档位证明 | 探针证明 DENY→403→ALLOW→200、显示谓词同源、双入口并存、库回预设 | QA 报告 22/22：A1 ACTION `portal.action.config` DENY→`GET /portal/config` 403 且 `detail` 无 `page`（证明确出自服务层）；A2 置回 200；A3 `TEST_EXECUTOR_MANAGE` DENY→403；A4 自服务短路径 200；C `wiki.action.edit` 翻转，`canEdit` 27→0→27；D `CASE_REVIEW_SUBMIT` DENY（ACTION 仍 ALLOW）→403 双入口成立；三角色 168 格还原相等。探针含 `trap cleanup EXIT`（`qa-r21-action-deny.sh:80`）。本次验收独立跑 s2r 确认库=预设、hash 与探针前一致 | PASS |
| AC-9 改动面与提交 | 仅约定文件 + 任务夹；无依赖变更；带 runId 单提交 | `git status`：17 个修改文件 + 任务夹，无 `node_modules/target/dist/.env`；pom/package diff 为空。**唯一越界**：`PermissionService.java:24` 一行 javadoc（无功能/接口影响，T1 曾标 OUT-OF-SCOPE，见 P3-1）。提交按流程由 host 在验收后按 runId 收口，当前未提交 | PASS（提交待 host） |

## 2. 架构一致性核查（M3）

- **蓝图对齐**：模块边界未动；判定入口只增加 `PermissionService` 注入，未新增 `require/assert` 包装层；`TenantContext` 从「上下文 + 判定」收为纯上下文，依赖不再指向 contract 的判定常量；`platform-contract ← platform-common ← deliver/req` 方向不变。
- **原子性**：删除两个静态方法与 15 个逻辑点改写在同一编译单元完成；`mvn compile` 与 307 单测一次通过，无过渡期壳子，符合蓝图「多一个入口就多一个可漂移口径」。
- **重复实现**：未新增第二套 oracle；`canFunction` 与旧 `access`、`allows` 与 `decide!=DENY` 同义。`PortalService.java:88/109` 仍有两份逐字相同的守卫（改造前即如此，非本轮引入，2 行，见 P3-2）；`GanttRescheduleService.java:28` 的 `FUNCTION` 常量已成死码（P3-3）。
- **该抽未抽**：无。`CaseReviewService` 两个私有守卫是保留两种 403 形状的必需差异；蓝图为 a5 明确「不新增包装」被遵守。
- **结构破坏**：无。a5 的 D3 判据抽成可导出纯函数并由同文件反例自证，是可测试性改进。

## 3. 独立复核记录（本次验收实际执行）

| 检查 | 命令 | 结果 |
|---|---|---|
| 编译 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -q -DskipTests compile` | exit 0 |
| 静态门禁 | `bash scripts/tests/lint.sh` | 22/22（L18/L19 均绿） |
| 能力门禁 | `ACCESS_COUNTS=/tmp/a5-acceptance-counts.txt node scripts/tests/access/a5-capability-probe.mjs` | 25/25，`a5 25 25` |
| D3 判据复验 | `node logs/teamflow/tf-mwiju0jk-9g728z/scripts/qa-r21-a5-d3-judge.mjs` | 6/6 |
| 库回预设 | `bash scripts/rbac-verify.sh s2r` | 10/10，`CANONICAL_HASH=ea21db0efe203777…`（与 QA 探针前一致） |
| 静态收口 | `grep` 三类关键模式 + 17 文件 diff 人工核对 | 0 处第二套 oracle；403 中文报文零变更；pom/package 零变更 |

未重复执行：9 套门禁中的 rbac/access/platform/negative/unit/verify 与 22 条翻档位断言，采用 QA 于本交付（09:44）的实跑记录；验收侧以 compile、lint、a5、a5-judge、s2r 与源码/diff 复核交叉验证。

## 4. 缺陷与观察（P3，均不阻断）

| 编号 | 级别 | 位置 | 观察 | 建议 |
|---|---|---|---|---|
| P3-1 | P3 | `platform-common/.../security/PermissionService.java:24` | 需求/技术单声明该文件不改，实际改了 1 行 javadoc（`TenantContext.can` → `service-layer guards`），无功能/接口影响，但属 scopedFiles 之外 | 后续把「接口不变」与「注释可订正」在任务单里写清；本轮接受 |
| P3-2 | P3 | `deliver-service/.../portal/PortalService.java:88,109` | GET/PUT 两份逐字相同的守卫条件（改造前已存在） | 可抽 `private boolean canConfigureOthers()`，与服务编码同批处理 |
| P3-3 | P3 | `req-service/.../gantt/GanttRescheduleService.java:28` | `FUNCTION="GANTT_EDIT"` 已无引用，成为死常量 | 清理并复跑 lint/compile |
| P3-4 | P3 | `docs/teamflow/20261113-r19/{PRD,TECHNICAL}.md` | 订正行是就地改写（109→111 + 追加 r21 说明），与 r20 约定的「只新增订正行」措辞不完全一致；内容与实测一致，AC 文本未动 | 下次订正改为追加一行，保留历史行 |
| P3-5 | P3 | `TECH-CHANGE.md` §6 | 风险表写「探针覆盖动作 DENY 与页面 DENY 两例」，实际探针覆盖 ACTION/FUNCTION/display/双入口，未翻 MENU 档；页面 DENY 会先被路由拦截器拒绝，服务层不可达，行为由 rbac s3（页面 DENY+动作 ALLOW⇒403）覆盖 | 修正技术单措辞，或补一条「页面 DENY 由 s3 覆盖」的说明 |

## 5. 遗留与人工补测

- **范围外第三路径**：`RequirementActionMatrix.java:135,144` 仍用 `PermissionMatrix.accessOf` 判定需求动作，建议 r22 立项（需动契约层与 s1/s3 门禁）。
- **编译器禁令缺失**：`PermissionMatrix.accessOf/accessFor*` 仍 public，可在 `scripts/tests/lint.sh` 加 grep 禁令（会改 lint 断言数与地板，需同批调整）。
- **历史残留**：演示库 27 个 `s2空间-*`/`s8厂商空间-*` 为 collab 门禁遗留，非 r21 引入。
- **UI 视觉**：本交付无前端文件改动，无截图可复核；QA 人工清单（wiki「编辑」按钮禁用态、报表「共享」按钮、45s 跨服务传播体感）保留为可选走查。

## 6. 结论

TECH-CHANGE §9 的 5 条 DoD 全部满足：判定入口在真实栈上被证明随运行时档位变化（不是「能编」），预设档位下与改前逐格等价（`s2r` hash 不变），403 五形状零变更，9 套门禁全绿且不低于地板，a5 新判据两向自证。架构符合蓝图：模块边界未动、无第二套 oracle、无新增包装层。P3 观察 5 项与范围外第三路径均不阻断本轮，提交由 host 按 runId `tf-mwiju0jk-9g728z` 收口。

验收结论：✅ 通过
