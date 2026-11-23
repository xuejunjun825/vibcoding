# r39 自动化规则引擎 · QA 报告（再验证轮 · 2026-11-23）

> 任务夹：`docs/teamflow/20261123-r39-automation-rule-engine/`　分支：`feat/automation-rules-engine`　HEAD：`d4ca363`（runId=tf-mwspkg4u-nvy5y5）
> 本轮是**再验证**：先复跑上一轮留下的全部 checker 与 D-1…D-9 的 Check command，再补上轮未覆盖的两个面（AC-13 慢 webhook、AC-14 §9 三截图 + 详情标签）。

## 1. 结论

**不可直接验收**：1 条 P2 功能缺陷（需求**详情**永不返回标签，列表有 chip、详情没有，违反 AC-14「列表与详情同源」），另有 4 条 P3 观察（其中 1 条是 DEMO-BY-ROLE §9.2 第二步不可按字面执行）。

除该 P2 外，交付的引擎能力与既有门禁全部达标：`automation-verify` 39/39 连跑两次、`gate-order` 20/20、`platform-verify` 194/194、`rbac` 113/113、`access` 206/206、`negative` 130/130、`rfv` 44/44、`lint` 23/23、`platform-unit` 443 条、`mvn test` BUILD SUCCESS；本轮新增覆盖 AC-13 真栈慢 webhook **12/12**、AC-14 §9 三张真栈截图 **3/3**。

## 2. 环境与范围

- 运行环境：真栈 8080（网关）/8082（req-service）/8083（deliver）/5173（web）全部 200，`/actuator/health` UP，MySQL `devops_platform`，Flyway 已在 V15；macOS，无 `timeout` 命令、`ps` 被沙箱拒绝。
- 本轮代码未改动任何产品源码；只新增/修正了 run 目录下的探针（见 §3 末尾「探针夹具维护」）。
- 覆盖：AC-1…AC-18 的可执行面；AC-19…AC-23 属历史需求，仅以回归门禁（rfv 44/44、negative 130/130、gate-order 20/20）方式确认未回退。

## 3. 用例与结果

### 3.1 门禁（全部通过，均不低于 AC-16 下限）

| # | 检查 | 命令 | 结果 |
|---|---|---|---|
| G1 | 自动化 6 段 / 39 条，连跑两次 | `bash scripts/automation-verify.sh` | **39/39 ×2**，RUN_TAG 残留 0（第二次 14:06:05） |
| G2 | 门禁自身可信度（A/B 双序 + 负向自检 0→1→0） | `bash scripts/gate-order-verify.sh` | **20/20** |
| G3 | 既有平台门禁 | `bash scripts/platform-verify.sh` | **194/194**（AC-1…AC-20） |
| G4 | 演示重置（5 段） | `bash scripts/platform-demo-reset.sh` | **80/80**，本轮连跑两次 + 收尾再跑一次均绿 |
| G5 | 权限矩阵 | `bash scripts/rbac-verify.sh` | **113/113**（11 段） |
| G6 | 页面/动作准入 | `bash scripts/access-verify.sh` | **206/206** |
| G7 | 负向用例 | `bash scripts/platform-test-negative.sh` | **130/130**，足迹 0 |
| G8 | 发版链 | `bash scripts/release-flow-verify.sh` | **44/44** |
| G9 | 静态门禁 L1–L21 | `bash scripts/tests/lint.sh` | **23/23**（L21 五段边界） |
| G10 | 单元测试 | `bash scripts/platform-unit.sh` / `mvn -o test` | **443 条通过** / BUILD SUCCESS |
| G11 | 前端 | `cd web && npm run typecheck && npm run build` | TYPECHECK_EXIT=0，BUILD_EXIT=0（7.80s） |

### 3.2 run 目录既有 checker（回归契约，逐条复跑）

| checker | 结果 |
|---|---|
| `t2-v15-ddl.sh` | 18 passed / 0 failed（V15 幂等、四表列/键/索引、`requirement_id` 可空、tag 长度 32） |
| `t2-flyway-boot.sh` | **14 passed / 0 failed**（夹具归一后，见 §3.4） |
| `t3-event-store-schema-probe.sh` | 9 passed / 0 failed（`INSERT IGNORE` 去重、claim/SKIP LOCKED、DONE/DROPPED/recoverStale） |
| `t4-rule-domain-probe.sh` | 43 通过 / 0 失败（含 vpm1 `INTERNAL_ONLY`、dev1 `FORBIDDEN` 页面码先于动作码） |
| `t6-views-contract.mjs` | 52 passed / 0 failed（空态/筛空态/厂商禁区态/404 文案） |
| `RegistryCountsProbe` | PROBE OK，27 asserts（37 页/73 动作/61 功能码/PMO ALLOW/VENDOR_PM DENY） |
| `qa-r39-scoped-idempotency.sh` | 5/5（同 event 两次→1 行 run、ADD_TAG 1 行、停用 3s 内 0 新 run） |
| `r39-assign-audit-check.sh` | ASSIGN_OWNER SUCCESS，audit 恰 1 条，owner 已变更 |
| `r39-run-attribution.sh` | R1/R2 各 1 条 SUCCESS，同 event 每规则 ≤1 |
| `r39-demo-shots.mjs` | **3/3**（见 §3.3） |

### 3.3 本轮新增覆盖（上轮未覆盖的面）

| 面 | 探针 | 结果 |
|---|---|---|
| **AC-13** 慢 webhook 不拖垮业务 | `qa-r39-webhook-stub.mjs`（2s 延迟对端）+ `qa-r39-webhook-slow.sh` | **12/12**：业务请求 **0.006s**（<1s）、run 终态 **2.61s**（≤5s）`SUCCESS`、`actions_json` 记 `WEBHOOK/SUCCESS/HTTP 200`、对端收到 `POST /hook` 且带 `X-Signature`、清理后规则残留 0 |
| **AC-14 §9** 三张真栈截图 | `qa-r39-demo-scenario.sh` + `r39-demo-shots.mjs` | 截图 **3/3**：R1 命中 `SUCCESS` 行、`DEMO-R-10` 上「大需求」chip + 12 人天、R1「已停用」且 R2 启用 |
| **AC-14** 重置后库态 | SQL | `automation_rule=2`（563/564 均 enabled）、`automation_run=0`、`automation_event=0`、`requirement_tag=0` |
| **AC-18** 文档/提交卫生 | git / grep | 提交信息含 runId 与任务夹；`pom.xml`/`web/package*.json`/V1–V14 **零 diff**；迁移仅新增 `V15__automation_rule.sql`；`.gitignore` 覆盖 `target/ logs/ .run/ .m2/ node_modules/ dist/`；`memory.md:46`、`ARCHITECTURE.md:260-272`、`DEMO-BY-ROLE.md:307-352` 均有 r39 同主题内容 |

**探针夹具维护（非产品改动，已记录）**
- `t2-flyway-boot.sh`：共享库已迁到 V15，原断言「克隆库中尚无 V15」必然红。补「克隆后 `DELETE ... flyway_schema_history WHERE version='15'` + `DROP TABLE` 四张新表」归一到 V14 基线，重跑 14/14（日志显式 `Current version 14 → Migrating to 15 → applied 1`）。
- `r39-demo-shots.mjs`：真栈 UI 把 `SUCCESS` 渲染成中文「成功」（`RunsView.vue:176`），断言原先写死英文导致假红；改为 `['执行日志','成功']` 后 3/3。
- 截图路径为 `docs/teamflow/demo-shots/r39-automation-*.png`（本轮新生成，`git status` 显示 3 个未跟踪 png；`.gitignore` 未忽略该目录，属可提交产物）。

### 3.4 新增缺陷的复现（详情标签）

```bash
bash logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/qa-r39-detail-tags.sh
# 实跑：REQ=631577 DB_TAG=大需求 API_VIEW_TAGS=[]
# → FAIL 详情 view.tags 缺「大需求」：列表有 chip、详情没有（AC-14 要求同源）
```

`bash logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/qa-r39-demo-scenario.sh` → **4 PASS / 3 FAIL**：`[04]` 把 P0 BUS 的人天改成 12 被 400 `PARENT_DAYS_READONLY`；`[05]` 故 R2 不触发；`[06]` 详情无标签（同 P2）。截图改用叶子 RD（BUS→PDR→RD 才允许，PDR 还要求父需求 `POOLED`）后跑通——见 P3-1。

## 4. 修复再验证对比表（round-1 → round-2）

| D 编号 | round-1 级 | 复验结论 | 证据 |
|---|---|---|---|
| D-1 automation-verify 34/39 | P1 | 已修复 | 39/39 ×2，RUN_TAG 残留 0 |
| D-2 gate-order 19/20 | P1 | 已修复 | 20/20，负向自检 0→1→0 |
| D-3 platform-verify 192/194 | P1 | 已修复 | 194/194 |
| D-4 rbac 112/113 | P1 | 已修复 | 113/113 |
| D-5 限流键 `(tenant,rule)` 偏离 AC-11 | P1 | 已修复（语义） | `AutomationRateLimiter.java:65 tryAcquire(long tenantId)`，`AutomationDispatcher.java:162` 传 `event.tenantId()`；automation-verify「120/分钟」段通过。原 Check command 的字面判据（`grep` 无输出）因签名改动已失效，按语义判据复验 |
| D-6 r39 未提交 | P2 | 已修复 | `d4ca363`，正文含 runId + 任务夹 |
| D-7 运行日志缺 SCHEMA_INVALID 文案 | P2 | 已修复 | `grep SCHEMA_INVALID web/src/components/AutomationRunVerdict.vue` → 1 |
| D-8 demo-reset 文案仍是 76/76 | P3 | 已修复 | `grep "76/76" scripts/platform-demo-reset.sh` 无输出（rc=1） |
| D-9 useRoleGrants 仍写 168 | P3 | 已修复 | `grep 168 web/src/views/admin/role-permissions/useRoleGrants.ts` 无输出（rc=1） |

## 5. 缺陷表（仅列仍 OPEN 项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| Q-1 | P2 | 需求详情 / 标签 | 1) `make platform-demo-reset`；2) 对 ORD 域任一叶子 RD `PUT /api/req/requirements/{id}` body `{"estimateDays":12}`；3) 等 `requirement_tag` 出现「大需求」；4) `GET /api/req/requirements/{id}` 读 `.view.tags` | 详情概览返回 `[{"tag":"大需求","source":"RULE"}]`（AC-14 §9.2：列表与详情同源） | `.view.tags = []`。根因：`RequirementViews.java:56-70` 兼容构造器把 tags 固定 `List.of()`；详情 `RequirementService.java:352` 走 `toView(entity, rollup)`，从未调用 `RequirementTagQuery.byRequirementIds`（只有列表 `RequirementPoolQuery.java:77/128` 查） | AC-14 / AC-6 / AC-17 | `bash logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/qa-r39-detail-tags.sh` | 输出含 `PASS 详情 view.tags 含「大需求」`，退出码 0（当前 `FAIL ... API_VIEW_TAGS=[]`，退出码 1） |
| Q-2 | P3 | 演示文档 DEMO-BY-ROLE §9.2 | 按 §9.2 第一步新建 P0（BUS）需求，第二步把「该需求」`estimate_days` 改成 12 | 200，R2 打「大需求」标签 | 400 `PARENT_DAYS_READONLY`（"父需求人天由子需求汇总，不可手工填写"）。按文档字面无法走完第二步；改为在叶子 RD 上改人天才复现（AC-14 截图即用 RD 替身） | AC-14 / AC-18 | `bash logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/qa-r39-demo-scenario.sh` | 脚本 `DEMO_SCENARIO_TOTAL=7 PASS=7 FAIL=0`（当前 4/7：`[04]`/`[05]` 因文档口径失败，`[06]` 随 Q-1） |
| Q-3 | P3 | 动作执行可解释性 | 配一条 WEBHOOK 规则、集成配置缺 secret（或路径被 SSRF 策略拦），命中后看执行日志 | `actions_json` 的 message 能区分失败原因（或规则保存时就拒掉不可用的配置） | `AutomationActionExecutor.java:87/107` 把异常压成 `failed(type,"FAILED",null)`，日志只有 `"message":"FAILED"`；真实原因仅在应用日志 `WARN automation side effect failed: ... error=secret is required` | AC-12 / AC-8 | `bash logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/qa-r39-webhook-slow.sh`（成功路径）；失败路径观测见 `captures.json` 首跑记录 | 失败 run 的 `actions_json` 含可辨识原因（如 `SECRET_MISSING`/`SSRF_BLOCKED`），不再是裸 `FAILED` |
| Q-4 | P3 | 规则列表（工艺） | 打开 `/automation/rules`，看「状态」列 | 启停开关与「启用/已停用」文案完整可读 | 启用态文案被列宽截断显示为「启用 ..」（截图 `r39-automation-r1-disabled.png` 中 R2 行）；停用态「已停用」完整 | AC-17 / UI-STYLE | - | 1440×900 下「状态」列文案不被截断（可加 `title` 或放宽列宽） |
| Q-5 | P3 | AC-14 通知计数口径 | `make platform-demo-reset` 后数 `notification` 表 | 「通知仍恰 4 条」 | 重置自检读的是 **Tx1 结束时**的计数（4 条种子；`DemoResetService.java:231`），随后 Tx2/Tx3 演示审批链再写 22 条 `APPROVAL/APPROVAL_RESULT`，收尾实测 **26 行**（4 种子 + 22 审批）。r39 未新增通知（R1 在重置期间不触发），属既有口径，非本轮回归 | AC-14 | `source scripts/lib.sh && db_q "SELECT type,COUNT(*) FROM notification GROUP BY type"` | 文档澄清「4 = 种子通知」，或断言改为「种子通知 4 + 审批链 22」并可复现 |

## 6. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 说明 |
|---|---|---|
| 截图目视复核 | 直接看 `docs/teamflow/demo-shots/r39-automation-{r1-success,r2-tag,r1-disabled}.png` | 本轮已用 CDP 真栈生成并逐张目视：SUCCESS 行、「大需求」chip + 12 人天、「已停用」均可见；悬停/焦点/动画手感需人眼在浏览器里过一遍 |
| 多实例限流上限 | 双实例部署后并发打同一租户 | `AutomationRateLimiter` 为单实例内存分钟窗口（memory.md:46 已登记技术债），本环境只有单实例 |
| 通知中心真实到达 | 以 `tl1` 登录看通知列表 | 通知落库已由 `automation-verify`（R1 NOTIFY）覆盖；未做浏览器内红点交互 |

## 7. 建议与结论

1. **必须修 Q-1**：把 `RequirementTagQuery.byRequirementIds` 接进详情视图（`RequirementService.toView`/`Detail` 构造处），删除或限制 `RequirementViews.View` 的兼容构造器，避免新调用点再次静默丢标签；修完用 §5 的 Q-1 命令复验。
2. **建议修 Q-2**：`DEMO-BY-ROLE.md` §9.2 第二步改为「在 ORD 域的叶子 RD（如 DEMO-R-10）上把人天改成 12」，否则演示人按字面走会撞 400。
3. Q-3/Q-4/Q-5 为可解释性/工艺/口径项，不阻塞功能验收，建议随 Q-1 的修复批次一并处理。
4. 引擎主体（事件源、幂等、防循环、作用域、白名单、四视图、演示种子与门禁）实测全部达标；**当前唯一阻塞点是把「列表可见的标签」补齐到详情**。

## 8. 验证脚本清单（verifyScripts）

`scripts/automation-verify.sh`、`scripts/gate-order-verify.sh`、`scripts/platform-verify.sh`、`scripts/platform-demo-reset.sh`、`scripts/rbac-verify.sh`、`scripts/access-verify.sh`、`scripts/platform-test-negative.sh`、`scripts/release-flow-verify.sh`、`scripts/tests/lint.sh`、`scripts/platform-unit.sh`、`scripts/platform-up.sh`、`scripts/platform-down.sh`、`web`（typecheck/build）、`logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/{qa-r39-webhook-stub.mjs,qa-r39-webhook-slow.sh,qa-r39-demo-scenario.sh,r39-demo-shots.mjs,qa-r39-detail-tags.sh,t2-v15-ddl.sh,t2-flyway-boot.sh,t3-event-store-schema-probe.sh,t4-rule-domain-probe.sh,t6-views-contract.mjs,qa-r39-scoped-idempotency.sh,r39-assign-audit-check.sh,r39-run-attribution.sh,RegistryCountsProbe.java}`
