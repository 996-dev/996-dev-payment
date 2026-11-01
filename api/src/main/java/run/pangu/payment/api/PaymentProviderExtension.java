package run.pangu.payment.api;

import java.util.List;
import java.util.Map;
import org.pf4j.ExtensionPoint;
import reactor.core.publisher.Mono;

/**
 * 支付渠道扩展点：第三方插件可以借此接入自己的支付渠道，
 * 无需修改本插件代码。
 *
 * <p>扩展点类型 {@code MULTI_INSTANCE}，可同时启用多个实现。
 * 实现类加上 {@code @Component} 即被 Halo 识别，本插件在启动后
 * 通过 {@code ExtensionGetter.getEnabledExtensions(PaymentProviderExtension.class)} 收集。
 *
 * <p><strong>契约要求</strong>
 * <ul>
 *   <li>{@link #getCode()} 全局唯一，且不能与内置渠道冲突
 *       （manual / bank-transfer / qrcode / easypay / alipay / wechatpay / stripe）。</li>
 *   <li>所有方法必须在响应式链路内非阻塞；渠道 SDK 若是同步的，
 *       用 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} 切换线程。</li>
 *   <li>{@link #initiate} 必须可重入：同一 sessionCode 重复调用不得产生两笔渠道订单。</li>
 *   <li>渠道配置（商户号、密钥）由本插件以 {@code Map<String,String>} 传入，
 *       实现方不得自行读取插件设置。</li>
 * </ul>
 *
 * <p>示例实现：
 * <pre>{@code
 * @Component
 * public class PalpayProvider implements PaymentProviderExtension {
 *
 *     @Override public String getCode() { return "palpay"; }
 *     @Override public String getDisplayName() { return "PalPay"; }
 *
 *     @Override public List<PaymentConfigField> getConfigFormSchema() {
 *         return List.of(PaymentConfigField.secret("apiKey", "API Key", true));
 *     }
 *
 *     @Override public Mono<ProviderInitiateResult> initiate(ProviderInitiateRequest req) {
 *         String apiKey = req.settings().get("apiKey");
 *         // ... 调用渠道创建订单
 *         return Mono.just(ProviderInitiateResult.redirect("https://palpay.example/pay/" + req.sessionCode()));
 *     }
 * }
 * }</pre>
 */
public interface PaymentProviderExtension extends ExtensionPoint {

    /** 渠道标识，小写字母与连字符，全局唯一。 */
    String getCode();

    /** 后台渠道列表与收银台展示的名称。 */
    String getDisplayName();

    /** 渠道说明，展示在后台。 */
    default String getDescription() {
        return "";
    }

    /** 支持的支付场景：PC_WEB / MOBILE_WEB / MINI_PROGRAM / APP。 */
    default List<String> getSupportedScenes() {
        return List.of("PC_WEB", "MOBILE_WEB");
    }

    /**
     * 渠道配置表单结构，后台据此渲染配置项。
     *
     * <p>返回空列表表示该渠道无可配置项（如完全依赖固定地址的渠道）。
     */
    default List<PaymentConfigField> getConfigFormSchema() {
        return List.of();
    }

    /**
     * 校验配置，返回人类可读的错误信息列表；为空表示通过。
     *
     * <p>本插件在保存配置前调用，直接展示给运营。
     */
    default List<String> validateSettings(Map<String, String> settings) {
        return List.of();
    }

    /**
     * 发起支付。
     *
     * @param request 会话信息与渠道配置
     * @return 收银台处理方式（跳转 / 二维码 / JSAPI / 人工指引）
     */
    Mono<ProviderInitiateResult> initiate(ProviderInitiateRequest request);

    /**
     * 处理渠道异步回调。
     *
     * <p>实现方负责验签；验签失败应返回 {@link ProviderCallbackResult#rejected(int, String)}。
     * 本插件拿到 {@code paid=true} 后统一回写会话状态，实现方不要自己改库。
     */
    default Mono<ProviderCallbackResult> handleCallback(ProviderCallbackRequest request) {
        return Mono.just(ProviderCallbackResult.rejected(400, "unsupported"));
    }

    /** 关闭未支付订单，释放渠道预下单；无此概念时保持空实现。 */
    default Mono<Void> close(String sessionCode, Map<String, String> settings) {
        return Mono.empty();
    }

    /**
     * 主动查单，用于回调丢失时的补偿。
     *
     * <p>本插件按分钟级扫描长期停留在待支付的会话时调用；
     * 渠道无此能力时保持默认实现（返回 unknown）。
     */
    default Mono<ProviderQueryResult> query(String sessionCode, Map<String, String> settings) {
        return Mono.just(ProviderQueryResult.unknown());
    }
}
