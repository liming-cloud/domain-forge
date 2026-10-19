import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.output.ConsoleRender;

/** 隔离运行成熟 OpenAPI 校验与兼容库，不向应用类路径注入门禁依赖。 */
public final class HttpValidation {
  /** 校验器仅提供命令入口。 */
  private HttpValidation() {}

  /**
   * 核对当前实际交互和独立已发布合同，输出原生问题供统一入口分类。
   *
   * @param args 当前合同、已发布合同、实际交互和结果路径
   * @throws Exception 合同无法加载或执行失败时终止
   */
  public static void main(String[] args) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var validator =
        OpenApiInteractionValidator.createForInlineApiSpecification(
                Files.readString(Path.of(args[0])))
            .build();
    List<Map<String, Object>> messages = new ArrayList<>();
    for (JsonNode item : mapper.readTree(Path.of(args[2]).toFile())) {
      validate(validator, item, messages);
    }
    var diff = OpenApiCompare.fromLocations(args[1], args[0]);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("messages", messages);
    result.put("compatible", diff.isCompatible());
    result.put("changed", diff.isDifferent());
    ByteArrayOutputStream rendered = new ByteArrayOutputStream();
    try (OutputStreamWriter writer = new OutputStreamWriter(rendered, StandardCharsets.UTF_8)) {
      new ConsoleRender().render(diff, writer);
    }
    result.put("details", rendered.toString(StandardCharsets.UTF_8));
    mapper.writeValue(Path.of(args[3]).toFile(), result);
  }

  /**
   * 以采集的原始字节和头验证，不用当前类型重新构造一个预期响应。
   *
   * @param validator 当前有效 OpenAPI 验证器
   * @param item 已观察交互
   * @param messages 原生问题
   */
  private static void validate(
      OpenApiInteractionValidator validator, JsonNode item, List<Map<String, Object>> messages) {
    JsonNode input = item.get("request");
    URI uri = URI.create(input.get("path").asText());
    var request = new SimpleRequest.Builder(input.get("method").asText(), uri.getPath());
    queryParameters(uri).forEach(request::withQueryParam);
    input
        .get("headers")
        .fields()
        .forEachRemaining(e -> request.withHeader(e.getKey(), e.getValue().asText()));
    if (!input.get("body").isNull()) {
      request.withBody(input.get("body").asText());
    }
    var response = SimpleResponse.Builder.status(item.get("status").asInt());
    item.get("headers")
        .fields()
        .forEachRemaining(
            e -> {
              List<String> values = new ArrayList<>();
              e.getValue().forEach(value -> values.add(value.asText()));
              response.withHeader(e.getKey(), values);
            });
    response.withBody(Base64.getDecoder().decode(item.get("body_base64").asText()));
    if (item.get("valid_request").asBoolean()) {
      messages(messages, item, validator.validateRequest(request.build()));
    }
    messages(
        messages,
        item,
        validator.validateResponse(
            uri.getPath(), Request.Method.valueOf(input.get("method").asText()), response.build()));
  }

  /**
   * 查询字段独立于路径交给成熟验证器，保留重复值及标准 URL 解码。
   *
   * @param uri 实际请求 URI
   * @return 已解码的多值查询字段
   */
  private static Map<String, List<String>> queryParameters(URI uri) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    if (uri.getRawQuery() != null) {
      for (String pair : uri.getRawQuery().split("&")) {
        String[] parts = pair.split("=", 2);
        String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
        result.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
      }
    }
    return result;
  }

  /**
   * 保留库的级别与键，警告不伪装为完成的语义证明。
   *
   * @param output 全部原生结果
   * @param item 交互标识
   * @param report 成熟库的实际报告
   */
  private static void messages(
      List<Map<String, Object>> output, JsonNode item, ValidationReport report) {
    for (var message : report.getMessages()) {
      output.add(
          Map.of(
              "scenario", item.get("scenario").asText(),
              "level", message.getLevel().name(),
              "key", message.getKey(),
              "message", message.getMessage()));
    }
  }
}
