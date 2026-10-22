package org.domainforge.iam.infrastructure.persistence.operation;

import java.util.function.Supplier;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.operation.Transactions;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用例边界建立新事务，禁止继承旧快照；异常由原生事务管理器回滚。 */
@Component
public final class SpringTransactions implements Transactions {
  private final TransactionTemplate transaction;

  /** 绑定唯一事务管理器及有界十秒操作；密码计算须在事务之外完成。 */
  public SpringTransactions(PlatformTransactionManager manager) {
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(10);
  }

  /** 原生事务成功返回之后才交付结果；提交故障不得假定未发生。 */
  @Override
  public <T> T execute(Supplier<T> work) {
    var completion = new CompletionStatus();
    try {
      return transaction.execute(
          status -> {
            completion.workStarted = true;
            TransactionSynchronizationManager.registerSynchronization(completion);
            T result = work.get();
            completion.workReturned = true;
            return result;
          });
    } catch (DuplicateKeyException e) {
      if (completion.workRolledBack()) throw new IamException(IamFailure.CONFLICT, "目标已存在或操作冲突");
      throw completion.failure();
    } catch (UnexpectedRollbackException e) {
      throw new IamException(IamFailure.UNAVAILABLE, "数据服务暂不可用");
    } catch (TransactionException | DataAccessException e) {
      throw completion.failure();
    }
  }

  /** 原生事务同步负责准确的提交后时点，不在回滚路径调用动作。 */
  @Override
  public void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(new AfterCommitAction(action));
  }

  /** 单次调用观察原生完成事实；不从异常父类型推导提交是否发生。 */
  private static final class CompletionStatus implements TransactionSynchronization {
    private boolean workStarted;
    private boolean workReturned;
    private int status = STATUS_UNKNOWN;

    @Override
    public void afterCompletion(int completionStatus) {
      status = completionStatus;
    }

    private IamException failure() {
      if (!workStarted || workRolledBack())
        return new IamException(IamFailure.UNAVAILABLE, "数据服务暂不可用");
      return new IamException(IamFailure.UNKNOWN_RESULT, "提交结果未确认，请按操作ID核对");
    }

    /** 提交异常后的补偿回滚不证明之前的commit未发生，不能据此返回确定失败。 */
    private boolean workRolledBack() {
      return !workReturned && status == STATUS_ROLLED_BACK;
    }
  }
}
