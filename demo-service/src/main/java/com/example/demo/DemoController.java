package com.example.demo;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proof endpoint for the whole chain: gateway -> Nacos discovery -> this service
 * -> Nacos config.
 *
 * <p>{@code demo.config-source} is intentionally absent from the local
 * application.yml. Its default here is {@code local}; only a value published in
 * Nacos can make it {@code nacos}. That is what makes AC-3 falsifiable.</p>
 */
@RestController
@RefreshScope
public class DemoController {

    private final String greeting;
    private final String configSource;
    private final int instancePort;

    public DemoController(@Value("${demo.greeting:Hello}") String greeting,
                          @Value("${demo.config-source:local}") String configSource,
                          @Value("${server.port:8081}") int instancePort) {
        this.greeting = greeting;
        this.configSource = configSource;
        this.instancePort = instancePort;
    }

    @GetMapping("/demo/hello")
    public Map<String, Object> hello() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", greeting);
        body.put("instancePort", instancePort);
        body.put("configSource", configSource);
        return body;
    }
}
