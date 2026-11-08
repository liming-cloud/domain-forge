package @@PACKAGE@@.infrastructure.persistence.order;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单凭证关联 SQL，写在 mapper/order/OrderFileMapper.xml。
 */
@org.apache.ibatis.annotations.Mapper
public interface OrderFileMapper {
    int count(long orderId);

    int insert(@Param("orderId") long orderId, @Param("fileId") String fileId);

    int delete(@Param("orderId") long orderId, @Param("fileId") String fileId);

    int exists(@Param("orderId") long orderId, @Param("fileId") String fileId);

    List<String> list(long orderId);
}
