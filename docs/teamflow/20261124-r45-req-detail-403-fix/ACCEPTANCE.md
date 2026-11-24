# r45 需求详情页 403 修复 · 验收报告

- 任务夹：`docs/teamflow/20261124-r45-req-detail-403-fix/`
- 分支 / HEAD：`feat/detail-approval-silent` / `e842192`（按 ADR-2026-08-27，工作区即交付面，收口提交由 host 执行）
- 验收人：产品经理（验收负责人）· 验收时间：2026-11-24
- 依据：PRD 15 条 AC、TECHNICAL 蓝图、QA-REPORT（可验收）、独立复跑与目检

## 1. 结论

**通过。** 审批可见性已与页面准入解耦：dev1/qa1/vendor1 打开自己可见的需求详情时，审批实例 403 只在审批页签内降级成唯一文案，URL 保持 `/requirements/{id}`、全程 0 次 `/403` 导航；非 403 仍走统一错误与重试，主数据越权仍落全局 `/403`；服务端零 diff，非参与实例仍 403 且不泄漏。实现与架构蓝图逐项吻合，无重复实现、无该抽未抽；本轮新增缺陷 0。

## 2. 逐条 AC 核对

| AC | 独立复核方式与结果 | 结论 |
|---|---|---|
| AC-1 | 独立浏览器探针（当前夹具 647625/647630，实例 45762/45771 真 403）：标题/动作条渲染、URL 保持 `/requirements/{id}?tab=approval`、0 次 `/403`；截图佐证 | 通过 |
| AC-2 | 探针文案逐字一致；`grep -rn` 全 `web/src` 仅 1 处（`ApprovalTab.vue:61`），主审批区 `EmptyState` 与评估块共用；审批区无「加载失败/重试」 | 通过 |
| AC-3 | 探针 B 注入审批实例 500：不出现降级文案，仍「审批信息暂不可用」+ 重试，URL 不变、不跳 403 | 通过 |
| AC-4 | 本轮 diff 未触及 `allowedActions`/动作矩阵/`actOnRequirement`；截图动作条 3 个按钮正常；动作矩阵由 `platform-verify` 199/199（dev 两跑）覆盖。真实状态流转点击留验收演示（避免改演示数据），见 §5 | 通过（门禁级） |
| AC-5 | 探针 D：vendor1 对 647630 打开详情，真 403 页内降级、动作条在；a8 R5/R6 通过；厂商隔离未放宽 | 通过 |
| AC-6 | `git diff -- req-service/src/main/java db/migration` 为空；a8 N1–N3：三角色对非参与实例 45762 仍 403 `FORBIDDEN`「无权查看该审批实例」，body 不含 instance/document/timeline | 通过 |
| AC-7 | 探针 C：注入 `getRequirement` 403 → 仍跳 `/403?from=...&code=FORBIDDEN`；详情页 `getRequirement` 保持裸调用 | 通过 |
| AC-8 | 详情页 3 处 `getApprovalInstance` + 1 处 `listApprovalMine` 全部单行带 `{silentForbidden:true}`；`getRequirement` 不带；变更计数、字典为既有 silent；`client.ts` 注释含 lint L23 / access a8 / getRequirement 锚点 | 通过 |
| AC-9 | 独立执行 `bash scripts/tests/lint.sh` → **25/25**，L23 含负向自检；抽掉一处 silent 或加裸 `http.` 必红（dev/QA 负向探针 3/3、a8 变异 12/13 exit 1） | 通过 |
| AC-10 | 独立执行 `bash scripts/access-verify.sh` → **219/219**（a0–a8 九段，a8 **13/13**，分段下限生效）；`... a8` 单跑 13/13 两次 | 通过 |
| AC-11 | 本人复核 lint 25/25、access 219/219；QA 各两次一致、dev 两次一致；其余门禁采信 dev 两跑（199/132/113/21/44/80×2/36/46/515 + web typecheck/build 0 error），本轮服务端零 diff、回归面未变 | 通过 |
| AC-12 | 目检 `evidence/dev1-detail.png`、`qa1-detail.png`：均 1280×800、同一需求 DEMO-R-04「订单审批页面」、标题/状态/动作条与逐字文案同屏，身份可辨（研发-赵 / 测试-孙），非 admin 代截 | 通过 |
| AC-13 | `evidence/negative-403.txt` 三段含 HTTP 状态行、`{"code":"FORBIDDEN","message":"无权查看该审批实例"}`、账号/需求/实例、token 脱敏；当前夹具实例 45762 独立复现同码同文案 | 通过 |
| AC-14 | `git status` 仅 9 改 + 2 增（均白名单）；`pom.xml`/`web/package.json`/lock/`db/migration`/`req-service` diff 为空；根目录无散落脚本；提交信息模板含 runId `tf-mwues0su-fpi3ea` 与任务夹，按 ADR 由 host 收口 | 通过 |
| AC-15 | `REQUIREMENT-FLOW.md` §5.7 新增「审批可见性 ≠ 页面准入」+ 依据表 3 行；`DELIVERED-MODULES.md` 补 r45 行；`memory.md` 同主题替换 r8 行（numstat 1/1，无新增 changelog）；`AGENTS.md` 0 diff | 通过 |

## 3. 架构一致性（M3 门禁）

- **遵循蓝图**：`req.ts` 的 `getApprovalInstance(id, extra?)` / `listApprovalMine(params, extra?)` 均以 `undefined, extra` 透传（旧调用点默认行为逐字不变）；`client.ts` 只升级注释、403 跳转逻辑零改动；详情页 403 收敛为 `approvalForbidden`/`estimateForbidden` 布尔并在 loader 开头复位，非 403 仍 `messageOf` + `ErrorState`；文案单点 `FORBIDDEN_HINT` 且 `forbidden` 分支先于 `error`；lint L23 与 a8 13 条、聚合器九段均按方案落地。
- **无重复实现 / 无该抽未抽**：未新建模块，未复制请求封装；两处 `status === 403` 同构判定是蓝图显式接受的取舍（由 a8 S3 静态钉住），文案与 api 封装各只有一个来源；审批中心、变更详情未传 `extra`，默认 403 跳转保持不变。
- **结论**：无 P1 架构问题；无需返工的结构性改动。

## 4. 独立复核证据（本轮实际执行）

- `bash scripts/tests/lint.sh` → 25/25（L23 ok，负向自检内建）。
- `bash scripts/access-verify.sh` → 219/219；`bash scripts/access-verify.sh a8` → 13/13（含 N1–N3 真 403）。
- `QA_REQ_DEV=647625 QA_INST_DEV=45762 QA_REQ_VENDOR=647630 QA_INST_VENDOR=45771 node .../qa-r45-branch-probe.mjs` → **22/22**（A 真 403 / B 注入 500 / C 主数据 403 / D vendor1），覆盖 AC-1/2/3/5/7。
- 目检两张 1280×800 截图（同需求、真实 403 触发内联文案）。
- 红线复核：`git diff` 白名单外为空；`.gitignore` 未忽略 evidence PNG。

## 5. 缺陷与遗留

| 项 | 级别 | 说明 | 处理 |
|---|---|---|---|
| D-1 | P2（存量，HEAD 同源） | 审批主区 `ErrorState` 未转发 `retry`，点「重试」不发新请求 | 非本轮引入，不阻塞；建议下轮以 QA 6/6 判据修复 |
| O-1 | P3 | 403 降级时概览「审批进度」卡仍显示「尚未发起审批」（PRD R6 已接受） | 记录，不处理 |
| O-2 | P3 | `EmptyState` 标题「无权查看审批进度」为独立字面量（正文仍单点） | 记录，不处理 |
| O-3 | P3 | 403 判定未复用 `ApiError.isForbidden`（全仓同风格，蓝图接受） | 记录，不处理 |
| 遗留 | — | AC-4 真实动作流转（开始开发/提交测试/测试通过）本轮未点击，避免改演示数据；动作链路零 diff、矩阵门禁 199 覆盖 | 验收演示时按角色实点一次 |
| 遗留 | — | 提交动作按 ADR-2026-08-27 由 host 在验收后统一收口 | host 收口 |

**本轮新增缺陷：0。**

## 6. 验收意见

交付面正确、门禁可复现、负向可红、证据充分：修复解决了「非审批参与人打不开自己需求详情」的真实缺陷，同时没有放宽权限模型，也没有把主数据越权误吞。同意通过，可进入收口提交/合并流程。

验收结论：✅ 通过
