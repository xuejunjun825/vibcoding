package com.example.req.integration.outbound;

/**
 * 重试判定：只有「传输失败 / 5xx / 429」值得重试。
 *
 * <p>4xx 重试会放大故障（凭据错、参数错、目标不存在，重试一百次也不会成功），
 * 因此判定必须是一个可穷举单测的纯函数，而不是每个适配器自己判断。</p>
 */
public final class RetryClassifier {

    private RetryClassifier() {
    }

    public static boolean shouldRetry(int httpStatus, boolean transportFailure) {
        if (transportFailure) {
            return true;
        }
        if (httpStatus == 429) {
            return true;
        }
        return httpStatus >= 500 && httpStatus <= 599;
    }
}
