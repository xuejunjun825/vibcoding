# 验收报告 · r42 网关非 ASCII 查询断连（P0）修复

- 任务夹：`docs/teamflow/20261123-r42-gateway-unicode-query/`　分支：`feat/gateway-unicode-query`　基线：`main@d2ad988`
- 验收依据：TECH-CHANGE.md §2/§4/§6（本需求为技术驱动修复，不新增功能 AC）；QA-REPORT.md（12 组用例，0 fail）
- 验收方式：独立复跑核心门禁 + 代码/改动面复核；未复跑与本改动无交集的 4 条门禁（access/rbac/rfv/change），采信 QA 证据

## 1. 本轮独立复跑（非引用 QA 结果）

| 命令 | 结果 |
|---|---|
| `bash scripts/platform-verify.sh` | exit 0，**199/199**，AC-21 段 5/5，退出复位 demo-reset 通过、足迹 0 |
| `bash scripts/platform-test-negative.sh` | exit 0，**132/132**，§18 两条：逐字节一致（采购 · 783B == 783B）、total 同源（2 == 2） |
| `bash scripts/tests/lint.sh` | **24/24** |
| `bash scripts/gate-order-verify.sh` | **21/21**（A/B 两序一致） |
| `bash logs/teamflow/tf-mwt98mh0-kh9wzm/scripts/t1-jar-fingerprint-selfheal.sh` | **16/16**：缺 fp→重启补写（pid 46978→93449）、一致→复用、mtime 变→自愈重启（93449→93582）并打印旧/新指纹、stop 清理后重建 |
| `git diff --name-only` | 7 文件（4 脚本 + memory + 2 个声明外脚本），**`api-gateway/` 命中 0** |

AC-21 实测：① keyword「验证」（取自标题「验证-准入正向RD-211144」）经 8080 → 200，`total=10`；②「验证RD」+page/size/status → 200；③ 单字/多字/含空格 → 200/200/200；④ `.run/api-gateway.jar.fp` == 磁盘指纹 `12530813:72057350:1795439347`；⑤ 中文 path 段 → 400（非 000）。

## 2. 逐条验收

| # | 验收要求（来源） | 判定 | 证据 |
|---|---|---|---|
| C1 | 根因定性成立且可复核（TECH §2 证据 A–G） | ✅ | 文档证据链完整（依赖树/类存在/同 jar 新进程 200/坏进程 lsof 指向 11793B `.jar.original`/受控复现/生命周期必然触发）；历史坏进程已不存在属不可重放项，改由 AC-21 ④ 常驻自证"在跑进程即当前磁盘 jar" |
| C2 | R1 指纹生命周期单点（`lib.sh`） | ✅ | `file_fingerprint` lib.sh:330（Darwin/GNU stat，取不到空串不报错）；`start_app` lib.sh:350 四分支；`stop_app` lib.sh:402 随 pid 删 `.jar.fp`；自愈 16/16 |
| C3 | R2 `platform-up` 构建后重跑 start_app | ✅ | platform-up.sh:72-80 仅两对 `start_app`+`wait_app_health`，无 stat/cmp/kill；dev 真栈端到端打印"已被构建覆盖（旧→新），重启"，pid 85508→88006 后 READY |
| C4 | R3 AC-21 五条（platform-verify） | ✅ | 199/199，5/5；`han_prefix`/`urlenc` 仅此一处（verify:1195/1203），keyword 从池中标题派生（:1207-1216） |
| C5 | R4 §18 两条反证（negative） | ✅ | 132/132，2/2；`api_call` 8080 与 `api_direct` 直连 8082（去 `/api/req` 前缀）、共用 HDR18 三头（negative:1026-1029） |
| C6 | 中文查询经 8080 可用（用户可见目标） | ✅ | AC-21 ① 200 + total=10（真 HTTP→StripPrefix→req-service→MySQL）；②③ 均 200 |
| C7 | 网关只透传、不改写查询语义 | ✅ | §18 响应体逐字节 `cmp` 相等（783B==783B）、total 同源且 `>=1`（0==0 被显式拒绝） |
| C8 | 网关/业务/Flyway/版本矩阵零改动 | ✅ | `git diff --name-only` 无 `api-gateway/`、pom、业务服务、迁移 |
| C9 | 既有门禁不降 | ✅ | 本轮：verify 199、negative 132、lint 24、gate-order 21；QA 全量：access 206、rbac 113、rfv 44、change 42、demo-reset 80×2、s7 10/10 |
| C10 | 门禁不写死版本号/种子文案 | ✅ | AC-21 keyword 由池标题派生、混排 ASCII 由标题派生；§18 keyword 由 `db_q` 派生；未见写死"演示"/版本号 |
| C11 | 架构蓝图一致性（单点/无重复/无 adapter drift） | ✅ | 指纹判定仅 lib.sh 一份；jar 解析仅 `app_jar`（verify:1261）；`.jar.fp` 独立于 pid；负向复用统一头与前缀；未新增包装层；无破坏既有结构 |
| C12 | 文档与产品记忆 | ✅ | memory.md:49 登记 r42 事实与新下限（≥199/≥132，其余不降）；TECH-CHANGE 87 行含根因表/影响面/门槛/回滚；Q1 口径与 TECH §7-3 的 197 备选已说明（取 199） |
| C13 | 提交面卫生 | ✅ | 分支 `feat/gateway-unicode-query`；`git status` 无 target/.run/node_modules/密钥；`.gitignore` 覆盖正常（提交尚未发生，见 §4） |

## 3. 架构核查结论（M3）

- **按蓝图落地**：根因修复单点落在 `start_app`（覆盖 dev-up、platform-up、手工起栈）；platform-up 只"再调一次"，不复制重启逻辑；AC-21 ④ 用 `file_fingerprint "$(app_jar api-gateway)"`，未另写 find。
- **未发现**重复实现、适配层漂移、既有结构破坏；`section/pass/fail` 两份为历史现状（蓝图明确接受）。
- 观察（不构成返工）：negative §18 的中文 keyword 派生用内联 jq（数据源为 DB），与 verify 的 `han_prefix`（数据源为 HTTP 池）各自实现；未复制函数、未新增第二套 curl 参数，符合蓝图"只放 verify"的字面要求，但"汉字提取"语义有两处表达，下轮若抽公共助手需保持两数据源独立。

## 4. 意见与遗留

| 编号 | 级别 | 内容 | 建议 |
|---|---|---|---|
| R-1 | P3 | TECH-CHANGE §6 把 `lint` 门槛写作 22，实测 24（QA-REPORT P3-1）；memory 基线行未列 lint | 下轮订正为 24；只增不减，不阻塞 |
| R-2 | P3 | 改动面多出 `scripts/access-verify.sh`（注释措辞）与 `scripts/tests/integration/s7-regression.sh`（sleep 12 → 窗口轮询），TECH §3 未登记（QA P3-2）；dev 已声明并有 Q7/Q8/Q9 证据 | 下轮在 TECH 改动清单补登记，或明确"门禁脚本随行修正"口径 |
| R-3 | 提示 | 提交尚未发生：7 个改动 + 任务夹在工作区；收口提交须含 runId 与任务夹路径（TECH §2） | 收口时执行 |
| R-4 | 人工补测 | 浏览器搜索框 IME 组合态/粘贴（沙箱禁止 Chromium，属环境限制非缺陷） | 人工在需求池搜索框输入中文，确认单次编码、200、≥1 条 |

## 5. 结论

R1–R4 全部落地并经独立真栈复跑证实：中文查询经 8080 恢复 200 且与直连逐字节一致，根因由 AC-21 ④ 与 §18 双重固化；架构与蓝图一致，网关零改动；无 P0/P1/P2，遗留均为文档口径与收口事项。**建议通过验收。**

验收结论：✅ 通过
