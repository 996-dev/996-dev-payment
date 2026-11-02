package run.pangu.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.setting.NotifySetting;

/**
 * 业务方异步通知。
 *
 * <p>支付成功后向会话上的 {@code spec.notifyUrl}（业务方下单时登记的地址）
 * 发送一次 POST；业务方返回非 2xx 时按插件设置「业务通知重试次数」重试，
 * 每次间隔 5 秒。通知失败只记录日志，不影响支付状态本身——
 * 业务方也可以通过主动查单接口补偿。
 *
 * <p>为什么 fire-and-forget：通知是尽力而为的补偿动作，
 * 渠道回调的响应不能被业务方的可用性拖住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentNotifyService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 插件容器不暴露 {@code WebClient.Builder}，自建实例（与易支付渠道同口径）。
     */
    private static final WebClient WEB_CLIENT = WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
        .build();

    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private final ReactiveSettingFetcher settingFetcher;

    /** 支付成功后发出业务通知（不阻塞调用方，失败仅记录日志）。 */
    public void notifyPaid(PaymentSession session) {
        notifyPaidInternal(session).subscribe(
            v -> { },
            e -> log.error("业务通知最终失败: code={}",
                session == null || session.getSpec() == null
                    ? null : session.getSpec().getSessionCode(), e)
        );
    }

    private Mono<Void> notifyPaidInternal(PaymentSession session) {
        if (session == null || session.getSpec() == null) {
            return Mono.empty();
        }
        String notifyUrl = session.getSpec().getNotifyUrl();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            return Mono.empty();
        }
        if (!notifyUrl.startsWith("http://") && !notifyUrl.startsWith("https://")) {
            log.warn("业务通知地址非法，跳过: code={}", session.getSpec().getSessionCode());
            return Mono.empty();
        }
        return settingFetcher.fetch("notify", NotifySetting.class)
            .defaultIfEmpty(new NotifySetting())
            .onErrorResume(e -> Mono.just(new NotifySetting()))
            .flatMap(setting -> {
                int retries = setting.getNotifyRetryTimes() == null
                    || setting.getNotifyRetryTimes() < 0 ? 0 : setting.getNotifyRetryTimes();
                Map<String, Object> payload = buildPayload(session);
                return WEB_CLIENT.post()
                    .uri(notifyUrl)
                    .headers(headers -> applyExtraHeaders(headers, setting.getBizNotifyHeader()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toJson(payload))
                    .retrieve()
                    .toBodilessEntity()
                    .retryWhen(Retry.fixedDelay(retries, RETRY_INTERVAL)
                        .filter(e -> {
                            log.warn("业务通知失败，准备重试: url={} code={} cause={}",
                                notifyUrl, session.getSpec().getSessionCode(),
                                e.getMessage());
                            return true;
                        }))
                    .doOnSuccess(v -> log.info("业务通知已送达: url={} code={}",
                        notifyUrl, session.getSpec().getSessionCode()))
                    .then();
            });
    }

    private Map<String, Object> buildPayload(PaymentSession session) {
        var spec = session.getSpec();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "payment.succeeded");
        payload.put("sessionCode", spec.getSessionCode());
        payload.put("provider", spec.getProvider());
        payload.put("bizType", spec.getBizType());
        payload.put("bizNo", spec.getBizNo());
        payload.put("amount", spec.getAmount());
        payload.put("currency", spec.getCurrency());
        payload.put("subject", spec.getSubject());
        payload.put("providerTradeNo", spec.getProviderTradeNo());
        payload.put("paidAt", spec.getPaidAt() == null ? null : spec.getPaidAt().toString());
        return payload;
    }

    /** 解析 {@code key:value} 形式的附加请求头，多个用分号或换行分隔；解析失败的项跳过。 */
    private void applyExtraHeaders(org.springframework.http.HttpHeaders headers, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String entry : raw.split("[;\\n]")) {
            int idx = entry.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String name = entry.substring(0, idx).trim();
            String value = entry.substring(idx + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                headers.set(name, value);
            }
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("业务通知序列化失败", e);
        }
    }
}
