# r15 演示数据重置污染 · QA 报告（round-2 复验）

- 任务夹：`docs/teamflow/20261113-r15-demo-reset-pollution/`；分支 `feat/demo-reset-pollution`，HEAD `a41b48d`；被测改动 6 个文件未提交。
- 环境：本地全栈（gateway 8080 / req 8082 / deliver 8083 / MySQL `devops_platform`）；req-service 运行 jar 与修复源码同为 09:20 前后构建，本轮所有断言均打到该进程。沙箱 workspace-write，浏览器不可启动（§8）。
- **结论：可验收**。round-1 的 D-01/D-02（P2）已按原 Check command 复跑关闭：覆盖脚本由 2 通过/5 失败 → **11/11**；新增行为探针真实造残留再重置 **33/33**。全部门禁全绿（unit 262、platform-verify 167、negative 25、access 145、analytics 74、lint 18、verify 14、s7 31、ops reset 49）。仅剩 1 条 P3 观察（不阻断）。

## 1. 复验方法

1. 先**逐条重跑** round-1 留下的全部 checker（`logs/teamflow/tf-mwe8jigl-47q6tw/scripts/` 7 个），不替换、不改写判据，结果见 §2。
2. 再补前一轮**没有覆盖到的面**：D-01/D-02 的修复此前只被证明「库里本来就没有残留时计数为 0」，没有证明「真的有残留时会被删、且种子/人工行不会被误删」。新增 `qa-r15-residue-fix-probe.sh` 真实插入 4 类行后调用内部重置（§3）。
3. 最后在**门禁真实污染之后**再跑一次重置，证明终态不是「脚本跑在干净库上」的假绿（§2 T3、§3 末）。

## 2. 回归契约逐项重跑（原始 checker，未改写）

| 编号 | 用例 | 命令（`logs/teamflow/tf-mwe8jigl-47q6tw/scripts/`） | 结果 |
|---|---|---|---|
| T1 | 真实制造污染（报表/pin/client/执行器 + 弄脏两行种子执行器） | `bash .../qa-r15-pollution-seed.sh` | **PASS 11/11**（exit 0） |
| T2 | 重置终态独立复核（4 表归零、executor=2 两 code、6 基数键、API 三面） | `bash .../qa-r15-post-reset-verify.sh` | **PASS 31/31**，本轮两个时点各一次 |
| T3 | ops 脚本真跑（内含连跑两遍重置验幂等） | `bash scripts/platform-demo-reset.sh` | **PASS 49/49 ×2**（门禁污染前 / 污染后各一次） |
| T4 | 静态契约 + 接口一致性（record/工厂/签名、调用点、无第二份表清单、23 个 counts 键全由服务端产出） | `bash .../qa-r15-static-contract.sh` | **PASS 59/59** |
| T5 | 全表覆盖审计（round-1 的失败项） | `bash .../qa-r15-table-coverage.sh` | **PASS 11/11**（round-1：2 通过/5 失败，exit 1） |
| T6 | 脚本契约（21 键 ↔ counts、无前后快照、keepFilter 与 code 字面量一致） | `bash .../demo-reset-script-contract.sh` | **PASS 25/25** |
| T7 | 重置后 API 只读探针（`/reports` total、`/portal/config` cards） | `bash .../demo-reset-api-probe.sh` | **PASS 5/5** |
| T8 | 单测（含 `DemoResetRetentionTest` 5/5） | `bash scripts/platform-unit.sh` | **PASS 262/262**（exit 0） |
| T9 | 平台回归 AC-1..AC-20 | `bash scripts/platform-verify.sh` | **PASS 167/167 ×2** |
| T10 | 平台负向 | `bash scripts/platform-test-negative.sh` | **PASS 25/25** |
| T11 | 角色×页面准入 | `bash scripts/access-verify.sh` | **PASS 145/145** |
| T12 | 分析域中文化（live 段报表被清 0 时自建 3 张夹具） | `bash scripts/analytics-i18n-verify.sh` | **PASS 74/74（live 41/41）** |
| T13 | lint | `bash scripts/tests/lint.sh` | **PASS 18/18** |
| T14 | r1 四步之验证 | `bash scripts/verify.sh` | **PASS 14/14** |
| T15 | 执行器状态机（会弄脏 STUB-LOCAL） | `bash scripts/tests/collab/s7-test-data-executor.sh` | **PASS 31/31** |

## 3. 本轮新增面（前一轮漏掉的正是「修复的行为证据」）

`bash logs/teamflow/tf-mwe8jigl-47q6tw/scripts/qa-r15-residue-fix-probe.sh` → **PASS 33/33**（exit 0）。它先插入：1 行派生 `test_strategy`（挂在现有 release 上）、`wf_definition` 残留 `verify-qa-probe-*` + 人工行 `QA人工流程-*`、`test_module` 残留 `验证模块QA-*` + 人工行 `QA人工模块-*`、`test_env` 残留 `QA环境-QA*` + 人工行 `QA人工环境-*`；再调内部重置，逐项断言：

- 残留全删（4 类 → 0），`test_strategy` 由 1 → 0（证明整表清空，不是「本来就没有」）；
- 种子与人工行**全部保留**：wf 5 行、module 4 行、env 2 行（= 基线 + 人工），证明 `purgeRunTagResidue` 的 `NOT(...)` 取反关系正确、没有「删种子留探针」；
- 响应新键真实存在且值正确：`testStrategies=0`、`testEnvs=2`（种子 1 + 人工 1）、`gateResidue=0`；**人工 env 行不会触发 409**（符合「testEnvs 不进不变量」的设计）；
- 静态契约：`test_strategy` 在 `release` 之前、工厂存在、keepFilter 为取反、Service 以 `AND NOT (...)` 应用。
- 首跑为 27 通过/5 失败，定位为**探针自身缺陷**（`wf_definition` 有 `uk_wf_def(tenant_id,biz_type,req_type,domain_id,version)` 唯一键，人工行需换 version；env 人工行漏插），修正探针后 33/33；4 条真实行为断言首跑即通过，交付代码未因此改动。

**门禁污染后重置（关键终态证据）**：先跑 T9/T10/T15 制造真实残留，查库 `wf 9 行/残留 5、module 6/3、reports 4、clients 2、executors 3（1 行脏）`；再跑 `platform-demo-reset.sh` → **49/49**，随后查库 `reports 0、portalCards 0、portalPins 0、clients 0、executors 2（LEGACY-OFF enabled=0、STUB-LOCAL enabled=1、report_count=0、last_report_at=NULL）、wf 4/0、module 3/0、env 1/0、strategy 0`。

**口径差异（非缺陷）**：修复摘要称 `platform-verify 168/168`，本轮两次实测均为 `167/167`（0 FAIL，AC-1..AC-20 全覆盖）；差 1 属计数快照口径，不影响判据，建议修复方在摘要中改为「以脚本实测总数为准」。

## 4. 缺陷复验对照（仅对照，不是本轮新缺陷）

| 编号 | round-2 级 | 模块 | 原判据 | 复验结果 |
|---|---|---|---|---|
| D-01 | 已关闭（原 P2） | 演示重置 / 覆盖审计 | `qa-r15-table-coverage.sh` exit 0 且 test_strategy/test_env/孤儿断言 ok | 现 **11/11 exit 0**；`test_strategy=0`、`test_env` 残留 0 且种子 1 行；且新增行为探针证明「有残留 → 被删」 |
| D-02 | 已关闭（原 P2） | 演示重置 / 覆盖审计 | 同脚本 wf/module 无门禁 RUN_TAG 残留 | 现 **11/11 exit 0**；污染后 wf 5 条残留、module 3 条残留 → 重置后 0，种子/人工行保留 |
| P3-01 | 仍开放（P3） | 演示重置 / 执行器基线 | 静态：`CASE code WHEN 'STUB-LOCAL' THEN 1 ELSE 0 END` 与 keepFilter 的 code 集合是两份字面量 | 代码未变；`ELSE 0` 会把未来新增的 keepFilter code 静默停用。不阻断验收 |

## 5. 架构与接口一致性（0 / 0a）

- **蓝图符合**：无新模块；`TableReset` 只在 `req-service/src/main/java/com/example/req/seed/DemoFixture.java` 定义一份，`DELETE_ORDER` 50 项仍单点有序；`DemoResetService.deleteBusinessData`（`:272-275`）用 `for (DemoFixture.TableReset reset : ...)` + `" AND NOT (" + reset.keepFilter() + ")"` 消费；`DemoResetController` 零改动；依赖方向 脚本→HTTP→Controller→Service→Fixture 未变。
- **无重复实现**：全仓 `grep -rn DELETE_ORDER` 除定义/测试/注释外无第二份表清单；bash 侧无 `PRE_CLIENTS`/前后快照、无 `for t in <表清单>`，只有「counts 键 + 字面量期望」的薄驱动断言（跨进程边界允许）。
- **接口一致性**：`TableReset.table()/keepFilter()`、`purge/purgeExcept/purgeRunTagResidue`、`RESTORE_KEPT_EXECUTOR_BASELINE`、`NOTIFICATION_COUNT=4` 均存在且调用形状匹配（`javac` 编译 + 262 单测 + 59/59 静态契约三重证明）；脚本断言的 23 个 counts 键逐一对应服务端产出，无跨进程契约破裂。
- **单点性复核**：`test_strategy` 在 `release` 前（静态 + 行为双证）；4 张新表顺序 `portal_card_config`→`report_definition`、`test_executor`→`api_client` 符合子先父后。

## 6. 缺陷表（仅列本轮仍在开放的缺陷）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| P3-01 | P3 | 演示重置 / 执行器基线还原 | 读 `req-service/src/main/java/com/example/req/seed/DemoFixture.java:70-73`；若将来给 `test_executor` 的 keepFilter 增加新 code | 基线还原与保留集合共用一份 code 清单，新增 code 不会静默失配 | `enabled = CASE code WHEN 'STUB-LOCAL' THEN 1 ELSE 0 END` 硬编码单码，新增 keepFilter code 会被置 enabled=0 | TECH-CHANGE §2（test_executor 保留） | `! grep -q "CASE code WHEN 'STUB-LOCAL' THEN 1 ELSE 0 END" req-service/src/main/java/com/example/req/seed/DemoFixture.java` | 命令 exit 0（即 CASE 不再硬编码单一 code，改为按种子 code 集合生成） |

## 7. 提交面卫生（0c）

`git status --porcelain` 仅 6 个 M（`docs/teamflow/memory.md`、`DemoFixture.java`、`DemoResetService.java`、`DemoResetRetentionTest.java`、`scripts/platform-demo-reset.sh`、`scripts/tests/analytics/i18n-live.mjs`）+ 任务夹未跟踪；无依赖目录/构建产物/缓存/IDE/密钥。`--ignored` 已覆盖 `.m2/`、`target/`、`web/dist/`、`web/node_modules/`。**无 P1**。

## 8. 人工补测清单（环境限制，非交付缺陷）

1. **浏览器视觉走查**：重置后 `/reports` 空态、`/portal` 回退 4 张角色模板卡（无个人 pin）、`/admin/workflows` 与用例模块树只剩种子。沙箱 Chromium/Edge 因 crashpad 需 `PROCESS_SET_INFORMATION`/`PROCESS_SUSPEND_RESUME` 无法启动（`--no-sandbox`/`--disable-breakpad` 实测无效）。方法：本机 Chrome，`make platform-up && make platform-demo-reset` 后人工比对。
2. **事务回滚故障注入**：本轮仍未做破坏性注入（需可控制造插入冲突或长事务锁）；代码级已确认 `reset()` `@Transactional`、不变量失败抛 409 且整体回滚。方法：对 `requirement` 加写锁后调内部端点，确认 409 后库内无「删一半」。
3. 性能/动画：本需求无 UI 与动画变更，N/A。

## 9. 结论

- **可验收性：可验收**。D-01/D-02 按原 Check command 复跑关闭（覆盖脚本 11/11，较 round-1 的 2/5 全绿），并用真实残留注入证明修复行为正确（33/33）；4 张点名表、6 个新计数键、通知精确 4、executor 2 行 + 基线还原、幂等（多轮共 5 次重置）、双门 401/200 全部保持。
- 剩余仅 P3-01（基线 CASE 硬编码），不阻断；建议在下次触碰 `test_executor` 时改为按种子 code 集合生成。
- 证据脚本：`logs/teamflow/tf-mwe8jigl-47q6tw/scripts/qa-r15-{pollution-seed,post-reset-verify,static-contract,table-coverage,residue-fix-probe}.sh`、`demo-reset-{script-contract,api-probe}.sh`；载荷 `logs/teamflow/tf-mwe8jigl-47q6tw/captures.json`。
