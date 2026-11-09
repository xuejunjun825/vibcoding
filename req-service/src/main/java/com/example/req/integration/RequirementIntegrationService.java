package com.example.req.integration;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.req.integration.config.IntegrationConfigService;
import com.example.req.integration.config.IntegrationConfigViews;
import com.example.req.integration.gitlab.MrSyncService;
import com.example.req.integration.gitlab.MrViews;
import com.example.req.integration.sonar.SonarSnapshotService;
import com.example.req.integration.sonar.SonarViews;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementService;

/**
 * 需求详情「集成」tab 的装配层：把代码卡、Sonar 卡与最近同步记录拼成一次载荷。
 *
 * <p>三个来源各自已有单一职责的服务，这里只做"同一次请求、同一租户校验"的编排，
 * 不复制任何判定：MR 是否过期由 {@link MrSyncService} 算，Sonar 的 SKIP 语义由
 * {@link SonarSnapshotService} 给，日志脱敏在写侧就完成了。</p>
 */
@Service
public class RequirementIntegrationService {

    /** tab 上只展示最近 10 条，与 TECHNICAL §4.4 的载荷约定一致。 */
    static final int SYNC_LIMIT = 10;

    private final RequirementService requirementService;
    private final MrSyncService mrSyncService;
    private final SonarSnapshotService sonarSnapshotService;
    private final IntegrationConfigService configService;

    public RequirementIntegrationService(RequirementService requirementService,
                                         MrSyncService mrSyncService,
                                         SonarSnapshotService sonarSnapshotService,
                                         IntegrationConfigService configService) {
        this.requirementService = requirementService;
        this.mrSyncService = mrSyncService;
        this.sonarSnapshotService = sonarSnapshotService;
        this.configService = configService;
    }

    @Transactional(readOnly = true)
    public RequirementIntegrationViews load(long requirementId) {
        // 先做需求可见性校验：跨租户访问在读到任何集成事实之前就被拒。
        RequirementEntity requirement = requirementService.requireVisible(requirementId);

        MrViews.MrCard code = mrSyncService.card(requirementId);
        if (code == null) {
            // AC-4.1：用户手工填的 mr_url 不能因为还没同步过就消失；没有链接时返回空卡，
            // 前端统一按"未同步"空态渲染（卡片自己带 stale=true，由前端只在有链接时提示过期）。
            code = MrViews.MrCard.linkOnly(requirement.getMrUrl());
        }
        SonarViews.SonarCard sonar = sonarSnapshotService.latest(requirementId);
        // DESIGN §I4/§325：这三类记录必须属于本需求，不是租户全局最近 10 条。
        // 作用域的派生（业务锚点 → requirement_id）在日志查询内部实现一次，这里只传需求。
        List<IntegrationConfigViews.LogRow> sync =
                configService.logsForRequirement(requirementId, SYNC_LIMIT);
        return new RequirementIntegrationViews(code, sonar, sync);
    }
}
