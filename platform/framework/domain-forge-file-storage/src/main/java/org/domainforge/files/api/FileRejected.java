package org.domainforge.files.api;

/**
 * 上传不符合策略。组件把它映射为业务拒绝（HTTP 200），代码见 {@link Reason}。
 */
public final class FileRejected extends RuntimeException {
    private final Reason reason;

    /**
     * 原因决定稳定代码，提示可安全展示。
     */
    public FileRejected(Reason reason, String message) {
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
        /** 空文件。 */
        EMPTY("FILE_EMPTY"),
        /** 超过大小上限。 */
        TOO_LARGE("FILE_TOO_LARGE"),
        /** 类型不在白名单中。 */
        TYPE_NOT_ALLOWED("FILE_TYPE_NOT_ALLOWED"),
        /** 清理后文件名为空。 */
        NAME_INVALID("FILE_NAME_INVALID");

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
