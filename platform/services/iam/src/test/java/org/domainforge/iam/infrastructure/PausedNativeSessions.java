package org.domainforge.iam.infrastructure;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.domainforge.iam.application.port.authentication.NativeSessions;

/** 测试只暂停真实Redis签发之后的时点，不模拟会话或数据库结果。 */
final class PausedNativeSessions implements NativeSessions {
  private final NativeSessions delegate;
  final CountDownLatch issued = new CountDownLatch(1);
  final CountDownLatch resume = new CountDownLatch(1);
  String token;

  PausedNativeSessions(NativeSessions delegate) {
    this.delegate = delegate;
  }

  @Override
  public String issue(UUID id) {
    token = delegate.issue(id);
    issued.countDown();
    try {
      if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放屏障");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
    return token;
  }

  @Override
  public UUID subject(String token) {
    return delegate.subject(token);
  }

  @Override
  public void revoke(String token) {
    delegate.revoke(token);
  }

  @Override
  public void cleanup(String token) {
    delegate.cleanup(token);
  }
}
