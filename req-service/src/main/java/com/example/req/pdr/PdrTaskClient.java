package com.example.req.pdr;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.req.integration.InternalTokenClient;

/**
 * 把「评审意见」变成交付域任务（设计 §4.5 / R8）。
 *
 * <p>走内部 HTTP 而不是直接写 {@code task} 表：任务表归交付域所有（排序号、派生域/厂商、看板状态机
 * 都在那边），req-service 直写会绕过它的 {@code nextSortNo} 与审计。代价是要么拿到任务 id 要么整笔
 * 失败——设计要求的正是这一点：跨服务失败返回 503，本地回写当事务回滚。</p>
 *
 * <p>为什么不复用公开的 {@code POST /tasks}：那个端点要求 JWT + 动作码 {@code task.action.create}，
 * 内部令牌过不去；{@code /internal/tasks} 只校验 {@code X-Internal-Token}。</p>
 */
@Component
public class PdrTaskClient {

    /** 读超时 2 秒：建任务是一次插入 + 一次审计，超过这个时间宁可 503 让用户重试。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final InternalTokenClient client;

    public PdrTaskClient(InternalTokenClient client) {
        this.client = client;
    }

    /**
     * 建任务并返回交付域给的 id/code。
     *
     * @throws com.example.platform.common.error.ApiException 503 TASK_CREATE_UNAVAILABLE（任何跨服务失败）
     */
    public TaskCreated create(long requirementId, String title, String description, Long assigneeId,
                              BigDecimal estimateHours, LocalDate dueDate) {
        CreateTaskRequest body = new CreateTaskRequest(requirementId, null, title, description,
                assigneeId, estimateHours, null, dueDate);
        try {
            TaskCreated created = client.deliver(READ_TIMEOUT).post()
                    .uri("/internal/tasks")
                    .body(body)
                    .retrieve()
                    .body(TaskCreated.class);
            if (created == null || created.taskId() <= 0) {
                throw PdrErrors.taskCreateUnavailable("交付域未返回任务 id");
            }
            return created;
        } catch (RestClientException ex) {
            throw PdrErrors.taskCreateUnavailable(ex.getMessage());
        }
    }

    /** 交付域 {@code POST /internal/tasks} 的请求体（字段顺序与 TaskViews.CreateTask 对齐）。 */
    record CreateTaskRequest(Long requirementId, Long sprintId, String title, String description,
                             Long assigneeId, BigDecimal estimateHours, LocalDate startDate,
                             LocalDate dueDate) {
    }

    /** 交付域响应：{@code taskCode} 是合成码 {@code TASK-<id>}（task 表没有 code 列）。 */
    public record TaskCreated(long taskId, String taskCode, String status) {
    }
}
