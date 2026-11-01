package run.pangu.payment.payload;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * 发起支付的统一返回载荷。
 *
 * <p>渠道实现只要产出本对象，上层无需关心具体渠道差异。
 * 各渠道专有字段统一收敛进 {@code params}；需要强类型时，
 * 可在 {@code payload} 包下为具体渠道定义子类，再由实现类自行转换。
 */
@Data
@Builder
public class PaymentInitiatePayload {

    /** 响应形态，决定前端如何消费本载荷。 */
    private PaymentResponseType responseType;

    /** 是否已确认支付成功（人工渠道直连场景）。 */
    private boolean paid;

    /** 跳转地址 / 二维码内容 / 表单动作地址。 */
    private String url;

    /** 渠道侧交易号（若能立即拿到）。 */
    private String providerTradeNo;

    /** 渠道专有参数：JSAPI 参数、表单键值、二维码 base64 等。 */
    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();

    /** 面向用户的提示文案。 */
    private String message;

    public static PaymentInitiatePayload redirect(String url, String tradeNo) {
        return PaymentInitiatePayload.builder()
            .responseType(PaymentResponseType.REDIRECT_URL)
            .url(url)
            .providerTradeNo(tradeNo)
            .build();
    }

    public static PaymentInitiatePayload qrcode(String content, String tradeNo) {
        return PaymentInitiatePayload.builder()
            .responseType(PaymentResponseType.QRCODE_URL)
            .url(content)
            .providerTradeNo(tradeNo)
            .build();
    }

    public static PaymentInitiatePayload manual(Map<String, Object> params, String message) {
        return PaymentInitiatePayload.builder()
            .responseType(PaymentResponseType.MANUAL)
            .params(params == null ? new LinkedHashMap<>() : params)
            .message(message)
            .build();
    }
}
