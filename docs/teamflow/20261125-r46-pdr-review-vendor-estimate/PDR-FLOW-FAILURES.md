# PDR-FLOW 门禁失败清单（派修用）

- 被测脚本：`scripts/pdr-flow-verify.sh`（66 条断言，四组 S/A/B/C/D）
- 执行方式：**只运行、未改动**该脚本
- 原始输出：`PDR-FLOW-RUN-1.log`（未打补丁的真实运行，**第 04 条即崩**）
- 诊断输出：`PDR-FLOW-RUN-2-diag.log`（为拿到后续断言，用 `/tmp` 外的临时副本仅修两处**脚本自身缺陷**后再跑，副本跑完即删；业务代码/仓库脚本零改动，见文末「诊断副本的两处补丁」）
- 运行时间：2026-11-25 11:34–11:36（Asia/Shanghai），栈：gateway 8080 / req 8082 / deliver 8083
- 结论：**真实运行 2 PASS / 1 FAIL / 63 未执行**（第 04 条脚本崩溃中止）；诊断运行 **3 PASS / 3 FAIL / 60 未执行**（夹具硬失败中止）。**66 条里 60 条根本没跑到**，不能据此判定后端契约是否合规。

## 一、失败项（按编号）

| 编号 | 验收项 | 期望 | 实际 | 初步归因（后端/前端/脚本） |
|---|---|---|---|---|
| 03 S3 | 前置：项目成员 5 人（PO/PM/DEV/QA/SME） | 5/5 成功 **且列表 5 条** | 成功 5/5，**列表 0 条**；body 其实是裸数组 5 条：`[{"id":56,"projectId":12,"userId":2,"username":"pm","roleInProject":"PO",…},{"id":57,…,{"id":58,…}]` | **脚本**。`pdr_len "${PDR_HTTP_BODY}" '(.items // .members // .)'`：jq 对**裸数组**取 `.items` 会报 `Cannot index array with string "items"`，整条表达式失败 → `pdr_len` 恒返回 0。加 `?`（`.items? // .members? // .`）后该条在诊断运行里 **PASS**。 |
| 04 S4 | 前置：BUS 创建（pm）→ 201 | 201 且 busId 非空 | **未拿到结果**：`scripts/pdr-flow-verify.sh: line 398: CREATED_REQ_CODE: unbound variable`（`set -u` 直接中止整支脚本） | **脚本**。`BUS_ID="$(create_req …)"` 是命令替换 → `create_req` 在**子壳**里执行，`CREATED_REQ_CODE="${PDR_HTTP_CODE}"`（第 207 行）写在子壳，父壳永远读不到；第 398/412/414/423/428/430/432 行再引用即崩。这是本支脚本「一次都没跑过」的直接原因。 |
| 04 S4（诊断） | 同上（补丁后继续跑） | 201 | **403** `{"status":403,"code":"OUT_OF_SCOPE","message":"无权在这些业务域下提报","detail":{"field":"domainIds","domainIds":[1242]}}` | **后端 + 脚本**。S1 每轮新建一个域（本次 id=1242），但**新建域没有授权入口**：`sys_user_domain` 只有种子写入（`SeedService.java:236`），全仓无 `POST/PUT .../domains/{id}/users` 之类端点；pm 的域可见集仍只有种子 7 域 → 403。同域用 **admin** 建同一需求 **201**（实测 id=648138 BUS-2406），证明不是域本身不可用。脚本侧：夹具用 `pm` 身份往「刚建的新域」提报，与产品能力不符。 |
| 05 S5 | 前置：BUS 真实入池 → `GET status=POOLED` | status=POOLED | `status=404 HTTP=200`，body 竟是**登录响应**（`{"token":"eyJ…","expiresIn":43200}`） | **脚本（级联）**。S4 未拿到 busId → 用空 id 发 GET，命中 `/api/req/requirements/{空}` 之外的路径；body 是父壳里上一次 `pdr_login` 的残留（同 S4 的子壳问题）。 |
| 06 S6 | 前置：PDR 夹具 A/B/D/E/F 创建 | 5 个 id 均非空 | `A=<空>(403) B=<空>(403) D=<空>(403) E=<空>(403) F=<空>(403)`；`域2=1242(200) BUS2=<空>(403)` | **脚本（级联）/ 后端（同 04）**。PDR 只能挂 POOLED 的 BUS，而 BUS 建不出来（同 04 的 403）。 |
| —（fatal） | 全量 66 条 | 66 条执行完 | `PDR_FLOW_ABORTED=PDR 夹具未创建`；**未执行 60 条**：`S7 S8 A1-1…D4-3` | **脚本**。前置夹具失败即 `pdr_abort` 中止，后续 A/B/C/D 四组（评审、厂商、附件、分类统计、意见转任务）一条都没验证到。 |

## 二、附带的清理缺陷（非断言失败，但会污染演示栈）

| 项 | 现象 | 归因 |
|---|---|---|
| 夹具遗留域 | 每跑一轮新建一个 `biz_domain`（本次 1240 `PDRG112511335969707`、1241 `PDRG112511343270066`、1242 `PDRS112511343270066`），脚本**不清理**；`platform-demo-reset` 把域当「定义数据」不删（`DELETE_ORDER` 不含 `biz_domain`），于是 `platform-demo-reset` 第 [36] 条 `定义数据被误删：种子账号 14 / 域 10 / 厂商 3` 红（域应为 7）。 | **脚本**（pdr-flow-verify 无 teardown） |

> 已由本次门禁维护手工清理：确认三行**零依赖**（`requirement.domain_id` / `project.domain_id` / `requirement_domain_rel` / `sys_user_domain` 均为 0）后 `DELETE FROM biz_domain WHERE id IN (1240,1241,1242)`，域回到 7，`platform-demo-reset` 复跑 **80/80**。

## 三、诊断副本的两处补丁（仅为绕过脚本自身缺陷，供派修对照）

副本 `scripts/.pdr-flow-diag.tmp.sh`（跑完 `rm -f`，仓库脚本未改）：

1. `pdr_len` 调用处 `(.items // .members // .)` → `(.items? // .members? // .)`（修 03）
2. `create_req` 末尾加 `printf '%s' "${PDR_HTTP_CODE}" > /tmp/pdr-flow-code`，并把 8 处 `${CREATED_REQ_CODE}` 改成读该文件（修 04 的子壳丢值）

> 修完这两处后：03 PASS、04 暴露真实 403、05/06 级联失败、夹具中止。**这两处必须先修，否则该门禁永远在第 04 条崩、66 条里 60 条永远跑不到。**

## 四、另一个套件的红（`platform-verify.sh`，非本文件范围但同源）

`bash scripts/platform-verify.sh` → **151 PASS / 47 FAIL / 198 项，exit 1**（期望 ≥199 全绿）。首个失败即新规则拦路：

| 位置 | 期望 | 实际 | 归因 |
|---|---|---|---|
| AC-2 `提交工时评估单（需求 648096）` | 2xx | `409 {"code":"PDR_REVIEW_REQUIRED","message":"PDR 必须先通过评审才能指派厂商 / 提交评估 / 排期"}` | **脚本（门禁未跟上新规则）**：platform-verify 的 PDR 场景建完 PDR 直接提交评估/派厂商/排期，没有插入「评审通过」这一步 |
| 后续 46 条 | 各自契约 | 大量 `400 VALIDATION_FAILED：Method parameter 'id' … For input string: "null"`、`409 PARENT_NOT_SCHEDULED`、`audit_log 0 条` | **级联**：上一步没拿到 id（null）继续发请求；`PARENT_NOT_SCHEDULED` 说明 PDR 也没排期。不是计数问题（`VENDOR_PM 的授权格 = 180（61 FUNCTION + 38 MENU + 81 ACTION）` 该条 **PASS**） |

## 五、复现命令

```bash
# 真实运行（会崩在 04）
bash scripts/pdr-flow-verify.sh
# 只看 66 条清单（不联网）
bash scripts/pdr-flow-verify.sh --dry-run
# 本文件对应日志
less docs/teamflow/20261125-r46-pdr-review-vendor-estimate/PDR-FLOW-RUN-1.log
less docs/teamflow/20261125-r46-pdr-review-vendor-estimate/PDR-FLOW-RUN-2-diag.log
```

---

# 修复后复跑（2026-11-25 11:45–12:15，同一分支/同一套栈）

## 六、脚本侧修了什么（断言口径与 66 条编号未变）

| # | 文件 | 缺陷 | 修法 | 证据 |
|---|---|---|---|---|
| 1 | `scripts/pdr-flow-verify.sh` | S3 成员列表：`GET /projects/{id}/members` 返回**裸数组**，`(.items // .members // .)` 对数组取 `.items` 报错 → 长度恒 0 | 改成类型自适应 `(if type=="array" then . else (.items // .members // []) end)`（D1-3 的意见列表同样处理） | 修前 `列表 0 条`，修后 `[03] S3 PASS 5/5` |
| 2 | `scripts/pdr-flow-verify.sh` | 约 30 处 `VAR="$(fn …)"` 命令替换：函数在子壳里赋的 `CREATED_REQ_CODE/OP_CODE/START_REVIEW_CODE` 父壳读不到，`set -u` 直接 `unbound variable` | 全部改为「函数只设全局、不 echo；调用处 `fn …; VAR="${GLOBAL}"`」，函数返回非 0 即 fail（`create_req/start_review/submit_op/req_get/ensure_closed/get_review`） | `grep -n 'CREATED_\|BUS_ID='` 只剩 `BUS_ID="${CREATED_REQ_ID}"` 这类读全局，无 `X="$(create_req …)"` |
| 3 | `scripts/pdr-flow-verify.sh` | S1 每轮新建 `biz_domain`，新域没有授权入口 → pm 403 `OUT_OF_SCOPE`，且残留域让 demo-reset [36] 红 | S1 改用**种子域 ORD(3)**（`GET /api/req/admin/domains` 裸数组解析）；S6 的隔离域改用 SUP(6)（无种子 PDR 意见污染）；不再建域 | `[01] S1 PASS 域=ORD(3)`、`[06] S6 PASS`，`biz_domain` 保持 7 行 |
| 4 | `scripts/tests/pdr/_lib.sh` | `pdr_req` 里 `shift 3`，但 `pdr_get() { pdr_req GET "$1"; }` 只传 2 个参数 → shift 失败、`"$@"` 仍含 method+path，curl 把它们当额外 URL → **每个 GET 的 `%{http_code}` 都是 `000`**（body 却正确） | `pdr_get/pdr_delete` 补空 body 参数，`shift 3 2>/dev/null \|\| true` | 修前所有 GET `HTTP=000`；修后 `/admin/domains` 200 |
| 5 | `scripts/tests/pdr/_lib.sh` | `pdr_upload` 同时把 `bizType/bizId` 放进 query 与 multipart，服务端合并成 `PDR_REVIEW,PDR_REVIEW` → 400 `BIZ_TYPE_UNSUPPORTED` | multipart 只留 `file`，`bizType/bizId` 走 query | `[17] A5-1 PASS 上传 201` |
| 6 | `scripts/pdr-flow-verify.sh` | `submit_op` 取 `.id`，但 `POST /pdr-reviews/{id}/opinions` 返回 `ReviewDetail{review,opinions[]}` → 意见 id 恒空，后续 to-task 打空 id | 改取 `.opinions[-1].id` | `[23] A3-6 PASS` |
| 7 | `scripts/pdr-flow-verify.sh` | 夹具角色与后端 RBAC/DataScope 不符：原 4 位评审人含 `po1`(SUB_BUSINESS_LEADER, domainIds=[]) 与 `qa1`(QA) | 评审人改 `pm/tl1/dev1/dev2`（项目角色仍 PO/PM/DEV/SME/QA 5 人不变；QA 仍是项目成员，只是不当评审人） | 见 §七 后端缺口 1/2 |
| 8 | `scripts/pdr-flow-verify.sh` | 无 teardown，每轮残留需求/评审/任务/附件/项目 | 新增 `teardown_fixture()`（见 §九） | 本次每轮 `本轮残留 0 条` |

> `platform-verify.sh` 另见 §八。

## 七、`pdr-flow-verify.sh` 复跑：**54 PASS / 12 FAIL / 66，exit 1**

第 20/31/32/34/44/52/58/59/61/62/63/66 条仍红，全部为**后端缺口**（无一条是脚本口径问题，断言一字未改）：

| 编号 | 验收项 | 期望 | 实际（本轮原文） | 归因 | 本轮阻塞 |
|---|---|---|---|---|---|
| 20 B6-1 | 非项目成员（vendor2）下载评审附件 | 404 | `HTTP=200 body=` | **后端**：`AttachmentService.download` 只 `guard.requireVisible(附件行)`（域 + 上传者厂商），从不校验所属需求/评审的承接厂商 | 否（记录） |
| 31 B2-4 | 承接厂商 vpm1/VEN-A 提交评估 | 201 且 `estimate.vendorId=VEN-A` | `HTTP=403 {"code":"FORBIDDEN","message":"无权提交工时评估","detail":{"required":"req:deliver"}}` | **后端**：`WbsEstimateService.requireDeliverPermission` 查的是 `req.action.deliver`（VENDOR_PM=DENY），而 `req.action.estimate_submit` 对 VENDOR_PM 是 ALLOW——厂商拿着评估权却被交付权挡住 | 否（记录） |
| 32 B2-5 | 评估审批通过 | `estimate.status=APPROVED` | `estimate.status=NONE`（31 没建单，级联） | **级联**（31） | 否 |
| 34 B3-2 | 换厂商后原评估失效 | `STALE` 且 `reason=VENDOR_CHANGED` | `estimate.status=NONE` | **级联**（31）+ 后端：`markStaleByVendor` 只在存在 APPROVED 单时写 | 否 |
| 44 B4-2 | RD IN_DEV 后换厂商 | 409 `VENDOR_LOCKED` | `HTTP=200`（PDR 回到 SCHEDULED 且换了厂商） | **后端**：`RequirementService.vendorChangeWindowOpen` 只看需求自身类型/状态，R3 要求「子 RD IN_DEV 也不许换」未实现 | 否（记录） |
| 52 C3-2 | `/reports` 交叉表与 stats 逐分类相等 | 建报告 201 且行数相等 | `HTTP=409 REPORT_NAME_DUPLICATED {"name":"PDR门禁统计-1125120118-24340"}`（该名字库里并不存在） | **后端**：`POST /api/deliver/reports` 落库时 `report_definition.dataset` 是 `varchar(16)`，`pdr_review_opinion` 18 字符 → `Data too long`（MySQL 1406），`ReportService.save()` 把 `DataIntegrityViolationException` 一律重抛成 `REPORT_NAME_DUPLICATED` → **真实错误被伪装成重名 409** | 否（记录，误导性最强） |
| 58 D2-1 | 意见转任务 | 201 且 `taskId/taskCode` 非空 | `HTTP=500 path=/pdr-reviews/68/opinions/288/to-task`；req-service 日志 `logs/req-service.log:171974-171980`：`Unknown column 'version' in 'field list'` / `UPDATE pdr_review_opinion SET status=?, task_id=?, updated_by=?, version = version + 1 …` | **后端（迁移缺口）**：`pdr_review_opinion` 表**没有 `version` 列**，但 `PdrReviewService.toTask` 的 UPDATE 乐观锁写 `version+1` → 必然 500 | **是**（D 组 6 条由此级联） |
| 59 D2-2 | 意见回写 `TASKED` + `task_id` | `status=TASKED, task_id=taskId` | `opinion.status=OPEN task_id=<空>` | **级联**（58）+ 非原子：`taskClient.create` 已先建远程任务，UPDATE 才失败 | 是 |
| 61 D2-4 | 审计 `PDR_OPINION_TO_TASK` +1 | 1 条 | `audit_log PDR_OPINION_TO_TASK=0` | **级联**（58：审计写在同一方法 UPDATE 之后） | 是 |
| 62 D3-1 | 重复转任务 | 409 `OPINION_ALREADY_TASKED` | `HTTP=500` | **级联**（58：`opinion.taskId()` 永远回写不进去，撞不到 409 分支，仍走建任务→UPDATE 500） | 是 |
| 63 D3-2 | 重复转任务不新增 task 行 | before=after | `task 行数 before=1 after=2` | **级联**（58）+ 建任务在事务外 | 是 |
| 66 D4-3 | 评审关闭后 to-task 仍 201 | 201 | `HTTP=500 path=/pdr-reviews/68/opinions/289/to-task` | **级联**（58） | 是 |

**修 58 的连带收益**：`pdr_review_opinion` 补 `version` 列（或去掉该UPDATE的乐观锁写法）后，59/61/62/63/66 五条应同时转绿，D 组可达 11/11。

## 八、`platform-verify.sh`：**208/208 全绿，exit 0**（修前 151/198、47 红）

三处夹具修复（断言数 198 → 208，只增不减）：

| # | 修法 | 位置 |
|---|---|---|
| 1 | 新增 `pdr_review_pass()`：`POST /requirements/{id}/pdr-review`（`{reviewerIds:[pm,tl1,dev1,dev2],mode:"MODE_ALL"}`，201）→ 每人一条 `{decision:"PASS",category:"RANGE"}` → `POST /pdr-reviews/{rid}/conclude` → 断言 `.review.status=APPROVED`；在 PDR 与 D9 准入链 PDR 各调一次 | `scripts/platform-verify.sh:265` |
| 2 | 新增前/后两条 `allowedActions` 断言：评审前 `.view.pdrReview.review.status=NONE` 且 `allowedActions` 无 `schedule`；**评审+评估双门都放行后** `allowedActions` 含 `schedule` | 同上两处调用点 |
| 3 | 新增 `assign_vendor_for_estimate()`：r41 新门是「权限→表单→重复→评审→**厂商**」，评估前必须已指派承接厂商，否则 `409 VENDOR_NOT_ASSIGNED`；用 admin 指派（唯一 ALLOW 角色）并还原调用者 token，在 PDR/RD A/唯一性 RD/D9 PDR/D9 RD 五处 estimate 前调用 | `scripts/platform-verify.sh:177` |

> 顺带发现并修掉的脚本 bug：评审人 id 取 `.id // .userId`，但 `GET /auth/me` 是 `{"user":{…}}` → 改成 `.user.id`（原来 4 个 id 全是空，评审人列表为空 → 评审单直接走 fallback）。

## 九、teardown 逻辑（`pdr-flow-verify.sh`）

- 触发：脚本正常结束、`pdr_abort`、`exit 2`、Ctrl-C（`trap on_exit EXIT` + `INT/TERM`），且 `TEARDOWN_DONE` 幂等保护。
- 范围：本轮造的全部 id —— `BUS/BUS2/PDR_A/B/D/E/F/H/RD` 及它们的评审单/意见/任务/附件/流程/通知/项目。
- 顺序（库里没有外键，仍按依赖删）：
  1. `pdr_review_opinion`（按 review_id 与 reviewer 兜底）
  2. `attachment`（`biz_type='PDR_REVIEW'` 且 `biz_id ∈ 评审 id`；另清 `REQUIREMENT`）
  3. `wf_history` → `wf_cc` → `wf_task` → `wf_instance`（按 biz_id/biz_type）
  4. `notification`（biz_id ∈ 需求/评审）
  5. `task` → `pdr_review`
  6. 需求子表（defect/test_execution/test_plan_case/test_case/req_change_item/req_change/artifact_record/automation_event/automation_run/build_record/mr_sync/quality_gate_snapshot/release_requirement/deploy_order_requirement/integration_sync_log/wiki_page_link/requirement_domain_rel/requirement_tag）
  7. `requirement` → `project_member` → `project`
- 删不掉的残留（权限/触发器等）**不静默**：把需求标题打上 `[残留 PDR${RTAG}]` 前缀，并在收口打印 `本轮残留 <n> 条：<表#id,…>`；全删完则打印 `teardown 校验：本轮残留 0 条（需求/评审/意见/任务/附件/流程/通知/项目）`。
- 本轮实测：`teardown 已删除：需求 9 评审 7 意见 19 任务 3 附件 1 流程实例 5 通知 56 项目 1`，随后 `platform-demo-reset` **连跑两次都 80/80**（§十）。

## 十、其余三套门禁

| 门禁 | 结果 | 说明 |
|---|---|---|
| `bash scripts/platform-demo-reset.sh`（第 1 次） | **exit 0，80/80** | 紧接 pdr-flow 之后跑，证明 teardown 真的清干净了 |
| `bash scripts/platform-demo-reset.sh`（第 2 次） | **exit 0，80/80** | 连跑第二次仍 80/80 |
| `bash scripts/rbac-verify.sh` | **exit 0，113/113** | 11 段全绿 |
| `bash scripts/access-verify.sh` | **exit 0，219/219** | 见下方说明（本轮改了一处基线） |
| `make lint` | **exit 0，25 项全绿** | 含脚本语法/插值检查 |

**access-verify 的一处改动（不是放松口径）**：首跑 `FAIL R1 导航条目数漂移：侧栏 39（期望 38）`。原因是前端同事已把 `评审中心(pdr.review)` 入口加进 `web/src/layouts/nav.ts`（`git diff web/src/layouts/nav.ts` 可见），且 `MenuRegistry` 已登记 `pdr.review`（a3 R2/R3/R10 全 PASS，说明入口与路由/菜单矩阵同源）；只有 `scripts/tests/access/a3-nav-audit.mjs` 的 `SIDEBAR_BASELINE=38` 是旧基线。按该规则自带的修复指引把基线改为 39（并写明「数字只是让悄悄多一条入口先被看见一次，pageCode 仍由 R2/R3/R10 逐条核对」），复跑 **219/219**。

## 十一、复跑命令

```bash
bash scripts/pdr-flow-verify.sh          # 54/66，exit 1（12 条后端缺口见 §七）
bash scripts/platform-verify.sh          # 208/208，exit 0
bash scripts/platform-demo-reset.sh      # 80/80，连跑两次均 exit 0
bash scripts/rbac-verify.sh              # 113/113，exit 0
bash scripts/access-verify.sh            # 219/219，exit 0
make lint                                # 25 项，exit 0
```
