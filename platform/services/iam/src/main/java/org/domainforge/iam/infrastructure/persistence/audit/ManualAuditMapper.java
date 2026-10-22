package org.domainforge.iam.infrastructure.persistence.audit;

/** 人工补录失败不能按普通观测失败静默忽略；它本身就是当前业务命令。 */
@org.apache.ibatis.annotations.Mapper
public interface ManualAuditMapper {
  /** 明确存储manual来源与未确认原结果，不伪造原操作者或补录时刻为原发生时刻。 */
  int append(ManualAuditRow row);
}
