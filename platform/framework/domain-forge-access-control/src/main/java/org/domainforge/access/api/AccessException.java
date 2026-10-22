package org.domainforge.access.api;

/** 明确的访问失败分类，错误消息不携带传输细节或秘密。 */
public final class AccessException extends RuntimeException {
  private final AccessFailure failure;

  /** 外部依赖错误不能伪装成无身份，也不能返回匿名允许。 */
  public AccessException(AccessFailure failure) {
    super(
        switch (failure) {
          case UNAUTHENTICATED -> "需要有效登录";
          case FORBIDDEN -> "无权执行该操作";
          case UNAVAILABLE -> "身份服务暂不可用";
        });
    this.failure = failure;
  }

  /** 返回稳定分类，消费者按自己的协议映射。 */
  public AccessFailure failure() {
    return failure;
  }
}
