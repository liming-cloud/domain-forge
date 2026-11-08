package @@PACKAGE@@.infrastructure.persistence.order;

import @@PACKAGE@@.application.port.attachment.OrderAttachments;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis 订单凭证关联。
 */
@Repository
public class SqlOrderFiles implements OrderAttachments {
    private final OrderFileMapper mapper;

    /**
     * 绑定凭证关联 Mapper。
     */
    public SqlOrderFiles(OrderFileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int count(long orderId) {
        return mapper.count(orderId);
    }

    @Override
    public void add(long orderId, String fileId) {
        mapper.insert(orderId, fileId);
    }

    @Override
    public boolean remove(long orderId, String fileId) {
        return mapper.delete(orderId, fileId) == 1;
    }

    @Override
    public boolean contains(long orderId, String fileId) {
        return mapper.exists(orderId, fileId) > 0;
    }

    @Override
    public List<String> list(long orderId) {
        return mapper.list(orderId);
    }
}
