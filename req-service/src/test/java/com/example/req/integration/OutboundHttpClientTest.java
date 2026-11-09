package com.example.req.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import com.example.platform.common.notify.NotificationWriter;
import com.example.req.integration.adapter.IntegrationAdapter;
import com.example.req.integration.config.IntegrationConfigRepository;
import com.example.req.integration.config.IntegrationProperties;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.notify.IntegrationNotifier;
import com.example.req.integration.outbound.OutboundHttpClient;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.outbound.OutboundResponse;
import com.example.req.integration.outbound.RetryPolicy;
import com.example.req.integration.security.SsrfGuard;
import com.example.req.integration.store.IntegrationSyncLogEntity;
import com.example.req.integration.store.IntegrationSyncLogRepository;
import com.example.req.integration.sync.SyncLogWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC-2 的可执行证据：真实 HTTP 往返（JDK HttpServer，不起 Spring）。
 * 覆盖成功、每次尝试一行日志、单次读不重试、重试耗尽、超时、跨主机重定向、SSRF 拒绝、连接失败。
 */
class OutboundHttpClientTest {

    private HttpServer server;
    private int port;
    private final List<IntegrationSyncLogEntity> logs = new CopyOnWriteArrayList<>();
    private OutboundHttpClient client;

    private static IntegrationProperties props(String allowHost) {
        IntegrationProperties properties = new IntegrationProperties();
        properties.setEgressAllowHosts(List.of(allowHost));
        properties.setConnectTimeoutMs(2000);
        properties.setReadTimeoutMs(5000);
        return properties;
    }

    private static IntegrationProperties openProps() {
        IntegrationProperties properties = new IntegrationProperties();
        properties.setEgressAllowHosts(List.of());
        properties.setConnectTimeoutMs(2000);
        properties.setReadTimeoutMs(5000);
        return properties;
    }

    @BeforeEach
    void setUp() throws IOException {
        logs.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
        client = newClient(props("127.0.0.1"));
    }

    private OutboundHttpClient newClient(IntegrationProperties properties) {
        IntegrationSyncLogRepository logRepository = mock(IntegrationSyncLogRepository.class);
        when(logRepository.search(anyLong(), any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        when(logRepository.save(any(IntegrationSyncLogEntity.class))).thenAnswer(invocation -> {
            IntegrationSyncLogEntity entity = invocation.getArgument(0);
            logs.add(entity);
            return entity;
        });
        IntegrationConfigRepository configRepository = mock(IntegrationConfigRepository.class);
        when(configRepository.findFirstByTenantIdAndSystemAndEnabledOrderByIdAsc(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        SyncLogWriter writer = new SyncLogWriter(logRepository, configRepository);
        NotificationWriter notificationWriter = mock(NotificationWriter.class);
        IntegrationNotifier notifier = new IntegrationNotifier(notificationWriter);
        return new OutboundHttpClient(new SsrfGuard(properties), writer, notifier,
                new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(String path, ExchangeHandler handler) {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception ex) {
                exchange.close();
            }
        });
    }

    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private OutboundRequest request(String path) {
        return OutboundRequest.builder(1L, IntegrationSystem.JENKINS, "test.connection",
                        org.springframework.http.HttpMethod.GET,
                        "http://127.0.0.1:" + port + path)
                .correlationId(OutboundRequest.newCorrelationId())
                .header("Authorization", "Bearer unit-only-secret")
                .biz("INTEGRATION", 9L)
                .build();
    }

    @Test
    @DisplayName("成功：单次尝试、一行 SUCCESS 日志、摘要不含凭据")
    void success() {
        handle("/ok", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        OutboundResponse response = client.exchange(request("/ok"));
        assertTrue(response.successful());
        assertEquals(200, response.httpStatus());
        assertEquals(1, response.attempts());
        assertEquals(1, logs.size());
        IntegrationSyncLogEntity log = logs.get(0);
        assertEquals(IntegrationSyncLogEntity.RESULT_SUCCESS, log.getResult());
        assertEquals(0, log.getRetryCount());
        assertFalse(log.getRequestSummary().contains("unit-only-secret"), log.getRequestSummary());
        assertFalse(log.getTargetUrl().contains("token="));
        assertEquals("OUT", log.getDirection());
    }

    @Test
    @DisplayName("后台写：500 两次后成功，总共三次尝试且每尝试一行日志")
    void retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        handle("/flaky", exchange -> {
            int n = calls.incrementAndGet();
            respond(exchange, n < 3 ? 500 : 200, "{\"n\":" + n + "}");
        });
        OutboundResponse response = client.exchangeWithRetry(request("/flaky").withRetry(RetryPolicy.of(3)));
        assertTrue(response.successful());
        assertEquals(3, response.attempts());
        assertEquals(3, calls.get());
        assertEquals(3, logs.size());
        assertEquals(List.of(0, 1, 2), logs.stream().map(IntegrationSyncLogEntity::getRetryCount).toList());
    }

    @Test
    @DisplayName("重试耗尽：RETRY_EXHAUSTED、尝试次数=策略上限、每行日志保留")
    void retryExhausted() {
        AtomicInteger calls = new AtomicInteger();
        handle("/down", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"down\"}");
        });
        OutboundResponse response = client.exchangeWithRetry(request("/down").withRetry(RetryPolicy.of(2)));
        assertEquals(2, calls.get());
        assertEquals(2, response.attempts());
        assertEquals(OutboundResponse.RETRY_EXHAUSTED, response.errorCode());
        assertEquals(2, logs.size());
        assertEquals(IntegrationSyncLogEntity.RESULT_RETRY_EXHAUSTED, logs.get(1).getResult());
    }

    @Test
    @DisplayName("交互读 exchange：即使请求带 standard 策略也只发一次")
    void singleShotDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        handle("/once", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 500, "{}");
        });
        OutboundResponse response = client.exchange(request("/once").withRetry(RetryPolicy.standard()));
        assertEquals(1, calls.get());
        assertEquals(1, response.attempts());
        assertEquals("HTTP_500", response.errorCode());
    }

    @Test
    @DisplayName("超时：readTimeout 生效，判 TIMEOUT 且可重试")
    void timeout() {
        handle("/slow", exchange -> {
            Thread.sleep(600);
            respond(exchange, 200, "{}");
        });
        OutboundRequest slow = OutboundRequest.builder(1L, IntegrationSystem.SONAR, "test.connection",
                        org.springframework.http.HttpMethod.GET, "http://127.0.0.1:" + port + "/slow")
                .correlationId(OutboundRequest.newCorrelationId())
                .readTimeout(Duration.ofMillis(150))
                .build();
        OutboundResponse response = client.exchange(slow);
        assertEquals(OutboundResponse.TIMEOUT, response.errorCode());
        assertTrue(response.transportFailure());
    }

    @Test
    @DisplayName("跨主机重定向：REDIRECT_BLOCKED，不跟随")
    void crossHostRedirectBlocked() {
        handle("/jump", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://evil.example/steal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        OutboundResponse response = client.exchange(request("/jump"));
        assertEquals(OutboundResponse.REDIRECT_BLOCKED, response.errorCode());
        assertEquals(302, response.httpStatus());
    }

    @Test
    @DisplayName("SSRF：未加白名单的环回地址在建立连接前拒绝，服务端一次也不收到")
    void ssrfBlockedBeforeConnect() {
        AtomicInteger calls = new AtomicInteger();
        handle("/secret", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        OutboundHttpClient guarded = newClient(openProps());
        OutboundResponse response = guarded.exchange(request("/secret"));
        assertEquals(OutboundResponse.SSRF_BLOCKED, response.errorCode());
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("连接拒绝：TRANSPORT_ERROR，且日志仍落一行")
    void connectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OutboundRequest dead = OutboundRequest.builder(1L, IntegrationSystem.NEXUS, "test.connection",
                        org.springframework.http.HttpMethod.GET, "http://127.0.0.1:" + closedPort + "/x")
                .correlationId(OutboundRequest.newCorrelationId())
                .build();
        OutboundResponse response = client.exchange(dead);
        assertEquals(OutboundResponse.TRANSPORT_ERROR, response.errorCode());
        assertTrue(response.transportFailure());
        assertEquals(1, logs.size());
    }
}
