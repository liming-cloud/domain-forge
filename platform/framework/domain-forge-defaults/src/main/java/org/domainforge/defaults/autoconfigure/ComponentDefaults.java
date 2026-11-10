package org.domainforge.defaults.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载各组件的固定 YAML（{@code META-INF/domain-forge/*-defaults.yml}），作为服务配置可覆盖的最低优先级默认值。
 *
 * <p>并入 Boot 的 {@code defaultProperties}：应用配置加载后它总被移到末尾，因此可以提前执行（配置组件的默认值须在 Nacos
 * 的导入检查之前就位），仍不覆盖服务 YAML、profile、环境变量或启动参数。每项配置只有一个归属，两个组件声明同一项属于打包错误。
 */
public final class ComponentDefaults implements EnvironmentPostProcessor, Ordered {
    private static final String LOCATION = "classpath*:META-INF/domain-forge/*-defaults.yml";

    /**
     * 按文件名顺序读取，已在 {@code defaultProperties} 中的项（如 {@code SpringApplication.setDefaultProperties}）保留原值。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var values = new HashMap<String, Object>();
        var owners = new HashMap<String, String>();
        for (var resource : resources()) {
            for (var source : load(resource)) {
                for (var name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                    var owner = owners.putIfAbsent(name, resource.getFilename());
                    if (owner != null)
                        throw new IllegalStateException(
                                "组件默认配置重复声明 " + name + "：" + owner + "、" + resource.getFilename());
                    values.put(name, source.getProperty(name));
                }
            }
        }
        var existing = environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME);
        if (existing != null && existing.getSource() instanceof Map<?, ?> map)
            values.keySet().removeAll(map.keySet());
        DefaultPropertiesPropertySource.addOrMerge(values, environment.getPropertySources());
    }

    /**
     * 在 Nacos 客户端默认值之后、应用配置加载之前执行。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private static Resource[] resources() {
        try {
            var resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));
            return resources;
        } catch (IOException error) {
            throw new IllegalStateException("组件默认配置无法定位", error);
        }
    }

    private static List<PropertySource<?>> load(Resource resource) {
        try {
            return new YamlPropertySourceLoader().load(resource.getFilename(), resource);
        } catch (IOException error) {
            throw new IllegalStateException("组件默认配置无法加载：" + resource.getFilename(), error);
        }
    }
}
