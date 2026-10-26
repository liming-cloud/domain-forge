package org.domainforge.iam.infrastructure.persistence.operation;

import org.apache.ibatis.annotations.Mapper;
import org.domainforge.iam.application.port.operation.DatabaseTime;

/**
 * 以 XML 查询直接实现单方法时间出口，不增加无转换的委托包装。
 */
@Mapper
public interface DatabaseTimeMapper extends DatabaseTime {
}
