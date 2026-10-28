package @@PACKAGE@@.infrastructure.acl.peer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.domainforge.shared.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * @@PEER@@ 的便笺公开接口；url 为空时经 Nacos 发现名为 @@PEER@@ 的服务。
 *
 * <p>用户令牌、Same-Token 与请求标识由跨服务调用组件自动附加，这里不声明。
 */
@FeignClient(name = "@@PEER@@", url = "${@@ENV@@_PEER_URL:}", contextId = "peerNotes")
public interface PeerNoteClient {
    /**
     * 当前用户可见的便笺。
     */
    @GetMapping("/api/notes")
    ApiResponse<List<Note>> visible();

    /**
     * 对方便笺表示中本服务用到的字段，其余字段忽略。
     *
     * @param id    便笺 ID
     * @param title 标题
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Note(String id, String title) {
    }
}
