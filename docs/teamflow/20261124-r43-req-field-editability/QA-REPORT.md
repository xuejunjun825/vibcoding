# r43 需求字段可编辑性 + 变更记录 · QA 测试报告（修复后复验轮）

- 任务夹：`docs/teamflow/20261124-r43-req-field-editability/`（runId `tf-mwtzd1oa-p2j7xu`）
- 分支：`feat/req-editability-rules`；改动未提交（33 项：24 M / 2 D / 7 新增源码 + 任务夹）
- 被测栈：网关 8080 / req-service 8082 / web 5173；`.run/req-service.jar.fp = 12608897:94882749:1795485178` 与磁盘 jar 一致，且无 java 源新于该 jar ⇒ 在跑服务即当前代码
- 结论：**可验收**。上一轮 3 条缺陷（D-1 P2、D-2 P3、D-3 P3）全部复验关闭，本轮未发现新缺陷；8 项既有门禁 + 新增门禁全部实跑通过，架构与蓝图一致、接口一致、版本库卫生合规

## 1. 范围与环境（第 2 轮：复验）

本轮只做两件事：① 用上一轮同一批检查器复验 3 条缺陷是否真正关闭（回归合同，不重建探针）；② 补上一轮未覆盖的两个面——「文档基线是否被门禁真正守护」与「父需求变更单闭环（F-6）」。功能面、架构面、接口面沿用上一轮已证结论并抽样复核。

| 层 | 本轮复核对象 |
|---|---|
| 契约/服务 | `RequirementEditability` 三数组投影、PUT 两类 409、变更行 `fields[]`（栈上复跑） |
| 前端 | `editability.ts` 禁用文案三支（DESIGN §3.1）、`FieldFlag.vue`、详情/概览/变更 Tab 渲染面（编译期断言） |
| 门禁 | `editability-verify` 36 条 + `--twice` + MUTATE 负向、8 项既有门禁、lint/unit/typecheck/build |
| 文档 | AC-17：`memory.md` 基线/约定、`REQUIREMENT-FLOW.md` §5.6，且被 `editability-verify` S1[⑥] 守护 |

环境限制（沿用上一轮，未变）：本沙箱无法启动真实浏览器（host 对自身进程的 `PROCESS_SET_INFORMATION` 限制使 Chromium crashpad 自终止，`--no-sandbox`/`--disable-breakpad` 均无效），属**环境限制而非交付缺陷**。`t5-live-views-probe.mjs` 本轮实测 `SKIP Chrome CDP http://127.0.0.1:9222 不可达`；纯视觉项入 §6 人工补测清单。

## 2. 上一轮检查器复跑（逐字通过数）

| # | 检查器 | 命令 | 本轮实测 |
|---|---|---|---|
| 1 | 新门禁静态段（6 条，含本轮的文档守护 S1[⑥]） | `bash scripts/editability-verify.sh --static-only` | ✅ 6/6 |
| 2 | 新门禁全量 36 条 6 段 | `bash scripts/editability-verify.sh` | ✅ `EDITABILITY_TOTAL=36 CASE=30 PASS=30 FAIL=0` |
| 3 | 连跑可重复（AC-14） | `bash scripts/editability-verify.sh --twice` | ✅ 两轮均 36/36，输出「两次连跑结果一致」 |
| 4 | 前端单点纯函数（9 reasonCode/6 标记/fail-closed/规范序） | `node --experimental-strip-types logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/t4-frontend-units.mjs` | ✅ `T4-OK 13 checks`（见 §3 探针修正说明） |
| 5 | 前端渲染面静态断言（导出面/三态/禁用/409 分流/11 标签） | `node logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/t4-frontend-static-check.mjs` | ✅ `T4-STATIC 15 checks` |
| 6 | 详情/概览/变更 Tab 编译期断言 | `node logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/t5-views-check.mjs` | ✅ `TOTAL=9 PASS=9 FAIL=0` |
| 7 | D-3 运行时文案（三场景 title 与短句同源） | `node --experimental-strip-types logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/editability-copy-check.mjs` | ✅ `EDITABILITY_COPY TOTAL=5 PASS=5 FAIL=0` |
| 8 | QA 独立探针（与门禁不同源，期望值按 PRD 手写） | `bash logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/qa-r43-independent-matrix.sh` | ✅ `QA_INDEPENDENT TOTAL=27 PASS=27 FAIL=0 SKIP=0` |
| 9 | 父需求变更单闭环（F-6，上一轮 dev 越界修复） | `bash logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/parent-change-order-probe.sh --expect-fixed` | ✅ P1/P2/P3 父/子 CONTENT 均 201、P4 父改人天仍 400、`RESIDUE 0`、`RESULT: ok` |
| 10 | 真栈视图探针 | `node logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/t5-live-views-probe.mjs` | ⏭ SKIP（无 CDP，环境限制，非缺陷） |

探针修正说明（透明披露，非放水）：`t4-frontend-units.mjs` 上一轮把「全锁定」场景的 `editDisabledReason` 硬编码为修复前的旧文案，D-3 修复后该断言必然红。本轮按 DESIGN §3.1 的**三支契约**更新该断言，并**新增**一支断言（仅 CHANGE_REQUIRED 场景 → 「当前状态不能直接修改，需发起变更单」），断言数由 2 支变 3 支，未删减任何检查。

## 3. 缺陷复验（round-1 级，非开放缺陷）

| 编号 | round-1 级 | 缺陷 | 复验命令（原文） | 通过判据 | 本轮命中数 | 判定 |
|---|---|---|---|---|---|---|
| D-1 | P2 | AC-17 文档未回写：memory 无 editability-verify 基线/矩阵约定、REQUIREMENT-FLOW 无可编辑性小节 | `grep -c "editability-verify" docs/teamflow/memory.md; grep -c "可编辑性判定" docs/teamflow/REQUIREMENT-FLOW.md` | 两命令各 ≥1 | 修复前 0/0（rc=1）→ 修复后 **2/1（rc=0）**，且 S1[⑥] 已把两条 grep 变门禁 | ✅ 已关闭 |
| D-2 | P3 | TECHNICAL §7 台账漏登记 5 个实际改动文件 | `git status --porcelain` 32 个改动文件逐个在 TECHNICAL.md 中按 basename 检索 | 0 个未登记 | 修复前 5 个漏登 → 修复后 **0 / 32**（§7.1 补录 6 行，含 `RequirementChangeValidator(Test)`、`req.ts`、`tokens.css`、`platform-verify.sh`、两份文档） | ✅ 已关闭 |
| D-3 | P3 | 终态需求「编辑」禁用原因恒返回「需发起变更单」，指向实际走不通的入口（与短句「字段均只读」不一致） | `node --experimental-strip-types logs/teamflow/tf-mwtzd1oa-p2j7xu/scripts/editability-copy-check.mjs` | 三场景各自 title 与短句同支、终态不提变更入口 | 修复前 1 支错配 → 修复后 **5/5 PASS**；`editability.ts:152-169` 按 `onlyChangeRequired` 分支，S1[⑥] 再加一道静态守护 | ✅ 已关闭 |

三方对照一致：dev 修复摘要称「D-1 回写、D-3 按 DESIGN 分支、D-2 补录 §7.1、门禁改前 5/6 改后 6/6」——本轮实测 `--static-only` 6/6、文档 grep 2/1、台账 0 漏登，**无分歧**。

## 4. 全量门禁（AC-16/17/19/20 证据）

| 检查 | 命令 | 结果 |
|---|---|---|
| 单元测试（含矩阵 15 例 / EditGuard 11 例 / ChangeService 10 例） | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -pl req-service -am test` | ✅ exit 0，`Tests run: 430, Failures: 0, Errors: 0` |
| 代码规范 | `bash scripts/tests/lint.sh` | ✅ 24/24 |
| 前端类型 / 构建 | `cd web && npx vue-tsc --noEmit` / `npm run build` | ✅ rc=0 / rc=0 |
| 变更单门禁 | `bash scripts/change-verify.sh` | ✅ 42/42 |
| 平台门禁 | `bash scripts/platform-verify.sh` | ✅ 199/199 |
| 负向门禁 | `bash scripts/platform-test-negative.sh` | ✅ 132/132 |
| 权限/页面门禁 | `bash scripts/access-verify.sh` | ✅ 206/206 |
| RBAC 门禁 | `bash scripts/rbac-verify.sh` | ✅ 113/113 |
| 发布流门禁 | `bash scripts/release-flow-verify.sh` / `--static-only` | ✅ 44/44 / 12/12 |
| 门禁顺序无关 | `bash scripts/gate-order-verify.sh` | ✅ 21/21（负向自检 0→1→0） |
| 演示基线可重复 | `bash scripts/platform-demo-reset.sh` ×2 | ✅ 两轮均 80/80（5/5 段） |

关键断言取证：36 条中 [10]–[16] 覆盖 type 全 LOCKED、BUS `parentId=NO_PARENT`、BUS/PDR 人天 `PARENT_ROLLUP`（早期态也锁）、RD TODO `EDIT/EARLY_STAGE`、域/Sonar 开放态 `EDIT/OPEN_STATE`、未知状态 11 字段全 `UNKNOWN_STATUS`；[17]–[21] 覆盖终态 `FIELD_LOCKED/TERMINAL`、受控多字段 `CHANGE_REQUIRED` + changeEndpoint、混合只报 LOCKED、终态同值 200 且审计 0 条；[25]/[26] 逐字段对拍「接口说 EDIT ↔ 同值 PUT 200」「locked[0] 与 FIELD_LOCKED 同 reasonCode」；[27]/[28] 变更行 `fields` 规范序与空快照 `[]`；[29] `EDITABILITY_MUTATE=always-edit` 子进程 rc=1 证明断言非空转；[30] 退出复位后本轮足迹 0、基线 12 条。

## 5. 架构与接口一致性（0a）

| 检查 | 证据 | 判定 |
|---|---|---|
| 矩阵唯一实现在 platform-contract | `RequirementEditability.java:27-52`（11 `F_*`/`FIELDS`/9 reasonCode）、`rules/rule/decision/reasonCode/editableFields/changeRequiredFields/lockedFields/earlyStage` 全部静态 | ✅ 与蓝图 api 字段逐项一致 |
| req-service 只委托 | 门禁 S1[③]：`EARLY_STATUSES` 0 处、change 包状态字面量 0 处；`RequirementChangeFields` 仅投影 7 字段旧键 | ✅ 无第二份状态表 |
| 双守卫未并存 | `RequirementChangeGuard.java` 为 `D`（已删），逻辑并入 `RequirementEditGuard.java:28/50/62/100/123/154` | ✅ |
| Detail 三数组单次投影 | `RequirementViews.java:72` `record FieldRule`、`:81-85` 三数组 + `domainIds`/`sonarProjectKey`；`RequirementService.detail` 一次 `rules()` 分组 | ✅ |
| 前端单点 / 调用存在性 | `editability.ts` 导出 `EDITABLE_UI_FIELDS/EDIT_UI_FIELDS/REASON_TEXT/REASON_MARKER/reasonText/reasonMarker/editabilityOf/ruleOf/decisionOf/canEditAny/canOpenEdit/editDisabledReason/editDisabledHint/firstFieldOf` 全在；消费方 `RequirementEditDrawer.vue:17`、`OverviewTab.vue:18`、`RequirementDetailView.vue:48`、`FieldFlag.vue:11` 均解析成功 | ✅ |
| 编译期闭包 | `vue-tsc --noEmit` rc=0（全部 TS import/成员解析）+ `mvn test` 430 通过（全部 Java 调用点编译） | ✅ 无缺失成员/参数形状不符 |
| 门禁接线 | `Makefile:9/119/120` 薄目标、`scripts/test-all.sh:45` 在 change-verify（:44）之后 | ✅ |

架构结论：**未发现架构缺陷**；无重复实现、无漏抽，越界修复（`RequirementChangeValidator` 收紧）已在 TECHNICAL §7.1 登记且有 5 例单测随行。

## 6. 缺陷表（仅列本轮仍 OPEN 的缺陷）

未发现缺陷。

## 7. 版本库卫生（0c）

`git status --porcelain` 33 项：24 M + 2 D + 7 新增源码/前端文件 + 任务夹；`target/`、`dist/`、`node_modules/`、`.run/`、`.nacos/`、`.env*`、`.idea/` 命中数 **0**；`.gitignore` 覆盖上述类别 ⇒ 收尾提交不会带入库外产物与本地密钥。

## 8. 人工补测清单（环境限制，非交付缺陷）

| # | 项目 | 方法 | 工具 |
|---|---|---|---|
| 1 | 概览 `需变更` 纯文本无胶囊、锁定 `🔒 词` tooltip 悬停 120ms / 键盘 focus 立即出现 | 真浏览器打开 `RD/IN_DEV` 与 `RD/DONE` 逐行悬停/tab，比对 DESIGN §2 | Chrome/Edge DevTools |
| 2 | 变更 Tab 7 列两行制在 1280/1440 无横向溢出（`scrollWidth <= clientWidth`）、chips 换行 | 浏览器 + 元素尺寸断言 | Chrome DevTools |
| 3 | 抽屉逐字段灰底/原因行对齐、组头「需发起变更」窄屏换行 | 打开多受控字段的编辑抽屉截图比对 DESIGN §4 | Chrome DevTools / Figma |
| 4 | 变更单全链路真人走查（发起→审批→apply）等待态与失败提示语感 | 演示环境手工操作 | 浏览器 + 演示账号 |

## 9. 结论

**可验收**：上一轮 3 条缺陷全部复验关闭（D-1 文档 grep 2/1、D-2 台账 0 漏登、D-3 文案三分支 5/5），本轮未发现新缺陷（P0/P1/P2/P3 均无）。新增门禁 36/36 且 `--twice` 两轮一致、MUTATE 负向必红；既有 8 项门禁全部实跑通过（199/132/206/113/44/21/42/80×2）、单测 430、lint 24、typecheck/build 绿；架构与蓝图一致、接口无缺失、版本库卫生合规。视觉观感项受沙箱环境限制，已列入 §8 人工补测清单供人工复核。
