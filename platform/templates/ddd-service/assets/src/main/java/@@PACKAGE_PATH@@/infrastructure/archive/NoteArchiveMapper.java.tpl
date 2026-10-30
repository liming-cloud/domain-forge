package @@PACKAGE@@.infrastructure.archive;

import @@PACKAGE@@.application.port.archive.ArchivedNote;
import org.apache.ibatis.annotations.Param;
import org.domainforge.persistence.api.AdditionalMapper;

/**
 * 归档库的 SQL，写在 mapper-archive/NoteArchiveMapper.xml；由归档库的会话工厂扫描。
 */
@AdditionalMapper
public interface NoteArchiveMapper {
    int upsert(@Param("note") ArchivedNote note);

    ArchivedNote find(long noteId);
}
