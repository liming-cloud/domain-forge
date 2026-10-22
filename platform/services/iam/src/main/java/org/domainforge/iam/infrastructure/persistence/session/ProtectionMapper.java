package org.domainforge.iam.infrastructure.persistence.session;

import org.apache.ibatis.annotations.Param;
import org.domainforge.iam.infrastructure.configuration.LoginProtectionSettings;

/** 防护代次与共享策略持久保存；实例不能覆盖不一致策略或缩短恢复期。 */
@org.apache.ibatis.annotations.Mapper
public interface ProtectionMapper {
  /** 在调用方事务中锁定保护状态，允许与否由数据库时间决定。 */
  ProtectionRow lock();

  /** 初始管理者同一事务内登记首代与当前策略，重复初始化拒绝。 */
  int initialize(
      @Param("generation") String generation,
      @Param("digest") String digest,
      @Param("policy") LoginProtectionSettings policy);

  /** 新Redis代次至少关闭完整当前窗口，保留已有更晚截止。 */
  int recover(@Param("generation") String generation, @Param("seconds") int seconds);

  /** 锁内核对旧摘要后切换，按旧新窗口最大值延长截止，重复相同策略拒绝。 */
  int changePolicy(
      @Param("expected") String expected,
      @Param("digest") String digest,
      @Param("policy") LoginProtectionSettings policy);
}
