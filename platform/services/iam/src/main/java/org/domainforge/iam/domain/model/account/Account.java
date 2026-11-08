package org.domainforge.iam.domain.model.account;

import org.domainforge.iam.domain.IamRuleViolation;
import org.domainforge.iam.domain.model.shared.LoginName;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.shared.AggregateRoot;

import java.util.Objects;
import java.util.Set;

/**
 * 账号维护自身凭据、启用状态、角色分配与成员归属；不向公开查询导出凭据。
 *
 * <p>账号 ID 为应用分配的雪花 long，创建前即确定，便于同一事务内关联邀请与审计。
 * 角色与成员归属只持 ID；角色、组织是否存在由应用在同一事务内核对。
 */
public record Account(
        long id,
        String loginName,
        String credentialDigest,
        long credentialVersion,
        long version,
        boolean enabled,
        Set<Long> roleIds,
        Set<Membership> memberships)
        implements AggregateRoot {
    /**
     * 一个账号最多分配的角色数，与当前主体合同的上限一致。
     */
    public static final int MAX_ROLES = 20;
    /**
     * 一个账号最多归属的组织数。
     */
    public static final int MAX_MEMBERSHIPS = 50;

    /**
     * 从已保存状态恢复账号也须校验不变量。
     */
    public Account {
        if (id <= 0 || credentialVersion < 1 || version < 1) {
            throw IamRuleViolation.invalid("ACCOUNT_STATE_INVALID", "账号身份与版本无效");
        }
        loginName = new LoginName(loginName).value();
        requireCredential(credentialDigest);
        roleIds = requireRoles(roleIds);
        memberships = requireMemberships(memberships);
    }

    /**
     * 创建受邀普通账号，初始无角色且不关联隐式组织。
     */
    public static Account register(long id, String loginName, String digest) {
        return new Account(id, loginName, digest, 1, 1, true, Set.of(), Set.of());
    }

    /**
     * 创建初始管理者：只分配系统管理员角色，不关联组织。
     */
    public static Account administrator(long id, String loginName, String digest) {
        return new Account(id, loginName, digest, 1, 1, true, Set.of(SystemRole.ADMIN_ID), Set.of());
    }

    /**
     * 摘要由成熟密码适配生成；领域只要求有界非空，不自行解析密码格式。
     */
    private static void requireCredential(String digest) {
        if (digest == null || digest.isBlank() || digest.length() > 512) {
            throw IamRuleViolation.invalid("CREDENTIAL_DIGEST_INVALID", "凭据摘要无效");
        }
    }

    private static Set<Long> requireRoles(Set<Long> roles) {
        if (roles == null || roles.size() > MAX_ROLES || roles.stream().anyMatch(id -> id == null || id <= 0))
            throw IamRuleViolation.invalid("ROLE_ASSIGNMENT_INVALID", "角色分配无效");
        return Set.copyOf(roles);
    }

    /**
     * 成员归属可以为空；存在归属时恰有一个主部门，同一组织不能重复。
     */
    private static Set<Membership> requireMemberships(Set<Membership> values) {
        if (values == null || values.size() > MAX_MEMBERSHIPS || values.stream().anyMatch(Objects::isNull))
            throw IamRuleViolation.invalid("MEMBERSHIP_INVALID", "成员归属无效");
        long units = values.stream().mapToLong(Membership::unitId).distinct().count();
        long primary = values.stream().filter(Membership::primary).count();
        if (units != values.size() || (!values.isEmpty() && primary != 1))
            throw IamRuleViolation.invalid("MEMBERSHIP_PRIMARY_INVALID", "成员归属须不重复且恰有一个主部门");
        return Set.copyOf(values);
    }

    /**
     * 改密同时提升凭据和并发版本；禁用账号不能使用此自助转换。
     */
    public Account changePassword(String digest) {
        if (!enabled) throw IamRuleViolation.conflict("ACCOUNT_DISABLED", "禁用账号不能自助改密");
        return new Account(
                id, loginName, digest, credentialVersion + 1, version + 1, true, roleIds, memberships);
    }

    /**
     * 状态实际改变时提升凭据版本；重新启用不恢复禁用前会话。
     */
    public Account changeEnabled(boolean state) {
        if (enabled == state) return this;
        return new Account(
                id, loginName, credentialDigest, credentialVersion + 1, version + 1, state, roleIds, memberships);
    }

    /**
     * 整体替换角色分配；权限随角色在下一次授权检查生效，不强制结束会话。
     */
    public Account changeRoles(Set<Long> next) {
        Set<Long> roles = requireRoles(next);
        if (roleIds.equals(roles)) return this;
        return new Account(
                id, loginName, credentialDigest, credentialVersion, version + 1, enabled, roles, memberships);
    }

    /**
     * 整体替换成员归属；数据范围随归属在下一次检查生效。
     */
    public Account changeMemberships(Set<Membership> next) {
        Set<Membership> values = requireMemberships(next);
        if (memberships.equals(values)) return this;
        return new Account(
                id, loginName, credentialDigest, credentialVersion, version + 1, enabled, roleIds, values);
    }

    /**
     * 启用且持有系统管理员角色；“至少保留一名”的规则由应用在管理守卫内协调。
     */
    public boolean isSystemAdministrator() {
        return enabled && roleIds.contains(SystemRole.ADMIN_ID);
    }

    /**
     * 诊断表示只含身份和版本，避免凭据进入日志。
     */
    @Override
    public String toString() {
        return "Account[id=" + id + ", version=" + version + "]";
    }
}
