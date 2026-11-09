package org.domainforge.files.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.files.api.FileStorageProperties;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.ObjectStorage;
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
    private FileStorageAssembly() {
    }

    /**
     * 默认存储后端：{@code domainforge.files.storage} 选择本地目录或 S3；业务提供自己的 {@link ObjectStorage} Bean 时不会调用。
     */
    public static ObjectStorage storage(FileStorageProperties properties, String application) {
        var s3 = properties.s3();
        return switch (properties.storage()) {
            case "local" -> new LocalObjectStorage(Path.of(required(properties.local().root(), "local.root")));
            case "s3" -> new S3ObjectStorage(s3(s3), required(s3.bucket(), "s3.bucket"),
                    s3.prefix().isEmpty() ? application + "/" : s3.prefix());
            default -> throw new IllegalStateException(
                    "domainforge.files.storage 只能是 local 或 s3：" + properties.storage());
        };
    }

    /**
     * 文件存取实现；先检查后端可用。
     */
    public static FileStore store(FileStorageProperties properties, FileMapper files, ObjectStorage storage,
            MeterRegistry metrics) {
        storage.check();
        Path spool = Path.of(properties.spoolDir().isEmpty()
                ? System.getProperty("java.io.tmpdir") + "/domainforge-files" : properties.spoolDir());
        return new SqlFileStore(files, storage, new SqlFileStore.Settings(properties.maxSize().toBytes(), spool),
                metrics, Clock.systemUTC());
    }

    /**
     * 清理任务。
     */
    public static FileCleanup cleanup(FileStorageProperties properties, FileMapper files, ObjectStorage storage,
            MeterRegistry metrics) {
        return new FileCleanup(files, storage, properties.grace(), metrics, Clock.systemUTC());
    }

    private static String required(String value, String name) {
        if (value.isBlank()) throw new IllegalStateException("文件存储缺少配置 domainforge.files." + name);
        return value;
    }

    /**
     * S3 客户端：路径风格寻址（默认）以兼容自建服务；只在服务要求时计算校验和，避免部分兼容实现不支持的新校验头。
     * 未配置访问密钥时使用 AWS 默认凭据链（环境变量、实例角色等）。写入发生在业务事务内，
     * 调用总时长（含重试，默认 10 秒）须小于上传用例的事务超时，后者又须小于网关的下游响应超时（15 秒）。
     */
    private static S3Client s3(FileStorageProperties.S3 config) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(config.region()))
                .forcePathStyle(config.pathStyle())
                .credentialsProvider(credentials(config))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(config.socketTimeout()))
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(config.attemptTimeout())
                        .apiCallTimeout(config.callTimeout()));
        if (!config.endpoint().isBlank()) builder.endpointOverride(URI.create(config.endpoint()));
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(FileStorageProperties.S3 config) {
        if (config.accessKey().isBlank()) return DefaultCredentialsProvider.builder().build();
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKey(), required(config.secretKey(), "s3.secret-key")));
    }
}
