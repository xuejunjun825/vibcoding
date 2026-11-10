<!-- meta: summary="补齐 r9 遗留的三组后端接口（datasets 白名单 / 岗位工作台五区与配置 / 自助报表 CRUD·共享·pin·run·水印导出），全部复用唯一 analytics 聚合口径与 ScopedJdbc 可见性，只交付后端与 curl 证据" -->

基线依赖：docs/teamflow/20261110-r9-workbench-reports/（V10 两表、60 权限码与 5 条路由准入、前端类型契约、300s 缓存与 `AnalyticsQueryService` 单一聚合均为其已确立行为，本轮只在其上补接口，不得回退）

取代：docs/teamflow/20261110-r9-workbench-reports/#AC-8：共享档位由四档扩为五档（新增 VENDOR=按厂商共享；TENANT 作为 PUBLIC 的入参别名），既有 ROLE/DOMAIN/PUBLIC 的语义与可见性断言不变。

# r11 · 岗位工作台与自助报表后端补齐（只做后端）

## 1. 背景与目标

r9 把三块页面写进了 `DELIVERED-MODULES §9`，前端骨架（4 条路由、`web/src/api/analytics.ts` 的完整类型、nav 三项）与数据层（`AnalyticsQueryService`/`AnalyticsMetrics`/`AnalyticsCache`/`AnalyticsDatasets`/V10 迁移）也已落地，但**控制器只通了 `GET /analytics/metrics` 与 `GET /analytics/dashboard` 两条**：`/analytics/datasets`、`/portal`、`/portal/config`、`/reports/**` 在网关后一律 404。于是一旦点开「岗位工作台」「报表」两个菜单，用户看到的不是数据而是空壳——前端类型已经按契约写死，改前端救不了。

本轮只做后端：把这三组接口补齐，并保证它们与已经上线的 `/analytics/metrics`、`/analytics/dashboard` **同一口径**。所谓同一口径是三项硬要求：同一指标只允许一个公式实现（`AnalyticsMetrics`，P90 只走 `FlowMetricFormulas`）；同一份数据只允许一处取数并带行级过滤（`ScopedJdbc`/`Scope`）；同一报表在页面、导出、门户卡三处的数字逐位相同。

交付判定不看单元测试绿，看 7 条 curl 证据（§2.3）：admin 能列数据集、拉门户、建报表、跑交叉表、导出带水印的 xlsx、共享到 DOMAIN、第 5 张卡被 409 拒绝；vendor1 与 vendor2 对同一报表互不含对方数据；pm 与 dev1 的越权调用分别被 403。

## 2. 用户故事与验收标准

### 2.1 用户故事

| # | 角色 | 故事 | 对应 AC |
|---|---|---|---|
| US-1 | 全角色用户 | 打开 `/portal`，一次拿到属于我岗位模板的五个区，数字与看板同源 | AC-2、AC-3、AC-4 |
| US-2 | 管理员 | 配置某角色可见的 4 张卡（只显隐、不加卡），保存后该角色所有人下次打开即生效 | AC-5 |
| US-3 | 任何人 | 把一张报表设为我的门户卡，最多 4 张；超了要被明确拒绝并告诉我是哪 4 张 | AC-6 |
| US-4 | 分析师 | 新建/改名/复制/删除报表，定义往返不变；私有报表别人（含管理员）打不开 | AC-7、AC-8 |
| US-5 | 报表属主 | 把报表共享到角色/域/厂商/全体，共享只共享定义，数据仍按查看者自己的范围重算 | AC-9、AC-10、AC-11 |
| US-6 | 分析师 | 把当前交叉表导成 xlsx/csv，行数与页面一致、带水印、拿到的绝不是我无权看的行 | AC-12 |

### 2.2 验收标准

约定：`G=http://127.0.0.1:8080/api`，`D=$G/deliver`，登录沿用既有平台契约（`POST $G/auth/login`，admin=`Admin@12345`，其余账号=`Passw0rd!`），下文 `tok <user>` 表示取该账号 token。所有证据都要能贴出真实输出，不接受「已实现」。

**AC-1 编译与改动边界**
- `mvn -o -q -DskipTests compile` 退出码 0；`cd web && npm run build` 退出码 0（本轮不改 web，此条是零回归证据）。
- `git status --porcelain` 中，非文档改动只出现在 `deliver-service/src/main/java/com/example/deliver/`（`analytics/` 少量补充 + 新增 `portal/`、`reports/`、`support/`）；`web/**`、`scripts/**`、`deliver-service/.../quality/**`、`req-service/src/main/resources/db/migration/V10__*.sql` 零 diff。
- 既有接口行为不变：`GET /analytics/metrics?keys=req.total` 与 `GET /analytics/dashboard` 均 200，且 `X-Report-Cache` 头仍为 HIT/MISS 语义。
- 证据：`mvn -o -q -DskipTests compile; echo rc=$?` / `git status --porcelain | grep -v '^?? docs/teamflow/'` / `git diff --stat -- web scripts req-service/src/main/resources/db/migration`

**AC-2 数据集白名单（datasets）**
- `GET $D/analytics/datasets` → 200 `{items:[…]}`，`items` 长度恰 4，`code` 依次含 `requirement`、`delivery`、`approval`、`quality`；每项有 `label` 与 `fields[]`，每个 field 含 `key/label/kind/measure`。
- 同一份装配另有别名 `GET $D/reports/datasets`（前端 `fetchDatasets()` 写死了这条路径），两次响应 body **逐字节相同**。
- 白名单只有一个来源：`grep -rn "new AnalyticsViews.Dataset" deliver-service/src/main/java` 命中 1 处（`AnalyticsDatasets`）。
- 负向：`POST $D/reports/{id}/run` 传未知字段 → 400 `REPORT_UNKNOWN_FIELD` 且 `detail.field` 指出该字段；传 A 数据集的字段到 B 数据集 → 400 `REPORT_DATASET_MISMATCH`。
- 证据：`tok admin` 后 `curl -s $D/analytics/datasets | jq -c '[.items[].code]'` → `["requirement","delivery","approval","quality"]`；`diff <(curl -s …/analytics/datasets | jq -S .) <(curl -s …/reports/datasets | jq -S .) && echo SAME`。

**AC-3 岗位工作台五区（portal）**
- `GET $D/portal` → 200，一次返回 `{template,templateLabel,todoMode,sections[5],todoSources[],dataAsOf,cache}`；`sections` 恰好 5 个且顺序固定为 `todo`（待办中心）→ `actions`（快捷动作）→ `metrics`（数据卡片）→ `list`（我的列表）→ `messages`（消息入口），每个 section 有 `state∈{OK,DEGRADED}`。
- 角色模板覆盖：admin→`admin`、pm→`product`、biz1→`business`、po1→`sub-business`、tl1→`pmo`、dev1→`dev`、qa1→`qa`、mgr1→`mgmt`、vendor1→`vendor`（第 9 套，见 §7-Q4）共 9 套；未匹配角色回落 `fallback`。需求点名的 admin/PRODUCT/DEV/QA/PMO/vendor 六类**不得落到 fallback**。
- 待办语义：`mgmt` 的 `todoMode=READONLY` 且 `todoSources` 为空；其余 FULL 且 `todoSources` 非空。任一源失败时该源 `count=null`+`state=DEGRADED`+`error` 可读，**不得以 0 冒充**。
- 卡片：`sections[2].cards` 每张有 `{key,title,kind∈{TEMPLATE,PERSONAL},metrics[1..2]}`，`metrics` 的 `key/value/unit/digits` 可直接渲染（前端不做除法）。
- 证据：逐个账号 `curl -s $D/portal | jq -c '{t:.template,m:.todoMode,s:[.sections[].key]}'`，九行输出贴出；`jq -c '.sections[2].cards[]|{key,title,n:(.metrics|length)}'`。

**AC-4 三处同源（不可各写一套）**
- portal 卡上的读数与 `/analytics/metrics?keys=<同 key>` 的 `value` **字符串逐位相同**（同一 `dataAsOf` 前提下）。
- `crossTab` 的度量值由 `AnalyticsMetrics` 计算，`AnalyticsQueryService` 之外不存在第二份百分比/除法实现：`grep -rn "double pct\|/ *100\|completionRate" deliver-service/src/main/java/com/example/deliver/{analytics,portal,reports}` 里，除 `AnalyticsMetrics` 定义处外不得出现新算式。
- portal 与 reports 包不得自行 `jdbc.query`/拼 SQL：`grep -rn "JdbcTemplate\|SELECT " deliver-service/src/main/java/com/example/deliver/{portal,reports}` 结果为空（取数只有 `AnalyticsQueryService` 一处）。
- 证据：`ck=$(curl -s "$D/analytics/metrics?keys=req.completion" | jq -r '.items[0].key+"="+(.items[0].value|tostring)')` 与 portal 卡对应 metric 的 `key=value` 相等；贴 grep 输出。

**AC-5 卡片配置（portal/config）**
- `GET $D/portal/config?roleCode=DEV`（管理员）→ 200 `{roleCode,cardKeys[],maxCards:4,cards[{key,title,reportId}]}`；非管理员只能取自己角色，传他人角色 → 403 `FORBIDDEN` 且 `detail.functions` 含 `PORTAL_CONFIG`。
- `PUT $D/portal/config` body `{roleCode:"DEV",cardKeys:[…]}`：管理员 200 且回读一致；`deliver` 库 `devops_platform.portal_card_config` 中该 `(tenant_id,role_code,user_id=0)` **只有一行**（`user_id=0` 是角色级哨兵）。
- 幂等：同一 body 连发两次仍 1 行，`updated_at` 变化、`card_keys` 不变。
- 越权：`pm` 调 `PUT /portal/config` → **403**，`code=FORBIDDEN`，`detail.functions` 含 `PORTAL_CONFIG`。
- 未知卡 key → 400 `PORTAL_UNKNOWN_CARD`，`detail.known` 列出当前可配 key；`cardKeys` 超过 4 → 400 `PORTAL_CARD_LIMIT`。只做显隐，不接受新增/改序（顺序按 `cardKeys` 传入序）。
- 证据：管理员 PUT+GET 回读一致性对拍；`pm` 的 `curl -s -o /dev/null -w '%{http_code}'` 为 403 且 body 贴出；DB 行数 `SELECT COUNT(*) … WHERE role_code='DEV' AND user_id=0` 为 1。

**AC-6 设为卡片与 4 张上限（pin）**
- `POST $D/reports/{id}/pin` → 200 `{pinned:true,roleCode,cards[]}`，落库 `portal_card_config` 中 `user_id=<当前用户 id>` 的个人行；重复 pin 同一报表幂等（不新增行）。
- 已 pin 4 张时 pin 第 5 张 → **409** `PORTAL_CARD_LIMIT`，且 body `detail` 含 `maxCards:4` 与 `cards`（当前 4 张，元素 `{key,title,reportId}`；同时保留 `cardKeys` 兼容旧契约）。
- 装配规则：个人卡优先占位，其后用角色/模板卡补足，`sections[2].cards` 总数 ≤4；个人卡不影响同角色其他用户。
- 证据：连续 pin 5 个报表，前 4 条 200、第 5 条 `curl -s -w '\n%{http_code}'` 得 409 且 `jq -c '.detail.cards|length'` = 4；另一账号 `GET /portal` 的 `sections[2].cards` 不含该个人卡。

**AC-7 报表 CRUD（含复制与软删）**
- `POST $D/reports` body `{name,datasetId,definition:{datasetId,rows,columns,measures,filters},chartType}` → 201，返回含 `id/name/datasetId/datasetLabel/dimCount/measureCount/scope:"PRIVATE"/ownerId/ownerName/chartType/updatedAt`，默认 `chartType=TABLE`、`scope=PRIVATE`。
- `GET $D/reports/{id}` 的 `definition` 与 POST 传入的**深比较相等**（键序无关）；`GET $D/reports?segment=MINE` 分页含该 id。
- `PUT $D/reports/{id}` 改名 → 200；同账号重名 → 409 `REPORT_NAME_DUPLICATED`。
- `POST $D/reports/{id}/copy` → 201，`name` 形如 `<原名>-副本`（重名时追加序号），`scope=PRIVATE`，`definition` 与原报表相等，`owner` 为调用者。
- `DELETE $D/reports/{id}` → 204（软删，`status='DELETED'`）：之后 `GET` 404 `REPORT_NOT_FOUND`、列表不含；**同名可再次新建**（唯一键含生成列 `active_flag`）。
- 证据：`diff <(jq -S .definition create.json) <(curl -s … | jq -S .definition) && echo ROUNDTRIP-OK`；`DELETE` 后同名 POST 返回 201；DB `SELECT id,status FROM devops_platform.report_definition WHERE id=…` 为 `DELETED`。

**AC-8 私有报表的可见性**
- 他人 `GET/PUT/DELETE/copy/run` 一张 PRIVATE 报表 → 一律 **404** `REPORT_NOT_FOUND`（不泄露存在性，管理员同样 404）。
- `dev1` 的 `?segment=MINE` 不含该报表；属主的 `MINE` 含。
- 证据：`dev1` 对 admin 的私有报表取 `-o /dev/null -w '%{http_code}'` 为 404，body `code=REPORT_NOT_FOUND`；属主 list 的 id 集合含之。

**AC-9 共享档位与闸门（share）**
- `PUT $D/reports/{id}/share` body `{scope,scopeRef}`：`scope ∈ {PRIVATE,ROLE,DOMAIN,VENDOR,PUBLIC}`，`TENANT` 作为 `PUBLIC` 的入参别名被接受，**落库规范化为 PUBLIC、响应也为 PUBLIC**。
- `scopeRef` 为数值 id：ROLE→`sys_role.id`、DOMAIN→`sys_domain.id`、VENDOR→`sys_vendor.id`、PRIVATE/PUBLIC→必须为空；DTO 接受数字或数字字符串。非空但非数值 → 400 `REPORT_UNKNOWN_FIELD`（`detail.field="scopeRef"`）；非法 `scope` → 400 `REPORT_UNKNOWN_FIELD`（`detail.field="scope"`）。
- 非属主调 share → 403 `REPORT_SCOPE_FORBIDDEN`；`dev1`（无 REPORT_SHARE）→ 403 `FORBIDDEN` 且 `detail.functions` 含 `REPORT_SHARE`；`pm`/`po1`/`mgr1` 调 share 成功（REPORT_SHARE 在 PMO/PRODUCT/MGMT）。
- 共享只共享定义：命中共享的他人 `GET` 看到 `definition` 与 `scope`，`run` 的 `rowHeaders` 按查看者 Scope 重算（与属主的行集不同即证明）。
- VENDOR 档：共享给 vendor2 后，vendor2 的 `?segment=SHARED` 含之、vendor1 **不含**；PRIVATE 报表不进任何人的 SHARED 段。
- 证据：admin 共享到 `DOMAIN`（scopeRef=域 id）→ 200 且 `jq .data.scope` = `DOMAIN`；`pm` 调 share → 200，`dev1` → 403；vendor1/vendor2 的 SHARED 列表各贴一条。

**AC-10 交叉表运行与缓存（run）**
- `POST $D/reports/{id}/run`：body 可省略 `definition`（用已存定义）或带 `definition`（草稿试算，**不写库**）；返回 `CrossTab`（`rowHeaders/colHeaders/rows[].values,counts/…/dataAsOf/cache`），且响应头 `X-Report-Cache` 与 body `cache` 一致。
- 同账号同参数连发两次：第一次 `MISS`、第二次 `HIT`；TTL 由 `analytics.cache.ttl-seconds` 控制，默认 300s。
- 换账号不串缓存：vendor1 对一张 admin 共享到 DOMAIN 的报表首次 run 必须 `MISS`，且行集与其可见范围一致（缓存 key 混入 scope，见 `AnalyticsRequest#cacheKey`）。
- 失效：`PUT /reports/{id}` 改定义后，该报表下一次 run 为 `MISS`，**其他报表仍 `HIT`**（只清自己）。
- 证据：`:run` 两次的 `jq -r '.cache'` 依次 `MISS`/`HIT`；改定义后再 run 得 `MISS` 且另一报表仍 `HIT`。

**AC-11 厂商隔离（同一份定义，两个账号）**
- 一张 `scope=DOMAIN` 的报表（行维度含 `vendorId` 或按厂商分组）分别由 vendor1、vendor2 run：两者 `rowHeaders` 交集为空、各自非空；各自 `counts` 合计小于 pm 全量，且两者之和 ≤ pm 全量。
- vendor1 的响应体不含 vendor2 的厂商标识（VEN-B / vendor2 对应 id）与域名外的行：`grep -c` 为 0。
- 隔离不靠 controller 兜底：`grep -rn "vendor_id\|vendorId" deliver-service/src/main/java/com/example/deliver/{portal,reports}` 中不得出现手写过滤条件（过滤只在 `AnalyticsSql`/`ScopedJdbc`）。
- 证据：三条 run 的 `jq -c '{n:(.rows|length),heads:.rowHeaders}'` 贴出，并给 `comm -12` 交集为空的输出。

**AC-12 导出（xlsx / csv，带水印、不越权）**
- `GET $D/reports/{id}/export?format=xlsx`：200，`Content-Type` 为 `…spreadsheetml.sheet`，`Content-Disposition: attachment; filename*=UTF-8''…`，`Content-Length`>2KB（二进制非空）；默认格式即 xlsx（`format` 缺省）。
- xlsx 首行为水印行（合并单元格、底色 `#EAF1FE`、含报表名+数据集+生成时间+「水印」字样），末行为页脚水印；数据行数与页面交叉表一致（含小计/总计行）。
- `format=csv`：200 `text/csv`，首字节为 UTF-8 BOM `EF BB BF`，首行 `#` 开头的元信息（报表/数据集/生成时间/行数/水印），数据行数 = 页面 `rows` 数。
- 上限：超过 10000 行 → 400 `REPORT_EXPORT_LIMIT`（`detail.rows`/`detail.maxRows`），**不得返回半截文件**。
- 不越权：vendor1 对同一报表导出的数据行数 = vendor1 自己 run 出来的组数，`grep VEN-B` 为 0；越权读他人私有报表导出 → 404。
- 审计：导出写一条 `action=report.export`、`bizType=report_definition`、`bizId=报表 id` 的操作日志（可查库证明）。
- 证据：`curl -sD- -o /tmp/r.xlsx …/export` 的响应头 + `ls -l /tmp/r.xlsx`；`head -c 3 /tmp/r.csv | xxd -p` 得 `efbbbf`；超限用单测构造第 10001 行触发。

**AC-13 降级与错误码契约**
- 单块失败只降级该块：模拟某区取数抛错（单测注入），`GET /portal` 仍 200，该 section `state=DEGRADED`+`error`，其余 section `OK`，页面不回滚成空白。
- 五类错误一律按契约返回（400/403/404/409 与 `code`），且 `code` 字面量只在 `AnalyticsErrors` 一处定义：`grep -rn "REPORT_EXPORT_LIMIT\|PORTAL_CARD_LIMIT\|PORTAL_UNKNOWN_CARD" deliver-service/src/main/java` 除 `AnalyticsErrors` 外无定义处。
- 错误 body 形态与平台一致：`{code,message,detail}`，`detail` 至少带一个可定位字段（`field`/`maxCards`/`cards`/`rows`）。
- 证据：单测名 + 断言输出；`curl -s … | jq -c '{code,detail}'` 五条。

### 2.3 完成判定（本轮必须贴出的证据）

一次 `make platform-up && make platform-seed` 之后，按顺序跑通并贴输出：

1. `mvn -o -q -DskipTests compile` 与 `cd web && npm run build` 均 0；
2. admin 列 datasets（4 项）与 `reports/datasets` 逐字节相同；
3. admin 拉 `/portal`（5 区、模板正确、卡片有读数）；
4. admin 建报表 → `run` 出交叉表（`cache` MISS→HIT）；
5. admin 导出 xlsx（`Content-Disposition` + 非空二进制 + 首行水印）；
6. admin `share` 到 DOMAIN 成功（回读 `scope=DOMAIN`）；
7. 第 5 张 `pin` 返回 409 且 `detail.cards` 长度 4；
8. vendor1 与 vendor2 对同一报表 run 的结果集互不含对方行（两条 curl + 行数 + 交集为空）；
9. `pm` 调 `PUT /portal/config` 403；`dev1` 调 `share` 403。

以上任何一条为「代码写了但没跑」，本轮都算未完成。

## 3. 口径与实现约束

1. **取数单点**：所有行级过滤只经 `ScopedJdbc`/`Scope`（`/*scope:*/` 标记 fail-closed）；controller、portal、reports 包内不许出现 `JdbcTemplate` 或手写 `WHERE`。
2. **公式单点**：比例、均值、P90、人天偏差只在 `AnalyticsMetrics`（P90 只走 `FlowMetricFormulas.p90Dwell`，nearest-rank，n=10/11 边界沿用 r9）；前端只渲染 `value/unit/digits`。
3. **白名单单点**：数据集/维度/度量/算子只在 `AnalyticsDatasets`；新接口不复制字段表。
4. **软删**：`status` 为 `VARCHAR(8)`，只能存 `ACTIVE`/`DELETED`（V10 注释里的 `TOMBSTONE` 超长，不采用）；唯一键靠生成列 `active_flag`，删后同名可重建。
5. **共享五档**：`PRIVATE|ROLE|DOMAIN|VENDOR|PUBLIC`，`TENANT` 入参别名→规范化为 `PUBLIC`。可见性谓词：属主恒可见；`ROLE`→查看者角色 id 命中 `scope_ref`；`DOMAIN`→查看者域命中；`VENDOR`→查看者 `vendor_id` 命中；`PUBLIC`→同租户全可见。`scopeRef` 一律数值 id（V10 为 `BIGINT`），前端注释里的「角色码」作废。
6. **共享只共享定义**：任何人 run 共享报表都按**查看者自己的 Scope** 取数并重算，绝不回放属主的缓存；缓存 key 必须混入 scope 与用户可见范围。
7. **配置归属**：`portal_card_config` 的 `user_id=0` 是角色级（管理员配角色显隐），`user_id=<真实 id>` 是个人级（pin）；二者不互相覆盖。
8. **卡片装配**：个人卡优先，模板卡补足，总数上限 `maxCards=4`；超限一律 409（不是静默截断）。
9. **模板**：9 套（`admin/product/business/sub-business/pmo/dev/qa/mgmt/vendor`）+ `fallback`；`mgmt` 为 `READONLY`（只读待办，不派活）。
10. **缓存**：`AnalyticsCache` 现有实现（默认 300s、512 条、key 混 scope）直接复用；写操作按报表 id 精准失效。
11. **导出**：新建 `support/WatermarkSheet`，**不改造**既有质量导出的 `ReportExportService`（r9 技术方案 D-3 已定，避免 q3 基线回归）；复用 `EXPORT_MAX_ROWS=10000`，先数行再写流，超限报错前不写文件头。
12. **权限**：`PORTAL_VIEW`（GET portal/config）、`PORTAL_CONFIG`（PUT config，平台管理员放行）、`REPORT_VIEW`（其余 reports）、`REPORT_SHARE`（POST share）。路由准入仍由 `PermissionRouteTable` 单点决定，controller 不再重复判角色。
13. **审计**：报表增删改/共享/导出写审计（`bizType=report_definition`），与既有 `report.*` 动作命名一致。
14. **错误码**：只允许新增 `PORTAL_UNKNOWN_CARD` 一个码；其余复用 `AnalyticsErrors` 既有 12 码，`cardLimit` 扩展 `detail.cards/maxCards` 不新增码。
15. **库表不动**：V10 已在 `devops_platform` 应用，deliver-service 为 `ddl-auto=validate` 且 `flyway.enabled=false`——不得改 V10，也不得新增列；确实缺列只能由 req-service 追加 V11。

## 4. 范围与非目标

**范围内**：`GET /analytics/datasets`（+`/reports/datasets` 别名）、`GET /portal`、`GET|PUT /portal/config`、`POST /reports/{id}/pin`、`/reports` 全套（POST/GET/PUT/DELETE、copy、run、share、export），以及支撑它们的 portal/reports/support 包、错误码扩展、缓存失效、审计。

**非目标（本轮明确不做）**：
- 前端视图（`/portal`、`/reports` 页面、拖拽、图表切换、导出按钮）；
- 新增门禁脚本 `workbench-verify.sh`/`report-verify.sh` 与既有套件基数同步（新路由/新权限码导致的基数变化只记录，不改断言）；
- 文档对齐（`DELIVERED-MODULES §9`、`DEMO-BY-ROLE`、截图）；
- `/workbench`（M6 质量工作台）与既有任何接口的行为变更；
- 报表「公用模板」「定时推送」「订阅」等 r9 未列的能力；
- 移动端/导出 PDF。

## 5. 反面目标（anti-goals）

技术上都通过、但人会当场否掉的五种结果：

1. **同一个数字在三个出口不一样**：门户卡显示完成率 82%、看板 79%、报表 P90 与看板差一位——每个接口的单测都绿，但用户再也不信任何一块屏。
2. **越权看得见**：vendor1 在「共享给我」里看到 vendor2 的报表，或 run 出来的交叉表里出现 VEN-B 的行。这既是隐私事故，也会让整个行级隔离的承诺作废。
3. **上限靠静默截断**：pin 第 5 张返回 200 但刷新后少一张；或 409 的 body 不给当前 4 张，用户不知道撤哪一张。用户会认为「系统在偷偷改我的配置」。
4. **导出的文件不能用**：xlsx 打开首行是数据不是水印行；CSV 用 Excel 打开是乱码（缺 BOM）；或 10001 行时先落了半个文件再报错。
5. **为快牺牲正确**：缓存 key 只按报表 id 拼，换账号 run 命中别人的行集。这是最坏的一类 bug——它让性能指标好看，同时把隔离撕开。

## 6. 交互流程摘要

1. 用户登录 → 前端按 nav 进入 `/portal` → `GET /portal` 一次拿齐五区与卡片 → 渲染；点某个待办 tile 跳到既有列表页（带筛选参数）。
2. 管理员进入「卡片配置」→ `GET /portal/config?roleCode=…` 拿可配卡 → 勾选显隐 → `PUT /portal/config` → 该角色所有人下次 `GET /portal` 生效。
3. 用户在报表页选中一张报表 → 「设为卡片」→ `POST /reports/{id}/pin`；已满 4 张时前端弹层展示 `detail.cards` 让用户先撤一张。
4. 分析师新建报表：选数据集（`/analytics/datasets` 同源白名单）→ 拖行/列/度量 → 存草稿（`POST /reports`）→ `POST /reports/{id}/run` 出交叉表（MISS/HIT 可见）→ 改定义后重跑。
5. 属主共享：`PUT /reports/{id}/share`（档位+范围 id）→ 被共享者 `?segment=SHARED` 看到 → 点开 run，数据按自己的范围重算。
6. 导出：`GET /reports/{id}/export?format=xlsx|csv` → 浏览器按 `Content-Disposition` 命名下载；服务端在写流之前完成行数校验与权限校验。

## 7. 假设与待澄清

- **Q1（taste）共享档位命名**：需求原话是「PRIVATE/DOMAIN/VENDOR/TENANT 四档」，仓库既有的是 `PRIVATE|ROLE|DOMAIN|PUBLIC`（前端类型已写死这四个）。本轮取**加法**：保留既有四档、新增 `VENDOR`，`TENANT` 作为 `PUBLIC` 的入参别名。代价是「四档」实际变成五档语义。若你要的是**彻底改名**（删掉 ROLE/PUBLIC，全用 VENDOR/TENANT），前端类型与 r9 已验收的共享断言都要跟着改——那就不是本轮能收口的，请明确。默认：加法方案。
- **Q2（taste）数据集路径**：需求写 `/analytics/datasets`，前端 `fetchDatasets()` 写死了 `/reports/datasets`。本轮两条都提供、同一装配、逐字节相同。若你要求只留一条，请在下一轮连同前端一起改。默认：两条并存、`/analytics/datasets` 为规范路径。
- **Q3（taste）pin 的归属**：`portal_card_config` 同时支持角色级（`user_id=0`）与个人级。本轮把 pin 定为**个人级**（「我的卡」随人不随角色），角色级只由管理员配置显隐使用。若你要的是「pin 影响同角色所有人」，语义会从「我的门户」变成「团队门户」。默认：个人级。
- **Q4（taste）vendor 模板**：r9 设计只列了 8 套模板 + fallback，需求点名 vendor 必须覆盖。本轮新增第 9 套 `vendor`（待办=我的交付/待验收，卡片=交付侧指标），不再让厂商落到 `pmo` 或 `fallback`。若你更希望厂商复用 `pmo` 布局只换数据源，可省一套模板但厂商会看到治理类待办。默认：独立 `vendor` 模板。
- **Q5（fact）1 万行上限的证据方式**：种子数据凑不出 10001 行，本轮用单测构造 `EXPORT_MAX_ROWS+1` 直接断言 400 且不产生文件；若你要求必须用真实数据端到端证明，需要先造 1 万+ 需求的性能数据集（属性能轮，AC-18 的范围）。默认：单测证明。
- **（非问题，已由仓库事实定案）** `scopeRef` 是 id 不是 code（V10 为 `BIGINT`，r9 技术方案 ROLE→role_id/DOMAIN→domain_id）；`PORTAL_CONFIG` 的 403 body 沿用平台 `ApiException(403,"FORBIDDEN",…,{functions:[...]})` 形态，而不是把 `PORTAL_CONFIG` 当 `code`。

## 8. 依赖与风险

| # | 依赖/风险 | 影响 | 处置 |
|---|---|---|---|
| R1 | `AnalyticsQueryService` 45KB 草稿（r10）未经端到端验证 | run/portal 口径可能一跑就偏 | 先让它对 `/analytics/dashboard` 的输出与 r9 已验收读数对齐，再在它上面加接口；不重写它 |
| R2 | `definition_json` 与 `ReportDefinition` 的字段名/嵌套不一致 | 往返不等、前端解析失败 | 落库前后各做一次 `jq -S` 深比较（AC-7 证据） |
| R3 | 缓存 key 不含 scope/用户范围 | 跨账号串数据（反面目标 5） | 复用 `AnalyticsRequest#cacheKey` 并加断言：换账号首跑必 MISS |
| R4 | 导出复用质量导出管道会改到 `ReportExportService` | q3 基线回归 | 新建 `support/WatermarkSheet`，质量导出零 diff（AC-1 边界） |
| R5 | 新路由/新权限码使既有 verify 基数变化 | 套件红 | 只记录到 state 待办，本轮不改断言（需求明确） |
| R6 | MySQL 单库 `devops_platform`：`deliver-service` 为 validate | 实体与列不符即启动失败 | 实体逐列对齐 V10；不改 V10、不加列 |

## 9. 优先级

- **P0**：AC-1、AC-2、AC-7、AC-9、AC-10、AC-11（没有这些，报表与共享不可用；隔离是红线）。
- **P1**：AC-3、AC-4、AC-5、AC-6、AC-12（门户与导出是演示主路径，缺一则 DELIVERED-MODULES §9 仍是空话）。
- **P2**：AC-8 的管理员 404 细节、AC-13 的降级单测（可先按契约实现，证据在收口轮补齐）。

## 10. 工程约束（含需求原话，逐字保留）

- 分支：**「在 feat/workbench-reports 分支上补齐」**；起点 **「HEAD = 6cff7dc，工作树干净」**（实际另有 `?? docs/teamflow/20261110-r11-reports-portal-backend/`，即本文档夹，**保持不动、随本轮一起提交**）。
- 每步验证：**「每完成一个任务必须保持这两个绿」**——`mvn -o -DskipTests compile` 与 `web` 的 `npm run build`。
- 并行度：**「开发任务最多 2 个，且串行（先 datasets+portal，再 reports）」**。
- 隔离：**「数据可见性只经 ScopedJdbc/Scope 单点；不许在 controller 里另写过滤」**。
- 口径：**「口径复用 AnalyticsMetrics/MetricFormulas，三处（portal 卡片 / dashboard / report）同一指标必须同源，禁止各写一套」**。
- 不回退：**「不改 /workbench（M6 质量工作台）与既有任何接口行为；不回归 platform-verify/access-verify 等既有套件（若因新路由/新权限码导致基数变化，记录到 state 里留给下一轮，不要顺手改既有断言）」**。
- 权限码：**「PORTAL_VIEW、REPORT_VIEW、REPORT_SHARE、PORTAL_CONFIG 已在 PermissionMatrix（60 码）中就位，直接用」**。
- 本轮不做：**「前端视图、workbench-verify.sh/report-verify.sh、文档对齐、基数同步（全部留到下一轮，写进 state 的待办）」**。
- 提交：本轮结束按 r10 惯例提交一次断点存档 `chore: r11 断点存档——analytics datasets / portal / reports 全套后端`，不 merge、不打 tag；若你要攒到收口轮再提交，直接说。
- **.gitignore 核对（dev 阶段动作）**：本栈为 Maven 多模块 + Vite/TS 前端 + MySQL/Nacos。提交前确认已覆盖 `target/`、`*.class`、`*.jar`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`.nacos/`、`.run/`、`.m2/`、`logs/`、`*.log`、`.env*`、`coverage/`、`*.tsbuildinfo`、`test-results/`、`playwright-report/`、`*.xlsx`、`*.csv`、IDE 文件与 `.DS_Store`；`config/nacos/` 种子必须保持入库。**导出验证产物写 `$TMPDIR` 或 `logs/teamflow/<runId>/`，不要落在仓库根**。

## 11. 里程碑建议

- **M1（串行第 1 步）**：`analytics/datasets`（含别名）+ `portal`（五区、9 模板、卡片装配）+ `portal/config` + `pin`；AC-2～AC-6 的 curl 全绿，`mvn compile` 绿。
- **M2（串行第 2 步）**：`/reports` 全套（CRUD/copy/软删/share/run/export/审计/缓存失效）；AC-7～AC-12 的 curl 全绿，`mvn compile` 绿。
- **M3（收口）**：AC-1、AC-13 的边界与降级证据 + §2.3 九条清单一次性贴出；把「前端视图 / 两个门禁脚本 / 文档对齐 / 基数同步」写进 state 待办，交下一轮。
