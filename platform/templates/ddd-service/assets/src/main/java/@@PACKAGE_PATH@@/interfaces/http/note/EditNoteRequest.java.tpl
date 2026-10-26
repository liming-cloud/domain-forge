package @@PACKAGE@@.interfaces.http.note;

/**
 * 修改便笺，须携带读取时的版本。
 */
public record EditNoteRequest(long expectedVersion, String title, String content) {
}
