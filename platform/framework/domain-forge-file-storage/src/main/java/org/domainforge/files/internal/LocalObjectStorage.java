package org.domainforge.files.internal;

import org.domainforge.files.api.FileStorageUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 本地目录存储：先写入根目录下的 {@code .incoming}、落盘后原子改名到目标位置，读者不会看到写了一半的文件。
 */
final class LocalObjectStorage implements ObjectStorage {
    private final Path root;
    private final Path incoming;

    LocalObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.incoming = this.root.resolve(".incoming");
    }

    private static FileStorageUnavailableException failed(String action, IOException cause) {
        return new FileStorageUnavailableException("本地文件存储" + action + "失败", cause);
    }

    @Override
    public void check() {
        try {
            Files.createDirectories(incoming);
            Path probe = Files.createTempFile(incoming, "probe-", ".tmp");
            Files.delete(probe);
        } catch (IOException unusable) {
            throw new IllegalStateException("本地文件存储目录不可写：" + root, unusable);
        }
    }

    @Override
    public void write(String key, Path source, long size) {
        Path target = path(key);
        Path partial = incoming.resolve(UUID.randomUUID() + ".partial");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, partial);
            try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw failed("写入", failure);
        } finally {
            deleteQuietly(partial);
        }
    }

    @Override
    public InputStream read(String key) {
        try {
            return Files.newInputStream(path(key));
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException failure) {
            throw failed("读取", failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException failure) {
            throw failed("删除", failure);
        }
    }

    @Override
    public List<StoredObject> list(String dayPrefix) {
        Path day = path(dayPrefix);
        if (!Files.isDirectory(day)) return List.of();
        try (Stream<Path> files = Files.list(day)) {
            return files.filter(Files::isRegularFile).map(file -> object(dayPrefix, file)).toList();
        } catch (IOException failure) {
            throw failed("列举", failure);
        }
    }

    private StoredObject object(String dayPrefix, Path file) {
        try {
            return new StoredObject(dayPrefix + file.getFileName(), Files.getLastModifiedTime(file).toInstant());
        } catch (IOException failure) {
            throw failed("列举", failure);
        }
    }

    private Path path(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.startsWith(incoming))
            throw new IllegalArgumentException("非法的存储键");
        return resolved;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 残留的 .partial 不会被读到，只占空间
        }
    }
}
