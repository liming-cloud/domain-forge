package org.domainforge.files.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.scheduling.api.ScheduledTask;
import org.domainforge.scheduling.api.TaskRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件清理任务（由 PowerJob 调度，可在控制台修改周期、暂停与手动触发）：
 * 删除标记早于保留期的文件对象与记录；删除近几天目录中超过保留期仍没有记录的孤儿对象
 * （上传事务提交前中断、回滚后删除失败、从备份恢复后多出的文件）。
 *
 * <p>手动触发时参数 {@code days=N} 指定检查最近多少天的目录（默认 3），用于恢复较早的备份之后。每一步都可安全重复。
 */
public final class FileCleanup implements ScheduledTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileCleanup.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd/");
    private static final int BATCH = 500;
    private static final int DEFAULT_DAYS = 3;
    private final FileMapper files;
    private final ObjectStorage storage;
    private final Duration grace;
    private final MeterRegistry metrics;
    private final Clock clock;

    FileCleanup(FileMapper files, ObjectStorage storage, Duration grace, MeterRegistry metrics, Clock clock) {
        this.files = files;
        this.storage = storage;
        this.grace = grace;
        this.metrics = metrics;
        this.clock = clock;
    }

    static int days(String parameters) {
        String value = parameters == null ? "" : parameters.strip();
        if (value.isEmpty()) return DEFAULT_DAYS;
        if (!value.startsWith("days=")) throw new IllegalArgumentException("参数格式为 days=N");
        int days = Integer.parseInt(value.substring("days=".length()).strip());
        if (days < 1 || days > 3660) throw new IllegalArgumentException("days 取值 1—3660");
        return days;
    }

    @Override
    public String name() {
        return "file-storage-cleanup";
    }

    /**
     * 初始周期：每小时一次（首次登记后以控制台中的周期为准）。
     */
    @Override
    public String cron() {
        return "0 17 * * * ?";
    }

    @Override
    public String description() {
        return "删除超过保留期的已删除文件与没有记录的孤儿对象；手动触发可带参数 days=N";
    }

    @Override
    public void run(TaskRun run) {
        int purged = purgeDeleted();
        int orphans = sweepOrphans(days(run.parameters()));
        LOGGER.info("file_cleanup purged={} orphans={}", purged, orphans);
    }

    /**
     * 删除标记早于保留期的文件：先删对象再删记录，中途失败下次重做。
     */
    int purgeDeleted() {
        int total = 0;
        List<FileRow> batch;
        do {
            batch = files.expired(grace.toSeconds(), BATCH);
            for (FileRow row : batch) {
                storage.delete(row.storageKey());
                files.purge(row.id());
            }
            total += batch.size();
        } while (batch.size() == BATCH);
        metrics.counter("domainforge.files.purged").increment(total);
        return total;
    }

    /**
     * 删除最近 {@code days} 天（含今天）目录中、早于保留期且没有记录的对象。
     */
    int sweepOrphans(int days) {
        Instant cutoff = clock.instant().minus(grace);
        int total = 0;
        for (int back = 0; back < days; back++) {
            String day = DAY.format(clock.instant().minus(Duration.ofDays(back)).atZone(ZoneOffset.UTC));
            List<ObjectStorage.StoredObject> old = storage.list(day).stream()
                    .filter(object -> object.lastModified().isBefore(cutoff)).toList();
            for (int from = 0; from < old.size(); from += BATCH)
                total += sweep(old.subList(from, Math.min(old.size(), from + BATCH)));
        }
        metrics.counter("domainforge.files.orphans").increment(total);
        return total;
    }

    private int sweep(List<ObjectStorage.StoredObject> objects) {
        Set<String> known = new HashSet<>(files.knownKeys(objects.stream().map(ObjectStorage.StoredObject::key)
                .toList()));
        int removed = 0;
        for (ObjectStorage.StoredObject object : objects) {
            if (known.contains(object.key())) continue;
            storage.delete(object.key());
            removed++;
        }
        return removed;
    }
}
