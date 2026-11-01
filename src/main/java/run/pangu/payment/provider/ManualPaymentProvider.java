package run.pangu.payment.provider;

import org.springframework.stereotype.Component;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.service.PaymentConfigService;

/**
 * 人工确认渠道：线下收款后由运营在后台核销。
 *
 * <p>字段与校验规则继承自人工类基类，与银行转账渠道共用。
 */
@Component
public class ManualPaymentProvider extends AbstractManualPaymentProvider {

    public ManualPaymentProvider(PaymentConfigService configService) {
        super(configService);
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.MANUAL;
    }

    @Override
    public String getDisplayName() {
        return PaymentProvider.MANUAL.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "线下完成支付后由管理员人工确认，适用于内部结算或对公转账场景";
    }
}
