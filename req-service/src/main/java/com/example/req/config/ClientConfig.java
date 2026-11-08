package com.example.req.config;

import java.time.Duration;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client used for cross-service calls ({@code http://deliver-service} resolved by the
 * load balancer).
 *
 * <p>Timeouts are short and explicit: the gate call sits inside a requirement transition, so a
 * hung deliver-service must degrade to the documented fail-closed answer instead of holding the
 * request open.</p>
 */
@Configuration
public class ClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(1500));
        return RestClient.builder().requestFactory(factory);
    }
}
