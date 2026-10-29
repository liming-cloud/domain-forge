package @@PACKAGE@@.infrastructure.persistence.note;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 便笺附件关联 SQL，写在 mapper/note/NoteFileMapper.xml。
 */
@org.apache.ibatis.annotations.Mapper
public interface NoteFileMapper {
    int count(long noteId);

    int insert(@Param("noteId") long noteId, @Param("fileId") String fileId);

    int delete(@Param("noteId") long noteId, @Param("fileId") String fileId);

    int exists(@Param("noteId") long noteId, @Param("fileId") String fileId);

    List<String> list(long noteId);
}
