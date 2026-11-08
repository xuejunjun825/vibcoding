# AGENTS.md — 团队协作守则与文档索引（Spring Cloud 开发环境骨架）

> 任何新加入本产品的 Agent（团队成员）必须先通读本文件，再按 §2 文档索引读取相关文档与任务卡片，不要自行全量探索项目。
> 维护者：团队本身 + TeamFlow 研发流水线（TeamFlow 仅维护文末 `<!-- teamflow -->` 托管区，其余内容为团队资产，不得改写）。

## 1. 产品是什么

- 产品：**Spring Cloud + Nacos 开发环境骨架**——一条命令起 Nacos + 网关 + 示例服务，一条 URL 打通「网关 → Nacos 注册 → 服务 → Nacos 配置」，一条命令体检且有负向用例。
- 产品根：本工作区根目录（`/Users/xuejunjun/Documents/deepseek-harness/project-1/`，工作区即产品根；不另建 `products/<product>/` 子目录）。
- 当前版本：`0.1.0-SNAPSHOT`（20261108-r1 交付）。
- 技术栈：JDK 25.0.2 · Spring Boot 4.0.9 · Spring Cloud 2025.1.4 · Spring Cloud Alibaba 2025.1.0.0 · Nacos 3.3.0 standalone · Maven 多模块。

## 2. 文档索引（按职责）

| 职责 | 位置 | 说明 |
|---|---|---|
| 产品入口 | [README.md](README.md) | 从零到绿灯（≤8 条命令）、版本矩阵、常用命令、排障 |
| 任务产物 | `docs/teamflow/<yyyyMMdd-rN-slug>/` | 每个需求一个自包含任务夹：PRD/设计/技术方案/QA 报告/验收报告（按日期倒序即迭代史） |
| 架构总览 | [docs/teamflow/architecture/ARCHITECTURE.md](docs/teamflow/architecture/ARCHITECTURE.md) | 工程方案、模块契约、取舍、构建/测试策略（产品级长期文档） |
| 产品记忆 | [docs/teamflow/memory.md](docs/teamflow/memory.md) | 团队约定/技术栈/已知待办（低频更新，只改同主题行） |
| 运行日志 | `logs/teamflow/<runId>/` → `$DSH_HOME/teamflow/<workspace>/logs/<runId>/` | 只放**要留存**的东西（一次性检查脚本、不可重跑的命令载荷、结论笔记）——**不制造命令输出 dump**（长输出由宿主截尾并把全文 spill 到它报告的临时路径，需要细节时读那个路径）；run 期间在项目内暂存，run 结束由 host 归档并从项目删除 |

## 3. 团队角色与标准流程

**角色**：产品经理 / UI/UX 设计师 / 架构师 / 高级全栈工程师 / QA 测试工程师。

**标准流程**：需求 → PRD →（UI 改造时）UI/UX 设计 →（新项目时）架构规划 → 技术方案 → 并行开发 → QA 测试 → 产品验收。

**产出物落盘约定**：每个需求的所有产物写入该需求的任务夹 `docs/teamflow/<任务夹>/`（夹名与路径由 TeamFlow 指定）；ARCHITECTURE.md 与 memory.md 是产品级长期文档。**除实际产品代码改造与 AGENTS.md 托管区外，TeamFlow 只在 `docs/teamflow/` 与 `logs/teamflow/` 下写文件，绝不写入宿主 `docs/<职责>/` 或项目根**。

**完成度自查**：每个环节交付前对照职责清单自查，未完成不得流转；架构师对新项目必须实际初始化脚手架文件与 AGENTS.md 草稿。

## 4. 工程约定

- **代码形态**：Maven 多模块（父 POM + `api-gateway` + `demo-service`），Java 25；不引入 Gradle、Docker、Kotlin。
- **模块边界**：
  - `api-gateway`：唯一入口，`/api/**` StripPrefix(1) → `lb://demo-service`；路由用 Java `RouteLocator` 声明，不用 YAML 属性。
  - `demo-service`：Nacos discovery + config；`GET /demo/hello` 返回 `{message, instancePort, configSource}`；`configSource` 只能来自 Nacos 种子配置。
  - `scripts/`：所有运维动作的**唯一**入口；Makefile 只做薄封装，不内联复杂逻辑。
  - `config/nacos/`：配置中心种子数据，必须入库；`dev-up` 通过 OpenAPI 幂等发布。
- **端口**：8080 网关 / 8081 示例服务 / 8848 Nacos HTTP / 8849 控制台 / 9848-9849 gRPC；均可用环境变量覆盖。
- **验证命令**：`make dev-up` → `make verify` → `make test-negative` → `make dev-down`；提交前这四步必须全绿。
- **禁止**：修改版本矩阵而不改 README/ARCHITECTURE；静默更换 Boot/Cloud/SCA/Nacos 版本；把 `.nacos/`、`target/`、日志提交入库；引入 Python 交付物（20261108-r1 已确认移除）。
- **失败优先**：任何脚本失败必须给出“哪一项 + 怎么修”，禁止用 `sleep` 掩盖未就绪。

## 5. 变更记录

- 2026-11-08：创建本文件（TeamFlow 脚手架）；确立上述技术栈与工程约定。

<!-- teamflow:begin -->
## TeamFlow 托管区（本块由 TeamFlow 自动维护，团队请勿手改）

- 团队文档根：`docs/teamflow/`（每需求一个任务夹 + `memory.md` + `architecture/`）
- 运行日志：`logs/teamflow/<runId>/`（run 期间的项目内暂存；run 结束由 host 归档到 `$DSH_HOME/teamflow/<workspace>/logs/<runId>/`，项目内不留存）
- 需求/任务/缺陷 backlog：持久化镜像位于 `$DSH_HOME/teamflow/<workspace>/`（按工作区/项目隔离）
- 规则：TeamFlow 只维护本块、`docs/teamflow/` 与 `logs/teamflow/`；本文件其余内容为团队资产。
<!-- teamflow:end -->
