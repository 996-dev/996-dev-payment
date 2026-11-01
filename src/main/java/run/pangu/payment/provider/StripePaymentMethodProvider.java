package run.pangu.payment.provider;

import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
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
 * Stripe 渠道（Checkout Session）。
 *
 * <p>配置项为 {@code apiKey / webhookSecret}。
 *
 * <p>下单创建 Stripe 托管收银台并返回跳转地址；回调走
 * {@code Stripe-Signature} 验签，只在 {@code checkout.session.completed}
 * 且 {@code payment_status=paid} 时确认支付。
 *
 * <p>API Key 通过 {@link RequestOptions} 逐请求传入而非常改静态字段，
 * 避免多个支付方式使用不同账号时互相覆盖。
 */
@Slf4j
@Component
public class StripePaymentMethodProvider implements PaymentMethodProvider {

    private final PaymentConfigService configService;
    private final PaymentStateService stateService;

    public StripePaymentMethodProvider(PaymentConfigService configService,
        PaymentStateService stateService) {
        this.configService = configService;
        this.stateService = stateService;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public String getDisplayName() {
        return PaymentProvider.STRIPE.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "Stripe 托管收银台，支持国际信用卡与本地支付方式";
    }

    @Override
    public List<PaymentScene> getSupportedScenes() {
        return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB);
    }

    @Override
    public List<Map<String, Object>> getConfigFormSchema() {
        return List.of(
            field("apiKey", "Secret Key（sk_...）", "password", true, true),
            field("webhookSecret", "Webhook 签名密钥（whsec_...）", "password", true, true)
        );
    }

    @Override
    public Errors validateSettings(PaymentMethodConfig config) {
        var errors = config.errors();
        if (config.missing("apiKey")) {
            errors.rejectValue("settings.apiKey", "required", "Secret Key 不能为空");
        } else if (!config.getString("apiKey").startsWith("sk_")) {
            errors.rejectValue("settings.apiKey", "invalid", "Secret Key 应以 sk_ 开头");
        }
        if (config.missing("webhookSecret")) {
            errors.rejectValue("settings.webhookSecret", "required", "Webhook 签名密钥不能为空");
        } else if (!config.getString("webhookSecret").startsWith("whsec_")) {
            errors.rejectValue("settings.webhookSecret", "invalid",
                "Webhook 签名密钥应以 whsec_ 开头");
        }
        return errors;
    }

    @Override
    public Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange) {
        return configService.get(methodName(context.method()))
            .flatMap(config -> Mono.fromCallable(() -> {
                RequestOptions options = RequestOptions.builder()
                    .setApiKey(config.getString("apiKey", ""))
                    .build();
                String currency = context.session() != null && context.session().getSpec() != null
                    && context.session().getSpec().getCurrency() != null
                        ? context.session().getSpec().getCurrency().toLowerCase() : "cny";
                String successUrl = context.returnUrl() != null && !context.returnUrl().isBlank()
                    ? context.returnUrl()
                    : context.callbackUrl(getProvider().getCode());

                SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(successUrl)
                    .putMetadata("sessionCode", String.valueOf(context.sessionCode()))
                    .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(currency)
                            .setUnitAmount(toMinorUnit(context.amount()))
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData
                                .builder()
                                .setName(truncate(context.subject(), 250, "Payment"))
                                .build())
                            .build())
                        .build())
                    .build();

                Session session = Session.create(params, options);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("stripeSessionId", session.getId());
                extra.put("sessionCode", context.sessionCode());
                return PaymentInitiatePayload.builder()
                    .responseType(PaymentResponseType.REDIRECT_URL)
                    .url(session.getUrl())
                    .providerTradeNo(session.getId())
                    .params(extra)
                    .message("正在跳转至 Stripe 收银台")
                    .build();
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
            .map(buf -> {
                byte[] bytes = new byte[buf.readableByteCount()];
                buf.read(bytes);
                DataBufferUtils.release(buf);
                return new String(bytes, StandardCharsets.UTF_8);
            })
            .defaultIfEmpty("")
            .flatMap(body -> configService.get(methodName(context.method()))
                .flatMap(config -> Mono.fromCallable(() -> Webhook.constructEvent(
                        body,
                        exchange.getRequest().getHeaders().getFirst("Stripe-Signature"),
                        config.getString("webhookSecret", "")))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(event -> handleStripeEvent(event, context)))
                .onErrorResume(e -> {
                    log.warn("Stripe 回调验签失败: code={}", context.sessionCode(), e);
                    return ServerResponse.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", "invalid signature"));
                }))
            .onErrorResume(e -> {
                log.error("Stripe 回调处理异常: code={}", context.sessionCode(), e);
                return ServerResponse.status(500).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "handler failure"));
            });
    }

    private Mono<ServerResponse> handleStripeEvent(Event event, PaymentCallbackContext context) {
        // 只处理支付完成事件；其余事件（如 payment_intent.created）直接确认已收到
        if (!"checkout.session.completed".equals(event.getType())) {
            log.debug("忽略 Stripe 事件类型: {}", event.getType());
            return acknowledged();
        }
        Session checkout = (Session) event.getDataObjectDeserializer()
            .getObject().orElse(null);
        if (checkout == null || !"paid".equals(checkout.getPaymentStatus())) {
            log.info("Stripe 会话未支付完成，忽略: code={}", context.sessionCode());
            return acknowledged();
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("eventId", event.getId());
        raw.put("eventType", event.getType());
        raw.put("stripeSessionId", checkout.getId());
        raw.put("paymentStatus", checkout.getPaymentStatus());
        raw.put("amountTotal", checkout.getAmountTotal());
        return stateService.markPaid(context.session(), checkout.getId(), raw)
            .then(acknowledged());
    }

    private Mono<ServerResponse> acknowledged() {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("received", true));
    }

    /**
     * Stripe 不提供「按商户单号查单」的公开接口，状态以 Webhook 为准；
     * 此处保持空实现，需要补偿时由后台重新拉取会话。
     */
    @Override
    public Mono<Void> reconcilePayment(PaymentQueryContext context) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> closePayment(PaymentSession session, PaymentMethod method) {
        return Mono.empty();
    }

    /** 金额转最小货币单位：多数币种 2 位小数，JPY 等零小数币种需单独处理。 */
    private long toMinorUnit(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
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
