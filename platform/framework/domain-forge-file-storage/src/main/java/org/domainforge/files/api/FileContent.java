package org.domainforge.files.api;

import java.io.IOException;
import java.io.InputStream;

/**
 * 打开的文件：信息与内容流，用完必须关闭。
 *
 * @param file    文件信息
 * @param content 内容流
 */
public record FileContent(StoredFile file, InputStream content) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        content.close();
    }
}
