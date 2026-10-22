package org.domainforge.iam.application.query.operation;

import java.util.UUID;
import org.domainforge.iam.application.port.operation.CommandResult;

/** 已确定操作结果的只读投影；没有收据不等于业务未提交。 */
public interface OperationQueries {
  /** 按明确账号范围读未过期收据，权限由应用查询编排核对。 */
  CommandResult find(UUID actorId, UUID operationId);
}
