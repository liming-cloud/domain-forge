package org.domainforge.iam.infrastructure.persistence.retention;

import org.domainforge.scheduling.api.ScheduledTask;
import org.domainforge.scheduling.api.TaskRun;
import org.springframework.stereotype.Component;

/**
 * 定时清理自然过期的会话许可、邀请与超过 90 天的审计。由 PowerJob 调度（可在控制台修改周期、暂停、手动触发）；
 * 每次只删除有限批次，多实例或手动触发与周期触发重叠时由调度保证不并发，数据库删除本身也可安全重复。
 */
@Component
public class IamRetentionTask implements ScheduledTask {
    private final RetentionCleanup cleanup;

    /**
     * 只依赖清理用例。
     */
    public IamRetentionTask(RetentionCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Override
    public String name() {
        return "iam-retention";
    }

    /**
     * 初始周期：每分钟一次（首次登记后以控制台中的周期为准）。
     */
    @Override
    public String cron() {
        return "0 * * * * ?";
    }

    @Override
    public String description() {
        return "清理过期的会话许可、邀请与超过保留期的审计（每次有限批次）";
    }

    @Override
    public void run(TaskRun run) {
        cleanup.run();
    }
}
