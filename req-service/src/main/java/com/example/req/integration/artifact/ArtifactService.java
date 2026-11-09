package com.example.req.integration.artifact;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.contract.DataScope;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.deploy.DeployOrderEntity;
import com.example.req.deploy.DeployOrderRepository;
import com.example.req.deploy.DeployOrderRequirementRepository;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.security.SsrfGuard;
import com.example.req.integration.store.ArtifactRecordEntity;
import com.example.req.integration.store.ArtifactRecordRepository;

/**
 * 制品登记与晋级（TECHNICAL §5.5 / AC-7）。
 *
 * <p>三条不变式全部在这个服务里收口，控制器不参与判定：</p>
 * <ol>
 *   <li><b>登记幂等</b>：唯一键 {@code uk_artifact(tenant_id, repo, group_id, artifact_name,
 *       version, packaging, sha256)} 命中即复用同一行；同一行被另一张发布单占用则 409
 *       {@code ARTIFACT_ALREADY_BOUND}，不悄悄易主。</li>
 *   <li><b>摘要一致</b>：NEXUS 登记时按坐标回查 Nexus 元数据（{@link NexusAdapter#checksum}），
 *       提供值与元数据不一致即 409 {@code CHECKSUM_MISMATCH}，且不写库；查不到元数据同样
 *       fail-closed（{@code CHECKSUM_UNAVAILABLE} 语义复用同一错误码，消息区分）。</li>
 *   <li><b>晋级幂等</b>：目标仓库的 sha256 与源记录一致才写新行（{@code promoted_from_id}），
 *       同一源制品重复晋级返回既有行（200），并发由 {@code uk_promoted} 兜底。</li>
 * </ol>
 *
 * <p>出站只发生在 Nexus 检索（SSRF/超时/脱敏在 {@code OutboundHttpClient} 内），
 * 制品直链只做白名单校验、绝不主动下载——AC-7.6 的「被拒地址 0 请求」由此成立。</p>
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    public static final String ACTION_SELECTED = "ARTIFACT_SELECTED";
    public static final String ACTION_PROMOTED = "ARTIFACT_PROMOTED";

    private static final String STATUS_VERIFIED = "VERIFIED";
    private static final String BIZ_ARTIFACT = "ARTIFACT";
    private static final String BIZ_ORDER = "DEPLOY_ORDER";

    private final ArtifactRecordRepository repository;
    private final DeployOrderRepository deployOrderRepository;
    private final DeployOrderRequirementRepository orderRequirementRepository;
    private final NexusAdapter nexusAdapter;
    private final AdapterRegistry adapterRegistry;
    private final SsrfGuard ssrfGuard;
    private final AuditService auditService;

    public ArtifactService(ArtifactRecordRepository repository,
                           DeployOrderRepository deployOrderRepository,
                           DeployOrderRequirementRepository orderRequirementRepository,
                           NexusAdapter nexusAdapter,
                           AdapterRegistry adapterRegistry,
                           SsrfGuard ssrfGuard,
                           AuditService auditService) {
        this.repository = repository;
        this.deployOrderRepository = deployOrderRepository;
        this.orderRequirementRepository = orderRequirementRepository;
        this.nexusAdapter = nexusAdapter;
        this.adapterRegistry = adapterRegistry;
        this.ssrfGuard = ssrfGuard;
        this.auditService = auditService;
    }

    /**
     * 绑定/登记制品。两种入口：{@code artifactId} 选已有记录，或按坐标登记（NEXUS/MANUAL）。
     * 成功恒 200（登记不是「新建资源」语义：同一坐标重复提交必须返回同一行）。
     */
    @Transactional
    public ArtifactViews.ArtifactView bind(long deployOrderId, ArtifactViews.BindCmd cmd) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        DeployOrderEntity order = requireOrder(deployOrderId, tenantId);
        if (cmd == null) {
            throw new ApiException(400, ArtifactCoordinates.CODE_INVALID, "请求体不能为空");
        }
        ArtifactViews.BindCmd body = cmd.effective();
        if (body.artifactId() != null) {
            return bindExisting(order, body.artifactId(), tenantId, scope.userId());
        }

        String source = resolveSource(cmd, body);
        String sha256 = ArtifactCoordinates.sha256(body.sha256());
        String downloadUrl = trimToNull(body.downloadUrl());
        checkDownloadUrl(downloadUrl);
        ArtifactCoordinates.Coordinates coordinates = ArtifactCoordinates.normalize(
                body.repository(), body.groupId(), body.artifactName(), body.version(),
                body.packaging(), body.classifier());

        IntegrationConfigEntity config = null;
        String status = ArtifactRecordEntity.STATUS_REGISTERED;
        if (ArtifactRecordEntity.SOURCE_NEXUS.equals(source)) {
            if (downloadUrl == null) {
                throw new ApiException(400, ArtifactCoordinates.CODE_INVALID,
                        "NEXUS 制品必须提供 downloadUrl", Map.of("field", "downloadUrl"));
            }
            config = adapterRegistry.require(IntegrationSystem.NEXUS);
            String nexusSha = nexusAdapter.checksum(config, coordinates.lookupKey());
            if (nexusSha == null) {
                throw new ApiException(409, ArtifactCoordinates.CODE_CHECKSUM_MISMATCH,
                        "无法从 Nexus 元数据核实 sha256（制品未入库或检索不可用）",
                        Map.of("coordinates", coordinates.lookupKey(),
                                "providedSha256", ArtifactCoordinates.shortSha(sha256)));
            }
            if (!ArtifactCoordinates.sameSha(nexusSha, sha256)) {
                throw mismatch(sha256, nexusSha, coordinates.lookupKey(), "NEXUS");
            }
            status = STATUS_VERIFIED;
        }

        Optional<ArtifactRecordEntity> existing = repository
                .findFirstByTenantIdAndRepoAndGroupIdAndArtifactNameAndVersionAndPackagingAndSha256(
                        tenantId, coordinates.repo(), coordinates.groupId(),
                        coordinates.artifactName(), coordinates.version(),
                        coordinates.packaging(), sha256);
        if (existing.isPresent()) {
            ArtifactRecordEntity record = existing.get();
            rejectIfBoundElsewhere(record, order);
            if (STATUS_VERIFIED.equals(status)
                    && !ArtifactRecordEntity.STATUS_PROMOTED.equals(record.getStatus())) {
                record.setStatus(STATUS_VERIFIED);
            }
            attach(order, record, tenantId, scope.userId());
            return view(record);
        }

        ArtifactRecordEntity entity = new ArtifactRecordEntity();
        entity.setTenantId(tenantId);
        entity.setConfigId(config == null ? null : config.getId());
        entity.setRepo(coordinates.repo());
        entity.setGroupId(coordinates.groupId());
        entity.setArtifactName(coordinates.artifactName());
        entity.setVersion(coordinates.version());
        entity.setPackaging(coordinates.packaging());
        entity.setClassifier(coordinates.classifier());
        entity.setSha256(sha256);
        entity.setDownloadUrl(downloadUrl);
        entity.setSizeBytes(body.sizeBytes());
        entity.setSource(source);
        entity.setStatus(status);
        entity.setRequirementId(firstRequirementId(tenantId, order.getId()));
        entity.setCreatedBy(scope.userId());
        try {
            entity = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // 并发重复登记：唯一键已由另一个请求写入。让调用方重试一次即可拿到幂等结果；
            // 这里不能假装成功，否则会返回一个没有 id 的视图。
            log.warn("artifact insert conflicted: tenant={} repo={} name={} version={}",
                    tenantId, coordinates.repo(), coordinates.artifactName(), coordinates.version());
            throw new ApiException(409, ArtifactCoordinates.CODE_ALREADY_BOUND,
                    "该制品正在被另一个请求登记，请重试",
                    Map.of("coordinates", coordinates.lookupKey()));
        }
        attach(order, entity, tenantId, scope.userId());
        return view(entity);
    }

    /** 发布单当前绑定制品；没有返回 {@code null}（页面显示空态）。 */
    @Transactional(readOnly = true)
    public ArtifactViews.ArtifactView current(long deployOrderId) {
        long tenantId = TenantContext.require().tenantId();
        DeployOrderEntity order = requireOrder(deployOrderId, tenantId);
        Optional<ArtifactRecordEntity> record = repository
                .findFirstByTenantIdAndDeployOrderIdOrderByIdDesc(tenantId, deployOrderId);
        if (record.isEmpty() && order.getArtifactId() != null) {
            record = repository.findByIdAndTenantId(order.getArtifactId(), tenantId);
        }
        return record.map(this::view).orElse(null);
    }

    /**
     * 晋级：目标仓库的 sha256 与源记录一致才写新行；重复晋级返回既有记录（200）。
     */
    @Transactional
    public ArtifactViews.PromoteResult promote(long artifactId, String targetRepo) {
        DataScope scope = TenantContext.require();
        long tenantId = scope.tenantId();
        ArtifactRecordEntity source = repository.findByIdAndTenantId(artifactId, tenantId)
                .orElseThrow(() -> new ApiException(404, "ARTIFACT_NOT_FOUND", "制品不存在"));

        Optional<ArtifactRecordEntity> promoted = repository
                .findFirstByTenantIdAndPromotedFromId(tenantId, artifactId);
        if (promoted.isPresent()) {
            return promoteResult(promoted.get());
        }
        String target = ArtifactCoordinates.requireRepo(targetRepo);
        if (!ArtifactRecordEntity.SOURCE_NEXUS.equals(source.getSource())) {
            throw new ApiException(409, ArtifactCoordinates.CODE_CHECKSUM_MISMATCH,
                    "手工制品没有制品仓元数据可核对，不能晋级",
                    Map.of("artifactId", artifactId, "source", source.getSource()));
        }
        ArtifactCoordinates.Coordinates coordinates = ArtifactCoordinates.normalize(
                target, source.getGroupId(), source.getArtifactName(), source.getVersion(),
                source.getPackaging(), source.getClassifier());
        IntegrationConfigEntity config = adapterRegistry.require(IntegrationSystem.NEXUS);
        String targetSha = nexusAdapter.checksum(config, coordinates.lookupKey());
        if (targetSha == null) {
            throw new ApiException(409, ArtifactCoordinates.CODE_CHECKSUM_MISMATCH,
                    "目标仓库未返回可核对的 sha256，晋级被拒绝",
                    Map.of("targetRepo", target, "sourceSha256",
                            ArtifactCoordinates.shortSha(source.getSha256())));
        }
        if (!ArtifactCoordinates.sameSha(source.getSha256(), targetSha)) {
            throw mismatch(source.getSha256(), targetSha, coordinates.lookupKey(), target);
        }

        ArtifactRecordEntity entity = new ArtifactRecordEntity();
        entity.setTenantId(tenantId);
        entity.setConfigId(config.getId());
        entity.setRepo(target);
        entity.setGroupId(source.getGroupId());
        entity.setArtifactName(source.getArtifactName());
        entity.setVersion(source.getVersion());
        entity.setPackaging(source.getPackaging());
        entity.setClassifier(source.getClassifier());
        entity.setSha256(source.getSha256());
        entity.setDownloadUrl(ArtifactCoordinates.retarget(source.getDownloadUrl(), target));
        entity.setSizeBytes(source.getSizeBytes());
        entity.setSource(source.getSource());
        entity.setStatus(ArtifactRecordEntity.STATUS_PROMOTED);
        entity.setPromotedFromId(source.getId());
        entity.setPromotedAt(LocalDateTime.now());
        entity.setPromotedBy(scope.userId());
        entity.setRequirementId(source.getRequirementId());
        entity.setCreatedBy(scope.userId());
        try {
            entity = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            log.warn("artifact promote conflicted: tenant={} source={} target={}",
                    tenantId, artifactId, target);
            throw new ApiException(409, "ARTIFACT_ALREADY_PROMOTED",
                    "该制品已被另一个请求晋级，请刷新后查看",
                    Map.of("artifactId", artifactId, "targetRepo", target));
        }

        auditService.record(ACTION_PROMOTED, BIZ_ARTIFACT, entity.getId(), Map.of(
                "sourceArtifactId", source.getId(),
                "targetRepo", target,
                "sha256", ArtifactCoordinates.shortSha(entity.getSha256())));
        return promoteResult(entity);
    }

    // ------------------------------------------------------------------ 内部

    private ArtifactViews.ArtifactView bindExisting(DeployOrderEntity order, Long artifactId,
                                                    long tenantId, long userId) {
        ArtifactRecordEntity record = repository.findByIdAndTenantId(artifactId, tenantId)
                .orElseThrow(() -> new ApiException(404, "ARTIFACT_NOT_FOUND", "制品不存在"));
        rejectIfBoundElsewhere(record, order);
        attach(order, record, tenantId, userId);
        return view(record);
    }

    private void rejectIfBoundElsewhere(ArtifactRecordEntity record, DeployOrderEntity order) {
        Long boundTo = record.getDeployOrderId();
        if (boundTo != null && !boundTo.equals(order.getId())) {
            throw new ApiException(409, ArtifactCoordinates.CODE_ALREADY_BOUND,
                    "该制品已绑定到另一张发布单",
                    Map.of("artifactId", record.getId(), "deployOrderId", boundTo));
        }
    }

    /** 绑定关系与审计：发布单指针、需求指针、artifactVersion 回填只在这一处发生。 */
    private void attach(DeployOrderEntity order, ArtifactRecordEntity record,
                        long tenantId, long userId) {
        Long previousDeployOrder = record.getDeployOrderId();
        boolean newlyBound = previousDeployOrder == null
                || !previousDeployOrder.equals(order.getId());
        boolean selectionChanged = !record.getId().equals(order.getArtifactId());
        record.setDeployOrderId(order.getId());
        if (record.getRequirementId() == null) {
            record.setRequirementId(firstRequirementId(tenantId, order.getId()));
        }
        repository.save(record);
        order.setArtifactId(record.getId());
        order.setArtifactVersion(record.getVersion());
        deployOrderRepository.save(order);
        // 幂等重复提交不重复记审计：只有「换了单」或「换了选中制品」才算一次新的选择。
        if (newlyBound || selectionChanged) {
            auditService.record(ACTION_SELECTED, BIZ_ORDER, order.getId(), Map.of(
                    "artifactId", record.getId(),
                    "repo", record.getRepo(),
                    "artifactName", record.getArtifactName(),
                    "version", record.getVersion(),
                    "source", record.getSource(),
                    "sha256", ArtifactCoordinates.shortSha(record.getSha256())));
        }
    }

    private DeployOrderEntity requireOrder(long deployOrderId, long tenantId) {
        return deployOrderRepository.findByIdAndTenantId(deployOrderId, tenantId)
                .orElseThrow(() -> new ApiException(404, "DEPLOY_ORDER_NOT_FOUND", "发布单不存在"));
    }

    /**
     * 制品直链的白名单校验。被拒地址在建立连接前就失败（{@link SsrfGuard#check}），
     * 因此这里不会产生任何出站请求；错误码统一成 {@code URL_NOT_ALLOWED} 供前端定位。
     */
    private void checkDownloadUrl(String downloadUrl) {
        if (downloadUrl == null) {
            return;
        }
        try {
            ssrfGuard.check(downloadUrl);
        } catch (ApiException ex) {
            throw new ApiException(400, ArtifactCoordinates.CODE_URL_NOT_ALLOWED,
                    "制品下载地址不被允许：" + ex.message(),
                    Map.of("host", String.valueOf(ArtifactCoordinates.hostOf(downloadUrl)),
                            "reason", ex.code()));
        }
    }

    private Long firstRequirementId(long tenantId, long deployOrderId) {
        List<com.example.req.deploy.DeployOrderRequirementEntity> links =
                orderRequirementRepository.findByTenantIdAndDeployOrderIdOrderByRequirementIdAsc(
                        tenantId, deployOrderId);
        return links.isEmpty() ? null : links.get(0).getRequirementId();
    }

    /** 来源枚举归一：显式值优先；嵌套 manual 缺省 MANUAL；扁平坐标缺省 NEXUS。 */
    private static String resolveSource(ArtifactViews.BindCmd raw, ArtifactViews.BindCmd body) {
        String source = body.source() == null ? "" : body.source().trim().toUpperCase(Locale.ROOT);
        if (source.isEmpty()) {
            if (raw.manual() != null) {
                source = ArtifactRecordEntity.SOURCE_MANUAL;
            } else {
                source = ArtifactCoordinates.isBlank(body.downloadUrl())
                        ? ArtifactRecordEntity.SOURCE_MANUAL
                        : ArtifactRecordEntity.SOURCE_NEXUS;
            }
        }
        if (!ArtifactRecordEntity.SOURCE_NEXUS.equals(source)
                && !ArtifactRecordEntity.SOURCE_MANUAL.equals(source)) {
            throw new ApiException(400, ArtifactCoordinates.CODE_INVALID,
                    "source 只能是 NEXUS 或 MANUAL", Map.of("field", "source"));
        }
        return source;
    }

    private static ApiException mismatch(String provided, String actual, String coordinates, String repo) {
        return new ApiException(409, ArtifactCoordinates.CODE_CHECKSUM_MISMATCH,
                "sha256 与制品仓元数据不一致",
                Map.of("expectedSha256", ArtifactCoordinates.shortSha(provided),
                        "actualSha256", ArtifactCoordinates.shortSha(actual),
                        "coordinates", coordinates,
                        "repo", repo));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ArtifactViews.PromoteResult promoteResult(ArtifactRecordEntity entity) {
        return new ArtifactViews.PromoteResult(entity.getId(), entity.getPromotedFromId(),
                entity.getStatus());
    }

    private ArtifactViews.ArtifactView view(ArtifactRecordEntity entity) {
        return new ArtifactViews.ArtifactView(entity.getId(), entity.getRepo(), entity.getGroupId(),
                entity.getArtifactName(), entity.getVersion(), entity.getPackaging(),
                entity.getClassifier(), entity.getSha256(), entity.getSource(), entity.getStatus(),
                entity.getDownloadUrl(), entity.getSizeBytes(), entity.getPromotedFromId(),
                entity.getPromotedAt());
    }
}
