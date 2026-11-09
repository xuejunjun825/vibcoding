package com.example.req.integration.config;

import java.util.Locale;
import java.util.Optional;

/**
 * 四类被集成系统。名称同时是数据库 {@code integration_config.system} 的取值与
 * 适配器注册表的键，因此**只在这里定义一次**：任何地方都不允许再写字符串字面量
 * "JENKINS" 之类的系统名。
 */
public enum IntegrationSystem {

    JENKINS("Jenkins"),
    GITLAB("GitLab"),
    SONAR("Sonar"),
    NEXUS("Nexus");

    private final String label;

    IntegrationSystem(String label) {
        this.label = label;
    }

    /** 展示名（前端字典以代码为准，这里只用于后端日志与消息）。 */
    public String label() {
        return label;
    }

    /** 大小写不敏感解析；未知系统返回空，由调用方转成 400。 */
    public static Optional<IntegrationSystem> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (IntegrationSystem system : values()) {
            if (system.name().equals(normalized)) {
                return Optional.of(system);
            }
        }
        return Optional.empty();
    }

    public static IntegrationSystem require(String code) {
        return of(code).orElseThrow(() -> new IllegalArgumentException("未知集成系统：" + code));
    }
}
