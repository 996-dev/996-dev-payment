package run.pangu.payment.extension;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.validation.Errors;
import org.springframework.validation.MapBindingResult;

/**
 * 支付方式的渠道配置载荷。
 *
 * <p>只含一个 {@code settings} 映射，作为
 * {@code PaymentMethodProvider#validateSettings(PaymentMethodConfig)} 的入参类型，
 * 保证渠道实现的校验签名不变。
 *
 * <p>运行时该映射由 ConfigMap 与 Secret 合并而成：先铺 ConfigMap 的公开项，
 * 再以 Secret 中的同名键覆盖，因此密钥永远以 Secret 为准。
 */
@Data
public class PaymentMethodConfig {

    /** 渠道配置项，键名与各 Provider 的 Properties 字段一一对应。 */
    private Map<String, Object> settings = new LinkedHashMap<>();

    public PaymentMethodConfig() {
    }

    public PaymentMethodConfig(Map<String, Object> settings) {
        this.settings = settings == null ? new LinkedHashMap<>() : settings;
    }

    public static PaymentMethodConfig of(Map<String, Object> settings) {
        return new PaymentMethodConfig(settings);
    }

    /** 取字符串配置，缺失时返回默认值。 */
    public String getString(String key, String defaultValue) {
        Object v = settings.get(key);
        return v == null ? defaultValue : String.valueOf(v).trim();
    }

    public String getString(String key) {
        return getString(key, null);
    }

    /** 必填校验：缺失或空白即视为无效。 */
    public boolean missing(String key) {
        String v = getString(key);
        return v == null || v.isEmpty();
    }

    /**
     * 新建一个用于收集字段级错误的容器。
     *
     * <p>配置项是动态键的 Map，必须用 {@link MapBindingResult}：
     * 用 Bean 绑定写 {@code settings.xxx} 会因找不到同名 getter 抛
     * {@code NotReadablePropertyException}，把校验失败变成 500。
     */
    public Errors errors() {
        return new MapBindingResult(settings, "settings");
    }
}
