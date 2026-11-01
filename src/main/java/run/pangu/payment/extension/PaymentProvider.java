package run.pangu.payment.extension;

/**
 * 支付渠道枚举。
 *
 * <p>每个枚举值对应一个渠道 code，配置项、路由参数与前端调用都以 code 为准。
 * {@link #PAYPAL} 仅保留枚举位，暂未提供实现，接入时返回"渠道未实现"。
 */
public enum PaymentProvider {

    WECHAT_PAY("wechatpay", "微信支付"),
    ALIPAY("alipay", "支付宝"),
    PAYPAL("paypal", "PayPal"),
    STRIPE("stripe", "Stripe"),
    MANUAL("manual", "人工确认"),
    BANK_TRANSFER("bank-transfer", "银行转账"),
    PAYMENT_QRCODE("qrcode", "静态收款码"),
    EASY_PAY("easypay", "易支付");

    private final String code;
    private final String displayName;

    PaymentProvider(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 对外暴露的渠道标识，用于路由与配置键。 */
    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 按渠道标识解析，忽略大小写与连字符差异。 */
    public static PaymentProvider of(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("渠道标识不能为空");
        }
        String normalized = code.trim().toLowerCase().replace("-", "").replace("_", "");
        for (PaymentProvider p : values()) {
            if (p.code.replace("-", "").replace("_", "").equals(normalized)
                || p.name().toLowerCase().replace("_", "").equals(normalized)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知支付渠道: " + code);
    }
}
