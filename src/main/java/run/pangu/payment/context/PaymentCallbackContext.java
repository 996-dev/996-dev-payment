package run.pangu.payment.context;

import lombok.Builder;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentSession;

/**
 * 异步回调上下文。
 *
 * <p>渠道实现从中拿到会话与方式，
 * 校验签名后回写支付结果。回调请求本身不带身份认证，
 * 安全性完全依赖各渠道的签名验证。
 *
 * @param session 支付会话
 * @param method  支付方式实例
 * @param bizType 来源业务类型
 * @param bizNo   来源业务单号
 */
@Builder
public record PaymentCallbackContext(
    PaymentSession session,
    PaymentMethod method,
    String bizType,
    String bizNo
) {

    public String sessionCode() {
        return session == null || session.getSpec() == null
            ? null : session.getSpec().getSessionCode();
    }
}
