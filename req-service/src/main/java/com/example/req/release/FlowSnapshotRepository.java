package com.example.req.release;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 快照仓库（r30 / AC-10 / AC-11）——<b>故意只暴露 {@code save} 与读方法</b>。
 *
 * <p>为什么不是 {@code JpaRepository}：那会把 {@code delete/deleteAll/deleteById} 一起继承进来，
 * "快照只读"就变成代码评审时才看得见的约定。这里 extends {@link Repository} 并只声明需要的方法，
 * 于是<b>接口本身就是 AC-11 的静态证据</b>：没有 update 方法可写、没有 delete 方法可调，
 * 想改一行只能绕过仓库直接写 SQL（会在评审里一眼看到）。</p>
 *
 * <p>{@code save} 只用于插入新轮次；轮次号由服务层取 {@code MAX+1}，并发由唯一键兜底。</p>
 */
public interface FlowSnapshotRepository extends Repository<FlowSnapshotEntity, Long> {

    FlowSnapshotEntity save(FlowSnapshotEntity entity);

    Optional<FlowSnapshotEntity> findTopByTenantIdAndDeployOrderIdOrderByRoundNoDesc(long tenantId,
                                                                                    long deployOrderId);

    Optional<FlowSnapshotEntity> findByTenantIdAndDeployOrderIdAndRoundNo(long tenantId, long deployOrderId,
                                                                         int roundNo);

    List<FlowSnapshotEntity> findByTenantIdAndDeployOrderIdOrderByRoundNoAsc(long tenantId, long deployOrderId);
}
