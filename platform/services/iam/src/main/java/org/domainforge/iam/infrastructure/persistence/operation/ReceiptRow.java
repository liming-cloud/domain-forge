package org.domainforge.iam.infrastructure.persistence.operation;

import java.util.UUID;
import org.domainforge.iam.application.port.operation.CommandResult;

/** 收据仅存固定结果字段，指纹为服务器HMAC而非可猜解的密码摘要。 */
public record ReceiptRow(
    String fingerprint, String operationId, String targetId, long resultVersion, String action) {
  /** 还原已经提交的非秘密结果。 */
  CommandResult result() {
    return new CommandResult(
        UUID.fromString(operationId), UUID.fromString(targetId), resultVersion, action);
  }

  /** 不把敏感指纹加入默认诊断输出。 */
  @Override
  public String toString() {
    return "ReceiptRow[operationId=" + operationId + "]";
  }
}
