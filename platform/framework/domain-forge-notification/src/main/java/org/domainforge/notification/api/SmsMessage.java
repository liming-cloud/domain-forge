package org.domainforge.notification.api;

import java.util.Map;
import java.util.Objects;

/**
 * 一条模板短信。
 *
 * @param phone      手机号：中国大陆 11 位号码，或带国际区号的号码（可带 {@code +}）
 * @param template   逻辑模板名，在 {@code domainforge.sms.templates.<名称>} 中映射为服务商的模板编号
 * @param parameters 模板变量
 */
public record SmsMessage(String phone, String template, Map<String, String> parameters) {
    /**
     * 号码与模板不能为空，变量不可变。
     */
    public SmsMessage {
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(template, "template");
        parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
    }
}
