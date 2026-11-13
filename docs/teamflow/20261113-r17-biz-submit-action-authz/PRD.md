<!-- meta: summary="把需求流转从端点级 req:review 改为按动作矩阵逐动作授权，使业务角色能提交自己的业务需求，同时厂商/非本人仍被拒，allowedActions 与 403 表现同步收敛。" -->
基线依赖：docs/teamflow/20261113-r16-demo-report-preseed（演示重置/演示数据集与 12 步全链路基线，其报表与门户断言不得回归）

# r17 业务需求提交动作级授权（BUSINESS submit 403 修复）

## 1. 背景与目标

演示链路 `docs/teamflow/DEMO-BUSINESS-FLOW.md` 的「卡点 1」：biz1（BUSINESS，租户 t1）打开自己提报、状态 `SUBMITTED` 的业务需求，页面显示「提交审批」按钮，点击后 toast「无权执行该操作」并整页跳 `/403?from=/requirements/{id}&code=FORBIDDEN`（截图 `docs/teamflow/demo-shots/flow-7-biz-submit-403.png`）。演示只能靠 pm 代点绕过。

已定位根因（两处叠加，均需修）：

1. **端点级一刀切**：`platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java:136` 把 `POST /requirements/{id}/actions` 整体要求 `req:review`；而 `platform-contract/src/main/java/com/example/platform/contract/PermissionMatrix.java` 给 BUSINESS 的只有 `req:report:bus`，没有 `req:review`。于是同一端点上的 `submit`（提报方动作）与 `review_start`（评审方动作）共用一个权限码。
2. **动作集合未按调用者过滤 + 前端 403 策略**：`RequirementService.allowedActions(long id)`（`req-service/.../RequirementService.java:269`）原样返回状态机动作，biz1 因此看到「提交审批」；`web/src/api/req.ts:126` 未声明 `silentForbidden`，`web/src/api/client.ts:180` 的默认语义是整页跳 `/403`。

目标：把「需求流转动作」的授权粒度从端点降到**动作**，由一份后端动作矩阵做唯一判定；`allowedActions` 用同一谓词过滤；越权调用仍 403（兜底不撤）；前端失败就地提示、按钮只认后端。

## 2. 用户故事与验收标准

角色与账号（`scripts/platform-seed.sh`，租户 t1）：biz1=BUSINESS、pm=PRODUCT、tl1=PMO、vendor1=PMO+厂商 VEN-A、dev1=DEV、qa1=QA、admin。**注意**：vendor1 的角色码是 PMO（持有 `req:review`），所以「厂商不能代提交」只能由「本人提报」与「厂商账号」谓词区分，不能靠角色码。

### US-1 业务方提交自己的需求（P0）

作为业务方，我要能把「已提报」的业务需求送进审批，以便产品经理评审入池。

- **AC-1（正向·本人）** 以 biz1 对自己提报的 `SUBMITTED` 需求 `POST /api/req/requirements/{id}/actions {"action":"submit"}`：HTTP 200，响应 `status=APPROVING`；`requirement_approval_instance` 恰好新增 1 行（`biz_type=REQ_SUBMIT`、`biz_id=该需求`）；再次 `GET` 详情 `allowedActions` 不含 `submit`。审计与通知沿用现有 transition 落库，不新增第二套写入。
- **AC-2（正向·代提交不回归）** 以 pm 对**他人**提报的 `SUBMITTED` 需求执行 `submit`：HTTP 200、`SUBMITTED→APPROVING`、生成 `REQ_SUBMIT` 实例（与 AC-1 同一路径，仅调用者不同）。

### US-2 按动作授权而不是按端点（P0）

作为平台，我要每个流转动作各自有授权口径，不允许「能评审的人自动能提交」「能提交的人自动能评审」。

- **AC-3（动作矩阵唯一判定）** 新增后端唯一动作矩阵（建议 `platform-contract` 的 `RequirementActionMatrix`，与 `PermissionMatrix` 同包），至少覆盖下表全部动作；`allowedActions` 过滤与 `transition` 执行**调用同一个谓词**（不得两套实现）：

  | 动作 | 授权谓词（`can(action, req, caller)`） |
  |---|---|
  | `submit` | 本人提报（`reporter_id == caller.userId`）**或**（`req:review` 且调用者非厂商账号 `vendorId == null`） |
  | `withdraw` | 本人提报 或（`req:review` 且非厂商账号） |
  | `review_start` / `review_pass` / `review_reject` / `accept` / `close` | `req:review` |
  | `approve` / `reject` | `req:review`（审批引擎回调 `source=APPROVAL` 不经过本矩阵，见 AC-9） |
  | `start_dev` / `deliver` / `merge` / `finish` | `req:deliver` |
  | `submit_test` / `test_pass` | `test:quick` |
  | `schedule` | `release:plan` |
  | `decompose` | `req:split`（独立端点，沿用 `PermissionRouteTable`） |
  | `assign-vendor` | `req:assign-vendor`（独立端点，沿用路由表） |

  判定顺序：**先鉴权、后状态**（无权 → 403，且不回传状态机信息）；动作不在矩阵内视为非法动作 → 409 `ILLEGAL_TRANSITION`（与现状一致）；状态非法但有权 → 409（现状）。
- **AC-4（负向**）** 以下三种调用全部 403，且需求 `status` 与 `requirement_approval_instance` 行数不变（拒绝即不落库）：
  1. vendor1 对 biz1 的需求 `submit` → 403；
  2. biz1 对**他人**提报的需求 `submit` → 403；
  3. 厂商账号对**非本人**提报的需求 `withdraw` → 403。
- **AC-5（评审/拆解不越权，且原有权限不变）** biz1 对 `PENDING_REVIEW` 需求 `review_start` → 403；biz1 调 `POST /requirements/{id}/decompose` → 403（`req:split`）；同时 pm 的 `review_start`/`review_pass`、dev1 的 `start_dev`/`submit_test` 仍 200（不得因本次改动误拒）。

### US-3 按钮只认后端（P0）

作为用户，我不该看到一个点了就报错的按钮。

- **AC-6（allowedActions 按权限过滤，单一真相）** `GET /requirements/{id}` 与 `GET /requirements/{id}/allowed-actions` 返回的集合按 AC-3 谓词过滤，量化断言：

  | 场景 | 断言 |
  |---|---|
  | biz1 自己 `SUBMITTED` | `allowedActions == ["submit"]` |
  | vendor1 看同一需求 | 不含 `submit`（也不含 `withdraw`） |
  | pm 看同一需求 | 含 `submit` |
  | biz1 看 `PENDING_REVIEW` 需求 | 不含 `review_start`/`review_pass`/`review_reject` |
  | biz1 看 `IN_DEV` 需求 | 不含 `start_dev`/`submit_test` |

  前端 `web/src/views/req/RequirementDetailView.vue` 的按钮渲染**只**由服务端 `allowedActions` 决定：删除 `canRender` 里按 `ACTION_META.permissions` 的本地二次过滤（`web/src/views/req/platform.ts:57-79` 的 permissions 字段若无其他引用则一并删除），按钮数量 = `allowedActions` 长度。列表/池行内 `allowedActions`（`RequirementPoolQuery.toView`）为提示性字段、不渲染按钮，本轮不动。
- **AC-7（失败就地提示，不再整页跳 403）** `web/src/api/req.ts` 的 actions 调用声明 `silentForbidden`；`RequirementDetailView.vue` 捕获 403（`FORBIDDEN`/`OUT_OF_SCOPE`）时就地 toast「无权执行该操作，请联系产品经理」并重新拉取详情以同步按钮，`window.location.pathname` 不变、不出现 `/403` 路由；其他页面/其他调用的 403 默认跳转语义不变（r8 约定）。`make lint` 新增静态断言钉住这两个调用点。

### US-4 不回归、可复跑（P0）

- **AC-8（门禁全绿且计数更新）** `make lint` / `make platform-unit` / `make platform-verify` / `make access-verify` / `make platform-test-negative` / `make verify`（r1 14/14）/ `make analytics-i18n-verify`（r14 静态+全量）全绿；`access-verify` 总数 145 → ≥148（新增不少于 3 条，落在 a5 能力探测段，a2 页面矩阵不动，分段下限与总数守卫同步）；`platform-test-negative` 25 → ≥28（新增不少于 3 条）；两处守卫仍是 `PASS == TOTAL`。`PermissionMatrixGridTest` 的 600 断言**不变**（本轮不新增权限码）。
- **AC-9（状态机与审批模板零改动，内部回调不被拦）** `RequirementStateMachine`/`StateTables` 与审批流模板定义文件 diff 为空；`source=APPROVAL`（`RequirementApprovalCallback.onApproved/onRejected/onWithdrawn`）与 `source=GITLAB`（`MrAdvanceService`）的回调路径不经用户动作矩阵，审批通过/驳回/撤回仍能推进需求（`make platform-verify` 全链路 + 审批中心撤回用例为证）。动作矩阵只在 `source=UI`（`RequirementController.action` 唯一入口）时生效。
- **AC-10（拒绝留痕、响应可定位）** 被动作矩阵拒绝的调用：403 响应 `code=FORBIDDEN` 且 `detail` 含 `action` 与当前 `allowedActions`；写 `PERMISSION_DENIED` 审计（`biz_type=REQUIREMENT`、`biz_id=需求 id`，含 action/roles）。`PermissionRouteTable` 中删除的 `/actions` 规则处留注释指向动作矩阵，`PermissionRouteTableTest` 增加「`POST /requirements/5/actions` 不再由路由表给码」的断言，避免下一个人加回去。
- **AC-11（演示链路复跑 + 留证）** 按 `docs/teamflow/DEMO-BUSINESS-FLOW.md` 卡点 1 的复现步骤重跑：biz1 点击「提交审批」→ 页面停留、状态变 `APPROVING`、审批中心出现待办；截图存 `docs/teamflow/20261113-r17-biz-submit-action-authz/evidence/`，并在 `DEMO-BUSINESS-FLOW.md` 卡点 1 追加一行「已修复（r17）」指向本任务夹（不改动历史截图文件）。
- **AC-12（改动边界）** `git diff --stat` 只命中：`platform-contract`（新增动作矩阵 + 测试）、`platform-common`（路由表删除一条规则 + 测试）、`req-service`（`RequirementService` 过滤与执行判定 + 测试）、`web/src/api/req.ts`、`web/src/views/req/RequirementDetailView.vue`、`web/src/views/req/platform.ts`、`scripts/access-verify.sh`+`scripts/tests/access/*`、`scripts/platform-test-negative.sh`、`scripts/tests/lint.sh`、`docs/teamflow/`；**无**新表/迁移/新接口/DTO 变更（403 detail 除外）、无新权限码、无 `ACTION_META` 之外的第二套前端权限表。

## 3. 范围与非目标

范围：上述 AC-1~AC-12；`POST /requirements/{id}/actions` 的动作级授权、`allowedActions` 过滤、前端 403 策略、相关脚本与测试。

非目标：

- 不改状态机语义（状态/边/终态）、不改审批流模板与审批任务语义；不新增审批节点。
- 不改数据隔离与租户语义（`requireVisible`/`ScopeSql` 不动）；不改演示数据夹具（`DemoFixture` 不动）。
- 不做列表/池行的动作集合过滤（该字段不渲染按钮，详情为准）。
- 不新增权限码（`req:submit` 方案被否：角色码无法区分 vendor1=PMO+厂商，仍会越权或误拒）。
- 不改 r1 骨架（`make dev-up/verify/test-negative`）与报表/门户行为。

## 4. anti-goals（反面目标）

技术全绿但下面任何一条成立，这个需求就算失败：

1. **把「能提交」修成「谁都能提交」**：vendor1 或任意业务账号直接打接口就能提交别人的需求——修的是按钮，破的是边界。
2. **UI 藏了按钮、后端照样放行**：`curl -X POST .../actions` 用无权限 token 仍 200 并推进了状态；只有前端过滤，等于没有权限。
3. **点了没反应也没解释**：403 不再跳页，却也不提示或只留一句「请求失败」，用户不知道是自己越权、该找谁、下一步做什么。
4. **只修 submit，其他动作退化成放行或误拒**：为让 submit 通过而把整段 `/actions` 放开，导致无 `req:deliver` 的人能 `start_dev`；或反过来把评审也统一成「本人」，pm 无法评审入池。
5. **内部回调被拦出新状态不一致**：审批中心点击通过/驳回/撤回后，审批实例已结束而需求卡在 `APPROVING`（动作矩阵误伤了 `source=APPROVAL` 的内部调用）。

## 5. 交互流程摘要

1. biz1 打开自己的业务需求（`SUBMITTED`）→ 详情接口返回 `allowedActions=["submit"]` → 按钮可见。
2. 点击「提交审批」→ `POST /requirements/{id}/actions {"action":"submit"}`：
   - 有权：200 → 详情 `status=APPROVING`、按钮区变为「撤回」（本人可撤回）；toast 成功；审批中心出现 `REQ_SUBMIT` 待办给 pm/审批人。
   - 无权（被篡改/按钮过期/他人需求）：403 `FORBIDDEN` → 就地 toast + 刷新详情（按钮按新 `allowedActions` 收敛），页面不跳转。
3. pm 在审批中心通过 → 内部回调 `approve` → `PENDING_REVIEW` → pm 在产品侧 `review_start` / `review_pass` → `POOLED`（既有链路）。
4. 任一动作被拒：`PERMISSION_DENIED` 审计一行；需求状态与审批实例不变。

## 6. 优先级

P0：AC-1~AC-10、AC-12（阻断演示链路，属正确性/越权问题）。P1：AC-11（证据与文档留痕）。

## 7. 依赖与风险

- 依赖：`PermissionMatrix`（不加码）、`PermissionRouteTable`（删一条规则）、`TenantContext`/`UserIdentity`（`roles()`、`principal().vendorId()`、`DataScope.userId()`）、`RequirementEntity.reporterId`。无需新依赖。
- 风险 1：**路由表不再给 `/actions` 兜底** → 若服务层漏判，端点对已认证用户开放。缓解：矩阵默认拒绝 + 覆盖全部动作的契约测试（每个 `StateTables` 动作必须有矩阵行，缺行即红）+ 负向脚本三例。
- 风险 2：**内部回调**（`SOURCE=APPROVAL`/`GITLAB`）误伤 → 需求卡在 `APPROVING`。缓解：仅 `source=UI` 生效，AC-9 用全链路与撤销用例验证。
- 风险 3：**vendor1 角色即 PMO**，任何「加权限码」的修法都区分不了它；矩阵必须带「本人提报」与「厂商账号」两个谓词（AC-3 表格为准）。
- 风险 4：断言计数漂移（145/25/600）→ 脚本守卫与 PRD 不符。缓解：AC-8 只要求下限同步更新，`PASS==TOTAL` 不变。
- 风险 5：前端无单测运行器（仅 `vue-tsc` + `vite build` + `scripts/tests/lint.sh`），AC-6/AC-7 的前端部分靠 lint 静态断言 + 真实 HTTP 探针 + AC-11 截图，不承诺浏览器自动化。

## 8. 工程约束

- **分支**：在当前分支 `feat/biz-submit-permission` 上开发（已存在），**不要新建分支**；基线为该分支当前 HEAD（近期提交 `28d1d63`/`c3e4a79`/`d806027`）。
- **未提交改动处理**：开工前工作区唯一未提交项是 `?? docs/teamflow/20261113-r17-biz-submit-action-authz/`（本需求任务夹），保留并随本次实现一起提交；若出现其他脏文件，先判断是否本需求产物，无关改动不得混入本次提交。
- **禁止**：把 `/actions` 改成「任意登录用户可用」而不加服务端判定；新增权限码；改状态机/审批模板/演示夹具；在前端维护第二套动作权限表。
- **验证命令（提交前全绿，可复跑）**：`make lint` → `make platform-unit` → `make platform-up` → `make platform-seed` → `make platform-verify` → `make access-verify` → `make platform-test-negative` → `make analytics-i18n-verify` → `make verify`。单项复跑：`bash scripts/access-verify.sh`、`bash scripts/platform-test-negative.sh`。
- **提交**：完成并全绿后 `git add` 本需求代码/脚本/任务夹，提交信息建议 `fix(req): 需求流转改为按动作矩阵授权，修复业务角色提交审批 403`；不 push、不打 tag、不合并分支（由用户决定）。
- **.gitignore 审计（dev 阶段动作）**：本栈为 Maven(JDK25)+pnpm/Vite(Vue3)+MySQL+bash。现有 `.gitignore` 已覆盖 `target/`、`*.class`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`logs/`、`.env*`、`*.xlsx`、`*.csv`、`.idea/`、`.vscode/`、`*.pid`、`test-results/`。本轮**不新增产物类型**（新截图 PNG 需入库，`git check-ignore` 必须 0 命中）；若本地跑出未覆盖的新产物（如 `web/*.tsbuildinfo` 之外的缓存），在 dev 阶段补规则，不许带进收尾提交。

## 9. 假设与待澄清

- **（口味）403 提示的呈现强度**：假定用右上角 toast 轻提示「无权执行该操作，请联系产品经理」+ 按钮按新 `allowedActions` 收敛；若用户想要更显眼的解释（页面内 banner / 按钮置灰并附原因），改动只在前端提示层，不影响后端判定。
- **（口味）提交成功后的落点**：假定停留在详情页并刷新为 `APPROVING`（可继续撤回、看审批进度），不自动跳审批中心；若用户希望一键直达审批进度，前端加一次跳转即可。
- **（口味）厂商账号的「本人提报」边界**：假定 `submit`/`withdraw` 对「本人提报」者一律放行（含厂商账号提报自己的需求），只拦「代他人提交/撤回」；若口径是「厂商账号完全不参与提报侧流转」，把谓词里的 `allowSelf` 对厂商账号关掉即可（AC-4 第 1/3 条不受影响）。

## 10. 里程碑建议

- M1（0.5d）后端动作矩阵 + `allowedActions` 过滤 + `transition` 判定 + 路由表删除一条规则 + 单元/契约测试。
- M2（0.5d）前端：`silentForbidden` + 就地提示 + 删除本地权限二次过滤 + lint 静态断言。
- M3（0.5d）脚本：access a5 ≥3 条、negative ≥3 条、计数与守卫更新。
- M4（0.5d）门禁全跑 + 演示复跑截图 + `DEMO-BUSINESS-FLOW.md` 卡点 1 留痕。
