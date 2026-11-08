package @@PACKAGE@@.infrastructure.persistence.tally;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单统计表；SQL 在 mapper/tally/OrderTallyMapper.xml。
 */
@Mapper
public interface OrderTallyMapper {
    /**
     * 不存在则建为 1，存在则加一。
     */
    int increment(@Param("ownerId") long ownerId);

    /**
     * 某账号的订单数，没有记录为 null。
     */
    Long count(@Param("ownerId") long ownerId);
}
