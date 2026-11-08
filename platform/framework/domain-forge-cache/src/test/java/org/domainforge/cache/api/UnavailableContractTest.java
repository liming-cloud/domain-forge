package org.domainforge.cache.api;

import org.domainforge.shared.DependencyUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 缓存与锁不可用必须是依赖不可用，Web 入口才会返回 503 而不是 500。
 */
class UnavailableContractTest {
    @Test
    void cacheAndLockFailuresAreDependencyUnavailable() {
        assertInstanceOf(DependencyUnavailableException.class, new CacheUnavailableException("缓存不可用", null));
        assertInstanceOf(DependencyUnavailableException.class, new LockUnavailableException("锁不可用", null));
    }
}
