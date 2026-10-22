package @@PACKAGE@@.interfaces.http;

import @@PACKAGE@@.application.query.RecordFilter;
import @@PACKAGE@@.application.query.Records;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.HttpFailureResponses;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 测试 HTTP 入口只转换参数，查询走应用合同。 */
@RestController
@PlatformJsonApi
public class RecordController {
  private final Records records;

  /** 注入查询合同，禁止直接注入 Mapper。 */
  public RecordController(Records records) {
    this.records = records;
  }

  /** 返回纯查询视图；无编排便不添加 Handler。 */
  @GetMapping("/fixture/records")
  public ResponseEntity<?> find(
      @RequestParam String value, @RequestParam int limit, @RequestParam String order) {
    RecordFilter filter;
    try {
      filter = new RecordFilter(value, limit, order);
    } catch (IllegalArgumentException failure) {
      return HttpFailureResponses.invalidRequest();
    }
    return ResponseEntity.ok(ApiResponse.success(records.find(filter)));
  }
}
