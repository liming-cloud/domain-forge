package org.domainforge.iam.domain.model.organization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 组织树规则：根受保护、移动不成环。
 */
class OrganizationUnitTest {
    private static final OrganizationUnit ROOT = new OrganizationUnit(1, null, "根", true, 1);

    @Test
    void rootCannotMoveDisableOrDelete() {
        assertThrows(IllegalStateException.class, () -> ROOT.changeEnabled(false));
        assertThrows(IllegalStateException.class, () -> ROOT.moveTo(5, List.of(5L, 1L)));
        assertThrows(IllegalStateException.class, ROOT::requireDeletable);
        assertThrows(IllegalArgumentException.class, () -> new OrganizationUnit(2, null, "x", true, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationUnit(1, 3L, "x", true, 1));
    }

    @Test
    void moveRejectsSelfAndDescendants() {
        OrganizationUnit unit = OrganizationUnit.create(10, 1, "华东");
        assertThrows(IllegalStateException.class, () -> unit.moveTo(10, List.of(10L, 1L)));
        assertThrows(IllegalStateException.class, () -> unit.moveTo(11, List.of(11L, 10L, 1L)));
        OrganizationUnit moved = unit.moveTo(12, List.of(12L, 1L));
        assertEquals(12L, moved.parentId());
        assertEquals(2, moved.version());
        assertSame(moved, moved.moveTo(12, List.of(12L, 1L)));
    }

    @Test
    void nameIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> OrganizationUnit.create(10, 1, " "));
        assertThrows(IllegalArgumentException.class, () -> OrganizationUnit.create(10, 1, "x".repeat(65)));
    }
}
