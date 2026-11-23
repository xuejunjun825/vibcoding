# r40 需求变更单 · QA 复验报告（round 2）

- 结论：**可验收**。上轮 8 个缺陷（D-01…D-08）逐条复验全部关闭，2 个 P3 观测项中 D-09 已修、D-10 仍在；本轮新增 1 个 P3（e2e 残留）。无 P0/P1/P2。
- 复验口径：不重跑「新测试」，先重跑上轮留在 `logs/teamflow/tf-mwt1f266-4e4y2h/scripts/` 的全部检查器，再补上轮未覆盖的面（架构、接口一致性、交付形态、提交卫生）。
- 分支 `feat/req-change-request`；r40 改动仍未提交（由 host 在验收环节统一提交），故 AC-23 的提交信息项不在本阶段可验范围。

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 交付 | 需求变更单闭环：申请 → 影响评估 → 两级审批 → 显式应用 → before/after 基线；V16 两表；第 10 个 Tab；5 个端点 |
| 环境 | macOS；网关 8080、req-service 8082（`/internal/health` db=up, flyway=16）、MySQL、web 5173、Chrome 158（CDP 9222）全部就绪 |
| 基线 | `platform-demo-reset` 80/80：12 条演示需求（DEMO-R-01..12）、`sys_action`=75、每租户 `sys_role_permission`=1903、`req_change`=0 |
| 夹具 | 演示数据 id 随 reset 变化（633725..633736），本轮把上轮硬编码编码/id 的检查器改为按「类型+状态」动态解析，断言不变 |

## 2. 缺陷逐条复验（round-2 级）

> 该表是复验对照，不是新的 OPEN 缺陷；严重级列名刻意用「round-2 级」。

| 编号 | round-2 级 | 复验命令（按上轮原文） | 实测 | 判定 |
|---|---|---|---|---|
| D-01 demo-reset 500 | 已关闭 | `bash scripts/platform-demo-reset.sh` | 80/80 通过，exit 0（连跑 3 次一致） | 修复有效：`TableReset` 子表用 `change_id IN (SELECT id FROM req_change WHERE tenant_id=?)` |
| D-02 固定键被 non_null 吞 | 已关闭 | `bash …/qa-r40-impact-shape.sh` | `before=7 after=7 impact=8 [admission,children,days,fields,frozenAt,postLock,release,targetRelease]`，TOTAL=4 PASS=4 | 修复有效：7 个 Map 组件局部 `@JsonInclude(ALWAYS)` + `WorkflowJson.writeStable`，全局配置未动 |
| D-03 S2 夹具链不符状态机 | 已关闭 | `bash …/qa-r40-gate-fixture-check.sh` | 7/7：BUS 建后 SUBMITTED、早期态 PUT 200、PDR 挂非 POOLED BUS 409 `PARENT_NOT_POOLED`、RD 无父 400 `INVALID_PARENT` | 修复有效：S2 走真实状态机（提交→两级审批→POOLED→PDR→人天） |
| D-04 索引断言只认非 PRIMARY | 已关闭 | 上轮 `db_q` 计数 + 门禁 S1 | `非PRIMARY索引=0`、`PRIMARY 前导(change_id,SEQ=1)=1`；门禁 S1 接受 PRIMARY 前缀，`change-verify` 42/42 绿 | 修复有效（断言口径校正，无需加冗余索引） |
| D-05 审计口径 | 已关闭 | `bash …/qa-r40-ac-probe.sh` | 46/46；`audit_log` 按 `action=REQUIREMENT_CHANGE_APPLIED AND biz_type=REQ_CHANGE AND biz_id=changeId` 恰 1 条，锁定留痕 `CHANGE_AFTER_RELEASE_LOCK` 同口径 | 修复有效 |
| D-06 12.01 被静默舍入 | 已关闭 | 同上（[44]） | 46/46；12.01 在 `checkHalfDay` 前被 400 拦截，边界不可静默通过 | 修复有效 |
| D-07 并发 apply 500 | 已关闭 | 同上（[31]） | 46/46；并发两次 apply 仅 200/409，无 500（行锁前不再加载同实体） | 修复有效 |
| D-08 前端 e2e 夹具与断言 | 已关闭 | `cd web && node e2e/req-change.spec.mjs` | 33/33（含 APPROVED 措辞、深链、8 键断言、撤回不改需求） | 修复有效 |
| D-09 详情页首屏空白 | 已关闭 | `grep -n "加载中" web/src/views/req/RequirementChangeDetailView.vue` | 首屏 `loading` → 「加载中…」分支存在（line 294），三态齐全 | 已修（P3 观测项，不阻断） |
| D-10 角标失败静默为 0 | **仍 OPEN** | `grep -n "tabCounts.changePending" -A3 web/src/views/req/RequirementDetailView.vue` | `catch` 里把计数置 0 → 接口失败时角标消失，与「无待审批」不可区分 | P3 工艺观测（见 §6） |

## 3. 用例与结果（AC 对照）

| AC | 结果 | 证据（命令 → 数值） |
|---|---|---|
| AC-1 V16 两表/幂等/不回填 | 通过 | `change-verify` S1；`t1-static-check.sh`；flyway=16，V1–V15 未改 |
| AC-2 创建校验+同事务起审批 | 通过 | `qa-r40-ac-probe` 46/46（类型/原因/无变化/白名单 400） |
| AC-3 影响快照固定键并冻结 | 通过 | `qa-r40-impact-shape` 4/4（7/7/8 键），库内 `impact_json` 同 8 键 |
| AC-4 第 6 类 REQ_CHANGE 两级审批 | 通过 | `t3-apply-e2e` 29/29；审批中心摘要探针（上轮）＋ e2e 深链 |
| AC-5 通过≠生效 | 通过 | `qa-r40-approved-ui` 7/7：横幅「审批已通过，尚未应用」、需求字段不变、APPLY 渲染 |
| AC-6 多 PENDING 并存/冲突 409 零写 | 通过 | `t3-apply-e2e`（冲突 apply 409 `CHANGE_CONFLICT`、标题未被覆盖、items 未增） |
| AC-7 单事务+并发恰一次 | 通过 | `qa-r40-ac-probe` [31]（200/409，无 500）；`t3-apply-e2e` 状态 APPLIED、items=fields |
| AC-8 apply 负向全零副作用 | 通过 | `qa-r40-ac-probe`（未审批 409、重复 409、无权 403） |
| AC-9 人天 >20% 转 STALE | 通过 | `qa-r40-ac-probe`（恰 20% 不转、±25% 转、12.01 → 400）；`WbsEstimateService.wouldBeStale` 单点 |
| AC-10 锁定版本可应用并留痕 | 通过 | `t3-apply-e2e` §7：LOCKED 下 apply 200、`release_requirement` 1 行、`CHANGE_AFTER_RELEASE_LOCK` 1 条 |
| AC-11 创建即拦（父链/环/人天/精度） | 通过 | `qa-r40-gate-fixture-check` 7/7；`qa-r40-ac-probe` 负向 |
| AC-12 PUT 收口 409 CHANGE_REQUIRED | 通过 | `t3-apply-e2e` §8（晚期态 PUT 409 带 `changeEndpoint`；非导航字段放行） |
| AC-13 编辑表单只读+发起变更 | 通过 | e2e 33/33（四组全锁、组内「需发起变更」、409 banner 预填） |
| AC-14 列表/详情契约与越权口径 | 通过 | `change-verify` S3/S4；`qa-r40-interface-check` 34/34（路径与类型一致） |
| AC-15 两动作码+声明式鉴权 | 通过 | 路由表 5 条；`sys_action`=75；VENDOR_PM DENY 两码（`t3-apply-e2e` 403） |
| AC-16 审计与逐字段事件 | 通过 | `t3-apply-e2e`（1 条审计+每字段 1 条事件） |
| AC-17 第 10 Tab/四步抽屉 | 通过 | `web/e2e/req-change.spec.mjs` 33/33：Tab、角标、720 抽屉四步、preview 影响卡 |
| AC-18 深链详情/APPLY 仅 APPROVED | 通过 | `qa-r40-approved-ui` 7/7；e2e 深链与措辞 |
| AC-19 change-verify 6 段/PASS==TOTAL/两次一致 | 通过 | `bash scripts/change-verify.sh --twice` → 两轮均 `CASE=42 PASS=42 FAIL=0`，结论「可重复，无顺序依赖」 |
| AC-20 8 项门禁不降 | 通过 | 见 §4 表（194/130/206/113/80/44/40/20 + lint 24 / unit 475 + web build） |
| AC-21 三份文档与台账 | 通过 | `docs/teamflow/CHANGE-REQUEST-FLOW.md`、`REQUIREMENT-FLOW.md` 变更入口、`DELIVERED-MODULES.md` r40 行均在；`change-verify --static-only` 12/12 含文档断言 |
| AC-22 仅新增 V16/依赖文件不动 | 通过 | `git status --porcelain` 中 `pom.xml`、`web/package.json`、`package-lock` 为空；V16 唯一新增，V1–V15 无修改 |
| AC-23 分支/提交信息 | 部分可验 | 分支 `feat/req-change-request` 正确；提交信息含 runId 需 host 提交后复核（本阶段无提交） |

## 4. 门禁与回归（全部实跑）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 变更闭环 | `bash scripts/change-verify.sh --twice` | **42/42 ×2，两次一致**（S1–S6） |
| 变更静态 | `bash scripts/change-verify.sh --static-only` | 12/12（含「自声明 42 == ck 调用点 42」） |
| 平台 | `bash scripts/platform-verify.sh` | 194/194 |
| 负向 | `bash scripts/platform-test-negative.sh` | 130/130（退出复位干净） |
| 权限矩阵 | `bash scripts/access-verify.sh` | 206/206 |
| RBAC | `bash scripts/rbac-verify.sh` | 113/113（11 段） |
| 演示复位 | `bash scripts/platform-demo-reset.sh` | 80/80（连跑 3 次一致） |
| 发布流 | `bash scripts/release-flow-verify.sh` | 44/44 |
| 自动化 | `bash scripts/automation-verify.sh` | 40/40 |
| 门禁顺序 | `bash scripts/gate-order-verify.sh` | 20/20（A/B 两序一致） |
| 单测 | `bash scripts/platform-unit.sh` | 475 通过 |
| Lint | `bash scripts/tests/lint.sh` | 24/24 |
| Web | `cd web && npm run typecheck` / `npm run build` | 0 诊断 / ✓ 8.39s（2717 modules） |

QA 自有探针（全部重跑）：`qa-r40-ac-probe` 46/46、`qa-r40-impact-shape` 4/4、`qa-r40-gate-fixture-check` 7/7、`t3-apply-e2e` 29/29（两次）、`qa-r40-pm-apply` 6/6（两次）、`qa-r40-approved-ui` 7/7（两次）、`web/e2e/req-change.spec.mjs` 33/33（两次）、`qa-r40-interface-check` 34/34、`qa-r40-arch-check` 12/12。

## 5. 架构核对（P1 质量门）

| 蓝图要求 | 实测 | 判定 |
|---|---|---|
| 变更域独立成包，不并入 `RequirementService` | `requirement/change/` 17 个类；`RequirementService` 仅引用 `RequirementChangeFields`/guard | 符合 |
| 写端点鉴权只在 `PermissionRouteTable` 声明 | change 包零 `@RequiresAction` 标注；路由表 5 条且在 `/requirements` 兜底之前 | 符合 |
| apply 先锁需求行 | `RequirementChangeApplyService` 调 `findWithLockByIdAndTenantId`（PESSIMISTIC_WRITE） | 符合 |
| 版本成员移动绕过 `ReleaseService` 的 `RELEASE_LOCKED` | `RequirementReleaseMembership` 自实现先删后插，未调 `addRequirements/removeRequirement` | 符合 |
| 早期态表单点 | `RequirementChangeFields.editableFields` 唯一实现；前端只读 `permissions.editableFields` | 符合（无复制） |
| 人天阈值单点 | `WbsEstimateService.STALE_THRESHOLD` 一处；Assessor 调 `wouldBeStale` | 符合（无第二处 0.20 比较） |
| 迁移受控 | 仅新增 V16，V1–V15 未改，pom/package.json 无 diff | 符合 |
| 重置顺序 | `DemoFixture` 两表入 `DELETE_ORDER`（子先于父），子表按 `change_id` 谓词清理 | 符合 |

结论：无重复实现、无该抽未抽、无既有结构被破坏。架构项 0 缺陷。

## 6. 缺陷表（仍 OPEN）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-10 | P3 | 工艺 · 变更角标 | 断开 req-service 或使 `GET /requirements/{id}/changes?status=PENDING` 失败后刷新需求详情 | 角标保持上次值或显示「未知」，不误导成「无待审批」 | `loadChangeCounts` 的 `catch` 直接把 `changePending/changeApprovedCount` 置 0，角标消失（`RequirementDetailView.vue:272-284`） | AC-17 | `grep -n "tabCounts.changePending = 0" web/src/views/req/RequirementDetailView.vue` | 失败分支不再写 0（保留旧值或置未知态） |
| D-11 | P3 | 测试卫生 · web e2e | `cd web && node e2e/req-change.spec.mjs` 后查库 | e2e 结束时清理自己造的变更单（与 `change-verify` 一致） | 撤回用例留下 1 行 `status=WITHDRAWN` 的 `req_change`（挂在演示需求上，演示时该需求的「变更」Tab 会多一条历史） | AC-17/AC-19 | `cd web && node e2e/req-change.spec.mjs && bash -c 'source scripts/lib.sh; db_q "SELECT COUNT(*) FROM req_change WHERE status=\"WITHDRAWN\""'` | e2e 结束后查询返回 0 |

上一轮其余缺陷已全部关闭，不再列入本表（对照见 §2）。

## 7. 接口一致性（静态，无需运行态）

`bash logs/teamflow/tf-mwt1f266-4e4y2h/scripts/qa-r40-interface-check.sh` → **34/34 PASS**。要点：

- 5 个前端调用路径逐个在控制器 `@*Mapping` 中找到（含 `/{id}/apply` 单独控制器）。
- `RequirementChangeViews` 的 Detail/Preview 组件（`canApply/status/changeType/before/after/items/impact/instanceId/appliedAt/appliedBy`）与前端 `ChangeDetail` 类型逐项对应。
- 后端 `permissions` 已放宽为 `Map<String,Object>`，`RequirementService.changePermissions` 下发 `changeRequest/changeApply/editableFields`；前端 `types.ts` 同步声明。
- impact 8 键在 Assessor 与前端 `ChangeImpact`（`web/src/api/req.ts:729-739`）同集合。
- 前端就地处理 `CHANGE_REQUIRED` / `CHANGE_CONFLICT`（非 toast）。

## 8. 交付形态与提交卫生

- 形态：可运行的 Spring 服务 + Vue 前端，已用真实栈验证（非「文件存在」式签收）：`change-verify` 42 条、`t3-apply-e2e` 29 条、`qa-r40-approved-ui` 7 条均为 HTTP/CDP 实测。
- `git status --porcelain` 62 项：1 个文档目录、17 个 change 类、V16、7 个前端新文件、测试与脚本；**无** `node_modules/`、`target/`、`dist/`、`.env`、IDE 文件。`web/node_modules`、`web/dist`、`logs/` 均被 `.gitignore` 命中。
- 未提交符合本阶段约定（host 统一提交）；AC-23 的提交信息项留待验收环节。

## 9. 人工补测清单（环境限制，非交付缺陷）

| 项 | 原因 | 方法/工具 |
|---|---|---|
| 真机 1440×900 视觉终检（抽屉 720 宽、对照表换行、影响卡排版） | agent 沙箱无法启动浏览器（host 令牌限制）；CDP 9222 的脚本化断言已覆盖尺寸与文案 | 人工打开 web 5173 → 需求详情 → 变更 Tab → 四步抽屉 |
| 深浅色主题与动效手感 | 同上，属观感项 | 人工切换主题目测 |
| 屏幕阅读器/键盘焦点顺序 | 无法脚本化 | axe + 键盘走查 |

## 10. 备注（QA 方法学，不记为交付缺陷）

- **门禁不可并发跑**：首次把 `rbac-verify` 与 `access-verify` 同时启动，access 的 a2 出现 1 条 403 假阳性（rbac s7/s8 会改角色授权并重置演示库）；串行复跑 206/206。已改为一条命令一个门禁。
- 演示夹具 id 每轮 reset 都变，检查器统一改为「按类型+状态查 id」；`qa-r40-cleanup-fixtures.sh` 会连需求行一起删，只能用于自建夹具，不能指向演示需求（本轮误用一次，已用 `platform-demo-reset` 复位，基线恢复 12 条）。

## 11. 结论

**可验收**。上轮 3 个 P1 + 5 个 P2 全部按原 Check 命令复跑通过；AC-1…AC-22 均有实跑证据；AC-23 的提交信息待 host 提交后复核。剩余 2 条 P3 观测项（角标失败态、e2e 残留）不影响功能与门禁，建议下一轮顺手修。
