package run.pangu.payment.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 会话视图：调用方读取支付状态用，只读快照。
 *
 * @param sessionCode 会话码
 * @param provider 渠道标识
 * @param bizType 来源业务类型
 * @param bizNo 来源业务单号
 * @param amount 应付金额
 * @param currency 币种
 * @param subject 支付标题
 * @param phase 状态：PENDING 待支付 / PENDING_REVIEW 待审核 / PAID 已支付 /
 *     CLOSED 已关闭 / EXPIRED 已过期 / FAILED 失败
 * @param message 状态说明
 * @param paidAt 支付确认时间
 * @param providerTradeNo 渠道交易号（人工渠道为核销单号）
 * @param expiresAt 过期时间
 * @param auditState 人工渠道审核状态：NONE / PENDING / APPROVED / REJECTED
 * @param auditRemark 审核备注或驳回原因
 */
public record PaymentSessionView(
    String sessionCode,
    String provider,
    String bizType,
    String bizNo,
    BigDecimal amount,
    String currency,
    String subject,
    String phase,
    String message,
    Instant paidAt,
    String providerTradeNo,
    Instant expiresAt,
    String auditState,
    String auditRemark
) {
}
