package org.domainforge.access.internal;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.exception.NotLoginException;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.shared.CurrentSubject;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本请求同令牌快照的边界反例，不替代 IAM 真实 SQL 许可与 Redis 验证。
 */
class RequestCurrentSubjectTest {
    // 切换凭证即废弃旧快照，失败后切回原凭证也必须重新验证完整事实。
    @Test
    void changedTokenNeverReusesOldSuccessAfterFailure() {
        for (var failure : AccessFailure.values()) {
            var reads = new AtomicInteger();
            var ids = new AtomicLong(100);
            var subjects = new RequestCurrentSubject(token -> {
                reads.incrementAndGet();
                if (token.equals("other")) throw new AccessException(failure);
                return new CurrentSubject(ids.incrementAndGet(), Set.of());
            });
            SaTokenContextMockUtil.setMockContext();
            try {
                var first = subjects.current("first");
                assertSame(first, subjects.current("first"));
                assertThrows(RuntimeException.class, () -> subjects.current("other"));
                assertNotEquals(first, subjects.current("first"));
                assertEquals(3, reads.get());
            } finally {
                SaTokenContextMockUtil.clearContext();
            }
        }
    }

    // 原生异常保留正确分类和登录类型，消息不能携带令牌。
    @Test
    void invalidIdentityUsesSanitizedNativeClassification() {
        SaTokenContextMockUtil.setMockContext();
        try {
            var subjects = new RequestCurrentSubject(token -> {
                throw new AccessException(AccessFailure.UNAUTHENTICATED);
            });
            var error = assertThrows(NotLoginException.class, () -> subjects.current("synthetic-secret-token"));
            assertEquals("login", error.getLoginType());
            assertEquals(NotLoginException.INVALID_TOKEN, error.getType());
            assertFalse(error.toString().contains("synthetic-secret-token"));
        } finally {
            SaTokenContextMockUtil.clearContext();
        }
    }

    // 缺少令牌直接按未登录处理，不调用事实源；事实源返回空属于不可用而非匿名。
    @Test
    void missingTokenAndEmptySourceResultAreRejected() {
        SaTokenContextMockUtil.setMockContext();
        try {
            var reads = new AtomicInteger();
            var subjects = new RequestCurrentSubject(token -> {
                reads.incrementAndGet();
                return null;
            });
            assertThrows(NotLoginException.class, () -> subjects.current(" "));
            assertEquals(0, reads.get());
            var error = assertThrows(AccessException.class, () -> subjects.current("token"));
            assertEquals(AccessFailure.UNAVAILABLE, error.failure());
        } finally {
            SaTokenContextMockUtil.clearContext();
        }
    }
}
