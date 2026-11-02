package run.pangu.payment.setting;

import lombok.Data;

/**
 * 插件设置「安全策略」分组的类型化视图。
 */
@Data
public class SecuritySetting {

    /** 是否允许匿名（不带 API Key）查询支付会话与状态。默认关闭。 */
    private Boolean allowAnonymousRead;

    /** 是否允许后台运营手工核销人工类渠道会话。默认开启。 */
    private Boolean allowManualMarkPaid;
}
