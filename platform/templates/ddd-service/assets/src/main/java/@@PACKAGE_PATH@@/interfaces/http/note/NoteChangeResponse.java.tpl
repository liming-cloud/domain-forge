package @@PACKAGE@@.interfaces.http.note;

import @@PACKAGE@@.application.command.note.NoteChange;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 命令完成后的 ID 与新版本。
 */
public record NoteChangeResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) long id, long version) {
    static NoteChangeResponse of(NoteChange change) {
        return new NoteChangeResponse(change.id(), change.version());
    }
}
