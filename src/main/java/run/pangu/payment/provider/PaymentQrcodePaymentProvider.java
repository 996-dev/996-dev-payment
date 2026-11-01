package run.pangu.payment.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import run.pangu.payment.context.PaymentCallbackContext;
import run.pangu.payment.context.PaymentInitiateContext;
import run.pangu.payment.context.PaymentQueryContext;
import run.pangu.payment.extension.PaymentMethod;
import run.pangu.payment.extension.PaymentMethodConfig;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.extension.PaymentScene;
import run.pangu.payment.extension.PaymentSession;
import run.pangu.payment.payload.PaymentInitiatePayload;
import run.pangu.payment.payload.PaymentResponseType;
import run.pangu.payment.service.PaymentConfigService;

/**
 * 静态收款码渠道：展示一张固定二维码，用户扫码付款后由运营核销。
 *
 * <p>配置项为
 * {@code qrcodeImageUrl}（二维码图片地址）与 {@code instructions}（付款指引）。
 *
 * <p>该渠道无商户对接能力，因此既不能主动查单也不会收到回调，
 * 状态只能由后台人工确认结束。
 */
@Component
public class PaymentQrcodePaymentProvider extends AbstractManualPaymentProvider {

    public PaymentQrcodePaymentProvider(PaymentConfigService configService) {
        super(configService);
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.PAYMENT_QRCODE;
    }

    @Override
    public String getDisplayName() {
        return PaymentProvider.PAYMENT_QRCODE.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "展示固定收款二维码，用户扫码付款后由管理员确认到账";
    }

    @Override
    public List<PaymentScene> getSupportedScenes() {
        return List.of(PaymentScene.PC_WEB, PaymentScene.MOBILE_WEB, PaymentScene.MINI_PROGRAM);
    }

    @Override
    public List<Map<String, Object>> getConfigFormSchema() {
        return List.of(
            field("qrcodeImageUrl", "收款码图片地址", "text", true),
            field("instructions", "付款指引", "textarea", false)
        );
    }

    @Override
    public Errors validateSettings(PaymentMethodConfig config) {
        var errors = config.errors();
        if (config.missing("qrcodeImageUrl")) {
            errors.rejectValue("settings.qrcodeImageUrl", "required", "收款码图片地址不能为空");
        } else {
            String url = config.getString("qrcodeImageUrl");
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("/")) {
                errors.rejectValue("settings.qrcodeImageUrl", "invalid",
                    "需为 http(s) 地址或站内绝对路径");
            }
        }
        return errors;
    }

    @Override
    public Mono<PaymentInitiatePayload> initiatePayment(PaymentInitiateContext context,
        ServerWebExchange exchange) {
        return resolveConfig(context.method()).map(config -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("qrcodeImageUrl", config.getString("qrcodeImageUrl", ""));
            params.put("amount", context.amount());
            params.put("sessionCode", context.sessionCode());
            String instructions = config.getString("instructions");
            if (instructions != null && !instructions.isBlank()) {
                params.put("instructions", instructions);
            }
            return PaymentInitiatePayload.builder()
                .responseType(PaymentResponseType.QRCODE_URL)
                .url(config.getString("qrcodeImageUrl", ""))
                .params(params)
                .message(instructions)
                .build();
        });
    }

    @Override
    public Mono<ServerResponse> handleCallback(PaymentCallbackContext context,
        ServerWebExchange exchange) {
        return super.handleCallback(context, exchange);
    }

    @Override
    public Mono<Void> reconcilePayment(PaymentQueryContext context) {
        return super.reconcilePayment(context);
    }

    @Override
    public Mono<Void> closePayment(PaymentSession session, PaymentMethod method) {
        return super.closePayment(session, method);
    }
}
