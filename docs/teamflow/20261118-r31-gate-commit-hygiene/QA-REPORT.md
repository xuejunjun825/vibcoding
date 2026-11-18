# r31 QA 报告：门禁提交卫生判据去「脏树依赖」

- 分支 `feat/release-multilevel-approval`，HEAD `fae843e`，BASE（`git merge-base main HEAD`）`79e1f1c`。
- 交付面：`scripts/release-flow-verify.sh`（1132 行，工作树 +187/−15）、`docs/teamflow/memory.md`（+1 行，第 37 行「门禁判据卫生（r31）」）、任务夹 `TECH-CHANGE.md`（94 行）。
- 结论：**可验收**。0×P0/P1/P2；P3 观察 3 条（文档漂移、本轮改动未提交、`--static-only` 不覆盖第 32 条），均不阻塞。判据与蓝图一致：只认 `BASE..HEAD`、只读 git 对象库、同日内置 1 正 3 反变异自检、分母仍 32。

## 1. 范围与环境

- 在范围内：第 32 条提交卫生判据（`check_commit_hygiene`）、基线解析（`resolve_git_base`）、变异自检（`hygiene_self_test`）、S6-1 源文件存在性守卫、S1 六槽前置自愈（`reset_binding_slots`）、`memory.md` 契约行、分母 32 与静态自检。
- 不在范围内（不改）：Java/Vue/SQL、`V14__release_flow_chain.sql` 内容、`pom.xml`、`web/package*.json`、Makefile 目标、r30 的 12 条 AC 文本。
- 环境：平台栈已起（`GATEWAY_URL=http://127.0.0.1:8080`），`platform_login t1 admin`；macOS + bash 3.2；沙箱内浏览器不可用（环境限制，本需求无 UI 变更，不需要）。
- 全部命令均为单命令、单检查、≤120s（0a3）。

## 2. 用例与结果（全部实跑，非存在性检查）

| 编号 | 用例 | 命令 | 结果 |
|---|---|---|---|
| C1 | 脚本语法 | `bash -n scripts/release-flow-verify.sh` | exit 0 |
| C2 | 静态自检 8 条 | `bash scripts/release-flow-verify.sh --static-only` | exit 0，`RELEASE_FLOW_STATIC_TOTAL=8 PASS=8 FAIL=0` |
| C3 | 全量门禁 32 条（含 S1–S6 运行时） | `bash scripts/release-flow-verify.sh` | exit 0，`RELEASE_FLOW_TOTAL=32 CASE=32 PASS=32 FAIL=0` |
| C4 | 判据纯度/独立性 22 断言 | `bash logs/teamflow/tf-mwm9q21d-fq21pr/scripts/qa-r31-hygiene-purity.sh` | exit 0，`PASS=22 FAIL=0` |
| C5 | S1 六槽自愈运行时 9 断言 | `bash logs/teamflow/tf-mwm9q21d-fq21pr/scripts/qa-r31-s1-selfheal.sh` | exit 0，`PASS=9 FAIL=0` |
| C6 | 仓库 lint 回归 | `bash scripts/tests/lint.sh` | exit 0，22/22 |
| C7 | dev 函数探针 | `bash logs/teamflow/tf-mwm9q21d-fq21pr/scripts/r31-hygiene-functions.sh` | exit 0，5/5 |
| C8 | 提交面卫生（0c） | `git status --porcelain` | 仅 `M docs/teamflow/memory.md`、`M scripts/release-flow-verify.sh`、`?? docs/teamflow/20261118-r31-gate-commit-hygiene/`；无 target/node_modules/.idea/secret |
| C9 | 判据覆盖物实存 | `git ls-files '*pom.xml' \| wc -l`；`test -f web/pnpm-lock.yaml`；`test ! -f web/package-lock.json` | 7；存在；不存在 |
| C10 | 分母唯一 | 静态项 03 `grep -c '^RELEASE_FLOW_TOTAL=32$'` | 1（C2 中 PASS） |
| C11 | git 判定路径无脏树信号 | `grep -n 'git status\|git diff HEAD' scripts/release-flow-verify.sh` | 仅第 332–333、379 行注释（说明旧判据为何错），无代码调用 |
| C12 | worktree 无泄漏 | `git worktree list`（C3 与 C4 各 3 次自检后） | 仅主树 `fae843e` |

C3 关键行（原文）：`PASS [31]` S6-1 无同批发布（含源文件存在性守卫生效）；`PASS [32] 提交区间 79e1f1c..fae843e 卫生：7 个 *pom.xml + web/package.json + web/pnpm-lock.yaml 无改动、迁移仅 0/1 条 A V14；判据自检 4/4（正例绿 / 改 V13 / 新增 V15 / 改模块 pom 均红）`；自检明细行 `自检 → 正例绿；a 改 V13 红；b 新增 V15 红；c 改 req-service/pom.xml 红`。全量日志 `/tmp/rf-full-qa.log` 中 `WARN`/复位失败计数为 0。

## 3. 判据纯度矩阵（C4，22/22）

- 正向（必须绿）：A1 真实区间 `79e1f1c..fae843e` 绿（唯一变更 `A …/V14__release_flow_chain.sql`）；A2 区间内除 V14 外无其它变更；A3 空区间（V14 为 0 条）绿——合并后不重演「一合并就假红」；A4 脏工作树（未提交改 V13 + 改 pom + 未跟踪文件）仍绿——证明不依赖工作树；A5 与 cwd 无关。
- 反向（必须红）：B1 `M V13__vendor_pm_role.sql`；B2 `A V15__mutation_probe.sql`；B3 `M req-service/pom.xml`（模块 pom，覆盖面修复的直接证据）；B4 `M web/pnpm-lock.yaml`；B5 `M web/package.json`；B6 `D V14`；B7 rename V13。
- 判据退化自检：C1 把判据改成恒绿 → 自检红；C2 改成恒红 → 自检红（正例抓住）；C3 还原后自检 4/4。
- fail-closed 与卫生：D1 `RELEASE_FLOW_BASE_REF=no-such-ref` → 非 0；D2 失败时 stdout 为空（未回退 HEAD，未把 HEAD 当基线）；D3 3 次自检后 worktree 无泄漏；D4 判定路径无 `git status`/`git diff HEAD` 残留；D5 分母唯一；D6 pom 实数 7；D7 lockfile 指向真实 `web/pnpm-lock.yaml`。

## 4. 架构核查（要求 0）

对照蓝图逐项核对，**无 P1 架构缺陷**：

1. 未抽新脚本、未新增 lib：改动集中在 `scripts/release-flow-verify.sh` 单文件；`git diff --numstat` 确认仅该脚本 +187/−15 与 `memory.md` +1/−0，未动 `scripts/lib.sh`、Makefile、`RELEASE_FLOW_TOTAL`。符合「`scripts/` 是运维唯一入口、Makefile 1:1 薄封装」的取舍。
2. 单一实现、无重复：全仓无第二处提交卫生判据（`scripts/release-flow-verify.sh:342` 唯一实现；`V14__release_flow_chain` 另见 `scripts/tests/rbac/s0-schema-migration.sh:134`，语义是「迁移是否已执行」，非提交卫生，不构成重复）。
3. 抽象层级正确：`resolve_git_base`（`:335`）、`check_commit_hygiene`（`:342`）、`hygiene_self_test`（`:377`）、`reset_binding_slots`（`:450`）均留在脚本内，未被过度下沉到 `lib.sh`；`static_checks`（`:466`）/`cleanup`（`:319`）/`pass`（`:64`）/`fail`（`:65`）签名未变。
4. 既有结构未被破坏：S6-1 守卫（`[[ ! -f "$RC_FILE" ]] → NO_PUBLISH=0 + S6_SRC_MISSING`）与 S1 前置自愈（`:581` `reset_binding_slots || WARN`）为最小侵入式补丁；第 32 条仍不新增编号，分母保持 32。
5. 唯一偏差是**文档而非代码**：见 P3-1。

## 5. 接口一致性（要求 0a，静态 + 运行时）

| 被调成员 | 定义 | 调用点 | 参数/返回形状 | 结论 |
|---|---|---|---|---|
| `check_commit_hygiene <repo> <base> <head>` | `scripts/release-flow-verify.sh:342` | `:1097` `check_commit_hygiene "$ROOT" "$GIT_BASE" "$GIT_HEAD"` | 3 参；返回 0/1/2；stdout `STATUS<TAB>relpath` | 一致（C4 B1–B7 实测） |
| `hygiene_self_test <base>` | `:377` | `:1099` `hygiene_self_test "$GIT_BASE"` | 1 参；返回 0/1；失败点名 | 一致（C3/C4 实测 4/4） |
| `resolve_git_base` | `:335` | `:531` `GIT_BASE="$(resolve_git_base)"` | 无参；stdout sha；失败非 0 | 一致（C4 D1/D2） |
| `reset_binding_slots` | `:450` | `:581` `reset_binding_slots \|\| WARN` | 无参；0 全成功 / 1 有失败 | 一致（C5 实测 200×6、幂等、未认证 rc=1） |
| `api_call <method> <path> [body]` → `HTTP_CODE`/`HTTP_BODY` | `scripts/lib.sh:423` | `reset_binding_slots` 内 DELETE | 与 `FlowBindingController` `@DeleteMapping("/{bizType}/{level}")` 对齐 | 一致（C5 实测 200） |

## 6. 交付形态（0b）与 craft（0d）

- 本需求是脚本 + 文档，无安装/打包产物；交付形态的可执行判据就是门禁本身，已实跑 C2/C3（含 32 条运行时断言），不存在「文件都在但程序空转」的情形。
- craft：无可见 UI 变更，N/A（不产生 P3 craft 行）。

## 7. 缺陷表

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| P3-1 | P3 | 门禁脚本（文档） | 打开 `docs/teamflow/20261118-r31-gate-commit-hygiene/TECH-CHANGE.md` 读 §3.2 第 2 行与 §3.4 H2 | 判据文字与实现一致：`'*pom.xml'` + `web/pnpm-lock.yaml` | 仍写 `pom.xml web/package.json web/package-lock.json`（只根 pom、lockfile 不存在） | r30 AC-23 / 本变更 §3.2 | `grep -n "package-lock" docs/teamflow/20261118-r31-gate-commit-hygiene/TECH-CHANGE.md` | 输出为空（或改为 pnpm-lock.yaml 且写全 7 个 pom） |
| P3-2 | P3 | 版本控制 | `git log -1 --format=%s` 与 `git status --porcelain` | r31 改动已提交，且提交信息含 runId 与任务夹 | HEAD 仍是 r30 的 `fae843e`，本轮改动为未提交状态；`BASE..HEAD` 不覆盖本轮脚本改动 | r30 AC-23 | `git status --porcelain` | 仅剩已提交内容（提交后输出为空），`git log -1` 含 `tf-mwm9q21d-fq21pr` 与 `20261118-r31-gate-commit-hygiene` |
| P3-3 | P3 | 门禁脚本 | `bash scripts/release-flow-verify.sh --static-only` | 快速模式也应能发现判据退化 | 静态 8 条不含第 32 条与变异自检，判据退化只在全量门禁暴露（TECHNICAL 已登记） | r30 AC-23 | `bash scripts/release-flow-verify.sh --static-only` | 保持 8/8 即可（若纳入第 32 条，分母与静态自声明需同步） |

未发现 P0/P1/P2 缺陷。

## 8. 人工补测清单

| 项 | 方法 | 工具 | 说明 |
|---|---|---|---|
| 提交信息含 runId 与任务夹（TECH-CHANGE §7 声明机器未校验） | 提交后 `git log -1 --format=%B` 人工核对 | git | 自动化会因 runId 每轮变化而假红，属设计取舍 |
| 干净树（提交后）复跑全量门禁 | `make release-flow-verify` | make | 本轮跑的是未提交工作树；提交后 `BASE..HEAD` 将包含脚本自身改动，须复跑确认仍 32/32 |
| 只读 `.git` 的 CI 环境（worktree 不可用） | 在 CI 中跑第 32 条，确认 fail-closed 而非跳过 | CI | 本地不可复现，属环境限制，非交付缺陷 |

## 9. 结论

- 判据修复达到变更单目标：干净树全绿（32/32）、已提交的「改历史迁移 / 新增 V15 / 改模块 pom」必红、脏工作树不影响结论、基线不可解析 fail-closed、无 worktree 泄漏；`memory.md` 契约行与实现逐条一致（7 个 `*pom.xml` + `web/pnpm-lock.yaml`，明确写「不存在 `web/package-lock.json`」）。
- 架构与接口核查无偏差；未抽新脚本、无重复实现、分母仍 32。
- 0×P0/P1/P2，P3 观察 3 条不阻塞 → **可验收**；P3-2 的提交动作在验收/收口阶段完成，提交后按 §8 复跑一次全量门禁即可闭环。
