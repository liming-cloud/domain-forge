package org.domainforge.iam.infrastructure.persistence.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

/** 原生事务生命周期的确定失败与未知结果分类，不以模拟业务成功代替数据库回归。 */
class SpringTransactionsTest {
  @Test
  void cannotCreateTransactionIsUnavailableWithoutExecutingWork() {
    var manager = new NativeManager(Fault.BEGIN);
    var work = new AtomicInteger();
    var failure =
        assertThrows(
            IamException.class,
            () -> new SpringTransactions(manager).execute(work::incrementAndGet));
    assertEquals(IamFailure.UNAVAILABLE, failure.failure());
    assertEquals(0, work.get());
    assertEquals(0, manager.commits);
    assertEquals(0, manager.rollbacks);
  }

  @Test
  void confirmedGlobalRollbackIsUnavailable() {
    var manager = new NativeManager(Fault.NONE);
    var failure =
        assertThrows(
            IamException.class,
            () ->
                new SpringTransactions(manager)
                    .execute(
                        () -> {
                          manager.state.rollbackOnly = true;
                          return "not committed";
                        }));
    assertEquals(IamFailure.UNAVAILABLE, failure.failure());
    assertEquals(0, manager.commits);
    assertEquals(1, manager.rollbacks);
  }

  @Test
  void confirmedWorkRollbackIsUnavailable() {
    var manager = new NativeManager(Fault.NONE);
    var failure =
        assertThrows(
            IamException.class,
            () ->
                new SpringTransactions(manager)
                    .execute(
                        () -> {
                          throw new DataAccessResourceFailureException("controlled work failure");
                        }));
    assertEquals(IamFailure.UNAVAILABLE, failure.failure());
    assertEquals(0, manager.commits);
    assertEquals(1, manager.rollbacks);
  }

  @Test
  void rollbackAcknowledgementFailureRemainsUnknown() {
    var manager = new NativeManager(Fault.ROLLBACK);
    var failure =
        assertThrows(
            IamException.class,
            () ->
                new SpringTransactions(manager)
                    .execute(
                        () -> {
                          throw new DataAccessResourceFailureException("controlled work failure");
                        }));
    assertEquals(IamFailure.UNKNOWN_RESULT, failure.failure());
    assertEquals(0, manager.commits);
    assertEquals(1, manager.rollbacks);
  }

  /** 只在指定原生生命周期点注入故障，不替代被测TransactionTemplate。 */
  private enum Fault {
    NONE,
    BEGIN,
    ROLLBACK
  }

  /** Spring自身判定全局rollback-only，测试不手工伪造完成回调。 */
  private static final class State implements SmartTransactionObject {
    private boolean rollbackOnly;

    @Override
    public boolean isRollbackOnly() {
      return rollbackOnly;
    }
  }

  /** 原生抽象管理器负责同步回调和提交/回滚次序，记录实际调用。 */
  private static final class NativeManager extends AbstractPlatformTransactionManager {
    private final Fault fault;
    private final State state = new State();
    private int commits;
    private int rollbacks;

    private NativeManager(Fault fault) {
      this.fault = fault;
    }

    @Override
    protected Object doGetTransaction() {
      return state;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      if (fault == Fault.BEGIN)
        throw new CannotCreateTransactionException("controlled begin failure");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
      if (fault == Fault.ROLLBACK)
        throw new DataAccessResourceFailureException("controlled rollback acknowledgement loss");
    }
  }
}
