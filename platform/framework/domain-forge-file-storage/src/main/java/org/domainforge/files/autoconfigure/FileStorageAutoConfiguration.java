package org.domainforge.files.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.ObjectStorage;
import org.domainforge.files.internal.FileCleanup;
import org.domainforge.files.internal.FileMapper;
import org.domainforge.files.internal.FileStorageAssembly;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 文件存储装配：{@code domainforge.files.enabled=true} 时启用，{@code domainforge.files.storage} 选择
 * {@code local}（默认，需 {@code domainforge.files.local.root}）或 {@code s3}（需 {@code domainforge.files.s3.bucket}）；
 * 其他后端由业务提供 {@link ObjectStorage} Bean 接入，默认后端随之让位。
 *
 * <p>启动时检查本地目录可写或桶可访问，否则启动失败。服务需在自己的迁移中创建 {@code domainforge_file} 表
 * （结构见组件 {@code domain-forge/files/schema.sql}）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "domainforge.files.enabled", havingValue = "true")
@MapperScan(basePackageClasses = FileMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class FileStorageAutoConfiguration {
    /**
     * 默认存储后端（本地目录或 S3）；业务提供自己的 {@link ObjectStorage} Bean 时让位。
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    ObjectStorage objectStorage(Environment environment) {
        return FileStorageAssembly.storage(environment);
    }

    /**
     * 文件存取接口；启动时检查存储后端可用。
     */
    @Bean
    @ConditionalOnMissingBean(FileStore.class)
    FileStore fileStore(Environment environment, FileMapper files, ObjectStorage storage,
            ObjectProvider<MeterRegistry> metrics) {
        return FileStorageAssembly.store(environment, files, storage, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 清理任务，由调度组件登记到 PowerJob。
     */
    @Bean
    @ConditionalOnMissingBean
    FileCleanup fileStorageCleanup(Environment environment, FileMapper files, ObjectStorage storage,
            ObjectProvider<MeterRegistry> metrics) {
        return FileStorageAssembly.cleanup(environment, files, storage,
                metrics.getIfAvailable(SimpleMeterRegistry::new));
    }
}
