package run.pangu.payment.api;

import java.math.BigDecimal;

/**
 * 回调处理结果。
 *
 * <p>{@code paid=true} 时本插件会把会话置为已支付并发布
 * {@link PaymentSucceededEvent}；{@code paid=false} 时仅按
 * {@code httpStatus + responseBody} 原样响应渠道。
 *
 * @param paid 是否确认已收款
 * @param sessionCode 会话码，实现方可从回调中解析后覆盖
 * @param providerTradeNo 渠道交易号
 * @param amount 渠道实收金额，用于与本地金额比对；为 null 时跳过校验
 * @param httpStatus 响应给渠道的 HTTP 状态码
 * @param responseBody 响应给渠道的报文（支付宝 "success"、微信 JSON、Stripe 空串）
 * @param message 内部日志用说明
 */
public record ProviderCallbackResult(
    boolean paid,
    String sessionCode,
    String providerTradeNo,
    BigDecimal amount,
    int httpStatus,
    String responseBody,
    String message
) {

    public static ProviderCallbackResult paid(String sessionCode, String providerTradeNo,
        BigDecimal amount, String responseBody) {
        return new ProviderCallbackResult(true, sessionCode, providerTradeNo, amount, 200,
            responseBody, "ok");
    }

    public static ProviderCallbackResult rejected(int status, String message) {
        return new ProviderCallbackResult(false, null, null, null, status, message, message);
    }
}
