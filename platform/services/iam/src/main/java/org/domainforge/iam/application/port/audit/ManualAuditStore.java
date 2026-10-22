package org.domainforge.iam.application.port.audit;

import java.util.UUID;

/** 受控审计补录写入出口，独立于普通审计失败容错。 */
public interface ManualAuditStore {
  /** 在补录用例事务中保存事实；补录者由当前身份提供，失败必须回滚并告知调用方。 */
  void append(UUID recordedBy, ManualAudit entry);
}
