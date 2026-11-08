package org.domainforge.iam.domain.model.organization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 组织树规则：根受保护、移动不成环。
 */
class OrganizationUnitTest {
    private static final OrganizationUnit ROOT = new OrganizationUnit(1, null, "根", true, 1);

    @Test
    void rootCannotMoveDisableOrDelete() {
        assertRule("ORG_ROOT_DISABLE", () -> ROOT.changeEnabled(false));
        assertRule("ORG_ROOT_MOVE", () -> ROOT.moveTo(5, List.of(5L, 1L)));
        assertRule("ORG_ROOT_DELETE", ROOT::requireDeletable);
        assertRule("ORG_STATE_INVALID", () -> new OrganizationUnit(2, null, "x", true, 1));
        assertRule("ORG_STATE_INVALID", () -> new OrganizationUnit(1, 3L, "x", true, 1));
    }

    @Test
    void moveRejectsSelfAndDescendants() {
        OrganizationUnit unit = OrganizationUnit.create(10, 1, "华东");
        assertRule("ORG_MOVE_CYCLE", () -> unit.moveTo(10, List.of(10L, 1L)));
        assertRule("ORG_MOVE_CYCLE", () -> unit.moveTo(11, List.of(11L, 10L, 1L)));
        OrganizationUnit moved = unit.moveTo(12, List.of(12L, 1L));
        assertEquals(12L, moved.parentId());
        assertEquals(2, moved.version());
        assertSame(moved, moved.moveTo(12, List.of(12L, 1L)));
    }

    @Test
    void nameIsBounded() {
        assertRule("NAME_INVALID", () -> OrganizationUnit.create(10, 1, " "));
        assertRule("NAME_INVALID", () -> OrganizationUnit.create(10, 1, "x".repeat(65)));
    }
}
