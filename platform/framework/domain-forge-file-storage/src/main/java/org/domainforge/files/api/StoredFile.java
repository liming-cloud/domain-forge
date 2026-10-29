package org.domainforge.files.api;

import java.time.Instant;

/**
 * 已保存文件的信息。
 *
 * @param id          文件 ID（UUID），业务保存它来引用文件
 * @param name        清理后的原始文件名
 * @param contentType 不含参数的小写类型
 * @param size        字节数
 * @param sha256      内容摘要（十六进制小写）
 * @param uploadedBy  上传人账号 ID，系统写入时为空
 * @param uploadedAt  上传时间
 */
public record StoredFile(String id, String name, String contentType, long size, String sha256, Long uploadedBy,
                         Instant uploadedAt) {
}
