# r20 技术方案：r19 三层授权三个阻断缺陷的最小修复

> 基线：`feat/rbac-admin` @ `a85ccd8`（r19 断点存档，编译绿）。需求源：[PRD.md](PRD.md)（12 条 AC）。
> 本文只写 r20 的**修复面**：不改 r18/r19 已交付的授权骨架（PermissionService 三序、V12 迁移、10×60 矩阵、1680 行口径）。

## 0. 一屏结论

| 缺陷 | 根因（一句话） | 修复点 |
|---|---|---|
| BUG-1 授权页读写键集不一致 | 读侧只枚举**页面绑定的 30 个** FUNCTION 码（138 行），写侧基底 `fullGrid()` 是**全 DENY**（168 行）；前端原样保存 = 把不可见的 30 个档位写成 DENY | 读侧补顶层分组 `unboundFunctions`（30 码，同一 `PermissionMatrix.functions()` 口径）；写侧基底改 `stored(tenantId, roleId)`，每角色**仍写满 168 行** |
| BUG-2 门禁套件非幂等 | `scripts/tests/rbac/_lib.sh:grants_of()` 的 jq 只拍平 `modules[].pages[]`（138 行）→ s3/s4/s7 的 `trap` 备份/还原漏掉 30 个 FUNCTION 档位，跑完把库留在非预设态 | `grants_of()` 补 `.unboundFunctions.functions`（168 行）；`rbac-verify.sh` 末尾加 `s2r` 段（套件结束断言库 = 预设） |
| BUG-3 reset 间歇失败 | **不是数据面**：[41] 的 500 是网关 `Connection reset`（deliver JVM 停顿），源于重置把本机打满 | ①重置「已是预设则 0 写」去掉 3360 行授权 churn；②网关对**幂等 GET** 加 1 次重试；③失败分类打印停止项/未执行项，退出码恒非 0 |

不可协商的三条底线（PRD anti-goals 的工程化）：**不放宽判据**（只增断言）、**不让前端「少发」绕过读侧缺口**（服务端基底必须同源，与前端发什么无关）、**不在 reset 失败时返回 0**。

## 1. 基线与范围

### 1.1 基线订正（r20 实测为准）

| 门禁 | r19 文档写的 | r20 实测基线 | 处理 |
|---|---|---|---|
| `make platform-verify` | 168 | **167** | 只订正数字，不删断言 |
| `make platform-test-negative` | 30 | **28** | 同上 |
| `make rbac-verify` | — | **101** | r20 结束为 **111**（+`s2r` 实跑 10 条、下限 8，只增） |

订正落点（PRD 批准的唯一「不可变任务夹」例外）：`docs/teamflow/20261113-r19/PRD.md` 与 `.../TECHNICAL.md` 顶部**各加一行**「基线订正（r20 实测）：167/28/306/…」；同一行口径写进 `docs/teamflow/memory.md`。**不改任何 AC 文本**。

### 1.2 改动面（AC-22）

`platform-contract`（1 处只读派生）、`req-service`（3 文件）、`api-gateway`（1 文件）、`web`（4 文件）、`scripts`（4 文件）、`docs/teamflow`（memory + r19 两行 + 本任务夹）。所有 `pom.xml`、`web/package.json` 的 diff 必须为空；不新增依赖。

## 2. 根因与证据

### 2.1 BUG-1：同一张表被两套键集推导

- 读：`RoleAdminService.grants()`（`req-service/src/main/java/com/example/req/sys/RoleAdminService.java:73-108`）只遍历 `MenuRegistry.modules()` → `of(module)` → `page.functionCodes()` + `page.actions()`，产出 **60+36+72 中的 138 行**（30 个未绑定页面的 FUNCTION 码不在任何页面里）。
- 写：`saveGrants()` 的 `before = stored(...)` 对，但 `after = fullGrid()`（`:226`）——基底是**全 DENY**，payload 里没有的 subject 就落 DENY。
- 伤害不是「显示不全」，而是**静默夺权**：`ActionRegistry` 里 `req.action.approve`→`req:review`、`deploy.action.force_release`→`deploy:execute`+`deploy:force`、`wiki.action.edit`→`WIKI_EDIT` 等动作的派生档位 = `accessForAny(action.functionCodes)`。把 30 个不可见 FUNCTION 写成 DENY，会让这些 ACTION（以及依赖它们的动作门禁）在**没有任何报错**的情况下失效；s2 的「ACTION 720 格不符 = 0：实际 30」就是它的量化形态。
- 因此读侧必须**同源**：分组里的 30 个码与页面里的功能码取自同一个 `PermissionMatrix.functions()`，写侧基底必须是库内现状（`stored()`），二者缺一不可。

### 2.2 BUG-2：备份/还原函数比读侧还窄

`_lib.sh:grants_of()`（`scripts/tests/rbac/_lib.sh:57-65`）的 jq 只拍平 `modules[].pages[]`，得到 138 行。s3/s4/s7 的 `trap cleanup EXIT` 用这份快照 `put_grants` 还原 → 服务端基底全 DENY → 30 个 FUNCTION 档位被钉在 DENY。此后任何 `s2` 必红（MENU/ACTION 由 FUNCTION 派生），这就是「s3 后跑 s2 必红」和「access-verify 全量后 s2 红」的全部机理（access-verify 本身只 login+GET，见 `scripts/access-verify.sh:77`）。

修复只需**一处**：`grants_of()` 补上 30 行，s3/s4/s7 的 trap 自动恢复完整（它们不改）。再加一道门禁级兜底：套件跑完再跑一次 s2（`s2r`），任何段漏还原都会被抓住。

### 2.3 BUG-3：[41] 的 500 是传输层

证据（同日两样本，同一形状）：

```
logs/api-gateway.log:81543  ERROR AbstractErrorWebExceptionHandler : [6aeb99c1-5024] 500 Server Error for HTTP GET "/api/deliver/test-executors"
logs/api-gateway.log:81520  WARN  r.netty.http.client.HttpClientConnect : [a7d6faf3-18, L:/192.168.1.103:60963 - R:/192.168.1.103:8083] The connection observed an error
                            java.net.SocketException: Connection reset  → NioSocketChannel.doReadBytes
logs/deliver-service.log:80666  WARN  HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=27s)   # 05:52:32.661，早于 500 约 23s
```

第二例同形（`api-gateway.log:81991` 07:06:40.260 / `deliver-service.log:80679` 07:05:49.951）；`req-service.log` 同类 starvation 每 1–2 分钟一条。静态面排除数据原因：V9 播种的两条 `test_executor` 是 `api_client_id=NULL`，`TestExecutorService.list()`（`deliver-service/src/main/java/com/example/deliver/executor/TestExecutorService.java:88-102`）走 `LEFT JOIN api_client`，`api_client` 无种子行——**清空 `api_client` 不可能让这条 SQL 报错**。

结论：**重置把宿主机打满 → deliver JVM 停顿 → 网关与它之间的连接被 reset → 网关 500**。所以修复分三层，且都不改数据语义：
1. **减少重置写入量**（3.3）：已预设则跳过 1680 行的 DELETE+INSERT。
2. **网关对幂等 GET 重试 1 次**（7.3）：连接被 reset 属于可重试的传输错误，GET 无副作用。
3. **脚本失败可诊断**（7.4）：区分「传输层」与「接口 4xx」，打印已执行/未执行项与重跑命令，退出码非 0。**不允许脚本内 sleep 重试**（PRD 待澄清默认值：不做静默重试）。

## 3. 数据模型与存储

### 3.1 表与迁移：零变更

`sys_role_permission(tenant_id, role_id, subject_type, subject_code, access)` + `uk_rp` 与 V12 迁移**不动**；本轮不新增表、列、索引、权限码。`V12__rbac_three_layer.sql` 的 1680 行口径不变。

### 3.2 键集：唯一口径（本轮把它固化成代码，不再靠两处循环各写一遍）

```
每角色 168 = FUNCTION 60（PermissionMatrix.functions()）
           + MENU     36（MenuRegistry.pages()）
           + ACTION   72（ActionRegistry.actions()）
未绑定页面的 FUNCTION = 30  → 60 − 30 = 30 个被页面绑定
```

绑定关系唯一来源：`MenuRegistry.MenuPage.functionCodes()`。r20 把它派生为**一个契约方法**（`platform-contract`，纯静态、无 DB）：

```java
// platform-contract/src/main/java/com/example/platform/contract/MenuRegistry.java
public static List<String> unboundFunctionCodes()   // 顺序 = PermissionMatrix.functions()，30 条
```

读侧 API、前端分组、验收探针全部引用它，避免「服务端一份、脚本一份、前端一份」第三次漂移。

### 3.3 重置写路径：预设即无写

现状 `DemoResetService.restoreRoleGrants()`（`req-service/src/main/java/com/example/req/seed/DemoResetService.java:153-167`）无条件 `DELETE FROM sys_role_permission WHERE tenant_id=?` 再 `RbacSeeder.seedTenant()`（`INSERT IGNORE` 1680 行）——即使库本来就是预设态，每次 reset 也要写 3360 行，这是 BUG-3 里「重置把自己打满」的主要来源之一。

改为**先判差异、无差异不写**：

```java
// req-service/src/main/java/com/example/req/seed/RbacSeeder.java（新增）
public long driftCells(long tenantId)   // 与 preset 逐格不同的格子数（缺行/多余行/档位不同都计 1）
```

`restoreRoleGrants()` 逻辑：先数 `customRoles`；`customRoles == 0 && rbacSeeder.driftCells(tenantId) == 0` → 直接返回（日志 `已是预设，跳过重写`）；否则走原路径（删自定义角色绑定 → 删全部授权行 → 删自定义角色 → `seedTenant` 重写）。**「回到预设」的判据与写入公式仍是 `RbacSeeder.preset()` 一处**，不新增第二条写路径；`counts()`/`invariants()`/`violations()` 与 `409 DEMO_RESET_INVARIANT` 整体回滚语义不变，所以 s8 的 reset 幂等断言、`platform-demo-reset.sh` 的 counts 逐键比较都不受影响。

### 3.4 不变量（dev 必守）

1. 单租户授权行恒为 `10 × 168 = 1680`（自定义角色各自的 168 行在外）；任何保存后该角色的行数**必须**是 168。
2. `MENU` 档位是**派生值**（`max(page.functionCodes)`），用户不直接写；`ACTION` 同理（`max(action.functionCodes)`）。写侧不校验这条（保持 r18 语义），由 s2 逐格断言守住。
3. `unboundFunctions` 分组**只含 FUNCTION**，不含 MENU/ACTION；为空时字段为 `{"code":"UNBOUND","name":"未绑定页面的功能码","functions":[]}`，前端隐藏该分组。
4. 无差异不写审计：`diff.total() == 0` 时不落 `ROLE_PERMISSION_UPDATE`（AC-19 既有断言，不许改）。

## 4. API 设计

无新增路由；`/admin/roles/{id}/permissions` 的 GET 响应**追加**一个字段，PUT 语义修正但请求/响应形状不变。

### 4.1 `GET /api/req/admin/roles/{id}/permissions`

权限：`role:manage` 页面 + `role.action.grant` 动作（既有）。响应 200：

```json
{
  "role": { "id": 3, "code": "QA", "name": "测试", "builtin": true, "status": "ACTIVE" },
  "epoch": 12,
  "modules": [ { "code": "需求域", "name": "需求域", "pages": [
      { "code": "req.pool", "name": "需求池", "routeName": "req-pool", "access": "ALLOW",
        "functionCodes": [ { "code": "req:view", "access": "ALLOW" } ],
        "actions": [ { "code": "req.action.approve", "name": "评审通过", "access": "RESTRICTED",
                       "highRisk": false, "functionCodes": ["req:review"] } ] } ] } ],
  "unboundFunctions": { "code": "UNBOUND", "name": "未绑定页面的功能码",
                        "functions": [ { "code": "req:review", "access": "RESTRICTED" } ] },
  "warnings": [ { "code": "PAGE_DENY_ACTION_GRANTED", "page": "req.pool", "actions": ["req.action.approve"] } ]
}
```

| 字段 | 类型 | r20 变化 |
|---|---|---|
| `role` / `epoch` | 不变 | 无 |
| `modules[].pages[].functionCodes` | `FunctionGrant[]` | 不变（仍只含绑定码） |
| `unboundFunctions` | `UnboundFunctionGroup` | **新增**，30 条，`access` 取自 `stored()`，缺行按 `DENY` |
| `warnings` | `Warning[]` | 不变 |

为什么是**顶层键**而不是「第 7 个 module」或「合成一个 page」：`modules[]` 的每个元素都被前端树、`s2`（MENU 计数）、`s1`（`nav.ts` 三向比对）当作「页面集合」消费。塞一个合成页会让 `60+37+72=169` 直接破 `role-grants-payload-gap.sh` 的 168 口径；把 30 个码挂到现有页面则是**伪造绑定语义**（页面可见性会多一条门禁，语义错误）。顶层键对旧消费者零影响。

### 4.2 `PUT /api/req/admin/roles/{id}/permissions`

```json
{ "grants": [ { "subjectType": "FUNCTION", "subjectCode": "req:review", "access": "RESTRICTED" } ],
  "confirmHighRisk": false }
```

服务端契约（r20 修正后）：

| 步骤 | 行为 |
|---|---|
| 校验 | 每个 `grant` 走 `RoleGuard.validateSubject(type, code)`（未注册码 400）与 `parseAccess`（非法档位 400） |
| 基底 | `before = stored(tenantId, roleId)`；`after = stored(tenantId, roleId)` 再按 payload 覆盖——**不再用 `fullGrid()`** |
| 写入 | `DELETE` 该角色全部行 + 按 FUNCTION→MENU→ACTION 顺序 `CHUNK=200` 批量 INSERT；写入行数恒为 168 |
| 高危 | `RoleGuard.highRiskGrants(...)` 非空且 `confirmHighRisk=false` → 409 `HIGH_RISK_REQUIRES_CONFIRM` |
| 防锁死 | 目标角色为 `PLATFORM_ADMIN` 时 `RoleGuard.assertNoLockout` → 409 `ADMIN_LOCKOUT_RISK` |
| 审计 | `diff.total() > 0` 才写 `ROLE_PERMISSION_UPDATE`（detail 含 before/after/added/removed/changed/highRiskGranted） |
| 缓存 | `permissions.invalidate(tenantId)`（本进程 epoch+1；跨进程靠 TTL 45s 传播） |
| 响应 | `GrantSaveResult{epoch, added, removed, changed, highRiskGranted, warnings, effective{menus,actions}}` |

语义放宽（有意的）：payload 里缺某个 subject 时**保留库内档位**而不是置 DENY。前端仍按契约发全量 168 行（AC-2/AC-5 的判据不变），但对「少发」的容错不再等于**越权清零**。这是 BUG-1 的纵深防御：即使将来又有客户端读不到某组码，服务端也不会替它做破坏性推断。

错误码沿用：400 `SUBJECT_UNKNOWN`/`ACCESS_INVALID`、404 `ROLE_NOT_FOUND`、409 `HIGH_RISK_REQUIRES_CONFIRM`/`ADMIN_LOCKOUT_RISK`。

### 4.3 其它接口

`POST /internal/demo-reset` 响应形状不变（`counts` 键集合不变），只是写入量可能为 0；`scripts/platform-demo-reset.sh` 的两次 counts 比较照旧成立。

## 5. 后端实现要点

### 5.1 `MenuRegistry.unboundFunctionCodes()`

```java
/** 未被任何页面绑定的功能码，顺序同 {@link PermissionMatrix#functions()}。r20 起授权页以独立分组呈现。 */
public static List<String> unboundFunctionCodes() {
    List<String> bound = PAGES.stream().flatMap(page -> page.functionCodes().stream()).toList();
    return PermissionMatrix.functions().stream()
            .filter(code -> !bound.contains(code))
            .toList();
}
```

零新 import（`List` 已在），纯静态、无 DB，`RbacRegistryTest` 直接断言 `bound 30 + unbound 30 == functions().size() == 60` 且两侧无交集。

### 5.2 `SysViews`：一个 record + 一个参数

```java
/** 「未绑定页面的功能码」分组：这些码没有任何页面承载，但它们仍会派生动作档位。 */
public record UnboundGroup(String code, String name, List<FunctionGrant> functions) { }

public record RoleGrantView(RoleRow role, long epoch, List<ModuleGrant> modules,
                            UnboundGroup unboundFunctions, List<Warning> warnings) { }
```

`RoleGrantView` 的唯一构造点是 `RoleAdminService.java:107`，改签名只需改一 Demo。前端 `types.ts` 里 `unboundFunctions` 声明为**可选**（`?: UnboundFunctionGroup | null`），老后端不带该字段时按 0 处理，避免 fixture 与旧桩一起崩。

### 5.3 `RoleAdminService.grants()`

在既有的 `Map<String, Map<String, Access>> stored = stored(tenantId, roleId)`（`:78` 附近）之后追加：

```java
List<SysViews.FunctionGrant> unbound = MenuRegistry.unboundFunctionCodes().stream()
        .map(code -> new SysViews.FunctionGrant(code, accessOf(stored, FUNCTION, code).name()))
        .toList();
SysViews.UnboundGroup unboundGroup =
        new SysViews.UnboundGroup("UNBOUND", "未绑定页面的功能码", unbound);
```

`accessOf(...)` 复用既有私有取值助手（缺行返回 `Access.DENY`，与 `fullGrid()` 的默认一致）。**不新增 SQL**：168 行本来就已随 `stored()` 一次查全。

### 5.4 `saveGrants()`：一行

```java
- Map<String, Map<String, Access>> after = fullGrid();
+ Map<String, Map<String, Access>> after = stored(tenantId, roleId);
```

`fullGrid()` 保留（给 `stored()` 打底、给单元测试），不再被写路径调用。`before` 与 `after` 现在是两份独立快照，`diff()`、审计、`invalidate()`、返回的 `effective` 全部不动。

### 5.5 边界

- **空分组**：`unboundFunctionCodes()` 为空（未来 60 码全绑定）→ `functions: []`，前端不渲染分组；服务端不特判。
- **未绑定码的用途**：它派生 ACTION（`req:review` 等），所以必须在 UI 里可编辑；但页面 MENU 派生只看 `page.functionCodes()`，**不受影响**——这是「独立分组」而非「挂到页面」的第二个理由。
- **FROZEN_CODES**：`org:manage`、`tenant:manage` 是页面绑定码，不在 unbound 集合内，前端无需为分组加冻结逻辑（仍保留既有逐格冻结判断）。
- **并发**：两个管理员同时保存 → 后写覆盖（既有语义，r18 起如此，不加乐观锁；本次不扩大范围）。
- **跨进程传播**：`invalidate` 只对本进程 +1 epoch，deliver 侧最多等 45s TTL——探针脚本必须用 `probe_settle`，不许 sleep 硬等。

## 6. 前端实现

### 6.1 类型（`web/src/api/types.ts`，`RoleGrantView` 附近 :781）

```ts
export interface UnboundFunctionGroup {
  code: string            // 'UNBOUND'
  name: string            // '未绑定页面的功能码'
  functions: FunctionGrant[]
}

export interface RoleGrantView {
  role: RoleRow
  epoch: number
  modules: ModuleGrant[]
  unboundFunctions?: UnboundFunctionGroup | null
  warnings: GrantWarning[]
}
```

`web/src/api/admin.ts` 不改（仍是 `getRoleGrants` / `saveRoleGrants` 两个函数，签名不变）。

### 6.2 状态（`web/src/views/admin/role-permissions/useRoleGrants.ts`）

| 关注点 | 现状 | r20 |
|---|---|---|
| `flatten(source)` :53-63 | 只走 `modules→pages→functionCodes/actions`（138 行，注释却写 168 → 本轮改注释+实现） | 追加 `source.unboundFunctions?.functions ?? []`；按 `draftKey` 去重（防御，正常不重复） |
| `baseline` / `draft` | `baseline = flatten(data)`，`draft = {...baseline}` | 不变（自然变成 168 行） |
| `unboundGroup` | — | `computed(() => view.value?.unboundFunctions ?? null)` |
| `unboundCount` | — | `computed(() => unboundGroup.value?.functions.length ?? 0)` |
| `unboundDirty` | — | `computed(() => unboundGroup.value?.functions.filter(f => accessOf(f.code) !== baseline[key]).length ?? 0)` |
| `payload()` :217-232 | 遍历 `draft` 全量，`{subjectType,subjectCode,access}`，`SUBJECT_ORDER` + 码升序 | 不变（draft 已有 168 键 → 自动全量；`unbound` 因 `SUBJECT_ORDER.FUNCTION=0` 排在最前） |
| `save()` :241-274 | 一次 PUT 全量；成功后 `baseline = sent`（按请求定格）、更新 `epoch/warnings`、`savedDiff/savedHighRisk` | 不变 |
| `setAccess(key, level)` | 仅当 `key in draft` 才生效 | 不变（读侧补齐后 30 个码自然可编辑——**不改这段逻辑**，这正是「修数据面而非修按钮」） |

状态机（一次保存）：

```
load()      → view=GET,  baseline=draft=flatten(view)   // 168
edit        → draft[key]=level                          // dirtyCount/unboundDirty 派生
save()      → payload()=168 行 → PUT ×1
  ├ 200     → baseline=sent, savedDiff=diff, epoch=resp.epoch, effective 更新
  ├ 409 HIGH_RISK → highRiskConfirmed=false, 弹确认后重发同一 payload
  └ 其它    → 保留 draft（不丢改动），toast 错误
```

关键约束：**保存成功后不再 GET**（t5 断言「1 PUT / 0 额外 GET」）；`baseline=sent` 而不是重新 `flatten(view)`，于是「原样保存」的 `changed` 由服务端算出 0（AC-4 判据）。

### 6.3 左树（`RoleGrantTree.vue`）

- 新增 prop `unbound: UnboundFunctionGroup | null`，在 320px 树里、模块列表**之后**渲染一个独立节点：「未绑定页面的功能码 `(30)`」，默认展开，列出 30 个功能码（`FunctionGrant` 一行一个），复用既有 `AccessSegmented` 三档。
- 选中用哨兵常量 `export const UNBOUND_SELECTION = '__UNBOUND__'`（页面码是 `module.page` 形式，永不与哨兵冲突）；`emits('update:selected', UNBOUND_SELECTION)` 与既有 `set-page/set-module` 并行不冲突。
- 搜索：把 `unbound.functions` 的码纳入命中集，命中时该节点置顶展开；`onlyGranted` 过滤下，仅当 30 个码里有非 DENY 才显示该节点（避免「全是 DENY 还占一行」）。
- tri-state 不适用（它是 FUNCTION 集合，没有 MENU 档位）：分组标题只显示 `{非 DENY 数}/{总数}`。

### 6.4 右栏（`RolePermissionsView.vue`）

`selectedPage` ref :58 / `currentPage` computed :67 不动，新增：

```ts
const unboundSelected = computed(() => selectedPage.value === UNBOUND_SELECTION)
const unboundRows = computed(() => unboundGroup.value?.functions ?? [])
```

`<RoleGrantTree>` 传 `:unbound="unboundGroup"`；右栏 `v-if="unboundSelected"` 分支渲染「功能码表」（码 + 三档 + 该码派生的动作清单 `ActionRegistry` 侧已随页面返回 → 这里只读展示派生动作数量，不新开接口）。文案固定一句话解释代价：「这些功能码没有页面承载，置 DENY 会让依赖它的动作（如发布强制放行、Wiki 编辑）一并失效」。

`dirtyBreakdown` 现有三分（`MENU|` / `ACTION|` / 其余=功能码）无需改；但页面底部 dirty 摘要增加一段 `未绑定功能码 N 处`，让「只改了看不见的格子」也有明确反馈。

### 6.5 交互边界

- 模块级开关（`setModule`）/页面级开关（`setPage`）只遍历该模块/页面的 `functionCodes`，**不会**碰到 unbound 分组（30 个码不在任何页面里）——语义正确，无需特判。
- `FROZEN_CODES` 不含 unbound 码，但保留既有检查。
- 保存失败（网络/500）：`draft` 原样保留，右侧 dirty 数不变，用户可重试；不发生「看起来保存成功但库没变」。

## 7. 门禁与重置脚本

### 7.1 `_lib.sh`：备份口径 = 读侧口径（BUG-2 的主修）

```bash
# scripts/tests/rbac/_lib.sh:57-65
grants_of() { ... jq -c '[.modules[]? | .pages[]? as $p | (...), (...), (...)],   # 138 行
                           (.unboundFunctions.functions[]? | {subjectType:"FUNCTION", subjectCode:.code, access:.access})   # +30 → 168
                        | add' ... }
```

实现上用一个 `jq` 表达式产出**单个数组**：先取三组，再 concat `unboundFunctions.functions`。这样 s3/s4/s7 的 `trap` 备份/还原、`role-grants-payload-gap.sh` 的 168 口径、`qa-r19-ui-save-loss.sh` 的快照恢复自动同源。

### 7.2 `rbac-verify.sh`：末尾 `s2r`

| 改动 | 内容 |
|---|---|
| `SEGMENTS` | `(s0 s1 s2 s3 s4 s5 s6 s7 s8 s9 s2r)`（`s2r` 在最后） |
| `segment_file s2r` | `s2-seed-equivalence.sh`（复用同一段，不新建脚本） |
| `segment_min s2r` | `8`（与 s2 同：1680/168/600/320/720/hash…） |
| 执行循环 | `RBAC_SEGMENT="$seg" bash "$file"`（导出后由 s2 的 `report_counts` 采用） |
| `s2-seed-equivalence.sh:33-35` | `printf '%s %s %s\n' "${RBAC_SEGMENT:-s2}" "$PASS" "$TOTAL"` |
| 总数下限 | 仍 `TOTAL >= 60`；实际 **101 → 111**（`s2r` 实跑 10 条、下限 8），每段下限不变（断言只增不减） |

效果：`bash scripts/rbac-verify.sh` 全跑完，`s2r` 用同一套 1680/168/逐格/hash 断言证明「套件结束时库 = 预设」（AC-6/AC-8）。单独跑 `s2`/`s3` 的行为不变（`s2r` 只在全量或显式指定时执行）。

### 7.3 网关：仅对幂等 GET 重试一次（BUG-3 传输层）

`api-gateway/src/main/java/com/example/gateway/PlatformRoutes.java:28-41`，四条路由的 filters 改为：

```java
private static GatewayFilterSpec idempotent(GatewayFilterSpec f) {
    return f.retry(c -> c.setRetries(1)
            .setMethods(HttpMethod.GET)
            .setExceptions(java.io.IOException.class)
            .setBackoff(Duration.ofMillis(200), Duration.ofSeconds(1), 2, false));
}
```

已用 `javap` 核对 5.0.4 的 `GatewayFilterSpec.retry(Consumer<RetryConfig>)` 与 `RetryConfig.setRetries/setMethods/setExceptions/setBackoff(Duration,Duration,int,boolean)` 均存在。**只 GET、只 IOException、只 1 次**：不重试 POST/PUT/DELETE（重复提交风险），不重试 4xx/5xx 业务响应（避免把确定性失败拖成慢失败）。`spring-cloud-gateway` 的 Retry 过滤器对已开始写响应的响应不会二次发送，符合预期。

### 7.4 `platform-demo-reset.sh`：失败可诊断、可重入（AC-10）

现状：`reset_once()` 非 200 直接 `err + exit 1`，[41] 只判 `.total == 2`，失败时看不出「哪一步断了、库停在哪」。改为：

1. **失败分类**：`HTTP_CODE=000/502/503/504` 或 body 含 `Connection reset` → `case=transport`（提示「网关/服务连接被重置，先看 `logs/api-gateway.log` 的 `HttpClientConnect` 与 `logs/deliver-service.log` 的 `Thread starvation`」）；`4xx` → `case=api`（打服务端 message）；其它 → `case=assert`。
2. **停止项/未执行项**：维护 `STEPS=()` 累积已执行检查名与结果，失败时打印「已通过 N 项 / 未执行 M 项（清单）」，并在末尾固定打印 `重跑：bash scripts/platform-demo-reset.sh`（reset 幂等，可直接重跑）。
3. **退出码**：任何失败恒 `exit 1`（保持非 0），不吞错、不重试、不用 `sleep` 掩盖。
4. **仍保留**：两次 reset 的 `counts` 逐键相等（幂等证据）、[41] `.total == 2`（执行器基线）、末尾第三次 reset（幂等收尾）、成功行 `${CASE}/${CASE}` 的 57 项计数（断言只增）。

### 7.5 证据（AC-12）

`docs/teamflow/20261114-r20-r19-rbac-blocker-fix/evidence/` 下 ≥4 张 1440×900 PNG：`01-unbound-group.png`（页尾分组 + 30 计数）、`02-save-noop.png`（原样保存后 toast/详情显示 `changed=0`）、`03-reset-x3.png`（连续 3 次 reset 57/57）、`04-s2-after-access.png`（access-verify 后 `s2` 复绿）。浏览器不可用时按 PRD 回退：改用 SSR + 静态断言，并在 ACCEPTANCE 注明替代方式。

## 8. 测试策略

### 8.1 单元与契约（`mvn -o ... test`）

| 文件 | 新增断言 |
|---|---|
| `req-service/src/test/java/com/example/req/rbac/RbacRegistryTest.java` | `unboundFunctionCodes().size()==30`；`bound ∪ unbound == PermissionMatrix.functions()`（60）；两侧无交集；顺序稳定（与 `functions()` 同序） |
| `req-service/src/test/java/com/example/req/rbac/RbacSeederTest.java` | `driftCells` 的纯函数部分（`preset` 期望集合）与 168 行形状不变 |
| 既有 | `PermissionMatrixGridTest` 600 条**不动**；`RbacSeedEquivalenceTest`、`PermissionUnionTest`、`PermissionServiceTest` 不回归 |

`RoleAdminService` 依赖 `SysDb`，本轮**不加需要数据库的单测**（会拖慢 306 基线）；读写同源由 `qa-r19-ui-save-loss.sh`（真栈）覆盖。

### 8.2 门禁与断言数

| 命令 | 判据 |
|---|---|
| `bash scripts/rbac-verify.sh` | 111/111；`s2r` 实跑 10 条（下限 8）；每段 ≥ 既有下限 |
| `bash scripts/rbac-verify.sh s2` / `s3` / `s8` | 分段可独立复跑（幂等） |
| `bash scripts/access-verify.sh` | 152/152，之后立刻 `s2` 仍绿（AC-7 跨套件） |
| `bash scripts/platform-demo-reset.sh` ×3 | 每次 exit 0、57/57（AC-9） |
| `bash scripts/platform-verify.sh` | 167（订正后基线） |
| `bash scripts/platform-test-negative.sh` | 28 |
| `bash scripts/platform-unit.sh` | 306 |
| `bash scripts/lint.sh` / `verify.sh` / `analytics-i18n-verify.sh` / `collab-verify.sh` | 22 / 14 / 74 / 263 |
| `cd web && npm run typecheck && npm run build` | 0 错 |

### 8.3 端到端与失败复现

| 探针（本轮共享区 `logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/`，从 r19 归档复制后**只做计数口径扩展、阈值不变**） | 判据 |
|---|---|
| `qa-r19-ui-save-loss.sh` | exit 0：`changed=0`、`nonDENY→DENY=0`、前后快照逐字节相等 |
| `role-grants-payload-gap.sh` | 缺口 0（fn+menu+act = 168） |
| `qa-r19-unbound-fn-exposure.sh` | 缺口 0（60 个 FUNCTION 码全覆盖） |
| `qa-r19-suite-idempotency.sh` | exit 0（reset→s2→s3→s2→reset→s2 两次 s2 均 0） |
| `t5-grants-check.mjs` | 57/57（fixture 补 `unboundFunctions` 30 条，draft/payload 各 168） |
| `t4-roles-page-contract.mjs` | 34/34 |
| 新增 `qa-r20-gateway-retry.sh` | 静态：四条路由 filters 含 `retry`，且 `setMethods` 仅 `GET`、异常仅 `IOException`；不存在对 POST 的重试 |

## 9. 任务拆分（按文件边界；files = 改写，reads = 只读）

并行档：**A**（后端数据面）、**B**（前端契约）、**C**（门禁幂等）、**D**（重置写放大）、**E**（网关+脚本诊断）五组 files 互不相交，可同时开工；B2 依赖 B1 的 `unboundGroup/unboundCount` 名字（已在 §6.2 定死，可并行读契约）；F 组收口必须最后。

> **T-A 后端读写同源**（P0，AC-2/AC-3/AC-1）
> files: `platform-contract/src/main/java/com/example/platform/contract/MenuRegistry.java`、`req-service/src/main/java/com/example/req/sys/SysViews.java`、`req-service/src/main/java/com/example/req/sys/RoleAdminService.java`、`req-service/src/test/java/com/example/req/rbac/RbacRegistryTest.java`
> reads: `platform-contract/.../PermissionMatrix.java`、`platform-contract/.../ActionRegistry.java`、`req-service/src/main/java/com/example/req/sys/RoleGuard.java`
> spec: 加 `MenuRegistry.unboundFunctionCodes()`；`SysViews` 加 `UnboundGroup` 并扩 `RoleGrantView` 第 4 参数；`grants()` 组装 30 条；`saveGrants()` 基底 `fullGrid()`→`stored()`；`RbacRegistryTest` 补 4 条断言。验收：`mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` 绿、`RbacRegistryTest` 绿、GET 返回 138+30。

> **T-B1 前端契约与状态**（P0，AC-5）
> files: `web/src/api/types.ts`、`web/src/views/admin/role-permissions/useRoleGrants.ts`
> reads: `web/src/api/admin.ts`
> spec: 加 `UnboundFunctionGroup`；`RoleGrantView.unboundFunctions?`；`flatten()` 纳入 30 条并修正 :15/:72 的错误注释；导出 `unboundGroup`/`unboundCount`/`unboundDirty`。验收：`npm run typecheck` 0 错；`draft`/`payload` 各 168 键。

> **T-B2 前端视图**（P0，AC-5/AC-12 截图①）
> files: `web/src/views/admin/role-permissions/RoleGrantTree.vue`、`web/src/views/admin/RolePermissionsView.vue`
> reads: `web/src/views/admin/role-permissions/useRoleGrants.ts`、`web/src/views/admin/role-permissions/AccessSegmented.vue`、`web/src/views/admin/admin-shared.css`
> spec: 树尾「未绑定页面的功能码 (30)」节点（`UNBOUND_SELECTION` 哨兵、默认展开、搜索/onlyGranted 兼容）；右栏功能码表 + 一句代价说明；dirty 摘要加「未绑定功能码 N 处」；按 UI-STYLE 复用既有三档控件。验收：typecheck/build 0 错；30 码可见可编辑；模块/页面级开关不误伤。

> **T-C 门禁幂等**（P0，AC-6/AC-7/AC-8）
> files: `scripts/tests/rbac/_lib.sh`、`scripts/tests/rbac/s2-seed-equivalence.sh`、`scripts/rbac-verify.sh`
> reads: `scripts/tests/rbac/s3-decision-order.sh`、`s4-cache-epoch.sh`、`s7-negative-nine.sh`、`s8-audit-reset.sh`
> spec: `grants_of()` 168 行；s2 `report_counts` 读 `RBAC_SEGMENT`；`rbac-verify.sh` 加 `s2r`（min 8、file 复用 s2、导出 `RBAC_SEGMENT`）。验收：`rbac-verify` 111/111（`s2r` 实跑 10 条、下限 8）；`bash scripts/rbac-verify.sh s3 && bash scripts/rbac-verify.sh s2` 两次都绿。

> **T-D 重置写放大**（P1，AC-9）
> files: `req-service/src/main/java/com/example/req/seed/RbacSeeder.java`、`req-service/src/main/java/com/example/req/seed/DemoResetService.java`
> reads: `req-service/src/test/java/com/example/req/rbac/RbacSeederTest.java`
> spec: `driftCells(tenantId)`（与 `preset` 逐格比对，含缺行/多余行）；`restoreRoleGrants()` 预设态直接返回并记日志。验收：连续两次 `POST /internal/demo-reset` counts 逐键相等、第二次日志出现「跳过重写」、s8 绿。

> **T-E 网关重试 + 重置脚本诊断**（P1，AC-9/AC-10）
> files: `api-gateway/src/main/java/com/example/gateway/PlatformRoutes.java`、`scripts/platform-demo-reset.sh`
> reads: `scripts/lib.sh`、`logs/api-gateway.log`（只读证据，不入库）
> spec: 四条路由接入 `idempotent()`（仅 GET + IOException + 1 次 + backoff 200ms→1s）；脚本加失败分类、已执行/未执行清单、重跑提示，退出码恒非 0。验收：编译绿；连续 3 次 reset 57/57；人为断栈时输出 `case=transport` 且 exit 1。

> **T-F 判据脚本固化与证据**（P0，AC-4/AC-5/AC-12）
> files: `logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/qa-r19-ui-save-loss.sh`、`.../role-grants-payload-gap.sh`、`.../qa-r19-unbound-fn-exposure.sh`、`.../qa-r19-suite-idempotency.sh`、`.../t5-grants-check.mjs`、`.../t4-roles-page-contract.mjs`、`.../qa-r20-gateway-retry.sh`、`docs/teamflow/20261114-r20-r19-rbac-blocker-fix/evidence/*.png`
> reads: `$DSH_HOME/teamflow/ws-project-1-17fd2735/logs/tf-mwencgru-xkthj3/scripts/*`（r19 归档，只读）
> spec: 从 r19 归档**复制**到本轮共享区（不重建、不改阈值），仅扩展计数口径（gap 探针把 `unboundFunctions.functions` 计入 FUNCTION 集合、menu 计数仍按 36 页）；新增网关重试静态断言脚本；跑出 4 张 1440×900 证据图。验收：六个脚本 exit 0 / t5 57/57 / t4 34/34。

> **T-G 基线订正与提交**（P0，AC-11/AC-12）
> files: `docs/teamflow/memory.md`、`docs/teamflow/20261113-r19/PRD.md`、`docs/teamflow/20261113-r19/TECHNICAL.md`
> reads: 本文件 §1.1
> spec: memory 加一行真实基线；r19 PRD/TECHNICAL 各加一行「基线订正（r20 实测）」且**不动 AC 文本**。git 动作：**留在 `feat/rbac-admin`**（不切分支、不建分支、不 amend/rebase），一次性提交，message 含 `runId=tf-mwfso8sv-t4ipv0` 与任务夹路径，例如
> `fix(rbac): r20 修 r19 三个阻断缺陷（读写同源/s2r 幂等/reset 确定性）` + body `runId=tf-mwfso8sv-t4ipv0` / `task: docs/teamflow/20261114-r20-r19-rbac-blocker-fix/`；提交前 `git diff --stat` 核对 `pom.xml`、`*/pom.xml`、`web/package.json` 为空。
> 验收：`git show --stat HEAD` 只含上述文件；工作区干净（`evidence/` 与任务夹文档已入库）。

> **T-H 交付自检**（收口，非 QA 复跑）
> files: 无（发现问题就地修，改动归属对应任务）
> spec: 在真栈上跑一遍 §8.2 的最小集（compile、typecheck、`rbac-verify`、`access-verify`→`s2`、reset×3），确认 AC-1/6/7/9/11 的自检结论；**只记录结论，不写 QA-REPORT/ACCEPTANCE，不跑跨浏览器/全矩阵**。任何发现立即修并复跑。

## 10. 风险、回退与 AC 映射

### 10.1 风险

| 风险 | 影响 | 处置 |
|---|---|---|
| GET 响应新增字段被旧消费者误读 | t4/a3 断言可能按「modules 页数」计数 | 顶层键，`modules` 条目数不变（仍 36 页）；t4 34 条只增不改 |
| `after = stored()` 让「payload 少发」变成「保留原值」 | 理论上弱化了一个隐式保护 | 前端仍发全量（t5 断言 168）；服务端行数恒 168（s2 守住）；这是有意的纵深防御 |
| `s2r` 让总量 101→111 | AC-7 字面写「仍 101」 | 按 AC-8「断言只增」与 AC-11「不缩水」解释为下限；TECHNICAL 已声明，QA 以 111 验收（`s2r` 实跑 10 条、下限 8） |
| `driftCells` 逐格比对带来一次 1680 行读 | reset 多一次（小）读 | 相比省掉的 3360 行写可忽略；只在 reset 内调用 |
| 网关重试放大下游压力 | 停顿期间 2 次请求 | 仅 GET、仅 1 次、仅 IOException；不重试业务 5xx |
| 浏览器不可用 | 截图缺失 | 按 PRD 回退：SSR + 静态断言，并在 ACCEPTANCE 注明 |

### 10.2 回退

三个修复彼此正交，可单独回退：撤 `RoleGrantTree/RolePermissionsView` 分组（前端仍能显示 138 行，但写侧已安全）、撤 `s2r`（`rbac-verify` 回到 101）、撤网关 `idempotent()`（reset 仍有 driftCells 减负）。**任何情况下不回退 `saveGrants` 的基底修正**——那是把「静默夺权」改回「静默夺权」。

### 10.3 AC → 证据

| AC | 证据 |
|---|---|
| AC-1 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` exit 0 |
| AC-2 | 保存后该角色 `SELECT COUNT(*) ... WHERE role_id=?` = 168；`qa-r19-ui-save-loss.sh` 前后快照相等 |
| AC-3 | GET 的 `unboundFunctions.functions` 长度 30；`role-grants-payload-gap.sh` 缺口 0；`qa-r19-unbound-fn-exposure.sh` 缺口 0 |
| AC-4 | `qa-r19-ui-save-loss.sh` exit 0（changed=0 / nonDENY→DENY=0 / 逐字节相等） |
| AC-5 | typecheck 0；`t5-grants-check.mjs` 57/57；`t4-roles-page-contract.mjs` 34/34；截图① |
| AC-6 | `qa-r19-suite-idempotency.sh` exit 0 |
| AC-7 | `access-verify` 152/152 后直接 `rbac-verify s2` 绿；全量 111/111 |
| AC-8 | `s2r` 8 条（CANONICAL_HASH 两侧相等）；段下限表未下调 |
| AC-9 | reset ×3 每次 exit 0、57/57；`api-gateway` 重试静态断言；截图③ |
| AC-10 | 人为断栈：`case=transport`、exit 1、打印未执行清单、可直接重跑 |
| AC-11 | §8.2 全表（167/152/28/22/306/14/74/263/111 + Grid 600） |
| AC-12 | `evidence/` 4 张 PNG；`git show` 含 `runId=tf-mwfso8sv-t4ipv0` 与任务夹路径 |
