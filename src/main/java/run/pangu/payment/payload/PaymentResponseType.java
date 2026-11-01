package run.pangu.payment.payload;

/**
 * 支付响应形态。
 *
 * <p>渠道实现据此决定返回给调用方的数据结构：是跳转链接、二维码内容、
 * JSAPI 参数、表单还是纯人工指引。业务方按此字段决定前端渲染方式。
 */
public enum PaymentResponseType {

    /** 返回一段需要 POST 到网关的 HTML 表单 */
    FORM,
    /** 返回二维码内容（字符串，需前端转成二维码图片） */
    QRCODE_URL,
    /** 返回需要浏览器跳转的地址 */
    REDIRECT_URL,
    /** 返回微信 JSAPI 调起参数 */
    JSAPI,
    /** 返回 App 调起参数 */
    APP,
    /** 返回小程序调起参数 */
    MINI_PROGRAM,
    /** 渠道已直连完成，无需前端二次动作 */
    DIRECT,
    /** 人工处理：展示收款信息与操作指引 */
    MANUAL
}
