# 技术变更单 · S7 演示链自造夹具（r35）

> 类型：tech-driven rework（门禁内聚性，不新增用户功能）｜分支 `feat/s7-self-fixture`｜runId `tf-mwoc52k1-22c0au`
> 任务夹：`docs/teamflow/20261120-r35-s7-self-fixture/`
> 基线依赖：`docs/teamflow/20261119-r34-gate-order-residue/`（main=`d715944`）
> 断言账：`RELEASE_FLOW_TOTAL=44` 不变（S7 仍 8 条、函数仍 10 个）；静态自检 11→12（新增 1 条）

## 1. 背景与目标

**一句话**：把 `release-flow-verify.sh` 的 S7 演示链从「读种子 `DEMO-R-04` + `DEMO-REL-1` + 种子用例」改成「用 r34 共享钩子自造一条等价 RD」，使该门禁对演示种子零读零写，而 44 条断言与 S7 八条判据的强度一字不放宽。

现象（main=`d715944`，r34 之后）：
- `scripts/release-flow-verify.sh:1196` 直接按 code 取种子：`db_q "SELECT id FROM requirement ... AND code='DEMO-R-04'"`；`:1197-1205` 还断言 `DEMO-R-04`（PENDING_TEST / `mr_url` / `actual_days≥2`）与 `DEMO-R-01`（POOLED + 2 条 APPROVED 会签）。
- `:1219` 为让 QG-01 不看种子版本里另外 10 条需求，S7 先用 `DELETE /api/req/releases/${owner}/requirements/${S7_R04}` 把 R-04 从 `DEMO-REL-1` 解挂——**门禁在跑的过程中改写了种子**；`:1237-1239` 再读种子用例 `DEMO-C-03/04`。
- `scripts/lib/gate-fixture.sh` 的 `gate_fixture_problems()` 第 6 项注释写着「rfv 的 S7 会把版本改来改去」——把「S7 会动种子」当成了契约的一部分。

结论：种子一旦改数、改名或被清理，S7 必红；S7 也污染了「种子在门禁运行期间不变」这一隐含不变量。r34 已让三个写门禁自建父链，**S7 是最后一条种子绑定链**。

## 2. 影响面

| 文件 | 改动 |
|---|---|
| `scripts/release-flow-verify.sh` | S7 段重写：`s7_fixture_ready` 变为「造夹具 + 复核」；`s7_build_chain` 删去解挂种子版本、改用自造用例；`S7_R04`→`S7_RD`（17 处，含 S8-1/S8-2）；新增静态检查 `[12]` |
| `scripts/gate-order-verify.sh` | 收尾断言 14 扩为「非夹具需求 0 + 本轮 tag 残留 0 + `S7-` 痕迹 0」，仍 15 条 |
| `scripts/lib/gate-fixture.sh` | 仅注释：第 6 项由「S7 会改版本」改为「S7 不碰 R-04 的证据」；判据与签名不动 |
| `docs/teamflow/memory.md` | 追加 r35 一行（同主题、幂等） |

不改：Java 代码、`pom.xml`、`web/*`、任何数据库迁移、种子数据内容、其余门禁脚本、S7 八条判据的数值/正则/阈值。

## 3. 技术方案要点

**3.1 自造夹具（`s7_fixture_ready`：造 + 验，函数仍 10 个、断言仍 8 条）**

在 `s7_reset` 成功之后、建链之前，用 r34 的共享钩子造一条与本轮 `GATE_TAG` 同生的
`BUS → PDR → RD` 链（标题统一 `S7-<角色>-${GATE_TAG}`，MR 为 `https://git.example.com/mr/r35-${GATE_TAG}`）：

```bash
S7_BUS=$(create_req BUS "S7-BUS-${GATE_TAG}")
gate_pool_bus "$S7_BUS" "S7 父链"          # submit→REQ_SUBMIT 会签→review_start→REQ_REVIEW 会签→断言 POOLED
S7_PDR=$(create_req PDR "S7-PDR-${GATE_TAG}" "$S7_BUS")
approve_estimate "$S7_PDR" 2 && act "$S7_PDR" schedule
S7_RD=$(create_req RD "S7-RD-${GATE_TAG}" "$S7_PDR" 2)
deliver {"estimateDays":2,"actualDays":2,"mrUrl":"https://git.example.com/mr/r35-${GATE_TAG}"}   # 必须 200
approve_estimate "$S7_RD" 2 && act "$S7_RD" start_dev && act "$S7_RD" submit_test
S7_C01=$(create_case ...) ; S7_C02=$(create_case ...)     # 两条落在 S7_RD 上
```

然后 S7-1 从库里读回同一批事实做断言（不是「调用成功即通过」）。造夹具失败时 S7-1 直接红，
执行块改为 `if s7_fixture_ready && s7_build_chain; then`，后 7 条按现有 `S7_BUILD_OK=0` 口径统一置红——不改失败记账方式。

**3.2 建链只造「版本后半段」**

`create_release "S7-版本-${GATE_TAG}"` → 挂 `S7_RD`（不再 `DELETE` 解挂：本版本天然只有这 1 条需求，QG-01 仍 1/1=100%）
→ `create_plan` 用 `S7_C01/S7_C02`（不再读 `DEMO-C-03/04`）→ `start_release` → 计划送审/批准/开始/执行两条
→ `act test_pass` → round 关闭 → 计划完成 → 报告 `S7-报告-${GATE_TAG}` + qa1/pm/tl1 三签 → `create_order`。
S8-1/S8-2 只改引用变量，判据与取数路径不动。

**3.3 断言措辞对照（原文 → 新文 → 为什么等价）**

| # | 原文（种子口径） | 新文（自造口径） | 为什么等价 |
|---|---|---|---|
| S7-1 | 演示夹具就绪：R-04 待测且带 MR/实际人天；R-01 两级会签均 APPROVED 且净状态 POOLED | S7 自造夹具就绪：自造 RD 待测且带本 run MR/实际人天、2 条自造用例；自造 BUS 两级会签 APPROVED 且 POOLED | 判据集合不变（`PENDING_TEST`/`mr_url`/`actual_days≥2`/2 条 APPROVED 实例/`POOLED`），只换对象；「2 条用例属本 RD」把原先隐含在种子用例里的前置显式化，只收紧 |
| S7-2 | 版本测试：需求 TEST_PASSED，round `2\|2\|1`，覆盖率 `1/1` | 自造 RD TEST_PASSED，round `2\|2\|1`，覆盖率 `1/1` | 数字与取数 SQL 全不变，仅断言对象由种子换自造 |
| S7-3 | 报告三签：`flowChain.signoff` 3/3 | 同左（对象为自造 RD 的版本） | 判据一字未动 |
| S7-4 | 负向 MR：清空 MR 后提交 409 且 failed 恰为 `[MR_FILLED]`，快照数不增 | 同左；仅失败提示里的 `R-04` 改为「自造 RD」 | 判据不变（409 / 集合恰等 / 计数不变），文案不是判据 |
| S7-5 | 提交成功：200、`flowSnapshot.round==1`、`payloadHash` ^[0-9a-f]{64}$、history≥1、`flowChain` 四段 | 同左（自造 RD 的版本） | 判据不变 |
| S7-6 | 质量门 QG-01/02/03 PASS 且 QG-01 actual 含 `100%` | 同左；失败提示「版本只挂 1 条自造 RD」替代「只挂 R-04」 | 判据不变；1/1 与 1/11 的差别正是本轮要消灭的种子耦合 |
| S7-7 | 发布单两级：2 级、n2 有任务且 APPROVED、seq=`APPROVED,APPROVED` | 同左 | 判据不变 |
| S7-8 | 版本三级：LOCKED→PUBLISHED、`releasedAt` 非空、3 级 | 同左 | 判据不变 |

**3.4 静态契约（机器判据，替代口头约定）**

- 新增 `[12]`：`awk '/^section "S7 /,/^section "S8 /'` 取出 S7 区段，`grep -cE 'DEMO-R-|DEMO-C-|DEMO-REL-'` 必须为 0
  → 「S7 区段零种子引用（自造夹具）」。`RELEASE_FLOW_STATIC_TOTAL` 由脚本自算：11→12，仍要求 `PASS==TOTAL` 且 exit 0。
- 保持不动：`[05] RELEASE_FLOW_TOTAL=44`、`[06] section==8`、`[07] s7_ 函数==10`、`[11]` 钩子形状
  （`lib/gate-fixture.sh` + `gate_fixture_begin`、无 `platform-demo-reset.sh >/dev/null`、无 WARN 降级、无 `trap cleanup EXIT`）。

**3.5 收尾不变量（`gate-order-verify` 仍 15 条）**

check 14 的 SQL 扩为三个子判据：非夹具需求 `NOT LIKE 'DEMO-R-%'` == 0、`title LIKE %GATE_TAG%` == 0、
`title LIKE '%S7-%'` == 0；check 15 不动（`DEMO-R-02` = `5.00|1`、`DEMO-R-04` ∈ `DEMO-REL-1`）。
A 序列（reset→rfv→access→access）与 B 序列（reset→access→rfv→access）必须继续全绿——S7 不再碰 R-04 后，
check 15 由「期望被改回」变成「从未被改」的证据。

## 4. 行为兼容影响

- **用户可见：无**。API/DB/页面/快照结构零改动，无新迁移（仍无 V15），不新增用户可见验收项，也不改写任何历史 PRD 的 AC。
- 开发可见：S7 运行期间种子不再被临时解挂；S7 耗时 +约 10s（多约 15 次调用与 2 轮会签）。
- 快照与审计：S7 的版本/发布单/报告全部落在自造 RD 上，仍由 `gate_fixture_*` 的 EXIT 钩子整租户复位；清理失败照旧置红，禁止 WARN。
- **AC 口径**：本需求为 tech-driven rework，**不新增、不改写任何功能性 AC**；验收 = §5 的判据 + §3.3 的逐条等价说明 + §3.4/§3.5 的静态与收尾契约。

## 5. 回归与验证计划

**命令与下限**（任一低于下限即判红）：
1. `bash -n` 两个脚本；`bash scripts/release-flow-verify.sh --static-only` → `STATIC_TOTAL=12 PASS=12 FAIL=0`、exit 0。
2. `bash scripts/release-flow-verify.sh` → `RELEASE_FLOW_TOTAL=44 CASE=44 PASS=44 FAIL=0`；`make gate-order-verify` → 15/15（A/B 均绿）。
3. `platform-verify` ≥194、`platform-test-negative` ≥130、`access-verify` 206、`rbac-verify` 113、`platform-demo-reset` 75。
4. `mvn -o -Dmaven.repo.local=$PWD/.m2/repository test` ≥392、0 失败；`bash scripts/tests/lint.sh` 22；`cd web && npm run typecheck && npm run build` exit 0。

附加（本轮特有，QA 必须做）：**连跑两次 rfv**，两次都 44/44；跑前跑后 `DEMO-R-04` 的 `release=DEMO-REL-1`、
`mr_url`/`actual_days` 与跑前逐字节一致，`DEMO-R-02` 汇总仍 `5.00`；`grep -c 'DEMO-' ` 在 S7 区段为 0。
下限只增不减，任一低于上表即判红。

## 6. 风险与回滚

- **R1** 夹具链多两轮会签：`gate_pool_bus` 失败则 S7-1 红并短路后 7 条，属真实失败，只修因不放宽。
- **R2** PDR 预估 2 / 子 RD 2.0：若后续把 RD 人天改成别的值而不动 PDR 预估值，20% 漂移会让 `schedule` 或后续提交 409——夹具固定 2/2，改动须同批改 PDR。
- **R3** 用例必须恰 2 条：计划 `STRICT` 全覆盖，S7-2 断言 `2|2|1` 与覆盖率 `1/1` 依赖它；造用例失败直接红，不跳过。
- **R4** 改名 `S7_R04`→`S7_RD` 漏改 S8 引用：`bash -n` 查不出（未绑定变量），由 S8-1/S8-2 的 `-z` 守卫置红——提交前 grep 计数应为 0。
- **回滚**：单提交 `git revert`（3 个脚本 + memory 一行）；无迁移、无快照结构变更、种子从未被改写，故无需数据复位。

## 7. 假设与待澄清

1. **造夹具塞进 `s7_fixture_ready` 还是新加 `s7_build_fixture`**：选前者（保 10 函数、静态 `[07]` 与「44 条」账一字不动）。若要求职责分离，仅 `[07]` 计数与文案（10→11）+ 执行块一行改动，判据不变。
2. **是否加第 9 条运行期断言「DEMO-R-04 前后不变」**：未加，改用静态 `[12]`——「不命名即不可能写」比运行期采样更强。若坚持运行期证据，需同步 5 处：`RELEASE_FLOW_TOTAL` 44→45、头部「44 条」、footer「44 条」、分段计数、`gate-order-verify` 里 `RELEASE_FLOW_TOTAL=44` 的精确匹配行。
3. **`S7_R04`→`S7_RD` 波及 S8 共 17 处**：选改名（可读性）。若要求最小 diff 可保留旧名，语义与判据无差。
4. **「S7 痕迹 0」做成 check 14 的子判据还是新第 16 条**：选前者（15 条不变，memory 与 r34 文档口径不改）。若要求独立编号，`gate-order-verify` 15→16，新文档需注明口径变更（历史文档不改）。
5. **边界**：`DOMAIN_ID`/`MODULE_ID`/租户 `t1` 等**字典与租户种子**仍在用——本轮只要求脱离「演示业务数据（`DEMO-R-*`/`DEMO-C-*`/`DEMO-REL-*`）」。若连字典也要自造，所有门禁都得重建字典，超出本轮范围。
