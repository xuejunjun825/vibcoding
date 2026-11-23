# QA 报告 · r41 门禁判据卫生（release-flow-verify 迁移判据去硬编码）

- 任务夹：`docs/teamflow/20261123-r41-rfv-migration-hygiene/`（基线：`docs/teamflow/20261123-r40-req-change-order/`）
- 分支：`feat/rfv-migration-hygiene` · HEAD `990874d` · merge-base(main, HEAD) = `990874d`（本分支尚未提交，区间为空）
- 被测交付形态：Shell 门禁脚本 + 团队文档（无 Java / Vue / Flyway 迁移 / pom / lockfile 变更）
- 测试日期：2026-11-23

## 1. 结论

**可验收**。核心判据（迁移卫生 = 区间纯函数 + 版本上界从 base 提交树派生）经独立判据矩阵 20/20、变异自检 5/5、第 21 条静态契约 1 正 4 变、坏基线 fail-closed、收口提交预演五路证明；8 项门禁全部达标且 `platform-demo-reset` 连跑两次一致。未发现 P0 / P1 / P2 缺陷；仅 1 条 P3（交付说明与工作树不一致，不影响代码正确性）。

## 2. 范围与环境

| 项 | 内容 |
|---|---|
| 改动面（`git status --porcelain`） | `M scripts/release-flow-verify.sh`、`M scripts/gate-order-verify.sh`、`M docs/teamflow/memory.md`、`M docs/teamflow/DELIVERED-MODULES.md`、`?? docs/teamflow/20261123-r41-rfv-migration-hygiene/` |
| 栈 | 演示栈已起：网关 8080、req-service 8082（`/internal/health` → `{"status":"UP","db":"up","flyway":"16"}`）、deliver-service 8083 |
| 工具链 | macOS + bash；Maven 离线仓库 `-Dmaven.repo.local=$PWD/.m2/repository`；浏览器在沙箱不可用（本交付无 UI，不需要） |
| 日志目录 | `logs/teamflow/tf-mwt6l1c8-58vacr/`（复用 dev 的 `r41-hygiene-unit.sh`、`r41-gate21-probe.sh`，本轮新增 `qa-r41-hygiene-matrix.sh`） |

## 3. 用例与结果

### 3.1 门禁回归（TECH-CHANGE §5 下限，只增不减）

| 编号 | 用例 | 命令 | 结果 | 判定 |
|---|---|---|---|---|
| G1 | 两脚本语法 | `bash -n scripts/release-flow-verify.sh scripts/gate-order-verify.sh` | exit 0，SYNTAX_OK | PASS |
| G2 | rfv 静态段 | `bash scripts/release-flow-verify.sh --static-only` | 12/12，exit 0 | PASS（=12 条不变） |
| G3 | rfv 全量 | `bash scripts/release-flow-verify.sh` | 44/44，exit 0；`[32] PASS` 并原样打印 5 行 `HYG_SELF[1/5]..[5/5]`，`迁移 added=0` | PASS（≥44） |
| G4 | gate-order | `bash scripts/gate-order-verify.sh` | 21/21，exit 0；A 序列 reset 80→rfv 44→access 206，B 序列 reset→access 206→rfv 44→access 206→rbac 113，收尾 5 条含 2 条 0→1→0 负向自检 | PASS（≥20，实测 21） |
| G5 | 平台门禁 | `bash scripts/platform-verify.sh` | 194/194 | PASS（≥194） |
| G6 | 负向门禁 | `bash scripts/platform-test-negative.sh` | 130/130 | PASS（≥130） |
| G7 | 权限门禁 | `bash scripts/access-verify.sh` | 206/206（a4 19 / a5 25 / a6 25 / a7 16） | PASS（≥206） |
| G8 | RBAC 门禁 | `bash scripts/rbac-verify.sh` | 113/113（含 s2r 11/11） | PASS（≥113） |
| G9 | 变更门禁 | `bash scripts/change-verify.sh` | 42/42 | PASS（≥42） |
| G10 | 演示重置 | `bash scripts/platform-demo-reset.sh` 连跑两次 | 80/80、80/80，幂等 | PASS（≥80，两次一致） |
| G11 | 提交前 lint | `bash scripts/tests/lint.sh` | 24/24 | PASS |

### 3.2 判据本体（QA 独立矩阵，不复用 dev 结论）

`logs/teamflow/tf-mwt6l1c8-58vacr/scripts/qa-r41-hygiene-matrix.sh`：从 rfv 抽出 `resolve_git_base` / `mig_max_version` / `check_commit_hygiene`，在自建临时 git 仓库构造 20 个区间场景，**20/20 PASS，exit 0**：

| 场景 | 期望 | 实测 |
|---|---|---|
| `mig_max_version` 取基线树最大版本 | 16 | 16 |
| 非法 rev 读不到树 | 非 0（fail-closed） | rc=1 |
| `resolve_git_base` 取 merge-base | 等于 merge-base | 一致 |
| 基线 ref 不存在 | 非 0 且**不回退 HEAD** | rc≠0、输出空 |
| 空区间（纯脚本轮） | rc0，`added=0 base_max=V16 expected=n/a` | 一致 |
| 合法新增 `V17__x.sql` | rc0，`expected=V17` | 一致（加迁移与改判据已解耦） |
| 脏工作树 + 未跟踪 `V99__x.sql` | rc0（只认提交区间） | 一致 |
| `M` 历史迁移 / `D` 迁移 | rc1 | rc1 |
| 一次 `A` 两条迁移 | rc1，「已第 2 条」 | 一致 |
| 跳号 `V18`（应 V17） | rc1，「期望 V17，实际 V18」 | 一致 |
| `A V17` + `M pom.xml` 双违规 | 两条都报，末行 `MIGRATION` 完整 | 一致 |
| 非法迁移名 `V17__Bad-Name.sql` | rc1 | rc1 |
| `R__repeatable.sql` | rc1 | rc1 |
| `A web/package-lock.json` | rc1（防御性收紧生效） | rc1 |
| base 提交树不可读 | rc2（不假绿），`base_max=none` | 一致 |
| 空 base 入参 | rc2 且无输出 | 一致 |
| 违规行字段形状 | 恰 3 个 TAB 字段 | 一致 |
| 零迁移基线 + `V1` | rc0，`base_max=V0 expected=V1` | 一致 |

### 3.3 变异自检与第 21 条静态契约

- `bash logs/teamflow/tf-mwt6l1c8-58vacr/scripts/r41-hygiene-unit.sh` → PASS：`MIG_MAX=16`、坏 rev fail-closed、空区间绿、`SELF_RC=0` 且 5 行 `HYG_SELF` 字面量全打印、worktree 合法新增 V17 → rc0 `added=1 base_max=V16 expected=V17`。
- `bash logs/teamflow/tf-mwt6l1c8-58vacr/scripts/r41-gate21-probe.sh` → PASS：真实源码 HIT；四路变异（删 `mig_max_version`/`ls-tree -r` 锚点、注入 `mig_allowed=`、删 `HYG_SELF[3/5]`、回旧文案「只允许新增 V1x」）全部 MISS —— 第 21 条不是永真断言。
- 静态计数：`grep -c 'HYG_SELF\[' scripts/release-flow-verify.sh` = 5；`grep -cE '只允许新增 V1\[0-9\]|mig_allowed='` = 0。

### 3.4 负向与收口预演

| 用例 | 命令 | 结果 | 判定 |
|---|---|---|---|
| 坏基线 fail-closed（门禁不可被绕过） | `RELEASE_FLOW_BASE_REF=no-such-ref bash scripts/release-flow-verify.sh` | exit 1；`FAIL [32] … (hygiene=2, selfTest=2)`；修复文案「基线不可解析：merge-base no-such-ref HEAD 失败；用 RELEASE_FLOW_BASE_REF 指定可用基线」；TOTAL 44 / PASS 43 / FAIL 1；退出 trap 仍复位演示基线 | PASS（红得明确，不假绿） |
| 收口提交预演 | `C=$(git stash create)` → `check_commit_hygiene <ROOT> 990874d $C` | rc=0，`MIGRATION added=0 base_max=V16 expected=n/a` | PASS（本轮 4 处改动收口提交后 [32] 仍绿、added=0） |

## 4. 架构符合性（M3）

| 蓝图要求 | 核对结果 |
|---|---|
| 判据是区间纯函数（只读 base/head 两个提交对象，不读工作树/status） | ✓ `check_commit_hygiene` 仅用 `git diff --name-status` 与 `git ls-tree`；矩阵「脏工作树 + 未跟踪迁移 → 绿」实证 |
| 版本上界从 base 提交树派生，判据里无写死版本 | ✓ `mig_max_version` + `git ls-tree -r --name-only <rev> -- <mig_dir>`；`grep -cE '只允许新增 V1\[0-9\]|mig_allowed='` = 0；V17/V18/V0 三向用例证明 |
| `mig_max_version` 留在本文件、不抽 `scripts/lib/` | ✓ 未抽库，gate-order 第 21 条锚点仍可 grep（变异删锚点 → MISS） |
| gate-order 新增第 21 条静态契约、分母 20→21、静态段 5→6 | ✓ 5 处声明一致（:25 头注释、:96 banner、:434 `TOTAL_N == 21`、:435 `21/21`、:438 声明 21 条），实测 21/21 |
| 只动两个脚本 + memory，Java/Vue/迁移/构建零变更 | ✓ `git status` 仅 scripts/ 与 docs/；pom、package.json、lockfile、Makefile 无 diff（Makefile:107 文案为已声明的例外） |
| 重复实现 | 未新增重复；已知 3 处硬编码 16 留在 `scripts/change-verify.sh:311`、`:371`、`scripts/tests/rbac/s0-schema-migration.sh:134-137`，蓝图已列为下轮债务，本轮不判缺陷；`scripts/tests/lint.sh:252-258` 工作树相位不同，不应与 S6[32] 合并（蓝图判定正确） |

**接口一致性（静态 + 实测）**：调用链 `rfv [32]` → `check_commit_hygiene(repo,base,head)` → `mig_max_version(repo,rev)` 三者在目标文件中均存在（:342 / :349 / :367），参数个数与顺序一致，全量执行 44/44 证明无未定义符号；出参 0/1/2、`STATUS<TAB>relpath<TAB>原因`、末行 `MIGRATION<TAB>added<TAB>base_max<TAB>expected` 均由矩阵逐字段验证（恰 3 个 TAB 字段、非法场景 rc=1/2 正确）；gate-order 依赖的 `HYG_SELF[k/5]` 五个字面量与 `mig_max_version` / `ls-tree -r` 均实存。**可执行证据**：主路径真实执行（rfv 44/44、gate-order 21/21、矩阵 20/20），非仅存在性检查。

## 5. 缺陷表

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| QA-R41-01 | P3 | 文档/交付说明 | 1) 读 dev 交付说明 `[OUT-OF-SCOPE]`：「DELIVERED-MODULES.md … 本轮不更新」2) 执行 `git diff --stat -- docs/teamflow/DELIVERED-MODULES.md` | 交付说明与工作树一致：要么不改，要么如实记为已改 | 实际改了 3 处（表头口径、r40 行 change-verify 40→42、规模行 gate-order 21/unit 475/lint 24），说明却称未改 | TECH-CHANGE §2 影响范围 | `git diff --stat -- docs/teamflow/DELIVERED-MODULES.md` | 二选一：接受该改动并在交付说明中如实记录（推荐，内容与 memory 一致），或回退该文件使 diff 为空 |

P0 / P1 / P2：未发现。

## 6. 已知偏差（不判缺陷，验收需知悉）

1. `Makefile:107` `make help` 仍写「gate-order-verify …（20 条）」——技术单 §2 明令本轮不动构建入口；建议下一轮单行同步为 21。
2. 迁移版本知识仍有 3 处硬编码 16（`scripts/change-verify.sh:311`、`:371`，`scripts/tests/rbac/s0-schema-migration.sh:134-137`）——下次加 V17 时这两处会重演 r40 的「门禁红在迁移上」；蓝图已列为下轮债务，本轮明确不扩范围。
3. `hygiene_self_test` stdout 实为 6 行（5 条 `HYG_SELF[k/5]` + 1 行汇总），技术单接口文字写「5 行」；唯一消费方按行 grep 字面量，无行为影响。
4. `docs/teamflow/memory.md:38` 仍未提及 r41 新增的防御性 `*package-lock.json` pattern（正文只写 pnpm-lock），判据与记忆行有轻微口径差，建议下轮同行补齐。

## 7. 人工补测清单

本交付为门禁脚本与团队文档，**无 UI 变更**，视觉/交互类检查 N/A（沙箱浏览器不可用，且无页面可测）。可选人工复核一项：

| 项 | 方法 | 工具 | 说明 |
|---|---|---|---|
| `make help` 文案是否接受为已知漂移 | 执行 `make help`，确认 gate-order 条数文案 | 终端 | 技术单 §2 已明令本轮不动 Makefile，属环境限制外的已声明偏差，非交付缺陷 |

## 8. 最终判定

**可验收（acceptance-ready）**：判据修正按蓝图落盘且被五路证据证明有效（矩阵 20/20、自检 5/5、第 21 条 1 正 4 变、坏基线 fail-closed、收口提交 added=0），8 项门禁 194/130/206/113/42/80×2/44/21 全部达标且两次一致；遗留仅 1 条 P3 文档一致性问题与 4 条经决策的已知偏差，无阻塞项。
