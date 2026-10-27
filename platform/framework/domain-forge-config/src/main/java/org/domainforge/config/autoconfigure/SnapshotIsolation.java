package org.domainforge.config.autoconfigure;

import com.alibaba.nacos.client.config.utils.SnapShotSwitch;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 关闭 Nacos 客户端的本地快照：否则配置中心不可达时客户端会读取上次的快照，让启动以旧值成功，
 * 违背“启动时连不上 Nacos 即失败”。须在加载 Nacos 配置之前执行，因此优先级最高。
 */
public final class SnapshotIsolation implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SnapShotSwitch.setIsSnapShot(false);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
