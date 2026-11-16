# r25 交付自检证据（T3）

这四张图不是「页面长什么样」的截图，而是 r25 两个演示层缺口的**渲染后证据**：
a6 的静态断言只能证明模板里写了 `:disabled` 与 `title`，证明不了浏览器里按钮真的是禁用态、
chip 真的在、DEMO-REL-1 真的出现在 vpm1 的版本里。截图负责这一层。

- 账号：`t1 / vpm1`（VENDOR_PM · 厂商项目经理-演示 · VEN-A）
- 生成脚本：`logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-evidence.mjs`（playwright，headless，1440×900 @2x）
- 复跑前提：`make platform-up` + `make platform-seed`（或 `make platform-demo-reset`）+ `npm --prefix web run dev`
- 复跑：`node logs/teamflow/tf-mwj1da26-p6dimw/scripts/r25-evidence.mjs` → 12/12 断言通过才生成这四张图

| 文件 | 证明什么 | 实测值 |
|---|---|---|
| `vpm1-requirements-write-gate.png` | AC-1/AC-2：无权限的写按钮**渲染但禁用**，原因双通道可见；点它不会再落到全局 403 页 | 页头按钮 `disabled`；`title="需要权限：req:report:bus / req:report:dev"`；chip「1 个操作需要权限 / req:report:bus / req:report:dev」；强制点击后 URL 仍为 `/requirements` |
| `vpm1-releases-demo-rel-1.png` | AC-7：vpm1 的版本计划不再是空白，VEN-A 承接链进入 DEMO-REL-1 | 列表 1 条：`DEMO-REL-1 演示版本 2026-11`（规划中 / 严格门禁）；「新建版本」禁用 |
| `vpm1-release-detail-vendor-chain.png` | AC-7 的机制层：承接链是**真挂了需求**，不是只有一条空版本 | `GET /api/req/releases/1321` → `stats={total:3,done:0}`，requirements = `DEMO-R-09|IN_DEV, DEMO-R-10|PENDING_TEST, DEMO-R-11|IN_DEV` |
| `vpm1-testcases-vendor-rows.png` | AC-7：用例库非空（`test_case.vendor_id` 按需求同源派生后 ScopeSql 放行） | tbody 3 行：`DEMO-C-09/10/11`；「新建用例」禁用（`test.action.case_edit` 不在 VENDOR_PM 授权内） |

## 一处需要知道的显示口径

版本**列表卡片**上恒显示 `0/0 需求完成`：列表接口 `/api/req/releases` 不返回 `requirementCount` / `doneCount`
（`ReleaseListView.vue:94,195` 里 `?? 0` 兜底）。版本**详情**用的是同一个 release 的 `stats`，显示 `total=3`。
即：数据挂上了，只有列表卡片这一格没有取数来源 —— 已在交付回复里作为 `[OUT-OF-SCOPE]` 登记，本轮不改（文件不属 T3 边界）。

## 与 a6 的分工

- `scripts/tests/access/a6-write-button-gate.mjs` 负责**可持续复核**的 25 条（静态 14 / 403 降级 5 / vpm1 运行时 403 4 / 自检 2），已接入 `scripts/access-verify.sh`（190/190，a6 下限 24）。
- 本目录四张图是**一次性渲染取证**：a6 的运行时那 4 条证明了「服务端仍 403」，这里证明「前端不再让人点到那个 403」。
