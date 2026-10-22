package org.domainforge.iam.infrastructure.persistence.operation;

import org.springframework.transaction.support.TransactionSynchronization;

/** 只有业务提交确认才触发独立审计；动作自身负责观测故障隔离。 */
record AfterCommitAction(Runnable action) implements TransactionSynchronization {
  @Override
  public void afterCommit() {
    action.run();
  }
}
