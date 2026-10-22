import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** 有限固定频率的真实HTTPS负载；不记录请求体、凭据或响应正文。 */
public final class IdentityLoad {
  private static HttpClient client() throws Exception {
    char[] password = System.getenv("STORE_PASSWORD").toCharArray();
    var keys = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(Path.of("/work/consumer.p12"))) { keys.load(in, password); }
    var trust = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(Path.of("/work/trust.p12"))) { trust.load(in, password); }
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
    var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
    var tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
  }
  private static void requests(String kind, int count, long interval, long start, int index) throws Exception {
    var client = client();
    String base = index % 2 == 0 ? "https://iam-a:8443" : "https://iam-b:8443";
    String authorization = System.getenv().getOrDefault("AUTHORIZATION_URL", base + "/internal/access/current");
    var builder = HttpRequest.newBuilder(URI.create(kind.equals("login") ? base + "/api/auth/login" : authorization))
        .timeout(Duration.ofSeconds(3));
    if (kind.equals("login")) builder.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Files.readString(Path.of("/work/login.json"))));
    else builder.header("Authorization", "Bearer " + Files.readString(Path.of("/work/access-token"))).GET();
    var request = builder.build();
    try (var out = Files.newBufferedWriter(Path.of("/output/" + kind + "-" + index + ".csv"))) {
      out.write("index,status,milliseconds,schedule_lag_ms,semantic\n");
      for (int n = 0; n < count; n++) {
        long due = start + index * (interval / 8) + n * interval;
        long wait = due - System.currentTimeMillis(); if (wait > 0) Thread.sleep(wait);
        long lag = Math.max(0, System.currentTimeMillis() - due), began = System.nanoTime();
        int status = 0; boolean semantic = false;
        try {
          var response = client.send(request, HttpResponse.BodyHandlers.ofString()); status = response.statusCode();
          semantic = response.body().contains(kind.equals("login") ? "\"token\"" : "\"accountId\"");
        } catch (Exception failure) { status = 0; }
        out.write(n + "," + status + "," + (System.nanoTime()-began)/1_000_000.0 + "," + lag + "," + semantic + "\n"); out.flush();
      }
    } finally { client.close(); }
  }
  public static void main(String[] args) throws Exception {
    int index = Integer.parseInt(args[0]); long start = Long.parseLong(args[1]);
    // TLS/类加载热身只做授权，登录来源计数不提前消耗。
    try (var warm = client()) {
      var request = HttpRequest.newBuilder(URI.create(System.getenv().getOrDefault("AUTHORIZATION_URL", index % 2 == 0 ? "https://iam-a:8443/internal/access/current" : "https://iam-b:8443/internal/access/current")))
          .header("Authorization", "Bearer " + Files.readString(Path.of("/work/access-token"))).timeout(Duration.ofSeconds(3)).build();
      for (int n=0;n<3;n++) { int status = warm.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(); if (status != 200) throw new IllegalStateException("warmup status " + status); }
    }
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = workers.submit(() -> { requests("login", 75, 4000, start, index); return null; });
      var b = workers.submit(() -> { requests("authorization", 750, 400, start, index); return null; });
      a.get(); b.get();
    }
  }
}
