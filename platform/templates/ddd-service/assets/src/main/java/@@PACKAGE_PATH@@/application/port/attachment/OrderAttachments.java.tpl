package @@PACKAGE@@.application.port.attachment;

import java.util.List;

/**
 * 订单与凭证文件的关联；文件本身由文件存储组件保存，这里只记录文件 ID。
 */
public interface OrderAttachments {
    /**
     * 订单当前的凭证数。
     */
    int count(long orderId);

    void add(long orderId, String fileId);

    /**
     * 解除关联，零行返回 false。
     */
    boolean remove(long orderId, String fileId);

    /**
     * 文件是否是该订单的凭证。
     */
    boolean contains(long orderId, String fileId);

    /**
     * 订单的凭证文件 ID，按添加顺序。
     */
    List<String> list(long orderId);
}
