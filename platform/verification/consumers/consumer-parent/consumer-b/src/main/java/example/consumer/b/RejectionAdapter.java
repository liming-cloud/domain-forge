package example.consumer.b;

import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;
import org.springframework.stereotype.Component;

/** B 的入口适配，显式把自有拒绝转换为安全稳定的 HTTP 业务结果。 */
@Component
public class RejectionAdapter implements BusinessRejectionMapping<ConsumerRejection> {
  /**
   * B 自有的确切异常类，不处理其子类或 cause。
   *
   * @return B 自有的确切异常类，不处理其子类或 cause
   */
  @Override
  public Class<ConsumerRejection> exceptionType() {
    return ConsumerRejection.class;
  }

  /**
   * 忽略内部消息，固定面向消费者的安全提示。
   *
   * @param exception 已匹配的自有异常
   * @return 稳定业务拒绝
   */
  @Override
  public ApiResponse<Void> map(ConsumerRejection exception) {
    return switch (exception.getMessage()) {
      case "throw" -> throw new IllegalStateException("synthetic-secret");
      case "invalid" -> new ApiResponse<>("SUCCESS", "非法映射", null);
      case "null" -> null;
      default -> ApiResponse.rejected("DECLINED", "合成拒绝");
    };
  }
}
