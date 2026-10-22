package org.domainforge.iam.infrastructure.query.operation;

import java.util.UUID;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.query.operation.OperationQueries;
import org.springframework.stereotype.Repository;

/** 未确认结果保留空值语义，不把它解释成安全重试任意操作的证明。 */
@Repository
public class SqlOperationQueries implements OperationQueries {
  private final OperationQueryMapper mapper;

  /** 使用只读结果投影Mapper。 */
  public SqlOperationQueries(OperationQueryMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public CommandResult find(UUID actor, UUID operation) {
    var row = mapper.find("account:" + actor, operation.toString());
    return row == null ? null : row.result();
  }
}
