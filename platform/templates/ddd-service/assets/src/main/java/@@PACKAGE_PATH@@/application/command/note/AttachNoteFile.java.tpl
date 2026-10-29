package @@PACKAGE@@.application.command.note;

import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.domain.model.note.Note;
import @@PACKAGE@@.domain.repository.NoteFileRepository;
import @@PACKAGE@@.domain.repository.NoteRepository;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.FileUpload;
import org.domainforge.files.api.StoredFile;
import org.domainforge.files.api.UploadPolicy;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

/**
 * 便笺附件命令：先按写范围核对便笺，再经文件存储组件保存或删除文件，文件信息与附件关联在同一事务中提交。
 *
 * <p>上传内容在进入用例前已由 Servlet 容器完整接收；保存文件发生在事务内。时限依次为：S3 调用 10 秒 &lt;
 * 本事务 12 秒 &lt; 网关下游响应 15 秒，保证超时由服务先判定并回滚，而不是网关返回 504 后服务仍然提交。
 */
@Service
public class AttachNoteFile {
    /**
     * 附件允许的大小与类型。
     */
    public static final UploadPolicy POLICY = UploadPolicy.of(10L * 1024 * 1024,
            "text/plain", "application/pdf", "image/png", "image/jpeg");
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final NoteRepository notes;
    private final NoteFileRepository attachments;
    private final FileStore files;

    /**
     * 只依赖访问组件、文件存储组件的公开接口、本服务端口与仓储。
     */
    public AttachNoteFile(CurrentAccess access, DataScopes scopes, NoteRepository notes,
                          NoteFileRepository attachments, FileStore files) {
        this.access = access;
        this.scopes = scopes;
        this.notes = notes;
        this.attachments = attachments;
        this.files = files;
    }

    /**
     * 添加附件：范围外的便笺按不存在处理；附件数达到上限为业务拒绝；文件不符合策略由组件拒绝（{@code FILE_*}）。
     */
    @Transactional(timeout = 12)
    public StoredFile attach(long noteId, String name, String contentType, InputStream content) {
        long account = access.current().accountId();
        Note note = writable(account, noteId);
        if (!Note.attachable(attachments.count(note.id())))
            throw new NoteRejected("NOTE_FILES_FULL", "每条便笺最多 " + Note.MAX_FILES + " 个附件");
        StoredFile stored = files.store(new FileUpload(name, contentType, content, account), POLICY);
        attachments.add(note.id(), stored.id());
        return stored;
    }

    /**
     * 删除附件：解除关联并删除文件；文件不是该便笺的附件时按不存在处理。
     */
    @Transactional
    public void detach(long noteId, String fileId) {
        Note note = writable(access.current().accountId(), noteId);
        if (!attachments.remove(note.id(), fileId))
            throw new NoteRejected("NOTE_FILE_NOT_FOUND", "附件不存在");
        files.delete(fileId);
    }

    private Note writable(long account, long noteId) {
        CurrentScope scope = scopes.scope(NotePermissions.WRITE);
        Note note = notes.lock(noteId);
        if (note == null || !note.within(account, scope.all(), scope.self(), scope.unitIds()))
            throw new NoteRejected("NOTE_NOT_FOUND", "便笺不存在");
        return note;
    }
}
