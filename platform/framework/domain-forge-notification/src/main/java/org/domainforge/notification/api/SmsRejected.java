package org.domainforge.notification.api;

import org.domainforge.shared.BusinessRuleViolation;

/**
 * 服务商拒绝了这条短信（号码无效、频控）。组件按类别转为 4xx，代码见 {@link Reason}。
 */
public final class SmsRejected extends BusinessRuleViolation {
    private final Reason reason;

    /**
     * 原因决定稳定代码，提示可安全展示。
     */
    public SmsRejected(Reason reason, String message) {
        super(reason.kind(), reason.code(), message);
        this.reason = reason;
    }

    /**
     * 拒绝原因。
     */
    public Reason reason() {
        return reason;
    }

    /**
     * 拒绝原因及其稳定代码。
     */
    public enum Reason {
        /** 号码格式无效或服务商判定为无效号码。 */
        INVALID_PHONE(Kind.UNPROCESSABLE, "SMS_INVALID_PHONE"),
        /** 服务商对该号码的发送频控。 */
        RATE_LIMITED(Kind.RATE_LIMITED, "SMS_RATE_LIMITED");

        private final Kind kind;
        private final String code;

        Reason(Kind kind, String code) {
            this.kind = kind;
            this.code = code;
        }

        /**
         * 违反的类别，决定协议状态。
         */
        public Kind kind() {
            return kind;
        }

        /**
         * 客户端判断用的稳定代码。
         */
        public String code() {
            return code;
        }
    }
}
