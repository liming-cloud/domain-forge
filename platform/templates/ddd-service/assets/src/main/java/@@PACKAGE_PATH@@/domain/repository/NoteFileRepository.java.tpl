package @@PACKAGE@@.domain.repository;

import java.util.List;

/**
 * 便笺与附件文件的关联；文件本身由文件存储组件保存，这里只记录文件 ID。
 */
public interface NoteFileRepository {
    /**
     * 便笺当前的附件数。
     */
    int count(long noteId);

    void add(long noteId, String fileId);

    /**
     * 解除关联，零行返回 false。
     */
    boolean remove(long noteId, String fileId);

    /**
     * 文件是否是该便笺的附件。
     */
    boolean contains(long noteId, String fileId);

    /**
     * 便笺的附件文件 ID，按添加顺序。
     */
    List<String> list(long noteId);
}
