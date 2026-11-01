package run.pangu.payment.provider;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import org.springframework.validation.Errors;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import run.pangu.payment.context.PaymentCallbackContext;
import run.pangu.payment.context.PaymentInitiateContext;
import run.pangu.payment.context.PaymentQueryContext;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentMethodConfig;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.payload.PaymentInitiatePayload;

/**
 * 支付渠道能力契约。
 *
 * <p>实现类只需声明渠道 code、配置表单与四个动作（下单、回调、查单、关单），
 * 路由、状态流转、幂等与流水留痕由 {@code PaymentService} 统一编排。
 *
 * <p>注意线程模型：所有方法都在 Halo 的响应式链路上被调用，
 * 渠道 SDK 多为阻塞式（支付宝、微信、Stripe 均基于同步 HTTP 客户端），
 * 实现内部必须用 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
 * 切到弹性线程池，禁止在 Netty 事件循环上做阻塞调用。
 */
public interface PaymentMethodProvider {

    /** 渠道标识，用于路由、配置键与回调地址拼接。 */
    PaymentProvider getProvider();

    /**
     * 渠道代码（字符串形式）。
     *
     * <p>内置渠道取自枚举；由 {@code PaymentProviderExtension} 适配而来的外部渠道
     * 没有对应枚举值，只能覆盖此方法返回自定义标识。
     */
    default String getCode() {
        return getProvider().getCode();
    }

    /** 展示名称。 */
    String getDisplayName();

    /** 渠道说明，展示在收银台与后台列表。 */
    default String getDescription() {
        return "";
    }

    /** 渠道图标地址。 */
    default String getIconUrl() {
        return "";
    }

    /** 支持的支付场景，下单时按场景过滤渠道。 */
    default List<PaymentScene> getSupportedScenes() {
        return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB);
    }

    /**
     * 当前请求环境下该渠道是否可用。
     *
     * <p>例如微信 JSAPI 只在小程序/公众号内可用，
     * 通过 User-Agent 判断，不可用时收银台不展示该渠道。
     */
    default Mono<Boolean> platformSupported(ServerWebExchange exchange) {
        return Mono.just(true);
    }

    /**
     * 渠道配置表单的结构描述，后台据此渲染配置项。
     *
     * <p>每个元素形如
     * {@code {"name":"appId","label":"应用 ID","type":"text","required":true,"secret":false}}，
     * 字段名必须与渠道配置读取时使用的键一致。
     */
    List<Map<String, Object>> getConfigFormSchema();

    /**
     * 校验渠道配置。
     *
     * <p>接收配置载荷、返回 {@link Errors}。
     * 实现中通过 {@code errors.rejectValue(...)} 写入字段级错误，
     * 上层读取 {@code errors.hasErrors()} 决定是否允许保存。
     */
    Errors validateSettings(PaymentMethodConfig config);

    /** 发起支付，返回收银台可直接消费的载荷。 */
    Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange);

    /**
     * 主动查单：渠道侧状态与本地不一致时回写会话。
     *
     * <p>用于回调丢失、网络抖动等场景的补偿，必须幂等。
     */
    Mono<Void> reconcilePayment(PaymentQueryContext context);

    /**
     * 处理异步回调。
     *
     * <p>实现里必须先验签再改状态；返回值直接作为响应体回给渠道，
     * 支付宝要求返回 {@code success}，微信要求返回 JSON，Stripe 要求 200 空响应。
     */
    Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange);

    /** 关闭支付（未支付订单的关单，释放渠道侧预下单）。 */
    Mono<Void> closePayment(PaymentSession session, PaymentMethod method);
}
