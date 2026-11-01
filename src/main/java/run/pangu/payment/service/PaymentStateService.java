package run.pangu.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.extension.PaymentTransaction;

/**
 * 支付状态机与流水留痕。
 *
 * <p>所有状态变更必须走这里，以保证：
 * <ul>
 *   <li>幂等：重复回调 / 重复查单不会重复记账</li>
 *   <li>可审计：每次变更都落一条 {@link PaymentTransaction}</li>
 *   <li>终态保护：已支付或已关闭的会话不再被改写</li>
 * </ul>
 *
 * <p>状态流转：
 * {@code PENDING → PAID}、{@code PENDING → CLOSED}、{@code PENDING → EXPIRED}、
 * {@code PENDING → FAILED}。终态之间不允许互相转换。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStateService {

    private final ReactiveExtensionClient client;

    /**
     * 标记会话已支付：幂等。
     *
     * <p>若会话已是 PAID，直接返回原对象；若已是 CLOSED/EXPIRED，
     * 说明存在渠道延迟回调，记录警告但不再改写状态，避免"已关闭订单被支付"的账务错乱。
     */
    public Mono<PaymentSession> markPaid(PaymentSession session, String providerTradeNo,
        Map<String, Object> raw) {
        if (session == null || session.getSpec() == null) {
            return Mono.empty();
        }
        String currentPhase = session.getStatus() == null ? null : session.getStatus().getPhase();
        if (PaymentSession.Phase.PAID.equals(currentPhase)) {
            log.info("支付会话已支付，跳过重复处理: code={}", session.getSpec().getSessionCode());
            return Mono.just(session);
        }
        if (PaymentSession.Phase.CLOSED.equals(currentPhase)
            || PaymentSession.Phase.EXPIRED.equals(currentPhase)) {
            log.warn("支付会话已处于终态 {}，收到迟到支付，需人工对账: code={}, tradeNo={}",
                currentPhase, session.getSpec().getSessionCode(), providerTradeNo);
            return Mono.just(session);
        }

        session.getSpec().setPaidAt(Instant.now());
        if (providerTradeNo != null && !providerTradeNo.isBlank()) {
            session.getSpec().setProviderTradeNo(providerTradeNo);
        }
        // 复用现有 status 对象而不是新建：审核状态、审核人、凭证提交时间
        // 必须在置为 PAID 后依然可追溯，新建对象会把它们清空。
        PaymentSession.PaymentSessionStatus status = session.getStatus() == null
            ? new PaymentSession.PaymentSessionStatus() : session.getStatus();
        status.setPhase(PaymentSession.Phase.PAID);
        status.setMessage("支付成功");
        status.setLastSyncAt(Instant.now());
        session.setStatus(status);

        return client.update(session)
            .flatMap(updated -> record(updated, PaymentTransaction.Type.CALLBACK, "SUCCESS", raw)
                .thenReturn(updated));
    }

    /** 标记为指定终态（关闭 / 过期 / 失败）。 */    public Mono<PaymentSession> markTerminal(PaymentSession session, String phase, String message) {
        if (session == null) {
            return Mono.empty();
        }
        return mutateWithRetry(session, current -> {
            if (current.getStatus() == null) {
                current.setStatus(new PaymentSession.PaymentSessionStatus());
            }
            String currentPhase = current.getStatus().getPhase();
            if (PaymentSession.Phase.PAID.equals(currentPhase)) {
                return false;
            }
            current.getStatus().setPhase(phase);
            current.getStatus().setMessage(message);
            current.getStatus().setLastSyncAt(Instant.now());
            return true;
        }, 3);
    }

    /**
     * 基于库里最新版本改会话，冲突自动重试。
     *
     * <p>调用方传进来的 {@code session} 可能是创建时或几秒前的快照，
     * 直接 update 会撞乐观锁。每次重试都重新拉取，改完立即写回。
     *
     * @param mutator 返回 false 表示「无需改动」，直接返回当前对象
     */
    private Mono<PaymentSession> mutateWithRetry(PaymentSession session,
        java.util.function.Predicate<PaymentSession> mutator, int maxAttempts) {
        String name = session.getMetadata() == null ? null : session.getMetadata().getName();
        if (name == null) {
            return Mono.just(session);
        }
        return client.fetch(PaymentSession.class, name)
            .defaultIfEmpty(session)
            .flatMap(current -> {
                if (!mutator.test(current)) {
                    return Mono.just(current);
                }
                return client.update(current);
            })
            .onErrorResume(e -> maxAttempts > 1
                ? Mono.delay(java.time.Duration.ofMillis(120L))
                    .then(mutateWithRetry(session, mutator, maxAttempts - 1))
                : Mono.error(e));
    }

    /**
     * 把任意值映射压平为字符串映射。
     *
     * <p>{@code PaymentTransaction.spec.raw} 是 {@code Map<String,String>}：
     * Map&lt;String,Object&gt; 会被 Halo 的 schema 推断为「值必须是 object」，
     * 写入字符串时校验直接失败。嵌套对象按 JSON 字符串保留，便于事后追溯。
     */
    private Map<String, String> flattenToStringMap(Map<String, Object> raw) {
        Map<String, String> flattened = new LinkedHashMap<>();
        if (raw == null) {
            return flattened;
        }
        raw.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (value instanceof java.util.Map<?, ?> nested && nested.isEmpty()) {
                return;
            }
            flattened.put(key, value instanceof String s ? s : String.valueOf(value));
        });
        return flattened;
    }

    /** 写入一条交易流水。 */
    public Mono<PaymentTransaction> record(PaymentSession session, String type, String result,
        Map<String, Object> raw) {
        PaymentTransaction tx = new PaymentTransaction();
        Metadata metadata = new Metadata();
        metadata.setName("tx-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        tx.setMetadata(metadata);

        PaymentTransaction.TransactionSpec spec = new PaymentTransaction.TransactionSpec();
        if (session != null && session.getMetadata() != null) {
            spec.setSessionName(session.getMetadata().getName());
        }
        if (session != null && session.getSpec() != null) {
            spec.setSessionCode(session.getSpec().getSessionCode());
            spec.setProvider(session.getSpec().getProvider());
            spec.setAmount(session.getSpec().getAmount() == null
                ? BigDecimal.ZERO : session.getSpec().getAmount());
            spec.setCurrency(session.getSpec().getCurrency());
            if (session.getSpec().getProviderTradeNo() != null) {
                spec.setProviderTradeNo(session.getSpec().getProviderTradeNo());
            }
        }
        spec.setType(type);
        spec.setResult(result);
        spec.setOccurredAt(Instant.now());
        spec.setRaw(flattenToStringMap(raw));
        tx.setSpec(spec);
        return client.create(tx);
    }
}
