package org.domainforge.iam.interfaces.http.audit;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.domainforge.iam.application.query.audit.AuditQueries;
import org.domainforge.iam.application.query.audit.AuditView;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前审计权限保护只读审计出口，不以历史操作者身份代替当前资格。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AuditController {
  private final AuditQueries queries;

  /** 只绑定有界查询，不将审计存储对象暴露到接口。 */
  public AuditController(AuditQueries queries) {
    this.queries = queries;
  }

  /** 按记录ID递增查询最多100条，补录与原生记录保留不同来源。 */
  @SaCheckPermission("iam:audit:read")
  @GetMapping("/api/audit")
  @Operation(summary = "分页查询审计事实")
  public ApiResponse<List<AuditView>> list(
      @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
    return ApiResponse.success(queries.list(after, limit));
  }
}
