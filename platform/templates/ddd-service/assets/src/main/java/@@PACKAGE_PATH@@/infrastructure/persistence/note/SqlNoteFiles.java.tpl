package @@PACKAGE@@.infrastructure.persistence.note;

import @@PACKAGE@@.domain.repository.NoteFileRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis 便笺附件关联。
 */
@Repository
public class SqlNoteFiles implements NoteFileRepository {
    private final NoteFileMapper mapper;

    /**
     * 绑定附件关联 Mapper。
     */
    public SqlNoteFiles(NoteFileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int count(long noteId) {
        return mapper.count(noteId);
    }

    @Override
    public void add(long noteId, String fileId) {
        mapper.insert(noteId, fileId);
    }

    @Override
    public boolean remove(long noteId, String fileId) {
        return mapper.delete(noteId, fileId) == 1;
    }

    @Override
    public boolean contains(long noteId, String fileId) {
        return mapper.exists(noteId, fileId) > 0;
    }

    @Override
    public List<String> list(long noteId) {
        return mapper.list(noteId);
    }
}
