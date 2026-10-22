package org.domainforge.iam.infrastructure.query.audit;

import java.util.List;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.query.audit.AuditQueries;
import org.domainforge.iam.application.query.audit.AuditView;
import org.springframework.stereotype.Repository;

/** 受保护审计查询短路径，不附带业务状态修改。 */
@Repository
public class SqlAuditQueries implements AuditQueries {
  private final AuditQueryMapper mapper;

  /** 使用只读查询Mapper。 */
  public SqlAuditQueries(AuditQueryMapper mapper) {
    this.mapper = mapper;
  }

  /** 参数错误拒绝，不自动改成无界读取。 */
  @Override
  public List<AuditView> list(long after, int limit) {
    if (after < 0 || limit < 1 || limit > 100)
      throw new IamException(IamFailure.INVALID_REQUEST, "审计分页输入无效");
    return mapper.list(after, limit).stream().map(AuditProjection::view).toList();
  }
}
