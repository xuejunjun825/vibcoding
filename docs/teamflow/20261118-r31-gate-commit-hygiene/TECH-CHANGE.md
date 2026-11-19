# r31 技术变更单：门禁提交卫生判据去「脏树依赖」

> 基线依赖：`docs/teamflow/20261118-r30-release-approval-chain/`
> 变更类型：tech-driven rework（修门禁自身假红）；**不新增用户可见 AC**；r30 AC-23 文本不变，只修其判据实现。
> 分支：`feat/release-multilevel-approval`，HEAD `fae843e`；BASE=`git merge-base main HEAD`=`79e1f1c`。

## 1. 背景与目标

一句话：r30 第 32 条用 `git status --porcelain` 判迁移合规，提交后工作树必干净，断言必红；改为 `BASE..HEAD` 提交历史判据，并内置变异自检，防止判据退化回「靠脏树才绿」。

实测证据：`RELEASE_FLOW_TOTAL=32 CASE=32 PASS=31 FAIL=1`，失败行 `[32] 提交卫生不符（pom/package diff clean=1, new=[], modified=0）`；而 `BASE..HEAD` 实际只有 `A req-service/src/main/resources/db/migration/V14__release_flow_chain.sql`，`pom.xml` / `web/package.json` 无 diff。

**目标**：干净树跑 `scripts/release-flow-verify.sh` 全绿；已提交的「改历史迁移 / 越权新增 V15」必须红；工作树有无临时文件都不影响第 32 条。

## 2. 影响范围

- 改 `scripts/release-flow-verify.sh`：S6-2 判据重写（基线解析 + name-status 解析 + 变异自检）；S6-1 补源文件存在性；S1 前置自愈六槽；头部注释同步。
- 改 `docs/teamflow/memory.md`：追加一条「门禁判据卫生」约定（同主题行，幂等）。
- 新增 `docs/teamflow/20261118-r31-gate-commit-hygiene/{TECH-CHANGE,QA-REPORT,ACCEPTANCE}.md`。
- 不改：Java/Vue/SQL、`V14__release_flow_chain.sql` 内容、`pom.xml`、`web/package*.json`、Makefile 目标、32 条断言分母、r30 的 12 条 AC 与业务语义。

## 3. 技术方案

### 3.1 基线解析

`GIT_BASE="$(git -C "$ROOT" merge-base "${RELEASE_FLOW_BASE_REF:-main}" HEAD)"`；解析失败 → 第 32 条直接 `fail` 并提示 `RELEASE_FLOW_BASE_REF`，**不 skip**。运行开始打印 `BASE=<short> HEAD=<short> BASE_REF=...`，供报告取证。判据只读 git 对象库，不看工作树。

### 3.2 判据函数

`check_commit_hygiene <repo> <base> <head>`，返回 0/1 并打印 `status + path`：

1. pom 判据：`git diff --name-only "$base" "$head" -- pom.xml web/package.json web/package-lock.json` 必须为空。
2. 迁移判据：`git diff --name-status --no-renames "$base" "$head" -- req-service/src/main/resources/db/migration`：
   - 每条只允许 `A .../V14__release_flow_chain.sql`；
   - 任何 `M` / `D` / `R`（含合并后又被改的 V14）→ 红；任何其它 `A`（V15+、`R__*.sql` 等）→ 红；
   - **V14 允许 0 或 1 条**：合并后 BASE 已含 V14、diff 为空时必须绿，否则又是同类假红。
3. `git status` / `git diff HEAD` 一律不参与判定；工作树脏（QA 的 `.run/`、临时文件）不改变结论。

### 3.3 变异自检（gate-of-gate）

同一脚本内实现 `hygiene_self_test`，随第 32 条执行；**不新增编号，分母仍 32**（理由：自检验证的是判据本身而非业务事实，独立计数会污染 AC-20 的分母语义）。

1. `git worktree add --detach "$TMP/wt" HEAD`，临时 worktree，不切当前分支、不写仓库 config。
2. 变异 a：对 `V13__*.sql` 追加 1 字节 → `git add -A` + 临时身份 `git commit` → `check_commit_hygiene "$wt" "$orig" "$mut"` 必须返回非 0。
3. `git reset --hard "$orig"`；变异 b：新增 `V15__mutation_probe.sql` → commit → 必须返回非 0。
4. subshell `trap` 清理 worktree + 临时目录；主工作树 `git status` 不受影响。
5. 任一变异未变红、或 worktree 创建失败 → 第 32 条红，并在失败行打印自检输出；全绿时打印 `自检 a/b → 红`。

内联而非独立脚本的理由：自检与判据同生共死，不存在「脚本在但没人执行」的第二种假绿，也无需再维护 Makefile/lint 入口。若改成 `git status` 判据，变异是已提交的，`status` 为空会让判据误判为绿，自检立即抓住。

### 3.4 同族判据核查（r28 教训：夹具只 fail 不 pass、不靠环境假设）

- H1（改）S6-2 迁移判据：`git status` → `BASE..HEAD`（本条主线）。
- H2（改）S6-2 的 pom 判据原用 `git diff --quiet HEAD`：已提交的 pom/package 改动会假绿 → 同改 `BASE..HEAD`，并纳入 `web/package-lock.json`。
- H3（改）原 `awk '{print $2}'` 只数 `'^ M'`：漏 staged / renamed / deleted，含空格路径还会错位 → 统一 `--name-status --no-renames` 解析。
- H4（改）S6-1 的 `RC_FILE` 不存在时 `grep` 报错但 `NO_PUBLISH` 保持 1 → 假绿；文件不存在即 `NO_PUBLISH=0`。
- H5（改）S1 假定六槽已是默认：上一轮中断/残留改绑会让 S1-1 假红；S1 开头幂等 `DELETE` 六槽回默认（setup 不计数）。
- H6（不改）固定演示账号/密码：仅作前置，登录失败 `die`，不参与 pass 判定；属演示种子契约。
- H7（不改）`as_user ... || true`：登录失败会让后续计数断言变红（不会假绿），仅诊断信息弱；本轮不动。
- H8（不改）`cleanup` 的 demo-reset 失败只 WARN：H5 已让下一轮不依赖上轮清理；不改退出码。
- H9（不改）`TAG` 秒级碰撞覆盖报告文件：不影响判定。

结论：核查 9 处，改 5 处（H1–H5），H6–H9 不构成「假绿」。

## 4. 行为兼容性

- 用户可见：无。API/UI/DB/权限码/导航/依赖均不变；V14 内容不动；r30 的 AC 文本不动。
- 开发者可见：干净树提交后由 31/32 变 32/32；已提交的「改历史迁移 / 新增 V15」由假绿变红；脏工作树不再影响第 32 条。
- 合并后兼容：BASE 已含 V14 时判据允许 `A V14` 为 0 条，避免「一合并就红」。

## 5. 回归与验证

- `bash scripts/release-flow-verify.sh` → `CASE=32 PASS=32 FAIL=0`，exit 0；输出含两条自检红。
- `bash scripts/release-flow-verify.sh --static-only` → 8/8（静态条数不变）。
- 手工变异（QA 可复跑）：临时 worktree 中改 V13 一字节、新增 V15，分别跑第 32 条判据 → 非 0；还原后主树 `git status --porcelain` 为空。
- 既有下限：`platform-verify` ≥193、`platform-test-negative` ≥130、`access-verify` ≥206、`rbac-verify` 113、`wbs-backfill-verify` 18、`platform-demo-reset` 69、`platform-unit` ≥371、`lint` 22、`web` typecheck+build 全绿。
- `git diff main HEAD --name-status` 迁移仅 `A req-service/src/main/resources/db/migration/V14__release_flow_chain.sql`；pom/package 无 diff。
- QA-REPORT 必须贴实际 `TOTAL/CASE/PASS/FAIL` 与退出码，不接受「已跑」。

## 6. 风险与回滚

- `git worktree` 在只读 `.git` / 受限 CI 不可用 → 自检 fail-closed（不提供跳过开关，跳过=假绿）；需 CI 允许 worktree，或后续改用可写临时 clone。
- `main` 引用缺失（浅克隆）→ 第 32 条红并提示 `RELEASE_FLOW_BASE_REF`；不 fallback 到 `HEAD`（会假绿）。
- 自检产生 dangling commit，不碰当前分支；`trap` 清理失败最多留临时目录，不影响判定。
- 未来新增迁移需同步更新「只允许 V14」清单（有意硬编码，避免任意新增都放行）。
- 回滚：仅回退本次脚本提交；无 schema/数据/接口变更，V14 不动。

## 7. 假设与待澄清

- **V14 允许 0/1 条**（而非必须 1 条）：合并后 `BASE..HEAD` 为空必须绿；若要求「必须新增 V14」，合并后每次跑都会红，等同旧缺陷。若你要求严格 1 条，需限定只在未合并分支运行并加 `--require-v14`。
- **自检内联、分母仍 32**：若希望独立编号（33）或独立脚本，行为不变，但需同步 `RELEASE_FLOW_TOTAL`、静态自声明检查、Makefile help 与 TECHNICAL §7.2。
- **BASE 默认 `main`**：可用 `RELEASE_FLOW_BASE_REF=origin/main` 覆盖；不自动猜测远端分支。
- **提交信息未机器校验**：runId 每轮不同，硬编码会假红；仍由提交规范/评审保证。若要自动化，传入 `RELEASE_FLOW_RUN_ID` 再 grep。
- **H5 六槽自愈**是夹具写操作：若要求「断言前零写入」，可去掉 H5、仅依赖 EXIT demo-reset（保留上一轮残留的假红风险）。
