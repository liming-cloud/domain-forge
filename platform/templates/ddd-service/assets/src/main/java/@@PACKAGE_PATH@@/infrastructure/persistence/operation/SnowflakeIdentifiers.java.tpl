package @@PACKAGE@@.infrastructure.persistence.operation;

import @@PACKAGE@@.application.port.operation.Identifiers;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 雪花 ID；多实例部署须为每个实例配置不同的 worker-id／datacenter-id。
 */
@Component
public class SnowflakeIdentifiers implements Identifiers {
    @Override
    public long next() {
        return IdWorker.getId();
    }
}
