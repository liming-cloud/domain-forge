package org.domainforge.iam.application.port;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * IAM 应用层的业务拒绝：类别决定协议状态，错误码稳定（清单见 {@code docs/reference/错误码.md}），提示由服务端固定提供。
 *
 * <p>由 web-support 的唯一分派点转为 Problem Details；限流另带 {@code Retry-After} 秒数。依赖不可用用 {@link IamUnavailableException}。
 */
public final class IamException extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final long MAX_RETRY_SECONDS = 3600;

    private final long retryAfterSeconds;

    private IamException(Kind kind, String code, String message, long retryAfterSeconds) {
        super(kind, code, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 输入格式或取值无效（400，{@code INVALID_REQUEST}）。
     *
     * @param message 固定说明
     * @return 规则违反
     */
    public static IamException invalid(String message) {
        return new IamException(Kind.INVALID_REQUEST, "INVALID_REQUEST", message, 0);
    }

    /**
     * 未认证或凭据无效（401）。
     *
     * @param code    稳定错误码
     * @param message 固定说明
     * @return 规则违反
     */
    public static IamException unauthenticated(String code, String message) {
        return new IamException(Kind.UNAUTHENTICATED, code, message, 0);
    }

    /**
     * 已认证但无权限（403，{@code FORBIDDEN}）。
     *
     * @param message 固定说明
     * @return 规则违反
     */
    public static IamException forbidden(String message) {
        return new IamException(Kind.FORBIDDEN, "FORBIDDEN", message, 0);
    }

    /**
     * 请求的目标资源不存在（404）。
     *
     * @param code    稳定错误码
     * @param message 固定说明
     * @return 规则违反
     */
    public static IamException notFound(String code, String message) {
        return new IamException(Kind.NOT_FOUND, code, message, 0);
    }

    /**
     * 当前状态不允许或并发冲突（409）。
     *
     * @param code    稳定错误码
     * @param message 固定说明
     * @return 规则违反
     */
    public static IamException conflict(String code, String message) {
        return new IamException(Kind.CONFLICT, code, message, 0);
    }

    /**
     * 请求引用的其他资源不存在或不可用（422）。
     *
     * @param code    稳定错误码
     * @param message 固定说明
     * @return 规则违反
     */
    public static IamException unprocessable(String code, String message) {
        return new IamException(Kind.UNPROCESSABLE, code, message, 0);
    }

    /**
     * 请求过于频繁（429）；重试建议限制在 1 秒到 1 小时。
     *
     * @param code              稳定错误码
     * @param message           固定说明
     * @param retryAfterSeconds 建议的等待秒数
     * @return 规则违反
     */
    public static IamException rateLimited(String code, String message, long retryAfterSeconds) {
        return new IamException(Kind.RATE_LIMITED, code, message, Math.clamp(retryAfterSeconds, 1, MAX_RETRY_SECONDS));
    }

    /**
     * 限流的等待建议（秒）；非限流为零。
     *
     * @return 秒数
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
