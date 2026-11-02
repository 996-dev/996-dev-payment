package run.pangu.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.extension.PaymentTransaction;
import run.pangu.payment.provider.ManualChannels;

/**
 * 人工类渠道的收款审核。
 *
 * <p>manual（人工确认）、bank-transfer（银行转账）、qrcode（静态收款码）没有
 * 在线扣款与异步回调，到账证据只能来自人：付款人提交凭证 → 运营核对银行流水 → 核销。
 * 直接把会话改 PAID 的「一键核销」会带来三个问题，这里逐一补上：
 *
 * <ol>
 *   <li><b>无凭证</b>：审核时看不到付了多少、谁付的、流水号是什么，无法与银行对账。
 *       因此新增 {@link #submitProof}，凭证落在 {@code spec.paymentProof}。</li>
 *   <li><b>无留痕</b>：谁在什么时候核销的、依据是什么，事后查不到。
 *       因此每次提交/通过/驳回都写 {@code PaymentTransaction}，审核人取登录态。</li>
 *   <li><b>无回退</b>：误核销无法撤销、驳回无法让用户重交。
 *       因此驳回把会话退回 {@code PENDING} 并保留驳回原因，用户可再次提交。</li>
 * </ol>
 *
 * <p>状态流转：{@code PENDING → PENDING_REVIEW → PAID}，
 * 驳回为 {@code PENDING_REVIEW → PENDING}（{@code auditState=REJECTED}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReviewService {

    /** 凭证截图允许的协议：站内绝对路径或 http(s)。 */
    private static final Set<String> ALLOWED_URL_PREFIX = Set.of("http://", "https://", "/");

    private final PaymentService paymentService;
    private final PaymentStateService stateService;
    private final ReactiveExtensionClient client;
    private final PaymentProviderRegistry registry;
    private final run.halo.app.plugin.ReactiveSettingFetcher settingFetcher;

    /** 插件设置「安全策略 → 允许后台手工核销」，默认开启。 */
    private Mono<Boolean> manualMarkPaidAllowed() {
        return settingFetcher.fetch("security",
                run.pangu.payment.setting.SecuritySetting.class)
            .map(run.pangu.payment.setting.SecuritySetting::getAllowManualMarkPaid)
            .map(Boolean.TRUE::equals)
            .defaultIfEmpty(true)
            .onErrorResume(e -> Mono.just(true));
    }

    /** 该渠道是否需要人工审核。判定口径见 {@link ManualChannels}，与下单流程共用。 */
    public boolean requiresReview(String provider) {
        if (provider == null) {
            return false;
        }
        String code = provider.toLowerCase();
        if (ManualChannels.isManual(code)) {
            return true;
        }
        // 外部扩展渠道：实现类继承人工基类即视为需要审核
        return registry.find(code)
            .map(p -> ManualChannels.isManual(code, p.getClass()))
            .orElse(false);
    }

    /**
     * 付款人提交付款凭证。
     *
     * <p>允许在 {@code PENDING}（首次提交）、{@code PENDING_REVIEW}（补充材料）、
     * {@code REJECTED} 后（重新提交）调用；已支付/已关闭/已过期一律拒绝。
     *
     * @param proof 凭证键值，见 {@link PaymentSession.PaymentSessionSpec#getPaymentProof()}
     * @param submitter 提交人标识（API Key 名或用户 ID），用于审计
     */
    public Mono<PaymentSession> submitProof(String sessionCode, Map<String, Object> proof,
        String submitter) {
        return paymentService.getByCode(sessionCode)
            .flatMap(session -> {
                var spec = session.getSpec();
                var status = session.getStatus();
                String phase = status == null ? null : status.getPhase();
                if (!PaymentSession.Phase.OPEN.contains(phase)) {
                    return Mono.error(new PaymentService.PaymentException("SESSION_NOT_OPEN",
                        "当前状态不允许提交凭证: " + phase));
                }
                if (!requiresReview(spec.getProvider())) {
                    return Mono.error(new PaymentService.PaymentException(
                        "REVIEW_NOT_REQUIRED", "该渠道为在线支付，无需提交付款凭证"));
                }
                Map<String, String> normalized = normalizeProof(proof);
                if (normalized.isEmpty()) {
                    return Mono.error(new PaymentService.PaymentException("EMPTY_PROOF",
                        "请至少填写付款人、银行流水号或凭证截图之一"));
                }
                if (normalized.containsKey("screenshotUrl")) {
                    String url = String.valueOf(normalized.get("screenshotUrl"));
                    if (!ALLOWED_URL_PREFIX.stream().anyMatch(url::startsWith)) {
                        return Mono.error(new PaymentService.PaymentException("INVALID_PROOF_URL",
                            "凭证截图需为 http(s) 地址或站内绝对路径"));
                    }
                }

                spec.setPaymentProof(normalized);
                // 凭证提交后必须给审核留出时间：会话有效期原本按"用户付款"设定（离线渠道 24h），
                // 若用户第 23 小时才提交，审核窗口只剩 1 小时就会被过期任务关掉，
                // 钱已到账却成了 EXPIRED。这里按审核保留期顺延，只延长不缩短。
                Instant reviewDeadline = Instant.now()
                    .plus(ManualChannels.REVIEW_WINDOW_HOURS, ChronoUnit.HOURS);
                if (spec.getExpiresAt() == null || spec.getExpiresAt().isBefore(reviewDeadline)) {
                    spec.setExpiresAt(reviewDeadline);
                }
                var target = status == null
                    ? new PaymentSession.PaymentSessionStatus() : status;
                target.setPhase(PaymentSession.Phase.PENDING_REVIEW);
                target.setAuditState(PaymentSession.Audit.PENDING);
                target.setAuditRemark(null);
                target.setProofSubmittedAt(Instant.now());
                target.setMessage("凭证已提交，等待运营审核");
                target.setLastSyncAt(Instant.now());
                session.setStatus(target);

                return client.update(session)
                    .flatMap(updated -> stateService.record(updated, PaymentTransaction.Type.PROOF,
                            "SUBMITTED", auditRaw(submitter, null, normalized))
                        .thenReturn(updated));
            });
    }

    /**
     * 审核通过并核销。
     *
     * <p>核销单号优先取凭证里的银行流水号，便于与银行对账单直接比对。
     *
     * @param remark 核销依据，必填（审核留痕的最低要求）
     */
    public Mono<PaymentSession> approve(String sessionCode, String reviewer, String remark) {
        if (remark == null || remark.isBlank()) {
            return Mono.error(new PaymentService.PaymentException("REMARK_REQUIRED",
                "请填写核销依据，例如银行流水号或对账说明"));
        }
        return manualMarkPaidAllowed().flatMap(allowed -> {
            if (!allowed) {
                return Mono.error(new PaymentService.PaymentException("MANUAL_MARK_PAID_DISABLED",
                    "后台手工核销已被关闭（插件设置 → 安全策略），人工类渠道请等待渠道回调或开启该开关"));
            }
            return approveInternal(sessionCode, reviewer, remark);
        });
    }

    private Mono<PaymentSession> approveInternal(String sessionCode, String reviewer,
        String remark) {
        return paymentService.getByCode(sessionCode)
            .flatMap(session -> {
                var status = session.getStatus();
                String phase = status == null ? null : status.getPhase();
                if (PaymentSession.Phase.PAID.equals(phase)) {
                    return Mono.just(session);
                }
                if (!PaymentSession.Phase.OPEN.contains(phase)) {
                    return Mono.error(new PaymentService.PaymentException("SESSION_NOT_OPEN",
                        "当前状态不允许核销: " + phase));
                }
                String tradeNo = tradeNoOf(session);
                return stateService.markPaid(session, tradeNo, auditRaw(reviewer, remark,
                        session.getSpec().getPaymentProof()))
                    .flatMap(paid -> {
                        var target = paid.getStatus();
                        target.setAuditState(PaymentSession.Audit.APPROVED);
                        target.setAuditRemark(remark);
                        target.setAuditedBy(reviewer);
                        target.setAuditedAt(Instant.now());
                        return client.update(paid);
                    })
                    .flatMap(updated -> stateService.record(updated,
                            PaymentTransaction.Type.REVIEW, "APPROVED",
                            auditRaw(reviewer, remark, updated.getSpec().getPaymentProof()))
                        .thenReturn(updated));
            });
    }

    /**
     * 审核驳回：退回待支付，保留驳回原因，允许付款人重新提交凭证。
     */
    public Mono<PaymentSession> reject(String sessionCode, String reviewer, String remark) {
        if (remark == null || remark.isBlank()) {
            return Mono.error(new PaymentService.PaymentException("REMARK_REQUIRED",
                "驳回必须填写原因，付款人会看到该内容"));
        }
        return paymentService.getByCode(sessionCode)
            .flatMap(session -> {
                var status = session.getStatus();
                String phase = status == null ? null : status.getPhase();
                if (PaymentSession.Phase.PAID.equals(phase)) {
                    return Mono.error(new PaymentService.PaymentException("SESSION_ALREADY_PAID",
                        "已支付的会话不能驳回，如需退款请走退款流程"));
                }
                if (!PaymentSession.Phase.OPEN.contains(phase)) {
                    return Mono.error(new PaymentService.PaymentException("SESSION_NOT_OPEN",
                        "当前状态不允许驳回: " + phase));
                }
                var target = status == null
                    ? new PaymentSession.PaymentSessionStatus() : status;
                target.setPhase(PaymentSession.Phase.PENDING);
                target.setAuditState(PaymentSession.Audit.REJECTED);
                target.setAuditRemark(remark);
                target.setAuditedBy(reviewer);
                target.setAuditedAt(Instant.now());
                target.setMessage("凭证未通过审核：" + remark);
                target.setLastSyncAt(Instant.now());
                session.setStatus(target);

                return client.update(session)
                    .flatMap(updated -> stateService.record(updated,
                            PaymentTransaction.Type.REVIEW, "REJECTED",
                            auditRaw(reviewer, remark, updated.getSpec().getPaymentProof()))
                        .thenReturn(updated));
            });
    }

    /**
     * 收敛超时会话：待支付 / 待审核超过有效期一律置为 EXPIRED。
     *
     * <p>待审核也纳入超时，是因为运营可能长期不处理；置为过期后付款人
     * 可重新下单，不会出现"钱付了但单据永远挂着"的僵尸状态。
     *
     * @return 本次收敛的会话数
     */
    public Mono<Integer> expireStale() {
        Instant now = Instant.now();
        return client.list(PaymentSession.class,
                s -> s.getSpec() != null && s.getSpec().getExpiresAt() != null
                    && s.getSpec().getExpiresAt().isBefore(now)
                    && s.getStatus() != null
                    && PaymentSession.Phase.OPEN.contains(s.getStatus().getPhase()),
                null)
            .flatMap(session -> stateService.markTerminal(session, PaymentSession.Phase.EXPIRED,
                    "超过有效期未完成支付，已自动关闭")
                .flatMap(expired -> stateService.record(expired,
                        PaymentTransaction.Type.EXPIRE, "EXPIRED",
                        Map.of("expiresAt", String.valueOf(session.getSpec().getExpiresAt())))
                    .thenReturn(1)))
            .reduce(0, Integer::sum)
            .doOnNext(count -> {
                if (count > 0) {
                    log.info("已收敛过期支付会话: {}", count);
                }
            })
            .onErrorResume(e -> {
                log.error("收敛过期会话失败", e);
                return Mono.just(0);
            });
    }

    /** 白名单化凭证字段，避免调用方塞入任意键污染资源。 */
    private Map<String, String> normalizeProof(Map<String, Object> proof) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (proof == null) {
            return normalized;
        }
        copyIfPresent(proof, normalized, "payerName", 64);
        copyIfPresent(proof, normalized, "payerAccount", 64);
        copyIfPresent(proof, normalized, "transferNo", 128);
        copyIfPresent(proof, normalized, "paidAt", 40);
        copyIfPresent(proof, normalized, "screenshotUrl", 512);
        copyIfPresent(proof, normalized, "remark", 512);
        Object amount = proof.get("paidAmount");
        if (amount != null) {
            try {
                BigDecimal parsed = new BigDecimal(String.valueOf(amount).trim());
                if (parsed.compareTo(BigDecimal.ZERO) > 0) {
                    normalized.put("paidAmount", parsed.toPlainString());
                }
            } catch (NumberFormatException e) {
                // 忽略非法金额，不影响其他字段提交
            }
        }
        return normalized;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, String> target, String key,
        int maxLength) {
        Object value = source.get(key);
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return;
        }
        target.put(key, text.length() > maxLength ? text.substring(0, maxLength) : text);
    }

    private String tradeNoOf(PaymentSession session) {
        Map<String, String> proof = session.getSpec().getPaymentProof();
        if (proof != null) {
            String transferNo = proof.get("transferNo");
            if (transferNo != null && !transferNo.isBlank()) {
                return transferNo;
            }
        }
        return "MANUAL-" + session.getSpec().getSessionCode();
    }

    private Map<String, Object> auditRaw(String actor, String remark, Map<String, String> proof) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("actor", actor == null ? "unknown" : actor);
        raw.put("at", Instant.now().toString());
        if (remark != null) {
            raw.put("remark", remark);
        }
        if (proof != null && !proof.isEmpty()) {
            raw.put("proof", proof);
        }
        return raw;
    }
}
