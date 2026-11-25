# 需求池运营（r47 设计与契约）

> 目的：把「需求池」从"一个列表"变成"能运营的池子"——提报时能看见相似需求、业务方能对已有需求表达「我也需要」、重复需求能合并而不是各写一条。
> 基线：`main = f43b491`（r46 后）。本文件是给后端/门禁/前端子代理的**契约**，实现与断言都以此为准。

## 1. 目标行为（用户视角）

| 场景 | 现在 | 改为 |
|---|---|---|
| 业务方提报「供应商门户要单点登录」 | 直接建新需求，没人知道已有 3 条类似需求 | 提报表单在标题输入后提示「找到 5 条相似需求」，可展开看状态/所属域/提报人，可直接给某条点「我也需要」 |
| 产品经理看某条 POOLED 需求 | 只有子需求和动态 | 右侧显示「32 人需要」+ 最近 5 个提报单位，可展开全部 |
| 同一诉求被写了 3 条 | 三条各自跑流程，工时/交付分散 | 产品经理可把重复的合并到主需求：被合并的需求变 `CLOSED`（`close_reason=MERGED`），其跟单人/子需求/变更记录迁到主需求，全程留痕 |

## 2. 数据模型（迁移从 `V22` 起）

```sql
-- V22__requirement_follow.sql
CREATE TABLE requirement_follow (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  requirement_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  org_id BIGINT NULL,
  note VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_follow (tenant_id, requirement_id, user_id),
  KEY idx_follow_req (tenant_id, requirement_id),
  KEY idx_follow_user (tenant_id, user_id)
);

-- V23__requirement_merge.sql
ALTER TABLE requirement
  ADD COLUMN merged_into_id BIGINT NULL,
  ADD COLUMN close_reason VARCHAR(32) NULL,
  ADD KEY idx_req_merged (tenant_id, merged_into_id);
```

- `merged_into_id`：被合并需求指向主需求（`CLOSED` 时才有值）；主需求不动。
- `close_reason` 取值：`MERGED`（本轮新增）、`REJECTED`、`DONE`、`NULL`（历史行不回填）。
- 不新增表存合并历史：合并写 `audit_log`（`REQUIREMENT_MERGED`，detail 带 `targetId/reason/movedFollows/movedChildren`）。

## 3. 接口

### 3.1 相似需求

```
GET /api/req/requirements/similar?title=&description=&domainId=&limit=5
GET /api/req/requirements/{id}/similar?limit=5
```

- 两个入口返回同一结构：`{items:[{id,code,type,title,status,domainId,reporterName,orgName,score,followCount}], total}`。
- 打分（`RequirementSimilarity` 单一实现，SQL + Java 各不得另写一套）：
  - 标题：按 2-gram 切分，命中权重 **3**；描述命中权重 **1**；同 `domain_id` **+1**；同 `type` **+0.5**。
  - `score = round(3*titleHit/titleTokens + 1*descHit/descTokens + domainBonus + typeBonus, 2)`，`score < 0.15` 的不返回。
- 可见性：复用既有 SQL scope（`/*scope:r*/`），**厂商只看到本厂商相关、二级企业只看本企业及其可见池**；排除自身、排除已合并（`merged_into_id IS NOT NULL`）、排除终态 `CLOSED`。
- 性能：`title`/`description` 走 `LIKE` 预筛（最多 200 行）再打分，p95 ≤ 200ms（10 万需求量级）；不做全表扫描打分。

### 3.2 「我也需要」

```
POST   /requirements/{id}/follow      body {note?}        201 / 200(已跟) / 409(终态)
DELETE /requirements/{id}/follow                           204 / 404
GET    /requirements/{id}/followers?page=&size=            分页 + {total, mine, orgs:[{orgId,orgName,count}]}
```

- 权限：任何能看到该需求的账号都可提交；**终态（CLOSED/MERGED）→ 409 `REQUIREMENT_CLOSED`**。
- 幂等：同一人重复提交返回 **200** 并回当前 `followCount`（不新增行、不报错）。
- `GET /requirements/{id}` 的 `view` 增 `followCount`、`followedByMe`；列表 `items[]` 同名字段（列表页需要显示"N 人需要"）。
- 跟单会写通知：给需求 `reporter_id` 与 `owner_user_id` 各一条站内通知（`REQUIREMENT_FOLLOWED`），同一天同一需求合并成一条（幂等键 `follow:{reqId}:{date}`）。

### 3.3 合并

```
POST /requirements/{id}/merge   body {targetId, reason}
```

- 权限：`req.action.merge`（PLATFORM_ADMIN + 内部 PMO + PRODUCT；VENDOR_PM 不给）。
- 前置：①同类型；②source 与 target 都存在且可见；③source 非终态、target 处于 `POOLED`/`SCHEDULED`/`IN_DEV`；④target 不能是 source 的祖先/后代（成环 400 `MERGE_CYCLE`）；⑤source 已有 `merged_into_id` → 409 `ALREADY_MERGED`；⑥target 已合并 → 400 `TARGET_MERGED`。
- 效果（单事务）：source `status=CLOSED`、`close_reason=MERGED`、`merged_into_id=targetId`；source 的 `requirement_follow` 行迁到 target（按 `(tenant,user)` 去重，冲突丢弃并计数）；source 的子需求 `parent_id` 改为 targetId（保留子需求状态，不改状态机）；写审计 `REQUIREMENT_MERGED` + 通知 source 提报人/跟单人。
- 返回：`{sourceId, targetId, movedFollows, movedChildren, closed:true}`。

## 4. 权限与计数

- 新增动作码：`req.action.follow`、`req.action.merge`（`ActionRegistry` 83 项；`PermissionRouteTable` 三条精确规则插在 `^/requirements.*$` 兜底前）。
- 页/功能码不变（仍 `req.pool` / `req:report:bus` 一族）；`sys_role_permission` 行数 +2 动作 × 11 角色 = **+22**（1980 → 2002），每角色 180 → **182**，动作 81 → **83**。
- 种子默认：`req.action.follow` 全角色 ALLOW；`req.action.merge` 仅 PLATFORM_ADMIN/PMO/PRODUCT ALLOW，其余 DENY。

## 5. 前端

1. **提报表单**：标题输入 500ms 防抖调 `/requirements/similar?title=`，在表单下方出「找到 N 条相似需求」折叠卡（编号/标题/状态/所属域/提报人 + 「我也需要」按钮 + 「仍然新建」）。
2. **需求详情**：右栏新增「需求热度」卡：`N 人需要`、`我也需要`/`已需要`按钮、提报单位 Top5（`orgs`）。
3. **需求池列表**：卡片/行显示 `N 人需要` 徽标（`followCount>0` 才显示）。
4. **合并**：详情页「更多」菜单 →「合并到其他需求」，弹层选目标（搜索 + 列表）+ 必填原因，成功后跳转主需求并 toast「已合并，3 人跟单与 2 个子需求已迁入」。
5. 复用现有组件与令牌；不允许新引入依赖。

## 6. 门禁（新增 `scripts/pool-ops-verify.sh`，`make pool-ops-verify`）

≥24 条断言，分 4 段：

- **P1 相似**（6）：同名标题命中≥3 条且 self 不在结果；跨域同标题 score 低于同域；`limit` 生效；厂商账号只看到本厂商相关；终态/已合并不出现；p95 ≤ 200ms（200 行样本）。
- **P2 跟单**（7）：201 建行 + `followCount` +1；重复 → 200 且仍 1 行；`GET followers` 分页与 `orgs` 聚合正确；`followedByMe` 对本人 true/他人 false；终态 → 409；DELETE → 204 且计数 -1；通知幂等（同日两条 follow 只 1 条通知）。
- **P3 合并**（7）：正常合并 → source CLOSED/MERGED + `merged_into_id`；follow 迁移去重 + `movedFollows` 计数；子需求 reparent；审计 1 条带 targetId；重复合并 → 409；跨类型 → 400；成环 → 400。
- **P4 回归**（4）：`GET /requirements` 列表字段含 `followCount`；详情含 `followedByMe`；无权限账号合并 → 403；`platform-demo-reset` 仍 80/80 且演示数据含 1 条示例跟单。

## 7. 演示数据（`DemoFixture` 增补）

`DEMO-R-02`（POOLED）挂 6 条 follow（biz1/biz2/suite1/po1/vendor1/vpm1），其中 2 条 note；另造 1 条与 `DEMO-R-02` 标题相似度≥0.6 的 PDR 用于演示「相似提示」与「合并」。

## 8. 明确不做

- 不做跨类型合并（BUS→PDR 不允许）、不做批量合并、不做合并回滚（回滚=改回来 + 审计，另开需求）、不做相似度模型训练（纯 2-gram 打分）。
- 不改需求状态机既有迁移；合并不新增状态，复用 `CLOSED` + `close_reason`。
