> **决策记录（暂缓，非终局）** · 2026-11-23 · 提出人：产品负责人（"自定义审批流是不是可以结合 Flowable 的开源插件实现？"）· 结论：**技术上可行（Flowable 8.0.0 对齐 Boot 4），但不建议现在整体替换；留 spike 路径待触发。**
>
> 本文只做技术判断存档，不改变任何既有实现与验收口径。基线：`main = 448ab4f`（r38 合回后）。

# 审批引擎选型：自研轻量引擎 vs Flowable 8

## 1. 一句话结论

现栈（JDK 25 + Spring Boot 4.0.9）**能**跑 Flowable——注意是 **8.0.0**，不是 7.x；但整体替换的代价大于收益：现有自研引擎 4570 行已覆盖我们全部单据语义并有 34 条门禁断言钉着，替换需要重做「加签/减签」这个最难的部分、把隔离变成两层、并重跑全部验收证据。**建议保持自研，把 BPMN 只在"业务人员要自己画流程图"成为真实需求时再评估。**

## 2. 现状：我们的引擎做了什么

| 维度 | 现状 |
|---|---|
| 规模 | `req-service/src/main/java/com/example/req/approval/` 共 **39 个文件 / 4570 行** |
| 数据表 | `wf_definition` / `wf_instance` / `wf_task` / `wf_history`（V1 建表，V14 扩三级链） |
| 单据类型 | 5 类：`REQ_SUBMIT`、`REQ_REVIEW`、`RELEASE`、`REQ_DEPLOY`、`WBS_ESTIMATE` |
| 节点能力 | 审批（会签/或签）、条件分支（类型/域/优先级/人天）、抄送 |
| 审批人来源 | 指定人 / 角色 / 发起人直属主管 / 域负责人 / 厂商负责人 |
| 操作 | 通过、驳回（必填原因）、**加签（前加签/后加签）**、**减签（必填原因 + 通知）**、转办、撤回、催办 |
| 生命周期 | 定义版本化（发布后不可改，在途实例按启动版本走）；5 类单据可各设默认流程、可启停 |
| 联动 | `ApprovalCallback` 回调单据状态机；审批结果进 `audit_log`；通知站内 + 邮件 + webhook（M7 适配层） |
| 门禁证据 | `release-flow-verify` **44** 条（含三级链、分级链、不可变快照）、`platform-verify` **194**、`negative` **130**、`rbac` **113**、`gate-order` **20** |

## 3. Flowable 侧事实（本机实测，2026-11-23）

### 3.1 版本与 Boot 对齐关系（决定能不能上）

| Flowable | 传递依赖的 Spring Boot | 能否配我们的 Boot 4.0.9 |
|---|---|---|
| **7.1.0** | `spring-boot-autoconfigure:3.3.4` | ❌ 不是一条线 |
| **8.0.0** | `spring-boot-autoconfigure:4.0.2` | ✅ 同线（我们 4.0.9 更高） |

Maven Central 上 `flowable-spring-boot-starter` 现有版本：`7.0.0 / 7.0.1 / 7.1.0 / 7.2.0 / 8.0.0`（metadata `lastUpdated=20260227`）。

**这条最容易被踩**：网上大量"Spring Boot 3 + Flowable 7"的资料，直接抄到我们栈上会在自动配置阶段炸；必须锁 8.0.0。

### 3.2 最小可跑验证（已通过）

- 组合：JDK 25 + Spring Boot **4.0.9** + `flowable-spring-boot-starter-process:8.0.0` + H2
- 结果：`Started App in 1.627 seconds`；`common / engine / history / identity / eventregistry` 五套表自动创建；异步 Job Executor 正常启动；Tomcat 监听正常
- 依赖体积：starter 展开 **114 个节点**，新增 MyBatis 3.5.19、Jackson 2.20 等

结论：**运行时兼容性不是障碍**。

## 4. 置换成本（为什么建议不换）

1. **回调要重写**：现在 5 类单据由 `ApprovalCallback` 回调状态机；换成 BPMN 后改为监听器/服务任务，需重做 5 个回调，并保证"引擎派发失败时单据状态不被半改"（我们现在的做法是回调失败整段回滚）。
2. **加签/减签是最大的坑**：Flowable 的多实例会签原生支持，但**动态减少会签实例**支持很差（通常要 custom behavior 或直接 `deleteTask` + 自维护计数）。而"加签/减签"正是我们 PRD 的 AC-11，等于把最难的那条重做一遍。
3. **隔离会变两层**：Flowable 的 `tenantId` 是软过滤（查询处处要带），我们的隔离是**行级 `ScopeSql` + 域×厂商**。混用后最容易出的 bug 是某条路径漏带 `tenantId`，表现为"数据变多了"而没人报警。
4. **表与运维**：ACT_* 三十来张表 + 定时作业表；应与业务库分 schema/库，否则和 V1–V14 的 Flyway 混在一张库上，备份/归档/清理策略全变。
5. **证据要重做**：168/194/206/113/44 这些数字背后一大半 AC 是围绕我们引擎语义写的，换引擎要重新验证一遍，且要重写 `release-flow-verify` 的多级链条目。
6. **在途实例迁移**：库里已有历史实例，切换时要一次性迁移或双跑——双跑期间"同一单据两个引擎"的一致性风险高于收益。

## 5. 什么时候值得换（触发条件）

出现以下任一情况，再启动评估：

- 业务要**业务人员自己画流程图**（BPMN 2.0 图形化建模），而不是由我们预置流程模板
- 需要**并行网关 / 子流程 / 事件补偿 / 补偿事务**这类我们引擎不具备的结构
- 需要**流程仿真、回放**或满足要求 BPMN 标准的合规审计
- 流程定义要**跨系统复用**（例如与集团其他系统共用一套流程定义仓库）

这些目前都在 PRD 的"二期/不做"清单里。

## 6. 三条路径对比

| 路径 | 做法 | 代价 | 适用前提 |
|---|---|---|---|
| **A. 保持自研（当前选择）** | 继续扩 `wf_*` 体系 | 每加一种节点类型要改代码 + 加断言 | 流程结构由我们预置、变化有限 |
| **B. 双引擎（推荐备选）** | `wf_definition` 增加 `engine=BPMN/INTERNAL` 与 `bpmn_xml`，仅特定单据类型走 Flowable，其余仍走自研 | 中等：多一套运行时与运维，但可灰度、可回退 | 只有个别流程需要复杂结构 |
| **C. 整体替换** | 全量迁到 Flowable 8 | 大：重做回调/加减签/隔离/证据/迁移 | 触发条件全部成立、且愿意停功能开发 2–3 周 |

## 7. 若未来启动：spike 方案（时间盒 2–3 天）

只验证、不动主干：

1. 用 Flowable 8.0.0 + Boot 4.0.9 起一个独立 spike 服务（已完成最小可跑验证）
2. 只把「发布版本三级审批」一条链做成 BPMN，部署、启动、逐级审批、会签、驳回
3. 量三件事：**代码量对比**（BPMN 定义 vs 现有 Java 定义）、**端到端延迟**（含引擎表写入）、**加签/减签在 BPMN 下的自定义代码量**
4. 产出成本表 → 再决定 A 维持 / B 灰度 / C 替换

## 8. 复现命令（本文结论的取证方式）

```bash
# 1) 版本对齐关系：看 Flowable 传递依赖的 Boot 版本
mkdir -p /tmp/flowable-probe && cd /tmp/flowable-probe
# pom.xml 中只放 org.flowable:flowable-spring-boot-starter:8.0.0
mvn -B dependency:tree | grep -E "spring-boot-autoconfigure|flowable-engine"

# 2) 最小可跑：JDK25 + Boot 4.0.9 + starter-process 8.0.0 + H2
mvn -B spring-boot:run     # 观察 "Started App in ..." 与五套 create 脚本日志

# 3) 我们现在的引擎规模
find req-service/src/main/java/com/example/req/approval -name '*.java' | wc -l   # 39
find req-service/src/main/java/com/example/req/approval -name '*.java' | xargs grep -c '' | awk -F: '{s+=$2} END{print s}'  # 4570
```

## 9. 决策状态

- **2026-11-23：暂缓**。保持自研；Flowable 8 作为已知可行备选记录在案。
- 触发条件见 §5；届时按 §7 的 spike 先取数，不做"拍脑袋替换"。
