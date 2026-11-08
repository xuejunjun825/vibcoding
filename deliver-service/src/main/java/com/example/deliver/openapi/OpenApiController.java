package com.example.deliver.openapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public ingestion endpoint.
 *
 * <p>Two mappings: the gateway strips {@code /openapi} (so the service sees
 * {@code /v1/test-executions}) while direct calls and the signature's canonical path use the
 * public form. The HMAC filter resolves the same pair, so the signature is computed over what the
 * client called, not over what the gateway forwarded.</p>
 */
@RestController
public class OpenApiController {

    private final ExecutionIngestService ingest;

    public OpenApiController(ExecutionIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping({"/v1/test-executions", "/openapi/v1/test-executions"})
    public ResponseEntity<OpenApiViews.IngestResult> ingest(
            @RequestBody OpenApiViews.IngestCommand cmd, HttpServletRequest request) {
        Long clientId = attribute(request, HmacAuthFilter.ATTR_CLIENT_ID);
        if (clientId == null) {
            // Reached only when the filter was bypassed; refuse instead of writing an unattributed row.
            throw new com.example.platform.common.error.ApiException(401, "UNAUTHENTICATED",
                    "缺少开放 API 客户端身份", java.util.Map.of());
        }
        String ip = HmacAuthFilter.clientIp(request);
        OpenApiViews.IngestResult result = ingest.ingest(cmd, clientId, ip);
        return ResponseEntity.ok(result);
    }

    private static Long attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value instanceof Number number ? number.longValue() : null;
    }
}
