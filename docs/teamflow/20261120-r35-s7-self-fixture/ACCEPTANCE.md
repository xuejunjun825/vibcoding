# 验收报告 · r35 门禁夹具自足（S7 自造 BUS→PDR→RD，零读演示种子）

> 验收人：产品经理（验收负责人）｜任务夹：`docs/teamflow/20261120-r35-s7-self-fixture/`
> 分支 `feat/s7-self-fixture`｜HEAD `d715944`｜runId `tf-mwoc52k1-22c0au`｜验收日期 2026-11-20
> 验收依据：`TECH-CHANGE.md` §3.3（八条判据等价）、§3.4（静态 [12]）、§3.5（收尾不变量）、§5（回归下限）+ 注入的架构蓝图（M3 结构一致性核对）
> 说明：本 run 为 tech 模式（`meta.json` `mode=tech`，任务夹无 PRD.md）；技术单 §4 明示「不新增、不改写任何功能性 AC」，故逐条判据即技术合同条款，另加架构一致性与卫生面。

## 1. 验收范围

- 交付面：4 文件、162+/78-——`scripts/release-flow-verify.sh`（S7 自造夹具 + 静态 [12]）、`scripts/gate-order-verify.sh`（check 14 增 S7 痕迹）、`scripts/lib/gate-fixture.sh`（仅第 6 项注释/文案 4 行）、`docs/teamflow/memory.md`（r35 同主题 1 行）。
- 明确不含：Java、`pom.xml`、`web/*`、迁移、种子数据内容、其他门禁——`git diff --name-only` 实测仅上述 4 文件，`scripts/lib.sh` 零改动。
- 验收以「S7 零读零写演示种子，而 44 条断言与 S7 八条判据不缩水」为核心主张；本阶段独立复跑 rfv／静态／gate-order／platform-verify／lint，其余下限核对 QA 实测记录。

## 2. 逐条判据核对

| 编号 | 判据 | 验收证据（本人实跑，除注明 QA 外） | 结论 |
|---|---|---|---|
| A-1 | S7 自造 BUS→PDR→RD 并读回复核：MR=本 run、预估/实际 2、两轮会签 APPROVED 且 POOLED、2 条自造用例 | `bash scripts/release-flow-verify.sh` → **[33] PASS**「自造 RD 待测且带本 run MR/实际人天、2 条自造用例；自造 BUS 两级会签均 APPROVED 且净状态 POOLED」；代码 `scripts/release-flow-verify.sh:1224` 只调 `gate_pool_bus "$S7_BUS"`，区段内 `approve_biz|gate_approve_biz` 计数 **0**（未抄第三份会签） | 通过 |
| A-2 | S7 八条判据数值/正则一字不放宽，`RELEASE_FLOW_TOTAL` 仍 44 | 同次全量 **[33]–[40] 8/8 PASS**、`TOTAL=44 CASE=44 PASS=44 FAIL=0`、exit 0；关键值保持：[36] `failedRules` 恰 `[MR_FILLED]` 且快照数不变、[37] `payloadHash` 64hex、[38] QG-01/02/03 PASS 且覆盖率 `100%`（1/1）、[39] 两级 `APPROVED,APPROVED`、[40] `LOCKED→PUBLISHED` 三级；`grep -cE '^s7_[a-z_]*\(\) \{'`=10、`^RELEASE_FLOW_TOTAL=`=44 | 通过 |
| A-3 | 静态契约 11→12，新增 [12]「S7 区段零 `DEMO-R-/DEMO-C-/DEMO-REL-`」且非空过 | `--static-only` → **12/12 PASS，exit 0**（含 `[12] S7 区段零演示种子`）；截取区段 `wc -l`=**344** 行非空、命中 **0**；QA 变异探针注入 `DEMO-R-04` 后 **11/12 rc=1**、还原后逐字节一致回到 12/12 | 通过 |
| A-4 | 收尾 check 14 增 S7 痕迹、条数仍 15；A/B 双序列全绿 | `bash scripts/gate-order-verify.sh` → **15/15、exit 0**；A：reset 75/75 → rfv 44/44 → access 206/206 → access 206/206；B：reset 75/75 → access 206/206 → rfv 44/44 → access 206/206；收尾「非夹具 0 / 本 tag 0 / **S7- 痕迹 0**」 | 通过 |
| A-5 | 演示种子零读零写：S7 不再移动 `DEMO-R-04`，`DEMO-R-02` 汇总不漂 | 本阶段每次跑后查库：`DEMO-R-04` = `r32-e2e｜2.0｜PENDING_TEST｜DEMO-REL-1`（与跑前同），`DEMO-R-02` 子需求汇总 `5.0` 且评估单 APPROVED 1 张，非夹具需求 0、`title LIKE '%S7-%'` 0；rfv 退出自检「演示不变量成立」；`S7_R04` 全树残留 0 | 通过 |
| A-6 | `gate-fixture.sh` 仅注释/失败文案，判据与签名不动 | `git diff` 该文件 **4 行**：第 6 项注释「被改回」→「从未被移动」、失败文案「未回到」→「不在」；SQL/阈值/函数签名零变化 | 通过 |
| A-7 | memory 追加 r35 同主题行、紧邻 r34、不写 changelog、AGENTS 不动 | `memory.md` 第 41 行 r34、第 42 行 r35 相邻；`git status AGENTS.md` 0 命中 | 通过 |
| A-8 | 回归下限：platform-verify ≥194 / negative ≥130 / access 206 / rbac 113 / demo-reset 75 / mvn ≥392 / lint 22 / web 构建 | 本人实跑：rfv **44/44**、静态 **12/12**、gate-order **15/15**（内嵌 reset **75×2**、access **206×4**）、platform-verify **194/194**、lint **22/22**；QA 记录：negative **130/130**、rbac **113/113**、mvn **392/0**、web typecheck+build 绿；Java/web 零 diff，mvn 与上轮同树 | 通过 |
| A-9 | 架构一致性（M3）：不新增模块、依赖方向 `lib.sh→gate-fixture.sh→门禁` 不变、无新重复实现 | `git diff --name-only` 无 `scripts/lib.sh`、无新脚本/目录；S7 复用共享 `gate_pool_bus`（内部 `gate_approve_biz`）；`create_plan` 仍单 `caseId`（`:227-232`），S7 用段内内联 `caseIds:[$a,$b]`（`:1323`），S2/S4 与 `build_positive_chain` 未动 | 通过 |
| A-10 | 卫生与边界：无 pom/web/迁移/构建产物/AGENTS 改动 | `git status --porcelain`：仅 4 个受控 M + 本任务夹；target/node_modules/dist/.env 命中 0；未提交按 r34 惯例由 host 收口 | 通过 |

## 3. 意见与遗留

- **未提交（惯例）**：工作树 4 M + 任务夹，按 r34 先例由 host 收口；交付前请以单提交落盘，提交信息含 `runId=tf-mwoc52k1-22c0au` 与任务夹路径（技术单 §6 的回滚即单提交 `git revert`）。
- **已声明债（非本轮缺陷）**：① `release-flow-verify.sh` 的 `approve_biz` 与 `gate-fixture.sh` 的 `gate_approve_biz` 双实现并存，技术单 §2 明示本轮不合并（会动 S2/S3 记账），S7 未扩散；② `[S7-8]` 注释标号重复且无 `[S7-6]`，属 AC-12 遗留标号，执行计 8 条、总数恒 44（实测）；③ S7 自造夹具使 rfv 约 +10s，为技术单预期代价。
- **任务夹无 PRD.md**：本 run 为 tech 模式，技术单 §4 已声明「无功能性 AC 变更」，故以技术合同为验收依据；若后续补 PRD，应在头部声明基线依赖，不改写本报告与历史任务夹。
- **人工补测**：本需求无 UI 改动；浏览器在 agent 沙箱不可启动（宿主限制，QA 已记录），视觉复核不适用。前端页面观感可留待本机按 `DEMO-RELEASE-FLOW.md` 8 步抽查，不构成验收条件。

## 4. 结论

核心主张由本阶段独立复跑实证：S7 完全自造 BUS→PDR→RD（复用 r34 共享审批原语），零读零写演示种子（`DEMO-R-04` 从未被移动、`DEMO-R-02` 5.00 不漂），而八条判据与 44 条总数一字未减；静态 [12] 与收尾 S7 痕迹把「无种子引用」变成机器判据，静态 11→12、gate-order 仍 15；回归面全绿、架构与蓝图一致、改动面仅 4 文件。无返工项。

验收结论：✅ 通过
