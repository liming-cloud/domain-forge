package org.domainforge.files.internal;

import org.domainforge.files.api.FileStorageUnavailableException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 兼容存储：对象键为 {@code <前缀><组件键>}，多个服务可共用一个桶。桶由运维创建，组件不自动创建。
 */
final class S3ObjectStorage implements ObjectStorage {
    private final S3Client client;
    private final String bucket;
    private final String prefix;

    S3ObjectStorage(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    private static FileStorageUnavailableException failed(String action, SdkException cause) {
        return new FileStorageUnavailableException("S3 文件存储" + action + "失败", cause);
    }

    @Override
    public void check() {
        try {
            client.headBucket(request -> request.bucket(bucket));
        } catch (SdkException unusable) {
            throw new IllegalStateException("S3 桶不可访问：" + bucket, unusable);
        }
    }

    @Override
    public void write(String key, Path source, long size) {
        try {
            client.putObject(request -> request.bucket(bucket).key(prefix + key).contentLength(size),
                    RequestBody.fromFile(source));
        } catch (SdkException failure) {
            throw failed("写入", failure);
        }
    }

    @Override
    public InputStream read(String key) {
        try {
            return client.getObject(request -> request.bucket(bucket).key(prefix + key));
        } catch (NoSuchKeyException missing) {
            return null;
        } catch (SdkException failure) {
            throw failed("读取", failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(request -> request.bucket(bucket).key(prefix + key));
        } catch (SdkException failure) {
            throw failed("删除", failure);
        }
    }

    @Override
    public List<StoredObject> list(String dayPrefix) {
        List<StoredObject> objects = new ArrayList<>();
        try {
            for (S3Object object : client.listObjectsV2Paginator(
                    request -> request.bucket(bucket).prefix(prefix + dayPrefix)).contents())
                objects.add(new StoredObject(object.key().substring(prefix.length()), object.lastModified()));
        } catch (SdkException failure) {
            throw failed("列举", failure);
        }
        return objects;
    }
}
