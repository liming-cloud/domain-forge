package @@PACKAGE@@.interfaces.http.note;

import org.domainforge.files.api.StoredFile;

import java.time.Instant;

/**
 * 附件信息；不含存储位置。
 */
public record NoteFileResponse(String id, String name, String contentType, long size, String sha256,
                               Instant uploadedAt) {
    static NoteFileResponse of(StoredFile file) {
        return new NoteFileResponse(file.id(), file.name(), file.contentType(), file.size(), file.sha256(),
                file.uploadedAt());
    }
}
