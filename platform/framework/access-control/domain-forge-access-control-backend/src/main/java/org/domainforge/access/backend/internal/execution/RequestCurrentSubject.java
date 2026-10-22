package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentSubject;
import org.domainforge.access.api.CurrentSubjectSource;

/** 仅按本请求实际令牌复用完整主体，不建立跨请求允许缓存。 */
public final class RequestCurrentSubject {
  private static final String KEY = RequestCurrentSubject.class.getName();
  private final CurrentSubjectSource source;

  /** 唯一事实源由应用显式选择，不在本地与远程之间降级。 */
  public RequestCurrentSubject(CurrentSubjectSource source) {
    this.source = source;
  }

  /** 令牌变化重新读取；无身份转换为原生异常，让可选原生查询返回空或false。 */
  public CurrentSubject current(String token) {
    var storage = SaHolder.getStorage();
    if (token == null || token.isBlank()) {
      storage.delete(KEY);
      throw missing();
    }
    Object value = storage.get(KEY);
    if (value instanceof Snapshot cached && cached.token().equals(token)) return cached.subject();
    storage.delete(KEY);
    try {
      CurrentSubject subject = source.resolve(token);
      if (subject == null) throw new AccessException(AccessFailure.UNAVAILABLE);
      storage.set(KEY, new Snapshot(token, subject));
      return subject;
    } catch (AccessException failure) {
      if (failure.failure() == AccessFailure.UNAUTHENTICATED) throw missing();
      throw failure;
    }
  }

  private static NotLoginException missing() {
    return new NotLoginException("需要有效登录", "login", NotLoginException.INVALID_TOKEN);
  }

  /** 请求内凭证与完整主体的不可变绑定，不暴露给业务或写入日志。 */
  private record Snapshot(String token, CurrentSubject subject) {
    @Override
    public String toString() {
      return "Snapshot[token=***]";
    }
  }
}
