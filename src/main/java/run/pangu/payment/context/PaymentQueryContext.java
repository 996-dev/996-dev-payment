package run.pangu.payment.context;

import lombok.Builder;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentSession;

/**
 * 主动查单上下文。
 *
 * <p>查单只需要会话与支付方式，业务归属由 {@code bizType + bizNo} 表达，
 * 渠道实现无需感知上层业务模型。
 *
 * @param session 支付会话
 * @param method  支付方式实例
 * @param bizType 来源业务类型
 * @param bizNo   来源业务单号
 */
@Builder
public record PaymentQueryContext(
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
