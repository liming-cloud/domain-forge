package org.domainforge.shared;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 合同边界：可变集合和无效权限不能扩大已校验的身份。
 */
class CurrentSubjectTest {
    @Test
    void identityIsImmutableAndBounded() {
        Set<String> input = new HashSet<>(Set.of("iam:account:read"));
        Set<String> roles = new HashSet<>(Set.of("manager"));
        var subject = new CurrentSubject(1001L, input, roles);
        input.add("iam:grant:manage");
        roles.add("administrator");
        assertEquals(Set.of("manager"), subject.roles());
        assertEquals(Set.of("iam:account:read"), subject.permissions());
        assertThrows(UnsupportedOperationException.class, () -> subject.roles().clear());
        assertThrows(UnsupportedOperationException.class, () -> subject.permissions().clear());
        assertEquals(Set.of(), new CurrentSubject(1001L, Set.of()).roles());
    }

    @Test
    void rejectsInvalidIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new CurrentSubject(0L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CurrentSubject(1L, Set.of("*")));
        assertThrows(IllegalArgumentException.class, () -> new CurrentSubject(1L, Set.of(), Set.of("*")));
        assertThrows(IllegalArgumentException.class, () -> new CurrentSubject(1L, null));
    }
}
