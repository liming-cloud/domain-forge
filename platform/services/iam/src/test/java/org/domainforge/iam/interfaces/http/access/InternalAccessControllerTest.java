package org.domainforge.iam.interfaces.http.access;

import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.query.access.AccessScope;
import org.domainforge.shared.CurrentScope;
import org.domainforge.shared.CurrentSubject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * snapshot 合并 current 与 scopes：主体来自本请求的访问组件，范围按同一会话摘要查询。
 */
class InternalAccessControllerTest {
    private static final CurrentSubject SUBJECT = new CurrentSubject(7, Set.of("order:order:write"), Set.of());

    @Test
    void snapshotCombinesSubjectAndScopes() {
        var identity = new CurrentIdentityAccess() {
            @Override
            public CurrentIdentity current() {
                return new CurrentIdentity(7, "digest-7", 1, Set.of("order:order:write"), Set.of());
            }

            @Override
            public String token() {
                return "token-7";
            }
        };
        var controller = new InternalAccessController(() -> SUBJECT, identity, (digest, permissions) -> {
            assertEquals("digest-7", digest);
            assertEquals(List.of("order:order:write"), permissions);
            return Map.of("order:order:write", new AccessScope(false, true, Set.of(10L)));
        });

        var snapshot = controller.snapshot(List.of("order:order:write"));

        assertEquals(SUBJECT, snapshot.subject());
        assertEquals(Map.of("order:order:write", new CurrentScope(false, true, Set.of(10L))), snapshot.scopes());
    }
}
