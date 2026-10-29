package org.domainforge.notification.api;

/**
 * 验证码发送受限。组件映射为业务拒绝（HTTP 200），代码见 {@link Reason}。
 */
public final class VerificationRejected extends RuntimeException {
    private final Reason reason;

    /**
     * 原因决定稳定代码，提示可安全展示。
     */
    public VerificationRejected(Reason reason, String message) {
        super(message);
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
        TOO_FREQUENT("VERIFICATION_TOO_FREQUENT"),
        /** 当天发送已达上限。 */
        DAILY_LIMIT("VERIFICATION_DAILY_LIMIT");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        /**
         * 客户端判断用的稳定代码。
         */
        public String code() {
            return code;
        }
    }
}
