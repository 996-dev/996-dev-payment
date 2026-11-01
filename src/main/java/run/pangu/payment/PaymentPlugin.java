package run.pangu.payment;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.extension.PaymentTransaction;
import run.pangu.payment.provider.PaymentMethodProvider;
import run.pangu.payment.service.PaymentProviderRegistry;

/**
 * 支付中心插件入口。
 *
 * <p>生命周期与 Halo 插件容器绑定：插件启用时由 Halo 实例化并调用 {@link #start()}，
 * 停用或卸载时调用 {@link #stop()}。
 *
 * <p><b>自定义模型必须在 {@link #start()} 里显式注册到 {@link SchemeManager}</b>。
 * Halo 2.x 的插件容器不会自动扫描 {@code @GVK} 注解
 * （{@code META-INF/plugin-components.idx} 只收录 Spring bean），
 * 未注册时 {@code ReactiveExtensionClient} 对相应 GVK 的任何读写都会抛
 * {@code SchemeNotFoundException}，且在插件停止阶段被资源清理逻辑二次触发，
 * 掩盖真正的启动异常。
 *
 * <p><b>渠道默认值不放在 {@code extensions/} 的 YAML 里</b>。Halo 在插件启动时会把
 * 插件资源 YAML 与库中记录做覆盖式协调，YAML 里写死的 {@code enabled: false}
 * 会在每次重启时把运营在后台启用的渠道改回停用。因此这里改为「缺失才创建」：
 * 已有记录原样保留，运营的启停与配置不会被发版覆盖。
 */
@Slf4j
@Component
public class PaymentPlugin extends BasePlugin {

    /** 插件声明的全部自定义模型，停止时按同一份清单反注册。 */
    private static final List<Class<? extends run.halo.app.extension.Extension>> MODELS = List.of(
        PaymentMethod.class,
        PaymentSession.class,
        PaymentTransaction.class
    );

    /** 默认渠道资源名的后缀，例如 alipay-default。 */
    private static final String DEFAULT_SUFFIX = "-default";

    private final SchemeManager schemeManager;
    private final ReactiveExtensionClient client;
    private final PaymentProviderRegistry registry;
    private final run.pangu.payment.service.PaymentReviewService reviewService;

    /** 超时收敛任务句柄，插件停止时必须释放，否则会持续持有插件类加载器。 */
    private reactor.core.Disposable expiryTask;

    public PaymentPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        ReactiveExtensionClient client, PaymentProviderRegistry registry,
        run.pangu.payment.service.PaymentReviewService reviewService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.client = client;
        this.registry = registry;
        this.reviewService = reviewService;
    }

    @Override
    public void start() {
        for (Class<? extends run.halo.app.extension.Extension> model : MODELS) {
            schemeManager.register(model);
            log.info("支付中心已注册自定义模型: {}", model.getSimpleName());
        }
        ensureDefaultMethods();
        // 外部渠道扩展依赖 Halo 的插件与扩展点就绪，异步加载；
        // 失败只影响第三方渠道，内置渠道已在构造期注册完毕。
        registry.loadExtensions().subscribe(
            size -> log.info("支付渠道总数（含外部扩展）: {}", size),
            e -> log.error("支付渠道扩展加载异常", e));
        // 每 5 分钟收敛一次超时会话：待支付与待审核都会过期，
        // 避免「用户付了钱但单据永远挂着」或「提交凭证后无人处理」的僵尸状态。
        expiryTask = reactor.core.publisher.Flux
            .interval(java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(5))
            .concatMap(tick -> reviewService.expireStale())
            .subscribe(
                count -> {
                    if (count > 0) {
                        log.info("定时收敛超时支付会话: {}", count);
                    }
                },
                e -> log.error("超时会话收敛任务异常", e));
    }

    @Override
    public void stop() {
        if (expiryTask != null && !expiryTask.isDisposed()) {
            expiryTask.dispose();
            log.info("支付中心已停止超时会话收敛任务");
        }
        for (Class<? extends run.halo.app.extension.Extension> model : MODELS) {
            try {
                schemeManager.unregister(Scheme.buildFromType(model));
            } catch (Exception e) {
                // 反注册失败不应阻断插件停止流程，Halo 会在卸载时清理剩余资源
                log.warn("反注册自定义模型失败: {}", model.getSimpleName(), e);
            }
        }
    }

    /**
     * 为每个有实现的渠道补一条默认记录，仅在该记录不存在时创建。
     *
     * <p>创建完成后立即返回，实际的库写入在响应式链路上异步完成——
     * 前台首次访问若早于写入完成，看到的是空列表，刷新即可。
     * 这类竞态只出现在插件刚安装的一瞬，可接受。
     */
    private void ensureDefaultMethods() {
        int priority = 10;
        for (PaymentMethodProvider impl : registry.all()) {
            // 用 getCode() 而非 getProvider().getCode()：外部扩展渠道由
            // PaymentProviderExtension 适配而来，没有对应枚举值
            String code = impl.getCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            String name = code + DEFAULT_SUFFIX;
            int order = priority;
            priority += 10;
            client.fetch(PaymentMethod.class, name)
                .switchIfEmpty(Mono.defer(() -> {
                    PaymentMethod method = new PaymentMethod();
                    Metadata metadata = new Metadata();
                    metadata.setName(name);
                    method.setMetadata(metadata);
                    PaymentMethod.PaymentMethodSpec spec = new PaymentMethod.PaymentMethodSpec();
                    spec.setProvider(code);
                    spec.setDisplayName(impl.getDisplayName());
                    spec.setEnabled(false);
                    spec.setPriority(order);
                    spec.setScenes(impl.getSupportedScenes().stream().map(PaymentScene::getCode).toList());
                    method.setSpec(spec);
                    log.info("支付中心创建默认渠道: {}", name);
                    return client.create(method);
                }))
                .onErrorResume(e -> {
                    log.warn("初始化默认渠道失败: {}", name, e);
                    return Mono.empty();
                })
                .subscribe();
        }
    }
}
