package com.example.req.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import com.example.platform.common.error.ApiError;
import com.example.platform.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * B7-1：容器层 multipart 限额必须真的读到 25MB/30MB，超限必须是 413 {@code FILE_TOO_LARGE}。
 *
 * <p>平台没起也能验证的两件事：① {@code spring.servlet.multipart.*} 经 Boot 的 YAML 装载 + Binder
 * 绑定后确实是 25MB/30MB（不是只写了注释）；② {@link GlobalExceptionHandler} 把
 * {@link MaxUploadSizeExceededException} 映射成 413 而不是 500。业务层 20MB 上限仍在
 * {@link AttachmentService#MAX_BYTES}，两层语义一致。</p>
 *
 * <p>真栈的 25MB curl 由 {@code scripts/pdr-flow-verify.sh} 的 B7-1 覆盖；本测试是它的无平台替身。</p>
 */
class MultipartLimitTest {

    @Test
    @DisplayName("容器层 25MB/30MB 读得到，业务层 20MB 上限仍在")
    void containerLimitIs25MbAndBusinessLimitStays20Mb() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("application", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));

        assertEquals(DataSize.ofMegabytes(25), binder
                .bind("spring.servlet.multipart.max-file-size", Bindable.of(DataSize.class))
                .orElse(null), "max-file-size 必须是容器层的 25MB（业务层 20MB 之外的第一道闸）");
        assertEquals(DataSize.ofMegabytes(30), binder
                .bind("spring.servlet.multipart.max-request-size", Bindable.of(DataSize.class))
                .orElse(null), "max-request-size 必须给 multipart 边界留出余量");

        assertEquals(20L * 1024 * 1024, AttachmentService.MAX_BYTES,
                "AttachmentService 的 20MB 业务上限不能被容器层配置吞掉");
    }

    @Test
    @DisplayName("超过容器限额 → 413 FILE_TOO_LARGE（不是 500）")
    void oversizeUploadMapsTo413() {
        long limit = 25L * 1024 * 1024;
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleUploadTooLarge(new MaxUploadSizeExceededException(limit));

        assertEquals(413, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals(413, body.status());
        assertEquals("FILE_TOO_LARGE", body.code());
        assertEquals(limit, body.detail().get("maxBytes"));
    }
}
