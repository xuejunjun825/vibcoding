package com.example.req.project;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.sys.SysDb;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目组的最小读写（r41）。
 *
 * <p>刻意<b>不是</b>一套项目组管理后台：没有迭代、没有里程碑、没有成员容量。设计只要求它回答
 * 一个问题——「这个需求的评审人应该从哪些人里选」，所以接口只有「列项目 / 建项目 / 改名册 /
 * 加成员 / 移成员」五件事。</p>
 *
 * <p>为什么用 {@link SysDb} 手写 SQL 而不是 JPA 仓储：本包的两张表没有实体映射（不需要乐观锁、
 * 没有状态机、没有级联），而 Hibernate 的 {@code ddl-auto=validate} 会按列类型码逐列比对
 * TINYINT/INT（见 V10 迁移头注释的踩坑记录）。手写 SQL 让「加一列」只需改迁移与这条 SQL，
 * 不必同步维护一个只做 CRUD 的实体。</p>
 *
 * <p>可见性：项目按 {@code tenant_id} + {@code domain_id} 收口——厂商与受限域账号只看得见自己
 * 域下的项目（{@link DataScope#canSeeDomain}）。{@code domain_id} 为空的项目是「跨域项目」，
 * 对同租户所有内部账号可见；成员名册本身不改变可见性，它只决定「谁能被选为评审人」。</p>
 */
@Service
public class ProjectService {

    /** 成员角色（设计 §4.1）。非法值 400 {@code INVALID_ROLE_IN_PROJECT} + detail.allowed[]。 */
    public static final List<String> ROLES = List.of("PO", "PM", "DEV", "QA", "SME");

    private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED");

    private final SysDb db;
    private final AuditService audit;

    public ProjectService(SysDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 项目

    @Transactional(readOnly = true)
    public List<ProjectViews.ProjectView> list(String status) {
        DataScope scope = TenantContext.require();
        StringBuilder sql = new StringBuilder("SELECT p.id, p.code, p.name, p.domain_id,"
                + " p.owner_user_id, p.status, p.created_at FROM project p"
                + " WHERE p.tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(scope.tenantId());
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) {
                throw new ApiException(400, "INVALID_STATUS", "status 只能是 ACTIVE / ARCHIVED",
                        Map.of("allowed", List.copyOf(STATUSES)));
            }
            sql.append(" AND p.status = ?");
            params.add(normalized);
        }
        sql.append(" ORDER BY p.id");
        List<ProjectViews.ProjectView> result = new ArrayList<>();
        for (Object[] row : db.rows(sql.toString(), params.toArray())) {
            ProjectViews.ProjectView view = toView(row);
            if (scope.seesAllVendors() || view.domainId() == null
                    || scope.canSeeDomain(view.domainId())) {
                result.add(view);
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ProjectViews.ProjectDetail detail(long id) {
        return new ProjectViews.ProjectDetail(get(id), members(id));
    }

    @Transactional(readOnly = true)
    public ProjectViews.ProjectView get(long id) {
        DataScope scope = TenantContext.require();
        Optional<Object[]> row = db.row("SELECT p.id, p.code, p.name, p.domain_id, p.owner_user_id,"
                + " p.status, p.created_at FROM project p WHERE p.id = ? AND p.tenant_id = ?",
                id, scope.tenantId());
        if (row.isEmpty()) {
            throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在或不在你的数据范围内",
                    Map.of("projectId", id));
        }
        ProjectViews.ProjectView view = toView(row.get());
        if (!scope.seesAllVendors() && view.domainId() != null
                && !scope.canSeeDomain(view.domainId())) {
            throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在或不在你的数据范围内",
                    Map.of("projectId", id));
        }
        return view;
    }

    @Transactional
    public ProjectViews.ProjectView create(ProjectViews.CreateProject request) {
        DataScope scope = TenantContext.require();
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "code 必填", null);
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "name 必填", null);
        }
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9-]{1,31}")) {
            throw new ApiException(400, "VALIDATION_FAILED", "code 只能是大写字母/数字/短横线（2-32 位）",
                    Map.of("field", "code"));
        }
        long id;
        try {
            id = db.insert("INSERT INTO project (tenant_id, code, name, domain_id, owner_user_id,"
                            + " status, created_by, updated_by) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                    scope.tenantId(), code, request.name().trim(), request.domainId(),
                    request.ownerUserId(), scope.userId(), scope.userId());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "PROJECT_CODE_TAKEN", "项目短码已被占用",
                    Map.of("code", code));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("name", request.name().trim());
        audit.record("PROJECT_CREATE", "project", id, detail);
        return get(id);
    }

    @Transactional
    public ProjectViews.ProjectView update(long id, ProjectViews.UpdateProject request) {
        DataScope scope = TenantContext.require();
        ProjectViews.ProjectView current = get(id);
        String name = request == null || request.name() == null || request.name().isBlank()
                ? current.name() : request.name().trim();
        Long owner = request == null || request.ownerUserId() == null
                ? current.ownerUserId() : request.ownerUserId();
        String status = request == null || request.status() == null || request.status().isBlank()
                ? current.status() : request.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new ApiException(400, "INVALID_STATUS", "status 只能是 ACTIVE / ARCHIVED",
                    Map.of("allowed", List.copyOf(STATUSES)));
        }
        db.update("UPDATE project SET name = ?, owner_user_id = ?, status = ?, updated_by = ?"
                + " WHERE id = ? AND tenant_id = ?", name, owner, status, scope.userId(), id,
                scope.tenantId());
        audit.record("PROJECT_UPDATE", "project", id, Map.of("name", name, "status", status));
        return get(id);
    }

    // ------------------------------------------------------------------ 成员

    @Transactional(readOnly = true)
    public List<ProjectViews.MemberView> members(long projectId) {
        get(projectId);
        List<ProjectViews.MemberView> result = new ArrayList<>();
        for (Object[] row : db.rows("SELECT m.id, m.project_id, m.user_id, u.username,"
                + " m.role_in_project, m.active, m.joined_at FROM project_member m"
                + " JOIN sys_user u ON u.id = m.user_id AND u.tenant_id = m.tenant_id"
                + " WHERE m.tenant_id = ? AND m.project_id = ? ORDER BY m.active DESC, m.id",
                TenantContext.require().tenantId(), projectId)) {
            result.add(toMember(row));
        }
        return result;
    }

    /** 在册成员的用户 id（评审人来源；不含已退出的人）。 */
    @Transactional(readOnly = true)
    public List<Long> memberUserIds(long projectId) {
        List<Long> ids = new ArrayList<>();
        for (Object value : db.scalars("SELECT user_id FROM project_member"
                + " WHERE tenant_id = ? AND project_id = ? AND active = 1 ORDER BY id",
                TenantContext.require().tenantId(), projectId)) {
            ids.add(SysDb.asLong(value));
        }
        return ids;
    }

    /**
     * 加成员：同名册里的人「重新加入」必须复用同一行（uk_project_member），因此是 upsert 而不是
     * INSERT——重复成员是 200/已存在，不是 409 新行。设计 §4.1 的 unique key 决定了这条实现。
     */
    @Transactional
    public ProjectViews.MemberView addMember(long projectId, ProjectViews.AddMember request) {
        DataScope scope = TenantContext.require();
        get(projectId);
        if (request == null || request.userId() == null || request.userId() <= 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "userId 必填", null);
        }
        String role = request.roleInProject() == null || request.roleInProject().isBlank()
                ? "DEV" : request.roleInProject().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw new ApiException(400, "INVALID_ROLE_IN_PROJECT",
                    "roleInProject 只能是 " + String.join(" / ", ROLES),
                    Map.of("allowed", ROLES));
        }
        Optional<Object[]> user = db.row("SELECT id FROM sys_user WHERE id = ? AND tenant_id = ?",
                request.userId(), scope.tenantId());
        if (user.isEmpty()) {
            throw new ApiException(404, "USER_NOT_FOUND", "用户不存在或不在本租户",
                    Map.of("userId", request.userId()));
        }
        Optional<Object[]> existing = db.row("SELECT id FROM project_member"
                + " WHERE project_id = ? AND user_id = ?", projectId, request.userId());
        if (existing.isPresent()) {
            db.update("UPDATE project_member SET role_in_project = ?, active = 1, updated_by = ?"
                    + " WHERE id = ?", role, scope.userId(), SysDb.asLong(existing.get()[0]));
        } else {
            db.insert("INSERT INTO project_member (tenant_id, project_id, user_id, role_in_project,"
                            + " active, created_by, updated_by) VALUES (?,?,?,?,1,?,?)",
                    scope.tenantId(), projectId, request.userId(), role, scope.userId(),
                    scope.userId());
        }
        audit.record("PROJECT_MEMBER_ADD", "project", projectId,
                Map.of("userId", request.userId(), "roleInProject", role));
        return member(projectId, request.userId());
    }

    /**
     * 移成员：置 {@code active = 0} 而不是删行。
     *
     * <p>理由与 uk_project_member 同源——删行后重新加入会拿到新 id，历史审计（
     * {@code PROJECT_MEMBER_ADD}）就指不回同一行了；而「已退出的成员还算不算评审人」必须能按
     * {@code active} 回答（评审人快照已经存进 pdr_review.reviewer_ids，名册变动不影响在评的单）。
     */
    @Transactional
    public void removeMember(long projectId, long userId) {
        DataScope scope = TenantContext.require();
        get(projectId);
        int updated = db.update("UPDATE project_member SET active = 0, updated_by = ?"
                + " WHERE tenant_id = ? AND project_id = ? AND user_id = ? AND active = 1",
                scope.userId(), scope.tenantId(), projectId, userId);
        if (updated == 0) {
            throw new ApiException(404, "MEMBER_NOT_FOUND", "该用户不在项目组在册成员中",
                    Map.of("projectId", projectId, "userId", userId));
        }
        audit.record("PROJECT_MEMBER_REMOVE", "project", projectId, Map.of("userId", userId));
    }

    /** 单个在册成员（含已退出，供 addMember 回读）。 */
    @Transactional(readOnly = true)
    public ProjectViews.MemberView member(long projectId, long userId) {
        Optional<Object[]> row = db.row("SELECT m.id, m.project_id, m.user_id, u.username,"
                + " m.role_in_project, m.active, m.joined_at FROM project_member m"
                + " JOIN sys_user u ON u.id = m.user_id AND u.tenant_id = m.tenant_id"
                + " WHERE m.tenant_id = ? AND m.project_id = ? AND m.user_id = ?",
                TenantContext.require().tenantId(), projectId, userId);
        if (row.isEmpty()) {
            throw new ApiException(404, "MEMBER_NOT_FOUND", "该用户不在项目组中",
                    Map.of("projectId", projectId, "userId", userId));
        }
        return toMember(row.get());
    }

    /** 需求挂的项目（没有则空）：PDR 发起时决定评审人来源是 PROJECT 还是 FALLBACK。 */
    @Transactional(readOnly = true)
    public Optional<Long> projectIdOfRequirement(long requirementId) {
        return db.row("SELECT project_id FROM requirement WHERE id = ? AND tenant_id = ?",
                requirementId, TenantContext.require().tenantId())
                .map(row -> SysDb.asNullableLong(row[0]));
    }

    private static ProjectViews.ProjectView toView(Object[] row) {
        return new ProjectViews.ProjectView(
                SysDb.asLong(row[0]),
                SysDb.asString(row[1]),
                SysDb.asString(row[2]),
                SysDb.asNullableLong(row[3]),
                SysDb.asNullableLong(row[4]),
                SysDb.asString(row[5]),
                toTime(row[6]));
    }

    private static ProjectViews.MemberView toMember(Object[] row) {
        return new ProjectViews.MemberView(
                SysDb.asLong(row[0]),
                SysDb.asLong(row[1]),
                SysDb.asLong(row[2]),
                SysDb.asString(row[3]),
                SysDb.asString(row[4]),
                SysDb.asBoolean(row[5]),
                toTime(row[6]));
    }

    private static LocalDateTime toTime(Object value) {
        return value instanceof LocalDateTime time ? time
                : value instanceof java.sql.Timestamp stamp ? stamp.toLocalDateTime() : null;
    }
}
