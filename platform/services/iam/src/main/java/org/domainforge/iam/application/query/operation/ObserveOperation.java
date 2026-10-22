package org.domainforge.iam.application.query.operation;

import java.util.Set;
import java.util.UUID;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.springframework.stereotype.Service;

/** 操作核对有真实身份编排，允许有权审计者核对他人结果，不把操作ID当访问凭证。 */
@Service
public final class ObserveOperation {
  private final OperationQueries queries;

  /** 查询出口只返回非秘密结果。 */
  public ObserveOperation(OperationQueries queries) {
    this.queries = queries;
  }

  /** 本人须仍有原操作资格；指定他人须审计权限，未知或过期返回未确认。 */
  public OperationObservation execute(
      UUID requester, Set<String> permissions, UUID operation, UUID actor) {
    UUID owner = actor == null ? requester : actor;
    boolean other = !owner.equals(requester);
    if (other && !permissions.contains("iam:audit:read")) throw forbidden();
    var result = queries.find(owner, operation);
    if (result != null && !other) {
      String required = requiredPermission(result.action());
      if (required != null && !permissions.contains(required)) throw forbidden();
    }
    return new OperationObservation(operation, result != null, result);
  }

  private static String requiredPermission(String action) {
    return switch (action) {
      case "invitation.issue" -> "iam:invitation:create";
      case "account.permissions" -> "iam:grant:manage";
      case "account.state" -> "iam:account:state";
      case "audit.backfill" -> "iam:audit:read";
      case "account.password", "session.logout" -> null;
      default -> throw forbidden();
    };
  }

  private static IamException forbidden() {
    return new IamException(IamFailure.FORBIDDEN, "无权核对该操作");
  }
}
