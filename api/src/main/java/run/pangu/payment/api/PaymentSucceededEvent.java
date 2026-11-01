package run.pangu.payment.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付成功共享事件。
 *
 * <p>本插件在会话首次置为 PAID 时发布一次（幂等保证不重复），
 * 调用方插件订阅后执行履约（发货、开通会员、充值到账等）。
 *
 * <p>为什么用事件而不是回调接口：支付成功可能由异步回调、主动查单、
 * 运营人工核销三条路径触发，事件是唯一收敛点；调用方也不必实现扩展点。
 *
 * <pre>{@code
 * @Component
 * public class OrderPaidListener {
 *     @EventListener
 *     public void onPaid(PaymentSucceededEvent event) {
 *         if (!"order".equals(event.bizType())) return;
 *         orderService.markPaid(event.bizNo(), event.amount());
 *     }
 * }
 * }</pre>
 *
 * <p>订阅方需在自己的 plugin.yaml 中声明 {@code pluginDependencies: { pangu-payment: ">=1.0.0" }}，
 * 否则无法解析本事件类型。
 *
 * @param sessionCode 会话码
 * @param provider 渠道标识
 * @param bizType 来源业务类型
 * @param bizNo 来源业务单号
 * @param amount 实收金额
 * @param currency 币种
 * @param providerTradeNo 渠道交易号
 * @param paidAt 支付确认时间
 */
public record PaymentSucceededEvent(
    String sessionCode,
    String provider,
    String bizType,
    String bizNo,
    BigDecimal amount,
    String currency,
    String providerTradeNo,
    Instant paidAt
) {
}
