<!-- meta: summary="修复 r19 三层授权的三个阻断缺陷：授权页保存不再清零未展示档位（读写同源 168 行）、门禁跑完库回预设、演示重置确定性可重入，并订正平台门禁真实基线数字。" -->
基线依赖：docs/teamflow/20261113-r19/（其 AC-1…AC-18 与 rbac-verify s0–s9、既有平台门禁基线是本轮修复对象；r18 建立的三层判定语义、V12 迁移形状与每角色 1680/168 行种子不得回归）
取代：docs/teamflow/20261113-r19#AC-9：授权页读侧由「仅页面绑定的 FUNCTION 码」改为「全部 168 个 subject，并单列未绑定页面的 30 个功能码」；写侧改为以库内现状为基底的差异写，不再以全 DENY 栅格整表重写。

# PRD：r20 修复 r19 三层授权的三个阻断缺陷

## 1. 背景与目标

### 1.1 这次是什么

r19（runId=`tf-mwencgru-xkthj3`）完成 15/16 阶段，但验收判「不可验收」：三个缺陷有可复跑脚本与明确判据，修复未落地。分支 `feat/rbac-admin` 的 HEAD 是断点存档 `a85ccd8`（工作区编译已绿，`mvn -o -DskipTests compile` exit 0），工作区仅 `?? docs/teamflow/20261114-r20-r19-rbac-blocker-fix/`。

本轮只做**最小修复**：不重写已存在文件，不新建第二套集合，不靠放宽门禁或删断言换绿。

### 1.2 现状核实（2026-11-14，以工作区 `a85ccd8` 为准）

| 缺陷 | 代码/脚本现状 | 可复现后果 |
|---|---|---|
| BUG-1（P1）读写键集双推导 | 读侧 `req-service/src/main/java/com/example/req/sys/RoleAdminService.java:78` 已用 `stored(tenantId, roleId)` 取现状，但只遍历 `MenuRegistry` 的页面绑定（30/60 个 FUNCTION 码，payload 138 行）；写侧 `:226` 仍 `after = fullGrid()`，`:328-340` 的 `fullGrid()` 是 60 FUNCTION + 36 MENU + 72 ACTION = 168 项全 DENY 基底 | 「GET 后原样 PUT」把 30 个不可见 FUNCTION 档位清零：QA 角色 16 个活档位 ALLOW/RESTRICTED→DENY；单租户暴露 300 行 / 114 个活档位（占该租户活档位 15%）；新租户继承同一缺陷；无任何提示或审计 |
| BUG-2（P2）门禁非幂等 | s3 跑完再跑 s2 必红；`access-verify` 全量（152/152 绿）后 s2 也红（`ACTION 720 格不符 = 0：实际 30`）；`scripts/tests/rbac/_lib.sh` 的写段不恢复预设 | 跑一次套件就把演示库改成非预设态，只能靠 `platform-demo-reset` 复绿，与 AC-16/AC-17「可重复、不缩水」冲突 |
| BUG-3（P2）演示重置间歇失败 | `scripts/platform-demo-reset.sh:235-239` 第 [41] 项断言 `/api/deliver/test-executors` 恰 2 条；`req-service/.../seed/DemoFixture.java:203` 的 `test_executor` 保留式清理在 `api_client` 全清之前执行 | 首次 56/57、exit 1（`[41] 执行器页接口 HTTP 500`），失败瞬间库处半重置态，无回滚或半态提示 |
| 基线漂移 | r19 PRD 写 `platform-verify 168` / `negative 30`，实测 167 / 28；本轮未改这两个脚本 | 文档数字与可复跑事实不一致，验收表格失真 |
| 交付面 | HEAD `a85ccd8` 已含断点存档，工作区仅任务夹未跟踪 | 修复需落成新提交，提交信息带 runId 与任务夹 |

### 1.3 目标

1. **保存不丢档**：授权页 GET 覆盖全部 168 个 subject，写侧以库内现状为基底；任何客户端「原样保存」都 `changed=0`、`nonDENY→DENY=0`，快照逐字节相等。
2. **门禁可重复**：任何会写 `sys_role_permission` 的段跑完，库回到预设；「套件结束 = 库等于预设」写成断言，而不是靠人工 reset 收尾。
3. **重置确定**：定位 `[41]` HTTP 500 的真实原因并修掉；连续 3 次 57/57；失败即回滚或给出明确半态，且可重入。
4. **基线可核对**：r19 文档与 `docs/teamflow/memory.md` 记录真实基线 167 / 28。
5. **交付干净**：改动面限定，pom 与 package.json diff 为空，全量门禁不缩水，一个带 runId 与任务夹的提交。

### 1.4 成功判据（一句话）

管理员打开授权页、不改任何格直接保存，QA 角色 16 个活档位原样保留（`qa-r19-ui-save-loss.sh` exit 0）；在此之后 先跑 `s3` 再跑 `s2` 仍绿、`access-verify` 全量后 `s2` 仍绿（`qa-r19-suite-idempotency.sh` exit 0）；连续 3 次 `bash scripts/platform-demo-reset.sh` 全 57/57，`[41]` 不再 500。

## 2. 用户故事

| # | 故事 | 可测验收锚点 |
|---|---|---|
| US-1 | 作为租户管理员，我打开角色授权页、什么都没改就点「保存并生效」，角色的权限必须一行不变，而不是静默失权 | AC-2、AC-3：payload 168 行、`changed=0`、`nonDENY→DENY=0`、快照逐字节相等 |
| US-2 | 作为租户管理员，我要在授权页看到「未绑定页面的功能码」这一组及其当前档位，能判断它们是否被授权，而不是让它们成为看不见的黑洞 | AC-3、AC-4：GET 168 subject、缺口 0；页面可见 30 个码与档位；原样保存后档位不变 |
| US-3 | 作为 QA 工程师，我要能任意顺序、任意次数跑门禁，跑完库仍是预设态，而不是「跑一次就得 reset 一次」 | AC-5、AC-6：s2 before/after 均 0；`access-verify` 后 s2 仍 0；结束 hash = 预设 |
| US-4 | 作为演示/运维人员，我要连续重置都成功；失败时脚本必须明确失败并说明停在哪，而不是返回 0 留下半重置库 | AC-7、AC-8：连续 3 次 57/57；注入失败非 0 且指出停止项；重跑收敛 |
| US-5 | 作为验收人，我要看到与实测一致的门禁基线数字和可复核证据，而不是引用上一版 PRD 的旧数字 | AC-9、AC-10、AC-12：167/28 记录在案；≥4 张证据与复跑输出 |

## 3. 范围与不做

### 3.1 范围内

- `req-service`：`RoleAdminService` 读写键集同源（写基底改 `stored`，读侧补「未绑定页面的功能码」分组/覆盖 168）；`SysViews.RoleGrantView` 契约扩展（只增不改既有 36 页结构）；`DemoResetService`/`DemoFixture` 重置顺序、事务与失败语义；必要时 `test_executor` 引用一致性。
- `web`：授权页新分组渲染与草稿物化（`useRoleGrants` 覆盖 168 行，修正 `useRoleGrants.ts:57-69` 与实现相反的注释）；不改 UI-STYLE、不改既有页面布局。
- `scripts`：`tests/rbac/_lib.sh` 与 s2/s3/s5/s7 的恢复/复位；`access-verify` 写段收尾复位；`platform-demo-reset.sh` 的 [41] 断言与失败语义。
- 文档：r19 基线订正行（仅新增，不改 AC 文本）、`memory.md` 一行真实基线、任务夹五件套。

### 3.2 非目标

- 不改需求状态机、审批流、`DataScope` 语义与 `PermissionService` 三序判定。
- 不新增权限码、不改 `PermissionMatrix` 10×60 矩阵内容、不改 V12 迁移与 1680 行种子口径。
- 不重做授权页视觉与信息架构（除新增「未绑定页面」分组）；不引新依赖。
- 不动 `TenantContext` 直连 `PermissionMatrix` 的 7 处（另开需求）。
- 不追求「全库统计口径」的治理；本轮统计一律单租户（`t1`）。

### 3.3 anti-goals / 反面目标

以下任一发生，即使门禁全绿也应判失败：

1. **脚本绿了但客户端仍会清零**：只改前端「不发全量」，或服务端对缺失 subject 继续按 DENY 写。可观察判据：用 curl 回放旧 138 行 payload，QA 角色 16 个活档位仍被清零。
2. **幂等靠「断言之间偷偷 reset」**：门禁不再证明「同一份数据上成立」，只是每步重建数据。可观察判据：跑完 s3 直接跑 s2（不 reset）仍红，或脚本在断言之间出现 reset 调用。
3. **reset 失败仍返回 0**：或只打印 WARN/「半态」而退出码为 0。可观察判据：人为注入 `[41]` 失败，脚本 exit 0。
4. **用删断言/放宽判据换绿**：`rbac-verify` 断言总数低于 101、`platform-verify` 低于 167、`negative` 低于 28，或把 fail 改 skip。
5. **未绑定功能码仍不可见**：1440×900 下首屏或一次点击内找不到该分组，或只显示码名不显示当前档位（无法判断是否授权）。可观察判据：管理员指着页面说不出「这 30 个码现在是什么档位」。

## 4. 交互与流程要点

1. **打开授权页**：`GET /api/req/admin/roles/{id}/permissions` 返回 36 个页面 + 1 个「未绑定页面」分组（30 个 FUNCTION 码，携带当前 `access`）；前端草稿物化 168 行（FUNCTION 60 + MENU 36 + ACTION 72）。
2. **编辑与保存**：用户改一格 → 仍只发 1 次 `PUT`（全量 168 行或仅差异皆可）；服务端以 `stored(tenantId, roleId)` 为基底覆盖请求中的 subject，`DELETE + INSERT` 仍写满 168 行，未提交的旧值原样保留；有差异才 `epoch++` 与写审计。
3. **原样保存**：`changed=0`、`nonDENY→DENY=0`、无审计、无 403。
4. **门禁运行**：`platform-demo-reset` 回预设 → 任意段（含 s3/access-verify）→ 段内 `trap` 或段末复位 → `s2` 仍逐格等于矩阵；套件结束断言库 hash = `CANONICAL_HASH`。
5. **重置失败**：`[41]` 失败即非 0，事务回滚到重置前或明确列出「已重置项 / 未完成项」，重跑可收敛；不得静默成功。

## 5. 验收标准

| AC | 判据（可复跑） | 说明 |
|---|---|---|
| AC-1 编译与启动 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` exit 0；`make platform-up` 后服务 READY、flyway=12 | 工作区已绿，修复后不得回退 |
| AC-2 BUG-1 写侧同源 | `saveGrants()` 基底 = `stored(tenantId, roleId)`（改掉 `:226` 的 `fullGrid()`）；每角色仍写满 168 行；未提交 subject 保留旧值 | 静态检查 + 快照对比 |
| AC-3 BUG-1 读侧全集 | GET 覆盖 168 subject；新增「未绑定页面的功能码」分组（30 个码，取自 `PermissionMatrix.functions()` 同一口径，禁止第二套集合）；`bash <r19归档>/role-grants-payload-gap.sh` exit 0（缺口 0） | 读侧与写侧共用同一全集来源 |
| AC-4 BUG-1 端到端不丢档 | `bash <r19归档>/qa-r19-ui-save-loss.sh` exit 0：payload subject=168 或只写改动格、`changed=0`、`nonDENY→DENY=0`、恢复快照逐字节相等；`qa-r19-unbound-fn-exposure.sh` exit 0（缺口 0 码 / 0 行） | 本轮核心判据 |
| AC-5 前端可见可保留 | 授权页分组可见 30 个码及当前档位；`useRoleGrants` 草稿 168 行且注释与实现一致；`node <r19归档>/t5-grants-check.mjs` 57/57、`t4` 34/34；`cd web && npm run typecheck` 0 error | 截图见 AC-12 |
| AC-6 BUG-2 幂等（rbac 内部） | `bash <r19归档>/qa-r19-suite-idempotency.sh` exit 0：s2(before)=0、s2(after)=0、reset 后仍 0 | 写段 trap/结束复位 |
| AC-7 BUG-2 幂等（跨套件） | `bash scripts/access-verify.sh` 全量（152/152）后直接 `bash scripts/tests/rbac/s2-seed-equivalence.sh` exit 0；随后 `rbac-verify` 仍 101/101 | 新触发面不得回潮 |
| AC-8 BUG-2 断言化 | 套件结束断言「库 = 预设」（`CANONICAL_HASH` 或逐格等于矩阵）；断言总数 ≥ 现状 101，只增不减 | 失败要能定位到角色/页面/动作 |
| AC-9 BUG-3 根因与确定性 | 定位 `[41]` 500 真实原因（重置顺序 / 执行器查询依赖 / 缓存或连接），修复后连续 3 次 `bash scripts/platform-demo-reset.sh` exit 0、57/57、`[41]` 不再 500 | 3 次日志逐次记录 |
| AC-10 BUG-3 失败语义 | 注入失败（如临时停 deliver 或改断言）时 reset 非 0，并输出停止项与已/未完成项；恢复后重跑 57/57；重复执行安全 | 允许回滚或明确半态，二者择一实现并测试 |
| AC-11 既有门禁不缩水 | `platform-verify` 167/167、`access` 152/152、`negative` 28/28、`lint` 22/22、`platform-unit` 306、`verify` 14/14、`analytics` 74/74、`collab s0–s8`（263）、`rbac` 101/101、`PermissionMatrixGridTest` 600 不动、r17 契约不回归 | 任一数字下降即失败 |
| AC-12 证据与提交 | `evidence/` ≥4 张 1440×900 截图：①未绑定分组可见 ②原样保存 changed=0 ③reset 连续 3 次 57/57 ④access-verify 后 s2 复绿；证据脚本新鲜度检查绿；改动面限于 contract/common/req-service/web 两页三视图/scripts；`git diff -- pom.xml */pom.xml web/package.json` 为空；提交信息含 runId=`tf-mwfso8sv-t4ipv0` 与任务夹路径 | 截图不可得时用 SSR/静态断言 + 服务端日志替代，并在 ACCEPTANCE 注明原因 |

**完成定义（DoD）**：AC-1…AC-12 全部有 exit code 或截图证据；`git status` 干净；验收报告逐条引用命令与结果。

### 5.1 基线数字订正（必须落地）

- 真实基线（脚本本轮未改）：`platform-verify 167`、`platform-test-negative 28`、`platform-unit 306`、`rbac 101`、`access 152`、`lint 22`、`verify 14`、`analytics 74`、`collab 263`。
- 落点：`docs/teamflow/20261113-r19/PRD.md` 顶部新增一行「基线订正（r20 实测）」+ `TECHNICAL.md` 同口径一行（**只新增订正行，不改任何 AC 文本**；此为对「任务夹不可变」的唯一例外，依据本轮澄清原文）；`docs/teamflow/memory.md` 记一行真实基线。
- 历史引用「168 / 30」必须能被读者识别为漂移，不得继续作为判据。

## 6. 优先级

| 优先级 | AC | 理由 |
|---|---|---|
| P0 | AC-1、AC-2、AC-3、AC-4、AC-6、AC-9、AC-12 | 数据完整性、门禁可信度、重置确定性是「不可验收」的三条根因；提交与证据决定能否交付 |
| P1 | AC-5、AC-7、AC-8、AC-10、AC-11 | 前端可见性、跨套件幂等、失败语义与全量门禁回归 |
| P2 | 基线订正（§5.1） | 文档一致性，不阻断功能，但不得遗漏 |

## 7. 依赖与风险

| 依赖/风险 | 影响 | 应对 |
|---|---|---|
| 本地 MySQL 9.6、Nacos、`.m2` 仓库、JDK 25 可用 | 门禁与 reset 全部依赖 | 开工先 `make platform-up` + `s0`；环境不可用按宿主策略立即上报，不重试掩盖 |
| r19 QA 脚本已归档到 `$DSH_HOME/teamflow/ws-project-1-17fd2735/logs/tf-mwencgru-xkthj3/scripts/`（项目内 `logs/teamflow/` 已由 host 归档删除） | 判据脚本路径失效 | 复跑用归档路径；需要固化时复制到本轮 `logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/`，不修改判据 |
| GET 响应扩到 168 可能改变契约（`modules` 形状） | t4/t5/a3 契约测试可能红 | 只**新增**分组字段/模块，不改既有 36 页字段；同步 t4/t5 断言（只增） |
| 写侧改 `stored` 基底后，旧库/未种租户的缺失行 | 保存可能漏行 | `stored()` 已用 `fullGrid()` 打底再回填；保留该行为，保证每角色仍 168 行 |
| `access-verify` 写段复位会改变门禁耗时/副作用 | 可能拖慢套件 | 复位限定在写段收尾，做整段事务或快照回填；记录耗时基线 |
| reset 500 根因可能不是顺序（连接池/缓存/并发） | 3 次全绿不足以证明 | 先复现并抓服务端异常栈；同日多次失败样本；修复后连续 5 次加跑 |
| 环境间歇冻结（`curl: (28)`、typecheck 长卡） | 门禁假红 | 失败先区分环境与真实失败；同命令重跑一次并记录，不计入「删断言」 |
| 浏览器不可用 | 证据截图受限 | 沿用 SSR/静态断言 + 已有截图基线；AC-12 允许替代证据并注明 |

## 8. 里程碑建议

| 里程碑 | 内容 | 预估 |
|---|---|---|
| M1 数据面 | AC-2/AC-3：`saveGrants` 基底改 `stored`；`grants()`/`RoleGrantView` 覆盖 168 与未绑定分组；payload-gap 归零 | 0.5–1 人日 |
| M2 交互面 | AC-4/AC-5：授权页分组渲染、草稿 168、注释纠正、typecheck | 0.5 人日 |
| M3 门禁幂等 | AC-6/AC-7/AC-8：写段恢复、跨套件复位、结束 hash 断言 | 0.5 人日 |
| M4 重置 | AC-9/AC-10：定位 500、修顺序/事务、失败语义、连续 3 次 | 0.5–1 人日 |
| M5 收口 | AC-1/AC-11/AC-12 + 基线订正：全量门禁、证据、提交 | 0.5 人日 |

合计约 2.5–3.5 人日；检查点：M2 后跑 `qa-r19-ui-save-loss.sh` 与 `payload-gap`，M4 后跑三次 reset，M5 跑全量门禁。

## 9. 工程约束

> 以下工程动作按澄清原文保留，不得改写或静默丢弃。

- **分支**：在 `feat/rbac-admin`（HEAD `a85ccd8`）上直接修复；**不切换、不新建分支**；不改写历史（不 amend/rebase `a85ccd8`）。
- **工作区现状**：未提交改动仅 `?? docs/teamflow/20261114-r20-r19-rbac-blocker-fix/`；修复开始前确认 `git status --porcelain` 只含该项，其余脏文件先查明来源，不得顺手提交无关改动。
- **最小修复**：不重写已存在文件；只改必要行；不引入新依赖；`pom.xml`、`*/pom.xml`、`web/package.json` 的 diff 必须为空。
- **不做**：不改需求状态机/审批/DataScope 语义；不改 UI-STYLE 设计系统与既有页面布局（除授权页补「未绑定页面的功能码」分组）；不删既有断言换绿（只能增或改判据）；不动 `TenantContext` 直连 `PermissionMatrix` 那 7 处（另开需求）。
- **编译命令**：`mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile`。
- **验收命令（全绿才算完）**：上述编译；`make platform-up` 后 `bash scripts/rbac-verify.sh`（s0–s9 全绿且 ≥60）；`make access-verify`（152）、`make platform-verify`（167）、`make platform-test-negative`（28）、`make lint`（22）、`make platform-unit`、`make verify`、`web npm run build`（vue-tsc 0）；连跑两次 `bash scripts/platform-demo-reset.sh` 全绿且幂等。
- **提交**：修复完成后**新建提交**（不改历史），提交信息带 `runId=tf-mwfso8sv-t4ipv0` 与任务夹 `docs/teamflow/20261114-r20-r19-rbac-blocker-fix/`；提交前 `git status` 只应包含本轮修复与任务夹产物。
- **`.gitignore`（dev 阶段动作）**：审计并补全本项目栈的忽略项（Java/Maven：`target/`、`.m2/`；Nacos：`.nacos/`；前端：`node_modules/`、`dist/`、`.vite/`、`*.tsbuildinfo`；本地：`.env*`、`logs/`）；当前 `.gitignore` 已覆盖上述项，若本轮新增产物（临时快照/截图中间件）落盘，必须补规则；**不得**删除 `logs/teamflow/` 忽略（AGENTS.md 明确要求），**不得**忽略 `docs/teamflow/**/evidence/*.png`（验收证据必须入库）。
- **基线订正**：见 §5.1，只新增订正行；依据本轮澄清「在 `docs/teamflow/20261113-r19/PRD.md` 或 TECHNICAL 里订正」，属对任务夹不可变约定的唯一例外。

## 10. 假设与待澄清

- **（视觉/信息架构，taste）未绑定功能码分组的呈现与可编辑性**：本轮只规定「必须可见、必须能保留其档位」，未规定它是独立模块、页尾折叠区还是只读列表。我推荐：页面树末尾独立「未绑定页面」分组，默认展开、沿用 `AccessSegmented` 三档、可编辑，带 30 计数徽标与一句说明（影响直接调用该功能码的接口）；若用户选择只读，则 AC-4 的「可编辑」降为「展示并原样保留」。
- **（事实，fact）BUG-3 是否允许「自动重试一次」作为兜底**：需求要求定位真实原因并连续 3 次全绿，但未规定是否允许失败后静默重试。我推荐：不允许静默重试，必须根因修复；失败回滚或明确半态，重试只作为人工/显式运维动作。若允许自动重试，AC-10 的失败语义需改写为「重试后仍失败才非 0」。
- **（事实，fact）基线订正的落点与不可变约定的例外范围**：澄清写「在 r19 PRD.md 或 TECHNICAL 里订正」，与「任务夹不可变」存在张力。我推荐：r19 PRD 顶部加一行「基线订正（r20 实测）」、TECHNICAL 同口径一行，均只新增、不改任何 AC 文本，且仅此一处例外；若用户要求严格不可变，则只在 r20 PRD 与 `memory.md` 记录，r19 保持原样。

## 11. 附：本轮判据脚本索引

| 判据 | 归档位置（r19） | 期望 |
|---|---|---|
| `qa-r19-ui-save-loss.sh` | `$DSH_HOME/teamflow/ws-project-1-17fd2735/logs/tf-mwencgru-xkthj3/scripts/` | exit 0 |
| `role-grants-payload-gap.sh` | 同上 | 缺口 0 |
| `qa-r19-unbound-fn-exposure.sh` | 同上 | 缺口 0 码 / 0 行 |
| `qa-r19-suite-idempotency.sh` | 同上 | exit 0 |
| `t5-grants-check.mjs` / `t4-roles-page-contract.mjs` | 同上 | 57/57、34/34 |
| `bash scripts/platform-demo-reset.sh` | 项目内 | 连续 3 次 57/57 |

> 归档目录只读；如需固化判据，复制到 `logs/teamflow/tf-mwfso8sv-t4ipv0/scripts/`，不得修改判据阈值。
