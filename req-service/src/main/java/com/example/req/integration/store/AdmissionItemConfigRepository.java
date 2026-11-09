package com.example.req.integration.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code admission_item_config} 查询：只存显式改过的项，缺失即取代码默认值。 */
public interface AdmissionItemConfigRepository extends JpaRepository<AdmissionItemConfigEntity, Long> {

    List<AdmissionItemConfigEntity> findByTenantIdOrderByItemCodeAsc(Long tenantId);

    Optional<AdmissionItemConfigEntity> findByTenantIdAndItemCode(Long tenantId, String itemCode);

    List<AdmissionItemConfigEntity> findByTenantIdAndEnabled(Long tenantId, Integer enabled);
}
