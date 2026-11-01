package run.pangu.payment.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import org.springframework.validation.Errors;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import run.pangu.payment.context.PaymentCallbackContext;
import run.pangu.payment.context.PaymentInitiateContext;
import run.pangu.payment.context.PaymentQueryContext;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentMethodConfig;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.payload.PaymentInitiatePayload;
import run.pangu.payment.service.PaymentConfigService;

/**
 * 人工类渠道基类：不发起真实在线扣款，只展示收款指引，由运营人工核销。
 *
 * <p>银行转账与人工确认共用
 * 同一套字段（银行名／开户行／户名／账号／操作指引），差异只在展示文案与渠道标识。
 *
 * <p>此类渠道的支付结果永远不由回调驱动：调用方拿到 {@code MANUAL} 载荷后，
 * 需由后台人工确认（或管理员调用核销接口）才会把会话置为已支付。
 */
public abstract class AbstractManualPaymentProvider implements PaymentMethodProvider {

    /** 收款账户信息字段，两个子类共用。 */
    protected static final List<String> REQUIRED_FIELDS =
        List.of("bankName", "accountName", "accountNumber");

    protected final PaymentConfigService configService;

    protected AbstractManualPaymentProvider(PaymentConfigService configService) {
        this.configService = configService;
    }

    @Override
    public List<PaymentScene> getSupportedScenes() {
        return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB, PaymentScene.MINI_PROGRAM);
    }

    @Override
    public List<Map<String, Object>> getConfigFormSchema() {
        return List.of(
            field("bankName", "银行名称", "text", true),
            field("bankAddress", "开户行支行", "text", false),
            field("accountName", "收款户名", "text", true),
            field("accountNumber", "收款账号", "text", true),
            field("instructions", "付款指引", "textarea", false)
        );
    }

    @Override
    public Errors validateSettings(PaymentMethodConfig config) {
        var errors = config.errors();
        for (String required : REQUIRED_FIELDS) {
            if (config.missing(required)) {
                errors.rejectValue("settings." + required, "required", "该字段不能为空");
            }
        }
        return errors;
    }

    @Override
    public Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange) {
        return resolveConfig(context.method()).map(config -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("bankName", config.getString("bankName", ""));
            params.put("bankAddress", config.getString("bankAddress", ""));
            params.put("accountName", config.getString("accountName", ""));
            params.put("accountNumber", config.getString("accountNumber", ""));
            params.put("instructions", config.getString("instructions", ""));
            params.put("amount", context.amount());
            params.put("sessionCode", context.sessionCode());
            return PaymentInitiatePayload.manual(params, buildManualMessage(config));
        });
    }

    /** 人工渠道无在线查单能力，状态由人工核销驱动，此处保持空实现以符合契约。 */
    @Override
    public Mono<Void> reconcilePayment(PaymentQueryContext context) {
        return Mono.empty();
    }

    /** 人工渠道不会收到渠道回调，任何回调都视为非法请求。 */
    @Override
    public Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange) {
        return ServerResponse.badRequest().bodyValue(
            Map.of("code", "CALLBACK_NOT_SUPPORTED",
                "message", getProvider().getCode() + " 渠道不支持异步回调"));
    }

    @Override
    public Mono<Void> closePayment(PaymentSession session, PaymentMethod method) {
        return Mono.empty();
    }

    /** 按支付方式资源名读取合并后的配置；缺失时返回空配置而非报错。 */
    protected Mono<PaymentMethodConfig> resolveConfig(PaymentMethod method) {
        if (method == null || method.getMetadata() == null) {
            return Mono.just(PaymentMethodConfig.of(Map.of()));
        }
        return configService.get(method.getMetadata().getName());
    }

    protected String buildManualMessage(PaymentMethodConfig config) {
        String instructions = config.getString("instructions");
        return instructions != null && !instructions.isBlank()
            ? instructions : "请按页面显示的收款信息完成转账，到账后由管理员确认。";
    }

    protected Map<String, Object> field(String name, String label, String type, boolean required) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("label", label);
        f.put("type", type);
        f.put("required", required);
        f.put("secret", false);
        return f;
    }
}
