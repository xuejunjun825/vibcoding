package com.example.deliver.openapi;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLogEntity, Long> {
}
