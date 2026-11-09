package com.example.req.integration.admission;

import com.example.platform.common.tenant.TenantContext;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.store.ArtifactRecordEntity;
import com.example.req.integration.store.ArtifactRecordRepository;
import com.example.req.integration.store.BuildRecordEntity;
import com.example.req.integration.store.BuildRecordRepository;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ⑦⑧⑨ 的证据读取器：<b>只读本地事实，零外呼</b>（TECHNICAL §7.3 / §8.2）。
 *
 * <p>准入必须 500ms 内出结论，而 Jenkins/Sonar/Nexus 的往返动辄数秒。结构性保证是：
 * 外呼只发生在"触发构建 / 刷新 Sonar 快照 / 登记制品"这些后台或交互动作里，它们把结果写成
 * 本地事实（{@code build_record} / {@code quality_gate_snapshot(source='SONAR')} /
 * {@code artifact_record}）；准入路径只读这三张表。</p>
 *
 * <p>每一种"拿不准"都落到 FAIL 并且带机器码（fail-closed）：查不到快照不等于通过，
 * 读库异常也不等于通过。例外只有两种明确的"不参与"——集成未启用（CLOSED）与需求未绑定
 * Sonar projectKey（SKIP，永不计数为通过）。</p>
 */
@Component
public class IntegrationEvidenceReader {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");

    /** Sonar 快照在 M6 表里的 source 区分值（V8 加的维度，M6 的行是 NULL）。 */
    private static final String SONAR_SOURCE = "SONAR";

    private final IntegrationConfigRepository configRepository;
    private final BuildRecordRepository buildRepository;
    private final ArtifactRecordRepository artifactRepository;
    private final RequirementRepository requirementRepository;
    private final ReleaseRepository releaseRepository;
    private final SonarSnapshotRepository sonarSnapshotRepository;

    public IntegrationEvidenceReader(IntegrationConfigRepository configRepository,
                                     BuildRecordRepository buildRepository,
                                     ArtifactRecordRepository artifactRepository,
                                     RequirementRepository requirementRepository,
                                     ReleaseRepository releaseRepository,
                                     SonarSnapshotRepository sonarSnapshotRepository) {
        this.configRepository = configRepository;
        this.buildRepository = buildRepository;
        this.artifactRepository = artifactRepository;
        this.requirementRepository = requirementRepository;
        this.releaseRepository = releaseRepository;
        this.sonarSnapshotRepository = sonarSnapshotRepository;
    }

    /** 某系统的集成是否已启用（存在 enabled=1 的配置行）。 */
    public boolean enabled(long tenantId, IntegrationSystem system) {
        return configRepository
                .findFirstByTenantIdAndSystemAndEnabledOrderByIdAsc(tenantId, system.name(), 1)
                .isPresent();
    }

    // ---------------------------------------------------------------- ⑦ 构建

    public AdmissionViews.Evidence build(long deployOrderId) {
        return build(TenantContext.require().tenantId(), deployOrderId, null);
    }

    /** ⑦：该发布单最近一次（或指针指向的那次）构建的状态。 */
    public AdmissionViews.Evidence build(long tenantId, long deployOrderId, Long buildId) {
        if (!enabled(tenantId, IntegrationSystem.JENKINS)) {
            return AdmissionViews.Evidence.closed(AdmissionViews.INTEGRATION_DISABLED,
                    "Jenkins 集成未启用，构建项自动关闭");
        }
        List<BuildRecordEntity> recent;
        try {
            recent = buildRepository.findTop5ByTenantIdAndDeployOrderIdOrderByIdDesc(tenantId, deployOrderId);
        } catch (RuntimeException ex) {
            return AdmissionViews.Evidence.fail("EVIDENCE_UNAVAILABLE", "读取构建记录失败：" + root(ex), null);
        }
        if (recent == null || recent.isEmpty()) {
            return AdmissionViews.Evidence.fail("NO_BUILD_RECORD",
                    "发布单还没有任何构建记录（Jenkins 已启用）", null);
        }
        BuildRecordEntity record = recent.get(0);
        if (buildId != null) {
            for (BuildRecordEntity candidate : recent) {
                if (buildId.equals(candidate.getId())) {
                    record = candidate;
                    break;
                }
            }
        }
        String status = upper(record.getStatus());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("buildId", record.getId());
        facts.put("jobName", record.getJobName());
        facts.put("buildNumber", record.getBuildNumber());
        facts.put("status", status);
        String hash = "build:" + record.getId() + ":" + status + ":" + record.getBuildNumber();
        String label = "构建 #" + nvl(record.getBuildNumber()) + "（" + nvl(record.getJobName()) + "）";

        if (record.isActive() && record.getTimeoutAt() != null
                && record.getTimeoutAt().isBefore(LocalDateTime.now())) {
            return new AdmissionViews.Evidence(AdmissionViews.FAIL, "BUILD_TIMEOUT",
                    label + " 已超过 2 小时未结束，按超时处理", hash, facts);
        }
        if ("SUCCESS".equals(status)) {
            return new AdmissionViews.Evidence(AdmissionViews.PASS, "BUILD_SUCCESS",
                    label + " 成功", hash, facts);
        }
        if ("QUEUED".equals(status) || "RUNNING".equals(status) || "PENDING".equals(status)
                || "IN_PROGRESS".equals(status)) {
            return new AdmissionViews.Evidence(AdmissionViews.FAIL, "BUILD_IN_PROGRESS",
                    label + " 正在进行（" + status + "）", hash, facts);
        }
        return new AdmissionViews.Evidence(AdmissionViews.FAIL, "BUILD_FAILED",
                label + " 未成功（" + nvl(status) + "）", hash, facts);
    }

    // ---------------------------------------------------------------- ⑧ Sonar

    public AdmissionViews.Evidence sonar(long requirementId) {
        return sonar(TenantContext.require().tenantId(), requirementId);
    }

    /** ⑧ 单需求：未绑定 key ⇒ SKIP；已绑定无快照 ⇒ FAIL；快照 OK ⇒ PASS。 */
    public AdmissionViews.Evidence sonar(long tenantId, long requirementId) {
        if (!enabled(tenantId, IntegrationSystem.SONAR)) {
            return AdmissionViews.Evidence.closed(AdmissionViews.INTEGRATION_DISABLED,
                    "Sonar 集成未启用，Sonar 门禁项自动关闭");
        }
        RequirementEntity requirement;
        try {
            requirement = requirementRepository.findByIdAndTenantId(requirementId, tenantId).orElse(null);
        } catch (RuntimeException ex) {
            return AdmissionViews.Evidence.fail("EVIDENCE_UNAVAILABLE", "读取需求失败：" + root(ex), null);
        }
        if (requirement == null) {
            return AdmissionViews.Evidence.fail("EVIDENCE_UNAVAILABLE",
                    "需求 " + requirementId + " 不存在或不属于当前租户", null);
        }
        String projectKey = firstNonBlank(requirement.getSonarProjectKey(),
                releaseSonarKey(tenantId, requirement.getReleaseId()));
        if (projectKey == null) {
            return AdmissionViews.Evidence.skip(AdmissionViews.NOT_CONFIGURED,
                    "跳过：需求 " + requirement.getCode() + " 未绑定 Sonar projectKey（需求与版本都没有设置）",
                    "sonar:" + requirementId + ":nokey");
        }
        SonarSnapshot snapshot;
        try {
            snapshot = latestSonarSnapshot(tenantId, requirementId);
        } catch (RuntimeException ex) {
            return AdmissionViews.Evidence.fail("ADAPTER_UNAVAILABLE",
                    "读取 Sonar 快照失败：" + root(ex), "sonar:" + requirementId + ":error");
        }
        if (snapshot == null) {
            return AdmissionViews.Evidence.fail("SNAPSHOT_MISSING",
                    "Sonar 已启用且已绑定 projectKey=" + projectKey + "，但还没有快照（等待后台刷新或手动刷新）",
                    "sonar:" + requirementId + ":nosnapshot");
        }
        String result = upper(snapshot.result());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("requirementId", requirementId);
        facts.put("requirementCode", requirement.getCode());
        facts.put("projectKey", firstNonBlank(snapshot.projectKey(), projectKey));
        facts.put("snapshotId", snapshot.id());
        facts.put("result", result);
        facts.put("metricStatus", snapshot.metricStatus());
        String hash = "sonar:" + snapshot.id() + ":" + nvl(snapshot.hash());
        String label = "Sonar 快照 #" + snapshot.id() + "（projectKey=" + facts.get("projectKey") + "）";

        if (isBlank(snapshot.metricsJson())) {
            return new AdmissionViews.Evidence(AdmissionViews.FAIL, "METRIC_MISSING",
                    label + " 缺少五度量明细，无法判定", hash, facts);
        }
        if ("OK".equals(result)) {
            return new AdmissionViews.Evidence(AdmissionViews.PASS, "SONAR_OK",
                    label + " 质量门 OK：" + metricSummary(snapshot.metricsJson()), hash, facts);
        }
        return new AdmissionViews.Evidence(AdmissionViews.FAIL, "SONAR_GATE_ERROR",
                label + " 质量门 " + nvl(result) + "：" + metricSummary(snapshot.metricsJson()), hash, facts);
    }

    /**
     * ⑧ 聚合：任一需求 FAIL ⇒ FAIL（列全部失败需求）；有 PASS ⇒ PASS（列出跳过/失败的需求）；
     * 全部 SKIP ⇒ SKIP（原因为"未配置"）。空需求集 ⇒ FAIL（发布单不能空转）。
     */
    public AdmissionViews.Evidence sonarAggregate(long tenantId, List<Long> requirementIds) {
        if (!enabled(tenantId, IntegrationSystem.SONAR)) {
            return AdmissionViews.Evidence.closed(AdmissionViews.INTEGRATION_DISABLED,
                    "Sonar 集成未启用，Sonar 门禁项自动关闭");
        }
        if (requirementIds == null || requirementIds.isEmpty()) {
            return AdmissionViews.Evidence.fail("SNAPSHOT_MISSING", "发布单未关联任何研发需求", "sonar:none");
        }
        List<AdmissionViews.Evidence> all = new ArrayList<>();
        StringBuilder digest = new StringBuilder();
        for (Long id : requirementIds) {
            AdmissionViews.Evidence evidence = sonar(tenantId, id);
            all.add(evidence);
            digest.append(id).append('=').append(evidence.status()).append(':')
                    .append(nvl(evidence.hash())).append(';');
        }
        String hash = "sonar:" + digest;
        List<String> failures = all.stream()
                .filter(e -> AdmissionViews.FAIL.equals(e.status()))
                .map(AdmissionViews.Evidence::detail)
                .toList();
        List<String> skips = all.stream()
                .filter(e -> AdmissionViews.SKIP.equals(e.status()))
                .map(AdmissionViews.Evidence::detail)
                .toList();
        if (!failures.isEmpty()) {
            return AdmissionViews.Evidence.fail("SONAR_GATE_ERROR",
                    String.join("；", failures), hash);
        }
        long passed = all.stream().filter(AdmissionViews.Evidence::passed).count();
        if (passed > 0) {
            String detail = passed + "/" + all.size() + " 条需求的 Sonar 质量门通过";
            return AdmissionViews.Evidence.pass("SONAR_OK",
                    skips.isEmpty() ? detail : detail + "；" + String.join("；", skips), hash);
        }
        return new AdmissionViews.Evidence(AdmissionViews.SKIP, AdmissionViews.NOT_CONFIGURED,
                String.join("；", skips), hash, Map.of());
    }

    // ---------------------------------------------------------------- ⑨ 制品

    public AdmissionViews.Evidence artifact(Long artifactId) {
        return artifact(TenantContext.require().tenantId(), artifactId);
    }

    /** ⑨ MANUAL 与 NEXUS 同等校验：登记的 sha256 必须是 64 位十六进制，且记录已校验。 */
    public AdmissionViews.Evidence artifact(long tenantId, Long artifactId) {
        if (!enabled(tenantId, IntegrationSystem.NEXUS)) {
            return AdmissionViews.Evidence.closed(AdmissionViews.INTEGRATION_DISABLED,
                    "Nexus 制品库未启用，制品校验项自动关闭");
        }
        if (artifactId == null) {
            return AdmissionViews.Evidence.fail("ARTIFACT_MISSING", "发布单未绑定制品（artifact_id 为空）", null);
        }
        ArtifactRecordEntity record;
        try {
            record = artifactRepository.findByIdAndTenantId(artifactId, tenantId).orElse(null);
        } catch (RuntimeException ex) {
            return AdmissionViews.Evidence.fail("EVIDENCE_UNAVAILABLE", "读取制品记录失败：" + root(ex), null);
        }
        if (record == null) {
            return AdmissionViews.Evidence.fail("ARTIFACT_MISSING",
                    "制品记录 #" + artifactId + " 不存在或不属于当前租户", "artifact:" + artifactId + ":missing");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("artifactId", record.getId());
        facts.put("repo", record.getRepo());
        facts.put("groupId", record.getGroupId());
        facts.put("artifactName", record.getArtifactName());
        facts.put("version", record.getVersion());
        facts.put("source", record.getSource());
        facts.put("status", record.getStatus());

        String sha256 = record.getSha256();
        String label = nvl(record.getArtifactName()) + ":" + nvl(record.getVersion());
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            facts.put("sha256", nvl(sha256));
            return new AdmissionViews.Evidence(AdmissionViews.FAIL, "CHECKSUM_MISMATCH",
                    "制品 " + label + " 的 sha256 缺失或不是 64 位十六进制（实际 "
                            + prefix(sha256) + "）",
                    "artifact:" + record.getId() + ":badsha", facts);
        }
        facts.put("sha256", sha256);
        String hash = "artifact:" + record.getId() + ":" + sha256.toLowerCase();
        String status = upper(record.getStatus());
        if ("VERIFIED".equals(status) || "PROMOTED".equals(status) || "OK".equals(status)) {
            return new AdmissionViews.Evidence(AdmissionViews.PASS, "ARTIFACT_OK",
                    "制品 " + label + " 已校验（sha256 " + prefix(sha256) + "，来源 " + nvl(record.getSource()) + "）",
                    hash, facts);
        }
        if ("MISMATCH".equals(status) || "CHECKSUM_MISMATCH".equals(status)) {
            return new AdmissionViews.Evidence(AdmissionViews.FAIL, "CHECKSUM_MISMATCH",
                    "制品 " + label + " 的 sha256 与源端不一致（实际 " + prefix(sha256) + "）",
                    hash, facts);
        }
        return new AdmissionViews.Evidence(AdmissionViews.FAIL, "ARTIFACT_NOT_VERIFIED",
                "制品 " + label + " 尚未完成校验（状态 " + nvl(status) + "）", hash, facts);
    }

    // ---------------------------------------------------------------- 内部

    private String releaseSonarKey(long tenantId, Long releaseId) {
        if (releaseId == null) {
            return null;
        }
        ReleaseEntity release = releaseRepository.findByIdAndTenantId(releaseId, tenantId).orElse(null);
        return release == null ? null : release.getSonarProjectKey();
    }

    private SonarSnapshot latestSonarSnapshot(long tenantId, long requirementId) {
        return sonarSnapshotRepository
                .findFirstByTenantIdAndSourceAndRequirementIdOrderByIdDesc(tenantId, SONAR_SOURCE, requirementId)
                .map(row -> new SonarSnapshot(row.getId(), row.getProjectKey(), row.getResult(),
                        row.getMetricStatus(), row.getMetricsJson(), row.getHash()))
                .orElse(null);
    }

    /** Sonar 五度量摘成一行（解析失败不抛：证据里只要可读的说明）。 */
    private String metricSummary(String metricsJson) {
        if (isBlank(metricsJson)) {
            return "（无指标）";
        }
        String text = metricsJson.trim();
        String compact = text.replaceAll("\\s+", " ");
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "…";
    }

    private record SonarSnapshot(Long id, String projectKey, String result, String metricStatus,
                                 String metricsJson, String hash) {
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nvl(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String prefix(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return "空";
        }
        return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12);
    }

    private static String root(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName() + (cursor.getMessage() == null ? "" : "：" + cursor.getMessage());
    }
}
