package com.example.req.integration.config;

import com.example.platform.common.crypto.AesGcmCodec;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 集成凭据的加解密入口，也是「凭据只写不读」的唯一落点。
 *
 * <p>密钥来源顺序（ADR-T7）：</p>
 * <ol>
 *   <li>{@code platform.integration.credential-keys}（密钥环，支持轮换）</li>
 *   <li>{@code platform.integration.credential-key}（默认绑定 {@code INTEGRATION_CREDENTIAL_KEY}）</li>
 *   <li>{@code platform.master-key}（默认绑定 {@code PLATFORM_MASTER_KEY}，让既有环境零改动起得来）</li>
 * </ol>
 *
 * <p>三者皆空时**拒绝启动**：一个没有密钥的集成模块会把凭据以不可解密的形式写进库，
 * 或者更糟——退化成明文。宁可起不来，也不要静默降级。启动日志只打印来源与版本号，
 * 绝不打印密钥本身。</p>
 */
@Component
public class IntegrationCrypto {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCrypto.class);

    private final AesGcmCodec codec;
    private final String keySource;

    public IntegrationCrypto(IntegrationProperties properties,
                             @Value("${platform.master-key:}") String masterKey) {
        String ring = trim(properties.getCredentialKeys());
        String single = trim(properties.getCredentialKey());
        String version = trim(properties.getCredentialKeyVersion());
        if (!ring.isEmpty()) {
            this.codec = AesGcmCodec.ofRing(ring, version.isEmpty() ? AesGcmCodec.DEFAULT_VERSION : version);
            this.keySource = "platform.integration.credential-keys";
        } else if (!single.isEmpty()) {
            this.codec = new AesGcmCodec(version.isEmpty() ? AesGcmCodec.DEFAULT_VERSION : version,
                    java.util.Map.of(version.isEmpty() ? AesGcmCodec.DEFAULT_VERSION : version, single));
            this.keySource = "INTEGRATION_CREDENTIAL_KEY";
        } else if (!trim(masterKey).isEmpty()) {
            this.codec = AesGcmCodec.ofKey(trim(masterKey));
            this.keySource = "PLATFORM_MASTER_KEY";
        } else {
            this.codec = AesGcmCodec.ofKey("");
            this.keySource = "NONE";
        }
    }

    /**
     * 启动即校验：没有可用密钥直接让容器启动失败，错误消息点名两个变量，
     * 运维不需要读代码就知道该配哪个。
     */
    @PostConstruct
    public void validateKey() {
        if (!codec.hasKey()) {
            throw new IllegalStateException(
                    "集成凭据密钥未配置：请设置 INTEGRATION_CREDENTIAL_KEY（或 PLATFORM_MASTER_KEY 作为回落），"
                            + "否则 integration_config 的凭据无法加密存储");
        }
        log.info("集成凭据密钥已就绪：来源={} 版本={}", keySource, codec.currentVersion());
    }

    public byte[] encode(String plain) {
        return codec.encode(plain);
    }

    public String decode(byte[] enc) {
        return codec.decode(enc);
    }

    /** {@code ****abcd} 形态的展示值；空值返回 null，绝不返回可逆内容。 */
    public String mask(String plain) {
        String last4 = last4(plain);
        return last4 == null ? null : "****" + last4;
    }

    /** 密钥末四位，供「已配置 ****abcd」展示与轮换比对。 */
    public String last4(String plain) {
        return codec.last4(plain);
    }

    /** 诊断用：当前密钥来源（不含密钥）。 */
    public String keySource() {
        return keySource;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
