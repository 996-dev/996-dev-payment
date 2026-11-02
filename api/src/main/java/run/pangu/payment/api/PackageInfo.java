package run.pangu.payment.api;

/**
 * 支付网关契约：跨插件调用的唯一入口。
 *
 * <p>本包（{@code run.pangu.payment.api}）是「支付配置与收银能力」插件的公开
 * Java API，只包含稳定契约，不含任何实现。业务插件（商城、会员、打赏等）
 * 只负责订单与履约，收银、渠道对接、回调验签、对账交给本插件。
 *
 * <h2>使用方如何接入</h2>
 * <ol>
 *   <li>构建依赖：{@code compileOnly 'run.pangu.payment:996-dev-payment-api:1.0.0'}</li>
 *   <li>plugin.yaml 声明运行时依赖：
 *       {@code spec.pluginDependencies: { 996-dev-payment: ">=1.0.0" }}</li>
 *   <li>注入 {@code ExtensionGetter}，按 {@link PaymentGateway} 获取实现</li>
 *   <li>订阅 {@link PaymentSucceededEvent} 完成履约</li>
 * </ol>
 *
 * <h2>扩展点一览</h2>
 * <ul>
 *   <li>{@link PaymentGateway} —— SINGLETON，发起支付 / 查询 / 关单</li>
 *   <li>{@link PaymentProviderExtension} —— MULTI_INSTANCE，接入新的支付渠道</li>
 * </ul>
 *
 * <p>不愿意引入 Java 依赖的调用方，也可以直接使用 HTTP 开放 API：
 * {@code /apis/api.payment.pangu.tax/v1alpha1/**}，鉴权头 {@code X-API-Key}。
 */
public final class PackageInfo {

    private PackageInfo() {
    }
}
