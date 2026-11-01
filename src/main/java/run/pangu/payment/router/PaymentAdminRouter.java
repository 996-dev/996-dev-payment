package run.pangu.payment.router;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.validation.Errors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentMethodConfig;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.provider.PaymentMethodProvider;
import run.pangu.payment.service.PaymentConfigService;
import run.pangu.payment.service.PaymentProviderRegistry;
import run.pangu.payment.service.PaymentService;
import run.pangu.payment.service.PaymentStateService;

/**
 * 支付中心后台管理端点（Console 专用）。
 *
 * <p>挂在 {@code /apis/console.api.payment.pangu.tax/v1alpha1} 下，
 * 与对外开放的 {@code /api/payment.pangu.tax/v1} 完全隔离：前者靠 Halo 登录态鉴权，
 * 后者靠 API Key 鉴权。两条链路的凭据互不通用，
 * 避免把后台管理能力误暴露给业务系统。
 *
 * <p>配置读写不直接操作扩展资源，而是走 {@link PaymentConfigService} 的双存储拆分：
 * 密钥字段落 Secret，其余落 ConfigMap。回显时密钥统一替换为 {@value #MASK}，
 * 提交时值为 {@value #MASK} 表示"保持原值不变"，避免前端把掩码写回库。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentAdminRouter {

    private static final String BASE = "/apis/console.api.payment.pangu.tax/v1alpha1";

    /** 密钥回显掩码：提交时携带该值表示不修改原密钥。 */
    private static final String MASK = "******";

    private final ReactiveExtensionClient client;
    private final PaymentConfigService configService;
    private final PaymentProviderRegistry registry;
    private final PaymentStateService stateService;
    private final run.pangu.payment.service.PaymentReviewService reviewService;

    @Bean
    public RouterFunction<ServerResponse> paymentAdminRoutes() {
        return RouterFunctions.route()
            .GET(BASE + "/methods", this::listMethods)
            .GET(BASE + "/methods/{name}/settings", this::getSettings)
            .PUT(BASE + "/methods/{name}/settings", this::saveSettings)
            .POST(BASE + "/methods/{name}/toggle", this::toggleMethod)
            .GET(BASE + "/sessions", this::listSessions)
            .GET(BASE + "/sessions/{code}", this::getSession)
            .POST(BASE + "/sessions/{code}/mark-paid", this::markPaid)
            .POST(BASE + "/sessions/{code}/review", this::review)
            .GET(BASE + "/overview", this::overview)
            .build();
    }

    /** 渠道列表：实体信息 + Provider 元信息 + 配置完成度。 */
    private Mono<ServerResponse> listMethods(ServerRequest request) {
        return client.list(PaymentMethod.class, null,
                Comparator.comparing(m -> Objects.requireNonNullElse(
                    m.getSpec() == null ? null : m.getSpec().getPriority(), 100)))
            .concatMap(method -> toMethodView(method).flux())
            .collectList()
            .flatMap(list -> ok(list));
    }

    private Mono<Map<String, Object>> toMethodView(PaymentMethod method) {
        Map<String, Object> view = new LinkedHashMap<>();
        String name = method.getMetadata() == null ? null : method.getMetadata().getName();
        PaymentMethod.PaymentMethodSpec spec = method.getSpec();
        view.put("name", name);
        view.put("provider", spec == null ? null : spec.getProvider());
        view.put("displayName", spec == null ? null : spec.getDisplayName());
        view.put("icon", spec == null ? null : spec.getIcon());
        view.put("scenes", spec == null || spec.getScenes() == null ? List.of() : spec.getScenes());
        view.put("enabled", spec != null && Boolean.TRUE.equals(spec.getEnabled()));
        view.put("priority", spec == null ? 100 : Objects.requireNonNullElse(spec.getPriority(), 100));
        view.put("remark", spec == null ? null : spec.getRemark());

        PaymentProvider provider = resolveProvider(spec == null ? null : spec.getProvider());
        view.put("providerDisplayName", provider == null ? null : provider.getDisplayName());
        PaymentMethodProvider impl = provider == null ? null : registry.find(provider).orElse(null);
        view.put("description", impl == null ? null : impl.getDescription());
        view.put("implemented", impl != null);
        view.put("supportedScenes", impl == null || impl.getSupportedScenes() == null ? List.of()
            : impl.getSupportedScenes().stream().map(Enum::name).toList());

        return configService.get(name).map(config -> {
            Set<String> secretKeys = secretKeysOf(impl);
            long filled = config.getSettings().entrySet().stream()
                .filter(e -> e.getValue() != null && !String.valueOf(e.getValue()).isBlank())
                .count();
            long secretFilled = config.getSettings().entrySet().stream()
                .filter(e -> secretKeys.contains(e.getKey()))
                .filter(e -> e.getValue() != null && !String.valueOf(e.getValue()).isBlank())
                .count();
            view.put("configuredFields", filled);
            view.put("configuredSecrets", secretFilled);
            view.put("requiredSecrets", secretKeys.size());
            view.put("configured", secretKeys.isEmpty() || secretFilled == secretKeys.size());
            return view;
        });
    }

    /** 配置回显：返回表单结构 + 已填值（密钥打码）。 */
    private Mono<ServerResponse> getSettings(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(PaymentMethod.class, name)
            .switchIfEmpty(Mono.error(new PaymentAdminException(404, "支付方式不存在: " + name)))
            .flatMap(method -> {
                PaymentMethodProvider impl = implOf(method);
                if (impl == null) {
                    return Mono.error(new PaymentAdminException(400, "该渠道尚未实现配置表单"));
                }
                List<Map<String, Object>> schema = impl.getConfigFormSchema() == null
                    ? List.of() : impl.getConfigFormSchema();
                Set<String> secretKeys = secretKeysOf(impl);
                return configService.get(name).map(config -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    schema.forEach(field -> {
                        String key = String.valueOf(field.get("name"));
                        Object raw = config.getSettings().get(key);
                        boolean isSecret = secretKeys.contains(key)
                            || Boolean.TRUE.equals(field.get("secret"));
                        if (isSecret) {
                            values.put(key, raw == null || String.valueOf(raw).isBlank()
                                ? "" : MASK);
                        } else {
                            values.put(key, raw == null ? "" : raw);
                        }
                    });
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name);
                    data.put("schema", schema);
                    data.put("values", values);
                    return data;
                });
            })
            .flatMap(this::ok)
            .onErrorResume(PaymentAdminException.class, this::fail);
    }

    /** 保存配置：校验后按密钥/公开分流写入 ConfigMap 与 Secret。 */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> saveSettings(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(Map.class)
            .defaultIfEmpty(Map.of())
            .flatMap(body -> {
                Object raw = body.get("settings");
                Map<String, Object> input = raw instanceof Map ? (Map<String, Object>) raw : Map.of();
                return client.fetch(PaymentMethod.class, name)
                    .switchIfEmpty(Mono.error(
                        new PaymentAdminException(404, "支付方式不存在: " + name)))
                    .flatMap(method -> configService.get(name).flatMap(existing -> {
                        PaymentMethodProvider impl = implOf(method);
                        Set<String> secretKeys = secretKeysOf(impl);
                        Map<String, Object> merged = new LinkedHashMap<>();
                        input.forEach((k, v) -> {
                            // 掩码表示"保持原值"，不能把掩码写回库
                            if (MASK.equals(v)) {
                                Object old = existing.getSettings().get(k);
                                if (old != null) {
                                    merged.put(k, old);
                                }
                            } else {
                                merged.put(k, v);
                            }
                        });

                        if (impl != null) {
                            Errors errors = validate(impl, merged);
                            if (errors.hasErrors()) {
                                return Mono.error(new PaymentAdminException(400,
                                    fieldMessages(errors)));
                            }
                        }
                        return configService.save(name, merged, secretKeys)
                            .then(ok(Map.of("name", name, "saved", merged.keySet())));
                    }));
            })
            .onErrorResume(PaymentAdminException.class, this::fail);
    }

    /**
     * 让渠道实现把字段级错误写进 Errors，交由上层转成 400。
     *
     * <p>直接返回实现方构造的 Errors：这里如果再包一层新的容器，
     * 实现写进自己容器的错误会被丢掉，等于保存时不做校验。
     */
    private Errors validate(PaymentMethodProvider impl, Map<String, Object> settings) {
        return impl.validateSettings(PaymentMethodConfig.of(settings));
    }

    private String fieldMessages(Errors errors) {
        List<String> msgs = new ArrayList<>();
        errors.getFieldErrors().forEach(e -> msgs.add(e.getField() + ": " + e.getDefaultMessage()));
        errors.getGlobalErrors().forEach(e -> msgs.add(e.getDefaultMessage()));
        return String.join("；", msgs);
    }

    /** 启用 / 停用渠道。 */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> toggleMethod(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(Map.class).defaultIfEmpty(Map.of())
            .flatMap(body -> {
                boolean enabled = Boolean.TRUE.equals(body.get("enabled"))
                    || "true".equals(String.valueOf(body.get("enabled")));
                return client.fetch(PaymentMethod.class, name)
                    .switchIfEmpty(Mono.error(
                        new PaymentAdminException(404, "支付方式不存在: " + name)))
                    .flatMap(method -> {
                        if (method.getSpec() == null) {
                            method.setSpec(new PaymentMethod.PaymentMethodSpec());
                        }
                        method.getSpec().setEnabled(enabled);
                        return client.update(method)
                            .then(ok(Map.of("name", name, "enabled", enabled)));
                    });
            })
            .onErrorResume(PaymentAdminException.class, this::fail);
    }

    /** 交易流水：按创建时间倒序分页。 */
    private Mono<ServerResponse> listSessions(ServerRequest request) {
        int page = intParam(request, "page", 1);
        int size = intParam(request, "size", 20);
        String phase = request.queryParam("phase").orElse(null);

        return client.list(PaymentSession.class,
                session -> phase == null || phase.isBlank()
                    || phase.equalsIgnoreCase(session.getStatus() == null
                        ? null : session.getStatus().getPhase()),
                Comparator.comparing((PaymentSession s) -> s.getMetadata() == null
                        ? Instant.EPOCH : s.getMetadata().getCreationTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())),
                page, size)
            .map(result -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("total", result.getTotal());
                data.put("page", result.getPage());
                data.put("size", result.getSize());
                data.put("items", result.getItems().stream().map(this::toSessionView).toList());
                return data;
            })
            .flatMap(this::ok);
    }

    private Mono<ServerResponse> getSession(ServerRequest request) {
        String code = request.pathVariable("code");
        return client.list(PaymentSession.class,
                s -> code.equals(s.getSpec() == null ? null : s.getSpec().getSessionCode()),
                Comparator.comparing((PaymentSession s) -> s.getMetadata() == null
                    ? Instant.EPOCH : s.getMetadata().getCreationTimestamp()),
                1, 1)
            .flatMap(result -> result.getItems().isEmpty()
                ? Mono.error(new PaymentAdminException(404, "支付会话不存在: " + code))
                : ok(toSessionView(result.getItems().get(0))))
            .onErrorResume(PaymentAdminException.class, this::fail);
    }

    /** 手工核销：仅对人工确认、银行转账这类离线渠道使用。 */
    /**
     * 直接核销：运营已在银行侧确认到账，跳过「用户提交凭证 → 审核」两步。
     *
     * <p>与 {@link #review} 的通过分支共用同一套状态机与审计，
     * 唯一区别是不要求会话处于 {@code PENDING_REVIEW}。
     * 无论走哪条路径，{@code remark} 都必须填写：没有依据的核销事后无法对账。
     */
    private Mono<ServerResponse> markPaid(ServerRequest request) {
        String code = request.pathVariable("code");
        return request.bodyToMono(Map.class).defaultIfEmpty(Map.of())
            .flatMap(body -> {
                String operator = String.valueOf(body.getOrDefault("operator", "console"));
                String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
                return reviewService.approve(code, operator, remark)
                    .map(this::toSessionView)
                    .flatMap(this::ok);
            })
            .onErrorResume(PaymentService.PaymentException.class,
                e -> fail(new PaymentAdminException(400, e.getMessage())))
            .onErrorResume(PaymentAdminException.class, this::fail);
    }

    /**
     * 人工审核：通过核销或驳回重交。
     *
     * <p>审核人取自 Halo 登录态（{@code request.principal()}），不接受前端伪造；
     * 未取到登录名时回落到请求体中的 reviewer，仅用于本地联调。
     *
     * <p>body：{@code {"action": "approve" | "reject", "remark": "银行流水号 1234，已对账"}}
     */
    private Mono<ServerResponse> review(ServerRequest request) {
        String code = request.pathVariable("code");
        return request.bodyToMono(Map.class).defaultIfEmpty(Map.of())
            .flatMap(body -> {
                String action = body.get("action") == null
                    ? "approve" : String.valueOf(body.get("action"));
                String remark = body.get("remark") == null
                    ? null : String.valueOf(body.get("remark"));
                String fallback = body.get("reviewer") == null
                    ? "console" : String.valueOf(body.get("reviewer"));
                return request.principal()
                    .map(java.security.Principal::getName)
                    .defaultIfEmpty(fallback)
                    .flatMap(reviewer -> "reject".equalsIgnoreCase(action)
                        ? reviewService.reject(code, reviewer, remark)
                        : reviewService.approve(code, reviewer, remark));
            })
            .map(this::toSessionView)
            .flatMap(this::ok)
            .onErrorResume(PaymentService.PaymentException.class,
                e -> fail(new PaymentAdminException(400, e.getMessage())))
            .onErrorResume(PaymentAdminException.class, this::fail);
    }

    /** 概览：总量、成功量、成功金额，供后台首页卡片使用。 */
    private Mono<ServerResponse> overview(ServerRequest request) {
        return client.list(PaymentSession.class, null,
                Comparator.comparing((PaymentSession s) -> Instant.EPOCH))
            .collectList()
            .map(sessions -> {
                long total = sessions.size();
                long paid = sessions.stream()
                    .filter(s -> s.getStatus() != null && "PAID".equalsIgnoreCase(
                        s.getStatus().getPhase()))
                    .count();
                var amount = sessions.stream()
                    .filter(s -> s.getStatus() != null && "PAID".equalsIgnoreCase(
                        s.getStatus().getPhase()))
                    .map(s -> s.getSpec() == null ? null : s.getSpec().getAmount())
                    .filter(Objects::nonNull)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("totalSessions", total);
                data.put("paidSessions", paid);
                data.put("paidAmount", amount);
                data.put("currency", "CNY");
                // 离线渠道的待审核积压：运营每天要先把这个数字清掉，钱才算真正到账
                long pendingReview = sessions.stream()
                    .filter(s -> s.getStatus() != null && "PENDING_REVIEW".equalsIgnoreCase(
                        s.getStatus().getPhase()))
                    .count();
                var pendingAmount = sessions.stream()
                    .filter(s -> s.getStatus() != null && "PENDING_REVIEW".equalsIgnoreCase(
                        s.getStatus().getPhase()))
                    .map(s -> s.getSpec() == null ? null : s.getSpec().getAmount())
                    .filter(Objects::nonNull)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                data.put("pendingReviewSessions", pendingReview);
                data.put("pendingReviewAmount", pendingAmount);
                return data;
            })
            .flatMap(this::ok);
    }

    private Map<String, Object> toSessionView(PaymentSession session) {
        Map<String, Object> view = new LinkedHashMap<>();
        PaymentSession.PaymentSessionSpec spec = session.getSpec();
        PaymentSession.PaymentSessionStatus status = session.getStatus();
        view.put("name", session.getMetadata() == null ? null : session.getMetadata().getName());
        view.put("createdAt", session.getMetadata() == null ? null
            : String.valueOf(session.getMetadata().getCreationTimestamp()));
        view.put("sessionCode", spec == null ? null : spec.getSessionCode());
        view.put("provider", spec == null ? null : spec.getProvider());
        view.put("methodName", spec == null ? null : spec.getMethodName());
        view.put("bizType", spec == null ? null : spec.getBizType());
        view.put("bizNo", spec == null ? null : spec.getBizNo());
        view.put("amount", spec == null ? null : spec.getAmount());
        view.put("currency", spec == null ? null : spec.getCurrency());
        view.put("subject", spec == null ? null : spec.getSubject());
        view.put("expiresAt", spec == null ? null : String.valueOf(spec.getExpiresAt()));
        view.put("phase", status == null ? null : status.getPhase());
        view.put("paidAt", spec == null || spec.getPaidAt() == null ? null
            : String.valueOf(spec.getPaidAt()));
        view.put("providerTradeNo", spec == null ? null : spec.getProviderTradeNo());
        view.put("message", status == null ? null : status.getMessage());
        // 人工渠道审核信息：列表与详情都依赖这几个字段决定按钮可见性
        view.put("auditState", status == null ? null : status.getAuditState());
        view.put("auditRemark", status == null ? null : status.getAuditRemark());
        view.put("auditedBy", status == null ? null : status.getAuditedBy());
        view.put("auditedAt", status == null || status.getAuditedAt() == null
            ? null : String.valueOf(status.getAuditedAt()));
        view.put("proofSubmittedAt", status == null || status.getProofSubmittedAt() == null
            ? null : String.valueOf(status.getProofSubmittedAt()));
        view.put("paymentProof", spec == null ? null : spec.getPaymentProof());
        view.put("requiresReview", reviewService.requiresReview(
            spec == null ? null : spec.getProvider()));
        return view;
    }

    private PaymentProvider resolveProvider(String code) {
        if (code == null) {
            return null;
        }
        return java.util.Arrays.stream(PaymentProvider.values())
            .filter(p -> p.getCode().equalsIgnoreCase(code))
            .findFirst().orElse(null);
    }

    private PaymentMethodProvider implOf(PaymentMethod method) {
        // 直接用字符串查注册表：外部扩展渠道没有对应 PaymentProvider 枚举值，
        // 走枚举解析会得到 null，导致后台看不到第三方渠道的配置表单。
        String code = method.getSpec() == null ? null : method.getSpec().getProvider();
        return code == null ? null : registry.find(code).orElse(null);
    }

    /** 从表单结构里取需要落 Secret 的字段名。 */
    private Set<String> secretKeysOf(PaymentMethodProvider impl) {
        if (impl == null || impl.getConfigFormSchema() == null) {
            return Set.of();
        }
        return impl.getConfigFormSchema().stream()
            .filter(f -> Boolean.TRUE.equals(f.get("secret")))
            .map(f -> String.valueOf(f.get("name")))
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private int intParam(ServerRequest request, String key, int def) {
        return request.queryParam(key).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return def;
            }
        }).orElse(def);
    }

    private Mono<ServerResponse> ok(Object data) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(data);
    }

    private Mono<ServerResponse> fail(PaymentAdminException e) {
        return ServerResponse.status(e.status).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", Map.of("status", e.status, "message", e.getMessage())));
    }

    /** 管理端点的业务异常，携带 HTTP 状态码。 */
    private static class PaymentAdminException extends RuntimeException {
        private final int status;

        PaymentAdminException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
