# r39 自动化规则引擎 · QA 报告（再验证轮）

- 需求：自动化规则引擎（规则 = 触发事件 + 条件 + 动作，可启停、有执行日志、防循环，动作不越权）
- 任务夹：`docs/teamflow/20261123-r39-automation-rule-engine/`
- 分支：`feat/automation-rules-engine`；被测提交 `0980543`（修复轮 Q-1..Q-5）；上一版 `d4ca363`
- 本轮性质：**修复再验证**——先复跑上一轮 run 目录里全部 checker 与每条缺陷的 Check command，再补未覆盖面（新增 2 个真栈探针 + 1 个接口一致性静态扫描）
- 环境：macOS · JDK 25 · Spring Boot 4.0.9 · 网关 8080 / req-service 8082 / deliver 8083 / web 5173 均 200；运行 jar 为 14:41 构建（含 0980543）；MySQL 真栈 `devops_platform`
- 结论：**可验收**。上一轮唯一阻塞项 Q-1 已修复并由三条独立路径复核；既有门禁未缩水；未发现新的 P0/P1/P2

## 1. 复跑结果（回归契约，逐项 verbatim）

| # | 检查 | 命令 | 结果 |
|---|---|---|---|
| 1 | 自动化主门禁（第 1 跑） | `bash scripts/automation-verify.sh` | `AUTOMATION_TOTAL=40 CASE=40 PASS=40 FAIL=0` |
| 2 | 自动化主门禁（第 2 跑，AC-15 两次全绿） | `bash scripts/automation-verify.sh` | `AUTOMATION_TOTAL=40 CASE=40 PASS=40 FAIL=0`；`[40] 自清理后残留 0 行` |
| 3 | 静态门禁 | `bash scripts/tests/lint.sh` | `ok lint 全部通过（24 项）`（L21 automation 边界、L22 标签同源） |
| 4 | 前端契约 | `node logs/teamflow/tf-mwspkg4u-nvy5y5/scripts/t6-views-contract.mjs` | `T6 static contract: 52 passed, 0 failed` |
| 5 | 注册表格数 | `java -cp platform-contract/target/classes .../RegistryCountsProbe.java` | `PROBE OK`（37 页 / 73 动作 / 61 功能码 / 671 / 171 / 1881 / 363） |
| 6 | V15 DDL 幂等 | `bash .../t2-v15-ddl.sh` | `18 passed, 0 failed` |
| 7 | Flyway 空库/已有库启动 + 十键 | `bash .../t2-flyway-boot.sh` | `14 passed, 0 failed`（`exactly ten automation keys`） |
| 8 | outbox 存储真 MySQL | `bash .../t3-event-store-schema-probe.sh` | `9 passed, 0 failed`（INSERT IGNORE / SKIP LOCKED / stale 回收） |
| 9 | 规则域 + 权限 + 负例 S2 | `bash .../t4-rule-domain-probe.sh` | `43 通过 / 0 失败` |
| 10 | 作用域内幂等（AC-9） | `bash .../qa-r39-scoped-idempotency.sh` | `QA_SCOPED TOTAL=5 PASS=5 FAIL=0` |
| 11 | 慢 webhook 不拖业务（AC-13） | `bash .../qa-r39-webhook-slow.sh` | `AC13_TOTAL=12 PASS=12 FAIL=0` |
| 12 | 演示三步脚本（AC-14 §9） | `bash .../qa-r39-demo-scenario.sh` | `DEMO_SCENARIO_TOTAL=7 PASS=7 FAIL=0` |
| 13 | 演示三截图（真 Chrome CDP，含状态列不截断） | `node .../r39-demo-shots.mjs` | `3/3 张截图通过断言` |
| 14 | 需求详情标签（Q-1 Check command） | `bash .../qa-r39-detail-tags.sh` | `PASS 详情 view.tags 含「大需求」` |
| 15 | 指派动作与审计 | `bash .../r39-assign-audit-check.sh` | `ASSIGN_OWNER SUCCESS target=5`；`audit_log` 有 `OWNER_ASSIGN` |
| 16 | run 归因（规则/事件/需求） | `bash .../r39-run-attribution.sh` | `SUCCESS`，`REQ:632531:CREATED:...`，同 event 每规则 1 行 |
| 17 | 门禁顺序无关 + 基线 | `bash scripts/gate-order-verify.sh` | `20/20`（内含 reset 80/80、access 206/206 ×4、rfv 44/44 ×2） |
| 18 | 平台总门禁 | `bash scripts/platform-verify.sh` | `194/194 项通过（AC-1..AC-20）` |
| 19 | 负向总门禁 | `bash scripts/platform-test-negative.sh` | `130/130 条负向用例全部按预期被拒且不落库` |
| 20 | 演示重置（AC-14 连续两次） | `bash scripts/platform-demo-reset.sh` ×2 | `80/80`、`80/80`（5/5 段），两次均 exit 0 |
| 21 | RBAC 全量（AC-3） | `bash scripts/rbac-verify.sh` | `113/113 全部通过（11 段）` |
| 22 | 单元测试（AC-16 只增） | `mvn -o test` | exit 0；surefire 合计 `TESTS=447`，无失败文件 |
| 23 | 前端构建（AC-17） | `npm run typecheck && npm run build` | 两者 exit 0 |
| 24 | 接口一致性（0a，静态） | `bash .../qa-r39-interface-consistency.sh` | `INTERFACE_OK=36 INTERFACE_MISS=0` |
| 25 | 版本详情标签（新增面） | `bash .../qa-r39-release-tags.sh` | `PASS`（`API_TAGS=[QAR39VR…]` 与 DB 标签一致） |
| 26 | webhook 失败归因（新增面，Q-3） | `bash .../qa-r39-webhook-failure.sh` | `PASS`：`STATUS=FAILED`，`actions_json=[{"type":"WEBHOOK","status":"FAILED","message":"SECRET_MISSING"}]`，无完整 URL |

基线复核（reset 后）：`automation_rule`=2（两条均 `enabled=1`）、`automation_run`=0、`automation_event`=0、`requirement_tag`=0、`requirement`=12、`notification`=26（= 4 条种子 + 22 条演示审批链，口径见 memory.md:46）；`git status --porcelain` 为空。

## 2. 上一轮缺陷再验证（对比表；本表不算在办缺陷）

| 编号 | round-1 级 | 功能模块 | 结论 | 复核证据（本轮实跑） |
|---|---|---|---|---|
| Q-1 | P2 | 需求视图标签 | **已修复** | `qa-r39-detail-tags.sh` PASS（API 详情返回「大需求」）；`qa-r39-release-tags.sh` PASS（版本详情同源）；automation-verify `[40]` 内 S3 断言同源；lint L22 静态锁（24/24） |
| Q-2 | P3 | 演示文档 | **已修复** | `DEMO-BY-ROLE.md` §9.2 第 2 步为 ORD 叶子 RD（`qa-r39-demo-scenario.sh` 7/7 走通） |
| Q-3 | P3 | 执行日志可解释性 | **已修复** | `qa-r39-webhook-failure.sh` PASS：缺 secret 不再只写裸 FAILED，归因 `SECRET_MISSING` |
| Q-4 | P3 | 规则列表状态列 | **已修复** | `r39-demo-shots.mjs` 用 `Range` 实测「启用/已停用」单元格 `内容宽 <= clientWidth`，3/3 |
| Q-5 | P3 | 产品记忆口径 | **已修复** | `memory.md:46` 写「种子通知恰 4 条；全表 26 = 4+22」并登记限流技术债 |

计数复核：上一轮 5 条（1×P2 + 4×P3）→ 本轮 0 条在办；未发现「已修但新面复现」的同类缺陷。新增覆盖的两面（版本详情标签、失败归因）均一次通过，说明修复不是单点补丁。

## 3. 架构与接口核对（0/0a，轻量）

- 事件单口：`AuditService` 在写审计后同事务回调 `AuditListener`；`AutomationEventOutbox` 是唯一实现，异常自吞（编译 + 真栈事件流 40/40 佐证）。
- 隔离单源：`ScopeSql` 登记四表（`automation_rule/event/run/requirement_tag`），`grep` 无第二处表→列映射。
- 幂等顺序：`AutomationRunWriter.tryStart/finish` 是唯一写 `automation_run` 的类；`AutomationEventStore` 唯一改 event 状态（`FOR UPDATE SKIP LOCKED` 在独立短事务）。
- 动作白名单：lint L21 静态锁「automation 包不出现 `.transition(` / 审批引擎 / 发布单入口、不拼隔离列字面量」，24/24 绿。
- 标签单读路径：`FROM requirement_tag` 的 SELECT 只出现在 `RequirementTagQuery`（接口扫描断言）。
- 蓝图偏离跟踪：既有偏离（`automation.rules` 归入「设置」模块、s2 MENU 比对 363、nav 基线 38、`automation.action.manage` sort=0）均为 dev 阶段已说明并留有证据的调整，不影响契约；未见重复实现或该抽未抽的第三份逻辑。

## 4. 人工补测清单（环境限制/人眼项，非交付缺陷）

| 项 | 验收标准 | 方法 |
|---|---|---|
| 三张演示截图的视觉走查 | 版式、间距、chip 颜色符合 UI-STYLE；开关无透明度衰减 | 人工查看 `docs/teamflow/demo-shots/r39-automation-r{1-success,2-tag,1-disabled}.png` |
| 真实第三方 webhook（HTTPS/TLS、证书、公网延迟） | 签名被对端验证通过、超时可观测 | 在可出网环境用真实接收端替换 `qa-r39-webhook-stub.mjs` |
| 多实例部署下的租户限流 | 单租户 120/分钟为全局口径 | 需 ≥2 实例 + 共享计数器（当前为单实例内存窗口，memory.md 已登记技术债，不作为本轮缺陷） |
| 浏览器内键盘/读屏可用性 | Tab 顺序、焦点可见、开关可用键盘操作 | 人工 + axe DevTools |

## 5. 缺陷表（只列仍 OPEN 项）

未发现缺陷。

## 6. 结论

- 交付**可验收**：AC-1/2/3/5/6/7/8/9/10/12/13/14/15/16/17/18 均有本轮实跑证据（探针 26 项，见 §1）。
- AC-4（事件来源唯一且持久）：本轮以「outbox 真 MySQL 9/9」「automation-verify 事件单口断言」佐证，**未做进程重启复测**（上一轮已测，本轮无相关改动）。
- AC-11（限流与止血）：由 447 条单测中的 `AutomationRateLimiterTest`（限额内/恰好到限/超限/跨分钟与租户隔离）与 `qa-r39-scoped-idempotency.sh` 的「停用后 3s 内 0 新 run」支撑；120/分钟的真栈压测属人工项（§4 多实例口径）。
- AC-19..23 属历史需求，由 rfv 44/44、negative 130/130、gate-order 20/20 回归确认未回退。
- 无 P0/P1/P2；无阻塞项；工作树干净、演示基线已复位（2 规则启用、0 残留 run/event/tag）。
- 遗留为产品级观察（见 §4），不构成本轮返工。
