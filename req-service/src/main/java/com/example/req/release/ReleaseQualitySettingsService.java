package com.example.req.release;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.RequirementViews.ReleaseDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本级质量设置（TECHNICAL §5.3 / §6.9）。
 *
 * <p>两个旋钮：{@code observationHours}（发布后观察窗，24–168 小时）与 {@code reportValidityDays}
 * （报告有效期，1–30 天）。它们分别被缺陷逃逸判定与门禁 QG-02 读取。范围在服务端硬判——
 * 数据库 CHECK 是最后一道，但不能让越界配置以 500 暴露出去：这里先判，返回 400
 * {@code WAIVER_EXPIRY_INVALID}（PRD 指定的机器码）并带 field/range，前端据此定位输入框。</p>
 *
 * <p>改配置只影响之后发生的事实：已落库缺陷的 {@code is_escape}/{@code escape_window_end}
 * 在 INSERT 时固化，本服务从不回写历史（AC-8.3）。</p>
 */
@Service
public class ReleaseQualitySettingsService {

    public static final int OBSERVATION_MIN_HOURS = 24;
    public static final int OBSERVATION_MAX_HOURS = 168;
    public static final int VALIDITY_MIN_DAYS = 1;
    public static final int VALIDITY_MAX_DAYS = 30;

    private static final String BIZ_TYPE = "RELEASE";

    private final ReleaseService releaseService;
    private final ReleaseRepository releaseRepository;
    private final AuditService auditService;

    public ReleaseQualitySettingsService(ReleaseService releaseService,
                                         ReleaseRepository releaseRepository,
                                         AuditService auditService) {
        this.releaseService = releaseService;
        this.releaseRepository = releaseRepository;
        this.auditService = auditService;
    }

    /** PATCH 载荷；两个字段都可省略（只改一个），显式 null 与省略等价。 */
    public record QualitySettings(Integer observationHours, Integer reportValidityDays) {
    }

    @Transactional
    public ReleaseDetail update(long releaseId, QualitySettings cmd) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = releaseService.requireVisible(releaseId);
        QualitySettings request = cmd == null ? new QualitySettings(null, null) : cmd;

        Map<String, Object> changed = new LinkedHashMap<>();
        if (request.observationHours() != null) {
            requireRange("observationHours", request.observationHours(),
                    OBSERVATION_MIN_HOURS, OBSERVATION_MAX_HOURS, "观察窗");
            release.setObservationHours(request.observationHours());
            changed.put("observationHours", request.observationHours());
        }
        if (request.reportValidityDays() != null) {
            requireRange("reportValidityDays", request.reportValidityDays(),
                    VALIDITY_MIN_DAYS, VALIDITY_MAX_DAYS, "报告有效期");
            release.setReportValidityDays(request.reportValidityDays());
            changed.put("reportValidityDays", request.reportValidityDays());
        }
        if (changed.isEmpty()) {
            return releaseService.detail(releaseId);
        }
        release.setUpdatedBy(scope.userId());
        releaseRepository.save(release);
        auditService.record("RELEASE_QUALITY_SETTINGS_UPDATE", BIZ_TYPE, releaseId, Map.copyOf(changed));
        return releaseService.detail(releaseId);
    }

    private static void requireRange(String field, int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw new ApiException(400, "WAIVER_EXPIRY_INVALID",
                    label + "必须在 " + min + "–" + max + " 之间（收到 " + value + "）",
                    Map.of("field", field, "range", min + "-" + max, "value", value));
        }
    }
}
