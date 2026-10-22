package org.domainforge.iam.infrastructure.persistence.invitation;

/** 邀请秘密只保存摘要，消费与重发共用同一行锁和终态条件。 */
@org.apache.ibatis.annotations.Mapper
public interface InvitationMapper {
  /** 秘密摘要不存在返回null；行锁持续到业务事务完成。 */
  InvitationRow byDigest(String digest);

  /** 管理重发也锁同一邀请，不能和接受并发成功。 */
  InvitationRow lock(String id);

  /** 新邀请和旧邀请失效在调用方同一事务中提交。 */
  int insert(InvitationRow row);

  /** 仅未消费未失效邀请可转换，零行表示状态冲突。 */
  int finish(InvitationRow row);
}
