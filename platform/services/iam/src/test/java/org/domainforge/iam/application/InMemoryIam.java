package org.domainforge.iam.application;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.domainforge.iam.application.port.authentication.LoginReservation;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.domainforge.iam.application.port.operation.AdministrationGuard;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.account.Membership;
import org.domainforge.iam.domain.model.invitation.RegistrationInvitation;
import org.domainforge.iam.domain.model.menu.MenuItem;
import org.domainforge.iam.domain.model.organization.OrganizationUnit;
import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.domainforge.iam.domain.model.permission.PermissionNamespace;
import org.domainforge.iam.domain.model.role.PermissionGrant;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.domainforge.iam.domain.repository.MenuRepository;
import org.domainforge.iam.domain.repository.OrganizationRepository;
import org.domainforge.iam.domain.repository.PermissionCatalog;
import org.domainforge.iam.domain.repository.RoleRepository;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用层单元测试用的内存端口与仓储，不起容器。
 *
 * <p>每次持久写入（含审计）在 {@link #writes} 记一行，拒绝场景据此断言“没有写入”。版本条件更新零行时与 SQL 实现一样报告冲突；
 * 事务用 {@link TransactionOperations#withoutTransaction()} 直接执行，不模拟回滚，所以被测命令必须在写入前拒绝。
 */
public final class InMemoryIam {
    /**
     * 测试中的数据库时间。
     */
    public static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    public final List<String> writes = new ArrayList<>();
    public final List<AuditEvent> audits = new ArrayList<>();
    public final Accounts accounts = new Accounts();
    public final Roles roles = new Roles();
    public final Organizations organizations = new Organizations();
    public final Menus menus = new Menus();
    public final Catalog catalog = new Catalog();
    public final Invitations invitations = new Invitations();
    public final Sessions sessions = new Sessions();
    public final Natives nativeSessions = new Natives();
    public final Protection protection = new Protection();
    public final Guard guard = new Guard();
    public final Secrets secrets = new Secrets();
    public final Passwords passwords = new HashPasswords();
    public final AuditTrail audit = this::audit;
    public final DatabaseTime clock = () -> NOW;
    public final TransactionOperations transactions = TransactionOperations.withoutTransaction();
    private long nextId = 100;
    public final Identifiers identifiers = () -> nextId++;

    /**
     * 与生产相同的授权逻辑，会话与权限来自内存会话存储。
     */
    public CurrentAuthorization authorization() {
        return new CurrentAuthorization(nativeSessions, sessions, secrets);
    }

    /**
     * 登记一个已登录的操作者并返回其令牌摘要；system 为真时带 SYSTEM_ADMIN 角色。
     */
    public String actor(long accountId, boolean system, String... permissions) {
        String digest = secrets.digest("token-of-" + accountId);
        Set<String> roleCodes = system ? Set.of(Role.SYSTEM_ADMIN_CODE) : Set.of();
        sessions.identities.put(digest, new CurrentIdentity(accountId, digest, 1, Set.of(permissions), roleCodes));
        return digest;
    }

    /**
     * 在仓储中放入启用的账号（不计入写入），返回保存的账号。
     */
    public Account account(long id, String loginName, Long... roleIds) {
        Account account = new Account(id, loginName, "hash:secret", 1, 1, true, Set.of(roleIds), Set.of());
        accounts.rows.put(id, account);
        return account;
    }

    /**
     * 在仓储中放入角色（不计入写入）。
     */
    public Role role(long id, String code, boolean enabled) {
        Role role = new Role(id, code, code, enabled, id == SystemRole.ADMIN_ID, 1, Set.of());
        roles.rows.put(id, role);
        return role;
    }

    /**
     * 在根组织下放入启用的组织（不计入写入）。
     */
    public OrganizationUnit unit(long id, long parentId, String name) {
        OrganizationUnit unit = new OrganizationUnit(id, parentId, name, true, 1);
        organizations.rows.put(id, unit);
        return unit;
    }

    /**
     * 在目录中放入权限定义（不计入写入）。
     */
    public void permission(String code, boolean scoped, boolean systemOnly) {
        catalog.rows.put(code, new PermissionDefinition(code, code, scoped, systemOnly));
    }

    private void write(String what) {
        writes.add(what);
    }

    private void audit(AuditEvent event) {
        audits.add(event);
        write("audit " + event.action());
    }

    private static IamException conflict() {
        return IamException.conflict("VERSION_CONFLICT", "版本已变化");
    }

    /**
     * 账号仓储：受邀账号另记邀请摘要。
     */
    public final class Accounts implements AccountRepository {
        public final Map<Long, Account> rows = new LinkedHashMap<>();
        public final Map<Long, String> invitationDigests = new HashMap<>();

        @Override
        public Account find(long id) {
            return rows.get(id);
        }

        @Override
        public Account findByLoginName(String normalizedName) {
            return rows.values().stream().filter(a -> a.loginName().equals(normalizedName)).findFirst().orElse(null);
        }

        @Override
        public void insert(Account account) {
            if (findByLoginName(account.loginName()) != null) throw conflict();
            rows.put(account.id(), account);
            write("account.insert " + account.id());
        }

        @Override
        public void insertInvited(Account account, String invitationDigest) {
            if (invitationUsed(invitationDigest)) throw conflict();
            insert(account);
            invitationDigests.put(account.id(), invitationDigest);
        }

        @Override
        public boolean invitationUsed(String invitationDigest) {
            return invitationDigests.containsValue(invitationDigest);
        }

        @Override
        public void update(Account current, Account changed) {
            Account stored = rows.get(changed.id());
            if (stored == null || stored.version() != current.version()) throw conflict();
            rows.put(changed.id(), changed);
            write("account.update " + changed.id());
        }

        @Override
        public long systemAdministrators() {
            return rows.values().stream().filter(Account::isSystemAdministrator).count();
        }
    }

    /**
     * 角色仓储：分配数按账号仓储现状计算。
     */
    public final class Roles implements RoleRepository {
        public final Map<Long, Role> rows = new LinkedHashMap<>();

        @Override
        public Role lock(long id) {
            return rows.get(id);
        }

        @Override
        public boolean codeTaken(String code) {
            return rows.values().stream().anyMatch(r -> r.code().equalsIgnoreCase(code));
        }

        @Override
        public void insert(Role role) {
            rows.put(role.id(), role);
            write("role.insert " + role.id());
        }

        @Override
        public void update(Role role, long expectedVersion) {
            Role current = rows.get(role.id());
            if (current == null || current.version() != expectedVersion) throw conflict();
            rows.put(role.id(), role);
            write("role.update " + role.id());
        }

        @Override
        public void delete(long id) {
            rows.remove(id);
            write("role.delete " + id);
        }

        @Override
        public long assignments(long id) {
            return accounts.rows.values().stream().filter(a -> a.roleIds().contains(id)).count();
        }
    }

    /**
     * 组织仓储：初始只有根组织；引用含子节点、成员归属与自定义范围授予。
     */
    public final class Organizations implements OrganizationRepository {
        public final Map<Long, OrganizationUnit> rows = new LinkedHashMap<>(
                Map.of(OrganizationUnit.ROOT_ID, new OrganizationUnit(OrganizationUnit.ROOT_ID, null, "总部", true, 1)));

        @Override
        public void lockStructure() {
            // 单线程测试不需要协调锁。
        }

        @Override
        public OrganizationUnit lock(long id) {
            return rows.get(id);
        }

        @Override
        public List<Long> ancestors(long id) {
            List<Long> chain = new ArrayList<>();
            for (OrganizationUnit unit = rows.get(id); unit != null; unit = unit.parentId() == null ? null
                    : rows.get(unit.parentId()))
                chain.add(unit.id());
            return chain;
        }

        @Override
        public long existing(Collection<Long> ids) {
            return ids.stream().filter(rows::containsKey).count();
        }

        @Override
        public boolean nameTaken(long parentId, String name, long exceptId) {
            return rows.values().stream().anyMatch(u -> Objects.equals(u.parentId(), parentId)
                    && u.name().equals(name) && u.id() != exceptId);
        }

        @Override
        public void insert(OrganizationUnit unit) {
            rows.put(unit.id(), unit);
            write("org.insert " + unit.id());
        }

        @Override
        public void update(OrganizationUnit unit, long expectedVersion) {
            OrganizationUnit current = rows.get(unit.id());
            if (current == null || current.version() != expectedVersion) throw conflict();
            rows.put(unit.id(), unit);
            write("org.update " + unit.id());
        }

        @Override
        public void delete(long id) {
            rows.remove(id);
            write("org.delete " + id);
        }

        @Override
        public boolean referenced(long id) {
            boolean child = rows.values().stream().anyMatch(u -> Objects.equals(u.parentId(), id));
            boolean member = accounts.rows.values().stream()
                    .anyMatch(a -> a.memberships().stream().map(Membership::unitId).anyMatch(u -> u == id));
            boolean scope = roles.rows.values().stream().flatMap(r -> r.grants().stream())
                    .anyMatch(g -> g.unitIds().contains(id));
            return child || member || scope;
        }
    }

    /**
     * 菜单仓储。
     */
    public final class Menus implements MenuRepository {
        public final Map<Long, MenuItem> rows = new LinkedHashMap<>();

        @Override
        public void lockStructure() {
            // 单线程测试不需要协调锁。
        }

        @Override
        public MenuItem lock(long id) {
            return rows.get(id);
        }

        @Override
        public List<Long> ancestors(long id) {
            List<Long> chain = new ArrayList<>();
            for (MenuItem item = rows.get(id); item != null; item = item.parentId() == null ? null
                    : rows.get(item.parentId()))
                chain.add(item.id());
            return chain;
        }

        @Override
        public boolean codeTaken(String code) {
            return rows.values().stream().anyMatch(m -> m.code().equals(code));
        }

        @Override
        public void insert(MenuItem item) {
            rows.put(item.id(), item);
            write("menu.insert " + item.id());
        }

        @Override
        public void update(MenuItem item, long expectedVersion) {
            MenuItem current = rows.get(item.id());
            if (current == null || current.version() != expectedVersion) throw conflict();
            rows.put(item.id(), item);
            write("menu.update " + item.id());
        }

        @Override
        public void delete(long id) {
            rows.remove(id);
            write("menu.delete " + id);
        }

        @Override
        public boolean hasChildren(long id) {
            return rows.values().stream().anyMatch(m -> Objects.equals(m.parentId(), id));
        }
    }

    /**
     * 权限目录：引用含角色授予与菜单关联。
     */
    public final class Catalog implements PermissionCatalog {
        public final Map<String, PermissionDefinition> rows = new LinkedHashMap<>();

        @Override
        public void lock(boolean exclusive) {
            // 单线程测试不需要协调锁。
        }

        @Override
        public Map<String, PermissionDefinition> find(Collection<String> codes) {
            return codes.stream().filter(rows::containsKey).distinct()
                    .collect(Collectors.toMap(code -> code, rows::get));
        }

        @Override
        public PermissionNamespace namespace(String namespace) {
            return new PermissionNamespace(namespace, inside(namespace));
        }

        @Override
        public long countOutside(String namespace) {
            return rows.values().stream().filter(d -> !d.namespace().equals(namespace)).count();
        }

        @Override
        public Set<String> referenced(Collection<String> codes) {
            Set<String> used = new HashSet<>();
            roles.rows.values().forEach(r -> r.grants().stream().map(PermissionGrant::permission).forEach(used::add));
            menus.rows.values().forEach(m -> used.add(m.permission()));
            return codes.stream().filter(used::contains).collect(Collectors.toSet());
        }

        @Override
        public void replace(PermissionNamespace namespace) {
            inside(namespace.namespace()).forEach(d -> rows.remove(d.code()));
            namespace.permissions().forEach(d -> rows.put(d.code(), d));
            write("catalog.replace " + namespace.namespace());
        }

        private Set<PermissionDefinition> inside(String namespace) {
            return rows.values().stream().filter(d -> d.namespace().equals(namespace)).collect(Collectors.toSet());
        }
    }

    /**
     * 邀请仓储。
     */
    public final class Invitations implements InvitationRepository {
        public final Map<Long, RegistrationInvitation> rows = new LinkedHashMap<>();

        @Override
        public RegistrationInvitation lockByDigest(String digest) {
            return rows.values().stream().filter(i -> i.secretDigest().equals(digest)).findFirst().orElse(null);
        }

        @Override
        public RegistrationInvitation lock(long id) {
            return rows.get(id);
        }

        @Override
        public void insert(RegistrationInvitation invitation) {
            rows.put(invitation.id(), invitation);
            write("invitation.insert " + invitation.id());
        }

        @Override
        public void invalidate(RegistrationInvitation invitation) {
            rows.put(invitation.id(), invitation);
            write("invitation.invalidate " + invitation.id());
        }
    }

    /**
     * SQL 会话许可：当前身份由测试登记，登录写入的许可另存。
     */
    public final class Sessions implements SessionStore {
        public final Map<String, CurrentIdentity> identities = new HashMap<>();
        public final List<SessionGrant> grants = new ArrayList<>();

        @Override
        public void insert(SessionGrant grant) {
            grants.add(grant);
            write("session.insert " + grant.accountId());
        }

        @Override
        public CurrentIdentity current(String tokenDigest) {
            return identities.get(tokenDigest);
        }

        @Override
        public void revoke(String tokenDigest) {
            identities.remove(tokenDigest);
            write("session.revoke");
        }
    }

    /**
     * 原生会话：只记录仍存活的令牌，不计入持久写入。
     */
    public static final class Natives implements NativeSessions {
        public final Map<String, Long> live = new HashMap<>();
        private int issued;

        @Override
        public String issue(long accountId) {
            String token = "native-" + accountId + "-" + ++issued;
            live.put(token, accountId);
            return token;
        }

        @Override
        public Long subject(String token) {
            return live.get(token);
        }

        @Override
        public void revoke(String token) {
            live.remove(token);
        }

        @Override
        public void cleanup(String token) {
            live.remove(token);
        }
    }

    /**
     * 登录防护：limited 为真时拒绝预占；记录尚未因成功而移除的预占。
     */
    public static final class Protection implements LoginProtection {
        public final List<LoginReservation> pending = new ArrayList<>();
        public boolean limited;

        @Override
        public LoginReservation reserve(String normalizedName, String directAddress) {
            if (limited) throw IamException.rateLimited("LOGIN_RATE_LIMITED", "尝试过于频繁", 1);
            LoginReservation reservation = new LoginReservation(normalizedName, "attempt-" + pending.size());
            pending.add(reservation);
            return reservation;
        }

        @Override
        public void succeeded(LoginReservation reservation) {
            pending.remove(reservation);
        }
    }

    /**
     * 管理守卫：initialized 表示项目已初始化。
     */
    public final class Guard implements AdministrationGuard {
        public boolean initialized;

        @Override
        public boolean lock() {
            return initialized;
        }

        @Override
        public void markInitialized() {
            initialized = true;
            write("guard.initialized");
        }
    }

    /**
     * 秘密与摘要：摘要用 SHA-256，满足会话许可与邀请对 64 位十六进制的要求。
     */
    public static final class Secrets implements SecretOperations {
        private int issued;

        @Override
        public String newSecret() {
            return String.format("%043d", ++issued);
        }

        @Override
        public String digest(String secret) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public String fingerprint(String... fields) {
            return digest(String.join("\u0000", fields));
        }
    }

    /**
     * 可逆的假摘要，便于断言保存了新密码；不校验密码策略。
     */
    private static final class HashPasswords implements Passwords {
        @Override
        public String encode(String password) {
            return "hash:" + password;
        }

        @Override
        public boolean matches(String password, String digest) {
            return digest != null && digest.equals("hash:" + password);
        }
    }
}
