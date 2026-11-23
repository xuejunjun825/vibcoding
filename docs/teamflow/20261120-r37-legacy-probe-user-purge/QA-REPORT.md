# QA-REPORT · r37 遗留探针停用账号清理（legacy-probe-user-purge）

- 分支：`feat/legacy-probe-user-purge`（未提交，工作区即交付）
- 任务夹：`docs/teamflow/20261120-r37-legacy-probe-user-purge/`
- 运行日志：`logs/teamflow/tf-mwookg49-88sn5b/`（探针与结论笔记）
- 结论：**可验收**。未发现 P0/P1/P2 缺陷；3 条 P3 观察（见 §3）。

## 1. 范围与环境

本轮交付：临时账号清理真源收敛到 `scripts/lib/temp-accounts.sh`（4 前缀白名单 `s[3-8]` / `t6p` / `t6probe` / `probe` + 14 名种子保护 + 只删 `DISABLED`），薄入口 `scripts/legacy-probe-purge.sh`，两个消费方接入（`gate-order-verify` 18→19 条含负向自检、`platform-demo-reset` 75→76 条），`Makefile:106` 帮助文案 15→19，`memory.md` 同主题行扩为 r36–r37。

环境：本机演示栈（8080 网关 / 8081 demo / 8082 req / 8083 deliver / 8848 Nacos）全部 health=200；MySQL 直连可用。
**环境限制（非交付缺陷）**：本轮宿主多次整段冻结（`deliver-service.log` 反复 `HikariPool-1 - Thread starvation or clock leap detected`，delta 15–16 分钟；`api-gateway.log` `PrematureCloseException`；load avg 4–10），导致两处完整套件中断（§4）。改动面在冻结前后均实测通过。

## 2. 用例与结果

### 2.1 静态与口径（L0）

| # | 检查 | 命令 | 实际 |
|---|---|---|---|
| L0-1 | 六个脚本语法 | `bash -n scripts/lib/temp-accounts.sh scripts/legacy-probe-purge.sh scripts/gate-order-verify.sh scripts/platform-demo-reset.sh scripts/lib/gate-fixture.sh scripts/release-flow-verify.sh` | exit 0 |
| L0-2 | 结构契约 35 条 | `bash logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-t3-gate-order-contract.sh` | 35/35 PASS |
| L0-3 | #19 正则负向探针 | `bash logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-t3-negative-probe.sh` | 4/4 PASS |
| L0-4 | 代码规范/脚本 lint | `bash scripts/tests/lint.sh` | 22/22 PASS |
| L0-5 | 正则单一真源 | `grep -rn 'TEMP_ACCOUNT_REGEX=' scripts/ \| grep -v temp-accounts.sh` | 无输出（唯一生成点 `scripts/lib/temp-accounts.sh:35`） |
| L0-6 | 消费方无旧正则副本 | `grep -c 't6p\|^(s3\|s5\|s6\|s7\|s8)' scripts/gate-order-verify.sh scripts/platform-demo-reset.sh` | 各 0 |
| L0-7 | 门禁条数口径 | `grep -n 'TOTAL_CHECKS=' scripts/platform-demo-reset.sh`；`tail -1 scripts/gate-order-verify.sh` | `TOTAL_CHECKS=76`；末行文案 19/19 |
| L0-8 | Makefile 只改一行 | `git diff -U0 -- Makefile \| grep -c '^@@'` | 1（`@@ -106 +106 @@`，`15 条`→`19 条`，无格式化） |
| L0-9 | 文档口径同步 | `grep -n '19 条\|76' Makefile docs/teamflow/memory.md` | `Makefile:106` 19 条；memory:41/42/43 已写 r37 19/76（`Makefile:95`「75 条断言」为 AC-9 明示允许的唯一存量口径） |

### 2.2 清理原语（L1，真库正负向，`logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-qa-purge-acceptance.sh`，12/12 PASS）

| 用例 | 实际 |
|---|---|
| 基线：孤儿 join 行 0、14 名保护账号 ACTIVE=14 | `orphan=0 protected=14` |
| AC-2 历史 4 条遗留已清 | `sys_user` id 72/76/77/452 均 0 行 |
| AC-2 首跑删除 + 关系行级联 | 插 `t6pqa<ts>`（DISABLED+2 join）→ rc=0 `purged=1`，user_left=0、join_left=0 |
| AC-8 幂等二跑 | `purged=0`，rc=0 |
| AC-4 只删 DISABLED | 插 ACTIVE `probe9qa<ts>`（+2 join）→ 仍在、`status=ACTIVE`、join=2（探针随后自删） |
| AC-3 保护守卫负向 | `TEMP_PROTECT_EXTRA=probe9neg<ts>` → rc=1、零删除、报错点名 `TEMP_PROTECT_EXTRA` |
| AC-2 DB 不可连不假绿 | `PLATFORM_DB_PORT=1` → rc=1，报错指向 `make platform-up` |
| 终态 | `orphan=0 protected=14 users_left=0` |

### 2.3 门禁（L2）

| 用例 | 命令 | 实际 |
|---|---|---|
| demo-reset 76 条 ×2 | `bash scripts/platform-demo-reset.sh`（连跑两次） | 76/76 ×2，`[76] 停用账号：DISABLED 0 / 种子 ACTIVE 14（purged=0）`，5/5 段 |
| gate-order 第 18/19 条复算 | `bash logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-qa-gate18-19.sh` | 9/9 PASS（三数全 0、保护 14；负向 `probe9<ts>` 停用计数 0→1→0） |
| gate-order 全量 | `bash scripts/gate-order-verify.sh` | A1–A4/B1–B3 共 12 项 OK（reset 76/76、rfv 44/44×2、access 206/206×3、静态 5/5），随后静默 exit 1（B4/B5 前，见 §3 P3-1 与 §4） |
| platform-verify | `bash scripts/platform-verify.sh` | 194/194 PASS |
| platform-test-negative | `bash scripts/platform-test-negative.sh` | 130/130 PASS |
| rbac s7（本轮重跑） | `bash scripts/rbac-verify.sh s7` | **挂起 >5 分钟未返回**（同期 8080/8082 health 200、登录直连 1–2ms、MySQL 无阻塞会话），判环境；T3/T5 轮曾 9/9，全段运行见 §4 |

### 2.4 提交面（L3）

| 检查 | 实际 |
|---|---|
| `git status --porcelain` | 仅 9 项：Makefile、memory.md、4 个 scripts 改动、任务夹、2 个新脚本；无 target/dist/node_modules/日志 |
| `git check-ignore`（真实路径） | `req-service/target`、`web/node_modules`、`web/dist`、`api-gateway/target`、`.nacos`、`logs/teamflow`、`.run` 全部 ignored |
| 依赖/迁移/pom | `git status` 无 pom.xml / package.json / lock / *.sql；migration 最新为 `V9`，无新增 V15 |
| 提交区间 | `git diff --name-status $(git merge-base main HEAD)..HEAD` 为空（本轮尚未提交，提交归 host） |
| 历史任务夹 | r36 任务夹零改动 |

## 3. 缺陷表

未发现 P0/P1/P2 缺陷。

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| P3-1 | P3 | 门禁脚本（既有代码） | 让任一子门禁失败（如 `rbac-verify` 退出非 0）后运行 `bash scripts/gate-order-verify.sh` | 打印 FAIL 行指出哪条门禁未达标，并继续跑到第 18/19 条 | `run_*` 的 `out="$(...)"` 在 lib.sh `set -e` 下直接终止进程：夹具 EXIT 复位照常打印，但没有 FAIL 行、到不了第 18/19 条（`scripts/gate-order-verify.sh:236-249` 等，HEAD 基线同款，非本轮引入） | AC-5/AC-8（诊断性） | - | - |
| P3-2 | P3 | 清理原语 | 阅读 `scripts/tests/rbac/_lib.sh:154 rbac_purge_users` 与 `scripts/lib/temp-accounts.sh` | 全仓只有一处临时账号清理实现 | rbac 测试自带的 `rbac_purge_users <prefix>` 仍保留 `^${prefix}[a-z0-9]*$` 且按任意状态删；与 temp-accounts「只删 DISABLED」语义不同、TECHNICAL/注释明示「停用谁 vs 删谁」，属有意分工 | AC-1 | - | - |
| P3-3 | P3 | 技术文档 | 执行 TECHNICAL §10-9 的残留复核 grep | 期望为空 | 命中合法存量：`Makefile:99`（wbs-backfill「共 18 条」）、`docs/teamflow/memory.md:39`（r26 历史「原记 15 条为笔误」） | AC-10（文档口径） | - | - |

## 4. 人工补测清单（环境限制，非交付缺陷）

| 项 | 原因 | 方法 / 工具 |
|---|---|---|
| `gate-order-verify` 完整 19/19 ×2 | 本轮 3 次运行均在子门禁处被宿主冻结/传输故障打断（`curl` 28 超时、HTTP 000；服务端日志显示 reset 已完成），且上述 P3-1 使中断无 FAIL 行 | 独占栈低负载窗口：`make platform-down && make platform-up` 后 `bash scripts/gate-order-verify.sh`，连跑两次取 19/19；工具：shell |
| `rbac-verify` 全段 113/113 ×2 | 全段运行在 s7 登录处 `curl: (28)` 超时；本轮分段重跑 `s7` 亦挂起 >5 分钟未返回（同时段 8080/8082 health 200、登录直连 1–2ms、MySQL processlist 无阻塞），本轮 diff 未触及 rbac 任何文件 | 同上窗口：`bash scripts/rbac-verify.sh`，同 `RBAC_RUN_TAG` 连跑两次；先单跑 `bash scripts/rbac-verify.sh s7` 取 9/9 |
| 提交后 rfv 第 32 条（BASE..HEAD 区间纯判据） | ADR-2026-08-27：提交归 host，本轮未提交 | 提交后 `bash scripts/release-flow-verify.sh`（44/44） |

## 5. 架构与接口一致性

- **模块抽取符合技术方案**：唯一真源 `scripts/lib/temp-accounts.sh`（9 个对外函数 + 4 个私有助手），薄入口 `scripts/legacy-probe-purge.sh`，两个消费方通过 `source` 复用；`grep -rn 'TEMP_ACCOUNT_REGEX=' scripts/` 仅命中该文件，消费方无第二份正则/第二套删除条件。
- **调用点逐条核对（静态，存在性 + 参数形状）**：`gate-order-verify.sh:80-82`（db_ready / guard / purge）、`:299-305`（users_left / disabled_left / roles_left / disabled_usernames）、`:327-343`（disabled_left×3）；`platform-demo-reset.sh:740-742`（purge / disabled_left / protected_active_count，均无参）；`legacy-probe-purge.sh:37-73`（db_ready / guard / disabled_left / disabled_usernames / purge / users_left / orphan_join_left）——全部在真源中定义，无「调用不存在的成员」。
- **依赖方向**：`scripts/*` → `scripts/lib/*`，无反向依赖、无循环；未发现复制粘贴实现漂移（P3-2 为有意分工）。
- 交付形态契约：本轮未注入 → N/A。

## 6. 复跑命令（本轮证据）

```bash
bash logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-qa-purge-acceptance.sh   # 12/12
bash logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-qa-gate18-19.sh          # 9/9
bash logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-t3-gate-order-contract.sh # 35/35
bash logs/teamflow/tf-mwookg49-88sn5b/scripts/r37-t3-negative-probe.sh      # 4/4
bash scripts/platform-demo-reset.sh                                        # 76/76
bash scripts/platform-verify.sh                                            # 194/194
bash scripts/platform-test-negative.sh                                     # 130/130
bash scripts/tests/lint.sh                                                 # 22/22
```

## 7. 结论

交付满足 PRD 的行为契约：白名单清理、遗留删除、只删停用、守卫零删除、DB 不可连不假绿、gate-order 18/19 判据与 demo-reset 第 76 条均以真实数据库实测；`rfv 44`、`access 206`、`verify 194`、`negative 130`、`lint 22` 计数不减。两处整套门禁因宿主冻结未取到绿（rbac 的 s7 分段在本轮窗口亦挂起），属环境限制（§4）。**建议验收通过**。
