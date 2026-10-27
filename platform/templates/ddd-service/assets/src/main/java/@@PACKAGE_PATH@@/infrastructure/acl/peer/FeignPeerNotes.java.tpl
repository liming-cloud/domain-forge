package @@PACKAGE@@.infrastructure.acl.peer;

import @@PACKAGE@@.application.port.peer.PeerNote;
import @@PACKAGE@@.application.port.peer.PeerNotes;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.servicecall.api.ServiceCallException;
import org.domainforge.servicecall.api.ServiceCalls;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 经 Feign 读取 @@PEER@@ 的便笺，并把调用失败翻译为本服务的访问失败。
 */
@Component
public class FeignPeerNotes implements PeerNotes {
    private final PeerNoteClient client;

    /**
     * 只依赖本服务声明的客户端。
     */
    public FeignPeerNotes(PeerNoteClient client) {
        this.client = client;
    }

    @Override
    public List<PeerNote> visible() {
        try {
            List<PeerNoteClient.Note> notes = ServiceCalls.data(client.visible());
            if (notes == null) throw new AccessException(AccessFailure.UNAVAILABLE);
            return notes.stream().map(note -> new PeerNote(note.id(), note.title())).toList();
        } catch (ServiceCallException failure) {
            throw new AccessException(translate(failure));
        }
    }

    /**
     * 对方的 401、403 保持语义；不可用、结果未知、拒绝一律为依赖不可用，不当作空列表。
     */
    private static AccessFailure translate(ServiceCallException failure) {
        return switch (failure.failure()) {
            case UNAUTHENTICATED -> AccessFailure.UNAUTHENTICATED;
            case FORBIDDEN -> AccessFailure.FORBIDDEN;
            default -> AccessFailure.UNAVAILABLE;
        };
    }
}
