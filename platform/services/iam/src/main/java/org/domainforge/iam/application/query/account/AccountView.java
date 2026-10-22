package org.domainforge.iam.application.query.account;

import java.util.Set;
import java.util.UUID;

/** 最小账号投影不含密码摘要、会话或组织假数据。 */
public record AccountView(
    UUID id, String loginName, boolean enabled, long version, Set<String> permissions) {
  /** 复制权限集合，防止消费者修改查询快照。 */
  public AccountView {
    permissions = Set.copyOf(permissions);
  }
}
