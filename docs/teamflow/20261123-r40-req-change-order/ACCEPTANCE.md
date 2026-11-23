# r40 需求变更单 · 验收报告

- 验收对象：需求变更单闭环（申请 → 影响评估 → 两级审批 → 显式应用 → before/after 基线）
- 分支：`feat/req-change-request`；交付 62 项改动未提交（按流程由 host 在验收收口时统一提交）
- 验收方式：逐条对照 PRD/状态索引 AC，独立复跑关键门禁 + 复核 QA 实跑证据 + 架构一致性核对
- 总评：功能 AC 全部满足，架构 0 偏差；遗留 3 项 P3（不阻断）

## 1. 本轮独立复验（非引用 QA，均为本阶段实跑）

| 项 | 命令 | 实测 |
|---|---|---|
| 变更闭环（两次一致） | `bash scripts/change-verify.sh --twice` | 两轮均 `CASE=42 PASS=42 FAIL=0`，rc=0，结论「可重复，无顺序依赖」 |
| 单测 | `bash scripts/platform-unit.sh` | 475 个全部通过 |
| Lint | `bash scripts/tests/lint.sh` | 24/24 |
| Web 类型 | `cd web && npm run typecheck` | 0 诊断（vue-tsc 静默） |
| 运行态 | `GET :8082/internal/health` | `db=up`，`flyway=16` |
| 计数基线 | `sys_action` / `sys_role_permission` / `req_change` | 75 / 每租户 1903（90×1903=171270）/ 0 行；演示需求 12 条 |
| 交付面 | `git status --porcelain` | 62 项：17 个 change 类、V16、16 个前端文件、脚本与文档；`pom.xml`/`web/package.json`/lock 无 diff；旧迁移 V1–V15 无改动 |
| 架构抽查 | grep | change 包 `@RequiresAction` 真标注 0（2 处为 Javadoc 说明）；`findWithLockByIdAndTenantId` 在 apply 路径；`editableFields` 单点；`wouldBeStale` 单点且 Assessor 复用 |

QA 已实跑并留证的门禁（本阶段未重跑，采信其原始输出）：platform-verify 194/194、negative 130/130、access 206/206、rbac 113/113、demo-reset 80/80×3、release-flow 44/44、automation 40/40、gate-order 20/20、web build 0 error。

## 2. AC 逐条核对

| AC | 结论 | 证据 |
|---|---|---|
| AC-1 V16 两表/旧迁移不动/幂等/不回填 | ✅ | V16 唯一新增，两表两索引、复合主键；flyway=16；`req_change`=0；change-verify S1 |
| AC-2 创建校验 + 同事务起审批 | ✅ | S2；qa-ac-probe 46/46（类型/空原因/无变化/白名单 400）；失败零留痕 |
| AC-3 impact 8 键冻结 | ✅ | qa-impact-shape 4/4（before=7/after=7/impact=8）；S2 原样返回 |
| AC-4 第 6 类 REQ_CHANGE 两级审批 | ✅ | SeedService 默认 PRODUCT→DOMAIN_OWNER；t3 29/29；审批中心摘要与深链 e2e |
| AC-5 通过 ≠ 生效；驳回/撤回不改需求 | ✅ | qa-approved-ui 7/7（「审批已通过，尚未应用」+ 字段不变）；S3 逐字段比对 |
| AC-6 多张 PENDING 并存；冲突 409 零写 | ✅ | t3（409 CHANGE_CONFLICT，标题未覆盖、items 未增） |
| AC-7 单事务 + 并发恰一次 | ✅ | S4；并发 apply 仅 200/409；items=fields；审计恰 1 条 |
| AC-8 apply 负向零副作用 | ✅ | qa-ac-probe：未审批/重复/无权/不存在/已关闭全部按码；字段/items/审计/状态无变化 |
| AC-9 人天严格 >20% 转 STALE，预测=实际 | ✅ | S5：+20% 不转、+20.1% 转、预测==实际；`wouldBeStale` 单点 |
| AC-10 锁定/已发布仍可 apply 并留痕 | ✅ | t3 §7：LOCKED apply 200、`CHANGE_AFTER_RELEASE_LOCK` 1 条、release_requirement 1 行；PLAN 不写锁审计 |
| AC-11 领域规则创建即拦 | ✅ | gate-fixture 7/7（父链/环/父人天只读/0.5 精度/目标版本）；与 PUT 同码 |
| AC-12 PUT 四类字段 409 CHANGE_REQUIRED | ✅ | S5/S6：detail 含 changeEndpoint/fields；早期态、同值、非四类字段放行 |
| AC-13 编辑表单只读 + 发起变更 | ✅ | e2e 33/33：四组按 `permissions.editableFields` 锁定、组内预填、409 banner 一键打开抽屉 |
| AC-14 列表/详情契约与越权 | ✅ | S3/S4 分页/过滤/形状；interface-check 34/34；越权 403/404 同口径 |
| AC-15 两动作码 + 声明式鉴权 | ✅ | `sys_action`=75；路由表 5 条在 `/requirements` 兜底前；VENDOR_PM 双 403；change 包零 `@RequiresAction` |
| AC-16 审计 + 逐字段事件 | ✅ | 创建 1 条 CHANGE_REQUESTED、apply 1 条 REQUIREMENT_CHANGE_APPLIED；每字段恰 1 条事件（automation 40/40 不回归） |
| AC-17 第 10 Tab + 四步抽屉 | ✅ | e2e：Tab 角标、720 抽屉四步、第④步必走 preview、提交防重 |
| AC-18 深链详情 / APPLY 仅 APPROVED | ✅ | 深链 pageCode=req.pool；APPLY 仅 `APPROVED && canApply`；二次确认；冲突高亮 |
| AC-19 change-verify ≥14 条/6 段/两次一致 | ✅ | 本阶段实跑 42/42×2；`--static-only` 12/12；RUN_TAG 退出复位 0 残留 |
| AC-20 8 项既有门禁不降 | ✅ | QA 全量实跑见 §1；本阶段复核 unit 475、lint 24、change-verify 42 |
| AC-21 文档与交付卫生（P1） | ✅ | CHANGE-REQUEST-FLOW 新建、REQUIREMENT-FLOW 变更入口、DELIVERED-MODULES r40 行、memory r40 同主题行均在；分支/依赖文件/gitignore 复核通过（提交项见 AC-23） |
| AC-22 仅新增 V16、依赖文件不动 | ✅ | V16 是唯一新增迁移；pom/package.json/lock diff 为空；web build 0 error（QA） |
| AC-23 分支与提交信息 | ◐ | 分支 `feat/req-change-request` 正确；62 项尚未提交——按流程由 host 收口，提交信息须含 `runId=tf-mwt1f266-4e4y2h` 与任务夹路径，否则本项不成立 |

## 3. 架构一致性（M3 质量门）

| 蓝图要求 | 实测 | 判定 |
|---|---|---|
| 变更域独立成包，不并入 RequirementService | `requirement/change/` 17 类；RequirementService 仅调 Guard/Fields | 符合 |
| 写端点鉴权只在 PermissionRouteTable | 真标注 0；5 条规则顺序正确 | 符合 |
| apply 单事务：状态闸→需求行锁→比对→复写 | `findWithLockByIdAndTenantId`（PESSIMISTIC_WRITE）+ 条件更新 | 符合 |
| 早期态表单点 | `RequirementChangeFields.editableFields` 唯一；前端只读后端 | 符合 |
| 人天阈值单点 | `WbsEstimateService.STALE_THRESHOLD/wouldBeStale` 唯一，无第二处 0.20 | 符合 |
| 版本成员移动绕过 RELEASE_LOCKED | `RequirementReleaseMembership` 先删后插，未调 ReleaseService | 符合 |
| 迁移与重置顺序 | 仅 V16；DemoFixture 子表先于父表 | 符合 |

结论：无「偏离蓝图 / 重复实现 / 该抽未抽 / 破坏既有结构」，无需架构返工。

## 4. 遗留（P3，不阻断验收，建议下一轮顺手处理）

1. **D-10 角标失败静默为 0**：`web/src/views/req/RequirementDetailView.vue` 的 `loadChangeCounts` catch 分支把 `changePending/changeApprovedCount` 置 0，接口失败与「无待审批」不可区分。建议保留旧值或置「未知」。
2. **D-11 e2e 残留**：`web/e2e/req-change.spec.mjs` 撤回用例留下 1 行 `WITHDRAWN` 的 `req_change`（挂在演示需求上）；建议结束时清理或改用自建夹具。
3. **台账计数漂移**：`DELIVERED-MODULES.md` r40 行写「change-verify 40/40」、规模段写「unit 392」，实测为 **42/42** 与 **475**；`memory.md` 同段亦写「change-verify 40」。AC-19 只要求 ≥14 条，不影响达标，但台账数字应与实测一致（本报告不改他人交付物，交下一轮订正）。

人工补测（环境限制，非缺陷）：真机 1440×900 视觉终检（抽屉 720、对照表换行、影响卡排版）、深浅色主题与动效、屏幕阅读器/键盘焦点；CDP 脚本化断言已覆盖尺寸/文案/深链。T4 期截图早于后端合入（页内可见「变更单加载失败」为当时 404），最终态以 QA e2e 33/33 为准。

## 5. 验收意见

交付实现了需求的核心语义：审批通过不生效、apply 单事务落字段 + items + 审计 + 基线；锁定版本下从「只能线下协调」变为可留痕变更；PUT 收口与四步抽屉形成完整引导。门禁与架构均未退化。P3 项为工艺问题，不改变功能与数据正确性，可随下一轮清理。

验收结论：✅ 通过
