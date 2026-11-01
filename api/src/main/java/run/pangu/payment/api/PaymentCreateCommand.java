package run.pangu.payment.api;

import java.math.BigDecimal;

/**
 * 下单命令。
 *
 * <p>只描述「这笔钱是谁为了什么付的」，不携带任何渠道实现细节。
 * 渠道相关的商户号、密钥等配置由本插件在后台按 {@code methodName} 读取。
 *
 * @param provider 渠道标识：manual / bank-transfer / qrcode / easypay / alipay / wechatpay / stripe
 * @param methodName 支付方式资源名（{@code PaymentMethod.metadata.name}），为空时按
 *     provider 取该渠道第一个启用的配置
 * @param bizType 来源业务类型，如 order / recharge / subscription，用于对账归类
 * @param bizNo 来源业务单号，同一 bizType 下应唯一；回调时可据此定位业务单
 * @param amount 支付金额，必填，最多两位小数
 * @param currency 币种，默认 CNY
 * @param subject 收银台展示的支付标题
 * @param description 支付描述
 * @param returnUrl 支付完成后前端回跳地址
 * @param notifyUrl 业务方异步通知地址，支付成功且业务方需要自己接收时填写
 * @param idempotencyKey 幂等键，建议用 {@code bizType + ":" + bizNo}
 * @param clientIp 终端 IP，渠道风控需要；不接受调用方伪造时留空
 */
public record PaymentCreateCommand(
    String provider,
    String methodName,
    String bizType,
    String bizNo,
    BigDecimal amount,
    String currency,
    String subject,
    String description,
    String returnUrl,
    String notifyUrl,
    String idempotencyKey,
    String clientIp
) {

    /** 最小构造：只给必要字段，其余留空。 */
    public static PaymentCreateCommand of(String provider, String bizType, String bizNo,
        BigDecimal amount, String subject) {
        return new PaymentCreateCommand(provider, null, bizType, bizNo, amount, "CNY",
            subject, null, null, null, null, null);
    }
}
