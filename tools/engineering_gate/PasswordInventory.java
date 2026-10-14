import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.w3c.dom.Element;

/** 观察实际密码 Bean 并用独立类路径的成熟实现校验合成输入；不提供登录能力。 */
public final class PasswordInventory {
  /** 密码观察器仅提供固定校准命令。 */
  private PasswordInventory() {}

  /**
   * 在实际应用或隔离工具类路径执行固定密码校准，不接收生产密码。
   *
   * @param args 固定模式、应用及 Bean 或私有观察文件、输出文件
   * @throws Exception 真实调用或私有结果保存失败时终止
   */
  public static void main(String[] args) throws Exception {
    if (args[0].equals("observe")) {
      observe(args[1], args[2], Path.of(args[3]));
    } else if (args[0].equals("verify")) {
      verify(Path.of(args[1]), Path.of(args[2]));
    } else {
      throw new IllegalArgumentException("不支持的固定模式");
    }
  }

  /**
   * 取得应用自己的密码 Bean，只记录固定合成场景，不替应用提供正确实现。
   *
   * @param application 实际生产应用类
   * @param bean 唯一被测密码 Bean 名称
   * @param file 仓库外私有结果
   * @throws Exception 应用启动或调用失败时终止
   */
  private static void observe(String application, String bean, Path file) throws Exception {
    try (ConfigurableApplicationContext context = HttpInventory.startApplication(application);
        var stream = Files.newOutputStream(file)) {
      var output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("password-observation");
      PasswordEncoder encoder = context.getBean(bean, PasswordEncoder.class);
      output.writeAttribute("bean-type", encoder.getClass().getName());
      for (String id : List.of("ascii", "unicode", "long", "nul")) {
        sample(encoder, id, output);
      }
      output.writeEndElement();
      output.close();
    }
  }

  /**
   * 实际生成两次哈希并测试正确与错误输入，避免仅观察方法调用次数。
   *
   * @param encoder 实际应用 Bean
   * @param id 固定场景标识
   * @param output 私有观察结果
   * @throws Exception 真实哈希、校验或保存失败时终止
   */
  private static void sample(PasswordEncoder encoder, String id, XMLStreamWriter output)
      throws Exception {
    String raw = password(id);
    output.writeStartElement("sample");
    output.writeAttribute("id", id);
    output.writeAttribute(
        "raw", Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    output.writeAttribute(
        "variant",
        Base64.getEncoder().encodeToString(variant(id).getBytes(StandardCharsets.UTF_8)));
    for (int index = 0; index < 2; index++) {
      String hash = encoder.encode(raw);
      output.writeStartElement("encoded");
      output.writeAttribute("same", String.valueOf(encoder.matches(raw, hash)));
      output.writeAttribute("wrong", String.valueOf(encoder.matches(raw + "X", hash)));
      output.writeAttribute("variant", String.valueOf(encoder.matches(variant(id), hash)));
      output.writeCharacters(
          Base64.getEncoder()
              .encodeToString((hash == null ? "" : hash).getBytes(StandardCharsets.UTF_8)));
      output.writeEndElement();
    }
    output.writeEndElement();
  }

  /**
   * 返回非生产的固定输入，含 Unicode、超过 72 字节及零字节，不归一化密码。
   *
   * @param id 场景标识
   * @return 合成密码
   */
  private static String password(String id) {
    return switch (id) {
      case "ascii" -> "Synthetic-Aa-Password!42";
      case "unicode" -> "合成-口令🙂-Z9";
      case "long" -> "L".repeat(80) + "A";
      case "nul" -> "Synthetic\0password-A";
      default -> throw new IllegalArgumentException("未知合成场景");
    };
  }

  /**
   * 提供末尾或大小写变化的独立错误输入，识别截断及大小写归一化。
   *
   * @param id 场景标识
   * @return 与正确密码不同的输入
   */
  private static String variant(String id) {
    return switch (id) {
      case "ascii" -> "synthetic-Aa-Password!42";
      case "unicode" -> "合成-口令🙂-z9";
      case "long" -> "L".repeat(80) + "B";
      case "nul" -> "Synthetic\0password-B";
      default -> throw new IllegalArgumentException("未知合成场景");
    };
  }

  /**
   * 在不含应用类的独立工具 JVM 中校验实际哈希，避免伪实现自己证明自己。
   *
   * @param observed 私有观察文件
   * @param file 不包含哈希或密码的原生验证结果
   * @throws Exception 原生校验或保存失败时终止
   */
  private static void verify(Path observed, Path file) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var document = factory.newDocumentBuilder().parse(observed.toFile());
    PasswordEncoder nativeEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    try (var stream = Files.newOutputStream(file)) {
      var output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("native-verification");
      var samples = document.getElementsByTagName("sample");
      for (int index = 0; index < samples.getLength(); index++) {
        nativeSample(nativeEncoder, (Element) samples.item(index), output);
      }
      output.writeEndElement();
      output.close();
    }
  }

  /**
   * 独立调用成熟校验器，输出布尔结果而非密码、哈希或异常原文。
   *
   * @param encoder 工具自身固定的成熟校验器
   * @param sample 实际应用观察
   * @param output 非敏感验证结果
   * @throws Exception 原生校验或保存失败时终止
   */
  private static void nativeSample(PasswordEncoder encoder, Element sample, XMLStreamWriter output)
      throws Exception {
    String id = sample.getAttribute("id");
    output.writeStartElement("sample");
    output.writeAttribute("id", id);
    var hashes = sample.getElementsByTagName("encoded");
    for (int index = 0; index < hashes.getLength(); index++) {
      String hash =
          new String(
              Base64.getDecoder().decode(hashes.item(index).getTextContent()),
              StandardCharsets.UTF_8);
      output.writeStartElement("verified");
      output.writeAttribute("same", String.valueOf(encoder.matches(password(id), hash)));
      output.writeAttribute("wrong", String.valueOf(encoder.matches(password(id) + "X", hash)));
      output.writeAttribute("variant", String.valueOf(encoder.matches(variant(id), hash)));
      output.writeEndElement();
    }
    output.writeEndElement();
  }
}
