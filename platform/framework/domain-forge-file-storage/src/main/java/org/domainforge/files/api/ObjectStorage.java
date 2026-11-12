package org.domainforge.files.api;

import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 对象存储的最小操作集，也是存储后端的提供方接口：默认提供本地目录与 S3 兼容服务两个实现；业务提供自己的 {@code ObjectStorage} Bean 即可接入其他后端，
 * 默认实现随之让位。键由组件生成（{@code yyyy/MM/dd/<uuid>}），不含客户端输入。
 *
 * <p>失败统一抛出 {@link FileStorageUnavailableException}。
 */
public interface ObjectStorage {
    /**
     * 启动检查：本地目录可写或桶可访问，否则启动失败。
     */
    void check();

    /**
     * 写入完整对象（来源是已校验的暂存文件）；同一键重复写入覆盖。
     */
    void write(String key, Path source, long size);

    /**
     * 打开对象；不存在时返回 null。
     */
    @Nullable
    InputStream read(String key);

    /**
     * 删除对象；不存在不算失败。
     */
    void delete(String key);

    /**
     * 列出某一天目录（{@code yyyy/MM/dd/}）下的对象。
     */
    List<StoredObject> list(String dayPrefix);

    /**
     * 存储中的一个对象。
     *
     * @param key          组件的键（不含 S3 前缀）
     * @param lastModified 最后修改时间
     */
    record StoredObject(String key, Instant lastModified) {
    }
}
