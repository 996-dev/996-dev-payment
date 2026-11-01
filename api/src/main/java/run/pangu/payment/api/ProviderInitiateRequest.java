package run.pangu.payment.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 发起支付请求。
 *
 * @param sessionCode 会话码，可作为商户订单号传给渠道
 * @param methodName 支付方式资源名
 * @param amount 应付金额
 * @param currency 币种
 * @param subject 支付标题
 * @param description 支付描述
 * @param clientIp 终端 IP
 * @param settings 渠道配置（已合并 ConfigMap 与 Secret 中的明文值）
 */
public record ProviderInitiateRequest(
    String sessionCode,
    String methodName,
    BigDecimal amount,
    String currency,
    String subject,
    String description,
    String clientIp,
    Map<String, String> settings
) {
}
