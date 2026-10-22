package org.domainforge.iam.infrastructure.persistence.operation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.operation.CommandReceipts;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.springframework.stereotype.Repository;

/** 原子幂等结果适配器，重用ID但改变请求必须显式拒绝。 */
@Repository
public class SqlCommandReceipts implements CommandReceipts {
  private final ReceiptMapper mapper;

  /** 使用同一业务事务的Mapper。 */
  public SqlCommandReceipts(ReceiptMapper mapper) {
    this.mapper = mapper;
  }

  /** 先串行化同一幂等键；常量时间比较避免泄露HMAC比较前缀。 */
  @Override
  public CommandResult previous(String scope, UUID operation, String fingerprint) {
    mapper.lock(scope + ":" + operation);
    mapper.expire(scope, operation.toString());
    ReceiptRow row = mapper.find(scope, operation.toString());
    if (row == null) return null;
    if (!MessageDigest.isEqual(
        row.fingerprint().getBytes(StandardCharsets.US_ASCII),
        fingerprint.getBytes(StandardCharsets.US_ASCII))) {
      throw new IamException(IamFailure.CONFLICT, "操作ID已经用于不同输入");
    }
    return row.result();
  }

  @Override
  public void save(String scope, String fingerprint, CommandResult result) {
    mapper.insert(
        scope,
        new ReceiptRow(
            fingerprint,
            result.operationId().toString(),
            result.targetId().toString(),
            result.version(),
            result.action()));
  }
}
