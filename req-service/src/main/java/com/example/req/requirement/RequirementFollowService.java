package com.example.req.requirement;

import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.RequirementPoolOpsViews.FollowRequest;
import com.example.req.requirement.RequirementPoolOpsViews.FollowResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「我也需要」跟单（r47 契约 §3.2）。
 *
 * <p>幂等的落点在数据库唯一键 {@code uk_follow}，不在应用层「先查再插」：并发下先查再插会两边都
 * 读到 0、随后一边撞唯一键抛 500。这里只把 {@code INSERT IGNORE} 的 0/1 翻译成 200/201，并把
 * 「重复跟单」仍返回当前热度。</p>
 *
 * <p>可跟单的前提是「能看到」——复用 {@link RequirementService#requireVisible(long)}，因此厂商
 * 越权跟单会在进入本方法前就 403；终态（含已合并来源）按契约给 409 REQUIREMENT_CLOSED。</p>
 */
@Service
public class RequirementFollowService {

    private final RequirementService requirements;
    private final RequirementFollowQuery follows;
    private final NotificationWriter notifications;
    private final RequirementStateMachine stateMachine;

    public RequirementFollowService(RequirementService requirements,
                                    RequirementFollowQuery follows,
                                    NotificationWriter notifications,
                                    RequirementStateMachine stateMachine) {
        this.requirements = requirements;
        this.follows = follows;
        this.notifications = notifications;
        this.stateMachine = stateMachine;
    }

    /**
     * 跟单：201 新建 / 200 已跟单 / 409 终态。
     *
     * <p>已合并的来源也算终态：{@code merged_into_id} 非空说明内容已搬到目标需求，继续在源需求上
     * 跟单只会让运营看到一份没人处理的名单。</p>
     */
    @Transactional
    public FollowResult follow(long requirementId, FollowRequest request) {
        DataScope scope = TenantContext.require();
        RequirementEntity entity = requirements.requireVisible(requirementId);
        requireInScope(requirementId);
        if (entity.getMergedIntoId() != null) {
            throw RequirementErrors.requirementClosed(
                    requirementId, "MERGED");
        }
        if (stateMachine.isTerminalAcrossTypes(entity.getStatus()) || "MERGED".equals(entity.getStatus())) {
            throw RequirementErrors.requirementClosed(
                    requirementId, entity.getStatus());
        }
        String note = normalizeNote(request == null ? null : request.note());
        Long orgId = follows.orgOf(scope.userId());
        int created = follows.insert(requirementId, scope.userId(), orgId, note);
        RequirementFollowQuery.Stats stats = follows.stats(requirementId);
        if (created > 0) {
            notifyFollowed(entity, scope.userId());
        }
        return new FollowResult(requirementId, stats.followCount(), stats.followedByMe(), created > 0);
    }

    /**
     * 写路径的可见性兜底：Java 侧 vendorVisible 与列表 SQL scope 不一致时（厂商 × 无厂商需求），
     * 用同一条 scope 谓词拒绝，避免「列表里看不到、跟单能写」的越权行。
     */
    private void requireInScope(long requirementId) {
        if (follows.visible(requirementId)) {
            return;
        }
        throw RequirementErrors.outOfScope("需求", requirementId);
    }

    /** 取消跟单：删到行返回 204，本来没跟返回 404（契约 §3.2）。 */
    @Transactional
    public void unfollow(long requirementId) {
        DataScope scope = TenantContext.require();
        requirements.requireVisible(requirementId);
        requireInScope(requirementId);
        if (follows.delete(requirementId, scope.userId()) == 0) {
            throw RequirementErrors.notFound("跟单", requirementId);
        }
    }

    /** 跟单明细 + 组织分布；page 从 1 开始，size 夹在 1..100。 */
    @Transactional(readOnly = true)
    public RequirementFollowQuery.FollowerPage followers(long requirementId, int page, int size) {
        requirements.requireVisible(requirementId);
        requireInScope(requirementId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return follows.page(requirementId, safePage, safeSize);
    }

    /**
     * 通知 reporter 与 owner（契约 §3.2），跳过跟单人自己，按「收件人 + 天」幂等。
     *
     * <p>{@link NotificationWriter} 是 best-effort 的 REQUIRES_NEW 写入，通知失败不能让跟单回滚；
     * 幂等判断在读侧（{@link RequirementFollowQuery#notifiedToday}），因为通知表没有幂等键列。</p>
     */
    private void notifyFollowed(RequirementEntity entity, long followerId) {
        String followerName = follows.nameOf(followerId);
        String who = followerName == null || followerName.isBlank() ? "有人" : followerName;
        String content = who + " 也表示需要「" + entity.getTitle() + "」";
        notifyOnce(entity, entity.getReporterId(), followerId, content);
        notifyOnce(entity, entity.getOwnerUserId(), followerId, content);
    }

    private void notifyOnce(RequirementEntity entity, Long recipientId, long followerId,
                            String content) {
        if (recipientId == null || recipientId <= 0 || recipientId == followerId) {
            return;
        }
        if (follows.notifiedToday(entity.getId(), recipientId)) {
            return;
        }
        notifications.send(recipientId, "REQUIREMENT_FOLLOWED", "有人跟单",
                content, RequirementService.BIZ_TYPE, entity.getId());
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // 列宽 255，超长截断而不是 500：备注是用户输入，不该让整个跟单失败。
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }
}
