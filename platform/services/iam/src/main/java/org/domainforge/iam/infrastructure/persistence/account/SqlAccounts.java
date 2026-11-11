package org.domainforge.iam.infrastructure.persistence.account;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;

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
        return row.toDomain(new HashSet<>(mapper.roles(row.id())), new HashSet<>(mapper.memberships(row.id())));
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
    public void insert(Account account) {
        mapper.insert(AccountRow.from(account));
        writeChildren(account);
    }

    @Override
    public void insertInvited(Account account, String invitationDigest) {
        mapper.insertInvited(AccountRow.from(account), invitationDigest);
        writeChildren(account);
    }

    @Override
    public boolean invitationUsed(String invitationDigest) {
        return mapper.invitationUsed(invitationDigest);
    }

    /**
     * 零行写入是版本冲突，不能默认为已完成；子表只在相对读取快照变化时整体替换。
     */
    @Override
    public void update(Account current, Account changed) {
        if (mapper.update(AccountRow.from(changed), current.version()) != 1) {
            throw new IamException(IamFailure.CONFLICT, "账号版本已变化");
        }
        if (!changed.roleIds().equals(current.roleIds())) {
            mapper.deleteRoles(changed.id());
            writeRoles(changed);
        }
        if (!changed.memberships().equals(current.memberships())) {
            mapper.deleteMemberships(changed.id());
            writeMemberships(changed);
        }
    }

    private void writeChildren(Account account) {
        writeRoles(account);
        writeMemberships(account);
    }

    private void writeRoles(Account account) {
        if (!account.roleIds().isEmpty()) mapper.insertRoles(account.id(), List.copyOf(account.roleIds()));
    }

    private void writeMemberships(Account account) {
        if (!account.memberships().isEmpty())
            mapper.insertMemberships(account.id(), List.copyOf(account.memberships()));
    }

    @Override
    public long systemAdministrators() {
        return mapper.systemAdministrators(SystemRole.ADMIN_ID);
    }
}
