package com.example.req.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** {@code wf_history} 访问：时间线与「同一任务最近一次催办」都读这张表。 */
public interface ApprovalHistoryRepository extends JpaRepository<ApprovalHistory, Long> {

    List<ApprovalHistory> findByInstanceIdOrderByIdAsc(Long instanceId);

    /** 批量流水（r27）：需求池按页取 SUBMIT 快照时一条 IN 取代 N 条查询。 */
    List<ApprovalHistory> findByInstanceIdInOrderByInstanceIdAscIdAsc(Collection<Long> instanceIds);

    List<ApprovalHistory> findByInstanceIdAndTaskIdAndActionOrderByIdDesc(
            Long instanceId, Long taskId, String action);

    Optional<ApprovalHistory> findFirstByInstanceIdAndTaskIdAndActionOrderByIdDesc(
            Long instanceId, Long taskId, String action);

    long countByInstanceIdAndAction(Long instanceId, String action);

    List<ApprovalHistory> findByInstanceIdAndCreatedAtAfterOrderByIdAsc(Long instanceId, LocalDateTime after);
}
