package @@PACKAGE@@.application.query.note;

import java.util.List;

/**
 * 便笺查询合同，实现直接使用本服务 SQL 投影；按 ID 升序键分页。
 */
public interface NoteQueries {
    /**
     * 本人创建的便笺；只需登录，是不经过编排的纯查询。
     */
    List<NoteView> owned(long ownerId, long after, int limit);

    /**
     * 按可信范围过滤的便笺。
     */
    List<NoteView> visible(NoteFilter filter);
}
