package org.domainforge.defaults.autoconfigure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentDefaultsTest {
    // 经真实启动流程：服务配置与启动参数覆盖组件默认值，未覆盖的项保留，应用自设的 defaultProperties 不被改写。
    @Test
    void serviceConfigurationOverridesComponentDefaults() {
        var application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("demo.by-application", "application"));
        try (ConfigurableApplicationContext context = application.run("--demo.by-argument=argument")) {
            var environment = context.getEnvironment();
            assertEquals("component", environment.getProperty("demo.kept"));
            assertEquals("service", environment.getProperty("demo.by-service"));
            assertEquals("argument", environment.getProperty("demo.by-argument"));
            assertEquals("application", environment.getProperty("demo.by-application"));
        }
    }

    // 两个组件声明同一项时启动失败，不依赖类路径顺序决定取值。
    @Test
    void duplicateKeyAcrossComponentsFails(@TempDir Path root) throws IOException {
        var file = root.resolve("META-INF/domain-forge/other-defaults.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "demo:\n  kept: other\n");
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, original)) {
            thread.setContextClassLoader(loader);
            var error = assertThrows(IllegalStateException.class,
                    () -> new ComponentDefaults().postProcessEnvironment(new StandardEnvironment(), null));
            assertEquals("组件默认配置重复声明 demo.kept：demo-defaults.yml、other-defaults.yml", error.getMessage());
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    static class Empty {
    }
}
