<!-- meta: summary="让 make platform-demo-reset 之后报表中心恰好有 2 张开箱可用的演示报表（需求状态分布 / 厂商交付概览），重置后可运行、可导出、幂等且不回归既有门禁" -->
基线依赖：docs/teamflow/20261113-r15-demo-reset-pollution（其「重置后只剩演示数据」的删除序列与双层断言不得回归）
取代：docs/teamflow/20261113-r15-demo-reset-pollution#AC-1：把「重置后报表中心为空（`reports=0`、库内 0、`/reports` total=0）」改为「重置后恰好 2 张预置演示报表」

# PRD · 演示重置预置 2 张演示报表

## 1. 背景与目标

r15 修好了演示数据污染：`make platform-demo-reset` 之后报表中心是**空态**（脚本断言 [42]：`/reports` total = 0，库内 `report_definition` = 0）。空态是干净，但不是可演示——每次演示都要现场新建报表、现场配维度、现场试图表，讲「报表能力」变成讲「编辑器怎么用」。

**目标**：把「重置」从「干净的空库」推进到「干净且有 2 张开箱可用的演示报表」。执行 `make platform-demo-reset` 后，用 admin 登录 `/reports` 能看到恰好 2 张报表（数据来自已有的 12 条演示需求集），点开即是可用的交叉表，能切图表、能导出 XLSX，不需要现场新建。

**成功语义（一句话）**：重置后的报表中心是一份**已写好口径的演示稿**，而不是一张白纸。

**本次只做这一件事**：新增 2 张报表定义 + 同步断言；不改报表引擎、不改 UI、不改中英文标签、不清理 wiki 探针页、不恢复 `api_client`。

## 2. 用户故事与验收标准

### US-1 演示者打开报表中心就有东西可讲（P0）

> 作为演示者，重置后登录 `/reports`，我要看到 2 张名字与口径都能直接讲的报表，点开就有数据。

- **AC-1（数量与名称）**：`make platform-demo-reset` 成功后，admin 调 `GET /api/deliver/reports?page=1&size=50`：`total = 2`，名称集合**恰为** {`需求状态分布`, `厂商交付概览`}（顺序不限，多一张/少一张/多一字都失败）。同一事实三处必须一致：服务端 `counts().reports = 2`（`DemoResetService.counts()`）、库内 `SELECT COUNT(*) FROM report_definition WHERE tenant_id=1 = 2`、`scripts/platform-demo-reset.sh` 的 API 断言（当前 `scripts/platform-demo-reset.sh:228-233` 的 `total = 0` 必须改为 `total = 2` + 名称集合相等；`:107` 的 `check_count reports 0` 与 `:157-163` 的库内 `== "0"` 同步改为 `2`）。
- **AC-2（两张必须不是同一张）**：两张定义回读（`GET /api/deliver/reports/{id}`）的 `(dataset, rows, columns, measures)` 两两不同，且字段全部落在 `GET /reports/datasets` 的白名单内：
  - **A「需求状态分布」**：`dataset = requirement`，`rows = [domain]`（域），`columns = [status]`（状态），`measures = [req.total, cycle.avg]`（需求数、平均交付周期），`chartType = TABLE`。**必须同时有行维度与列维度**，且列维度取值 ≥ 2（交叉表肉眼可见是二维的）。
  - **B「厂商交付概览」**：`dataset = requirement`，`rows = [vendor]`（承接厂商），`columns = []`，`measures = [req.total, effort.estimate, effort.deviation]`（需求数、预估人天、人天偏差），`chartType = TABLE`。**单维多度量**，与 A 的二维形状形成对照。
  - 说明：B 用 `requirement` 而非 `delivery`，因为「预估人天 / 人天偏差」只在需求数据集注册（`deliver-service/.../analytics/AnalyticsDatasets.java:172-185`）；`delivery` 数据集没有 effort 类度量，硬切会退化成「需求数 + 准时率」两张都只有计数的报表。
- **AC-3（打开即出数）**：重置后对两张各发一次 `POST /api/deliver/reports/{id}/run`（空 body）：HTTP 200；A 的 `rows ≥ 1` 且「需求数」列存在 ≥1 个正数；B 的厂商行 ≥ 2（admin 全量口径：VEN-A 与 VEN-C 都应出现）且「预估人天」合计 > 0；两者的 cells 不得全为空/全为 0。
- **AC-4（中文标签不回归，r14）**：两次 `run` 返回的 `colHeaders` / `rowHeaders` 全为中文，正则 `\b[A-Z]{3,}(_[A-Z]+)*\b` 0 命中（含 A 的列头，如状态不得出现 `PENDING_TEST`）；`colCodes` / `rowCodes` 与 header 同长同序、保留机器可读 code（合计列为 `null`）；`make analytics-i18n-verify`（静态 + 全量）绿。
- **AC-5（可导出）**：两张各 `GET /api/deliver/reports/{id}/export?format=xlsx`：200、content-type 为 xlsx、字节头 `PK\x03\x04`；表头行与页面表头**逐字符串相等**（中文）；带 r14 的水印行（导出件不是无水印裸表）。

### US-2 重置仍然是一次可重复的固定夹具（P0）

> 作为团队，我要能随时重跑重置、随时演示；报表不能越跑越多，也不能第二次就消失。

- **AC-6（幂等）**：连跑两次 `make platform-demo-reset`：两次之后 `total` 恒为 2、名称集合相同、`definition_json` 规范化（键排序）后 `dataset/rows/columns/measures/filters/chartType/scope/scopeRef` 逐字段相等。**id 允许变化**（自增），任何断言/文档不得写死 id。
- **AC-7（探针零残留）**：两张预置报表之外，报表名对 `T8 编辑器探针`、`r11-ev-`、`r13-` 三个探针模式 0 命中（谓词唯一来源 `req-service/.../seed/DemoResetService.java:67`）；这条是「预置 2 张」与「上一轮探针被清干净」的区分依据，缺了它，报表数=2 可能只是「探针没长出来」。
- **AC-8（只在演示重置链路产生）**：`make platform-seed` 之后报表数仍为 0；应用正常启动不插入报表。预置只发生在既有的 `DemoFixture` + `DemoResetService` 单一路径内：不新增第二个 `make` 目标、不新增第二套报表目录、不引入独立 JSON 文件作为报表定义来源。
- **AC-9（不越权）**：预置报表不得让任何角色看到超出其 Scope 的数据——非属主角色（如 vendor1）请求这两张报表的 `run`/`export` 必须得到 403/404（走既有 `ReportService` 的 scope 判定），响应体不含任何需求行或厂商名。

### US-3 既有演示口径与门禁不回归（P0）

> 作为 QA，我要确认这次改动只是「多两条固定数据」，其余一切照旧。

- **AC-10（门户口径不变）**：重置后 admin 的门户配置仍为 **4 张模板卡**、无 `report:` 前缀卡片、`portalPins = 0`（原 [43]/[44] 断言不改）。**默认不把报表 pin 成个人卡片**；若后续判定「其中一张作为工作台卡片候选」更利于演示，必须在验收报告写明理由并同步断言——本 PRD 不建议改（见 §4 反面目标 4）。
- **AC-11（门禁全绿）**：`platform-verify`、`analytics-i18n-verify`（静态 + 全量）、`access-verify`、`platform-test-negative`、`platform-seed`、`unit`、`lint`、`verify` 全部绿；`make platform-demo-reset` 0 fail，pass 条数由 49 增加（新增 3–5 条），**QA 报告必须写明实测条数**（不接受「约 50」）。
- **AC-12（失败可定位）**：任何新断言的失败信息必须带维度数字/集合，例如「报表名称集合 [需求状态分布, 厂商交付概览, T8 编辑器探针]，期望 2 张」「报表数 2，但名称集合不符：缺 厂商交付概览」「run 返回 0 行：检查 definition 的 rows/columns 是否在数据集白名单内」。禁止 `report check failed` 这类无信息失败。

## 3. 范围与非目标

**范围**
1. `DemoFixture` 内新增 2 张报表定义的**数据常量**（名称、数据集、维度/度量、图表类型、scope、scopeRef、owner），与需求行/用例行同属一份固定夹具。
2. `DemoResetService` 在清表之后按主键回填这两行（`report_definition` 已由 `DELETE_ORDER` 整表清空，`DemoFixture.java:157` 起），并让 `counts().reports` 与不变量断言反映 2。
3. `scripts/platform-demo-reset.sh` 三处断言同步：服务端计数、库内计数、API 数量+名称集合；保留探针命中 = 0 的诊断断言。
4. 演示可运行性自证（run 非空 + 中文表头 + export 带水印）纳入本轮验收证据。

**非目标**
- 不改 `/reports` 列表与编辑器的任何 UI/交互（r13 已交付）。
- 不改报表引擎（`AnalyticsQuery`/`AnalyticsSql`/`ReportService` 的运行与导出逻辑），不新增数据集、不新增指标、不改 i18n 注册表。
- 不新增 `make` 目标；不把报表定义搬到独立配置文件或第二张表。
- 不让重置外（如 `platform-seed`）产生报表。
- 不清理 wiki 探针页、不恢复 `api_client`、不动门户 pin。

## 4. anti-goals / 反面目标

> 以下每一条都是「测试全绿但仍会被演示者当场否掉」的具体失败，且演示现场 10 秒内可看出。

1. **点开是空表**：交叉表 0 行，或所有数值都是 0/空——报表的筛选口径（时间窗/状态）把 12 条演示需求全过滤掉了，或维度字段写错（如把 `standard` 当 `status`）。检查方式：重置后 admin 点开 A，第一屏必须看到 ≥1 行且「需求数」有正数。
2. **两张看起来是同一张**：维度与度量高度重叠（例如都只有「需求数」、都按状态分组），演示时讲不清区别，等于白预置。检查方式：并排打开，行维度、列维度形状、度量数至少两项不同（A 二维两度量 / B 一维三度量）。
3. **表头出现英文或「未知状态」**：如 `PENDING_TEST`、`req.total`、`vendor` 出现在列头，或某列头是「未知状态」——说明定义里的字段/取值绕开了 r14 注册表。检查方式：肉眼读列头 + `make analytics-i18n-verify`。
4. **admin 看不到 / 点开 403**：重置后 `/reports` 仍是空态，或两张都报 403/404——scope 选了 `DOMAIN`/`VENDOR` 却依赖环境写死的 `scopeRef`，或 owner 不是演示账号。检查方式：admin 登录、点开两张（这也是「不自动 pin」的前提：列表能看见但门户不自动加卡）。
5. **越跑越多或越跑越少**：第二次重置后变成 4 张（清表漏了/唯一键 `uk_report_name` 让插入静默跳过又被别的路径补回），或 0 张（删除序列与插入不在同一事务）。检查方式：连跑两次，报表总数始终 2、名称集合恒定。

## 5. 交互流程摘要

```
make platform-demo-reset
  └─ POST /api/req/admin/demo-reset（admin 令牌）
       └─ DemoResetService（单事务）
            1) 按 DemoFixture.DELETE_ORDER 清表（含 report_definition 整表清空）
            2) 回填演示数据：需求 12 / 用例 12 / 缺陷 3 / 通知 4 … + **报表 2**
            3) 不变量自检：counts().reports = 2、门户 cards = 4、portalPins = 0、隔离 8/3/0
  └─ scripts/platform-demo-reset.sh 断言：服务端计数 / 库内计数 / API total=2 + 名称集合 / 探针 0
演示路径：admin 登录 → /reports（2 张卡）→ 点 A（交叉表：域 × 状态，需求数 + 平均交付周期）
          → 切图表 → 导出 XLSX（水印）→ 返回列表点 B（厂商 × 需求数/预估人天/人天偏差）
```

## 6. 优先级

| 优先级 | 内容 | 理由 |
|---|---|---|
| **P0** | AC-1、AC-2、AC-3、AC-6、AC-7、AC-8、AC-9、AC-10、AC-11 | 「有 2 张、能出数、能重复、不越权、门禁绿」构成本需求的成立条件；缺一条，演示当场断 |
| **P0** | AC-5（导出带水印） | 需求明文要求；且 r14 水印是导出链路唯一回归信号 |
| **P1** | AC-4（i18n 不回归）、AC-12（失败可定位） | AC-4 由既有 `analytics-i18n-verify` 强约束，属回归防线；AC-12 影响维护成本而非演示效果 |

## 7. 依赖与风险

**依赖**
- `report_definition` 表结构（`req-service/src/main/resources/db/migration/V10__workbench_reports.sql:25-47`）：`scope`/`scope_ref`/`chart_type`/`definition_json`/`active_flag` 齐备；唯一键 `(tenant_id, owner_id, name, active_flag)` 决定「同名活动报表只能一张」，是幂等必须整表清空的原因。
- 报表运行链路：`POST /reports/{id}/run`、`GET /reports/{id}/export`、`GET /reports/datasets`（`deliver-service/.../reports/ReportController.java`）；图表类型仅 `TABLE/BAR/LINE/PIE`（`ReportService.java:478-481`）。
- 演示数据集：需求 12 条含 `estimate_days`（DONE 行的 `actual_days` 取同值）、域/厂商/状态维度均有取值；隔离口径 po1=8 / VEN-A=3 / VEN-B=0。
- 分析缓存 TTL 300s，但键前缀为 `report:<id>|`（`AnalyticsCache` 注释），**重置后 id 必变 → 无陈旧条目**，AC-3 不依赖缓存失效；`ReportService` 的写路径已 `evictReport(id)`。

**风险**
1. **其他门禁对报表数量的硬断言**：`workbench-verify`/`report-verify`/`access-verify` 若存在「某角色报表列表 total = N」的断言，新增 2 张会撞红。缓解：默认 `PRIVATE` 属主 admin（最小暴露面），技术方案阶段须 grep 全部脚本并列出受影响断言；若必须公开共享再按 AC-9 评估。
2. **名称/口径与断言耦合**：脚本按名称集合断言，改一个字的名称就红——这是有意的（演示文案也是交付物），但改名前必须同步脚本与截图。
3. **人天偏差口径为 0**：演示数据里 `actual_days = estimate_days`（DONE 行），「人天偏差」列可能整列为 0，演示说服力弱。缓解：AC-3 只要求非空非全 0，不要求偏差非 0；若 QA 认为演示效果不足，在技术方案里评估是否给已关闭演示需求设置差异化 `actual_days`（不改行数与隔离口径）。
4. **管理员演示账号漂移**：若 owner 写死某个 id 而非按 `admin` 用户名解析，种子账号 id 变化后报表会变成「无主不可见」。缓解：按 `DemoFixture` 既有的 `refs.get("user:admin")` 解析，禁止写死数字。

## 8. 工程约束

**分支与提交**（来自本轮状态核对，原样执行）
- 当前分支：`feat/demo-report-preseed`；**在该分支上开发，不另开分支、不切 main**。
- 未提交改动 1 处：`?? docs/teamflow/20261113-r16-demo-report-preseed/`（本任务夹，含本 PRD）。**不要丢弃、不要 stash 掉**；与代码改动一并在本轮收尾提交，提交前 `git status` 只应剩忽略项。
- 不要改写任何历史任务夹（含 `docs/teamflow/20261113-r15-demo-reset-pollution/` 的全部产物）；r15 的 `QA-REPORT.md`/`ACCEPTANCE.md` 是历史记录。
- 近期提交可能含场外改动（`f4ad57a` 补 r15 截图等），提交前先看 `git log`/`git diff` 再决定提交范围，不整树盲提。

**.gitignore 审计（dev 阶段动作，无需改）**
- 已按本产品真实工具链覆盖：Maven `target/`、`.flattened-pom.xml`、`.mvn/timing.properties`；Nacos `.nacos/`；前端 `node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`；运行态 `logs/`、`.run/`、`.m2/`、`var/`、`*.pid`、`*.hprof`；导出残留 `*.xlsx`、`*.csv`；IDE/macOS `.idea/`、`*.iml`、`.DS_Store`；`logs/teamflow/`（插件运行日志）。
- 本轮若新增文件（报表定义常量、脚本断言、自检脚本），均为源码/脚本文本，**不得新增需忽略的构建产物**；若出现新的本地产物（如导出的 `报表-*.xlsx`），现有 `*.xlsx`/`*.csv` 规则已覆盖。**不要从 `.gitignore` 删除任何既有行**，尤其是 `logs/teamflow/` 与 `config/nacos/` 相关注释（后者是「必须入库」的提醒）。
- 依赖零新增：不引入新 Maven/npm 依赖（含测试依赖）。

## 9. 假设与待澄清

以下三条均为**口味类**（`kind: taste`）——都能做出可交付的版本，但选错会让演示者觉得「这不是我要的那张报表」。**未发现必须由用户裁决的事实缺口**：表结构、数据集字段白名单、缓存键、三处断言的位置均已在仓库核实（见 §7）。

- **共享档位与属主**：假设两张都设 `PRIVATE`、属主为 admin（`refs.get("user:admin")`），理由是只有 admin 演示时开箱即见，且不改变 po1/vendor1 等角色的报表列表、不会撞既有门禁的数量断言。若改为 `DOMAIN`（域内共享）或 `PUBLIC`，其他角色的列表与截图会变、需同步断言与验收证据。
- **是否把其中一张 pin 成工作台卡片**：假设**不 pin**，保持门户 [43]/[44] 的 4 张模板卡口径（需求默认即此）。若用户希望「工作台也有一张现成报表卡」，需要额外改门户配置并同步断言与截图。
- **两张报表的内容口径与命名**：假设沿用需求建议的 `需求状态分布`（域 × 状态，需求数 + 平均交付周期）与 `厂商交付概览`（厂商 × 需求数/预估人天/人天偏差）。若用户更想演示「交付准时」而非「人天」，B 可换成 `delivery` 数据集的「需求数 + 准时率 + P90 周期」——这会让两张报表分属两个数据集，语义更分得开，但失去人天口径。

## 10. 里程碑建议

- **M1 · 数据与插入路径（P0）**：`DemoFixture` 新增两张定义常量（含 owner 解析、scope），`DemoResetService` 在清表后回填并让 `counts().reports = 2` 与不变量断言一致；单测覆盖「重置后恰好 2 张、定义可被 codec 解析」。
- **M2 · 断言同步与幂等（P0）**：`scripts/platform-demo-reset.sh` 三处断言改为 2 + 名称集合，保留探针 0 命中；连跑两次实测幂等（id 可变、内容恒定）。
- **M3 · 可演示自证与回归（P0）**：run 非空 + 中文表头 + export 带水印的证据留档；全门禁回归（含 `analytics-i18n-verify` 静态+全量）；QA 报告写明重置脚本实测 pass 条数与名称集合。
