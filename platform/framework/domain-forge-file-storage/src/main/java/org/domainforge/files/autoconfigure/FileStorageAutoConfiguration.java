package org.domainforge.files.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.internal.FileCleanup;
import org.domainforge.files.internal.FileFailureAdvice;
import org.domainforge.files.internal.FileMapper;
import org.domainforge.files.internal.FileRejectionMapping;
import org.domainforge.files.internal.FileStorageAssembly;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 文件存储装配：{@code domainforge.files.enabled=true} 时启用，{@code domainforge.files.storage} 选择
 * {@code local}（默认，需 {@code domainforge.files.local.root}）或 {@code s3}（需 {@code domainforge.files.s3.bucket}）。
 *
 * <p>启动时检查本地目录可写或桶可访问，否则启动失败。服务需在自己的迁移中创建 {@code domainforge_file} 表
 * （结构见组件 {@code domain-forge/files/schema.sql}）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "domainforge.files.enabled", havingValue = "true")
@MapperScan(basePackageClasses = FileMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class FileStorageAutoConfiguration {
    /**
     * 按配置建立存储后端并完成启动检查；关闭时释放 S3 客户端。
     */
    @Bean(destroyMethod = "close")
    FileStorageAssembly fileStorageAssembly(Environment environment, FileMapper files,
                                            ObjectProvider<MeterRegistry> metrics) {
        return FileStorageAssembly.create(environment, files, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 文件存取接口。
     */
    @Bean
    FileStore fileStore(FileStorageAssembly assembly) {
        return assembly.store();
    }

    /**
     * 清理任务，由调度组件登记到 PowerJob。
     */
    @Bean
    FileCleanup fileStorageCleanup(FileStorageAssembly assembly) {
        return assembly.cleanup();
    }

    /**
     * 存储不可用 503、上传过大 413。
     */
    @Bean
    FileFailureAdvice fileFailureAdvice() {
        return new FileFailureAdvice();
    }

    /**
     * 上传策略拒绝映射为业务拒绝。
     */
    @Bean
    FileRejectionMapping fileRejectionMapping() {
        return new FileRejectionMapping();
    }
}
