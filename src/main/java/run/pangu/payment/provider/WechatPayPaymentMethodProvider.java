package run.pangu.payment.provider;

import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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
import run.pangu.payment.payload.PaymentResponseType;
import run.pangu.payment.service.PaymentConfigService;
import run.pangu.payment.service.PaymentStateService;

/**
 * 微信支付渠道（APIv3）。
 *
 * <p>配置项为 {@code appId / merchantId / merchantPrivateKey / merchantSerialNumber /
 * apiV3Key / platformPublicKey / platformPublicKeyId}。
 *
 * <p>采用 Native 下单（{@code /v3/pay/transactions/native}）：返回 code_url，
 * 前端渲染成二维码扫码支付。回调为 APIv3 通知，使用平台公钥验签 + AEAD 解密，
 * 验签通过且 {@code trade_state=SUCCESS} 才回写支付成功。
 */
@Slf4j
@Component
public class WechatPayPaymentMethodProvider implements PaymentMethodProvider {

    private final PaymentConfigService configService;
    private final PaymentStateService stateService;

    public WechatPayPaymentMethodProvider(PaymentConfigService configService,
        PaymentStateService stateService) {
        this.configService = configService;
        this.stateService = stateService;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.WECHAT_PAY;
    }

    @Override
    public String getDisplayName() {
        return PaymentProvider.WECHAT_PAY.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "微信支付 APIv3 Native 下单，生成二维码供微信扫码支付";
    }

    @Override
    public List<PaymentScene> getSupportedScenes() {
        return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB, PaymentScene.MINI_PROGRAM);
    }

    @Override
    public List<Map<String, Object>> getConfigFormSchema() {
        return List.of(
            field("appId", "应用 AppID", "text", true, false),
            field("merchantId", "商户号 MchID", "text", true, false),
            field("merchantSerialNumber", "商户证书序列号", "text", true, false),
            field("merchantPrivateKey", "商户私钥（apiclient_key.pem 内容）", "textarea", true, true),
            field("apiV3Key", "APIv3 密钥", "password", true, true),
            field("platformPublicKey", "微信支付平台公钥", "textarea", true, false),
            field("platformPublicKeyId", "平台公钥 ID", "text", true, false)
        );
    }

    @Override
    public Errors validateSettings(PaymentMethodConfig config) {
        var errors = config.errors();
        for (String required : List.of("appId", "merchantId", "merchantSerialNumber",
            "merchantPrivateKey", "apiV3Key", "platformPublicKey", "platformPublicKeyId")) {
            if (config.missing(required)) {
                errors.rejectValue("settings." + required, "required", "该字段不能为空");
            }
        }
        return errors;
    }

    @Override
    public Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange) {
        return configService.get(methodName(context.method()))
            .flatMap(config -> Mono.fromCallable(() -> {
                NativePayService service = newNativePayService(config);
                PrepayRequest request = new PrepayRequest();
                request.setAppid(config.getString("appId", ""));
                request.setMchid(config.getString("merchantId", ""));
                request.setDescription(truncate(context.subject(), 127, "支付订单"));
                request.setOutTradeNo(context.sessionCode());
                request.setNotifyUrl(context.callbackUrl());

                Amount amount = new Amount();
                amount.setTotal(toCents(context.amount()));
                amount.setCurrency(context.session() != null && context.session().getSpec() != null
                    && context.session().getSpec().getCurrency() != null
                        ? context.session().getSpec().getCurrency() : "CNY");
                request.setAmount(amount);

                PrepayResponse response = service.prepay(request);
                String codeUrl = response.getCodeUrl();
                if (codeUrl == null || codeUrl.isBlank()) {
                    throw new IllegalStateException("微信支付未返回 code_url");
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("codeUrl", codeUrl);
                params.put("sessionCode", context.sessionCode());
                return PaymentInitiatePayload.builder()
                    .responseType(PaymentResponseType.QRCODE_URL)
                    .url(codeUrl)
                    .params(params)
                    .message("请使用微信扫码支付")
                    .build();
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange) {
        return readBody(exchange)
            .flatMap(body -> configService.get(methodName(context.method()))
                .flatMap(config -> Mono.fromCallable(() -> {
                    RequestParam requestParam = new RequestParam.Builder()
                        .serialNumber(header(exchange, "Wechatpay-Serial"))
                        .nonce(header(exchange, "Wechatpay-Nonce"))
                        .signature(header(exchange, "Wechatpay-Signature"))
                        .timestamp(header(exchange, "Wechatpay-Timestamp"))
                        .signType(header(exchange, "Wechatpay-Signature-Type"))
                        .body(body)
                        .build();
                    NotificationParser parser = new NotificationParser(newConfig(config));
                    return parser.parse(requestParam, Transaction.class);
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(transaction -> {
                    if (transaction.getTradeState() != Transaction.TradeStateEnum.SUCCESS) {
                        log.info("微信回调非成功状态: {} code={}", transaction.getTradeState(),
                            context.sessionCode());
                        return successResponse();
                    }
                    // 通知里的商户订单号必须就是本会话码；金额必须与会话一致。
                    // 两者任一不符都说明通知与目标会话对不上，拒绝入账。
                    if (transaction.getOutTradeNo() == null
                        || !transaction.getOutTradeNo().equals(context.sessionCode())) {
                        log.warn("微信回调商户订单号与会话不符，拒绝: expect={} actual={} code={}",
                            context.sessionCode(), transaction.getOutTradeNo(),
                            context.sessionCode());
                        return ServerResponse.status(400).contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("code", "FAIL", "message", "订单号不匹配"));
                    }
                    PaymentSession session = context.session();
                    if (session != null && session.getSpec() != null
                        && session.getSpec().getAmount() != null) {
                        int expectedCents = toCents(session.getSpec().getAmount());
                        int actualCents = transaction.getAmount() == null
                            || transaction.getAmount().getTotal() == null
                            ? -1 : transaction.getAmount().getTotal();
                        if (expectedCents != actualCents) {
                            log.warn("微信回调金额与会话不符，拒绝: expect={} actual={} code={}",
                                expectedCents, actualCents, context.sessionCode());
                            return ServerResponse.status(400)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of("code", "FAIL", "message", "金额不匹配"));
                        }
                    }
                    Map<String, Object> raw = new LinkedHashMap<>();
                    raw.put("outTradeNo", transaction.getOutTradeNo());
                    raw.put("transactionId", transaction.getTransactionId());
                    raw.put("tradeState", String.valueOf(transaction.getTradeState()));
                    raw.put("amount", transaction.getAmount() == null ? null
                        : transaction.getAmount().getTotal());
                    return stateService.markPaid(context.session(),
                            transaction.getTransactionId(), raw)
                        .then(successResponse());
                }))
            .onErrorResume(e -> {
                log.error("微信回调处理异常: code={}", context.sessionCode(), e);
                return ServerResponse.status(500).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("code", "FAIL", "message", "处理失败"));
            });
    }

    @Override
    public Mono<Void> reconcilePayment(PaymentQueryContext context) {
        return configService.get(methodName(context.method()))
            .flatMap(config -> Mono.fromCallable(() -> {
                NativePayService service = newNativePayService(config);
                com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest q =
                    new com.wechat.pay.java.service.payments.nativepay.model
                        .QueryOrderByOutTradeNoRequest();
                q.setMchid(config.getString("merchantId", ""));
                q.setOutTradeNo(context.sessionCode());
                Transaction tx = service.queryOrderByOutTradeNo(q);
                if (tx.getTradeState() == Transaction.TradeStateEnum.SUCCESS) {
                    return stateService.markPaid(context.session(), tx.getTransactionId(),
                        Map.of("query", "SUCCESS")).then();
                }
                return Mono.<Void>empty();
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .onErrorResume(e -> {
                log.warn("微信查单失败: code={}", context.sessionCode(), e);
                return Mono.empty();
            });
    }

    @Override
    public Mono<Void> closePayment(PaymentSession session, PaymentMethod method) {
        return configService.get(methodName(method))
            .flatMap(config -> Mono.fromCallable(() -> {
                NativePayService service = newNativePayService(config);
                com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest req =
                    new com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest();
                req.setMchid(config.getString("merchantId", ""));
                req.setOutTradeNo(session.getSpec().getSessionCode());
                service.closeOrder(req);
                return true;
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .onErrorResume(e -> {
                log.warn("微信关单失败，忽略", e);
                return Mono.empty();
            });
    }

    private RSAPublicKeyConfig newConfig(PaymentMethodConfig config) {
        return new RSAPublicKeyConfig.Builder()
            .merchantId(config.getString("merchantId", ""))
            .privateKey(normalizePem(config.getString("merchantPrivateKey", "")))
            .merchantSerialNumber(config.getString("merchantSerialNumber", ""))
            .publicKey(normalizePem(config.getString("platformPublicKey", "")))
            .publicKeyId(config.getString("platformPublicKeyId", ""))
            .apiV3Key(config.getString("apiV3Key", ""))
            .build();
    }

    private NativePayService newNativePayService(PaymentMethodConfig config) {
        return new NativePayService.Builder().config(newConfig(config)).build();
    }

    /** 金额转分：微信支付以分为单位，四舍五入避免精度丢失。 */
    private int toCents(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .intValueExact();
    }

    private String normalizePem(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        // 微信 SDK 要求以 PEM 头开始；用户若只贴了正文则补全头尾
        if (!trimmed.startsWith("-----BEGIN")) {
            String body = trimmed.replaceAll("\\s+", "");
            StringBuilder sb = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
            for (int i = 0; i < body.length(); i += 64) {
                sb.append(body, i, Math.min(i + 64, body.length())).append('\n');
            }
            sb.append("-----END PRIVATE KEY-----");
            return sb.toString();
        }
        return trimmed;
    }

    /** 回调请求体上限：微信 APIv3 通知通常不超过几 KB，超限直接拒绝。 */
    private static final int MAX_CALLBACK_BODY_BYTES = 64 * 1024;

    private Mono<String> readBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_CALLBACK_BODY_BYTES)
            .map(buf -> {
                byte[] bytes = new byte[buf.readableByteCount()];
                buf.read(bytes);
                DataBufferUtils.release(buf);
                return new String(bytes, StandardCharsets.UTF_8);
            })
            .defaultIfEmpty("");
    }

    private String header(ServerWebExchange exchange, String name) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        return v == null ? "" : v;
    }

    private Mono<ServerResponse> successResponse() {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", "SUCCESS", "message", "成功"));
    }

    private String methodName(PaymentMethod method) {
        return method == null || method.getMetadata() == null
            ? null : method.getMetadata().getName();
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
