package @@PACKAGE@@.interfaces.http.order;

import cn.dev33.satoken.annotation.SaCheckPermission;
import @@PACKAGE@@.application.command.order.AttachOrderFile;
import @@PACKAGE@@.application.command.order.OrderPermissions;
import @@PACKAGE@@.application.usecase.order.ReadOrderFiles;
import org.domainforge.files.api.FileResponses;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 订单凭证入口：上传与下载都经本服务转发，权限码由注解检查，订单的数据范围由用例核对；存储不对外暴露。
 *
 * <p>上传接口的路径以 {@code /files} 结尾：入口 Nginx 只对这类路径放宽请求体上限（见 deploy/nginx/locations.inc）。
 */
@RestController
@PlatformJsonApi
public class OrderFileController {
    private final AttachOrderFile writes;
    private final ReadOrderFiles reads;

    /**
     * 只依赖应用用例。
     */
    public OrderFileController(AttachOrderFile writes, ReadOrderFiles reads) {
        this.writes = writes;
        this.reads = reads;
    }

    /**
     * 上传凭证（multipart 字段 {@code file}）。
     */
    @SaCheckPermission(OrderPermissions.WRITE)
    @PostMapping("/api/orders/{id}/files")
    public OrderFileResponse upload(@PathVariable long id, @RequestPart("file") MultipartFile file)
            throws IOException {
        try (InputStream content = file.getInputStream()) {
            return OrderFileResponse.of(
                    writes.attach(id, file.getOriginalFilename(), file.getContentType(), content));
        }
    }

    /**
     * 凭证信息列表。
     */
    @SaCheckPermission(OrderPermissions.READ)
    @GetMapping("/api/orders/{id}/files")
    public List<OrderFileResponse> list(@PathVariable long id) {
        return reads.list(id).stream().map(OrderFileResponse::of).toList();
    }

    /**
     * 下载凭证：作为附件下载返回，禁止浏览器嗅探与缓存。
     */
    @SaCheckPermission(OrderPermissions.READ)
    @GetMapping("/api/orders/{id}/files/{fileId}")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id, @PathVariable String fileId) {
        return FileResponses.attachment(reads.open(id, fileId));
    }

    /**
     * 删除凭证。
     */
    @SaCheckPermission(OrderPermissions.WRITE)
    @DeleteMapping("/api/orders/{id}/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @PathVariable String fileId) {
        writes.detach(id, fileId);
    }
}
