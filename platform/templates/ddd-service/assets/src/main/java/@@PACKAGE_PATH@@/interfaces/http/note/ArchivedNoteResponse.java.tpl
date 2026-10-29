package @@PACKAGE@@.interfaces.http.note;

import @@PACKAGE@@.application.port.archive.ArchivedNote;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * 归档快照。
 */
public record ArchivedNoteResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) long id, String title,
                                   String content, long version, Instant archivedAt) {
    static ArchivedNoteResponse of(ArchivedNote note) {
        return new ArchivedNoteResponse(note.noteId(), note.title(), note.content(), note.version(),
                note.archivedAt());
    }
}
