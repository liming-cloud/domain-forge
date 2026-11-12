package org.domainforge.iam.application.port;

import org.domainforge.shared.DependencyUnavailableException;
import org.jspecify.annotations.Nullable;

import java.io.Serial;

/**
 * IAM 依赖不可用：会话存储、登录防护存储、密码计算或数据库读取无法完成，结果不可确认。
 *
 * <p>Web 入口统一返回 503 与 {@code SERVICE_UNAVAILABLE}；消息与原因只进内部诊断。
 */
public final class IamUnavailableException extends DependencyUnavailableException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息为固定说明，不含令牌、键或 SQL。
     *
     * @param message 固定说明
     * @param cause   原因，没有时为空
     */
    public IamUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
