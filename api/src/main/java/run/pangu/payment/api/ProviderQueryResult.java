package run.pangu.payment.api;

import java.math.BigDecimal;

/**
 * 主动查单结果。
 *
 * @param paid 渠道侧是否已支付
 * @param amount 渠道侧订单金额，null 表示渠道无此订单
 * @param providerTradeNo 渠道交易号
 * @param message 说明
 */
public record ProviderQueryResult(
    boolean paid,
    BigDecimal amount,
    String providerTradeNo,
    String message
) {

    public static ProviderQueryResult unknown() {
        return new ProviderQueryResult(false, null, null, "not-found");
    }

    public static ProviderQueryResult paid(BigDecimal amount, String providerTradeNo) {
        return new ProviderQueryResult(true, amount, providerTradeNo, "paid");
    }
}
