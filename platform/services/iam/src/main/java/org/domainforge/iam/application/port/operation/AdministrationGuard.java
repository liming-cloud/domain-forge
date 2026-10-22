package org.domainforge.iam.application.port.operation;

/** 单库管理协调边界；不是跨进程内存锁或领域聚合自身规则。 */
public interface AdministrationGuard {
  /** 在当前事务先取得唯一守卫，之后才允许锁账号；返回是否已初始化。 */
  boolean lock();

  /** 首次初始化完成标记与初始账号在同一事务提交。 */
  void markInitialized();
}
