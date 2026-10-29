package org.domainforge.files.api;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * 下载响应：一律作为附件返回并禁止浏览器嗅探与缓存，避免上传的内容在本站页面中被当作脚本或页面执行。
 */
public final class FileResponses {
    private FileResponses() {
    }

    /**
     * 把打开的文件写成附件响应；内容流在写完后由框架关闭。
     */
    public static ResponseEntity<InputStreamResource> attachment(FileContent opened) {
        StoredFile file = opened.file();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8).build());
        headers.setContentLength(file.size());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(headers).contentType(mediaType(file.contentType()))
                .body(new InputStreamResource(opened.content()));
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException invalid) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
