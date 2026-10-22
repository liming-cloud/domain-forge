package org.domainforge.iam.domain.repository;

import java.util.UUID;
import org.domainforge.iam.domain.model.Account;

/** 账号聚合仓储，账号不存在时读取返回null，写入冲突不可当成功。 */
public interface AccountRepository {
  /** 读取当前账号；不得从跨请求缓存恢复允许依据。 */
  Account find(UUID id);

  /** 读取用于密码校验的账号快照，密码计算后仍须再次锁定核对版本。 */
  Account findByLoginName(String normalizedName);

  /** 在调用方事务中取得账号排他锁；管理操作须先持有管理守卫。 */
  Account lock(UUID id);

  /** 创建新账号；唯一登录名由数据库兜底。 */
  void insert(Account account);

  /** 仅按原版本保存转换结果，零行必须报告冲突。 */
  void update(Account account, long expectedVersion);

  /** 返回启用且持有全部有限管理权限的账号数；须在管理守卫内使用。 */
  long completeAdministrators();
}
