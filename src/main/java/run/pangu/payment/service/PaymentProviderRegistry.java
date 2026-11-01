package run.pangu.payment.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.pangu.payment.api.PaymentProviderExtension;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.provider.ExtensionPaymentProviderAdapter;
import run.pangu.payment.provider.PaymentMethodProvider;

/**
 * 渠道注册表：内置渠道（Spring Bean）+ 外部扩展渠道（{@link PaymentProviderExtension}）。
 *
 * <p>内置实现来自 Spring 容器，启动时立即可用。外部扩展只能在 Halo 的
 * 插件与扩展点就绪后异步加载，因此单独提供 {@link #loadExtensions()}，
 * 由插件 {@code start()} 触发；加载失败不影响内置渠道可用。
 *
 * <p>外部渠道与内置渠道同码时，以内置为准并记录告警——内置实现经过验证，
 * 不允许被第三方插件静默顶替。
 */
@Slf4j
@Component
public class PaymentProviderRegistry {

    private final Map<String, PaymentMethodProvider> byCode = new ConcurrentHashMap<>();
    private final Set<String> builtinCodes;
    private final ExtensionGetter extensionGetter;
    private final PaymentConfigService configService;
    private final PaymentStateService stateService;

    public PaymentProviderRegistry(List<PaymentMethodProvider> providers,
        ExtensionGetter extensionGetter, PaymentConfigService configService,
        PaymentStateService stateService) {
        this.extensionGetter = extensionGetter;
        this.configService = configService;
        this.stateService = stateService;

        Map<String, PaymentMethodProvider> builtins = new LinkedHashMap<>();
        for (PaymentMethodProvider provider : providers) {
            String code = normalize(provider.getCode());
            if (code == null) {
                log.warn("忽略渠道标识为空的实现: {}", provider.getClass().getName());
                continue;
            }
            if (builtins.containsKey(code)) {
                log.warn("检测到重复的支付渠道实现，后者覆盖前者: {}", code);
            }
            builtins.put(code, provider);
        }
        this.builtinCodes = Set.copyOf(builtins.keySet());
        this.byCode.putAll(builtins);
        log.info("内置支付渠道已注册: {}", builtinCodes);
    }

    /**
     * 加载第三方插件提供的渠道扩展。
     *
     * <p>幂等：重复调用只会覆盖同码扩展，不会重复注册。
     * 缺少 ExtensionPointDefinition 时 Halo 会抛异常，此处吞掉并告警，
     * 保证收银台仍可用内置渠道。
     */
    public Mono<Integer> loadExtensions() {
        return Mono.defer(() -> extensionGetter
            .getEnabledExtensions(PaymentProviderExtension.class)
            .doOnNext(this::registerExtension)
            .count()
            .map(count -> {
                log.info("外部支付渠道扩展已加载: count={} total={}", count, byCode.size());
                return byCode.size();
            })
            .onErrorResume(e -> {
                log.error("加载支付渠道扩展失败（不影响内置渠道）: {}", e.getMessage());
                return Mono.just(byCode.size());
            }));
    }

    private void registerExtension(PaymentProviderExtension extension) {
        String code = normalize(extension.getCode());
        if (code == null) {
            log.warn("忽略 code 为空的渠道扩展: {}", extension.getClass().getName());
            return;
        }
        if (builtinCodes.contains(code)) {
            log.warn("渠道扩展 {} 与内置渠道同名，已忽略: {}", code, extension.getClass().getName());
            return;
        }
        byCode.put(code, new ExtensionPaymentProviderAdapter(extension, configService,
            stateService));
        log.info("注册外部支付渠道: {} ({})", code, extension.getDisplayName());
    }

    private String normalize(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toLowerCase(Locale.ROOT);
    }

    /** 按渠道标识取实现；不存在时返回空。 */
    public Optional<PaymentMethodProvider> find(String code) {
        String normalized = normalize(code);
        return normalized == null ? Optional.empty()
            : Optional.ofNullable(byCode.get(normalized));
    }

    /** 按枚举取实现；未实现的渠道（如 PAYPAL）返回空。 */
    public Optional<PaymentMethodProvider> find(PaymentProvider provider) {
        return provider == null ? Optional.empty() : find(provider.getCode());
    }

    public Collection<PaymentMethodProvider> all() {
        return byCode.values();
    }

    /** 按场景过滤可用渠道。 */
    public List<PaymentMethodProvider> forScene(PaymentScene scene) {
        return byCode.values().stream()
            .filter(p -> p.getSupportedScenes().contains(scene))
            .toList();
    }

    /** 已注册的渠道数量（含外部扩展），用于健康检查与文档展示。 */
    public int size() {
        return byCode.size();
    }
}
