package @@PACKAGE@@.interfaces.http.note;

import @@PACKAGE@@.application.query.note.NoteView;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 便笺的 HTTP 表示；ID 以十进制字符串传输。
 */
public record NoteResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long ownerId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long unitId,
        String title,
        long version) {
    static NoteResponse of(NoteView view) {
        return new NoteResponse(view.id(), view.ownerId(), view.unitId(), view.title(), view.version());
    }
}
