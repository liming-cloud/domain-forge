package org.domainforge.iam.infrastructure.query.operation;

import java.util.UUID;
import org.domainforge.iam.application.port.operation.CommandResult;

/** 操作查询刻意不选择内部敏感请求指纹。 */
public record OperationProjection(
    String operationId, String targetId, long resultVersion, String action) {
  /** 将实际已存结果还原为非秘密命令结果。 */
  CommandResult result() {
    return new CommandResult(
        UUID.fromString(operationId), UUID.fromString(targetId), resultVersion, action);
  }
}
