package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import java.util.List;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentSubject;

/** 原生权限和角色数据扩展点；只提供本请求已完整验证的同一主体。 */
public final class CurrentSubjectPermissions implements StpInterface {
  private final CurrentAccess current;
  private final StpLogic logic;

  /** 复用公共请求快照；不查询其他账号或复制原生AND／OR判断。 */
  public CurrentSubjectPermissions(CurrentAccess current, StpLogic logic) {
    this.current = current;
    this.logic = logic;
  }

  @Override
  public List<String> getPermissionList(Object loginId, String loginType) {
    return List.copyOf(subject(loginId, loginType).permissions());
  }

  @Override
  public List<String> getRoleList(Object loginId, String loginType) {
    return List.copyOf(subject(loginId, loginType).roles());
  }

  /** 原生参数不能被用于借本请求身份查询其他主体或登录类型。 */
  private CurrentSubject subject(Object loginId, String loginType) {
    CurrentSubject subject = current.current();
    if (!logic.getLoginType().equals(loginType)
        || !subject.accountId().toString().equals(String.valueOf(loginId))
        || !subject.accountId().toString().equals(String.valueOf(nativeLoginId())))
      throw new AccessException(AccessFailure.FORBIDDEN);
    return subject;
  }

  /** 程序式提供者调用也拥有同一原生读取边界，不依赖HTTP拦截器包住其执行。 */
  private Object nativeLoginId() {
    try {
      return logic.getLoginId();
    } catch (NotLoginException failure) {
      var rejection = new NotLoginException("需要有效登录", failure.getLoginType(), failure.getType());
      rejection.setCode(failure.getCode());
      throw rejection;
    } catch (AccessException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AccessException(AccessFailure.UNAVAILABLE);
    }
  }
}
