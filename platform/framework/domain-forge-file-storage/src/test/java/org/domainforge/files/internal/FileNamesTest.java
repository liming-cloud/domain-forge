package org.domainforge.files.internal;

import org.domainforge.files.api.FileRejected;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 客户端给出的文件名与类型只作展示：去掉路径与控制字符、限制长度，类型去掉参数并统一小写。
 */
class FileNamesTest {
    @Test
    void namesLosePathsAndControlCharacters() {
        assertEquals("passwd", SqlFileStore.cleanName("../../etc/passwd"));
        assertEquals("a.txt", SqlFileStore.cleanName("C:\\Users\\x\\a.txt"));
        assertEquals("报告.pdf", SqlFileStore.cleanName(" 报\u0000告.pdf\r\n"));
        assertEquals(255, SqlFileStore.cleanName("名".repeat(300)).length());
        for (String invalid : new String[]{null, "", "  ", "dir/", "..", "a/."})
            assertEquals(FileRejected.Reason.NAME_INVALID,
                    assertThrows(FileRejected.class, () -> SqlFileStore.cleanName(invalid)).reason());
    }

    @Test
    void typesAreNormalized() {
        assertEquals("text/plain", SqlFileStore.cleanType("Text/Plain; charset=UTF-8"));
        assertEquals("application/octet-stream", SqlFileStore.cleanType(null));
        assertEquals("application/octet-stream", SqlFileStore.cleanType(" "));
    }
}
