package org.domainforge.iam.client.internal;

import feign.Response;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.shared.CurrentSubject;
import org.springframework.http.MediaType;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 将 IAM 的有限 HTTP 协议适配为公共事实来源，不建立会话或跨请求身份缓存。 */
public final class IamCurrentSubjectSource implements CurrentSubjectSource {
  private final Supplier<IamAccessFeignClient> client;
  private final String projectId;
  private final JsonMapper json =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  /** 项目绑定是本应用的信任边界；Feign 代理由原生容器管理。 */
  public IamCurrentSubjectSource(Supplier<IamAccessFeignClient> client, String projectId) {
    if (projectId == null || !projectId.matches("[a-z][a-z0-9-]{2,63}"))
      throw new IllegalArgumentException("IAM 项目绑定无效");
    this.client = client;
    this.projectId = projectId;
  }

  /** 令牌仅作为当前调用的显式 Bearer 参数，不记录异常底层消息。 */
  @Override
  public CurrentSubject resolve(String token) {
    validateToken(token);
    try (var response = client.get().current("Bearer " + token)) {
      return decode(response);
    } catch (AccessException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new AccessException(AccessFailure.UNAVAILABLE);
    }
  }

  /** 只接受有限且不含换行的显式凭证，非法输入在网络调用前拒绝。 */
  private void validateToken(String token) {
    if (token == null
        || token.isBlank()
        || token.length() > 4096
        || token.indexOf('\r') >= 0
        || token.indexOf('\n') >= 0) throw new AccessException(AccessFailure.UNAUTHENTICATED);
  }

  /** 401 不依赖错误体；只有严格的有限成功协议才能成为可信身份。 */
  private CurrentSubject decode(Response response) throws java.io.IOException {
    if (response.status() == 401) throw new AccessException(AccessFailure.UNAUTHENTICATED);
    if (response.status() != 200 || response.body() == null)
      throw new AccessException(AccessFailure.UNAVAILABLE);
    var types =
        response.headers().entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
            .flatMap(entry -> entry.getValue().stream())
            .toList();
    if (types.size() != 1) throw new AccessException(AccessFailure.UNAVAILABLE);
    var media = MediaType.parseMediaType(types.getFirst());
    if (!"application".equals(media.getType()) || !"json".equals(media.getSubtype()))
      throw new AccessException(AccessFailure.UNAVAILABLE);
    try (var stream = response.body().asInputStream()) {
      byte[] body = stream.readNBytes(16385);
      if (body.length > 16384) throw new AccessException(AccessFailure.UNAVAILABLE);
      return subject(json.readTree(body));
    }
  }

  /** 完整成功外壳与当前项目身份必须同时成立，不做宽松 DTO 转换。 */
  private CurrentSubject subject(JsonNode root) {
    if (!root.isObject()
        || root.size() != 3
        || !"SUCCESS".equals(root.path("code").asString())
        || !root.path("message").isTextual()) throw new AccessException(AccessFailure.UNAVAILABLE);
    var data = root.path("data");
    validateData(data);
    return new CurrentSubject(
        projectId,
        UUID.fromString(data.path("accountId").asString()),
        values(data.path("permissions")),
        values(data.path("roles")));
  }

  /** 限定公开当前身份协议，不接收跨项目、未知字段或非规范标识。 */
  private void validateData(JsonNode data) {
    if (!data.isObject()
        || data.size() != 4
        || !projectId.equals(data.path("projectId").asString())
        || !data.path("accountId").isTextual()
        || !data.path("accountId")
            .asString()
            .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        || !data.path("permissions").isArray()
        || data.path("permissions").size() > 128
        || !data.path("roles").isArray()
        || data.path("roles").size() > 128) throw new AccessException(AccessFailure.UNAVAILABLE);
  }

  /** 权限／角色只接受不重复文本，实际字符边界继续由公共契约检查。 */
  private Set<String> values(JsonNode values) {
    var result = new HashSet<String>();
    for (var value : values)
      if (!value.isTextual() || !result.add(value.asString()))
        throw new AccessException(AccessFailure.UNAVAILABLE);
    return result;
  }
}
