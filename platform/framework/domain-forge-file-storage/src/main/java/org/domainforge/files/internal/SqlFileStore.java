package org.domainforge.files.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.files.api.FileContent;
import org.domainforge.files.api.FileRejected;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.FileUpload;
import org.domainforge.files.api.StoredFile;
import org.domainforge.files.api.UploadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件存取实现：先写对象、再在业务事务中写文件信息；事务回滚时删除刚写入的对象。
 *
 * <p>对象写入后、事务提交前应用中断，会留下没有记录的对象，由清理任务在保留期后删除。
 */
public final class SqlFileStore implements FileStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlFileStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd/");
    private static final int MAX_NAME = 255;
    private final FileMapper files;
    private final ObjectStorage storage;
    private final Settings settings;
    private final MeterRegistry metrics;
    private final Clock clock;

    SqlFileStore(FileMapper files, ObjectStorage storage, Settings settings, MeterRegistry metrics, Clock clock) {
        this.files = files;
        this.storage = storage;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 去掉路径部分与控制字符，截断到 255 个字符；结果为空即拒绝。
     */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.codePoints().filter(point -> !Character.isISOControl(point))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (name.isEmpty() || name.equals(".") || name.equals(".."))
            throw new FileRejected(FileRejected.Reason.NAME_INVALID, "文件名不合法");
        return name.codePointCount(0, name.length()) <= MAX_NAME ? name
                : name.substring(0, name.offsetByCodePoints(0, MAX_NAME));
    }

    /**
     * 去掉参数并统一为小写；未声明时为 application/octet-stream。
     */
    static String cleanType(String raw) {
        if (raw == null || raw.isBlank()) return "application/octet-stream";
        int parameters = raw.indexOf(';');
        return (parameters < 0 ? raw : raw.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("文件写入与删除必须在业务事务内调用");
    }

    @Override
    public StoredFile store(FileUpload upload, UploadPolicy policy) {
        requireTransaction();
        String name = cleanName(upload.name());
        String type = cleanType(upload.contentType());
        if (!policy.contentTypes().contains(type)) {
            metrics.counter("domainforge.files.rejected", "reason", "type").increment();
            throw new FileRejected(FileRejected.Reason.TYPE_NOT_ALLOWED, "不允许的文件类型：" + type);
        }
        Spool spool = spool(upload.content(), Math.min(policy.maxBytes(), settings.maxBytes()));
        try {
            // 截到微秒：PostgreSQL 只保存到微秒，返回值须与之后读到的一致（Linux 时钟精度为纳秒）。
            OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.MICROS);
            String id = UUID.randomUUID().toString();
            String key = DAY.format(now) + id;
            storage.write(key, spool.path(), spool.size());
            TransactionSynchronizationManager.registerSynchronization(new RemoveOnRollback(key));
            FileRow row = new FileRow(id, key, name, type, spool.size(), spool.sha256(), upload.uploadedBy(), now);
            files.insert(row);
            metrics.counter("domainforge.files.stored").increment();
            return row.toFile();
        } finally {
            spool.discard();
        }
    }

    private Spool spool(InputStream content, long maxBytes) {
        try {
            return Spool.of(content, maxBytes, settings.spoolDirectory());
        } catch (FileRejected rejected) {
            metrics.counter("domainforge.files.rejected", "reason", rejected.reason().name().toLowerCase(Locale.ROOT))
                    .increment();
            throw rejected;
        }
    }

    @Override
    public Optional<StoredFile> find(String fileId) {
        return Optional.ofNullable(row(fileId)).map(FileRow::toFile);
    }

    @Override
    public Optional<FileContent> open(String fileId) {
        FileRow row = row(fileId);
        if (row == null) return Optional.empty();
        InputStream content = storage.read(row.storageKey());
        if (content == null) throw new IllegalStateException("文件对象缺失：" + row.id());
        return Optional.of(new FileContent(row.toFile(), content));
    }

    @Override
    public boolean delete(String fileId) {
        requireTransaction();
        return valid(fileId) && files.markDeleted(fileId) == 1;
    }

    private FileRow row(String fileId) {
        return valid(fileId) ? files.find(fileId) : null;
    }

    private static boolean valid(String fileId) {
        if (fileId == null || fileId.length() != 36) return false;
        try {
            return UUID.fromString(fileId).toString().equals(fileId);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * 组件设置。
     *
     * @param maxBytes       服务的全局大小上限
     * @param spoolDirectory 上传暂存目录
     */
    record Settings(long maxBytes, Path spoolDirectory) {
    }

    /**
     * 事务回滚后删除刚写入的对象；删除失败只留下孤儿对象，由清理任务处理。
     */
    private final class RemoveOnRollback implements TransactionSynchronization {
        private final String key;

        RemoveOnRollback(String key) {
            this.key = key;
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) return;
            try {
                storage.delete(key);
            } catch (RuntimeException failure) {
                LOGGER.warn("file_rollback_cleanup_failed key={}", key, failure);
            }
        }
    }
}
