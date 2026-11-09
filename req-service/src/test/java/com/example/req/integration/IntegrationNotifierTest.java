package com.example.req.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.platform.common.notify.NotificationWriter;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.notify.IntegrationNotifier;
import com.example.req.integration.outbound.OutboundRequest;
import com.example.req.integration.outbound.OutboundResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** AC-2.2：重试耗尽要有一条站内告警，且告警内容不带凭据。 */
class IntegrationNotifierTest {

    @Test
    @DisplayName("显式传入发起人时即使没有 TenantContext 也能告警")
    void alertsWithExplicitActor() {
        NotificationWriter writer = mock(NotificationWriter.class);
        IntegrationNotifier notifier = new IntegrationNotifier(writer);
        OutboundRequest request = OutboundRequest.builder(1L, IntegrationSystem.JENKINS, "build.trigger",
                        org.springframework.http.HttpMethod.POST, "http://jenkins.local/job/x/build")
                .correlationId("corr-1").biz("BUILD", 8L).build();
        OutboundResponse response = new OutboundResponse(503, null, 31000, 6,
                OutboundResponse.RETRY_EXHAUSTED, false);

        notifier.retryExhausted(request, response, 42L);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(writer).send(eq(42L), eq(IntegrationNotifier.TYPE_SYNC_FAILED), anyString(),
                content.capture(), eq(IntegrationNotifier.BIZ_TYPE), eq(8L));
        assertTrue(content.getValue().contains("6"), content.getValue());
        assertTrue(content.getValue().contains("RETRY_EXHAUSTED"), content.getValue());
    }

    @Test
    @DisplayName("既无发起人也无租户上下文：跳过而不是抛异常")
    void skipsWithoutPrincipal() {
        NotificationWriter writer = mock(NotificationWriter.class);
        IntegrationNotifier notifier = new IntegrationNotifier(writer);
        OutboundRequest request = OutboundRequest.builder(1L, IntegrationSystem.SONAR, "sonar.read",
                        org.springframework.http.HttpMethod.GET, "http://sonar.local/api")
                .correlationId("corr-2").build();
        notifier.retryExhausted(request, new OutboundResponse(0, null, 1, 1,
                OutboundResponse.TRANSPORT_ERROR, true), 0L);
        verify(writer, never()).send(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("告警内容二次脱敏：即使错误信息混入 secret 也不会出现在标题/正文")
    void masksContent() {
        NotificationWriter writer = mock(NotificationWriter.class);
        IntegrationNotifier notifier = new IntegrationNotifier(writer);
        OutboundRequest request = OutboundRequest.builder(1L, IntegrationSystem.GITLAB, "mr.sync",
                        org.springframework.http.HttpMethod.GET, "http://gitlab.local/api?private_token=unit-only-secret")
                .correlationId("corr-3").build();
        notifier.retryExhausted(request, new OutboundResponse(401, null, 10, 1,
                "HTTP_401", false), 7L);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(writer).send(eq(7L), anyString(), anyString(), content.capture(), anyString(), any());
        assertFalse(content.getValue().contains("unit-only-secret"), content.getValue());
    }
}
