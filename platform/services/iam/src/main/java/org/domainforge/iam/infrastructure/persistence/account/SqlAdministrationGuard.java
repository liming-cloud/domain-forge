package org.domainforge.iam.infrastructure.persistence.account;

import org.domainforge.iam.application.port.operation.AdministrationGuard;
import org.springframework.stereotype.Repository;

/** 固定守卫不存在属于部署故障，不能重新推断为全新项目。 */
@Repository
public class SqlAdministrationGuard implements AdministrationGuard {
  private final GuardMapper mapper;

  /** 绑定唯一数据库守卫。 */
  public SqlAdministrationGuard(GuardMapper mapper) {
    this.mapper = mapper;
  }

  /** 必须在已开始的业务事务中调用；缺失守卫拒绝继续。 */
  @Override
  public boolean lock() {
    Boolean initialized = mapper.lock();
    if (initialized == null) throw new IllegalStateException("缺失管理守卫");
    return initialized;
  }

  /** 重复初始化不允许重新设置管理身份。 */
  @Override
  public void markInitialized() {
    if (mapper.initialize() != 1) throw new IllegalStateException("初始化状态已变化");
  }
}
