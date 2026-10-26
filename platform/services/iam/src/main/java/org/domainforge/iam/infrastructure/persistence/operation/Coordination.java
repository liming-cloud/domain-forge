package org.domainforge.iam.infrastructure.persistence.operation;

import org.springframework.stereotype.Component;

/**
 * 协调锁的统一入口；缺失协调行属于部署故障。
 */
@Component
public class Coordination {
    private final CoordinationMapper mapper;

    /**
     * 绑定协调表 Mapper。
     */
    public Coordination(CoordinationMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 在当前事务内锁定，事务结束释放。
     */
    public void lock(String name, boolean exclusive) {
        String locked = exclusive ? mapper.exclusive(name) : mapper.shared(name);
        if (locked == null) throw new IllegalStateException("缺失协调锁 " + name);
    }
}
