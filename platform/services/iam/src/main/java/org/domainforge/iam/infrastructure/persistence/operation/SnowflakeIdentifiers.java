package org.domainforge.iam.infrastructure.persistence.operation;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.springframework.stereotype.Component;

/**
 * 使用 MyBatis-Plus 的雪花 ID 生成器；worker 与数据中心标识由
 * mybatis-plus.global-config.worker-id／datacenter-id 配置，多实例部署须各不相同。
 */
@Component
public class SnowflakeIdentifiers implements Identifiers {
    /**
     * 返回全局默认生成器的下一个 ID。
     */
    @Override
    public long next() {
        return IdWorker.getId();
    }
}
