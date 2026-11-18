# RUNBOOK · 存量 PDR/RD 工时评估单回填（r29）

适用：r26 之后无法 `schedule`／`start_dev`、且没有工时评估单的存量 PDR/RD。契约见同目录 `TECH-CHANGE.md`。
工具：`scripts/backfill-wbs-estimate.sh`（薄封装，业务全在 `POST /internal/wbs-estimate-backfill`）。**默认 dry-run，`--apply` 才写库。**

## 0. 前置（缺一不可）

1. **备份**（回填只增单据，但这四张表先留底，便于事后核对）：
   ```bash
   mysqldump -h127.0.0.1 -uroot devops_platform requirement wf_instance wf_task wf_history \
     > backup-wbs-$(date +%Y%m%d%H%M).sql
   ```
2. **打开开关并重启 req-service**（启动期开关，默认 `false`；为假时端点直接 404）：
   ```bash
   export PLATFORM_OPS_BACKFILL_ENABLED=true     # 与 PLATFORM_INTERNAL_TOKEN 一同注入
   ```
   生产窗口期外请保持 `false`；用完即改回并重启。
3. **探活**：`curl -fsS http://127.0.0.1:8082/internal/health | jq '{status,db,flyway}'` → `UP/up/非 null`。
4. **记录批次号**：`batchId=wbsbf-<yyyymmddTHHMM>-<6位>`（不传则脚本自动生成并回显，抄进变更单）。
5. 确认目标租户：`--tenant t1`（默认 `platform.tenant`）。

## 1. dry-run（必做，先读报告）

```bash
bash scripts/backfill-wbs-estimate.sh --tenant t1 --report logs/teamflow/<runId>/wbs-backfill-dryrun.json
```
报告读法（`rows[]` 一行一条需求，`summary` 是汇总）：

| 字段 | 怎么读 |
|---|---|
| `mode` | 必须是 `dry-run`；`summary.created` 必须为 `0` |
| `planned` | **本次 apply 会创建多少张单**；为 0 说明存量已被补齐或全被拦，不要 apply |
| `result=PLANNED` | dry-run 下表示「本次会建单」，看 `estimateDays`（apply 时将写入快照的人天）；apply 成功后同一张单的行才变 `result=CREATED` 并带 `instanceId` |
| `result=SKIPPED` + `reason` | `TYPE_BUS`（设计如此）／`TERMINAL`／`NO_ESTIMATE_DAYS`（人天为空或 ≤0，**需人工补人天**后再跑）／`HAS_ACTIVE_ESTIMATE`（有人在批，等它走完）／`HAS_APPROVED_ESTIMATE`（已合规，幂等跳过） |
| `result=FAILED` + `reason` | `INVALID_ESTIMATE_DAYS`（非 0.5 倍数，改人天）／`NO_APPROVER`（该需求域负责人与待办都解析不出）／`NO_WORKFLOW_DEFINITION`（租户没种 WBS_ESTIMATE 流程）／`APPROVED_NOT_DERIVED`（快照与实体人天不一致，先查是否有人并发改人天） |
| `previousStatus` | 回填前工时评估六态（`NONE`／`STALE`／`REJECTED`／`SUPERSEDED`…）；dry-run 与 apply 行都有值。`STALE` 表示曾有人天漂移，本单会 supersede 旧单 |
| `approverSource`／`flags` | **只有 apply 行才有值**（审批人在写入事务内才解析，dry-run 恒 `null`／`[]`）：`approverSource ∈ DOMAIN_OWNER｜PLATFORM_ADMIN｜PENDING_TASK`；`flags` 含 `FALLBACK_APPROVER` 即审批人不是域负责人，需在变更单注明并事后补域负责人配置 |

**准入判据**：`planned > 0`、`failed == 0`（或失败项已逐条解释并接受）、`summary.scanned` 与你的存量口径一致；`wf_instance`／`wf_history` 计数在 dry-run 前后**未变**（`TECH-CHANGE.md` §5.1 S1 的同一判据，可自行复算一次）。

## 2. apply（窗口期）

- **窗口**：业务低峰（建议 20:00–次日 06:00）。回填逐条独立事务、按 id 串行，不整批长事务；不会锁表级资源，但会**同步改写 `requirement.estimate_days` 并刷新父 PDR 汇总**（既有规则），故仍需避开批量排期操作。
- **单实例**：同一时刻只允许一个 apply（脚本不并发、不重试）。多副本部署时只在其中一台的直连端口上执行。
- **先小批**：存量多时按 `--tenant` 分租户跑，先确认单租户报告与预期一致再逐租户铺开（脚本只有 `--tenant`／`--batch-id`／`--report`／`--apply` 四个参数）。

```bash
bash scripts/backfill-wbs-estimate.sh --apply --tenant t1 --batch-id wbsbf-20261118T2000-a1b2c3 \
  --report logs/teamflow/<runId>/wbs-backfill-apply.json
echo "退出码=$?"   # 0=无 FAILED；1=有 FAILED；2=用法错；3=连不上服务
```

**失败重试**：单条 FAILED 不影响其余行。修因（补人天／修正 0.5 倍数／补域负责人／补流程种子）后**用同一个 `batchId` 重跑**（幂等：已 APPROVED 的会被 `HAS_APPROVED_ESTIMATE` 跳过，只补失败项）。`NO_APPROVER` 属数据缺口，脚本无法自愈。

## 3. 收尾与核对

1. 抽样验证门禁放行：对一条已回填 PDR 调 `POST /api/req/requirements/{id}/actions`（`action=schedule`）应不再返回 409 `ESTIMATE_NOT_APPROVED`；RD 的 `start_dev` 同理。
2. 按批次号反查（**`wf_history` 两处**：SUBMIT 的 `detail_json` 与 APPROVE 的 `comment`）：
   ```sql
   SELECT h.instance_id, h.task_id, h.action, h.comment, h.detail_json, h.created_at
     FROM wf_history h
    WHERE h.detail_json LIKE '%存量回填（wbsbf-20261118T2000-a1b2c3%'
       OR h.comment     LIKE '%存量回填（wbsbf-20261118T2000-a1b2c3%'
    ORDER BY h.instance_id, h.id;
   ```
   预期每个回填实例 **2 行**（`SUBMIT`（`detail_json` 内含 `batchId`）＋`APPROVE`（`comment` 为「存量回填（batchId）」））。

   > **订正（r29 实测）**：`audit_log` 里只有 `ESTIMATE_APPLIED`／`ESTIMATE_APPROVED`，其 `detail` 仅含 `instanceId`／`estimateDays`（`RequirementService.applyApprovedEstimate`），**不含 `batchId`** —— 按批次号审计只能查 `wf_history`。若后续要让 `audit_log` 也可反查，需先给 web 事件字典补 `ESTIMATE_BACKFILLED` 文案再让 `WbsBackfillRowWriter` 落审计，否则页面会显示英文裸码，本轮未做。
3. 关闭开关并重启 req-service（`PLATFORM_OPS_BACKFILL_ENABLED=false`）；把 dry-run/apply 报告与 `batchId` 附到变更单。

## 4. 回滚口径

回填**只增单据**，不改状态机、不迁 schema、不删数据（唯一副作用是既有的 `estimate_days` 同步与父 PDR 汇总刷新），因此**不做数据回滚**，也没有反向脚本。确需撤销：

1. 用 §3 步骤 2 的 SQL 按 `batchId` 枚举受影响需求与单据；
2. 在页面重新提交这些需求的评估单（UI 既有路径会 supersede 旧单，人天偏差超 ±20% 自然转 `STALE`，门禁随之恢复拦截）；
3. 被改写的 PDR `estimate_days` 按 §0.1 备份核对，需要时人工回填。

禁止为了「回滚」直接 `UPDATE wf_instance/wf_task` 或 `DELETE` 单据 —— 会留下没有 SUBMIT/APPROVE 流水的孤儿单，审计与审批中心随即不一致。
