import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 真实条件装配和 Nacos 刷新的隔离对象，不交付平台运行模块。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(RuntimeFixture.RemoteConfiguration.class)
@ImportAutoConfiguration(RuntimeDefaults.class)
public class RuntimeFixture {
  /** 只暴露校准消费需要的读取和可用状态。 */
  public interface Connector {
    /** 缺少必要目标时明确失败，不返回演示成功。 */
    String read();

    /** 返回当前能力的可用事实。 */
    boolean available();
  }

  /** 必需目标与明确实现选择独立于进程存活。 */
  public static class DefaultConnector implements Connector {
    private final String target;
    private final String implementation;

    /** 缺失输入在启动装配时成为不可用状态。 */
    public DefaultConnector(String target, String implementation) {
      this.target = target;
      this.implementation = implementation;
    }

    /** 调用不可用能力时拒绝，不静默换用默认实现。 */
    @Override
    public String read() {
      if (!available()) {
        throw new IllegalStateException("capability-unavailable");
      }
      return target;
    }

    /** 核对必需目标与受支持实现。 */
    @Override
    public boolean available() {
      return !target.isBlank() && implementation.equals("default");
    }
  }

  /** 消费方通过公开类型只替换所需组件。 */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "probe.override", havingValue = "true")
  public static class OverrideConfiguration {
    /** 有效用户实现替换默认组件，不影响无关能力。 */
    @Bean
    public Connector customConnector() {
      return new DefaultConnector("override-target", "default");
    }
  }

  /** 两个同类型用户组件必须明确冲突，不能按注册顺序选取。 */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "probe.conflict", havingValue = "true")
  public static class ConflictConfiguration {
    /** 第二实现只用于冲突反例。 */
    @Bean
    public Connector secondConnector() {
      return new DefaultConnector("second", "default");
    }
  }

  /** Nacos 内容正确，应用依赖故障由独立故障注入控制。 */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "probe.remote", havingValue = "true")
  public static class RemoteConfiguration {
    /** 标准刷新作用域在实际调用时提供新的能力实例。 */
    @Bean
    @RefreshScope
    public RemoteValue remoteValue(
        @Value("${probe.value}") String value, @Value("${probe.failure-file}") String failureFile) {
      if (Files.exists(Path.of(failureFile))) {
        throw new IllegalStateException("application-update-unavailable");
      }
      return new RemoteValue(value);
    }
  }

  /** 实际应用对象保留已应用值，区别收到通知与真正使用。 */
  public static class RemoteValue {
    private final String value;

    /** 初始化来自框架当前有效配置的值。 */
    public RemoteValue(String value) {
      this.value = value;
    }

    /** 返回实际实例使用的配置。 */
    public String read() {
      return value;
    }
  }
}

/** 自动装配遵守用户替换条件，未启用时不要求输入。 */
@AutoConfiguration
class RuntimeDefaults {
  /** 无关能力用于核对局部错误的影响范围。 */
  @Bean
  public String unaffected() {
    return "independent-ready";
  }

  /** 仅对启用且选择受支持实现的能力装配默认值。 */
  @Bean
  @ConditionalOnMissingBean(RuntimeFixture.Connector.class)
  @ConditionalOnProperty(name = "probe.enabled", havingValue = "true")
  public RuntimeFixture.Connector connector(
      @Value("${probe.target:}") String target,
      @Value("${probe.implementation:default}") String implementation) {
    return new RuntimeFixture.DefaultConnector(target, implementation);
  }
}
