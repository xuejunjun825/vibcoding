# QA-PLAN — r47 需求池运营（相似 / 我也需要 / 合并）门禁计划

- 任务夹：`docs/teamflow/20261125-r47-pool-ops/`
- 契约：`docs/teamflow/DESIGN-POOL-OPS.md`（§2 数据模型 / §3 接口 / §4 权限 / §6 门禁口径 / §7 演示数据）
- 门禁脚本：`scripts/pool-ops-verify.sh`（1199 行）+ 共享基座 `scripts/tests/pool/_lib.sh`（326 行）
- 分支：`feat/pool-ops-gate`（与后端分支 `feat/pool-ops` 并行，不切不并）
- 断言总量：**33 条**（P1 相似 7 / P2 跟单 9 / P3 合并 9 / P4 回归 8），超出契约 §6 的 ≥24 条要求

## 1. 运行方式

```bash
# 1) 不联网自检（清单/编号/分段），CI 可先跑这一步
bash scripts/pool-ops-verify.sh --dry-run        # 打印 33 条标题，exit 0
bash scripts/pool-ops-verify.sh --static-only    # 6 条静态检查（语法/清单/分段/唯一/无 python/原语）

# 2) 全量（需栈在跑：make platform-up && make platform-seed）
make dev-up                       # 或已有栈 8080/8082/8083/5173
bash scripts/pool-ops-verify.sh   # 结尾 POOL_OPS_TOTAL=33 PASS=.. FAIL=..
```

退出码语义：

| 码 | 含义 |
|---|---|
| 0 | 全绿（33/33） |
| 1 | 有断言 FAIL（每条 FAIL 后跟 `实际:` 与一句 `修复:`） |
| 2 | 前置中止（栈/DB/种子缺失），已打印 `POOL_OPS_ABORTED=<原因>` |
| 64 | 参数错误 |

关键输出契约：

```
PASS [07] P1-7  相似性能：200 行 LIKE 预筛样本 p95 ≤ 200ms
FAIL [17] P3-1  合并：200 closed=true；source CLOSED/MERGED/merged_into_id=target
     实际: HTTP=404 code= body={"status":404,...}
     修复: 合并必须 200 且返回 {closed:true,sourceId,targetId}；...
...
POOL_OPS_TOTAL=33 PASS=2 FAIL=31
```

## 2. 夹具矩阵（每轮自造、自清理）

`GATE_TAG=POOLGATE<MMDDhhmmss><pid>`，所有夹具标题带该 tag，teardown 用「本轮 id ∪ title LIKE %tag%」双口径收口。

**类型层级前置（实测发现的硬门，2026-11-25）**：`req-service/.../RequirementRules.java:145-170 checkParentCombination` 只允许 BUS → PDR → RD 三层，且每一层对父状态有要求：

1. `PDR` 必须挂 `BUS` 父（否则 400 `INVALID_PARENT`），`BUS` 必须顶层，`RD` 必须挂 `PDR` 父；
2. 建子需求前父必须已入池（409 `PARENT_NOT_POOLED`，新建默认 `SUBMITTED`）；
3. 建 `RD` 要求父 `PDR` 已排期（409 `PARENT_NOT_SCHEDULED`，需 `SCHEDULED`/`IN_DEV`，光 `POOLED` 不够）。

所以本轮夹具固定为：先建一个 `R_BUS_ROOT`（BUS）并 `UPDATE status='POOLED'`，所有 PDR 以它为父；`R_SRC` 置 `SCHEDULED`（既能建 RD 子需求，又仍是「非终态」可被合并），`R_TGT` 置 `POOLED`（过合并 target 状态门）。

| 夹具 | 类型/域 | 构造 | 用途 |
|---|---|---|---|
| `R_BUS_ROOT` | BUS / ORD | 先建，再 `db_q UPDATE status='POOLED'` | 本轮全部 PDR 的父（层级门要求） |
| `R_SELF` / `R_A` / `R_B` | PDR / ORD | 同标题 `供应商门户单点登录 <tag>`，`parent=R_BUS_ROOT` | P1 自身排除 + 同名 ≥3 + 降序 |
| `R_X` | PDR / INV | 与上同标题，域不同 | P1-3 同域加分对照（ORD > INV） |
| `R_CLOSED` | PDR / ORD | `db_q UPDATE status='CLOSED', close_reason='DONE'` | P1-5 终态不出现 |
| `R_MERGED` | PDR / ORD | `status=CLOSED, close_reason='MERGED', merged_into_id=R_A` | P1-5 已合并不出现 |
| `R_FOLLOW` | PDR / ORD | reporter=pm，owner=po1 | P2 全段（跟单/幂等/分页/followedByMe/DELETE/通知） |
| `R_FOLLOW_TERM` | PDR / ORD | `status='CLOSED'` | P2-6 终态 409 |
| `R_SRC` / `R_TGT` | PDR / ORD | SRC `SCHEDULED`（可建 RD 且在非终态）、TGT `POOLED`；SRC 2 位跟单（pm/po1），TGT 预置 po1 | P3-1/2/4/5/9 |
| `R_C1` / `R_C2` | RD / ORD | 以 `R_SRC` 为父直接创建（父已 `SCHEDULED`），记合并前状态 | P3-3 reparent 且状态不变 |
| `R_BUS` / `R_PDRTGT` | BUS vs PDR | `R_BUS` 顶层 BUS，PDRTGT `POOLED` | P3-6 跨类型 400 |
| `R_CYC_A` / `R_CYC_B` | PDR / ORD | **脏数据**：接口拦 PDR→PDR，故用 `db_q UPDATE requirement SET parent_id=R_CYC_A, status='POOLED'` 造祖先关系 | P3-7 成环 400 MERGE_CYCLE |
| `R_X2` / `R_T2` | PDR / ORD | T2 `status='POOLED', merged_into_id=R_TGT, close_reason='MERGED'` | P3-8 TARGET_MERGED（T2 保持 POOLED 以排除状态门干扰） |
| 性能样本 ×200 | PDR / ORD | 直接 `INSERT INTO requirement(...) VALUES`，title=`性能样本 <tag> 第N条` | P1-7 LIKE 预筛 200 行 p95 |

设计取舍：

- **为什么用 `db_q UPDATE` 造终态/已合并/POOLED/脏父**：契约没有「把需求置终态/改父/置池」的 API，演示种子也不能改；仓库既有门禁（`scripts/editability-verify.sh:205`、`scripts/pdr-flow-verify.sh:907`）同款做法。`R_CYC_B` 的祖宗环只能这么造——接口层本来就会拒绝 PDR→PDR 的父子关系，而合并的环检测必须能挡住脏数据。
- **为什么不新建域**：种子给每个账号绑全部 7 域，ORD(3)/INV(2) 直接可用；新建域会污染 `sys_role_permission` 之外的数据且 teardown 面积变大。
- **为什么性能样本直接 INSERT**：只为把 LIKE 预筛样本量顶到 200 行（契约上限），走 API 建 200 条要 200 次 HTTP，慢且噪音大；这些行不带 `GATE_TAG` 之外的业务含义，teardown 按 tag 删净。
- **清理顺序**（外键安全）：notification → audit_log → requirement_follow → requirement_domain_rel → requirement_tag → req_change_item → req_change → task → requirement；删完回查四张表，`POOL_RESIDUE = left_tag + left_follow + left_audit + left_notif` 必须为 0（P4-4）。
- **id 清单必须逐行取**：`SELECT GROUP_CONCAT(id)` 在 200 行样本上被 `group_concat_max_len=1024` 截断，只收回 146 个 id，54 行留在库里（首跑实测）。改为逐行 `SELECT id ...` 再 `paste -sd,`，并且残留除了按 id 数，还要再按 `title LIKE '%<tag>%'` 数一遍（byId/byTag 双口径），否则 demo-reset 会把残留顺手清掉，掩盖清理缺陷。

## 3. 断言矩阵（段 → 条 → 断言内容）

### P1 相似需求（契约 §3.1，7 条）

| 条 | 断言（真正验的契约点） |
|---|---|
| P1-1 | `GET /requirements/{id}/similar`：`R_SELF` 不在 items（self 排除）、同标题候选 ≥2 |
| P1-2 | `GET /requirements/similar?title=`：同名命中 ≥3 条，且 score 严格降序（相等也算降序） |
| P1-3 | 同域加分：`score(R_A,ORD) > score(R_X,INV)`（同标题同类型，只差 domainBonus +1） |
| P1-4 | limit 生效：title 端点 `limit=1` → items=1 且 total ≥3；id 端点 `limit=1` → items=1 |
| P1-5 | `CLOSED` 与 `merged_into_id IS NOT NULL` 均不出现，且 items ≥3（防空结果假通过） |
| P1-6 | vpm1（VENDOR_PM/VEN-A）结果 ⊆ 其列表可见集且含 `DEMO-R-09`；vendor2 看不到 VEN-A 的 `R-09/10/11` |
| P1-7 | 200 行样本：3 次预热 + 20 次计时，p95（升序第 19 个）≤ 200ms 且 20/20 HTTP 200 |

### P2 我也需要（契约 §3.2，9 条）

| 条 | 断言 |
|---|---|
| P2-1 | pm 首次跟单 → **201**、详情 `followCount=1`、`requirement_follow` 1 行 |
| P2-2 | 重复跟单 → **200**、仍 1 行、`followCount` 不增（不是「只要没 500 就算过」） |
| P2-3 | po1 跟单 → 201、总 2 行、`followCount=2`（不同 user 不去重） |
| P2-4 | `followers?page=1&size=1`：`total=2`、`items=1`（分页生效）、`mine=true`、`orgs` 的 count 合计 = 2 |
| P2-5 | `followedByMe`：pm=true、po1=true、admin=false，且必须是**布尔**（字符串 `"true"` 判红） |
| P2-6 | 终态跟单 → **409 + code=REQUIREMENT_CLOSED** |
| P2-7 | `DELETE` → **204**、本人 0 行、`followCount` 2→1 |
| P2-8 | 同日再跟一次：notification（`type=REQUIREMENT_FOLLOWED`, `biz_id=R_FOLLOW`）pm=1、po1=1、合计 2（幂等键 `follow:{reqId}:{date}`） |
| P2-9 | vendor2 跟单 → 403/404 且库 0 行（越权写必须不落库） |

### P3 合并（契约 §3.3，9 条）

| 条 | 断言 |
|---|---|
| P3-1 | `POST .../merge` → 200、响应 `closed=true/sourceId/targetId`；source 详情 `status=CLOSED`、`closeReason=MERGED`、`mergedIntoId=target` |
| P3-2 | follow 迁移去重：`movedFollows=1`、source 0 行、target 2 行、target 的 user 集合 = {pm,po1} |
| P3-3 | `movedChildren=2`、C1/C2 `parent_id=target`、**状态与合并前一致** |
| P3-4 | `audit_log` 中 `REQUIREMENT_MERGED`（biz_id=source）**恰 1 条**，detail 带 targetId/movedFollows=1/movedChildren=2 |
| P3-5 | 重复合并 → **409 ALREADY_MERGED** |
| P3-6 | BUS→PDR 跨类型 → 400 |
| P3-7 | target 是 source 后代 → **400 MERGE_CYCLE** |
| P3-8 | target 已合并（T2 仍 POOLED） → **400 TARGET_MERGED** |
| P3-9 | source 提报人 pm 收到 `type LIKE %MERGED%` 通知 ≥1 |

### P4 回归（契约 §6 P4 / §7，8 条）

| 条 | 断言 |
|---|---|
| P4-1 | `GET /requirements` 列表：每个 item 都有 `followCount` 且 `type == "number"` |
| P4-2 | 详情 `followedByMe` 为布尔 true（pm 已跟） |
| P4-3 | vpm1（VENDOR_PM）合并 → **403**（`req.action.merge` 只给 PLATFORM_ADMIN/PMO/PRODUCT） |
| P4-4 | 自清理：删完回查，本轮需求/跟单/审计/通知残留 **0** |
| P4-5 | `bash scripts/platform-demo-reset.sh` 仍 **rc=0 且输出含 80/80** |
| P4-6 | 演示数据有示例跟单：`DEMO-R-02` follow ≥1 且带 note ≥1 |
| P4-7 | 演示 `followCount`（API）== `requirement_follow` 行数（≥1），计数不漂 |
| P4-8 | 按 §7 严格口径：`DEMO-R-02` = **6 条 follow / 2 条 note** |

> P4-4 与 P4-5 的顺序是刻意的：先在本轮的夹具上断言「能自己删干净」，再跑 demo-reset。这样 demo-reset 的 80/80 不会被本轮夹具污染，也不会用 reset 掩盖 teardown 的漏洞。

## 4. 失败语义（后端未落地时也不崩）

1. 每条断言先看 HTTP 码：非 2xx 直接 `FAIL` 并带传输层提示，**不进入字段断言**。三种「没就绪」分别有专门文案：
   - 裸 404（`{"timestamp":…,"error":"Not Found"}`，无 `code` 字段）→ 「接口未就绪（404）：该路由尚未落地」；
   - `/similar` 的 400 `VALIDATION_FAILED` + `Failed to convert ... "similar"` → 「接口未就绪（/similar 尚无独立路由，被 `/requirements/{id}` 通配匹配：id="similar" 转 long 失败）」（`pool_similar_hint`）；
   - `000` → 「网关/服务不可达」，`401/403` → 鉴权，`5xx` → 让看 req-service 日志。
2. 所有 JSON 取值走 `jq`（`pool_pick/pool_bool/pool_len`），取不到给空串而不是让 `jq` 抛错；`set -u` 下所有夹具变量在文件头初始化。
3. 夹具创建失败 → `fixture_missing` 记 FAIL（不给 「实际: 空」这种没信息量的红）。
4. 表未迁移（V22/V23 没跑）→ 依赖它的断言单独 FAIL 并在 `修复:` 里点名迁移文件，而不是让 SQL 报错中断。
5. 栈/DB/种子整体缺失 → `pool_abort` 打 `POOL_OPS_ABORTED=` 后 exit 2（区分「环境没起」与「需求不达标」）。
6. 不用 `sleep` 掩盖未就绪：`pool_wait_ready` 每秒探测 `/internal/health`（status=UP + db=up + flyway）、30s 上限，超时打印是哪一步没就绪。
7. **「必须被拒」的断言不许被 404 混过**：`pool_biz_404 <body>` 要求 404 带业务 `code`；路由没落地时的裸 404 一律判红（P2-9 首跑就是这么假绿的）。

## 5. 交接给后端（feat/pool-ops）的接口清单

| 断言依赖 | 端点/字段 |
|---|---|
| P1-1…P1-7 | `GET /requirements/similar`、`GET /requirements/{id}/similar`，响应 `{items[{id,title,status,domainId,score,followCount}],total}` |
| P2-1…P2-9 | `POST/DELETE /requirements/{id}/follow`、`GET /requirements/{id}/followers`，详情 `view.followCount/followedByMe` |
| P3-1…P3-9 | `POST /requirements/{id}/merge`，详情 `view.closeReason/mergedIntoId`，`audit_log.action=REQUIREMENT_MERGED`，`notification.type` 含 MERGED |
| P4-1/P4-7 | 列表 `items[].followCount` |
| P4-6/P4-8 | V22 表存在 + `DemoFixture` 给 `DEMO-R-02` 造 6 条 follow（2 条带 note） |

## 6. 契约缺口 / 歧义（≤10 条，按影响排序）

| # | 缺口 | 影响 | 本门禁的取法 |
|---|---|---|---|
| G-1 | §7 说 `DEMO-R-02` 是 `POOLED`，当前种子是 `SCHEDULED` | 演示脚本/截图对不上 | 不依赖该状态；P4-6/7/8 只验 follow 行数 |
| G-2 | 任务简报写「演示数据有 1 条示例跟单」，§7 写 6 条（2 条带 note） | 两条口径互斥 | P4-6 取「≥1 且 ≥1 条 note」（不卡数量），P4-8 单独按 §7 卡 6/2；两者分离，红的时候能看出是「没做」还是「做少了」 |
| G-3 | §3.1 只说 id 端点「排除自身」，title 端点没写 | title 端点可能把「与自身同标题的他条」也算进 self？ | P1-1 只对 id 端点断言 self 排除；title 端点不假设 |
| G-4 | §3.3 前置⑤（source 已合并 → 409）与「source 非终态」的判定顺序未写明；合并后的 source 同时是 CLOSED | 实现可能先判终态返回 409 REQUIREMENT_CLOSED，与 ALREADY_MERGED 冲突 | P3-5 明确要求 `ALREADY_MERGED`：合并态优先于终态判定 |
| G-5 | §3.2 通知「同日同需求合并」只给了幂等键 `follow:{reqId}:{date}`，没说落哪张表/哪个唯一索引 | 无法从库侧区分「没发」与「发了但没去重」 | P2-8 直接数 `notification` 行数（pm=1、po1=1），不假设存储形态 |
| G-6 | §3.3 「通知 source 提报人/跟单人」没给 `notification.type` 取值 | 断言只能模糊匹配 | P3-9 用 `type LIKE '%MERGED%'`；建议契约补 `REQUIREMENT_MERGED` |
| G-7 | §3.1 `limit` 无上限、无缺省说明（示例写 5） | 大 limit 可能拖垮 p95 | P1-4 只验「limit=1 被消费」，不验上限；建议契约补 `limit ∈ [1,50]` |
| G-8 | §3.1 p95 ≤ 200ms 没说「多少请求、什么样本量」 | 无法判定性能门禁是否达标 | P1-7 自定口径：200 行 LIKE 样本 + 3 预热 + 20 次取第 19 个；写进本文件备案，待契约确认 |
| G-9 | §3.2 `DELETE` 404 的语义（没跟过 vs 需求不存在）未区分；§3.3 跨类型 400 未给错误码 | 断言只能卡 HTTP 码 | P3-6 只断言 400（不卡 code）；建议补 `TYPE_MISMATCH` |
| G-10 | §2 的 `requirement_follow.org_id` 来源（跟单人当时的组织 vs 需求组织）未定义 | orgs 聚合口径可能有歧义 | P2-4 只验「orgs count 合计 = total」，不验具体 orgId |

### 6.1 跨分支耦合（`feat/pool-ops` 侧必须一并处理，否则本门禁 P4-5 必红）

§4 把 `sys_role_permission` 从 1980（11×180）改成 2002（11×182），但 `scripts/platform-demo-reset.sh:709` 硬编码断言 `role_permission = 1980（11 角色 × 180）`（同文件 713 行 `[[ ${RBAC_TENANT_ROWS} == 1980 ]]`、731/740/745 行也按 180 算）。后端一旦落库 2002，`make platform-demo-reset` 会从 80/80 掉下来，P4-5 直接红——这不是本门禁写错，而是**权限矩阵变更必须同步更新演示重置脚本的期望值与文案**。建议该改动随 `feat/pool-ops` 一起提交，并在 PR 描述里点名 `platform-demo-reset.sh:709/713`。

同理，`platform-demo-reset.sh:332` 断言 `NON_FIXTURE_REQS = 0`（`code NOT LIKE 'DEMO-R-%'`）：本门禁的性能样本用 `code='PQ<tag><n>'`，所以 teardown 必须先把它们删净，否则 P4-5 也会因为「库里多出非 DEMO-R 行」而红。这正是 P4-4 → P4-5 顺序的意义。

## 7. 建议接入 `make test`（一行 patch，未改 Makefile）
`make test`（Makefile:30-36）此刻的顺序是 `scripts/test-all.sh` → `platform-up.sh` → `collab-verify.sh` → `platform-down.sh`：**平台栈在 `platform-down` 之前是活的**，所以门禁要插在 `collab-verify.sh` 与 `platform-down.sh` 之间，否则 33 条会全部因 000 而红。

```diff
--- a/Makefile
+++ b/Makefile
@@ -30,6 +30,7 @@
 test: ## 全量门禁：lint + 单测 + r1 四步 + 平台栈 AC-1..AC-20 + 种子 + 负向 + 协作域 s0..s8
 	@./scripts/test-all.sh
 	@echo ""
 	@echo "== 协作域验收（M8）：test-all 结束时已 platform-down，这里重新拉起平台栈 =="
 	@./scripts/platform-up.sh
 	@./scripts/collab-verify.sh
+	@bash scripts/pool-ops-verify.sh
 	@./scripts/platform-down.sh
```

若希望 `make test` 不因为「后端未落地」而变红，把上面那行换成 `@bash scripts/pool-ops-verify.sh --dry-run`（只验清单/编号/分段，毫秒级、不碰栈）：33 条全量会造 200 行夹具并跑一次 `platform-demo-reset`（约 40–60s），更适合 `make verify` 或 CI 的「全量」阶段。

（可选 `PHONY` 一行：把 `pool-ops-verify` 加进 Makefile:5-9 的 `.PHONY`，并加 `pool-ops-verify: ## 需求池运营门禁 33 条` + `@bash scripts/pool-ops-verify.sh` 两条；本门禁未改 Makefile。）

## 8. 本轮自测记录

| 项 | 命令 | 结果 |
|---|---|---|
| 语法 | `bash -n scripts/pool-ops-verify.sh` / `_lib.sh` | 通过 |
| 清单 | `bash scripts/pool-ops-verify.sh --dry-run` | `POOL_OPS_DRY_RUN=1 TOTAL=33` + 33 行编号标题，exit 0 |
| 静态 | `bash scripts/pool-ops-verify.sh --static-only` | `POOL_OPS_TOTAL=6 PASS=6 FAIL=0`，exit 0（连跑 3 次一致） |
| 全量 ×5 轮 | `bash scripts/pool-ops-verify.sh` | 见第 9 节；每轮都 exit 1（后端未落地）、无 unbound/无 jq 崩溃、teardown 残留 0 |

首跑（修复前）暴露的 4 个真实缺陷，均已修：① 夹具 `create_req` 全 400 `INVALID_PARENT`——PDR 必须挂 BUS 父；② 补父后仍 409 `PARENT_NOT_POOLED`——父必须先 POOLED；③ RD 子需求 409 `PARENT_NOT_SCHEDULED`——父 PDR 需 `SCHEDULED`/`IN_DEV`，于是 source 置 `SCHEDULED` 而非 `POOLED`；④ teardown 用 `GROUP_CONCAT` 被 1024 字节截断（219 行只删 146 行，54 行靠随后 demo-reset 顺手清掉）——改为逐行取 id + 按 tag 再数一遍残留。

## 9. 全量结果（feat/pool-ops 未合并时，2026-11-25 实测）

后端此刻尚未在 `feat/pool-ops` 落地 `similar/follow/merge`，全量运行是「P1–P3 全红、P4 部分红」，重点验的是**脚本本身不崩、错误可读、清理干净**：

- `POOL_OPS_TOTAL=33 PASS=2 FAIL=31`，`PASS+FAIL=33`，exit 1；`PASS` 仅 `P4-4`（teardown 后残留 0）与 `P4-5`（`platform-demo-reset` 仍 80/80）；
- 夹具全部落地（`R_BUS_ROOT` + 9 个 PDR + SRC/TGT + 2 个 RD 子需求 + 200 行性能样本），说明层级/状态前置已摸清；
- 每条红都带 `实际:` 与可执行 `修复:`，且区分了三种「没就绪」：`/similar` 被 `/requirements/{id}` 通配吃掉 → 400 `Failed to convert ... "similar"`；`/follow`、`/followers`、`/merge` → 裸 404；`requirement_follow` 表未迁移 → P4-6 直接点名 V22；
- teardown：`已删 需求 219 跟单 0 审计 19 通知 18`，`残留 0 条（按 id 与标题 tag 双口径）`；
- 一处刻意收紧：P2-9（不可见账号跟单）曾因「裸 404」误判 PASS，现要求 `403` 或**带业务 code 的 404**（`pool_biz_404`），路由没落地时必须红。

后端合并后重跑同一条命令即可，不需要改脚本；预期 P1–P4 全绿后 exit 0。
