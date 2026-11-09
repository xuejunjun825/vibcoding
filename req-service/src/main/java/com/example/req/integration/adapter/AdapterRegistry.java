package com.example.req.integration.adapter;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationSystem;

/**
 * 按系统解析「唯一启用配置」与对应适配器。
 *
 * <p>「未配置即 409 {@code INTEGRATION_DISABLED}」只在这里判定：适配器与业务服务拿到
 * 配置之前不需要各自写一遍空判断。交互读路径用 {@link #requireEnabled(IntegrationSystem)}
 * 拿 Optional 做降级，写路径用 {@link #require(IntegrationSystem)} 直接失败。</p>
 */
@Component
public class AdapterRegistry {

    public static final String CODE_DISABLED = "INTEGRATION_DISABLED";
    public static final String CODE_ADAPTER_MISSING = "INTEGRATION_ADAPTER_MISSING";

    private final IntegrationConfigRepository configRepository;
    private final Map<IntegrationSystem, IntegrationAdapter> adapters;

    public AdapterRegistry(IntegrationConfigRepository configRepository,
                           List<IntegrationAdapter> discovered) {
        this.configRepository = configRepository;
        Map<IntegrationSystem, IntegrationAdapter> bySystem = new EnumMap<>(IntegrationSystem.class);
        if (discovered != null) {
            for (IntegrationAdapter adapter : discovered) {
                if (adapter != null && adapter.system() != null) {
                    bySystem.put(adapter.system(), adapter);
                }
            }
        }
        this.adapters = Map.copyOf(bySystem);
    }

    /** 当前租户下该系统唯一启用的配置；未启用返回空（交互读据此降级）。 */
    public Optional<IntegrationConfigEntity> requireEnabled(IntegrationSystem system) {
        if (system == null) {
            return Optional.empty();
        }
        return configRepository.enabled(system);
    }

    /** 后台任务版本：显式带 tenantId，不依赖 ThreadLocal。 */
    public Optional<IntegrationConfigEntity> findEnabled(long tenantId, IntegrationSystem system) {
        if (system == null || tenantId <= 0) {
            return Optional.empty();
        }
        return configRepository.findFirstByTenantIdAndSystemAndEnabledOrderByIdAsc(
                tenantId, system.name(), 1);
    }

    /** 写路径：未启用直接 409，消息里只说系统名，不回显配置。 */
    public IntegrationConfigEntity require(IntegrationSystem system) {
        return requireEnabled(system).orElseThrow(() -> new ApiException(409, CODE_DISABLED,
                system == null ? "集成系统未启用" : system.label() + " 集成未启用"));
    }

    public IntegrationAdapter adapter(IntegrationSystem system) {
        IntegrationAdapter adapter = system == null ? null : adapters.get(system);
        if (adapter == null) {
            throw new ApiException(500, CODE_ADAPTER_MISSING,
                    system == null ? "缺少集成系统" : "缺少 " + system.label() + " 适配器");
        }
        return adapter;
    }

    public Optional<IntegrationAdapter> find(IntegrationSystem system) {
        return system == null ? Optional.empty() : Optional.ofNullable(adapters.get(system));
    }

    /** 便于启动日志与自检：已注册的系统集合。 */
    public java.util.Set<IntegrationSystem> registered() {
        return adapters.keySet();
    }
}
