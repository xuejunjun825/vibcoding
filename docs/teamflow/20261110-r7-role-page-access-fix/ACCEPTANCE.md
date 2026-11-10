# 验收报告 · r7 角色 × 页面准入修复（P0 演示阻断修复）

- 需求：非管理员角色打不开需求池 / 版本计划 / 审批中心 / 测试计划 / 分支策略（req-7）
- 任务夹：`docs/teamflow/20261110-r7-role-page-access-fix/`；分支 `feat/role-page-access-fix`（自 `main@ea877fa`）
- 验收人：产品经理（验收负责人）；验收时间 2026-11-10
- 依据：PRD 17 条 AC（AC-1..AC-17）+ QA-REPORT 第 2 轮复验 + **验收人本轮独立复跑**

## 1. 验收方式与独立证据

不只看 QA 报告：以下为验收人本轮亲自执行的复跑（同一工作树、同一栈 8080/8082/8083/5173）：

| 复跑项 | 结果 |
|---|---|
| `bash scripts/access-verify.sh` | exit 0，**95/95**（a0 29 / a1 8 / a2 35 / a3 9 / a4 14） |
| `GATEWAY_PORT=19999 … bash scripts/access-verify.sh` | **exit 1**，报「平台栈未就绪…先执行 make platform-up」 |
| `bash scripts/platform-verify.sh` | 167/167（需求池最差 0.004569s） |
| `bash scripts/platform-seed.sh` | 10/10（12 账号全部登录，BUS=2→PDR=7→RD=11） |
| `bash scripts/platform-demo-reset.sh` | 28/28（连跑两次幂等）；重置后 **po1=8 / vendor1=3 / vendor2=0**（API 实测） |
| `bash scripts/platform-unit.sh` | **237** 个单测全绿 |
| `bash scripts/verify.sh` / `lint.sh` / `platform-test-negative.sh` | 14/14 / 16/16 / 25/25 |
| `cd web && npx vue-tsc --noEmit` / `npm run build` | exit 0（0 error）/ exit 0 |
| `node …/qa-r7-executors-page-guard.mjs`（D-01 原判据） | PASS（qa1 调 `/admin/api-clients` 仍 403，但页面已 `silentForbidden` 降级，不再全局跳 403） |
| `bash …/qa-r7-demo-reset-coverage.sh`（D-02 原判据） | PASS 5/5（分支策略 2、执行器 7 数据齐备） |
| 验收人自测 AC-11：`biz1` 登录 → `POST /api/req/requirements {type:"BUS"}` | **201**，`BUS-0047` SUBMITTED；`biz1` 需求池 total 8→9；随后 demo-reset 复原 8 |
| QA 侧未由验收人重跑項 | quality 348 / integration 231 / collab 263（QA 已逐项附命令与计数，与 PRD 基线一致，仅新增未删改） |

环境限制：浏览器不可用（宿主沙箱 Chromium crashpad 需 PROCESS_ALL_ACCESS，`--no-sandbox` 无效），页面级点击/视觉见 §5 人工补测；不构成交付缺陷。

## 2. AC 逐条核对（17/17）

| AC | 判据摘要 | 验收证据 | 结果 |
|---|---|---|---|
| AC-1 | 3 只读字典端点、登录可读、字段白名单、匿名 401、未知 404、管理接口不变 | a0 29/29；匿名网关实测 401；`/admin/domains` 仍 `org:manage` | ✅ |
| AC-2 | 按 §7 裁剪、跨租户 403、厂商不可枚举他方人员 | a1 8/8；vendor1 仅 VEN-A；admin 域集合 = `/admin/domains` 集合 | ✅ |
| AC-3 | 非管理页字典调用 100% 走 `api/dictionaries.ts` | `grep -rl "@/api/dictionaries" web/src` = **16** 文件；管理面外无裸 `listDomains/listVendors/listUsers`；typecheck/build 0 | ✅ |
| AC-4 | 403 默认跳转；`silentForbidden` 仅 reject 不导航；字典全声明 | client-semantics 12/12 + round2-fix-behavior 8/8（真实源码转译执行：默认跳 `/403?...`、silent 零导航、options 第 3 参透传） | ✅ |
| AC-5 | 5 页 × 7 角色 = 30 ✅ + 5 ⛔ 精确状态码 | a2-page-matrix **35/35**；5 个 ⛔ 均为 403（未新增权限码） | ✅ |
| AC-6 | po1=8 / vendor1=3 / vendor2=0 | demo-reset 28/28 + 验收人 API 实测三值精确命中；字典不改变列表 total | ✅ |
| AC-7 | 41 条导航 100% 显式 `functionCode`（含 `AUTHENTICATED` 哨兵） | a3 9/9；`nav.ts` 42 处 `functionCode`（41 条 + 类型声明），10 处哨兵 | ✅ |
| AC-8 | 侧栏/顶栏同一 `navVisible`、空分组消失、菜单与路由同源 | `SideNav.vue:35`、`TopNav.vue:28` 仅调用 `navAccess.ts:32` 唯一导出；a4 14/14 + crosscheck 31/31（真实 navVisible 求值对账） | ✅ |
| AC-9 | 7 角色关键隐藏/可见抽检 | a4 14/14（po1/dev1/qa1/pm/tl1/vendor1/admin 逐项） | ✅ |
| AC-10 | 深链无权限落 403、有权限刷新正常 | t6 35/35（`internalOnly` 先于功能码、6 条路由 meta 同源）+ a2 状态码；**导航守卫的浏览器点击留人工项**（§5） | ✅（静态+求值） |
| AC-11 | biz1(BUSINESS)/suite1(SUB_BUSINESS_LEADER)、域同 po1、可提报并看到 | platform-seed 10/10；probe 218/218（角色码、域 7==po1、vendor_id NULL）；**验收人实测提交 BUS → 201 并见 9 条** | ✅ |
| AC-12 | 种子断言 10→12、loginUsers 12、文档账号表同步 | platform-seed [08] 12 账号；t7 34/34；DEMO-BY-ROLE §1 两行；两遍计数一致 | ✅ |
| AC-13 | 幂等重建固定小数据集、清夹具、8/3/0 | demo-reset 28/28 两遍逐键相等；保留定义表、重建 12 需求链路；po1=8 | ✅ |
| AC-14 | 不进自动门禁；执行后 seed/各 verify 全绿；失败回滚 | `test-all.sh`/`platform-up`/`platform-seed` 均无 demo-reset；reset 后 seed 10/10、access 95/95；保留集单测 2/2 + 不变量 409 单测覆盖 | ✅ |
| AC-15 | 既有门禁一条不红（只增不减） | 本轮复跑 access 95 / platform 167 / seed 10 / unit 237 / verify 14 / lint 16 / negative 25 / typecheck 0 / build 0；QA：quality 348 / integration 231 / collab 263（均 ≥ 基线，无 SKIP） | ✅ |
| AC-16 | 新增 `make access-verify` ≥45 断言、栈未起非 0 | **95 条**断言；栈未起 exit 1 且打印修复提示；已挂入 `make test` 的 platform-up 之后 | ✅ |
| AC-17 | DEMO-BY-ROLE §1/§3/§6 同步、不动工作台/报表、memory 同主题替换 | t10 101/101 + t10-role-grants 9×9 对账；§1 12 账号、§3 标 5 ⛔ 与原因、§6 两条 P0 已修复含验证命令；`memory.md:27` 为同主题新增约定，无变更日志 | ✅ |

**统计：17 条 AC 全部通过；P0 两条（D-01/D-02）按原判据复验关闭。**

## 3. 缺陷复验（上轮 2 条 P2 关闭）

| 编号 | 上轮 | 复验 | 判定 |
|---|---|---|---|
| D-01 执行器页 qa1 被全局 403 踢走 | P2 | 原检查器 PASS：`TestExecutorsView.vue` 调用 `listApiClients(..., { silentForbidden: true })`，qa1 直连执行器 API 200（total=7），`/admin/api-clients` 仍 403 但只降级 | **关闭** |
| D-02 重置后分支策略/执行器页空白 | P2 | 原检查器 5/5 PASS：定义表移出 `DELETE_ORDER`，reset 28/28、保留单测 2/2，两页接口非空（2 / 7） | **关闭** |

仍开放 2 条 **P3**（不阻塞，已附判据）：**D-03** `web/src/router/types.ts` 保留第二份 `NavItem/RouteMeta`（`functionCode` 可选、缺 `internalOnly`），仅 `router/index.ts:10` 注释引用，该文件归另一任务维护，本轮以 `declare module` 合并绕过；**D-04** 门禁残留累积（t1 现有 5 条 `S7-*` 执行器 + 9 条 `*-ci-*` 客户端，实测），因 D-02 修复后 reset 不再清这三张表，建议下一轮让 s7/verify/neg 自清理。

## 4. 架构一致性（M3 自查）

- **按技术方案单点落位**：`DictionaryController`/`DictionaryViews`（record 即白名单）、`api/dictionaries.ts`（16 消费方唯一入口）、`navAccess.ts` 的 `navVisible`（侧栏/顶栏唯一判定）、`DemoFixture.DELETE_ORDER:68` → `DemoResetService:205` 唯一消费，均无第二套实现。
- **零权限码、零 schema/Flyway 变更**：`/dictionaries/*` 故意无路由规则（`PermissionRouteTableTest` 12/12 钉住），无 migration 新增。
- **无越界/污染**：`git status` 中无 `node_modules/target/dist/.m2/.nacos/.env`；未跟踪项仅为预期源码与文档。
- 结论：**未发现偏离技术方案的模块或重复实现**；D-03 属跨任务文件的历史重复声明（无消费方），不构成结构破坏。

## 5. 遗留与人工补测（环境限制，非交付缺陷）

1. 浏览器不可用，以下需人工目检：AC-10 深链 403 跳转（vendor1→`/testcases`、po1→`/test-plans`）、有权限刷新不回退；AC-8 空分组 DOM 消失、侧栏折叠；AC-17 12 张页面截图（`docs/teamflow/demo-shots/`）；403 页文案与返回入口。
2. AC-14 失败回滚：本轮由单测覆盖逻辑，未在真实栈注入不变量违反（可脚本化：`POST /internal/demo-reset` 断言 409 + 行数不变）。
3. 文档口径小注：PRD AC-11 写「BUSINESS 类型需求」，接口枚举实为 `BUS`（业务需求），验收人按 `BUS` 实测 201；建议下轮在文档标注映射。另 `platform-demo-reset` 应在 `platform-seed` **之后**执行（Makefile 已注明），顺序颠倒会使 po1 可见数由 8 变 16（seed 链路叠加），非缺陷但演示前需按 Makefile 顺序。
4. D-03/D-04 为 P3，建议下一轮清理。

## 6. 验收结论

功能面 17/17 AC 达成，两条演示阻断缺陷按原判据关闭，既有门禁全部复绿且新增准入门禁 95 条断言可用，架构未见偏离；遗留仅 2 条 P3 与浏览器受限的人工目检项。**同意验收通过**，建议合并入 `main` 后按 `DEMO-BY-ROLE` §6 执行演示前检查。

验收结论：✅ 通过
