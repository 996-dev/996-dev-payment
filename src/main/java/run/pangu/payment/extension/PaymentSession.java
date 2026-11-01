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
 * 支付会话：一次支付请求的完整生命周期载体。
 *
 * <p>由业务方发起（携带 bizType/bizNo 标识来源业务单），
 * 经渠道路由后落地为第三方支付订单，回调或主动查单后回写状态。
 * 会话不绑定任何订单模型：业务归属由 {@code bizType + bizNo} 表达，
 * 因此同一套支付链路可服务多个业务域。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "payment.pangu.tax", version = "v1alpha1", kind = "PaymentSession",
    plural = "paymentsessions", singular = "paymentsession")
public class PaymentSession extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private PaymentSessionSpec spec;

    private PaymentSessionStatus status;

    @Data
    public static class PaymentSessionSpec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "支付会话唯一码，对外暴露")
        private String sessionCode;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "渠道标识，如 alipay / wechatpay / stripe")
        private String provider;

        @Schema(description = "支付方式资源名（PaymentMethod.metadata.name）")
        private String methodName;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "来源业务类型，如 order / recharge / subscription")
        private String bizType;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "来源业务单号")
        private String bizNo;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "支付金额")
        private BigDecimal amount;

        @Schema(description = "币种，默认 CNY")
        private String currency;

        @Schema(description = "支付标题，展示在收银台")
        private String subject;

        @Schema(description = "支付描述")
        private String description;

        @Schema(description = "支付完成后业务方回跳地址")
        private String returnUrl;

        @Schema(description = "业务方异步通知地址")
        private String notifyUrl;

        /**
         * 渠道返回的支付参数（二维码链接、JSAPI 参数等）。
         *
         * <p>值统一为字符串。{@code Map<String, Object>} 会被 Halo 的 schema 生成器
         * 推断为「每个 value 都必须是 object」，写入字符串/布尔值时校验直接 1027 失败。
         * 这也是早期「下单 500 但会话已创建」的根因——create 成功、写 payload 时被拒。
         */
        @Schema(description = "渠道返回的支付参数（二维码链接、JSAPI 参数等）")
        private Map<String, String> payload;

        @Schema(description = "响应类型：QRCODE / REDIRECT / JSAPI / H5 / NATIVE / MANUAL")
        private String responseType;

        @Schema(description = "会话过期时间")
        private Instant expiresAt;

        @Schema(description = "支付完成时间")
        private Instant paidAt;

        @Schema(description = "渠道交易号")
        private String providerTradeNo;

        @Schema(description = "幂等键，相同键的重复请求返回同一会话")
        private String idempotencyKey;

        @Schema(description = "备注")
        private String remark;

        /**
         * 人工渠道的付款凭证，由付款人在收银台提交。
         *
         * <p>键：payerName 付款人 / payerAccount 付款账号后四位 / transferNo 银行流水号 /
         * paidAmount 实付金额 / paidAt 付款时间 / screenshotUrl 凭证截图 /
         * remark 付款说明 / submittedAt 提交时间。
         *
         * <p>值统一为字符串：{@code Map<String, Object>} 会被 Halo 的 schema 生成器
         * 推断成「每个 value 都是 object」，校验时字符串值直接报 1027 类型错误。
         */
        @Schema(description = "付款凭证（人工类渠道）")
        private Map<String, String> paymentProof;
    }

    @Data
    public static class PaymentSessionStatus {

        @Schema(description = "状态：PENDING 待支付 / PENDING_REVIEW 待审核 / PAID 已支付 / CLOSED 已关闭 / EXPIRED 已过期 / FAILED 失败")
        private String phase;

        @Schema(description = "状态说明或失败原因")
        private String message;

        @Schema(description = "最后同步时间")
        private Instant lastSyncAt;

        /** 人工审核状态：NONE 无需审核 / PENDING 待审核 / APPROVED 通过 / REJECTED 驳回。 */
        @Schema(description = "人工审核状态")
        private String auditState;

        /** 审核备注：通过时记录核销依据，驳回时即驳回原因，会展示给付款人。 */
        @Schema(description = "审核备注或驳回原因")
        private String auditRemark;

        /** 审核人账号名，来自登录态，不接受前端伪造。 */
        @Schema(description = "审核人")
        private String auditedBy;

        @Schema(description = "审核时间")
        private Instant auditedAt;

        @Schema(description = "凭证提交时间")
        private Instant proofSubmittedAt;
    }

    /** 支付会话状态常量。 */
    public static final class Phase {
        public static final String PENDING = "PENDING";
        /** 人工渠道专用：用户已提交凭证，等待运营审核。 */
        public static final String PENDING_REVIEW = "PENDING_REVIEW";
        public static final String PAID = "PAID";
        public static final String CLOSED = "CLOSED";
        public static final String EXPIRED = "EXPIRED";
        public static final String FAILED = "FAILED";

        /** 未终结状态，可被超时任务或人工动作推进。 */
        public static final java.util.Set<String> OPEN = java.util.Set.of(PENDING, PENDING_REVIEW);

        private Phase() {
        }
    }

    /** 人工审核状态常量。 */
    public static final class Audit {
        public static final String NONE = "NONE";
        public static final String PENDING = "PENDING";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";

        private Audit() {
        }
    }
}
