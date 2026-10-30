package @@PACKAGE@@.infrastructure.archive;

import @@PACKAGE@@.application.port.archive.ArchivedNote;
import @@PACKAGE@@.application.port.archive.NoteArchive;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * MyBatis 归档库实现：写入在归档库自己的事务中。
 */
@Repository
public class SqlNoteArchive implements NoteArchive {
    private final NoteArchiveMapper mapper;

    /**
     * 绑定归档库 Mapper。
     */
    public SqlNoteArchive(NoteArchiveMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional("archiveTransactionManager")
    public void save(ArchivedNote note) {
        mapper.upsert(note);
    }

    @Override
    public Optional<ArchivedNote> find(long noteId) {
        return Optional.ofNullable(mapper.find(noteId));
    }
}
