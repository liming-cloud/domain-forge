package org.domainforge.iam.infrastructure.persistence.account;

import java.util.TreeSet;
import java.util.UUID;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Repository;

/** MyBatis账号仓储将表表示还原为领域快照并验证实际更新行数。 */
@Repository
public class SqlAccounts implements AccountRepository {
  private final AccountMapper mapper;

  /** 使用当前服务唯一SqlSessionFactory绑定的Mapper。 */
  public SqlAccounts(AccountMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Account find(UUID id) {
    return domain(mapper.find(id.toString()));
  }

  @Override
  public Account findByLoginName(String name) {
    return domain(mapper.byName(name));
  }

  @Override
  public Account lock(UUID id) {
    return domain(mapper.lock(id.toString()));
  }

  @Override
  public void insert(Account account) {
    mapper.insert(AccountRow.from(account));
  }

  /** 零行写入是版本冲突，不能默认为已完成。 */
  @Override
  public void update(Account account, long expected) {
    if (mapper.update(AccountRow.from(account), expected) != 1) {
      throw new IamException(IamFailure.CONFLICT, "账号版本已变化");
    }
  }

  @Override
  public long completeAdministrators() {
    return mapper.administrators(String.join(",", new TreeSet<>(Account.MANAGEMENT_PERMISSIONS)));
  }

  private static Account domain(AccountRow row) {
    return row == null ? null : row.toDomain();
  }
}
