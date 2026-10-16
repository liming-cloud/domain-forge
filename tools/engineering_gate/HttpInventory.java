import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.SpringVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.ServletContextResource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.w3c.dom.Element;

/** 在隔离应用的真实 Servlet 注册表及回环 HTTP 中观察合同，不从注解文本推定入口。 */
public final class HttpInventory {
  /** 观察器不提供可实例化的业务能力。 */
  private HttpInventory() {}

  /**
   * 启动明确指定的隔离应用，采集后关闭实际 Web 容器。
   *
   * @param args 应用类、独立场景 XML 和输出路径
   * @throws Exception 启动、请求或采集失败时终止
   */
  public static void main(String[] args) throws Exception {
    try (ConfigurableApplicationContext context = startApplication(args[0]);
        var stream = Files.newOutputStream(Path.of(args[2]))) {
      XMLStreamWriter output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("http-observation");
      observe(context, Path.of(args[1]), output);
      output.writeEndElement();
      output.close();
    }
  }

  /**
   * HTTP 与访问观察器共享相同的真实启动参数，不向应用注入安全实现。
   *
   * @param application 工程实际应用类
   * @return 调用者负责关闭的实际上下文
   * @throws ClassNotFoundException 应用类不存在时终止
   */
  static ConfigurableApplicationContext startApplication(String application)
      throws ClassNotFoundException {
    SpringApplication app = new SpringApplication(Class.forName(application));
    app.setBannerMode(Banner.Mode.OFF);
    app.addInitializers(
        context ->
            context.getBeanFactory().registerSingleton("domainForgeHttpTrace", new RequestTrace()));
    return app.run(
        "--server.address=127.0.0.1",
        "--server.port=0",
        "--springdoc.api-docs.version=OPENAPI_3_0",
        "--spring.main.register-shutdown-hook=false",
        "--logging.level.root=ERROR");
  }

  /**
   * 在同一个实际上下文采集入口及请求，访问校准不得另起模拟应用替代它。
   *
   * @param context 实际启动的应用
   * @param scenarios 独立请求文件
   * @param output 调用者管理的观察输出
   * @throws Exception 注册或请求采集失败时终止
   */
  static void observe(
      ConfigurableApplicationContext context, Path scenarios, XMLStreamWriter output)
      throws Exception {
    output.writeAttribute("boot", SpringBootVersion.getVersion());
    output.writeAttribute("spring", SpringVersion.getVersion());
    registered(context, output);
    otherMappings(context, output);
    observeRequests(context, scenarios, output);
  }

  /**
   * 在原应用链路执行有限校准请求，保留首次真实分派而不改变鉴权。
   *
   * @param context 实际应用上下文
   * @param scenarios 独立请求文件
   * @param output 请求观察
   * @throws Exception 请求或记录失败时终止
   */
  static void observeRequests(
      ConfigurableApplicationContext context, Path scenarios, XMLStreamWriter output)
      throws Exception {
    String port = context.getEnvironment().getRequiredProperty("local.server.port");
    requests("http://127.0.0.1:" + port, scenarios, output, context.getBean(RequestTrace.class));
  }

  /** 只记录 DispatcherServlet 已选处理器，不校验、放行或修补应用访问策略。 */
  private static final class RequestTrace implements WebMvcConfigurer, HandlerInterceptor, Ordered {
    private final Map<String, Map<String, String>> handlers = new ConcurrentHashMap<>();

    /**
     * 声明观察配置优先级，保证登录拒绝前也能记录已选业务处理器。
     *
     * @return 不改变鉴权的观察优先级
     */
    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * 注册无授权逻辑的观察器，保留应用原有全部拦截器。
     *
     * @param registry 实际 Servlet 拦截链注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
      registry.addInterceptor(this).order(Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * 只保存首次分派事实；错误二次分派不能覆盖原业务入口。
     *
     * @param request 实际请求
     * @param response 原响应，不修改
     * @param handler 框架实际选择的处理器
     * @return 始终继续原应用拦截链，不承担访问决定
     */
    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      String id = request.getHeader("X-Domain-Forge-Scenario");
      if (id != null && handler instanceof HandlerMethod method) {
        handlers.putIfAbsent(
            id,
            Map.of(
                "dispatched-pattern",
                String.valueOf(
                    request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)),
                "dispatched-owner",
                method.getBeanType().getName(),
                "dispatched-handler",
                method.getMethod().toGenericString()));
      }
      return true;
    }
  }

  /**
   * 其他路由不得靠不在注解映射表中绕过覆盖；当前未校准形式显式阻断。
   *
   * @param context 实际应用
   * @param output 范围缺口输出
   * @throws Exception 输出失败时终止
   */
  private static void otherMappings(ConfigurableApplicationContext context, XMLStreamWriter output)
      throws Exception {
    for (var mapping : context.getBeansOfType(HandlerMapping.class).values()) {
      if (mapping instanceof RequestMappingHandlerMapping) {
        continue;
      }
      if (mapping instanceof RouterFunctionMapping router) {
        if (router.getRouterFunction() != null) {
          unscanned(output, mapping, "functional-router");
        }
      } else if (mapping instanceof AbstractUrlHandlerMapping url) {
        urlMappings(context, output, url);
      } else if (mapping instanceof RequestMappingInfoHandlerMapping operations
          && Set.of(
                  "org.springframework.boot.webmvc.actuate.endpoint.web.AdditionalHealthEndpointPathsWebMvcHandlerMapping",
                  "org.springframework.boot.webmvc.actuate.endpoint.web.WebMvcEndpointHandlerMapping")
              .contains(mapping.getClass().getName())) {
        operations(output, operations);
      } else {
        unscanned(output, mapping, "unknown-handler-mapping");
      }
    }
  }

  /**
   * 保留精确 Boot 运维注册，调用方仍须提供独立允许范围和真实请求验证。
   *
   * @param output 原生注册记录
   * @param mapping 实际运维映射
   * @throws Exception 输出失败时终止
   */
  private static void operations(XMLStreamWriter output, RequestMappingInfoHandlerMapping mapping)
      throws Exception {
    for (var entry : mapping.getHandlerMethods().entrySet()) {
      for (String path : entry.getKey().getPatternValues()) {
        output.writeEmptyElement("operations");
        output.writeAttribute("path", path);
        output.writeAttribute("methods", entry.getKey().getMethodsCondition().toString());
        output.writeAttribute("params", entry.getKey().getParamsCondition().toString());
        output.writeAttribute("headers", entry.getKey().getHeadersCondition().toString());
        output.writeAttribute("mapping", mapping.getClass().getName());
      }
    }
  }

  /**
   * 框架静态资源单独保留协议边界，业务 URL 处理器不能混入该例外。
   *
   * @param context 用于解析实际处理器
   * @param output 范围缺口输出
   * @param mapping 实际 URL 映射
   * @throws Exception 输出失败时终止
   */
  private static void urlMappings(
      ConfigurableApplicationContext context,
      XMLStreamWriter output,
      AbstractUrlHandlerMapping mapping)
      throws Exception {
    for (var entry : mapping.getHandlerMap().entrySet()) {
      Object handler = entry.getValue();
      if (handler instanceof String name) {
        handler = context.getBean(name);
      }
      if (handler.getClass() != ResourceHttpRequestHandler.class
          || !emptyStaticMapping(entry.getKey(), (ResourceHttpRequestHandler) handler)) {
        unscanned(output, mapping, entry.getKey());
      }
    }
    if ((mapping.getDefaultHandler() != null || mapping.getRootHandler() != null)
        && !mapping.getClass().getName().startsWith("org.springframework.boot.webmvc.")) {
      unscanned(output, mapping, "default-or-root-handler");
    }
  }

  /**
   * 只识别没有内容的框架默认资源映射，自定义路径或实际资源需补协议校准。
   *
   * @param path 实际 URL 模式
   * @param handler 实际资源处理器
   * @return 是否没有额外可服务资源
   */
  private static boolean emptyStaticMapping(String path, ResourceHttpRequestHandler handler) {
    return Set.of("/**", "/webjars/**").contains(path)
        && handler.getLocations().stream().allMatch(HttpInventory::emptyDefaultLocation);
  }

  /**
   * 检查已知默认位置是否为空；其他位置不通过类型相同获得豁免。
   *
   * @param resource 实际资源位置
   * @return 是否属于空的默认资源位置
   */
  private static boolean emptyDefaultLocation(Resource resource) {
    if (resource instanceof ClassPathResource location) {
      return Set.of(
                  "META-INF/resources/webjars/",
                  "META-INF/resources/",
                  "resources/",
                  "static/",
                  "public/")
              .contains(location.getPath())
          && !location.exists();
    }
    if (resource instanceof ServletContextResource location) {
      Set<String> paths = location.getServletContext().getResourcePaths(location.getPath());
      return location.getPath().equals("/") && (paths == null || paths.isEmpty());
    }
    return false;
  }

  /**
   * 输出不受支持的实际入口，不抛弃事实或假装已经完整扫描。
   *
   * @param output 注册输出
   * @param mapping 实际映射器
   * @param reason 未校准形式
   * @throws Exception 输出失败时终止
   */
  private static void unscanned(XMLStreamWriter output, HandlerMapping mapping, String reason)
      throws Exception {
    output.writeEmptyElement("unscanned");
    output.writeAttribute("mapping", mapping.getClass().getName());
    output.writeAttribute("reason", reason);
  }

  /**
   * 采集每个映射的真实处理器、条件及来源，不默认忽略工程内未登记类型。
   *
   * @param context 已启动应用
   * @param output 实际注册记录
   * @throws Exception 输出失败时终止
   */
  private static void registered(ConfigurableApplicationContext context, XMLStreamWriter output)
      throws Exception {
    for (var mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      for (var entry : mapping.getHandlerMethods().entrySet()) {
        RequestMappingInfo info = entry.getKey();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
          methods = Set.of(RequestMethod.values());
        }
        for (String path : info.getPatternValues()) {
          for (RequestMethod method : methods) {
            handler(output, path, method, entry);
          }
        }
      }
    }
  }

  /**
   * 保留同路径不同条件的记录，不能把复杂路由悄悄合并成单一入口。
   *
   * @param output 注册输出
   * @param path 实际模式
   * @param method 实际 HTTP 方法
   * @param entry 注册表条目
   * @throws Exception 输出失败时终止
   */
  private static void handler(
      XMLStreamWriter output,
      String path,
      RequestMethod method,
      Map.Entry<RequestMappingInfo, HandlerMethod> entry)
      throws Exception {
    Class<?> owner = entry.getValue().getBeanType();
    output.writeEmptyElement("registered");
    output.writeAttribute("path", path);
    output.writeAttribute("method", method.name());
    output.writeAttribute("owner", owner.getName());
    output.writeAttribute("handler", entry.getValue().getMethod().toGenericString());
    output.writeAttribute(
        "origin", owner.getProtectionDomain().getCodeSource().getLocation().toString());
    output.writeAttribute("params", entry.getKey().getParamsCondition().toString());
    output.writeAttribute("headers", entry.getKey().getHeadersCondition().toString());
    output.writeAttribute("consumes", entry.getKey().getConsumesCondition().toString());
    output.writeAttribute("produces", entry.getKey().getProducesCondition().toString());
  }

  /**
   * 请求只使用已固定的回环地址，保留原始字节、状态和头，不跟随重定向。
   *
   * @param base 实际随机端口
   * @param scenarios 独立请求输入
   * @param output 响应输出
   * @param trace 实际分派记录
   * @throws Exception 请求或输出失败时终止
   */
  private static void requests(
      String base, Path scenarios, XMLStreamWriter output, RequestTrace trace) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var cases =
        factory.newDocumentBuilder().parse(scenarios.toFile()).getElementsByTagName("scenario");
    try (HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      for (int i = 0; i < cases.getLength(); i++) {
        Element item = (Element) cases.item(i);
        var request = HttpRequest.newBuilder(URI.create(base + item.getAttribute("path")));
        request.header("X-Domain-Forge-Scenario", item.getAttribute("id"));
        request.timeout(Duration.ofSeconds(5));
        var headers = item.getElementsByTagName("header");
        for (int j = 0; j < headers.getLength(); j++) {
          Element header = (Element) headers.item(j);
          request.header(header.getAttribute("name"), header.getAttribute("value"));
        }
        byte[] body = Base64.getDecoder().decode(item.getTextContent().trim());
        request.method(item.getAttribute("method"), HttpRequest.BodyPublishers.ofByteArray(body));
        response(
            output,
            item,
            client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()),
            trace.handlers.remove(item.getAttribute("id")));
      }
    }
  }

  /**
   * 原始响应独立于生成合同保存，交给成熟验证器核对。
   *
   * @param output 采集输出
   * @param item 请求标识
   * @param response 实际回环响应
   * @param handler 真实分派事实，未到达受支持分派时为空
   * @throws Exception 输出失败时终止
   */
  private static void response(
      XMLStreamWriter output,
      Element item,
      HttpResponse<byte[]> response,
      Map<String, String> handler)
      throws Exception {
    output.writeStartElement("response");
    output.writeAttribute("scenario", item.getAttribute("id"));
    output.writeAttribute("status", Integer.toString(response.statusCode()));
    if (handler != null) {
      for (var entry : handler.entrySet()) {
        output.writeAttribute(entry.getKey(), entry.getValue());
      }
    }
    for (var entry : response.headers().map().entrySet()) {
      for (String value : entry.getValue()) {
        output.writeEmptyElement("header");
        output.writeAttribute("name", entry.getKey());
        output.writeAttribute("value", value);
      }
    }
    output.writeCharacters(Base64.getEncoder().encodeToString(response.body()));
    output.writeEndElement();
  }
}
