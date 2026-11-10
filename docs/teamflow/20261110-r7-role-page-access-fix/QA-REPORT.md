# QA 报告 · r7 角色页面准入修复（第 2 轮复验：D-01/D-02 修复验证）

- 任务夹：`docs/teamflow/20261110-r7-role-page-access-fix/`；分支 `feat/role-page-access-fix`（自 `main@ea877fa`）
- 本轮性质：对上一轮两条 P2（D-01 执行器页 qa1 被踢 403、D-02 重置后协作域演示页空白）的修复做**复验**，并重跑既有回归契约
- 结论：**可验收（通过）** —— D-01/D-02 均按原「检测命令/通过判据」复跑通过；17 条 AC 全绿；既有门禁一条不红；新增/遗留仅 2 条 P3 观察（D-03 契约重复声明、D-04 演示租户门禁残留累积），不阻塞

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 被测交付 | 3 个只读字典端点 + 前端字典单入口（15 调用点）+ navAccess 单一准入 + 33 侧栏/8 顶栏功能码 + 5 条路由 meta + biz1/suite1 种子 + demo-reset + access-verify 门禁 + DEMO-BY-ROLE 文档 |
| 栈 | 网关 8080 / req 8082 / deliver 8083 / web 5173，`make platform-up` 起；MariaDB 本地实例 |
| 账号 | 12 个种子账号（密码 `Passw0rd!`，admin 为 `Admin@12345`）；本轮额外以 qa1 实测执行器页数据通路 |
| 数据终态 | `make platform-demo-reset` → `platform-seed` 复原协作种子；po1=8 / vendor1=3 / vendor2=0 保持 |
| 浏览器 | **不可用**（宿主沙箱限制：Chromium crashpad 需 PROCESS_ALL_ACCESS，`--no-sandbox` 无效，非交付缺陷）；页面点击/视觉列入 §8，脚本化证据见 §2–§5 |
| 运行日志 | `logs/teamflow/tf-mw9yzocw-5kv3pt/scripts/`（全部检查器可复跑；本轮新增 `qa-r7-round2-fix-behavior.mjs`） |

环境备注：并行 agent 的 `mvn package` 覆盖 fat jar 曾使运行中 req-service 报 `NoClassDefFoundError: ch.qos.logback.classic.spi.ThrowableProxy` 并 503，重启即恢复；与本交付无关。

## 2. 复验回归契约（上一轮检查器逐条重跑，命令原样）

| 检查器（logs/teamflow/tf-mw9yzocw-5kv3pt/scripts/） | 本轮结果 | 说明 |
|---|---|---|
| `node qa-r7-access-probe.mjs` | 218/218 PASS | 字典/隔离/矩阵/登录全覆盖 |
| `node qa-r7-client-semantics.mjs` | 12/12 PASS | 403 默认跳转 vs silentForbidden 仅 reject |
| `node qa-r7-nav-crosscheck.mjs` | 31/31 PASS | 真实 navVisible 求值 vs a4 推导 |
| `node qa-r7-executors-page-guard.mjs` | PASS `OK: 执行器页对 qa1 不再触发全局 403` | **D-01 原检测命令** |
| `bash qa-r7-demo-reset-coverage.sh` | PASS 5/5 `OK: 重置后协作域演示数据齐备` | **D-02 原检测命令**（t1 policy 1、executors 2、两接口非空） |
| `node qa-r7-round2-fix-behavior.mjs`（本轮新增） | 8/8 PASS | 用真实 `session.ts/client.ts/admin.ts` 转译执行：B1 403+silentForbidden → reject 且零导航；B2 无 options → 仍跳 `/403?...`；B3 200 返回 PageResponse；B4 options 作为第 3 参透传 |
| 开发期检查器（t1 24 / t2 19 / t3 迁移 47 / t3 运行时 48 / t4 49 / t5 22 / t6 35 / t7 34 / t10 文档 101） | 全部 PASS | 与上轮同数，无回退 |
| `bash t7-seed-e2e.sh` | **N/A（未跑）** | 需在 18082 另起隔离实例（教训：勿并起第二个 req-service 注册 Nacos）；其断言已由 `platform-seed.sh` 10/10 + access-probe 218/218 在当前栈覆盖（12 登录、biz1/suite1 域授权 7==po1、vendor_id NULL） |

## 3. 缺陷复验对比（round-1 → round-2）

| 编号 | round-1 级 | 原检测命令（本轮重跑） | 本轮结果 | 判定 |
|---|---|---|---|---|
| D-01 执行器页 qa1 被全局 403 踢走 | P2 | `node logs/.../qa-r7-executors-page-guard.mjs` | PASS；qa1 直连 `GET /api/deliver/test-executors` = 200 total=7，`/api/req/admin/api-clients` 仍 403 但页面已 `silentForbidden` 降级 | **已关闭** |
| D-02 重置后分支策略/执行器页空白 | P2 | `bash logs/.../qa-r7-demo-reset-coverage.sh` | PASS 5/5；`DELETE_ORDER` 已移除 `branch_policy/test_executor/api_client`，retention 单测 2/2，reset 28/28 | **已关闭** |
| D-03 router/types.ts 第二份契约声明 | P3 | `grep -rn "router/types" web/src` | 仅 `router/index.ts:10` 注释命中；`NavItem.functionCode` 仍可选 | **仍开放（P3）** |
| D-04 演示租户门禁残留累积（本轮新增） | — | `source scripts/lib.sh; db_q "SELECT code FROM test_executor WHERE tenant_id=1 AND code LIKE 'S7-%'"` | t1 有 5 条 `S7-*` 执行器 + 9 条 `*-ci-*` api_client；collab 一轮后执行器 API total 6→7 | **新开（P3）** |

- **命中面重算**：D-01 类 = 非管理页 import `@/api/admin` 仅 1 处（`TestExecutorsView.vue:11`，已声明 silentForbidden），a3 规则 R9 9/9；D-02 类 = `DELETE_ORDER` 中「删除但不重放」的定义表 0 条，retention 2/2、reset 28/28。
- **与 dev 残留说明的分歧（按要求记为发现）**：dev 只提到 `s7-ci-*` 客户端累积；实测 `test_executor` 同样累积（每跑一轮 collab s7 增加 1 条 `S7-<ts>`），且 `verify-ci-*`/`neg-ci-*` 客户端也在（共 9 条）。因 D-02 修复后 reset 不再清这三张表，门禁复跑会在演示租户留痕 → D-04。

## 4. 用例与结果（AC 逐条）

| AC | 断言 | 证据命令 | 结果 |
|---|---|---|---|
| AC-1 字典端点契约 | 匿名 401；登录 200；域/厂商 `id,code,name`、用户 `id,username,name`；unknown 404；`/admin/domains` 仍 `org:manage` | `bash scripts/tests/access/a0-dictionary-contract.sh` → 29/29；access-probe 218/218 | ✅ |
| AC-2 §7 裁剪与隔离 | 厂商只见本厂商及人员；域为租户子集；跨租户 403 `TENANT_MISMATCH` | `bash scripts/tests/access/a1-isolation.sh` → 8/8 | ✅ |
| AC-3 字典调用收敛 | 15 视图 + `platform.ts` = 16 文件 import `@/api/dictionaries`；管理页外无 `listUsers/listDomains/listVendors` | `grep -rln "@/api/dictionaries" web/src` = 16；`npm run build` exit 0 | ✅ |
| AC-4 403 语义 | 默认跳 `/403?from=…&code=…`；silentForbidden 零导航仍 reject；`loadDictionaries` 全 403 → 空 bundle | client-semantics 12/12；round2-fix-behavior 8/8 | ✅ |
| AC-5 5 页 × 7 角色 35 格 | 30 ✅ + 5 ⛔ 精确状态码 | `bash scripts/tests/access/a2-page-matrix.sh` → 35/35 | ✅ |
| AC-6 数据隔离 | po1=8 / vendor1=3 / vendor2=0 | `bash scripts/platform-demo-reset.sh` → 28/28；platform-verify 167/167 | ✅ |
| AC-7 导航功能码 100% | 33+8 项必填、值域、menu ⊆ route meta、silentForbidden、R9 | `node scripts/tests/access/a3-nav-audit.mjs` → 9/9 | ✅ |
| AC-8 侧栏/顶栏统一过滤 | 单一 `navVisible`（SideNav:35 / TopNav:28），空分组消失，admin 特判已删 | nav-crosscheck 31/31；a4 14/14 | ✅ |
| AC-9 角色可见集抽检 | 7 角色关键隐藏/可见 | a4 14/14 + crosscheck | ✅ |
| AC-10 深链不回归 | `internalOnly` 判定先于功能码；6 条路由 meta 同源 | t6 35/35；浏览器交互见 §8 | ✅（静态+求值） |
| AC-11 业务方账号 | biz1/suite1 可登录，域授权 7 = po1，`vendor_id IS NULL` | platform-seed 10/10；access-probe 218/218 | ✅ |
| AC-12 种子/文档同步 | `SEEDED_USERS` 12、`loginUsers` 12、两遍 counts 相等、skipped=true | platform-seed 10/10；t7 34/34；t10 101/101 | ✅ |
| AC-13 幂等演示重置 | 两遍逐键相等；12 需求链路；po1 8/3/0；定义表保留后两页非空 | `bash scripts/platform-demo-reset.sh` → 28/28 | ✅（D-02 关闭；附 D-04） |
| AC-14 不污染既有链路 | reset → seed → 全门禁绿；不变量违反 409 回滚（单测覆盖） | §5 全表；retention 2/2 | ✅ |
| AC-15 既有门禁 | 一条不红 | §5 | ✅ |
| AC-16 access-verify | ≥45 断言；栈未就绪非 0 | `bash scripts/access-verify.sh` → 95/95 exit 0；`GATEWAY_PORT=19999 REQ_PORT=19998 …` → exit 1「平台栈未就绪…先执行 make platform-up」 | ✅ |
| AC-17 文档同步 | §1 账号 12、§3 30✅/5⛔ 逐格、§6 两条 P0 已修复；工作台/报表段未改 | t10 101/101；t10-role-grants 13×9 对账 | ✅ |

## 5. 门禁复跑（同一工作树，reset → seed 后）

| 命令 | 结果 |
|---|---|
| `bash scripts/access-verify.sh` | exit 0，95/95（a0 29 / a1 8 / a2 35 / a3 9 / a4 14） |
| `bash scripts/platform-verify.sh` | exit 0，167/167（需求池最差 0.0046s） |
| `bash scripts/platform-seed.sh` | exit 0，10/10（12 账号；BUS=2→PDR=7→RD=11） |
| `bash scripts/platform-demo-reset.sh` | exit 0，28/28（两遍幂等；新增 5 条定义表保留断言） |
| `bash scripts/platform-unit.sh` | exit 0，237 个单测（`DemoResetRetentionTest` 2/2、`PermissionRouteTableTest` 12/12） |
| `bash scripts/tests/lint.sh` / `verify.sh` / `platform-test-negative.sh` | 16/16 / 14/14 / 25/25 |
| `bash scripts/quality-verify.sh` / `integration-verify.sh` / `collab-verify.sh` | 348/348 / 231/231 / 263/263（s7 31/31） |
| `cd web && npx vue-tsc --noEmit` / `npm run build` | exit 0（0 error）/ exit 0 |

## 6. 架构与接口一致性自查

- **按蓝图落点**：`DictionaryController`/`DictionaryViews`（record 白名单）/`DemoFixture`/`DemoResetService`/`DemoResetController`/`dictionaries.ts`/`navAccess.ts` 均单点实现；`SideNav.vue:35`、`TopNav.vue:28` 只调用 `navVisible`（`navAccess.ts:32` 唯一导出），无第二份判定；`DELETE_ORDER` 仅 `DemoFixture.java:68` 定义、`DemoResetService.java:205` 消费。
- **跨模块调用（静态，非存在性检查）**：16 个消费文件 import 的 `listDictionary*/loadDictionaries/Dictionary*` 均在 `dictionaries.ts` 真实导出且无参调用匹配；`listApiClients(params, options?)` 的 2 参调用与定义一致且 options 透传为 `http.get` 第 3 参（round2-fix-behavior B4 实测）；`navVisible(item, ctx)` 签名匹配；后端三分量字段与前端类型逐字一致。
- **重复实现/抽象缺口**：未发现第二套字典取数、导航判定或删除顺序；`DemoResetController` 复用 `X-Internal-Token` + `PLATFORM_SEED_ENABLED` 双门（关闭返回 404，与 SeedController 一致）。唯一契约重复为 D-03（`router/types.ts` 第二份 `NavItem/RouteMeta`，本轮仍开放）。
- **零权限码/零 schema 变更**：`/dictionaries/*` 故意无规则，由 `PermissionRouteTableTest` 钉住；无 migration 新增。
- **提交面卫生**：`git status --porcelain` 无 `node_modules/target/dist/.m2/.nacos/IDE/.env`，未跟踪项仅为预期源码/文档；无构建产物或密钥入树。

## 7. 缺陷表（仅本轮仍开放项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-03 | P3 | 架构/契约声明 | 查看 `web/src/router/types.ts` 并 `grep -rn "router/types" web/src` | `RouteMeta`/`NavItem` 单点声明，`functionCode` 必填、含 `internalOnly` | 该文件保留第二份 `RouteMeta`（缺 `internalOnly`）与 `NavItem`（`functionCode?: string\|string[]`），仅被 `router/index.ts:10` 注释引用；本次靠 `declare module` 合并绕过，后续易漂移 | AC-7 | `grep -rn "router/types" web/src` | 仅注释命中；删除 `NavItem` 或改 `extends NavGuard`，`RouteMeta` 补 `internalOnly?: boolean` |
| D-04 | P3 | 演示数据/门禁卫生 | 连续跑 `bash scripts/collab-verify.sh` 或 `platform-verify.sh`，再查 t1 | `make platform-demo-reset` 后演示租户只含种子数据，门禁残留不累积 | reset 已不再清 `test_executor/api_client`（D-02 修复的必然结果），门禁每轮写入 `S7-<ts>` 执行器与 `s7-ci-*/verify-ci-*/neg-ci-*` 客户端：当前 t1 有 5 条 `S7-*` 执行器（API total 6→7）与 9 条测试客户端 | AC-13/AC-14 | `source scripts/lib.sh; db_q "SELECT code FROM test_executor WHERE tenant_id=1 AND code LIKE 'S7-%'"; db_q "SELECT name FROM api_client WHERE tenant_id=1"` | 重置后 `S7-%`/`s7-ci-%`/`verify-ci-%`/`neg-ci-%` 行数=0（门禁自清理，或 reset 仅清 ci 前缀行、保留 STUB-LOCAL/LEGACY-OFF/种子客户端） |

未发现 P0/P1 缺陷；D-01/D-02 已关闭（见 §3），不再列入本表。

## 8. 人工补测清单（环境限制，非交付缺陷）

1. **页面级深链与 403 跳转（AC-10）**：浏览器不可用。方法：vendor1 直入 `/testcases` 应落 `/403?code=INTERNAL_ONLY`；po1 直入 `/test-plans` 落 `/403?code=FORBIDDEN`；有权限账号刷新不回退。工具：Chrome/Edge DevTools。
2. **视觉与交互（AC-17、craft）**：`DEMO-BY-ROLE.html` §3/§7 的 12 张截图、侧栏折叠/空分组、下拉空态与禁用态、403 文案；判据见 DESIGN.md 交互/视觉期望。工具：浏览器 + 截图比对。
3. **AC-14 失败回滚**：注入一条不变量违反后调 `POST /internal/demo-reset`，断言 409 `DEMO_RESET_INVARIANT` 且行数不变（需 mysql + curl + token）。工具：脚本化（本轮由单测覆盖逻辑，未在真实栈制造违反）。
4. **真实浏览器菜单渲染（AC-8/AC-9）**：脚本已用真实 `navVisible` 求值并与 a4 对账，「空分组 DOM 消失」属渲染层，需浏览器目检。
5. **性能全量基线（AC-20）**：本轮只跑 platform-verify 冒烟（需求池 0.0046s）。方法：`make platform-perf`。工具：`scripts/platform-perf.sh`。

## 9. 结论

- D-01/D-02 两条 P2 按原「检测命令/通过判据」复跑通过（执行器页 qa1 不再被踢；reset 后分支策略/执行器两页数据齐备），修复面无回归：access 95、platform 167、seed 10、reset 28、unit 237、lint 16、verify 14、negative 25、quality 348、integration 231、collab 263、typecheck/build 均绿。
- 17 条 AC 全部通过；`make access-verify` 95 条断言（≥45）且栈未起 exit 1。
- 遗留 2 条 P3 观察（D-03 契约重复声明、D-04 门禁残留累积），均附可执行判据，不阻塞验收；建议下轮清理 D-03 并让 s7/verify/neg 自清理其执行器与客户端。
- 判定：**可验收**。
