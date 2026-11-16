# QA 报告 · r25 演示层收敛与厂商夹具（20261116-r25）

- 需求夹：`docs/teamflow/20261116-r25-demo-perm-vendor-seed/`
- 分支：`feat/vendor-demo-polish`（未提交、未建新分支、未 merge）
- 结论：**可验收**。P0/P1/P2 = 0；P3 观察 6 条（视觉/文档口径/门禁稳定性，均不阻塞）
- 复核方式：静态审计 + 真栈 HTTP + 真登录浏览器（playwright headless）+ 直接查库；每项一条命令、一条检查

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 本阶段复测对象 | 三处裸写入口收敛（需求池/缺陷/迭代）、`searchRequirements` 403 降级、`a6` 写按钮门禁与 `access-verify` 接线、DemoFixture VEN-A 承接链、DemoResetService 用例 `vendor_id` 派生、`platform-demo-reset.sh` 61→66 |
| 栈 | `make platform-up` + `make platform-seed`（MySQL 127.0.0.1:3306 / 网关 8080 / req 8082 / deliver 8083 / web 5173） |
| 浏览器 | 沙箱内 playwright（`/Users/xuejunjun/.npm-global/lib/node_modules/playwright`）可用，headless 1440×900 / 1280×900 |
| 数据状态说明 | 断言分两类状态：**重置态**（`platform-demo-reset.sh` 后立即查库）与**种子态**（`platform-seed.sh` 后跑 `platform-verify`）。两类都跑过，报告按各自口径标注 |
| 环境限制 | `scripts/verify.sh`（dev 栈 14/14）要求 demo-service 占 8081、网关占 8080，与当前 platform 栈互斥；本轮未启动 dev 栈，故未重跑（r25 未触碰 dev 栈任何文件，r24 基线 14/14） |

## 2. 用例与结果

### 2.1 门禁与既有回归（全部实跑）

| # | 命令 | 结果 |
|---|---|---|
| 1 | `node scripts/tests/access/a6-write-button-gate.mjs` | exit 0，**25/25**（静态 14 / 降级 5 / 运行时 4 / 自检 2），满足 AC-4「≥24」 |
| 2 | `bash scripts/access-verify.sh` | exit 0，**190/190**（a0 29 · a1 8 · a2 73 · a3 11 · a4 19 · a5 25 · a6 25；a6 下限 24，全量 ≥189）→ AC-4/AC-11 |
| 3 | `bash scripts/platform-demo-reset.sh` | exit 0，**66/66**（[54] vpm1 版本含 DEMO-REL-1 且 `release_requirement=11`、[55] vpm1 用例 3、[56] vendor1 版本 1、[57] admin 版本 1、[58] admin 用例 12）；脚本内两次重置逐键一致 → AC-7/AC-8 含幂等 |
| 4 | `bash scripts/platform-verify.sh`（种子态） | exit 0，**174/174** → AC-9/AC-11（C4 仍读 `SeedService.R2026-11`，与 `DEMO-REL-1` 不同源，语义未变） |
| 5 | `bash scripts/rbac-verify.sh` | exit 0，**113/113**（s2r 11、s6 9、s7 9、s8 7、s9 8）→ AC-10 |
| 6 | `bash scripts/platform-test-negative.sh`（两次） | exit 0，**92/92**（两次均 92 条 PASS；首次历史观测出现过 90，见 P3-D4）→ AC-11「≥92」 |
| 7 | `bash scripts/platform-unit.sh` | exit 0，**338 个单元测试全部通过** → AC-11「≥338」 |
| 8 | `bash scripts/tests/lint.sh` | exit 0，**22/22** → AC-11 |
| 9 | `bash scripts/analytics-i18n-verify.sh` | 首次因栈刚被 `platform-up` 重启而未就绪（live 段 34/45）；栈就绪后复跑 **74/74**（static 33 + live 41）→ AC-11「74」 |
| 10 | `bash scripts/collab-verify.sh` | exit 0，**257/257**（s0–s8）→ AC-11 |
| 11 | `cd web && npm run build` | exit 0（`build` 脚本内含 `vue-tsc --noEmit` → AC-11 typecheck 0 + build 绿） |
| 12 | `mvn -o -q -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` | exit 0 → AC-11 compile 0 |
| 13 | `A6_VPM_PASS=WrongPass node scripts/tests/access/a6-write-button-gate.mjs` | exit **1**，且**不输出** `ACCESS_COUNTS` 计数行 → 门禁不可被假绿（A4），聚合器按缺失段判红 |

### 2.2 QA 侧新增的两条独立检查（补 dev 自检没覆盖的面）

| # | 命令 | 结果 |
|---|---|---|
| 14 | `node logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-qa-ui-probe.mjs` | exit 0，**18/18**（真登录 vpm1 / admin / pm 三账号、三页面 + 深浅链 + 1280 断点）；1 条视觉 OBS（见 P3-D1） |
| 15 | `bash logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-qa-db-probe.sh`（`platform-demo-reset.sh` 后立即跑） | exit 0，**11/11** 直接查库断言（重置态） |

`r25-qa-ui-probe.mjs` 覆盖的实际行为（不是存在性）：

- vpm1 缺陷页：「新建缺陷」`disabled`、`title="需要权限：defect.action.create"`；**强制点击**后弹窗未开、URL 仍为 `/defects`、`POST` 写请求 0 个、未落 `/403`（AC-2/AC-3）
- vpm1 迭代页：未选版本与选中版本后都保持 `disabled`，`title` 落在 `sprint.action.create`（AC-2 的「补动作码维度」）
- vpm1 需求池 chip：tooltip 实测 `需要权限：req:report:bus / req:report:dev`（不是「当前数据范围：…」），`tabindex="0"` 可聚焦（AC-1 的键盘原因通道）
- vpm1 深链 `/requirements/new` → 实测落到 `/403?from=/requirements/new&code=req.create`：**入口收敛没有放宽准入**（AC-3）
- **A2 反例实测**：admin「提报需求」`disabled=false`、无「操作需要权限」chip、点击后真的进入 `/requirements/new`；admin「新建缺陷」可点且开弹窗；pm「提报需求」可点 —— 没有把全站按钮一起弄灰（anti-goal A2 通过）
- 1280 宽页头 `h=32` 与 1440 一致（未换行）、`scrollWidth-clientWidth=0`（无横向溢出）

`r25-qa-db-probe.sh` 的库内断言（重置态，`tenant_id=1`）：

- `test_case` 仍 **12** 行（没有靠新增行凑厂商视角，A3/AC-6）；带 `vendor_id` 的 **4** 行 = VEN-A 3 + VEN-C 1
- 用例 `vendor_id` 与所属需求 `assignee_vendor_id` **逐行相等且缺失状态一致**（0 行例外）→ 与 `TestCaseService.applyOwnership` 的 `setVendorId(requirement.vendorId())` 同源（`ReqReadModel.java:26` 取的正是 `r.assignee_vendor_id`）
- 3 行归 VEN-A（vpm1 用例库正好 3 行）、1 行归 VEN-C（`DEMO-R-12`，未进 DEMO-REL-1，不泄漏到 vpm1）
- 需求仍 12、版本仍 1、`DEMO-REL-1` 的 `release_requirement=11`、VEN-A 的 3 条需求全部在其中、进了关系表的需求 `release_id` 非空（没有「半挂」）

### 2.3 接口一致性（0a · 静态，全部命中）

| 蓝图接口 | 实际位置 | 结论 |
|---|---|---|
| `canReport / reportDenyTip / deniedWriteChip / goCreate / goCreateFromEmpty` | `web/src/views/req/RequirementPoolView.vue`（16 处引用） | 存在、模板与 handler 同源 |
| `canCreateDefect / createDenyTip / deniedWriteChip / openCreate` | `web/src/views/deliver/DefectListView.vue`（14 处） | 同上 |
| `canCreateSprint / canCreateIteration / createDenyTip / openCreate` | `web/src/views/deliver/IterationsView.vue`（13 处） | `canCreateIteration = canCreateSprint && !!releaseId`，disabled 与 handler 同源 |
| `searchRequirements(q)` 签名不变、内部 `{silentForbidden:true}` | `web/src/api/req.ts:163`；`client.ts:22/180` 的 `silentForbidden` 真实存在 | 唯一调用点 `DefectListView.vue:182` 未受影响 |
| `blankComments / auditWriteButtons / auditForbiddenDegrade / probeVpm1Write / selfCheck` | `scripts/tests/access/a6-write-button-gate.mjs:59/188/333/429/571`（`main` 在 :637 未 export，按 CLI 入口使用） | 全部存在，参数形与蓝图一致 |
| `segment_min[a6]=24`、总数 `TOTAL < 189` | `scripts/access-verify.sh:69/138`，`ALL_SEGMENTS` 含 a6 | 已接线，只增不减 |
| `TOTAL_CHECKS=66` | `scripts/platform-demo-reset.sh:34` | 已改 |
| `row[15]=inRelease`、`requirementVendors` | `DemoFixture.java`（09/10/11 三行置 `1`）、`DemoResetService.java:380/412/442` | 14 占位符与 14 参数对齐，仍 12 行、无 UPDATE |

## 3. 架构核查（轻量）

- **与蓝图吻合**：三处视图按 R1「渲染 + `:disabled` + `title="需要权限：<码>"` + handler 首行 return」落位；`/requirements/new` 字面量仍为 2 条（a6 以计数 2 断言两条路径都被守卫覆盖）；`searchRequirements` 只在缺陷弹窗单点降级，未污染需求池主体的 403 语义；夹具走 `release_id` + `release_requirement` 同源写入，**无 UPDATE 造数**；用例 `vendor_id` 从同一遍历产出的映射取值，未新增第三处派生点。
- **重复实现**：`canX/denyTip/deniedWriteChip` 在三视图各写一份、`a6` 与 `a5` 复制 `blankComments/matchParen`、用例厂商派生在 `DemoResetService` 与 `TestCaseService` 各一处——三处均是蓝图显式接受的取舍（抽 composable 会改 `usePermission` 契约；跨脚本 import 会引入加载顺序耦合；夹具构造器无法调服务层）。查库 11/11 已把「同源」从口头约束变成可执行断言，**未出现第三份 vendored 派生**（`grep -rn setVendorId` 全仓：服务层 4 处语义不同，seed 仅 1 处）。
- **结构破坏**：无。服务端鉴权/授权数据零改动（`git diff` 仅 `DemoFixture`/`DemoResetService` 两个 seed 文件）；`pom.xml`/`package.json` diff = 0。
- 架构层面未发现 P1。

## 4. 缺陷表

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| 未发现 P0/P1/P2 缺陷 | - | - | - | - | - | - | - | - |

P3 观察（craft / 文档口径 / 门禁稳定性，**不阻塞验收**）：

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D1 | P3 | 前端视觉 | `node logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-qa-ui-probe.mjs`，读 OBS 行 | chip 按 DESIGN §3.4「高 22 / 12px」 | 实测 `h=20.0 / fs=11px`，浅底与圆角符合；沿用组件既有尺度，未按设计稿加高 | AC-1（视觉） | - | - |
| D2 | P3 | 架构/文档 | `git diff -- web/src/components/ScopeChip.vue` | 改动面与 AC-12/蓝图模块清单一致 | 共享组件被改（新增可选 `tooltip`、权限 chip 文案分流、`tabindex` 恒 0、focus trigger、`space` 键）；DESIGN §6 明确要求该键盘通道，改动向后兼容，但 dev T1 回复称「未改共享组件」、`memory.md` 已知待办①仍写「原因对键盘用户不可达」——文档与代码已不一致 | AC-12 / AC-13 | - | - |
| D3 | P3 | 文档 | 对照 DESIGN §66/§67 与实测 `chip` 宽度 | 页头宽度预算 chip ≈132 | 实测 chip `w=275.6`（`scope` 把两个权限码内联渲染，§124 又要求传 `scope`）；实测 1280/1440 均不换行、无溢出，属预算口径笔误，非布局缺陷 | AC-1 | - | - |
| D4 | P3 | 门禁稳定性 | 连续两次 `bash scripts/platform-test-negative.sh` | 断言总数恒定、可作「≥92」判据 | 断言总数随夹具状态在 90↔92 间漂移（脚本内 VENDOR_PM 段与「清空厂商」等分支不满足时 `warn` 整段跳过，总数照减不报错）；本轮两跑均 92 且 exit 0，非 r25 引入 | AC-11 | - | - |
| D5 | P3 | 前端/接口 | vpm1 打开 `/releases`，对比 `/releases/<id>` | 列表卡片的「需求完成」与详情一致 | 列表接口不返回 `requirementCount/doneCount`，卡片恒显示「0/0 需求完成」，而同一版本详情 `stats.total=3` → 演示时易被误读为「承接链没挂上」（dev T3 已登记 out-of-scope） | AC-7（演示可读性） | - | - |
| D6 | P3 | 前端一致性 | vpm1 打开 `/testcases`、`/releases` 空态 | 全站写按钮统一形态「渲染 + 禁用 + 原因」 | `TestCaseLibraryView.vue:552` 无权限时把主操作 `v-if` 隐藏、`ReleaseListView.vue:162` 空态无 CTA，与 R1 不一致（`memory.md` 已登记，同族下次收敛） | AC-2 | - | - |

## 5. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法 / 工具 |
|---|---|---|
| 真实浏览器视觉走查（chip 与按钮的色值/间距/悬浮观感） | 与 DESIGN §3.4/§5 目视一致，chip 不压按钮、不换行 | 本机 Chrome 打开 `http://127.0.0.1:5173`，`t1/vpm1` 登录 `/requirements` `/defects` `/iterations`，比对 `evidence/*.png` |
| 屏幕阅读器朗读原因 | 键盘 Tab 到 chip 时读出「需要权限：<码>」；禁用按钮无焦点但 chip 可读 | VoiceOver（macOS）或 NVDA，配合 `tabindex=0` 的 chip |
| 触屏/窄屏（375 宽） | 页头不横向滚动、chip 不被裁切 | Chrome DevTools 设备模式 375×812 |
| 多权限码（>6 个）时的 chip 截断 | 文案省略号、tooltip 仍可读全量 | 临时给角色加更多受限动作后目视，或用 DevTools 改 DOM 文本 |
| 视觉回归（chip 常驻 `tabindex` 对其它 4 个消费页的 tab 序影响） | tab 序可预期、不打断主流程 | 键盘走查 `/defects/board`、`/defects/:id`、`/requirements/:id`、`/components` |

## 6. 提交面卫生

`git status --porcelain` 共 12 项：10 个已跟踪文件的改动 + 本任务夹 + `scripts/tests/access/a6-write-button-gate.mjs`；**无** `target/`、`dist/`、`node_modules/`、`.m2/`、`.env`、`logs/` 等构建产物或本地密钥（`pom.xml`/`package.json` diff = 0）。`AGENTS.md` 零改动，`memory.md` 为同主题行替换式补充。

## 7. 结论

**可验收**：两个演示层缺口在真栈 + 真登录下都被证明修复（vpm1 三个写入口禁用且给原因、点击不发请求不落 403、深链仍由守卫 403；vpm1/vendor1 版本计划与用例库非空，库内 `vendor_id` 与需求同源）；A2 反例（admin/pm 按钮仍可用）实测通过；门禁只增不减且 a6 在被喂假密码时 exit 1 不写计数行。6 条 P3 为视觉规格、文档口径与门禁计数稳定性观察，不构成阻塞。
