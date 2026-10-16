package example.consumer.b;

/** B 项目自己的具体拒绝异常，不继承平台业务异常。 */
public class ConsumerRejection extends RuntimeException {
  /**
   * 内部诊断不会直接成为响应提示。
   *
   * @param detail 内部诊断
   */
  public ConsumerRejection(String detail) {
    super(detail);
  }
}
