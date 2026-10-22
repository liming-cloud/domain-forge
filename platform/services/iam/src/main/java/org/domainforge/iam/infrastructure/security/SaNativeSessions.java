package org.domainforge.iam.infrastructure.security;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.slf4j.LoggerFactory;

/** 会话签发和验证交给Sa-Token原生Redis实现，不自行解析或签发令牌协议。 */
public final class SaNativeSessions implements NativeSessions {
  private final StpLogic nativeLogic;
  private final Counter cleanupFailures;

  /** 使用公共组件装配的同一普通原生逻辑，不创建第二个同名实例。 */
  public SaNativeSessions(StpLogic nativeLogic, MeterRegistry metrics) {
    this.nativeLogic = nativeLogic;
    this.cleanupFailures = metrics.counter("iam.session.cleanup.failures");
  }

  /** createLoginSession不提前把令牌写入响应，SQL提交前客户端拿不到有效登录结果。 */
  @Override
  public String issue(UUID accountId) {
    try {
      return nativeLogic.createLoginSession(
          accountId.toString(), new SaLoginParameter().setTimeout(28800).setIsShare(false));
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  /** 原生状态无效返回null，Redis故障与认证失败不混淆。 */
  @Override
  public UUID subject(String token) {
    try {
      Object id = nativeLogic.getLoginIdByToken(token);
      return id == null ? null : UUID.fromString(id.toString());
    } catch (IllegalArgumentException e) {
      return null;
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  @Override
  public void revoke(String token) {
    try {
      nativeLogic.logoutByTokenValue(token);
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  /** 清理故障独立计数，绝不把令牌或原始框架异常写入日志。 */
  @Override
  public void cleanup(String token) {
    try {
      revoke(token);
    } catch (RuntimeException failure) {
      cleanupFailures.increment();
      LoggerFactory.getLogger("iam.session.cleanup").warn("native_session_cleanup_failed");
    }
  }

  private static IamException unavailable() {
    return new IamException(IamFailure.UNAVAILABLE, "会话存储暂不可用");
  }
}
