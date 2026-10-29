package @@PACKAGE@@.infrastructure.persistence.note;

import @@PACKAGE@@.domain.model.note.Note;
import @@PACKAGE@@.domain.repository.NoteRepository;
import org.springframework.stereotype.Repository;

/**
 * MyBatis 便笺仓储。
 */
@Repository
public class SqlNotes implements NoteRepository {
    private final NoteMapper mapper;

    /**
     * 绑定便笺 Mapper。
     */
    public SqlNotes(NoteMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Note lock(long id) {
        NoteRow row = mapper.lock(id);
        return row == null ? null : row.toDomain();
    }

    @Override
    public Note find(long id) {
        NoteRow row = mapper.find(id);
        return row == null ? null : row.toDomain();
    }

    @Override
    public void insert(Note note) {
        mapper.insert(NoteRow.from(note));
    }

    @Override
    public boolean update(Note note, long expected) {
        return mapper.update(NoteRow.from(note), expected) == 1;
    }
}
