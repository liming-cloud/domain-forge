package @@PACKAGE@@.domain.model.note;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 便笺不变量与范围规则。
 */
class NoteTest {
    @Test
    void scopeRules() {
        Note note = Note.create(1, 7, 10L, "标题", "内容");
        assertTrue(note.within(9, true, false, Set.of()));
        assertTrue(note.within(7, false, true, Set.of()));
        assertFalse(note.within(9, false, true, Set.of()));
        assertTrue(note.within(9, false, false, Set.of(10L)));
        assertFalse(note.within(9, false, false, Set.of(11L)));
    }

    @Test
    void selfScopeCannotCreateInArbitraryUnit() {
        assertTrue(Note.creatable(null, false, true, Set.of()));
        assertFalse(Note.creatable(10L, false, true, Set.of()));
        assertTrue(Note.creatable(10L, false, false, Set.of(10L)));
        assertFalse(Note.creatable(null, false, false, Set.of(10L)));
        assertTrue(Note.creatable(10L, true, false, Set.of()));
    }

    @Test
    void editBumpsVersionOnlyWhenChanged() {
        Note note = Note.create(1, 7, null, " 标题 ", "内容");
        assertEquals("标题", note.title());
        assertSame(note, note.edit("标题", "内容"));
        assertEquals(2, note.edit("新标题", "内容").version());
        assertThrows(IllegalArgumentException.class, () -> note.edit(" ", "内容"));
        assertThrows(IllegalArgumentException.class, () -> note.edit("标题", "x".repeat(2001)));
    }
}
