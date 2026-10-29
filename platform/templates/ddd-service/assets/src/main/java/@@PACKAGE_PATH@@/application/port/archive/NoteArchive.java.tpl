package @@PACKAGE@@.application.port.archive;

import java.util.Optional;

/**
 * 便笺归档库（独立数据库）。写入在归档库自己的事务中完成，与主库没有原子性。
 */
public interface NoteArchive {
    /**
     * 保存快照；同一便笺再次归档时覆盖为新的版本。
     */
    void save(ArchivedNote note);

    /**
     * 读取快照。
     */
    Optional<ArchivedNote> find(long noteId);
}
