package run.pangu.payment.provider;

import java.util.Set;

/**
 * 人工类（离线）渠道判定。
 *
 * <p>抽成独立工具而不是放在 {@code PaymentReviewService} 里，是因为下单流程
 * （决定会话有效期）和审核流程（决定是否接受凭证）都要判这一件事：
 * 若两边各写一份，新增渠道时必然漂移——某处认它是人工渠道、另一处不认，
 * 结果就是"能提交凭证但会话 30 分钟就过期"这类难查的问题。
 *
 * <p>判定有两个来源：
 * <ol>
 *   <li>内置渠道 code 白名单（manual / bank-transfer / qrcode）；</li>
 *   <li>外部扩展渠道的实现类继承人工基类（类名含 {@code Manual}）。</li>
 * </ol>
 */
public final class ManualChannels {

    /** 内置的离线渠道：无在线扣款、无异步回调，到账靠人工核对。 */
    private static final Set<String> BUILTIN = Set.of("manual", "bank-transfer", "qrcode");

    /** 离线渠道默认有效时长（分钟）：留足线下转账时间。 */
    public static final int DEFAULT_EXPIRE_MINUTES = 24 * 60;

    /** 在线渠道默认有效时长（分钟）：与主流收银台一致。 */
    public static final int ONLINE_EXPIRE_MINUTES = 30;

    /** 提交凭证后为审核预留的时长（小时）：避免凭证被支付超时任务扫掉。 */
    public static final long REVIEW_WINDOW_HOURS = 72L;

    private ManualChannels() {
    }

    /** 按渠道 code 判定。 */
    public static boolean isManual(String providerCode) {
        return providerCode != null && BUILTIN.contains(providerCode.toLowerCase());
    }

    /** 按渠道 code + 实现类判定（外部扩展渠道靠类名识别人工流程）。 */
    public static boolean isManual(String providerCode, Class<?> implClass) {
        if (isManual(providerCode)) {
            return true;
        }
        return implClass != null && implClass.getName().contains("Manual");
    }

    /** 按 provider 实例判定，下单流程用这个（已有实例，无需再查注册表）。 */
    public static boolean isManual(PaymentMethodProvider provider) {
        if (provider == null) {
            return false;
        }
        String code = provider.getCode();
        if (code == null) {
            code = provider.getProvider() == null ? null : provider.getProvider().getCode();
        }
        return isManual(code, provider.getClass());
    }

    /** 默认有效期：离线渠道给一天，在线渠道给半小时。 */
    public static int defaultExpireMinutes(PaymentMethodProvider provider) {
        return isManual(provider) ? DEFAULT_EXPIRE_MINUTES : ONLINE_EXPIRE_MINUTES;
    }
}
