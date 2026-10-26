package @@PACKAGE@@.infrastructure.query.note;

import @@PACKAGE@@.application.command.note.NoteRejected;
import @@PACKAGE@@.application.query.note.NoteFilter;
import @@PACKAGE@@.application.query.note.NoteQueries;
import @@PACKAGE@@.application.query.note.NoteView;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 便笺查询实现。
 */
@Repository
public class SqlNoteQueries implements NoteQueries {
    private final NoteQueryMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlNoteQueries(NoteQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<NoteView> owned(long ownerId, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new NoteRejected("NOTE_INVALID", "分页游标须非负且大小须为1至100");
        return mapper.owned(ownerId, after, limit);
    }

    @Override
    public List<NoteView> visible(NoteFilter filter) {
        return mapper.visible(filter);
    }
}
