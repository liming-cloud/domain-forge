package org.domainforge.iam.infrastructure.persistence.account;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.account.Membership;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MyBatis账号仓储：主表与角色、成员子表一起还原和保存，并验证实际更新行数。
 */
@Repository
public class SqlAccounts implements AccountRepository {
    private final AccountMapper mapper;

    /**
     * 使用当前服务唯一SqlSessionFactory绑定的Mapper。
     */
    public SqlAccounts(AccountMapper mapper) {
        this.mapper = mapper;
    }

    private Account domain(AccountRow row) {
        if (row == null) return null;
        Set<Membership> memberships = mapper.memberships(row.id()).stream()
                .map(item -> new Membership(item.unitId(), item.primary()))
                .collect(Collectors.toSet());
        return row.toDomain(new HashSet<>(mapper.roles(row.id())), memberships);
    }

    @Override
    public Account find(long id) {
        return domain(mapper.find(id));
    }

    @Override
    public Account findByLoginName(String name) {
        return domain(mapper.byName(name));
    }

    @Override
    public Account lock(long id) {
        return domain(mapper.lock(id));
    }

    @Override
    public void insert(Account account) {
        mapper.insert(AccountRow.from(account));
        writeChildren(account);
    }

    /**
     * 零行写入是版本冲突，不能默认为已完成；子表整体替换。
     */
    @Override
    public void update(Account account, long expected) {
        if (mapper.update(AccountRow.from(account), expected) != 1) {
            throw new IamException(IamFailure.CONFLICT, "账号版本已变化");
        }
        mapper.deleteRoles(account.id());
        mapper.deleteMemberships(account.id());
        writeChildren(account);
    }

    private void writeChildren(Account account) {
        if (!account.roleIds().isEmpty()) mapper.insertRoles(account.id(), List.copyOf(account.roleIds()));
        if (!account.memberships().isEmpty()) {
            mapper.insertMemberships(account.id(), account.memberships().stream()
                    .map(item -> new MembershipRow(item.unitId(), item.primary()))
                    .toList());
        }
    }

    @Override
    public long systemAdministrators() {
        return mapper.systemAdministrators(Role.SYSTEM_ADMIN_ID);
    }
}
