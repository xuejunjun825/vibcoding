# 技术方案 · r33 质量门 SKIPPED 不计入「未通过」

- 任务夹：`docs/teamflow/20261119-r33-qg-skipped-rule-count/`
- 基线依赖：`docs/teamflow/20261119-r32-release-flow-e2e/`（发版链路 8 步实测与冻结快照）
- 分支：`feat/quality-gate-skipped-count`
- 判决：**一个统计口径、一处计算、三处展示**。判定语义（`result` / 准入第 6 项 / 409）**零改动**；改的是「把一条规则算进哪一桶」以及由此派生的文案。

---

## 1. 目标与非目标

**做**：把质量门规则统计从「非 PASS 即失败」改成四态分桶（PASS / WAIVED / SKIPPED / FAIL），`failedRules` 只装 FAIL，`skippedRules` 单列；发版链路卡片副标题、质量门页计数、准入第 6 项 detail 三处统一到同一口径；旧快照按 `status` 回放保持原样。

**不做**（越界即退回）：
1. 不改 `result` 判定、不改 9 项准入顺序、不改两级/三级审批链、不改 409 语义。
2. 不改 `quality_gate_snapshot` 表结构、不改 `rules_json` 已存形状、不重写历史快照、**不加 V15 迁移**。
3. 不让 SKIPPED 变成「通过」：它既不在 `passedCount` 里，也不在通过率分母里，任何文案不得写成「全部通过」。
4. 不改 `web/package.json` / `pom.xml` / 锁文件；不引入测试框架。
5. 不动演示 MR 示例地址（PRD 明确本轮不改）。

---

## 2. 现状与根因（已逐行确认）

| # | 位置 | 现状 | 后果 |
|---|---|---|---|
| 1 | `deliver-service/src/main/java/com/example/deliver/quality/internal/FlowEvidenceService.java:276-318` | `qualityGate(...)` 用 `if (rule.passed()) continue;` 收 `failedRules`；`GateRuleResult.passed()` 只认 `PASS`/`WAIVED` | `QG-05`（`SKIPPED`）被收进 `failedRules` |
| 2 | `web/src/components/release/FlowChainPanel.vue:131-147` | 徽标 tone 取 `status`/`result`，副标题取 `failedRules.length` | 同一张卡「PASS + 1 条未通过」 |
| 3 | `web/src/components/quality/QualityGatePanel.vue:100-107,143` | `passedCount` 把 `PASS|WAIVED|SKIPPED` 都算通过，分母是全部规则 | 质量门页「5/5 通过」把跳过算成通过（PRD AC-6 第二处） |
| 4 | `req-service/src/main/java/com/example/req/deploy/AdmissionPolicy.java:264-302` | PASS 分支写 `rules.size()+" 条规则全部通过"` | SKIPPED 存在时文案谎报「5 条全通过」（AC-6 第三处） |

**已有正确口径作参照**：`deliver-service/.../quality/gate/QualityGateService.java:461-472` 的 `mapRow` 对 `SKIPPED` 直接 `continue`，`passed/total` 分母不含跳过。本方案是把发版链路对齐到这条既有标准，不新立标准。

**跳过原因在哪**：`deliver-service/.../quality/gate/GateEvaluator.java:634-638` 的 `skip(code, actual)` 把原因写进 **`actual`**，`fixHint` 是配置里的启用提示，`required` 恒 `false`（AC-1 断言 `required=false` 成立）。因此明细行取 `actual` 而非 `fixHint`。

---

## 3. 数据模型与存储

### 3.1 物理存储：零变更
`quality_gate_snapshot` 表、列、`snapshot_json` / `rules_json` 形状、`payloadHash` 计算、`(deployOrderId, hash)` 复用语义全部不变；无新迁移文件（迁移目录仍以 V14 为末位）。旧快照里 `SKIPPED` 仍在 `failedRules` 数组内——这是**允许的**，因为读取端按 `status` 分桶，不按数组长度。

### 3.2 分桶口径（唯一真值表，服务端与前端必须一致）

| `status` | 桶 | `failedRules` | `skippedRules` | 通过率分母 |
|---|---|---|---|---|
| `PASS` | passed | — | — | 计入 |
| `WAIVED` | waived | — | — | 计入（对齐 `QualityGateService` 的 passed 口径） |
| `FAIL` | failed | **是** | — | 计入 |
| `SKIPPED` | skipped | — | **是** | **不计入** |
| 空 / 未知 | failed（fail-closed） | 是 | — | 计入 |

恒等式：`passed + waived + failed + skipped == total`。未知状态归 failed 是为了「宁可拦、不可放」：任何将来新增的状态若没在这里登记，不会被静默当作可用证据。

### 3.3 接口载荷形状（flow-chain 的 `flowChain.qualityGate`，只增字段）

```jsonc
{
  "status": "PASS", "result": "PASS", "reason": "…",
  "snapshotId": 812, "gateId": 21, "reportId": 55, "computedAt": "2026-11-19T…",
  "failedRules": [],                          // 只装 FAIL（新语义）
  "skippedRules": [ { "code": "QG-05", "status": "SKIPPED", "required": false,
                      "actual": "未启用（本轮无外部质量数据源接入）",
                      "threshold": "—", "fixHint": "…" } ],
  "waivers": [],
  "total": 5, "passedCount": 4, "waivedCount": 0, "failedCount": 0, "skippedCount": 1
}
```

计数 5 个字段是**新增的断言面**（AC-1 要 `total=5` 与恒等式），不是展示来源：展示永远由规则数组按 `status` 分桶得出，这样旧快照（无计数、`failedRules` 内混 SKIPPED）与新快照走同一条代码路径。

---

## 4. API 设计

| 接口 | 变化 | 说明 |
|---|---|---|
| `GET /api/req/releases/{id}`（`flowChain.qualityGate`） | 新增 `skippedRules`、`total`、`passedCount`、`waivedCount`、`failedCount`、`skippedCount`；`failedRules` 语义收窄为 FAIL | req 侧原样透传 deliver 段，不做二次判定 |
| `GET /api/req/deploy-orders/{id}/flow-chain`（同段） | 同上 | 同一段实体，两个入口共用 |
| `GET /api/req/deploy-orders/{id}/flow-snapshots/{round}`（回放） | 不新增字段、不迁移；回放旧 JSON 时新字段为 `null`/缺省 | 前端按 `status` 分桶，不依赖计数 |
| `GET /api/deliver/quality-gates/snapshots/{id}` | **不变**（`rules[]` 原样 5 条含 SKIPPED） | 质量门页与 S7 现有断言继续可用 |
| `POST /api/deliver/quality-gates/{releaseId}/evaluate` | 不变 | S8-2 用它造 FAIL 快照 |
| `GET /api/deliver/quality-gates/{releaseId}/…`（列表口径） | 已正确，不改 | 它就是「跳过不进分母」的参照实现 |

**准入（`GET /api/req/deploy-orders/{id}` 的 `admission.items[]`）**：`QUALITY_GATE` 项的 `result` 仍只由 `evidence.result` 派生（PASS/PASS_WITH_WAIVER → 通过；FAIL → 失败；其余 → 未定）；仅 `detail` 文案在 `skippedCount > 0` 时改为：

```
4 条通过 · 1 条跳过（未启用），质量门 PASS
```

`skippedCount == 0` 时保留原句 `N 条规则全部通过（含规则级豁免），质量门快照 #id`——这样 `AdmissionPolicyTest` 现有「5 条全 PASS」夹具继续绿（PRD §7 风险项），且全通过场景不引入噪音。

---

## 5. 服务端实现

### 5.1 新增契约类（唯一分桶实现）

`platform-contract/src/main/java/com/example/platform/contract/quality/GateRuleBuckets.java`

```java
public record GateRuleBuckets(int passedCount, int waivedCount, int failedCount, int skippedCount,
                              List<GateSnapshot.GateRuleResult> failedRules,
                              List<GateSnapshot.GateRuleResult> skippedRules) {
    public static GateRuleBuckets of(List<GateSnapshot.GateRuleResult> rules); // null/空 → 全 0
    public int totalCount();        // passed+waived+failed+skipped
    public int judgedCount();       // total - skipped：通过率分母
    public int passedLikeCount();   // passed + waived：对齐 QualityGateService 的 passed
    public boolean hasSkipped();
}
```

- 放在 `platform-contract` 而非各服务内：deliver 产、req 消费（准入文案）与断言（S8）都要同一套分桶，复制一份必然漂移。
- `GateSnapshot.GateRuleResult` 增两个**非组件**便利方法 `public boolean skipped()`、`public boolean waived()`；record 组件不动，故 JSON 与 hash 不变（`passed()` 已是同样写法）。

### 5.2 `FlowEvidenceService.qualityGate`

- 取 `GateRuleBuckets b = GateRuleBuckets.of(snapshot.rules())`。
- 输出：`failedRules` = `b.failedRules()` 映射为同形 Map（code/status/required/actual/threshold/fixHint）；新增 `skippedRules` 同形；`total/passedCount/waivedCount/failedCount/skippedCount` 取 `b`。
- `reason`：FAIL 分支仍 `b.failedCount() + " 条门规则未通过"`（`failedCount==0` 时保留「质量门结论 FAIL」），因此 AC-2 得到「1 条门规则未通过」而不是「2 条」。PASS/MISSING 分支文案不动。
- `status` / `result` / `snapshotId` 等其余键与顺序不变（`LinkedHashMap` 顺序即回放可读性）。

### 5.3 req 透传

`req-service/src/main/java/com/example/req/release/FlowChainViews.java`：`QualityGateSegment` 在 `failedRules` 之后插入 `List<RuleLine> skippedRules`，尾部追加 `Integer total/passedCount/waivedCount/failedCount/skippedCount`（**可空 Integer**，旧快照缺字段就是 `null`，不伪装 0）；compact 构造器把 null 列表归一为 `List.of()`，`missing(reason)` 传 null 计数。

`req-service/src/main/java/com/example/req/release/FlowChainService.java`：`qualityGate(Map)` 增加 `skippedRules` 映射与 5 个 `Integer` 读取（`Object → Integer` 的小工具方法，null 安全）。**不做判定、不重算计数**。

### 5.4 准入文案

`req-service/src/main/java/com/example/req/deploy/AdmissionPolicy.java` 的 `qualityGate(GateEvidence)`：PASS 分支用 `GateRuleBuckets.of(evidence.rules())`；`hasSkipped()` 时 detail = `passedLikeCount() + " 条通过 · " + skippedCount() + " 条跳过（未启用），质量门 PASS"`（`PASS_WITH_WAIVER` 再追加「（含规则级豁免）」）；否则原句不变。FAIL 分支不动（它本来就只列 FAIL 规则）。

---

## 6. 前端：纯函数 + 两个组件

### 6.1 新增纯函数模块（零 import，可被 `node` 直接加载）

`web/src/components/release/qualityGateBuckets.ts`

```ts
export interface GateRuleLike {
  code?: string | null; status?: string | null; required?: boolean | null
  actual?: string | null; threshold?: string | number | null; fixHint?: string | null
}
export interface GateBuckets {
  passed: number; waived: number; failed: number; skipped: number; total: number
  failedRules: GateRuleLike[]; skippedRules: GateRuleLike[]
}
export function bucketGateRules(
  input: GateRuleLike[] | { failedRules?: GateRuleLike[] | null; skippedRules?: GateRuleLike[] | null } | null | undefined,
): GateBuckets
export function gateCountText(b: GateBuckets): string       // '' | '未通过 1 条' | '跳过 1 条 · 未通过 0 条'
export function gateCountLabel(b: GateBuckets): string      // '4/4 通过 · 跳过 1' | '5/5 通过'
export function failedRuleLines(b: GateBuckets, limit?: number): string[]  // ['QG-04 1 个未关闭 P0/P1（#9）/≤ 0 个']
export function skippedRuleLines(b: GateBuckets, limit?: number): string[] // ['跳过：QG-05 未启用（本轮无外部质量数据源接入）']
```

规则：
1. **按 `status` 分桶，绝不看数组长度**（AC-7 硬约束）：输入数组既可能是新形状（`failedRules` 只有 FAIL），也可能是旧形状（`failedRules` 内混 SKIPPED）。
2. 以 `code` 合并两个数组，`skippedRules` 覆盖同 code 的旧行；无 `code` 的行按序占位，保证顺序稳定。
3. `gateCountText`：`skipped>0` → `跳过 N 条 · 未通过 M 条`；`skipped==0 && failed>0` → `未通过 M 条`；否则 `''`（全通过不显示「未通过 0 条」，PRD §10-1）。分隔符固定 ` · `。
4. `gateCountLabel` 分母 = `total - skipped`，分子 = `passed + waived`；`skipped>0` 追加 ` · 跳过 N`。
5. 零 `import`（不引 `dayjs`/`format.ts`/`types.ts`）：`node --experimental-strip-types` 可直接 import，无需路径别名与依赖解析；时间串由组件负责拼接。

### 6.2 `web/src/components/release/FlowChainPanel.vue`

③ 段改为 `const b = bucketGateRules(segment)`：
`sub = [formatDateTime(computedAt), gateCountText(b), waivers.length>0 ? \`${waivers.length} 条豁免\` : ''].filter(Boolean).join(' · ')`；
`details = [...failedRuleLines(b, 2), ...skippedRuleLines(b, 1)]`。
`tone` 映射保持只由 `status` 派生（AC-4：卡片 PASS/FAIL/PENDING 不因数字改变）。组件仍「零判定、零请求」——分桶是纯函数，不发请求、不读全局状态。

### 6.3 `web/src/components/quality/QualityGatePanel.vue`

删除把 `SKIPPED` 计入通过的 `passedCount`/`failedCount`，改用 `bucketGateRules(ruleList)`：计数行渲染 `规则 {{ gateCountLabel(b) }}`，未通过追加 `b.failed`；明细里 SKIPPED 行标「跳过」而不是「未通过」。

### 6.4 `web/src/api/types.ts`

`FlowQualityGateSegment` 增 `skippedRules?: FlowGateRuleLine[]` 与 `total?/passedCount?/waivedCount?/failedCount?/skippedCount?: number`（全部可选：旧快照回放没有它们）。**不改** `FlowGateRuleLine`。

**状态管理**：无新增 store / 无全局状态。分桶是 props → `computed` 的纯派生；`web/package.json` 不动（不引测试框架）。

---

## 7. 关键实现点与边界

1. **旧快照回放**：`FlowSnapshotService` 用 `tools.jackson.databind` 反序列化 `SnapshotPayload`，缺字段 → `null`。`QualityGateSegment` 计数用 `Integer` 而非 `int`，就是为了不把「无此字段」伪装成「0 条」；前端也**不读**计数，故回放与实时走同一条路径。
2. **恒等式自检**：deliver 侧一旦出现「某条规则既不在 passed/waived/failed/skipped 任一处」，`totalCount()` 就与 `rules.size()` 不等——单测与 S8-1 都断言 `total == 5` 且四项和相等。
3. **`reason` 兼容**：AC-2 要求恰为「1 条门规则未通过」；实现里 `reason` 仍由 `failedCount` 生成，绝不能改成 `failedRules.size() + skippedRules.size()`。
4. **MISSING 段**：`missingGate`/`missing(reason)` 产出的段没有规则数组，分桶为全 0、文案为空，页面显示「缺失」而不是「0 条未通过」。
5. **`waivers` 与跳过的区别**：`waivers` 是审批过的豁免（`PASS_WITH_WAIVER`），跳过是「未启用」；文案分别追加，不得合并计数。
6. **准入判定不看 `skippedRules`**：第 6 项只读 `evidence.result`；`SKIPPED` 存在且 `result=PASS` 时仍 200、9 项不变（AC-5 回归面）。
7. **`QG-04` 不可停用**：`QualityGateService.configure` 已拦（`RULE_NOT_WAIVABLE`），本方案不碰；S8-2 造的 P0 只会让 QG-04 FAIL，不会被「关掉规则」绕过。
8. **S8-2 的状态自愈**：在 S7 建出的**本轮临时演示版本**上造未关闭 P0 → 复评 → 断言 → 关缺陷 → 再复评恢复 PASS 最新快照，避免 `RELEASE_FLOW_SKIP_RESET=1` 时把演示库留在 FAIL。
9. **hash 不变**：`snapshot_json` 与 `payloadHash` 无任何字段改动；S8-4 断言回放 round=1 的 `payloadHash` 与提交时记录一致。
10. **门禁只增不减**：`RELEASE_FLOW_TOTAL` 40 → 44，section 计数 7 → 8，静态自检条数 9 → 10；任何一条断言被删都会在 static 段先失败。

---

## 8. 测试策略

### 8.1 单元测试（新增 3 个，`mvn` 307 → ≥310）

| 文件 | 覆盖 |
|---|---|
| `deliver-service/src/test/java/com/example/deliver/quality/gate/GateRuleBucketsTest.java` | 三桶切分（`failedRules` 只含 FAIL、`skippedRules` 只含 SKIPPED）、恒等式、未知状态 fail-closed、`null` 列表不炸 |
| `req-service/src/test/java/com/example/req/deploy/AdmissionPolicySkippedDetailTest.java` | `skipped>0` → 「4 条通过 · 1 条跳过（未启用），质量门 PASS」；全 PASS → 原句不变；`result=FAIL` 仍失败 |
| `req-service/src/test/java/com/example/req/release/FlowChainViewsQualityGateTest.java` | `QualityGateSegment` 旧形状 JSON（无 `skippedRules`/计数）反序列化后列表为空、计数为 `null`，不抛异常 |

### 8.2 node 断言（前端无 DOM 测试框架，故断言纯函数）

`scripts/tests/release-flow/qg-buckets.mjs`（新增，`node --experimental-strip-types`）
- `text` 模式：三态文案（`跳过 1 条 · 未通过 0 条` / `未通过 1 条` / `''`）、质量门页 label（`4/4 通过 · 跳过 1`）、跳过明细含 `QG-05` 与原因。
- `legacy` 模式：旧形状 fixture（`failedRules` 内混 `SKIPPED`、无 `skippedRules`/计数）→ `skipped=1, failed=0`、文案同 `text` 模式（AC-7 的机制断言）。
- 输出 `PASS <n>` 行，任一失败 `exit 1`。

### 8.3 门禁新增 S8 四条（`scripts/release-flow-verify.sh` 40 → 44）

| 断言 | 覆盖 AC | 做法 |
|---|---|---|
| `s8_demo_chain_buckets` | AC-1 / AC-4 / AC-6 | 读 S7 发布版本的 flow-chain：`status/result=PASS`、`failedRules` 长度 0、`skippedRules` 含 `QG-05` 且 `required=false`、`total=5`、四项和 == total；准入第 6 项结论 == 门 `result` |
| `s8_negative_p0_gate` | AC-2 / AC-5 | 在 S7 的临时版本上 `POST /api/deliver/defects`（P0，`releaseId`）→ `POST …/evaluate`：`result=FAIL`、`failedRules` 恰 `[QG-04]`、`skippedRules` 仍含 `QG-05`、`reason == "1 条门规则未通过"`；关缺陷后复评恢复 PASS |
| `s8_text_contract` | AC-3 / AC-6 | 跑 `qg-buckets.mjs text`，逐字断言三态文案与 `4/4 通过 · 跳过 1` |
| `s8_legacy_replay` | AC-7 | 跑 `qg-buckets.mjs legacy`；并断言 live 回放 round=1 两次 `payloadHash` 相同、旧 `failedRules` 分桶 `skipped=1/failed=0` |

`static_checks()` 同批更新：`RELEASE_FLOW_TOTAL=44` 唯一声明、`^section "S[1-8] ` 计数 8、`^s8_[a-z_]+\(\) \{` 计数 4、S7 函数计数仍 10、帮助/取证文本与文件头「44 条 / 八组」一致。

### 8.4 全量门禁（只增不减）

`make release-flow-verify` 44/44 · `platform-verify` ≥194 · `platform-test-negative` ≥130 · `access-verify` ≥206 · `rbac-verify` 113 · `platform-demo-reset` ≥74 · `platform-unit` ≥374 · `make lint` 22 · `cd web && npm run typecheck && npm run build` · `mvn -o … test` ≥310、0 失败。

### 8.5 文档与证据（AC-10）

- `docs/teamflow/DEMO-RELEASE-FLOW.md:311-314` 遗留段改写为「已修正（r33）：跳过 1 条 · 未通过 0 条」并说明三桶口径；A.2 四个实操事实、8 步脚本、被拦链一律不动。
- 新截图 `docs/teamflow/demo-shots/r33-qg-skipped-count.png`（**新文件名**，不覆盖 r30/r32 旧图），须能看清卡片 PASS 徽标 + 「跳过 1 条 · 未通过 0 条」。
- 取证脚本沿用仓库既有 Playwright/`shots.mjs` 方式，脚本落在 `logs/teamflow/<runId>/scripts/`（run 内共享区，不重建已有 checker，缺失能力就地扩展）。

---

## 9. 并行任务拆分（按文件边界，disjoint ⇒ 可并行）

> 契约先冻结：`GateRuleBuckets` 的签名（§5.1）与 `qualityGateBuckets.ts` 的签名（§6.1）是本方案的对外契约，下游任务必须**逐字**调用，不得自创 `bucket()`/`skipCount()` 等改名。

| 任务 | 文件（ownership） | 只读 | 依赖 | 验收 |
|---|---|---|---|---|
| **T0 契约**（assemblyOrder 1） | `platform-contract/src/main/java/com/example/platform/contract/quality/GateRuleBuckets.java`（新）、`platform-contract/…/quality/GateSnapshot.java`、`deliver-service/src/test/java/com/example/deliver/quality/gate/GateRuleBucketsTest.java`（新） | `QualityGateService.java`（口径参照） | — | 单测绿；`mvn -o -pl platform-contract,deliver-service -am test` |
| **T1 deliver 产出** | `deliver-service/src/main/java/com/example/deliver/quality/internal/FlowEvidenceService.java` | `GateRuleBuckets.java`、`GateEvaluator.java` | T0 | 段内 `failedRules` 只含 FAIL、新增 `skippedRules` 与 5 计数；`mvn -o -pl deliver-service test` |
| **T2 req 透传 + 准入文案** | `req-service/src/main/java/com/example/req/release/FlowChainViews.java`、`req-service/…/release/FlowChainService.java`、`req-service/src/main/java/com/example/req/deploy/AdmissionPolicy.java`、`req-service/src/test/java/com/example/req/deploy/AdmissionPolicySkippedDetailTest.java`（新）、`req-service/src/test/java/com/example/req/release/FlowChainViewsQualityGateTest.java`（新） | `DeployViews.java`、`GateRuleBuckets.java` | T0 | 旧快照回放不抛异常；全 PASS 文案原句不变；`mvn -o -pl req-service test` |
| **T3 前端三桶** | `web/src/components/release/qualityGateBuckets.ts`（新）、`web/src/components/release/FlowChainPanel.vue`、`web/src/components/quality/QualityGatePanel.vue`、`web/src/api/types.ts` | `web/src/utils/format.ts`、`scripts/tests/release-flow/`（无） | — | `cd web && npm run typecheck && npm run build`；`node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs text` 由 T4 提供脚本后联调 |
| **T4 门禁 S8 + node 断言** | `scripts/release-flow-verify.sh`、`scripts/tests/release-flow/qg-buckets.mjs`（新） | `qualityGateBuckets.ts`、`FlowEvidenceService.java`、`AdmissionPolicy.java`、`scripts/platform-demo-reset.sh` | T1、T2、T3 | `bash scripts/release-flow-verify.sh --static-only` 静态 10 项全绿；`make release-flow-verify` 44/44 |
| **T5 文档与截图** | `docs/teamflow/DEMO-RELEASE-FLOW.md`、`docs/teamflow/demo-shots/r33-qg-skipped-count.png`（新） | `PRD.md`、`docs/teamflow/memory.md` | T1、T3（文案定稿）+ T4（跑通后截图） | 遗留段改写、新文件名截图入库、8 步脚本零改动 |
| **T6 交付自检 + 提交** | 无（只修 T0–T5 的缺陷） | 全部 | T0–T5 | 见 §10 自检清单；**git 约束见下** |

**并行编排**：T0 → {T1, T2, T3} → {T4, T5} → T6。

### git 约束（PRD 工程约束，必须落到 T6 spec）
- 在 `feat/quality-gate-skipped-count` 分支提交（当前即此分支，唯一未提交改动为任务夹目录）。
- 提交信息含 `runId=tf-mwnasdev-57rg81` 与 `docs/teamflow/20261119-r33-qg-skipped-rule-count/`。
- diff 不得含 `pom.xml`、`web/package.json`、`web/pnpm-lock.yaml`、任何迁移文件（无 V15）。
- 提交前复核 `.gitignore`（`target/`、`node_modules/`、`dist/`、`.run/`、`logs/`、`.env*` 已忽略）；新截图必须在 `docs/teamflow/demo-shots/` 入库。
- 提交后复跑 `make release-flow-verify` 全绿（含第 32 条 `BASE..HEAD` 区间纯判据），并把结论写进 `logs/teamflow/tf-mwnasdev-57rg81/`（仅结论笔记，不 dump 命令输出）。

---

## 10. 交付自检清单（T6，不是 QA 全矩阵）

1. `make release-flow-verify` 44/44、`--static-only` 10/10。
2. `make platform-verify` / `platform-test-negative` / `access-verify` / `rbac-verify` / `platform-demo-reset` 均不低于下限且全绿。
3. `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` + 相关模块 `test` ≥310、0 失败。
4. `cd web && npm run typecheck && npm run build`。
5. 人工点两页（发版链路卡、质量门页）数字一致；回放 r30/r32 旧发布单显示「跳过 1 条 · 未通过 0 条」。
6. `git status` 与 `git diff --stat` 核对 §9 git 约束；无迁移/无 pom/无锁文件改动。

---

## 11. 风险与回滚

| 风险 | 处置 |
|---|---|
| `AdmissionPolicyTest` 现有 5 条全 PASS 夹具因文案改动变红 | 全 PASS 分支保留原句，仅 `skipped>0` 换文案（§4） |
| 旧快照回放因 record 新增组件反序列化失败 | 新组件全部可空/可缺省（`Integer`、compact ctor 归一），并由 `FlowChainViewsQualityGateTest` 用旧形状 JSON 钉住 |
| 前端两处实现分桶导致再次漂移 | 分桶只有 `qualityGateBuckets.ts` 一处；质量门页与链路卡共用同一函数；S8-3 逐字断言输出 |
| `--experimental-strip-types` 在该 Node 版本不可用 | S8-3 会显式失败（不做静默跳过）；备选是把纯函数复制为 `.mjs` 的同构实现，但**不得**改动 `web/package.json` |
| 造 P0 影响演示库后续截图 | S8-2 关缺陷后复评恢复；EXIT trap 仍跑 `platform-demo-reset.sh` |

**回滚**：所有改动集中在「新增分桶类 + 三个消费者 + 门禁/文档」，`git revert` 单次提交即可；快照与表结构从未变更，回滚不需要数据修复。
