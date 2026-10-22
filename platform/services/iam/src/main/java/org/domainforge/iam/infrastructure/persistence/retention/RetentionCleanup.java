package org.domainforge.iam.infrastructure.persistence.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立短事务清理自然过期数据；失败保留数据并告警，不影响已提交身份操作。 */
@Component
public final class RetentionCleanup {
  private static final Logger LOG = LoggerFactory.getLogger(RetentionCleanup.class);
  private final RetentionMapper mapper;
  private final TransactionTemplate transaction;
  private final Counter deleted;
  private final Counter failures;

  /** 共用单库连接池，最多四条有界删除；不增加无界重试或后台工作队列。 */
  public RetentionCleanup(
      RetentionMapper mapper, PlatformTransactionManager manager, MeterRegistry metrics) {
    this.mapper = mapper;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(5);
    deleted = metrics.counter("iam.retention.deleted");
    failures = metrics.counter("iam.retention.failures");
  }

  /** 返回本次真实提交删除行数；失败返回零并计数，等待下轮固定周期再试。 */
  public int run() {
    try {
      int count =
          transaction.execute(
              status -> {
                mapper.budget();
                return mapper.receipts()
                    + mapper.sessions()
                    + mapper.invitations()
                    + mapper.audits();
              });
      deleted.increment(count);
      return count;
    } catch (RuntimeException error) {
      failures.increment();
      LOG.warn("IAM retention failed type={}", error.getClass().getSimpleName());
      return 0;
    }
  }
}
