package org.domainforge.files.internal;

import org.domainforge.files.api.FileRejected;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;

/**
 * 把上传策略拒绝映射为业务拒绝：HTTP 200 与 {@code FILE_*} 稳定代码。
 */
public final class FileRejectionMapping implements BusinessRejectionMapping<FileRejected> {
    @Override
    public Class<FileRejected> exceptionType() {
        return FileRejected.class;
    }

    @Override
    public ApiResponse<Void> map(FileRejected exception) {
        return ApiResponse.rejected(exception.reason().code(), exception.getMessage());
    }
}
