package @@PACKAGE@@.domain.repository;

import @@PACKAGE@@.domain.model.note.Note;

/**
 * 便笺聚合仓储；不存在返回 null，版本冲突不能当作成功。
 */
public interface NoteRepository {
    /**
     * 锁定便笺直到事务结束。
     */
    Note lock(long id);

    void insert(Note note);

    /**
     * 按原版本保存，零行返回 false。
     */
    boolean update(Note note, long expectedVersion);
}
