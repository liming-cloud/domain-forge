package org.domainforge.iam.infrastructure.query.account;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 单次业务投影SQL，身份校验SQL单独统计，不使用总数查询或逐账号补读。 */
@org.apache.ibatis.annotations.Mapper
public interface AccountQueryMapper {
  /** 本人查询只按可信身份绑定主键。 */
  AccountProjection self(String id);

  /** 固定排序和绑定游标，不能将客户端字段作为SQL排序片段。 */
  List<AccountProjection> list(@Param("after") String after, @Param("limit") int limit);
}
