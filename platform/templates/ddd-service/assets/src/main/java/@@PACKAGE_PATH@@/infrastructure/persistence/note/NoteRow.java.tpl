package @@PACKAGE@@.infrastructure.persistence.note;

import @@PACKAGE@@.domain.model.note.Note;

/**
 * 便笺表投影。
 */
public record NoteRow(long id, long ownerId, Long unitId, String title, String content, long version) {
    static NoteRow from(Note note) {
        return new NoteRow(note.id(), note.ownerId(), note.unitId(), note.title(), note.content(), note.version());
    }

    Note toDomain() {
        return new Note(id, ownerId, unitId, title, content, version);
    }
}
