# r22 QA 报告 · 需求动作判定改数据驱动（ActionPermissionResolver）

- 任务夹：`docs/teamflow/20261116-r22-action-matrix-datadriven/`（契约文档：`TECH-CHANGE.md`，本需求无单独 PRD.md/TECHNICAL.md，验收判据以其 §6「验收判据 ①–⑤」+ §8 假设为准）
- 分支：`feat/rbac-contract-action-matrix`；HEAD `211db80`
- 结论：**可验收**。P0/P1/P2 = 0，P3 = 2（版本控制面 1、文档基线 1），均不阻断。
- 唯一行为校正已被双向证据钉住：`start_dev` 的 PRODUCT 未扩权、`submit_test` 的 QA 未失去提测（单测 480 格 + 真实 HTTP 6 断言两处都验）。

## 1. 测试范围

| 维度 | 覆盖 |
|---|---|
| 功能 | 16 动作判定数据化（`req.action.<动作>`）、页面码 `req.pool` 三序、本人/代他人/厂商三种关系、`allowedActions` 与执行同一谓词、403 四键、审计顺序 |
| 数据 | `sys_action` 72 码 upsert、ACTION 格由 `RbacSeeder` 现算（`accessForAny(role, functionCodes)`）、reset 后 1680 行、`CANONICAL_HASH` |
| 架构 | 依赖方向 `platform-contract ← platform-common`；端口唯一实现；矩阵不再持功能码表；页面码四处字面量一致 |
| 接口一致性 | 5 处跨模块调用逐条核对（§4），无「文件在、符号不在」 |
| 回归 | 9 条门禁全部本轮亲跑，无一缩水 |
| 未覆盖 | 浏览器真实渲染（环境限制，见 §6）、多租户并发、性能（无 AC） |

环境：JDK 25 / Maven 离线仓 `$PWD/.m2/repository`、服务已由 `make platform-up` 起在 8080、库经 `make platform-demo-reset` 回预设。浏览器在本 agent sandbox 内不可启动（宿主 token 限制，非交付缺陷），故 UI 侧只做静态与接口核对；本需求无前端改动。

## 2. 验收判据逐条结果（8/8 通过）

| # | 判据 | 命令（本轮实跑） | 结果 |
|---|---|---|---|
| AC-1 | 矩阵内 `PermissionMatrix.accessOf` 0 次 | `grep -rn "accessOf" platform-contract/src/main/java/com/example/platform/contract/RequirementActionMatrix.java` | 0 命中；残留仅 `PermissionMatrix` 自身与 javadoc（exit 0） |
| AC-2 | 两向 e2e：改 DENY → 下一次请求立即生效并 403；改回 → 恢复 | `bash logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-toggle-deny.sh` | exit 0，19/19 断言。撤权后 `allowedActions=["review_pass","review_reject","close"]`（兄弟动作仍在）、写端点 403 `FORBIDDEN` `detail.action=req.action.review_start`；还原后 168 格逐格 0 差异 |
| AC-3 | 10 角色 × 16 动作 × 3 关系与 r17 等价 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service -am test` | exit 0，248 tests / 0 fail。`RequirementActionMatrixWiringTest` 480 格逐格比对，差异集恰为 `{MGMT\|submit\|OWN, MGMT\|withdraw\|OWN}`（§8.3 的预期落点） |
| AC-4 | `start_dev` PRODUCT 仍不可、`submit_test` QA 仍可（真实 HTTP） | `bash logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-live-role-cells.sh` | exit 0，6/6：PDR/SCHEDULED 上 DEV 得 `[start_dev]` 而 PRODUCT 得 `[]`；RD/IN_DEV 上 QA 得 `[submit_test]` 而 BUSINESS 得 `[]` |
| AC-5 | 403 四键与审计「先落库再抛」不变 | `bash logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-contract-wiring-check.sh` + `make platform-test-negative` | 18/18（含 403 四键、审计新事务）+ negative 30/30（含 r17 动作级 5 条：越权 403 且不落库） |
| AC-6 | 门禁不缩水 | 见下表 | 9/9 全绿，全部不低于下限 |
| AC-7 | 依赖方向 `contract ← common`，端口零 Spring 依赖 | `grep -rn "ActionPermissionResolver" --include=*.java platform-contract platform-common req-service` | contract 只有接口 + 矩阵引用；唯一实现 `PermissionService.java:50`；contract 侧无 Spring/TenantContext 引用 |
| AC-8 | 改动面 6 改 1 新，无 pom/前端 | `git status --porcelain` | 6 M + 2 ??（新增接口 + 新增测试）；`pom.xml`/`package.json` diff 为空 |

## 3. 门禁实测（全部本轮亲跑，下限取 r20 订正后基线）

| 门禁 | 命令 | 下限 | 实测 |
|---|---|---|---|
| 编译 | `mvn -o -q -DskipTests compile` | exit 0 | exit 0 |
| 单测（req-service + 上游） | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service -am test` | 248（本轮只增） | 248/0 fail，BUILD SUCCESS |
| 平台单测 | `bash scripts/platform-unit.sh` | 306 | 316 通过 |
| RBAC | `make rbac-verify` | 111 | 111/111，`CANONICAL_HASH=1cb84118c0e798026eb2caa085bb29ef09c060392e121ce31cb6632c821bed5d`（与 r21 `ea21db0e` 不同属预期：2 动作 × 10 角色 ACTION 格变化） |
| 访问 | `make access-verify` | 152 | 152/152（a0 29 / a1 8 / a2 64 / a3 10 / a4 16 / a5 25） |
| 平台 | `make platform-verify` | 167 | 167/167 |
| 负向 | `make platform-test-negative` | 28 | 30/30 |
| 静态 | `make lint` | 22，L19 残留 2→0 | 22/22，L19 绿（直连常量矩阵残留 0） |
| 冒烟 | `make verify` | 14 | 14/14 |
| 预设重置 | `make platform-demo-reset` | 57 | 57/57（授权行 1680、自定义角色 0、被改过的格回预设） |

## 4. 接口一致性（0a，静态核对，无需运行时）

| 调用点 | 目标成员 | 实测 |
|---|---|---|
| `platform-common/.../PermissionService.java:50` | `implements ActionPermissionResolver` | 接口在 `platform-contract/.../ActionPermissionResolver.java:31` 存在，`@FunctionalInterface`，`decide(String menuCode, String actionCode)` 返回 `PermissionMatrix.Access` |
| `platform-common/.../PermissionService.java:99` | `public Access decide(String menuCode, String actionCode)` | 签名与接口逐字一致（`@Override` 生效，编译通过）；方法体未改（仅加 `@Override`） |
| `platform-contract/.../RequirementActionMatrix.java:248` | `resolver.decide(PAGE, code)` | 入参序 = (页面码, 动作码)，与实现一致；`code` 来自 `actionCode(action)`（`req.action.` 前缀） |
| `req-service/.../RequirementService.java:70,81` | 构造注入 `ActionPermissionResolver actionPermissions` | 字段 + 构造参数同类型，`authorize`(:629-632) 与 `allowedActions`(:650-652) 两处传同一 bean |
| `platform-contract/.../ActionRegistry.java`（两行 functionCodes） | `start_dev→req:deliver`、`submit_test→test:quick` | 与 `RbacSeeder.java:181-182` 的 `accessForAny(role, functionCodes)` 公式对齐；`RequirementActionMatrixTest` 的 `everyActionIsRegisteredAndPartitioned` 钉住两行与评审侧 7 动作 |

- 全仓 `new RequirementService(` 0 处（构造参数追加在末尾不破坏调用点）；矩阵 `can/filter` 已无 3 参调用点，全部 4/5 参。
- 页面码四处字面量一致：`RequirementActionMatrix.PAGE="req.pool"`、`MenuRegistry.java:57` 页面行（`functionCodes=List.of()`）、`ActionRegistry` 18 条需求动作的 `menuCode`、`PermissionRouteTable.java:136` 的 `bodyAction("req.pool","req.action.")` —— 由 `everyDecisionAsksTheRequirementPage` 一条断言钉死，不各写一份。

## 5. 架构核对（M3 质量门）

| 蓝图要求 | 实测 | 判定 |
|---|---|---|
| 端口出在 contract、实现在 common（依赖方向 `contract ← common`） | 接口 `ActionPermissionResolver` 在 contract；`PermissionService` 在 common 只加 `implements`，`decide` 零改动（未借机重写三序） | 符合 |
| 矩阵只留关系语义 + 动作码前缀，不再读功能码表 | `REQUIRED_FUNCTION/requiredFunction` 已删；`ACTION_PREFIX="req.action."`；`REVIEW_SIDE` 显式 7 个（不从注册表派生，防静默放大） | 符合 |
| 唯一判定点，两处调用共用同一 bean（请求级 memo 不退化） | `authorize`/`allowedActions` 都传注入字段；无 `new` 实例 | 符合 |
| 无重复实现/无第二套判定语义 | 生产侧 `union` 只在 `PermissionService`；测试桩只复刻种子公式（`accessForAny` + `union`），未长出第二套语义 | 符合 |
| 页面码单一真相 | 四处字面量由一条测试断言钉住 | 符合 |
| 未见既有结构破坏 | `TenantContext.can/access`、`PermissionMatrix.accessOf` 在业务代码 0 处；L19 残留 0 | 符合 |

## 6. 缺陷表

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-1 | P3 | 版本控制 | `git status --porcelain` 查看 `scripts/rbac-verify.sh` | 交付面 = TECH-CHANGE §2 的 6 改 1 新，其他改动应在提交说明里显式登记 | 该文件以 `100644→100755` 的模式变更**已 staged**（修好此前 `make rbac-verify` 的 Permission denied），属交付面之外的一行改动，未在提交说明中登记 | AC-8 | `git status --porcelain \| grep rbac-verify` | 提交说明中出现该模式修复的一句说明（或该改动移出本提交），且后续 round 不再把它当越界改动 |
| D-2 | P3 | 文档（memory 基线） | 读 `docs/teamflow/memory.md` 门禁基线行 | 基线数字与当前实测一致或标注「只增不降」 | 该行仍记 `platform-test-negative` **28**，本轮实测 **30**（r21/r22 各增） | AC-6 | `grep -n "platform-test-negative.*28" docs/teamflow/memory.md` | 下次订正基线时把 28 改为 30，或在同句注明「28 为 r20 实测，后续只增」 |

P0/P1/P2：未发现。以下三条为**说明项**，不入缺陷表：

- `r22-toggle-deny.sh` 里「拦截器级 403 的 detail 不含 `allowedActions`，按缺失即满足」是对脚本自身限制的让步（服务层四键的 `allowedActions` 不存在于拦截器报文），已在脚本文件头声明偏离；不影响结论，后续可改为直接断言服务层 detail。
- `docs/teamflow/memory.md` 的 r22 主题行已按同主题替换（原 r21 行加「r21 起，r22 收尾」并新增 r22 行），未新增 changelog 式噪声。
- 本需求无前端改动，故「工艺级（craft）」一项 N/A。

## 7. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 工具 |
|---|---|---|
| 需求详情页按钮随档位实时增减的视觉表现 | 以 pm/dev1/qa1 登录，按 §2 AC-4 的顺序翻 `req.action.start_dev`/`submit_test` 档位，观察按钮出现/消失与「403 静默提示」样式 | 真实浏览器（本 sandbox 内 Chromium crashpad 受限，无法启动） |
| 45s 快照 TTL 与多实例下的生效时序 | 双实例 + 改档位后立即并发请求，确认「下一次请求即生效」非窗口期偶然 | 客户环境压测脚本 |
| 多租户隔离（动作格按租户独立） | 第二租户登录同角色对比 `allowedActions` | 真实双租户环境 |

## 8. 证据与复现

- 检查器（本 run 归档，均未提交，`logs/` 已被 `.gitignore:19` 忽略）：
  - `logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-toggle-deny.sh` — 两向档位 e2e（19 断言，`trap` 兜底还原）
  - `logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-live-role-cells.sh` — 逐角色真实 HTTP allowedActions（6 断言，本轮 QA 新增）
  - `logs/teamflow/tf-mwio9710-zvbc2p/scripts/r22-contract-wiring-check.sh` — 静态接线契约（18 项）
  - `logs/teamflow/tf-mwio9710-zvbc2p/r22-verification-notes.md` — 两向证据与门禁数字
- 提交面卫生：`git status --porcelain` 无 `target/`、`.m2/`、`node_modules/`、IDE/密钥类噪声；`git check-ignore` 确认 `.gitignore:5 target/`、`.gitignore:16 .m2/`、`web/.gitignore:1 node_modules/`、`.gitignore:19 logs/` 均已覆盖。
