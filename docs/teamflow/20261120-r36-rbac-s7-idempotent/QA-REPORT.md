# r36 QA 报告 · rbac 门禁临时主体幂等（s3–s8 造名/清理收敛 + gate-order 18 条）

- 任务夹：`docs/teamflow/20261120-r36-rbac-s7-idempotent/`（基线：r35；runId=`tf-mwojp33j-jol2iv`）
- 分支：`feat/rbac-s7-idempotent`　被测改动：7 个 shell 脚本 + `docs/teamflow/memory.md`（无 Java/DB/迁移/pom/web 改动）
- 结论：**可验收**。核心修复（`_lib.sh` 三原语 + 五段 trap 清理 + gate-order 18 条）全部按蓝图落地并经真实主路径执行验证；未发现 P0/P1/P2 缺陷，3 条 P3 观察项不阻断。

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 需求 | 门禁临时主体幂等：造名改全数字 tag、跑完即删、建号失败显式红、gate-order 加 rbac 与残留断言（15→18） |
| 被测文件 | `scripts/tests/rbac/_lib.sh`、`s3-decision-order.sh`、`s5-role-lockout.sh`、`s6-user-org.sh`、`s7-negative-nine.sh`、`s8-audit-reset.sh`、`scripts/gate-order-verify.sh` |
| 环境 | 本机平台栈已起（req :8082 health 200 / gateway :8080 返回 401 属预期）；MySQL 9.6 可直连（`scripts/lib.sh: db_q`）；JDK/Maven 离线仓 `$PWD/.m2/repository` |
| 浏览器 | 沙箱不可启动（宿主 token 限制，环境限制，非交付缺陷）；本需求为纯脚本交付，无 UI/视觉项 |
| 运行隔离 | 全部门禁串行执行，未并发跑两个写门禁（并发会互相改 QA/DEV 授权，见 §6 说明） |

## 2. 用例与结果（全部为已执行命令）

| # | 用例 | 命令 | 结果 |
|---|---|---|---|
| 1 | rbac 全段跑通 | `RBAC_RUN_TAG=26112014000123456 bash scripts/rbac-verify.sh` | **113/113**，exit 0，0 处 `403 USER_DISABLED` |
| 2 | 同 tag 不重置连跑（幂等/撞名复现） | 同上，**第二次**（中间不 reset） | **113/113**，exit 0，s3..s8 逐段全绿（s4 7/7） |
| 3 | 门禁顺序无关 + 收尾干净 | `bash scripts/gate-order-verify.sh` | **18/18**，exit 0；A `reset→rfv→access→access`、B `reset→access→rfv→access→rbac` 双序基线一致；收尾「rbac 临时主体残留 0 条」OK |
| 4 | 历史残留形状可被清扫 | `bash logs/teamflow/tf-mwojp33j-jol2iv/scripts/r36-qa-legacy-residue.sh` | 6/6 PASS：预置 DISABLED `s7a12345`/`s7b777` + `S7ROLE_LEGACY99`（挂在一个用户上）→ 真跑 s7 9/9，且 s7 前缀用户 0、S7% 角色 0、`sys_user_role` 孤儿 0；同 tag 立即再跑仍 9/9 |
| 5 | 建号失败显式红（不再靠 403 兜底） | `RBAC_RUN_TAG=<40 位数字> bash scripts/tests/rbac/s7-negative-nine.sh` | exit 1，`err 建临时角色 S7ROLE_<…> 失败：HTTP 400 … INVALID_ROLE_CODE`（当场点名真因）；事后 s7 残留 users=0 roles=0 |
| 6 | 接口一致性（静态） | `bash logs/teamflow/tf-mwojp33j-jol2iv/scripts/r36-qa-interface-consistency.sh` | 4/4 PASS：五段调用的每个 helper 都在 `lib.sh`/`_lib.sh` 有定义；`segment_file` 11 个目标全部存在；`run_rbac` 目标可执行；`rbac_purge_*` 调用点都带前缀参数 |
| 7 | 架构符合蓝图（静态） | `bash logs/teamflow/tf-mwojp33j-jol2iv/scripts/r36-qa-arch-conformance.sh` | 9/9 PASS：`sys_user` 删除只在 `_lib.sh`（s5 的 1 条 `sys_user_role` 前置删除为蓝图允许的例外）；依赖方向 `lib.sh → _lib.sh → 段 → rbac-verify → gate-order` 无反向 source；改动面恰为 7 脚本 + memory；段内 `$RANDOM` = 0；`rbac_run_tag` 只定义一次 |
| 8 | platform-verify | `bash scripts/platform-verify.sh` | **194/194**，退出复位通过 |
| 9 | platform-test-negative | `bash scripts/platform-test-negative.sh` | **130/130** |
| 10 | release-flow-verify | 由 gate-order A2/B3 真实执行 | **44/44** ×2，基线干净 |
| 11 | access-verify | 由 gate-order A3/A4/B2/B4 真实执行 | **206/206** ×4，基线干净 |
| 12 | platform-demo-reset | 由 gate-order A1/B1 起跑基线 + 退出复位执行 | **75/75** ×3 |
| 13 | lint | `bash scripts/tests/lint.sh` | **22/22** |
| 14 | 单测回归 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository test` | **392**（req 319 + deliver 73），Failures 0，Errors 0，BUILD SUCCESS |
| 15 | 提交面卫生 | `git status --porcelain` + `.gitignore` 复核 | 仅 7 个受控脚本 + `memory.md` + 任务夹未跟踪；`target/ node_modules/ dist/ .run/ logs/ .env*` 均 IGNORED；无构建产物/依赖/IDE/密钥；`git diff --check` 干净 |

## 3. 架构与接口一致性（强制项）

- **蓝图落地**：三个原语（`rbac_run_tag` / `rbac_purge_users` / `rbac_purge_roles`）落在既有公共缝 `scripts/tests/rbac/_lib.sh:146-217`，未新建目录或层级；五段只保留各半截（s3/s7 还原授权、s6 删组织），s8 补上此前缺失的 trap 与条件化还原（`ADMIN_MUTATED=1` 才回写快照）；`rbac-verify.sh` 与 `platform-demo-reset.sh` 未改。**无偏离、无重复实现、无该抽未抽**。
- **接口一致性**：跨脚本调用逐条 static 核对（见用例 6），包括 `_lib.sh` 未自带 `set -e` 时各调用点的 `||` 处理、`rbac_purge_*` 的 stdout 约定（删前计数 / 删角色数）与 `>/dev/null` 用法、`rbac_purge_roles` 要求「先 purge_users」的调用顺序（s5/s7/s8 的 cleanup 均为删用户→删角色）。
- **依赖方向**：无环；`gate-order` 通过子进程调用 `rbac-verify.sh`（不 source），`env GATE_TAG=` 清空避免把自身 tag 漏进 rbac。

## 4. 缺陷表

未发现 P0/P1/P2 缺陷。以下 3 条为 **P3 观察项**（不阻断验收，建议后续清理）：

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-1 | P3 | 文档 | 读 TECH-CHANGE.md 的「gate-order B 序列」段与风险第 2 条，对照 `scripts/gate-order-verify.sh` 的 banner | 文档与代码一致：A 序列不含 rbac、B 序列仅在尾部追加一次全段 rbac | 技术单写成 `reset→access→rbac→rfv→rbac` 且称「A/B 各保留一次 rbac」；代码/注入蓝图/memory 均为 A 原样、B 尾一次（总数 18 与代码一致） | AC（门禁条数与顺序） | `grep -n 'A/B 各保留一次 rbac' docs/teamflow/20261120-r36-rbac-s7-idempotent/TECH-CHANGE.md` | 该表述被删除或改为与 `scripts/gate-order-verify.sh` 的 18 条一致（任务夹不可变，故本轮只记录） |
| D-2 | P3 | 脚本健壮性 | 在 s3/s6 的 cleanup「还原授权」处制造 put_grants 失败（如临时停 req-service），再让段退出 | 还原失败应像 s7/s8 一样 `err` + 置红，避免把 QA/DEV/BUSINESS 停在 DENY | s3 `scripts/tests/rbac/s3-decision-order.sh:56-71`、s6 仍用 `>/dev/null 2>&1 \|\| true` 静默吞掉（r36 前既有写法，蓝图只要求「还原留在段内」） | 门禁清理口径一致性 | `grep -n 'put_grants .*\|\| true' scripts/tests/rbac/s3-decision-order.sh` | 该行改为失败即 `err` + `exit 1`（与 s7 `restore_role_grants … \|\| exit 1` 同口径） |
| D-3 | P3 | 脚本健壮性 | 造一个**其他租户**下 `S5*` 前缀的角色，再跑 `rbac-verify.sh s5` | `rbac_purge_roles` 的残留复核应与 DELETE 同域（带 `tenant_id`） | `scripts/tests/rbac/_lib.sh:206` 复核为 `SELECT COUNT(*) FROM sys_role WHERE code LIKE '${prefix}%'`，未带 `tenant_id`；DELETE 走 API 是当前租户，故跨租户同前缀角色会造成假红（当前全局 S5/S7/S8 角色 0，不触发） | 门禁可重复性 | `grep -n "FROM sys_role WHERE code LIKE" scripts/tests/rbac/_lib.sh` | 复核语句带 `AND tenant_id = ${tid}`（或与 DELETE 同口径） |

## 5. 人工补测清单

| 项 | 为何人工 | 方法 |
|---|---|---|
| 真实 527 条历史残留的「首次一次性清扫」 | dev 已在本轮跑前清空，事后不可复现（DB 中 s3/s5/s6/s7/s8 残留均为 0） | 已用**同形状夹具**替代（用例 4：`s7a*`/`s7b*` + `S7ROLE_LEGACY99`，正是 527 条的产生形状）；如需原样重放，可回滚至 `fdaeac8` 前复现残留后再跑 s7，成本高、不影响结论 |
| 浏览器/视觉/交互项 | 本需求为纯 shell 脚本，无 UI 改动；沙箱亦不可启动 Chrome（宿主 token 限制） | N/A |

## 6. 说明与遗留

1. 首次 rbac 全段运行（同 tag）曾在 s4 红在「预热就 403」。原因是 QA 自己的接口检查器误 `source scripts/rbac-verify.sh`，在同一演示库上**并行**起了第二套 rbac，两个进程互相改 QA 授权；改为串行后 s4 7/7、全段 113/113 两次全绿。**结论：rbac 套件与其它写门禁一样必须独占演示库（gate-order 已是串行），不是交付缺陷。**
2. `sys_notification.user_id` 无外键：删用户后可能留孤儿通知行；本轮按技术单不处理，无断言、不影响门禁（技术债已记 memory）。
3. `sys_user_domain` 历史孤儿行（413 条）在 purge 前后不变，非本轮引入；`rbac_purge_users` 已覆盖新产生的 join 行（用例 4 断言孤儿 0）。
4. `platform-demo-reset` 的停用兜底正则 `^(s[0-9]+[a-z]*u|t[0-9]+[a-z]*|probe)[0-9]*$` 与新名字形状逐条核对通过（`s3u/s5u/s6u/s7u1/s7u2/s8u + 17 位数字 tag` 全部匹配），即使 trap 被 `kill -9` 跳过仍有兜底。

## 7. 证据文件

- 检查器：`logs/teamflow/tf-mwojp33j-jol2iv/scripts/r36-qa-interface-consistency.sh`、`…/r36-qa-arch-conformance.sh`、`…/r36-qa-legacy-residue.sh`（可直接重跑；exit 0 = 全绿）
- 复用的 dev 探针：`…/scripts/r36-collision-probe.sh`、`r36-s7-collision-probe.sh`、`r36-s8-trap-restore-probe.sh`、`check-gate-order-r36.sh`
