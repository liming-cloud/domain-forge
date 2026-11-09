package org.domainforge.files.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 文件存储配置（前缀 {@code domainforge.files}）。默认后端所需的 {@code local.root}、{@code s3.bucket} 在创建默认后端时才检查，
 * 业务提供自己的 {@link ObjectStorage} Bean 时可以不配。
 *
 * @param enabled   设为 true 启用文件存储
 * @param storage   默认后端：local 或 s3
 * @param maxSize   单个文件大小上限
 * @param spoolDir  上传暂存目录，留空为系统临时目录下的 domainforge-files
 * @param grace     未关联文件的保留时间，超过后由清理任务删除
 * @param local     本地目录后端
 * @param s3        S3 后端
 */
@ConfigurationProperties("domainforge.files")
public record FileStorageProperties(
        boolean enabled,
        @DefaultValue("local") String storage,
        @DefaultValue("20MB") DataSize maxSize,
        @DefaultValue("") String spoolDir,
        @DefaultValue("24h") Duration grace,
        @DefaultValue Local local,
        @DefaultValue S3 s3) {
    /**
     * 本地目录后端。
     *
     * @param root 存放根目录
     */
    public record Local(@DefaultValue("") String root) {
    }

    /**
     * S3 后端；未配置 {@code accessKey} 时使用 AWS 默认凭据链。
     *
     * @param bucket         桶名
     * @param prefix         对象键前缀，留空为“应用名/”
     * @param region         区域
     * @param pathStyle      路径风格寻址，兼容自建服务
     * @param endpoint       自定义服务地址
     * @param accessKey      访问密钥标识
     * @param secretKey      访问密钥
     * @param socketTimeout  套接字超时
     * @param attemptTimeout 单次调用超时
     * @param callTimeout    调用总时长（含重试）
     */
    public record S3(
            @DefaultValue("") String bucket,
            @DefaultValue("") String prefix,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("true") boolean pathStyle,
            @DefaultValue("") String endpoint,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue("5s") Duration socketTimeout,
            @DefaultValue("5s") Duration attemptTimeout,
            @DefaultValue("10s") Duration callTimeout) {
    }
}
