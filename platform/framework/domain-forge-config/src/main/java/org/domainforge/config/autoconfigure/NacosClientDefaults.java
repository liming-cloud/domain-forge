package org.domainforge.config.autoconfigure;

import com.alibaba.nacos.client.config.utils.SnapShotSwitch;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.Map;

/**
 * 在加载 Nacos 配置之前确定 Nacos 客户端的进程级行为，因此优先级最高：
 *
 * <ul>
 *   <li>不使用客户端自带的日志配置：它向 {@code ${user.home}/logs/nacos} 写文件，容器中的服务用户没有主目录，
 *       创建失败会让 Spring Boot 的日志初始化报错退出；关闭后客户端日志随应用日志输出到标准输出。</li>
 *   <li>只在 {@code config-center} profile 启用时开启 Nacos 配置客户端（组件默认关闭）。否则不使用配置中心的应用
 *       也会以默认地址 127.0.0.1:8848 建立客户端并反复重连；而且该客户端是进程级单例，同一进程中之后启用配置中心的
 *       应用会沿用它的地址（集成测试顺序不同时实测读到空配置）。开关须在此判断：Nacos 的位置解析器在服务
 *       profile 文档中的属性生效之前就检查它。</li>
 *   <li>关闭本地快照：否则配置中心不可达时客户端读取上次的快照，让启动以旧值成功，违背“启动时连不上 Nacos 即失败”。</li>
 * </ul>
 */
public final class NacosClientDefaults implements EnvironmentPostProcessor, Ordered {
    static final String PROFILE = "config-center";
    static final String CLIENT_LOGGING = "nacos.logging.default.config.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 必须先设置：访问 SnapShotSwitch 会触发 Nacos 客户端的静态初始化，其中就包括加载日志配置。
        if (System.getProperty(CLIENT_LOGGING) == null) System.setProperty(CLIENT_LOGGING, "false");
        if (active(environment)) {
            environment.getPropertySources().addLast(new MapPropertySource(
                    "domain-forge-config-center", Map.of("spring.cloud.nacos.config.enabled", "true")));
        }
        SnapShotSwitch.setIsSnapShot(false);
    }

    private static boolean active(ConfigurableEnvironment environment) {
        String profiles = environment.getProperty("spring.profiles.active", "");
        return Arrays.stream(profiles.split(",")).map(String::trim).anyMatch(PROFILE::equals)
                || Arrays.asList(environment.getActiveProfiles()).contains(PROFILE);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
