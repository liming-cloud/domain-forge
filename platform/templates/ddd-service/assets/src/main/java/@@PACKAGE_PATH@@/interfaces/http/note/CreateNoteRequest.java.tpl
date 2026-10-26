package @@PACKAGE@@.interfaces.http.note;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 新建便笺；unitId 为空表示不归属组织。
 */
public record CreateNoteRequest(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long unitId, String title, String content) {
}
