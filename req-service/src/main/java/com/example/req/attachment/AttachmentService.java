package com.example.req.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopeGuard;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachment upload/list/download.
 *
 * <p>This is the fifth of the eight isolation entries in AC-4, and it was missing entirely: the
 * table and the frontend call existed, the endpoint did not (404). The business object is the
 * scope anchor — {@code domain_id}/{@code vendor_id} are read from the requirement or defect in
 * the same scoped query the list uses, so a forged form field cannot move a file across tenants,
 * domains or vendors.</p>
 */
@Service
public class AttachmentService {

    static final long MAX_BYTES = 20L * 1024 * 1024;

    private final AttachmentRepository attachments;
    private final ScopedJdbc jdbc;
    private final ScopeGuard guard;
    private final AuditService audit;
    private final Path root;

    public AttachmentService(AttachmentRepository attachments,
                             ScopedJdbc jdbc,
                             ScopeGuard guard,
                             AuditService audit,
                             @Value("${platform.attachment.dir:./.run/attachments}") String dir) {
        this.attachments = attachments;
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<AttachmentViews.AttachmentView> list(String bizType, Long bizId) {
        TenantContext.require();
        StringBuilder sql = new StringBuilder("""
                SELECT a.id AS id, a.file_name AS file_name, a.size AS size,
                       a.uploader_id AS uploader_id, a.created_at AS created_at
                  FROM attachment a
                 WHERE /*scope:a*/
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        String type = normalizeType(bizType, false);
        if (type != null) {
            sql.append(" AND a.biz_type = :bizType");
            params.put("bizType", type);
        }
        if (bizId != null) {
            sql.append(" AND a.biz_id = :bizId");
            params.put("bizId", bizId);
        }
        sql.append(" ORDER BY a.id ASC");
        List<Map<String, Object>> rows = jdbc.list(sql.toString(), params);
        List<AttachmentViews.AttachmentView> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(new AttachmentViews.AttachmentView(
                    asLong(row.get("id")),
                    String.valueOf(row.get("file_name")),
                    asLong(row.get("size")),
                    asLong(row.get("uploader_id")),
                    row.get("created_at") instanceof LocalDateTime time ? time : null));
        }
        return items;
    }

    @Transactional
    public AttachmentViews.UploadResult upload(MultipartFile file, String bizType, Long bizId) {
        DataScope scope = TenantContext.require();
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "FILE_REQUIRED", "请选择要上传的文件", null);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(413, "FILE_TOO_LARGE", "附件不能超过 20MB", Map.of("maxBytes", MAX_BYTES));
        }
        String type = normalizeType(bizType, true);
        if (bizId == null) {
            throw new ApiException(400, "BIZ_REQUIRED", "附件必须挂在业务对象下（bizId 必填）", null);
        }
        Scoped biz = resolveBiz(type, bizId);
        if (biz == null) {
            throw new ApiException(404, "BIZ_NOT_FOUND", "附件所属对象不存在或不在你的数据范围内",
                    Map.of("bizType", type, "bizId", bizId));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(400, "FILE_UNREADABLE", "附件读取失败", null);
        }
        String original = sanitize(file.getOriginalFilename());
        String stored = UUID.randomUUID() + "-" + original;
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(stored), bytes);
        } catch (IOException ex) {
            throw new ApiException(500, "STORAGE_UNAVAILABLE", "附件存储不可用", null);
        }

        LocalDateTime now = LocalDateTime.now();
        AttachmentEntity entity = new AttachmentEntity();
        entity.setTenantId(scope.tenantId());
        entity.setBizType(type);
        entity.setBizId(bizId);
        entity.setFileName(original);
        entity.setPath(stored);
        entity.setSize((long) bytes.length);
        entity.setContentType(file.getContentType());
        entity.setSha256(sha256(bytes));
        entity.setUploaderId(scope.userId());
        entity.setDomainId(asLong(biz.domainId()));
        entity.setVendorId(asLong(biz.vendorId()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        AttachmentEntity saved = attachments.save(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bizType", type);
        detail.put("bizId", bizId);
        detail.put("fileName", original);
        detail.put("size", bytes.length);
        audit.record("ATTACH_UPLOAD", "attachment", saved.getId(), detail);
        return new AttachmentViews.UploadResult(saved.getId());
    }

    @Transactional(readOnly = true)
    public Download download(long id) {
        AttachmentEntity entity = attachments.findById(id)
                .orElseThrow(() -> new ApiException(404, "ATTACHMENT_NOT_FOUND",
                        "附件不存在或不在你的数据范围内", Map.of("attachmentId", id)));
        guard.requireVisible(entity);
        Path path = root.resolve(entity.getPath()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new ApiException(404, "ATTACHMENT_FILE_MISSING", "附件文件已不存在", null);
        }
        Resource resource = new FileSystemResource(path);
        return new Download(resource, entity.getFileName(), entity.getContentType());
    }

    /** Resolved business object: the scope anchor for the attachment row. */
    private record Scoped(Object domainId, Object vendorId) {
    }

    public record Download(Resource resource, String fileName, String contentType) {
    }

    private Scoped resolveBiz(String type, long bizId) {
        Map<String, Object> row;
        if ("REQ".equals(type)) {
            row = jdbc.one("""
                    SELECT r.domain_id AS domain_id,
                           COALESCE(r.assignee_vendor_id, r.owner_vendor_id) AS vendor_id
                      FROM requirement r
                     WHERE r.id = :id AND /*scope:r*/
                    """, Map.of("id", bizId));
        } else {
            row = jdbc.one("""
                    SELECT df.domain_id AS domain_id, df.vendor_id AS vendor_id
                      FROM defect df
                     WHERE df.id = :id AND /*scope:df*/
                    """, Map.of("id", bizId));
        }
        return row == null ? null : new Scoped(row.get("domain_id"), row.get("vendor_id"));
    }

    private static String normalizeType(String bizType, boolean required) {
        if (bizType == null || bizType.isBlank()) {
            if (required) {
                throw new ApiException(400, "BIZ_TYPE_REQUIRED", "bizType 必填（REQ 或 DEFECT）", null);
            }
            return null;
        }
        String type = bizType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "REQ", "REQUIREMENT" -> "REQ";
            case "DEFECT", "BUG" -> "DEFECT";
            default -> throw new ApiException(400, "BIZ_TYPE_UNSUPPORTED",
                    "不支持的 bizType：" + bizType + "（仅 REQ / DEFECT）", null);
        };
    }

    private static String sanitize(String name) {
        String base = name == null ? "" : name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty()) {
            base = "attachment.bin";
        }
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : Long.parseLong(text);
    }
}
