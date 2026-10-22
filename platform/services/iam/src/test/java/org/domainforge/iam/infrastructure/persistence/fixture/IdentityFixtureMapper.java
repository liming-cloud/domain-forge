package org.domainforge.iam.infrastructure.persistence.fixture;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用数据库观察与故障入口，独立于被测业务查询；不进入产品制品。 */
@Mapper
public interface IdentityFixtureMapper {
  /** 清空本次独立用例数据及防护代次，保留真实迁移结构。 */
  void reset();

  /** 为保留期测试独立准备未过期人工记录。 */
  void manualRetentionRecord();

  /** 独立数据库观察或受控故障：reissuedAccountCount。 */
  int reissuedAccountCount();

  /** 独立数据库观察或受控故障：deleteSession。 */
  void deleteSession(@Param("value") String value);

  /** 独立数据库观察或受控故障：restoreSessionTable。 */
  void restoreSessionTable();

  /** 独立数据库观察或受控故障：hideSessionTable。 */
  void hideSessionTable();

  /** 独立数据库观察或受控故障：operationCount。 */
  int operationCount(@Param("value") String value);

  /** 独立数据库观察或受控故障：tokenSessionCount。 */
  int tokenSessionCount(@Param("value") String value);

  /** 独立数据库观察或受控故障：sessionCount。 */
  int sessionCount();

  /** 独立数据库观察或受控故障：manualAuditCount。 */
  int manualAuditCount();

  /** 独立数据库观察或受控故障：replayInvitationCount。 */
  int replayInvitationCount();

  /** 独立数据库观察或受控故障：cleanupAuditCount。 */
  int cleanupAuditCount();

  /** 独立数据库观察或受控故障：cleanupReceiptCount。 */
  int cleanupReceiptCount();

  /** 独立数据库观察或受控故障：expiredAudits。 */
  void expiredAudits();

  /** 独立数据库观察或受控故障：expireReplayInvitation。 */
  void expireReplayInvitation();

  /** 独立数据库观察或受控故障：expiredReceipts。 */
  void expiredReceipts();

  /** 独立数据库观察或受控故障：manualAudit。 */
  Map<String, Object> manualAudit(@Param("value") String value);

  /** 独立数据库观察或受控故障：targetAuditCount。 */
  int targetAuditCount(@Param("value") String value);

  /** 独立数据库观察或受控故障：dropAuditFailure。 */
  void dropAuditFailure();

  /** 独立数据库观察或受控故障：removeAuditFailure。 */
  void removeAuditFailure();

  /** 独立数据库观察或受控故障：attachAuditFailure。 */
  void attachAuditFailure();

  /** 独立数据库观察或受控故障：createAuditFailure。 */
  void createAuditFailure();

  /** 独立数据库观察或受控故障：expiredAccountCount。 */
  int expiredAccountCount();

  /** 独立数据库观察或受控故障：expireInvitation。 */
  void expireInvitation();

  /** 独立数据库观察或受控故障：dropInvitationFailure。 */
  void dropInvitationFailure();

  /** 独立数据库观察或受控故障：removeInvitationFailure。 */
  void removeInvitationFailure();

  /** 独立数据库观察或受控故障：unconsumedRollbackInvitationCount。 */
  int unconsumedRollbackInvitationCount();

  /** 独立数据库观察或受控故障：rollbackAccountCount。 */
  int rollbackAccountCount();

  /** 独立数据库观察或受控故障：attachInvitationFailure。 */
  void attachInvitationFailure();

  /** 独立数据库观察或受控故障：createInvitationFailure。 */
  void createInvitationFailure();

  /** 独立数据库观察或受控故障：registrationReceiptCount。 */
  int registrationReceiptCount();

  /** 独立数据库观察或受控故障：parallelAccountCount。 */
  int parallelAccountCount();

  /** 独立数据库观察或受控故障：initializationAuditCount。 */
  int initializationAuditCount();

  /** 独立数据库观察或受控故障：accountCount。 */
  int accountCount();

  // 独立SQL观察恢复秒数和摘要，不从生产适配返回值推断持久化成功。
  long protectionRemainingSeconds();

  String protectionDigest();

  java.time.Instant protectionDeadline();

  // 只用于证明更晚恢复截止不会被维护或Redis重启缩短，不用于冒充实际窗口恢复。
  int extendProtectionWindow(int seconds);
}
