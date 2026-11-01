package run.pangu.payment.api;

import java.util.Map;

/**
 * 渠道回调请求。
 *
 * @param sessionCode 从回调 URL 路径中解析出的会话码，可能为空（部分渠道回调不带）
 * @param methodName 支付方式资源名
 * @param headers 原始请求头（验签需要，键为小写）
 * @param query 查询参数
 * @param body 原始请求体（JSON / 表单原文，验签必须用原文，不能反序列化后再拼）
 * @param settings 渠道配置
 */
public record ProviderCallbackRequest(
    String sessionCode,
    String methodName,
    Map<String, String> headers,
    Map<String, String> query,
    String body,
    Map<String, String> settings
) {
}
