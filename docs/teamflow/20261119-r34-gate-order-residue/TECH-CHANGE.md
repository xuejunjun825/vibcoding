# 技术变更单 · 门禁顺序无关化（r34）

> 类型：tech-driven rework（不新增用户功能）｜分支 `feat/gate-order-residue`｜runId `tf-mwnefs38-150e8l`
> 任务夹：`docs/teamflow/20261119-r34-gate-order-residue/`
> 基线依赖：`docs/teamflow/20261119-r33-qg-skipped-rule-count/`（main=`4d9f5b6`）

## 1. 背景与目标

**一句话**：让每个会写演示租户的门禁在退出时把数据还原到「干净重置态」，使任意门禁组合、任意顺序跑完都与先 `platform-demo-reset` 等价，不再靠「先跑哪个」才绿。

现象（实测 main=4d9f5b6）：`reset(74/74) → release-flow-verify → platform-verify → platform-test-negative → access-verify`，access 报 a7 段 15/16——R2 要求种子 PDR 的评估单为 `APPROVED`，实得 `status=STALE approved=5 proposed=5`；再 reset 后 206/206。

复核结论（读码 + 库，修正归因、不放宽任何要求）：
- 挂在种子 PDR `DEMO-R-02` 下的 3 条子需求来自 **`scripts/platform-test-negative.sh:97`**——它按「第一条 `SCHEDULED|IN_DEV` 的 PDR」取父（干净态即 `DEMO-R-02`），随后 `:99` `neg-sm`、`:177` `neg-gate-rd-ok`、`:331` `neg-r27-rd`（`:336` 提交 1.5 人天并批准）全部 `parentId:${PDR_ID}`。
- `req-service/.../seed/DemoFixture.java:390` 已写明 5.0 是「刚好不触发 20% STALE」的取值，任何子需求人天都会让 rollup 漂移（5.0→6.5 即 +30%）。
- `release-flow-verify.sh` 的 S1–S8 本身不建子需求（S7 只把 `DEMO-R-04` 改挂自建版本），但其 `cleanup()`（`scripts/release-flow-verify.sh:326-335`）把 demo-reset 的 rc 降级为 `WARN`、且 `>/dev/null 2>&1`——**清理失败静默**。真实缺口是「门禁没有统一的自清理契约」，不是单脚本。

## 2. 影响面

| 文件 | 改动 |
|---|---|
| `scripts/lib/gate-fixture.sh` | **新增**：`GATE_TAG`、前后置钩子、残留/夹具断言、显式失败 |
| `scripts/release-flow-verify.sh` | `cleanup()` 改用共享钩子（静默吞错→显式失败）；S8 静态自检同步；44 条不动 |
| `scripts/platform-test-negative.sh` | 夹具父链改挂自建 BUS→PDR（不再取种子 PDR）；接入共享钩子 |
| `scripts/platform-verify.sh` | PDR 改挂自建 `BUS_ID`（`:248` 已建），不再挂 `SEED_POOLED_BUS`；接入共享钩子 |
| `scripts/platform-demo-reset.sh` | 新增「非夹具需求保留 0 条」输出与断言（74→75）；修掉「57/57」陈旧文案 |
| `scripts/gate-order-verify.sh` | **新增**：顺序无关回归门禁（15 条） |
| `Makefile` | 新增薄封装 `gate-order-verify` |
| `docs/teamflow/memory.md` | 同步本约定（同主题行替换/追加） |

不改：`req-service`/`deliver-service` Java、`pom.xml`、`web/*`、任何迁移、`a7-estimate-gate.mjs` 判据、种子数据内容。

## 3. 技术方案要点

**3.1 统一契约（`scripts/lib/gate-fixture.sh`，被三个写入门禁 source）**
- `GATE_TAG="$(date +%m%d%H%M%S)-$$"`；本轮所有夹具标题统一带该 tag（现有 `neg-*`/`r30-*`/`验证-*` 前缀保留，后缀追加 tag）。
- `gate_fixture_begin`：注册 `trap gate_fixture_end EXIT INT TERM`；先做残留探测，脏则先 reset（崩溃轮的残留也能自愈）。
- `gate_fixture_end`（幂等、可重入）：`POST /internal/demo-reset`（`X-Internal-Token`）→ 断言四件事：① 本 tag 残留 = 0；② 夹具计数 `requirement=12 / release=1 / estimate approved=11 / pending=0`；③ `DEMO-R-02` 的 rollup = 5.0 且评估单 `APPROVED`；④ `DEMO-R-04` 回到 `DEMO-REL-1`。任一不满足**打印 FAIL 并 `exit 1`**，不再降级为 WARN。
- **为什么不是「按 tag 物理删」**：`DELETE /api/req/requirements/{id}` 固定 403「需求不支持物理删除，请使用关闭动作」（`RequirementController.java:179`），物理清理由 `/internal/demo-reset` 独占，它复用 `DemoFixture.DELETE_ORDER` 的 FK 顺序与 `DEMO_RESET_INVARIANT` 事务校验。tag 的作用是**证明清理彻底 + 失败可定位**，不新增删除通道、不碰 Java。若要真按 tag 定点删除，需新增 internal 端点（见 §7）。

**3.2 去污染：夹具不挂种子需求**
- `platform-test-negative.sh`：在段 3 之前自建 `gate-<tag>-BUS → gate-<tag>-PDR`（`SCHEDULED`），`PDR_ID` 指向它；`:142/:153` 的 4b 链保持不变。原「按状态取第一条 PDR」删除，并加一条静态断言禁止回退到「取种子 PDR」。
- `platform-verify.sh`：`:259` 的 PDR 父从 `SEED_POOLED_BUS` 改为 `:248` 自建的 `BUS_ID`；`DEMO-R-01` 的 rollup 不再被门禁改写。
- `release-flow-verify.sh`：保持自建 BUS→PDR→RD；S7 对 `DEMO-R-04` 的版本归属改动由 `gate_fixture_end` 的 ④ 复位断言兜底。

**3.3 `demo-reset` 删除范围审计**
`DemoFixture.DELETE_ORDER`（`:194-244`）对 `requirement` 是 `TableReset.purge("requirement")`——**整租户全清**，因此「挂在种子需求下的非 `DEMO-*` 子需求」本就被覆盖，保留数恒为 0。结论：**不扩大删除范围**，只补显式口径——reset 报告输出「非夹具需求保留 0 条（整租户需求行全清）」，并作为断言纳入 `TOTAL_CHECKS`（74→75）。观测到的残留是「rfv 复位之后 negative 又写进去」，不是 reset 漏删。

**3.4 新增门禁 `scripts/gate-order-verify.sh`（`make gate-order-verify`）**
- 序列 A：`reset → rfv → access → access`，第二次仍 206/206；
- 序列 B：`reset → access → rfv → access`，三段全绿；
- 每步断言：rfv 44/44、access 206/206、`DEMO-R-02` rollup 5.0 且 `APPROVED`、本 tag 残留 0；
- 静态断言：三个写入门禁都 `source gate-fixture.sh`、都注册 `trap`、脚本内不存在 `platform-demo-reset.sh >/dev/null 2>&1`；`demo-reset` 含「非夹具需求保留」行。
- 共 15 条；前置检查栈健康，缺栈直接失败并提示 `make platform-up`（不跳过）。

## 4. 行为兼容影响

- **无用户可见行为变化**：不动接口、判定、种子语义、审批链与快照结构；`a7` 判据、`release-flow-verify` 44 条断言一条不放宽。
- 唯一可观察差异：`platform-demo-reset` 多一行输出（非夹具需求保留 0 条）；门禁运行时长各增加约 3–5s（退出复位）。
- 门禁必须**串行独占**演示栈（复位是全租户级）；并行跑门禁不在本轮保障范围（见 §7）。

## 5. 回归与验证计划

| 命令 | 下限 |
|---|---|
| `make gate-order-verify` | **15/15**（新增；A/B 双序列 + 静态） |
| `make release-flow-verify` | **44/44**（不变） |
| `make platform-demo-reset` | **75/75**（74+1） |
| `make access-verify` | **206/206** |
| `make platform-verify` | **≥194** |
| `make platform-test-negative` | **≥130** |
| `make rbac-verify` | **113/113** |
| `make lint` | **22/22** |
| `mvn -o -Dmaven.repo.local=$PWD/.m2/repository test` | **≥392**，0 失败 |

提交前另跑：`make access-verify` 连跑两次均 206/206；`git merge-base main HEAD..HEAD` 区间 diff 只含 `scripts/`、`Makefile`、`docs/teamflow/`，不含 `pom.xml`/`web/package.json`/lock/迁移。

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| teardown reset 让门禁变慢、且不能并行 | 只在退出跑一次；文档写明「门禁独占演示栈」；并行需求另行做 scoped cleanup 端点 |
| 栈已挂时 teardown 失败会二次报错 | `gate_fixture_end` 区分「栈不可用」与「清理未彻底」，提示先 `make platform-up`，不掩盖首次失败 |
| 改夹具父链导致原断言失效 | 父链仍满足 r23 前置门（BUS `POOLED` / PDR `SCHEDULED`），断言数不减，QA 跑全套复核 |
| 误伤演示数据 | 只改门禁脚本与 reset 输出；种子内容零改动，`DEMO-R-01..12` 计数仍 12 |

**回滚**：单次 revert（脚本 + Makefile + 文档，无迁移、无 Java、无数据结构变更），回滚后旧行为即恢复（含旧的静默清理）。

## 7. 假设与待澄清

- **清理实现选型**：选「reset + tag 残留断言」而非「按 tag 定点删除」，因为需求行无物理删除 API；若要求真正定点删除，需新增 `POST /internal/gate-fixture-cleanup`（Java + mvn 基线变化 + FK 顺序维护），影响面显著扩大。
- **共享实现位置**：新建 `scripts/lib/gate-fixture.sh` 而非塞进 `scripts/lib.sh`，避免所有脚本被动加载门禁策略；若团队偏好单一 lib，可平移，行为不变。
- **修复面**：用户点名 rfv，但只修 rfv 无法达成「任意顺序」；本单同时改 negative/verify 的父挂载。若限定只改 rfv，则 A/B 序列中 negative 段仍会红。
- **新门禁是否进默认链**：`gate-order-verify` 需 ~10–15 分钟，暂不进 `make test`，由 QA/验收显式执行；若要进 CI，需同步调整默认链时长预算。
- **S7 仍使用种子 `DEMO-R-04`**：这是演示链语义（1/1 质量门分母）要求；若要求完全不碰种子，需自造等价 RD 夹具并重写 S7 八条断言。

## 8. 技术验收（T1–T6，交 QA）

- T1 三个门禁成功/失败/中途 `set -e`/Ctrl-C 退出后，库内本 tag 残留 = 0，夹具计数 12/1/11/0。
- T2 清理失败（如栈停掉）时门禁退出码非 0 且报错指向具体断言，不再 WARN 放过。
- T3 A/B 双序列全绿，`access-verify` 连续两次均 206/206。
- T4 `DEMO-R-02` 在任意序列后 rollup=5.0、评估单 `APPROVED`；`DEMO-R-04` 在 `DEMO-REL-1`。
- T5 `demo-reset` 输出并断言「非夹具需求保留 0 条」，75/75。
- T6 各门禁计数不低于 §5 下限；diff 面不含 Java/pom/web/迁移。
