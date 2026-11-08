package @@PACKAGE@@.interfaces.http.order;

import org.domainforge.files.api.StoredFile;

import java.time.Instant;

/**
 * 凭证信息；不含存储位置。
 */
public record OrderFileResponse(String id, String name, String contentType, long size, String sha256,
                               Instant uploadedAt) {
    static OrderFileResponse of(StoredFile file) {
        return new OrderFileResponse(file.id(), file.name(), file.contentType(), file.size(), file.sha256(),
                file.uploadedAt());
    }
}
