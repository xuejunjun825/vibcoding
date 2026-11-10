# 验收报告 · r8 分支策略页（/code-policies）残留 403 修复

- **任务夹**：`docs/teamflow/20261110-r8-code-policies-403-fix/`
- **验收对象**：分支 `feat/role-page-access-fix` 上的工作区交付（6 改：`integration.ts`、`CodePolicyView.vue`、`access-verify.sh`、`DEMO-BY-ROLE.md`、`DEMO-BY-ROLE.html`、`memory.md`；3 新增：a5 脚本、任务夹、`dev1-code-policies-fixed.png`；另含 r7 的 5 张 `*-fixed.png` 未跟踪文件）
- **验收依据**：本夹 `PRD.md` §6（AC-1..AC-8）、`TECHNICAL.md` 架构蓝图、`QA-REPORT.md`
- **验收环境**：macOS · Node 22 · 演示栈在线（网关 8080 / req 8082 / deliver 8083 / web 5173；GitLab 桩 19090/19091 由验收脚本自起）· 复用 headless Chrome（CDP 9222）
- **日期**：2026-11-10 ｜ **验收人**：产品经理（验收）

**结论**：✅ 通过。8 条 AC 逐条独立复验通过，实现与架构蓝图一致，既有门禁全部复跑绿；4 条 P3 观察，均不阻断。

## 1. 逐条 AC 核验

| AC | 判据摘要 | 独立复验方式（本轮亲自执行） | 结果 |
|---|---|---|---|
| **AC-1** 页面级 14 格矩阵 | admin/dev1/tl1/vendor1 停留 `/code-policies` 且表格挂载；pm/po1/qa1 落 `/403?code=CCODE_POLICY_VIEW` 且侧栏无入口 | `node logs/teamflow/tf-mwa402ox-br1nqs/scripts/qa-r8-page-gate-matrix.mjs`（真实浏览器逐账号同源登录，非模拟） | **PASS** `PAGE-MATRIX PASS=62 FAIL=0`：4 账号停留 + 表格 2 行；3 账号落 `/403?from=/code-policies&code=CCODE_POLICY_VIEW`、无侧栏入口 |
| **AC-2** 探测语义钉住 + 可变异 | `silentForbidden` 落在同一次 http 调用；`client.ts` 403 默认分支零改动；删标记必红并报文件:行 | ①`grep -rn listConfigs(` → 仅 3 处定义/调用：`CodePolicyView.vue:69` 带 `{ silentForbidden: true }`、`ConfigTab.vue:96` 裸调用；②`git diff -- web/src/api/client.ts` 空；③对**真实文件**做内存变异（删除 `, { silentForbidden: true }`）后跑导出的审计器 → `offenders=[{file:CodePolicyView.vue, line:69}]`，还原 → `offenders=[]` | **PASS**；QA 另做过磁盘级变异（a5 exit 1 且报 `:69` → 还原 exit 0 22/22），与本次结论一致 |
| **AC-3** 三态与文案 | UNREADABLE 不含「未配置/未启用/尚未接入/请配置」与「Maintainer」；DISABLED 不提权限；DISABLED/UNREADABLE 下点核验 0 POST；列表始终渲染 | 同上脚本 C/D/E 段（admin 真实 `enabled=false`、CDP 回放服务端**真实 403 体**、CDP 回放 `enabled=true`） | **PASS**：DISABLED「GitLab 集成未启用…」无权限词、带「去集成配置」；UNREADABLE 点名 `integration:manage`、禁词全不出现、不给配置入口；两态 `POST /check = 0` 且 URL 不跳转；ENABLED 无常驻条、核验可点；各态 `rows=2` |
| **AC-4** 同类调用点扫描归零 | 端点→需码→调用点→守卫码四步清单；未声明且未豁免 = 0 | 复核 `PermissionRouteTable.java:58-59`（`^/integration/(configs\|logs\|admission-items)` → `integration:manage`）；a5 S2/S4/S5 复算；`/requirements/{id}/sonar` 无专用规则（落认证即可读），`/releases/{id}/integration-settings` 落 `^/releases → release:plan` | **PASS**：4 条登记（1 修复 + 1 管理页豁免 + 1 无风险 + 1 页面守卫已覆盖），`未声明且未豁免 = 0`；`views/components` 117 个源文件中 `/integration/configs` 字面量 0 |
| **AC-5** a5 ≥20 断言并接入聚合器 | 14 运行时 + ≥4 静态 + ≥2 自检；五处登记；总数 ≥115；栈未起非 0 | `node scripts/tests/access/a5-capability-probe.mjs` → 22/22；`bash scripts/access-verify.sh` → 117/117（a0 29 / a1 8 / a2 35 / a3 9 / a4 14 / a5 22，六段均达下限）；`GATEWAY_URL=http://127.0.0.1:59999 …` → **exit 1** 且**未生成计数文件** | **PASS**；`ALL_SEGMENTS`/`segment_cmd`/`segment_file`/`segment_min`/汇总分发五处齐全，总数守卫 45→115，分段下限用 `case`（bash 3.2 兼容，实测通过） |
| **AC-6** 既有门禁不回归 | 全部门禁绿 | 逐条独立执行（见 §4） | **PASS**：access 117 · platform 168 · quality 348 · integration 231 · collab 263 · unit 237 · lint 16 · verify 14 · negative 25 · typecheck 0 · build 0 · demo-reset 28/28 |
| **AC-7** 零协议 / 零 schema / 零权限码 | 无 Flyway、无表变更、权限码不变、无契约变更 | `git diff --stat HEAD -- '*.sql' '*.java' '*.yml' '*.yaml'` → 空；无 `PermissionMatrix.java` diff；无新增迁移文件 | **PASS**（纯前端修复，无后端面改动） |
| **AC-8** 演示文档与截图 | dev1 行补实测与「为什么是未核验」；新增截图；不动工作台/报表 | `git diff -- docs/teamflow/DEMO-BY-ROLE.md`（3 处：§3 口径、§4.5 dev1 实测、§7.2 新图）；HTML 同步（含新图引用）；人工看图 | **PASS**：`DEMO-BY-ROLE.html` 已含 4 处 `fixed.png`（含新图）；截图 1440×900 / 114016B，目视：URL 未跳 `/403`、常驻黄条逐字为权限原因、表格 2 行两枚「未核验」、操作列置灰、无遮挡/错位 |

## 2. 反目标（anti-goals）逐条排查

| 反目标 | 排查证据 | 结论 |
|---|---|---|
| 1. 列表被探测失败拖垮 | D 段（合成 403）`rows=2`、`hasTable=true`；dev1 截图表格 2 行 | 未发生 |
| 2. 把权限问题说成配置问题 | D 段禁词断言：「未配置/未启用/尚未接入/请配置/Maintainer」全部不出现；文案点名 `integration:manage` | 未发生 |
| 3. 关小全局 403 换绿灯 | `client.ts` 零 diff；pm/po1/qa1 深链仍落 `/403?code=CCODE_POLICY_VIEW` | 未发生 |
| 4. 门禁只认字符串 | a5 C1–C3 自检 + 本次对真实文件的内存变异（删标记 → 报 `:69`，还原 → 0） | 未发生 |
| 5. 提示成为噪音或指错方向 | E 段 ENABLED 无常驻条；DISABLED 才给「去集成配置」；UNREADABLE 复用 GitLab Maintainer 文案 = 0 | 未发生 |

## 3. 架构一致性（M3 质量门）

| 蓝图要求 | 实际实现 | 判定 |
|---|---|---|
| `listConfigs(system?, options?)` 只透传、**不给默认值**，管理页 403 语义不被吞 | `web/src/api/integration.ts:91-102` 原样把第二参作为 `http.get` 第三参；`ConfigTab.vue:96` 裸调用不变 | 一致 |
| 标记由**调用点**声明，api 层只做透传（审计可单跳判定） | `CodePolicyView.vue:69` 声明；api 层除类型外无 `silentForbidden` 字样 | 一致 |
| 三态为页面级事实，与行内 pill/`rowNotice` 分离 | `gitlabCapability`(54) → `capabilityNotice`(81) 只驱动常驻条与核验入口；`rowNotice` 独立、一次性、可关闭 | 一致，未合并 |
| 核验闸门早于请求 | `onCheck` 在 `checkPolicy()` 前判三态（`CodePolicyView.vue:213-222`），实测 0 POST | 一致 |
| a5 按调用点（平衡括号取实参）审计，可内存夹具自检 | `callArgs`/`auditCapabilityCalls` 已导出；C1–C3 证明「缺标记红 / 带标记绿 / 别处有字样仍红」 | 一致 |
| 聚合器只做编排：段清单/命令/文件/分发 + 总数守卫 + 分段下限 | `scripts/access-verify.sh` 五处登记；`segment_min` 用 `case` 兼容 bash 3.2 | 一致 |
| 无重复实现 / 不合并 a3 与 a5 / `client.ts` 单一入口 | 新增代码只在两个 owned 文件 + a5；a3 未动；`client.ts` 零改动 | 无发现 |

## 4. 基线不回归（r7 契约）

- platform-verify `12 个账号` 断言 PASS（账号表 12 条未破）；`platform-demo-reset` 28/28（幂等重建完成）。
- 深链/菜单不回归由 AC-1 的 3 个 403 账号覆盖。
- 注意：`git status` 显示 `DEMO-BY-ROLE.html`、r7 的 5 张 `*-fixed.png` 与 a5 脚本仍在工作区未提交，收尾提交时须一并纳入（属交付流程，不影响功能判决）。

## 5. 遗留与建议（均为 P3，不阻断验收）

| 编号 | 观察 | 建议 |
|---|---|---|
| P3-1 | **门禁计数随演示数据漂移**：本轮 `platform-verify 168/168`（AC-6 基线 167）、`negative 25/25`（QA 记录 23）；脚本无 diff，差异来自「数据不足时 `warn` 跳过」的分支 | 基线写成「≥」或在被跳过时 fail；让断言数确定性可核 |
| P3-2 | `gitlabConfig`（`CodePolicyView.vue:56/71/75`）现在只写不读，三态已由 `gitlabCapability` 承载 | 删除该死状态，或在需要配置详情的入口复用 |
| P3-3 | QA D-02：置灰「核验」按钮无 tooltip，原因只能从页首黄条推断 | 加 `title="需要 CCODE_POLICY_EDIT"` |
| P3-4 | 人工补测项未自动覆盖：ENABLED 下真实核验、窄屏（1024/1280）、黄条对比度、真实 GitLab 401/超时文案 | 下一轮演示前抽查，或纳入浏览器脚本 |

## 6. 证据清单（本轮亲自执行）

```bash
bash scripts/access-verify.sh                                   # 117/117（六段均达下限）
node scripts/tests/access/a5-capability-probe.mjs               # 22/22
GATEWAY_URL=http://127.0.0.1:59999 … a5-capability-probe.mjs    # exit 1，不写计数
node logs/teamflow/tf-mwa402ox-br1nqs/scripts/qa-r8-page-gate-matrix.mjs   # 62/62（真实浏览器）
bash scripts/platform-verify.sh                                 # 168/168
bash scripts/quality-verify.sh                                  # 348/348
bash scripts/platform-unit.sh                                   # 237
bash scripts/tests/lint.sh / bash scripts/verify.sh             # 16 / 14
bash scripts/platform-test-negative.sh                          # 25/25
bash scripts/integration-verify.sh / collab-verify.sh           # 231 / 263
bash scripts/platform-demo-reset.sh                             # 28/28
cd web && npm run typecheck && npm run build                    # exit 0 / built 5.12s
```

> 说明：a5 的运行时矩阵与页面矩阵都使用**同源真实登录**，未用 mock 权限；唯一合成部分是 D/E 段对 `/integration/configs` 的响应回放（用于在真实数据只有 DISABLED 的情况下覆盖 UNREADABLE/ENABLED），回放体取自服务端真实 403 响应。

验收结论：✅ 通过
