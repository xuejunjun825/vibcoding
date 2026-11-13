# TECHNICAL — r17 需求流转动作级授权（修复业务角色提交审批 403）

> 基线：`docs/teamflow/20261113-r17-biz-submit-action-authz/PRD.md`（12 条 AC）
> 分支：`feat/biz-submit-permission`（已存在，不新建、不 push、不 merge）
> 结论一句话：把「端点级 `req:review`」换成「`platform-contract` 里的一张动作矩阵」，`allowedActions` 过滤与 `transition` 执行调用**同一个谓词**，前端只认服务端返回的 `allowedActions`。

---

## 1. 根因与判定

| # | 位置 | 现状 | 后果 |
|---|---|---|---|
| R1 | `platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java:136` | `rule("POST", "^/requirements/\\d+/actions$", false, "req:review")` | 整个动作端点在**过滤器**层就被 `req:review` 拦住；BUSINESS 只有 `req:report:bus` → biz1 点「提交审批」必 403 |
| R2 | `req-service/src/main/java/com/example/req/requirement/RequirementService.java:595` | `allowedActions(String type,String status)` 直接返回 `stateMachine.allowedActions(...)`，**不过滤** | 按钮照常出现、点了才 403；`allowedActions` 与执行是两套口径 |
| R3 | `web/src/api/client.ts:180` 一带 | 403 默认 `navigate('/403')` | 页面拿不到错误对象，只能整页跳走 |
| R4 | `web/src/views/req/RequirementDetailView.vue:88` | `disabledReason()` 用 `meta.permissions` 做前端二次过滤 | 前端出现第二套权限真相，与后端不一致时按钮被判死 |

修法（与 PRD AC-3/AC-6/AC-7 对齐）：
1. 删除 R1 的路由规则（留注释），端点改为「已认证即可达」；
2. 在 contract 新增 `RequirementActionMatrix`，**一个动作一行**，`filter()`/`can()` 同源；
3. `RequirementService.transition` 在 **`requireWritable` 之后、`stateMachine.target` 之前**插入 `authorize()`：先鉴权后状态；
4. 前端删本地 `permissions` 过滤，`actOnRequirement` 声明 `silentForbidden`，403 就地提示 + 刷新详情。

## 2. 数据模型与存储

**无新表、无迁移、无新列、无新权限码**（AC-12，且 `PermissionMatrixGridTest` 600 断言必须保持不变）。

- 动作矩阵是**代码常量**，落在 `platform-contract`：`Map<String,String>`（动作 → 权限码）+ `Set<String> SELF_SERVICE = {submit, withdraw}`。
- 拒绝留痕写既有 `audit_log`：`action=PERMISSION_DENIED`、`biz_type=REQUIREMENT`、`biz_id=<需求 id>`、`detail` 含 `action`/`userId`/`roles`/`vendorId`/`allowedActions`。
- 审批实例落 **既有** `wf_instance`：`biz_type='REQ_SUBMIT'`（`RequirementApprovalCallback.BIZ_TYPE`）、`biz_id=<需求 id>`、`active_flag=1`。
  > PRD 正文写的 `requirement_approval_instance` 是概念名；仓库真实表是 `wf_instance`（`req-service/src/main/java/com/example/req/approval/ApprovalInstance.java:19` `@Table(name="wf_instance")`）。脚本断言一律按 `wf_instance` 写，`biz_type` 取 `'REQ_SUBMIT'`（复核阶段是 `'REQ_REVIEW'`）。

## 3. 动作矩阵（唯一真相）

新文件：`platform-contract/src/main/java/com/example/platform/contract/RequirementActionMatrix.java`
该模块 pom 注释明确「Deliberately dependency-free」，因此 **API 只用基本类型 + `java.util`**，不引用 `RequirementEntity`/`TenantContext`。

```java
public final class RequirementActionMatrix {
    /** 调用者快照：由服务层从 TenantContext 组装，contract 不反向依赖 platform-common。 */
    public record Caller(long userId, List<String> roles, Long vendorId) {}

    public static boolean known(String action);                                  // 该动作是否在矩阵登记
    public static boolean can(String action, Long reporterId, Caller caller);     // 唯一谓词
    public static List<String> filter(String type, String status,
                                      Long reporterId, Caller caller);            // allowedActions 唯一来源
    public static Set<String> actions();                                          // 已登记动作全集（测试用）
    public static String requiredFunction(String action);                         // submit/withdraw→"req:review"，其余→对应码，未登记→null
}
```

谓词（**必须与 PRD AC-3 表格逐行一致**）：

| 动作 | 授权判定 |
|---|---|
| `submit`、`withdraw` | `reporterId != null && reporterId == caller.userId()` **或**（`caller.vendorId() == null` 且 `accessOf("req:review", roles) == ALLOW`） |
| `approve`、`reject`、`review_start`、`review_pass`、`review_reject`、`accept`、`close` | `req:review` |
| `start_dev`、`deliver`、`merge`、`finish` | `req:deliver` |
| `submit_test`、`test_pass` | `test:quick` |
| `schedule` | `release:plan` |

- 16 个流转动作全覆盖（`submit, approve, reject, withdraw, review_start, review_pass, review_reject, close, schedule, start_dev, deliver, accept, submit_test, test_pass, merge, finish`）；`decompose`/`assign-vendor` 是**独立端点**，继续由路由表守着（`req:split` / `req:assign-vendor`），不进矩阵，也不进 `filter()`。
- `can` 对未登记动作返回 `false`；`known()` 为 `false` 时**调用方不得据此报 403**（见 §4 的顺序）。
- `filter()` 读 `StateTables.actions(type,status)`（同模块，零依赖），按表内顺序过滤；`StateTables` 抛异常时返回空列表（与 `RequirementStateMachine` 的容错一致），不新增第二份迁移表。
- 角色为空 → `PermissionMatrix.accessOf` 本身 fail-closed 到 DENY，矩阵不另写判空。

## 4. 后端改造

### 4.1 `PermissionRouteTable`（platform-common）

删除 `PermissionRouteTable.java:136` 的 `actions` 规则，原地留注释（AC-10 要求「指向动作矩阵」）：

```java
// POST /requirements/{id}/actions 故意不登记：需求流转是「一动作一授权」，
// 由 platform-contract 的 RequirementActionMatrix 在服务层逐动作判定（r17）。
// 在这里登记任何一个权限码，都会把 BUSINESS 的 submit 或 PMO 的 review 之一挡在门外
// （r17 之前正是 req:review → biz1 提交审批 403）。
```

删除后该路径**无路由规则 = 任何已认证账号可达**，与 `GET /requirements/{id}` 同口径（读/写由服务层判）。已确认 `requirements` 段无 `^/requirements(/.*)?$` 兜底规则，后续段落（`quality/`、`^/(testcases|…)`）也不匹配该路径；`PermissionRouteTableTest` 加锁定断言防止他人加回。

### 4.2 `RequirementService`（req-service）

```java
private RequirementActionMatrix.Caller callerOf(DataScope scope) {
    TenantContext.Principal p = TenantContext.principal();
    return p != null
            ? new RequirementActionMatrix.Caller(p.userId(), p.roles(), p.vendorId())
            : new RequirementActionMatrix.Caller(scope.userId(), TenantContext.roles(), null);
}

/** 矩阵未登记的动作原样返回空，交给状态机 409 —— 鉴权不知道的动作不算「无权执行」。 */
private void authorize(RequirementEntity entity, String action, DataScope scope) {
    if (!RequirementActionMatrix.known(action)) {
        return;
    }
    RequirementActionMatrix.Caller caller = callerOf(scope);
    if (RequirementActionMatrix.can(action, entity.getReporterId(), caller)) {
        return;
    }
    List<String> allowed = allowedActions(entity.getType(), entity.getStatus(), entity.getReporterId());
    Map<String, Object> detail = new LinkedHashMap<>();   // 不用 Map.of：vendorId 可为 null
    detail.put("action", action);
    detail.put("userId", caller.userId());
    detail.put("roles", caller.roles());
    detail.put("vendorId", caller.vendorId());
    detail.put("allowedActions", allowed);
    auditService.recordInNewTx("PERMISSION_DENIED", BIZ_TYPE, entity.getId(), detail); // 新事务，随后的 403 回滚不影响它
    throw RequirementErrors.actionForbidden(action, allowed);
}

List<String> allowedActions(String type, String status, Long reporterId) {   // 原 (type,status) 的唯一替代
    return RequirementActionMatrix.filter(type, status, reporterId, callerOf(TenantContext.require()));
}
```

`transition(...)`（`RequirementService.java:316` 起）的**唯一**插入点，放在 `requireWritable` 之后、`stateMachine.target` 之前：

```java
DataScope scope = TenantContext.require();
RequirementEntity entity = requireWritable(id);
if ("UI".equals(source)) {              // AC-9：APPROVAL（审批回调）/ GITLAB（MrAdvanceService）旁路
    authorize(entity, action, scope);
}
String from = entity.getStatus();
String target = stateMachine.target(entity.getType(), from, action);
```

顺序不变量（写成 JUnit + 负向脚本双保险）：

| 场景 | 期望 |
|---|---|
| 有权 + 状态合法 | 正常流转 |
| **无权 + 状态非法** | **403**（先鉴权；不泄露 `from`/`allowedTargets`） |
| 有权 + 状态非法 | 409 `ILLEGAL_TRANSITION`（现状不变） |
| 动作未登记（如 `bogus`） | 409 `ILLEGAL_TRANSITION`（`known()==false` 直接跳过鉴权） |
| `source != "UI"` | 完全不进矩阵（回调必须能推进需求，AC-9） |

其余 5 处 `allowedActions(...)` 调用点补 reporter 参数：`RequirementService.java:131`（create 结果）、`:266`（detail）、`:271`（`allowedActions(long id)`）、`:307`（review_start 返回）、`:352`（transition 结果）、`:613`（toView）。全部传 `entity.getReporterId()`。

### 4.3 `RequirementErrors`

```java
public static ApiException actionForbidden(String action, List<String> allowedActions) {
    return new ApiException(403, FORBIDDEN, "无权执行该操作",
            Map.of("action", action == null ? "" : action, "allowedActions", allowedActions));
}
```

## 5. API 契约（无新端点，只有错误体与集合口径变化）

| 端点 | 变化 |
|---|---|
| `POST /api/req/requirements/{id}/actions` | 授权口径改为逐动作；拒绝时 `403 {code:"FORBIDDEN", message:"无权执行该操作", detail:{action, allowedActions}}`；成功响应 `{id, status, allowedActions}` 不变 |
| `GET /api/req/requirements/{id}` | `view.allowedActions` / `detail.allowedActions` 变为**按调用者过滤后**的集合（同一谓词）；字段名、结构不变 |
| `GET /api/req/requirements/{id}/actions` | 同上（`{id, allowedActions}`） |

`allowedActions` 的可见语义（AC-6）：
- biz1 看自己 `SUBMITTED` 的 BUS 需求 → 含 `submit`；
- vendor1 看同一需求 → 不含 `submit`（无 `req:review`，也无「本人提报」）；
- pm 看他人 `SUBMITTED` 需求 → 含 `submit`（`req:review` 且 `vendorId==null`）；
- biz1 在 `PENDING_REVIEW` → 不含 `review_*`；`IN_DEV` → 不含 `start_dev`/`deliver`。

## 6. 前端改造

### 6.1 `web/src/api/req.ts:126`

```ts
export function actOnRequirement(id, payload): Promise<ActionResponse> {
  // 403 由详情页就地处理（r17）：整页跳 /403 会把「按钮过期」变成一次页面丢失
  return http.post<ActionResponse>(`${REQ}/requirements/${id}/actions`, payload, { silentForbidden: true })
}
```
`client.ts` 的 `request()` 已有 `silentForbidden` 分支（r8），本轮**零改动**；`post<T>(url, body?, extra?)` 在 `client.ts:218`。

### 6.2 `web/src/views/req/RequirementDetailView.vue`

| 改点 | 动作 |
|---|---|
| 头部按钮（模板 ~`:355`） | 去掉 `:disabled="!!disabledReason(action) \|\| busy"` → `:disabled="busy"`；去掉 `:title="disabledReason(action)"`；底部 submit/withdraw 按钮同样只看 `busy` |
| `disabledReason()`（:86-90） | 整函数删除（本地二次过滤，R4） |
| `const { can } = usePermission()`（:49）与 `import { usePermission }`（:37） | 删除（`noUnusedLocals: true`，留着 `vue-tsc` 会红） |
| 文件头注释（:40-43） | 改为「动作区只渲染 allowedActions；403 就地提示并刷新」 |
| `handleActionError()`（:151-161） | 新增 403 分支 |

```ts
function handleActionError(error: unknown): void {
  const status = (error as { status?: number }).status
  const code = (error as { code?: string }).code
  if (status === 409) {                       // 现状：常驻条，不 toast
    actionBanner.value = { text: messageOf(error), code }
    void refreshAll()
    return
  }
  if (status === 403) {                       // r17：就地提示 + 同步按钮，不跳 /403
    ElMessage.warning(code === 'OUT_OF_SCOPE' ? messageOf(error) : '无权执行该操作，请联系产品经理')
    void refreshAll()
    return
  }
  ElMessage.error(messageOf(error))
}
```

状态管理：不新增 store/全局状态。`allowedActions` 仍是 `computed(() => detail.value?.allowedActions ?? [])`，唯一来源是后端响应；`refreshAll()` 已在 403/409 后重拉详情+审批，按钮随服务端收敛。

**不动的部分**：`web/src/views/req/platform.ts` 的 `ACTION_META.permissions` 保留（`RequirementPoolView.vue:213/218` 仍在读，改它就是第二处越界）；但**详情页不再读它**，`ACTION_META` 退化为纯文案/危险标记表。

## 7. 门禁与测试策略

### 7.1 计数（AC-8，全部只增不减）

| 门禁 | 现状 | 目标 | 新增内容 |
|---|---|---|---|
| `make access-verify` | 145（a5 实 22，下限 20） | **148** | a5 新增 a5-d 三条静态断言；`scripts/access-verify.sh` 把 a5 下限 20→23、总数守卫 `TOTAL<143`→`<148`（同步头注释） |
| `make platform-test-negative` | 25 | **28** | 第 16 段三条负向（AC-4 三例），`PASS==TOTAL` 守卫不变 |
| `PermissionMatrixGridTest` | 600 | **600** | 不动（不新增权限码） |
| `scripts/tests/lint.sh` | L1–L16 | **L1–L18** | L17 前端单点真相、L18 路由表删除位 |

### 7.2 新增 JUnit（`req-service/src/test`，沿用 `PermissionMatrixGridTest` 先例，**不改 pom**）

`req-service/src/test/java/com/example/req/requirement/RequirementActionMatrixTest.java`
1. `everyStateActionHasARow`：遍历 `StateTables` 三类需求全部 `(type,status)`，`actions()` 的并集 == 矩阵已登记集合（16 个），双向相等 —— 漏一个动作即红（AC-3）。
2. `businessSubmitsOwnRequirement`：`can("submit", biz1Id, caller(BUSINESS,biz1Id,null))` → true。
3. `businessCannotSubmitOthers`：reporter=pmId → false。
4. `vendorWithReviewRoleCannotSubmitForOthers`：`caller(PMO, otherId, 1L)` → **false**（vendor1 的角色码就是 PMO，这条锁死「加权限码修不了」的坑）。
5. `pmoWithoutVendorMaySubmitForOthers`：`caller(PMO, otherId, null)` → true（AC-2 代提交不回归）。
6. `reviewAndDevAndTestFunctions`：`review_start` BUSINESS→false / PRODUCT→true；`start_dev` DEV→true / BUSINESS→false；`test_pass` QA→true。
7. `filterMatchesCan`：对 `(BUS,SUBMITTED)`，biz1 自己 → `[submit]`，vendor1 → `[]`，pm → `[submit]`；`(BUS,PENDING_REVIEW)` 下 biz1 → `[]`（AC-6）。
8. `unknownActionIsUnknown`：`known("bogus")==false`、`can("bogus",…)==false`。

`req-service/src/test/java/com/example/req/sys/PermissionRouteTableTest.java` 新增：
```java
@Test
void requirementActionsAreNotRouteGuarded() {
    assertNull(PermissionRouteTable.requirementFor("POST", "/requirements/5/actions"),
            "动作端点必须交给 RequirementActionMatrix 逐动作授权；加回任何权限码都会重现 r17 的 403");
}
```
（`decompose→req:split`、`quick-test→test:quick` 的既有断言保留，证明独立端点没被误删。）

### 7.3 a5 能力探测新增 3 条（`scripts/tests/access/a5-capability-probe.mjs`，**原地扩展**，a5-a~a5-c 一条不减）

新增 `---- a5-d 单点真相（r17）----` 段，复用文件内已有的 `callArgs`/`declaresSilent`/`blankComments`：
- **D1** `web/src/api/req.ts` 的 `actOnRequirement` 调用点恰好 1 处且实参含 `silentForbidden`（防空扫：找不到调用点也判红）。
- **D2** `RequirementDetailView.vue`：`blankComments()` 后含 `status === 403` 且含 `refreshAll()`，且**不含** `navigate('/403'`；同时 `disabledReason`/`meta.permissions` 在该文件出现 0 次。
- **D3** `platform-common/.../PermissionRouteTable.java` 已无 `^/requirements/\\d+/actions$` 规则，且 `platform-contract/.../RequirementActionMatrix.java` 存在并同时出现 `submit` 与 `req:review`（服务端唯一真相的结构证据）。

头注释 `22 条断言` → `25 条断言`，`a5-a/a5-b/a5-c` 说明同步；计数仍经 `ACCESS_COUNTS` 交回 `access-verify.sh`。

### 7.4 负向脚本第 16 段（`scripts/platform-test-negative.sh`，25→28）

沿用既有 `login_as`/`api_call`/`deny`/`db_q`，新增一次夹具创建 + 三条断言（每条同时校验「拒绝」与「没落库」）：

```
16) 需求流转动作级授权（r17）
  夹具：login_as biz1 → POST /api/req/requirements {type:BUS,title:neg-r17-*,domainIds:[DOMAIN1]} → B
        login_as pm   → 同法建 P（reporter=pm）
  a) vendor1 submit B            → 403 FORBIDDEN（detail 含 "action":"submit"），requirement.status 仍 SUBMITTED，wf_instance(biz_type='REQ_SUBMIT',biz_id=B) 计数 0
  b) biz1    submit P            → 403，P.status 仍 SUBMITTED，instance 计数 0
  c) vendor1 withdraw B          → 403（SUBMITTED 上 withdraw 本是 409：先鉴权后状态的证据），B.status 仍 SUBMITTED
  # 每条断言结束后 API_TOKEN 复位为 ADMIN_TOKEN
```
`deny` 的 needle 用 `"action":"submit"`（`jq -c` 稳定体），计数用 `db_count wf_instance "tenant_id=${TID} AND biz_type='REQ_SUBMIT' AND biz_id=${B}"`。

### 7.5 lint 新增两项（`scripts/tests/lint.sh`，加在 L16 之后）

- **L17 需求动作前端单点真相**（`requirement_action_frontend_single_source`）：`api/req.ts` 的 `actOnRequirement` 实参含 `silentForbidden`；`RequirementDetailView.vue` 含 `status === 403` 与 `refreshAll()`、不含 `navigate('/403'`、不含 `meta.permissions`。
- **L18 路由表删除位留痕**（`route_table_defers_requirement_actions`）：`PermissionRouteTable.java` 不含 `\^/requirements/\\\\d+/actions` 规则，且含 `RequirementActionMatrix` 注释标记（防止「删了规则也删了注释」，AC-10）。

### 7.6 真栈自检（开发自查用，`logs/teamflow/tf-mweizygd-pd8fbm/scripts/r17-action-authz-probe.sh`）

一次性探针，覆盖门禁之外的正向证据（脚本随 run 归档，不入库）：
1. biz1 提交自己的 SUBMITTED 需求 → 200 / `APPROVING` / `wf_instance` +1（AC-1）；
2. pm 提交他人的 SUBMITTED 需求 → 200 / `APPROVING`（AC-2）；
3. biz1 对 PENDING_REVIEW 需求 `review_start` → 403；biz1 `POST /decompose` → 403；pm `review_start` → 200；dev1 `start_dev` → 200（AC-5）；
4. `source=APPROVAL` 链路：pm 审批通过后需求到 `PENDING_REVIEW`、撤回回到 `SUBMITTED`（AC-9 冒烟，完整链路由 `make platform-verify` 兜）；
5. 403 响应体断言 `detail.action` / `detail.allowedActions` 存在（AC-10）。

### 7.7 AC → 证据

| AC | 证据 |
|---|---|
| AC-1/AC-2 | 7.6 的 1/2 + `make platform-verify` |
| AC-3 | `RequirementActionMatrixTest`（8 例）+ `make platform-unit` |
| AC-4 | `make platform-test-negative` 第 16 段三条 |
| AC-5 | 7.6 的 3 + `access-verify`（路由层 `req:split` 不回归） |
| AC-6 | `RequirementActionMatrixTest#filterMatchesCan` + a5-d |
| AC-7 | `make lint` L17 + a5-d D1/D2 |
| AC-8 | 五条门禁 + 计数守卫（145→148 / 25→28 / 600 不变） |
| AC-9 | `git diff --stat` 无 `StateTables`/`RequirementStateMachine`/审批模板 + `make platform-verify` 全链路 |
| AC-10 | 负向脚本 403 body + `audit_log` 断言 + `PermissionRouteTableTest` 新例 + lint L18 |
| AC-11 | QA 阶段复跑 `docs/teamflow/DEMO-BUSINESS-FLOW.md` 卡点 1，截图入 `evidence/` |
| AC-12 | `git diff --stat` 只命中 §11 任务表列出的文件 |

## 8. 边界与坑（实现时逐条对照）

1. **vendor1 的角色码就是 PMO**（持 `req:review`）→ 只靠权限码永远拦不住「厂商代提交」。谓词里的 `vendorId == null` 是**必需项**，不是补充项。
2. **不要用 `Map.of` 装审计 detail**：`vendorId` 可为 `null`，`Map.of` 会 NPE，403 变成 500。用 `LinkedHashMap`。
3. **`recordInNewTx` 而非 `record`**：`authorize` 在 `@Transactional` 内抛异常，同事务写入会被回滚，审计就丢了。
4. **`known()==false` 不进 403 分支**：未登记动作仍是 409（PRD 明写「与现状一致」）；否则 `{"action":"bogus"}` 从 409 变 403，`PermissionRouteTableTest`/负向脚本会红。
5. **`source` 判断放在最前**：`RequirementApprovalCallback`（approve/reject/withdraw）与 `MrAdvanceService`（GITLAB）都不带用户上下文，进了矩阵等于把需求卡死在 `APPROVING`。
6. **`allowedActions` 只在一处过滤**：`RequirementService` 保留唯一实现，`RequirementPoolQuery`/`ReleaseService` 不新增分支（它们的 `allowedActions` 是各自领域的状态机提示，本轮不动）。
7. **详情页删 `can` 后必须删 import**：`web/tsconfig.json` 开着 `noUnusedLocals`，否则 `npm run build` / `vue-tsc --noEmit` 直接红。
8. **`ACTION_META.permissions` 不能删**：`RequirementPoolView.vue:213/218` 还在用；本轮只让详情页不读它。
9. **a5 的下限与总数守卫要同步改**：只加断言不改 `TOTAL < 143`，门禁仍显示旧下限；只改守卫不加断言，`PASS==TOTAL` 会红。
10. **负向脚本必须自建夹具**：不依赖种子里「刚好有一条 biz1 的 SUBMITTED」，`make platform-demo-reset` 后要能复跑。

## 9. 前端无单测运行器的取舍

本仓库前端只有 `vue-tsc` + `vite build` + `scripts/tests/lint.sh`，没有单测运行器（PRD 风险 5 已声明）。因此 AC-6/AC-7 的前端部分用**三重静态证据**替代浏览器自动化：lint L17、a5-d 三条、以及 AC-11 的人工截图。不引入 vitest/jest（AC-12：依赖零新增）。

## 10. 任务拆分（文件边界互斥 → 可并行）

> 无 PIPELINE-DISPATCHED TASKS，以下为本次下发的任务清单。T1→T3 有编译期依赖（T3 需要 T1 的类），但**文件不相交**，署名契约已由本文件 §3 钉死，可按签名并行实现，最后统一编译。

| ID | 标题 | 文件（MODIFY） | 读取 | 验收 |
|---|---|---|---|---|
| T1 | 动作矩阵契约 | `platform-contract/src/main/java/com/example/platform/contract/RequirementActionMatrix.java`（新）、`req-service/src/test/java/com/example/req/requirement/RequirementActionMatrixTest.java`（新） | `StateTables.java`、`PermissionMatrix.java`、`RequirementStateMachineTest.java` | `mvn -q -pl platform-contract,req-service -am test`；8 个用例；16 动作双向覆盖 |
| T2 | 路由表去端点级码 | `platform-common/src/main/java/com/example/platform/common/security/PermissionRouteTable.java`、`req-service/src/test/java/com/example/req/sys/PermissionRouteTableTest.java` | `PermissionInterceptor.java` | 新断言 `POST /requirements/5/actions` 为 null；既有断言不减少 |
| T3 | 服务层接线 | `req-service/src/main/java/com/example/req/requirement/RequirementService.java`、`RequirementErrors.java` | T1 的矩阵类、`TenantContext.java`、`AuditService.java` | 先鉴权后状态；6 处 `allowedActions` 带 reporter；`source!=UI` 旁路 |
| T4 | 前端单点真相 | `web/src/api/req.ts`、`web/src/views/req/RequirementDetailView.vue` | `web/src/api/client.ts`、`web/src/views/req/platform.ts`、`web/src/composables/usePermission.ts` | `cd web && npx vue-tsc --noEmit`、`npm run build` 绿；无 `meta.permissions` 引用 |
| T5 | 门禁扩展 | `scripts/tests/access/a5-capability-probe.mjs`、`scripts/access-verify.sh`、`scripts/platform-test-negative.sh`、`scripts/tests/lint.sh` | `scripts/lib.sh`、`scripts/tests/access/a{0..4}*` | access 148、negative 28、lint L1–L18 全绿 |
| T6 | 交付自检（串行，T1–T5 之后） | `logs/teamflow/tf-mweizygd-pd8fbm/scripts/r17-action-authz-probe.sh`（新） | 全部 | §7.6 五条自检；`make lint && make platform-unit && (make platform-up && make platform-seed && make platform-verify && make access-verify && make platform-test-negative && make analytics-i18n-verify)` 全绿；发现问题**当场修**（只记结论，不写 QA/ACCEPTANCE） |

T6 收尾时按 PRD §8 工程约束提交（见 §11）。

## 11. 工程约束与提交

- 分支：`feat/biz-submit-permission`（已存在）——不新建分支、不 `push`、不 `merge`、不改 `.gitignore`。
- 提交信息：`fix(req): 需求流转改为按动作矩阵授权，修复业务角色提交审批 403`。
- 提交前必须：`git status` 只含本任务表内文件 + `docs/teamflow/`；`git diff --stat` 不出现 `StateTables.java`、`RequirementStateMachine.java`、`db/migration/**`、`config/nacos/**`。
- 依赖零新增（无 `pom.xml`/`package.json` 变更）。

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 矩阵漏动作 → 有权用户突然 403 | `everyStateActionHasARow` 双向覆盖，漏一个即红 |
| 回调被拦 → 需求卡 `APPROVING` | `source=UI` 才进矩阵；AC-9 用全链路与撤回用例验证 |
| 前端删过滤后按钮消失（服务端过滤更严） | `allowedActions` 与执行同谓词，属预期收敛；AC-6 逐条量化 |
| 计数守卫与断言数漂移 | 三处计数同改同测（§7.1） |
| 回滚 | 单提交可 revert；无 DDL、无数据迁移，回滚不涉及存量数据 |
