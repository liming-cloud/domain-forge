package org.domainforge.files.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载一律为附件，禁止嗅探、缓存与在本站执行；非 ASCII 文件名按 RFC 5987 编码。
 */
class FileResponsesTest {
    @Test
    void downloadsAreInertAttachments() {
        var file = new StoredFile("id", "季度 报告.html", "text/html", 3, "x", null, Instant.EPOCH);
        HttpHeaders headers = FileResponses.attachment(
                new FileContent(file, new ByteArrayInputStream(new byte[3]))).getHeaders();
        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment;"), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''%E5%AD%A3%E5%BA%A6%20%E6%8A%A5%E5%91%8A.html"), disposition);
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("default-src 'none'; sandbox", headers.getFirst("Content-Security-Policy"));
        assertEquals("private, no-store", headers.getCacheControl());
        assertEquals(3, headers.getContentLength());
        assertEquals("text/html", String.valueOf(headers.getContentType()));
    }

    @Test
    void malformedStoredTypeFallsBackToOctetStream() {
        var file = new StoredFile("id", "a", "not a type", 1, "x", null, Instant.EPOCH);
        assertEquals("application/octet-stream", String.valueOf(FileResponses.attachment(
                new FileContent(file, new ByteArrayInputStream(new byte[1]))).getHeaders().getContentType()));
    }
}
