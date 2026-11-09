package com.example.req.integration.artifact;

import org.springframework.stereotype.Service;

import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationProperties;
import com.example.req.integration.config.IntegrationSystem;

/**
 * 制品检索用例：解析「唯一启用的 Nexus 配置 → 适配器 → 夹紧 limit」。
 *
 * <p>未启用 Nexus 是 409 {@code INTEGRATION_DISABLED}（管理动作，不是空列表）：让用户
 * 明确知道「没配」而不是「配了但没有制品」。网络/解析失败则是
 * {@code degraded=true} + 空列表——两者在页面上是两种提示，不能混。</p>
 */
@Service
public class ArtifactSearchService {

    /** 与 PRD AC-7.1 一致：一次最多 50 条。 */
    public static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 20;

    private final AdapterRegistry adapterRegistry;
    private final NexusAdapter nexusAdapter;
    private final IntegrationProperties properties;

    public ArtifactSearchService(AdapterRegistry adapterRegistry,
                                 NexusAdapter nexusAdapter,
                                 IntegrationProperties properties) {
        this.adapterRegistry = adapterRegistry;
        this.nexusAdapter = nexusAdapter;
        this.properties = properties;
    }

    /** 设计声明的三参入口；version 由页面拼进关键字时可为空。 */
    public ArtifactViews.SearchResult search(String query, String repository, int limit) {
        return search(query, repository, null, limit);
    }

    /** 带版本过滤的检索（Nexus 侧过滤 + 适配器本地精确过滤）。 */
    public ArtifactViews.SearchResult search(String query, String repository, String version, int limit) {
        IntegrationConfigEntity config = adapterRegistry.require(IntegrationSystem.NEXUS);
        int effectiveLimit = clamp(limit);
        return nexusAdapter.searchResult(config, query, repository, version, effectiveLimit);
    }

    private int clamp(int requested) {
        int configured = properties == null ? MAX_LIMIT : properties.getArtifactSearchLimit();
        int cap = Math.max(1, Math.min(configured, MAX_LIMIT));
        int effective = requested <= 0 ? DEFAULT_LIMIT : requested;
        return Math.min(effective, cap);
    }
}
