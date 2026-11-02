package run.pangu.payment.setting;

import lombok.Data;

/**
 * 插件设置「通知」分组的类型化视图。
 *
 * <p>支付成功后向业务方 notifyUrl 发起 POST 通知时读取本配置：
 * 附加请求头用于业务方校验来源，重试次数用于业务方短暂不可用时的补偿。
 */
@Data
public class NotifySetting {

    /** 业务通知附加请求头，格式 {@code key:value}，多个用分号或换行分隔。 */
    private String bizNotifyHeader;

    /** 业务方返回非 2xx 时的重试次数，0 表示只发一次。 */
    private Integer notifyRetryTimes;
}
