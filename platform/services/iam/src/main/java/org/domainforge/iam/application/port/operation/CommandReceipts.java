package org.domainforge.iam.application.port.operation;

import java.util.UUID;

/** 幂等收据与业务共享事务；先重查当前访问资格，再读取收据。 */
public interface CommandReceipts {
  /** 串行化同一可信范围与操作ID；相同输入返回已确定结果，不同指纹拒绝。 */
  CommandResult previous(String scope, UUID operationId, String fingerprint);

  /** 写入24小时收据；仅保存受控结果字段，不保存请求或秘密。 */
  void save(String scope, String fingerprint, CommandResult result);
}
