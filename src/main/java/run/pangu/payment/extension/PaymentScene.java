package run.pangu.payment.extension;

/**
 * 支付场景。
 *
 * <p>渠道通过 {@code getSupportedScenes()} 声明可覆盖的场景，
 * 下单时按请求来源场景做渠道过滤。
 */
public enum PaymentScene {

    /** 桌面浏览器 */
    PC_WEB("pc-web", "桌面网页"),
    /** 移动端浏览器 / H5 */
    MOBILE_WEB("mobile-web", "移动网页"),
    /** 小程序 / 公众号内 */
    MINI_PROGRAM("mini-program", "小程序");

    private final String code;
    private final String displayName;

    PaymentScene(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PaymentScene of(String code) {
        if (code == null || code.isBlank()) {
            return PC_WEB;
        }
        String normalized = code.trim().toUpperCase().replace("-", "_");
        for (PaymentScene s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        return PC_WEB;
    }
}
