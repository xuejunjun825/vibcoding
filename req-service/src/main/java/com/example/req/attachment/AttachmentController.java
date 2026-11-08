package com.example.req.attachment;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachment entry (AC-4 #5). The list is scoped by {@code ScopedJdbc}; the download is scoped by
 * {@code ScopeGuard} on the attachment's own domain/vendor, which is what the negative suite
 * exercises with {@code /attachments/999999/download}.
 */
@RestController
@RequestMapping("/attachments")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<AttachmentViews.AttachmentView> list(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long bizId) {
        return service.list(bizType, bizId);
    }

    @PostMapping
    public ResponseEntity<AttachmentViews.UploadResult> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long bizId) {
        return ResponseEntity.status(201).body(service.upload(file, bizType, bizId));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        AttachmentService.Download download = service.download(id);
        MediaType type = download.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(download.contentType());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.resource());
    }
}
