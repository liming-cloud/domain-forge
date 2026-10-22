package org.domainforge.iam.infrastructure.persistence.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只在服务进程运行清理，显式初始化命令不启动后台循环。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IamRetentionSchedule {
  private final RetentionCleanup cleanup;

  /** 单实例顺序执行；多实例由数据库SKIP LOCKED协调。 */
  public IamRetentionSchedule(RetentionCleanup cleanup) {
    this.cleanup = cleanup;
  }

  /** 固定一分钟间隔且不补跑积压任务。 */
  @Scheduled(initialDelay = 60000, fixedDelay = 60000)
  public void clean() {
    cleanup.run();
  }
}
