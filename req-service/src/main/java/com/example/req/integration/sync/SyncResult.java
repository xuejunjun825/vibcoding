package com.example.req.integration.sync;

/**
 * 同步日志的结果枚举（落库为 {@code integration_sync_log.result} 的大写名）。
 *
 * <p>语义边界：{@code SUCCESS} 只表示这次调用拿到了可用的 2xx；{@code DEGRADED} 是
 * 交互读降级（页面仍可渲染，但事实不新鲜）；{@code RETRY_EXHAUSTED} 只在后台写把
 * 重试预算用尽时出现——它同时触发站内告警，因此不能与 {@code FAILED} 混用。</p>
 */
public enum SyncResult {

    SUCCESS,
    DEGRADED,
    FAILED,
    RETRY_EXHAUSTED,
    IGNORED;

    public boolean failure() {
        return this == FAILED || this == RETRY_EXHAUSTED;
    }

    /** 由响应状态推导默认结果，调用方可显式覆盖。 */
    public static SyncResult fromResponse(int httpStatus, boolean transportFailure) {
        if (transportFailure) {
            return FAILED;
        }
        if (httpStatus >= 200 && httpStatus < 300) {
            return SUCCESS;
        }
        return FAILED;
    }
}
