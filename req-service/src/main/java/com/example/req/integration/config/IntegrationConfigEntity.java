package com.example.req.integration.config;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一类外部系统的一条接入配置（表 {@code integration_config}，见 V8）。
 *
 * <p>几个刻意的选择：</p>
 * <ul>
 *   <li>{@code system}/{@code authType} 存 String 而不是 {@code @Enumerated}：数据库列是
 *       VARCHAR，Hibernate 6/7 会把 STRING 枚举按 MySQL {@code enum(...)} 校验，DDL 与实体
 *       就会对不上。枚举语义由 {@link IntegrationSystem} 在服务层保证。</li>
 *   <li>密文列是 {@code VARBINARY(1024)}，用 {@code @JdbcTypeCode(VARBINARY)} 固定映射，
 *       避免 Hibernate 把 {@code byte[]} 默认当 BLOB 而 validate 失败。</li>
 *   <li>{@code activeKey} 只在启用时写 {@code ENABLED}，配合唯一键
 *       {@code uk_cfg_active(tenant_id, system, active_key)} 保证「同一系统只有一条启用」，
 *       停用的行是 NULL（MySQL 唯一键允许多个 NULL）。</li>
 * </ul>
 */
@Entity
@Table(name = "integration_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_cfg_active",
                columnNames = {"tenant_id", "system", "active_key"}))
public class IntegrationConfigEntity {

    /** 启用行在 active_key 上的固定值；停用行为 NULL。 */
    public static final String ACTIVE = "ENABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    // `system` 是 MySQL 保留字（MySQL 9 直接拒绝未加引号的列名），DDL 与 ORM 都必须加反引号。
    @Column(name = "`system`", nullable = false, length = 16)
    private String system;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    /** NONE / BASIC / TOKEN / PRIVATE_TOKEN */
    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType;

    @Column(name = "username", length = 128)
    private String username;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "credential_enc", length = 1024)
    private byte[] credentialEnc;

    @Column(name = "credential_last4", length = 8)
    private String credentialLast4;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "secret_enc", length = 1024)
    private byte[] secretEnc;

    @Column(name = "secret_last4", length = 8)
    private String secretLast4;

    @Column(name = "extra_json", columnDefinition = "TEXT")
    private String extraJson;

    @Column(name = "enabled", nullable = false)
    private Integer enabled = 0;

    @Column(name = "active_key", length = 8)
    private String activeKey;

    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs = 5000;

    @Column(name = "last_test_at")
    private LocalDateTime lastTestAt;

    @Column(name = "last_test_ok")
    private Integer lastTestOk;

    @Column(name = "last_test_message", length = 300)
    private String lastTestMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (enabled == null) {
            enabled = 0;
        }
        if (timeoutMs == null) {
            timeoutMs = 5000;
        }
        syncActiveKey();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
        syncActiveKey();
    }

    /**
     * 启用态与 {@code active_key} 必须同源：只有在启用时写 {@code ENABLED}，
     * 唯一键 {@code uk_cfg_active(tenant_id, system, active_key)} 才挡得住「同系统两条启用」。
     * 由实体自己维护，调用方无需记得同时改两个字段。
     */
    void syncActiveKey() {
        activeKey = (enabled != null && enabled == 1) ? ACTIVE : null;
    }

    /** 是否启用：唯一的判据，禁止各处再比字符串。 */
    public boolean isActive() {
        return enabled != null && enabled == 1 && ACTIVE.equals(activeKey);
    }

    /** 供服务层使用的类型化视图；数据库里存的是 {@link #getSystem()} 的字符串。 */
    public IntegrationSystem systemEnum() {
        return IntegrationSystem.of(system).orElse(null);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public byte[] getCredentialEnc() { return credentialEnc; }
    public void setCredentialEnc(byte[] credentialEnc) { this.credentialEnc = credentialEnc; }
    public String getCredentialLast4() { return credentialLast4; }
    public void setCredentialLast4(String credentialLast4) { this.credentialLast4 = credentialLast4; }
    public byte[] getSecretEnc() { return secretEnc; }
    public void setSecretEnc(byte[] secretEnc) { this.secretEnc = secretEnc; }
    public String getSecretLast4() { return secretLast4; }
    public void setSecretLast4(String secretLast4) { this.secretLast4 = secretLast4; }
    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
    public LocalDateTime getLastTestAt() { return lastTestAt; }
    public void setLastTestAt(LocalDateTime lastTestAt) { this.lastTestAt = lastTestAt; }
    public Integer getLastTestOk() { return lastTestOk; }
    public void setLastTestOk(Integer lastTestOk) { this.lastTestOk = lastTestOk; }
    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
