package org.domainforge.iam.application.port;

/** 只携带可公开的稳定分类与说明；不将框架异常消息转交客户端。 */
public final class IamException extends RuntimeException {
  private final IamFailure failure;
  private final long retryAfterSeconds;

  /** 建立应用拒绝或不可确定的结果，消息必须由服务端固定提供。 */
  public IamException(IamFailure failure, String message) {
    this(failure, message, failure == IamFailure.RATE_LIMITED ? 1 : 0);
  }

  /** 限流出口提供实际窗口等待建议；其他失败不携带重试秒数。 */
  public IamException(IamFailure failure, String message, long retryAfterSeconds) {
    super(message);
    if (failure == IamFailure.RATE_LIMITED
        ? retryAfterSeconds < 1 || retryAfterSeconds > 3600
        : retryAfterSeconds != 0) throw new IllegalArgumentException("重试秒数不符合失败分类");
    this.failure = failure;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** 返回稳定失败分类。 */
  public IamFailure failure() {
    return failure;
  }

  /** 返回秒数上取整的限流建议；非限流为零。 */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
