package org.domainforge.access.api;

import org.domainforge.shared.CurrentSubject;

/** 当前令牌的完整可信事实源；本地与远程应用复用同一访问执行链。 */
@FunctionalInterface
public interface CurrentSubjectSource {
  /** 校验当前令牌及本应用要求的安全事实；无身份、拒绝和依赖故障使用公共失败分类。 */
  CurrentSubject resolve(String token);
}
