package run.pangu.payment.provider;

import com.alipay.v3.ApiClient;
import com.alipay.v3.api.AlipayTradeApi;
import com.alipay.v3.model.AlipayTradeCloseModel;
import com.alipay.v3.model.AlipayTradePrecreateModel;
import com.alipay.v3.model.AlipayTradePrecreateResponseModel;
import com.alipay.v3.model.AlipayTradeQueryModel;
import com.alipay.v3.model.AlipayTradeQueryResponseModel;
import com.alipay.v3.util.AlipaySignature;
import com.alipay.v3.util.model.AlipayConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.Errors;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import run.pangu.payment.context.PaymentCallbackContext;
import run.pangu.payment.context.PaymentInitiateContext;
import run.pangu.payment.context.PaymentQueryContext;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentMethodConfig;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.payload.PaymentInitiatePayload;
import run.pangu.payment.service.PaymentConfigService;
import run.pangu.payment.service.PaymentStateService;

/**
 * 支付宝渠道。
 *
 * <p>配置项为 {@code appId / merchantPrivateKey / alipayPublicKey / encryptKey / serverUrl}，
 * 与支付宝开放平台「自研应用」的密钥形态一一对应。
 *
 * <p>实现采用支付宝开放平台的「当面付预下单」（{@code alipay.trade.precreate}）：
 * 服务端生成收款二维码内容，PC 与移动端都能扫码支付，
 * 回调走 RSA2 验签后回写状态，补偿查询走 {@code alipay.trade.query}，
 * 关单走 {@code alipay.trade.close}。下单、回调、查单、关单四段链路闭合。
 */
@Slf4j
@Component
public class AlipayPaymentMethodProvider implements PaymentMethodProvider {

    private static final String DEFAULT_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final String CHARSET = "UTF-8";
    private static final String SIGN_TYPE = "RSA2";

    private final PaymentConfigService configService;
    private final PaymentStateService stateService;

    public AlipayPaymentMethodProvider(PaymentConfigService configService,
        PaymentStateService stateService) {
        this.configService = configService;
        this.stateService = stateService;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.ALIPAY;
    }

    @Override
    public String getDisplayName() {
        return PaymentProvider.ALIPAY.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "支付宝当面付，生成收款二维码供用户扫码支付";
    }

    @Override
    public List<PaymentScene> getSupportedScenes() {
        return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB);
    }

    @Override
    public List<Map<String, Object>> getConfigFormSchema() {
        return List.of(
            field("appId", "应用 AppID", "text", true, false),
            field("merchantPrivateKey", "应用私钥", "textarea", true, true),
            field("alipayPublicKey", "支付宝公钥", "textarea", true, false),
            field("encryptKey", "接口内容加密密钥（可选）", "password", false, true),
            field("serverUrl", "网关地址", "text", false, false)
        );
    }

    @Override
    public Errors validateSettings(PaymentMethodConfig config) {
        var errors = config.errors();
        for (String required : List.of("appId", "merchantPrivateKey", "alipayPublicKey")) {
            if (config.missing(required)) {
                errors.rejectValue("settings." + required, "required", "该字段不能为空");
            }
        }
        String serverUrl = config.getString("serverUrl");
        if (serverUrl != null && !serverUrl.isBlank()
            && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            errors.rejectValue("settings.serverUrl", "invalid", "网关地址必须以 http(s):// 开头");
        }
        return errors;
    }

    @Override
    public Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange) {
        return configService.get(methodName(context.method()))
            .flatMap(config -> Mono.fromCallable(() -> {
                AlipayTradeApi api = newAlipayApi(config);
                AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
                model.setOutTradeNo(context.sessionCode());
                model.setTotalAmount(context.amount().toPlainString());
                model.setSubject(truncate(context.subject(), 256, "支付订单"));
                model.setNotifyUrl(context.callbackUrl());
                model.setTimeoutExpress("30m");
                AlipayTradePrecreateResponseModel resp = api.precreate(model);
                String qrCode = resp.getQrCode();
                if (qrCode == null || qrCode.isBlank()) {
                    throw new IllegalStateException("支付宝未返回二维码内容");
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("qrCode", qrCode);
                params.put("outTradeNo", resp.getOutTradeNo());
                params.put("sessionCode", context.sessionCode());
                return PaymentInitiatePayload.builder()
                    .responseType(run.pangu.payment.payload.PaymentResponseType.QRCODE_URL)
                    .url(qrCode)
                    .providerTradeNo(resp.getOutTradeNo())
                    .params(params)
                    .message("请使用支付宝扫码支付")
                    .build();
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 支付宝异步通知处理。
     *
     * <p>支付宝以 POST form（application/x-www-form-urlencoded）发送异步通知，
     * 查询参数兼容保留；签名原文以合并后的参数为准。
     *
     * <p>除验签外还做三层校验，防止伪造或张冠李戴的回调：
     * 支付宝公钥未配置直接拒绝（空公钥验签必然通过或失去意义）；
     * {@code out_trade_no} 必须等于本会话码；{@code total_amount} 必须等于会话金额。
     */
    @Override
    public Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange) {
        return exchange.getFormData()
            .defaultIfEmpty(new org.springframework.util.LinkedMultiValueMap<>())
            .map(form -> {
                Map<String, String> params = flatten(exchange.getRequest().getQueryParams());
                flatten(form).forEach(params::putIfAbsent);
                return params;
            })
            .flatMap(params -> configService.get(methodName(context.method()))
                .flatMap(config -> Mono.fromCallable(() -> {
                    String alipayPublicKey = normalizePem(config.getString("alipayPublicKey", ""));
                    if (alipayPublicKey.isBlank()) {
                        // 未配置验签公钥时无法确认来源，宁可拒绝也不放行
                        return false;
                    }
                    return AlipaySignature.verifyV1(params, alipayPublicKey, CHARSET, SIGN_TYPE);
                }).subscribeOn(Schedulers.boundedElastic()).flatMap(valid -> {
                    if (!valid) {
                        log.warn("支付宝回调验签失败: code={}", context.sessionCode());
                        return ServerResponse.badRequest()
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("failure");
                    }
                    String tradeStatus = params.get("trade_status");
                    if (!"TRADE_SUCCESS".equals(tradeStatus)
                        && !"TRADE_FINISHED".equals(tradeStatus)) {
                        return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("success");
                    }
                    PaymentSession session = context.session();
                    String outTradeNo = params.get("out_trade_no");
                    if (outTradeNo == null
                        || !outTradeNo.equals(context.sessionCode())) {
                        log.warn("支付宝回调商户订单号与会话不符，拒绝: expect={} actual={} code={}",
                            context.sessionCode(), outTradeNo, context.sessionCode());
                        return ServerResponse.badRequest()
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("failure");
                    }
                    String totalAmount = params.get("total_amount");
                    if (session != null && session.getSpec() != null
                        && session.getSpec().getAmount() != null
                        && (totalAmount == null || session.getSpec().getAmount()
                            .compareTo(new java.math.BigDecimal(totalAmount)) != 0)) {
                        log.warn("支付宝回调金额与会话不符，拒绝: expect={} actual={} code={}",
                            session.getSpec().getAmount(), totalAmount, context.sessionCode());
                        return ServerResponse.badRequest()
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("failure");
                    }
                    Map<String, Object> raw = new LinkedHashMap<>(params);
                    return stateService.markPaid(context.session(), params.get("trade_no"), raw)
                        .then(ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("success"));
                })))
            .onErrorResume(e -> {
                log.error("支付宝回调处理异常: code={}", context.sessionCode(), e);
                return ServerResponse.status(500).contentType(MediaType.TEXT_PLAIN)
                    .bodyValue("failure");
            });
    }

    @Override
    public Mono<Void> reconcilePayment(PaymentQueryContext context) {
        return configService.get(methodName(context.method()))
            .flatMap(config -> Mono.fromCallable(() -> {
                AlipayTradeApi api = newAlipayApi(config);
                AlipayTradeQueryModel model = new AlipayTradeQueryModel();
                model.setOutTradeNo(context.sessionCode());
                AlipayTradeQueryResponseModel resp = api.query(model);
                String status = resp.getTradeStatus();
                if ("TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status)) {
                    return stateService.markPaid(context.session(), resp.getTradeNo(),
                        Map.of("query", status, "tradeNo", String.valueOf(resp.getTradeNo())));
                }
                return Mono.<PaymentSession>empty();
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .onErrorResume(e -> {
                log.warn("支付宝查单失败: code={}", context.sessionCode(), e);
                return Mono.empty();
            });
    }

    @Override
    public Mono<Void> closePayment(PaymentSession session, PaymentMethod method) {
        return configService.get(methodName(method))
            .flatMap(config -> Mono.fromCallable(() -> {
                AlipayTradeApi api = newAlipayApi(config);
                AlipayTradeCloseModel model = new AlipayTradeCloseModel();
                model.setOutTradeNo(session.getSpec().getSessionCode());
                api.close(model);
                return true;
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .onErrorResume(e -> {
                log.warn("支付宝关单失败，忽略: code={}",
                    session.getSpec() == null ? null : session.getSpec().getSessionCode(), e);
                return Mono.empty();
            });
    }

    /** 构造支付宝客户端：每次按最新配置创建，配置变更后立即生效。 */
    private AlipayTradeApi newAlipayApi(PaymentMethodConfig config) throws Exception {
        AlipayConfig alipayConfig = new AlipayConfig();
        String serverUrl = config.getString("serverUrl");
        alipayConfig.setServerUrl(serverUrl == null || serverUrl.isBlank()
            ? DEFAULT_GATEWAY : serverUrl);
        alipayConfig.setAppId(config.getString("appId", ""));
        alipayConfig.setPrivateKey(normalizePem(config.getString("merchantPrivateKey", "")));
        alipayConfig.setAlipayPublicKey(normalizePem(config.getString("alipayPublicKey", "")));
        String encryptKey = config.getString("encryptKey");
        if (encryptKey != null && !encryptKey.isBlank()) {
            alipayConfig.setEncryptKey(encryptKey);
        }
        ApiClient apiClient = new ApiClient();
        apiClient.setAlipayConfig(alipayConfig);
        return new AlipayTradeApi(apiClient);
    }

    /**
     * 支付宝要求密钥为纯 Base64 正文，不带 PEM 头尾与换行。
     * 后台粘贴时用户常带 {@code -----BEGIN...}，这里统一清洗。
     */
    private String normalizePem(String key) {
        if (key == null) {
            return "";
        }
        return key.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");
    }

    private String methodName(PaymentMethod method) {
        return method == null || method.getMetadata() == null
            ? null : method.getMetadata().getName();
    }

    private Map<String, String> flatten(MultiValueMap<String, String> multi) {
        Map<String, String> flat = new LinkedHashMap<>();
        multi.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                flat.put(k, v.get(0));
            }
        });
        return flat;
    }

    private String truncate(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Map<String, Object> field(String name, String label, String type, boolean required,
        boolean secret) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("label", label);
        f.put("type", type);
        f.put("required", required);
        f.put("secret", secret);
        return f;
    }
}
