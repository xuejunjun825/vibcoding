package com.example.deliver.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One JSON mapper for the quality domain.
 *
 * <p>deliver-service uses Spring MVC with the plain {@code spring-boot-starter-webmvc} slice, so no
 * {@code ObjectMapper} bean exists in the context (no Jackson auto-configuration on this module).
 * The quality packages need one for {@code *_json} columns — plan criteria, round case scopes,
 * strategy payloads and change-log before/after snapshots. Declaring it here keeps a single
 * configured mapper instead of {@code new ObjectMapper()} in five services.</p>
 */
@Configuration
public class QualityJsonConfig {

    @Bean
    public ObjectMapper qualityObjectMapper() {
        return new ObjectMapper();
    }
}
