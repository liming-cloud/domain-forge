package org.domainforge.web.internal;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;

/** 启动时固定确切异常映射，执行时验证扩展返回的拒绝语义。 */
public final class RejectionMappings {
  private final Map<Class<?>, BusinessRejectionMapping<?>> mappings = new HashMap<>();

  /**
   * 检查具体类型及重复声明，非法接入使容器启动失败。
   *
   * @param declared 消费方声明的映射
   */
  public RejectionMappings(List<BusinessRejectionMapping<?>> declared) {
    for (BusinessRejectionMapping<?> mapping : declared) {
      Class<?> type = mapping.exceptionType();
      if (type == null
          || type == Exception.class
          || type == RuntimeException.class
          || !Exception.class.isAssignableFrom(type)
          || Modifier.isAbstract(type.getModifiers())) {
        throw new IllegalArgumentException("平台拒绝映射必须声明自有具体异常类型");
      }
      if (mappings.putIfAbsent(type, mapping) != null) {
        throw new IllegalArgumentException("平台拒绝映射类型重复");
      }
    }
  }

  /**
   * 仅按原始异常的确切类匹配；未知类型不遍历 cause。
   *
   * @param failure 原始异常
   * @return 拒绝结果或无匹配的 null
   */
  public ApiResponse<Void> resolve(Exception failure) {
    BusinessRejectionMapping<?> mapping = mappings.get(failure.getClass());
    return mapping == null ? null : apply(mapping, failure);
  }

  /**
   * 保持映射声明的泛型类型，并验证结果为无数据的合法拒绝。
   *
   * @param mapping 具体映射
   * @param failure 同类异常
   * @param <E> 映射类型
   * @return 校验后的拒绝
   */
  private static <E extends Exception> ApiResponse<Void> apply(
      BusinessRejectionMapping<E> mapping, Exception failure) {
    ApiResponse<Void> response = mapping.map(mapping.exceptionType().cast(failure));
    if (response == null || response.data() != null) {
      throw new IllegalArgumentException("非法业务拒绝结果");
    }
    return ApiResponse.rejected(response.code(), response.message());
  }
}
