# r31 验收报告：门禁提交卫生判据去「脏树依赖」

> 验收对象：[TECH-CHANGE.md](docs/teamflow/20261118-r31-gate-commit-hygiene/TECH-CHANGE.md)（94 行）及实现提交 `a7c7f13`；基线 `docs/teamflow/20261118-r30-release-approval-chain/`；分支 `feat/release-multilevel-approval`，BASE（`git merge-base main HEAD`）`79e1f1c`。
> 验收口径：r31 为 tech-driven rework，**不新增用户可见 AC**；验收对象＝变更单 §1–§5 的可检验声明 + r30 AC-23 的判据实现 + 架构蓝图的落位。

## 1. 结论摘要

- 判据已从「读工作树」改为提交区间纯函数：干净树全绿、已提交违规必红、脏工作树不影响结论、基线不可解析 fail-closed。
- 提交 `a7c7f13` 已含 runId（`tf-mwm9q21d-fq21pr`）与任务夹；提交后干净树复跑全量门禁 **32/32**，真实区间（含 `A V14` 与本次脚本改动）未误红。
- 0×P0/P1/P2；P3 观察 2 条（文档漂移、`--static-only` 覆盖面），均不阻塞；QA 的 P3-2（未提交）已在本阶段闭环。

## 2. 逐项核对

| # | 判据（变更单/蓝图/AC-23） | 实跑证据 | 结论 |
|---|---|---|---|
| R31-1 | 基线解析失败即红、绝不回落 HEAD | 纯度 D1/D2：`RELEASE_FLOW_BASE_REF=no-such-ref` 非 0 且 stdout 为空 | ✅ |
| R31-2 | 依赖/锁判据覆盖全部 7 个 `*pom.xml` + `web/package.json` + 真实 `web/pnpm-lock.yaml` | 代码 `scripts/release-flow-verify.sh:344,353`；`git ls-files '*pom.xml'`=7；`web/pnpm-lock.yaml` 存在、`web/package-lock.json` 不存在；纯度 B3/B4/B5 分别改模块 pom / pnpm-lock / package.json 均红 | ✅ |
| R31-3 | 迁移只允许 0/1 条 `A V14__release_flow_chain.sql`；`M/D/R` 或其它新增即红 | 纯度 A3（空区间绿）、B1（M V13 红）、B2（A V15 红）、B6（D V14 红）、B7（rename 红） | ✅ |
| R31-4 | 判据不读工作树（脏树/未跟踪文件不影响） | 纯度 A4：未提交改 V13+pom+未跟踪文件时仍绿；判定路径无 `git status`/`git diff HEAD` 调用（仅注释） | ✅ |
| R31-5 | 同脚本变异自检 1 正 3 反，判据退化即红；worktree 不可用 fail-closed | 第 32 条输出「自检 → 正例绿；a 改 V13 红；b 新增 V15 红；c 改 req-service/pom.xml 红」；纯度 C1/C2（恒绿/恒红改造均被自检抓住）、D3（无 worktree 泄漏） | ✅ |
| R31-6 | 分母仍 32 且唯一；静态 8/8；全量 32/32 | `grep -c '^RELEASE_FLOW_TOTAL=32$'`=1；`--static-only` → 8/8 exit 0；全量 → `CASE=32 PASS=32 FAIL=0` exit 0（提交前后各一次） | ✅ |
| R31-7 | S6-1 源文件缺失不再回落「未发现违规」 | `scripts/release-flow-verify.sh:1064-1083`：缺文件置 `NO_PUBLISH=0` 并报 `S6_SRC_MISSING`；全量第 31 条 PASS | ✅ |
| R31-8 | S1 六槽幂等自愈、setup 不计 pass | 自愈探针 9/9：200×6、二次执行 rc=0 结果一致、未认证 rc=1 不静默 | ✅ |
| R31-9 | `memory.md` 契约行纠正（7 pom + pnpm-lock + 禁 `git status`，显式声明无 package-lock） | `docs/teamflow/memory.md:37` 逐条比对实现，一致 | ✅ |
| R31-10 | 改动面受控：仅脚本+memory+任务夹；V14/pom/web/Makefile/r30 AC 不动；无 target/node_modules 等噪声 | `git show --stat a7c7f13`：5 文件（脚本 +188/−15、memory +1、任务夹 3 个），提交时 `git status --porcelain` 为空 | ✅ |
| R31-11 | r30 AC-23 提交卫生：提交信息含 runId 与任务夹、提交后干净树、提交后门禁仍绿 | `a7c7f13` 标题含 `runId=tf-mwm9q21d-fq21pr` 与任务夹；提交后 `git status --porcelain` 0 行；提交后全量 32/32，第 32 条区间 `79e1f1c..a7c7f13` | ✅ |
| R31-12 | 既有门禁不低于下限 | 本机复跑 `lint` 22/22、`release-flow` 32/32；其余套件（platform-verify 194、negative 130、access 206、rbac 113、unit 375、quality 348、integration 231、demo-reset 70、web build 0）采信 QA 在相同内容工作树的实跑记录（本需求无 Java/Vue/SQL 改动，风险面收敛） | ✅ |

关键原文（提交后复跑）：`PASS [32] 提交区间 79e1f1c..a7c7f13 卫生：7 个 *pom.xml + web/package.json + web/pnpm-lock.yaml 无改动、迁移仅 0/1 条 A V14；判据自检 4/4（正例绿 / 改 V13 / 新增 V15 / 改模块 pom 均红）`。

## 3. 架构一致性核查

- 蓝图要求「不抽新脚本、第 32 条内联自检、pom 覆盖 7 个、lockfile 用 pnpm-lock、S6-1/S1 同族修复、分母仍 32、memory 纠错」——逐项落位：实现集中于 `scripts/release-flow-verify.sh`（`check_commit_hygiene:342`、`hygiene_self_test:377`、`resolve_git_base:335`、`reset_binding_slots:450`），未动 `scripts/lib.sh`、Makefile、`RELEASE_FLOW_TOTAL`。
- 无重复实现：提交卫生判据全仓唯一；`scripts/tests/rbac/s0-schema-migration.sh` 对 V14 的引用是「迁移是否已执行」，语义不同，不构成重复。
- 既有入口签名不变（`static_checks`/`cleanup`/`pass`/`fail`），S6-1、S1 为最小侵入补丁；架构与代码质量无返工项。

## 4. 对变更单 §7 两项待拍板的裁决

1. **V14 允许 0/1 条**：接受。实测空区间必须绿（纯度 A3），严格 1 条会在合并后重演「一合并就假红」的原始缺陷。
2. **自检内联、分母仍 32**：接受。保持 AC-20 分母语义与静态 8/8 不变，且避免「脚本在但没人跑」的第二种假绿。

## 5. 缺陷与遗留（均 P3，不阻塞）

- **P3-1 文档漂移**：TECH-CHANGE §3.2 与 §3.4-H2 仍写 `pom.xml web/package.json web/package-lock.json`；实现（按蓝图）为 7 个 `*pom.xml` + `web/pnpm-lock.yaml`，`memory.md:37` 已纠正。任务夹按约定不可变，且实现/蓝图/memory 三者一致，判为非阻塞；下次触碰变更单时同步文字。
- **P3-3 `--static-only` 不覆盖第 32 条**：判据退化只在全量门禁暴露（TECHNICAL/QA 已登记）。保持现状，不阻塞。
- **设计边界（非缺陷）**：提交信息含 runId 未机器校验（runId 每轮变化，硬校验会假红）；只读 `.git` 环境 worktree 不可用 → 第 32 条 fail-closed，风险已登记于变更单 §6。
- **P3-2 已闭环**：QA 提出的「本轮改动未提交」在验收阶段已提交（`a7c7f13` 交付 + 本报告提交），工作树干净。

## 6. 未复跑项

- 非 release-flow 的既有套件采信 QA 同内容实跑数据（见 R31-12）；本需求不改业务代码，未重复全量套件。
- 无 UI 变更，QA 未附浏览器截图；本需求不需要。

## 7. 验收结论

r31 实现与变更单、架构蓝图一致：第 32 条已成为提交区间纯函数并具备正负变异自检，提交后真实区间（含 `A V14` 与脚本自身改动）仍 32/32；r30 AC-23 的提交卫生要求（runId + 任务夹、干净树）已满足。无 P0/P1/P2、无架构返工项 → **通过**。

验收结论：✅ 通过
