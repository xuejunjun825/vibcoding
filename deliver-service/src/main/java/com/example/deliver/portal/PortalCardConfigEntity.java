package com.example.deliver.portal;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One {@code portal_card_config} row.
 *
 * <p>The table holds two different things keyed by {@code user_id}: {@code user_id = 0} is the
 * <b>role-level</b> template configuration (which of the role's cards are visible), while
 * {@code user_id = me} is my <b>personal</b> row (the pinned {@code report:<id>} keys). Callers
 * must branch on that sentinel before writing; the unique key
 * {@code uk_portal_card(tenant_id, role_code, user_id)} makes both writes idempotent.</p>
 *
 * <p>{@code updated_at} is mapped updatable so an idempotent save can move it explicitly: MySQL's
 * {@code ON UPDATE} only fires when a column value actually changes, and a config save that keeps
 * the same keys must still look touched.</p>
 */
@Entity
@Table(name = "portal_card_config")
public class PortalCardConfigEntity {

    /** The role-level sentinel: rows with this {@code user_id} are shared by the whole role. */
    public static final long ROLE_LEVEL = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private long tenantId;

    @Column(name = "role_code", nullable = false, length = 32)
    private String roleCode;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "card_keys", nullable = false, length = 200)
    private String cardKeys;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    public PortalCardConfigEntity() {
    }

    public Long getId() {
        return id;
    }

    public long getTenantId() {
        return tenantId;
    }

    public void setTenantId(long tenantId) {
        this.tenantId = tenantId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getCardKeys() {
        return cardKeys;
    }

    public void setCardKeys(String cardKeys) {
        this.cardKeys = cardKeys;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
