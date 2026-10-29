package @@PACKAGE@@.application.query.note;

import @@PACKAGE@@.application.command.note.NotePermissions;
import @@PACKAGE@@.application.command.note.NoteRejected;
import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.application.port.archive.ArchivedNote;
import @@PACKAGE@@.application.port.archive.NoteArchive;
import @@PACKAGE@@.domain.model.note.Note;
import @@PACKAGE@@.domain.repository.NoteRepository;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;

/**
 * 读取归档快照：先按读范围核对主库中的便笺，再读归档库；范围外或尚未归档按不存在处理。
 */
@Service
public class ReadNoteArchive {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final NoteRepository notes;
    private final NoteArchive archive;

    /**
     * 只依赖访问组件公开接口、本服务端口与仓储。
     */
    public ReadNoteArchive(CurrentAccess access, DataScopes scopes, NoteRepository notes, NoteArchive archive) {
        this.access = access;
        this.scopes = scopes;
        this.notes = notes;
        this.archive = archive;
    }

    /**
     * 读范围内便笺的归档快照。
     */
    public ArchivedNote read(long noteId) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(NotePermissions.READ);
        Note note = notes.find(noteId);
        if (note == null || !note.within(account, scope.all(), scope.self(), scope.unitIds()))
            throw new NoteRejected("NOTE_NOT_FOUND", "便笺不存在");
        return archive.find(noteId).orElseThrow(() -> new NoteRejected("NOTE_NOT_ARCHIVED", "便笺尚未归档"));
    }
}
