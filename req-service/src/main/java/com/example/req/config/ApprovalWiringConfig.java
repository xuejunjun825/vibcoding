package com.example.req.config;

import com.example.req.approval.WorkflowJson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the approval module's JSON codec.
 *
 * <p>{@code WorkflowJson} is a plain class (no stereotype) and nothing in the approval package
 * constructs it, so the container has no such bean and the whole service fails to start. Declaring
 * it here keeps the assembly in the config package; {@code @ConditionalOnMissingBean} lets the
 * approval module take ownership later without a duplicate-definition failure.</p>
 */
@Configuration
public class ApprovalWiringConfig {

    @Bean
    @ConditionalOnMissingBean(WorkflowJson.class)
    public WorkflowJson workflowJson(ObjectMapper objectMapper) {
        return new WorkflowJson(objectMapper);
    }
}
