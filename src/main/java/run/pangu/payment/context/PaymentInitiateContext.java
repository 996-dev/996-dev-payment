package run.pangu.payment.context;

import java.math.BigDecimal;
import java.net.URL;
import java.util.Map;
import lombok.Builder;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.payload.PaymentResponseType;

/**
 * 发起支付时的上下文快照。
 *
 * <p>上下文只携带支付本身需要的信息：会话、方式、金额与回调地址。
 * 业务归属通过 {@code bizType + bizNo} 与 {@code bizData} 表达，
 * 不绑定任何订单模型，因此可被会员、商城、内容付费、打赏等场景直接复用。
 *
 * @param session       支付会话
 * @param method        支付方式实例
 * @param amount        应付金额
 * @param description   支付描述
 * @param returnUrl     业务方回跳地址
 * @param notifyUrl     业务方异步通知地址
 * @param externalUrl   站点外部地址，用于拼接渠道回调地址
 * @param bizType       来源业务类型
 * @param bizNo         来源业务单号
 * @param bizData       业务附加数据（如订单明细摘要），原样透传给渠道实现
 * @param preferredResponseType 业务偏好的响应形态
 */
@Builder
public record PaymentInitiateContext(
    PaymentSession session,
    PaymentMethod method,
    BigDecimal amount,
    String description,
    String returnUrl,
    String notifyUrl,
    URL externalUrl,
    String bizType,
    String bizNo,
    Map<String, Object> bizData,
    PaymentResponseType preferredResponseType
) {

    /** 本次支付的会话业务码，渠道实现用于拼接商户订单号。 */
    public String sessionCode() {
        return session == null || session.getSpec() == null
            ? null : session.getSpec().getSessionCode();
    }

    /** 支付标题，展示在渠道收银台。 */
    public String subject() {
        return session == null || session.getSpec() == null
            ? null : session.getSpec().getSubject();
    }

    /**
     * 站点回调地址。
     *
     * <p>形如 {@code {外部地址}/apis/payment.pangu.tax/v1alpha1/callbacks/{provider}}，
     * 由渠道在商户后台登记后用于接收异步通知。
     */
    public String callbackUrl(String provider) {
        String base = externalUrl == null ? "" : externalUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/apis/payment.pangu.tax/v1alpha1/callbacks/" + provider;
    }
}
