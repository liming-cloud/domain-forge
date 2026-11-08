package org.domainforge.servicecall.api;

import org.domainforge.shared.DependencyUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 调用方不翻译的跨服务调用失败按依赖不可用处理（503），而不是 500。
 */
class FailureContractTest {
    @Test
    void everyCallFailureIsDependencyUnavailable() {
        for (ServiceCallFailure failure : ServiceCallFailure.values()) {
            assertInstanceOf(DependencyUnavailableException.class, new ServiceCallException(failure));
        }
    }
}
