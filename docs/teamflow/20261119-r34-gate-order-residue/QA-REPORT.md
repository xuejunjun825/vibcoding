# QA-REPORT — r34 门禁顺序无关（gate-order-residue）· 第 2 轮复验

- 任务夹：`docs/teamflow/20261119-r34-gate-order-residue/`
- 分支：`feat/gate-order-residue`（HEAD `4d9f5b6`，未提交）
- 轮次：**fix 后复验（round 2）**，上一轮 1×P2（D-1）+2×P3（D-2/D-3）
- 结论：**可验收**。三个缺陷全部按原判据关闭，**0 个缺陷仍开放（P0/P1/P2/P3 均 0）**；A/B 双序列 15/15 复跑通过，收尾基线与起跑一致。
- 复验方式：先原样重跑上轮留下的两个检查器（6/6、8/8），再逐条执行各缺陷「检测命令/通过判据」，最后跑完整回归门禁。

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 被测交付 | 7 个修改 + 2 个新增：`scripts/lib/gate-fixture.sh`(新)、`scripts/gate-order-verify.sh`(新)、`platform-demo-reset.sh`、`release-flow-verify.sh`、`platform-test-negative.sh`、`platform-verify.sh`、`Makefile`、`scripts/tests/lint.sh`、`docs/teamflow/memory.md` |
| fix 轮改动面（按 mtime 核实） | 仅 3 个文件：`scripts/lib/gate-fixture.sh`(19:00)、`scripts/gate-order-verify.sh`(19:00)、`scripts/tests/lint.sh`(18:59)；negative/verify 未再改 |
| 明确不在范围 | Java / 前端 / `pom.xml` / `db/migration`（`git diff --name-only HEAD` 实测 0 命中） |
| 环境 | macOS；演示栈已就绪（网关 `/actuator/health`=200，discovery 含 api-gateway/req/deliver/demo），租户 t1 |
| 浏览器 | 不可启动（宿主 token 限制致 Chromium crashpad 自终止，非交付缺陷）；本交付无 UI 改动，无视觉断言项 |
| 数据基线 | 起跑/收尾均：DEMO-R-01..12、DEMO-R-02 汇总 5.00、非夹具需求 0、DEMO-R-04 回 DEMO-REL-1 |

## 2. 上轮检查器原样复跑（回归契约）

| 检查器 | 命令 | 本轮结果（原文） |
|---|---|---|
| 夹具接口契约 | `bash logs/teamflow/tf-mwnefs38-150e8l/scripts/qa-r34-fixture-contract.sh` | `PASS=6 FAIL=0`，rc=0（上轮 5/1；第 4 项「单独 source」已由 FAIL→OK） |
| 收尾置红隔离桩 | `bash logs/teamflow/tf-mwnefs38-150e8l/scripts/qa-r34-teardown-red.sh` | `PASS=8 FAIL=0`，rc=0（成功/失败/rc=3 保留/SIGINT→130/调试口全绿） |

## 3. 回归门禁（本轮实跑）

| # | 检查项 | 命令 | 结果 |
|---|---|---|---|
| 1 | 静态体检 22/22 | `bash scripts/tests/lint.sh` | 通过；L1 已含「source 目标存在」 |
| 2 | L1 新断言可证伪（防「恒绿检查」） | 复制 `scripts/` 到 `/tmp`，注入一条指向不存在文件的 `source` 后跑副本 lint | 通过：`err [1] … source 目标不存在: scripts/bad-source-probe.sh -> scripts/definitely-missing.sh`，rc=1（临时树已删除） |
| 3 | rfv 静态自检 11/11 | `bash scripts/release-flow-verify.sh --static-only` | 通过 `RELEASE_FLOW_STATIC_TOTAL=11 PASS=11 FAIL=0`；第 11 条断言钩子形状 |
| 4 | **顺序无关证明 15/15** | `bash scripts/gate-order-verify.sh` | 通过：A `reset 75/75 → rfv 44/44 → access 206/206 → access 206/206`；B `reset 75/75 → access 206/206 → rfv 44/44 → access 206/206`；每步均断言自身分母 + `gate_fixture_problems` 为空；收尾 `本轮足迹 0、非夹具需求 0、演示不变量成立` |
| 5 | platform-verify ≥194 | `bash scripts/platform-verify.sh` | 通过 `194/194`，收尾基线干净 |
| 6 | negative ≥130 | `bash scripts/platform-test-negative.sh` | 通过 `130/130`，收尾基线干净 |
| 7 | rbac 113/113 | `bash scripts/rbac-verify.sh` | 通过 `113/113`（11 段下限核对） |
| 8 | demo-reset 75/75 + 新断言 | `bash scripts/platform-demo-reset.sh` | 通过 `75/75`；`PASS [34] 库内非夹具需求 0 行（门禁夹具不得留在演示租户）` |
| 9 | 提交面卫生 | `git status --porcelain` / `git ls-files --others --exclude-standard` | 通过：7×M + 任务夹 + 2 个新脚本；无 `node_modules/target/dist/.env/logs` |
| 10 | 改动面 | `git diff --stat HEAD` | 通过：仅 scripts/Makefile/docs；无 Java/web/pom/迁移 |
| 11 | Makefile 可发现 | `make help \| grep gate-order-verify` | 通过：目标 + 「15 条，独占演示栈」帮助行 |
| 12 | mvn ≥392 | 未重跑 | 本轮 fix 只改 3 个 shell 文件，Java 树与上轮 392/0 完全相同（`git diff` 无 Java/pom）；不重复消耗 |

## 4. 缺陷逐条复验（fix 前 → fix 后）

| 编号 | round-2 级 | 原判据 / 检测命令 | 复验结果 | 状态 |
|---|---|---|---|---|
| D-1 | P2 → 关闭 | `bash logs/teamflow/tf-mwnefs38-150e8l/scripts/qa-r34-fixture-contract.sh; echo rc=$?` 要求 `PASS=6 FAIL=0` 且 rc=0 | 本轮 `PASS=6 FAIL=0`，rc=0；`qa-r34-fixture-contract.sh:40` 单独 source 无输出，`gate-fixture.sh:33` 已改为 `scripts/lib/../lib.sh`，缺失/版本不符时显式 `return 1`（:34-43） | **已修复** |
| D-2 | P3 → 关闭 | 读 `gate-fixture.sh` 注释与 `negative/verify` 的 tag 用法 | 注释口径已订正：:78-82 明确「历史/其余夹具用各自 RUN_TAG，不在本项口径内，由 demo-reset 整租户 purge + 第 2 项兜底」；与 `negative:111,117` 带 GATE_TAG、其余带 RUN_TAG 的实际一致 | **已修复（文档口径）** |
| D-3 | P3 → 关闭 | `TECH-CHANGE §3.2` 承诺的「禁止按状态取种子 PDR」静态断言 | `gate-order-verify.sh:70-79` 已实现：对三门口禁 grep 旧式 `type='PDR' AND status IN ('SCHEDULED','IN_DEV') ORDER BY id`，命中即红；本轮该静态契约 4/4 通过（含本项） | **已修复** |

> 未发现「复验后仍开放」的缺陷；上表仅作 fix 前后对照，不进入 §6。

## 5. 架构与接口核对（要求 0 / 0a）

- **抽取到位**：`scripts/lib/gate-fixture.sh` 是复位/基线/夹具原语的唯一实现；3 个写门禁均 `source scripts/lib/gate-fixture.sh` + `gate_fixture_begin`（`release-flow-verify.sh:610`、`platform-verify.sh:130`、`platform-test-negative.sh:52`），新门禁 `gate-order-verify.sh:59` 同。
- **禁止静默 reset**：三个写门禁内已无 `platform-demo-reset.sh >/dev/null` 与 WARN 降级；`rfv` 本地 `cleanup()` 已删除（:333 仅注释说明由陷阱接管），S7 起跑改 `gate_fixture_reset "S7 起跑"`（:1191）。
- **接口一致性（静态 grep，无运行）**：9 个公开函数定义齐全（`gate_fixture_problems/reset/assert_clean/end/begin/login_as/login_admin/approve_biz/pool_bus`）；调用点参数形状与定义一致——`gate_pool_bus <busId> [label]`（verify:265 / negative:115 各 2 参）、`gate_approve_biz <bizId> <bizType> [maxPasses]`（negative:124 两参，定义 `max="${3:-6}"`）、`gate_fixture_reset <label>`。钩子依赖的 7 个 `lib.sh` 成员均存在（契约检查第 3 项）。
- **单一基线定义点**：12/11/0 与计数口径仍只在 `platform-demo-reset.sh`（`TOTAL_CHECKS=75`）；钩子只断言它覆盖不到的 tag 足迹与跨门禁不变量，未另立口径。
- **重复实现收敛**：复位实现 3 份 → 1 份（rfv×2 + negative 无 trap → 共享钩子）；仍保留的 `rfv approve_biz`、`wbs-backfill-verify` 自有 trap 与技术单声明的债一致，非缺陷。
- 未发现重复实现漂移、该抽取未抽取或既有结构破坏导致的 P1 架构问题。

## 6. 缺陷表（复验后仍开放项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|

**未发现缺陷**（0 个仍开放）。

## 7. 人工补测清单

| 项 | 原因 | 方法/工具 |
|---|---|---|
| 真机 Ctrl-C 打断「真实长跑门禁」 | 隔离桩已覆盖 SIGINT→130 且仍复位（8/8），但沙箱内未在 `gate-order-verify` 4 分钟长跑上手工按 Ctrl-C | 终端跑 `bash scripts/gate-order-verify.sh`，中途 Ctrl-C，确认退出码 130 且随后 `gate_fixture_problems` 为空 |
| 浏览器端视觉复核 | 沙箱内 Chromium 无法启动（环境限制，非交付缺陷）；本交付无前端改动 | 人工在真机浏览器打开演示站复核，预期无差异 |

## 8. 结论

- **可验收**：D-1/D-2/D-3 全部按原判据关闭且各自有可执行证据（契约检查 6/6、注释口径一致、静态断言 4/4）；核心主张由 `gate-order-verify 15/15`（A/B 双序列、每步「自身分母 + 基线干净」双断言）复跑实证。
- 回归全部达标：rfv 静态 11/11、rfv 全量 44/44（A2/B3 各一次）、demo-reset 75/75、platform-verify 194/194、negative 130/130、access 206/206（4 次）、rbac 113/113、lint 22/22（含新 L1 的证伪测试）、mvn 392/0（同 Java 树，上轮已绿）。
- 交付面 7 M + 2 新脚本，无 Java/web/pom/迁移；提交面卫生通过，可单次 revert 回滚。
