import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 只观察实际 Sa-Token 配置与真实 HTTP，不替应用设置令牌读取或修补鉴权。 */
public final class SaAccessInventory {
  /** 观察器不提供可实例化的身份能力。 */
  private SaAccessInventory() {}

  /**
   * 与 HTTP 合同使用同一应用、类路径和请求，采集实际配置后按原生生命周期释放 DAO。
   *
   * @param args 应用类、独立请求、输出及可选的工具负例探针参数
   * @throws Exception 真实应用或采集失败时终止
   */
  public static void main(String[] args) throws Exception {
    try (ConfigurableApplicationContext context = HttpInventory.startApplication(args[0]);
        var stream = Files.newOutputStream(Path.of(args[2]))) {
      var output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("http-observation");
      var config = StpUtil.getStpLogic().getConfigOrGlobal();
      output.writeAttribute("sa-token-name", String.valueOf(config.getTokenName()));
      output.writeAttribute("sa-token-prefix", String.valueOf(config.getTokenPrefix()));
      output.writeAttribute("sa-read-header", String.valueOf(config.getIsReadHeader()));
      output.writeAttribute("sa-read-body", String.valueOf(config.getIsReadBody()));
      output.writeAttribute("sa-read-cookie", String.valueOf(config.getIsReadCookie()));
      output.writeAttribute("sa-dao", SaManager.getSaTokenDao().getClass().getName());
      HttpInventory.observe(context, Path.of(args[1]), output);
      policies(context, output);
      if (args.length == 6) {
        defaultDeny(context, args, output);
      }
      output.writeEndElement();
      output.close();
    } finally {
      SaManager.getSaTokenDao().destroy();
    }
  }

  /**
   * 原注册和合同采集后临时加入无声明探针，验证应用真实默认拒绝而不修补它。
   *
   * @param context 实际应用上下文
   * @param args 固定工具探针类型、私有请求及唯一相对路径
   * @param output 只用于限定默认拒绝的独立观察
   * @throws Exception 注册、真实请求或清理失败时终止
   */
  private static void defaultDeny(
      ConfigurableApplicationContext context, String[] args, XMLStreamWriter output)
      throws Exception {
    var mapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    var info =
        RequestMappingInfo.paths(args[5])
            .methods(RequestMethod.GET)
            .options(mapping.getBuilderConfiguration())
            .build();
    if (mapping.getHandlerMethods().containsKey(info)) {
      throw new IllegalStateException("工具探针路径冲突");
    }
    Class<?> type = Class.forName(args[3]);
    var method = type.getMethod("unassigned");
    mapping.registerMapping(info, type.getConstructor().newInstance(), method);
    try {
      output.writeStartElement("default-deny-probe");
      output.writeAttribute("path", args[5]);
      output.writeAttribute("owner", type.getName());
      output.writeAttribute("handler", method.toGenericString());
      HttpInventory.observeRequests(context, Path.of(args[4]), output);
      output.writeEndElement();
    } finally {
      mapping.unregisterMapping(info);
    }
  }

  /**
   * 从同一真实映射取得策略来源，不从场景清单反推需要扫描的处理器。
   *
   * @param context 实际应用上下文
   * @param output 实际策略观察
   * @throws Exception 原生解析或输出失败时终止
   */
  private static void policies(ConfigurableApplicationContext context, XMLStreamWriter output)
      throws Exception {
    var handlers = new TreeMap<String, HandlerMethod>();
    for (var mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      for (var handler : mapping.getHandlerMethods().values()) {
        handlers.put(handler.getBeanType().getName() + ":" + handler.getMethod(), handler);
      }
    }
    for (var handler : handlers.values()) {
      policy(handler, output);
    }
  }

  /**
   * 使用应用实际采用的原生解析策略；忽略声明遵循框架优先语义，不注入解析器。
   *
   * @param handler 实际业务或框架处理器
   * @param output 原生声明观察
   * @throws Exception 实际解析或输出失败时终止
   */
  private static void policy(HandlerMethod handler, XMLStreamWriter output) throws Exception {
    var method = handler.getMethod();
    output.writeStartElement("access-policy");
    output.writeAttribute("owner", handler.getBeanType().getName());
    output.writeAttribute("handler", method.toGenericString());
    boolean anonymous =
        SaAnnotationStrategy.instance.isAnnotationPresent.apply(method, SaIgnore.class);
    output.writeAttribute("anonymous", String.valueOf(anonymous));
    if (!anonymous) {
      for (AnnotatedElement element : List.of(method.getDeclaringClass(), method)) {
        conditions(element, output);
      }
    }
    output.writeEndElement();
  }

  /**
   * 保留类与方法的共同条件及实际处理器；未知注解不默认为仅需登录。
   *
   * @param element 框架实际采用的声明元素
   * @param output 条件输出
   * @throws Exception 原生解析或输出失败时终止
   */
  private static void conditions(AnnotatedElement element, XMLStreamWriter output)
      throws Exception {
    var strategy = SaAnnotationStrategy.instance;
    if (strategy.getAnnotation.apply(element, SaCheckLogin.class) instanceof SaCheckLogin login) {
      condition(output, "login", login.type(), SaCheckLogin.class);
      output.writeEndElement();
    }
    if (strategy.getAnnotation.apply(element, SaCheckPermission.class)
        instanceof SaCheckPermission permission) {
      condition(output, "permission", permission.type(), SaCheckPermission.class);
      output.writeAttribute("mode", permission.mode().name());
      values(output, "value", permission.value());
      values(output, "or-role", permission.orRole());
      output.writeEndElement();
    }
    for (Class<?> kind : strategy.annotationHandlerMap.keySet()) {
      if (!Set.of(SaCheckLogin.class, SaCheckPermission.class).contains(kind)
          && strategy.getAnnotation.apply(element, kind.asSubclass(Annotation.class)) != null) {
        output.writeEmptyElement("unsupported-policy");
      }
    }
  }

  /**
   * 观察真正注册的注解处理器，不以注解仍存在掩盖处理器被移除或替换。
   *
   * @param output 条件输出
   * @param kind 条件类别
   * @param type 原生账号体系标识
   * @param annotation 原生声明类型
   * @throws Exception 输出失败时终止
   */
  private static void condition(
      XMLStreamWriter output, String kind, String type, Class<?> annotation) throws Exception {
    output.writeStartElement("condition");
    output.writeAttribute("kind", kind);
    output.writeAttribute("type", type);
    Object handler = SaAnnotationStrategy.instance.annotationHandlerMap.get(annotation);
    output.writeAttribute("handler", handler == null ? "" : handler.getClass().getName());
  }

  /**
   * 保存框架实际声明值，仅供私有校准比较，不把令牌或会话放进策略观察。
   *
   * @param output 条件输出
   * @param name 原生字段类别
   * @param values 实际声明值
   * @throws Exception 输出失败时终止
   */
  private static void values(XMLStreamWriter output, String name, String[] values)
      throws Exception {
    for (String value : values) {
      output.writeEmptyElement(name);
      output.writeAttribute("code", value);
    }
  }
}
