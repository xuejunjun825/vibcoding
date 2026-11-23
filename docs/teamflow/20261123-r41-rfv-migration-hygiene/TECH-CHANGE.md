<!-- meta: summary="r41 技术变更单：release-flow-verify [32] 迁移判据从写死 V15 改为版本号无关（0/1 条 A、V<n>__<snake>.sql、版本号==基线提交树最大+1，由 mig_max_version 从 ls-tree 派生），自检扩为 5 条逐条打 HYG_SELF[k/5]，gate-order 增第 21 条静态自证；只改脚本与文档。" -->
基线依赖：docs/teamflow/20261123-r40-req-change-order（r40 新增 V16 后 [32] 必红；本单修判据本身，不改产品行为）

# 门禁迁移判据版本号无关化（r41）技术变更单

## 0. 一句话

把 `release-flow-verify` 第 32 条「提交卫生」里**写死的允许迁移版本号**换成「区间纯函数 + 版本号动态派生」，并把变异自检从 1 正 3 反扩到 1 正 4 反，再由 `gate-order-verify` 静态自证防止回退。

## 1. 背景与目标

- 症状（main 已合 r40）：`FAIL [32] 提交卫生不符或判据自检失败（range=289af7d..c9896eb, hygiene=1, selfTest=0）`，提示写「迁移只允许新增 V15」，实际区间新增的是 `A req-service/src/main/resources/db/migration/V16__requirement_change.sql`。
- 影响链：`release-flow-verify` 44 条中 [32] 红 → `gate-order-verify` 在 A2 段调它即失败退出，A 序列跑不到 A2。
- 目标：判据不再依赖「本轮是第几个迁移」这个环境假设；此后每加一个 Flyway 迁移都不必改门禁。

## 2. 影响范围

| 文件 | 改动 |
|---|---|
| `scripts/release-flow-verify.sh` | `check_commit_hygiene()`（:349）、`hygiene_self_test()`（:384）、第 32 条断言块（:1160-1177）、头部 S6 描述（:17） |
| `scripts/gate-order-verify.sh` | 静态契约段新增第 21 条（:141 后、`# ---- 7-15 双序列` 前）、`TOTAL_N == 20`→`21`（:408）、`banner "1) 静态契约（5）"`→`6`（:95）、头部条数说明（:25） |
| `docs/teamflow/memory.md` | 「门禁判据卫生」行同主题替换（不新增行） |
| 不动 | `pom.xml`/`web/package.json`/lockfile、`Makefile`、Java/Vue 代码、任何 Flyway 迁移（V1–V16 的数量与内容都不变） |

## 3. 技术方案

### 3.1 判据：`check_commit_hygiene <repo> <base> <head>`（签名不变，出参仍 0/1/2）

1. **依赖/锁文件**（逻辑不变，pattern 补一条）：`('*pom.xml' 'web/package.json' 'web/pnpm-lock.yaml' '*package-lock.json')`，区间内任一 name-status 行即违规。
2. **迁移目录** `req-service/src/main/resources/db/migration`：`git diff --name-status --no-renames "$base" "$head" -- "$mig_dir"` 逐行判——状态必须为 `A`（`M/D/R` 即违规）；basename 必须匹配 `^V[0-9]+__[a-z0-9_]+\.sql$`（`R__*.sql`、大写或横线名即违规）；条数必须 ≤1（一次新增 2 条即违规）。
3. **版本号动态派生**：新增恰 1 条时令 `base_max = mig_max_version "$repo" "$base"`，要求 `N == base_max + 1`，否则违规（跳号、落后都红）。`base_max` 取 **base 提交树**（= `git merge-base main HEAD`），判据仍是区间纯函数，main 前进不会让结论随时间漂移。
4. **`mig_max_version <repo> <rev>`**（新函数，判据与自检的唯一派生点）：`git ls-tree -r --name-only "$rev" -- "$mig_dir"` → basename 过滤 `^V([0-9]+)__` → `sort -n` → 取最大；目录不存在返回 0；`ls-tree` 失败返回非 0（fail-closed 判红，不落回 0）。
5. **输出契约**：违规行 `STATUS<TAB>relpath<TAB>原因`；末尾一行 `MIGRATION<TAB>added=<n><TAB>base_max=V<m|none><TAB>expected=V<want|n/a>`；返回值仍是唯一判据。

### 3.2 自检：`hygiene_self_test <base>`（5 条，逐条打印结论）

在 `git worktree add --detach` 的临时树里做，每条变异**提交后**再判定（退回 `git status`/`git diff HEAD` 的判据会被立即抓住），每条结束 `git reset --hard "$orig"`：

| # | 用例 | 期望 | 日志前缀 |
|---|---|---|---|
| 1 | 空区间 + 工作树留未跟踪临时文件 | 绿（防恒红） | `HYG_SELF[1/5]` |
| 2 | `M` 既有迁移 `V13__vendor_pm_role.sql` | 红 | `HYG_SELF[2/5]` |
| 3 | 一次 `A` 两条：`V<m+1>__…` 与 `V<m+2>__…` | 红（条数 >1） | `HYG_SELF[3/5]` |
| 4 | 跳号：单独 `A V<m+2>__…` | 红（≠ max+1） | `HYG_SELF[4/5]` |
| 5 | 锁文件四连：`M pom.xml`、`M web/package.json`、`M web/pnpm-lock.yaml`、`A web/package-lock.json` | 四者各自红 | `HYG_SELF[5/5]` |

- `m` 由 `mig_max_version` 现算，**自检里不出现任何写死的版本号**；用例 5d 新建仓库当前不存在的 `package-lock.json`，证明该 pathspec 不是「永远命不中」的空判据（r31 教训）。
- 汇总行 `自检 → 5/5 全绿（1 正 4 反，反例 5 含 4 个子变异）`；任一用例不符即 `exit 1`，第 32 条随之红。

### 3.3 第 32 条断言文案

- pass：`提交区间 <b>..<h> 卫生：<n> 个 *pom.xml + web/package.json + pnpm-lock/package-lock 无改动；迁移 added=<n>（0 或 1 条 A，版本 = 基线最大+1，M/D/R 即红）；判据自检 5/5`。
- fail 提示：`… pom/lockfile 不得改；迁移只允许 0 或 1 条 A、文件名 V<n>__<snake>.sql、版本号 = 基线最大+1；自检必须 1 正 4 反`。
- 两处文案与脚本头注释（:17、:1163）不得再出现「只允许新增 V1」或具体版本号；分母仍 44、静态分段仍 12。

### 3.4 `gate-order-verify.sh` 第 21 条（静态自证，防回退）

```
f=scripts/release-flow-verify.sh
① ! grep -qE '只允许新增 V1[0-9]'                 # 写死允许版本的措辞
② ! grep -qE 'mig_allowed=.*V[0-9]+__'            # 写死的允许文件名常量
③   grep -q 'mig_max_version' && grep -q 'ls-tree -r'   # 判据确实动态派生（整条规则被删也不能变绿）
④   for i in 1..5: grep -q "HYG_SELF\[$i/5\]"      # 自检 5 条不得缩水
```

① ② 命中或 ③ ④ 缺失即该条红；分母 `TOTAL_N` 20→**21**（下限 ≥20 仍满足），静态契约 5→6 条。

## 4. 行为与兼容性影响

- **无用户可见变更**：不改 HTTP/UI/DB 行为，不动示例与业务数据；只改开发/QA 门禁的判据与自检。
- 唯一「更严」处：锁文件 pattern 补 `*package-lock.json`（当前仓库无此文件，属防御性收紧）；迁移规则由「等于某常量」改为「等于基线最大+1」，对合法新增（每次 +1）等价。
- 分支自证：本单不新增迁移，r41 自身区间 `added=0` → [32] 必须绿（0 条是合法常态）。

## 5. 回归与验证计划

| 命令 | 下限 | 期望 |
|---|---|---|
| `bash -n scripts/release-flow-verify.sh scripts/gate-order-verify.sh` | 0 error | 语法过 |
| `bash scripts/release-flow-verify.sh --static-only` | 12/12 | 免栈静态段绿 |
| `bash scripts/release-flow-verify.sh` | ≥44 | 44/44，[32] 绿并打印 5 行 `HYG_SELF` |
| `bash scripts/gate-order-verify.sh` | ≥20 | 21/21（含新第 21 条） |
| `bash scripts/platform-verify.sh` | ≥194 | 不降 |
| `bash scripts/platform-test-negative.sh` | ≥130 | 不降 |
| `bash scripts/access-verify.sh` | ≥206 | 不降 |
| `bash scripts/rbac-verify.sh` | ≥113 | 不降 |
| `bash scripts/change-verify.sh` | ≥42 | 不降 |
| `bash scripts/platform-demo-reset.sh` | ≥80 | 连跑两次同结果 |
| `git diff --name-only $(git merge-base main HEAD)..HEAD` | — | 只有 `scripts/` 与 `docs/`，无 pom/package/lock、无迁移 |

顺序：先静态（`--static-only` + `gate-order` 静态段），`make platform-up` 起栈后跑全量，最后跑自带 A/B 双序列的 `gate-order-verify`。证据进 QA 报告：各门禁末行分母 + [32] 的 5 行 `HYG_SELF` + 第 21 条 hit 文案。

## 6. 风险与回滚

| 风险 | 处置 |
|---|---|
| 自检多 4 次提交 + 1 个 worktree，[32] 约 +3~6s | 可接受；`git worktree add` 不可用一律 fail-closed 红，不提供跳过开关（沿用 r31） |
| `base_max` 取值口径 | 取 base 提交树而非 main tip：前者是区间纯函数，后者随 main 前进漂移（见 §7-2） |
| 判据收紧让历史分支合并后首跑变红 | 正确信号（M/D/R、跳号本就该红）；回滚只需 revert 本提交两脚本 |
| 第 21 条绑定 `mig_max_version` 名与 `HYG_SELF[k/5]` 标记 | 改名须同步改 gate-order；已写入 memory 约定行 |
| 回滚 | `git revert <r41 commit>`；无 DDL、无数据迁移、无 API 变更，V1–V16 一字不动 |

## 7. 假设与待澄清

1. **「恰好 1 个」与「0 个也必须过」字面冲突** → 取「∈{0,1}」。若坚持必须恰好 1 个，纯前端/纯脚本轮会恒红，与要求 1 自相矛盾。
2. **「main 现存最大 +1」的取值点** → 取 `merge-base` 提交树。若要求对比 main tip，结论会随 main 前进而漂移（历史分支被后加迁移误判跳号），需改成静态快照表。
3. **`package-lock`**：仓库用 pnpm、无 `package-lock.json`；判据用 `*package-lock.json` 通配并在自检里新建该文件自证。若明确永不 npm 化，可删该 pattern（其余 4 条不受影响）。
4. **gate-order 分母 20→21**：新增独立静态条而非并入第 1 条，代价是分母 +1；若必须保持 20 可并入现有静态契约（失败原因会混在一起，不推荐）。
5. **自检用例数固定 5**：`HYG_SELF[k/5]` 的上界写进了 gate-order 循环；将来加用例要同步改两处（已入 memory）。

## 8. 任务边界（dev 执行）

- T1 判据重写 + `mig_max_version`（`release-flow-verify.sh`）；T2 自检 5 条 + 断言文案/头注释；T3 `gate-order-verify.sh` 第 21 条与分母/横幅；T4 memory 同步 + 回归证据收集。
- 禁止：新增或改名迁移、动 `Makefile`、动任何 `pom.xml`/`package.json`/lockfile、改既有断言分母语义（44/12 不变）。
