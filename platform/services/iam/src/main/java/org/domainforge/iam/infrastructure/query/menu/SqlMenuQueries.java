package org.domainforge.iam.infrastructure.query.menu;

import org.domainforge.iam.application.query.menu.MenuQueries;
import org.domainforge.iam.application.query.menu.MenuView;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 菜单查询实现；可见投影在一次读取的全量结果上按树计算。
 */
@Repository
public class SqlMenuQueries implements MenuQueries {
    private final MenuQueryMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlMenuQueries(MenuQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MenuView> all() {
        return mapper.all();
    }

    /**
     * 三步：自上而下求“本项及祖先均启用”，自下而上求候选（持有关联权限，或目录有候选下级），
     * 再自上而下剔除父节点不可见的候选。
     */
    @Override
    public List<MenuView> visible(Set<String> permissions) {
        List<MenuView> items = mapper.all();
        Set<Long> candidates = candidates(items, reachable(items), permissions);
        Set<Long> shown = new HashSet<>();
        for (MenuView item : items) {
            if (candidates.contains(item.id()) && (item.parentId() == null || shown.contains(item.parentId())))
                shown.add(item.id());
        }
        return items.stream().filter(item -> shown.contains(item.id())).toList();
    }

    private static Set<Long> reachable(List<MenuView> items) {
        Set<Long> reachable = new HashSet<>();
        for (MenuView item : items) {
            if (item.enabled() && (item.parentId() == null || reachable.contains(item.parentId())))
                reachable.add(item.id());
        }
        return reachable;
    }

    private static Set<Long> candidates(List<MenuView> items, Set<Long> reachable, Set<String> permissions) {
        Set<Long> candidates = new HashSet<>();
        Set<Long> withCandidateChild = new HashSet<>();
        for (MenuView item : items.reversed()) {
            boolean held = item.permission() != null && permissions.contains(item.permission());
            boolean directory = "DIRECTORY".equals(item.type()) && withCandidateChild.contains(item.id());
            if (reachable.contains(item.id()) && (held || directory)) {
                candidates.add(item.id());
                if (item.parentId() != null) withCandidateChild.add(item.parentId());
            }
        }
        return candidates;
    }
}
