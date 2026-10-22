package org.domainforge.iam.infrastructure;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

/** 仅在真实事务已提交后注入确认丢失，验证调用方不能将未知结果猜成失败或成功。 */
final class CommitAcknowledgementLoss implements PlatformTransactionManager {
  private final PlatformTransactionManager delegate;

  CommitAcknowledgementLoss(PlatformTransactionManager delegate) {
    this.delegate = delegate;
  }

  @Override
  public TransactionStatus getTransaction(TransactionDefinition definition) {
    return delegate.getTransaction(definition);
  }

  @Override
  public void commit(TransactionStatus status) {
    delegate.commit(status);
    throw new TransactionSystemException("controlled acknowledgement loss");
  }

  @Override
  public void rollback(TransactionStatus status) {
    delegate.rollback(status);
  }
}
