package org.domainforge.iam.infrastructure.security;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.iam.application.port.IamUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SaNativeSessionsTest {
    private final RuntimeException storeDown = new RuntimeException("redis down");

    private final SaNativeSessions sessions = new SaNativeSessions(new StpLogic("login") {
        @Override
        public String createLoginSession(Object id, SaLoginParameter parameter) {
            throw storeDown;
        }

        @Override
        public Object getLoginIdByToken(String token) {
            throw storeDown;
        }

        @Override
        public void logoutByTokenValue(String token) {
            throw storeDown;
        }
    }, new SimpleMeterRegistry());

    // 会话存储故障为依赖不可用，原始异常作为原因保留给内部诊断。
    @Test
    void storageFailuresKeepCause() {
        assertSame(storeDown, assertThrows(IamUnavailableException.class, () -> sessions.issue(1)).getCause());
        assertSame(storeDown, assertThrows(IamUnavailableException.class, () -> sessions.subject("t")).getCause());
        assertSame(storeDown, assertThrows(IamUnavailableException.class, () -> sessions.revoke("t")).getCause());
    }
}
