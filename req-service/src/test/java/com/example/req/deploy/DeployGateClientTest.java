package com.example.req.deploy;

import com.example.platform.contract.GateDecision;
import com.example.req.integration.DeliverGateClient;
import com.example.req.integration.InternalTokenClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨服务取数的真实 HTTP 断言（用 JDK 自带 HttpServer，不起 Spring）：
 * 批量门禁解析、缺陷计数解析、超时按预算中断且 fail-closed、内部 token 必带。
 *
 * <p>这一组是 AC-12「跨服务预算 ≤500ms」与「服务不可用不能静默放行」的可执行证据；
 * 真栈上的端到端断言由 scripts/platform-verify.sh 负责。</p>
 */
class DeployGateClientTest {

    private static final String TOKEN = "t4-test-token";

    private HttpServer server;
    private DeliverGateClient client;
    private final List<String> seenTokens = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String token = exchange.getRequestHeaders().getFirst(InternalTokenClient.TOKEN_HEADER);
            synchronized (seenTokens) {
                seenTokens.add(token == null ? "" : token);
            }
            int status = 200;
            String body;
            if (path.equals("/internal/gates/batch")) {
                body = """
                        {"11":{"passed":true,"mode":"STRICT","reason":"2/2 用例通过"},
                         "12":{"passed":false,"mode":"STRICT","blockers":[{"code":"CASE_FAILED","message":"TC-0007 失败"}]}}""";
            } else if (path.equals("/internal/defects/open-high")) {
                body = "{\"counts\":{\"11\":0,\"12\":3}}";
            } else if (path.startsWith("/internal/gates/")) {
                String id = path.substring("/internal/gates/".length());
                switch (id) {
                    case "99" -> {
                        status = 500;
                        body = "{\"code\":\"BOOM\"}";
                    }
                    case "55" -> {
                        sleep(600);
                        body = "{\"passed\":true,\"mode\":\"STRICT\"}";
                    }
                    default -> body = "{\"passed\":true,\"mode\":\"STRICT\",\"reason\":\"1/1 用例通过\"}";
                }
            } else {
                status = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new DeliverGateClient(new InternalTokenClient(RestClient.builder(), TOKEN, baseUrl));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("批量门禁一次往返拿齐结论，阻断项折成人话")
    void evaluateAllParsesBatch() {
        Map<Long, GateDecision> decisions = client.evaluateAll(List.of(11L, 12L));

        assertEquals(2, decisions.size());
        assertTrue(decisions.get(11L).passed());
        assertEquals("2/2 用例通过", decisions.get(11L).reason());
        assertFalse(decisions.get(12L).passed());
        assertTrue(decisions.get(12L).reason().contains("TC-0007"), decisions.get(12L).reason());
    }

    @Test
    @DisplayName("缺陷计数按需求解析，数值 0 也要保留（否则会被当成取不到）")
    void openHighDefectsParses() {
        Map<Long, Integer> counts = client.openHighDefects(List.of(11L, 12L));

        assertEquals(2, counts.size());
        assertEquals(0, counts.get(11L));
        assertEquals(3, counts.get(12L));
    }

    @Test
    @DisplayName("每次跨服务调用都带 X-Internal-Token")
    void tokenAlwaysSent() {
        client.evaluateAll(List.of(11L));
        client.openHighDefects(List.of(11L));

        assertFalse(seenTokens.isEmpty());
        assertTrue(seenTokens.stream().allMatch(TOKEN::equals), "每个请求都要带内部 token：" + seenTokens);
    }

    @Test
    @DisplayName("批量门禁 500：抛 DeliverUnavailable，准入据此判 FAIL（不静默放行）")
    void batchFailureFailsClosed() throws IOException {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        assertThrows(DeliverGateClient.DeliverUnavailable.class, () -> client.evaluateAll(List.of(11L)));
    }

    @Test
    @DisplayName("单条门禁失败按不通过返回（需求流转不能因此 500）")
    void singleEvaluateFailureIsBlocked() {
        GateDecision decision = client.evaluate(99L);

        assertFalse(decision.passed());
        assertTrue(decision.reason().contains("门禁服务不可用"), decision.reason());
    }

    @Test
    @DisplayName("单条门禁超时按 250ms 预算中断，不等对端 600ms")
    void singleEvaluateHonoursBudget() {
        long begin = System.nanoTime();
        GateDecision decision = client.evaluate(55L);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        assertFalse(decision.passed());
        assertTrue(elapsedMillis < 600, "预算 250ms，实际 " + elapsedMillis + "ms；说明超时没生效");
    }

    @Test
    @DisplayName("准入整体预算 500ms ≥ 两次跨服务往返预算之和")
    void budgetFitsAdmissionWindow() {
        long gate = DeliverGateClient.GATE_BUDGET.toMillis();
        long defect = DeliverGateClient.DEFECT_BUDGET.toMillis();
        long admission = DeliverGateClient.ADMISSION_BUDGET.toMillis();

        assertTrue(gate + defect <= admission,
                "门禁 " + gate + "ms + 缺陷 " + defect + "ms 必须在 " + admission + "ms 内");
    }
}
