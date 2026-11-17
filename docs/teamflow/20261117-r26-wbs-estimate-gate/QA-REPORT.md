# QA 报告 · r26 工时评估初审（WBS_ESTIMATE）与前置门 · 复验轮

- 任务夹：`docs/teamflow/20261117-r26-wbs-estimate-gate/`（PRD / DESIGN / TECHNICAL 同夹）
- 分支：`feat/wbs-estimate-approval`；本轮为**修复后复验**（上轮 1×P1 + 3×P2 → 已修复）
- 结论：**可验收**。D-01~D-04 全部复验关闭（4 条原检测命令全部 exit 0）；**无 P0/P1/P2 未决**；仅 4 条 P3 观察项（见 §5），均不阻塞。

## 1. 范围与环境

- 范围：AC-1 ~ AC-18 全量复验 + 修复引入的新面（驳回参数校验的授权/状态优先序、supersede 作用域、撤回回落链耗尽）。
- 环境：JDK 25 / Spring Boot 4.0.9；真实栈 8080 网关 + 8082 req-service（health UP，db=MySQL up，flyway 13）、前端 `web/dist` 由 5173 提供。
- 浏览器：沙箱内 Chrome 不可启动（restricted token → crashpad 失败，`--no-sandbox` 无效），且 9222 无可连实例——UI 交互类证据用静态断言 + 真导入纯函数 + 真栈 HTTP 替代；人眼项见 §7，**环境限制、非交付缺陷**。
- 数据：所有破坏性探针跑完后执行 `platform-demo-reset` 还原（69/69，11 张 APPROVED / 0 在途）。

## 2. 复验执行（上轮遗留 checker 全部复跑，逐条一次）

| 命令 | exit | 实测（原文计数） |
|---|---|---|
| `bash logs/.../qa-r26-ac4-reject.sh` | 0 | **6/6**（修前 2/6；无 comment、空白 comment 均 400 `REASON_REQUIRED`，实例仍 PENDING） |
| `bash logs/.../qa-r26-withdraw-fallback.sh` | 0 | **7/7**（修前 5/7；撤回后回落 APPROVED/10 人天，start_dev 200） |
| `bash logs/.../qa-r26-uniqueness.sh` | 0 | **7/7**（修前 6/7；两单都通过后 APPROVED=1、PENDING=0） |
| `bash logs/.../qa-r26-gate-matrix.sh` | 0 | **41/41**（AC-7/8/9/10/12 真栈，含 STALE、SUPERSEDED、审计） |
| `bash logs/.../qa-r26-interface-check.sh` | 0 | **54/54**（跨模块成员/签名/常量唯一性） |
| `bash logs/.../qa-r26-estimate-submit.sh` | 1 | 25/27（2 条为探针自身期望写错，见 §4 说明，非缺陷） |
| `bash logs/.../qa-r26-boundary.sh` | 0 | **2/2**（0.5 人天 + 恰好 500 字说明 → 201） |
| `bash logs/.../e2e-estimate-gate.sh` | 0 | **36/36**（提交→或签→回写→排期/开发→改人天 STALE 再拦，到终态） |
| `node scripts/tests/access/a7-estimate-gate.mjs` | 0 | **16/16**（S1–S10 静态 + R1–R6 运行时；含 S10 评估事件入时间线） |
| `bash scripts/access-verify.sh` | 0 | **206/206**（a0 29 / a1 8 / a2 73 / a3 11 / a4 19 / a5 25 / a6 25 / a7 16） |
| `bash scripts/platform-verify.sh` | 0 | **192/192**（含新增 AC-5 撤回回落/唯一性 14 条） |
| `bash scripts/platform-test-negative.sh` | 0 | **111/111**（4c 段含 3 条无/空白 comment 400 负向） |
| `bash scripts/platform-demo-reset.sh` | 0 | **69/69**（estimateApproved 11 / estimatePending 0 / estimateRequirements 11） |
| `bash scripts/platform-unit.sh` | 0 | **367** 个单测全绿（≥338） |
| `bash scripts/rbac-verify.sh` | 0 | **113/113**（11 段，下限已核对） |
| `bash scripts/tests/lint.sh` | 0 | **22/22** |
| `cd web && npx vue-tsc --noEmit` | 0 | 0 error |
| `cd web && npm run build` | 0 | 构建成功（dist 已 gitignore） |
| `cd web && node --experimental-strip-types logs/.../t3-estimate-frontend-check.mjs` | 0 | **57/57**（静态 44 + 真导入 `estimate.ts` 运行时 13） |
| `node logs/.../t3-platform-normalize-estimate.mjs` | 0 | **14/14**（`estimate` 透传） |
| `git status --porcelain`（噪声过滤） | 0 | 42 条改动，**0** 条 `target/`/`node_modules/`/`dist/`/`.env`/`.idea` |
| `git diff --stat HEAD -- pom.xml web/package.json` | 0 | 空（AC-18） |

> rbac 说明：本轮首次 rbac 跑到 s5 时被我中止，残留 1 个自定义角色（168 行）导致第二次 s0/s2 红灯（104/113）；清残留后复跑 113/113。属**套件对预设状态的正常断言**，非交付缺陷。

## 3. AC 用例结果（全 PASS）

| AC | 结论 | 关键证据 |
|---|---|---|
| AC-1 发起 | PASS | 201 PENDING，需求状态不变；BUS → 400、终态 → 400 |
| AC-2 校验 | PASS | 0 / 1.25 / 501 字 / 未知字段 → 400，不建实例；0.5 + 500 字 → 201 |
| AC-3 通过 | PASS | 人天回写、`estimate.status=APPROVED`、审计 `ESTIMATE_APPROVED`；AC-5 段实测 |
| AC-4 驳回 | PASS（原 D-01 关闭） | 无/空白原因 → 400 `REASON_REQUIRED` 且实例保持 PENDING；原因进卡片与时间线 |
| AC-5 唯一性 | PASS（原 D-03 关闭） | 同人天 409 `ESTIMATE_ALREADY_APPROVED`；两轮通过后 APPROVED=1；撤回回落旧单 |
| AC-6 双向可查 | PASS | 审批中心 `?bizType=WBS_ESTIMATE` 筛选与「工时评估」标签；详情页签按 `instanceId` 取实例 |
| AC-7 / AC-8 门 | PASS | 409 `ESTIMATE_NOT_APPROVED`，detail 三键；状态与 `STATE_CHANGE` 均不变 |
| AC-9 四态拦 | PASS | NONE/PENDING/REJECTED/STALE 真栈；SUPERSEDED 由矩阵单测 + 真栈残留单覆盖 |
| AC-10 ±20% | PASS | 10→12.0 放行、10→12.5 拦；PUT 与 deliver 两路径；null → STALE |
| AC-11 同源 | PASS | `statusOf/ensureGate/applyGate` 一处判定；`0.20` 仅一处 |
| AC-12 顺序 | PASS | 403 → `ILLEGAL_TRANSITION` → r23 门 → 评估门（新增 A 面：非受理人 403 先于参数校验） |
| AC-13 卡片+动作行 | PASS（静态+HTTP） | 按钮仍渲染+disabled+`gateActionTip` 单一来源；通过后可点；权限文案与门文案分函数 |
| AC-14 页签三态 | PASS | 加载/失败/空三态中文 |
| AC-15 夹具 | PASS | reset 后 11 张 APPROVED、0 在途、11 条需求各一张 |
| AC-16 门禁补装 | PASS | verify 192（≥174）、negative 111（≥96） |
| AC-17 不缩水 | PASS | 见 §2：rbac 113 / access 206 / verify 192 / negative 111 / demo-reset 69 / unit 367 / lint 22 / typecheck+build+compile 绿 |
| AC-18 改动面 | 部分（仅 P3 证据，见 §5） | 改动面与 pom/package.json diff 空；memory 含「渲染看权限、禁用看门」；**evidence 截图仍缺（环境限制）** |

## 4. 缺陷复验对照（仅对照用；不属本轮开放缺陷）

| 编号 | round-1 级 | 复验结论 | 复验命令（原样） | 实测 |
|---|---|---|---|---|
| D-01 | P1 | 已关闭 | `bash logs/teamflow/tf-mwjyh9m4-f01rmh/scripts/qa-r26-ac4-reject.sh` | exit 0，6/6 |
| D-02 | P2 | 已关闭 | `bash logs/teamflow/tf-mwjyh9m4-f01rmh/scripts/qa-r26-withdraw-fallback.sh` | exit 0，7/7 |
| D-03 | P2 | 已关闭 | `bash logs/teamflow/tf-mwjyh9m4-f01rmh/scripts/qa-r26-uniqueness.sh` | exit 0，7/7 |
| D-04 | P2 | 已关闭 | `grep -rqE "ESTIMATE_(SUBMIT\|APPROVED\|REJECTED)" web/src/views; echo exit=$?` | exit=0（修前 1） |

命中数复核（与修复摘要一致性）：`public void reject(` 写入口 **1**；引擎内 `REASON_REQUIRED` **3** 处（含新增前置校验）；`supersedeApproved` 定义+调用 **2** 处；`effectiveInstance` 仅 `WbsEstimateService`；`ESTIMATE_*` 字面量仅 `estimate.ts`（消费方走常量）——**与修复摘要一致，无分歧**。

说明：`qa-r26-estimate-submit.sh` 的 2 条失败为探针期望写错，已复核非交付缺陷——①审批实例详情字段名是 `document`（`ApprovalViews.java:98-103`）而非 `summary`；②需求「动态」无独立 REST 端点（探针打的路径 404），评估事件由详情页 `estimateTimeline` 合成。

## 5. 本轮缺陷表（仅剩开放项，均为 P3 观察）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-05 | P3 | 架构 | 对照注入蓝图核 `seed/` 与 `DemoResetService` | 蓝图指定 `seedEstimateWorkflow`/`backfillDomainOwners` 方法、`ResetResult resetBusiness()` | `SeedService` 内联建流程与回填、`DemoResetService` 返回 `Map<String,Long>`（全仓无 `ResetResult`）；不影响跨模块调用，无重复实现 | AC-18 / 技术方案 | - | - |
| D-06 | P3 | 交付物 | 查看任务夹 | `evidence/` ≥3 张截图 | 目录不存在（浏览器不可用，见 §7） | AC-18 | - | - |
| N-01 | P3 | 文档/记忆 | 查看 `docs/teamflow/memory.md:36` | r26 实测基线随修复轮更新 | 仍写 verify 178 / negative 108 / access 205 / a7 15，本轮实测 192/111/206/16 | AC-18 | - | - |
| N-02 | P3 | 审批引擎 | 新单通过取代旧 APPROVED 后查 `wf_history` | 历史动作能区分「被取代」 | `appendHistory` 复用 `INSTANCE_WITHDRAWN` 常量（评论已写明「已被新的评估单取代」，纯语义标注） | AC-5 | - | - |

未发现 P0/P1/P2 缺陷。

## 6. 本轮新增面（上轮未覆盖，原因为何）

上轮聚焦「六态 + 门 + 唯一性」，未覆盖**修复本身引入的优先序与作用域**。新增 `qa-r26-r2-new-surfaces.sh` → **19/19**：

- **A 授权/状态先于参数校验**：vpm1 与管理员（非受理人）非法驳回 → 403（响应不含 `REASON_REQUIRED`）；受理人 pm 非法驳回 → 400（真实校验）；已处理任务再驳 → 409 `TASK_ALREADY_HANDLED`。证明 D-01 的修复没有把校验插到鉴权之前。
- **C 零副作用**：非法驳回后实例终态仍 APPROVED。
- **D supersede 作用域隔离**：批 RD_Y 的新单后 RD_X 的 APPROVED 仍为 1、SUPERSEDED=0（`supersedeApproved` 只按 tenant+bizId 收敛）。
- **E 回落链耗尽**：唯一一张单被撤回 → `estimate.status=NONE`，`start_dev` 409 且 `detail.status=NONE`。

## 7. 架构与接口核查

- **蓝图落地**：`WBS_ESTIMATE` 常量在 `WorkflowVersion.BIZ_WBS_ESTIMATE` 唯一声明；`STATUS_SUPERSEDED` 在 `ApprovalInstance`；`EstimateSnapshot` 快照工具类被 Service/Provider 共用；门唯一插点在 `RequirementService.transition`；零新增表/列/迁移（`git status` 无 `.sql`）。
- **判定同源**：`statusOf` 一处派生，`ensureGate`/`applyGate`/`view` 全部复用它，阈值 `0.20` 单点；`supersedeActive`（重提）与 `supersedeApproved`（通过后取代）职责不重叠。
- **接口一致性**：`qa-r26-interface-check.sh` 54/54 —— 每个跨模块调用（常量、`DocumentSummaryProvider` 实例重载、`EstimateView` 字段、`Detail.estimate`、`submitEstimate`、`estimate.ts` 导出、`bizKeyOf`）均在目标模块存在且签名匹配；前端 `EstimateBlock` 与后端 `EstimateView` 字段逐一对应。
- **提交卫生**：42 条改动全部落在 approval/requirement/seed/web/scripts/docs，无构建产物或密钥；`pom.xml`、`web/package.json` diff 为空。

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法 / 工具 |
|---|---|---|
| AC-13 真实浏览器交互 | 未评估时 `disabled=true`、title 与行内提示逐字一致、点击零请求、通过后同位置可用、权限文案「需要权限：<码>」 | Chrome 9222 + `node logs/teamflow/tf-mwjyh9m4-f01rmh/scripts/r26-t3-estimate-ui.mjs`（dev 侧 30/30） |
| AC-18 evidence 截图 | ≥3 张：禁用态+原因、审批中心待办「工时评估」、驳回原因 | 同上，存 `evidence/` |
| 视觉走查 | 960/1440 卡片整宽、禁用三色、内联 SVG 锁图标不抖动 | 浏览器对照 DESIGN.md |
| 键盘/读屏 | 禁用按钮不进 tab 序，门原因可读 | Tab 走查 + 读屏 |
| 并发或签 | 双审批人同时通过只有一张生效 | 双会话并发脚本 |

## 9. 结论

- 主链真栈跑到终态：提交 → 待办 → 或签通过 → 人天回写 → `schedule`/`start_dev` 放行；改人天超 ±20% 读时派生 STALE 重拦；被拦不落库、不写 `STATE_CHANGE`；判定顺序 403 → `ILLEGAL_TRANSITION` → 既有门 → 评估门成立。
- D-01~D-04 的四条原检测命令全部转绿且未被绕过（新增面 A/B/C/D/E 19/19）；全部门禁只增不减（rbac 113 / access 206 / verify 192 / negative 111 / demo-reset 69 / unit 367 / lint 22 / typecheck+build/compile 绿）。
- **可验收**：无 P0/P1/P2；P3 四项（蓝图方法抽取、evidence 截图、memory 基线漂移、supersede 历史动作标注）建议进 backlog，不阻塞本次交付。
