package example.consumer.a;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 未接入的平台外协议验证，返回值不得被自动包装。 */
@RestController
public class OutsideController {
  /**
   * 原始文本。
   *
   * @return 原始文本
   */
  @GetMapping(value = "/plain", produces = "text/plain")
  public String plain() {
    return "plain";
  }

  /**
   * 原始二进制字节。
   *
   * @return 原始二进制字节
   */
  @GetMapping(value = "/binary", produces = "application/octet-stream")
  public byte[] binary() {
    return new byte[] {0, 1, 2, -1};
  }

  /**
   * 宿主管理生命周期的原始流。
   *
   * @return 宿主管理生命周期的原始流
   */
  @GetMapping(value = "/stream", produces = "application/octet-stream")
  public org.springframework.http.ResponseEntity<StreamingResponseBody> stream() {
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .body(output -> output.write(new byte[] {3, 4, 5}));
  }
}
