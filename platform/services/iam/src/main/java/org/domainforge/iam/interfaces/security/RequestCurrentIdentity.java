package org.domainforge.iam.interfaces.security;

import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** 公共主体校验之后取得IAM私有快照，业务命令不依赖框架子类。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RequestCurrentIdentity implements CurrentIdentityAccess {
  private final CurrentAccess access;
  private final StpLogic logic;
  private final IamCurrentSubjectSource source;

  /** 公开解析与私有取回分开接线，避免事实源与CurrentAccess循环依赖。 */
  public RequestCurrentIdentity(
      CurrentAccess access, StpLogic logic, IamCurrentSubjectSource source) {
    this.access = access;
    this.logic = logic;
    this.source = source;
  }

  @Override
  public CurrentIdentity current() {
    var subject = access.current();
    CurrentIdentity identity = source.privateIdentity(logic.getTokenValue());
    if (!subject.accountId().equals(identity.accountId()))
      throw new AccessException(AccessFailure.UNAUTHENTICATED);
    return identity;
  }

  @Override
  public String token() {
    current();
    return logic.getTokenValue();
  }
}
