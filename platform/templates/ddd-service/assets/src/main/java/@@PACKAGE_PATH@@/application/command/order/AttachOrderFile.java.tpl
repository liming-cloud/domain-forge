package @@PACKAGE@@.application.command.order;

import @@PACKAGE@@.application.order.OrderAccess;
import @@PACKAGE@@.application.order.OrderPermissions;
import @@PACKAGE@@.application.order.OrderRejected;
import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.application.port.attachment.OrderAttachments;
import @@PACKAGE@@.domain.model.order.Order;
import @@PACKAGE@@.domain.repository.OrderRepository;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.FileUpload;
import org.domainforge.files.api.StoredFile;
import org.domainforge.files.api.UploadPolicy;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

/**
 * 订单凭证命令：先按写范围核对订单，再经文件存储组件保存或删除文件，文件信息与凭证关联在同一事务中提交。
 *
 * <p>上传内容在进入用例前已由 Servlet 容器完整接收；保存文件发生在事务内。时限依次为：S3 调用 10 秒 &lt;
 * 本事务 12 秒 &lt; 网关下游响应 15 秒，保证超时由服务先判定并回滚，而不是网关返回 504 后服务仍然提交。
 *
 * <p>这里锁定订单是版本条件更新的例外：凭证数上限是“先计数再插入”，只有按订单串行化才能保证并发上传不超限。
 */
@Service
public class AttachOrderFile {
    /**
     * 一张订单最多的凭证数。
     */
    public static final int MAX_FILES = 10;
    /**
     * 凭证允许的大小与类型。
     */
    public static final UploadPolicy POLICY = UploadPolicy.of(10L * 1024 * 1024,
            "text/plain", "application/pdf", "image/png", "image/jpeg");
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final OrderRepository orders;
    private final OrderAttachments attachments;
    private final FileStore files;

    /**
     * 只依赖访问组件、文件存储组件的公开接口、本服务端口与仓储。
     */
    public AttachOrderFile(CurrentAccess access, DataScopes scopes, OrderRepository orders,
                          OrderAttachments attachments, FileStore files) {
        this.access = access;
        this.scopes = scopes;
        this.orders = orders;
        this.attachments = attachments;
        this.files = files;
    }

    /**
     * 添加凭证：范围外的订单按不存在处理；凭证数达到上限为业务拒绝；文件不符合策略由组件拒绝（{@code FILE_*}）。
     */
    @Transactional(timeout = 12)
    public StoredFile attach(long orderId, String name, String contentType, InputStream content) {
        long account = access.current().accountId();
        Order order = writable(account, orderId);
        if (attachments.count(order.id()) >= MAX_FILES)
            throw OrderRejected.unprocessable("ORDER_FILES_FULL", "每张订单最多 " + MAX_FILES + " 个凭证");
        StoredFile stored = files.store(new FileUpload(name, contentType, content, account), POLICY);
        attachments.add(order.id(), stored.id());
        return stored;
    }

    /**
     * 删除凭证：解除关联并删除文件；文件不是该订单的凭证时按不存在处理。
     */
    @Transactional
    public void detach(long orderId, String fileId) {
        Order order = writable(access.current().accountId(), orderId);
        if (!attachments.remove(order.id(), fileId))
            throw OrderRejected.notFound("ORDER_FILE_NOT_FOUND", "凭证不存在");
        files.delete(fileId);
    }

    private Order writable(long account, long orderId) {
        CurrentScope scope = scopes.scope(OrderPermissions.WRITE);
        Order order = orders.lock(orderId);
        if (order == null || !OrderAccess.within(order, account, scope))
            throw OrderRejected.notFound("ORDER_NOT_FOUND", "订单不存在");
        return order;
    }
}
