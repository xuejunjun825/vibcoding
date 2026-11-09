package com.example.req.integration.artifact;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 制品域的 HTTP 面（TECHNICAL §4.4）。
 *
 * <p>控制器只做参数与状态码：检索、登记、晋级的所有判定都在
 * {@link ArtifactSearchService} / {@link ArtifactService}。登记成功返回 200 而不是 201，
 * 因为「同一坐标重复提交」必须复用同一行，语义是幂等绑定而非新建资源。</p>
 */
@RestController
public class ArtifactController {

    private final ArtifactSearchService searchService;
    private final ArtifactService artifactService;

    public ArtifactController(ArtifactSearchService searchService,
                              ArtifactService artifactService) {
        this.searchService = searchService;
        this.artifactService = artifactService;
    }

    /** Nexus 检索；未启用 Nexus → 409 {@code INTEGRATION_DISABLED}，读失败 → degraded。 */
    @GetMapping("/integration/artifacts/search")
    public ArtifactViews.SearchResult search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "repository", required = false) String repository,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return searchService.search(query, repository, version, limit);
    }

    /** 登记/绑定：{@code {artifactId}} 选已有记录，或按坐标登记（NEXUS/MANUAL）。 */
    @PostMapping("/deploy-orders/{id}/artifacts")
    public ArtifactViews.ArtifactView bind(@PathVariable("id") long deployOrderId,
                                           @RequestBody(required = false) ArtifactViews.BindCmd cmd) {
        return artifactService.bind(deployOrderId, cmd);
    }

    /** 发布单当前制品；没有返回 200 + 空体（前端空态）。 */
    @GetMapping("/deploy-orders/{id}/artifacts")
    public ArtifactViews.ArtifactView current(@PathVariable("id") long deployOrderId) {
        return artifactService.current(deployOrderId);
    }

    /** 晋级 RELEASE→PROD：摘要不一致 409，重复晋级 200（幂等返回既有记录）。 */
    @PostMapping("/artifacts/{id}/promote")
    public ArtifactViews.PromoteResult promote(@PathVariable("id") long artifactId,
                                               @RequestBody(required = false) ArtifactViews.PromoteCmd cmd) {
        return artifactService.promote(artifactId, cmd == null ? null : cmd.targetRepo());
    }
}
