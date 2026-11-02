package run.pangu.payment.service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.pangu.payment.context.PaymentCallbackContext;
import run.pangu.payment.context.PaymentInitiateContext;
import run.pangu.payment.context.PaymentQueryContext;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.payload.PaymentInitiatePayload;
import run.pangu.payment.payload.PaymentResponseType;
import run.pangu.payment.provider.ManualChannels;
import run.pangu.payment.provider.PaymentMethodProvider;

/**
 * 支付业务编排。
 *
 * <p>对外暴露四个动作：创建会话、查询会话、关闭会话、同步状态；
 * 回调由渠道路由直接转入 {@link #handleCallback}。
 *
 * <p>幂等策略：同一 {@code bizType + bizNo} 在 PENDING 状态下的重复下单
 * 直接返回既有会话，避免用户重复点击产生多笔渠道订单；
 * 若显式传入 {@code idempotencyKey}，则以该键为准。
 */
@Slf4j
@Component
public class PaymentService {

    private final ReactiveExtensionClient client;
    private final PaymentProviderRegistry registry;
    private final PaymentStateService stateService;
    private final run.halo.app.plugin.ReactiveSettingFetcher settingFetcher;
    private final org.springframework.beans.factory.ObjectProvider<run.halo.app.infra.ExternalUrlSupplier>
        externalUrlSupplier;

    public PaymentService(ReactiveExtensionClient client, PaymentProviderRegistry registry,
        PaymentStateService stateService,
        run.halo.app.plugin.ReactiveSettingFetcher settingFetcher,
        org.springframework.beans.factory.ObjectProvider<run.halo.app.infra.ExternalUrlSupplier>
            externalUrlSupplier) {
        this.client = client;
        this.registry = registry;
        this.stateService = stateService;
        this.settingFetcher = settingFetcher;
        this.externalUrlSupplier = externalUrlSupplier;
    }

    /**
     * 创建支付会话并触发渠道下单。
     *
     * @return 会话与渠道载荷的组合
     */
    public Mono<PaymentCreated> create(PaymentCreateRequest request, ServerWebExchange exchange) {
        return validate(request)
            .flatMap(req -> findMethod(req)
                .flatMap(method -> registry.find(method.getSpec().getProvider())
                    .map(provider -> reuseOrCreate(req, method, provider)
                        .flatMap(session -> initiate(session, method, provider, exchange)))
                    .orElseGet(() -> Mono.error(new PaymentException("CHANNEL_NOT_IMPLEMENTED",
                        "支付渠道尚未实现: " + method.getSpec().getProvider()))))
                .switchIfEmpty(Mono.error(new PaymentException("METHOD_NOT_FOUND",
                    "没有可用的支付方式，请先在后台启用该渠道"))));
    }

    private Mono<PaymentCreateRequest> validate(PaymentCreateRequest req) {
        if (req == null) {
            return Mono.error(new PaymentException("INVALID_REQUEST", "请求体不能为空"));
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new PaymentException("INVALID_AMOUNT", "金额必须大于 0"));
        }
        if (req.getProvider() == null || req.getProvider().isBlank()) {
            return Mono.error(new PaymentException("INVALID_PROVIDER", "渠道不能为空"));
        }
        if (req.getBizNo() == null || req.getBizNo().isBlank()) {
            return Mono.error(new PaymentException("INVALID_BIZ_NO", "业务单号不能为空"));
        }
        try {
            PaymentProvider.of(req.getProvider());
        } catch (IllegalArgumentException e) {
            return Mono.error(new PaymentException("INVALID_PROVIDER", e.getMessage()));
        }
        return Mono.just(req);
    }

    /** 选出可用的支付方式：优先用显式指定的，否则取该渠道下第一个启用的。 */
    private Mono<PaymentMethod> findMethod(PaymentCreateRequest req) {
        if (req.getMethodName() != null && !req.getMethodName().isBlank()) {
            return client.fetch(PaymentMethod.class, req.getMethodName());
        }
        String providerCode = PaymentProvider.of(req.getProvider()).getCode();
        return client.list(PaymentMethod.class, null, null)
            .filter(m -> m.getSpec() != null
                && providerCode.equalsIgnoreCase(m.getSpec().getProvider())
                && Boolean.TRUE.equals(m.getSpec().getEnabled()))
            .sort((a, b) -> {
                int pa = a.getSpec().getPriority() == null ? 100 : a.getSpec().getPriority();
                int pb = b.getSpec().getPriority() == null ? 100 : b.getSpec().getPriority();
                return Integer.compare(pa, pb);
            })
            .next();
    }

    /** 幂等复用：命中可复用的 PENDING 会话则直接返回，否则新建。 */
    private Mono<PaymentSession> reuseOrCreate(PaymentCreateRequest req, PaymentMethod method,
        PaymentMethodProvider provider) {
        String bizType = req.getBizType() == null || req.getBizType().isBlank()
            ? "default" : req.getBizType();
        String idem = req.getIdempotencyKey();

        return client.list(PaymentSession.class, null, null)
            .filter(s -> s.getSpec() != null
                && PaymentSession.Phase.PENDING.equals(
                    s.getStatus() == null ? null : s.getStatus().getPhase())
                && (idem != null && !idem.isBlank()
                    ? idem.equals(s.getSpec().getIdempotencyKey())
                    : bizType.equals(s.getSpec().getBizType())
                        && req.getBizNo().equals(s.getSpec().getBizNo())
                        && provider.getProvider().getCode().equals(s.getSpec().getProvider())))
            .next()
            .flatMap(existing -> {
                if (existing.getSpec().getExpiresAt() != null
                    && existing.getSpec().getExpiresAt().isBefore(Instant.now())) {
                    return Mono.empty();
                }
                log.info("命中幂等会话，复用: {}", existing.getSpec().getSessionCode());
                return Mono.just(existing);
            })
            .switchIfEmpty(Mono.defer(() -> createSession(req, method, provider, bizType)));
    }

    private Mono<PaymentSession> createSession(PaymentCreateRequest req, PaymentMethod method,
        PaymentMethodProvider provider, String bizType) {
        return defaultExpireMinutes(provider).flatMap(resolvedMinutes -> {
            PaymentSession session = new PaymentSession();
            Metadata metadata = new Metadata();
            metadata.setName("ps-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            session.setMetadata(metadata);

            // 调用方显式传入的有效期优先，否则用渠道默认/插件设置
            int expireMinutes = req.getExpireMinutes() == null || req.getExpireMinutes() <= 0
                ? resolvedMinutes : req.getExpireMinutes();
            PaymentSession.PaymentSessionSpec spec = new PaymentSession.PaymentSessionSpec();
            spec.setSessionCode(generateCode());
            spec.setProvider(provider.getProvider().getCode());
            spec.setMethodName(method.getMetadata().getName());
            spec.setBizType(bizType);
            spec.setBizNo(req.getBizNo());
            spec.setAmount(req.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));
            spec.setCurrency(req.getCurrency() == null || req.getCurrency().isBlank()
                ? "CNY" : req.getCurrency().toUpperCase());
            spec.setSubject(req.getSubject() == null || req.getSubject().isBlank()
                ? "支付订单 " + req.getBizNo() : req.getSubject());
            spec.setDescription(req.getDescription());
            spec.setReturnUrl(req.getReturnUrl());
            spec.setNotifyUrl(req.getNotifyUrl());
            spec.setIdempotencyKey(req.getIdempotencyKey());
            spec.setExpiresAt(Instant.now().plus(expireMinutes, ChronoUnit.MINUTES));
            session.setSpec(spec);

            PaymentSession.PaymentSessionStatus status = new PaymentSession.PaymentSessionStatus();
            status.setPhase(PaymentSession.Phase.PENDING);
            status.setMessage("待支付");
            session.setStatus(status);

            return client.create(session);
        });
    }

    /**
     * 会话默认有效期（分钟）。
     *
     * <p>离线渠道固定给一天；在线渠道读插件设置「会话有效期」，
     * 未配置时回落到 {@link ManualChannels#ONLINE_EXPIRE_MINUTES}。
     * 调用方显式传入 {@code expireMinutes} 时本方法不会被调用。
     */
    private Mono<Integer> defaultExpireMinutes(PaymentMethodProvider provider) {
        if (ManualChannels.isManual(provider)) {
            return Mono.just(ManualChannels.DEFAULT_EXPIRE_MINUTES);
        }
        return settingFetcher.fetch("api", run.pangu.payment.setting.ApiSetting.class)
            .map(run.pangu.payment.setting.ApiSetting::getDefaultExpireMinutes)
            .filter(minutes -> minutes != null && minutes > 0)
            .map(minutes -> Math.toIntExact(minutes))
            .onErrorResume(e -> Mono.just(ManualChannels.ONLINE_EXPIRE_MINUTES))
            .defaultIfEmpty(ManualChannels.ONLINE_EXPIRE_MINUTES);
    }

    /** 调用渠道下单并把载荷回写到会话。 */
    private Mono<PaymentCreated> initiate(PaymentSession session, PaymentMethod method,
        PaymentMethodProvider provider, ServerWebExchange exchange) {
        PaymentInitiateContext context = PaymentInitiateContext.builder()
            .session(session)
            .method(method)
            .amount(session.getSpec().getAmount())
            .description(session.getSpec().getDescription())
            .returnUrl(session.getSpec().getReturnUrl())
            .notifyUrl(session.getSpec().getNotifyUrl())
            .externalUrl(externalUrl(exchange))
            .bizType(session.getSpec().getBizType())
            .bizNo(session.getSpec().getBizNo())
            .preferredResponseType(PaymentResponseType.QRCODE_URL)
            .build();

        return provider.initiatePayment(context, exchange)
            .flatMap(payload -> persistPayload(session, payload)
                .flatMap(updated -> stateService.record(updated, "INITIATE", "SUCCESS",
                        Map.of("responseType", String.valueOf(payload.getResponseType())))
                    .thenReturn(new PaymentCreated(updated, payload))))
            .onErrorResume(e -> {
                log.error("渠道下单失败: provider={} code={}", provider.getCode(),
                    session.getSpec().getSessionCode(), e);
                // 标记 FAILED 只是附带动作：它自己的写冲突不能覆盖真正的下单异常，
                // 否则排查时只能看到「版本冲突」，看不到渠道为什么失败。
                return stateService.markTerminal(session, PaymentSession.Phase.FAILED,
                        "下单失败: " + e.getMessage())
                    .onErrorResume(markError -> {
                        log.warn("标记下单失败状态时出错（忽略）: {}", markError.getMessage());
                        return Mono.just(session);
                    })
                    .then(Mono.error(new PaymentException("INITIATE_FAILED",
                        "渠道下单失败: " + e.getMessage())));
            });
    }

    private Mono<PaymentSession> persistPayload(PaymentSession session,
        PaymentInitiatePayload payload) {
        String name = session.getMetadata() == null ? null : session.getMetadata().getName();
        if (name == null) {
            return Mono.just(session);
        }
        return updateSessionWithRetry(name, current -> {
            // 全部写成字符串：spec.payload 是 Map<String,String>，
            // 混入布尔/数字会让 Halo 的 schema 校验直接拒绝（详见字段注释）。
            Map<String, String> stored = new java.util.LinkedHashMap<>();
            stored.put("responseType", String.valueOf(payload.getResponseType()));
            stored.put("url", payload.getUrl() == null ? "" : payload.getUrl());
            stored.put("paid", String.valueOf(payload.isPaid()));
            stored.put("message", payload.getMessage() == null ? "" : payload.getMessage());
            // 渠道自定义参数（JSAPI 包、订单号等）一并收敛为字符串，便于统一校验与前端消费
            if (payload.getParams() != null) {
                payload.getParams().forEach((key, value) -> {
                    if (key != null && value != null) {
                        stored.put(key, String.valueOf(value));
                    }
                });
            }
            current.getSpec().setPayload(stored);
            if (payload.getProviderTradeNo() != null) {
                current.getSpec().setProviderTradeNo(payload.getProviderTradeNo());
            }
            current.getSpec().setResponseType(String.valueOf(payload.getResponseType()));
        }, 3);
    }

    /**
     * 带乐观锁重试的会话写回。
     *
     * <p>Halo 的扩展客户端读到的可能是索引缓存中的旧版本，
     * 直接用创建时的对象 update 会报
     * {@code Was the entity updated or deleted concurrently?}。
     * 这里每次尝试都重新拉取最新版本再写入，最多 {@code maxAttempts} 次。
     */
    private Mono<PaymentSession> updateSessionWithRetry(String name,
        java.util.function.Consumer<PaymentSession> mutator, int maxAttempts) {
        return client.fetch(PaymentSession.class, name)
            .switchIfEmpty(Mono.error(new PaymentException("SESSION_NOT_FOUND",
                "支付会话不存在: " + name)))
            .flatMap(current -> {
                mutator.accept(current);
                log.info("写回支付会话: name={} version={} attemptLeft={}", name,
                    current.getMetadata() == null ? null : current.getMetadata().getVersion(),
                    maxAttempts);
                return client.update(current);
            })
            .onErrorResume(e -> maxAttempts > 1
                ? Mono.delay(java.time.Duration.ofMillis(120L))
                    .then(updateSessionWithRetry(name, mutator, maxAttempts - 1))
                : Mono.error(e));
    }

    /**
     * 按幂等键查找仍可复用的待支付会话。
     *
     * <p>供跨插件 {@code PaymentGateway} 使用：只匹配 PENDING 且未过期的会话，
     * 不做 bizNo 宽松匹配，避免同单号补差价被错误复用。
     */
    public Mono<PaymentSession> findPendingByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Mono.empty();
        }
        return client.list(PaymentSession.class,
                s -> s.getSpec() != null && idempotencyKey.equals(s.getSpec().getIdempotencyKey()),
                null)
            .filter(s -> PaymentSession.Phase.PENDING.equals(
                    s.getStatus() == null ? null : s.getStatus().getPhase())
                && (s.getSpec().getExpiresAt() == null
                    || s.getSpec().getExpiresAt().isAfter(Instant.now())))
            .next();
    }

    /** 按业务码查询会话。 */
    public Mono<PaymentSession> getByCode(String sessionCode) {        return client.list(PaymentSession.class, null, null)
            .filter(s -> s.getSpec() != null && sessionCode.equals(s.getSpec().getSessionCode()))
            .next()
            .switchIfEmpty(Mono.error(new PaymentException("SESSION_NOT_FOUND",
                "支付会话不存在: " + sessionCode)));
    }

    /** 主动同步状态：调渠道查单后回写。 */
    public Mono<PaymentSession> syncStatus(String sessionCode) {
        return getByCode(sessionCode)
            .flatMap(session -> {
                if (PaymentSession.Phase.PAID.equals(
                    session.getStatus() == null ? null : session.getStatus().getPhase())) {
                    return Mono.just(session);
                }
                return resolveMethod(session)
                    .flatMap(method -> registry.find(session.getSpec().getProvider())
                        .map(provider -> provider.reconcilePayment(PaymentQueryContext.builder()
                                .session(session)
                                .method(method)
                                .bizType(session.getSpec().getBizType())
                                .bizNo(session.getSpec().getBizNo())
                                .build())
                            .then(getByCode(sessionCode)))
                        .orElseGet(() -> Mono.just(session)));
            });
    }

    /** 关闭会话：先调渠道关单，再本地置终态。 */
    public Mono<PaymentSession> close(String sessionCode) {
        return getByCode(sessionCode)
            .flatMap(session -> {
                if (PaymentSession.Phase.PAID.equals(
                    session.getStatus() == null ? null : session.getStatus().getPhase())) {
                    return Mono.error(new PaymentException("SESSION_ALREADY_PAID",
                        "已支付的会话不能关闭"));
                }
                return resolveMethod(session)
                    .flatMap(method -> registry.find(session.getSpec().getProvider())
                        .map(provider -> provider.closePayment(session, method)
                            .then(stateService.markTerminal(session, PaymentSession.Phase.CLOSED,
                                "已关闭")))
                        .orElseGet(() -> stateService.markTerminal(session,
                            PaymentSession.Phase.CLOSED, "已关闭")));
            });
    }

    /**
     * 回调入口：按会话所属渠道分发到对应实现的 handleCallback。
     *
     * <p>安全要点：渠道实现一律取自会话的 {@code spec.provider}，
     * 而不是回调地址中的任何路径片段——回调地址由外部构造，
     * 若按它选择验签实现，攻击者就能给「无密钥渠道」的会话
     * 套上其他渠道的验签逻辑（例如用空密钥伪造易支付 MD5 签名）。
     * 会话码来自路径，实现内部还须校验回调参数中的商户订单号、
     * 金额与该会话一致。
     */
    public Mono<ServerResponse> handleCallback(String sessionCode, ServerWebExchange exchange) {
        return getByCode(sessionCode)
            .flatMap(session -> dispatchCallback(session, exchange, sessionCode))
            // getByCode 空时抛 PaymentException(SESSION_NOT_FOUND)：显式 404，
            // 避免未映射异常被 WebFlux 兜底成 500（渠道会无意义重试）。
            .onErrorResume(PaymentService.PaymentException.class,
                e -> "SESSION_NOT_FOUND".equals(e.getCode())
                    ? ServerResponse.status(404)
                        .bodyValue(Map.of("code", "SESSION_NOT_FOUND",
                            "message", "支付会话不存在"))
                    : Mono.error(e));
    }

    /** 按会话自身渠道分发回调；渠道不存在时 404。 */
    private Mono<ServerResponse> dispatchCallback(PaymentSession session,
        ServerWebExchange exchange, String sessionCode) {
        return resolveMethod(session)
            .flatMap(method -> registry.find(session.getSpec().getProvider())
                .map(provider -> provider.handleCallback(PaymentCallbackContext.builder()
                        .session(session)
                        .method(method)
                        .bizType(session.getSpec().getBizType())
                        .bizNo(session.getSpec().getBizNo())
                        .build(), exchange)
                    .onErrorResume(e -> {
                        log.error("回调处理异常: provider={} code={}",
                            session.getSpec().getProvider(), sessionCode, e);
                        return ServerResponse.status(500)
                            .bodyValue(Map.of("code", "CALLBACK_ERROR",
                                "message", "回调处理失败"));
                    }))
                .orElseGet(() -> ServerResponse.status(404)
                    .bodyValue(Map.of("code", "CHANNEL_NOT_FOUND",
                        "message", "未知支付渠道: " + session.getSpec().getProvider()))));
    }

    private Mono<PaymentMethod> resolveMethod(PaymentSession session) {
        String name = session.getSpec().getMethodName();
        if (name != null && !name.isBlank()) {
            return client.fetch(PaymentMethod.class, name);
        }
        return client.list(PaymentMethod.class, null, null)
            .filter(m -> m.getSpec() != null
                && session.getSpec().getProvider().equalsIgnoreCase(m.getSpec().getProvider()))
            .next();
    }

    /**
     * 取站点外部地址。
     *
     * <p>优先级：请求的 Host（含反向代理 X-Forwarded-*）→
     * Halo 的 {@code ExternalUrlSupplier}（站点设置中的外部访问地址，
     * Java 扩展点下单没有请求上下文时靠它）→ 相对路径兜底。
     * 不再回退到 {@code 127.0.0.1}：渠道服务器回调该地址必然失败，
     * 宁可返回不带主机的路径并在日志中给出明确提示。
     */
    private java.net.URL externalUrl(ServerWebExchange exchange) {
        if (exchange != null) {
            try {
                var request = exchange.getRequest();
                String scheme = request.getHeaders().getFirst("X-Forwarded-Proto");
                if (scheme == null || scheme.isBlank()) {
                    scheme = request.getURI().getScheme();
                }
                String host = request.getHeaders().getFirst("X-Forwarded-Host");
                if (host == null || host.isBlank()) {
                    host = request.getHeaders().getHost() == null
                        ? null : request.getHeaders().getHost().getHostName();
                    int port = request.getHeaders().getHost() == null
                        ? -1 : request.getHeaders().getHost().getPort();
                    if (host != null && port > 0 && port != 80 && port != 443) {
                        host = host + ":" + port;
                    }
                }
                if (host != null && !host.isBlank()) {
                    return java.net.URI.create(scheme + "://" + host).toURL();
                }
            } catch (Exception e) {
                log.warn("从请求还原站点外部地址失败", e);
            }
        }
        // Java 扩展点下单（exchange == null）或请求头缺失：
        // 用 Halo「设置 → 基础设置 → 外部访问地址」。
        var supplier = externalUrlSupplier == null ? null : externalUrlSupplier.getIfAvailable();
        if (supplier != null) {
            java.net.URI external = supplier.get();
            if (external != null) {
                try {
                    return external.toURL();
                } catch (Exception e) {
                    log.warn("外部访问地址配置非法: {}", external, e);
                }
            }
        }
        log.warn("无法确定站点外部地址，渠道回调地址将不带主机名；"
            + "请在 Halo「设置 → 基础设置」中配置外部访问地址");
        return null;
    }

    /** 生成对外支付码：时间戳 + 随机，长度 24，便于日志检索与人工核对。 */
    private String generateCode() {
        String ts = Long.toString(System.currentTimeMillis(), 36);
        String rnd = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return ("p" + ts + rnd).toLowerCase();
    }

    /** 创建结果：会话 + 渠道载荷。 */
    public record PaymentCreated(PaymentSession session, PaymentInitiatePayload payload) {
    }

    /** 业务异常，携带错误码，由路由层转成统一错误响应。 */
    public static class PaymentException extends RuntimeException {
        private final String code;

        public PaymentException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
