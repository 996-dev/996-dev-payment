package run.pangu.payment.api;

import java.time.Instant;
import java.util.Map;

/**
 * 下单结果。
 *
 * @param sessionCode 支付会话码，查询/关闭/对账的唯一凭据
 * @param provider 实际使用的渠道标识
 * @param methodName 实际使用的支付方式资源名
 * @param responseType 前端处理方式：REDIRECT 跳转 / QRCODE_URL 展示二维码图片 /
 *     QRCODE_CONTENT 前端自行渲染二维码 / JSAPI 调起 JS / MANUAL 展示人工收款指引
 * @param url 跳转地址或二维码图片地址，{@code MANUAL} 时为空
 * @param payload 渠道原始参数，{@code MANUAL} 时为银行账户/收款码等信息
 * @param expiresAt 会话过期时间
 * @param phase 会话初始状态
 * @param reused 是否为幂等命中（同一幂等键的重复请求）
 */
public record PaymentCreateResult(
    String sessionCode,
    String provider,
    String methodName,
    String responseType,
    String url,
    Map<String, Object> payload,
    Instant expiresAt,
    String phase,
    boolean reused
) {
}
