package run.pangu.payment.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 支付方式：某个渠道在特定场景下的一个可用实例。
 *
 * <p>渠道密钥不随实体落库，按 Halo 安全规范拆分为双存储：
 * <ul>
 *   <li>非敏感配置 → ConfigMap {@code 996-dev-payment-config}，键为 {@code metadata.name}</li>
 *   <li>密钥类字段 → Secret {@code 996-dev-payment-secret}，键同上</li>
 * </ul>
 * 两类配置都以本资源的 {@code metadata.name} 作为键，运行时按名合并读取；
 * 表单字段、校验规则与下单参数统一由渠道实现定义。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "payment.pangu.tax", version = "v1alpha1", kind = "PaymentMethod",
    plural = "paymentmethods", singular = "paymentmethod")
public class PaymentMethod extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private PaymentMethodSpec spec;

    @Data
    public static class PaymentMethodSpec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "渠道标识：alipay / wechatpay / stripe / easypay / qrcode / manual / bank-transfer")
        private String provider;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "展示名称")
        private String displayName;

        @Schema(description = "图标地址")
        private String icon;

        @Schema(description = "支持的支付场景（可多选）")
        private List<String> scenes;

        @Schema(description = "是否启用")
        private Boolean enabled;

        @Schema(description = "排序权重，数值越小越靠前")
        private Integer priority;

        @Schema(description = "备注")
        private String remark;
    }
}
