package @@PACKAGE@@.application.query.digest;

import @@PACKAGE@@.application.port.peer.PeerNote;
import @@PACKAGE@@.application.port.peer.PeerNotes;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 跨服务调用示例：汇总当前用户在 @@PEER@@ 中可见的便笺。只读，不跨服务写入。
 */
@Service
public class NoteDigestQueries {
    private static final int TITLES = 10;

    private final PeerNotes peers;

    /**
     * 经应用端口调用，不依赖 HTTP 客户端类型。
     */
    public NoteDigestQueries(PeerNotes peers) {
        this.peers = peers;
    }

    /**
     * 当前用户的便笺摘要；对方的失败原样上抛，不返回部分结果。
     */
    public NoteDigest digest() {
        List<PeerNote> notes = peers.visible();
        return new NoteDigest(notes.size(), notes.stream().limit(TITLES).map(PeerNote::title).toList());
    }
}
