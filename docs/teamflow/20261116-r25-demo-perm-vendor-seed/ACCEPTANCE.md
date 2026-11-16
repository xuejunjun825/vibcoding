<!-- meta: summary="r25 验收：功能 12 AC 基本达成；因 ScopeChip.vue 越出 AC-12 改动面、memory 待办与代码不一致，判有条件通过，列 2 项必做复验条件。" -->
# 验收报告 · r25 演示层权限收敛与厂商演示数据

- 需求夹：docs/teamflow/20261116-r25-demo-perm-vendor-seed/（PRD / TECHNICAL / QA-REPORT / evidence）
- 分支：`feat/vendor-demo-polish`（工作区态，未提交、未合并）；改动 10 个已跟踪文件 + 新增 `scripts/tests/access/a6-write-button-gate.mjs` + 任务夹；`pom.xml`、`web/package.json` diff = 0；`AGENTS.md` 零改动。
- 验收依据：PRD 的 12 条 AC、TECHNICAL 蓝图、QA-REPORT（P0/P1/P2 = 0，P3×6）；本阶段独立复跑见 §2。
- 结论：**⚠️ 有条件通过**——AC-1..AC-11 全部达成，AC-12 的「改动面仅限」被 `web/src/components/ScopeChip.vue` 越界（43 行变更），且 memory 已知待办与代码矛盾；按 §4 处置后复验即可签字。

## 1. 逐条验收（r25 PRD · 12 条）

| AC | 验收判据（摘要） | 证据 | 结论 |
|---|---|---|---|
| AC-1 | 需求池页头+空态两条 `/requirements/new` 同受权限约束；vpm1 disabled+原因、点击无请求/URL 不变/不落 403；admin/pm/po1 照旧 | 字面量=2；`canReport/reportDenyTip` 同源（`RequirementPoolView.vue:146-161/366-368/503-505`）；UI 探针 18/18（vpm1 深链仍 403、admin/pm 可点）；截图 | 通过 |
| AC-2 | R1 七入口逐条落位，vpm1 全禁用；已合规 4 处不回归 | 三视图 `:disabled="!canX"` + `:title` + handler 首行兜底；a6 静态 14/14（W1–W14）；UI 探针实测缺陷/迭代/需求池 | 通过 |
| AC-3 | 探测调用 `silentForbidden`，403 就地降级；深链仍由守卫 403 | `web/src/api/req.ts:164`；`client.ts:180` 未改；a6 降级 5/5；vpm1 深链落 `/403?from=/requirements/new&code=req.create` | 通过 |
| AC-4 | a6 ≥24（静态≥12/降级≥4/运行时≥4/自检≥2），纳入 access；失败非零且不写计数行 | 独立跑 a6 25/25（14/5/4/2）；access 190/190；QA 假密码 exit 1 无计数行 | 通过 |
| AC-5 | DEMO-R-09/10/11 纳入 DEMO-REL-1，同源写 release_id+release_requirement，不 UPDATE，12 需求/1 版本 | `DemoFixture.java` row[15]=1；`release_requirement`=11；DB 探针 11/11；demo-reset 66/66 | 通过 |
| AC-6 | test_case.vendor_id 按需求派生，VEN-A ≥1，仍 12 行，不硬编码/UPDATE | `DemoResetService` requirementVendors + insert vendor_id；DB 探针：12 行、VEN-A 3、与需求逐行一致 | 通过 |
| AC-7 | vpm1/vendor1 版本 ≥1；vpm1 用例 ≥1；admin/pm 1/12/12 不减；隔离 3/0/8 | demo-reset [54]–[58]；DB 探针；QA 隔离断言 | 通过 |
| AC-8 | demo-reset 61→≥65，新增真实登录 API 断言，只增不删，幂等 | 独立跑 66/66（[54]–[58] 新增 5 条）；QA 连跑两次逐键一致 | 通过 |
| AC-9 | platform-verify 相关断言核对；C4 仍读 SeedService R2026-11；总数 ≥173 | QA 174/174；`platform-verify.sh:996-1007` 未改；SeedService 未改 | 通过 |
| AC-10 | 服务端鉴权/授权数据零改动；1848 格/168 行/11 DENY；POST 仍 403 | diff 无 PermissionMatrix/RbacSeeder/ScopeSql/Controller；rbac 113/113；demo-reset [59]–[66]；a6 C1–C4 均 403 | 通过 |
| AC-11 | 全门禁不缩水 | access 190、rbac 113、demo 66、platform 174、negative 92、unit 338、lint 22、analytics 74、collab 257、typecheck 0、build/compile 0；`verify` 未复跑（环境互斥，见 R3） | 通过（附证） |
| AC-12 | 改动面仅限清单；pom/package 空；evidence ≥3 | 清单内文件齐；pom/package 0；4 张截图；**`ScopeChip.vue` 43 行越界** | 有条件 |

> r24 及以前的基线（rbac 1848/168、11 条 DENY、PermissionMatrixGridTest 等）由 rbac 113、access 190、platform-verify 174 覆盖，未发现回归。注：r25 PRD 只声明 12 条 AC。

## 2. 本阶段独立复核（非引用 QA）

| 命令 | 结果 |
|---|---|
| `node scripts/tests/access/a6-write-button-gate.mjs` | exit 0，25/25（14/5/4/2） |
| `bash scripts/access-verify.sh` | exit 0，190/190（a0 29 · a1 8 · a2 73 · a3 11 · a4 19 · a5 25 · a6 25） |
| `bash scripts/platform-demo-reset.sh` | exit 0，66/66（含 [54]–[58] 五条新断言） |
| `bash scripts/rbac-verify.sh` | exit 0，113/113（11 段） |
| `bash logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-qa-db-probe.sh`（重置后） | 11/11（12 用例/3 VEN-A/11 release_requirement/12 需求/1 版本） |
| `node logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-qa-ui-probe.mjs` | 18/18（vpm1 禁用、深链 403、admin/pm 反例、1280 无溢出） |
| 证据目检（4 张 PNG） | 需求池禁用+chip、版本列表含 DEMO-REL-1、版本详情 stats.total=3、用例库 3 行；无遮挡/换行 |

## 3. 架构一致性（强制项）

- **与蓝图一致**：三处视图、`searchRequirements`、两个 seed 文件、a6 + access-verify、demo-reset 均在清单内；写入口单一形态、chip 原因、厂商派生单点；服务端鉴权/授权零改动，无 DDL/UPDATE 造数；a6 与 a5 的重复、三视图 canX 重复、夹具/服务层派生重复均为蓝图显式接受。
- **偏离（1 处）**：`web/src/components/ScopeChip.vue` 被改 43 行（新增 `tooltip` prop、权限/范围文案分流、`tabindex="0"` 恒挂、focus trigger、Space 键、focus ring）。它不在 AC-12 清单与蓝图模块表内，TECHNICAL D-3 明确「不改共享组件，键盘可达性登记待办」，dev T1 也称「本轮未改共享组件」；memory 已知待办① 仍写「只有 clickable 才 tabindex、原因对键盘用户不可达」——代码与文档已矛盾。改动本身向后兼容、UI 探针实测 tooltip 为「需要权限：req:report:bus / req:report:dev」，但把 `tabindex=0` 扩到 4 个既有消费页（`/defects/board`、`/defects/:id`、`/requirements/:id`、`/components`）的 tab 序，未纳入自动门禁。
- **结论**：无重复实现/结构破坏/第三处厂商派生；但「越出改动面 + 文档失实」需处置（R1/R2）。

## 4. 意见与复验条件

- **R1（必须）处置 ScopeChip 越界**：二选一——(a) 保留并登记为经产品确认的改动面扩展（更新 memory 同主题行 + 交付记录注明理由：AC-1 的「可见原因」语义与 DESIGN §6 键盘通道），同时补 4 个消费页的键盘 tab 序走查；(b) 回退 `ScopeChip.vue`，严格回到 AC-12 清单（此时 chip tooltip 会把权限码读成「当前数据范围」，需另行处理文案）。建议 (a)。
- **R2（必须）修正 memory**：`已知待办①` 与代码不符（同主题行替换、幂等、不新增 changelog）；若 R1 选 (b) 则保持原文。`AGENTS.md` 托管区外零改动。
- **R3（建议）**：`scripts/verify.sh` 14/14 本轮因 dev 栈与 platform 栈端口互斥未复跑；r25 未触碰 dev 栈文件，r24 基线 14/14，建议在 dev 栈可用时补跑后再做最终签字。
- **P3 遗留（不阻塞，已入 QA 报告）**：D1 chip 实测 20px/11px vs 设计 22/12；D3 chip 宽 275.6 vs 预算 132（口径笔误，1280/1440 均不换行）；D4 negative 90↔92 计数漂移（历史，非 r25 引入）；D5 版本列表卡片恒 0/0（接口无计数字段，已登记 out-of-scope）；D6 用例库/版本库空态未按 R1（memory 已登记）。
- 无 P0/P1/P2；提交面无 target/dist/node_modules/logs，`pom.xml`/`package.json` 零 diff。

## 5. 结论

功能目标（两个演示层缺口）已在真栈 + 真登录下证明修复，门禁只增不减且独立复跑全绿；但 AC-12 改动面被 `ScopeChip.vue` 越界且 memory 待办与代码不一致，属可小成本收敛的架构/文档偏离。按「有条件通过」处理：完成 R1+R2（R3 建议）后即可复验签字，无需重做功能。

验收结论：⚠️ 有条件通过
