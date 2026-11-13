# r15 演示数据重置污染 · 验收报告

- 验收人：产品经理（验收负责人）｜验收日期：2026-11-13
- 验收依据：本需求为技术驱动返工（`meta.json` mode=tech），无独立 PRD；以 [TECH-CHANGE.md](TECH-CHANGE.md) §1 目标、§2 覆盖审计、§4 方案契约、§6 回归计划为验收契约，基线 r14 任务夹。
- 分支 `feat/demo-reset-pollution`，HEAD `a41b48d`；被测改动 6 个文件未提交 + 任务夹未跟踪（提交由流水线收口，见 §4）。
- 环境：本地全栈 gateway/req/deliver 健康 200，MySQL `devops_platform`；以下均为验收侧**独立复跑**，不采信开发/QA 摘要。

## 1. 独立复跑证据

| 命令 | 结果 |
|---|---|
| `bash scripts/platform-unit.sh` | **262/262**，exit 0（含 `DemoResetRetentionTest` 5/5） |
| `bash scripts/platform-demo-reset.sh` | **49/49 ×3**，exit 0（初跑、`platform-verify` 污染后、分析门禁软删残留后收尾各一次；含连跑两遍幂等、库内/API 复核） |
| `bash scripts/platform-verify.sh` | **167/167**（AC-1..AC-20），exit 0 |
| `bash scripts/access-verify.sh` | **145/145**，exit 0 |
| `bash scripts/analytics-i18n-verify.sh` | **74/74**（live 41/41；Playwright 可用），exit 0 |
| `POST /internal/demo-reset` 无 token / 错 token | **401 / 401**（双门有效；关闭态 404 由既有门禁覆盖） |
| 静态复核（grep + diff） | `DELETE_ORDER` 50 项单点；`DemoResetService` 用 `for (DemoFixture.TableReset …)` + `AND NOT (keepFilter)`；Controller 零 diff；bash 无表清单 |

## 2. 逐条验收

| 编号 | 验收项（TECH-CHANGE 出处） | 通过判据 | 实测 | 结论 |
|---|---|---|---|---|
| AC-1 | 报表中心重置后为空（§1/§4.2） | `reports=0`、库内 0、`/reports` total=0 | 0/0/0，脚本 [17][30][42] | ✅ |
| AC-2 | 岗位工作台回退角色模板（§1/§4.2） | `portalCards=0`、`portalPins=0`、cards=4 且无 `report:` | 0/0，cards=4，[18][19][43][44] | ✅ |
| AC-3 | 开放客户端清零（§4.1） | `apiClients=0`、库内 0 | 0/0，[21][32] | ✅ |
| AC-4 | 执行器保留且还原基线（§4.1/§4.3） | 恰 2 行、code=LEGACY-OFF/STUB-LOCAL、enabled 0/1、`last_report_at=NULL`、`report_count=0`、API total=2 | [20][33][34][35][41] 全 PASS | ✅ |
| AC-5 | 演示夹具口径不变（§4.2「原 AC-13 口径保留」） | 需求12/版本1/迭代1/任务8/用例12/计划1/计划用例10/缺陷3/通知4/隔离 8-3-0/痕迹0 | 49/49 中 [2]–[16][25]–[28][47]–[49] | ✅ |
| AC-6 | 运行时残留清理（§2 修正/D-01、D-02） | `test_strategy=0` 且无孤儿；三表 RUN_TAG 残留 0；种子 wf≥4/module≥3/env≥1；API 无残留 | [23][24][36]–[39][45][46]；QA 行为探针 33/33 | ✅ |
| AC-7 | 重置幂等（§4.3） | 两遍 counts 逐键相等 | [01] PASS；本会话共 3 次、QA 多轮合计 ≥5 次 | ✅ |
| AC-8 | 漏删必须变红（§4.2 三层断言） | 服务端 `violations()` 409 + 脚本 23 键断言 + retention 单测契约 | 服务端 9 键断言（含 6 个 r15 键）；脚本 23 个 `check_count`；单测 5/5（过滤方向、api_client 无过滤、`test_strategy` 先于 release） | ✅ |
| AC-9 | 删除序列单一来源（§4.1/架构蓝图） | `TableReset` 仅一处、50 项有序、无第二份表清单、Controller/依赖方向不变 | 代码 + QA 静态契约 59/59 | ✅ |
| AC-10 | 兼容性（§5） | 无 API 形状/schema/迁移变化；批量删除只在双门端点 | 6 文件无迁移；401/401；reset 外写入路径零改动 | ✅ |
| AC-11 | r13/r14 零回归（§6） | platform-verify 不减、access ≥143、analytics 通过 | 167/167、145/145、74/74；QA 另测 negative 25/25、lint 18/18、verify 14/14、s7 31/31 | ✅ |
| AC-12 | 提交面与文档（§3/§7） | 无构建产物/密钥；memory 同主题行替换；回滚单元为单次提交 | `git status` 仅 6 M + 任务夹；memory 1 行替换；**改动尚未提交** | ✅（提交待收口） |

## 3. 架构一致性（M3 强制项）

- **符合蓝图**：无新模块；`TableReset` 只在 `DemoFixture` 定义一份，`DELETE_ORDER` 50 项单点有序；`DemoResetService` 以 `TableReset` 消费并拼 `AND NOT (...)`；`DemoResetController` 零改动；依赖方向 脚本→HTTP→Controller→Service→Fixture 不变。
- **无重复实现/适配漂移**：bash 侧无表清单、无 `PRE_CLIENTS`/前后快照；跨进程仅保留「counts 键 + 字面量期望」与 RUN_TAG 谓词各一份（技术单已声明为跨进程边界，允许）。r15-fix 增补 4 张运行时表与 3 个计数键，属同一契约内修复，未破坏单点。
- **结论：无架构返工项**，不触发「有条件通过」条件。

## 4. 遗留与建议（均不阻断）

1. **P3-01（沿用 QA）**：`DemoFixture.RESTORE_KEPT_EXECUTOR_BASELINE` 的 `CASE code WHEN 'STUB-LOCAL' THEN 1 ELSE 0 END` 与 keepFilter 的 code 集合是两份字面量，未来新增保留 code 会被静默置 `enabled=0`；建议下次触碰时按种子 code 集合生成。
2. **技术单文档漂移（P3）**：§4.2 仍写「新增 6 键」，实现为 9 键（另含 `testStrategies`/`testEnvs`/`gateResidue`）；§3 影响范围未列 `scripts/tests/analytics/i18n-live.mjs`（必要回归适配：重置清空报表后门禁自建 3 张临时报表，74/74 已证有效）。
3. **i18n 门禁软删残留（P3）**：实测其 `DELETE` 为软删，跑完 `report_definition` 留 4 行 `status=DELETED`；用户不可见、下一次 reset 清除，与「重置后报表 0 行」不冲突，但注释「不给下一次重置留垃圾」表述不准确，建议改硬删或修正注释。
4. **wiki 探针页未清**（§8 已声明待办，`wiki_comment`/`wiki_page_link` 同理），本轮未扩范围，接受。
5. **改动未提交**：6 M + 任务夹 untracked，按 §7「单次提交整体 revert」收口；提交后应复跑 `git status` 确认仅忽略项。
6. 人工视觉走查（重置后 `/reports` 空态、`/portal` 四卡）未做截图，属环境/流程限制，非交付缺陷；自动门禁的 Playwright 段本轮可运行。

## 5. 结论

功能与架构契约全部满足：4 张漏删表 + 4 张运行时残留表纳入单点删除序列，报表/门户/客户端/执行器/通知/夹具口径均由服务端不变量、脚本、API/库三层守卫；漏删会使重置变红；r13/r14 零回归。仅 3 条 P3 观察（含 QA 已记录的基线 CASE 硬编码），无 P1/P2，不构成返工。

验收结论：✅ 通过
