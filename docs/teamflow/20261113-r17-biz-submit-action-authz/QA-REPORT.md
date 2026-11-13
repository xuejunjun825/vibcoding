# r17 需求流转动作级授权 · QA 复验报告（第 2 轮）

- 任务夹：`docs/teamflow/20261113-r17-biz-submit-action-authz/`　分支：`feat/biz-submit-permission`（全部未提交）
- 复验时间：2026-11-13 13:53–13:59　测试者：QA　前置：栈在跑（网关 8080 / web 5173 / CDP headless Chrome 9222），`mvn` 需 `-Dmaven.repo.local=$PWD/.m2/repository`
- 本轮性质：**修复复验**（非全新测试）。只做三件事：重跑上轮全部回归契约；逐一重跑上轮 4 条缺陷的检测命令；补测上轮未覆盖的surface（厂商评审侧全动作/双状态、详情页 DOM craft、构建新鲜度）。

## 1. 结论

**可验收（无 P0/P1/P2 开放缺陷）**。上轮 4 条缺陷（2×P2 + 2×P3）全部修复并经运行时复验；核心诉求（biz1 提交自己的业务需求 → 200/APPROVING，pm 仍可代提交，越权一律 403 且不落库不建实例，前端 403 就地收敛不跳错误页）成立。门禁全绿：lint 20/20、unit 278/278、access 148/148、negative 30/30、platform-verify 168/168、verify 14/14、analytics 74/74。

遗留 3 条 P3 观察（不阻塞验收，见 §7）：池视图本地权限表残留、池视图 403 由跳转变就地提示、矩阵新增的「厂商不得评审」分支缺单测锁定（仅有运行时探针覆盖）。

## 2. 复验方法（含对首轮方法学的一处修正）

- 首轮 BUG-1 的检测命令**本身无效**：其内联 `as()` 只设 `API_TOKEN` 不设 `API_TENANT`，复跑得到 `{"status":403,"code":"TENANT_REQUIRED","message":"缺少 X-Tenant-Id 请求头"}` —— 这个 403 是缺租户头，**不能**证明动作矩阵拦截。本轮改用 `scripts/lib.sh` 的 `as(){ … API_TENANT="$(j '.tenantId')"; }` 重写为独立探针 `qa-r17-vendor-review-scope.sh`，并按「缺陷类」而非「单点」扫全（详见 §4）。此项修正属 QA 自纠，不计交付缺陷。
- 首轮只验了 `review_start` 一个动作、一个状态；本轮扩到评审侧 7 个动作 × 2 个状态 + 自服务正向，避免「修一处、同类漏一片」。

## 3. 构建新鲜度事件（本轮首跑 5 FAIL 的真因，非代码缺陷）

- 现象：`qa-r17-detail-craft.mjs` 首跑 5 项 FAIL——APPROVING 时「撤回」出现 2 个、页脚仍显示 SUBMITTED 文案。
- 定位：`curl -s http://127.0.0.1:5173/src/views/req/RequirementDetailView.vue` 返回的是 `index.html`（5173 是 `vite preview`，服务 `web/dist`）；`web/dist/assets/*` 时间戳 13:38，早于本轮前端修复 → 浏览器拿到的是**旧构建**，`web/dist` 被 `web/.gitignore` 忽略、不进提交面。
- 处置：`cd web && npm run build`（含 `vue-tsc --noEmit`，exit 0，6.48s）重建后复跑 → 19/19 全绿；`docs/.../evidence/` 3 张截图同批重拍（13:58），旧图作废。
- 结论：交付代码无问题；风险在于「改前端后未重建即取证」会产出假证据。后续轮次动前端必须先 `npm run build` 再跑 CDP 探针。

## 4. 上轮缺陷复验（表头用「第 2 轮 级」，仅记录对比，不并入缺陷台账）

| 编号 | 第 2 轮 级 | 原缺陷 | 修复落点 | 本轮复验命令 | 结果 |
|---|---|---|---|---|---|
| BUG-1 | 已关闭 | vendor1（角色码即 PMO、持 `req:review`）可对内部需求 `review_start` → 200 并建 REQ_REVIEW 实例 | `platform-contract/.../RequirementActionMatrix.java:141`：`REVIEW` 系动作且 `caller.vendorId() != null` → 拒绝 | `bash logs/teamflow/tf-mweizygd-pd8fbm/scripts/qa-r17-vendor-review-scope.sh` | PASS 35/35：APPROVING 下 vendor approve/reject 全 403、allowedActions `[]`、状态不变；PENDING_REVIEW 下 review_start/review_pass/review_reject/accept/close 全 403 且 REQ_REVIEW 实例 0→0；pm 不误伤（approve 200 → PENDING_REVIEW、review_start 200 且实例 0→1） |
| BUG-2 | 已关闭 | `DEMO-BUSINESS-FLOW.md` 缺「已修复（r17）」 | 文档 :104 追加并指向任务夹 | `grep -n "已修复（r17）" docs/teamflow/DEMO-BUSINESS-FLOW.md` | PASS 命中 1 行，含 `20261113-r17-biz-submit-action-authz` |
| BUG-3 | 已关闭 | 「撤回」在页头与页脚重复渲染 | `RequirementDetailView.vue:82-83` `footerActionKeys` 让 `headerActions` 排除 submit/withdraw | `node logs/teamflow/tf-mweizygd-pd8fbm/scripts/qa-r17-detail-craft.mjs` | PASS：APPROVING 全页「撤回」恰 1 个、`.detail__actions` 内 0 个 |
| BUG-4 | 已关闭 | APPROVING 时页脚仍显示「提交审批前请确认…」 | `RequirementDetailView.vue:320-331` `footerHint` 按状态分支 | 同上 | PASS：SUBMITTED 含「提交审批前请确认」；APPROVING 含「已提交审批」且不含旧句；PENDING_REVIEW 含「等待产品经理评审入池」（19/19） |

## 5. 回归契约复跑（首轮全部探针，逐字通过数）

| 探针 | 覆盖 | 结果 |
|---|---|---|
| `r17-action-authz-probe.sh` | AC-1/2/3/5/9/10 真栈 | 18/18 PASS |
| `t3-order-probe.sh` | 先鉴权后状态（无权+非法态=403 不泄状态、未登记动作=409） | 15/15 PASS |
| `qa-r17-authorized-actions.sh` | AC-6/AC-10 allowedActions 量化 | 15/15 PASS |
| `t4-frontend-403-smoke.mjs` | AC-7 403 就地处理（响应序列 [403,200]） | 13/13 PASS |
| `ac11-evidence-shots.mjs` | AC-11 取证（小标题截图重拍） | 11/11 PASS |
| `qa-r17-vendor-review-scope.sh`（新增） | BUG-1 缺陷类全扫 | 35/35 PASS |
| `qa-r17-detail-craft.mjs`（新增） | BUG-3/BUG-4 DOM 断言 | 19/19 PASS |

门禁：`scripts/tests/lint.sh` 20/20（L17/L18 在）· `platform-unit.sh` 278/278 · `access-verify.sh` 148/148（a5 25，下限 23）· `platform-test-negative.sh` 30/30（第 16 段 5 条）· `platform-verify.sh` 168/168 · `verify.sh` 14/14 · `analytics-i18n-verify.sh` 74/74 · `cd web && npm run build`（含 vue-tsc）exit 0。
AC-9：`git diff --stat -- '*RequirementStateMachine.java' '*StateTables.java' 'req-service/src/main/java/com/example/req/approval/*'` 为空；回调 source 仅 `APPROVAL` / `INTEGRATION_MR_*`，不经 UI 矩阵。

## 6. 架构与接口一致性核对（静态，命令可复跑）

- **蓝图吻合**：矩阵在 `platform-contract`（`grep '^import' RequirementActionMatrix.java` 只有 `java.util.*`，零外部依赖，符合模块「Deliberately dependency-free」）；签名与蓝图逐字一致——`record Caller(long userId, List<String> roles, Long vendorId)`、`known(String)`、`can(String, Long, Caller)`、`filter(String,String,Long,Caller)`、`actions()`、`requiredFunction(String)`；`RequirementService:639-641` 的 `allowedActions(type,status,reporterId)` 单点委托 `filter`，`authorize`（:617-635）用同一 `can` —— 授权判据全仓仅一份。
- **顺序契约**：`authorize` 在 `requireWritable` 之后、`stateMachine.target` 之前且仅 `"UI".equals(source)`，运行时由 `t3-order-probe.sh`（403 不带 from）与 `r17-action-authz-probe.sh`（回调可推进）双向钉住。
- **无重复实现/抽象缺失**：`PERMISSION_DENIED` 两个写点形状不同、可分流——`RequirementService:634` 写 `biz_type="REQUIREMENT"` + `biz_id`，`PermissionInterceptor:91` 写 `biz_type="function"` + `biz_id=null`，符合蓝图「按 biz_type 分流」。
- **接口一致性（跨模块调用均实证存在）**：`RequirementErrors.actionForbidden(String, List<String>)`（:104）与 `:635` 调用点参数形状一致；`RequirementService.allowedActions(long)`（:269-271）与 `RequirementController:94` 一致；前端 `actOnRequirement(id, payload)`（`web/src/api/req.ts:121-127`）两处调用（`RequirementDetailView.vue:165`、`RequirementPoolView.vue:237`）签名匹配，`silentForbidden` 由 `client.ts:22/180` 既有能力承载；详情页本地权限过滤 `grep -c "usePermission\|disabledReason\|meta.permissions"` = 0（单一真相）。
- **偏差（记录，不判缺陷）**：PRD AC-6 写 `GET /requirements/{id}/allowed-actions`，实现沿用既有 `GET /requirements/{id}/actions`（AC-12 明令不新增接口），属文档笔误。

## 7. 缺陷表（仅列仍开放项）

未发现缺陷（P0/P1/P2 无开放项：上轮 2×P2 + 2×P3 全部关闭，见 §4）。

P3 观察项（craft/技术债，**不阻塞验收**，不计入返工）：

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| OBS-1 | P3 | 需求池（前端） | 打开需求池 → 选行 → 看动作按钮来源 | 动作可用性只认服务端 `allowedActions` | 池视图仍用 `ACTION_META.permissions` 本地过滤（`RequirementPoolView.vue:213/218`），与详情页口径并存；执行侧仍由服务端矩阵鉴权，故仅显示层漂移风险 | AC-6（旁证） | `grep -c "meta.permissions\|ACTION_META" web/src/views/req/RequirementPoolView.vue` | 收敛后应为 0（改造池视图时一并删除） |
| OBS-2 | P3 | 需求池（交互） | 池视图点动作 → 服务端返回 403 | AC-7 字面要求「其他页面默认跳转不变」 | `actOnRequirement` 统一 `silentForbidden` 后，池视图 403 走 `ElMessage.error` 就地提示（`RequirementPoolView.vue:244-248`）而非跳 /403；行为可接受（仍被拒且不丢上下文），但与 AC-7 措辞不符 | AC-7 | `grep -n "messageOf(err)" web/src/views/req/RequirementPoolView.vue` | 建议下轮把 AC-7 措辞改为「动作端点 403 一律就地提示」 |
| OBS-3 | P3 | 授权矩阵（测试） | 查 `RequirementActionMatrix.java:141`（厂商不得评审）的覆盖 | 新增授权分支应有单测锁定 | 该分支仅由运行时探针（35/35）覆盖，`RequirementActionMatrixTest` 无对应用例；后续重构易回退 | AC-3/AC-6 | `grep -c "REVIEW.*vendorId\|vendor.*REVIEW" req-service/src/test/java/com/example/req/requirement/RequirementActionMatrixTest.java` | 应为 ≥1（补 `can("review_start", …, vendorCaller)==false` 断言） |

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 工具 |
|---|---|---|
| toast 动效、按钮 hover/focus 观感、浮层遮挡 | 本地 `make platform-up` 后人工点验 | Chrome DevTools |
| 移动端 100dvh / 安全区 / 多指手势 | 真机或 DevTools 设备模式 | Chrome 设备模拟 |
| 演示脚本整链路（提报→评审→工时/版本→拆解→执行→关闭）人工走查 | 按 `DEMO-BUSINESS-FLOW.md` 顺序操作 | 平台 UI |
| 屏幕阅读器对 403 提示的可达性 | VoiceOver/NVDA 走查详情页 | 系统读屏 |

## 9. 本轮复核脚本与夹具

- `logs/teamflow/tf-mweizygd-pd8fbm/scripts/qa-r17-vendor-review-scope.sh`（35/35，BUG-1 缺陷类全扫）
- `logs/teamflow/tf-mweizygd-pd8fbm/scripts/qa-r17-detail-craft.mjs`（19/19，BUG-3/BUG-4 DOM 断言，复用 9222 CDP）
- 复跑：`r17-action-authz-probe.sh` · `t3-order-probe.sh` · `qa-r17-authorized-actions.sh` · `t4-frontend-403-smoke.mjs` · `ac11-evidence-shots.mjs`
- 探针在演示库遗留 `r17-*`/`qa-*`/`t4-smoke-*`/`craft-*` 夹具（BUS-0227/0228/0270-0283 等），演示前 `make platform-demo-reset` 即可，非交付缺陷。
