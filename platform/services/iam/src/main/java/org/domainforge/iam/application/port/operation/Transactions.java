package org.domainforge.iam.application.port.operation;

import java.util.function.Supplier;

/** 应用用例选择原子范围；适配器建立独立READ_COMMITTED事务并回滚异常。 */
public interface Transactions {
  /** 返回值仅在提交确认后交付；提交结果未知抛出应用故障，不能返回成功。 */
  <T> T execute(Supplier<T> work);

  /** 登记仅在当前业务提交确认后执行的动作；不能用于承担授权失效。 */
  void afterCommit(Runnable action);
}
