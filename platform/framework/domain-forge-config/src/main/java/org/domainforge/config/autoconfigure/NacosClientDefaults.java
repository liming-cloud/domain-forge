package org.domainforge.config.autoconfigure;

import com.alibaba.nacos.client.config.utils.SnapShotSwitch;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 在加载 Nacos 配置之前调整 Nacos 客户端的两项进程级默认行为，因此优先级最高：
 *
 * <ul>
 *   <li>关闭本地快照：否则配置中心不可达时客户端读取上次的快照，让启动以旧值成功，违背“启动时连不上 Nacos 即失败”。</li>
 *   <li>不使用客户端自带的日志配置：它向 {@code ${user.home}/logs/nacos} 写文件，容器中的服务用户没有主目录，
 *       创建失败会让 Spring Boot 的日志初始化报错退出；关闭后客户端日志随应用日志输出到标准输出。</li>
 * </ul>
 */
public final class NacosClientDefaults implements EnvironmentPostProcessor, Ordered {
    static final String CLIENT_LOGGING = "nacos.logging.default.config.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 必须先设置：访问 SnapShotSwitch 会触发 Nacos 客户端的静态初始化，其中就包括加载日志配置。
        if (System.getProperty(CLIENT_LOGGING) == null) System.setProperty(CLIENT_LOGGING, "false");
        SnapShotSwitch.setIsSnapShot(false);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
