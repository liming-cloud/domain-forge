package org.domainforge.iam.interfaces.http.menu;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.query.menu.MenuView;
import org.domainforge.iam.interfaces.http.protocol.Ids;

/**
 * 菜单项的 HTTP 表示；按父节点在前、同级排序值顺序返回，由前端组装树。
 */
public record MenuResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        String parentId,
        String type,
        String code,
        String name,
        String path,
        int sortOrder,
        String permission,
        boolean enabled,
        long version) {
    static MenuResponse of(MenuView view) {
        return new MenuResponse(
                view.id(), Ids.string(view.parentId()), view.type(), view.code(), view.name(), view.path(),
                view.sortOrder(), view.permission(), view.enabled(), view.version());
    }
}
