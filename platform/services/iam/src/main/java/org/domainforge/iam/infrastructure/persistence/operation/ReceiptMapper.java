package org.domainforge.iam.infrastructure.persistence.operation;

import org.apache.ibatis.annotations.Param;

/** 收据和业务同事务；事务级数据库锁串行化相同范围的操作ID。 */
@org.apache.ibatis.annotations.Mapper
public interface ReceiptMapper {
  /** 哈希碰撞只导致额外串行，不允许数据串用；实际主键仍含完整scope和ID。 */
  int lock(String key);

  /** 按已过期的精确主键删除，不进行无界清理。 */
  int expire(@Param("scope") String scope, @Param("id") String id);

  /** 仅读取同一可信主体范围，不支持通过自报账号ID查他人结果。 */
  ReceiptRow find(@Param("scope") String scope, @Param("id") String id);

  /** 保存确定结果，响应中不包含fingerprint。 */
  int insert(@Param("scope") String scope, @Param("row") ReceiptRow row);
}
