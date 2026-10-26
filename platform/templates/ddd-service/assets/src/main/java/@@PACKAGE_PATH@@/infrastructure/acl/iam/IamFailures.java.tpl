package @@PACKAGE@@.infrastructure.acl.iam;

import feign.FeignException;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.shared.ApiResponse;

/**
 * 把 IAM 调用结果翻译为本地访问失败：401／403 保持语义，其他状态、超时与解码错误一律视为依赖不可用。
 *
 * <p>不可用不能降级为匿名或允许，也不能伪装成无权限。
 */
final class IamFailures {
    private IamFailures() {
    }

    static AccessException translate(RuntimeException failure) {
        if (failure instanceof AccessException access) return access;
        if (failure instanceof FeignException feign) {
            return switch (feign.status()) {
                case 401 -> new AccessException(AccessFailure.UNAUTHENTICATED);
                case 403 -> new AccessException(AccessFailure.FORBIDDEN);
                default -> new AccessException(AccessFailure.UNAVAILABLE);
            };
        }
        return new AccessException(AccessFailure.UNAVAILABLE);
    }

    /**
     * 成功外壳才返回数据；其他代码或缺失数据视为合同不符。
     */
    static <T> T data(ApiResponse<T> response) {
        if (response == null || !"SUCCESS".equals(response.code()) || response.data() == null)
            throw new AccessException(AccessFailure.UNAVAILABLE);
        return response.data();
    }
}
