package org.domainforge.files.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 文件信息的读写；SQL 在 mapper/domain-forge/files/FileMapper.xml。
 */
@Mapper
public interface FileMapper {
    /**
     * 写入文件信息。
     */
    int insert(@Param("row") FileRow row);

    /**
     * 未删除的文件信息；不存在或已删除时为 null。
     */
    FileRow find(@Param("id") String id);

    /**
     * 标记删除，返回影响行数（已删除的不再计入）。
     */
    int markDeleted(@Param("id") String id);

    /**
     * 删除标记早于保留期的文件（最多 limit 条），返回其 ID 与存储键。
     */
    List<FileRow> expired(@Param("graceSeconds") long graceSeconds, @Param("limit") int limit);

    /**
     * 删除一条已标记删除的记录。
     */
    int purge(@Param("id") String id);

    /**
     * 给定存储键中有记录（含已标记删除）的那些。
     */
    List<String> knownKeys(@Param("keys") Collection<String> keys);
}
