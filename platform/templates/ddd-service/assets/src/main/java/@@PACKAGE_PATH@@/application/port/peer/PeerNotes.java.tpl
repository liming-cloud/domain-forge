package @@PACKAGE@@.application.port.peer;

import java.util.List;

/**
 * 读取 @@PEER@@ 中当前用户可见的便笺。以当前用户身份调用：对方按自己的权限与数据范围判定，本服务不放宽。
 *
 * <p>失败以访问组件的 {@code AccessException} 报告：对方判定未认证、无权限分别保持 401、403 语义，
 * 其他失败（不可用、超时、拒绝）为依赖不可用，不当作空列表。
 */
public interface PeerNotes {
    /**
     * 当前用户可见的便笺，顺序与对方一致。
     */
    List<PeerNote> visible();
}
