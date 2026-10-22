package org.domainforge.iam.domain.model;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.domainforge.domain.api.AggregateRoot;

/** 账号维护自身凭据、启用状态及直接权限；不向公开查询导出凭据。 */
public record Account(
    UUID id,
    String loginName,
    String credentialDigest,
    long credentialVersion,
    long version,
    boolean enabled,
    Set<String> permissions)
    implements AggregateRoot {
  /** 本切片的完整管理权限；分配和最后管理者保护由应用事务统一协调。 */
  public static final Set<String> MANAGEMENT_PERMISSIONS =
      Set.of(
          "iam:invitation:create",
          "iam:account:read",
          "iam:account:state",
          "iam:grant:manage",
          "iam:audit:read");

  /** 从已保存状态恢复账号也须校验不变量，不能通过构造绕过权限目录。 */
  public Account {
    if (id == null || credentialVersion < 1 || version < 1) {
      throw new IllegalArgumentException("账号身份与版本无效");
    }
    loginName = normalizeLoginName(loginName);
    requireCredential(credentialDigest);
    if (permissions == null || !MANAGEMENT_PERMISSIONS.containsAll(permissions)) {
      throw new IllegalArgumentException("权限必须来自本项目明确目录");
    }
    permissions = Set.copyOf(permissions);
  }

  /** 规范化登录名大小写；不修剪空白，不改变密码输入。 */
  public static String normalizeLoginName(String name) {
    if (name == null || !name.matches("[a-zA-Z0-9._-]{3,64}")) {
      throw new IllegalArgumentException("登录名必须为3至64个ASCII字母、数字或._-");
    }
    return name.toLowerCase(Locale.ROOT);
  }

  /** 创建受邀普通账号，初始无管理权限且不关联隐式组织。 */
  public static Account register(UUID id, String loginName, String digest) {
    return new Account(id, loginName, digest, 1, 1, true, Set.of());
  }

  /** 改密同时提升凭据和并发版本；禁用账号不能使用此自助转换。 */
  public Account changePassword(String digest) {
    if (!enabled) throw new IllegalStateException("禁用账号不能自助改密");
    return new Account(
        id, loginName, digest, credentialVersion + 1, version + 1, true, permissions);
  }

  /** 状态实际改变时提升凭据版本；重新启用不恢复禁用前会话。 */
  public Account changeEnabled(boolean state) {
    if (enabled == state) return this;
    return new Account(
        id, loginName, credentialDigest, credentialVersion + 1, version + 1, state, permissions);
  }

  /** 修改当前权限并提升并发版本，不强制结束本来仍有效的登录。 */
  public Account changePermissions(Set<String> next) {
    if (permissions.equals(next)) return this;
    return new Account(
        id, loginName, credentialDigest, credentialVersion, version + 1, enabled, next);
  }

  /** 完整管理者用于跨账号保全规则；调用方须在统一锁边界下判断集合。 */
  public boolean isCompleteAdministrator() {
    return enabled && permissions.containsAll(MANAGEMENT_PERMISSIONS);
  }

  /** 摘要由成熟密码适配生成；领域只要求有界非空，不自行解析密码格式。 */
  private static void requireCredential(String digest) {
    if (digest == null || digest.isBlank() || digest.length() > 512) {
      throw new IllegalArgumentException("凭据摘要无效");
    }
  }

  /** 诊断表示只含身份和版本，避免凭据进入日志。 */
  @Override
  public String toString() {
    return "Account[id=" + id + ", version=" + version + "]";
  }
}
