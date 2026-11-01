package run.pangu.payment.provider;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.Errors;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import run.pangu.payment.api.PaymentConfigField;
import run.pangu.payment.api.PaymentProviderExtension;
import run.pangu.payment.api.ProviderCallbackRequest;
import run.pangu.payment.api.ProviderCallbackResult;
import run.pangu.payment.api.ProviderInitiateRequest;
import run.pangu.payment.api.ProviderInitiateResult;
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
 * 把第三方插件的 {@link PaymentProviderExtension} 适配成本插件内部的
 * {@link PaymentMethodProvider}，使其获得与内置渠道完全一致的能力：
 * 收银台展示、配置表单、下单、回调、对账。
 *
 * <p>适配层承担三件事：
 * <ol>
 *   <li>把内部上下文降级为纯 JDK 类型，避免把内部类泄漏给外部扩展实现；</li>
 *   <li>把扩展返回值映射回内部载荷，并做金额一致性校验；</li>
 *   <li>兜底异常与空值，渠道实现抛错不能击穿主链路。</li>
 * </ol>
 */
@Slf4j
public class ExtensionPaymentProviderAdapter implements PaymentMethodProvider {

    private final PaymentProviderExtension delegate;
    private final PaymentConfigService configService;
    private final PaymentStateService stateService;

    public ExtensionPaymentProviderAdapter(PaymentProviderExtension delegate,
        PaymentConfigService configService, PaymentStateService stateService) {
        this.delegate = delegate;
        this.configService = configService;
        this.stateService = stateService;
    }

    @Override
    public PaymentProvider getProvider() {
        // 外部渠道没有对应枚举值，统一返回 null；
        // 内部代码一律使用 getCode()，不得调用 getProvider().getCode()
        return null;
    }

    @Override
    public String getCode() {
        return delegate.getCode();
    }

    @Override
    public String getDisplayName() {
        return delegate.getDisplayName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public List<PaymentScene> getSupportedScenes() {
        List<String> codes = delegate.getSupportedScenes();
        if (codes == null || codes.isEmpty()) {
            return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB);
        }
        return codes.stream()
            .map(code -> {
                try {
                    return PaymentScene.of(code);
                } catch (RuntimeException e) {
                    log.warn("扩展渠道 {} 声明了未知支付场景: {}", getCode(), code);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .toList();
    }


    /** 渠道配置统一转成字符串映射：扩展接口只暴露简单类型，避免跨插件类型漂移。 */
    private Map<String, String> settingsOf(PaymentMethodConfig config) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (config == null || config.getSettings() == null) {
            return settings;
        }
        config.getSettings().forEach((key, value) -> {
            if (value != null) {
                settings.put(key, String.valueOf(value));
            }
        });
        return settings;
    }

    @Override
    public List<Map<String, Object>> getConfigFormSchema() {
        List<PaymentConfigField> fields = delegate.getConfigFormSchema();
        return fields == null ? List.of() : fields.stream().map(this::toFieldMap).toList();
    }

    private Map<String, Object> toFieldMap(PaymentConfigField field) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", field.name());
        map.put("label", field.label());
        map.put("type", field.type());
        map.put("required", field.required());
        map.put("secret", field.secret());
        if (field.placeholder() != null) {
            map.put("placeholder", field.placeholder());
        }
        if (field.help() != null) {
            map.put("help", field.help());
        }
        if (field.options() != null && !field.options().isEmpty()) {
            map.put("options", field.options().stream()
                .map(o -> Map.of("label", o.label(), "value", o.value()))
                .toList());
        }
        return map;
    }

    @Override
    public Errors validateSettings(PaymentMethodConfig config) {
        var errors = config.errors();
        List<String> messages;
        try {
            messages = delegate.validateSettings(settingsOf(config));
        } catch (RuntimeException e) {
            log.warn("扩展渠道 {} 校验配置异常: {}", getCode(), e.getMessage());
            errors.reject("invalid", "渠道校验失败: " + e.getMessage());
            return errors;
        }
        if (messages != null) {
            messages.forEach(m -> errors.reject("invalid", m));
        }
        return errors;
    }

    @Override
    public Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange) {
        return configService.get(context.method().getMetadata().getName())
            .flatMap(config -> delegate.initiate(new ProviderInitiateRequest(
                context.sessionCode(),
                context.method().getMetadata().getName(),
                context.amount(),
                currencyOf(context.session()),
                context.subject() == null ? "" : context.subject(),
                context.description(),
                clientIpOf(exchange),
                settingsOf(config)
            )))
            .map(this::toPayload)
            .onErrorResume(e -> {
                log.error("扩展渠道 {} 下单失败: {}", getCode(), e.getMessage(), e);
                return Mono.error(e);
            });
    }

    private String currencyOf(PaymentSession session) {
        String currency = session == null || session.getSpec() == null
            ? null : session.getSpec().getCurrency();
        return currency == null || currency.isBlank() ? "CNY" : currency;
    }

    private String clientIpOf(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }
        var headers = exchange.getRequest().getHeaders();
        String forwarded = headers.getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? null : remote.getAddress().getHostAddress();
    }

    private PaymentInitiatePayload toPayload(ProviderInitiateResult result) {
        if (result == null) {
            return PaymentInitiatePayload.builder()
                .responseType(PaymentResponseType.MANUAL)
                .message("渠道未返回支付信息")
                .build();
        }
        return PaymentInitiatePayload.builder()
            .responseType(toResponseType(result.responseType()))
            .url(result.url())
            .params(result.payload() == null ? Map.of() : result.payload())
            .message(result.message())
            .build();
    }

    /** 扩展返回的字符串按枚举名映射，无法识别时退回 MANUAL，避免误跳转。 */
    private PaymentResponseType toResponseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaymentResponseType.MANUAL;
        }
        try {
            return PaymentResponseType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("扩展渠道 {} 返回未知响应类型: {}，按 MANUAL 处理", getCode(), raw);
            return PaymentResponseType.MANUAL;
        }
    }

    @Override
    public Mono<Void> reconcilePayment(PaymentQueryContext context) {
        var session = context.session();
        var spec = session.getSpec();
        String methodName = spec.getMethodName() == null
            ? methodNameOf(context) : spec.getMethodName();
        return configService.get(methodName)
            .flatMap(config -> delegate.query(spec.getSessionCode(), settingsOf(config)))
            .flatMap(result -> {
                if (result == null || !result.paid() || !amountMatches(session, result.amount())) {
                    return Mono.empty();
                }
                return stateService.markPaid(session, result.providerTradeNo(),
                        Map.of("source", "query", "provider", getCode()))
                    .then();
            })
            .onErrorResume(e -> {
                log.warn("扩展渠道 {} 查单失败: {}", getCode(), e.getMessage());
                return Mono.empty();
            });
    }

    private String methodNameOf(PaymentQueryContext context) {
        return context.method() == null || context.method().getMetadata() == null
            ? null : context.method().getMetadata().getName();
    }

    @Override
    public Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange) {
        return configService.get(context.method().getMetadata().getName())
            .flatMap(config -> delegate.handleCallback(new ProviderCallbackRequest(
                context.sessionCode(),
                context.method().getMetadata().getName(),
                flattenHeaders(exchange),
                flattenQuery(exchange),
                "",
                settingsOf(config)
            )))
            .flatMap(result -> {
                if (result == null) {
                    return ServerResponse.badRequest().bodyValue("empty callback result");
                }
                if (!result.paid()) {
                    return respond(result, null);
                }
                // 金额不符视为验签通过但报文被篡改，拒绝置为已支付
                if (!amountMatches(context.session(), result.amount())) {
                    log.error("扩展渠道 {} 回调金额不符，拒绝入账: code={} expect={} actual={}",
                        getCode(), context.sessionCode(),
                        amountOf(context.session()), result.amount());
                    return ServerResponse.status(400).bodyValue("amount mismatch");
                }
                return stateService.markPaid(context.session(), result.providerTradeNo(),
                        Map.of("source", "callback", "provider", getCode()))
                    .then(respond(result, context.session()));
            })
            .onErrorResume(e -> {
                log.error("扩展渠道 {} 回调异常: {}", getCode(), e.getMessage(), e);
                return ServerResponse.status(500).bodyValue("callback failed");
            });
    }

    private Mono<ServerResponse> respond(ProviderCallbackResult result, PaymentSession ignored) {
        int status = result.httpStatus() <= 0 ? 200 : result.httpStatus();
        String body = result.responseBody() == null ? "" : result.responseBody();
        return ServerResponse.status(status).bodyValue(body);
    }

    /** 渠道金额为 null 时跳过比对（部分渠道回调不带金额），否则必须精确相等。 */
    private boolean amountMatches(PaymentSession session, BigDecimal actual) {
        if (actual == null) {
            return true;
        }
        BigDecimal expect = amountOf(session);
        if (expect == null) {
            return true;
        }
        if (expect.compareTo(actual) != 0) {
            return false;
        }
        return true;
    }

    private BigDecimal amountOf(PaymentSession session) {
        return session == null || session.getSpec() == null ? null : session.getSpec().getAmount();
    }

    @Override
    public Mono<Void> closePayment(PaymentSession session, PaymentMethod method) {
        return configService.get(method.getMetadata().getName())
            .flatMap(config -> delegate.close(session.getSpec().getSessionCode(),
                settingsOf(config)))
            .onErrorResume(e -> {
                log.warn("扩展渠道 {} 关单失败（忽略）: {}", getCode(), e.getMessage());
                return Mono.empty();
            });
    }

    private Map<String, String> flattenHeaders(ServerWebExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (exchange == null) {
            return headers;
        }
        exchange.getRequest().getHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k.toLowerCase(), v.get(0));
            }
        });
        return headers;
    }

    private Map<String, String> flattenQuery(ServerWebExchange exchange) {
        Map<String, String> query = new LinkedHashMap<>();
        if (exchange == null) {
            return query;
        }
        exchange.getRequest().getQueryParams().forEach((k, v) -> {
            if (!v.isEmpty()) {
                query.put(k, v.get(0));
            }
        });
        return query;
    }
}
