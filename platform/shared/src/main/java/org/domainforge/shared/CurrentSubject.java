package org.domainforge.shared;

import java.util.Set;
import java.util.UUID;

/** 某项目本次验证取得的主体及有限权限，不构成对消费方任意资源的授权。 */
public record CurrentSubject(
    String projectId, UUID accountId, Set<String> permissions, Set<String> roles) {
  /** 旧版主体没有角色授予，保持空集合而不推定角色。 */
  public CurrentSubject(String projectId, UUID accountId, Set<String> permissions) {
    this(projectId, accountId, permissions, Set.of());
  }

  /** 只接受明确项目和有界权限，复制集合；不携带令牌、HTTP或持久化类型。 */
  public CurrentSubject {
    if (projectId == null
        || !projectId.matches("[a-z][a-z0-9-]{2,63}")
        || accountId == null
        || roles == null
        || roles.size() > 128
        || roles.stream().anyMatch(r -> r == null || !r.matches("[A-Za-z][A-Za-z0-9:_-]{0,127}"))
        || permissions == null
        || permissions.size() > 128
        || permissions.stream().anyMatch(p -> p == null || !p.matches("[a-z][a-z0-9:-]{1,127}"))) {
      throw new IllegalArgumentException("当前主体合同无效");
    }
    permissions = Set.copyOf(permissions);
    roles = Set.copyOf(roles);
  }
}
