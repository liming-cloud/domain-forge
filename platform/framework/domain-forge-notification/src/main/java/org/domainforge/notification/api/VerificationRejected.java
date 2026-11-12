package org.domainforge.notification.api;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * 验证码发送受限。组件按类别转为 4xx，代码见 {@link Reason}。
 */
public final class VerificationRejected extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    /**
     * 原因决定稳定代码，提示可安全展示。
     */
    public VerificationRejected(Reason reason, String message) {
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
        /** 距上次发送未满重发间隔。 */
        TOO_FREQUENT(Kind.RATE_LIMITED, "VERIFICATION_TOO_FREQUENT"),
        /** 当天发送已达上限。 */
        DAILY_LIMIT(Kind.RATE_LIMITED, "VERIFICATION_DAILY_LIMIT");

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
