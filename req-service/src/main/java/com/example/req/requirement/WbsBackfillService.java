package com.example.req.requirement;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.StateTables;
import com.example.req.sys.SysDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 存量回填的<b>计划与编排</b>：解析租户/批次 → 扫描需求 → 批量判态出计划 → dry-run 直接报告，
 * apply 则逐行委托 {@link WbsBackfillRowWriter}（每行一个 {@code REQUIRES_NEW} 事务）。
 *
 * <p>本类<b>不写库、不开事务</b>：它只读（扫描 + {@link WbsEstimateService#statusesOf} 批量判态），
 * 写入边界全在行写入器里。这不是洁癖——{@code REQUIRES_NEW} 只能在「外层没有可复用的读写事务」
 * 时才是真正的一行一事务，而且一条失败必须只回滚它自己。</p>
 *
 * <p>为什么状态判定必须调 {@link WbsEstimateService#statusesOf} 而不是自己看
 * {@code wf_instance}：r27 已把「列表/详情/门禁/回填」四处的六态收敛进唯一的 {@code decide} 表
 * （含 0.20 的 STALE 阈值与 WITHDRAWN 回落）。回填若自判，页面说「已通过」而回填说「可补」，
 * 就会造出第二套真相——这正是 r27 修掉的缺陷类型。</p>
 */
@Service
public class WbsBackfillService {

    private static final Logger log = LoggerFactory.getLogger(WbsBackfillService.class);

    /** 批次号形如 {@code wbsbf-20261118T1030-a1b2c3}：可排序、可 grep、可区分同一分钟的两次运行。 */
    private static final DateTimeFormatter BATCH_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm").withZone(ZoneOffset.UTC);

    private static final String TENANT_SQL = "SELECT id FROM sys_tenant WHERE code = ?";

    /**
     * 扫描只取报告需要的列；排序把 RD 放在 PDR 之前——RD 获批会改写父 PDR 的汇总人天，
     * 反过来先补 PDR 就会用旧汇总值建单并派生 STALE。
     */
    private static final String SCAN_SQL = """
            SELECT id, code, type, status, estimate_days, domain_id
              FROM requirement
             WHERE tenant_id = ?
             ORDER BY CASE type WHEN 'RD' THEN 0 WHEN 'PDR' THEN 1 ELSE 2 END, id
            """;

    private final RequirementService requirements;
    private final WbsEstimateService estimates;
    private final WbsBackfillRowWriter writer;
    private final SysDb db;

    public WbsBackfillService(RequirementService requirements, WbsEstimateService estimates,
                              WbsBackfillRowWriter writer, SysDb db) {
        this.requirements = requirements;
        this.estimates = estimates;
        this.writer = writer;
        this.db = db;
    }

    /**
     * 跑一批回填，返回完整报告（含 FAILED 行）。{@code dryRun=true} 时零写入。
     *
     * <p>汇总口径：dry-run 的 {@code planned} 是判为「可补」的行数且 {@code created=0}；
     * apply 的 {@code planned} 只计真正尝试过的行（{@code planned == created + failed}），
     * 计划阶段判为可补、但执行时发现已被并发补上的行落进 {@code skipped}。
     * 这样「planned 恒等于 created+failed」在任何情况下都成立，脚本可以只凭报告判红。</p>
     */
    public WbsBackfillViews.Report run(WbsBackfillViews.Command cmd) {
        String tenantCode = cmd == null || cmd.tenant() == null ? "" : cmd.tenant().trim();
        if (tenantCode.isEmpty()) {
            throw new ApiException(400, "USAGE", "tenant 必填：?tenant=<租户编码>");
        }
        String batchId = cmd.batchId() == null || cmd.batchId().isBlank()
                ? newBatchId(Clock.systemUTC()) : cmd.batchId().trim();
        long tenantId = tenantIdOf(tenantCode);

        DataScope previousScope = TenantContext.scopeOrNull();
        TenantContext.Principal previousPrincipal = TenantContext.principal();
        TenantContext.set(DataScope.system(tenantId));
        try {
            List<WbsBackfillViews.Candidate> candidates = scan(tenantId);
            if (candidates.isEmpty()) {
                return report(batchId, tenantCode, cmd.dryRun(), List.of());
            }
            Map<Long, String> statuses = estimates.statusesOf(candidates.stream()
                    // 计划期快照没有承接厂商（扫描 SQL 不取该列）：这里传 null，评估态只按
                    // 单据自身派生；执行阶段由 WbsBackfillRowWriter 在事务内用实体重读，含厂商。
                    .map(c -> new WbsEstimateService.GateSubject(
                            c.requirementId(), c.type(), c.estimateDays(), null))
                    .toList());

            List<WbsBackfillViews.Row> rows = new ArrayList<>(candidates.size());
            int created = 0;
            int failed = 0;
            for (WbsBackfillViews.Candidate scanned : candidates) {
                String estimateStatus = statuses.getOrDefault(scanned.requirementId(),
                        WbsEstimateService.STATUS_NONE);
                WbsBackfillViews.Candidate candidate = withPreviousStatus(scanned, estimateStatus);
                Decision decision = classify(candidate, estimateStatus);
                if (!WbsBackfillViews.RESULT_PLANNED.equals(decision.result())) {
                    rows.add(skipped(candidate, decision.reason()));
                    continue;
                }
                if (cmd.dryRun()) {
                    rows.add(planned(candidate));
                    continue;
                }
                WbsBackfillViews.Row row = applyRow(candidate, tenantId, batchId);
                if (WbsBackfillViews.RESULT_CREATED.equals(row.result())) {
                    created++;
                } else if (WbsBackfillViews.RESULT_FAILED.equals(row.result())) {
                    failed++;
                }
                rows.add(row);
            }
            log.info("wbs-backfill {} tenant={} mode={} scanned={} created={} failed={}", batchId,
                    tenantCode, cmd.dryRun() ? WbsBackfillViews.MODE_DRY_RUN
                            : WbsBackfillViews.MODE_APPLY, rows.size(), created, failed);
            return report(batchId, tenantCode, cmd.dryRun(), rows, created, failed);
        } finally {
            TenantContext.clear();
            if (previousScope != null) {
                TenantContext.set(previousScope);
            }
            if (previousPrincipal != null) {
                TenantContext.setPrincipal(previousPrincipal);
            }
        }
    }

    /** 批次号：同一分钟内用 6 位随机后缀区分，脚本靠 {@code --batch-id} 复现同一批。 */
    static String newBatchId(Clock clock) {
        String suffix = String.format("%06x", new SecureRandom().nextInt(0x1000000));
        return "wbsbf-" + BATCH_STAMP.format(clock.instant()) + "-" + suffix;
    }

    /** 租户编码 → id；不存在按 400 拒（调用方传错编码不该静默跑一张空计划）。 */
    private long tenantIdOf(String tenantCode) {
        return db.row(TENANT_SQL, tenantCode)
                .map(row -> SysDb.asLong(row[0]))
                .orElseThrow(() -> new ApiException(400, "TENANT_UNKNOWN",
                        "租户不存在: " + tenantCode));
    }

    private List<WbsBackfillViews.Candidate> scan(long tenantId) {
        List<WbsBackfillViews.Candidate> candidates = new ArrayList<>();
        for (Object[] row : db.rows(SCAN_SQL, tenantId)) {
            candidates.add(new WbsBackfillViews.Candidate(
                    SysDb.asLong(row[0]), SysDb.asString(row[1]), SysDb.asString(row[2]),
                    SysDb.asString(row[3]), null, decimal(row[4]), SysDb.asNullableLong(row[5])));
        }
        return candidates;
    }

    /**
     * 把计划期六态写回候选：{@code planned/skipped/failed} 行的 {@code previousStatus} 与写入器产出的
     * {@code created} 行同口径（都是「回填前六态」），避免同名字段在 dry-run 与 apply 报告里两种读法。
     *
     * <p>只是报告字段：执行阶段仍由 {@link WbsBackfillRowWriter} 在事务内 {@code statusOf} 重读，
     * 计划期快照不参与任何判定。</p>
     */
    private static WbsBackfillViews.Candidate withPreviousStatus(WbsBackfillViews.Candidate c,
                                                                 String estimateStatus) {
        return new WbsBackfillViews.Candidate(c.requirementId(), c.code(), c.type(), c.status(),
                estimateStatus, c.estimateDays(), c.domainId());
    }

    /**
     * 计划期分类：只有 {@code STALE/NONE/REJECTED/SUPERSEDED} 才补，其余都带原因跳过。
     *
     * <p>与 {@link WbsEstimateService#statusOf} 同源，不重写阈值或回落规则；
     * 人天是否合法留到写入器（{@link RequirementRules#checkHalfDay}）判，
     * 这样 dry-run 里非法人天显示为 PLANNED、apply 才落 FAILED——与「先看计划再执行」一致。</p>
     */
    private Decision classify(WbsBackfillViews.Candidate candidate, String estimateStatus) {
        if (candidate.type() == null || RequirementRules.TYPE_BUS.equals(candidate.type())) {
            return new Decision(WbsBackfillViews.RESULT_SKIPPED, WbsBackfillViews.REASON_TYPE_BUS);
        }
        if (StateTables.isTerminal(candidate.type(), candidate.status())) {
            return new Decision(WbsBackfillViews.RESULT_SKIPPED, WbsBackfillViews.REASON_TERMINAL);
        }
        if (candidate.estimateDays() == null || candidate.estimateDays().signum() <= 0) {
            return new Decision(WbsBackfillViews.RESULT_SKIPPED,
                    WbsBackfillViews.REASON_NO_ESTIMATE_DAYS);
        }
        if (WbsEstimateService.STATUS_PENDING.equals(estimateStatus)) {
            return new Decision(WbsBackfillViews.RESULT_SKIPPED,
                    WbsBackfillViews.REASON_HAS_ACTIVE_ESTIMATE);
        }
        if (WbsEstimateService.STATUS_APPROVED.equals(estimateStatus)) {
            return new Decision(WbsBackfillViews.RESULT_SKIPPED,
                    WbsBackfillViews.REASON_HAS_APPROVED_ESTIMATE);
        }
        return new Decision(WbsBackfillViews.RESULT_PLANNED, null);
    }

    /**
     * 执行一行：行写入器内部已把整行放在一个 {@code REQUIRES_NEW} 事务里，
     * 抛 {@link WbsBackfillRowWriter.RowFailure} 时那行事务已回滚（不留 PENDING 孤儿实例），
     * 这里只把失败翻译成 FAILED 行，整批继续。
     */
    private WbsBackfillViews.Row applyRow(WbsBackfillViews.Candidate candidate, long tenantId,
                                          String batchId) {
        try {
            return writer.apply(candidate, tenantId, batchId);
        } catch (WbsBackfillRowWriter.RowFailure failure) {
            log.warn("wbs-backfill: {} 未能补齐 reason={} instanceId={}", candidate.code(),
                    failure.reason(), failure.instanceId());
            return failed(candidate, failure.reason(), failure.instanceId());
        } catch (RuntimeException ex) {
            log.warn("wbs-backfill: {} 意外失败", candidate.code(), ex);
            return failed(candidate, WbsBackfillViews.REASON_ROW_FAILED, null);
        }
    }

    private WbsBackfillViews.Report report(String batchId, String tenant, boolean dryRun,
                                           List<WbsBackfillViews.Row> rows) {
        return report(batchId, tenant, dryRun, rows, 0, 0);
    }

    private WbsBackfillViews.Report report(String batchId, String tenant, boolean dryRun,
                                           List<WbsBackfillViews.Row> rows, int created,
                                           int failed) {
        int planned = 0;
        int skipped = 0;
        if (dryRun) {
            for (WbsBackfillViews.Row row : rows) {
                if (WbsBackfillViews.RESULT_PLANNED.equals(row.result())) {
                    planned++;
                } else if (WbsBackfillViews.RESULT_SKIPPED.equals(row.result())) {
                    skipped++;
                }
            }
        } else {
            planned = created + failed;
            skipped = rows.size() - planned;
        }
        return new WbsBackfillViews.Report(batchId, tenant,
                dryRun ? WbsBackfillViews.MODE_DRY_RUN : WbsBackfillViews.MODE_APPLY,
                Instant.now().toString(),
                new WbsBackfillViews.Summary(rows.size(), planned, created, skipped, failed),
                List.copyOf(rows));
    }

    private static WbsBackfillViews.Row planned(WbsBackfillViews.Candidate candidate) {
        return new WbsBackfillViews.Row(candidate.code(), candidate.requirementId(),
                candidate.type(), candidate.status(), candidate.estimateDays(),
                WbsBackfillViews.RESULT_PLANNED, null, null, candidate.previousStatus(), null, null,
                List.of());
    }

    private static WbsBackfillViews.Row skipped(WbsBackfillViews.Candidate candidate,
                                                String reason) {
        return new WbsBackfillViews.Row(candidate.code(), candidate.requirementId(),
                candidate.type(), candidate.status(), candidate.estimateDays(),
                WbsBackfillViews.RESULT_SKIPPED, reason, null, candidate.previousStatus(), null,
                null, List.of());
    }

    private static WbsBackfillViews.Row failed(WbsBackfillViews.Candidate candidate, String reason,
                                               Long instanceId) {
        return new WbsBackfillViews.Row(candidate.code(), candidate.requirementId(),
                candidate.type(), candidate.status(), candidate.estimateDays(),
                WbsBackfillViews.RESULT_FAILED, reason, instanceId, candidate.previousStatus(), null,
                null, List.of());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString());
    }

    /** 计划期结论：{@code result} 是 PLANNED/SKIPPED，{@code reason} 仅在跳过时有值。 */
    private record Decision(String result, String reason) {
    }
}
