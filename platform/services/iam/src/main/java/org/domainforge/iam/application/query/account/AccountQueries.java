package org.domainforge.iam.application.query.account;

import java.util.UUID;

/** 纯查询短路径，公开合同不泄露Mapper、PO或领域凭据。 */
public interface AccountQueries {
  /** id必须来自入口已经验证的当前主体；不提供“按任意ID读本人”的接口。 */
  AccountView self(UUID trustedAccountId);

  /** 入口必须已验证account:read；limit为1至100，按UUID稳定升序且无写入。 */
  AccountPage list(UUID after, int limit);
}
