package org.domainforge.notification.internal;

import org.domainforge.notification.api.SmsRejected;

import java.util.regex.Pattern;

/**
 * 手机号规范化：去掉空格与连字符；{@code +86}／{@code 0086} 开头的中国大陆号码去掉区号；
 * 其余国际号码保留区号、去掉 {@code +}（阿里云的国际号码格式为“区号＋号码”）。
 */
final class PhoneNumbers {
    private static final Pattern MAINLAND = Pattern.compile("1[3-9][0-9]{9}");
    private static final Pattern INTERNATIONAL = Pattern.compile("[1-9][0-9]{7,14}");

    private PhoneNumbers() {
    }

    /**
     * 返回规范化号码；格式无效时抛出 {@link SmsRejected}。
     */
    static String normalize(String raw) {
        String phone = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        if (phone.startsWith("+86")) phone = phone.substring(3);
        else if (phone.startsWith("0086")) phone = phone.substring(4);
        if (MAINLAND.matcher(phone).matches()) return phone;
        if (phone.startsWith("+") && INTERNATIONAL.matcher(phone.substring(1)).matches()) return phone.substring(1);
        if (phone.startsWith("00") && INTERNATIONAL.matcher(phone.substring(2)).matches()) return phone.substring(2);
        throw new SmsRejected(SmsRejected.Reason.INVALID_PHONE, "手机号格式无效");
    }

    /**
     * 日志中使用的掩码：只保留后 4 位。
     */
    static String mask(String phone) {
        return phone.length() <= 4 ? "****" : "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
