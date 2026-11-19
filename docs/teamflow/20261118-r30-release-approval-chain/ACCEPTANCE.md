# r30 验收报告 · 版本发布分级审批链

- 任务夹：`docs/teamflow/20261118-r30-release-approval-chain/`　runId：`tf-mwm516ql-s4dqpy`
- 分支：`feat/release-multilevel-approval`，HEAD `79e1f1c`；交付面 32 改 + 23 新增（未提交）
- 环境：macOS / JDK 25；网关 8080 · req 8082 · deliver 8083 · web 5173；MySQL `devops_platform`，Flyway V14
- 验收方式：不采信 QA 结论本身，重跑新门禁与关键探针 + 逐项对源码核契约；浏览器类项按 QA 报告与人工清单复核

## 1. 本轮独立复验（原样命令）

| 命令 | 本轮结果 |
|---|---|
| `bash scripts/release-flow-verify.sh` | EXIT=0，`TOTAL=32 CASE=32 PASS=32 FAIL=0`；S1 6 / S2 7 / S3 6 / S4 7 / S5 4 / S6 2，收尾自动 `platform-demo-reset` |
| `bash scripts/release-flow-upgrade.sh` | EXIT=0，12/12：两次 seed 定义数/槽数/每定义 version 均不增，槽仍 6 条且仍指 PUBLISHED |
| `bash logs/.../scripts/r30-qa-r2-extra-probes.sh` | 11/12；[05]「DRAFT 发布单状态门槛」因 demo-reset 后库内无 DRAFT 单而**不可执行**（探针夹具依赖，非产品缺陷）；该分支由 `ReleaseLevelValidator.java:87-105` 只放行 APPROVED/SUCCESS 静态确认，QA 上轮已在有 DRAFT 单时实测 400 |
| 静态核查 | 迁移仅新增 `V14`；`pom.xml`/`web/package.json` diff 空；`new FlowChainViews.FlowChain(` 主代码只在 `FlowChainService.java:106/108`；`ApprovalLevelProjector` 唯一；无 publish 路由；`ApprovalEngine` diff 仅 `NODE_APPROVED` 增 nodeName；`.gitignore` 覆盖 `target/ .run/ logs/ dist/` 且显式不忽略 `*.sql` 与 `evidence/*.png` |
| 演示文档 | `docs/teamflow/DEMO-RELEASE-FLOW.md` 8 步 + 4 条被拦链 + 8 个截图位 + 复位说明齐备；**无 mermaid/表格形式的链路图**（见 C2） |

## 2. 逐条验收（PRD 23 条）

| AC | 判据要点 | 证据 | 结论 |
|---|---|---|---|
| AC-1 | 默认三级/两级、仅当前级建待办 | 门禁 S1 6/6；`FlowChainSeedService` 6 定义 + 6 绑定，升级 12/12 链形状不变 | ✅ |
| AC-2 | 逐级留痕、末级才 PUBLISHED/APPROVED | S2 7/7；`ApprovalEngine.java:180-186` 三键含 nodeName；QA D-5 实测 release=2025 末级 PUBLISHED、n2 PENDING=0 | ✅ |
| AC-3 | 驳回回填、重提新实例从 n1、不可跳级 | S3 6/6；`r30-qa-ac3` 实测驳回五字段 + 回 PLAN；构造器单点 | ✅ |
| AC-4 | 存量兼容与幂等升级、在途钉版本 | 升级 12/12（两次 seed 零增量）；S1 换绑不影响在途实例 | ✅ |
| AC-5 | 等级字段/编辑窗口/锁定 409/非法 400/审计 | 本轮 extra [07]-[09]：501 字 400、PLAN 内 200、`POST /lock` 后 409 `RELEASE_LOCKED` | ✅ |
| AC-6 | 6 槽可配可恢复、下次生效、非管理员 403 | QA D-2：pm 三方法 403 JSON、admin 200 六槽全默认；S1 6/6 换绑/恢复/下次生效 | ✅ |
| AC-7 | 紧急原因 1–500、回滚单号同租户且 APPROVED/SUCCESS，非法 400 | extra [01][03][04][06] 实测 400 + `detail.field` + 三次 400 无半写；DRAFT 分支静态确认（探针 [05] 夹具缺失） | ✅ |
| AC-8 | 四段同源聚合、缺段 MISSING、req 不重算门 | extra [10]-[12] 实测空版本 200、三段 MISSING、准入段有状态；`FlowChainService` 唯一聚合 | ✅ |
| AC-9 | 前端只消费不重算、1280×800 四段可读 | `r30-flow-frontend-check` 37/37（零请求、无第二套判定、4×minmax(0,1fr)）；**1280×800 截图证据缺失** | ⚠️ |
| AC-10 | 快照不可变、hash、轮次回放、stale 后端给 | S4 7/7；`SnapshotE2E` 16/16（hash 64 hex、replay 一致、越界 404、仓储无 update/delete） | ✅ |
| AC-11 | 拒绝不写快照、轮次连续、只读 | S4/S5；`FlowSnapshotRepository` 仅 save/find；拒绝路径在 `captureSnapshot` 之前抛错 | ✅ |
| AC-12 | 计划未批 409 `PLAN_NOT_APPROVED` 且不落库 | S5 4/4；`DeployOrderService.java:329` 在准入与快照之前 | ✅ |
| AC-13 | 未三签/无豁免 409 `QUALITY_GATE_FAILED` 带 failedRules | S5；`DeployOrderService.java:339-341` gate 判定先于准入汇总 | ✅ |
| AC-14 | 驳回回 PLAN 不可发布、无 publish 路由、重入 409 | S3；控制器 publish 命中 0；`requireReleaseLocked` 拦旧准入 | ✅ |
| AC-15 | 紧急/回滚缺字段 400 且整体回滚 | extra [01][03][06]；校验在写库前，等级仍 NORMAL | ✅ |
| AC-16 | 越级/越权 403、任务仍 PENDING、无 APPROVE 留痕 | S3 6/6（403 FORBIDDEN、history 无 APPROVE） | ✅ |
| AC-17 | 演示文档含链路图 + 8 步脚本 + 双路径 + 复位 | 8 步、角色/入口/预期/截图位、4 条被拦链、复位齐备；**缺「链路图（mermaid 或表格）」** | ⚠️ |
| AC-18 | 真实端到端 + `evidence/` ≥6 PNG | 门禁 32 条在真栈跑完整链并 PASS==TOTAL（命令/账号/时间可查）；**`evidence/` 目录不存在（0 张）** | ⚠️ |
| AC-19 | 被拦链可演示：409/400/403 | DEMO 被拦链 A–D + S5 真栈 409/403/400 实测 | ✅ |
| AC-20 | ≥30 断言 6 段、PASS==TOTAL、可重复、前置不静默跳过 | 32/32，段计数 6/7/6/7/4/2；缺表/缺路由直接 die；本轮独立重跑一致 | ✅ |
| AC-21 | 既有门禁不缩水、无跳级后门 | QA 复验 194/130/206/113/375/22/348/231/70，typecheck+build 绿；`FORCE_RELEASE_RETIRED` 恒 409；无代批/跳级路由与动作码 | ✅ |
| AC-22 | 仅新增 V14、改动面受控、pom/pkg 空、gitignore 复核 | 静态核查全过；`git status` 仅 r30 文件，无 target/dist/logs 噪声 | ✅ |
| AC-23 | feat 分支、收尾提交含 runId 与任务夹 | 分支正确；**收尾提交尚未产生**（待验收收口一次性入库，勿 stash/丢弃） | ⚠️ |

## 3. 架构一致性（M3）

- **蓝图逐件落地**：`V14` / `ReleaseLevel(+Validator)` / `FlowBindingEntity+Repository+Service+Controller` / `FlowChainViews` / `DeliverFlowEvidenceClient` / `FlowChainService` / `FlowSnapshotEntity+Repository+Service` / `ApprovalLevelProjector` / `FlowChainSeedService` / 前端 3 组件 + 2 详情页 + 绑定页 + 路由 / 2 门禁脚本 + Makefile / DEMO 文档，与蓝图清单一致，无「应抽未抽」。
- **判定单点成立**：deliver 只返回事实（`FlowEvidenceService` 无 deployAdmission）；四段聚合与段 digest 仅 `FlowChainService`；`approvalLevels` 仅 `ApprovalLevelProjector`；门结果只透传 deliver GateSnapshot，req 无 QG 判定；准入顺序唯一来自 `AdmissionPolicy`；引擎执行语义未动。
- **一处无害形变（非偏离）**：提交顺序为「计划→LOCKED→准入→门→起审批→置 PENDING→快照」，蓝图写作「快照→审批」。快照所需的 definitionId/节点序列必须由起审批解析绑定后才有，且两者同事务；「门通过前绝不写快照」的安全顺序未变，AC-10/11 门禁 32/32。
- **重复实现检查**：无第二套聚合/等级推导/门规则；`MessageDigest` 余量均属既有附件/Sonar/准入项 hash，非段 digest。

## 4. 条件与遗留

| 编号 | 级别 | 条件 | 验收方式 |
|---|---|---|---|
| C1 | P0（AC-18/AC-9） | 按 DEMO 8 步补 `evidence/` ≥6 张可判读 PNG（链路面板/三签/门 PASS/准入 9 项/逐级审批/PUBLISHED），并在 1280×800 下确认 `scrollWidth<=clientWidth`、四段同屏 | 截图抽查关键字段 + DevTools 尺寸；无需重跑功能门禁 |
| C2 | P0（AC-17） | DEMO 文档补一张链路图（mermaid 或表格：四段 → 六级节点 → 两单据） | 结构检查 |
| C3 | P1（AC-23） | 收尾提交：`feat/release-multilevel-approval` 上一次提交，信息含 `runId=tf-mwm516ql-s4dqpy` 与任务夹；任务夹随提交入库 | `git log -1` + `git status` 干净 |
| O-1 | P3 | 绑定页按钮无局部 hover（与既有页面一致） | 可选 |
| O-2 | P3 | 审批阶梯空态无「发起审批」入口 | 可选 |
| O-3 | P3 | 令牌表：3 枚已收口，`--w-node-key/--h-seg-grid` 依「不落无用令牌」未声明，建议同步 DESIGN §5 | 可选 |
| — | — | `r30-qa-r2-extra-probes.sh` [05] 依赖库内既有 DRAFT 单，复位后失效；建议探针自建夹具（检查器质量，非交付缺陷） | 可选 |

**环境复原**：门禁收尾已执行 `platform-demo-reset`（70/70），演示数据复位，无残留。

## 5. 结论

功能面全部达成：新门禁 32/32、幂等升级 12/12、关键 AC-5/7/8 实测通过、既有 13 项门禁只增不减，未发现 P0/P1/P2 交付缺陷，架构与蓝图一致（唯一形变为有理由的提交顺序调整）。
AC-17 链路图、AC-18 证据截图（含 AC-9 1280×800）与 AC-23 收尾提交尚未完成，故不签 ✅；完成 C1–C3 后可直接复验签字，无需重跑功能门禁。

验收结论：⚠️ 有条件通过
