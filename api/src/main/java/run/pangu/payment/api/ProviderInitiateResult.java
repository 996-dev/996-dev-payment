package run.pangu.payment.api;

import java.util.Map;

/**
 * 发起支付结果，描述前端该如何处理。
 *
 * @param responseType REDIRECT / QRCODE_URL / QRCODE_CONTENT / JSAPI / MANUAL
 * @param url 跳转地址或二维码图片地址
 * @param qrCodeContent 二维码原始内容（{@code QRCODE_CONTENT} 时由前端渲染）
 * @param payload 渠道原始参数，{@code MANUAL} 时放收款账户信息
 * @param message 展示给用户的提示文案
 */
public record ProviderInitiateResult(
    String responseType,
    String url,
    String qrCodeContent,
    Map<String, Object> payload,
    String message
) {

    public static ProviderInitiateResult redirect(String url) {
        return new ProviderInitiateResult("REDIRECT", url, null, Map.of(), null);
    }

    public static ProviderInitiateResult qrCodeUrl(String imageUrl) {
        return new ProviderInitiateResult("QRCODE_URL", imageUrl, null, Map.of(), null);
    }

    public static ProviderInitiateResult manual(Map<String, Object> payload, String message) {
        return new ProviderInitiateResult("MANUAL", null, null, payload, message);
    }
}
