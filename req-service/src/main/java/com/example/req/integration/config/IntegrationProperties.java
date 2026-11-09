package com.example.req.integration.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M7 集成的运维旋钮（前缀 {@code platform.integration}）。
 *
 * <p>这里只放「运维需要在不改代码的情况下调整」的值：超时、轮询节奏、准入预算、限流与
 * 出站白名单。业务语义（哪些项锁定、状态机怎么迁移）属于代码常量，不放配置——
 * 配置能改的只能是节奏，不能是红线。</p>
 */
@Component
@ConfigurationProperties(prefix = "platform.integration")
public class IntegrationProperties {

    /**
     * 单密钥形态的凭据密钥（默认绑定 {@code INTEGRATION_CREDENTIAL_KEY}）。
     * 与 {@link #credentialKeys} 二选一：密钥环非空时优先用环。
     */
    private String credentialKey = "";

    /** 单密钥写入 {@code credentialKey} 时使用的密钥版本号（决定密文前缀）。 */
    private String credentialKeyVersion = "v1";

    /** 密钥环，形如 {@code v1=...,v2=...}；为空则回落到 credentialKey / PLATFORM_MASTER_KEY。 */
    private String credentialKeys = "";

    /** 出站连接超时（毫秒）。 */
    private int connectTimeoutMs = 3000;

    /** 出站读取超时（毫秒）；配置的 {@code timeout_ms} 不得超过它。 */
    private int readTimeoutMs = 10000;

    /** 准入评估预算（毫秒）：超过即 fail-closed，绝不为等外部系统而拖住提交。 */
    private int admissionBudgetMs = 500;

    /** 活动构建轮询间隔（秒），fixedDelay 执行，天然不重叠。 */
    private int buildPollIntervalSeconds = 10;

    /** 构建超时（分钟）：超时置 ABORTED 并转缺陷。 */
    private int buildTimeoutMinutes = 120;

    /** Sonar 快照后台刷新间隔（分钟）：准入零外呼，快照必须提前拉好。 */
    private int sonarRefreshMinutes = 30;

    /** webhook 每个 configId 的令牌桶速率（req/s）。 */
    private int webhookRateLimitPerSecond = 20;

    /** 制品检索一次最多返回多少条（PRD 上限 50）。 */
    private int artifactSearchLimit = 50;

    /** 同步日志保留天数（仅用于查询默认窗口与后续清理任务）。 */
    private int logRetentionDays = 30;

    /**
     * 允许出站的额外主机（SSRF 白名单补充项）。内网/回环地址默认拒绝，
     * 只有这里显式列出的主机才放行——本地桩即通过此项加入。
     */
    private List<String> egressAllowHosts = new ArrayList<>();

    public String getCredentialKey() {
        return credentialKey;
    }

    public void setCredentialKey(String credentialKey) {
        this.credentialKey = credentialKey;
    }

    public String getCredentialKeyVersion() {
        return credentialKeyVersion;
    }

    public void setCredentialKeyVersion(String credentialKeyVersion) {
        this.credentialKeyVersion = credentialKeyVersion;
    }

    public String getCredentialKeys() {
        return credentialKeys;
    }

    public void setCredentialKeys(String credentialKeys) {
        this.credentialKeys = credentialKeys;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getAdmissionBudgetMs() {
        return admissionBudgetMs;
    }

    public void setAdmissionBudgetMs(int admissionBudgetMs) {
        this.admissionBudgetMs = admissionBudgetMs;
    }

    public int getBuildPollIntervalSeconds() {
        return buildPollIntervalSeconds;
    }

    public void setBuildPollIntervalSeconds(int buildPollIntervalSeconds) {
        this.buildPollIntervalSeconds = buildPollIntervalSeconds;
    }

    public int getBuildTimeoutMinutes() {
        return buildTimeoutMinutes;
    }

    public void setBuildTimeoutMinutes(int buildTimeoutMinutes) {
        this.buildTimeoutMinutes = buildTimeoutMinutes;
    }

    public int getSonarRefreshMinutes() {
        return sonarRefreshMinutes;
    }

    public void setSonarRefreshMinutes(int sonarRefreshMinutes) {
        this.sonarRefreshMinutes = sonarRefreshMinutes;
    }

    public int getWebhookRateLimitPerSecond() {
        return webhookRateLimitPerSecond;
    }

    public void setWebhookRateLimitPerSecond(int webhookRateLimitPerSecond) {
        this.webhookRateLimitPerSecond = webhookRateLimitPerSecond;
    }

    public int getArtifactSearchLimit() {
        return artifactSearchLimit;
    }

    public void setArtifactSearchLimit(int artifactSearchLimit) {
        this.artifactSearchLimit = artifactSearchLimit;
    }

    public int getLogRetentionDays() {
        return logRetentionDays;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = logRetentionDays;
    }

    public List<String> getEgressAllowHosts() {
        return egressAllowHosts;
    }

    public void setEgressAllowHosts(List<String> egressAllowHosts) {
        this.egressAllowHosts = egressAllowHosts == null ? new ArrayList<>() : egressAllowHosts;
    }
}
