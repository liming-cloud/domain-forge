package @@PACKAGE@@.application.query.note;

import @@PACKAGE@@.application.command.note.NotePermissions;
import @@PACKAGE@@.application.command.note.NoteRejected;
import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.domain.model.note.Note;
import @@PACKAGE@@.domain.repository.NoteFileRepository;
import @@PACKAGE@@.domain.repository.NoteRepository;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.files.api.FileContent;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.StoredFile;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 读取附件的查询用例：需要加载便笺后按读范围作资源授权，因此不走纯查询短路径。范围外的便笺按不存在处理。
 */
@Service
public class ReadNoteFiles {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final NoteRepository notes;
    private final NoteFileRepository attachments;
    private final FileStore files;

    /**
     * 只依赖访问组件、文件存储组件的公开接口、本服务端口与仓储。
     */
    public ReadNoteFiles(CurrentAccess access, DataScopes scopes, NoteRepository notes,
                         NoteFileRepository attachments, FileStore files) {
        this.access = access;
        this.scopes = scopes;
        this.notes = notes;
        this.attachments = attachments;
        this.files = files;
    }

    /**
     * 便笺的附件信息。
     */
    public List<StoredFile> list(long noteId) {
        Note note = readable(noteId);
        return attachments.list(note.id()).stream().map(files::find).flatMap(Optional::stream).toList();
    }

    /**
     * 打开附件内容；调用方负责关闭。
     */
    public FileContent open(long noteId, String fileId) {
        Note note = readable(noteId);
        if (!attachments.contains(note.id(), fileId))
            throw new NoteRejected("NOTE_FILE_NOT_FOUND", "附件不存在");
        return files.open(fileId).orElseThrow(() -> new NoteRejected("NOTE_FILE_NOT_FOUND", "附件不存在"));
    }

    private Note readable(long noteId) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(NotePermissions.READ);
        Note note = notes.find(noteId);
        if (note == null || !note.within(account, scope.all(), scope.self(), scope.unitIds()))
            throw new NoteRejected("NOTE_NOT_FOUND", "便笺不存在");
        return note;
    }
}
