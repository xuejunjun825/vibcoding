# r45 需求详情页 403 修复 · QA 报告

- 任务夹：`docs/teamflow/20261124-r45-req-detail-403-fix/`
- 分支 / HEAD：`feat/detail-approval-silent` / `e842192`（改动未提交，工作区即交付面）
- 测试时间：2026-11-24
- 结论：**可验收**。15 条 AC 全部通过；发现 1 条**存量** P2（主审批区「重试」按钮点击无效，HEAD 同源、非本轮引入），不阻塞本轮验收；另有 3 条 P3 观察。

## 1. 范围与环境

| 项 | 内容 |
|---|---|
| 交付面 | 9 改 + 2 增：`web/src/api/{client,req}.ts`、`web/src/views/req/RequirementDetailView.vue`、`web/src/views/req/tabs/ApprovalTab.vue`、`scripts/tests/lint.sh`、`scripts/access-verify.sh`、`scripts/tests/access/a8-detail-optional-probe.mjs`（新增）、`docs/teamflow/{REQUIREMENT-FLOW,DELIVERED-MODULES,memory}.md`、本任务夹 |
| 真栈 | 网关 `http://127.0.0.1:8080`、Vite `http://127.0.0.1:5173`、Nacos `8848`（health 均 200） |
| 账号 | dev1 / qa1 / vendor1 / admin（租户 `t1`，密码 `Passw0rd!`） |
| 夹具 | demo-reset 后：dev1 可见 DEMO-R-02=647599（审批实例对三角色 403）、vendor1 可见 DEMO-R-09=647606（实例 45377 对三角色 403）；reset 前另一组 647287/647294 也实测过 |
| 浏览器 | Playwright chromium（headless，1280×800）——沙箱内可启动，非环境限制 |
| 方法 | 静态 diff 审查 + 接口一致性核对 + 真栈浏览器端到端（真实 403 / 注入 500 / 注入主数据 403）+ 门禁复跑 + 文档与 git 卫生 |

## 2. 用例与结果

| AC | 场景 / 方法 | 结果 |
|---|---|---|
| AC-1 | dev1、qa1、vendor1 打开自己的需求详情：标题可见、动作条在、URL 保持 `/requirements/{id}?tab=approval`、0 次 `/403` 导航（探针 A/D，真实 403） | 通过 |
| AC-2 | 审批页签内逐字「无权查看审批进度（仅发起人/审批人/抄送人可见）」；`grep -rn` 全 `web/src` 常量只 1 处（`ApprovalTab.vue:61`），主区与评估块共用；主区无「重试」 | 通过 |
| AC-3 | 注入审批实例 500：不出现内联降级文案，仍渲染「审批信息暂不可用」+ 重试，URL 不变、不跳 403 | 通过（重试按钮可用性另见 D-1） |
| AC-4 | 详情页动作条 3 个按钮照常渲染；本轮 diff 未触及动作码与 allowedActions；动作矩阵由 `platform-verify` 199 覆盖（dev 两跑） | 通过（页面级存在性 + 静态） |
| AC-5 | vendor1 对 647606 真 403，同样页内降级、服务端仍 403（a8 N3 不泄漏 instance/document/timeline） | 通过 |
| AC-6 | `git diff -- req-service db/migration` 为空；a8 N1–N3 三角色对非参与实例仍 `403 FORBIDDEN`「无权查看该审批实例」 | 通过 |
| AC-7 | 注入 `getRequirement` 403 → 仍跳全局 `/403?from=...&code=FORBIDDEN`（主数据未被静默） | 通过 |
| AC-8 | 详情页 3 处 `getApprovalInstance` + 1 处 `listApprovalMine` 全部同一调用带 `{ silentForbidden: true }`（L23 计数 3/3、1/1）；`client.ts` 注释含 L23 / a8 / getRequirement 锚点 | 通过 |
| AC-9 | `bash scripts/tests/lint.sh` → 25/25（[25] L23 ok，含内置负向自检）；QA 连跑 2 次一致 | 通过 |
| AC-10 | `node scripts/tests/access/a8-detail-optional-probe.mjs` → 13/13；`bash scripts/access-verify.sh` → 219/219（a0–a8 九段，a8 13/13）；各跑 2 次一致 | 通过 |
| AC-11 | QA 独立复跑 lint、access、release-flow（static 12/12）、gate-order、rbac、web typecheck+build；其余（platform 199 / negative 132 / unit 515 / demo-reset 80×2 / editability 36 / change 46）采信 dev 两跑（服务端零 diff，回归面未变） | 通过 |
| AC-12 | 任务夹 `evidence/dev1-detail.png`、`qa1-detail.png` 均 1280×800 真实截图；QA 在 reset 后新夹具上复验同样形态（标题/动作条/文案同屏、URL 不变、无重试） | 通过 |
| AC-13 | `evidence/negative-403.txt` 三段含状态行 + `{"code":"FORBIDDEN","message":"无权查看该审批实例"}`、token `<redacted>`；a8 N 段独立复现同码同文案 | 通过 |
| AC-14 | `git status --porcelain` 仅上述 9 改 + 2 增；`pom.xml`/`web/package.json`/lock/`db/migration`/`req-service` diff 为空；根目录无散落脚本 | 通过 |
| AC-15 | `REQUIREMENT-FLOW.md:142` 增 §5.7；`DELIVERED-MODULES.md:39` r45 行；`memory.md:29` 同主题替换（numstat 1/1、无新增 changelog 行） | 通过 |

### 关键证据命令

- `node logs/teamflow/tf-mwues0su-fpi3ea/scripts/qa-r45-branch-probe.mjs` → **22/22**（reset 前后各一次）：A 真实 403（detail=200/instance=403）、B 注入 500、C 注入主数据 403、D vendor1 真 403。
- `bash scripts/access-verify.sh` → **219/219**（两次）；`bash scripts/access-verify.sh a8` → 13/13，N 段实例 45374 三角色 403 不泄漏。
- `A8_WEB_SRC=.../probe/web-mutant node scripts/tests/access/a8-detail-optional-probe.mjs` → **12/13、exit 1**（S2：getApprovalInstance 2/3 带 silent）——a8 不是字符串匹配，抽掉一处静默即红。
- `cd web && npm run build` → exit 0，✓ built in 8.38s；`npx vue-tsc --noEmit` → 0 error。
- `bash scripts/tests/lint.sh` → 25/25，两次。

## 3. 架构与接口一致性（强制项）

| 检查 | 结论 |
|---|---|
| 遵循蓝图 | `req.ts:213` `listApprovalMine(params, extra?)`、`req.ts:225` `getApprovalInstance(id, extra?)`，均把 `extra` 作为第三参交给 `http.get`；`client.ts:220` 的 `get(url, params?, extra?)` 是既有能力，本轮只补注释，**未新建模块/未改 403 跳转逻辑**（`client.ts:185` 仍是 `if (!options.silentForbidden) navigate('/403')`） |
| 调用点 | 详情页 3 处实例探测 + 1 处待办探测全部声明静默；`web/src/views/approval/ApprovalCenterView.vue:278`、`web/src/views/req/RequirementChangeDetailView.vue:115` 未传 extra → 行为逐字不变（默认跳转） |
| 渲染分叉 | `ApprovalTab.vue:363` estimateForbidden 先于 estimateError、`:399` forbidden 先于 error；403 与「加载失败+重试」在数据层即分叉 |
| 接口符号存在 | 导入 `getApprovalInstance`/`listApprovalMine` 均在 `req.ts` 导出；`Partial<RequestOptions>` 由 `client.ts:13` 导出；`ApprovalTab` 的 `forbidden?`/`estimateForbidden?` 已在 props 声明并与父模板 `:forbidden`/`:estimate-forbidden` 同名匹配 |
| 参数形状 | `getApprovalInstance(direct, { silentForbidden: true })` 匹配 `(id, extra?)`；`listApprovalMine({page,size}, { silentForbidden: true })` 匹配 `(params, extra?)` |
| 门禁 API | `lint.sh:697/723` 的 `_page_optional_probe_scan`/`detail_optional_probes_are_silent`、`a8` 的 `check/staticChecks/runtimeChecks/negativeFor`、`access-verify.sh:27` 九段与 `:78` `a8→13`、`:147` 下限 219 均存在且生效 |
| 重复实现 | 未新增重复封装；403 两处同构判定为蓝图显式接受（a8 S3 静态钉住），文案常量单点 |
| **P1 架构问题** | **无** |

## 4. 缺陷与观察

### 缺陷

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-1 | P2（存量，HEAD 同源，非本轮引入） | 需求详情 · 审批页签 | 1) dev1 登录；2) 打开任一需求详情并切到「审批」；3) 使 `/api/req/approvals/mine` 返回 500（探针用路由注入，等价于服务端故障）；4) 点击错误块内「重试」 | 点击「重试」重新发起待办/实例探测，错误可恢复 | 按钮可点但无任何请求发出（`mine` 请求数点击前后均为 1），错误态无法在页内恢复 | AC-3（存量旁证） | `QA_REQ_DEV=647599 node logs/teamflow/tf-mwues0su-fpi3ea/scripts/qa-r45-main-error-retry.mjs` | 6/6 通过，且「点击『重试』真的重新发起探测请求」为 PASS（`/approvals/mine` 请求数 +1） |

说明：`ApprovalTab.vue:406` 的 `ErrorState` 未监听/转发 `retry` 事件（父级只接 `retry-estimate`），`git show HEAD` 同位置一致 → 非本轮回归；修复建议：`<ErrorState ... @retry="emit('retry')" />` + 父级 `@retry="loadApproval"`（改动仍在 AC-14 白名单内）。

### P3 观察（不阻塞）

| 编号 | 严重级 | 功能模块 | 现象 | 关联验收项 | 检测命令 |
|---|---|---|---|---|---|
| O-1 | P3 | 需求详情 · 概览卡 | 403 降级时 `approval` 为 null，概览「审批进度」小卡显示「尚未发起审批；提交后这里显示当前节点与待办人」，与「已有审批实例但无权查看」矛盾（PRD R6 已接受该取舍） | AC-2 延伸 | `grep -n "尚未发起审批" web/src/views/req/tabs/OverviewTab.vue` |
| O-2 | P3 | 审批页签 | `ApprovalTab.vue:400` EmptyState 标题「无权查看审批进度」是 `FORBIDDEN_HINT` 的首句，独立字面量维护；改文案会漂移一处（正文仍单点） | AC-2 | `grep -n "无权查看审批进度" web/src/views/req/tabs/ApprovalTab.vue` |
| O-3 | P3 | 前端约定 | 403 判定用裸 `status === 403`，未复用 `client.ts:48` 的 `ApiError.isForbidden`（全仓 13 处同风格，蓝图接受） | AC-8 | `grep -rn "status === 403" web/src \| wc -l` |

**本轮新增缺陷：0**（D-1 为存量）。

## 5. 人工补测清单

| 项 | 为什么不能自动验 | 方法 / 工具 |
|---|---|---|
| 视觉细节（字体、阴影、遮挡、动画） | 脚本可测尺寸/颜色，审美需人眼 | 打开 `evidence/{dev1,qa1}-detail.png` 复核；或 `make platform-up` 后用浏览器看 `/requirements/647599?tab=approval` |
| 键盘/读屏可达性（focus 顺序、aria 播报） | 需要真实辅助技术 | VoiceOver/NVDA + Tab 走查审批页签内联文案与「重试」 |
| 移动端 100dvh / 软键盘 | 需真机 | iPhone Safari 打开详情页审批页签 |
| 真实 500（非注入）下的审批主区表现 | 需后端故障演练 | 停 req-service 或注入延迟/异常后打开详情页，观察错误提示与重试 |
| 审批中心默认 403 跳转的运行时 | demo 数据中 dev1/admin 待办均为 0，无真实待办行 | 造一条待办后以审批中心账号打开非参与实例；本轮以静态（未传 extra→默认 false + 403 分支未改）论证 |
| 动作真实流转（开始开发/提交测试/测试通过） | 会改演示数据，本轮刻意不点 | 验收演示时按角色实点一次，确认按钮仍按 allowedActions 出现 |

## 6. 门禁复核（QA 独立执行 vs 采信 dev）

| 门禁 | 结果 | 来源 |
|---|---|---|
| `lint` | 25/25 ×2 | QA |
| `access-verify` | 219/219 ×2（a8 13/13） | QA |
| `access a8` 负向自检 | 12/13、exit 1（抽静默必红） | QA |
| `release-flow-verify --static-only` | 12/12 | QA |
| `web typecheck` / `build` | 0 error / built 8.38s | QA |
| `gate-order-verify` | 通过（末尾 B4 219/219 + 退出复位通过；完整计数行未保留） | QA |
| `rbac-verify` | 113/113（独立复跑，11 段下限核对通过） | QA |
| `platform-verify` 199 / `negative` 132 / `unit` 515 / `demo-reset` 80×2 / `editability` 36 / `change` 46 | dev 各两跑一致；服务端零 diff，QA 未重复长跑 | dev 证据 |

注：QA 首轮把 `rbac-verify` 与 `gate-order-verify` 并行执行，后者退出时会跑 `platform-demo-reset`，导致 `rbac` 的 s5 段夹具被中途重置（报 s5 无计数、100/101）。该结果归因于测试编排干扰，非交付缺陷；随后单独复跑 `rbac-verify`，结果以复跑为准。

## 7. 结论

- 修复本身正确：**审批可见性不再是页面准入**——三角色（含厂商）打开自己的需求详情均留在详情页，审批区就地显示唯一文案；非 403 仍走统一错误、主数据越权仍落 `/403`；服务端零 diff、非参与实例仍 403 且不泄漏。
- 门禁与接口一致：L23（25）、a8（13）/access（219）双钉生效且负向可红；蓝图 API 与实现逐项吻合，无重复实现、无该抽未抽的模块。
- **可验收**。唯一 P2 为存量问题（D-1），建议下一轮以 6/6 判据修复；3 条 P3 观察不阻塞。
