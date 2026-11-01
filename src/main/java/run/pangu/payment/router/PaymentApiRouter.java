package run.pangu.payment.router;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.pangu.payment.service.PaymentConfigService;
import run.pangu.payment.service.PaymentCreateRequest;
import run.pangu.payment.service.PaymentProviderRegistry;
import run.pangu.payment.service.PaymentService;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.provider.PaymentMethodProvider;

/**
 * 对外开放的支付 API。
 *
 * <p>路径统一挂在 {@code /apis/api.payment.pangu.tax/v1alpha1} 下：
 * 该前缀在 Halo 中与主题端 API 同级，允许匿名访问，
 * 便于第三方业务系统与支付渠道回调直接调用；写操作的鉴权由
 * {@link run.pangu.payment.security.ApiKeyWebFilter} 通过 {@code X-API-Key} 完成。
 *
 * <p>端点清单：
 * <ul>
 *   <li>{@code POST /payments} 创建支付</li>
 *   <li>{@code GET  /payments/{sessionCode}} 查询会话</li>
 *   <li>{@code GET  /payments/{sessionCode}/status} 查询状态</li>
 *   <li>{@code POST /payments/{sessionCode}/sync} 主动同步渠道状态</li>
 *   <li>{@code POST /payments/{sessionCode}/close} 关闭会话</li>
 *   <li>{@code GET|POST /callbacks/{provider}/{sessionCode}} 渠道异步通知</li>
 *   <li>{@code GET  /providers} 可用渠道与配置表单</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentApiRouter {

    public static final String BASE = "/apis/api.payment.pangu.tax/v1alpha1";

    private final PaymentService paymentService;
    private final PaymentProviderRegistry registry;
    private final PaymentConfigService configService;
    private final ReactiveExtensionClient client;
    private final run.pangu.payment.service.PaymentReviewService reviewService;

    @Bean
    public RouterFunction<ServerResponse> paymentRoutes() {
        return RouterFunctions.route()
            .GET(BASE + "/providers", this::listProviders)
            .POST(BASE + "/payments", this::createPayment)
            .GET(BASE + "/payments/{sessionCode}", this::getSession)
            .GET(BASE + "/payments/{sessionCode}/status", this::getStatus)
            .POST(BASE + "/payments/{sessionCode}/sync", this::syncStatus)
            .POST(BASE + "/payments/{sessionCode}/close", this::closeSession)
            .POST(BASE + "/payments/{sessionCode}/proof", this::submitProof)
            .GET(BASE + "/methods/{methodName}/config", this::getMethodConfig)
            .PUT(BASE + "/methods/{methodName}/config", this::saveMethodConfig)
            .POST(BASE + "/methods/{methodName}/toggle", this::toggleMethod)
            .GET(BASE + "/callbacks/{provider}/{sessionCode}", this::callback)
            .POST(BASE + "/callbacks/{provider}/{sessionCode}", this::callback)
            .build();
    }

    /** 读取支付方式的公开配置（密钥字段不会返回）。 */
    private Mono<ServerResponse> getMethodConfig(ServerRequest request) {
        String methodName = request.pathVariable("methodName");
        return client.fetch(run.pangu.payment.extension.PaymentMethod.class, methodName)
            .switchIfEmpty(Mono.error(new PaymentService.PaymentException("METHOD_NOT_FOUND",
                "支付方式不存在: " + methodName)))
            .flatMap(method -> configService.getPublicSettings(methodName)
                .flatMap(settings -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("methodName", methodName);
                    data.put("provider", method.getSpec().getProvider());
                    data.put("enabled", Boolean.TRUE.equals(method.getSpec().getEnabled()));
                    data.put("settings", settings);
                    data.put("configForm", registry
                        .find(method.getSpec().getProvider())
                        .map(PaymentMethodProvider::getConfigFormSchema)
                        .orElse(List.of()));
                    return ok(data);
                }))
            .onErrorResume(this::renderError);
    }

    /**
     * 保存支付方式配置。
     *
     * <p>按渠道 schema 中的 {@code secret} 标记把字段分流：密钥进 Secret，
     * 其余进 ConfigMap。保存前先跑渠道自身的校验，避免写入半成品配置。
     */
    private Mono<ServerResponse> saveMethodConfig(ServerRequest request) {
        String methodName = request.pathVariable("methodName");
        return client.fetch(run.pangu.payment.extension.PaymentMethod.class, methodName)
            .switchIfEmpty(Mono.error(new PaymentService.PaymentException("METHOD_NOT_FOUND",
                "支付方式不存在: " + methodName)))
            .flatMap(method -> {
                PaymentMethodProvider provider = registry
                    .find(method.getSpec().getProvider())
                    .orElseThrow(() -> new PaymentService.PaymentException(
                        "CHANNEL_NOT_IMPLEMENTED",
                        "渠道尚未实现: " + method.getSpec().getProvider()));
                return request.bodyToMono(new org.springframework.core.ParameterizedTypeReference
                        <Map<String, Object>>() { })
                    .defaultIfEmpty(Map.of())
                    .flatMap(settings -> {
                        Set<String> secretKeys = provider.getConfigFormSchema().stream()
                            .filter(f -> Boolean.TRUE.equals(f.get("secret")))
                            .map(f -> String.valueOf(f.get("name")))
                            .collect(java.util.stream.Collectors.toSet());
                        var errors = provider.validateSettings(
                            run.pangu.payment.extension.PaymentMethodConfig.of(settings));
                        if (errors.hasErrors()) {
                            String detail = errors.getAllErrors().stream()
                                .map(e -> String.valueOf(e.getDefaultMessage()))
                                .collect(java.util.stream.Collectors.joining("；"));
                            return Mono.error(new PaymentService.PaymentException(
                                "INVALID_REQUEST", "配置校验失败：" + detail));
                        }
                        return configService.save(methodName, settings, secretKeys)
                            .then(ok(Map.of("methodName", methodName, "saved", true)));
                    });
            })
            .onErrorResume(this::renderError);
    }

    /** 启用 / 停用支付方式。 */
    private Mono<ServerResponse> toggleMethod(ServerRequest request) {
        String methodName = request.pathVariable("methodName");
        boolean enabled = request.queryParam("enabled")
            .map(Boolean::parseBoolean).orElse(true);
        return client.fetch(run.pangu.payment.extension.PaymentMethod.class, methodName)
            .switchIfEmpty(Mono.error(new PaymentService.PaymentException("METHOD_NOT_FOUND",
                "支付方式不存在: " + methodName)))
            .flatMap(method -> {
                method.getSpec().setEnabled(enabled);
                return client.update(method);
            })
            .flatMap(method -> ok(Map.of("methodName", methodName, "enabled", enabled)))
            .onErrorResume(this::renderError);
    }

    /** 渠道列表：供业务方渲染收银台，{@code scene} 参数可按场景过滤。 */
    /**
     * 可用渠道列表。
     *
     * <p>收银台据此渲染支付方式，因此必须同时满足三个条件才会出现：
     * 后台已启用（{@code PaymentMethod.spec.enabled}）、渠道有实现、支持当前场景。
     * 只看注册表会把后台已停用的渠道也吐给前端，用户点进去必然下单失败。
     */
    private Mono<ServerResponse> listProviders(ServerRequest request) {
        String sceneCode = request.queryParam("scene").orElse(null);
        PaymentScene scene = sceneCode == null ? null : PaymentScene.of(sceneCode);

        return client.list(PaymentMethod.class, null,
                Comparator.comparing(m -> m.getSpec() == null ? 100
                    : java.util.Objects.requireNonNullElse(m.getSpec().getPriority(), 100)))
            .filter(method -> method.getSpec() != null
                && Boolean.TRUE.equals(method.getSpec().getEnabled()))
            .concatMap(method -> {
                PaymentProvider provider = resolveProvider(method.getSpec().getProvider());
                if (provider == null) {
                    return Flux.empty();
                }
                PaymentMethodProvider impl = registry.find(provider).orElse(null);
                if (impl == null) {
                    return Flux.empty();
                }
                if (scene != null && !impl.getSupportedScenes().contains(scene)) {
                    return Flux.empty();
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("methodName", method.getMetadata() == null
                    ? null : method.getMetadata().getName());
                item.put("code", provider.getCode());
                item.put("name", method.getSpec().getDisplayName() == null
                    ? impl.getDisplayName() : method.getSpec().getDisplayName());
                item.put("description", impl.getDescription());
                item.put("icon", method.getSpec().getIcon() == null
                    ? impl.getIconUrl() : method.getSpec().getIcon());
                item.put("scenes", impl.getSupportedScenes().stream()
                    .map(PaymentScene::getCode).toList());
                item.put("configForm", impl.getConfigFormSchema());
                return Mono.just(item).flux();
            })
            .collectList()
            .flatMap(data -> ok(Map.of("total", data.size(), "items", data)));
    }

    /** 渠道标识转枚举，未知标识返回 null 而不是抛异常（历史数据可能残留已下线渠道）。 */
    private PaymentProvider resolveProvider(String code) {
        if (code == null) {
            return null;
        }
        return java.util.Arrays.stream(PaymentProvider.values())
            .filter(p -> p.getCode().equalsIgnoreCase(code))
            .findFirst().orElse(null);
    }

    /** 创建支付。 */
    private Mono<ServerResponse> createPayment(ServerRequest request) {
        return request.bodyToMono(PaymentCreateRequest.class)
            .flatMap(req -> paymentService.create(req, request.exchange()))
            .flatMap(created -> ok(render(created.session(), created.payload())))
            .onErrorResume(this::renderError);
    }

    private Mono<ServerResponse> getSession(ServerRequest request) {
        String code = request.pathVariable("sessionCode");
        return paymentService.getByCode(code)
            .map(session -> render(session, null))
            .flatMap(this::ok)
            .onErrorResume(this::renderError);
    }

    private Mono<ServerResponse> getStatus(ServerRequest request) {
        String code = request.pathVariable("sessionCode");
        return paymentService.getByCode(code)
            .map(session -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sessionCode", String.valueOf(session.getSpec().getSessionCode()));
                data.put("phase", session.getStatus() == null
                    ? "UNKNOWN" : session.getStatus().getPhase());
                data.put("paidAt", session.getSpec().getPaidAt() == null
                    ? "" : session.getSpec().getPaidAt().toString());
                data.put("amount", session.getSpec().getAmount());
                data.put("currency", String.valueOf(session.getSpec().getCurrency()));
                data.put("providerTradeNo", orEmpty(session.getSpec().getProviderTradeNo()));
                // 轮询接口同样要带审核态：人工渠道的调用方靠这里判断"待审核"和"被驳回原因"。
                data.put("auditState", session.getStatus() == null
                    ? "" : orEmpty(session.getStatus().getAuditState()));
                data.put("auditRemark", session.getStatus() == null
                    ? "" : orEmpty(session.getStatus().getAuditRemark()));
                return data;
            })
            .flatMap(this::ok)
            .onErrorResume(this::renderError);
    }

    private Mono<ServerResponse> syncStatus(ServerRequest request) {
        String code = request.pathVariable("sessionCode");
        return paymentService.syncStatus(code)
            .map(session -> render(session, null))
            .flatMap(this::ok)
            .onErrorResume(this::renderError);
    }

    private Mono<ServerResponse> closeSession(ServerRequest request) {
        String code = request.pathVariable("sessionCode");
        return paymentService.close(code)
            .map(session -> render(session, null))
            .flatMap(this::ok)
            .onErrorResume(this::renderError);
    }

    /**
     * 付款人提交付款凭证（仅人工类渠道）。
     *
     * <p>凭证内容：付款人、付款账号、银行流水号、实付金额、付款时间、截图地址、备注。
     * 提交后会话进入 {@code PENDING_REVIEW}，等待运营在后台审核，
     * 审核通过才置为已支付。
     */
    private Mono<ServerResponse> submitProof(ServerRequest request) {
        String code = request.pathVariable("sessionCode");
        String submitter = request.headers().firstHeader("X-API-Key");
        String actor = submitter == null || submitter.isBlank()
            ? "api-client" : "key:" + maskKey(submitter);
        return request.bodyToMono(Map.class).defaultIfEmpty(Map.of())
            .flatMap(body -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> proof = new LinkedHashMap<>((Map<String, Object>) body);
                return reviewService.submitProof(code, proof, actor);
            })
            .map(session -> render(session, null))
            .flatMap(this::ok)
            .onErrorResume(this::renderError);
    }

    /** 只保留 API Key 首尾各 4 位，审计日志里不落完整密钥。 */
    private String maskKey(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private Mono<ServerResponse> callback(ServerRequest request) {
        String provider = request.pathVariable("provider");
        String code = request.pathVariable("sessionCode");
        return paymentService.handleCallback(provider, code, request.exchange());
    }

    private Map<String, Object> render(run.pangu.payment.extension.PaymentSession session,
        run.pangu.payment.payload.PaymentInitiatePayload payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionCode", session.getSpec().getSessionCode());
        data.put("provider", session.getSpec().getProvider());
        data.put("amount", session.getSpec().getAmount());
        data.put("currency", session.getSpec().getCurrency());
        data.put("subject", session.getSpec().getSubject());
        data.put("bizType", session.getSpec().getBizType());
        data.put("bizNo", session.getSpec().getBizNo());
        data.put("phase", session.getStatus() == null ? "PENDING" : session.getStatus().getPhase());
        // 调用方靠 responseType 决定前端动作（跳转/展示二维码/展示收款信息），
        // 放在顶层，免得它去 payment 子对象里猜。
        data.put("responseType", orEmpty(session.getSpec().getResponseType()));
        data.put("expiresAt", session.getSpec().getExpiresAt() == null
            ? "" : session.getSpec().getExpiresAt().toString());
        data.put("requiresReview", reviewService.requiresReview(session.getSpec().getProvider()));
        // 审核态必须回给付款方：驳回原因要能展示在收银台，否则用户不知道该改什么。
        // 审核人不外发——那是内部运营账号，属于后台信息。
        if (session.getStatus() != null) {
            data.put("auditState", orEmpty(session.getStatus().getAuditState()));
            data.put("auditRemark", orEmpty(session.getStatus().getAuditRemark()));
            data.put("auditedAt", session.getStatus().getAuditedAt() == null
                ? "" : session.getStatus().getAuditedAt().toString());
            data.put("proofSubmittedAt", session.getStatus().getProofSubmittedAt() == null
                ? "" : session.getStatus().getProofSubmittedAt().toString());
        }
        data.put("providerTradeNo", orEmpty(session.getSpec().getProviderTradeNo()));
        if (session.getSpec().getPaymentProof() != null) {
            data.put("paymentProof", session.getSpec().getPaymentProof());
        }
        if (payload != null) {
            Map<String, Object> pay = new LinkedHashMap<>();
            pay.put("responseType", String.valueOf(payload.getResponseType()));
            pay.put("url", payload.getUrl());
            pay.put("params", payload.getParams());
            pay.put("message", payload.getMessage());
            data.put("payment", pay);
        } else if (session.getSpec().getPayload() != null) {
            data.put("payment", session.getSpec().getPayload());
        }
        return data;
    }

    /** 空值统一输出空串，避免调用方拿到 null 还要额外判空。 */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private Mono<ServerResponse> ok(Object data) {
        return ServerResponse.ok().bodyValue(Map.of("code", 0, "message", "OK", "data", data));
    }

    /** 统一错误体：与 Halo 其他开放接口的 {@code {code,message,data}} 风格一致。 */
    private Mono<ServerResponse> renderError(Throwable error) {
        if (error instanceof PaymentService.PaymentException pe) {
            int status = switch (pe.getCode()) {
                case "SESSION_NOT_FOUND", "METHOD_NOT_FOUND", "CHANNEL_NOT_FOUND" -> 404;
                case "SESSION_ALREADY_PAID" -> 409;
                case "INVALID_REQUEST", "INVALID_AMOUNT", "INVALID_PROVIDER",
                     "INVALID_BIZ_NO" -> 400;
                default -> 500;
            };
            return ServerResponse.status(status).bodyValue(Map.of(
                "code", pe.getCode(),
                "message", pe.getMessage(),
                "data", Map.of()
            ));
        }
        log.error("支付 API 未预期异常", error);
        return ServerResponse.status(500).bodyValue(Map.of(
            "code", "INTERNAL_ERROR",
            "message", error.getMessage() == null ? "服务内部错误" : error.getMessage(),
            "data", Map.of()
        ));
    }
}
