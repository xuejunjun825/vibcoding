# r24 QA 复验报告 · 厂商项目经理（VENDOR_PM）角色

- 任务夹：`docs/teamflow/20261116-r24-vendor-pm-role/`｜分支：`feat/vendor-pm-role`｜轮次：**fix 后复验**（上一轮 P2×1 + P3×4）
- 结论：**可验收**。P0/P1/P2 = 0；本轮新增 P3 = 1（D-6 文案残留，非阻塞，见 §7）。上一轮 D-1～D-5 全部关闭（§5）。
- 口径：先复跑上一轮全部 checker（回归契约），再对**上一轮未覆盖的面**补 1 个新探针（§3），最后复算每个缺陷类的命中数（§6）。

## 1. 复验范围与环境

| 项 | 值 |
|---|---|
| 被测交付 | VENDOR_PM 第 11 内置角色：168 格/租户、总量 1680→1848、11 条 ACTION DENY 覆盖、release 派生可见性、vendor_id 必填、vpm1 演示账号、前端 canAction 收写口 |
| 环境 | macOS；JDK 25 + Spring Boot 4 栈全起（网关 8080，`/api/actuator/health` 401 即鉴权生效；MySQL 3306；Nacos 8848；web dev 5173）；headless Chrome 157.0.8103.78 CDP `127.0.0.1:9222`（用于真实 DOM 断言，非沙箱内启动） |
| 复验方式 | 复跑 4 个既有 checker + 产品内 `web/e2e/vendor-pm.spec.mjs` + 1 个新探针；再跑全部产品门禁；静态核对跨模块符号；`git status --porcelain` 查提交面 |
| 数据基线 | 演示租户 t1：11 角色 / 1848 授权行 / 14 账号（含 vpm1）；复验末尾执行 `platform-demo-reset` 清理探针夹具（连跑两次均 61/61，库回到 1848 行） |

## 2. 上一轮 checker 复跑（逐条计数，verbatim）

| checker | 命令 | 本轮结果 |
|---|---|---|
| 验收探针 | `bash logs/teamflow/tf-mwiuzjpm-poecia/scripts/qa-r24-acceptance-probe.sh` | **37/37**：12A/1R/47D + 16A/1R/19D + 7A/65D、11 条覆盖、vpm1 登录、隔离 3=3（与 vendor1 同口径）且 3<15、版本列表+详情 403/200、400 在写库前（用户数 251 不变）、PUT 清空 400 且 vendor_id 仍为 1、文档同步 |
| 前端契约 | `node --experimental-strip-types logs/teamflow/tf-mwiuzjpm-poecia/scripts/t3-frontend-contract.mjs` | **57 passed / 0 failed**（真实 `navVisible` 行为段 + 静态段） |
| 导航可见性门禁 | `node scripts/tests/access/a4-nav-visibility.mjs` | **19/19**（A17 vpm1 见用例库、A18 vpm1 不见全部 10 个 `admin.*`、A19 vendor1 深链 403 而 vpm1 放行） |
| 导航审计门禁 | `node scripts/tests/access/a3-nav-audit.mjs` | **11/11**（R11：`vendorPmAllowed` 全库仅 1 处、判定只认 `VENDOR_PM` 角色码） |
| 证据脚本 | `node logs/teamflow/tf-mwiuzjpm-poecia/scripts/r24-t3-evidence-shots.mjs` | **28/28**，8 张 PNG 重生成到 `evidence/` |
| 侧栏实测 | `node logs/teamflow/tf-mwiuzjpm-poecia/scripts/a4-vpm1-probe.mjs` | vpm1 顶部可见 `[需求,版本,审批,缺陷,测试,追溯]`，`adminLeak=[]` |
| 产品内 UI 回归 | `node web/e2e/vendor-pm.spec.mjs` | **19/19**（深链 403 `INTERNAL_ONLY`、只读页主按钮非 primary、厂商内联错误+聚焦、弹窗不关；无 CDP/web 时打印 SKIP 并 exit 0） |

## 3. 本轮新增探针（补上一轮未覆盖的面）

上一轮只证明了 `POST /releases/{id}/reviews` 对 vpm1 是 403，**没有**证明该路由从页面码改动作码后**内部角色仍可建评审轮次**（改错方向就会把 admin/PMO 一起挡掉）。

| 命令 | 结果 |
|---|---|
| `bash logs/teamflow/tf-mwiuzjpm-poecia/scripts/r24-reviews-route-probe.sh` | **3/3**：admin `POST /api/req/releases/1271/reviews {"decision":"GO"}` → 201；同 payload 以 vpm1 → **403 FORBIDDEN**；vpm1 回应体含 `FORBIDDEN` 不含 500 |

判定：`PermissionRouteTable.java:249` 的 `action("req.release","release.action.update")` 方向正确——写口收窄、内部角色不回归。

## 4. 门禁实测（全绿，无缩水）

| 门禁 | 实测 | 基线下限 | 判定 |
|---|---|---|---|
| `bash scripts/rbac-verify.sh` | **113/113**（s0 14、s1 17、s2 11、s3 8、s4 7、s5 12、s6 9、s7 9、s8 7、s9 8、s2r 11；等价 SHA `f0e1137b51c6f241…`） | ≥111 | 通过 |
| `bash scripts/access-verify.sh` | **165/165**（a0 29、a1 8、a2 73、a3 11、a4 19、a5 25） | ≥152 | 通过（a3 10→11、a4 16→19） |
| `bash scripts/platform-verify.sh` | **173/173** | ≥168 | 通过 |
| `bash scripts/platform-test-negative.sh` | **92/92**（第 17 段 VENDOR_PM 边界全绿） | ≥54 | 通过 |
| `bash scripts/platform-demo-reset.sh` | **61/61**（连跑两次；1848 行/11 角色/vpm1 可见 3 条与 vendor1 同口径） | ≥60 | 通过 |
| `bash scripts/platform-unit.sh` | **338/338** | ≥335 | 通过（上一轮红的那条 `RequirementActionMatrixWiringTest` 已绿） |
| `bash scripts/tests/lint.sh` | **22/22** | ≥22 | 通过 |
| `bash scripts/verify.sh` | **14/14** | 14 | 通过 |
| `bash scripts/analytics-i18n-verify.sh` | **74/74** | 74 | 通过 |
| `bash scripts/collab-verify.sh` | **257/257**（s0–s8） | s0–s8 全绿 | 通过 |
| `cd web && npm run typecheck` | exit 0，0 diagnostics | 0 | 通过 |
| `cd web && npm run build` | exit 0（7.55s） | 绿 | 通过 |
| `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` | exit 0 | 绿 | 通过 |

## 5. 缺陷复验对照（上一轮 → 本轮）

| 编号 | round-2 级 | 功能模块 | 上轮问题 | 本轮复验命令与证据 | 结论 |
|---|---|---|---|---|---|
| D-1 | 关闭（上轮 P2） | 权限门禁 | a3/a4 未覆盖 vpm1 与 `vendorPmAllowed`，例外只能靠人眼 | `node scripts/tests/access/a4-nav-visibility.mjs` → 19/19（A17–A19 均含 vpm1）；`node scripts/tests/access/a3-nav-audit.mjs` → 11/11（R11 断言唯一处+仅角色码）；`grep -c "vpm1" scripts/tests/access/a4-nav-visibility.mjs` → 13 | 关闭 |
| D-2 | 关闭（上轮 P3） | 文档/注释 | 8 处注释仍写「10 角色 / 1680」 | `grep -rn "1680" req-service/src/main/java/com/example/req/seed/DemoResetService.java req-service/src/main/java/com/example/req/sys/RoleAdminService.java scripts/rbac-verify.sh` → 0 行 | 关闭（声明范围内，另见 D-6 残余） |
| D-3 | 关闭（上轮 P3） | 前端视觉 | 只读抽屉「保存为新版本」是 primary 蓝 | `TestCaseLibraryView.vue:484,726`、`ReleaseListView.vue:136`、`TestPlansView.vue:341` 均改为按权限条件绑定 type；证据 03/04 显示 disabled 时为默认按钮；`web/e2e/vendor-pm.spec.mjs` 断言「新建版本」非 primary | 关闭 |
| D-4 | 关闭（上轮 P3） | 前端表单 | 厂商必填只弹 toast，错误文字压住下一行 | `AdminUsersView.vue:111 focusVendorField()` 在客户端拦截与服务端 400 两条路径都聚焦；`:370 ref="vendorSelectRef"`；`:406` 错误改为流内定位；证据 07 为红框+红字+光标聚焦+弹窗未关；e2e 断言 focus/dialogOpen | 关闭 |
| D-5 | 关闭（上轮 P3） | 测试资产 | UI 断言只存在于临时日志脚本 | 新增产品内 `web/e2e/vendor-pm.spec.mjs`（19 断言，零新依赖，服务缺失时 SKIP exit 0）→ 19/19 | 关闭 |

## 6. 类扫描复算（fix 声明 vs 本轮实测）

| 缺陷类 | fix 声明 | 本轮实测 | 判定 |
|---|---|---|---|
| D-1 导航例外覆盖 | a4 8 角色/19 断言、a3 R11/11 | a4 `vpm1` 命中 21、断言 19/19；a3 `vendorPmAllowed` 命中 15、规则 11/11 | 一致 |
| D-2 「10 角色/1680」残留 | 3 文件 8→0 | 3 文件 = 0；但 `web/src` 仍有 7 处（3 处为**渲染文本**），测试注释 2 处 → **D-6** | **不一致（面更宽）**：3 文件数字正确，缺陷类未收口 |
| D-3 只读主操作 primary | 4 处条件绑定 | 4 处目标点条件绑定成立、截图可见；全库 `type="primary"…:disabled` 仍 22 行（弹窗确认按钮，非本类，仅计数留痕） | 一致（类口径=只读页主操作） |
| D-4 厂商必填反馈 | 聚焦 + 流内错误 | 代码 3 点（聚焦函数/ref/样式）+ 截图 + e2e 断言 | 一致 |
| D-5 UI 断言固化 | 产品内 spec 19 断言 | 文件存在且 19/19，SKIP 分支有明确约定 | 一致 |

## 7. 缺陷表（仅未关闭）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-6 | P3 | 前端文案（管理端） | 以 admin 登录 → 角色与权限页看页脚；组织与租户页看建租户提示；侧栏「权限总览」看 hint | 文案随 r24 变为「11 个内置角色 / 1848 行」 | 三处渲染文本仍写「10 个内置角色回到 1680 行预设授权」（`AdminPermissionsView.vue:155`）、「创建后自动预置 10 角色」（`AdminOrgsView.vue:253`）、「10 角色 × 功能码只读总览」（`AdminLayout.vue:31`）；另有 4 处注释与 2 处测试注释同口径 | AC-13（文档同步）/AC-10 | `grep -rn "1680" web/src \| wc -l`（现 3）与 `grep -rn "10 角色\|10 个内置角色" web/src \| wc -l`（现 5） | 两命令均输出 0（或全部改为 11 角色 / 1848 行） |

> 说明：D-6 是 D-2 同类的**另一面**（管理端渲染文案与注释），不在上一轮 D-2 声明的 3 个文件内，故记为新发现而非 D-2 未修复。不影响任何鉴权/隔离行为，标记为非阻塞 P3。

## 8. 架构与接口一致性（M3）

- **蓝图落实、无重复实现**：`ScopeSql.DERIVED_VENDOR` 只登记 `release`，VENDOR 档生成 `EXISTS (SELECT 1 FROM release_requirement __rr JOIN \`requirement\` __rq … owner_vendor_id/assignee_vendor_id = :vendor)`；`ReleaseService` 列表（:144）与详情（:378）复用同一 `/*scope:r*/` marker（`requireVendorVisible` → 403 `OUT_OF_SCOPE`），未出现第二份 SQL。
- **写口收口单一入口**：`PermissionRouteTable.java:249` 把 `POST ^/releases/\d+/reviews$` 改为 `action("req.release","release.action.update")`，本轮探针双向验证（admin 201 / vpm1 403）。
- **账号约束在写库前**：`AdminUserController.validateVendorBinding`（:311-314）对 create 用请求角色、对 update 用**将生效角色**，先 400 `VALIDATION_FAILED`（`field=vendorId`）再 UPDATE；探针验证用户数不变、清空被拒且原 vendor_id 保留。
- **角色级覆盖单一处**：`RbacSeeder.ACTION_DENY_OVERRIDES:69` + `actionAccessFor:213`，`PermissionMatrix` 保持「角色×功能码」契约（168 格逐格与探针一致）；`RoleCatalog.requiresVendor:90` 为账号校验唯一来源。
- **前端判定同源**：`navAccess.ts:59` 一套 `navVisible`，导航数据（`nav.ts:154`、`deliver.routes.ts:59`）与路由守卫（`router/index.ts:121-125`）共用 `vendorPmAllowed`，三处 NavContext（`SideNav.vue:40`/`TopNav.vue:32`/`AdminLayout.vue:45`）传同一 `auth.isVendorPm`（`auth.ts:125`，只认角色码）；a3 R11 反证「例外只有一处」。
- **接口一致性（静态）**：`canAction` 调用点（`ReleaseListView:24`、`ReleaseDetailView:83-84`、`TestCaseLibraryView:39`、`TestPlansView:59-61`）均在 `stores/auth.ts` 有同名导出；`ActionRegistry.java:73/98/102/104/122/123` 的 `release.action.*`、`test.action.case_edit`、`plan.action.*` 等码均存在；`AdminUsersView` 的 `focusVendorField`/`vendorSelectRef` 均已声明并在模板绑定。**未发现 P1 级架构或接口缺陷。**
- 风险注记（非缺陷，既有模式）：`a4-nav-visibility.mjs` 内含一份与客户端同构的 `navVisible` 测试替身；客户端判定变更时须同步，否则门禁与实现可能漂移。

## 9. Craft 观察（P3，不阻塞）

复核 8 张证据图：只读主操作已降级为默认按钮（03 用例库抽屉、04 测试计划「新建计划」）、禁用态有 `title=需要权限：<动作码>`、厂商必填错误为流内红字+红框+聚焦（07）、空态（「还没有测试计划」）有说明文案。**未发现新的 craft 缺口**，故不新增 P3 观察行。

## 10. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法/工具 |
|---|---|---|
| 真实浏览器视觉终检（本机沙箱不能直接启动 Chromium，本轮改用已就绪的 CDP 9222 抓图） | 03/04/07/08 与 `evidence/assertions.txt` 逐条一致 | 本机 Chrome 打开 `http://127.0.0.1:5173`，admin/vpm1 各登录一次，对照 `evidence/README.md` |
| 1440→1920 宽度侧栏与表格换行 | 无横向滚动、无截断 | Chrome DevTools 响应式，两档宽度各截一张 |
| 键盘可达性与焦点顺序 | Tab 顺序合理、禁用按钮不可聚焦、错误字段自动聚焦 | 仅键盘走查「新建用户」弹窗 |
| 暗色主题对比度 | 正文/禁用/错误文字 ≥ 4.5:1 | DevTools 取色 + axe DevTools |

## 11. 复跑清单（verifyScripts）

```
bash logs/teamflow/tf-mwiuzjpm-poecia/scripts/qa-r24-acceptance-probe.sh
node --experimental-strip-types logs/teamflow/tf-mwiuzjpm-poecia/scripts/t3-frontend-contract.mjs
node logs/teamflow/tf-mwiuzjpm-poecia/scripts/a4-vpm1-probe.mjs
node logs/teamflow/tf-mwiuzjpm-poecia/scripts/r24-t3-evidence-shots.mjs
bash logs/teamflow/tf-mwiuzjpm-poecia/scripts/r24-reviews-route-probe.sh
node scripts/tests/access/a3-nav-audit.mjs
node scripts/tests/access/a4-nav-visibility.mjs
node web/e2e/vendor-pm.spec.mjs
bash scripts/rbac-verify.sh
bash scripts/access-verify.sh
bash scripts/platform-verify.sh
bash scripts/platform-test-negative.sh
bash scripts/platform-demo-reset.sh
bash scripts/platform-unit.sh
bash scripts/lint.sh
bash scripts/verify.sh
bash scripts/analytics-i18n-verify.sh
bash scripts/collab-verify.sh
cd web && npm run typecheck && npm run build
mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile
```

## 12. 结论

**可验收。** 上一轮 P2 与 4 项 P3 均已按「检测命令 + 通过判据」逐条关闭并复跑验证；本轮唯一新增为 **D-6（P3 文案残留，非阻塞）**，不影响鉴权、隔离与写边界。门禁全部 ≥ 基线且无删断言（rbac 113、access 165、platform-verify 173、negative 92、demo-reset 61、unit 338、lint 22、verify 14、analytics 74、collab 257、typecheck 0、build 绿）。提交面前置检查：51 个变更路径中无 `node_modules/`、`target/`、`dist/`、`.env`、`logs/`，`pom.xml` 与 `web/package.json` diff 为空，可安全提交。
