package com.example.req.integration.gitlab;

import java.time.LocalDateTime;

/**
 * 一条 GitLab MR 行级评论（表 {@code mr_comment}，见 V9）。
 *
 * <p>这里刻意<b>不是</b> JPA 实体：写入必须是
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}（幂等键 {@code uk_note}），JPA 的
 * {@code merge()} 会先 SELECT 再 UPDATE，两个并发 webhook 仍可能撞唯一键；
 * 读取要走 {@code ScopedJdbc} 才能带上需求可见性谓词。两个方向都不适合
 * {@code @Entity}，所以它只是行载体，SQL 在 {@link MrCommentService} 里。</p>
 *
 * <p>{@code body} 在落库前已脱敏并截断到 2000 字符（列宽即上限）。</p>
 */
public class MrCommentEntity {

    private Long id;
    private Long tenantId;
    private Long mrSyncId;
    private Long gitlabNoteId;
    private String author;
    private String body;
    private String filePath;
    private Integer lineNo;
    private boolean resolved;
    private LocalDateTime noteCreatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getMrSyncId() {
        return mrSyncId;
    }

    public void setMrSyncId(Long mrSyncId) {
        this.mrSyncId = mrSyncId;
    }

    public Long getGitlabNoteId() {
        return gitlabNoteId;
    }

    public void setGitlabNoteId(Long gitlabNoteId) {
        this.gitlabNoteId = gitlabNoteId;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public LocalDateTime getNoteCreatedAt() {
        return noteCreatedAt;
    }

    public void setNoteCreatedAt(LocalDateTime noteCreatedAt) {
        this.noteCreatedAt = noteCreatedAt;
    }

    /** 转成对外视图；UI 不该看见 tenant_id / 内部行号。 */
    public MrCommentViews.CommentRow toRow() {
        return new MrCommentViews.CommentRow(
                id == null ? 0L : id,
                mrSyncId == null ? 0L : mrSyncId,
                gitlabNoteId == null ? 0L : gitlabNoteId,
                author,
                body,
                filePath,
                lineNo,
                resolved,
                noteCreatedAt);
    }
}
