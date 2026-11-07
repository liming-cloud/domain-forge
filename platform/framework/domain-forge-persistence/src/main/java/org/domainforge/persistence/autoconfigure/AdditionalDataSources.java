package org.domainforge.persistence.autoconfigure;

import org.domainforge.persistence.api.AdditionalDataSource;
import org.domainforge.persistence.internal.PooledAdditionalDataSource;
import org.springframework.core.env.Environment;

/**
 * 附加数据源的创建入口，由服务的 bootstrap 调用。
 */
public final class AdditionalDataSources {
    private AdditionalDataSources() {
    }

    /**
     * 读取配置、建立连接池、执行迁移并组装事务与会话；任一步失败即关闭连接池并抛出。
     *
     * @param name        数据源名称：小写字母开头，字母与数字，2—31 位；不能是 {@code dataSource}
     * @param environment 服务环境
     * @return 已迁移的附加数据源
     */
    public static AdditionalDataSource create(String name, Environment environment) {
        return PooledAdditionalDataSource.create(name, environment);
    }
}
