package org.domainforge.web.api;

import org.domainforge.shared.ApiResponse;

/**
 * 消费方入口适配层的具体拒绝映射；不要求领域异常继承平台类型。
 *
 * @param <E> 已判定的具体异常类，仅按确切运行类型匹配
 */
public interface BusinessRejectionMapping<E extends Exception> {
  /**
   * 声明确切类型；通用异常基类或重复类型在启动时拒绝。
   *
   * @return 非空具体异常类
   */
  Class<E> exceptionType();

  /**
   * 转为安全业务拒绝；抛错、空结果或非法结果由组件按未知故障处理。
   *
   * @param exception 已匹配的异常
   * @return 非保留代码、无数据的业务拒绝
   */
  ApiResponse<Void> map(E exception);
}
