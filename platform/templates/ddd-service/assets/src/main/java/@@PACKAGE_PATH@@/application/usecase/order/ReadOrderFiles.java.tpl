package @@PACKAGE@@.application.usecase.order;

import @@PACKAGE@@.application.command.order.OrderAccess;
import @@PACKAGE@@.application.command.order.OrderPermissions;
import @@PACKAGE@@.application.command.order.OrderRejected;
import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.application.port.attachment.OrderAttachments;
import @@PACKAGE@@.domain.model.order.Order;
import @@PACKAGE@@.domain.repository.OrderRepository;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.files.api.FileContent;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.StoredFile;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 读取凭证的查询用例：需要加载订单后按读范围作资源授权，因此不走纯查询短路径。范围外的订单按不存在处理。
 */
@Service
public class ReadOrderFiles {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final OrderRepository orders;
    private final OrderAttachments attachments;
    private final FileStore files;

    /**
     * 只依赖访问组件、文件存储组件的公开接口、本服务端口与仓储。
     */
    public ReadOrderFiles(CurrentAccess access, DataScopes scopes, OrderRepository orders,
                         OrderAttachments attachments, FileStore files) {
        this.access = access;
        this.scopes = scopes;
        this.orders = orders;
        this.attachments = attachments;
        this.files = files;
    }

    /**
     * 订单的凭证信息。
     */
    public List<StoredFile> list(long orderId) {
        Order order = readable(orderId);
        return attachments.list(order.id()).stream().map(files::find).flatMap(Optional::stream).toList();
    }

    /**
     * 打开凭证内容；调用方负责关闭。
     */
    public FileContent open(long orderId, String fileId) {
        Order order = readable(orderId);
        if (!attachments.contains(order.id(), fileId))
            throw OrderRejected.notFound("ORDER_FILE_NOT_FOUND", "凭证不存在");
        return files.open(fileId).orElseThrow(() -> OrderRejected.notFound("ORDER_FILE_NOT_FOUND", "凭证不存在"));
    }

    private Order readable(long orderId) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(OrderPermissions.READ);
        Order order = orders.find(orderId);
        if (order == null || !OrderAccess.within(order, account, scope))
            throw OrderRejected.notFound("ORDER_NOT_FOUND", "订单不存在");
        return order;
    }
}
