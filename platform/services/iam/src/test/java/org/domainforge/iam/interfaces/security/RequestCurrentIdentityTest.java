package org.domainforge.iam.interfaces.security;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.internal.RequestCurrentSubject;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.domainforge.iam.infrastructure.security.JcaSecretOperations;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestCurrentIdentityTest {
    // 跨请求重新读取当前授权，原请求内换令牌也不能取回先前私有身份。
    private static void assertNewRequestAndChangedToken(
            CurrentAccess access,
            StpLogic logic,
            RequestCurrentIdentity privateAccess,
            IamCurrentSubjectSource source,
            AtomicInteger reads) {
        SaTokenContextMockUtil.clearContext();
        SaTokenContextMockUtil.setMockContext();
        logic.setTokenValue("first-token");
        assertEquals(Set.of(), access.current().permissions());
        assertEquals(2, reads.get());
        logic.setTokenValue("second-token");
        assertThrows(cn.dev33.satoken.exception.NotLoginException.class, privateAccess::current);
        assertEquals(3, reads.get());
        assertEquals(
                AccessFailure.UNAUTHENTICATED,
                assertThrows(AccessException.class, () -> source.privateIdentity("first-token")).failure());
    }

    // 公共和私有身份消费只触发一次完整事实读取；换令牌与新请求均不能复用旧允许。
    @Test
    void publicAndPrivateIdentityShareOnlyTheCurrentTokenRequest() {
        var previous = StpUtil.getStpLogic();
        var logic = new StpLogic("login");
        var reads = new AtomicInteger();
        long account = 42L;
        var secrets = new JcaSecretOperations(new byte[32]);
        var stored =
                new AtomicReference<>(
                        new CurrentIdentity(
                                account, secrets.digest("first-token"), 1, Set.of("iam:account:read"), Set.of()));
        SessionStore sessions =
                new SessionStore() {
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
                        reads.incrementAndGet();
                        CurrentIdentity value = stored.get();
                        return value != null && value.tokenDigest().equals(digest) ? value : null;
                    }
                };
        NativeSessions nativeSessions =
                new NativeSessions() {
                    @Override
                    public String issue(long id) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Long subject(String token) {
                        return account;
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
        var source =
                new IamCurrentSubjectSource(new CurrentAuthorization(nativeSessions, sessions, secrets));
        // 与 access-control 自动装配相同的组装方式
        var subjects = new RequestCurrentSubject(source);
        CurrentAccess access = () -> subjects.current(logic.getTokenValue());
        var privateAccess = new RequestCurrentIdentity(access, logic, source);
        try {
            SaTokenContextMockUtil.setMockContext();
            logic.setTokenValue("first-token");
            assertEquals(account, access.current().accountId());
            assertEquals(stored.get(), privateAccess.current());
            assertEquals("first-token", privateAccess.token());
            assertEquals(1, reads.get());
            assertFalse(access.current().toString().contains(secrets.digest("first-token")));
            stored.set(new CurrentIdentity(account, secrets.digest("first-token"), 1, Set.of(), Set.of()));
            assertEquals(Set.of("iam:account:read"), access.current().permissions());
            assertNewRequestAndChangedToken(access, logic, privateAccess, source, reads);
        } finally {
            SaTokenContextMockUtil.clearContext();
            StpUtil.setStpLogic(previous);
        }
    }
}
