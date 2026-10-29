package org.domainforge.files.api;

import java.io.InputStream;
import java.util.Objects;

/**
 * 一次上传：客户端给出的文件名与类型只作展示和下载时使用，组件不按它们决定存储位置。
 *
 * @param name        原始文件名；路径部分与控制字符会被去掉
 * @param contentType 客户端声明的类型，可为空（按 application/octet-stream）
 * @param content     内容
 * @param uploadedBy  上传人账号 ID，系统写入时为空
 */
public record FileUpload(String name, String contentType, InputStream content, Long uploadedBy) {
    /**
     * 内容不能为空。
     */
    public FileUpload {
        Objects.requireNonNull(content, "content");
    }
}
