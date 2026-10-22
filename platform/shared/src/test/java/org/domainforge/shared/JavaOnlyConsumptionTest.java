package org.domainforge.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 独立消费只给 JDK 与组件类路径，防止标识接口悄悄绑定框架或统一实体基类。 */
final class JavaOnlyConsumptionTest {
  @TempDir Path directory;

  /** 普通类、具备自有基类的实体和 record 值对象都能在无框架类路径下消费。 */
  @Test
  void compilesWithoutFrameworkOrSharedBaseClass() throws Exception {
    Path source = directory.resolve("Consumer.java");
    Files.writeString(
        source,
        """
        import org.domainforge.shared.AggregateRoot;
        import org.domainforge.shared.Entity;
        import org.domainforge.shared.ValueObject;
        class Consumer {
          static class OwnBase {}
          static class Order extends OwnBase implements AggregateRoot {}
          record Amount(int cents) implements ValueObject {}
          Entity root() { return new Order(); }
          ValueObject amount() { return new Amount(100); }
        }
        """);
    String component =
        Path.of(AggregateRoot.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    int result =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-proc:none",
                "-classpath",
                component,
                "-d",
                directory.toString(),
                source.toString());
    assertEquals(0, result);
  }
}
