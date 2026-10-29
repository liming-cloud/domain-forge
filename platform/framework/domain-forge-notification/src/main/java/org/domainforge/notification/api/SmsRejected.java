package org.domainforge.notification.api;

/**
 * 服务商拒绝了这条短信（号码无效、频控）。组件映射为业务拒绝（HTTP 200），代码见 {@link Reason}。
 */
public final class SmsRejected extends RuntimeException {
    private final Reason reason;

    /**
     * 原因决定稳定代码，提示可安全展示。
     */
    public SmsRejected(Reason reason, String message) {
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
        /** 号码格式无效或服务商判定为无效号码。 */
        INVALID_PHONE("SMS_INVALID_PHONE"),
        /** 服务商对该号码的发送频控。 */
        RATE_LIMITED("SMS_RATE_LIMITED");

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
