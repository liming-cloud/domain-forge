package @@PACKAGE@@.application.command.note;

import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.application.port.archive.ArchivedNote;
import @@PACKAGE@@.application.port.archive.NoteArchive;
import @@PACKAGE@@.domain.model.note.Note;
import @@PACKAGE@@.domain.repository.NoteRepository;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 归档便笺：从主库读出并核对写范围，再把快照写入归档库（第二个数据源）。
 *
 * <p>两个库没有共同的事务：本用例不开启主库事务，归档库的写入在它自己的事务中完成（见 SqlNoteArchive）。
 * 快照按便笺 ID 覆盖保存，重复执行结果相同；归档失败不影响主库。
 */
@Service
public class ArchiveNote {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final NoteRepository notes;
    private final NoteArchive archive;
    private final Clock clock = Clock.systemUTC();

    /**
     * 只依赖访问组件公开接口、本服务端口与仓储。
     */
    public ArchiveNote(CurrentAccess access, DataScopes scopes, NoteRepository notes, NoteArchive archive) {
        this.access = access;
        this.scopes = scopes;
        this.notes = notes;
        this.archive = archive;
    }

    /**
     * 归档写范围内的便笺；范围外按不存在处理。
     */
    public ArchivedNote archive(long noteId) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(NotePermissions.WRITE);
        Note note = notes.find(noteId);
        if (note == null || !note.within(account, scope.all(), scope.self(), scope.unitIds()))
            throw new NoteRejected("NOTE_NOT_FOUND", "便笺不存在");
        var snapshot = new ArchivedNote(note.id(), note.ownerId(), note.unitId(), note.title(), note.content(),
                note.version(), Instant.now(clock));
        archive.save(snapshot);
        return snapshot;
    }
}
