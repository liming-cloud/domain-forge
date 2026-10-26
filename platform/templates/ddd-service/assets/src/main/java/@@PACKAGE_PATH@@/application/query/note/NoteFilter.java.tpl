package @@PACKAGE@@.application.query.note;

import @@PACKAGE@@.application.command.note.NoteRejected;

import java.util.Set;

/**
 * 范围受限的列表条件：范围来自可信服务端（IAM），不是客户端输入。
 *
 * @param account 当前账号
 * @param all     ALL 范围
 * @param self    本人范围
 * @param units   可见组织
 * @param after   键分页游标
 * @param limit   1 至 100
 */
public record NoteFilter(long account, boolean all, boolean self, Set<Long> units, long after, int limit) {
    /**
     * 复制组织集合并校验分页。
     */
    public NoteFilter {
        units = Set.copyOf(units);
        if (after < 0 || limit < 1 || limit > 100) throw new NoteRejected("NOTE_INVALID", "分页游标须非负且大小须为1至100");
    }
}
