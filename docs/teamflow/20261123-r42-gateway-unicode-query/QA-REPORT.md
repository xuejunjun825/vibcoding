# QA-REPORT · r42 网关非 ASCII 查询（P0）修复

- 任务夹：`docs/teamflow/20261123-r42-gateway-unicode-query/`（runId=tf-mwt98mh0-kh9wzm）
- 分支：`feat/gateway-unicode-query`（HEAD `d2ad988` + 未提交改动）
- **结论：可验收（acceptance-ready）**——R1–R4 全部落地并经真栈证明；无 P0/P1/P2；2 条 P3 观察（不影响验收）。

## 1. 范围与环境

- 交付面：`scripts/lib.sh`（指纹判定单点）、`scripts/platform-up.sh`（构建后重跑 start_app）、`scripts/platform-verify.sh`（AC-21×5，194→199）、`scripts/platform-test-negative.sh`（§18×2，130→132）、`docs/teamflow/memory.md`；另 2 个声明外文件见 P3-2。`git diff --name-only` 无 `api-gateway/` 命中，**网关与业务零改动**符合 PRD。
- 环境：macOS（Darwin stat）+ JDK 25；真栈 Nacos 8848 / 网关 8080 / demo 8081 / req 8082 / deliver 8083 / MySQL；`make platform-up` 起栈。

## 2. 用例与结果（12 组，0 fail / 0 blocked）

| 编号 | 用例组 | 命令 | 断言与实测 | 结论 |
|---|---|---|---|---|
| Q1 | 正向门禁 | `bash scripts/platform-verify.sh` | exit 0，**199/199**（AC-1..AC-21），AC-21 5/5；退出复位 demo-reset 通过、足迹 0 | PASS |
| Q2 | 正向门禁复跑 | 同上 | exit 0，**199/199**，计数不漂移 | PASS |
| Q3 | 负向门禁 | `bash scripts/platform-test-negative.sh` | exit 0，**132/132**；§18 逐字节一致（采购 · 782B==782B）、total 同源（2==2） | PASS |
| Q4 | R1 自愈（真栈） | `bash logs/teamflow/tf-mwt98mh0-kh9wzm/scripts/t1-jar-fingerprint-selfheal.sh` | exit 0，**16/16**：缺 fp→重启补写（pid 14901→46714）、一致→复用、mtime 变→自愈（46714→46891）、stop 清理后重建 | PASS |
| Q5 | lint | `bash scripts/tests/lint.sh` | exit 0，**24/24**（L1..L22+2） | PASS |
| Q6 | 语法 | `bash -n` 6 个改动脚本 | 全部通过 | PASS |
| Q7 | 孤儿门禁（未接 Makefile） | `bash scripts/tests/platform/scripts-contract.sh` | **58/58**（仅参考，不影响本轮判据） | PASS |
| Q8 | s7 集成回归 | `bash scripts/tests/integration/s7-regression.sh` | **10/10**（q4 39/39、12s 桩零外呼） | PASS |
| Q9 | s7 窗口语义 | `bash logs/teamflow/tf-mwt98mh0-kh9wzm/scripts/s7-idle-window-check.sh` | **2/2**（零请求整段流过；出现外呼立即早退） | PASS |
| Q10 | 端到端中文查询 | 登录 t1/admin 后分别打 8080 与 8082（keyword=采购） | 200/200，total 2==2，**783B==783B** | PASS |
| Q11 | 跨模块接口一致性 | grep/Read（明细见 §4.3） | 8/8 成员存在且参数形状一致 | PASS |
| Q12 | 提交卫生 | `git status --porcelain` + `git check-ignore` | 仅 7 个脚本/文档改动 + 任务夹；`target/`、`.run/`、`logs/`、`.m2/`、`web/node_modules/` 均被忽略，无根 node_modules | PASS |

## 3. AC-21 五条口径与实测（`scripts/platform-verify.sh:1189-1284`）

| # | 断言（脚本口径） | 实测 |
|---|---|---|
| ① | keyword = 池中标题前 2 个汉字（动态派生，禁写死），经 8080 → 200 且 total≥1 | 「验证」（取自「验证-准入正向RD-210753」）→ 200，total=10 |
| ② | 中英混排 + `page=1&size=5&status=ALL` → 200 | 「验证RD」→ 200 |
| ③ | 单字 / 多字 / 含空格(%20) 三连 → 全 200 | 「验」「验证准」「中 文」→ 200 200 200 |
| ④ | `.run/api-gateway.jar.fp` == `file_fingerprint "$(app_jar api-gateway)"` | 两侧均 `12530813:72057350:1795438905` |
| ⑤ | 中文 path 段 ∈ {400,404} 且非 000 | 400 VALIDATION_FAILED（网关未断连） |

**口径记录**：③ 的三次调用合并计 1 条（与 TECH §三 的「5 条」一致），**未**采用 TECH §7-3 的 197 合并口径；194+5=199，与 `memory.md` 新下限一致。

## 4. 架构核查（M3）

### 4.1 蓝图一致性

| 蓝图要求 | 实现 | 判定 |
|---|---|---|
| 指纹判定单点放 `start_app`，覆盖 dev-up 与手工起栈 | `scripts/lib.sh:330` file_fingerprint、`:350` start_app；dev-up.sh:143/145 与 platform-up.sh:77/79/108/126 全部经 start_app | 符合 |
| platform-up 构建后只重跑 start_app，禁止另写 stat/cmp/kill | `scripts/platform-up.sh:72-80` 仅两对 start_app+wait_app_health；grep 无 stat/cmp/kill | 符合 |
| jar 解析唯一入口 `app_jar` | `scripts/platform-verify.sh:1261` 用 `app_jar`；无第二处 find | 符合 |
| AC-21 5 条、keyword 从池中派生、`han_prefix/urlenc` 只在 verify | `platform-verify.sh:1189-1284`；两助手仅此一处；断言无写死种子文案 | 符合 |
| 负向复用 `api_call/api_direct` 同一组头，直连去 `/api/req` 前缀 | `platform-test-negative.sh:1029-1043`（HDR18 三头共用；直连 `/requirements`） | 符合 |
| 指纹独立 `.run/<name>.jar.fp`，不塞 pid | `lib.sh:353/402`；pid 文件仍纯整数 | 符合 |
| 网关与业务零改动 | `git diff --name-only` 无 `api-gateway/` | 符合 |

### 4.2 重复实现与抽象

未发现重复：指纹逻辑 1 处、jar 解析 1 处、`urlenc/han_prefix` 1 处；`section/pass/fail` 两份为历史现状（蓝图明确接受）；未新增安全/存储/适配层包装器。`stub_idle_window` 为单点新增，无平行实现。

### 4.3 跨模块调用一致性（静态，无需运行）

- `file_fingerprint(path)` `scripts/lib.sh:330` ← verify:1262（1 参）
- `start_app(name,port,module)` `scripts/lib.sh:350` ← dev-up:143/145、platform-up:77/79/108/126（均 3 参）
- `stop_app(name)` `scripts/lib.sh:397` ← dev-down:49/51、platform-down:9/10、platform-up:19/20（1 参）
- `app_jar(module)` `scripts/lib.sh:308` ← verify:1261
- `wait_app_health(name,port)` `scripts/lib.sh:151` ← 全部 2 参
- `api_direct(method,base,path,body,headers...)` `scripts/lib.sh:487` ← negative:1040（5 参）
- `api_call(method,path,body)` `scripts/lib.sh:478` ← negative:1038（3 参）
- `db_q(sql)` `scripts/lib.sh:435` ← negative:1017（1 参）

结论：无「成员不存在 / 参数错位」类 P1。

## 5. 主路径执行证据（不是存在性检查）

`platform-verify` 的 AC-21 ① 是一次真实 HTTP：8080 → StripPrefix 后转发 req-service → MySQL LIKE → 200 + `total=10`；Q10 另证 8080 与直连 8082 响应体逐字节相同（783B==783B）。即「中文查询经网关可用」与「网关只透传」都有执行证据。

## 6. 提交面卫生

`git status --porcelain`：`M docs/teamflow/memory.md`、`M scripts/{access-verify,lib,platform-test-negative,platform-up,platform-verify}.sh`、`M scripts/tests/integration/s7-regression.sh`、`?? docs/teamflow/20261123-r42-gateway-unicode-query/`。无构建产物、依赖目录、IDE 文件或本地密钥；`.gitignore` 覆盖 `target/`、`.run/`、`logs/`、`node_modules/`。

## 7. 人工补测清单

| 项 | 原因 | 方法 / 工具 | 判据 |
|---|---|---|---|
| 浏览器搜索框中文输入（IME 组合态与粘贴） | 沙箱禁止 Chromium 启动（crashpad 受限），**环境限制，非交付缺陷** | 人工在 web 需求池搜索框输入「采购」，看 Network / Chrome DevTools | 请求 keyword=`%E9%87%87%E8%B4%AD`（单次编码）、HTTP 200、列表 ≥1 条 |
| 视觉 / Craft 评分 | 本轮零 UI 改动，无 craft 对象 | N/A | N/A |

## 8. 缺陷表

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| P3-1 | P3 | 文档 | 打开 `TECH-CHANGE.md:71` 与 `memory.md` 基线行，对照 `bash scripts/tests/lint.sh` | 门槛表与实测一致 | 文档记 lint 22 条，实测 24 条（只增不减，门禁不受影响，但下轮按 22 判读会低估基线） | AC-21 / 交付自检 | `bash scripts/tests/lint.sh` | 输出「24 项」；文档订正为 24 |
| P3-2 | P3 | 架构 / 版本控制 | `git diff --name-only` 对比 TECH §三 改动清单 | 改动面与清单一致 | 多出 `scripts/access-verify.sh:62`（注释「declare -A」→「关联数组」）与 `scripts/tests/integration/s7-regression.sh:32`（`sleep 12`→窗口轮询）；dev 已在 state 声明且各自有证据（Q7/Q8/Q9），但 TECH 清单未登记 | AC-20 / 架构 | `git diff --name-only \| grep -v -E '^(scripts/(lib\.sh\|platform-up\.sh\|platform-verify\.sh\|platform-test-negative\.sh)\|docs/teamflow/memory\.md)$'` | 输出仅这两个已声明的文件（或 TECH §三 补登记） |

## 9. 结论

- R1（指纹自愈）、R2（构建后重启）、R3（AC-21×5）、R4（§18×2）全部落地；根因（就地覆盖在跑 fat jar）被 ④ 与 §18 双重固化，中文查询经 8080 实测 200 且与直连逐字节一致。
- 门禁：platform-verify **199/199 ×2**、negative **132/132**、lint 24/24、自愈 16/16、s7 10/10；两次运行计数一致，无假绿（0==0 被显式拒绝）。
- **可验收**；P3-1/P3-2 为文档口径与改动面登记问题，建议下一轮顺手订正，不阻塞验收。
