package org.domainforge.iam.infrastructure.persistence.account;

import org.apache.ibatis.annotations.Param;

/** 账号持久化SQL仅以绑定值访问具体账号，管理守卫由应用提前取得。 */
@org.apache.ibatis.annotations.Mapper
public interface AccountMapper {
  /** 返回指定账号当前存储状态，不存在返回null。 */
  AccountRow find(String id);

  /** 登录名已由领域规则规范化，不在SQL拼接外部输入。 */
  AccountRow byName(String name);

  /** 锁定账号直到当前事务结束。 */
  AccountRow lock(String id);

  /** 唯一键拒绝同名账号，包括并发注册。 */
  int insert(AccountRow row);

  /** 乐观版本条件同时保护意外漏锁调用。 */
  int update(@Param("row") AccountRow row, @Param("expected") long expected);

  /** 完整管理权限采用规范排序后的精确集合，无超级管理员旁路。 */
  long administrators(String permissions);
}
