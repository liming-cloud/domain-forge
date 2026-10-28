package @@PACKAGE@@.application.command.note;

import @@PACKAGE@@.application.contract.event.NoteCreated;
import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.application.port.event.NoteEvents;
import @@PACKAGE@@.application.port.operation.Identifiers;
import @@PACKAGE@@.domain.model.note.Note;
import @@PACKAGE@@.domain.repository.NoteRepository;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 便笺命令：权限码由入口声明检查，数据范围在用例内按目标对象核对，写入在一个本地事务中完成。
 *
 * <p>IAM 范围在事务开始前读取，避免持锁等待远程调用；范围之后的变化只影响之后的新请求。
 */
@Service
public class WriteNote {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final NoteRepository notes;
    private final Identifiers ids;
    private final NoteEvents events;

    /**
     * 只依赖访问组件公开接口、本服务端口与仓储。
     */
    public WriteNote(CurrentAccess access, DataScopes scopes, NoteRepository notes, Identifiers ids,
                     NoteEvents events) {
        this.access = access;
        this.scopes = scopes;
        this.notes = notes;
        this.ids = ids;
        this.events = events;
    }

    /**
     * 新建便笺：不归属组织时需要本人范围，归属组织时该组织须在写范围内。同一事务内发布 {@link NoteCreated}。
     */
    @Transactional
    public NoteChange create(Long unitId, String title, String content) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(NotePermissions.WRITE);
        Note note = rules(() -> Note.create(ids.next(), account, unitId, title, content));
        if (!Note.creatable(unitId, scope.all(), scope.self(), scope.unitIds()))
            throw new AccessException(AccessFailure.FORBIDDEN);
        notes.insert(note);
        events.created(new NoteCreated(note.id(), account));
        return new NoteChange(note.id(), note.version());
    }

    /**
     * 修改便笺：范围外的对象按不存在处理，不泄露其存在；版本不符为业务冲突。
     */
    @Transactional
    public NoteChange edit(long id, long version, String title, String content) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(NotePermissions.WRITE);
        Note current = notes.lock(id);
        if (current == null || !current.within(account, scope.all(), scope.self(), scope.unitIds()))
            throw new NoteRejected("NOTE_NOT_FOUND", "便笺不存在");
        if (current.version() != version) throw new NoteRejected("NOTE_CONFLICT", "便笺已被修改，请刷新后重试");
        Note changed = rules(() -> current.edit(title, content));
        if (changed != current && !notes.update(changed, current.version()))
            throw new NoteRejected("NOTE_CONFLICT", "便笺已被修改，请刷新后重试");
        return new NoteChange(changed.id(), changed.version());
    }

    private static Note rules(Supplier<Note> transition) {
        try {
            return transition.get();
        } catch (IllegalArgumentException rejected) {
            throw new NoteRejected("NOTE_INVALID", rejected.getMessage());
        }
    }
}
