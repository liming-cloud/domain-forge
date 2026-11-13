package org.domainforge.iam.infrastructure.persistence.account;

import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.jspecify.annotations.Nullable;
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

    private @Nullable Account domain(@Nullable AccountRow row) {
        if (row == null) return null;
        return row.toDomain(new HashSet<>(mapper.roles(row.id())), new HashSet<>(mapper.memberships(row.id())));
    }

    @Override
    public @Nullable Account find(long id) {
        return domain(mapper.find(id));
    }

    @Override
    public @Nullable Account findByLoginName(String name) {
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
    public boolean update(Account current, Account changed) {
        if (mapper.update(AccountRow.from(changed), current.version()) != 1) return false;
        if (!changed.roleIds().equals(current.roleIds())) {
            mapper.deleteRoles(changed.id());
            writeRoles(changed);
        }
        if (!changed.memberships().equals(current.memberships())) {
            mapper.deleteMemberships(changed.id());
            writeMemberships(changed);
        }
        return true;
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
