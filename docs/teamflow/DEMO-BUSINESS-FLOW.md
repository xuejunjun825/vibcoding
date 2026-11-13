# 业务需求全流程演示脚本（提报 → 评审 → 工时/版本 → 拆解 → 执行 → 关闭）

> 生成时间：2026-11-13 · 基线 `main` = `c3e4a79`（r16 合并后）· 环境 `make platform-up`（网关 8080 / 需求 8082 / 交付 8083 / 前端 5173 / MySQL `devops_platform`）
> 本文所有状态、数字、截图都是在本机真实跑出来的：`BUS-0206`、`BUS-0207` 两条链路已完整走完并留痕。

---

## 1. 角色速查

| 账号 | 姓名 / 角色 | 在这条流程里做什么 | 密码 |
|---|---|---|---|
| `biz1` | 陈业务 / 业务部门 | 提报业务需求、看自己需求的进度 | `Passw0rd!` |
| `pm` | 产品经理-张 / PRODUCT | 提交评审、评审入池、拆解产品需求、推进状态 | `Passw0rd!` |
| `tl1` | 项目域负责人-李 / PMO | 分配承接厂商、排期、交付推进 | `Passw0rd!` |
| `dev1` | 研发-赵 / DEV | 反馈预估工时、建任务、开发 | `Passw0rd!` |
| `qa1` | 测试-孙 / QA | 快速测试记录、提缺陷、验证关闭 | `Passw0rd!` |
| `admin` | 平台管理员 | 全量视图、追溯矩阵、版本门禁 | `Admin@12345` |

租户统一 `t1`；接口直连需 `Authorization: Bearer <token>` + `X-Tenant-Id: 1`。

---

## 2. 六步流程（UI 点击路径 + 状态变化）

状态机速记：`BUS` 提报 → 审批 → 待评审 → 入池 →（拆解）→ 关闭；`PDR` 待分析 → 已排期 → 开发中 → 已交付 → 验收关闭；`RD` 待开发 → 开发中 → 待测试 → 测试通过 → 已合入 → 完成。

### 第 1 步：业务需求提报（`biz1`）

- **点哪里**：`需求池` → 右上 `提报需求` → 类型选「业务需求」，填标题/期望交付/所属域 → 提交
- **结果**：生成 `BUS-xxxx`，状态 **已提报（SUBMITTED）**
- **谁看得到**：提报人、本企业负责人、产品经理；厂商角色看不到（域×厂商隔离）
- 截图：`demo-shots/flow-2-bus-submitted.png`

### 第 2 步：需求评审入池（`pm` 提交 + 审批 + 评审）

1. `pm` 打开该业务需求 → `提交审批` → 状态 **审批中（APPROVING）**，生成 `REQ_SUBMIT` 审批单（节点「产品评审」）
2. `pm` 在 `审批中心` → `我的待办` 通过 → 状态 **待评审（PENDING_REVIEW）**
3. `pm` 点 `开始评审` → 生成 `REQ_REVIEW` 审批单 → 通过 → 状态 **已入池（POOLED）**，进入需求池

> 演示话术：提报和评审是两个动作，审批单在 `审批中心` 留有完整流转记录（谁、什么时间、什么意见）。

### 第 3 步：研发反馈预估工时 + 版本计划（`dev1` + `pm`）

- **工时反馈**：`dev1` 打开发给自己的**叶子研发需求**（`RD`），在「预估人天」填 5.5 → 保存。
  - 父需求（`BUS`/`PDR`）的人天是**只读汇总**：`BUS-0207` 立即变成 `预估 5.5 人天`（实测返回 `PARENT_DAYS_READONLY` 保护，父需求不可手填）
- **版本计划**：`pm` 对产品需求点 `排期` → 选版本 `DEMO-REL-1` → 状态 **已排期（SCHEDULED）**
- **版本视图**：`版本计划` → `演示版本 2026-11`：需求总数 8、预估人天 50、完成度 13%、工作项列表 + 里程碑 + 风险项 + 集成设置
- 截图：`demo-shots/flow-6-release.png`

### 第 4 步：拆解为产品需求 / 研发需求（`pm`）

- 业务需求详情 → `拆解` → 生成产品需求 `PDR-0096`（状态「待分析」）
- 产品需求详情 → `拆解` → 生成研发需求 `RD-0903`（状态「待开发」，此时可填预估人天 6.5）
- 约束：子必须挂父；一件父需求可拆多个子；父详情页看子清单 + 完成率；`BUS` 的预估/实际人天全部来自子级汇总
- 截图：`demo-shots/flow-1-bus-detail.png`（业务方视角：子需求 1、预估 6.5 人天、已关闭）

### 第 5 步：研发执行（`dev1` + `pm`）

1. `tl1` 分配承接厂商（`assign-vendor`，必须填原因）：`RD-0903` → 甲方科技（VEN-A）
2. `pm` 点 `开始开发` → `RD` 状态 **开发中（IN_DEV）**
3. `dev1` 在 `迭代/任务` 建任务「签署页接入签章 SDK」（16 小时），拖到 `进行中` → `已完成`（完成率 100% 回写）
4. `pm` 点 `提测` → 状态 **待测试（PENDING_TEST）**
5. `qa1` 在需求详情 `测试` 页做「快速测试记录」`PASS`（也可走正式测试计划：用例库 → 测试计划 → 执行 → 测试报告）

### 第 6 步：测试、缺陷、上线关闭（`qa1` + `pm`）

- `qa1` 提 `P1` 缺陷「签章回调重放导致重复签署」（关联 `RD-0903`）→ 处理 → 提交验证 → 验证通过 → **已关闭（CLOSED）**
- `pm` 点 `测试通过` → `RD` **测试通过（TEST_PASSED）** → `合入` → **已合入（MERGED）** → `完成` → **DONE**
- `tl1` 对产品需求点 `交付` → **已交付（DELIVERED）** → `pm` 验收 → **验收关闭（ACCEPTED_CLOSED）**
- `pm` 关闭业务需求 → `BUS-0206` **已关闭（CLOSED）**，父子进度 **100%**
- 追溯验证：`追溯` 页能看到 `需求 → 任务 → MR → 执行 → 缺陷 → 发布单 → 版本` 的缺口与齐备情况
- 截图：`demo-shots/flow-4-traceability.png`、`demo-shots/flow-5-defects.png`

---

## 3. 实测结果（本次演示跑出来的真实数据）

| 编号 | 类型 | 最终状态 | 预估人天 | 进度 |
|---|---|---|---|---|
| `BUS-0206` 演示-合同电子签章对接 | 业务需求 | **CLOSED** | 6.5（子级汇总） | 100% |
| `PDR-0096` 电子签章能力接入采购合同流程 | 产品需求 | **ACCEPTED_CLOSED** | 6.5 | 100% |
| `RD-0903` 采购合同签署页接入电子签章 SDK | 研发需求 | **DONE** | 6.5 | — |
| `DEFECT-0149` 签章回调重放导致重复签署 | 缺陷 P1 | **CLOSED** | — | — |
| `BUS-0207` 演示-供应商门户单点登录 | 业务需求 | **POOLED**（演示用中间态） | 5.5 | 0% |
| `PDR-0097` 供应商门户 SSO 接入方案 | 产品需求 | **SCHEDULED** | — | — |
| `RD-0904` 门户登录改造为 OIDC | 研发需求 | **TODO** | 5.5 | — |

审批留痕：`BUS-0206` 依次产生 `REQ_SUBMIT`（实例 383，产品评审 → 通过）与 `REQ_REVIEW`（实例 384，通过后入池）。

---

## 4. 已知卡点（演示前须知，属实现缺口，不是数据问题）

### 卡点 1（P1，会打断演示）：业务方点「提交审批」直接 403

- 现象：`biz1` 在「已提报」的业务需求上能看到 `提交审批` 按钮，点下去弹「无权执行该操作」，整页被 403 页顶掉。
- 原因（两处叠加）：
  1. `platform-common/.../security/PermissionRouteTable.java:136` 把 `POST /requirements/{id}/actions` 统一要求 `req:review`；而 `BUSINESS` 角色只有 `req:report:bus`（`PermissionMatrix.java:237`），没有 `req:review`。
  2. `allowedActions` 没有按调用者权限过滤，所以按钮照常显示；`web/src/api/client.ts` 对 403 默认跳全局 403 页（未标 `silentForbidden`）。
- 影响：**业务方无法自己提交审批**——演示时必须由 `pm` 代为点「提交审批」，与「业务方提报」的直觉冲突。
- 截图：`demo-shots/flow-7-biz-submit-403.png`
- 建议修法（三选一）：① 给 `BUSINESS` 补一个「提交自己的需求」权限码，动作端点按动作细分权限；② `allowedActions` 按权限过滤，前端不显示无权动作；③ 该动作前端标 `silentForbidden` + 提示「请联系产品经理提交评审」。
- 临时绕法（今天演示可用）：`biz1` 提报后，切 `pm` 账号点「提交审批」。

### 卡点 2（P3，讲清楚即可）：工时只有叶子需求能填

父需求（业务/产品需求）的人天是子级汇总的只读值，接口会回 `PARENT_DAYS_READONLY`。这是设计如此，演示时说明「研发反馈工时填在研发需求上，父需求自动汇总」即可。

---

## 5. 演示脚本（10 分钟版）

1. （`biz1`）需求池 → 提报需求 → 业务需求「演示-供应商门户单点登录」→ 提报
2. （`biz1`）详情页展示：状态「已提报」、域、期望交付、审批进度占位
3. （`pm`）同一需求 → 提交审批 → 审批中心待办 → 通过 → 开始评审 → 通过 → 状态「已入池」
4. （`pm`）拆解 → 产品需求「SSO 接入方案」→ 再拆解 → 研发需求「登录改造为 OIDC」
5. （`dev1`）研发需求 → 预估人天 5.5 → 保存；回到业务需求详情看「预估 5.5 人天（汇总）」
6. （`pm`）产品需求 → 排期 → 选 `演示版本 2026-11`；打开版本计划看工作项与预估人天
7. （`dev1`）任务看板建任务、拖到进行中/完成；（`pm`）开始开发 → 提测
8. （`qa1`）快速测试记录 PASS；提一个 P1 缺陷 → 处理 → 验证关闭
9. （`pm`）测试通过 → 合入 → 完成；产品需求交付 → 验收；业务需求关闭（进度 100%）
10. （`admin`）追溯矩阵 + 版本质量门 + 价值流：看这条需求在各环节的留痕

---

## 6. 复现命令（API 版，便于录屏或脚本化）

```bash
GW=http://127.0.0.1:8080
tok(){ curl -s -X POST $GW/api/req/auth/login -H 'Content-Type: application/json' \
  -d "{\"tenantCode\":\"t1\",\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'; }
B=$(tok biz1 'Passw0rd!'); P=$(tok pm 'Passw0rd!'); D=$(tok dev1 'Passw0rd!'); Q=$(tok qa1 'Passw0rd!')

# 1 提报（业务方）
curl -s -X POST $GW/api/req/requirements -H "Authorization: Bearer $B" -H "X-Tenant-Id: 1" \
  -H 'Content-Type: application/json' \
  -d '{"type":"BUS","title":"演示-供应商门户单点登录","description":"接入集团 SSO","domainIds":[1],"priority":"P2"}'

# 2 提交评审 → 审批 → 评审入池（产品经理；注意卡点 1，业务方无 req:review）
#   POST /requirements/{id}/actions            {"action":"submit"}
#   POST /approvals/tasks/{taskId}/actions     {"action":"approve","comment":"同意"}
#   POST /requirements/{id}/actions            {"action":"review_start"}
#   POST /approvals/tasks/{taskId}/actions     {"action":"approve"}
# 3 拆解两级：POST /requirements/{id}/decompose  {"type":"PDR",...} → {"type":"RD","estimateDays":5.5}
# 4 排期：POST /requirements/{id}/actions {"action":"schedule","releaseId":784}
# 5 执行：POST /deliver/tasks → POST /deliver/tasks/{id}/move {"status":"DOING"|"DONE"}
#   POST /requirements/{id}/actions {"action":"start_dev"|"submit_test"|"test_pass"|"merge"|"finish"}
#   POST /requirements/{id}/quick-test {"result":"PASS","note":"..."}
# 6 缺陷：POST /deliver/defects → /defects/{id}/actions start → submit_verify → verify_pass
```

---

## 7. 重置与清理

```bash
make platform-demo-reset   # 回到 12 条演示需求 / 2 张演示报表 / 通知 4 条，本次演示新增的需求会被清掉
make platform-up           # 若服务未起
```

演示新增数据（`BUS-0206`/`BUS-0207` 及其子需求、`DEFECT-0149`）都在当前演示库里，重置一次即消失；若想保留这轮链路做讲解，请先别跑 `platform-demo-reset`。

---

## 8. 截图索引

| 文件 | 内容 |
|---|---|
| `demo-shots/flow-1-bus-detail.png` | 业务方视角：业务需求详情（子需求 1、预估 6.5 人天、已关闭） |
| `demo-shots/flow-2-bus-submitted.png` | 业务方视角：刚提报的 `BUS-0207`（已提报 + 提交审批按钮） |
| `demo-shots/flow-3-pm-detail.png` | 产品经理视角：同一条需求 |
| `demo-shots/flow-4-traceability.png` | 追溯矩阵：需求 → 任务/MR/构建/执行/缺陷/Sonar/发布单/版本 |
| `demo-shots/flow-5-defects.png` | 缺陷列表（含本次 `DEFECT-0149`） |
| `demo-shots/flow-6-release.png` | 版本计划详情：8 条工作项、预估 50 人天、完成度 13% |
| `demo-shots/flow-7-biz-submit-403.png` | 卡点 1 证据：业务方点「提交审批」→ 403 页 |
