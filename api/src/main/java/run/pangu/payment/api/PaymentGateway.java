package run.pangu.payment.api;

import org.pf4j.ExtensionPoint;
import reactor.core.publisher.Mono;

/**
 * 支付网关：其他插件调用本插件完成支付的唯一入口。
 *
 * <p>扩展点类型为 {@code SINGLETON}，Halo 中同时只会有一个启用的实现。
 * 使用方注入 {@code ExtensionGetter} 后按类型获取：
 *
 * <pre>{@code
 * @Component
 * @RequiredArgsConstructor
 * public class OrderPayService {
 *     private final ExtensionGetter extensionGetter;
 *
 *     public Mono<PaymentCreateResult> pay(Order order) {
 *         return extensionGetter.getEnabledExtension(PaymentGateway.class)
 *             .flatMap(gateway -> gateway.create(new PaymentCreateCommand(
 *                 "alipay", "alipay-default", "order", order.getId(),
 *                 order.getAmount(), "CNY", "订单支付", null, returnUrl, notifyUrl, null, null)));
 *     }
 * }
 * }</pre>
 *
 * <p><strong>语义边界</strong>：本插件只负责「收钱」——创建支付会话、驱动渠道、
 * 回写支付状态。订单状态、发货、退款审批、库存等交易语义由调用方插件自己维护。
 * 支付成功后可订阅 {@link PaymentSucceededEvent} 做履约。
 *
 * <p>所有方法返回 {@link Mono}，调用方不得在其中执行阻塞操作。
 */
public interface PaymentGateway extends ExtensionPoint {

    /**
     * 创建支付会话。
     *
     * <p>幂等：同一个 {@code idempotencyKey}（非空时）重复调用返回同一会话，
     * 结果中的 {@link PaymentCreateResult#reused()} 为 {@code true}。
     *
     * @param command 下单参数，不能为 null
     * @return 收银台可直接消费的结果（跳转地址 / 二维码内容 / 手工收款指引）
     */
    Mono<PaymentCreateResult> create(PaymentCreateCommand command);

    /**
     * 查询支付会话当前状态。
     *
     * @param sessionCode 会话码（{@link PaymentCreateResult#sessionCode()}）
     * @return 会话视图；不存在时以 {@link java.util.NoSuchElementException} 结束
     */
    Mono<PaymentSessionView> get(String sessionCode);

    /**
     * 关闭未支付的会话，释放渠道侧预下单。
     *
     * <p>对已支付会话调用是幂等的空操作，返回当前状态而非报错。
     */
    Mono<PaymentSessionView> close(String sessionCode, String reason);
}
