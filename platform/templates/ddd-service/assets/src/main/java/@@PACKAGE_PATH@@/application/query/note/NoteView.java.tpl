package @@PACKAGE@@.application.query.note;

/**
 * 便笺列表投影。
 */
public record NoteView(long id, long ownerId, Long unitId, String title, long version) {
}
