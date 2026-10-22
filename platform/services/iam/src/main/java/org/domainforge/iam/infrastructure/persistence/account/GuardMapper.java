package org.domainforge.iam.infrastructure.persistence.account;

/** 锁定唯一管理守卫，串行化首次初始化和最后管理者检查。 */
@org.apache.ibatis.annotations.Mapper
public interface GuardMapper {
  /** 守卫由迁移固定建立，缺失时不能按未初始化继续。 */
  Boolean lock();

  /** 与初始账号一起提交，重复初始化必须在应用提前拒绝。 */
  int initialize();
}
