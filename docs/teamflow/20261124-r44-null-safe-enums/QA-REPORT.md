# QA 复验报告 · r44 空值可达性修复（变更单缺 `changeType` 500 → 400）

- 任务夹：`docs/teamflow/20261124-r44-null-safe-enums/`；分支 `feat/null-safe-enum-checks`（HEAD `bd3426d`）
- 轮次：**第 2 轮 · 缺陷修复复验**（policy 6b：先复跑上轮全部探针，再补上轮未覆盖的面）
- 结论：**可验收**；上轮 D-1（P2）、D-2（P3）均关闭，本轮未发现新缺陷

## 1. 结论

| 项 | 结论 |
|---|---|
| 功能修复是否成立 | 成立：缺 `changeType`、`""`、`" "` 三者均 400 `CHANGE_FIELD_NOT_ALLOWED`，不再是 500/NPE；未知类型同样 400 且 detail 带 `allowedTypes` |
| 上轮两条缺陷 | D-1 已 `revert`（`ApiException.java` 与 HEAD 逐字节相同）；D-2 已补 §2 表格行与 §6 回滚口径 |
| 架构/接口一致性 | 与蓝图一致：唯一谓词全函数、四参重载 additive、单点校验；无平台级模块改动、无重复实现 |
| 回归下限 | 全部不降并全绿（见 §10），新增探针 12/12 且自检可红 |
| 交付面 | 8 个产品文件 + 任务夹；`git status` 无依赖/构建产物/平台级模块 |

## 2. 范围与环境

- 被测：`req-service` 变更单创建/预览入口（`POST /api/req/requirements/{id}/changes`、`.../changes/preview`），`knownType` 唯一谓词，`RequirementErrors` 错误形状，`scripts/change-verify.sh` S2 门禁，`CHANGE-REQUEST-FLOW.md` / `memory.md` 契约文档。
- 环境：JDK 25 / Maven 多模块；栈全起（8848 Nacos、8080 网关、8081 demo、8082 req-service、8083 deliver），8082 `/actuator/health` UP（MySQL + discovery），`t1/admin` 登录可用；演示库经 `platform-demo-reset` 两次复位到基线。
- 环境限制（非交付缺陷）：宿主沙箱内 Chromium 无法启动（crashpad 需 `PROCESS_ALL_ACCESS`），本轮不做真实浏览器操作，改以脚本化 HTTP + 静态断言覆盖；需人眼/真浏览器的项见 §8。
- 本轮只读：未修改任何产品代码/文档；新增探针仅落在 `logs/teamflow/tf-mwu9qgf4-ljjad4/scripts/`。

## 3. 用例与结果（执行结果，非清单）

| 编号 | 用例 | 命令（节选） | 结果 |
|---|---|---|---|
| C1 | 缺 `changeType` → 400 且 code 非空 | `change-type-missing-probe.sh` MISSING | PASS（400 `CHANGE_FIELD_NOT_ALLOWED`） |
| C2 | `changeType=""` → 400 | 同上 BLANK | PASS |
| C3 | `changeType=" "` → 400 | 同上 SPACE | PASS |
| C4 | 未知类型 `NOPE` → 400 + `detail.allowedTypes` 四值 | 同上 UNKNOWN | PASS |
| C5 | 四合法值（CONTENT/DAYS/PARENT/RELEASE）仍判真 | `known-type-truth-table.jsh` | PASS 13/13 |
| C6 | 大小写/前后空格变体全部拒绝（不做归一） | 同上 + 单测真值表 | PASS |
| C7 | 字段越界仍 400 三键、无 `allowedTypes` | `qa-r44-type-boundary-matrix.sh` [22] | PASS |
| C8 | 空 patch 仍 400 `{changeType, allowedFields}` | 同上 [23] | PASS |
| C9 | 判定次序：类型先于原因、原因先于白名单 | 同上 [24][25] | PASS |
| C10 | preview 缺类型 400、正向 preview 200 且 `after.title` 生效 | 同上 [26][27][28] | PASS |
| C11 | 主路径闭环：申请 → 影响 → 审批 → 应用 → 基线复位 | `change-verify.sh --once` | PASS 46/46 |
| C12 | 可编辑性矩阵不被本次改动扰动 | `editability-verify.sh --once` | PASS 36/36 |
| C13 | 边界矩阵可重复（无顺序/夹具依赖） | `qa-r44-type-boundary-matrix.sh` 连跑 2 次 | PASS 28/28 ×2，逐项一致 |

失败 0；阻塞 0（沙箱不可用的项已转 §8，不计为阻塞）。

## 4. 空值边界矩阵（实测）

| 入参 | 修复前 | 修复后 | 期望 | 判定 |
|---|---|---|---|---|
| 键缺失 | NPE → 500 | 400 `CHANGE_FIELD_NOT_ALLOWED`，detail `{allowedFields:[], allowedTypes:[4]}` | 400 | PASS |
| `null`（显式） | NPE → 500 | 同上（与缺键等价，A1 假设成立） | 400 | PASS |
| `""` / `" "` / `"   "` / `"\t"` / `"\n"` | 400 | 400，detail 保留原 `changeType` 键 | 400 | PASS |
| `NOPE` / `content` / `CONTENT ` | 400 | 400 + `allowedTypes` | 400 | PASS |
| `CONTENT` / `DAYS` / `PARENT` / `RELEASE` | 200 路径 | 仍 200 路径 | 通过 | PASS |

`detail` 键序观察：`Map.copyOf` 的迭代序在两次运行间不同（本轮 `[allowedFields, allowedTypes]`，上轮 `[allowedTypes, allowedFields]`），故**只能按键集合断言**；单测与探针均按键集合断言，未把键序写成契约。

## 5. 架构与接口一致性核对

| 蓝图要求 | 实际 | 判定 |
|---|---|---|
| `knownType` 变全函数，单点边界判定 | `RequirementChangeFields.java:93-95`：`changeType != null && !changeType.isBlank() && TYPES.contains(changeType)`；生产代码仅 `RequirementChangeValidator.java:47` 一处调用 | 一致 |
| 四参重载 additive，三参既有调用点不动 | `RequirementErrors.java:170-186` 新增四参；`RequirementErrors.java:159-167` 三参原样；`RequirementChangeValidator.java:61,65` 三参调用未变；两处 message 表达式逐字相同 | 一致 |
| 校验次序不重排 | `RequirementChangeValidator.java:45-50` 仅把 `TYPES` 作为 `allowedTypes` 传入，类型→原因→白名单→归一化→业务前置顺序未动 | 一致 |
| 不新增模块/不改依赖方向/不动前端 | `git status` 无 `pom.xml`、无 `web/`、无平台级模块；`platform-common` diff 为空 | 一致 |
| 无重复实现漂移 | Java 侧类型常量仅 `RequirementChangeFields.TYPES` 一份，`"CONTENT"` 字面量在其它模块 0 命中；前端 `web/src/views/req/change.ts:CHANGE_TYPES` 是既有第二份白名单（r40 起已知风险，本轮未动，服务端仍是最终闸门） | 一致（沿用既有风险） |

接口一致性（静态，无运行时）：`changeFieldNotAllowed(String,String,List<String>,List<String>)` 定义存在且实参 `(changeType, null, List.of(), RequirementChangeFields.TYPES)` 形参类型逐位匹配；`TYPES` 为 `public static final List<String>`；`knownType(String)` 签名未变、返回 `boolean`。无「符号不存在 / 参数错位 / 蓝图 api 不符」。

## 6. 缺陷表（仅列本轮仍 OPEN 的缺陷）

未发现缺陷。

## 6b. 上轮缺陷复验对比（非 open，仅供追溯；表头刻意不用「严重级」）

| 编号 | round-1 级 | 模块 | 上轮问题 | 本轮复验命令（按原文执行） | 结果 |
|---|---|---|---|---|---|
| D-1 | P2 | 架构 | `platform-common/.../ApiException.java` 越界改动（`Map.copyOf`→`Collections.unmodifiableMap`）且未登记 | `grep -c 'error/ApiException.java' docs/teamflow/20261124-r44-null-safe-enums/TECH-CHANGE.md` | PASS，输出 1（判据 ≥1） |
| D-2 | P3 | 文档 | `CHANGE-REQUEST-FLOW.md` 未进 TECH-CHANGE §2 影响面 | `grep -c 'CHANGE-REQUEST-FLOW.md' docs/teamflow/20261124-r44-null-safe-enums/TECH-CHANGE.md` | PASS，输出 2（判据 ≥1） |

D-1 处置复验（revert 是否真的干净）：`git status --porcelain` 中 `platform-common/` 命中 0；`git diff --quiet HEAD -- platform-common/.../ApiException.java` rc=0；末行 `return clean.isEmpty() ? Map.of() : Map.copyOf(clean);`；该文件 `unmodifiableMap` 命中 0；交付 diff 无新增 `unmodifiableMap` 行。新探针 A1–A5 全部覆盖，见 §9。

## 7. 空值扫描复核（同一缺陷类的命中数重算）

- 独立复核 `NULL-SAFETY-SCAN.md`：声明 142 点 = 不可变集合成员判定 63 + `switch` 选择器 79；必加固 1 / 建议 1 / 不可达 140，与修复摘要**一致（无 disagreement）**。
- 重算「不可变集合 + 外部字符串」这一类：Java 侧 `TYPES.contains(` 生产代码仅 1 处（`RequirementChangeFields.java:94`），已在同一表达式内 null/blank 短路 —— 与「必加固 1」相符。
- 抽查 3 处看起来「未同行判空」的既有站点，确认上游已兜：`IntegrationConfigService.authType`(先 `value == null || isBlank()` 再 `AUTH_TYPES.contains(type)`)、`WorkflowAdminService.requireBizType`(`bizType == null ? "" : trim()` 后判)、`GanttRescheduleService.normalizeType`(先判空再 `GanttViews.TYPES.contains`)。
- 建议项 `AdmissionItemPolicy.orderOf`（`ORDER.indexOf(null)`）入参仍来自 DB 非空列，本轮按「只修外部可达点」不改，与蓝图 A6 一致；若将来该入参改为请求参数，升级为 P1。
- 旁证：基线另有 4 个文件使用 `unmodifiableMap`（`AdmissionItem` / `AdmissionViews` / `RequirementRules` / `AutomationEventDraft`），均非本单改动且各自包裹自有 Map，不在回退面。

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法 / 工具 |
|---|---|---|
| 前端发起变更抽屉在缺类型场景的提示 | 抽屉内不出现「未知错误」，能按 `allowedTypes` 给出可选类型 | 真实浏览器（Chrome/Edge，非沙箱）+ 登录 `t1/admin` → 需求详情 → 发起变更，Network 面板核对 400 body |
| 500→400 的用户可见表现 | 合法路径无回归；异常路径不再弹「服务器错误」 | 同上，人工点击一次正常变更申请 |
| `detail` 键序在浏览器 JSON 视图中的展示 | 不作为契约；仅确认前端按键名读取、不依赖顺序 | 人工查看 Network 响应 + 代码走查 `web/src/api/*` |

## 9. 检查脚本与证据（本轮实跑）

| 脚本 | 断言数 | 结果 |
|---|---|---|
| `logs/teamflow/tf-mwu9qgf4-ljjad4/scripts/r44-fix-impact-registry.sh` | 11 | 11/11 PASS，`UNREGISTERED=0` |
| 同上 `--mutate`（负向自检） | 12 | 10/12，`MUTATION_RED=1`，A1/B 按设计转红 |
| `.../r44-doc-contract-check.sh` | 20 | 20/20 PASS |
| `.../r44-revert-and-rollback-completeness.sh`（**本轮新增**） | 12 | 12/12 PASS（A 回退纯净 5 + B 回滚清单 7） |
| 同上 `--selftest` | 13 | rc=1、`FAIL=1`（证明可红，非恒绿） |
| `.../change-type-missing-probe.sh` | 4 | 4/4 全 400 + code + `allowedTypes` |
| `.../known-type-truth-table.jsh` | 13 | 13/13 |
| `.../api-error-detail-order.jsh` | — | `KEYS`/`IMMUTABLE` 行通过；`JSON=` 行因 classpath 缺 `jackson-annotations` 无法执行（脚本自身限制，非交付缺陷；键序结论已由两次运行差异佐证） |
| `.../qa-r44-type-boundary-matrix.sh` | 28 | 28/28，连跑两次逐项一致 |

新增探针补的是上轮**完全没覆盖**的面：D-1 的「revert 是否真干净」（回退后无人再断言）与「§6 回滚清单是否等于真实交付面」（上一轮只查了 §2 有没有登记，没查回滚口径）。B 组把「3 主源码 + 2 测试 + 1 脚本 + 2 文档」逐类与实际 `git status` 计数对齐，并逐字点名两个文档、声明平台级文件不在交付面。

## 10. 回归门禁分母（全部实跑，只增不减）

| 门禁 | 实测 | 下限 | 结果 |
|---|---|---|---|
| `scripts/change-verify.sh --static-only` | 12/12 | 12 | PASS |
| `scripts/change-verify.sh --once` | 46/46 | 46 | PASS（含退出复位与基线干净） |
| `scripts/editability-verify.sh --once` | 36/36（语义 30 + 静态 6） | 36 | PASS |
| `mvn -o -pl req-service -am test` | 437/437 | 437（r43 430） | PASS |
| `scripts/tests/lint.sh` | 24/24 | 24 | PASS |
| `scripts/platform-verify.sh` | 199/199 | 199 | PASS |
| `scripts/platform-test-negative.sh` | 132/132 | 132 | PASS |
| `scripts/access-verify.sh` | 206/206（a5 25 / a6 25 / a7 16） | 206 | PASS |
| `scripts/rbac-verify.sh` | 113/113（11 段） | 113 | PASS |
| `scripts/release-flow-verify.sh` | 44/44 | 44 | PASS |
| `scripts/gate-order-verify.sh` | 21/21（A/B 两序一致） | 21 | PASS |
| `scripts/platform-demo-reset.sh` ×2 | 80/80、80/80 | 80 | PASS |
| `cd web && npm run typecheck` | rc=0 | — | PASS |
| `cd web && npm run build` | rc=0，built in 7.46s | — | PASS |

门禁互斥注记：`change-verify` 与 `editability-verify` 共用演示库，并发会互相触发 `/internal/demo-reset` 500；本轮全程串行执行。

## 11. 交付面与 git 卫生（0c）

- `git status --porcelain` 共 9 项：7 个修改（3 主源码 + 1 测试 + 1 门禁脚本 + 2 产品文档）+ 2 个未跟踪（任务夹、`RequirementChangeFieldsTest.java`）。
- 无 `platform-common/`、无依赖目录/构建产物/IDE 文件/密钥；`web/dist`（`web/.gitignore:2`）、`req-service/target`（`.gitignore:5`）、`.m2`（`.gitignore:16`）均已被忽略。
- 前端无改动，故 craft 维度（0d）本轮 N/A：无可观察的新 UI 元素。
