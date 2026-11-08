# 集成对接规格（Jenkins / GitLab / Sonar / 制品库）

> 用户明确要求：「CI对接 jekins、gitlab、sonar、制品库」。
> 排期已定：**本轮 r2（M1–M5）先跑完，紧接着开增量 run「M6 集成对接」**，本文件是那一轮的现成输入。
> 与 [PRD.md](PRD.md) 的关系：PRD §4.19 已定通用开放 API + 出站 Webhook（AC-17 通用上报契约）；本文件把「具体系统」的适配细节补齐，不改变已定验收口径。

## 1. 通用原则（四条，所有集成共享）

1. **统一适配层**：新增 `integration_config`（type=JENKINS / GITLAB / SONAR / NEXUS / HARBOR、base_url、凭据密文、webhook_secret、启停、默认环境、超时与重试策略），业务代码只依赖适配接口，不直接散落 HTTP 调用。
2. **凭据只写不读**：凭据 AES-GCM 加密入库（密钥可换），接口与页面永不回显明文，仅显示「已配置/末四位」。
3. **失败降级不污染主流程**：除「准入校验项」外，集成调用失败只记 `integration_sync_log`（请求摘要/结果/耗时/错误码/重试次数）并告警，不阻断需求/审批/发布主流程；重试用指数退避（最多 5 次）。
4. **全部留痕可审计**：触发构建、MR 同步、质量门禁拉取、制品校验都写审计与追溯链（§3.5），可反查。

## 2. Jenkins（构建）

- **触发**：发布单/需求页「触发构建」，参数 = 版本号、制品版本、目标环境、回调地址；记录 `build_record`（job、queueId、buildId、状态、console URL、耗时、触发人）。
- **回写**：轮询 + 回调双通道，状态 QUEUED → RUNNING → SUCCESS / FAILURE / ABORTED；阶段结果（编译/单测/质量门禁）写入 `build_record.stages_json`。
- **展示**：发布单台账展示构建记录与日志链接；失败可直接转缺陷（带 buildId）。
- **准入**：可选校验项「最近一次构建成功」。

## 3. GitLab（代码与 MR）

- **关联**：`mr_url` 升级为结构化（gitlab_project、mr_iid），需求详情展示 MR 标题/状态/审批人/变更行数。
- **同步**：Webhook（MR 事件、pipeline 事件、approval 事件）+ 定时兜底轮询；`mr_sync` 记录 opened/merged/closed、pipeline 状态、approvals。
- **状态驱动**：MR merged 事件可自动把研发需求推进到「已合入」（可按 Release 配置自动/手工确认）。
- **准入**：校验项「关联 MR 均已合入」可选启用。

## 4. SonarQube（质量门禁）

- **绑定**：需求/版本级 `sonar_project_key`；拉取质量门禁结果、覆盖率、漏洞/坏味道/重复率、质量阈快照 → `quality_gate_snapshot`。
- **准入**：新增校验项「Sonar 质量门禁通过」；项目未绑定或 Sonar 不可用时显示「跳过（未配置/不可用）」且**不计 FAIL**，但 UI 明示跳过原因。
- **追溯**：快照进需求追溯链，可在版本详情看门禁趋势。

## 5. 制品库（Nexus / Harbor）

- **默认两类**：Nexus（Maven/npm 包）+ Harbor（容器镜像），适配同一 `artifact_record`。
- **选取**：发布单「制品版本」由手工填 → 改为可从制品库检索选取（保留手填兜底）；记录仓库、坐标、版本、sha256、大小、上传人。
- **准入**：校验项「制品在库且 sha256 与登记一致」；支持制品晋级（RELEASE → PROD 仓库）记录与审计。
- **安全**：只读查询为主，凭据按 §1.2；出站地址走白名单防 SSRF。

## 6. 数据模型增量

`integration_config`、`integration_sync_log`、`build_record`、`mr_sync`、`quality_gate_snapshot`、`artifact_record`；
`requirement` 增 `gitlab_project`、`mr_iid`、`sonar_project_key`；`deploy_order` 增 `build_id`、`artifact_id`、`quality_gate_id`。

## 7. 准入校验扩展（原 5 项 → 8 项，逐项可开关）

| # | 校验项 | 默认 |
|---|---|---|
| 1 | 关联需求全部测试通过 | 必开 |
| 2 | 无未关闭 P0/P1 缺陷 | 必开 |
| 3 | 需求级审批已通过 | 必开 |
| 4 | MR 链接已填 | 开 |
| 5 | 人天（预估/实际）已填 | 开 |
| 6 | 最近一次构建成功 | 开（未配 Jenkins 则跳过） |
| 7 | Sonar 质量门禁通过 | 开（未配则跳过） |
| 8 | 制品在库且校验和一致 | 开（未配则跳过） |

跳过 ≠ 通过：UI 必须标「跳过（未配置）」，验收报告里单独统计「本轮被跳过的校验项数」。

## 8. 验收要点（供 M6 run 的 PRD 直接引用）

1. Jenkins 触发构建 → 状态回写 → 发布单可见 buildId 与日志链接。
2. GitLab MR merged → 需求状态按配置自动推进，且可反查 MR 详情。
3. Sonar 门禁失败 → 发布准入 FAIL 并列出阈值项；修复后通过放行。
4. 制品 sha256 不一致 → 准入 FAIL 并指明期望/实际；一致则放行。
5. 集成不可用（超时/401）→ 主流程不被阻断，`integration_sync_log` 有记录与重试，页面提示降级。
6. 凭据明文不出现在接口响应、页面、日志、导出中。

## 9. 启动 M6 前需要用户提供

- 每个系统的**基址**（Jenkins/Sonar 内网地址、GitLab 实例地址、Nexus/Harbor 地址）；
- 访问方式（Token / 用户名密码 / 应用令牌）与权限范围；
- 制品库类型确认（Nexus、Harbor，或两者都要）；
- 是否允许平台**触发**构建（只读回写 vs 双向）；
- 演示环境是否已有可用的 Jenkins job / Sonar 项目 / 制品可挂（没有则用 mock 服务做验收）。
