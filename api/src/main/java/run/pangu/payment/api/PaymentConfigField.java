package run.pangu.payment.api;

import java.util.List;

/**
 * 渠道配置表单字段描述，后台按 {@code type} 渲染不同控件。
 *
 * @param name 字段键名，写入渠道配置
 * @param label 展示标签
 * @param type 控件类型：text / textarea / password / number / select / switch
 * @param required 是否必填
 * @param secret 是否敏感（写入 Secret 并在回显时打码）
 * @param placeholder 占位提示
 * @param options {@code type=select} 时的候选项，其余类型为空
 * @param help 字段说明
 */
public record PaymentConfigField(
    String name,
    String label,
    String type,
    boolean required,
    boolean secret,
    String placeholder,
    List<Option> options,
    String help
) {

    /** 下拉选项。 */
    public record Option(String label, String value) {
    }

    public static PaymentConfigField text(String name, String label, boolean required) {
        return new PaymentConfigField(name, label, "text", required, false, null, List.of(), null);
    }

    public static PaymentConfigField secret(String name, String label, boolean required) {
        return new PaymentConfigField(name, label, "password", required, true, null, List.of(), null);
    }

    public static PaymentConfigField textarea(String name, String label, boolean required) {
        return new PaymentConfigField(name, label, "textarea", required, false, null, List.of(), null);
    }

    public static PaymentConfigField select(String name, String label, boolean required,
        List<Option> options) {
        return new PaymentConfigField(name, label, "select", required, false, null, options, null);
    }
}
