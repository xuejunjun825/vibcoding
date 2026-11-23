# QA 报告 · r38 孤儿 join 行门禁（orphan-join-gate）

- 需求：`docs/teamflow/20261123-r38-orphan-join-gate/TECH-CHANGE.md`（本夹无 PRD，AC 以该技术变更单 + 架构蓝图接口清单为准）
- 分支：`feat/orphan-domain-cleanup`；交付形态：**脚本级改造**（4 个脚本 + `Makefile:106` 一行 + docs），无 Java / 前端 / 迁移 / 种子改动
- 测试环境：macOS 本机真栈（gateway 8080、nacos 8848、MySQL 127.0.0.1:3306/devops_platform），mysql 9.6 客户端；`make platform-up` 已起
- 结论：**可验收**（未发现 P0/P1/P2，3 条 P3 观察；门禁 20/20、独立探针 51/51、lint 22/22 全绿）

## 1. 交付面核对（事实清单）

| 文件 | 改动 | 核对结论 |
|---|---|---|
| `scripts/lib/temp-accounts.sh` | +63/-? 2 hunk | 新增 `temp_orphan_join_where` 唯一谓词生成点；四个计数器全部由它渲染；`temp_orphan_join_left` 改整行；`temp_purge_disabled` 零改动（`git diff -U0` 中无 `temp_purge_disabled(` 的增删行） |
| `scripts/orphan-join-purge.sh` | 新增，755 | 默认 dry-run；`--apply` 两条 DELETE 均由 `${where_*}` 渲染；未知参数 rc=2 |
| `scripts/legacy-probe-purge.sh` | +13/-8 | 证据行 `… users_left=<n> orphans_role=<n> orphans_domain=<n>`，退出码按**整串**比较，无 awk/cut |
| `scripts/gate-order-verify.sh` | +65/-? | 19→20 条；第 20 条只断言不自愈 + domain 孤儿负向自检并入 `r37_probe_cleanup`（未注册第二个 EXIT trap） |
| `Makefile:106` | 1 hunk / 1 行 | `（19 条 → 20 条，独占演示栈）`；`make help` 实测同步 |
| `docs/teamflow/memory.md` | :41/:43 | 「r38 起 20 条」、r36–r38 段，与代码口径一致 |

提交面卫生：`git status --porcelain` 仅 5 M + 2 ??（任务夹、新脚本），无 `target/`、`node_modules/`、日志；`.gitignore:19 logs/`、`.gitignore:5 target/` 覆盖到位。

## 2. 架构核对（蓝图符合性，M3 门禁项）

- **单点抽取到位**：全仓 `grep -rl "LEFT JOIN sys_user u ON u.id ="` 除 `scripts/lib/temp-accounts.sh` 外**零命中**；4 个计数器（role/domain × left/users）与 purge 的 2 条 DELETE 全部由 `temp_orphan_join_where` 渲染 → 「查的」与「删的」锁在同一谓词，未出现第 5 份手写 SQL。
- **两套清理语义未合并**：`temp_purge_disabled`（按账号 id 级联）与孤儿谓词（按行无主）仍分离；purge 只调 `temp_accounts_db_ready`、刻意不调 `temp_accounts_guard`（源码有注释说明理由，非遗漏）。
- **端口前置技巧**：`scripts/orphan-join-purge.sh` 复制了 `scripts/legacy-probe-purge.sh:19-27` 的「抓 `PLATFORM_DB_PORT` → source → 回填」，注释指回真源，符合蓝图可接受复制的前提。
- **无重复实现 / 无抽象缺失 / 无结构破坏**：改动面严格 4 脚本 + 1 行 Makefile，未触碰 Java、迁移、种子。
- 结论：**架构零缺陷**（无 P1 行）。

## 3. 执行证据（命令 + 退出码 + 判据）

| # | 命令 | RC | 判据 / 结果 |
|---|---|---|---|
| E1 | `bash logs/teamflow/tf-mwsm0khj-297ost/scripts/r38-qa-acceptance.sh` | 0 | `RESULT: PASS=51 FAIL=0`（S1 接口 16 / S2 真栈 12 / S3 边界 7 / S4 门禁契约 7 / S5 架构 7 / S6 终态 2） |
| E2 | `bash scripts/gate-order-verify.sh`（独占演示栈，约 11 分钟） | 0 | `gate-order-verify: 20/20`；A1–A4、B1–B5 全 OK；收尾 5 条含「孤儿 join 行 role=0 domain=0（负向自检 0→1→0）」与 r37「probe9112310530114757 0→1→0」；退出复位 `platform-demo-reset` 通过 |
| E3 | `bash scripts/tests/lint.sh` | 0 | `lint 全部通过（22 项）` |
| E4 | `bash scripts/orphan-join-purge.sh` | 0 | `mode=dry-run role=0 domain=0 distinct_role_users=0 distinct_domain_users=0`，库内行数不变 |
| E5 | `bash scripts/orphan-join-purge.sh --apply`（二跑） | 0 | `deleted_role=0 deleted_domain=0` + `post role=0 domain=0`（幂等） |
| E6 | `bash scripts/legacy-probe-purge.sh` | 0 | `purged=0 remaining=0 users_left=0 orphans_role=0 orphans_domain=0` |
| E7 | `PLATFORM_DB_PORT=1 bash scripts/orphan-join-purge.sh` | 1 | `MySQL 不可连（127.0.0.1:1/devops_platform）`，无 `mode=dry-run` 成功行 → 不假绿 |
| E8 | `PLATFORM_DB_PORT=1 bash scripts/legacy-probe-purge.sh` | 1 | 同上，端口覆盖前置未回归 |
| E9 | `bash scripts/orphan-join-purge.sh --bogus` | 2 | 未知参数先于连库判定 |
| E10 | `bash -n` × 4 脚本 | 0 | 4/4 语法通过 |
| E11 | `make help \| grep gate-order` | 0 | `A/B 双序列证明门禁顺序无关（20 条，独占演示栈）` |
| E12 | `git diff -U0 -- Makefile \| grep -c '^@@'` | 0 | `1`（单 hunk，仅 :106 文案） |

### 3.1 真栈行为（S2，可逆夹具：`user_id` 用 9 前缀，结束必删）

插入 `sys_user_role` + `sys_user_domain` 各一条无主行（tenant=t1、用户 id 不存在）→ 两个计数器各 +1 → dry-run 只报数（`role=1 domain=1`，库内行数不变）→ `--apply` 删除且 `deleted_role≥1 deleted_domain≥1`、`post 0/0` → `sys_user`/`sys_role`/**真实用户**的 join 行计数全部不变（证明「删除集 ≡ 断言集」且不碰账号）→ 二跑 `deleted=0` 幂等 → `legacy-probe-purge` 两分量皆 0 且 rc=0 → S6 终态 0/0 且夹具零残留。

### 3.2 接口一致性（S1，纯静态 + 单行契约）

按蓝图 `api` 字段逐条 grep：6 个导出函数全部存在；生成器逐字输出 `LEFT JOIN sys_user u ON … WHERE u.id IS NULL` 且恰好一行；未登记表 / 别名 `u` / 非法标识符三种入参均拒绝且 stdout 为空；`join_left` 整行形状 `^orphans_role=\d+ orphans_domain=\d+$` 且与两个单值计数器一致；两个消费方（legacy-probe、gate-order）都只整行/整串比较、无 `awk`/`cut` 拆 token；purge 内无手写孤儿 SQL。

## 4. 人工补测清单

| 项 | 为何不能自动化 | 方法 / 工具 | 结论 |
|---|---|---|---|
| 真实历史脏库（413 条）由 `--apply` 一次清空 | 历史 413 条已在 T4 用同一谓词清掉，当前库为 0/0，不可回灌真库 | 在备份库上 `mysqldump` 回放 r38 前的 59 个 9 前缀 user_id 行，再跑 `orphan-join-purge.sh` | 环境限制，非交付缺陷；已用夹具等价复放（T5 413 条全路径 + 本轮 S2 小样本），链路一致 |
| 浏览器 / 视觉 / 跨浏览器矩阵 | 本次无 UI、无前端构建产物变动；且宿主沙箱不可启动 Chromium | — | N/A（无可见界面） |

## 5. 缺陷表

**未发现 P0/P1/P2 缺陷。** 以下 3 条为 P3 观察（不阻塞验收）：

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| OBS-01 | P3 | 脚本库（架构观察） | 在 `source scripts/lib.sh` 之后执行 `cmd; RC=$?` 形式捕获非零退出 | 调用方按常规方式取到 rc | `scripts/lib.sh:3` 的 `set -euo pipefail` 传播给 source 方，第一个非零命令即静默 abort（本报告探针首版停在 S3：无输出 + rc=1，排查耗时） | TECH-CHANGE §4 幂等与不缩水（间接） | `grep -n 'set -euo pipefail' scripts/lib.sh` | 建议在 lib 头注释点明「errexit 会传播，消费方须用 `\|\| rc=$?` 或 `if` 条件」；本轮未改代码 |
| OBS-02 | P3 | `scripts/lib/temp-accounts.sh` | `PLATFORM_DB_PORT=1` 后直接调 `temp_orphan_join_left` | 期望 rc=1 | rc=0 且 `orphans_role=0 orphans_domain=0`：`scripts/lib.sh:50-57` 由 `PLATFORM_DB_URL` 重算端口并覆盖同名环境变量，必须先用「抓端口→source→回填」技巧才失效（已按该技巧实测 rc=1 且零半行） | TECH-CHANGE §3.1「任一分量失败 return 1 不出半行」 | `PLATFORM_DB_PORT=1 bash -c 'p=$PLATFORM_DB_PORT; source scripts/lib.sh; PLATFORM_DB_PORT=$p; source scripts/lib/temp-accounts.sh; temp_orphan_join_left'` | 行为本身正确；建议在孤儿块注释补一句「库函数层需回填端口才可复现失败路径」，避免下一位 QA 误判 |
| OBS-03 | P3 | 文档（可追溯性） | 阅读任务夹 TECH-CHANGE §5 / 直接跑 `orphan-join-purge.sh` | 文档说明首跑应清 413 条 | 实际首跑即 0/0：413 条已在 **T4** 用同一谓词清掉，该操作事实只记在 `logs/teamflow/tf-mwsm0khj-297ost/r38-t4-orphan-baseline.md`，任务夹内无痕 | TECH-CHANGE §5.1 基线数字 | `bash scripts/orphan-join-purge.sh` | 建议验收时把「413 已由 T4 预清、QA 阶段 `--apply` 应为 0/0（幂等）」写进验收报告，避免误判为缺陷 |

## 6. 结论

- **功能**：判定 / 清理 / 证据行 / 门禁第 20 条全部按契约工作；`--apply` 与 `dry-run` 集合一致，账号与真实 join 行零影响，幂等成立（二跑 deleted=0），DB 不可连与未知参数均不假绿。
- **门禁不缩水**：`gate-order-verify` 20/20（含 r37 第 19 条、r38 第 20 条两条负向自检）、A/B 双顺序基线一致、退出复位 `platform-demo-reset` 76/76；`lint` 22/22。
- **架构**：谓词单点、四计数器 + 两 DELETE 全量渲染、两套清理语义分离、端口技巧有真源指回 —— 符合蓝图，无重复实现。
- **判定：可验收**。3 条 P3 均为观察/建议，不需要修复即可交付。
