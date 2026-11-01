package run.pangu.payment.setting;

import lombok.Data;

/**
 * 插件设置「接口对接」分组的类型化视图。
 *
 * <p>为什么不直接读 {@code JsonNode}：Halo 2.27 起
 * {@code ReactiveSettingFetcher#get(String)} 已废弃（返回 Jackson 2 的节点，
 * 而主容器已迁移到 Jackson 3），继续使用会在未来版本直接编译失败。
 * 走 {@code fetch(group, Class)} 由 Halo 内部完成反序列化，与具体 Jackson 版本解耦。
 */
@Data
public class ApiSetting {

    /** 允许调用支付 API 的密钥，多个用英文逗号分隔。 */
    private String apiKeys;

    /** 会话默认有效期（分钟），未配置时由调用方兜底。 */
    private Integer defaultExpireMinutes;
}
