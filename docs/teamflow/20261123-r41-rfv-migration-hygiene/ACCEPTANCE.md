<!-- meta: summary="r41 验收：核心判据修复成立（rfv 44/44、gate-order 21/21、static 12/12、矩阵 20/20、第 21 条 4 变异全红），但因台账与 memory 两处文档口径返工，判定有条件通过。" -->

# 验收报告 · r41 门禁迁移判据卫生

- 任务夹：`docs/teamflow/20261123-r41-rfv-migration-hygiene/`（基线：`docs/teamflow/20261123-r40-req-change-order/`）
- 分支：`feat/rfv-migration-hygiene` · HEAD `990874d` · `merge-base(main, HEAD) = 990874d`（main 当前同为 `990874d`，本分支尚未提交；区间为空）
- 交付形态：`release-flow-verify` 判据修正 + `gate-order-verify` 静态自证 + 团队文档（无 Java / Vue / Flyway 迁移 / pom / lockfile 变更）
- 验收日期：2026-11-23

## 1. 总体判定

核心目标已达成：第 32 条迁移判据不再依赖「本轮是第几个迁移」，版本上界由 `mig_max_version` 从 base 提交树派生，加迁移与改判据解耦；第 21 条静态契约可执行地上锁，且经变异证明非永真。本次验收独立复跑了关键门禁（rfv 44/44、gate-order 21/21、static 12/12、lint 24/24、判据矩阵 20/20、第 21 条 1 正 4 变），全部通过。

但交付夹带了两处未按技术单声明同步的文档改动（`DELIVERED-MODULES.md` 台账口径、`memory.md` 锁文件口径），其中台账新增的合并状态陈述与仓库事实不符，故判 **有条件通过**（返工项仅文档，不动脚本）。

## 2. 本次验收独立复跑证据（不引用 QA 结论）

| 命令 | 实测结果 |
|---|---|
| `bash -n scripts/release-flow-verify.sh scripts/gate-order-verify.sh` | exit 0，语法过 |
| `bash scripts/release-flow-verify.sh --static-only` | 12/12，`RELEASE_FLOW_STATIC_TOTAL=12 PASS=12 FAIL=0` |
| `bash scripts/release-flow-verify.sh` | 44/44，exit 0；`[32] PASS` 打印 `HYG_SELF[1/5]..[5/5]` 五条 + 汇总，`迁移 added=0`、`base_max=V16`、`判据自检 5/5` |
| `bash scripts/gate-order-verify.sh` | 21/21，exit 0；静态契约 6 条含第 21 条 OK；A 序列 reset 80→rfv 44→access 206×2；B 序列 reset→access 206→rfv 44→access 206→rbac 113；收尾 5 条含 2 条负向自检 0→1→0 |
| `bash logs/teamflow/tf-mwt6l1c8-58vacr/scripts/qa-r41-hygiene-matrix.sh` | `QA_MATRIX PASS=20 FAIL=0`（含 `MIG_MAX=16`、坏 rev fail-closed、空区间绿、合法 V17 绿、V18 跳号红、package-lock 红、rc=2 不假绿、字段形状） |
| `bash logs/teamflow/tf-mwt6l1c8-58vacr/scripts/r41-gate21-probe.sh` | real→HIT；拆 `mig_max_version`/`ls-tree -r`、注入 `mig_allowed=`、删 `HYG_SELF[3/5]`、回旧文案四路全 MISS |
| `bash scripts/tests/lint.sh` | 24/24 |
| `grep -c 'HYG_SELF\[' scripts/release-flow-verify.sh` | 5（恰五处字面量） |

> gate-order 自带 reset/access/rbac 段，故 80/206/113 亦被本次独立复跑覆盖；`platform-verify 194`、`platform-test-negative 130`、`change-verify 42`、`unit 475`、`web build` 采用 QA 实测记录（本验收未重复跑，未发现反证）。

## 3. 逐条验收（判据取自 TECH-CHANGE §1/§3/§5/§8 + 架构蓝图）

| 编号 | 验收判据 | 证据 | 结果 |
|---|---|---|---|
| AC-1 | 判据内无写死允许版本/允许文件名；版本上界从 base 提交树派生 | 源码 :349 `mig_max_version`（`git ls-tree -r --name-only <rev>`），:367–422 无 `mig_allowed=`、无「只允许新增 V1x」；`grep -cE '只允许新增 V1\[0-9\]|mig_allowed='` = 0；矩阵 V17/V18/V0 三向用例 | PASS |
| AC-2 | 判据是区间纯函数（只读 base..head 对象，不读工作树/status），出参 0/1/2，输出契约不变 | `check_commit_hygiene` 仅用 `git diff`/`ls-tree`；矩阵「脏工作树 + 未跟踪迁移 → 绿」；违规行恰 3 个 TAB 字段；末行 `MIGRATION added/base_max/expected`；坏基线 rc=2 `base_max=none` | PASS |
| AC-3 | 迁移规则：0/1 条 `A`、`^V[0-9]+__[a-z0-9_]+\.sql$`、恰 1 条时 `N == base_max+1`；`M/D/R` 红；锁文件四 pattern | 源码 :396–422；矩阵：`M/D` 红、一次 A 两条红、跳号 V18 红、非法名/`R__` 红、`A package-lock.json` 红、空区间绿、`V1` 于空基线绿 | PASS |
| AC-4 | 自检 1 正 4 反共 5 条，`m` 由 `mig_max_version` 现算，`HYG_SELF[k/5]` 为字面量且恰 5 处 | 源码 :426–524；实跑 5 条全打印、汇总 5/5；`grep -c` = 5；自检内无写死版本号（`m` 现算） | PASS |
| AC-5 | 第 32 条文案去版本号、pass 时打印 5 行自检；分母 44 / 静态 12 不变 | 实跑 `[32]` 输出；`RELEASE_FLOW_TOTAL=44`、`RELEASE_FLOW_STATIC_TOTAL=12` | PASS |
| AC-6 | gate-order 第 21 条四组断言齐备（禁旧文案、禁 `mig_allowed`、必含派生锚点、5 标记齐全）；分母 20→21，静态 5→6，声明一致 | 源码 :193–216；五处声明 `:25/:96/:434/:435/:438` 均为 21；实跑 21/21；变异探针 4 路 MISS 证明非永真 | PASS |
| AC-7 | memory 同主题行内替换、不新增行、幂等，判据口径与条数同步 | `git diff docs/teamflow/memory.md`：2 行替换（判据卫生行、门禁基线行），净新增 0 行；判据行含 `mig_max_version`、5 条自检、gate-order 21 | PASS（另见 R2） |
| AC-8 | 影响面受控：不改 pom/package/lock/Makefile/Java/Vue/迁移；本分支区间空、`added=0` | `git diff --name-only` 仅 2 脚本 + 2 文档；`git diff --name-only 990874d..HEAD` 为空；migration 目录 diff 0；pom/lock/Makefile 无 diff；`[32] added=0` | PASS |
| AC-9 | 回归门禁只增不减：rfv ≥44、gate-order ≥20、platform ≥194、negative ≥130、access ≥206、rbac ≥113、change ≥42、demo-reset ≥80×2、lint 24、bash -n | 本次复跑 rfv 44/44、gate-order 21/21、access 206×2、rbac 113、reset 80 多次、lint 24；QA 记录 platform 194、negative 130、change 42、demo-reset 80×2、unit 475 | PASS |
| AC-10 | 架构蓝图符合：`mig_max_version` 留 rfv 不抽 `scripts/lib/`，gate-order 锚点可 grep；无新增模块/重复实现 | :349 函数在原地；删锚点变异 → MISS；无新文件、无新重复实现；3 处硬编码 16 属蓝图已登记下轮债务，本轮未扩大 | PASS |
| AC-11 | 交付说明、台账与工作树一致；git 卫生（分支、任务夹、无污染） | `git status`：4 改 + 任务夹；但 dev 主说明称 `DELIVERED-MODULES.md`「本轮不更新」而实际修改（QA-R41-01），且台账新增口径与 main 事实不符（R1）；memory 锁文件口径自相矛盾（R2） | **FAIL（文档）** |

## 4. 架构一致性（M3）

| 蓝图要求 | 核对 |
|---|---|
| 判据是区间纯函数（只读两个提交对象） | ✓ 矩阵「脏树 + 未跟踪迁移仍绿」实证 |
| 版本上界从 base 树派生，判据无写死版本 | ✓ `ls-tree -r` + V17/V18/V0 三向；`grep` 无旧文案/常量 |
| `mig_max_version` 留在本文件（gate-order 第 21 条要 grep 字面量） | ✓ 未抽库，锚点在位，删锚点变异红 |
| gate-order 新增第 21 条、分母 21、静态 6 | ✓ 五处声明一致，实跑 21/21 |
| 只动两个脚本 + memory 两条事实行；`DELIVERED-MODULES.md` 本轮不更新 | ✗ 实际改了 `DELIVERED-MODULES.md`，且内容含不实陈述（见 R1）——这是本次判「有条件通过」的唯一结构原因 |
| 不新增重复实现 | ✓ 未新增；既有 3 处硬编码 16（`change-verify.sh:311/:371`、`tests/rbac/s0-schema-migration.sh:134-137`）按蓝图留待下轮 |

## 5. 返工项（复验后即可关闭）

| 编号 | 严重级 | 位置 | 要求 |
|---|---|---|---|
| R1 | P3 | `docs/teamflow/DELIVERED-MODULES.md` | 二选一：①（推荐）保留数值同步（gate-order 21、change-verify 42、unit 475、lint 24 均与实测一致），但修正两处事实：`main 当前 = 990874d`（不是 `d99530d`），且 r39（`b7ab1ab`）、r40（`990874d` 合并提交）**已合入 main**——删除「r39 起含未合 main 的轮次」口径、把对应行改为已合 main；②按蓝图「本轮不更新」整文件回退到 HEAD。无论哪种，交付说明须如实记录该文档是否改动 |
| R2 | P3 | `docs/teamflow/memory.md` 门禁判据卫生行 | 该行仍写「不存在 `web/package-lock.json`，写成后者等于一条永远为空的判据」，与 r41 新增的 `*package-lock.json` 防御性 pattern（自检第 5/5 条会新建该文件证明非空判据）矛盾。同主题行内替换补齐一句，不新增行 |

> 复验范围：仅 R1/R2 与 `git status`；AC-1～AC-10 已由本次实跑与变异证据锁定，无需重跑。

## 6. 已知偏差（经决策接受，不返工）

1. `Makefile:107` 仍写「gate-order-verify（20 条）」——TECH-CHANGE §2 明令不动构建入口；建议下一轮单行同步为 21。
2. `hygiene_self_test` stdout 6 行（5 条标记 + 1 行汇总）vs 技术单接口文字「5 行」——唯一消费方按行 grep 字面量，无行为影响。
3. 迁移版本知识仍 3 份副本（见 AC-10）——蓝图已列下轮债务，本轮明确不扩范围。
4. 新增 `*package-lock.json` pattern 属防御性收紧（当前仓库无该文件），符合 TECH-CHANGE §7-3。
5. `docs/teamflow/DELIVERED-MODULES.md` 与 memory 对同一事实的双份陈述，长期应收敛为一处（本轮 R1 修正后仍建议下轮彻底去重）。

## 7. 生产可用性判断

无用户可见变更（无 HTTP/UI/DB 行为改动），无需灰度与回滚演练；回滚即 revert 两脚本提交。`hygiene_self_test` 增加一个临时 worktree 与 4 次提交，`[32]` 约 +3~6s，可接受；`git worktree` 不可用时 fail-closed 红，不提供静默跳过。

验收结论：⚠️ 有条件通过
