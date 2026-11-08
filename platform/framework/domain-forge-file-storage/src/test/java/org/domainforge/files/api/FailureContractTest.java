package org.domainforge.files.api;

import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.domainforge.shared.DependencyUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 上传拒绝带稳定错误码和类别，存储不可用是依赖不可用。
 */
class FailureContractTest {
    @Test
    void rejectionsCarryKindAndCode() {
        var tooLarge = new FileRejected(FileRejected.Reason.TOO_LARGE, "文件过大");
        assertEquals(Kind.PAYLOAD_TOO_LARGE, tooLarge.kind());
        assertEquals("FILE_TOO_LARGE", tooLarge.code());
        var type = new FileRejected(FileRejected.Reason.TYPE_NOT_ALLOWED, "类型不允许");
        assertEquals(Kind.UNPROCESSABLE, type.kind());
        assertEquals("FILE_TYPE_NOT_ALLOWED", type.code());
    }

    @Test
    void storageFailureIsDependencyUnavailable() {
        assertInstanceOf(DependencyUnavailableException.class, new FileStorageUnavailableException("不可用", null));
    }
}
