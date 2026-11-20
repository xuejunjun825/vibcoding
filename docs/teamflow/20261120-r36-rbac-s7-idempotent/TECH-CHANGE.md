# 技术变更单：rbac-verify s7 分段幂等缺陷修复（r36）

- 基线依赖：`docs/teamflow/20261120-r35-s7-self-fixture/`（HEAD=fdaeac8，分支 feat/rbac-s7-idempotent）
- 类型：tech-driven rework（无新增/改写用户可见 AC；不改任何历史任务夹）
- runId：tf-mwojp33j-jol2iv

## 1. 背景与目标（一句话）

`scripts/tests/rbac/s7-negative-nine.sh` 用 `$RANDOM` 造临时账号且不留清理，历史 DISABLED 同名账号会让建号 409 被吞、`login_as` 撞 403 USER_DISABLED 直接 die；本单把它连同 s3/s5/s6/s8 的同型残留改为「run 级唯一命名 + 起跑自愈 + 退出清理 + 创建失败显式红」，并把 `rbac-verify` 纳入 `gate-order-verify` 的顺序无关断言。

实测残留（`db_q` 查 t1，修复前）：DISABLED 临时账号 527 条——s3 103 / s5 90 / s6 81 / s7 168 / s8 85；s7 样本 `s7a19762`、`s7b30973`、`s7a32224`。临时角色 0 条（reset 会清自定义角色）。注意 `s7a*`/`s7b*` 不匹配 demo-reset 的停用兜底 REGEXP（`^(s[0-9]+[a-z]*u|...)`），reset 救不了。

## 2. 影响面（仅测试脚本与门禁）

| 文件 | 改动 |
|---|---|
| `scripts/tests/rbac/_lib.sh` | 新增 `rbac_run_tag` / `rbac_purge_users <prefix>` / `rbac_purge_roles <prefix>` |
| `scripts/tests/rbac/s3-decision-order.sh` | 命名换 tag；起跑 purge；trap 清理（原只 DISABLED） |
| `scripts/tests/rbac/s5-role-lockout.sh` | `S5PROBE_*`/`S5CHANGED_*`/临时用户换 tag；起跑 purge；清理改 purge 用户 + API 删角色 |
| `scripts/tests/rbac/s6-user-org.sh` | 临时用户/组织名换 tag；起跑 purge；清理加删用户 |
| `scripts/tests/rbac/s7-negative-nine.sh` | **主修**：两用户一角色换 tag；建号失败显式红；起跑 purge；cleanup 顺序修正 |
| `scripts/tests/rbac/s8-audit-reset.sh` | 命名换 tag；**补**起跑 purge + `trap cleanup EXIT`（原来完全没有） |
| `scripts/gate-order-verify.sh` | `run_rbac`；A/B 序列插入 rbac；静态 4→5、收尾 2→3；总数 15→18 |

不动：Java/DB/迁移/pom/web/Makefile、`platform-demo-reset` 的停用 REGEXP、rbac-verify.sh 分段框架与 113 计数口径。

## 3. 技术方案

### 3.1 命名唯一化（拒绝单靠 $RANDOM）

```bash
# _lib.sh
rbac_run_tag() { printf '%s' "${RBAC_RUN_TAG:-$(date +%m%d%H%M%S)$$$RANDOM}"; }
RBAC_RUN_TAG="$(rbac_run_tag)"   # 全数字；允许环境覆盖（QA 构造撞名用）
```

- s7：`TMP_USER1="s7u1${RBAC_RUN_TAG}"`、`TMP_USER2="s7u2${RBAC_RUN_TAG}"`、`TMP_ROLE="S7ROLE_${RBAC_RUN_TAG}"`
- s3：`s3u${RBAC_RUN_TAG}`；s5：`s5u…` + `S5PROBE_…` + `S5CHANGED_…`；s6：`s6u…` + `s6组织…`；s8：`s8u…` + `S8ROLE_…`
- 长度上界：`8+12+7+5=32`，恰不超过 `sys_role.code VARCHAR(32)`；`sys_user.username VARCHAR(64)` 富余。
- 新名 `sNu<纯数字>` 天然匹配 demo-reset 的兜底 REGEXP，trap 未执行时 reset 仍能停用，不新增 Java 改动。

### 3.2 起跑自愈 + 退出清理（清理失败置红）

```bash
# 用户无 DELETE API → db_q 直连（与 s5:197、platform-verify.sh:783 同源先例）
rbac_purge_users() {  # <prefix> 如 s7：删 ^s7[a-z0-9]*$（含历史残留），复核 0 条
  local prefix="$1" tid
  tid="$(db_q "SELECT id FROM sys_tenant WHERE code='${PLATFORM_TENANT:-t1}'" | head -n1)"
  [[ -n "$tid" ]] || return 1
  db_q "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id AND u.tenant_id=ur.tenant_id
        WHERE u.tenant_id=${tid} AND u.username REGEXP '^${prefix}[a-z0-9]*$'" >/dev/null || return 1
  db_q "DELETE ud FROM sys_user_domain ud JOIN sys_user u ON u.id=ud.user_id AND u.tenant_id=ud.tenant_id
        WHERE u.tenant_id=${tid} AND u.username REGEXP '^${prefix}[a-z0-9]*$'" >/dev/null || return 1
  db_q "DELETE FROM sys_user WHERE tenant_id=${tid} AND username REGEXP '^${prefix}[a-z0-9]*$'" >/dev/null || return 1
  [[ "$(db_q "SELECT COUNT(*) FROM sys_user WHERE tenant_id=${tid} AND username REGEXP '^${prefix}[a-z0-9]*$'" | head -n1)" == "0" ]]
}
# 角色有 DELETE /admin/roles/{id}：GET 列表按 code startswith($prefix) 逐个删（仅 S5/S7/S8 非内置）
rbac_purge_roles() { local prefix="$1" id; admin_login >/dev/null || return 1
  api_call GET /api/req/admin/roles >/dev/null || return 1
  for id in $(printf '%s' "$HTTP_BODY" | jq -r --arg p "$prefix" '.[]? | select(.code|startswith($p)) | .id'); do
    api_call DELETE "/api/req/admin/roles/$id" >/dev/null
    [[ "${HTTP_CODE:-000}" == "200" || "${HTTP_CODE:-000}" == "204" ]] || return 1
  done; }
```

每段（s3/s5/s6/s7/s8）：
1. `admin_login` 后先 `rbac_purge_users <prefix> || { err "…"; report_counts; exit 1; }`；角色前缀段再加 `rbac_purge_roles`。这同时清掉 527 条历史残留，也让「同 tag 重跑」确定性通过。
2. `trap cleanup EXIT`、`trap 'exit 130' INT TERM`（EXIT 只跑一次）。
3. cleanup 顺序：还原被改的授权（s3/s7 现有逻辑保留）→ `rbac_purge_users` → `rbac_purge_roles` → 复核 0 条；任一步失败 `err "sN cleanup: <哪一步> <怎么修>"` + `exit 1`。

### 3.3 创建失败显式红

s7 建角色（:44 附近）与两个建号（:108、:131）捕获 `HTTP_CODE`：非 200/201 → `err` + `report_counts` + `exit 1`，不允许再靠后续 `login_as` 的 403 兜底；s8 的角色/用户创建同样加显式检查。保留原有 `>/dev/null` 仅用于成功路径的噪声抑制，判定用 `HTTP_CODE` 而非重定向。

### 3.4 gate-order-verify 顺序无关

- `run_rbac(){ env GATE_TAG= bash scripts/rbac-verify.sh; }` 解析 `rbac-verify: N/M`，要求 rc=0、N==M、**N≥113**、`gate_fixture_problems` 为空。
- 序列 A：`reset → rfv → rbac → access`（4 步）；序列 B：`reset → access → rbac → rfv → rbac`（5 步）——覆盖「rfv 之后跑」与「access 之后跑」两种曾致红的顺序。
- 静态断言 +1：s3/s5/s6/s7/s8 必须含 `rbac_run_tag`、`rbac_purge_users`、`trap cleanup EXIT`，且不得再出现 `s[0-9][a-z]*\$RANDOM` 式命名。
- 收尾断言 +1：t1 下 `username REGEXP '^(s3|s5|s6|s7|s8)[a-z0-9]*$'` 的用户 **0 条**，`code REGEXP '^(S5|S7|S8)'` 的角色 **0 条**（run 结束库面无残留，比「已停用」更强）。
- 总条数 15→**18**，同步 header 注释与 `TOTAL_N == 18`。

### 3.5 明确不做

不新增 `DELETE /admin/users` API（需新动作码 + 种子 + 迁移 + s0/s2 计数）；不改 `rbac-verify.sh` 分段与 113 下限；不做并发并行跑段（trap/purge 非并发安全，仍串行）。

## 4. 行为兼容影响

- **产品用户可见行为：无**。不改 Java/DB/迁移/前端/API，不改任何权限判定。
- 测试数据行为变化（需在 QA 报告写明）：门禁临时主体从「停用留库」改为「本段结束即删除」；首次运行会一次性删除 527 条历史 DISABLED 临时账号（仅 `^s3…^s8` 测试命名空间，14 个种子账号与演示数据不匹配）。`audit_log` 为追加表且无 FK，历史审计行保留；`sys_user_role`/`sys_user_domain` 先清 join 行不留孤儿。
- `gate-order-verify` 单轮时长增加约 2 倍（多跑 3 次 rbac），仍不进 `make test`/`make verify` 默认链。

## 5. 回归与验证计划（下限只增不减）

| 命令 | 判据 |
|---|---|
| `bash scripts/rbac-verify.sh`（连续两次） | 113/113 均绿；两次之间不做 reset，第二次靠起跑 purge 通过；s7 不得出现 403 USER_DISABLED |
| 撞名复现：`RBAC_RUN_TAG=26010100000012345` 先跑一次 + 手工造同名 DISABLED 账号再跑一次 | 第二次仍 113/113（起跑 purge 生效）；且该 tag 账号跑完 0 条 |
| `bash scripts/gate-order-verify.sh` | 18/18，A/B 全绿，收尾残留 0 |
| `bash scripts/release-flow-verify.sh` | `RELEASE_FLOW_TOTAL=44 CASE=44 PASS=44 FAIL=0` |
| `bash scripts/platform-verify.sh` / `platform-test-negative.sh` / `access-verify.sh` / `platform-demo-reset.sh` | ≥194 / ≥130 / 206 / 75，全绿 |
| `mvn -o test` / `bash scripts/lint.sh` | ≥392 用例 0 失败 / 22 项全绿 |
| 静态 | 改动脚本 `bash -n` 全过；`grep -n '\$RANDOM' scripts/tests/rbac/s{3,5,6,7,8}-*.sh` 只允许出现在 `rbac_run_tag` 内 |

开发自检顺序：s7 单段 → 双跑 rbac → gate-order 静态段 → 全量 gate-order（需 `make platform-up`）。QA 必须独立复跑双跑 rbac 与 gate-order，不接受只跑一次。

## 6. 风险与回滚

- 风险 1：purge 前缀正则误删人工造的测试账号——命名空间 `s3…s8` 已在 memory 声明为门禁专用；若有人手工建号必须避开该前缀。
- 风险 2：cleanup 在栈已停时 fail → 分段红。这是契约要的（清理失败必须可见），不是 flake；重跑前先 `make platform-up`。
- 风险 3：`gate-order` 变长导致超时——单轮预计 6–10 分钟；若 CI 预算紧，可去掉 B 序列第二次 rbac（18→17），但 A/B 各保留一次 rbac 不可省。
- 回滚：`git revert <commit>` 单提交即可；无 schema/数据迁移。已删的 527 条临时账号不可恢复，但均为测试垃圾，种子与演示数据不受影响。

## 7. 假设与待澄清

1. 用户删除无 API，选择 `db_q` 直连清理（已有 s5:197 / platform-verify:783 先例）；若团队要求走 API，则本单需扩为「新增 DELETE /admin/users + 动作码 + 种子 + 迁移 + s0/s2 计数」，工作量与影响面另议。
2. 清理按**整段前缀**（`^s3`…`^s8`）而非仅本轮 tag——否则收尾「停用临时用户 0 条」不成立；代价是首次运行一次性删除 527 条历史残留。
3. 把 s6/s8 一并改造（用户只点名 s3/s5）：同缺陷同修，否则新收尾断言必红。
4. `gate-order-verify` 15→18（静态 +1、序列 B +1、收尾 +1）；如认为单轮过长，可只保留 B 的一次 rbac → 17 条。
5. 分支沿用 `feat/rbac-s7-idempotent`；提交信息含 runId=tf-mwojp33j-jol2iv 与任务夹路径，diff 不含 pom/package/lock/迁移，判据全部读工作树脚本（不依赖 `git diff` 脏树状态）。
