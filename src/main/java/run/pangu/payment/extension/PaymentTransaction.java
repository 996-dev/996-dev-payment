package run.pangu.payment.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 支付流水：一次渠道交互的原始凭证留痕。
 *
 * <p>一个支付会话可能产生多条流水（下单、查单、回调、退款），
 * 用于对账、排障与审计。
 * 只追加不修改，回调原文完整保留以便复现争议订单。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "payment.pangu.tax", version = "v1alpha1", kind = "PaymentTransaction",
    plural = "paymenttransactions", singular = "paymenttransaction")
public class PaymentTransaction extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private TransactionSpec spec;

    @Data
    public static class TransactionSpec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "关联的支付会话 metadata.name")
        private String sessionName;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "支付会话业务码")
        private String sessionCode;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "渠道标识")
        private String provider;

        @Schema(description = "渠道侧交易号")
        private String providerTradeNo;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "金额")
        private BigDecimal amount;

        @Schema(description = "币种")
        private String currency;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "类型：INITIATE / QUERY / CALLBACK / CLOSE / REFUND")
        private String type;

        @Schema(description = "结果：SUCCESS / FAILED / PENDING")
        private String result;

        @Schema(description = "渠道返回或回调原文")
        private Map<String, String> raw;

        @Schema(description = "发生时间")
        private Instant occurredAt;
    }

    public static final class Type {
        public static final String INITIATE = "INITIATE";
        public static final String QUERY = "QUERY";
        public static final String CALLBACK = "CALLBACK";
        public static final String CLOSE = "CLOSE";
        public static final String REFUND = "REFUND";
        /** 付款人提交凭证（人工类渠道）。 */
        public static final String PROOF = "PROOF";
        /** 运营审核通过并核销。 */
        public static final String REVIEW = "REVIEW";
        /** 超时自动过期。 */
        public static final String EXPIRE = "EXPIRE";

        private Type() {
        }
    }
}
