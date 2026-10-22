package org.domainforge.iam.infrastructure.query.operation;

import org.apache.ibatis.annotations.Param;

/** 收据查询只限明确账号和操作ID，不返回邀请秘密或HMAC指纹。 */
@org.apache.ibatis.annotations.Mapper
public interface OperationQueryMapper {
  /** 过期或未确认收据返回null，不执行补建或其他写入。 */
  OperationProjection find(@Param("scope") String scope, @Param("operation") String operation);
}
