package org.domainforge.files.api;

import java.util.Optional;

/**
 * 文件存取：业务先按自身权限与数据范围判断，再经本接口读写；存储不对外暴露，下载经业务接口转发。
 *
 * <p>文件信息保存在服务自己的 {@code domainforge_file} 表中，与业务数据同事务提交；业务只保存文件 ID。
 * 写入与删除必须在业务事务内调用：回滚时新写入的对象随即删除，删除只做标记，超过保留期后由清理任务删除对象。
 */
public interface FileStore {
    /**
     * 按策略检查并保存一个文件。
     *
     * @param upload 上传内容；组件读完后不关闭流，由调用方关闭
     * @param policy 本用例允许的大小与类型
     * @return 已保存的文件信息
     * @throws FileRejected                    名称、类型或大小不符合策略
     * @throws FileStorageUnavailableException 存储不可用
     * @throws IllegalStateException           当前没有进行中的事务
     */
    StoredFile store(FileUpload upload, UploadPolicy policy);

    /**
     * 读取文件信息；不存在或已删除时为空。
     */
    Optional<StoredFile> find(String fileId);

    /**
     * 打开文件内容；不存在或已删除时为空。返回的内容须由调用方关闭（交给 {@link FileResponses} 时由框架关闭）。
     *
     * @throws FileStorageUnavailableException 存储不可用
     * @throws IllegalStateException           文件信息存在而对象缺失（数据不一致）
     */
    Optional<FileContent> open(String fileId);

    /**
     * 删除文件：在业务事务内标记删除，之后读取不到；对象在保留期后由清理任务删除。
     *
     * @return 是否删除了一个存在的文件
     * @throws IllegalStateException 当前没有进行中的事务
     */
    boolean delete(String fileId);
}
