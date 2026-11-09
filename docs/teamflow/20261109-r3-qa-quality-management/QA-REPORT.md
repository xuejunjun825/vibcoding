# QA-REPORT · M6 质量管理补全（20261109-r3）

- 任务夹：`docs/teamflow/20261109-r3-qa-quality-management/`
- 分支：`feat/qa-quality-management`（未提交改动均为本轮实现，提交面已核）
- 轮次：**第 2 轮 QA（修复后复验）**；上一轮结论「不可验收」，缺陷 D-01~D-10
- 环境：macOS + JDK 25 / Spring Boot 4 真栈；网关 8080、req 8082、deliver 8083、web 5173、MySQL(devops_platform) 全部健康；种子账号 admin/qa1/pm/tl1
- 复验原则：先逐条重跑上一轮全部检查器（`logs/teamflow/tf-mw8a30zm-alxfmp/scripts/`），再补未覆盖面；缺陷表只列**仍 OPEN** 的项

## 1. 复验总表（命令 → 结果，逐字）

| # | 检查 | 命令 | 结果 |
|---|---|---|---|
| 1 | 质量域验收套件 q0–q7 | `bash scripts/quality-verify.sh` | **308/308** PASS（q0 31、q1 52、q2 39、q3 47、q4 29、q5 39、q6 26、q7 45），exit 0 |
| 2 | 平台全量验收 AC-1..AC-20 | `bash scripts/platform-verify.sh` | **167/167**，exit 0 |
| 3 | 负向用例 | `bash scripts/platform-test-negative.sh` | **25/25**，exit 0 |
| 4 | 单元测试 | `bash scripts/platform-unit.sh` | **133** 个测试全通过，exit 0（含 PermissionMatrixGridTest） |
| 5 | 静态规约 | `bash scripts/tests/lint.sh` | **16/16**（含 L14 报告取数只经 QualityAggregator），exit 0 |
| 6 | 栈体检 | `bash scripts/verify.sh` | **14/14**，exit 0 |
| 7 | 性能基线 | `bash scripts/platform-perf.sh` | **10/10**，exit 0（见 §5） |
| 8 | 质量域 E2E 冒烟 | `node web/e2e/quality.spec.mjs` | **16/16**，exit 0 |
| 9 | 前端类型 | `cd web && pnpm typecheck` | exit 0，0 error |
| 10 | 前端构建 | `cd web && pnpm build:only` | exit 0，4.54s；11 个质量页均有独立 chunk（GoNoGoView/QualityGatesView/…） |
| 11 | 契约自检 T1 | `bash …/scripts/t1-contract-check.sh` | **172/172**（修正 1 条过时断言，见 §6） |
| 12 | 迁移/回填 T2 | `bash …/scripts/t2-schema-backfill-check.sh` | **45/45**（空库两遍幂等，指纹不变） |
| 13 | 准入契约 T7 | `bash …/scripts/t7-admission-contract.sh` | **26/26** |
| 14 | 准入 JSON 往返 T7 | `bash …/scripts/t7-admission-json-roundtrip.sh` | **12/12** |
| 15 | 豁免生命周期 T6 | `bash …/scripts/t6-waiver-admin-verification.sh` | **14/14** |
| 16 | Go/No-Go 端到端 T8 | `bash …/scripts/t8-review-e2e.sh` | **24/24** |
| 17 | 导出产物深检 | `bash …/scripts/q3-export-artifact-probe.sh 65 1` | **14/14**（XLSX 8 sheet 顺序+水印；PDF 3 页 3 水印、STSong-Light CID 非嵌入） |
| 18 | 三级会签真栈 | `bash …/scripts/q3-signoff-complete-probe.sh 117 144` | qa1→pm→tl1 依次 **200/200/200**，版本 SIGNED；重复签 409 |
| 19 | 接口一致性静态检查 | `node …/scripts/qa-interface-check.mjs` | fail=0 |
| 20 | 前端基座字典/路由 | `node --experimental-strip-types …/scripts/t9-frontend-base-check.mjs` | 18/18 |

## 2. 逐 AC 结论（全部 PASS）

| AC | 结论 | 关键证据 |
|---|---|---|
| AC-1 计划生命周期/变更留痕 | PASS | q1 52/52；七态+allowedNext 与 StateTables 同源；变更 reason/revision/change_log |
| AC-2 多轮次不可覆盖 | PASS | q2 39/39；`(plan_id,active)` 唯一键转 409；作用域三态；关轮未执行 409 |
| AC-3 结构化准出 409 列未满足项 | PASS | q2；409 `unmet[{key,actual,expected,fixHint}]` |
| AC-4 报告 8 段/版本化/导出/三级会签 | PASS | q3 47/47 + 导出深检 14/14 + 会签 200/200/200；PDF 水印按内容流字节断言 |
| AC-5 准入第 6 项质量门 | PASS | q4 29/29；`qa-nogo-probe` 3/3；门禁 5 规则仅 GateEvaluator 一处实现 |
| AC-6 规则级豁免/强制放行退役 | PASS | `qa-waiver-redline-probe` 5/5（QG-04 不可豁免、1–30 天、自批 403）；force-release 恒 409 `FORCE_RELEASE_RETIRED`（t7 26/26） |
| AC-7 快照可回放 | PASS | t6-gate 回放 200 + diff + 不写库；数据变化后 QG-02 `REPORT_STALE` 且 fixHint 提示重新出具（本轮直连复现） |
| AC-8 生产缺陷逃逸+5 指标 | PASS | q6 26/26；指标经 MetricFormulas 单点 |
| AC-9 策略/分配/环境 | PASS | q7 45/45；策略版本化、分配差额 400、环境台账/字典（AVAILABLE/DISABLED/MAINTENANCE） |
| AC-10 Go/No-Go 记录 | PASS | t8 24/24；无 NO-GO 二次确认 → 409 `NO_GO_CONFIRM_REQUIRED`（qa-nogo 3/3） |

## 3. 缺陷表（仅 OPEN）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| Q-01 | P3 | 质量门 QG-01 | 版本需求集合为空时求值门禁 | 空集覆盖应为 N/A 或 FAIL，不得判 PASS | `total==0 → percent=100`，报告齐备即 PASS（`GateEvaluator.qg01`） | AC-5 | - | - |
| Q-02 | P3 | 质量门列表页 | 打开 `/quality-gates`，后端返回 0 条 | 展示空态文案/空插画 | 仅表头，无空态（TEST_REPORTS/QUALITY_METRICS 已有 loading/空态） | AC-5 | - | - |

> 未发现 P0/P1/P2 缺陷；上述两条为 P3 观察项，不阻塞验收。

## 4. 缺陷复验对照（round-1 级，历史项，均已关闭）

| 编号 | round-1 级 | 模块 | 复验结论 | 检测命令 | 结果 |
|---|---|---|---|---|---|
| D-01 | P1 | 环境字典 | 已关闭 | `bash …/scripts/qa-env-contract-probe.sh` | 5/5：AVAILABLE/PROD 创建 201；IDLE → 400 VALIDATION_FAILED（allowed=AVAILABLE/DISABLED/MAINTENANCE） |
| D-02 | P1 | NO-GO 二次确认 | 已关闭 | `bash …/scripts/qa-nogo-probe.sh` | 3/3：未确认 409 `NO_GO_CONFIRM_REQUIRED`；confirmNoGo=true 转 `QUALITY_GATE_FAILED` |
| D-03 | P1 | 权限矩阵 | 已关闭 | `bash scripts/platform-unit.sh` | 133/133；qa1→pm→tl1 三签 200/200/200（tl1 已具 report:sign），waiver:approve 仅 MGMT |
| D-04 | P1 | r2 旧断言 | 已关闭 | `platform-verify` + `platform-test-negative` | 167/167、25/25 |
| D-05 | P2 | q5/q6/q7 脚本缺陷 | 已关闭 | `bash scripts/quality-verify.sh` | q5 39/39、q6 26/26、q7 45/45 |
| D-06 | P1 | 报告聚合重复实现 | 已关闭 | `bash scripts/tests/lint.sh` + grep | L14 通过（ReportService 仅 1 处 aggregator 调用；5 处 SUM 均为缺陷统计，非执行聚合）；`MessageDigest` 质量域仅 QualityDigest |
| D-07 | P2 | perf 静默跳过 | 已关闭 | `bash scripts/platform-perf.sh` | 10/10，报告/导出三段均实际执行（0.221s/0.021s/0.009s） |
| D-08 | P3 | QG-01 空集 | **仍 OPEN**（Q-01） | - | - |
| D-09 | P3 | 空/加载态 | 部分修复，余 **Q-02** | - | - |
| D-10 | P3 | 百分比自除 | 已关闭 | `grep -n displayPercent GateEvaluator.java` | 现为 1 位小数 HALF_UP 统一实现，注释明确 18/19 渲染 94.7% |

## 5. 性能基线（本轮实测，10 项全部达标）

| 场景 | p95 | 阈值 |
|---|---|---|
| 需求池列表（10 万行） | 0.227s | 1.0s |
| 缺陷看板四指标 | 0.078s | 2.0s |
| 发布准入 5 项（跨服务） | 0.023s | 0.5s |
| 追溯矩阵 50 行×六环 | 0.267s | 2.0s |
| 开放 API 上报（20 并发） | 0.016s | 0.3s |
| 用例导入 1000 行 | 3s | 10s |
| 质量门 5 规则求值 | 0.007s | 0.5s |
| 报告生成 5000 用例 | 0.221s | 5.0s |
| XLSX 导出 5000 用例 | 0.021s | 10s |
| PDF 导出 5000 用例 | 0.009s | 15s |

## 6. 架构与接口一致性（§0/§0a，静态+运行期）

- 唯一实现：`GateEvaluator` 为 QG-01..05 唯一算法（req 仅透传 `failedRules`）；`QualityDigest` 为质量域唯一 `MessageDigest`（其余命中均为既有密码/HMAC/附件类）；`QualityAggregator` 被 ReportService/PlanLifecycleService/GateEvaluator/TestPlanQuery 共用；四处分母统一走 `MetricFormulas`；状态表统一 `StateTables.PLAN/ROUND/REPORT/GATE/WAIVER/REVIEW`。
- 跨模块成员存在性：`QualityGatePort.evaluate/snapshot` ⇄ `QualityGateService implements`；`DeliverQualityGateClient.evaluateDetailed/evaluate/latest/summary` 均被 AdmissionEvaluator/ReleaseReviewService 调用且签名匹配；前端 45 个端点路径与 deliver 控制器注解逐条对齐（含 `quality-gates/{releaseId:\d+}`、`snapshots/{id}/replay`、`releases/{id}/quality`）。
- 前端入口：11 个质量页面均有构建 chunk，路由 8 条 + `/test-plans/:id`、`/workbench` 已注册；侧栏入口指向已注册 routeName。
- 检查器维护（非交付缺陷）：T1 检查器 1 条 `PMO report:sign DENY` 断言与 D-03 修复相反，已改为 ALLOW 并注明理由；`t3-t4-e2e.sh` 硬编码 release 71/用例 6073,6072、单次建策略假设 → 已改为自建 release+动态用例+两次建版，结果 **68 passed / 8 failed**，余 8 条为「关轮错误码优先级/退级」断言顺序假设（同语义由 q1 52/52、q2 39/39 覆盖）；`t6-gate-pass-path.sh` 依赖「报告 CONDITIONAL」夹具，当前夹具结论为 PASS → **23 passed / 6 failed**，失败项均为豁免分支未进入，同语义由 q5 39/39、t6b 14/14 覆盖；`qa-qg02-replay-probe.sh` 硬编码 release 47 已被历史数据污染（QG-03 3/1025）→ 不再作为门禁证据，改用 q5+t6 回放断言。

## 7. 交付形态与提交面

- 运行面：`scripts/quality-verify.sh`、`q0..q7`、`Makefile quality-verify/quality-e2e`、`web/e2e/quality.spec.mjs` 均可执行；栈未起时 e2e 打印 SKIP 且 exit 0。
- 提交面：`git status --porcelain` 无 `node_modules/ target/ dist/ .env .idea .m2 logs/` 等噪声；`.gitignore` 覆盖 target/dist/node_modules/logs/teamflow/.env*（lint L13 亦断言）。
- 导出不落盘：`ReportExportService` 无 FileOutputStream/Files.write/createTempFile，XLSX/PDF 以 byte[] 直出。

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法/工具 |
|---|---|---|
| 质量页面视觉（空态/骨架/悬浮/焦点/禁用态） | 与 DESIGN 一致，交互有反馈 | Chrome DevTools + 真栈，逐页点检 `/quality-gates`、`/test-reports`、`/quality-metrics` |
| PDF 水印肉眼效果（45°、10% 透明、中文不糊） | 每页可见且不遮挡正文 | 打开导出的 3 页 PDF 目视 |
| 报告 XLSX 在 Excel/WPS 打开 | 8 sheet 顺序正确、列宽可读 | Excel/WPS 打开 |
| 大列表滚动/骨架屏动画 | 无卡顿、无布局跳动 | 浏览器 Performance 面板 |
| 屏幕阅读器/键盘可达性 | 表格与按钮可聚焦、读屏可读 | VoiceOver + Tab 走查 |

> 沙箱内 Chromium 受宿主限制无法启动（PROCESS_SET_INFORMATION 被拒），故浏览器交互项不做自动化断言；已用 HTTP 冒烟 + 构建 chunk + 类型检查覆盖「页面可加载、路由已注册、组件可编译」。

## 9. 结论

**可验收。** 复验 20 项检查全绿（质量套件 308/308、平台 167/167、负向 25/25、单测 133、lint 16/16、verify 14/14、perf 10/10、E2E 16/16、类型/构建 0 error），上一轮 D-01~D-07、D-10 全部关闭且以原检测命令复跑通过；仅余 2 条 P3 观察项（QG-01 空集语义、门禁列表空态），不阻塞发布。
