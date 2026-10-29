package @@PACKAGE@@.application.port.archive;

import java.time.Instant;

/**
 * 归档库中的便笺快照。
 */
public record ArchivedNote(long noteId, long ownerId, Long unitId, String title, String content, long version,
                           Instant archivedAt) {
}
