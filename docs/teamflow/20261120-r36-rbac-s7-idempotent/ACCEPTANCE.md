# r36 验收报告 · rbac 门禁临时主体幂等（s3–s8 造名/清理收敛 + gate-order 18 条）

- 任务夹：`docs/teamflow/20261120-r36-rbac-s7-idempotent/`（基线 r35 · runId=`tf-mwojp33j-jol2iv`）
- 分支：`feat/rbac-s7-idempotent`（HEAD=fdaeac8，改动未提交，工作树即交付面）
- 被测改动：7 个 shell 脚本 + `docs/teamflow/memory.md`（`git diff --stat` = 8 文件，331+ / 59−）
- 结论：**✅ 通过**。技术单声明的三件事（造名唯一化、跑完即删、失败显式红）与 gate-order 18 条全部落地并经本轮独立复跑验证；架构与注入蓝图一致，无偏离、无重复实现；仅 3 条 P3 观察项，不阻断。

## 1. 验收对象说明（PRD 未新增用户可见 AC）

本需求是 tech-driven rework：r35 TECH-CHANGE 明写「无新增/改写用户可见 AC，不改任何历史任务夹」。因此本轮把**技术单 §1/§3 的目标** + **注入的架构蓝图** + **技术单 §5 的回归下限**当作验收判据逐条核对，而不是虚构新 AC。产品用户可见行为（权限判定、API、前端、DB）声明不变，本轮以 diff 面为证。

## 2. 逐条核对表

| # | 判据（来源） | 证据（文件:行 / 命令） | 结论 |
|---|---|---|---|
| A1 | 造名唯一化：五段一律 `rbac_run_tag`，全数字，`RBAC_RUN_TAG` 可覆盖（技术单 §3.1） | `scripts/tests/rbac/_lib.sh:146` 定义 `printf '%s' "${RBAC_RUN_TAG:-$(date +%m%d%H%M%S)$$$RANDOM}"`；`_lib.sh:217` 进程内统一 `RBAC_RUN_TAG="$(rbac_run_tag)"`；s3/s5/s6/s7/s8 各 1–3 处调用；五段 `$RANDOM` 计数均为 **0** | ✅ |
| A2 | 起跑自愈 + 退出清理：每段 `admin_login` 后先 purge，且 `trap cleanup EXIT` + `trap 'exit 130' INT TERM`（§3.2） | 五段 `grep -c 'trap cleanup EXIT'` 均 = 1；s3:82 / s6:60 / s7:91-94 / 各段起跑 purge 均在；s8:63-64 为本轮新补（此前完全没有 trap） | ✅ |
| A3 | 删除顺序硬约束：先删用户、再删角色，否则 `ROLE_IN_USE 409`（蓝图点名的第二个根因） | `_lib.sh:154-183` `rbac_purge_users` 先 `sys_user_role`→`sys_user_domain`→`sys_user` 并复核 0；s7 cleanup `:67-70` = 还原授权→purge_users→purge_roles；s5/s8 同序；全仓 `rbac_purge_users()`/`rbac_purge_roles()` 各**仅 1 处定义** | ✅ |
| A4 | 报告同一根因不再被吞：建号/建角色失败按 `HTTP_CODE` 显式 `err + exit 1`（§3.3） | `scripts/tests/rbac/s7-negative-nine.sh:77` `require_created()`，调用点 `:142`（角色）`:148`/`:172`（两个用户）；s8 建角色/建号同样加显式码校验 | ✅ |
| A5 | 清理失败必须置红（团队约定，禁止 `\|\| true` 吞掉主体清理） | s7/s8/s3/s6 的 `rbac_purge_*` 全部 `\|\| { err …; exit 1; }`；`rbac_purge_roles` 对 409 立即 `err + return 1`（`_lib.sh:201`） | ✅（s3/s6 授权/组织还原仍静默，见 P3-2） |
| A6 | s8 条件化还原：仅本段改脏过才回写快照，避免把「起跑前就脏」锁死 | `s8-audit-reset.sh:36-38` `ADMIN_MUTATED`、`:49` 条件判断、`:165` reset 成功归 0 | ✅ |
| A7 | gate-order 加 `run_rbac`：rc=0、PASS==TOTAL、**≥113**、`gate_fixture_problems` 空 | `scripts/gate-order-verify.sh:189-204`；实跑 B5 = `rbac-verify 113/113 且基线干净` | ✅ |
| A8 | 序列：A 一字不改（4 步），B 尾部追加一次全段 rbac（5 步）（**蓝图原文**，非 TECH-CHANGE §3.4 的旧写法） | `gate-order-verify.sh:207-215`：A `reset→rfv→access→access`、B `reset→access→rfv→access→rbac`；实跑 A/B 全绿 | ✅ |
| A9 | 静态契约 4→5、收尾 2→3、总数 15→18，header/`TOTAL_N` 同步 | `:14` 头注释「18 = 1+5+9+3」、`:147-176` 第 5 条静态契约、`:217-260` 三条收尾、`:262` `TOTAL_N == 18`；实跑 **18/18** | ✅ |
| A10 | 收尾残留按前缀口径 0 条（用户 `^(s3\|s5\|s6\|s7\|s8)[a-z0-9]*$` + 角色 `^(S5\|S7\|S8)`） | `:239-256`；实跑 `收尾：rbac 临时主体残留 0 条` | ✅ |
| A11 | 幂等：同一 `RBAC_RUN_TAG` 连跑两次均 113/113，无 `403 USER_DISABLED` | 本轮独立复跑（见 §4）：RUN1/RUN2 均 `113/113`，11 段逐段全绿，`USER_DISABLED` 命中 **0**，跑后 `users=0 roles=0` | ✅ |
| A12 | 行为兼容：不改 Java/DB/迁移/pom/web，不改 `rbac-verify.sh` 与 `platform-demo-reset.sh` | `git status --porcelain` 仅 7 脚本 + memory + 任务夹；两文件不在 diff 中；无 V15 迁移 | ✅ |
| A13 | 回归下限不减：rfv 44、platform-verify ≥194、negative ≥130、access 206、rbac 113、gate-order 18、demo-reset 75、lint 22、mvn ≥392 | 本轮实跑：gate-order **18/18**（内含 rfv **44/44 ×2**、access **206/206 ×4**、reset **75/75 ×2**、rbac **113/113**）、lint **22/22**；其余见 §5（采信 QA，diff 不相交） | ✅ |
| A14 | 文档与提交面：memory 同主题行替换（无 changelog 追加）、任务夹自洽、无构建产物入库 | `docs/teamflow/memory.md` 仅 r34 行订正为「r36 起 18 条」+ 新增 r36 一行；`.gitignore` 复核 `target/ node_modules/ dist/ .run/ logs/` 均 IGNORED | ✅ |
| A15 | **架构一致性（强制 M3）**：三原语落在既有公共缝 `_lib.sh`，五段只留各半截，依赖方向不变不成环，无重复实现、无该抽未抽 | 见 §3 | ✅ |

## 3. 架构一致性检查（M3 · 强制项）

- **模块抽取按蓝图**：`rbac_run_tag` / `rbac_purge_users` / `rbac_purge_roles` 落在 `scripts/tests/rbac/_lib.sh:146-217`，未新建目录或层级；这是 r18 起 s3–s8 的既有公共缝（文件头明写「不是段脚本」），不是为本次需求新造的抽象层。
- **各段只保留自己那半截**：s3/s7 保留「还原被改的授权」，s6 保留「删组织（`ORG_ID`）」，s8 保留「条件化还原 `ADMIN_MUTATED`」——与蓝图逐字一致；s5:197 的 `sys_user_role` 直连前置删除原位保留（它是第 10 条断言的前置，不是清理）。
- **依赖方向**：`lib.sh → _lib.sh → 段 → rbac-verify.sh → gate-order-verify.sh` 无反向 `source`；`gate-order` 以子进程调用 `rbac-verify.sh` 并 `env GATE_TAG=` 清空自身 tag，无环、无「第二套清理写法」。
- **无重复实现/无适配漂移**：三个原语全仓各仅 1 处定义；五段 `$RANDOM` 归零；`rbac_run_tag` 的全数字设计同时满足 purge 前缀与 `platform-demo-reset.sh:40` 的 `TEMP_ACTIVE` 兜底正则（`sNu<tag>` 落进 `[0-9]*$`），把「名字契约分裂」这一处历史隐患收口。
- **未改动的边界按蓝图守住**：`rbac-verify.sh`（分段/113 下限/末行契约）与 `platform-demo-reset.sh` 均未改，避免「门禁被削弱」与「门禁被修好」混在同一次提交。
- **结论**：**无偏离、无重复实现、无破坏既有结构**，不触发 ⚠️ 的架构返工条件。技术单 §3.4 的序列写法与代码不一致属**文档滞后**（P3-1），不是实现偏离——代码与注入蓝图一致，且代码选择的顺序（rbac 站 B 队尾）才是安全的：s8 内含两次 demo-reset，插在 rfv 与 access 之间会清掉前一步夹具。

## 4. 本轮验收独立复跑证据（acceptance 阶段实跑）

| 命令 | 结果 |
|---|---|
| `bash scripts/gate-order-verify.sh` | **18/18**，exit 0；A2/B3 rfv `44/44`、A3/A4/B2/B4 access `206/206`、A1/B1 reset `75/75`、B5 rbac `113/113`；三条收尾全 OK；退出复位通过 |
| `RBAC_RUN_TAG=26112014550055555 bash scripts/rbac-verify.sh`（连跑两次，中间不 reset） | RUN1 `113/113` rc=0；RUN2 `113/113` rc=0；s3 8/8、s4 7/7、s5 12/12、s6 9/9、s7 9/9、s8 7/7；`USER_DISABLED` 命中 0；跑后 `users=0 roles=0` |
| `bash scripts/tests/lint.sh` | 22/22 通过 |
| `git status --porcelain` + `--ignored` | 仅 7 脚本 + memory + 任务夹；构建产物全部 IGNORED |

## 5. 采信 QA、本轮未复跑的项（说明理由）

`platform-verify 194/194`、`platform-test-negative 130/130`、`mvn -o test 392（0 失败）` 采信 QA 报告：本轮 diff 与这三条链路**不相交**（无 Java/无 pom/无 demo 数据改动，三脚本未改），且它们已被 gate-order 的静态契约与 A/B 序列覆盖为「夹具/基线不变」；`access`/`rfv`/`reset` 已在本轮实跑中重复验证 4/2/2 次。这符合验收「关键路径独立复跑、无关链路采信 QA」的分工，不构成漏验。

## 6. 遗留与建议（均 P3，不阻断）

| 编号 | 位置 | 问题 | 建议 |
|---|---|---|---|
| P3-1 | `docs/teamflow/.../TECH-CHANGE.md:78`、`:23`、`:111` | 技术单写 A `reset→rfv→rbac→access`、B `reset→access→rbac→rfv→rbac` 且称「A/B 各保留一次 rbac」，与代码/蓝图（A 原样、B 队尾一次）不一致；总数 18 与代码一致，故只是文档滞后。任务夹按 ADR-0008 不可变，本轮只记录 | 下轮若再动 gate-order，以代码与 memory 为准；文档口径在 r37 技术单里订正 |
| P3-2 | `scripts/tests/rbac/s3-decision-order.sh:61,65`、`scripts/tests/rbac/s6-user-org.sh:48` | 还原授权 / 删组织仍 `>/dev/null 2>&1 \|\| true`：失败会静默把 QA/DEV 留在 DENY、或留下 `s6组织<tag>` 孤儿（收尾只断言用户/角色，不断言组织） | 改成与 s7 `restore_role_grants … \|\| exit 1` 同口径；s6 组织删除失败也置红 |
| P3-3 | `scripts/tests/rbac/_lib.sh:206` | `rbac_purge_roles` 的残留复核 `SELECT COUNT(*) FROM sys_role WHERE code LIKE '${prefix}%'` 未带 `tenant_id`，而 DELETE 走当前租户 API；跨租户同前缀角色会造成**假红**（当前全局 S5/S7/S8 角色 0，未触发） | 复核语句补 `AND tenant_id = ${tid}`，与 DELETE 同域 |

另记两条非本轮引入、按技术单明确不处理的技术债：① `sys_notification.user_id` 无外键，删用户后可能留孤儿通知行（无断言、不影响门禁）；② 断言计数样板 `TOTAL/PASS/FAIL + pass()/fail()/report_counts()` 在五段各一份（抽它会重写五段报告口径，风险大于收益）。`sys_user_domain` 的 413 条历史孤儿行 purge 前后不变，非本轮引入。

## 7. 验收结论

功能、架构、门禁三条均满足：技术单目标全部落地且可复现（同 tag 双跑 113/113、gate-order 18/18），架构与蓝图逐条一致、无重复实现，回归下限只增不减，改动面严格限于 7 个测试脚本 + memory。3 条 P3（技术单文档滞后、两处静默还原、跨租户复核口径）均不影响功能与门禁结论，登记为后续清理项。

验收结论：✅ 通过
