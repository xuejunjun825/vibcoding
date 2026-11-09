package com.example.req.integration.artifact;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 制品域对外的视图与命令（TECHNICAL §4.4）。
 *
 * <p>三个事实源各自一个类型：Nexus 检索项（{@link ArtifactItem}）、库里的
 * {@code artifact_record} 行（{@link ArtifactView}）、以及晋级结果
 * （{@link PromoteResult}）。前端契约、验收断言与 OpenAPI 都从这里对齐；控制器只做
 * 参数与状态码，不另拼字段。</p>
 */
public final class ArtifactViews {

    private ArtifactViews() {
    }

    /**
     * Nexus 检索到的一条制品。
     *
     * @param id          Nexus 的资产 id；没有时由适配器用坐标拼一个稳定值
     * @param sha256      制品仓给出的摘要；为空表示该条不具备绑定条件（前端置灰）
     * @param downloadUrl 制品直链；只存储不主动下载，落库前经 SSRF 白名单校验
     */
    public record ArtifactItem(String id,
                               String repo,
                               String groupId,
                               String artifactName,
                               String version,
                               String packaging,
                               String classifier,
                               String sha256,
                               Long sizeBytes,
                               String downloadUrl) {
    }

    /**
     * 检索结果。{@code degraded=true} 表示这次没读成（超时/5xx/响应不可解析），
     * 页面显示原因并允许重试，而不是白屏——AC-7.1「失败降级」的落点。
     */
    public record SearchResult(List<ArtifactItem> items, boolean degraded, String reason) {

        public SearchResult {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static SearchResult of(List<ArtifactItem> items) {
            return new SearchResult(items, false, null);
        }

        public static SearchResult degraded(String reason) {
            return new SearchResult(List.of(), true, reason);
        }
    }

    /** 发布单当前绑定的制品（详情页与准入第 9 项读取）。 */
    public record ArtifactView(Long id,
                               String repo,
                               String groupId,
                               String artifactName,
                               String version,
                               String packaging,
                               String classifier,
                               String sha256,
                               String source,
                               String status,
                               String downloadUrl,
                               Long sizeBytes,
                               Long promotedFromId,
                               LocalDateTime promotedAt) {
    }

    /**
     * 绑定命令：既接受「从检索结果选一条」的 {@code artifactId}，也接受按坐标登记。
     *
     * <p>坐标字段同时支持两种形态：</p>
     * <ul>
     *   <li>扁平（前端检索后回填、验收脚本使用）：{@code repository/groupId/...} 直接在顶层；</li>
     *   <li>嵌套（设计文档的 {@code {manual:{...}}} 手工兜底）：字段放在 {@code manual} 里。</li>
     * </ul>
     * {@link #effective()} 把两种形态合并成一个命令，业务层不需要再判分支。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BindCmd(Long artifactId,
                          String source,
                          String repository,
                          String groupId,
                          String artifactName,
                          String version,
                          String packaging,
                          String classifier,
                          String sha256,
                          String downloadUrl,
                          Long sizeBytes,
                          Manual manual) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Manual(String repository,
                             String groupId,
                             String artifactName,
                             String version,
                             String packaging,
                             String classifier,
                             String sha256,
                             String downloadUrl,
                             Long sizeBytes) {
        }

        /** 合并嵌套的 manual 字段；没有 manual 时原样返回。 */
        public BindCmd effective() {
            if (manual == null) {
                return this;
            }
            Manual m = manual;
            return new BindCmd(artifactId, source,
                    pick(repository, m.repository()),
                    pick(groupId, m.groupId()),
                    pick(artifactName, m.artifactName()),
                    pick(version, m.version()),
                    pick(packaging, m.packaging()),
                    pick(classifier, m.classifier()),
                    pick(sha256, m.sha256()),
                    pick(downloadUrl, m.downloadUrl()),
                    sizeBytes != null ? sizeBytes : m.sizeBytes(),
                    null);
        }

        private static String pick(String primary, String fallback) {
            return primary == null || primary.isBlank() ? fallback : primary;
        }
    }

    /** 晋级命令：目标仓库名（Nexus 的 repository，不是 URL）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromoteCmd(String targetRepo) {
    }

    /** 晋级结果；重复晋级幂等返回既有行，{@code artifactId} 是晋级后新记录的 id。 */
    public record PromoteResult(Long artifactId, Long promotedFromId, String status) {
    }
}
