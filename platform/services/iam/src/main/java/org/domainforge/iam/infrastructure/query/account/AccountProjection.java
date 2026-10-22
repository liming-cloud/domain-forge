package org.domainforge.iam.infrastructure.query.account;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.domainforge.iam.application.query.account.AccountView;

/** 数据库投影只包含公开查询字段，查询SQL不读取凭据。 */
public record AccountProjection(
    String id, String loginName, boolean enabled, long version, String permissions) {
  /** 将私有存储编码转为技术无关只读视图。 */
  AccountView view() {
    return new AccountView(
        UUID.fromString(id),
        loginName,
        enabled,
        version,
        permissions.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(permissions.split(","))));
  }
}
