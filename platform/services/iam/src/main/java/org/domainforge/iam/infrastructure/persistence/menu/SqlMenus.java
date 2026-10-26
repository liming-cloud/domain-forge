package org.domainforge.iam.infrastructure.persistence.menu;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.menu.MenuItem;
import org.domainforge.iam.domain.repository.MenuRepository;
import org.domainforge.iam.infrastructure.persistence.operation.Coordination;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis 菜单仓储。
 */
@Repository
public class SqlMenus implements MenuRepository {
    private final MenuMapper mapper;
    private final Coordination coordination;

    /**
     * 绑定菜单 Mapper 与协调锁。
     */
    public SqlMenus(MenuMapper mapper, Coordination coordination) {
        this.mapper = mapper;
        this.coordination = coordination;
    }

    @Override
    public void lockStructure() {
        coordination.lock("menu", true);
    }

    @Override
    public MenuItem lock(long id) {
        MenuRow row = mapper.lock(id);
        return row == null ? null : row.toDomain();
    }

    @Override
    public List<Long> ancestors(long id) {
        return mapper.ancestors(id);
    }

    @Override
    public boolean codeTaken(String code) {
        return mapper.codes(code) > 0;
    }

    @Override
    public void insert(MenuItem item) {
        mapper.insert(MenuRow.from(item));
    }

    @Override
    public void update(MenuItem item, long expected) {
        if (mapper.update(MenuRow.from(item), expected) != 1)
            throw new IamException(IamFailure.CONFLICT, "菜单版本已变化");
    }

    @Override
    public void delete(long id) {
        mapper.delete(id);
    }

    @Override
    public boolean hasChildren(long id) {
        return mapper.children(id) > 0;
    }
}
