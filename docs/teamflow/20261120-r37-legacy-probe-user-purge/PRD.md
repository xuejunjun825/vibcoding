<!-- meta: summary="把 r36 的临时主体清理从 s3–s8 前缀扩到 4 条遗留探针停用账号（t6probeu/t6pd72599/t6pe72710/probe11272），用显式前缀白名单 + 显式保护名单做单点清理，并把 gate-order 收尾断言升级为「白名单前缀停用用户 0 + 角色 0」外加一条会红的负向自检。" -->
基线依赖：docs/teamflow/20261120-r36-rbac-s7-idempotent/（r36 建立的 `scripts/tests/rbac/_lib.sh` 造名/清理原语、`gate-order-verify` 18 条与 `platform-demo-reset` 75 条口径不得回归；本轮只在其上加白名单前缀与一条负向自检）

# r37 遗留探针用户清理与收尾断言升级

## 1. 背景与目标

### 1.1 现状（2026-11-20 实测）

r36 已把 s3–s8 段临时用户/角色的造名与清理统一到 `scripts/tests/rbac/_lib.sh`（`rbac_run_tag` 唯一名 + `trap` + `rbac_purge_users`/`rbac_purge_roles`），`scripts/gate-order-verify.sh` 收尾第 18 条只按 `^(s3|s5|s6|s7|s8)[a-z0-9]*$` 计数。但库里还留着 4 条更早的探针账号，它们不在任何段的前缀里，因此从未被清掉：

| id | username | 来源 | 现状 | 关系行 |
|---|---|---|---|---|
| 72 | `t6probeu` | 2026-11-13 r18/rbac 探针 | `tenant_id=1`，`status='DISABLED'` | 无 |
| 76 | `t6pd72599` | 同上 | 同上 | 无 |
| 77 | `t6pe72710` | 同上 | 同上 | 无 |
| 452 | `probe11272` | 2026-11-17 探针 | 同上 | **`sys_user_role` 2 行：role 5=PMO、role 7=QA**；`sys_user_domain` 0 行 |

全库 `status='DISABLED'` 总数恰为 4（没有其他停用账号）；14 个种子账号当前全部 ACTIVE；`^(s[3-8]|t6p|t6probe|probe)[a-z0-9]*$` 在当前库只命中这 4 条，无 ACTIVE 命中；`^(S5|S7|S8)` 角色数为 0。

两个由此暴露的缺口：

1. **清理范围不是可扩展的白名单**。r36 的 `rbac_purge_users` 按段前缀清扫，每来一批新探针就要改段脚本；历史一次性探针（r18、11-17）没有归属段，于是永久残留。清理需要一个**显式前缀白名单 + 显式保护名单**的单点原语，新增探针只加一行前缀。
2. **收尾断言只覆盖已知段前缀**。`gate-order-verify` 第 18 条对 `t6p*`/`probe*` 是盲区，且"断言写得对"与"断言真的会红"从未被证明——r36 的 527 条历史残留正是在断言存在的情况下攒出来的。收尾断言必须覆盖白名单前缀，并用一条**故意造残留**的负向自检证明它会红。

另有一个删除正确性问题：`probe11272` 挂着 PMO/QA 两条 `sys_user_role`。只 `DELETE FROM sys_user` 会留下 `user_id=452` 的孤儿关系行，角色成员数/会签集合继续把它算进去。清理必须先删 join 行再删用户（与 `rbac_purge_users` 同序）。

### 1.2 目标

- **G1 清掉 4 条遗留账号**：一次运行后 `t6probeu`/`t6pd72599`/`t6pe72710`/`probe11272` 在 `sys_user` 中为 0，`sys_user_role`/`sys_user_domain` 无对应孤儿行；第二次运行 `purged=0`（幂等）。
- **G2 单点白名单**：前缀清单只在一处定义（≥ `s[3-8]`、`t6p`、`t6probe`、`probe`），每条带来源注释；未来新增探针只加前缀，不改门禁逻辑。
- **G3 显式保护**：14 个种子账号写成常量，清理前先校验"保护名 ∩ 白名单 = ∅"与"14 名存在且 ACTIVE"，命中即拒绝执行并置红；清理后 14 名仍 ACTIVE。
- **G4 断言升级 + 自证**：`gate-order-verify` 收尾从"rbac 段前缀残留 0"升级为"白名单前缀用户（任意状态）0 + 其中 DISABLED 0 + `^(S5|S7|S8)` 角色 0"，并新增一条负向自检（现场插一条 DISABLED 探针，断言必须计数 ≥1，删除后回 0），条数 18→19。
- **G5 复位后仍干净**：`platform-demo-reset` 在 Java 停用步骤之后执行同一清理，并新增第 76 条断言（白名单前缀 DISABLED 0 + 14 种子 ACTIVE 14），75→76。
- **G6 不扩面**：不改后端 API/Java/迁移/pom/web；diff 仅 `scripts/`、`docs/`，外加 `Makefile:106` 一行帮助文案；六条门禁计数只增不减。

### 1.3 口径说明（与历史的关系）

- 本需求**不声明 `取代：`**：r30 的 "demo-reset 只把临时号 UPDATE 成 DISABLED、不改行" 是 `DemoFixture.DISABLE_GATE_TEMP_USERS` 的实现注释，从未作为 AC 写进任何任务夹；r30 的 75 条断言（尤其 `TEMP_ACTIVE=0`、`KEPT_USERS=14`）本轮一条不减，只在其后新增第 76 条。
- 行为变化的确切边界：**仅"白名单前缀命中且 `status='DISABLED'`"的行由"保留"改为"删除"**；白名单之外的停用账号一条不删（需求原文"全库白名单外的停用用户 0 条"按澄清 #3 落实为"白名单前缀命中的停用用户 0 条"，见 §11 Q1）。
- `POST /internal/demo-reset`（Java）仍只停用不删行；删除只发生在 shell 侧 `platform-demo-reset.sh`，因此 r36 的 s8 段在段内两次调用 internal reset 的语义完全不变。

## 2. 用户故事与验收标准

### US-1 值班/运维：一条命令清掉历史探针，且不误伤真实账号

作为值班运维，我希望有一条命令清掉白名单前缀的遗留停用账号，并在保护账号异常时直接拒绝，而不是让我事后从审计里发现 `admin` 没了。

- 验收：AC-2（4 条被清、无孤儿行、二次幂等）、AC-3（保护名单守卫与 14 名 ACTIVE）、AC-4（只删 DISABLED）、AC-9（diff 边界与提交卫生）。

### US-2 QA：收尾断言能覆盖这批账号，而且我能证明它会红

作为 QA，我希望 `gate-order-verify` 的收尾断言覆盖"白名单前缀停用用户 0"，并且有一条负向自检证明这条断言查了库、正则会命中——否则永远不知道它是绿还是空跑。

- 验收：AC-1（白名单单点）、AC-5（收尾三项为 0）、AC-6（负向自检必红）、AC-8（六条门禁不降）。

### US-3 后续开发者：新增探针只加一行前缀

作为下一个加探针的人，我希望白名单是一处常量数组，加前缀 + 一行来源注释就生效，不需要理解门禁顺序。

- 验收：AC-1、AC-5（断言与清理共用同一常量，禁止第二份正则）。

### US-4 评审/产品：复位之后库里仍是干净的

作为评审，我希望跑完 `platform-demo-reset` 后，白名单前缀的停用账号仍是 0、14 个种子仍 ACTIVE，并且计数与脚本声明一致（75→76 同步）。

- 验收：AC-7、AC-10（文档与记忆同步）。

## 3. 验收标准

> 全部判据在 `main=81967c4` 基线上可执行；命令默认在仓库根运行。计数类判据必须由脚本自身断言，PRD 里的数字是验收时的期望值。

### AC-1（P0）白名单与保护名单单点定义，每条带来源

- 新增 `scripts/lib/temp-accounts.sh`（与 `scripts/lib/gate-fixture.sh` 同层），其中：
  - `TEMP_ACCOUNT_PATTERNS` 恰 4 个元素：`s[3-8]`、`t6p`、`t6probe`、`probe`；**每个元素上一行注释写明来源**：
    - `s[3-8]`：r36 的 s3–s8 段临时用户（`s3u`/`s5u`/`s6u`/`s7u*`/`s8u` + 全数字 tag）；
    - `t6p`：2026-11-13 r18 探针 `t6probeu`、`t6pd72599`、`t6pe72710`；
    - `t6probe`：同批 `t6probeu` 的显式家族前缀（被 `t6p` 覆盖，保留以表达来源并允许未来单独演进；清理幂等，重复前缀第二次删 0 条）；
    - `probe`：2026-11-17 探针 `probe11272`。
  - `TEMP_ACCOUNT_REGEX` 由数组拼装为 `^(s[3-8]|t6p|t6probe|probe)[a-z0-9]*$`，全脚本只此一处生成；
  - `TEMP_PROTECTED_USERS` 恰 14 名：`admin pm tl1 po1 dev1 dev2 qa1 qa2 vendor1 vendor2 biz1 suite1 mgr1 vpm1`，注释指向 `req-service/.../seed/DemoFixture.java` 与 `platform-demo-reset.sh` 的同源名单。
- 判据：`bash -c 'source scripts/lib/temp-accounts.sh; echo "${#TEMP_ACCOUNT_PATTERNS[@]}"; echo "$TEMP_ACCOUNT_REGEX"'` 输出 `4` 与上述正则；`grep -rn "t6pd72599\|t6pe72710\|probe11272" scripts/` 只命中 `temp-accounts.sh` 的来源注释（入口脚本与门禁脚本引用常量/函数，不复制名字）。
- 反例判据：`scripts/gate-order-verify.sh` 与 `scripts/platform-demo-reset.sh` 中不得再出现手写的 `^(s3|s5|s6|s7|s8)` 或 `t6p` 正则字面量（`grep -c` 为 0）。

### AC-2（P0）4 条遗留账号被删除，且不留孤儿关系行

- 新增薄入口 `scripts/legacy-probe-purge.sh`（source `scripts/lib.sh` + `scripts/lib/temp-accounts.sh`，默认执行清理；失败按"哪一项 + 怎么修"输出并 `exit 1`）。
- 首次运行输出含 `purged=4`（删除前打印 4 个 username）与 `remaining=0`；第二次运行 `purged=0`、`remaining=0`。
- 库查为 0：
  - `SELECT COUNT(*) FROM sys_user WHERE username IN ('t6probeu','t6pd72599','t6pe72710','probe11272')` → `0`；
  - `SELECT COUNT(*) FROM sys_user_role WHERE user_id IN (72,76,77,452)` → `0`；
  - `SELECT COUNT(*) FROM sys_user_domain WHERE user_id IN (72,76,77,452)` → `0`；
  - 全库孤儿：`SELECT COUNT(*) FROM sys_user_role ur LEFT JOIN sys_user u ON u.id=ur.user_id WHERE u.id IS NULL` → `0`（清理前 `probe11272` 的 PMO/QA 两行是这条判据的起点）。
- DB 不可连时必须 `exit 1` 并点名（`db_up` 前置判断），**禁止**因为 `db_q` 丢弃 stderr 而把空串当 0 输出 `purged=0` 假装成功。
- 清理顺序固定：先 `sys_user_role`/`sys_user_domain` join 行，再 `sys_user`（与 `rbac_purge_users` 同序）；删后复核 0，否则 `exit 1`。

### AC-3（P0）保护名单守卫：命中即拒绝，种子必须仍 ACTIVE

- 清理前两项校验，任一失败 `exit 1` 且**不执行任何 DELETE**：
  1. `TEMP_PROTECTED_USERS` 与白名单正则求交，非空即点名报错（"保护账号 X 命中清理前缀 Y，拒绝执行"）；
  2. 14 名在 `tenant_id` 下存在且 `status='ACTIVE'`，缺失/停用即报错并提示先修复种子（`make platform-seed` 或 demo-reset）。
- 负向证明（测试注入缝）：`TEMP_PROTECT_EXTRA=probe<纯数字>` 运行清理，`rc≠0`、stderr 点名该账号，且 `probe*` 停用行数前后不变（证明拒绝发生在删除之前）。
- 清理后：`SELECT COUNT(*) FROM sys_user WHERE username IN (<14 名>) AND status='ACTIVE'` → `14`。
- `TEMP_PROTECT_EXTRA` 只用于测试，生产默认空；注释写明"注入保护名以证明守卫会红"。

### AC-4（P0）只删 DISABLED，不动 ACTIVE 临时账号

- 清理的 WHERE 固定为 `status='DISABLED' AND username REGEXP '<TEMP_ACCOUNT_REGEX>'`，与计数共用同一常量。
- 判据：插入一条 ACTIVE 的 `probe9<纯数字 tag>`（`tenant_id` 同演示租户，`password_hash` 非空）→ 运行清理 → `purged` 不含它、该行仍在且 `status='ACTIVE'`；测试自行删除后计数回 0。
- 理由（写进脚本注释）：ACTIVE 的临时号可能正被某道门禁使用；停用语义归 demo-reset 的 Java 步骤，删除只处理"已经停用"的残留。

### AC-5（P0）gate-order 收尾断言升级：用户（任意状态）+ DISABLED + 角色 三项为 0

- 第 18 条改为三项同时为 0（全库，不带租户过滤；角色沿用 t1）：
  1. 白名单前缀用户数（**任意状态**，保留旧"任意状态 0"的严格度）；
  2. 其中 `status='DISABLED'` 的条数（需求要求的显式口径）；
  3. `^(S5|S7|S8)` 角色数。
  三项均通过 `temp_users_left`/`temp_disabled_left`/角色计数函数取自 `temp-accounts.sh`，`gate-order-verify.sh` 内不新写正则。
- 失败时打印三个数值 + 至多 5 个残余用户名 + 修复命令 `bash scripts/legacy-probe-purge.sh`。
- 起跑先调用一次清理（自愈，覆盖"上次被 kill 的负向自检残留"），失败立即 `exit 1`，不得降级为 WARN。
- 计数同步：头注释"18 条 = 1+5+9+3"→"19 条 = 1+5+9+4"，`TOTAL_N=19`，末行 `19/19`，静态断言 `TOTAL_CHECKS=76`，`run_reset` 下限 `-ge 76`；`make gate-order-verify` 连跑两次均 `19/19`，A/B 双序列顺序不变。
- 静态契约第 5 条扩展（不增条数）：断言 `temp-accounts.sh` 内"4 条前缀 + 14 名保护 + 只删 DISABLED"存在且单点。

### AC-6（P0）负向自检：故意造一条残留，断言必须红

- 新增第 19 条，四步全满足才 PASS：
  1. 先记 `temp_disabled_left` = 0（承接第 18 条）；
  2. 插入 `probe9<纯数字 tag>`（`tenant_id` 同演示租户、`status='DISABLED'`、`password_hash` 非空）；
  3. 再算 `temp_disabled_left`，必须 ≥1；若仍为 0 → FAIL 并打印实际计数，说明"断言没查库或正则没命中"；
  4. 删除该行（先 join 行、再 `sys_user`），计数回 0。
- 无论 PASS/FAIL、含中断路径（trap），插入行必须被删除；第 19 条之后（收尾末尾）再断言 `temp_disabled_left=0`，保证自检自身不制造新残留。
- 该条计入 19 分母；输出可读到 `19/19`。

### AC-7（P0）demo-reset 接入同一清理，并新增第 76 条

- `scripts/platform-demo-reset.sh` 在 Java 停用步骤（`POST /internal/demo-reset`）之后调用 `temp_purge_disabled`（只删 DISABLED），新增第 76 条断言：白名单前缀 DISABLED `0` 且 14 名种子 `ACTIVE=14`。
- `TOTAL_CHECKS` 75→76，输出 `76/76`；连续两次均 `76/76`，第二次 `purged=0`。
- 既有 75 条一条不减：`TEMP_ACTIVE=0`、`KEPT_USERS=14` 等断言与计数不动；`gate-fixture.sh` 中"75 条"注释同批更新为 76（不新增行）。
- 首次运行顺带清掉 4 条遗留账号（即 `platform-demo-reset` 一条命令也达成 G1）。

### AC-8（P0）幂等与六条门禁不降

- `make rbac-verify` 连跑两次，各 ≥113/113（`PASS==TOTAL`，非 0 退出保留）；
- `make release-flow-verify` 44/44（`RELEASE_FLOW_TOTAL=44` 不变）；
- `make gate-order-verify` 19/19，连跑两次；
- `bash scripts/platform-demo-reset.sh` 76/76（可含在 gate-order 内，但需单独可跑）；
- `make platform-verify` ≥194、`make platform-test-negative` ≥130、`make access-verify` 206、`make lint` 22；
- 无 Java 改动，`mvn` 不强制重跑；若跑须 0 失败（≥392）。
- 任一失败按"哪一项 + 怎么修"输出；禁止 `|| true`/`>/dev/null 2>&1` 吞错。

### AC-9（P1）改动面受控与提交卫生

- `git diff --name-status $(git merge-base main HEAD)..HEAD` 只含 `scripts/`、`docs/` 与 `Makefile`；不含 `pom.xml`、`web/package.json`、`pnpm-lock.yaml`、`*.sql`（不新增迁移，仍无 V15）。
- `Makefile` 仅允许第 106 行文案改动（「gate-order-verify（15 条）」→「…（19 条）」）：`git diff -U0 $(git merge-base main HEAD)..HEAD -- Makefile` 必须只有该行一处 `-`/`+`，无其它 hunk、无顺带格式化。
- 不新增删除 API、不改 Java、不给 `Makefile` 加目标。
- `.gitignore` 复核通过：`target/`、`node_modules/`、`dist/`、`.run/`、`logs/`、`.env*`、`.nacos/` 已忽略；本轮无新产物类型；禁止把 DB 导出、用户名清单、脚本输出 dump 入库。
- 分支 `feat/legacy-probe-user-purge`；提交信息含 `runId=tf-mwookg49-88sn5b` 与 `docs/teamflow/20261120-r37-legacy-probe-user-purge/`；任务夹（含本 PRD）随提交入库。

### AC-10（P1）文档与记忆同步

- `docs/teamflow/memory.md` 的「门禁临时主体幂等（r36）」同一主题行扩为 r36–r37：白名单 4 前缀 + 14 名保护 + gate-order 19 条 + demo-reset 76 条 + 首次清 4 条（72/76/77/452，含 `probe11272` 的 2 条角色关系行）；**替换同主题行，不追加变更日志段**。
- `docs/` 内引用 gate-order 18 条 / demo-reset 75 条的现行口径同步为 19 / 76；`Makefile:106` 的帮助文案同批改为「gate-order-verify（19 条）」（仅此一行，见 AC-9）。
- 本需求 PRD/TECHNICAL/QA-REPORT/ACCEPTANCE 全部位于 `docs/teamflow/20261120-r37-legacy-probe-user-purge/`。

## 4. 范围与非目标

**范围**
- `scripts/lib/temp-accounts.sh`（新增）：白名单/保护名单常量、守卫、清理、计数。
- `scripts/legacy-probe-purge.sh`（新增）：运维入口。
- `scripts/gate-order-verify.sh`：起跑自愈、第 18 条升级、第 19 条负向自检、计数 19/`TOTAL_CHECKS=76`。
- `scripts/platform-demo-reset.sh`：接入清理、第 76 条断言、`TOTAL_CHECKS=76`。
- `scripts/lib/gate-fixture.sh`：仅注释中的 75→76。
- `Makefile:106`：帮助文案「gate-order-verify（15 条）」→「gate-order-verify（19 条）」，仅此一行。
- `docs/teamflow/memory.md` + 本任务夹文档。

**非目标（本轮不做）**
- 不给平台加"删除用户"API，不改 `sys_user` 表结构、不改任何迁移。
- 不清扫白名单之外的真实停用账号（例如未来审计保留的离职账号）。
- 不重构 `_lib.sh` 既有函数语义（只在需要时新增调用，不改 `rbac_purge_users`/`rbac_purge_roles` 行为）。
- 不改 `Makefile` 除第 106 行文案之外的任何内容（不重排、不格式化、不新增目标）、不改 `platform-demo-reset` 的 Java 停用逻辑、不改 s8 段内的 `/internal/demo-reset` 调用。
- 不做"定时清理"或后台任务；清理只在显式入口、gate-order 起跑、demo-reset 尾部发生。

## 5. anti-goals / 反面目标

> 以下任何一条成立，即使 `19/19`、`76/76` 全绿，这个需求也算失败。

1. **误删真实/演示账号**：有人用 14 个种子账号之一登录得到 401/403，或 `admin` 在 `sys_user` 里不存在。技术上"清理成功"，产品上直接不可用。
2. **断言空跑**：故意插一条 DISABLED `probe9<tag>` 后 `gate-order-verify` 仍是 19/19 绿——说明收尾断言没查库或正则不匹配。这是本轮最可能出现的"技术性假绿"，也是第 19 条负向自检存在的理由。
3. **只删用户不删关系行**：`probe11272` 从 `sys_user` 消失，但 PMO/QA 的角色成员数/会签集合仍把它算进去（`sys_user_role` 留 `user_id=452` 的孤儿行）。用角色页面数人数的人会发现"删了还在"。
4. **删到正在使用的 ACTIVE 临时号**：某道门禁跑到一半，临时号被并发清理，后续 `login_as` 拿 401/403——表现为"随机红、重跑又绿"，比确定性红更难查。
5. **计数漂移**：`gate-order` 打印 `19/19` 但头注释/`TOTAL_N` 仍写 18，或 `demo-reset` 输出 76 条而 `TOTAL_CHECKS=75`；下一个人会以为断言没跑或漏跑。

## 6. 交互流程摘要

```
运维/QA
  │
  ├─(A) bash scripts/legacy-probe-purge.sh
  │      1. db_up 前置（不可连 → exit 1）
  │      2. 保护守卫：保护名单 ∩ 白名单 = ∅？14 名 ACTIVE？
  │            └─ 否 → stderr 点名 + exit 1（零删除）
  │      3. 打印将删 username（白名单 ∩ DISABLED）
  │      4. 删 sys_user_role/sys_user_domain join 行 → 删 sys_user（仅 DISABLED）
  │      5. 复核 remaining=0，输出 purged=N remaining=0
  │
  ├─(B) make gate-order-verify
  │      起跑 → (A 的清理，自愈)
  │      A 序列 reset→rfv→access→access；B 序列 reset→access→rfv→access→rbac
  │      收尾 #18：白名单用户(任意状态)=0 且 DISABLED=0 且 ^(S5|S7|S8) 角色=0
  │      收尾 #19：插 probe9<tag>(DISABLED) → 计数≥1 → 删除 → 计数=0
  │      末行 19/19
  │
  └─(C) bash scripts/platform-demo-reset.sh
         POST /internal/demo-reset（Java 只停用）→ (A 的清理，仅 DISABLED）
         第 76 条：白名单 DISABLED=0 且 14 种子 ACTIVE=14 → 76/76
```

失败路径统一：哪一项、实际值、修复命令；清理/断言失败一律非 0 退出。负向自检的中断路径由 trap 清理自己插入的行，下一次 gate-order 起跑的 (A) 也会兜底。

## 7. 优先级

| 项 | 优先级 | 说明 |
|---|---|---|
| AC-1 白名单单点 | P0 | 后续所有断言/清理的地基 |
| AC-2 4 条被清 + 无孤儿行 | P0 | 需求正文的直接目标 |
| AC-3 保护守卫 | P0 | 误删的唯一边界 |
| AC-4 只删 DISABLED | P0 | 防止清理与门禁并发互抢 |
| AC-5 收尾断言升级 | P0 | 覆盖 `t6p*`/`probe*` 盲区 |
| AC-6 负向自检 | P0 | 证明断言会红 |
| AC-7 demo-reset 76 条 | P0 | 复位后仍干净 |
| AC-8 六条门禁不降 | P0 | 回归门槛 |
| AC-9 diff 边界/提交卫生 | P1 | 评审可复核 |
| AC-10 文档与 memory | P1 | 下一轮不必重读历史 |

## 8. 依赖与风险

**依赖**
- 运行中的 MySQL（`PLATFORM_DB_*`，默认 `devops_platform`）；`gate-order-verify` 另需 req/deliver/gateway 健康（栈前置 1 条）。
- r36 已合回（`main=81967c4`）：`_lib.sh` 的 `rbac_purge_users` 顺序（先 join 行再用户）是本轮"无孤儿行"的实现参照。
- `DemoFixture` 的 14 名种子名单与 demo-reset 的 `KEPT_USERS` 是保护名单的同源依据。

**风险与缓解**
- *全库删除波及其他租户同名前缀账号*：当前库仅 t1 命中 4 条；缓解=只删 DISABLED + 白名单显式 + 保护守卫；若要求限 t1 需加租户条件（§11 Q1）。
- *demo-reset 被所有门禁调用，改动影响面大*：先只删 DISABLED、且守卫失败即红；六条门禁逐一回归（AC-8）。
- *并发门禁互抢*：清理只碰 DISABLED，跳过 ACTIVE；`probe11272` 的 PMO/QA 关系行删除后角色人数下降属预期，QA 需 `grep -rn "PMO" scripts/` 复核无固定人数断言。
- *负向自检插入行残留*：trap + 收尾再断言 0 + 下次起跑自愈，三重兜底。
- *DB 不可连导致假绿*：`db_q` 丢 stderr，必须在清理入口/断言前 `db_up`，否则空串会被当 0。
- *计数漂移*：`TOTAL_CHECKS`/头注释/`TOTAL_N`/末行四处同批改，`Makefile:106` 帮助文案同批改；grep 复核 `18`、`75`、`15 条` 残留为 0。

## 9. 里程碑建议

- **M1 清理原语**：`temp-accounts.sh` + `legacy-probe-purge.sh`；单跑 `purged=4`，二次 `purged=0`，孤儿行为 0（AC-1/2/3/4）。
- **M2 demo-reset 接入**：第 76 条 + 计数；连跑两次 76/76（AC-7）。
- **M3 gate-order 升级**：第 18 条三项、第 19 条负向自检、19 条计数；连跑两次 19/19（AC-5/6）。
- **M4 全量回归**：rbac 113×2、rfv 44、access 206、verify 194、negative 130、lint 22（AC-8）。
- **M5 收尾**：memory 同主题行更新、diff 边界与提交信息复核、任务夹入库（AC-9/10）。

## 10. 工程约束

以下为需求原文的工程动作，照做，不静默改写：

- **分支**：在 `feat/legacy-probe-user-purge` 上实现并提交（当前已检出），基线 `main=81967c4`（r36 已合回）；不新建分支、不合并 main、不 push（合并由用户决定）。
- **未提交改动**：工作区现有 1 处未跟踪目录 `?? docs/teamflow/20261120-r37-legacy-probe-user-purge/`（本任务夹）；提交时一并纳入，提交前 `git status` 复核，禁止盲目 `git add -A` 把无关文件带入。
- **提交信息**：含 `runId=tf-mwookg49-88sn5b` 与任务夹路径 `docs/teamflow/20261120-r37-legacy-probe-user-purge/`；实现与文档可一次或两次提交，最终 `git log -1` 可复核。
- **diff 边界**：仅 `scripts/`、`docs/` 与 `Makefile:106` 一行帮助文案（「15 条」→「19 条」，仅此一行）；不含 `pom.xml`、`web/package.json`、`pnpm-lock.yaml`、迁移 SQL；不新增 V15；不加删除端点。
- **`.gitignore` 复核（dev 阶段动作）**：本项目为 Maven + Node 混合栈，确认 `target/`、`node_modules/`、`dist/`、`.run/`、`logs/`（含 `logs/teamflow/`）、`.env*`、`.nacos/`、`*.log` 已忽略；本轮不引入新产物类型，无需新增规则，但提交前必须用 `git status --ignored` 抽查一次，确保没有 DB 导出/用户名清单/命令输出 dump 混入。
- **失败优先**：任何脚本失败给"哪一项 + 怎么修"，禁止 `sleep` 掩盖、禁止 `|| true` 放过。
- **不改**：`Makefile` 除第 106 行文案外的任何内容（不重排/不格式化/不加目标）、Java/`DemoFixture`/`DemoResetService`、`_lib.sh` 既有函数语义。
- **提交后复跑**：`make gate-order-verify`（19/19）与 `make rbac-verify`（≥113）各一次，确认提交未带歪。

## 11. 假设与待澄清

- **已定 · 清理范围（Q1，按缺省）**：全库、只删 `DISABLED`、不加 tenant 过滤。理由：需求原文即"全库"，当前库仅 t1 命中 4 条；反向（限 t1）需给 `temp_purge_disabled`/`temp_disabled_left` 加租户条件并同步两处断言，只有未来真的出现跨租户同名前缀时才值得做。
- **已定 · 删除证据（Q3，按缺省）**：只打 stdout（`purged=4` + 用户名列表，随 run 日志由宿主归档），git 与工作区都不留用户名清单。反向（落 `logs/teamflow/<runId>/purged-users.txt`）会让验收多一个需确认被 `.gitignore` 覆盖的文件。
- **已定 · demo-reset 第 76 条（Q4，按缺省）**：包含「14 名种子 `ACTIVE=14`」。加它会让"种子账号被手工停用"从静默变成整条门禁链红；反向（只查 DISABLED=0）会重新变成静默。
- **事实澄清（已定，不开放）**：需求原文"全库白名单外的停用用户 0 条"按澄清 #3 落实为"白名单前缀命中的停用用户 0 条"——即只删白名单内的停用行，白名单外的真实停用账号一条不动；否则会与"保护真实账号"的目标自相矛盾。
- **已定（用户批准，2026-11-20）**：Q2 采「改那一行文案」——`Makefile:106` 的「15 条」改为「19 条」，仅此一行并纳入 AC-9 diff 白名单；Q1/Q3/Q4 按上述缺省。无遗留待澄清项。
