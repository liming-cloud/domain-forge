package org.domainforge.iam.interfaces.security;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import org.domainforge.access.api.AccessUnavailableException;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamUnavailableException;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.domainforge.iam.infrastructure.security.JcaSecretOperations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IamCurrentSubjectSourceTest {
    // 身份事实源不可用时对外是访问不可用，原始异常作为原因保留给内部诊断。
    @Test
    void unavailableIdentityKeepsCause() {
        var storeDown = new IamUnavailableException("会话存储暂不可用", null);
        NativeSessions nativeSessions = new NativeSessions() {
            @Override
            public String issue(long accountId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Long subject(String token) {
                throw storeDown;
            }

            @Override
            public void revoke(String token) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void cleanup(String token) {
                throw new UnsupportedOperationException();
            }
        };
        SessionStore sessions = new SessionStore() {
            @Override
            public void insert(SessionGrant grant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void revoke(String digest) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CurrentIdentity current(String digest) {
                throw new UnsupportedOperationException();
            }
        };
        var source = new IamCurrentSubjectSource(
                new CurrentAuthorization(nativeSessions, sessions, new JcaSecretOperations(new byte[32])));
        try {
            SaTokenContextMockUtil.setMockContext();
            assertSame(storeDown, assertThrows(AccessUnavailableException.class, () -> source.resolve("t")).getCause());
        } finally {
            SaTokenContextMockUtil.clearContext();
        }
    }
}
