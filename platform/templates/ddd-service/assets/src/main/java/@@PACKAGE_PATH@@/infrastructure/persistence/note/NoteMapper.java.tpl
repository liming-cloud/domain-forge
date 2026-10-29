package @@PACKAGE@@.infrastructure.persistence.note;

import org.apache.ibatis.annotations.Param;

/**
 * 便笺持久化 SQL，写在 mapper/note/NoteMapper.xml。
 */
@org.apache.ibatis.annotations.Mapper
public interface NoteMapper {
    NoteRow lock(long id);

    NoteRow find(long id);

    int insert(NoteRow row);

    int update(@Param("row") NoteRow row, @Param("expected") long expected);
}
