package org.domainforge.iam.interfaces.http.authentication;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 退出操作的幂等身份。 */
@Schema(description = "退出操作的幂等身份")
public record OperationRequest(UUID operationId) {
  /** 拒绝缺少命令必要字段；不会从空值推断写入意图。 */
  public OperationRequest {
    if (operationId == null) throw new IllegalArgumentException("请求字段不完整");
  }
}
