package example.consumer.b;

import static org.junit.jupiter.api.Assertions.*;

import org.domainforge.web.api.ApiResponse;
import org.junit.jupiter.api.Test;

class ClientResultTest {
  @Test
  void clientMustNotTreatHttp200BusinessRejectionAsSuccess() {
    var response = ApiResponse.rejected("DECLINED", "合成拒绝");
    assertFalse(isSuccess(200, response));
    assertTrue(isSuccess(200, ApiResponse.success(null)));
    assertFalse(isSuccess(500, ApiResponse.success(null)));
    assertFalse(isSuccess(202, ApiResponse.accepted(null).getBody()));
  }

  private static boolean isSuccess(int status, ApiResponse<?> response) {
    return status == 200 && response != null && "SUCCESS".equals(response.code());
  }
}
