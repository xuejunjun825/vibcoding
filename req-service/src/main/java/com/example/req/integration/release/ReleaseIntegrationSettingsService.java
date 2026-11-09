package com.example.req.integration.release;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.integration.sonar.SonarProjectKeys;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseService;

/**
 * 版本级集成设置的唯一读写入口（TECHNICAL §5.9）。
 *
 * <p>继承规则「需求没填 Sonar key 就用版本的」只在这里实现一次：需求详情卡的展示、
 * 准入第 ⑧ 项的取键都调 {@link #effectiveSonarKey(long, String)}，不允许各自再写一遍
 * {@code requirement.getSonarProjectKey() != null ? ... : release...}。</p>
 *
 * <p>{@code mrAutoAdvance} 默认关闭（V8 列默认 0）：它决定 GitLab 合并事件是「只记录」
 * 还是「推进需求状态」，属于业务红线，必须显式开启并留审计。</p>
 */
@Service
public class ReleaseIntegrationSettingsService {

    public static final String ACTION_UPDATED = "INTEGRATION_SETTINGS_UPDATED";

    private static final String BIZ_TYPE = "RELEASE";

    private final ReleaseService releaseService;
    private final AuditService auditService;

    public ReleaseIntegrationSettingsService(ReleaseService releaseService, AuditService auditService) {
        this.releaseService = releaseService;
        this.auditService = auditService;
    }

    /** 读：可见版本即可读（不可见 → 404，和版本详情同口径）。 */
    @Transactional(readOnly = true)
    public ReleaseIntegrationViews.Settings get(long releaseId) {
        ReleaseEntity release = releaseService.requireVisible(releaseId);
        return new ReleaseIntegrationViews.Settings(release.getId(),
                autoAdvance(release), release.getSonarProjectKey());
    }

    /**
     * 写：只更新显式给出的字段；{@code defaultSonarProjectKey} 传空串表示清除
     * （JSON 无法区分「省略」与「null」，所以清除用空串而不是 null）。
     */
    @Transactional
    public ReleaseIntegrationViews.Settings update(long releaseId, ReleaseIntegrationViews.SettingsCmd cmd) {
        ReleaseEntity release = releaseService.requireVisible(releaseId);
        ReleaseIntegrationViews.SettingsCmd request = cmd == null
                ? new ReleaseIntegrationViews.SettingsCmd(null, null)
                : cmd;

        Map<String, Object> changed = new LinkedHashMap<>();
        if (request.mrAutoAdvance() != null) {
            boolean next = request.mrAutoAdvance();
            if (autoAdvance(release) != next) {
                release.setMrAutoAdvance(next ? 1 : 0);
                changed.put("mrAutoAdvance", next);
            }
        }
        if (request.defaultSonarProjectKey() != null) {
            String next = normalizeSonarKey(request.defaultSonarProjectKey());
            String current = release.getSonarProjectKey();
            if (next == null ? current != null : !next.equals(current)) {
                release.setSonarProjectKey(next);
                changed.put("defaultSonarProjectKey", next);
            }
        }
        if (!changed.isEmpty()) {
            release.setUpdatedBy(TenantContext.require().userId());
            auditService.record(ACTION_UPDATED, BIZ_TYPE, releaseId, Map.copyOf(changed));
        }
        return new ReleaseIntegrationViews.Settings(release.getId(),
                autoAdvance(release), release.getSonarProjectKey());
    }

    /**
     * 生效的 Sonar 项目键：需求自带优先，否则回落版本默认，两者都空返回 {@code null}
     * （调用方据此判「⑦⑧⑨ 里 ⑧ 无证据」而不是拿空 key 去外呼）。
     */
    @Transactional(readOnly = true)
    public String effectiveSonarKey(long releaseId, String requirementKey) {
        if (requirementKey != null && !requirementKey.isBlank()) {
            return requirementKey.trim();
        }
        ReleaseEntity release = releaseService.requireVisible(releaseId);
        String fallback = release.getSonarProjectKey();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private static boolean autoAdvance(ReleaseEntity release) {
        Integer value = release.getMrAutoAdvance();
        return value != null && value == 1;
    }

    /**
     * 空格串 = 清除；非法字符 → 400 SONAR_KEY_INVALID（PRD 指定机器码）。
     * 正则与错误码只在 {@link SonarProjectKeys} 一处，避免需求级与版本级两套校验漂移。
     */
    private static String normalizeSonarKey(String raw) {
        return SonarProjectKeys.normalize(raw, "defaultSonarProjectKey");
    }
}
