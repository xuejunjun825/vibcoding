package com.example.req.requirement.change;

import com.example.req.deploy.AdmissionEvaluator;
import com.example.req.deploy.DeployViews;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import com.example.req.release.ReleaseRequirementEntity;
import com.example.req.release.ReleaseRequirementRepository;
import com.example.req.release.ReleaseService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.WbsEstimateService;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更影响快照：固定 8 个顶层键，创建时冻结、之后不重算。
 *
 * <p>创建与 preview 共用同一个 {@link #assess}，第④步看到的影响与落库的 impact 必须逐字一致；
 * 若 preview 自己拼一份，用户就会「预览一个样、审批后另一个样」。</p>
 *
 * <p>DB 异常不降级：影响评估是审批人的决策依据，查不到就报错，不能把空数据包装成
 * 「无影响」（那比 500 更危险）。</p>
 */
@Component
public class RequirementChangeImpactAssessor {

    private static final String SQL_ADMISSION = """
            SELECT d.code AS code, d.admission_result_json AS admission_result_json
              FROM deploy_order d
              JOIN deploy_order_requirement dr
                ON dr.deploy_order_id = d.id AND dr.tenant_id = d.tenant_id
             WHERE /*scope:d*/ AND dr.requirement_id = :requirementId
               AND d.admission_result_json IS NOT NULL
             ORDER BY d.id DESC
            """;

    private final RequirementRepository requirements;
    private final ReleaseRepository releases;
    private final ReleaseRequirementRepository memberships;
    private final ScopedJdbc scoped;
    private final AdmissionEvaluator admissions;
    private final WbsEstimateService wbs;

    public RequirementChangeImpactAssessor(RequirementRepository requirements,
                                           ReleaseRepository releases,
                                           ReleaseRequirementRepository memberships,
                                           ScopedJdbc scoped,
                                           AdmissionEvaluator admissions,
                                           WbsEstimateService wbs) {
        this.requirements = requirements;
        this.releases = releases;
        this.memberships = memberships;
        this.scoped = scoped;
        this.admissions = admissions;
        this.wbs = wbs;
    }

    public Map<String, Object> assess(RequirementEntity entity, String changeType,
                                      Map<String, Object> after) {
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> before = RequirementChangeFields.snapshot(entity);
        List<String> changed = RequirementChangeFields.changedFields(before, after);

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("fields", fieldDeltas(before, after, changed));
        impact.put("children", Map.of("count", requirements.countByTenantIdAndParentId(
                tenantId, entity.getId())));
        impact.put("release", releaseOf(tenantId, entity.getReleaseId()));
        impact.put("targetRelease", RequirementChangeFields.TYPE_RELEASE.equals(changeType)
                ? releaseOf(tenantId, asLong(after.get(RequirementChangeFields.F_RELEASE_ID)))
                : null);
        impact.put("days", days(entity, after));
        impact.put("admission", Map.of("passedOrderCodes", passedOrderCodes(entity.getId())));
        impact.put("postLock", postLock(tenantId, entity.getId()));
        impact.put("frozenAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        return impact;
    }

    /**
     * 列表行的轻量摘要：只放决策需要的一层标量，避免列表把整份 impact 拖出来。
     * 键固定 5 个（children/daysDelta/becomesStale/releaseLocked/affectedOrderCount）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> summary(Map<String, Object> impact) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> children = (Map<String, Object>) impact.get("children");
        Map<String, Object> days = (Map<String, Object>) impact.get("days");
        Map<String, Object> postLock = (Map<String, Object>) impact.get("postLock");
        Map<String, Object> admission = (Map<String, Object>) impact.get("admission");
        summary.put("children", children == null ? 0 : children.get("count"));
        summary.put("daysDelta", days == null ? null : days.get("delta"));
        summary.put("becomesStale", days != null && Boolean.TRUE.equals(days.get("becomesStale")));
        summary.put("releaseLocked", postLock != null && Boolean.TRUE.equals(postLock.get("value")));
        List<Object> codes = admission == null ? null : (List<Object>) admission.get("passedOrderCodes");
        summary.put("affectedOrderCount", codes == null ? 0 : codes.size());
        return summary;
    }

    // ------------------------------------------------------------------ 分段

    private static List<Map<String, Object>> fieldDeltas(Map<String, Object> before,
                                                         Map<String, Object> after,
                                                         List<String> changed) {
        List<Map<String, Object>> fields = new ArrayList<>(changed.size());
        for (String field : changed) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("field", field);
            delta.put("before", before.get(field));
            delta.put("after", after.get(field));
            fields.add(delta);
        }
        return fields;
    }

    private Map<String, Object> days(RequirementEntity entity, Map<String, Object> after) {
        BigDecimal beforeDays = entity.getEstimateDays();
        BigDecimal afterDays = asDecimal(after.get(RequirementChangeFields.F_ESTIMATE_DAYS));
        BigDecimal approvedDays = wbs.approvedDaysOf(entity.getId());

        BigDecimal delta = null;
        if (beforeDays != null && afterDays != null) {
            delta = afterDays.subtract(beforeDays).setScale(1, RoundingMode.HALF_UP);
        }
        BigDecimal deltaRatio = null;
        if (approvedDays != null && approvedDays.signum() > 0 && afterDays != null) {
            deltaRatio = afterDays.subtract(approvedDays).abs()
                    .divide(approvedDays, 6, RoundingMode.HALF_UP);
        }

        Map<String, Object> days = new LinkedHashMap<>();
        days.put("before", beforeDays);
        days.put("after", afterDays);
        days.put("delta", delta);
        days.put("estimateStatus", wbs.statusOf(entity));
        days.put("approvedDays", approvedDays);
        days.put("deltaRatio", deltaRatio);
        days.put("becomesStale", WbsEstimateService.wouldBeStale(approvedDays, afterDays));
        return days;
    }

    private Map<String, Object> releaseOf(long tenantId, Long releaseId) {
        if (releaseId == null) {
            return null;
        }
        return releases.findByIdAndTenantId(releaseId, tenantId)
                .map(RequirementChangeImpactAssessor::releaseMap)
                .orElse(null);
    }

    private static Map<String, Object> releaseMap(ReleaseEntity release) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", release.getId());
        map.put("code", release.getCode());
        map.put("name", release.getName());
        map.put("status", release.getStatus());
        map.put("locked", frozen(release.getStatus()));
        return map;
    }

    private List<String> passedOrderCodes(long requirementId) {
        List<Map<String, Object>> rows = scoped.list(SQL_ADMISSION,
                Map.of("requirementId", requirementId));
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object raw = row.get("admission_result_json");
            if (raw == null) {
                continue;
            }
            DeployViews.AdmissionResult result = admissions.parse(jsonOf(raw));
            if (admissions.allPassed(result)) {
                codes.add(String.valueOf(row.get("code")));
            }
        }
        return codes;
    }

    private Map<String, Object> postLock(long tenantId, long requirementId) {
        List<ReleaseRequirementEntity> rows =
                memberships.findByTenantIdAndRequirementId(tenantId, requirementId);
        Map<String, Object> statuses = new LinkedHashMap<>();
        List<Long> lockedReleaseIds = new ArrayList<>();
        for (ReleaseRequirementEntity row : rows) {
            ReleaseEntity release = releases.findByIdAndTenantId(row.getReleaseId(), tenantId)
                    .orElse(null);
            if (release == null) {
                continue;
            }
            statuses.put(String.valueOf(release.getId()), release.getStatus());
            if (frozen(release.getStatus())) {
                lockedReleaseIds.add(release.getId());
            }
        }
        Map<String, Object> postLock = new LinkedHashMap<>();
        postLock.put("value", !lockedReleaseIds.isEmpty());
        postLock.put("statuses", statuses);
        postLock.put("lockedReleaseIds", lockedReleaseIds);
        return postLock;
    }

    private static boolean frozen(String status) {
        return ReleaseService.STATUS_LOCKED.equals(status)
                || ReleaseService.STATUS_PUBLISHED.equals(status);
    }

    private static String jsonOf(Object raw) {
        return raw instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(raw);
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.parseLong(text);
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value).trim());
    }
}
