package run.pangu.payment.provider;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.Errors;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
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
import run.pangu.payment.util.SignUtils;

/**
 * 易支付（聚合支付）渠道。
 *
 * <p>配置项为 {@code serverUrl / merchantId / merchantPrivateKey / type / cid}，
 * 其中 {@code merchantPrivateKey} 为商户密钥，存于 Secret。
 *
 * <p>协议要点：
 * <ul>
 *   <li>下单：拼签后跳转 {@code {serverUrl}/submit.php}</li>
 *   <li>回调：明文回传，{@code sign = MD5(排序后参数 + 密钥)}，{@code trade_status=TRADE_SUCCESS} 视为成功</li>
 *   <li>查单：{@code {serverUrl}/api.php?act=order}</li>
 * </ul>
 */
@Slf4j
@Component
public class EasyPaymentMethodProvider implements PaymentMethodProvider {

    /**
     * 插件容器不暴露 {@code WebClient.Builder}，这里自建实例。
     * 单次请求超时统一在 Mono 层用 {@code timeout()} 控制，避免连接池层面配置漂移。
     */
    private static final WebClient WEB_CLIENT = WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
        .build();

    private final PaymentConfigService configService;
    private final PaymentStateService stateService;

    public EasyPaymentMethodProvider(PaymentConfigService configService,
        PaymentStateService stateService) {
        this.configService = configService;
        this.stateService = stateService;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.EASY_PAY;
    }

    @Override
    public String getDisplayName() {
        return PaymentProvider.EASY_PAY.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "兼容彩虹易支付协议，可对接任意支持该协议的上游支付通道";
    }

    @Override
    public List<PaymentScene> getSupportedScenes() {
        return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB);
    }

    @Override
    public List<Map<String, Object>> getConfigFormSchema() {
        return List.of(
            field("serverUrl", "接口地址", "text", true, false),
            field("merchantId", "商户 ID（pid）", "text", true, false),
            field("merchantPrivateKey", "商户密钥", "password", true, true),
            field("type", "支付类型（alipay/wxpay）", "text", true, false),
            field("cid", "通道 ID（可选）", "text", false, false)
        );
    }

    @Override
    public Errors validateSettings(PaymentMethodConfig config) {
        var errors = config.errors();
        for (String required : List.of("serverUrl", "merchantId", "merchantPrivateKey", "type")) {
            if (config.missing(required)) {
                errors.rejectValue("settings." + required, "required", "该字段不能为空");
            }
        }
        String serverUrl = config.getString("serverUrl");
        if (serverUrl != null && !serverUrl.isBlank()
            && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            errors.rejectValue("settings.serverUrl", "invalid", "接口地址必须以 http(s):// 开头");
        }
        return errors;
    }

    @Override
    public Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange) {
        return configService.get(methodName(context.method())).map(config -> {
            String serverUrl = trimTrailingSlash(config.getString("serverUrl", ""));
            String merchantId = config.getString("merchantId", "");
            String key = config.getString("merchantPrivateKey", "");

            Map<String, String> params = new LinkedHashMap<>();
            params.put("pid", merchantId);
            params.put("type", config.getString("type", "alipay"));
            params.put("out_trade_no", context.sessionCode());
            params.put("notify_url", context.callbackUrl());
            if (context.returnUrl() != null) {
                params.put("return_url", context.returnUrl());
            }
            params.put("name", context.subject() == null ? "商品" : context.subject());
            params.put("money", context.amount().toPlainString());
            String cid = config.getString("cid");
            if (cid != null && !cid.isBlank()) {
                params.put("cid", cid);
            }
            params.put("sign", SignUtils.easyPaySign(params, key));
            params.put("sign_type", "MD5");

            String url = serverUrl + "/submit.php?" + SignUtils.buildQuery(params, true);
            return PaymentInitiatePayload.redirect(url, null);
        });
    }

    /**
     * 易支付异步通知处理。
     *
     * <p>协议以 GET 查询参数回传，POST form 兼容保留；签名以合并后的参数为准。
     *
     * <p>除验签外还校验：商户密钥未配置直接拒绝（空密钥可被任何人伪造签名）；
     * {@code out_trade_no} 必须等于本会话码；{@code money} 必须等于会话金额。
     */
    @Override
    public Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange) {
        return exchange.getFormData()
            .defaultIfEmpty(new LinkedMultiValueMap<>())
            .map(form -> {
                Map<String, String> params = queryParams(exchange);
                form.toSingleValueMap().forEach(params::putIfAbsent);
                return params;
            })
            .flatMap(params -> configService.get(methodName(context.method()))
                .flatMap(config -> {
                    String key = config.getString("merchantPrivateKey", "");
                    if (key.isBlank()) {
                        log.warn("易支付回调被拒绝：商户密钥未配置，无法验签: code={}",
                            context.sessionCode());
                        return ServerResponse.badRequest()
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("fail");
                    }
                    String received = params.get("sign");
                    String expected = SignUtils.easyPaySign(params, key);
                    if (received == null || !expected.equalsIgnoreCase(received)) {
                        log.warn("易支付回调验签失败: code={}", context.sessionCode());
                        return ServerResponse.badRequest()
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("fail");
                    }
                    if (!"TRADE_SUCCESS".equals(params.get("trade_status"))) {
                        return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("success");
                    }
                    String outTradeNo = params.get("out_trade_no");
                    if (outTradeNo == null || !outTradeNo.equals(context.sessionCode())) {
                        log.warn("易支付回调商户订单号与会话不符，拒绝: expect={} actual={} code={}",
                            context.sessionCode(), outTradeNo, context.sessionCode());
                        return ServerResponse.badRequest()
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("fail");
                    }
                    PaymentSession session = context.session();
                    String money = params.get("money");
                    if (session != null && session.getSpec() != null
                        && session.getSpec().getAmount() != null
                        && (money == null || session.getSpec().getAmount()
                            .compareTo(new java.math.BigDecimal(money)) != 0)) {
                        log.warn("易支付回调金额与会话不符，拒绝: expect={} actual={} code={}",
                            session.getSpec().getAmount(), money, context.sessionCode());
                        return ServerResponse.badRequest()
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("fail");
                    }
                    Map<String, Object> raw = new LinkedHashMap<>(params);
                    return stateService.markPaid(context.session(), params.get("trade_no"), raw)
                        .then(ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("success"));
                }));
    }

    /**
     * 主动查单：命中已支付时回写状态。
     *
     * <p>易支付查单接口需带商户密钥明文，因此只在服务端调用，不暴露给前端。
     */
    @Override
    public Mono<Void> reconcilePayment(PaymentQueryContext context) {
        return configService.get(methodName(context.method()))
            .flatMap(config -> {
                String serverUrl = trimTrailingSlash(config.getString("serverUrl", ""));
                String pid = config.getString("merchantId", "");
                String key = config.getString("merchantPrivateKey", "");
                String outTradeNo = context.sessionCode();
                URI uri = UriComponentsBuilder.fromUriString(serverUrl + "/api.php")
                    .queryParam("act", "order")
                    .queryParam("pid", pid)
                    .queryParam("key", key)
                    .queryParam("out_trade_no", outTradeNo)
                    .build()
                    .toUri();
                return WEB_CLIENT.get().uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(body -> {
                        log.debug("易支付查单响应: {}", body);
                        if (body.contains("\"status\":1") || body.contains("\"status\": 1")) {
                            Map<String, Object> raw = Map.of("query", body);
                            return stateService.markPaid(context.session(), null, raw).then();
                        }
                        return Mono.empty();
                    })
                    .onErrorResume(e -> {
                        log.warn("易支付查单失败: code={}", outTradeNo, e);
                        return Mono.empty();
                    });
            });
    }

    @Override
    public Mono<Void> closePayment(PaymentSession session, PaymentMethod method) {
        // 易支付协议未提供关单接口，本地置为关闭即可，过期后上游会自动失效
        return Mono.empty();
    }

    private String methodName(PaymentMethod method) {
        return method == null || method.getMetadata() == null
            ? null : method.getMetadata().getName();
    }

    private Map<String, String> queryParams(ServerWebExchange exchange) {
        MultiValueMap<String, String> multi = exchange.getRequest().getQueryParams();
        Map<String, String> flat = new LinkedHashMap<>();
        multi.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                flat.put(k, v.get(0));
            }
        });
        return flat;
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
