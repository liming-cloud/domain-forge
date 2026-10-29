package @@PACKAGE@@.domain.model.note;

import org.domainforge.shared.AggregateRoot;

import java.util.Set;

/**
 * 示例聚合：一条便笺属于创建者，可选归属一个组织；数据范围决定谁能读写。
 *
 * <p>范围事实由应用从 IAM 取得后以纯值传入，领域不依赖 IAM 或传输类型。
 */
public record Note(long id, long ownerId, Long unitId, String title, String content, long version)
        implements AggregateRoot {
    /**
     * 一条便笺最多的附件数。
     */
    public static final int MAX_FILES = 10;

    /**
     * 恢复状态也校验标题、内容与版本。
     */
    public Note {
        if (id <= 0 || ownerId <= 0 || version < 1 || (unitId != null && unitId <= 0))
            throw new IllegalArgumentException("便笺身份、归属或版本无效");
        title = requireTitle(title);
        content = requireContent(content);
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank() || title.strip().length() > 100)
            throw new IllegalArgumentException("标题须为1至100个字符");
        return title.strip();
    }

    private static String requireContent(String content) {
        if (content == null || content.length() > 2000) throw new IllegalArgumentException("内容最多2000个字符");
        return content;
    }

    /**
     * 创建者在给定组织（或不归属组织）下新建便笺。
     */
    public static Note create(long id, long ownerId, Long unitId, String title, String content) {
        return new Note(id, ownerId, unitId, title, content, 1);
    }

    /**
     * 范围规则：ALL 可见全部；SELF 可见本人创建的；组织集合可见归属其中组织的。
     */
    public boolean within(long account, boolean all, boolean self, Set<Long> units) {
        return all || (self && ownerId == account) || (unitId != null && units.contains(unitId));
    }

    /**
     * 新建规则：不归属组织的便笺需要 ALL 或本人范围；归属组织时该组织须在范围内（本人范围不能扩展到任意组织）。
     */
    public static boolean creatable(Long unitId, boolean all, boolean self, Set<Long> units) {
        return all || (unitId == null ? self : units.contains(unitId));
    }

    /**
     * 附件规则：已有附件少于上限时才能再添加。
     */
    public static boolean attachable(int attached) {
        return attached < MAX_FILES;
    }

    /**
     * 修改标题与内容；未变化时返回自身，不提升版本。
     */
    public Note edit(String nextTitle, String nextContent) {
        Note changed = new Note(id, ownerId, unitId, nextTitle, nextContent, version + 1);
        return changed.title.equals(title) && changed.content.equals(content) ? this : changed;
    }
}
