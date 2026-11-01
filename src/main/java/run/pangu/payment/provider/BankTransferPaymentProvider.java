package run.pangu.payment.provider;

import org.springframework.stereotype.Component;
import run.pangu.payment.extension.PaymentProvider;
import run.pangu.payment.service.PaymentConfigService;

/**
 * 银行转账渠道。
 *
 * <p>与人工确认渠道共用字段与流程，
 * 区别在于文案面向对公转账场景，渠道标识为 {@code bank-transfer}。
 */
@Component
public class BankTransferPaymentProvider extends AbstractManualPaymentProvider {

    public BankTransferPaymentProvider(PaymentConfigService configService) {
        super(configService);
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.BANK_TRANSFER;
    }

    @Override
    public String getDisplayName() {
        return PaymentProvider.BANK_TRANSFER.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "展示收款账户信息，客户完成银行转账后由管理员核对到账并确认";
    }
}
