package org.domainforge.shared;

import java.io.Serial;

import java.util.regex.Pattern;

/**
 * 业务规则违反的基类：带稳定错误码，消息可以安全展示给调用方。
 *
 * <p>领域层与应用层各自声明具体子类；Web 入口由 web-support 按 {@link Kind} 统一转为 Problem Details，不逐个登记映射。
 */
public abstract class BusinessRuleViolation extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final Kind kind;
    private final String code;

    /**
     * 拒绝格式不合规的错误码和空提示。
     *
     * @param kind    违反的类别，决定协议状态
     * @param code    客户端判断用的稳定代码，大写字母、数字与下划线
     * @param message 可安全展示的提示，不含秘密或内部细节
     */
    protected BusinessRuleViolation(Kind kind, String code, String message) {
        super(message);
        if (kind == null || code == null || !CODE.matcher(code).matches() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("规则违反必须有类别、合规的错误码和提示");
        }
        this.kind = kind;
        this.code = code;
    }

    /**
     * 违反的类别。
     *
     * @return 非空类别
     */
    public final Kind kind() {
        return kind;
    }

    /**
     * 稳定错误码。
     *
     * @return 非空代码
     */
    public final String code() {
        return code;
    }

    /**
     * 规则违反的类别；每个类别对应一个固定的 HTTP 状态。
     */
    public enum Kind {
        /** 输入格式或取值无效（400）。 */
        INVALID_REQUEST,
        /** 未认证（401）。 */
        UNAUTHENTICATED,
        /** 已认证但无权限（403）。 */
        FORBIDDEN,
        /** 资源不存在（404）。 */
        NOT_FOUND,
        /** 当前状态不允许或并发冲突（409）。 */
        CONFLICT,
        /** 请求内容过大（413）。 */
        PAYLOAD_TOO_LARGE,
        /** 业务规则不满足（422）。 */
        UNPROCESSABLE,
        /** 请求过于频繁（429）。 */
        RATE_LIMITED
    }
}
