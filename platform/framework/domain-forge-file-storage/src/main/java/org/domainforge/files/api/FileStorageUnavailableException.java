package org.domainforge.files.api;

import org.domainforge.shared.DependencyUnavailableException;

import java.io.Serial;

/**
 * 存储不可用（S3 无法连接或返回服务端错误、本地磁盘读写失败）；组件把它映射为 HTTP 503。
 */
public final class FileStorageUnavailableException extends DependencyUnavailableException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 原因只写入日志，不返回给客户端。
     */
    public FileStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
