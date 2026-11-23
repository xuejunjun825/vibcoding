# r38 验收报告 · 孤儿 join 行判定扩两表 + 413 条历史孤儿清账（orphan-join-gate）

- 任务夹：`docs/teamflow/20261123-r38-orphan-join-gate/`（runId=`tf-mwsm0khj-297ost`，tech-driven rework）
- 分支：`feat/orphan-domain-cleanup`（基于 main `8c39a3a`）；改动未提交，工作树即交付面（沿用 r36/r37 惯例，流转时补提交信息）
- 被测改动：`scripts/lib/temp-accounts.sh`、`scripts/orphan-join-purge.sh`（新增）、`scripts/legacy-probe-purge.sh`、`scripts/gate-order-verify.sh`、`Makefile:106`、`docs/teamflow/memory.md`；`git diff --stat` = 5 文件 131+/24−，无 Java / pom / 迁移 / 种子
- 结论：**✅ 通过**。技术单目标全部落地，判定与删除同源于唯一谓词；架构与蓝图一致、无重复实现；20 条门禁与 8 项回归下限经本轮独立复跑全绿。仅 3 条 P3 观察，不阻断。

## 1. 验收对象说明（本夹无 PRD）

本需求为 tech-driven rework，未新增/改写任何用户可见 AC（TECH-CHANGE 抬头）。故以 **TECH-CHANGE §1/§3/§5** 的目标与判据、**注入的架构蓝图接口清单**、**§5.2 回归下限**为验收判据逐条核对，不虚构新 AC。

## 2. 逐条核对表

| # | 判据（来源） | 证据（文件:行 / 命令） | 结论 |
|---|---|---|---|
| A1 | 判定扩两表：role/domain 各一个单值计数器，走 `^[0-9]+$`，查询失败 return 1（TECH §3.1） | `scripts/lib/temp-accounts.sh:180-202`；真栈 `temp_orphan_join_left` → `orphans_role=0 orphans_domain=0`（r37 时 domain=413 的基线见 T4 记录） | ✅ |
| A2 | 唯一谓词生成点：四计数器 + purge 两条 DELETE 全由 `temp_orphan_join_where` 渲染，无第 5 份手写 SQL（蓝图重复风险 1） | 生成器 `scripts/lib/temp-accounts.sh:165-179`；调用点 `:182/:188/:194/:200`、`scripts/orphan-join-purge.sh:86-93`；全仓 grep（排除 logs 检查器）仅此一处 `LEFT JOIN sys_user u ON u.id =` | ✅ |
| A3 | `temp_orphan_join_left` 恰好一行、任一分量失败不出半行（蓝图接口） | `scripts/lib/temp-accounts.sh:204-213`（两分量先算入变量再 printf）；实测输出单行且与两单值计数器一致；非法表/别名 `u` 均 rc=1 且 stdout 为空 | ✅ |
| A4 | `orphan-join-purge.sh`：默认 dry-run、`--apply` 才删、删除集≡断言集、删后复核 0、不调账号 guard、DB 不可连/未知参数分明（TECH §3.2） | `scripts/orphan-join-purge.sh:48-111`；dry-run 行 `mode=dry-run role=0 domain=0 distinct_*=0` rc=0；`--apply` 二跑 `deleted_role=0 deleted_domain=0` + `post 0/0`（QA E5）；`--bogus` rc=2；`PLATFORM_DB_PORT=1` rc=1；源码无 `temp_accounts_guard` 调用（:57 只调 db_ready） | ✅ |
| A5 | `legacy-probe-purge.sh` 证据行含两分量、退出码整串比较（TECH §3.4） | `scripts/legacy-probe-purge.sh:66-90`；实测 rc=0 且 `purged=0 remaining=0 users_left=0 orphans_role=0 orphans_domain=0`；`PLATFORM_DB_PORT=1` rc=1 | ✅ |
| A6 | `gate-order-verify.sh` 19→20，第 20 条只断言不自愈 + 负向自检并入 `r37_probe_cleanup`（TECH §3.3 / 蓝图） | `scripts/gate-order-verify.sh:269-412`；`TOTAL_N == 20`（:408）；实测 **20/20 exit 0**，第 20 条 `role=0 domain=0（负向自检 0→1→0）`；仅 INT/TERM trap，未注册第二个 EXIT trap；`domain_id` 取 `biz_domain`（:377） | ✅ |
| A7 | 413 条历史域孤儿清账、59 个 distinct user_id（TECH §1/§5.1） | `logs/teamflow/tf-mwsm0khj-297ost/r38-t4-orphan-baseline.md`：`sys_user_domain 413→0`、distinct=59、`sys_user_role 0→0`；当前 dry-run 0/0，CLI 幂等（首跑不可复现，见 P3-1） | ✅ |
| A8 | `Makefile:106` 仅 19→20 一处 hunk（TECH §3.5 / AC） | `git diff -U0 -- Makefile` = 1 hunk 1 行；`make help` 实测 `…（20 条，独占演示栈）` | ✅ |
| A9 | memory 同主题两行同步、无 changelog 追加（TECH §3.5） | `docs/teamflow/memory.md:41` 追加「r38 起 20 条」、`:43` 标题扩为 r36–r38 并追加 r38 段；`git diff` 仅这 2 行（4+/4−） | ✅ |
| A10 | 文档纠偏：域表是 `biz_domain`、不存在 `sys_domain`（蓝图重复风险 4） | `TECH-CHANGE.md:47-48` 已改正并注明迁移行号；实现 `scripts/gate-order-verify.sh:377` 按 `biz_domain` 取、失败退化 1 | ✅ |
| A11 | 回归下限只增不减（TECH §5.2） | 本轮实跑：gate 内含 **reset 76/76 ×2、rfv 44/44 ×2、access 206/206 ×4、rbac 113/113**；另跑 `platform-verify 194/194`、`platform-test-negative 130/130`、`lint 22/22` | ✅ |
| A12 | diff 卫生：仅 scripts/docs/Makefile；无 Java/pom/迁移/种子；构建产物不入库（TECH §5.4） | `git diff --stat` 5 文件；`git diff -- '*.java' pom.xml '*/db/migration/*'` 为空；`git diff --check` 干净；`.gitignore` 覆盖 `target/ node_modules/ dist/ .run/ logs/ .env*`；未跟踪仅任务夹与新脚本 | ✅ |
| A13 | 假绿防线：计数非数字不当 0、负向自检、DB 不可连必红（TECH §5.3） | `_temp_count` 的 `^[0-9]+$` 闸门；第 20 条负向自检 0→1→0；`PLATFORM_DB_PORT=1` 两入口均 rc=1 且无成功行 | ✅ |
| A14 | **架构一致性（强制 M3）** | 见 §3 | ✅ |

## 3. 架构一致性检查（M3 · 强制项）

- **单点抽取到位**：`temp_orphan_join_where` 是唯一谓词生成点，role/domain 的计数、distinct 计数与 purge 的两条 DELETE 全部整行引用它；未出现第 5 份手写 SQL——直接消除了 r36 的 527 条 / r37 的 413 条「复制后漂移」根因。
- **两套清理语义未合并**：`temp_purge_disabled`（按停用账号 id 级联）零改动（`git diff -U0` 中无其增删行）；孤儿 purge 只删无主 join 行、刻意不套 14 名保护 guard，且源码注释写明理由——符合蓝图「不可合并」约束。
- **前置技巧可追溯**：`scripts/orphan-join-purge.sh:26-34` 复制「抓 `PLATFORM_DB_PORT` → source → 回填」，注释指回 `scripts/legacy-probe-purge.sh:19-27` 真源，符合蓝图「可接受复制但须指回」的前提。
- **依赖方向与装配序**：`temp-accounts.sh` 不 source `lib.sh`（由调用方提供 `db_q`/`db_exec`）；`orphan-join-purge.sh` 先 lib.sh 后卫生库；gate/legacy 作为下游只做整行/整串比较、无 token 解析，无环、无反向依赖。
- **第 20 条与既有结构同形**：以收尾块内联实现（第 16–19 条同为内联块，无外部消费方），语义/判据与蓝图一致；蓝图接口清单中的 `r38_orphan_join_check()` 名称并非可调用契约，未影响任何调用方，不构成结构偏离。若后续要单测该条，可再抽函数，属可选改进。
- **文档纠偏已完成**：`sys_domain` → `biz_domain` 已写入 TECH-CHANGE，代码按正确口径落地，无「照文档跑得到空结果」的坑。
- **结论**：**无偏离蓝图、无重复实现、无该抽未抽、无结构破坏**，不触发 ⚠️ 返工条件。

## 4. 本轮验收独立复跑证据（acceptance 阶段实跑）

| 命令 | 结果 |
|---|---|
| `bash scripts/gate-order-verify.sh` | exit 0，`gate-order-verify: 20/20`；A1 reset 76/76、A2/B3 rfv 44/44、A3/A4/B2/B4 access 206/206、B5 rbac 113/113；收尾 5 条全 OK（含 r38 孤儿 0→1→0、r37 探针 0→1→0）；退出复位通过 |
| `bash scripts/platform-verify.sh` | exit 0，`194/194 项通过（AC-1..AC-20）` |
| `bash scripts/platform-test-negative.sh` | exit 0，`130/130 条负向用例全部按预期被拒且不落库` |
| `bash scripts/tests/lint.sh` | exit 0，`lint 全部通过（22 项）` |
| `bash scripts/orphan-join-purge.sh` | rc=0，`mode=dry-run role=0 domain=0 distinct_role_users=0 distinct_domain_users=0` |
| `bash scripts/legacy-probe-purge.sh` | rc=0，`purged=0 remaining=0 users_left=0 orphans_role=0 orphans_domain=0` |
| `bash scripts/orphan-join-purge.sh --bogus` | rc=2（用法错误先于连库） |
| `PLATFORM_DB_PORT=1` 跑 orphan / legacy | 均 rc=1（不假绿） |
| 生成器探针（source lib + temp-accounts） | 两表输出逐字等于约定行；未知表 rc=1；别名 `u` rc=1；`join_left` 单行 0/0 |
| `bash -n` × 4 脚本 / `make help` / `git diff -U0 Makefile` | 4/4 语法通过；帮助行 20 条；1 hunk 1 行 |

## 5. 采信 QA / 未复跑项

- `mvn`、`web` typecheck/build、浏览器视觉：本需求无 Java/前端/迁移/种子改动，明确不在改动面（TECH §2），不构成漏验。
- `--apply` 实删路径：本轮库内已 0/0，实删以 QA E5（预置夹具 1/1 → 删净 → 二跑 0）与 T4 的 413→0 记录为证；验收阶段只复跑 dry-run + 幂等，不重复造脏数据。

## 6. 遗留与观察（均 P3，不阻断）

| 编号 | 问题 | 建议 |
|---|---|---|
| P3-1 | 413 条已在 T4 用同一谓词预先清掉，TECH-CHANGE §5.1 的「首跑 domain=413」在本次运行不可复现；事实仅存于 `logs/teamflow/tf-mwsm0khj-297ost/r38-t4-orphan-baseline.md` | 本报告即验收留痕；后续以「首跑 0/0 属幂等、非缺陷」为准，勿据旧数误判 |
| P3-2 | `scripts/lib.sh:3` 的 `set -euo pipefail` 会传播给 source 方，调用方用 `cmd; RC=$?` 取非零退出会被静默 abort（QA OBS-01，既有行为） | 在 lib 头注释点明「消费方须用 `\|\| rc=$?` 或 `if` 条件」；本轮不改代码 |
| P3-3 | 库函数层单设 `PLATFORM_DB_PORT=1` 会被 `lib.sh:50-57` 按 `PLATFORM_DB_URL` 重算覆盖（QA OBS-02，既有行为）；入口脚本已用「抓端口→source→回填」规避 | 在孤儿块注释补一句函数层复现条件，避免下一位 QA 误判 |

另记流程事实：改动未提交（`git status` = 5 M + 2 ??），提交信息需在流转时补 `runId=tf-mwsm0khj-297ost` 与任务夹路径（TECH §5.4）；本轮验收以工作树为交付面，不影响功能/架构结论。

## 7. 验收结论

功能判据 A1–A13 全部满足且可复现；架构 M3 单点、分工、依赖方向、文档纠偏均符合蓝图，无重复实现与结构破坏；回归下限 76/44/206/113/194/130/22 全部实测达到或超过，只增不减。3 条 P3 为既有行为/流程留痕，不需要返工即可交付。

验收结论：✅ 通过
