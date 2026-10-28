package @@PACKAGE@@.infrastructure.acl.iam;

import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.servicecall.api.ServiceCallException;
import org.domainforge.servicecall.api.ServiceCalls;
import org.domainforge.shared.ApiResponse;

/**
 * 把 IAM 调用结果翻译为本地访问失败：401／403 保持语义，其他失败（拒绝、不可用、结果未知、合同不符）一律视为依赖不可用。
 *
 * <p>不可用不能降级为匿名或允许，也不能伪装成无权限。调用失败的分类由跨服务调用组件给出。
 */
final class IamFailures {
    private IamFailures() {
    }

    static AccessException translate(RuntimeException failure) {
        if (failure instanceof AccessException access) return access;
        if (failure instanceof ServiceCallException call) {
            return switch (call.failure()) {
                case UNAUTHENTICATED -> new AccessException(AccessFailure.UNAUTHENTICATED);
                case FORBIDDEN -> new AccessException(AccessFailure.FORBIDDEN);
                default -> new AccessException(AccessFailure.UNAVAILABLE);
            };
        }
        return new AccessException(AccessFailure.UNAVAILABLE);
    }

    /**
     * 成功外壳且有数据才返回；其他代码或缺失数据视为合同不符。
     */
    static <T> T data(ApiResponse<T> response) {
        T data = ServiceCalls.data(response);
        if (data == null) throw new AccessException(AccessFailure.UNAVAILABLE);
        return data;
    }
}
