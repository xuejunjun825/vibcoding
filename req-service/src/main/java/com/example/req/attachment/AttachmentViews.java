package com.example.req.attachment;

import java.time.LocalDateTime;

/** Wire shapes for the attachment endpoints (records only — the entity never leaves the service). */
public final class AttachmentViews {

    private AttachmentViews() {
    }

    /** Matches the frontend's {@code AttachmentRow}: the page only needs these five fields. */
    public record AttachmentView(Long id,
                                 String fileName,
                                 Long size,
                                 Long uploadedBy,
                                 LocalDateTime createdAt) {
    }

    public record UploadResult(Long id) {
    }
}
