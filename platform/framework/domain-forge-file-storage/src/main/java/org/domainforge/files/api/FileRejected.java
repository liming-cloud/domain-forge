package org.domainforge.files.api;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * 上传不符合策略。组件把它按类别转为 4xx，代码见 {@link Reason}。
 */
public final class FileRejected extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    /**
     * 原因决定稳定代码，提示可安全展示。
     */
    public FileRejected(Reason reason, String message) {
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
        /** 空文件。 */
        EMPTY(Kind.UNPROCESSABLE, "FILE_EMPTY"),
        /** 超过大小上限。 */
        TOO_LARGE(Kind.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE"),
        /** 类型不在白名单中。 */
        TYPE_NOT_ALLOWED(Kind.UNPROCESSABLE, "FILE_TYPE_NOT_ALLOWED"),
        /** 清理后文件名为空。 */
        NAME_INVALID(Kind.UNPROCESSABLE, "FILE_NAME_INVALID");

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
