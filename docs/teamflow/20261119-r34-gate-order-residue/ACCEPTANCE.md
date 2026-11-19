# 验收报告 · r34 门禁顺序无关化（gate-order-residue）

> 验收人：产品经理（验收负责人）｜任务夹：`docs/teamflow/20261119-r34-gate-order-residue/`
> 分支 `feat/gate-order-residue`｜基线 HEAD `4d9f5b6`（main）｜runId `tf-mwnefs38-150e8l`｜验收日期 2026-11-19
> 验收依据：`TECH-CHANGE.md` §8 技术验收 T1–T6 + 注入的架构蓝图（M3 结构一致性核对）

## 1. 验收范围

- 交付面：**2 新增**（`scripts/lib/gate-fixture.sh`、`scripts/gate-order-verify.sh`）+ **6 修改**（`release-flow-verify.sh`、`platform-verify.sh`、`platform-test-negative.sh`、`platform-demo-reset.sh`、`scripts/tests/lint.sh`、`Makefile`）+ `docs/teamflow/memory.md` 同主题约定行。
- 明确不含：`req-service`/`deliver-service` Java、`pom.xml`、`web/*`、任何迁移、种子数据内容（`git diff --name-only HEAD` 实测 0 命中）。
- 本轮为 tech-driven rework（不新增用户功能），验收以「任意门禁顺序跑完与先 reset 等价」为唯一核心主张。

## 2. 逐条判据核对（T1–T6）

| 判据 | 判据内容 | 验收证据（本人实跑 / QA 记录） | 结论 |
|---|---|---|---|
| T1 | 门禁成功/失败/中途 `set -e`/Ctrl-C 退出后，本 tag 残留=0、夹具计数 12/1/11/0 | `bash scripts/gate-order-verify.sh` → **15/15**，每步均打印「基线干净」；`qa-r34-teardown-red.sh` **8/8**（成功/失败/rc=3 保留/SIGINT→130 且仍复位）；`qa-r34-fixture-contract.sh` **6/6**；收尾「本轮足迹 0、非夹具需求 0、演示不变量成立」 | 通过 |
| T2 | 清理失败时退出码非 0 且报错指向具体断言，不再 WARN 放过 | 隔离桩 8/8：「复位失败 → `gate_fixture_end` 返回 1」「本体失败 rc=3 被保留，不把红变绿」「SIGINT 后仍执行复位」；`platform-test-negative.sh` 内已无 `>/dev/null` 静默 reset（grep 0 命中） | 通过 |
| T3 | A/B 双序列全绿，`access-verify` 连跑两次均 206/206 | 实测 A：`reset 75/75 → rfv 44/44 → access 206/206 → access 206/206`；B：`reset 75/75 → access 206/206 → rfv 44/44 → access 206/206`；另加序列 C：`verify 194/194 → negative 130/130` 各自干净收尾 | 通过 |
| T4 | 任意序列后 `DEMO-R-02` rollup=5.00 且评估单 `APPROVED`；`DEMO-R-04` 在 `DEMO-REL-1` | gate-order 收尾两项断言原文：「`DEMO-R-02` 汇总 5.00 + 评估单 APPROVED、`DEMO-R-04` 回 `DEMO-REL-1`」 | 通过 |
| T5 | `demo-reset` 输出并断言「非夹具需求保留 0 条」，75/75 | `TOTAL_CHECKS=75`（`platform-demo-reset.sh:40`）；`:321-324` 新增 `NON_FIXTURE_REQS = db_count(requirement, code NOT LIKE 'DEMO-R-%') == 0` 断言；A1/B1 与每次退出复位均 `reset 75/75` | 通过 |
| T6 | 各门禁计数不低于下限；diff 面不含 Java/pom/web/迁移 | 本人实跑：lint **22/22**、rfv `--static-only` **11/11**、rfv **44/44**（A2/B3）、access **206/206**×4、verify **194/194**、negative **130/130**、demo-reset **75/75**；QA 记录 rbac **113/113**、mvn **392/0**（本 diff 无 Java，等价树）。`git diff --stat`：仅 `scripts/`×6 + `Makefile` + `memory.md` | 通过 |

## 3. 架构一致性核对（M3）

- **蓝图模块落地**：`scripts/lib/gate-fixture.sh` 为复位/基线/夹具原语唯一实现，9 个公开函数齐备——`gate_fixture_problems:76` / `reset:138` / `assert_clean:152` / `end:200` / `begin:205` / `login_as:236` / `login_admin:248` / `approve_biz:254` / `pool_bus:304`。
- **三门口禁只声明使用**：`release-flow-verify.sh:45` source + `:610` begin；`platform-verify.sh:12` + `:130`；`platform-test-negative.sh:15` + `:52`；新门禁 `gate-order-verify.sh:19` + `:59`。
- **禁止静默 reset**：三门口禁内 `platform-demo-reset.sh >/dev/null` 与 WARN 降级均 0 命中；rfv 本地 `cleanup()` 已删除、由唯一 trap 接管（`:333` 注释说明）。
- **单一基线定义点**：12/1/11/0 与计数口径仍只在 `platform-demo-reset.sh`；钩子只断言其覆盖不到的 tag 足迹与跨门禁不变量，未另立口径。
- **去污染（父链自建）**：negative `:111` 建 `gate-${GATE_TAG}-BUS` → `:115` `gate_pool_bus` 池化 → `:118` 建 PDR → `:124` 批评估；verify `:258` `BUS_ID` → `:265` 池化 → `:267` PDR 父指向自建 BUS。种子 PDR 不再是任何门禁的夹具父。
- **新门禁结构**：15 条 = 1 栈前置 + 4 静态契约 + 8 序列 + 2 收尾；静态契约 4 项含「接入共享钩子」「无静默 reset/WARN」「demo-reset 单点定义 75」「make 目标可发现」。
- **偏差评估**：rfv 的 `cleanup` 为删除而非薄封装（正确——钩子是唯一 trap，封装会成第二个 trap，行为由 8/8 隔离桩证明）；`approve_est` 未移位（定义本就在首次使用之前，新链改用 `gate_approve_biz`）。均为实现细节，不偏离蓝图，不构成返工。
- **结论**：无重复实现漂移、无该抽取未抽取、无既有结构破坏 → 架构通过。

## 4. 意见与遗留（与 TECH-CHANGE §7 一致，均为已声明债，非本轮缺陷）

- rfv 自有 `approve_biz`/池化链保留第二份实现（44 条断言依赖，后续薄别名化）。
- `wbs-backfill-verify.sh` 自有 trap 未纳入共享钩子（本轮范围外）。
- lint 文件枚举仍为显式 glob（`scripts/**/*.sh` 递归枚举记为后续债）；`scripts/lib.sh` 与 `scripts/lib/` 并存为命名债，memory 已注明语义。
- `scripts/collab-perf.sh:72` 仍「按状态取种子 PDR」（手工性能脚本，不建子需求、不在门禁范围）；`platform-test-negative.sh:205` 的 `LEGAL_PDR` 是「改挂到另一合法 PDR」的正向回归读，随即改回 + 退出复位，不构成夹具挂载。
- 门禁必须串行独占演示栈（复位为租户级）——已写入 memory 与 Makefile 帮助，属设计约束而非缺陷。
- 工作树未提交（7 M + 2 新 + 任务夹），按流水线惯例由 host 收口；T1–T6 均为工作树实测，提交不改变判据。

## 5. 人工补测

- 本交付无 UI 改动、无截图项；浏览器视觉复核不适用（QA 已记录沙箱 Chromium 限制）。
- 「真实长跑门禁中途 Ctrl-C」未在 4 分钟序列上手按，但隔离桩已覆盖 SIGINT→130 且仍复位（8/8），风险低，不影响验收。

## 6. 结论

核心主张（门禁顺序无关）由 A/B 双序列 15/15 独立复跑实证；六条技术判据全部有可执行证据；架构与蓝图一致，遗留债均已在技术单中声明。交付可验收，无返工项。

验收结论：✅ 通过
