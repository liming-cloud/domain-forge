package org.domainforge.access.backend.internal.remote;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.backend.internal.execution.RequestCurrentSubject;

/** 远程模式只桥接当前身份读取；不对外承诺签发、退出或任意会话查询。 */
public final class BackendStpLogic extends StpLogic {
  private final RequestCurrentSubject subjects;

  /** 事实共享由公共请求协作承担；构造器不替换StpUtil全局逻辑。 */
  public BackendStpLogic(RequestCurrentSubject subjects) {
    super("login");
    this.subjects = subjects;
  }

  @Override
  public Object getLoginId() {
    return subjects.current(getTokenValue()).accountId().toString();
  }

  @Override
  public Object getLoginIdDefaultNull() {
    try {
      return getLoginId();
    } catch (NotLoginException failure) {
      return null;
    }
  }
}
