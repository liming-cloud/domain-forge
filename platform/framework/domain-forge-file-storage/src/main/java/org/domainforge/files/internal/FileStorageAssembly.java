package org.domainforge.files.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.ObjectStorage;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * 按配置组装默认存储后端、存取实现与清理任务。
 */
public final class FileStorageAssembly {
    private static final String PREFIX = "domainforge.files.";

    private FileStorageAssembly() {
    }

    /**
     * 默认存储后端：{@code domainforge.files.storage} 选择本地目录或 S3；业务提供自己的 {@link ObjectStorage} Bean 时不会调用。
     */
    public static ObjectStorage storage(Environment environment) {
        String kind = environment.getProperty(PREFIX + "storage", "local");
        return switch (kind) {
            case "local" -> new LocalObjectStorage(Path.of(required(environment, "local.root")));
            case "s3" -> new S3ObjectStorage(s3(environment), required(environment, "s3.bucket"),
                    environment.getProperty(PREFIX + "s3.prefix",
                            environment.getProperty("spring.application.name", "app") + "/"));
            default -> throw new IllegalStateException("domainforge.files.storage 只能是 local 或 s3：" + kind);
        };
    }

    /**
     * 文件存取实现；先检查后端可用。
     */
    public static FileStore store(Environment environment, FileMapper files, ObjectStorage storage,
            MeterRegistry metrics) {
        storage.check();
        long maxBytes = DataSize.parse(environment.getProperty(PREFIX + "max-size", "20MB")).toBytes();
        Path spool = Path.of(environment.getProperty(PREFIX + "spool-dir",
                System.getProperty("java.io.tmpdir") + "/domainforge-files"));
        return new SqlFileStore(files, storage, new SqlFileStore.Settings(maxBytes, spool), metrics,
                Clock.systemUTC());
    }

    /**
     * 清理任务。
     */
    public static FileCleanup cleanup(Environment environment, FileMapper files, ObjectStorage storage,
            MeterRegistry metrics) {
        Duration grace = environment.getProperty(PREFIX + "grace", Duration.class, Duration.ofHours(24));
        return new FileCleanup(files, storage, grace, metrics, Clock.systemUTC());
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name, "");
        if (value.isBlank()) throw new IllegalStateException("文件存储缺少配置 " + PREFIX + name);
        return value;
    }

    /**
     * S3 客户端：路径风格寻址（默认）以兼容自建服务；只在服务要求时计算校验和，避免部分兼容实现不支持的新校验头。
     * 未配置访问密钥时使用 AWS 默认凭据链（环境变量、实例角色等）。写入发生在业务事务内，
     * 调用总时长（含重试，默认 10 秒）须小于上传用例的事务超时，后者又须小于网关的下游响应超时（15 秒）。
     */
    private static S3Client s3(Environment environment) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(environment.getProperty(PREFIX + "s3.region", "us-east-1")))
                .forcePathStyle(environment.getProperty(PREFIX + "s3.path-style", Boolean.class, true))
                .credentialsProvider(credentials(environment))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(environment.getProperty(PREFIX + "s3.socket-timeout", Duration.class,
                                Duration.ofSeconds(5))))
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(environment.getProperty(PREFIX + "s3.attempt-timeout",
                                Duration.class, Duration.ofSeconds(5)))
                        .apiCallTimeout(environment.getProperty(PREFIX + "s3.call-timeout", Duration.class,
                                Duration.ofSeconds(10))));
        String endpoint = environment.getProperty(PREFIX + "s3.endpoint", "");
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(Environment environment) {
        String accessKey = environment.getProperty(PREFIX + "s3.access-key", "");
        if (accessKey.isBlank()) return DefaultCredentialsProvider.builder().build();
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, required(environment,
                "s3.secret-key")));
    }
}
