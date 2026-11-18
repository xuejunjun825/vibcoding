package com.example.req.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.req.release.FlowBindingController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 审批包的错误体：{@code {code,message,detail}}，与平台 {@code GlobalExceptionHandler} 同一形状。
 *
 * <p>只拦截显式列出的控制器（{@code assignableTypes}），避免与 platform-common 的全局处理器
 * 争抢同一异常——否则错误体形状会随 Bean 注册顺序漂移，脚本按 code 断言就会随机失败。
 *
 * <p>{@code FlowBindingController} 必须在内：它在 release 包，但抛的同样是 {@link ApprovalException}
 * （403 管理员守卫 / 400 BINDING_INVALID / 409 WORKFLOW_NOT_CONFIGURED）。漏掉它会退化成 500
 * 纯文本，AC-6 的「非管理员 403」与前端按 code 提示全部失效（QA D-2 的 500 复现）。
 */
@RestControllerAdvice(assignableTypes = {
        ApprovalController.class, WorkflowAdminController.class, FlowBindingController.class})
public class ApprovalExceptionHandler {

    @ExceptionHandler(ApprovalException.class)
    public ResponseEntity<ErrorBody> handle(ApprovalException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorBody(ex.code(), ex.getMessage(), ex.detail()));
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ErrorBody(String code, String message, Map<String, Object> detail) {
    }
}
