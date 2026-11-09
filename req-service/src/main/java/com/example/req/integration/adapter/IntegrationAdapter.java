package com.example.req.integration.adapter;

import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationSystem;

/**
 * 四类系统的统一适配器接口：只做「配置 → 协议请求 → 统一结果」的映射。
 *
 * <p>实现类不得自建 RestClient/RestTemplate，所有出站必须走
 * {@code OutboundHttpClient}——SSRF、超时、重试、脱敏、日志只在那一处实现。</p>
 */
public interface IntegrationAdapter {

    IntegrationSystem system();

    AdapterResult testConnection(IntegrationConfigEntity config);
}
