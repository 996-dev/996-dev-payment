package run.pangu.payment.api;

/**
 * 会话状态与审核状态常量。
 *
 * <p>字符串取值而非枚举：跨插件升级时新增状态不会让使用方的 switch 编译失败。
 */
public final class PaymentPhase {

    /** 待支付：会话已创建，尚未收到任何到账证据。 */
    public static final String PENDING = "PENDING";

    /** 待审核：用户已提交付款凭证，等待运营确认（仅人工类渠道）。 */
    public static final String PENDING_REVIEW = "PENDING_REVIEW";

    /** 已支付：渠道回调 / 主动查单 / 人工审核通过，三选一确认。 */
    public static final String PAID = "PAID";

    /** 已关闭：调用方主动关单。 */
    public static final String CLOSED = "CLOSED";

    /** 已过期：超过 expiresAt 未支付，由定时任务收敛。 */
    public static final String EXPIRED = "EXPIRED";

    /** 失败：渠道明确返回失败。 */
    public static final String FAILED = "FAILED";

    /** 审核状态：不需要审核（在线渠道）。 */
    public static final String AUDIT_NONE = "NONE";

    /** 审核状态：已提交凭证待审核。 */
    public static final String AUDIT_PENDING = "PENDING";

    /** 审核状态：审核通过。 */
    public static final String AUDIT_APPROVED = "APPROVED";

    /** 审核状态：审核驳回，用户可重新提交凭证。 */
    public static final String AUDIT_REJECTED = "REJECTED";

    private PaymentPhase() {
    }
}
