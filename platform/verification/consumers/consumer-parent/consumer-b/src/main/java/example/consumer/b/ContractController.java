package example.consumer.b;

import java.util.List;
import org.domainforge.web.api.ApiResponse;
import org.domainforge.web.api.HttpFailureResponses;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 显式接入的合成 JSON 场景，不执行领域操作或认证判断。 */
@PlatformJsonApi
@RestController
public class ContractController {
  /**
   * 合成成功数据。
   *
   * @return 合成成功数据
   */
  @GetMapping("/success")
  public ApiResponse<String> success() {
    return ApiResponse.success("synthetic");
  }

  /**
   * 空列表仍表示成功。
   *
   * @return 空列表仍表示成功
   */
  @GetMapping("/empty")
  public ApiResponse<List<String>> empty() {
    return ApiResponse.success(List.of());
  }

  /**
   * 明确无数据的成功。
   *
   * @return 明确无数据的成功
   */
  @GetMapping("/null")
  public ApiResponse<Void> absent() {
    return ApiResponse.success(null);
  }

  /**
   * 仅受理的 HTTP 202，不代表任务完成。
   *
   * @return 仅受理的 HTTP 202，不代表任务完成
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "明确合同状态")
  @GetMapping("/accepted")
  public org.springframework.http.ResponseEntity<ApiResponse<Void>> accepted() {
    return ApiResponse.accepted(null);
  }

  /**
   * 合成拒绝；B 通过其自有异常扩展映射。
   *
   * @return 合成拒绝；B 通过其自有异常扩展映射
   */
  @GetMapping("/rejected")
  public ApiResponse<Void> rejected() {
    throw new ConsumerRejection("synthetic-secret");
  }

  /**
   * 验证非法 JSON 由平台处理；合法请求只回显合成输入。
   *
   * @param body 请求数据
   * @return 请求数据
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "请求不合法",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "请求有效")
  @PostMapping("/parse")
  public ApiResponse<List<String>> parse(@RequestBody List<String> body) {
    return ApiResponse.success(body);
  }

  /**
   * 验证参数转换失败。
   *
   * @param value 必需整数
   * @return 合成整数
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "请求不合法",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "请求有效")
  @GetMapping("/parameter")
  public ApiResponse<Integer> parameter(@RequestParam("value") int value) {
    return ApiResponse.success(value);
  }

  /**
   * HTTP 401 结果；本场景没有真实认证链。
   *
   * @return HTTP 401 结果；本场景没有真实认证链
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "明确合同状态")
  @GetMapping("/unauthenticated")
  public org.springframework.http.ResponseEntity<ApiResponse<Void>> unauthenticated() {
    return HttpFailureResponses.unauthenticated();
  }

  /**
   * HTTP 403 结果；本场景没有真实授权链。
   *
   * @return HTTP 403 结果；本场景没有真实授权链
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "明确合同状态")
  @GetMapping("/forbidden")
  public org.springframework.http.ResponseEntity<ApiResponse<Void>> forbidden() {
    return HttpFailureResponses.forbidden();
  }

  /**
   * 显式服务不可用。
   *
   * @return 显式服务不可用
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "明确合同状态")
  @GetMapping("/unavailable")
  public org.springframework.http.ResponseEntity<ApiResponse<Void>> unavailable() {
    return HttpFailureResponses.unavailable();
  }

  /**
   * 本方法总是失败，用于验证固定安全提示。
   *
   * @return 本方法总是失败，用于验证固定安全提示
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "明确合同状态")
  @GetMapping("/unknown")
  public ApiResponse<Void> unknown() {
    throw new IllegalStateException("synthetic-secret");
  }

  /**
   * 注入扩展错误以验证固定兜底，不允许借扩展把故障报告为成功。
   *
   * @param mode 明确的合成错误种类
   * @return 本方法总是抛出由扩展处理的异常
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "扩展非法")
  @GetMapping("/mapping")
  public ApiResponse<Void> mapping(@RequestParam("mode") String mode) {
    throw new ConsumerRejection(mode);
  }
}
