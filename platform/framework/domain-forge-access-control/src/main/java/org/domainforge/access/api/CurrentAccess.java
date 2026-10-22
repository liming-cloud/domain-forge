package org.domainforge.access.api;

import org.domainforge.shared.CurrentSubject;

/** 消费后端读取当前请求的可信主体；资源归属仍由消费上下文判断。 */
public interface CurrentAccess {
  /** 必须在已装配的请求范围内调用，缺少身份、拒绝或依赖故障分别报告，不回退匿名成功。 */
  CurrentSubject current();
}
