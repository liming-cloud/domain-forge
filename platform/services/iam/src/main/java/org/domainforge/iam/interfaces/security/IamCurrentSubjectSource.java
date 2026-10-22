package org.domainforge.iam.interfaces.security;

import cn.dev33.satoken.context.SaHolder;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentSubject;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** IAM提供本地完整事实，公共组件拥有请求复用和原生鉴权执行。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IamCurrentSubjectSource implements CurrentSubjectSource {
  private static final String PRIVATE_IDENTITY = IamCurrentSubjectSource.class.getName();
  private final CurrentAuthorization authorization;
  private final String projectId;

  /** 项目由部署配置固定；本地模式不创建HTTP客户端或读取远程IAM资源。 */
  public IamCurrentSubjectSource(
      CurrentAuthorization authorization, @Value("${iam.project-id}") String projectId) {
    if (projectId == null || !projectId.matches("[a-z][a-z0-9-]{2,63}"))
      throw new IllegalArgumentException("项目标识无效");
    this.authorization = authorization;
    this.projectId = projectId;
  }

  /** 同一次SQL读取同时产生公开主体和IAM私有凭据快照，不在读取路径补建许可。 */
  @Override
  public CurrentSubject resolve(String token) {
    var storage = SaHolder.getStorage();
    storage.delete(PRIVATE_IDENTITY);
    try {
      CurrentIdentity identity = authorization.current(token);
      CurrentSubject subject =
          new CurrentSubject(projectId, identity.accountId(), identity.permissions());
      storage.set(PRIVATE_IDENTITY, new PrivateIdentity(token, identity));
      return subject;
    } catch (IamException failure) {
      throw new AccessException(
          switch (failure.failure()) {
            case UNAUTHENTICATED -> AccessFailure.UNAUTHENTICATED;
            case FORBIDDEN -> AccessFailure.FORBIDDEN;
            default -> AccessFailure.UNAVAILABLE;
          });
    } catch (RuntimeException failure) {
      throw new AccessException(AccessFailure.UNAVAILABLE);
    }
  }

  /** 仅同一请求、同一原生令牌可取回；不存在时拒绝，不能回落到旧身份或再次查库。 */
  CurrentIdentity privateIdentity(String token) {
    Object value = SaHolder.getStorage().get(PRIVATE_IDENTITY);
    if (value instanceof PrivateIdentity snapshot && snapshot.token().equals(token))
      return snapshot.identity();
    throw new AccessException(AccessFailure.UNAUTHENTICATED);
  }

  /** 原生令牌与私有事实只在当前请求内关联，默认字符串不暴露凭证。 */
  private record PrivateIdentity(String token, CurrentIdentity identity) {
    @Override
    public String toString() {
      return "PrivateIdentity[accountId=" + identity.accountId() + "]";
    }
  }
}
