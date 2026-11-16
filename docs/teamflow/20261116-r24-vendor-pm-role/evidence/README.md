# r24 · T3 前端证据（VENDOR_PM 收写口）

> 生成脚本：`logs/teamflow/tf-mwiuzjpm-poecia/scripts/r24-t3-evidence-shots.mjs`
> 复跑：`node logs/teamflow/tf-mwiuzjpm-poecia/scripts/r24-t3-evidence-shots.mjs`
> 断言清单：同目录 `assertions.txt`（本次 **28/28 PASS**）。
> 产品内固化版（不依赖本 run 目录）：`node web/e2e/vendor-pm.spec.mjs`（19/19，见 §「回归入口」）。
> 每张图都是「先断言、再截图」：断言失败脚本以 1 退出，不会留下「看着像对」的图。

被测账号（演示种子）：`vpm1 / Passw0rd!`（VENDOR_PM + 厂商 VEN-A）、`vendor1`（VENDOR + VEN-A）、`admin`（内部管理员）。
栈：网关 8080 · web dev 5173 · Chrome CDP 9222（脚本注入 `sessionStorage['devops.session']` 登录）。

| 图 | 断言要点 | 证明什么 |
|---|---|---|
| `01-vpm1-sidenav.png` | 侧栏含「用例库」；不含「用户与角色」「价值流」 | `vendorPmAllowed` 窄例外只放用例库；`admin.*`/`collab.value-stream` 仍 DENY |
| `02-vpm1-releases-isolated.png` | 版本列表未 403；`vpm1` 版本集 ⊂ `admin` 版本集（接口对账，不写死版本名）；「新建版本」`disabled` 且 `title=需要权限：release.action.create` | 版本可见性走 `ScopeSql` 派生谓词（只见本厂商需求所属版本）；写口被 `release.action.create` 关掉 |
| `03-vpm1-testcases-readonly.png` | 深链 `/testcases` 未被 403；新建/导入 `disabled` 且 `title=需要权限：test.action.case_edit`；导出 CSV 仍可用；详情只读横幅写明 `test.action.case_edit`；抽屉底部「保存为新版本」`disabled` 且**不再是 primary 蓝**（`el-button--default`） | 页面例外只开「进得来」，写能力仍由 ACTION 判定；只读保留查询/详情/导出；权限不足的主操作在视觉上就不可点（D-3） |
| `04-vpm1-testplans-readonly.png` | 「新建计划」`disabled` 且 `title=需要权限：test.action.plan_create`；「执行」不渲染或禁用且提示 `test.action.execute` | 测试计划的新建/执行分别按动作码收敛（`executePlan` 入口另有静态守卫断言） |
| `05-vendor1-testcases-403.png` | 落 `/403?from=/testcases&code=INTERNAL_ONLY` | 例外只认 `VENDOR_PM` 角色码，**不认 vendorId**：其他厂商账号仍 403 |
| `06-vpm1-admin-users-403.png` | 落 `/403?from=/admin/users&code=admin.users` | 管理后台对 VENDOR_PM 仍是 MENU DENY（不是 internalOnly 例外路径） |
| `07-admin-vendor-required.png` | 选 `VENDOR_PM` 后出现厂商必填提示；未选厂商点保存 → 表单就地报错（红框+红字）+ toast，**焦点落到「所属厂商」下拉**，**弹窗不关、不发请求** | 前端先拦一次；错误就地可见且可改（D-4）；服务端 400 `detail.field=vendorId` 有回填位（`AdminUsersView` catch 分支） |
| `08-admin-roles-vendor-pm.png` | 角色列表出现 `VENDOR_PM` | 第 11 个内置角色确实落库可管理（168 格由 rbac 门禁独立证明） |

## 回归入口（产品内，不随 run 目录归档）

`node web/e2e/vendor-pm.spec.mjs` —— 19 条 UI 级断言（零新依赖：Node 22+ 全局 `fetch`/`WebSocket` 驱动本机 Chrome CDP），覆盖：vpm1 侧栏含用例库/无用户与角色、`/testcases` 例外放行且新建与导入禁用、非 primary、导出可用、`/releases` 新建禁用、vendor1 与 vpm1 的后台深链 403、admin 建 VENDOR_PM 未选厂商时的内联错误 + 聚焦 + 弹窗不关。
约定与服务未起 → 打印 SKIP 且 exit 0（离线不制造假红灯）；服务在但断言失败 → exit 1。

## 说明（避免误判）

- **版本页有时是空列表**：数据域是「本厂商承接需求所属版本」派生的，演示夹具的版本只含内部需求，所以 `vpm1/vendor1` 看到的版本数可能是 0~N；脚本因此不写死条数，只断言「子集 + 非空隔离」（演示库无任何版本时该条转为 NOTE）。AC-8 的 200 正例由负向脚本自建 VEN-A 版本验证。
- **用例库有时是空列表**：同理按厂商派生。脚本按「有行则点开详情断言只读横幅，无行则断言空态」两条路径自适应，demo reset 前后都能跑。本次截图里的 `TC-0287`（`证据用例-厂商对账只读`，关联 `DEMO-R-09`）是**用 admin 通过 `POST /api/deliver/testcases` 建的证据夹具**，只为让「只读抽屉 + 灰掉的主按钮」可见；`platform-demo-reset` 会清掉它，按同法可重建。
- 截图只读：脚本不写业务数据（唯一副作用是打开弹窗后点一次「保存」被前端拦下，未产生请求）。
