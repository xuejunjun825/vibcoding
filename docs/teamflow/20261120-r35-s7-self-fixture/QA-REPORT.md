# QA 报告 · r35 门禁夹具自足（S7 自造 BUS→PDR→RD，零读演示种子）

- 任务夹：`docs/teamflow/20261120-r35-s7-self-fixture/`（技术合同：`TECH-CHANGE.md`）
- 分支：`feat/s7-self-fixture`，HEAD `d715944`（未提交改动：本需求 4 文件 + 本任务夹）
- 判定：**可验收**，未发现缺陷（0 P0 / 0 P1 / 0 P2 / 0 P3）

## 1. 范围与环境

被测交付面（`git diff --stat`，共 4 文件、162+/78-）：

| 文件 | 改动 | 本轮契约 |
|---|---|---|
| `scripts/release-flow-verify.sh` | +152/-72 | S7 由「读种子 DEMO-R-04 + DELETE 解挂」改为自造 BUS→PDR→RD 并读回复核；新增静态 `[12]` |
| `scripts/gate-order-verify.sh` | 11 行 | 收尾 check 14 增「`title LIKE '%S7-%'` = 0」；check 15 仅措辞 |
| `scripts/lib/gate-fixture.sh` | 4 行 | 仅第 6 项注释与失败文案（「被改回」→「从未被移动」），无逻辑改动 |
| `docs/teamflow/memory.md` | +1 行 | r35 同主题记忆行（紧邻 r34 行） |

环境：本机沙箱 + 演示栈已起（`req_ready=0 deliver_ready=0 db_up=0`，`http://localhost:8080/actuator/health=200`，tenant=t1，GATE_TAG 每 run 唯一）。浏览器在 agent 沙箱内不可启动（宿主 token 限制，属环境限制，非交付缺陷）；本需求无 UI 改动，全部判据以脚本化断言执行。
说明：`mvn -o test` 必须带本机仓库参数 `-Dmaven.repo.local=$PWD/.m2/repository`，否则离线模式解析不到父 POM——这是本机 Maven 配置的既有约束（记忆与历史轮次同口径），不是本交付的缺陷。

## 2. 架构核验（轻量，随功能测试一并做）

- **蓝图一致**：架构判断要求「不新增模块、依赖方向 `lib.sh → gate-fixture.sh → 门禁` 不变」。实测 `git diff --name-only` 仅上述 4 文件，`scripts/lib.sh` 零改动、无新脚本/新目录，依赖方向未变 ⇒ 一致。
- **抽象未缺失**：S7 自造 BUS 的会签一次也没自己遍历——`gate_pool_bus "$S7_BUS"` 走的是 r34 共享实现（`scripts/lib/gate-fixture.sh:304`，内部 `gate_approve_biz` 并在结束时 `gate_login_admin` 还原 admin）。S7 区段内只有发布单/版本审批调用 `approve_biz`，那是 rfv 自己的既存实现，未新增第三份。
- **重复实现**：`scripts/release-flow-verify.sh` 的 `approve_biz` 与 `gate-fixture.sh` 的 `gate_approve_biz` 仍并存——技术合同明示「本轮不合并（会动 S2/S3 记账）」，且 S7 未再扩散该重复 ⇒ 属已登记取舍，不计缺陷。
- **未破坏既有结构**：`create_plan` 签名仍单 `caseId`（`scripts/release-flow-verify.sh:227-232`），S7 用段内内联 jq body 传 `caseIds:[$a,$b]`，S2/S4 与 `build_positive_chain` 未受影响。
- 架构结论：**未发现架构缺陷**（无 P1「架构」项）。

## 3. 接口一致性（静态核验，无运行时）

| 被调符号 | 调用点 | 定义处 | 参数形状 | 结论 |
|---|---|---|---|---|
| `gate_pool_bus <busId> [label]` | rfv `s7_fixture_ready` | `scripts/lib/gate-fixture.sh:304` | 2 实参，与形参一致 | ✅ |
| `create_req <type> <title> [parentId] [estimateDays]` | BUS/PDR/RD 三处 | rfv `create_req()` | `create_req RD "S7-RD-${GATE_TAG}" "$S7_PDR" 2` | ✅ |
| `approve_estimate <reqId> <days>` | PDR、RD 各一次 | rfv `approve_estimate()` | 均 2；内部 `as_user pm` 后 `as_admin` 复原 | ✅ |
| `act <reqId> <action>` | PDR schedule / RD start_dev、submit_test、test_pass | rfv `act()` | 动作码存在 | ✅ |
| `POST /api/req/requirements/{id}/deliver` | RD（预估/实际 2 + 本 run MR） | 既有端点 | 与 `build_positive_chain` 同字段同值 | ✅ |
| `create_case <reqId> <title>` ×2 | S7 两条自造用例 | rfv `create_case()` | 返回 id ⇒ `S7_C01/S7_C02` 供 `--argjson` | ✅ |
| `create_release / attach_req / start_release / create_report / sign_report / create_order` | `s7_build_chain` | rfv 同名函数 | 参数个数与顺序一致；`start_release` 用 `>/dev/null` 后读 `START_CODE/START_INSTANCE` | ✅ |
| S8 消费的 `S7_REL / S7_RD / S7_ORDER / S7_BUILD_OK` | `scripts/release-flow-verify.sh:1534,1584,1679` | S7 建链与执行块赋值 | 均有定义，`S7_BUILD_OK=1` 在 `:1498` 初始化 | ✅ |

`grep -rn 'S7_R04' scripts/` → 0 命中；`grep -cE '^s7_[a-z_]*\(\) \{'` → 10（与 `[05]` 静态断言同源）；`S7_MR_URL` 全文件仅 `:1197` 一处定义，被 S7-5 断言与恢复共用。

## 4. 用例与执行结果

| 编号 | 用例 | 命令 | 结果 |
|---|---|---|---|
| TC-01 | 静态契约 11→12，含新增 `[12]` | `bash scripts/release-flow-verify.sh --static-only` | rc=0，`STATIC_TOTAL=12 PASS=12 FAIL=0` |
| TC-02 | `[12]` 截取区段非空（防空段假绿） | `awk '/^section "S7 /{f=1} /^section "S8 /{f=0} f' scripts/release-flow-verify.sh \| wc -l` | 344 行（非空） |
| TC-03 | `[12]` 变异探针：注入种子引用必红 | 在 S7 段插入 `# QA MUTATION-PROBE: DEMO-R-04` 后跑 `--static-only` | rc=1，`[12] FAIL S7 区段仍引用演示种子 1 处`（11/12） |
| TC-04 | 变异还原后逐字节一致 | 删除注入行后 `git diff … \| shasum` | `0e8bd43d…` 与注入前同值；`--static-only` 回到 12/12 rc=0 |
| TC-05 | rfv 全量第 1 次 | `bash scripts/release-flow-verify.sh` | rc=0，`TOTAL=44 CASE=44 PASS=44 FAIL=0` |
| TC-06 | rfv 全量第 2 次（连跑） | 同上，紧接一次 | rc=0，44/44；退出复位「演示基线已重建」「本轮足迹 0、非夹具需求 0」 |
| TC-07 | S7 段 8 条运行期判据 | 同 TC-05 输出 `[33]–[40]` | 全 PASS：自造夹具就绪（BUS 会签 2/POOLED、RD 待测带本 run MR、2 条用例）→ TEST_PASSED（轮次 2/2、覆盖 1/1）→ 三签 3/3 → 清空 MR 409 `[MR_FILLED]` 快照数不变 → 恢复 MR 200 快照 64hex → QG-01/02/03 PASS 且 QG-01 100% → 发布单两级 APPROVED → 版本 PUBLISHED |
| TC-08 | 跑前/跑后演示种子逐字节一致 | `logs/teamflow/tf-mwoc52k1-22c0au/scripts/qa-r35-demo-invariants.sh` 跑前/每次跑后 diff | 三次 diff 均无差异（含最终一次）：`DEMO-R-04=…/r32-e2e\|2.0\|2.0\|PENDING_TEST\|DEMO-REL-1`、`DEMO-R-02=5.00\|1`、`DEMO-R-01=POOLED\|2`、`nonfix=0`、`S7left=0` |
| TC-09 | 门禁顺序无关 15 条（A/B 双序列） | `bash scripts/gate-order-verify.sh` | rc=0，`15/15`；A：reset 75/75 → rfv 44/44 → access 206/206 → access 206/206；B：reset 75/75 → access 206/206 → rfv 44/44 → access 206/206；收尾「非夹具 0 / 本 tag 0 / S7- 痕迹 0」「R-02 5.00+APPROVED、R-04 仍在 DEMO-REL-1」 |
| TC-10 | platform-verify 回归 | `bash scripts/platform-verify.sh` | rc=0，194/194 |
| TC-11 | 负向回归 | `bash scripts/platform-test-negative.sh` | rc=0，130/130 |
| TC-12 | rbac 回归 | `bash scripts/rbac-verify.sh` | rc=0，113/113（11 段下限核对） |
| TC-13 | lint | `bash scripts/tests/lint.sh` | rc=0，22/22 |
| TC-14 | Java 单测 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository test` | rc=0，BUILD SUCCESS；surefire 汇总 `tests=392 errors=0 failures=0 skipped=0` |
| TC-15 | 前端类型与构建 | `cd web && npm run typecheck` / `npm run build` | 均 rc=0（build 6.92s） |
| TC-16 | 提交卫生 | `git status --porcelain`；`git check-ignore -v target/ web/dist .run` | 仅 4 个受控改动 + 本任务夹；`target/`、`web/dist`、`.run`、`logs/`、`node_modules/`、`.env*` 均在 .gitignore 覆盖内 |

S7 断言语义未放宽（逐条对照，编号沿用代码注释）：[S7-1] 读回复核由「种子的 2 人天/2 用例/MR=r32-e2e」换成「本 run 自造的 2 人天/2 用例/MR=r35-${GATE_TAG}」，判据维度（待测状态 + MR 非空 + 实际人天 + 两轮会签 APPROVED + 净状态 POOLED + 用例数 2）一条未减；[S7-2] 的 `2|2|1`、`1/1`，[S7-3] 的 `3/3`，[S7-8]（负向，执行上先于 [S7-5]）的 `[MR_FILLED]` 与快照数不变，[S7-5] 的 64hex 与四段齐全，[S7-4] 的 QG-01/02/03 + `100%`，[S7-7] 发布单两级、[S7-8]（版本三级）的状态序列，数值与正则均与原判据一致；唯一变化是覆盖率分母不再需要「先把种子需求从种子版本摘掉」——因为自造版本天然只挂 1 条需求。
附注（继承自 AC-12 的既有注释标号，非本轮引入、不影响执行与计数）：`scripts/release-flow-verify.sh` 里 `[S7-8]` 注释出现两次（负向 MR 与版本三级），且无 `[S7-6]` 标号；本轮 diff 只改了 `[S7-1]` 的措辞。执行层面 8 条判据各计一次、总数恒 44（TC-05/06 实测）。

## 5. 人工补测清单（环境限制，非交付缺陷）

| 项 | 原因 | 方法 / 工具 |
|---|---|---|
| 演示链在前端页面的人工观感（版本详情 flowChain 卡、质量门页、发布单审批页） | 沙箱内浏览器无法启动（宿主进程 token 限制，见任务约束；本需求无 UI 改动，非缺陷） | 本机 Chrome 打开 `http://localhost:5173`，按 `docs/teamflow/DEMO-RELEASE-FLOW.md` 8 步走一遍，确认 S7 自造版本显示正常 |
| S7 夹具耗时增加（约 +10s）的体验确认 | 计时类主观项，脚本只保证正确性 | 观察 `make release-flow-verify` 墙钟时间；属预期代价 |
| 真机/音频/100dvh 等 | 本需求无相关改动面 | N/A |

## 6. 缺陷表

未发现缺陷。

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — |

## 7. 结论

- 交付行为与 `TECH-CHANGE.md` 契约一致：S7 演示链**零读取、零写入**演示种子（跑前/跑后/多门禁连跑后指纹逐字节一致），而 8 条运行期判据与 44 条总数不缩水；新增静态 `[12]` 经变异探针证明非空过。
- 门禁顺序无关性在 A/B 两条真实序列上均成立（15/15），`S7-` 痕迹与 `DEMO-R-02/DEMO-R-04` 不变量在收尾被显式断言。
- 回归面全绿：rfv 44、gate-order 15、platform-verify 194、negative 130、access 206（gate-order 内 4 次）、rbac 113、demo-reset 75（2 次）、lint 22、mvn 392/0、web typecheck+build。
- 架构与接口一致性均无缺陷；提交面仅 4 个受控文件 + 本任务夹，无构建产物/依赖目录/密钥。

**判定：可验收（acceptance-ready）。**
