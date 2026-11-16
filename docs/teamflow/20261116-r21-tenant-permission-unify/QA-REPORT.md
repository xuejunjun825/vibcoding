# r21 三层授权判定收口 · QA 报告

| 项 | 值 |
|---|---|
| 需求 | r21：8 文件 11 处判定表达式收口到 `PermissionService`，删除 `TenantContext.access/can`，a5 的 D3 判据改为「动作码来自请求体」 |
| 任务夹 | `docs/teamflow/20261116-r21-tenant-permission-unify/`（契约文档只有 `TECH-CHANGE.md`；无 PRD.md/DESIGN.md） |
| 分支 / 基线 | `feat/rbac-p3-followup` @ `6f93922`；工作区 16 个修改文件 + 1 个未跟踪任务夹 |
| 被测运行栈 | T3 起的 r21 新构建（`make platform-up` 后未再重启；gateway 8080 / deliver / req / Nacos 8848 均在监听） |
| 结论 | **可验收**：P0/P1/P2 缺陷 0，9 套门禁 + 22 条真实翻档位断言 + 6 条判据自证全绿 |

## 1. 范围与方法

- 功能范围：①判定是否真的收口到唯一入口（不是「改完还能编」而是「改完行为随档位变」）；②预设档位下判定与改前逐格等价；③5 种 403 报文形状逐字不变；④`canEdit`/自服务短路径这类 display 谓词与拦截同源。
- 方法：**真实栈翻档位负向探针**（22 断言，脚本 `logs/teamflow/tf-mwiju0jk-9g728z/scripts/qa-r21-action-deny.sh`）+ 8 套既有门禁 + 静态核查（grep / 编译）+ 独立判据复验（`.../scripts/qa-r21-a5-d3-judge.mjs`，6 断言）。
- 为什么必须翻档位：rbac/access 都按**预设档位**跑，预设态下「读编译期矩阵」与「读运行时三层授权」判决相同，区分不开 r21 是否真的收口。只有把一格翻成 DENY 再打端点，才能把两者分开。
- 环境限制：本沙箱无法启动 Chromium（crashpad 需要 `PROCESS_ALL_ACCESS`，`--no-sandbox` 无效），故无截图；**本交付无任何前端文件改动**，UI 视觉走查列入人工清单。

## 2. 门禁结果（一条命令一个检查，全部实跑）

| # | 命令 | 结果 | 地板 | 判定 |
|---|---|---|---|---|
| 1 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -q -DskipTests compile` | exit 0（BUILD 静默成功） | - | PASS |
| 2 | `bash scripts/rbac-verify.sh` | 111/111（11 段，下限已核） | 111 | PASS |
| 3 | `bash scripts/access-verify.sh` | 152/152（a0 29 / a1 8 / a2 64 / a3 10 / a4 16 / a5 25） | 152 | PASS |
| 4 | `bash scripts/platform-verify.sh` | 167/167（AC-1..AC-20） | 167 | PASS |
| 5 | `bash scripts/platform-test-negative.sh` | 30/30 负向用例被拒且不落库 | 28 | PASS |
| 6 | `bash scripts/tests/lint.sh` | 22/22（L18 bodyAction、L19 单入口 均绿） | 22 | PASS |
| 7 | `bash scripts/platform-unit.sh` | 307/307 | 306 | PASS |
| 8 | `bash scripts/verify.sh` | 14 PASS / 0 FAIL | 14 | PASS |
| 9 | `bash scripts/rbac-verify.sh s2r`（探针后复跑） | 10/10，`CANONICAL_HASH=ea21db0efe203777…` 与探针前一致 | 8 | PASS |

## 3. r21 专属负向探针（真翻一格，未改预设库）→ 22/22 断言，exit 0

| 段 | 翻哪一格（其余格逐格断言不变） | 观测 | 说明 |
|---|---|---|---|
| A1 | ADMIN 的 `ACTION portal.action.config` → DENY | `GET /portal/config?roleCode=qa` **403**，`detail={function:PORTAL_CONFIG, method:GET, path:/portal/config}` **无 `detail.page`** | GET 在路由表只有页面规则，403 只能出自服务层；带 page 才是拦截器形状，故这条同时证明「出自行内守卫」 |
| A2 | 置回 ALLOW | 同一请求 **200**、`roleCode=qa`、卡片 4 张 | 下一次请求即生效，不需要重启 |
| A3 | QA 的 `FUNCTION TEST_EXECUTOR_MANAGE` → DENY（MENU 仍 ALLOW） | `POST /test-executors {}` **403「无权管理执行器」**，`detail.function=TEST_EXECUTOR_MANAGE`；翻转前同请求 400 | 无 ACTION 登记的老码仍走 FUNCTION 层 |
| A4 | 不翻档位 | qa1 **不带** `roleCode` 读本人配置 → **200** `roleCode=qa` | 「先 roleCode 短路再权限」未被新守卫改坏（自服务不误伤） |
| C1–C5 | DEV 的 `ACTION wiki.action.edit` → DENY（`FUNCTION WIKI_EDIT` 仍 ALLOW） | `GET /wiki/spaces` 仍 **200**，`canEdit=true` 由 **27 → 0**；置回后 **27** | display 谓词与端点拦截同一个 `decide`：只翻 ACTION 就变，说明它不再读旧的 FUNCTION 码 |
| D1–D5 | QA 的 `FUNCTION CASE_REVIEW_SUBMIT` → DENY（`ACTION test.action.case_review` 仍 ALLOW，种子派生不回写） | `POST /testcases/1/reviews` **403「无权发起用例评审」**，`detail.function=CASE_REVIEW_SUBMIT`，无 page；翻转前 404 | 双入口并存成立：拦截器按 ACTION 放行，服务层按 FUNCTION 拒绝 |
| 收尾 | 三角色 168 格逐格写回 | 与快照逐格相等、各 168 格 | 探针无残留（配套 s2r 已复跑） |

## 4. 静态核查（DoD 逐条）

| 检查 | 命令 | 结果 |
|---|---|---|
| 第二套 oracle 已消失 | `grep -rn "TenantContext\.\(can\|access\)(" --include=*.java deliver-service/src req-service/src platform-common/src \| wc -l` | **0** |
| 业务代码不再直连矩阵 | `grep -rn "PermissionMatrix\.accessOf" deliver-service/src/main req-service/src/main` | **0**（`accessFor/accessForAny` 3 处都在 `req-service/.../seed/RbacSeeder.java:174,178,182`，是种子派生，合法） |
| 11 处判定只走两个入口 | `grep -rn "permissions\.\(allows\|canFunction\|decide\)" deliver-service/src/main req-service/src/main` | 14 行表达式，全部是 `allows(页面,动作)` 或 `canFunction(老码)`，无第三种写法 |
| 403 报文逐字不变 | `git diff -U0 -- '*.java' \| grep '^-' \| grep '[一-龥]'` | 唯一被删的中文行是 `requireFunction("CASE_REVIEW_DECIDE", …)`，新行 `requireMenuAction(…, "CASE_REVIEW_DECIDE", "下用例评审结论")` 保 message 与 legacy detail |
| 文档订正 | `git diff -- docs/teamflow/20261113-r19/{PRD,TECHNICAL}.md` | 两处 `109` → `111`（注 `s2r` 10 条），AC 文本未改 |
| 产品记忆 | `git diff -- docs/teamflow/memory.md` | 新增 r21 行（单入口 + 4 个老码分层 + D3 口径），同主题幂等 |
| 版本控制卫生 | `git status --porcelain` | 仅 16 个交付文件 + 任务夹；无 `node_modules/`、`target/`、`dist/`、`.idea/`、`.env`；`.gitignore` 含 `logs/teamflow/` 与 `logs/` |
| 服务与源码一致（避免假 FAIL） | `find … -newer deliver-service/target/*.jar` | 只有 `platform-common/.../security/PermissionService.java` 比 jar 新，其 diff 是**单行 javadoc**（`:24`），功能代码与 jar 同步 ⇒ 探针结论有效 |

## 5. 接口一致性（静态；编译是最强证据）

| 契约（蓝图 api） | 实际 | 判定 |
|---|---|---|
| `PermissionService`: `decide` / `accessOf` / `canFunction` / `canMenu` / `canAction` / `allows` / `epoch` / `invalidate` / `clearRequestMemo` / `enum Layer` | 9 个方法 + 枚举全部存在（`PermissionService.java:52,91,113,121,125,129,137,200,214,232`） | 一致 |
| `TenantContext` 只剩上下文 | `set/setPrincipal/require/isSet/scopeOrNull/principal/roles/username/tenantCode/tenantName/clear`；无 `access/can`，无 `PermissionMatrix` import | 一致 |
| 8 个服务的构造器注入 | 与蓝图逐参一致：`PortalService(5)`、`TestPlanService(11)`、`CaseReviewService(4)`、`TestExecutorService(4)`、`ReportService(8)`、`DefectService(10)`、`WikiSpaceService(5)`、`WikiPageService(7)`、`GanttRescheduleService(5)` | 一致 |
| 跨模块成员真实存在 | `mvn compile` + `mvn test`（platform-unit 307）通过 ⇒ 每个 `permissions.*` 调用点的方法与实参类型都在 `platform-common` 上解析成功 | 一致 |

## 6. 架构核查（蓝图对齐）

- **唯一入口**：8 文件 11 处判定全部改调同一个 `PermissionService` 实例；`TenantContext` 只剩上下文职责，不再依赖 contract 的判定常量——依赖方向 `platform-contract ← platform-common ← deliver/req` 保持不变，蓝图判定「不动模块边界」成立。
- **T1 原子性**：删 `TenantContext.access/can` 与 11 处调用点在同一编译单元内完成，`mvn compile` 与 307 个单测一次性绿，无过渡期壳子（未保留 `@Deprecated` 委托，符合蓝图「多一个入口就多一个可漂移口径」）。
- **重复实现**：未发现新增的第二套 oracle/包装层；`canFunction` 与旧 `access` 同义、`allows=decide!=DENY` 同义，无逻辑复制。**P3-2**：`PortalService.java:88` 与 `:109` 是同一判据的两份拷贝（GET/PUT），当前逐字一致但未来改动需同步。
- **该抽未抽**：无（蓝图明确「不新增 require/assert 包装」，实现遵守；`CaseReviewService` 的两个私有守卫是为保留不同 403 形状，属必需差异而非漂移）。
- **结构破坏**：无。`a5` 的 D3 判据被抽成可导出纯函数（`classifyActionsEndpoint` / `declaresFromBodyOnActionsHandler`），并由同一文件自证，属于可测试性改进。

## 7. 用例与结果汇总

- 通过：9 套门禁（111/152/167/30/22/307/14/10/compile）+ 22 条翻档位断言 + 6 条独立判据断言 + 8 项静态核查。
- 失败：0。
- 阻塞：0。
- 未覆盖（有意）：`ReportService` 的 `canShare` 展示位与 `DefectService` 的 `proddefect:report` 只做静态核查（前者需要「被翻档位角色拥有的报表定义」夹具，后者需要真实需求 id + 会在演示库建出生产缺陷）；两者与其他 4 处共用同一 API、同一 403 形状，风险由 A1/A3/C4/D4 的等价证据覆盖。

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 判据 | 方法 / 工具 |
|---|---|---|
| wiki 页「编辑」按钮的禁用态与悬浮提示（`canEdit` 驱动） | 档位 DENY 时按钮置灰且不出现「点击后 403」 | 浏览器打开 `/wiki`，用 admin 改 DEV 的 `wiki.action.edit` 后以 dev1 刷新；Chrome DevTools |
| 角色改档后跨服务 45s 传播窗口的体感 | 改档后同一 JVM 立刻生效、另一服务 ≤45s（`platform.rbac.cache-ttl-seconds`） | 手工改档 → 立刻刷新 → 计时观察 |
| 报表详情「共享」按钮（`canShare` 展示位） | 与 `report.action.share` 档位一致，无「按钮亮、点下去 403」 | 浏览器打开 `/reports/{id}`，切换档位前后各看一次 |

## 9. 缺陷表

**未发现 P0/P1/P2 缺陷。** 以下为不影响验收的 P3 观察：

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| OBS-1 | P3 | 协作/甘特 | 查看 `GanttRescheduleService` | 常量随判据收口一并清理 | `req-service/.../gantt/GanttRescheduleService.java:28` 的 `FUNCTION="GANTT_EDIT"` 已无任何引用（仅 `ACTION` 用于审计） | TECH-CHANGE §3 调用清单 | `grep -rn "GanttRescheduleService.FUNCTION" --include=*.java .` | -（死常量，无行为影响） |
| OBS-2 | P3 | 工作台 | 读 `PortalService.java:88` 与 `:109` | 同一判据只写一处 | GET/PUT 两处逐字重复 `!code.equals(roleCode()) && !permissions.allows("ana.portal","portal.action.config")` | TECH-CHANGE §3 | `grep -n "portal.action.config" deliver-service/src/main/java/com/example/deliver/portal/PortalService.java` | -（可抽私有方法，本轮一致） |
| OBS-3 | P3 | 测试工具/探针 | 跑 `qa-r21-action-deny.sh`，服务 jar 早于任一 java 文件 | 只在**功能代码**变更时告警 | 本轮因 `PermissionService.java:24` 单行 javadoc 触发 `WARN 服务跑的是旧构建`；实测 jar 功能同步（A1/C4 已翻转生效） | AC-12 证据 | `bash logs/teamflow/tf-mwiju0jk-9g728z/scripts/qa-r21-action-deny.sh` | -（告警启发式只看 mtime，建议比对 diff 或忽略注释） |
| OBS-4 | P3 | 门禁/a5 D3 | 把 `/requirements/{id}/actions` 规则变异成 `page(...)` | D3 的 `no-action-rule` 分支不红是被蓝图允许的，但需写明兜底方 | 该形态由 rbac `s9`（写端点必须声明动作码）+ 端点内 `RequirementActionMatrix` 兜底；a5 注释未点明这层依赖 | AC-11 门禁不缩水 | `node logs/teamflow/tf-mwiju0jk-9g728z/scripts/qa-r21-a5-d3-judge.mjs` | -（建议在 a5 D3 注释补一句「page-only 由 s9 兜底」） |

## 10. 结论

**可验收。** 判定入口收口在真实栈上被证明（不是「能编」而是「翻一格就跟着变」）：ACTION 层、FUNCTION 层、双入口、自服务短路径、display 谓词五条路径都随运行时授权快照改变且下一次请求即生效；预设档位下判定与改前逐格等价（s2r 哈希 `ea21db0e…` 不变）；5 种 403 报文形状零变更；9 套门禁全绿且不低于地板（negative 30 > 28、unit 307 > 306）。架构上符合蓝图：模块边界未动、无第二套 oracle、无新增包装层。

遗留（不阻断本轮）：①`RequirementActionMatrix.java:135,144` 仍是需求动作的第三条判定路径，建议 r22 立项；②`PermissionMatrix` 的 `accessOf/accessFor*` 仍公开，建议在 `scripts/tests/lint.sh` 加一条 grep 禁令（会改 lint 断言数，需与地板一起调整）；③演示库里 27 个 `s2空间-*`/`s8厂商空间-*` 是历史 collab 门禁残留，建议 collab 段自清理（非 r21 引入）。
