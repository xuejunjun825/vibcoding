# QA-REPORT · r20（r19 三个阻断缺陷修复轮）

- 任务夹：`docs/teamflow/20261114-r20-r19-rbac-blocker-fix/`　runId：`tf-mwfso8sv-t4ipv0`　分支：`feat/rbac-admin`（基线 `a85ccd8`，改动未提交）
- 结论：**可验收**。P0/P1/P2 缺陷 0 条；P3 观察 5 条（证据命名、右栏信息冗余、改动面/文档口径、跨套件基线 256、探针租户残留）。
- 复跑环境：真实栈 `gateway:8080` / `req-service:8082` / `web:5173`（HMR）+ MySQL `devops_platform`（租户 `t1`，10 个内置角色，1680 行授权）。运行时 jar 与前端 dev 服均为本轮代码（GET 响应含 `unboundFunctions` 组、证据图为真实 UI 渲染）。

## 1. 范围与方法

被测改动 19 个文件：后端 `MenuRegistry`/`SysViews`/`RoleAdminService`/`RbacSeeder`/`DemoResetService`/`RbacRegistryTest`/`PlatformRoutes`；前端 `types.ts`/`useRoleGrants.ts`/`RoleGrantTree.vue`/`RolePermissionsView.vue`；脚本 `rbac-verify.sh`/`_lib.sh`/`s2-seed-equivalence.sh`/`s3-decision-order.sh`/`platform-demo-reset.sh`；文档 `memory.md` + r19 两行基线订正。

方法：静态接口核对（grep/Read，逐条对照 TECHNICAL 的 api 字段）→ 真实栈 API 探针（读模型/写模型/审计）→ 既有门禁全量复跑 → 失败注入（断网关）→ 前端 hook 级 DOM 检查 + 视图 SSR 检查 → 证据图人工目检。全部命令均在沙箱内执行，每条命令单独一次调用。

## 2. 用例与结果（全部 pass，无 fail/blocked）

| AC | 判据命令 | 结果 |
|---|---|---|
| AC-1 编译启动 | `mvn -o -q -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` / `cd web && npm run typecheck` / `npm run build` | exit 0 / 0 / exit 0（6.7s） |
| AC-2 写侧同源 | `bash logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/r20-backend-grants-write-probe.sh` | 10/10：发 138 唯一 subject → changed/added/removed=0，30 条未绑定档位逐格不变，库内 168 行 |
| AC-3 读侧全集 | `bash logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/qa-r20-all-roles-grants.sh` | 52 断言全 PASS：10 个角色各 168 subject、unbound 30、unbound∩bound=0、非法档位 0 |
| AC-4 端到端不丢档 | `bash logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/qa-r19-ui-save-loss.sh` | exit 0：payload 168 distinct、PUT 200、changed=0、nonDENY→DENY=0、还原后 168 行逐字节相等 |
| AC-5 前端可见可保留 | `node logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/t5-grants-check.mjs` / `t4-roles-page-contract.mjs` / `t-b2-unbound-group-check.mjs` / `b1-frontend-keys.mjs` | 57/57、34/34、11/11、33/33；typecheck 0 |
| AC-6 套件内幂等 | `bash scripts/rbac-verify.sh` | exit 0，111/111（s0 14/14 · s1 17/17 · s2 10/10 · s3 8/8 · s4 7/7 · s5 12/12 · s6 9/9 · s7 9/9 · s8 7/7 · s9 8/8 · s2r 10/10 下限 8） |
| AC-7 跨套件幂等 | `bash scripts/access-verify.sh` → `bash scripts/tests/rbac/s2-seed-equivalence.sh` | 152/152 → 10/10 exit 0（access 全量后 s2 仍绿） |
| AC-8 结束断言库=预设 | s2r 段（`RBAC_SEGMENT=s2r`） | 10/10，`CANONICAL_HASH=ea21db0efe2037772531c434248e63d1121ddc1a9de926b7308167049ddbb3c7`，逐格 vs 矩阵一致 |
| AC-9 reset 确定性 ×3 | `bash scripts/platform-demo-reset.sh`（连跑 3 次） | 3 次各 exit 0、57/57、5/5 段，counts 逐键相等 |
| AC-10 失败可诊断可重入 | `GATEWAY_PORT=59999 bash scripts/platform-demo-reset.sh` | exit 1：`停止项: admin 登录（网关不可达）（case=transport，HTTP 000）`、`已执行 39 项 / 未执行 18 项`、未执行清单、`重跑: bash scripts/platform-demo-reset.sh` |
| AC-11 既有门禁不缩水 | `platform-verify` 167/167、`platform-test-negative` 30/30、`platform-unit` 307、`lint` 22/22、`verify` 14/14、`analytics-i18n-verify` 74/74、`collab-verify` 256/256 | 全部 exit 0；`PermissionMatrixGridTest` 600 保留；collab 见 P3-4 |
| AC-12 证据与提交 | `ls docs/teamflow/20261114-r20-r19-rbac-blocker-fix/evidence/` | 4 张 1440×900（01 未绑定分组、02 保存 toast、03 reset×3、04 access 后 s2）；改动面见 P3-3；提交由 host 按 ADR 收口（当前 19 文件未提交） |
| AC-13/14 人员组织与审计 | `bash scripts/rbac-verify.sh s6` / `s8` | 9/9、7/7 |
| AC-15 负向 9 例 | `bash scripts/rbac-verify.sh s7` | 9/9 |
| AC-16 门禁全绿 | 同 AC-11 | 全绿 |
| AC-17 重置与预设幂等 | `bash logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/r20-reset-drift-probe.sh` + reset×3 | 8/8：已预设时日志「跳过重写」、改坏 1 格报「差异 1 格」并归位 |
| AC-18 改动面 | `git status --porcelain` / `git diff -- pom.xml */pom.xml web/package.json` | 19 文件、pom/package.json diff 为空、无依赖/构建/缓存噪声 |
| AC-19 审计 4 类 | `bash logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/qa-r20-audit-noop.sh` | exit 0：无差异保存**不写**审计（459→459）；1 码 DENY→ALLOW 写 `ROLE_PERMISSION_UPDATE`（added=1）、改回写 removed=1，均带 before/after 与 `highRiskGranted` |
| AC-20 既有门禁 | 同 AC-11 | 全绿；r17 自服务谓词、内部回调无回归（s3/s7） |
| AC-21 rbac 套件 | `bash scripts/rbac-verify.sh` | 111/111、11 段，失败定位到角色/页面/动作 |
| AC-22 改动面约束 | P3-3 口径说明 | 与 TECHNICAL §1.2 一致（含 api-gateway 1 文件），PRD AC-12 文本漏列 api-gateway |

补充 QA 自建复核（非 AC 直接要求，用于交叉验证）：`qa-r20-all-roles-grants.sh` 覆盖 10 角色读写同源；`qa-r20-audit-noop.sh` 覆盖「无差异不写审计」。诊断题外话：GET 视图 payload 是 **144 行 / 138 唯一 subject**（6 行跨页重复绑定：`org:manage` 5 页、`PORTAL_VIEW` 2 页、`case:manage` 2 页），前端 flatten 后 168 键，服务端 PUT 按 subject 去重写 168 行——三者口径必须区分，文档已订正。

## 3. 接口一致性（静态，逐条对照 TECHNICAL 的 api 字段）

| 蓝图接口 | 实际落点 | 结论 |
|---|---|---|
| `MenuRegistry.unboundFunctionCodes()` | `platform-contract/.../MenuRegistry.java:171`，以 `PAGES` 绑定集合对 `PermissionMatrix.functions()` 取差集（60−30=30，保序） | 一致 |
| `SysViews.UnboundGroup` / `RoleGrantView(5 参)` | `req-service/.../SysViews.java:86`、`:89` | 一致（顶层键，modules 仍 36 页） |
| `RoleAdminService.grants/saveGrants/unboundGroup/accessOf` | `:75`、`:234`、`:115`、`:369`；写侧 `:238-239` 双 `stored(tenantId, roleId)` | 一致（`fullGrid()` 仅由 `stored()` 在 `:341` 调用） |
| `types.ts` `UnboundFunctionGroup` / `unboundFunctions?` | `web/src/api/types.ts:789`、`:799` | 一致（旧后端无该键不崩，b1 33/33 覆盖） |
| `useRoleGrants` flatten 168 / unboundGroup·Count·Dirty / payload | `web/src/views/admin/role-permissions/useRoleGrants.ts:170/174/180`、`:258`、`:282` | 一致（t-b2 断言 168 键与 payload 168） |
| `RoleGrantTree` `UNBOUND_SELECTION` + `unbound` prop | `RoleGrantTree.vue:9`、`:32`、`:296`（`v-if="showUnbound"`，0 条时整组隐藏） | 一致 |
| `RolePermissionsView` `unboundSelected/unboundRows` + 右栏分支 | `RolePermissionsView.vue:80-83`、`:513-541`（空态 `acts__empty`）、`:733`（savebar 「含未绑定 N 处」） | 一致 |
| `_lib.sh grants_of` 168 | `scripts/tests/rbac/_lib.sh` 合并 `unboundFunctions.functions[]` 并 `unique_by(subjectType,subjectCode)` | 一致（138+30=168） |
| `s2 report_counts` 段名注入 | `s2-seed-equivalence.sh:19` `SEGMENT="${RBAC_SEGMENT:-s2}"`；`rbac-verify.sh:24` `SEGMENTS=(… s2r)`、`:100-102` 注入 | 一致 |
| `RbacSeeder.driftCells` / `DemoResetService.restoreRoleGrants` | `RbacSeeder.java:200`（复用 `preset()`）；`DemoResetService.java:158-163` 差异 0 且自定义角色 0 → 跳过重写 | 一致 |
| `PlatformRoutes.idempotent()` | `PlatformRoutes.java:35/38/41/44` 接入；`:67` `setRetries(1).setMethods(GET).setExceptions(IOException.class).setBackoff(200ms→1s)` | 一致（`qa-r20-gateway-retry.sh` 8/8：无写方法、无状态码重试） |
| `platform-demo-reset.sh` 诊断 | `failure_case`/`fail_stop`/`fail_http`/`login_or_stop`/`unexecuted_list`/`reset_once` + 五段化 | 一致（失败注入实测 case=transport） |

## 4. 架构检查（轻度）

- 权源单一：`grep -rln unboundFunctionCodes` 仅命中 `MenuRegistry.java`、`RoleAdminService.java:116`、`RbacRegistryTest.java`；前端与脚本**没有**自行 60−30 的推导（`useRoleGrants.ts:17` 仅注释），符合「脚本/前端只消费 API」。
- 无重复实现：gateway 重试只有 `PlatformRoutes.java:67` 一处；`fullGrid()` 只经 `stored()` 使用；`failure_case/fail_stop` 只在 `platform-demo-reset.sh`；`driftCells` 复用 `preset()` 而非另写 SQL 期望（与 s2 的 SQL 推导构成刻意交叉验证）。
- 抽象到位：未绑定分组放在顶层键而非第 7 个 module，`modules[]` 仍 36 页、168 未被撑成 169；写侧改基底而非改前端「发全量」，属纵深防御（实测只发 138 行仍不丢档）。
- 输入校验未被削弱：`RoleGuard.validateSubject`（`RoleGuard.java:126-148`）对 FUNCTION/MENU/ACTION 逐码校验注册表，未知码 400 `UNKNOWN_SUBJECT`。
- 未发现既有结构被破坏、无循环依赖新增、无跨层直连（`TenantContext` 直连 7 处未动）。

## 5. 缺陷表

**未发现 P0/P1/P2 缺陷。** 以下为 P3 观察（不阻塞验收）：

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| QA-R20-01 | P3 | 证据/文档 | 打开 `evidence/02-save-noop.png`，对照 `r20-evidence-shots.mjs` 头注释 | 文件名能表达内容（零改动保存 changed=0 的 API 证据） | 图内容是「把 req:review 置允许后保存，toast 新增 1」，零改动保存因 savebar `:disabled="!dirty"` 在 UI 不可达，改由 `qa-r19-ui-save-loss.sh` 在 API 层断言 | AC-12 | `bash logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/qa-r19-ui-save-loss.sh` | 该脚本 exit 0 且打印 `changed=0`（已满足）；建议 ACCEPTANCE 注明文件名与实际语义 |
| QA-R20-02 | P3 | 授权页 UI（craft） | 打开授权页未绑定分组右栏 | 每列信息不重复 | 「当前」列与紧邻三档控件表达同一档位，信息冗余 | AC-5 | - | 视觉观察项，非功能缺陷 |
| QA-R20-03 | P3 | 改动面/文档 | `git status --porcelain` 与 `TECHNICAL.md:28-30` 对比 | 蓝图改动面清单与实际一致 | 实际多改 `scripts/tests/rbac/s3-decision-order.sh`（+47 行，403 body 改为在 45s 窗口内重试取 403 那次响应，断言未削弱）；PRD AC-12 文本未列 `api-gateway`（TECHNICAL §1.2 明列 1 文件） | AC-12/AC-22 | `git status --porcelain \| grep s3-decision-order` | 文档口径订正或 ACCEPTANCE 说明后关闭 |
| QA-R20-04 | P3 | 工程/环境 | `bash scripts/collab-verify.sh` | 263/263（基线） | 256/256：`scripts/tests/collab/s8-isolation.sh:60` 查 `sys_tenant id=2`，当前库只有 id 1/63/64，第二租户段整段 `info` 跳过 7 条条件断言；脚本未改（`git status` 无 collab 变更），属数据漂移 | AC-11 | `bash scripts/collab-verify.sh s8` | 恢复租户 id=2 后 s8 应回到 24 条；当前 17 条为环境所致 |
| QA-R20-05 | P3 | 工程/环境 | `SELECT id,code FROM sys_tenant` | 演示库无探针残留 | 残留 2 个探针租户 `t2-135114`(13:51)、`t2-141150`(14:11)，由 access/tenant 探针产生；demo reset 不删租户，不影响 t1 验收 | AC-11 | `MYSQL_PWD= mysql --protocol=TCP -h127.0.0.1 -uroot devops_platform -N -e "SELECT id,code FROM sys_tenant"` | 清库后只剩 `t1` |

## 6. 人工补测清单（环境限制，非交付缺陷）

1. **真实浏览器交互**：沙箱进程令牌被限制（Chromium crashpad 需 `PROCESS_ALL_ACCESS`，`--no-sandbox` 无效），本环境无法起浏览器。已用等价手段覆盖：真实 UI 截图（证据 01/02，由宿主侧渲染）+ `t-b2` 的 DOM/组件断言 + `t5/t4/b1` 的 hook 级检查。人工复核建议：在浏览器打开 `http://127.0.0.1:5173` → 设置 → 角色与权限 → 测试工程师 QA → 观察未绑定分组与右栏三档。
2. **视觉细节**：右栏 30 行在 900px 高度内的滚动体验、toast 自动消失时长、hover/focus 态观感——需人眼。
3. **网关重试的真实触发**：`Connection reset` 为对端 JVM 停顿的瞬时错误，脚本只能静态断言（`qa-r20-gateway-retry.sh` 8/8）+ 生产日志对证；无法在沙箱内稳定复现，建议观察下一轮 reset 的 `[41]` 是否再不 500（本轮 3×57/57 已间接覆盖）。

## 7. 结论

- **可验收**：r19 的 BUG-1（读写键集不一致）、BUG-2（套件非幂等）、BUG-3（reset 间歇失败）三条阻断缺陷均已修复且可复跑证明：读写同源（10 角色 × 168 subject、只发 138 行不丢 30 档）、套件内/跨套件双幂等（rbac 111/111、access 后 s2 10/10、结束 s2r 断言库=预设）、reset 3 连 57/57 且失败注入可诊断可重入。
- 既有门禁不缩水：167 / 30 / 307 / 22 / 14 / 74 / 256（唯一差异见 P3-4，环境数据漂移，脚本未改）。
- 遗留：AC-12 的提交由 host 按 ADR-2026-08-27 收口（当前 19 文件仍在工作区，未提交）；P3-3 文档口径、P3-4 租户 id=2、P3-5 探针租户建议在 ACCEPTANCE 或后续 run 一并说明/清理。
