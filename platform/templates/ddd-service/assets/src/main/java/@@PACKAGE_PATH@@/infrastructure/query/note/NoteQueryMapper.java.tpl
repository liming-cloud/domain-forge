package @@PACKAGE@@.infrastructure.query.note;

import @@PACKAGE@@.application.query.note.NoteFilter;
import @@PACKAGE@@.application.query.note.NoteView;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 便笺查询 SQL；范围条件全部参数绑定。
 */
@org.apache.ibatis.annotations.Mapper
public interface NoteQueryMapper {
    List<NoteView> owned(@Param("owner") long owner, @Param("after") long after, @Param("limit") int limit);

    List<NoteView> visible(@Param("filter") NoteFilter filter);
}
