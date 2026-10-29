package org.domainforge.files.internal;

import org.domainforge.files.api.FileRejected;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 上传内容的暂存文件：边读边计算大小与 SHA-256，超过上限立即停止读取；写入存储前内容已完整校验。
 *
 * @param path   暂存文件，用完由调用方删除
 * @param size   字节数
 * @param sha256 十六进制小写摘要
 */
record Spool(Path path, long size, String sha256) {
    private static final int BUFFER = 64 * 1024;

    /**
     * 读完输入流；超过 {@code maxBytes} 抛出 {@link FileRejected}，空内容同样拒绝。
     */
    static Spool of(InputStream content, long maxBytes, Path directory) {
        Path path = null;
        try {
            Files.createDirectories(directory);
            path = Files.createTempFile(directory, "upload-", ".spool");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copy(content, path, digest, maxBytes);
            if (size == 0) throw new FileRejected(FileRejected.Reason.EMPTY, "文件为空");
            return new Spool(path, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException failure) {
            discard(path);
            throw new UncheckedIOException("读取上传内容失败", failure);
        } catch (NoSuchAlgorithmException impossible) {
            discard(path);
            throw new IllegalStateException(impossible);
        } catch (RuntimeException rejected) {
            discard(path);
            throw rejected;
        }
    }

    private static long copy(InputStream content, Path path, MessageDigest digest, long maxBytes)
            throws IOException {
        long size = 0;
        byte[] buffer = new byte[BUFFER];
        try (OutputStream out = Files.newOutputStream(path)) {
            for (int read = content.read(buffer); read >= 0; read = content.read(buffer)) {
                size += read;
                if (size > maxBytes)
                    throw new FileRejected(FileRejected.Reason.TOO_LARGE, "文件超过大小上限 " + maxBytes + " 字节");
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return size;
    }

    /**
     * 删除暂存文件；失败只留下临时文件。
     */
    void discard() {
        discard(path);
    }

    private static void discard(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时目录中的残留不影响正确性
        }
    }
}
