package run.pangu.payment.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.pangu.payment.api.PaymentCreateCommand;
import run.pangu.payment.api.PaymentCreateResult;
import run.pangu.payment.api.PaymentGateway;
import run.pangu.payment.api.PaymentSessionView;
import run.pangu.payment.extension.PaymentSession;

/**
 * {@link PaymentGateway} 扩展点实现：把跨插件调用转成本地支付编排。
 *
 * <p>之所以不直接让其他插件注入 {@link PaymentService}：Halo 的插件类加载器相互隔离，
 * 其他插件拿不到本插件的 Spring Bean；只有实现 {@code ExtensionPoint} 的 Bean
 * 才能被 {@code ExtensionGetter} 按类型跨插件发现。
 *
 * <p>这类调用没有 {@code ServerWebExchange}，因此渠道实现中依赖请求上下文的逻辑
 * （UA 判断、客户端 IP）在此路径下不可用，需由调用方在命令里显式传入 clientIp。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentGatewayImpl implements PaymentGateway {

    private final PaymentService paymentService;

    @Override
    public Mono<PaymentCreateResult> create(PaymentCreateCommand command) {
        if (command == null) {
            return Mono.error(new IllegalArgumentException("下单命令不能为空"));
        }
        return findReusable(command)
            .flatMap(session -> {
                log.info("跨插件下单命中幂等会话: {}", session.getSpec().getSessionCode());
                return Mono.just(toResult(session, true));
            })
            .switchIfEmpty(Mono.defer(() -> paymentService
                .create(toRequest(command), null)
                .map(created -> toResult(created.session(), false))));
    }

    @Override
    public Mono<PaymentSessionView> get(String sessionCode) {
        return paymentService.getByCode(sessionCode).map(this::toView);
    }

    @Override
    public Mono<PaymentSessionView> close(String sessionCode, String reason) {
        return paymentService.close(sessionCode)
            .onErrorResume(PaymentService.PaymentException.class, e ->
                // 已支付的会话不能关闭，但不是错误：返回当前状态让调用方自行判断
                "SESSION_ALREADY_PAID".equals(e.getCode())
                    ? paymentService.getByCode(sessionCode) : Mono.error(e))
            .map(this::toView);
    }

    /**
     * 幂等命中判定：只认 PENDING 且未过期的会话。
     *
     * <p>不做「同 bizNo 复用」的宽松匹配——跨插件调用方可能用同一 bizNo
     * 补差价，宽松匹配会错误复用旧会话导致金额不符。
     */
    private Mono<PaymentSession> findReusable(PaymentCreateCommand command) {
        String idem = command.idempotencyKey();
        if (idem == null || idem.isBlank()) {
            return Mono.empty();
        }
        return paymentService.findPendingByIdempotencyKey(idem);
    }

    private PaymentCreateRequest toRequest(PaymentCreateCommand command) {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setProvider(command.provider());
        request.setMethodName(command.methodName());
        request.setBizType(command.bizType());
        request.setBizNo(command.bizNo());
        request.setAmount(command.amount());
        request.setCurrency(command.currency());
        request.setSubject(command.subject());
        request.setDescription(command.description());
        request.setReturnUrl(command.returnUrl());
        request.setNotifyUrl(command.notifyUrl());
        request.setIdempotencyKey(command.idempotencyKey());
        request.setClientIp(command.clientIp());
        return request;
    }

    private PaymentCreateResult toResult(PaymentSession session, boolean reused) {
        var spec = session.getSpec();
        var status = session.getStatus();
        String responseType = spec == null ? null : spec.getResponseType();
        String url = null;
        if (spec != null && spec.getPayload() != null) {
            url = spec.getPayload().get("url");
        }
        // 存储层是 Map<String,String>，对外 DTO 保留 Object 值域，
        // 便于调用方拿到数字/布尔型扩展参数时自行转换。
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (spec != null && spec.getPayload() != null) {
            payload.putAll(spec.getPayload());
        }
        return new PaymentCreateResult(
            spec == null ? null : spec.getSessionCode(),
            spec == null ? null : spec.getProvider(),
            spec == null ? null : spec.getMethodName(),
            responseType,
            url,
            payload,
            spec == null ? null : spec.getExpiresAt(),
            status == null ? null : status.getPhase(),
            reused
        );
    }

    private PaymentSessionView toView(PaymentSession session) {
        var spec = session.getSpec();
        var status = session.getStatus();
        return new PaymentSessionView(
            spec == null ? null : spec.getSessionCode(),
            spec == null ? null : spec.getProvider(),
            spec == null ? null : spec.getBizType(),
            spec == null ? null : spec.getBizNo(),
            spec == null ? null : spec.getAmount(),
            spec == null ? null : spec.getCurrency(),
            spec == null ? null : spec.getSubject(),
            status == null ? null : status.getPhase(),
            status == null ? null : status.getMessage(),
            spec == null ? null : spec.getPaidAt(),
            spec == null ? null : spec.getProviderTradeNo(),
            spec == null ? null : spec.getExpiresAt(),
            status == null ? null : status.getAuditState(),
            status == null ? null : status.getAuditRemark()
        );
    }

    /** 供追踪用：会话创建时间，避免调用方拿到 null 时误判。 */
    static Instant createdOf(PaymentSession session) {
        return session.getMetadata() == null ? null : session.getMetadata().getCreationTimestamp();
    }
}
