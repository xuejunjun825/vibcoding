# 技术变更单 · 变更单创建接口漏传 changeType 的 500 修复 + 不可变集合空值加固（r44）

- 类型：技术驱动改造（tech-driven，无功能 PRD；不新增用户可见功能）
- 基线：`main@bd3426d`（r43 已合回）· 基线依赖：`docs/teamflow/20261124-r43-req-field-editability`
- 分支：`feat/null-safe-enum-checks` · runId：`tf-mwu9qgf4-ljjad4` · 任务夹：`docs/teamflow/20261124-r44-null-safe-enums/`
- 契约对象：Dev / QA / 验收 · 取代：无（r43 可编辑性矩阵与 PUT 两类 409 原样保留）

## 1. 背景与目标（一句话）

`POST /api/req/requirements/{id}/changes` 漏传 `changeType` 时，`RequirementChangeFields.TYPES`（`List.of(...)`）的 `contains(null)` 抛 NPE → **500**；本轮把它收敛为 **400 `CHANGE_FIELD_NOT_ALLOWED`**，并扫清 4 个后端模块的同族隐患、把「错误响应必须是 4xx 且带 code」写进门禁。

实测栈（`main=bd3426d`）：`ImmutableCollections$ListN.indexOf` ← `AbstractImmutableList.contains` ← `RequirementChangeFields.knownType(:87)` ← `RequirementChangeValidator.validateCreate(:47)` ← `RequirementChangeService.create(:97)`。未知类型 `NOPE` 不走 null 分支，原本已是 400。

## 2. 影响面

| 文件 | 改动 | 说明 |
|---|---|---|
| `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeFields.java:86-88` | `knownType` 增加 null/blank 短路 | 唯一行为修复点 |
| `req-service/src/main/java/com/example/req/requirement/RequirementErrors.java:155-172` | `changeFieldNotAllowed` 增四参重载，类型未知时带 `allowedTypes` | 旧三参签名保留，调用点不动 |
| `req-service/src/main/java/com/example/req/requirement/change/RequirementChangeValidator.java:45-49` | 未知/缺失类型分支传 `TYPES` | `create` 与 `preview` 共用此校验 |
| `req-service/src/test/java/com/example/req/requirement/change/RequirementChangeValidatorTest.java` | 新增缺键 / `""` / `" "` 三用例 + detail 断言 | Mockito 单测 |
| `req-service/src/test/java/com/example/req/requirement/change/RequirementChangeFieldsTest.java`（新增） | `knownType` 真值表（null/blank/四合法值/未知） | 纯函数单测 |
| `scripts/change-verify.sh` | S2 增 4 条断言，`CHANGE_VERIFY_TOTAL` 42→46 | 6 段结构不变 |
| `docs/teamflow/20261124-r44-null-safe-enums/NULL-SAFETY-SCAN.md`（新增） | 142 点扫描清单 | 本轮审计证据 |
| `docs/teamflow/memory.md` | 门禁基线 + 空值约定同主题行替换 | 幂等 |
| `docs/teamflow/CHANGE-REQUEST-FLOW.md` | §8 错误码表补 `allowedTypes` 与「类型缺失/空白/未知」行，§3 据 `knownType` 单点说明、§12 门禁 46 | 契约人类判据同步（漏登记已由 QA D-2 补上） |

**不动**：`changeType` 合法值集合、`RequirementEditability`（r43 矩阵）、PUT 的 `FIELD_LOCKED`/`CHANGE_REQUIRED`、前端、`pom.xml`/`web/package.json`、DB 与 Flyway。

**不动的越界项（QA D-1 复验后 revert）**：`platform-common/src/main/java/com/example/platform/common/error/ApiException.java`。开发轮曾把 `copyWithoutNulls` 的 `Map.copyOf(clean)` 改为 `Collections.unmodifiableMap(clean)` 以保留 detail 键序；因属平台级共享错误路径、蓝图外、未被端到端栈覆盖，且 detail 键序不是任何契约的断言对象（`DeployOrderService:445` 的注释仍写明「ApiException 用 Map.copyOf 收口」），本轮已**原样回退到基线**，`git diff` 该文件为空。

## 3. 技术方案

### 3.1 修复点

```java
// RequirementChangeFields.java:86-88
- public static boolean knownType(String changeType) { return TYPES.contains(changeType); }
+ public static boolean knownType(String changeType) {
+     return changeType != null && !changeType.isBlank() && TYPES.contains(changeType);
+ }
```

- 合法四值（`CONTENT/DAYS/PARENT/RELEASE`）判定不变；`""`/`" "` 原本就是 false，现在仍 false；**只有 `null` 从「抛 NPE」变为 false**。
- 不做 trim / 大小写归一：`" content "` 仍按未知类型 400，避免隐式改变契约。
- 不新增常量集合、不改 `TYPES`/`FIELDS` 内容；`allowedFields(null)` 本就用 `TYPE_X.equals(...)`，无需改。

### 3.2 错误 detail

- 未知/缺失类型：400 `CHANGE_FIELD_NOT_ALLOWED`，detail 为 `{changeType: null, allowedFields: [], allowedTypes: [CONTENT, DAYS, PARENT, RELEASE]}`（`field: null` 被 `ApiException` 丢弃），message 不变。
- 字段越界分支维持三键 `{changeType, field, allowedFields}`；四参重载只在类型分支使用，既有 `change-verify:464` 的 `.detail.allowedFields` 断言不受影响（additive）。

### 3.3 全仓扫描结论（详见 [NULL-SAFETY-SCAN.md](NULL-SAFETY-SCAN.md)）

- 范围：`req-service`/`deliver-service`/`platform-common`/`platform-contract` 的 main 源码，共 **142** 点 = 63 处不可变集合成员判定 + 79 处 `switch` 选择器。
- **必加固 1**：`RequirementChangeFields:87`；**建议 1**：`AdmissionItemPolicy:101` 的 `List.of().indexOf(null)`（同源 NPE，入参来自 DB 非空，本轮不改）；其余 **140** 点不可达（已判空/归一化/DB NOT NULL/枚举/内部常量），逐条落盘理由。
- switch 家族无外部可空选择器：B1 已判空或归一化 30、B2 非空构造 46、B3 外部字符串但容器或上游保证 3。
- 范围纪律：只修外部可达点，避免为「防御性统一」铺开约 20 个文件的改动。

### 3.4 门禁补强（`scripts/change-verify.sh` S2）

| 断言 | 期望 |
|---|---|
| body 只有 `{reason, patch}`（缺 `changeType`） | 400，`code=CHANGE_FIELD_NOT_ALLOWED`，且状态码非 5xx |
| `changeType=""` | 400（code 同上） |
| `changeType=" "` | 400（code 同上） |
| 通用：S2 全部错误响应 `assert_error_shape` | HTTP 4xx 且 body `code` 非空；禁止再以 500 表达校验失败 |

- `CHANGE_VERIFY_TOTAL` 42→**46**，头部「40/42 条」文案同步；`STATIC_TOTAL` 仍 12，六段（S1–S6）结构不变，`--twice` 逐行一致。
- 既有「未知类型 `NOPE` → 400」断言保留；不新增脚本、不改 Makefile（`change-verify` 已在 `test-all.sh` / `make test` 链内）。

## 4. 行为兼容性影响

- **唯一对外可见变化**：漏传 `changeType`（含显式 `null`）由 500 变为 400 `CHANGE_FIELD_NOT_ALLOWED`。前端正常路径固定传该字段，无感知；外部集成方从「无 code 的 500」变为可判别的 4xx，属修正。
- detail 新增 `allowedTypes`（仅类型分支，additive；前端忽略未知键，无需改动）。
- 审批流、`apply`、字段快照 7 键、r43 三数组与 PUT 的 409 语义全部不变；无 DB 变更、无新依赖、无构建配置变化。

## 5. 回归与验证方案

| 命令 | 门槛 |
|---|---|
| `mvn -o -pl req-service -am test` | 全绿，含新增用例，旧数不降 |
| `bash scripts/change-verify.sh` | **46/46**；`--static-only` 12/12；`--twice` 一致 |
| `bash scripts/editability-verify.sh` | 36/36（r43 矩阵不放宽） |
| `bash scripts/platform-verify.sh` | 199（先 `make platform-up`） |
| `bash scripts/platform-test-negative.sh` | 132 |
| `bash scripts/access-verify.sh` / `rbac-verify.sh` | 206 / 113 |
| `bash scripts/release-flow-verify.sh` / `gate-order-verify.sh` | 44 / 21 |
| `bash scripts/platform-demo-reset.sh` | 80，连跑两次一致 |
| `bash scripts/lint.sh` / `cd web && npm run typecheck && npm run build` | 项数不降 / 0 error（前端未改，防契约漂移） |

负向自检：临时把 `knownType` 回退为 `TYPES.contains(changeType)`，新增断言必须红；恢复后全绿。新计数写回 `docs/teamflow/memory.md` 门禁基线同主题行。

## 6. 风险与回滚

| 风险 | 处置 |
|---|---|
| R1 新断言与 payload 构造耦合（缺键 vs 显式 `null`） | 门禁覆盖缺键与空白，显式 `null` 由单测覆盖 |
| R2 detail 形状不统一（类型分支四键、字段分支三键） | 按语义分键并在本单固定；若验收要求统一，改 `RequirementErrors` 一处 + 一条断言 |
| R3 扫描漏判未来新入口 | 不可达点均留「为什么不可达」，新入口按清单复核；`knownType` 对 null/blank 恒 false，不会回退 |
| R4 断言数与脚本自检不一致 | 脚本静态自检用 `ck "` 调用点对齐声明总数，先跑 `--static-only` 即暴露 |

回滚：单 commit revert（交付面 = 3 个 Java 主源码 + 2 个测试 + 1 个门禁脚本 + 2 个产品文档 `docs/teamflow/memory.md`、`docs/teamflow/CHANGE-REQUEST-FLOW.md`）；`NULL-SAFETY-SCAN.md` 是审计证据、保留不回滚。**平台级共享文件不在交付面**（`platform-common/.../ApiException.java` 已回退到基线，`git diff` 为空），故不存在需要回滚的跨模块改动。无数据迁移。

## 7. 假设与待澄清

- A1 缺键与显式 `null` 等价（Jackson 均绑定为 null）：若要求区分，需在 `CreateRequest` 加 `@NotNull` 走 Spring 统一 400，错误 code 会变成框架默认值。
- A2 `changeType` 不做 trim/大小写归一：若要宽松（`" content "` 也接受），改 `knownType` 一行即可，但合法值集合语义随之变化。
- A3 `allowedTypes` 只出现在类型未知分支（detail 形状不统一）：若要固定四键，改 `RequirementErrors` 一处并同步一条门禁断言。
- A4 只加固外部可达点（1 处必改 + 1 处建议）：若要求全量防御性加固，改动面扩到约 20 个文件，回归成本显著上升。
- A5 扫描范围 = 4 模块 main 源码（不含测试与 `web/`）：测试内的同类写法不视为可达。
- A6 `AdmissionItemPolicy.orderOf` 本轮不改：如要求同族零残留，加一行 `code == null` 短路即可。

## 8. 交付口径

- Dev：按 §2/§3 改动，提交信息含 runId 与任务夹路径；完成后跑 §5 全量。
- QA：以 §3.4 四条断言 + §5 命令为验收依据，负向必红；计数差异以实测为准，只增不减。
- 验收：确认无功能 AC 变更（本单不新增用户可见能力），仅错误码与门禁增强。
