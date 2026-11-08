package com.example.req.attachment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<AttachmentEntity, Long> {

    List<AttachmentEntity> findByTenantIdAndBizTypeAndBizIdOrderByIdAsc(Long tenantId, String bizType,
                                                                       Long bizId);
}
