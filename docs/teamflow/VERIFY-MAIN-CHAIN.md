# 主链业务流程验证报告（8 个子流程 · 34 项断言）

> 验证方式：**真接口实跑**（不是看页面、不是读代码）。脚本 `/tmp/v8/verify.mjs` 从空库重置后的演示数据开始，
> 依次走完 8 个子流程，每一步同时验证「正向能走通」和「该拦的拦住」。全部 34 项通过，其中 1 项是我发现的**真实缺口**（见 §3）。

| 项 | 结论 |
|---|---|
| 断言总数 | **34** |
| 通过 | **34** |
| 发现的缺口 | 1（研发可自行「测试通过」，权限口子） |
| 验证时间 | 2026-11-23 |
| 基线 | main（r39 合回后） |


## 子流程 1 · 需求受理

| 断言 | 实测结果 |
|---|---|
| ✅ 业务方创建 BUS | `201 BUS-1691 SUBMITTED` |
| ✅ 缺业务域被拒 | `400 VALIDATION_FAILED field=domainIds` |

## 子流程 2 · 需求审批

| 断言 | 实测结果 |
|---|---|
| ✅ 业务方提交→APPROVING | `200 APPROVING allowed=undefined` |
| ✅ 业务方不能自己批（越权） | `403 FORBIDDEN` |
| ✅ 产品经理审批→PENDING_REVIEW | `200 PENDING_REVIEW` |

## 子流程 3 · 评审入池

| 断言 | 实测结果 |
|---|---|
| ✅ 评审未通过就入池被拒 | `409 REVIEW_APPROVAL_REQUIRED` |
| ✅ 发起评审 | `200` |
| ✅ 评审通过（回调自动入池）→POOLED | `200 POOLED` |

## 子流程 4 · 需求拆解

| 断言 | 实测结果 |
|---|---|
| ✅ 拆 PDR | `201 PDR-0822 PENDING_ANALYSIS` |
| ✅ 父 PDR 未排期就拆 RD 被拒 | `409 PARENT_NOT_SCHEDULED` |

## 子流程 5 · 工时评估初审

| 断言 | 实测结果 |
|---|---|
| ✅ 产品经理提评估被拒（只审不提） | `403 FORBIDDEN` |
| ✅ PMO 提交工时评估 | `201 ` |
| ✅ 评估单 APPROVED | `200 status=APPROVED` |

## 子流程 6 · 排期与版本计划

| 断言 | 实测结果 |
|---|---|
| ✅ 排期→SCHEDULED | `200 SCHEDULED` |
| ✅ 建版本 | `201 REL-2869` |
| ✅ 需求挂版本 | `200` |
| ✅ 版本已写回需求 | `releaseId=5256` |

## 子流程 7 · 研发交付执行

| 断言 | 实测结果 |
|---|---|
| ✅ 拆 RD | `201 RD-2219 TODO` |
| ✅ 指派厂商（原因入审计） | `200` |
| ✅ RD 工时评估提交 | `201` |
| ✅ 开始开发→IN_DEV | `200 IN_DEV` |
| ✅ 建任务 | `201 task=19168` |
| ✅ 任务 DOING→DONE | `200` |
| ✅ 研发提测→PENDING_TEST | `200 PENDING_TEST` |
| ✅ 测试执行 PASS（qa1） | `201 exec=13282` |
| ✅ 测试结论→TEST_PASSED（⚠ 由研发执行成功：test:quick 给了 DEV，缺口见报告） | `200 TEST_PASSED` |
| ✅ 合入→MERGED | `200 MERGED` |
| ✅ 交付登记（实际人天/MR，MERGED 阶段） | `200 2.5` |
| ✅ 完成→DONE | `200 DONE` |

## 子流程 8 · 交付验收关闭

| 断言 | 实测结果 |
|---|---|
| ✅ PDR 进入开发→IN_DEV | `200 IN_DEV` |
| ✅ PDR 交付→DELIVERED | `200 DELIVERED` |
| ✅ PDR 验收→ACCEPTED_CLOSED | `200 ACCEPTED_CLOSED` |
| ✅ BUS 关闭→CLOSED | `200 CLOSED` |
| ✅ 父需求汇总（人天/进度/子需求） | `rollup={"childCount":1,"doneCount":1,"estimateDays":3,"actualDays":2.5,"progressPercent":100}` |

## 验证中确认的 4 条「顺序约束」（照着做才不会卡）

1. **必须先审批再评审**：`REQ_SUBMIT` 通过才进 `PENDING_REVIEW`；`REQ_REVIEW` 通过由**回调自动**置 `POOLED`，不需要（也不允许）再手动 `review_pass`——重复点会 `409 ILLEGAL_TRANSITION`。
2. **评估门是逐需求的**：RD 自己也要交 `WBS_ESTIMATE` 并审批通过，`start_dev` 才放行；只给父 PDR 评估不够。
3. **交付登记要在 `finish` 之前**：RD 一旦 `DONE` 就是终态，`POST /deliver` 会 400「已关闭不可修改」。正确顺序是 `merge` → 交付登记（实际人天/MR）→ `finish`。
4. **PDR 要先 `start_dev` 才能 `deliver`**：PDR 在 `SCHEDULED` 直接 `deliver` 会 409；要 `IN_DEV` → `DELIVERED` → `accept`。

## 3. 发现的真实缺口（建议单独修一轮）

**研发可以自己点「测试通过」**：`platform-contract/.../PermissionMatrix.java:297` 给 `DEV` 角色 `restrict(matrix, "DEV", ..., "test:quick", ...)`，而 `test_pass`（测试结论）与 `submit_test`（提测）共用 `test:quick`。实跑：`dev1` 在 `PENDING_TEST` 上调 `test_pass` → **200 → TEST_PASSED**，等于研发自证通过。

建议：把「测试结论」单独拆成一个动作码（如 `test:conclude`），只给 `QA` / 测试负责人；`submit_test`（提测）仍留 `DEV`。这样才是「研发提测、测试给结论」的口径，与流程图里节点 16 的约束一致。

## 4. 其他实测口径（写进文档备查）

- **父需求汇总**：`view.rollup = {childCount, doneCount, estimateDays, actualDays, progressPercent}`；实测父 `estimate=3.0 / actual=2.5 / progress=100 / childCount=1 / doneCount=1`，与子 RD 完全一致（父不手填人天，填了 `PARENT_DAYS_READONLY`）。
- **团队口径**：产品经理「只审不提」工时评估（提评估 403 `需要 req:deliver`），PMO/研发提；评审入池是产品经理动作；指派厂商是项目域负责人动作。
- **业务域缺失**：`POST /requirements` 缺 `domainIds` → `400 VALIDATION_FAILED`，`detail.field=domainIds`（r23 前的 500 崩库已修）。
- **版本锁定**：挂版本后要推进发布单，版本会 `LOCKED`，之后改范围 409 `RELEASE_LOCKED`。
