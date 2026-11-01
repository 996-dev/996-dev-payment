package run.pangu.payment.service;

import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

/**
 * 创建支付的入参。
 *
 * <p>开放 API 与后台路由共用同一结构。{@code bizType / bizNo / idempotencyKey}
 * 三个字段承担业务解耦与幂等，其余字段只描述这一笔支付本身。
 */
@Data
public class PaymentCreateRequest {

    /** 渠道标识：alipay / wechatpay / stripe / easypay / qrcode / manual / bank-transfer。 */
    private String provider;

    /** 指定支付方式资源名；不填则按渠道自动选择第一个启用的方式。 */
    private String methodName;

    /** 金额，必须大于 0。 */
    private BigDecimal amount;

    /** 币种，默认 CNY。 */
    private String currency;

    /** 支付标题，展示在渠道收银台。 */
    private String subject;

    /** 支付描述。 */
    private String description;

    /** 来源业务类型，默认 default。 */
    private String bizType;

    /** 来源业务单号。 */
    private String bizNo;

    /** 支付场景：pc-web / mobile-web / mini-program。 */
    private String scene;

    /** 支付完成后回跳地址。 */
    private String returnUrl;

    /** 业务方异步通知地址，支付成功后会 POST 到该地址。 */
    private String notifyUrl;

    /** 幂等键：相同键的重复请求返回同一会话。 */
    private String idempotencyKey;

    /** 业务附加数据，原样透传给渠道实现。 */
    private Map<String, Object> bizData;

    /** 会话有效期（分钟），默认 30。 */
    private Integer expireMinutes;

    /** 终端 IP。HTTP 入口自动从请求头解析；跨插件 Java 调用需调用方显式传入。 */
    private String clientIp;
}
