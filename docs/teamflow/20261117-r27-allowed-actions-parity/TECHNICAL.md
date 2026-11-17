# r27 技术方案 · 需求池 allowedActions 与详情同源

> 基线依赖：`docs/teamflow/20261117-r26-wbs-estimate-gate/`（r26 工时评估门）。
> 本需求**不取代**任何历史 AC，只修 r26 之后暴露的**契约旁路**：池/导出的 `allowedActions` 没走 r26 落地的唯一判定链。
> 分支：`feat/wbs-estimate-approval`（**不新建分支**）。runId：`tf-mwk3086h-ojufgx`。

## 1. 判断与改动面

**判断**：缺陷是**服务端契约旁路**，不是前端问题。`RequirementPoolQuery.toView` 用 `stateMachine.allowedActions(type, status)` 直出数组，既没有调用者身份（行里缺 `reporter_id`），也没有 r26 的评估门状态，于是同一条 PDR 在列表里是 `["schedule"]`、在详情里是 `[]`。

**修复**：把池/导出的 `allowedActions` 收回 `RequirementService` 的唯一判定链（权限矩阵 ∩ 状态机 → r26 门过滤），读路径改为**按页批量判定**（每页固定 ≤2 条评估查询，与行数无关）。**零 schema 变更、零前端改动、零新依赖**。

改动面（TECHNICAL 唯一声明，AC-14/AC-18）：

| 文件 | 改动 |
| --- | --- |
| `req-service/src/main/java/com/example/req/requirement/RequirementPoolQuery.java` | 补 `reporter_id` 列、注入 `RequirementService`、`toView` 带 actions、page/all/exportBatch 批量 |
| `req-service/src/main/java/com/example/req/requirement/RequirementService.java` | 新增 `ActionRow` / `allowedActionsByRow` / `gateFiltered(type,estimateStatus,actions)` |
| `req-service/src/main/java/com/example/req/requirement/WbsEstimateService.java` | 新增 `GateSubject` / `statusesOf` / `applyGate(type,status,actions)`，六态判定收敛为 `decide` 一张表 |
| `req-service/src/main/java/com/example/req/requirement/EstimateSnapshot.java` | `document(List<ApprovalHistory>, json)` 重载（解析仍一处） |
| `req-service/src/main/java/com/example/req/approval/ApprovalStore.java` | `default Map<Long,List<ApprovalHistory>> histories(Collection<Long>)` |
| `req-service/src/main/java/com/example/req/approval/JpaApprovalStore.java` | 覆写 `histories` 为 1 条 IN 查询 |
| `req-service/src/main/java/com/example/req/approval/ApprovalInstanceRepository.java` | 新增 `findAllByTenantIdAndBizTypeAndBizIdInOrderByIdDesc` |
| `req-service/src/main/java/com/example/req/approval/ApprovalHistoryRepository.java` | 新增 `findByInstanceIdInOrderByInstanceIdAscIdAsc` |
| `req-service/src/test/java/com/example/req/requirement/WbsEstimateBatchParityTest.java` | 新增（AC-2 单测） |
| `scripts/platform-test-negative.sh` | 新增 4d) 段（AC-1/3/4/5/6/7）+ 变异钩子 |
| `scripts/platform-verify.sh` | 新增 1 条同源静态检查（AC-2） |
| `scripts/platform-perf.sh` | 新增 `req-list-200` 项（AC-8） |
| `docs/teamflow/memory.md` | AC-12 同主题行订正 |
| `docs/teamflow/20261117-r26-wbs-estimate-gate/evidence/*.png` | AC-11 三张（新建 `evidence/`，只新增文件） |

**不动**：`pom.xml`、`web/`（含 `web/package.json`）、`api-gateway`、`deliver-service`、审批引擎、`wf_*` 表结构、r17 动作矩阵、r26 门/六态的任何判定语义。

## 2. 根因（逐处对照）

| 入口 | 现状 | 修复后 |
| --- | --- | --- |
| `GET /{id}` 详情根 + `.view` | `gateFiltered(entity, allowedActions(type,status,reporterId))`（`RequirementService.java:329-342`、`:780-800`） | 不变 |
| `GET /{id}/actions` | 同上一条链（`RequirementService.allowedActions(id)`） | 不变 |
| `GET /requirements` 列表 | `stateMachine.allowedActions(type,status)`（`RequirementPoolQuery.java:215`） | 同源 + 批量 |
| `GET /requirements/search` | 同一 `poolQuery.page(...)`，因此同缺陷 | 同源 + 批量 |
| `GET /requirements/export` | `exportBatch` → 同一 `toView`，语义同样旁路（CSV 不含该列，但仍须同源） | 同源 + 按批 |

两个缺失的输入，正是修复要补的两件事：
1. **调用者身份无关，缺的是被判定对象**：动作矩阵 `RequirementActionMatrix.filter(type,status,reporterId,caller)` 需要 `reporterId` 才能判「提交/评审自己的 vs 他人的」；`COLUMNS`（`RequirementPoolQuery.java:27-36`）只取了 `owner_user_id`，没有 `reporter_id`。
2. **门状态**：r26 的门是「读时派生」，池路径没有派生过评估六态，也没有调用 `applyGate`。

## 3. 数据模型与存储

**无 schema 变更**：`reporter_id`、`estimate_days`、`tenant_id` 均已在 `requirement` 表；`wf_instance` / `wf_history` 表结构不动，r26 的五类单据模型不变。无迁移脚本、无新索引（现有 `(tenant_id, biz_type, biz_id)` 与 `(instance_id)` 覆盖新增查询）。

新增两条**只读**批量查询（JPA derived query，不写 `@Query`）：

```java
// ApprovalInstanceRepository
List<ApprovalInstance> findAllByTenantIdAndBizTypeAndBizIdInOrderByIdDesc(
        Long tenantId, String bizType, Collection<Long> bizIds);

// ApprovalHistoryRepository
List<ApprovalHistory> findByInstanceIdInOrderByInstanceIdAscIdAsc(Collection<Long> instanceIds);
```

以及 `ApprovalStore` 的批量读抽象（默认实现退化为逐条，只有 JPA 实现覆写为一次 IN）：

```java
default Map<Long, List<ApprovalHistory>> histories(Collection<Long> instanceIds) { ... }
```

这样「历史流水的读取」仍然只有一个抽象（`ApprovalStore`），批量只是它的一个实现细节；`EstimateSnapshot` 的 JSON 解析也只有一份（新增 `document(List<ApprovalHistory>, WorkflowJson)` 重载，旧方法委托它）。

## 4. 接口契约（对外零变化）

路由/参数/返回结构全部不变，只修数组内容。判定必须满足的不变式：

- **I1 五处一致**：`list.items[i].allowedActions`、`search.items[i].allowedActions`、`detail.allowedActions`、`detail.view.allowedActions`、`/{id}/actions.allowedActions` —— 逐字符串、**含顺序**相等（`jq -c` 原始文本相等）。
- **I2 列表行 = 详情**：同一调用者、同一租户、且两次读取之间需求未流转时成立。
- **I3 不新增动作**：结果 ⊆ 该 `(type,status)` 的状态机合法集合。
- **I4 受门动作**：`PDR:schedule`、`RD:start_dev` 仅当 `WbsEstimateService.statusOf == APPROVED` 时出现（与 `transition` 的 409 判定同一函数）。
- **I5 权限动作只由矩阵决定**：门只摘受门动作，不引入/删除 `approve`/`review_*`/`submit`/`deliver`。
- **I6 空集是 `[]`**，不返回 `null`。
- **I7 顺序可复现**：结果顺序 = `RequirementActionMatrix.filter` 的输出顺序，`applyGate` 保序（不得 `Set` 化、不得排序）。

## 5. 后端设计

### 5.1 唯一判定链（修复后）

```
Controller
  └─ RequirementPoolQuery.page/all/exportBatch
       ├─ rows(含 reporter_id) → RequirementService.ActionRow[]
       ├─ RequirementService.allowedActionsByRow(rows)          ← 唯一入口（package-private）
       │    ├─ WbsEstimateService.statusesOf(GateSubject[])     ← 每页 1~2 条查询
       │    ├─ allowedActions(type,status,reporterId)           ← 既有：状态机 ∩ 矩阵
       │    └─ gateFiltered(type,estimateStatus,actions)        ← 与详情同一实现
       └─ toView(row, rollup, actions)
```

详情链不动，只把它的门过滤换成语义相同的重载：`gateFiltered(entity, actions) → gateFiltered(type, statusOf(entity), actions)`。

### 5.2 精确签名（开发必须逐字调用，改签名=改契约）

`com.example.req.requirement.RequirementService`（package-private，同包调用）：

```java
record ActionRow(long id, String type, String status, Long reporterId, BigDecimal estimateDays) {}
Map<Long, List<String>> allowedActionsByRow(List<ActionRow> rows);          // 空入参返回 Map.of()
List<String> gateFiltered(String type, String estimateStatus, List<String> actions);
List<String> allowedActions(String type, String status, Long reporterId);   // 既有，签名不变
```

`com.example.req.requirement.WbsEstimateService`：

```java
record GateSubject(long requirementId, String type, BigDecimal estimateDays) {}
Map<Long, String> statusesOf(List<GateSubject> subjects);                   // 只对 PDR/RD 取数
List<String> applyGate(String type, String estimateStatus, List<String> actions);
public List<String> applyGate(RequirementEntity entity, List<String> actions); // 保留，委托上面
private static String decide(boolean active, ApprovalInstance effective,
                             BigDecimal estimateDays, BigDecimal approvedDays); // 六态判定表（唯一一份）
```

`statusOf(entity)` 改为「取数 + `decide`」：`activeInstance`（在途）→ `effectiveInstance` → 仅当生效单为 APPROVED 时读 SUBMIT 快照人天 → `decide`。取数路径不变，因此 r26 的 4c 段与全部既有单测零回归。

`statusesOf` 的取数（每页固定）：

```java
Long tenantId = TenantContext.require().tenantId();
List<ApprovalInstance> all = instances.findAllByTenantIdAndBizTypeAndBizIdInOrderByIdDesc(
        tenantId, BIZ_TYPE, wantedIds);                       // ① 1 条
// 按 bizId 分组、保序；每条选 active 或 effective（跳过 WITHDRAWN 的回落规则与原实现一致）
// 仅对生效单为 APPROVED 的实例批量取 SUBMIT 快照
Map<Long, List<ApprovalHistory>> hs = store.histories(approvedIds);   // ② 0 或 1 条
```

`decide` 是唯一的状态表：`active → PENDING`；`effective == null → NONE`；`APPROVED → 偏差>20% 或人天缺失 ? STALE : APPROVED`；`REJECTED/SUPERSEDED` 同名；其余 `PENDING`。

### 5.3 每页查询预算（AC-13 留痕）

| 项 | 次数 |
| --- | --- |
| 总数 `COUNT(*)` | 1 |
| 行查询 | 1 |
| 聚合计（仅 `view=agg`） | 1 / 0 |
| 权限快照（`PermissionService` 请求内 memo） | ≤1 |
| 评估实例 IN | 1 |
| SUBMIT 流水 IN（无 APPROVED 行时为 0） | ≤1 |
| **合计** | **≤6，与 size 无关** |

修复前：每行 2 条（active + latest）→ `size=200` 约 400 条。详情路径次数不变（active 1 + latest 1 + history 1）。

### 5.4 RequirementPoolQuery 改造要点

- `COLUMNS` 增 `r.reporter_id AS reporter_id`（用 `Rows.toLong(Rows.object(row, "reporter_id"))` 读取）。
- 构造器：`RequirementPoolQuery(ScopedJdbc jdbc, RequirementStateMachine stateMachine, RequirementService requirementService)`。
- 私有方法：`View toView(Map<String,Object> row, Rollup rollup, List<String> allowedActions)`。
- `page()`：`total == 0` 或 offset 越界提前返回；否则取 rows → 拼 `ActionRow[]` → **一次** `allowedActionsByRow` → 逐行 `toView`。
- `all()` / `exportBatch()`：同样按批判定（每批一次），不得回退到状态机直出。
- `stateMachine` 字段保留，**只**用于 `rollups` 的 `stateMachine::isTerminalAcrossTypes`；`stateMachine.allowedActions` 在该文件出现 0 处（AC-2，`platform-verify.sh` 守护）。
- 依赖方向：`RequirementPoolQuery → RequirementService`（同包新边）；`RequirementService` 不反依赖 `RequirementPoolQuery`，无环。**禁止**让 `RequirementPoolQuery` 直接注入 `WbsEstimateService`——那会造出第二处门判定入口（PRD R1）。

### 5.5 边界情况

| 情况 | 处理 |
| --- | --- |
| 空页 / `total == 0` | 不查评估、不查权限，返回 `Page(List.of(), 0, …)` |
| `WbsEstimateService` 未装配（r25 前的老单测/裁剪部署） | `statusesOf` 不调用、`gateFiltered` 原样返回（与修复前一致） |
| 已通过但 `estimate_days` 为 null | `decide` 得 STALE（与详情同一口径，4c 的 STALE 用例覆盖） |
| BUS/其它类型 | `statusesOf` 不取数，`applyGate` 不动其动作 |
| `reporter_id` 为 null（历史数据） | 矩阵按「非本人」判；列表与详情入参相同，仍一致 |
| 导出 | 与列表同一过滤；CSV 列不变（不含 allowedActions），但对象内数组同源 |
| 并发 | I2 只在两次读取之间未流转时成立；断言脚本只在夹具静止时比较，不在流转中比 |
| 200 行整页 | 批量判定只发 2 条评估查询；若发现每行一条 IN，视为实现缺陷（T3 查询计数会红） |

## 6. 前端（零改动）

依据：池页动作条读的是 `selected.value?.allowedActions`（`web/src/views/req/RequirementPoolView.vue:235`、`:650`），`selected` 来自详情接口；列表行的数组在 `web/` 内**没有消费方**（`RequirementDetailView.vue:91` 也只读详情）。类型定义（`web/src/api/types.ts` 的 `allowedActions: string[]`）不变。

因此：**不改 `web/` 任何文件**（AC-14 要求 `web/package.json` diff 为空）。修复后用户可见的变化是「接口不再自相矛盾」，不是 UI 改版。QA 仍需在池页点开一行，确认动作条与详情一致（回归，不是新功能）。

## 7. 状态管理与缓存

- **不引入缓存**：六态是读时派生（r26 决策），缓存会让 STALE 变陈旧；列表/详情必须同一时刻同一答案。
- **权限快照**：`PermissionService` 已按租户快照 + 请求内 memo，200 行 × 每行动作数不会放大查询；本需求不新增缓存层。
- **事务**：池查询只读，`RequirementPoolQuery` 不加 `@Transactional`（JPA repository 自带只读事务）；不为一致性跨请求加锁。
- **失效**：无缓存可失效。

## 8. 测试策略

### 8.1 单测（AC-2，新增 `WbsEstimateBatchParityTest`）

mock 风格同 `WbsEstimateStaleTest`（`mock(ApprovalInstanceRepository)` + `mock(ApprovalStore)` + `TenantContext.set(DataScope.system(1L))`）：

1. `statusesOf(6 个 subject)` 与逐条 `statusOf(entity)` **全等**：NONE / PENDING / APPROVED / REJECTED / SUPERSEDED / STALE。
2. BUS 不在 `statusesOf` 结果里（不取数、不误判）。
3. 常数查询：`statusesOf(3 行)` → `instances.findAll…In…` 1 次、`store.histories` 1 次（`verify(times(1))`）。
4. `applyGate(type,status,actions)`：保序、APPROVED 不删、非 APPROVED 摘 `schedule`、全被摘时返回 `[]`、BUS 原样。
5. `applyGate(entity, actions)` 与新重载结果一致（同一语义一份实现）。

unit 基线 367 → 预计 ≥371。

### 8.2 负向 4d（AC-1/3/4/5/6/7）

位置：`scripts/platform-test-negative.sh`，插在 4c) 之后、5) 之前，复用 4b/4c 的 `GATE_PDR`、`GATE_RD` 与 pm/biz1 登录。helper（只比较原始数组，**禁止** `length`/`contains`/排序）：

```bash
actions_of() { printf '%s' "$1" | jq -c '.allowedActions // []'; }
row_actions() { printf '%s' "$2" | jq -c --argjson id "$1" '(.items // [])[] | select(.id==$id) | .allowedActions'; }
parity() { # desc id keyword
  api_call GET "/api/req/requirements?keyword=${3}&size=50"      # 列表
  api_call GET "/api/req/requirements/search?q=${3}&size=50"     # 搜索（参数名 q）
  api_call GET "/api/req/requirements/${2}"                       # 详情根 + .view
  # 四段文本必须逐字节相等；R27_PARITY_MUTATE=order 时对列表侧加 `| reverse` → 必红
}
```

| # | 用例 | 夹具 | 断言 |
| --- | --- | --- | --- |
| 4d.1 | 未评估 PDR 五处一致且为空 | `GATE_PDR`（4b 产出，PENDING_ANALYSIS，无评估单） | 五处 `[]`（AC-1、AC-4） |
| 4d.2 | 同一 PDR 过评估门后五处含 `schedule` | 4d.1 之后提交评估 → pm APPROVE | 五处一致且 `index("schedule")`（AC-5） |
| 4d.3 | 已通过 RD 五处一致且含 `start_dev` | `GATE_RD`（4c 收尾态 APPROVED） | 一致 + 含 `start_dev`（AC-1） |
| 4d.4 | biz1 自建 BUS(SUBMITTED) 五处含 `submit` | biz1 新建 BUS | 一致 + 含 `submit`（AC-6） |
| 4d.5 | biz1 提交后 PENDING_REVIEW 五处无评审动作 | 同一 BUS `submit` | 一致 + 不含 `approve` 与任何 `review_*`（AC-3） |
| 4d.6 | 反假修复：pm 看同一 PDR 行有评审动作 | pm 登录看 4d.5 的行 | 列表与详情一致且都含 `approve`（证明不是「一律置空」） |
| 4d.7 | 批量路径逐行正确 | `size=50&status=POOLED` 取前 5 行 | 逐行「行数组 == 该行详情数组」，≥5 次比较 |
| 4d.8 | 顺序敏感（变异自检，QA 执行） | `R27_PARITY_MUTATE=order` 跑一遍 | 必须出现 ≥1 条 4d FAIL 且退出码非 0；去掉钩子复绿 |

negative 基线 111 → 预计 ≥119。

### 8.3 性能（AC-8）

- **改前基线**（T1 第 0 步，改代码之前）：`size=200&status=POOLED` 连测 20 次取 p95，写 `logs/teamflow/tf-mwk3086h-ojufgx/perf-before.txt`。
- **改后**：同参同次数，写 `perf-after.txt`；断言 `p95_after ≤ 1.1 × p95_before` **且** `≤ 1000ms`。
- `scripts/platform-perf.sh` 新增 `req-list-200`（`timed` 20 次 + `assert_metric 1.0`），与既有 `req-list`（50/页）并存。
- **查询常数**：单次请求前后 `SHOW GLOBAL STATUS LIKE 'Com_select'` 取差值，`size=20` 与 `size=200` 各 3 次，断言 `Δ(200) − Δ(20) ≤ 2`（证明查询数与行数无关，而不是背绝对值）。

### 8.4 证据与门禁

- AC-11：`docs/teamflow/20261117-r26-wbs-estimate-gate/evidence/`（新建）三张 1440×900：`estimate-gate-pdr-disabled.png`、`estimate-todo-approval-center.png`、`estimate-reject-reason.png`；r26 其余文档**一个字节都不改**。
- 门禁顺序与阈值：`make lint`(22) → `mvn -o -DskipTests compile` → `make test`(unit ≥371) → `make platform-verify`(≥192，新增 1 条同源静态检查) → `make platform-test-negative`(≥119) → `make access-verify`(≥206) → `make rbac-verify`(113) → `make platform-demo-reset`(69) → `make platform-perf` → `cd web && npm run typecheck && npm run build`。
- 同源静态检查（`platform-verify.sh` 新增一段）：

```bash
POOL_SRC="${ROOT}/req-service/src/main/java/com/example/req/requirement/RequirementPoolQuery.java"
grep -q "stateMachine.allowedActions" "$POOL_SRC" && fail "池查询仍在直出 allowedActions" \
  || pass "池查询 allowedActions 只走 RequirementService（AC-2）"
```

## 9. 任务拆分（按文件边界；T2/T3 与 T1 并行开工）

| 任务 | 文件（ownership） | 依赖 | 验收 |
| --- | --- | --- | --- |
| **T1 后端同源 + 单测** | 上表 8 个 Java 文件 + `WbsEstimateBatchParityTest.java` | 无 | `mvn -o -pl req-service -am test` 绿；`stateMachine.allowedActions` 在 PoolQuery 为 0；4c 段不回归 |
| **T2 负向 4d + 静态检查** | `scripts/platform-test-negative.sh`、`scripts/platform-verify.sh` | 跑通需 T1 | 4d.1–4d.7 绿、4d.8 变异必红 |
| **T3 性能与 r26 证据** | `scripts/platform-perf.sh`、`logs/teamflow/tf-mwk3086h-ojufgx/`（采样/截图脚本）、r26 `evidence/*.png` | T1（before 数字由 T1 第 0 步提供） | p95 双门槛达标；三张图可判读；查询差 ≤2 |
| **T4 门禁 + memory + 提交** | `docs/teamflow/memory.md`，git 提交 | T1–T3 | §8.4 全绿；AC-12 同主题行替换；单次提交 |

**T1 第 0 步（顺序不能颠倒）**：在**未改代码**的当前工作树上先采 `size=200` 的 p95 基线并落 `logs/teamflow/tf-mwk3086h-ojufgx/perf-before.txt`，随后才允许编辑 Java。

**git 约束（PRD §10，逐条落到任务）**：

- 分支：留在 `feat/wbs-estimate-approval`，**不新建/不切换**（T4 检查 `git rev-parse --abbrev-ref HEAD`）。
- 每个任务开工先 `mvn -o -DskipTests compile`；失败即停，不带着编译错继续写脚本。
- 只提交**一次**：message 含 `runId=tf-mwk3086h-ojufgx` 与任务夹 `docs/teamflow/20261117-r27-allowed-actions-parity/`（T4）。
- 禁止 `amend` / `rebase` / `force push`；不提交 `target/`、`node_modules/`、`dist/`、`.nacos/`、`logs/teamflow/`；`.gitignore` 不得用 `*.png` 一类通配把 `evidence/` 挡掉（`git status --ignored` 自查）。
- 提交前 `git status --porcelain` 只应有本方案的改动面 + 任务夹 + r26 `evidence/`。

## 10. 风险与应对

| 风险 | 应对 |
| --- | --- |
| 循环依赖（PoolQuery → RequirementService） | 已核 `RequirementService` 不依赖 `RequirementPoolQuery`；T4 启动冒烟（`make platform-up`）兜底，cycle 会启动即失败 |
| 批量语义与逐条漂移 | 8.1 单测全等断言 + 4d.7 逐行抽样 + r26 4c 全段回归 |
| 页级性能回退（误把 IN 放进逐行） | 8.3 查询计数断言（Δ 差 ≤2）直接变红，修复=把 `statusesOf` 提到循环外 |
| 假修复（一律置空动作） | 4d.6 反向用例（pm 必须仍看到 `approve`）+ 4d.4 `submit` |
| 证据截图依赖栈 | `make platform-up` 失败按环境不可用上报，不重试、不用推理代替执行 |

## 11. 实测留痕（T4 全量门禁，2026-11-17）

按 `make lint → mvn compile → make test → make platform-verify → make platform-test-negative → make access-verify → make rbac-verify → make platform-demo-reset → make platform-perf → web typecheck/build` 逐条实跑，下列数字均为本轮实测值（非估计）：

| 命令 | 实测 | 阈值（PRD AC-9..AC-14） |
| --- | --- | --- |
| `mvn -o -q -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` | exit 0 | 必须 0 |
| `make lint` | 22/22 | ≥ 22 |
| `make test`（13 步，含 platform-unit / collab） | exit 0；`platform-unit` **371** 条、`collab` 257/257 | unit ≥ 367 |
| `make platform-verify` | **193/193**（含 AC-2 静态同源检查） | ≥ 192 |
| `make platform-test-negative` | **130/130**（含 4d 段 19 条） | ≥ 111 |
| `make access-verify` | **206/206**（a0 29 / a1 8 / a2 73 / a3 11 / a4 19 / a5 25 / a6 25 / a7 16） | ≥ 206 |
| `make rbac-verify` | **113/113** | 113 |
| `make platform-demo-reset` | **69/69**（授权行 1848 = 11×168，自定义角色 0） | 69 |
| `make platform-perf`（10 万行数据） | **10/10**：需求池列表 10 万行 50/页 p50 0.211s / p95 **0.230s** ≤ 1s；其余 9 项均达标 | 全部达标 |
| `cd web && npm run typecheck` | exit 0（`vue-tsc --noEmit`） | 0 |
| `cd web && npm run build` | exit 0（`vue-tsc + vite build`，在 `make platform-up` 内完成） | 0 |

**批量取数预算（第 5 章口径实测）**：整页 200 行 = 1 条列表查询 + 1 条审批实例 `IN` + 0~1 条 SUBMIT 快照 `IN` + 2 条权限快照查询，合计 ≤ 6 条，与行数无关（修复前 2×N ≈ 400 条）。

**200 行前后对比（AC-13）**：同一脚本同参数（`page=1&size=200&status=POOLED`，20 次采样，末页 200 行）——p95 **0.222s → 0.217s（0.98×）**，p50 0.212s → 0.214s，满足「≤1.1× 且 ≤1s」。明细在 `logs/teamflow/tf-mwk3086h-ojufgx/perf-before.txt` / `perf-after.txt`（run 临时目录，按日志策略不入库）。

**git 核对**：分支 `feat/wbs-estimate-approval`（HEAD `0d91715`）；`git diff --stat HEAD -- pom.xml web/package.json` 为空；`git status --porcelain` 仅本需求改动面（8 个 Java 文件 + 新增 `WbsEstimateBatchParityTest.java` + `scripts/platform-test-negative.sh` + `scripts/platform-verify.sh` + `docs/teamflow/memory.md` + 任务夹）。按要求**未提交**，由 host 验收后统一提交。
