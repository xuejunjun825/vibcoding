package com.example.req.collab.wiki;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Wiki 的 HTTP 契约（TECHNICAL §2.3 / §3.2）。
 *
 * <p>集中放 record 而不是让 controller 各拼一份 Map：前端 {@code web/src/api/collab.ts}
 * 的字段名与这里一一对应，QA 脚本断言的 {@code .page.*} 也来自这里。字段只增不改——
 * 删除一个字段等于悄悄破坏一个消费方。</p>
 *
 * <p>{@link PageDetail} 在顶层同时重复 {@code id}/{@code currentVersion}：
 * {@code scripts/tests/collab/s2-wiki.sh} 建页后直接读 {@code .id}，而页面详情消费方
 * 读 {@code .page.*}。两种读法都是既有契约，不能二选一。</p>
 */
public final class WikiViews {

    private WikiViews() {
    }

    /** 空间列表行（列表已按可见性过滤；{@code pageCount} 不含回收站页面）。 */
    public record SpaceView(long id,
                            String name,
                            String visibility,
                            long domainId,
                            Long vendorId,
                            String editorPolicy,
                            Long ownerId,
                            String ownerName,
                            long pageCount,
                            boolean canEdit) {
    }

    /** 建/改空间入参：{@code domainId} 可空时由服务端按调用方作用域收敛。 */
    public record SpaceCmd(Long domainId,
                           String name,
                           String visibility,
                           Long vendorId,
                           String editorPolicy,
                           Long ownerId,
                           String description) {
    }

    /** 建页入参；{@code parentId} 为空即根页（depth=1）。 */
    public record CreateCmd(long spaceId,
                            Long parentId,
                            String title,
                            String contentMd) {
    }

    /** 页面元数据 + 正文。{@code links}/{@code attachments} 同时被放进 {@code page} 内。 */
    public record Page(long id,
                       long spaceId,
                       String title,
                       String contentMd,
                       int currentVersion,
                       boolean canEdit,
                       String visibility,
                       LocalDateTime updatedAt,
                       String updatedByName,
                       LocalDateTime deletedAt,
                       Integer remainingDays,
                       String contentHash,
                       List<Link> links,
                       List<Attachment> attachments) {
    }

    /** 页面详情：{@code page} 给页面主体，{@code breadcrumb} 是「根 → 当前页」。 */
    public record PageDetail(long id,
                             long spaceId,
                             String title,
                             int currentVersion,
                             Page page,
                             List<Breadcrumb> breadcrumb,
                             List<Link> links,
                             List<Attachment> attachments) {
    }

    public record Breadcrumb(long id, String title) {
    }

    /** 页面挂到需求/缺陷/版本上的双向关联。 */
    public record Link(long id, String bizType, long bizId, String title) {
    }

    /** 附件锚点视图（源是 {@code AttachmentService.list("WIKI_PAGE", pageId)}）。 */
    public record Attachment(long id, String name, Long size, String url) {
    }

    /** 树节点；{@code level} 与 {@code depth} 同义（前端列头显示「n / 4 级」）。 */
    public record TreeNode(long id,
                           Long parentId,
                           String title,
                           int currentVersion,
                           int level,
                           boolean hasChildren,
                           List<TreeNode> children) {
    }

    /** 保存入参；{@code baseVersion} 落后于当前版本即 409 {@code WIKI_EDIT_CONFLICT}。 */
    public record SaveCmd(String title, String contentMd, Integer baseVersion) {
    }

    /** 保存/回滚结果；{@code versionNo} 是保存后的当前版本（noChange 时等于原版本）。 */
    public record SaveResult(long pageId,
                             boolean noChange,
                             int versionNo,
                             String title,
                             int currentVersion) {
    }

    /** 历史版本（不含正文：列表页只显示元信息，正文走 diff/回滚）。 */
    public record Version(int versionNo,
                          String title,
                          LocalDateTime savedAt,
                          Long savedBy,
                          String savedByName,
                          String changeNote,
                          String contentHash,
                          boolean current) {
    }

    /** 行级 diff；{@code kind ∈ SAME/ADD/DEL}。 */
    public record DiffLine(String kind, Integer leftNo, Integer rightNo, String text) {
    }

    public record Diff(List<DiffLine> lines, int added, int removed, int changed) {
    }

    /** 全文检索命中；{@code snippet} 中的命中词由 \u0001/\u0002 包裹。 */
    public record SearchHit(long pageId,
                            String title,
                            String snippet,
                            String spaceName,
                            LocalDateTime updatedAt) {
    }

    /** 检索入参；{@code page} 从 1 开始，{@code size} 默认 20、上限 50。 */
    public record SearchQuery(String q, Integer page, Integer size) {
    }

    public record SearchResult(List<SearchHit> items,
                               long total,
                               boolean degraded,
                               int page,
                               int size) {
    }

    /** 回收站行：只列被删子树的根（{@code deletedRootId == id}）。 */
    public record TrashItem(long id,
                            String title,
                            long spaceId,
                            LocalDateTime deletedAt,
                            String deletedByName,
                            int remainingDays,
                            Long deletedRootId) {
    }

    /** {@code /wiki/links} 的响应：前端同时兼容裸数组与 {@code {items:[]}}。 */
    public record LinkList(List<SearchHit> items) {
    }

    /** 空间列表响应；列表统一 {@code {items:[]}} 信封（TECHNICAL §2.3 分页约定）。 */
    public record SpaceList(List<SpaceView> items) {
    }

    /** 树响应；一次返回整棵 ≤500 节点，{@code nodes} 为根节点集合。 */
    public record TreeView(List<TreeNode> nodes) {
    }

    /** 回收站响应；只含被删子树的根行。 */
    public record TrashList(List<TrashItem> items) {
    }

    public record MoveCmd(Long parentId) {
    }

    public record RollbackCmd(Integer versionNo) {
    }

    public record LinkCmd(String bizType, Long bizId) {
    }

    public record Comment(long id,
                          Long userId,
                          String userName,
                          String content,
                          LocalDateTime createdAt) {
    }

    public record CommentCmd(String content) {
    }
}
