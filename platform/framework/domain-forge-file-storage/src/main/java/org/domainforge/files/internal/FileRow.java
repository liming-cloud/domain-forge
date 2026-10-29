package org.domainforge.files.internal;

import org.domainforge.files.api.StoredFile;

import java.time.OffsetDateTime;

/**
 * {@code domainforge_file} 的一行。
 */
public record FileRow(String id, String storageKey, String name, String contentType, long size, String sha256,
               Long uploadedBy, OffsetDateTime uploadedAt) {
    /**
     * 公开的文件信息，不含存储键。
     */
    public StoredFile toFile() {
        return new StoredFile(id, name, contentType, size, sha256, uploadedBy, uploadedAt.toInstant());
    }
}
