package org.domainforge.files.internal;

import org.domainforge.files.api.FileStorageUnavailableException;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.HttpFailureResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 只处理文件存取相关的技术失败：存储不可用 503，请求体超过 multipart 上限 413，multipart 格式错误 400。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class FileFailureAdvice {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileFailureAdvice.class);

    /**
     * 存储不可用属于依赖故障，原因只写日志。
     */
    @ExceptionHandler(FileStorageUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(FileStorageUnavailableException failure) {
        LOGGER.error("file_storage_unavailable", failure);
        return HttpFailureResponses.unavailable();
    }

    /**
     * 超过 {@code spring.servlet.multipart.max-file-size}／{@code max-request-size}。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge() {
        return HttpFailureResponses.payloadTooLarge();
    }

    /**
     * 其他 multipart 解析失败按请求不合法处理。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return HttpFailureResponses.invalidRequest();
    }
}
