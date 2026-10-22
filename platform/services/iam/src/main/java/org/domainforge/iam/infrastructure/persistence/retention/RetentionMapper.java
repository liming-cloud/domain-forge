package org.domainforge.iam.infrastructure.persistence.retention;

/** 每表每次最多删除1000行，数据库时钟决定保留期，其他实例跳过已锁行。 */
@org.apache.ibatis.annotations.Mapper
public interface RetentionMapper {
  /** 单语句上限避免清理占满业务数据库连接。 */
  void budget();

  /** 收据只删除已过完整24小时保留期的记录。 */
  int receipts();

  /** 删除过期SQL许可不影响任何有效会话，Redis过期由原生TTL管理。 */
  int sessions();

  /** 邀请额外保留24小时，保证临到期注册仍能按完整收据期限重放。 */
  int invitations();

  /** 补录按真实记录时间保留90天，不能用未知或久远原发生时间立即清掉。 */
  int audits();
}
