# QA 报告 · r8 分支策略页残留 403（可选能力探测降级）

- **任务夹**：`docs/teamflow/20261110-r8-code-policies-403-fix/`
- **被测交付**：工作区分支 `feat/role-page-access-fix` 上未提交的 6 改 + 3 新增（a5 脚本、任务夹、2 张截图）
- **测试环境**：macOS · Node 22 · 演示栈已起（网关 8080 / req 8082 / deliver 8083 / web 5173 / 本地 GitLab 桩 19090）· 复用本机已运行的 headless Chrome（CDP 9222，Chrome 156）
- **测试日期**：2026-11-10
- **结论**：**可验收**。14 格准入矩阵、三态提示、核验闸门、a5 门禁与变异自检全部实测通过；既有门禁无回归（access 117 / platform 167 / quality 348 / integration 231 / collab 263 / unit 237 / lint 16 / verify 14 / typecheck / build 全绿）。无 P0/P1/P2 缺陷，P3 观察 2 条（含 1 条 AC 基线数字过时）。

## 1. 交付形态与范围

| 项 | 事实 |
|---|---|
| 代码改动 | `web/src/api/integration.ts`（+13/−1）、`web/src/views/code/CodePolicyView.vue`（+80/−9）、`scripts/access-verify.sh`（+43/−7）、`docs/teamflow/DEMO-BY-ROLE.md`、`docs/teamflow/DEMO-BY-ROLE.html`（+31/−4）、`docs/teamflow/memory.md` |
| 新增 | `scripts/tests/access/a5-capability-probe.mjs`、`docs/teamflow/demo-shots/dev1-code-policies-fixed.png`、任务夹 3 文件 |
| 零改动确认 | `git diff --stat HEAD -- '*.sql' '*.java' '*.yml' '*.yaml'` → **空**；`git diff -- web/src/api/client.ts` → **空**（AC-2/AC-7 的前置事实） |
| 提交面卫生 | `git status --porcelain` 无 `dist/`、`node_modules/`、`logs/`；lint L13「.gitignore 覆盖前端产物与运行态」PASS |

## 2. 用例与结果（按本夹 AC）

| AC | 检查方式 | 命令 / 证据 | 结果 |
|---|---|---|---|
| AC-1 页面级 14 格矩阵 | 真实浏览器逐账号同源登录 → 直达 `/code-policies` | `node logs/teamflow/tf-mwa402ox-br1nqs/scripts/qa-r8-page-gate-matrix.mjs`（A 段 21 断言） | **PASS**：admin/dev1/tl1/vendor1 停留 `/code-policies`、表格 2 行、侧栏有「分支策略」；pm/po1/qa1 落 `/403?from=/code-policies&code=CCODE_POLICY_VIEW`、侧栏无入口 |
| AC-2 探测语义钉住 + 删标记必红 | 读代码 + 变异（QA 亲自改源码再还原）+ a5 | `grep -n "listConfigs('GITLAB'" web/src/views/code/CodePolicyView.vue` → `:69` 同一次调用带 `{ silentForbidden: true }`；删该实参后 `node scripts/tests/access/a5-capability-probe.mjs` → **exit 1**，S1/S2/S5 三条报 `CodePolicyView.vue:69`；还原 → **exit 0** 22/22 | **PASS** |
| AC-3 三态与文案 | D 段回放服务端真实 403 体（`{"status":403,"code":"FORBIDDEN",...}`）合成 UNREADABLE；C 段用真实 `enabled=false`；E 段 CDP 拦截 `enabled=true` | 同上脚本 B/C/D/E 段（41 断言） | **PASS**：UNREADABLE 文案点名 `integration:manage`、无「未配置/未启用/Maintainer」、列表照常 2 行；DISABLED 文案说「未启用」不提权限码；ENABLED 无常驻条。三态下点「核验」均 **0 个 POST `/code/policies/{id}/check`**（DISABLED/UNREADABLE 闸门生效） |
| AC-4 同口径扫描归零 | 独立 grep 四步口径（端点 → 需码 → 调用点 → 守卫码） | 见 §3 清单 | **PASS**：`integration:manage` 端点仅 `^/integration/(configs\|logs\|admission-items)` 一条；前端调用点 2 处，非管理页 1 处已声明，未声明且未豁免 = 0 |
| AC-5 a5 ≥20 断言 + 聚合器 | a5 单跑 / 聚合器单段 / 全量 / 栈未起 | `node scripts/tests/access/a5-capability-probe.mjs` → 22/22；`bash scripts/access-verify.sh a5` → 22/22（下限 20）；`bash scripts/access-verify.sh` → **117/117**（a0 29 a1 8 a2 35 a3 9 a4 14 a5 22）；`GATEWAY_URL=http://127.0.0.1:59999 ACCESS_COUNTS=/tmp/a5-down-r8.txt node scripts/tests/access/a5-capability-probe.mjs` → **exit 1** 且**未生成计数文件** | **PASS** |
| AC-6 既有门禁不回归 | 逐条独立执行 | access 117/117 · platform 167/167 · quality 348/348 · integration 231/231 · collab 263/263 · unit 237/237 · lint 16/16 · verify 14/14 · `npm run typecheck` 0 error · `npm run build` exit 0 · negative **23/23**（见 P3-1） | **PASS**（negative 计数差异见 P3-1） |
| AC-7 零协议/零 schema/零权限码 | git diff 面 + 权限表 | `git diff --stat HEAD -- '*.sql' '*.java'` → 空；`PermissionMatrix.java` 未改；无 Flyway 新脚本 | **PASS** |
| AC-8 文档与截图同步 | 读 diff + 图像复核 | `git diff -- docs/teamflow/DEMO-BY-ROLE.md` 三处（§3 口径、§4.5 dev1 实测、§7.2 新图）；`DEMO-BY-ROLE.html` **也已同步**（+31 行、含新图引用 1 处）；`dev1-code-policies-fixed.png` 实测 1440×900 / 114016B，图像工具复核：URL 未跳转、常驻黄条逐字为权限原因、表格 2 行、两枚「未核验」pill、身份 dev1/DEV | **PASS** |
| AC-9 关键角色抽检 | 同 AC-1（7 账号全量，无抽样） | 同上 | **PASS** |
| AC-10 深链不回归 | 每账号均以 URL 直达（等价深链 + 刷新） | 同上；D/E 段各再整页重载一次后仍正常 | **PASS** |

## 3. AC-4 四步口径清单（端点 → 需码 → 调用点 → 守卫码）

| 端点 | `PermissionRouteTable` 需码 | 前端调用点 | 页面守卫码 | 判定 |
|---|---|---|---|---|
| `GET /integration/configs`（含 `logs`、`admission-items`） | `integration:manage`（`PermissionRouteTable.java:58`） | `views/code/CodePolicyView.vue:69` | `CCODE_POLICY_VIEW` | **本次修复**：带 `{ silentForbidden: true }`，403 降级为 UNREADABLE |
| 同上（管理面全量） | `integration:manage` | `views/admin/integrations/ConfigTab.vue:96` 裸调用 | 管理页 `ADMIN` 准入 | 合规：401/403 仍走全局语义，**不得**加默认 `silentForbidden` |
| `GET /requirements/{id}/integration/...`（`getSonar`） | 无（仅认证，`PermissionRouteTable.java:123` 为 `true`） | `views/deliver/TraceabilityView.vue:134` | 追溯页守卫码 | 合规：无需标记 |
| `GET /releases/{id}/code-quality` 等（`getReleaseIntegration`） | 落到 `^/releases` → `release:plan` | `views/release/ReleaseDetailView.vue:243` | 发布页守卫码 | 合规：页面守卫已覆盖，非「借读管理端点」 |

独立复核命令：`grep -rn "listConfigs(" web/src`（仅 2 处）、`grep -rn "integration/configs" web/src/views web/src/components`（0 处字面量）。**未声明且未豁免 = 0**；a5 的 S1/S2/S5 与 C1–C3 自检同口径复算一致。

## 4. 独立复验脚本（本次新增，可重跑）

`logs/teamflow/tf-mwa402ox-br1nqs/scripts/qa-r8-page-gate-matrix.mjs` —— 62 断言，全部基于真实浏览器（复用 CDP 9222，不新起浏览器、不抢演示 profile；脚本自建 target 后关闭）：

- A 段：7 账号 ×（停留/403、表格行数、侧栏入口、403 原因码）
- B 段：dev1 真实 UNREADABLE（文案三关键词、禁词、列表、按钮置灰、页头入口收起）
- C 段：admin 真实 DISABLED（库里两条 GITLAB 均 `enabled=false`）→ 点「核验」→ 行内提示「核验不会发起请求」+ 网络层 0 POST
- D 段：CDP 回放服务端**真实 403 响应体** → UNREADABLE 下点击同样 0 POST、页面不跳转、列表不清空
- E 段：CDP 回放 `enabled=true` → 无常驻条、核验可点、页头入口仍在

> 说明：本会话沙箱**不能新起** Chrome（crashpad 受限），但机器上已有演示用 headless Chrome（9222），本次复验复用它完成页面级 E2E——因此「无法启动浏览器」在这里**不是豁免理由，也未豁免**。

## 5. 缺陷表

未发现 P0/P1/P2 缺陷。以下为 P3 观察（不阻塞验收）：

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-01 | P3 | 验收基线（文档） | 在 canonical 演示数据上执行负向门禁 | AC-6 声明 `negative 25`，门禁应输出 25/25 | 实际输出 `platform-test-negative: 23/23`（exit 0、无 SKIP 提示、无失败；脚本自 `15cd53c` 未改动，工作区无 diff） | AC-6 | `bash scripts/platform-test-negative.sh` | 输出 `23/23`（把 AC-6 基线校正为 23）或补齐 2 条断言后输出 `25/25`；两种都需与 AC 文案一致 |
| D-02 | P3 | 分支策略页（craft） | dev1 打开 `/code-policies`，hover 置灰的「核验」按钮 | 置灰原因可就地得知（如 tooltip「需要 CCODE_POLICY_EDIT」） | 按钮仅 `disabled`，无 `title`/tooltip（`CodePolicyView.vue:622-632`），原因只能从页首黄条推断 | AC-3（craft 观察，非阻断） | - | - |

## 6. 架构核查（M3 质量门）

| 蓝图要求 | 实际 | 判定 |
|---|---|---|
| `listConfigs(system?, options?: { silentForbidden?: boolean })`，**不给默认值**，管理页 403 语义不被吞 | `web/src/api/integration.ts:91-102` 原样透传第三参给 `http.get`；`ConfigTab.vue:96` 裸调用 | 一致 |
| 标记由**调用点**声明，api 层只透传（审计器可单跳判定） | `CodePolicyView.vue:69` 声明；`api/integration.ts` 内无 `silentForbidden` 字样（除类型） | 一致 |
| 三态页面级事实与行内 pill/`rowNotice` **分离** | `gitlabCapability`/`capabilityNotice` 只驱动常驻条与核验入口；`rowNotice` 一次性、可关闭（`.policy-notice[role="alert"]`），刷新不清页面级事实 | 一致，未合并 |
| a5 按**调用点**（平衡括号取实参）判定，可内存夹具自检 | `auditCapabilityCalls`/`callArgs`/`blankComments` 已导出；`invokedDirectly` 守卫避免被 import 时抢跑 HTTP；C1–C3 证明「缺标记红 / 带标记绿 / 别处有字样仍红」 | 一致 |
| 聚合器只编排：段清单/命令/文件/分发 + 总数守卫 115 + 分段下限 | `scripts/access-verify.sh` 五处登记齐全，`segment_cmd/segment_file/segment_min` 存在，bash 3.2 兼容（`case` 而非 `declare -A`） | 一致 |
| 重复实现 / 抽象缺失 / 结构破坏 | 无新增 wrapper；`client.ts` 零改动（403 全局语义唯一入口）；未把 a3（`@/api/admin` 借读）与 a5（`@/api/integration` + 页面直连）合并 | 无发现 |

## 7. 接口一致性核查（静态，无需运行时）

| 调用 | 目标存在性 | 签名/实参匹配 | 结果 |
|---|---|---|---|
| `CodePolicyView` → `listConfigs('GITLAB', { silentForbidden: true })` | `export function listConfigs` @ `api/integration.ts:91` | 第二参 `options`，实参形状一致 | PASS |
| `listConfigs` → `http.get(url, params, options)` | `http.get<T>(url, params?, extra?: Partial<RequestOptions>)` @ `client.ts:214` | 三参位置一致；`RequestOptions.silentForbidden` 存在（`client.ts:22`） | PASS |
| 蓝图接口名：`loadGitlabConfig / onCheck / refreshAll / capabilityNotice / gitlabCapability / canManageIntegration` | `CodePolicyView.vue:65 / 213 / 95 / 81 / 54 / 49` 全部存在，返回类型与蓝图一致 | — | PASS |
| `a5` 导出：`auditCapabilityCalls / callArgs`（+`blankComments`、内部 `main`） | `a5-capability-probe.mjs:145 / 82 / 32 / 266` | 与蓝图 `{scanned, offenders[]}` 形状一致 | PASS |
| 聚合器 `segment_cmd / segment_file` | `access-verify.sh:36 / 42`（另有 `segment_min:56`） | — | PASS |

## 8. 人工补测清单（环境限制 / 需写数据，非交付缺陷）

| 项 | 判据 | 方法/工具 |
|---|---|---|
| ENABLED 状态下真实发起一次核验 | 点击「核验」后 `POST /code/policies/{id}/check` 发出并按 GitLab 桩结果回写行内 pill | 人工在 `集成配置` 把 GITLAB 置 `enabled=true`（会写演示数据，QA 自动检查未做以免污染），1440×900 真机点击；本地桩 19090 |
| 窄屏布局（1024 / 1280） | 操作列「核验/编辑/删除」不换行溢出、表格横向滚动不压住固定列 | Chrome DevTools 设备模拟 + 人眼确认 |
| 常驻黄条对比度与色弱可读性 | 正文与 `#fff7e6` 背景对比度达标、不依赖颜色单独传意 | 真机 + 对比度工具（如 axe DevTools） |
| 真实 GitLab（非桩）凭证错误/超时的页面文案 | 401/超时降级为行内可读提示，不整页跳转 | 需外部网络与真实实例 |

## 9. 结论

- **功能**：AC-1/AC-2/AC-3/AC-4/AC-5 实测通过，含 62 条页面级断言与 1 次真人级变异（删标记 → 门禁红、报准 `CodePolicyView.vue:69` → 还原复绿）。
- **回归**：AC-6 全部既有门禁复跑绿（access 117、platform 167、quality 348、integration 231、collab 263、unit 237、lint 16、verify 14、typecheck、build；negative 23/23，见 D-01）。
- **文档/证据**：AC-8 图文同步，截图内容经图像复核与页面实测一致。
- **判定**：**可验收**。D-01/P3 建议在下一轮把 AC-6 的 negative 基线校正为 23（或补断言到 25），D-02/P3 属 craft 观察，不影响验收。

## 10. 复跑命令（验收/回归用）

```bash
node logs/teamflow/tf-mwa402ox-br1nqs/scripts/qa-r8-page-gate-matrix.mjs   # 62 断言（需 5173 + 8080 + CDP 9222）
node scripts/tests/access/a5-capability-probe.mjs                          # 22 断言
bash scripts/access-verify.sh                                              # 117 断言
bash scripts/platform-test-negative.sh                                     # 23 断言（D-01 关注点）
cd web && npm run typecheck && npm run build
```
