package org.domainforge.files.internal;

import org.apache.ibatis.annotations.Mapper;
import org.jspecify.annotations.Nullable;

/**
 * 文件存储集成测试读文件记录（SQL 在 mapper/files-it/FileFixtureMapper.xml）。与 FileMapper 同包，
 * 由 FileStorageAutoConfiguration 的 Mapper 扫描注册。
 */
@Mapper
public interface FileFixtureMapper {
    @Nullable
    String storageKey(String id);

    int rows(String id);

    long total();
}
