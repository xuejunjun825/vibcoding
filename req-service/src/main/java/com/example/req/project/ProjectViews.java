package com.example.req.project;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目组（r41）的请求/响应契约。
 *
 * <p>项目组只服务于一件事：PDR 评审发起时「从名册里挑评审人」。因此四个读字段之外不加任何
 * 派生量（进度、成员数、需求数都不在这里算）——那些是交付域的工作台口径，放进本包会让两个
 * 页面各自维护一份统计，数字必然分叉。</p>
 *
 * <p>成员角色只有五个（{@code PO/PM/DEV/QA/SME}，设计 §4.1）。它是「在项目里担什么角色」，
 * 与 {@code sys_role}（平台 RBAC 角色）无关：同一个人可以是 PRODUCT 角色而在某个项目里是 SME。</p>
 */
public final class ProjectViews {

    private ProjectViews() {
    }

    /** 项目（列表与详情同形）。 */
    public record ProjectView(long id, String code, String name, Long domainId, Long ownerUserId,
                               String status, LocalDateTime createdAt) {
    }

    /** 新建项目。{@code code} 是租户内唯一短码（uk_project_code），重复 409。 */
    public record CreateProject(String code, String name, Long domainId, Long ownerUserId) {
    }

    /** 更新项目：三个字段都可空，空表示不改（PUT 在本资源上是 patch 语义，见服务层注释）。 */
    public record UpdateProject(String name, Long ownerUserId, String status) {
    }

    /** 成员（带用户名的原因是评审人选择器要直接显示，不让前端再查一次用户表）。 */
    public record MemberView(long id, long projectId, long userId, String username,
                             String roleInProject, boolean active, LocalDateTime joinedAt) {
    }

    /** 新增成员：{@code roleInProject} 缺省 DEV。 */
    public record AddMember(Long userId, String roleInProject) {
    }

    /** 项目详情的成员汇总（GET /projects/{id} 只回这两个字段之外的成员，避免第二次请求）。 */
    public record ProjectDetail(ProjectView project, List<MemberView> members) {
    }
}
