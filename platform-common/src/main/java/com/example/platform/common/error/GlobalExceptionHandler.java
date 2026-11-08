package com.example.platform.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * One error shape for both services.
 *
 * <p>The scripts assert on {@code code}, so the mapping lives here rather than in每个 controller.
 * A 500 is deliberately not caught: an unexpected exception should keep Boot's default body and
 * stack trace, which is what makes a bug debuggable instead of being flattened into "ERROR".</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handle(ApiException ex) {
        if (ex.status() >= 500) {
            log.error("{}: {}", ex.code(), ex.message(), ex);
        } else {
            log.debug("{}: {}", ex.code(), ex.message());
        }
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.status(), ex.code(), ex.message(), ex.detail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            detail.put(error.getField(), error.getDefaultMessage());
        }
        return badRequest("请求参数校验失败", detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        Map<String, Object> detail = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                detail.put(String.valueOf(v.getPropertyPath()), v.getMessage()));
        return badRequest("请求参数校验失败", detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return badRequest("请求无法解析", Map.of("reason", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        int value = ex.getStatusCode().value();
        String code = status == null ? "ERROR" : status.name();
        return ResponseEntity.status(value)
                .body(ApiError.of(value, code,
                        ex.getReason() == null ? code : ex.getReason()));
    }

    private static ResponseEntity<ApiError> badRequest(String message, Map<String, Object> detail) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "VALIDATION_FAILED", message, detail));
    }
}
