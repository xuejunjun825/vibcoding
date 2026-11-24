# 验收报告 · r44 变更单缺失 `changeType` 的 500 修复 + 不可变集合空值加固

- 任务夹：`docs/teamflow/20261124-r44-null-safe-enums/`；分支 `feat/null-safe-enum-checks`；HEAD `bd3426d`
- 需求形态：`mode=tech`，任务夹无 PRD.md；需求契约 = 注入的 PRD 修订日志（唯一行为改动 + 全仓扫描 + 门禁 42→46 + 回归下限 + 契约要点）+ [TECH-CHANGE.md](TECH-CHANGE.md)。以下 12 条 AC 由该契约逐条拆出，不改语义。
- 总评：功能修复成立、与架构蓝图逐项一致、门禁与回归只增不减、交付面受控；无阻塞项，遗留均为 P3 观察。

## 1. AC 逐条核对

| AC | 判据（来自 PRD 修订日志 / 技术变更单） | 证据（本轮实测优先） | 结果 |
|---|---|---|---|
| AC-1 | `knownType` 为全函数：`null`/`""`/空白 → `false` 不抛；四合法值 → `true`；不做 trim/大小写归一 | `RequirementChangeFields.java:93-95` 为 `changeType != null && !changeType.isBlank() && TYPES.contains(changeType)`；`RequirementChangeFieldsTest` 4/4（真值表 13/13 同口径） | ✅ |
| AC-2 | 缺 `changeType` → 400 `CHANGE_FIELD_NOT_ALLOWED`（非 500/NPE），`create` 与 `preview` 均如此 | 实跑 `change-type-missing-probe.sh`：MISSING `http=400`、`code=CHANGE_FIELD_NOT_ALLOWED`；preview 缺键/空串均 400 同 code | ✅ |
| AC-3 | `changeType=""` / `" "` → 400（修复前即 400，修复后保持） | 探针 BLANK/SPACE 均 `http=400`，detail 保留原 `changeType` 值 | ✅ |
| AC-4 | 未知类型 → 400 且 detail 增 `allowedTypes`（四合法值），`allowedFields` 空；既有 detail 断言不受影响 | 探针 UNKNOWN `http=400`，`detail.allowedTypes=[CONTENT,DAYS,PARENT,RELEASE]`、`allowedFields=[]`；三参错误工厂与 3 处调用点零改动 | ✅ |
| AC-5 | 一处修复覆盖两条入口（`create` 与 `preview` 共用 `validateCreate`） | `RequirementChangeValidator.java:47` 是生产代码唯一 `knownType` 调用；两条入口 E2E 均 400 | ✅ |
| AC-6 | 四合法值路径不回归；不新增 trim/归一化 | 门禁 46/46 含合法预览、申请、审批、应用闭环；单测真值表锁定 `content`/`CONTENT ` 仍 false | ✅ |
| AC-7 | 全仓扫描：142 点 = 63 处不可变集合成员判定 + 79 处 switch；必加固 1 / 建议 1 / 不可达 140，逐条给理由 | [NULL-SAFETY-SCAN.md](NULL-SAFETY-SCAN.md) 结论表与逐文件清单；QA §7 独立重算一致（`TYPES.contains` 生产代码仅 1 处） | ✅ |
| AC-8 | `change-verify` 42→46：S2 +4 条，六段结构（5+13+6+8+7+7）与 `STATIC_TOTAL=12` 不变，既有 42 条不缩水，分母由脚本自证 | `change-verify.sh:48` `TOTAL=46`、`:250` `STATIC_TOTAL=12`、行首 `ck` 实测 46；本轮 `--static-only` 12/12、`--once` **46/46 exit 0** | ✅ |
| AC-9 | 负向自检：回退 `knownType` 后新增断言必红 | QA §3/§9 实测：未修复 jar 下 `--once` 44/46，红的是新加类型缺失断言，body 为 `http=500` 且无 `code`；修复后同脚本 46/46 | ✅（QA 实测记录，本轮不重复回退源码） |
| AC-10 | 回归下限只增不减：change 46 · editability 36 · platform 199 · negative 132 · access 206 · rbac 113 · release-flow 44 · gate-order 21 · demo-reset 80×2 · lint 24 · unit 437 · web typecheck/build | QA §10 全部实跑全绿；本轮独立复跑 change 46/46、static 12/12、定向单测 12/12（4+8，0 失败） | ✅ |
| AC-11 | 契约文档对齐：错误码表补 `allowedTypes` 与类型缺失触发行；`memory.md` 同主题行更新；AGENTS.md 托管区外不动 | `CHANGE-REQUEST-FLOW.md:5,58,117,173`（46 条、单点谓词、类型缺失行）；`memory.md:40` r44 行含 46 与两条空值约定；`git status` 无 AGENTS.md | ✅ |
| AC-12 | 改动面受控：8 个产品文件 + 任务夹；无 pom/web/迁移/依赖/`platform-common` 改动；可单 commit 回滚 | `git status` 9 项（7 M + 任务夹 + 1 新测试）；diffstat 176+/13-；`ApiException.java` 对 HEAD `diff --quiet` rc=0；`pom.xml`/`web/`/`.sql` 命中 0 | ✅ |

## 2. 架构一致性核对（M3 门禁）

| 蓝图要求 | 实际 | 判定 |
|---|---|---|
| `knownType` 改为全函数，仍是外部字符串与白名单之间唯一边界判定 | 单表达式 null/blank 短路；生产代码仅 `RequirementChangeValidator.java:47` 一处调用 | 一致 |
| 错误工厂加 additive 四参重载，三参既有调用点与文案一字不动 | `RequirementErrors.java:170-186` 新增；`:159-167` 三参原样；两处 message 表达式逐字相同 | 一致 |
| 校验器只改传参行，判定次序（类型→原因→白名单→归一化→业务前置）不重排 | `RequirementChangeValidator.java:45-50` 仅传 `TYPES` 作 `allowedTypes`；后续逻辑零改动 | 一致 |
| 不新增模块、不改依赖方向、不动前端与构建配置 | diff 仅 7 文件，无模块/POM/前端；`platform-common` 零改动 | 一致 |
| 单测落在既有最贴近调用链的类，既有 5 例只增不改 | `RequirementChangeValidatorTest` +76/-0（diff 全 `+`）；新 `RequirementChangeFieldsTest` 4 例 | 一致 |
| 无重复实现/适配漂移 | Java 侧类型常量唯一来源 `RequirementChangeFields.TYPES`；前端 `web/src/views/req/change.ts` 白名单为 r40 起既有风险、本轮未新增，服务端仍是最终闸门 | 一致 |

## 3. 独立复核记录（本轮亲自执行）

- `bash scripts/change-verify.sh --static-only` → `STATIC_TOTAL=12 CASE=12 PASS=12`，exit 0。
- `bash scripts/change-verify.sh --once` → `CHANGE_VERIFY_TOTAL=46 CASE=46 PASS=46 FAIL=0`，exit 0（含退出复位与 RUN_TAG 清理）。
- `change-type-missing-probe.sh` → MISSING/BLANK/SPACE/UNKNOWN 全部 400 + `code` + `allowedTypes` 四值；`allowedFields=[]`。
- `POST .../changes/preview` 缺键、空串 → 均 400 `CHANGE_FIELD_NOT_ALLOWED`。
- `mvn -o -pl req-service test -Dtest='RequirementChangeFieldsTest,RequirementChangeValidatorTest'` → surefire `tests=4`/`tests=8`，`errors=0 failures=0`。
- `git diff --quiet HEAD -- platform-common/.../ApiException.java` → rc=0（D-1 回退干净）；无 `pom.xml`/`web/`/`AGENTS.md`/迁移改动。
- 键序观察：`detail` 的 `Map.copyOf` 迭代序两次运行不同（`allowedFields`/`allowedTypes` 先后互换），与 `memory.md` 约定一致，仅按键集合断言，未当作契约。

## 4. 遗留与观察（均不阻塞验收）

- **P3-1 文档计数漂移**：TECH-CHANGE 实际 118 行，PRD 摘要写「115 行」；纯计数、无内容缺失（6 条假设 A1–A6 齐全），下轮同主题修正即可。
- **P3-2 `detail` 键序非契约**：根因在 `platform-common` 的 `Map.copyOf`；r44 已明确「不得为键序改平台级共享类」，前端/QA 按 key 读取。若将来确需稳定序，须单独立项。
- **P3-3 建议项未加固**：`AdmissionItemPolicy:101` 的 `ORDER.indexOf(null)` 同源，入参当前来自 DB 非空列，按「只修外部可达点」保留；入参一旦改为请求参数即升 P1。
- **P3-4 收口提交**：改动仍为工作区未提交（7 M + 2 未跟踪），沿用 r40/r42/r43 的 host 统一收口提交；提交信息需含 `tf-mwu9qgf4-ljjad4` 与任务夹路径。
- **可选人工复核（非阻塞）**：无真实浏览器，前端抽屉在类型缺失时按键读取 `allowedTypes` 的展示未人眼确认；本轮无前端改动，且该键为纯增量，不影响既有 UI。

## 5. 验收意见

功能、架构、门禁、文档、交付面五项均与 r44 契约一致；唯一对外可见变化就是「缺参 500 → 400」，合法路径与 r43 可编辑性矩阵未被扰动。遗留 4 项均为 P3 观察或流程性事项，不构成返工条件。

验收结论：✅ 通过
