# 技术方案 · r25 演示层写按钮收敛 + 厂商视角夹具

- 基线依赖：`docs/teamflow/20261116-r24-vendor-pm-role/` + `docs/teamflow/20261115-r22-*/`
- 取代：`20261116-r24-vendor-pm-role#AC-8`（**仅**演示数据预期翻转：VEN-A 承接链进入 DEMO-REL-1；授权语义一字不动）
- 对应 PRD：`docs/teamflow/20261116-r25-demo-perm-vendor-seed/PRD.md`；设计：同夹 `DESIGN.md`
- 结论先行：**服务端零改动**（无 DDL、无 Controller、无 PermissionMatrix/RbacSeeder/ScopeSql）；改动集中在前端 4 个文件、夹具 2 个 Java 文件、2 个门禁脚本、1 个新检查器。三条任务**串行**执行（PRD §9）。

---

## 0. 与 DESIGN 的 4 处实测修正（施工以本节为准，其余按 DESIGN）

| # | DESIGN 原文 | 实测 | 本方案 |
|---|---|---|---|
| D-1 | §4.4 台账 3：`listRequirements` 需补 `opts` | 调用点只有 `TestStrategiesView.vue:165`、`ReleaseDetailView.vue:300`、`RequirementPoolView.vue:86/150`——**没有弹窗探测点**；补 opts 等于顺手改需求池主体 403 语义 | **不改** `listRequirements` |
| D-2 | §4.4 台账 4：`listTestModules` 需补 `silentForbidden` | `web/src` 全仓**零调用点**（仅 `api/deliver.ts:217-226` 声明）；用例弹窗的模块字段是 `el-input-number`（`TestCaseLibraryView.vue:756`） | **不改**；用例弹窗无探测调用 |
| D-3 | §6「chip 是 `tabindex="0"` 可聚焦，作为键盘原因通道」 | `ScopeChip.vue` 只在 `clickable === true` 时挂 `tabindex`；默认不可聚焦 | 不改共享组件；chip **只做视觉+鼠标**，键盘可达性登记 memory 待办（不写断言） |
| D-4 | §4.6「守卫函数首行 `if (!canReport.value) return`」 | 空态在筛选态下动作是「清空筛选」，首行守卫会把无权限用户的清空筛选也挡死 | 守卫**紧邻 `router.push` 之前**（清空筛选分支在前）；a6 断言「守卫先于字面量」，不要求物理首行 |

唯一实补的探测标记：`searchRequirements`（缺陷弹窗，`DefectListView.vue:164`）。AC-3 的其余点位均已合规或不存在，按「零改动 + 证据」交付，并在 a6 里以「豁免台账」断言固化（`D5`）。

---

## 1. 数据模型与存储

### 1.1 结论：无 schema 变更

- 不新增表/列/索引/迁移（无 V14）。`test_case.vendor_id` 列**已存在**（`deliver-service/src/main/java/com/example/deliver/testcase/TestCaseEntity.java:70`，`@Column(name = "vendor_id")`），本轮只是让夹具按线上口径**写入**它。
- `release_requirement` 表与 `requirement.release_id` 均已存在，VEN-A 承接链走**既有同源路径**，不做 UPDATE 造数。

### 1.2 夹具数据流（改动前后）

```
DemoFixture.REQUIREMENTS[row]  ── spec[15] inRelease ─┐
   (R-01..08 "1"; 09/10/11 由 "0" 改 "1"; 12 保持 "0") │
                                                      ▼
DemoResetService.insertFixture()
   ├─ 4) requirement INSERT：release_id = inRelease ? releaseId : null
   │      + 若 inRelease → INSERT release_requirement(tenant_id, release_id, requirement_id, added_by)
   │      + 新增：requirementVendors.put(code, row[R_VENDOR_ID])   // 同一次遍历，不查库
   └─ 5) test_case INSERT：新增 vendor_id 列，值 = requirementVendors.get(requirementCode)
          （内部需求为 null；R-10/R-11 → VEN-A 的 id；R-12 → VEN-C 的 id）
```

### 1.3 不变量（重置后必须同时成立）

| ID | 不变量 | 校验位置 |
|---|---|---|
| I1 | `requirement` 12 行、`release` 1 行、`test_case` 12 行 | demo-reset `check_count`（既有 C1 计数键） |
| I2 | `release_requirement` **11** 行（内部 01–08 + 09/10/11），R-12 不挂 | demo-reset 新增查库断言或 API 详情断言 |
| I3 | VEN-A 名下用例 **3** 行（C-09/C-10→R-10，C-11→R-11），且 `vendor_id = requirement.vendor_id` | demo-reset API 断言 + 服务端不变量 |
| I4 | admin/pm：版本 1、需求 12、用例 12（**不减少**） | demo-reset 新增 API 断言 |
| I5 | 隔离：vpm1 需求 3、vendor2 0、po1 8；vpm1 版本 ≥1、vendor1 版本 ≥1 | demo-reset 既有 + 新增 |
| I6 | 连跑两次结果逐值相同（幂等），第二次不得变空 | demo-reset C1/C2 比对（既有结构） |

### 1.4 预期变化点

- `DEMO-REL-1` 的需求数 **8 → 11**（09/10/11 进入）。凡是以演示夹具为数据源的「版本详情需求数」预期值，只改数字，不删断言（AC-9）。
- `platform-verify.sh` 的 C4（996–1007 行）读的是 `SeedService` 的 `R2026-11`，与 `DEMO-REL-1` **不是同一数据集**，本轮不动 SeedService → 该断言语义不变（AC-9）；注意门禁顺序：`platform-demo-reset` 会清租户业务表，跑完需 `platform-seed` 才能跑 `platform-verify`。

---

## 2. 接口设计

### 2.1 无新增/变更端点

| 端点 | 用途 | 变化 |
|---|---|---|
| `GET /api/req/requirements?page&size` | 需求池列表（分页 `{items,total,page,size}`） | 无 |
| `GET /api/req/requirements/search?q=` | 缺陷弹窗选需求（`RequirementController.java:82`，与列表同 PoolQuery 口径，vpm1 可读） | 无（前端加 `silentForbidden`） |
| `GET /api/req/releases?page&size` | 版本计划（vpm1 走 ScopeSql 派生 EXISTS，改后 ≥1） | 数据面变化，契约不变 |
| `GET /api/deliver/testcases?page&size` | 用例库（vendor 范围按 `test_case.vendor_id`） | 数据面变化，契约不变 |
| `POST /api/req/requirements`、`POST /api/req/releases`、`POST /api/deliver/testcases`、`POST /api/deliver/defects` | 写端点 | **不变**，vpm1 仍 403（`@RequiresAction`，AC-10） |

### 2.2 403 三层语义（不得混淆）

1. **页面入口**：路由守卫 `web/src/router/index.ts:102-136`（`requiredCodes` + `auth.canAny`）→ 深链 `/requirements/new` 对 vpm1 仍跳 `/403`（AC-3 保留）。
2. **写动作**：服务端 `@RequiresAction` 403，前端**不请求**（按钮 disabled + handler 兜底），客户端**禁止**把 403 导航到全局页。
3. **探测调用**：`silentForbidden: true` → `api/client.ts:178-195` 跳过 `navigate('/403')`，就地降级。

### 2.3 前端 API 签名

```ts
// web/src/api/req.ts（签名不变，仅内部加标记；唯一调用点：DefectListView.vue:164）
export function searchRequirements(q: string): Promise<RequirementRow[]>   // 内部 http.get(url, { q }, { silentForbidden: true })
```

`listRequirements` / `listTestModules` / `listReleases` **不改**（D-1/D-2 证据 + 豁免理由）。

---

## 3. 前端组件与页面拆分

统一形态 R1：**始终渲染 + `:disabled` + `:title` 原因 + handler 兜底**；`type` 随可用性 `primary → default`；禁用态颜色由 `theme.css:53-55/82` 提供（`opacity:1`、`cursor:not-allowed`），**严禁给 `.el-button.is-disabled` 加 `pointer-events:none`**（会杀死 title 悬浮，A6 直接红）。

### 3.1 `web/src/views/req/RequirementPoolView.vue`

改动点：`<script setup>` 顶部（第 41 行 `const { canAny } = usePermission()` 处）+ 页头 326–335 + 空态 446–458。

```ts
const { can, canAny } = usePermission()
const reportCodes = ['req:report:bus', 'req:report:dev'] as const
const canReport = computed(() => can('req:report:bus') || can('req:report:dev'))
const reportDenyTip = computed(() => (canReport.value ? '' : `需要权限：${reportCodes.join(' / ')}`))
const deniedWriteChip = computed(() => (canReport.value ? '' : '1 个操作需要权限'))

function goCreate(): void {                 // 页头路径
  if (!canReport.value) return
  router.push('/requirements/new')          // 字面量 #1（必须保留）
}
function goCreateFromEmpty(): void {        // 空态路径
  if (isFiltered.value) { reset(); return } // 清空筛选：无权限也要能用（D-4）
  if (!canReport.value) return
  router.push('/requirements/new')          // 字面量 #2（必须保留）
}
```

模板（页头，`ScopeChip` 之后）：

```vue
<ScopeChip v-if="deniedWriteChip" :label="deniedWriteChip" :scope="reportCodes.join(' / ')" tone="warning" />
<el-button :type="canReport ? 'primary' : 'default'" :disabled="!canReport"
           :title="reportDenyTip" @click="goCreate">提报需求</el-button>
```

空态（`EmptyState.vue` 已有 `#action` 具名插槽，组件不改）：

```vue
<EmptyState :action-text="isFiltered ? '清空筛选' : '提报需求'">
  <template #action>
    <el-button :type="canReport ? 'primary' : 'default'" :disabled="!canReport"
               :title="reportDenyTip" @click="goCreateFromEmpty">
      {{ isFiltered ? '清空筛选' : '提报需求' }}
    </el-button>
  </template>
</EmptyState>
```

硬约束（DESIGN §4.6）：模板内**不得**出现内联 `router.push('/requirements/new')`；两处字面量各在一个具名函数里；`a6` 断言字面量计数 `=== 2`。单点收敛会打破 AC-1 计数，需 PM 同步改 AC 才允许。

### 3.2 `web/src/views/deliver/DefectListView.vue`

```ts
const { canAction } = usePermission()   // 如未引入则补
const canCreateDefect = computed(() => canAction('defect.action.create'))
const createDenyTip = computed(() => (canCreateDefect.value ? '' : '需要权限：defect.action.create'))
const deniedWriteChip = computed(() => (canCreateDefect.value ? '' : '1 个操作需要权限'))
function openCreate(): void {
  if (!canCreateDefect.value) return     // 首行兜底
  …既有重置表单 + 打开弹窗…
}
```

- 页头 chip 放在 `.dl-head` 动作区**之前**（293–315）。
- 按钮 313 行：`:type="canCreateDefect ? 'primary' : 'default'"`、`:disabled="!canCreateDefect"`、`:title="createDenyTip"`。
- 空态 422–423：改用 `#action` 插槽渲染同一按钮（文案保持 `isFiltered ? '重置筛选' : '新建缺陷'`，重置筛选分支不受权限限制）。

### 3.3 `web/src/views/deliver/IterationsView.vue`

```ts
const canCreateSprint = computed(() => canAction('sprint.action.create'))
const canCreateIteration = computed(() => canCreateSprint.value && !!releaseId.value)
const createDenyTip = computed(() => (!releaseId.value
  ? '请先选择版本'                                   // 前置条件优先（DESIGN §4.2）
  : (canCreateSprint.value ? '' : '需要权限：sprint.action.create')))
const deniedWriteChip = computed(() => (canCreateSprint.value ? '' : '1 个操作需要权限'))
function openCreate(): void {
  if (!canCreateIteration.value) return
  …既有逻辑…
}
```

- 按钮 189 行改为 `:type="canCreateIteration ? 'primary' : 'default'" :disabled="!canCreateIteration" :title="createDenyTip"`（保留原有 `:disabled="!releaseId"` 语义，合并为一个条件）。
- chip 放动作区之前；空态（224–227）同 3.2 用 `#action` 插槽。
- 该页无 `ScopeChip` import → 新增 import（组件不改）。

### 3.4 已合规、**零改动**的 4 个入口（a6 只断言不修改）

| 页面 | 行 | 现状 |
|---|---|---|
| `ReleaseListView.vue` 新建版本 | 139 | `canAction('release.action.create')` + disabled + title + type 降级 |
| `ReleaseDetailView.vue` 提交审批 | 481 | `release.action.approve` 同上 |
| `TestCaseLibraryView.vue` 新建用例 | 484 | `test.action.case_edit` 同上 |
| `TestPlansView.vue` 新建计划 | 341 | `test.action.plan_create` 同上 |

已登记不修（改动面外）：`TestCaseLibraryView.vue:550-556` 空态在 `!canEdit` 时隐藏动作；`ReleaseListView.vue:162` 空态无创建 CTA → 写 memory 待办。

---

## 4. 状态管理

- **无新 store、无新全局状态**。权限一律从 `stores/auth.ts` 派生（`can` / `canAny` / `canAction`），页面内 3 行 `computed`；不引入新 composable 契约（`denyActionReason` 文案是「你没有「X」的操作权限」，缺 `需要权限：` 前缀，不满足 a6 观察标准，故不复用）。
- 页面不缓存权限快照：`permissionsLoaded` 变化后 computed 自动重算（与 r24 一致）。
- 演示数据状态由服务端重置接口驱动，前端不做本地镜像。

---

## 5. 关键实现点与边界

1. **disabled 的 title 必须真的能出**：Element Plus 不给 `.el-button.is-disabled` 设 `pointer-events:none`；若有人为了「不响应 hover」加上它，A6/a6 必红，代码评审直接拒。
2. **handler 兜底不是冗余**：disabled 只挡鼠标；键盘 Enter/程序化 `click()`、以及未来把 `disabled` 误删时，首行 `return` 是最后一道闸（a6 静态断言）。
3. **两条 `/requirements/new` 字面量**：`a6` 用 `blankComments` 后正则计数；模板内出现即红。
4. **chip 只做鼠标通道**（D-3）：不设 `clickable`，因此没有 `tabindex`；不改 `ScopeChip.vue`（共享组件，AC-12 改动面外）。无障碍缺口写进 memory 待办。
5. **`≤1280` 隐藏 scope 明细**：沿用 DESIGN §0，chip 的 `:scope` 在窄屏由 `ScopeChip` 自身的 CSS 省略号处理，不新增断点逻辑。
6. **前置条件优先于权限**：迭代页未选版本时 title 是「请先选择版本」——a6 断言该字符串出现在 `createDenyTip` 且早于权限分支。
7. **空态筛选分支**：`isFiltered` 时动作是重置/清空，不受写权限限制（D-4），a6 断言守卫在 push 之前而非函数首行。
8. **夹具厂商派生用内存映射**：`DemoResetService` 在同一次需求遍历里 `requirementVendors.put(code, row[R_VENDOR_ID])`，用例插入直接取值；**不允许**第二条 SQL 回查、**不允许** UPDATE 补数（A3）。
9. **null 绑定**：内部需求 `R_VENDOR_ID` 为 `null`，`test_case.vendor_id` 可空；`db.update` 传 `Long` 直传即可（Spring 以 `setNull(Types.NULL)` 绑定，MySQL 接受）；若驱动报类型错，改用显式 `new Object[]{..., vendorId, Types.BIGINT}` 形式，**不得**用 `0` 占位（会变成「厂商 0」脏数据）。
10. **幂等**：重置仍是「先按 `DELETE_ORDER` 删、再插」；第二次运行不依赖第一次残留，I6 由 C1/C2 比对守卫。
11. **a6 在栈未起时**：照 `a5-capability-probe.mjs` 惯例，**不写** `ACCESS_COUNTS` 计数行并 `exit 1`，避免「空扫假绿」；`access-verify.sh` 聚合时该段缺失即整体失败。
12. **运行时探针路径**：`POST /api/req/requirements`、`POST /api/req/releases`、`POST /api/deliver/testcases`、`POST /api/deliver/defects`，空 JSON body 即可（鉴权先于参数校验），断言 `403`。

---

## 6. 测试策略

### 6.1 新增 `scripts/tests/access/a6-write-button-gate.mjs`（≥24，实作 25）

四组断言，全部输出 `{id, ok, detail}`；源码审计前必须先 `blankComments()`（注释里的字样不算数）。

**A. 静态收敛（14）**

| id | 断言 |
|---|---|
| W1 | 需求池页头按钮 `:disabled="!canReport"` |
| W2 | 页头 `:title="reportDenyTip"`，且 `reportDenyTip` 含 `需要权限：` 与 `req:report:bus` / `req:report:dev` |
| W3 | 页头 `@click="goCreate"` |
| W4 | `goCreate` 内 `if (!canReport.value) return` 出现在 `router.push` 之前 |
| W5 | 空态走 `#action` 插槽且按钮同样 disabled/title |
| W6 | `router.push('/requirements/new')` 字面量计数 === 2 |
| W7 | `goCreateFromEmpty` 内守卫先于 push（清空筛选分支可在前） |
| W8 | 模板区无内联 `router.push('/requirements/new')` |
| W9 | 版本列表新建按钮绑定 `release.action.create` |
| W10 | 版本详情提交审批绑定 `release.action.approve` |
| W11 | 用例库新建绑定 `test.action.case_edit` |
| W12 | 测试计划新建绑定 `test.action.plan_create` |
| W13 | 缺陷页 disabled/title 绑定 `canCreateDefect`，且 `openCreate` 首行守卫 |
| W14 | 迭代页 `:disabled="!canCreateIteration"`、title 含 `请先选择版本`，`openCreate` 有守卫 |

**B. 403 降级（5）**

| id | 断言 |
|---|---|
| D1 | `api/dictionaries.ts` 三个字典函数均带 `silentForbidden: true` |
| D2 | `searchRequirements` 带 `silentForbidden: true` |
| D3 | `api/client.ts` 的 `/403` 导航被 `!options.silentForbidden` 包住 |
| D4 | 三个视图内不出现 `navigate('/403'` / `router.push('/403'` |
| D5 | `searchRequirements` 全仓调用点 === 1（改动面受限的机器证据） |

**C. 运行时（4）**：vpm1 登录后对 4 个写端点发 `POST`（空 body）全部 `403`；登录失败按传输层失败处理，不写计数行。

**D. 自检（2）**：合规内存夹具 → 全绿；把 `:disabled="!canReport"` 抽掉 → W1 变红（证明断言不是永真）。

计数行为 `ACCESS_COUNTS` 追加 `a6 <pass> <total>`；单独运行 `node scripts/tests/access/a6-write-button-gate.mjs` 失败必须 `exit 1`。

### 6.2 `scripts/access-verify.sh` 接线

- `ALL_SEGMENTS=(a0 a1 a2 a3 a4 a5 a6)`；`segment_cmd` 增 `a6) node`；`segment_file` 增 `a6-write-button-gate.mjs`；`segment_min[a6]=24`。
- 头部注释与总数下限 `165 → 189`；仅全段模式下生效的总量守卫同步 189。

### 6.3 `scripts/platform-demo-reset.sh`：61 → 66

新增 helper（放在 `check_visible` 之后）：

```bash
check_total() { # check_total <user> <path> <eq|ge> <expected> <label>
  local user="$1" path="$2" cmp="$3" expected="$4" label="$5" total
  login_or_stop "${user}" 'Passw0rd!' "${user}" || return 0
  api_call GET "${path}"
  [[ "${HTTP_CODE}" == "200" ]] || { fail_http "${label}" "检查网关/token/tenant 头；000/502/503 属传输层"; return 0; }
  total="$(jget "${HTTP_BODY}" '.total')"
  …按 cmp 比较，空值判失败（禁止 [[ "" -ge 1 ]] 退化）…
}
```

新增 5 条（`section 4` 尾部，`API_TOKEN` 清空前）：

1. `vpm1` `GET /api/req/releases?page=1&size=50` → `total ≥ 1`，且 `items[].code` 含 `DEMO-REL-1`
2. `vpm1` `GET /api/deliver/testcases?page=1&size=50` → `total ≥ 3`
3. `vendor1` `GET /api/req/releases?page=1&size=50` → `total ≥ 1`
4. `admin` `GET /api/req/releases` → `total == 1`
5. `admin` `GET /api/deliver/testcases` → `total == 12`

`TOTAL_CHECKS=61 → 66`；**一条不删**；两次重置的 C1/C2 幂等比对保持原样。

### 6.4 回归与门禁

| 门禁 | 要求 | 说明 |
|---|---|---|
| `node scripts/tests/access/a6-write-button-gate.mjs` | 25/25 | 栈起着跑；栈未起须 exit 1 且无计数行 |
| `bash scripts/access-verify.sh` | ≥189，且 6 段全绿 | a6 下限 24 |
| `bash scripts/platform-demo-reset.sh` | 66/66，连跑两次一致 | 真登录 + 真 API |
| `bash scripts/platform-verify.sh` | ≥173 | C4 语义不变；全量 grep 硬编码 8/11 只改预期值 |
| `bash scripts/rbac-verify.sh` | ≥113 | 服务端零改动，应原样通过 |
| `bash scripts/platform-test-negative.sh` | ≥92 | 同上 |
| `mvn -o -DskipTests compile` | 0 错 | 任务前后各一次 |
| `cd web && npm run typecheck && npm run build` | 0 错 | 任务前后各一次 |
| `bash scripts/tests/lint.sh`、`bash scripts/verify.sh` | 绿 | 文档/脚本边界检查 |

**证据（≥3 张，落 `docs/teamflow/20261116-r25-demo-perm-vendor-seed/evidence/`）**：vpm1 需求池（灰按钮 + chip + 悬浮 title）、vpm1 版本计划（DEMO-REL-1，需求 11）、vpm1 用例库（3 行 + 只读横幅）；截图脚本放 `logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-evidence.mjs`（run 结束由 host 归档，不进库）。

---

## 7. 任务拆分（3 条，串行；PRD §9 约束进任务）

> 顺序 T1 → T2 → T3；每条任务开始前、结束后各跑一次 `mvn -o -DskipTests compile` 与 `cd web && npm run build`。

### T1 前端写入口收敛（4 文件）

- **files**：`web/src/views/req/RequirementPoolView.vue`、`web/src/views/deliver/DefectListView.vue`、`web/src/views/deliver/IterationsView.vue`、`web/src/api/req.ts`
- **reads**：`web/src/composables/usePermission.ts`、`web/src/components/ScopeChip.vue`、`web/src/components/EmptyState.vue`、`web/src/api/client.ts`、`web/src/stores/auth.ts`
- **验收**：按 §3 逐条落位；`vue-tsc` 0 错；admin/pm/po1 行为不变；不新增依赖；模板无内联 push。
- **git**：留在 `feat/vendor-demo-polish`；不新建分支、不 merge、不 stash；`web/package.json` diff 为空。

### T2 演示夹具与重置断言（3 文件）

- **files**：`req-service/src/main/java/com/example/req/seed/DemoFixture.java`、`req-service/src/main/java/com/example/req/seed/DemoResetService.java`、`scripts/platform-demo-reset.sh`
- **reads**：`deliver-service/src/main/java/com/example/deliver/testcase/TestCaseService.java`（同源口径）、`scripts/platform-verify.sh`（C4）、`docs/teamflow/memory.md`（同主题行）
- **验收**：R-09/10/11 `inRelease` 改 `"1"`；用例 `vendor_id` 由需求派生；重置 66/66 且两次一致；admin/pm 计数不减；查库证明 `release_requirement` 11 行；无 UPDATE 造数；`pom.xml` diff 为空。
- **文档**：`docs/teamflow/memory.md` 只在既有「演示与准入」同主题行/「已知待办」同主题行做替换式补充（两条设计偏差 D-3、空态不一致），**不新增段、不改 AGENTS.md**。

### T3 a6 门禁 + 接线 + 交付自检

- **files**：`scripts/tests/access/a6-write-button-gate.mjs`（新建）、`scripts/access-verify.sh`、（证据）`docs/teamflow/20261116-r25-demo-perm-vendor-seed/evidence/*.png`
- **reads**：`scripts/tests/access/a5-capability-probe.mjs`、`a3-nav-audit.mjs`、三个目标视图
- **验收**：§6.1 的 25 条全绿；单独运行失败 exit 1；`access-verify.sh` ≥189；`platform-verify` ≥173；`rbac` ≥113；`negative` ≥92；`typecheck`/`build`/`compile` 0 错；3 张证据截图；**本任务是交付自检，不写 QA-REPORT/ACCEPTANCE、不跑跨浏览器矩阵**。
- **提交**：一个提交包含任务夹（PRD/DESIGN/TECHNICAL/evidence）+ `memory.md` + 代码；提交前 `git status --porcelain` 审计，确认无 `target/`、`.nacos/`、`logs/` 入库；不 push、不 merge（合并由用户确认）。

---

## 8. AC → 实现/验证对照

| AC | 实现 | 验证 |
|---|---|---|
| AC-1 | §3.1 | a6 W1–W8；vpm1 手工无请求/无跳转 |
| AC-2 | §3.1–3.3 + §3.4 零改动 | a6 W1–W14 |
| AC-3 | §2.3 `searchRequirements` | a6 D1–D5；深链仍 403 |
| AC-4 | §6.1 + §6.2 | a6 25/25；access ≥189 |
| AC-5 | §1.2 DemoFixture | demo-reset 66/66；`release_requirement` 11 行 |
| AC-6 | §1.2 vendor 派生 | demo-reset vpm1 用例 ≥3；查库同源 |
| AC-7 | §1.3 I3–I5 | demo-reset 5 条新断言 |
| AC-8 | §6.3 | 66/66，两次一致 |
| AC-9 | §1.4 | platform-verify ≥173；C4 不动 |
| AC-10 | 无服务端改动 | rbac ≥113；4 个写端点 403 |
| AC-11 | §6.4 | 全部门禁 |
| AC-12 | §7 文件边界 | `git diff --stat`；pom/package.json 空；证据 3 张 |

## 9. 风险

| 风险 | 应对 |
|---|---|
| 版本详情「需求 8 → 11」打破既有硬编码 | 只改预期值，grep 全量核对；禁止删断言 |
| 用例 `vendor_id` 直接影响 ScopeSql，写错会串厂商 | 值与需求同源；查库断言 VEN-A=3、vendor2=0 |
| 空态改 `#action` 插槽丢掉筛选分支 | a6 W5 + 人工点「清空筛选」 |
| a6 静态扫描被注释里的示例骗过 | 统一 `blankComments()` 后再匹配 |
| 运行时探针栈未起 | 按 a5 惯例 exit 1、不写计数行 |

## 10. 待确认（均有默认值，不阻塞施工）

1. chip 文案「1 个操作需要权限」是否加复数/明细 → 默认单数 + `scope` 列码。
2. 空态清空筛选的守卫顺序 → 默认 D-4（筛选优先）。
3. vpm1 用例断言用 `≥3` 还是 `≥1` → 默认 `≥3`（更强，且与 I3 一致）。
4. 证据截图放任务夹 `evidence/` → 默认是（任务夹自包含）。
