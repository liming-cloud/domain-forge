# 文件存储

服务的文件统一经 `domain-forge-file-storage` 组件保存到本地目录（默认）或 S3 兼容存储。业务先按自身权限与数据范围判断，再经组件读写；上传与下载都经业务服务自己的接口转发，存储不对外暴露。产品要求见[文件存储功能边界](../../product/01-产品定义.md#文件存储功能边界)与[实现支持与扩展范围](../../product/01-产品定义.md#实现支持与扩展范围)，规则卡见[安全 A-15](../../rules/07-安全与访问控制.md#a-15-文件经业务接口存取)。

决定记录：[ADR 0017](../../decisions/0017-文件存储的取舍.md)。

## 存取合同

| 操作 | 合同 |
|---|---|
| 保存 `store(upload, policy)` | 只能在业务事务内调用。先检查名称与类型，把内容读到暂存文件（同时计算大小与 SHA-256，超过上限立即停止），再写入存储，最后写文件信息。事务回滚时删除刚写入的对象 |
| 读取 `find`／`open` | 不存在或已删除时为空；文件信息存在而对象缺失是数据不一致，抛出 `IllegalStateException`（500） |
| 删除 `delete` | 只能在业务事务内调用：标记删除，之后读取不到；对象在保留期（默认 24 小时，`domainforge.files.grace`）后由清理任务删除 |
| 策略 `UploadPolicy` | 业务按用例声明大小上限与类型白名单；服务全局上限 `domainforge.files.max-size`（默认 20MB）同时生效，取较小者 |
| 名称与类型 | 客户端给出的文件名去掉路径与控制字符、截断到 255 个字符，只作展示与下载文件名；类型以客户端声明为准（去掉参数、统一小写），组件不识别内容。存储键由组件生成（`yyyy/MM/dd/<uuid>`），与客户端输入无关 |
| 下载 `FileResponses.attachment` | 一律作为附件返回（RFC 5987 编码文件名），带 `X-Content-Type-Options: nosniff`、`Content-Security-Policy: default-src 'none'; sandbox`、`Cache-Control: private, no-store`，上传的 HTML 或脚本不会在本站页面中执行 |
| 不在合同内 | 目录、标签、版本、回收站、共享管理（产品范围外）；断点续传与分片上传；按内容识别类型或病毒扫描；本地与 S3 之间的数据迁移 |

失败映射（组件自带，入口不需要再处理）见[错误码](../错误码.md#组件带来的业务拒绝代码)。

### 一致性

文件对象与文件信息不在同一个事务里，组件按下面的顺序保证不出现“有记录、没对象”：

- 先写对象再写记录：写对象后、事务提交前应用中断，留下没有记录的孤儿对象。
- 删除只标记，保留期后才删除对象：在线备份先导出数据库、再打包文件卷，只要两者之间不超过保留期，备份中有记录的文件对象都还在。
- 孤儿对象（提交前中断、回滚后删除失败、从备份恢复后多出的文件）由清理任务删除。

### 时限

保存文件发生在业务事务内，时限依次为：S3 调用总时长 10 秒（单次尝试 5 秒，含 SDK 默认重试）< 上传用例的事务超时（模板示例 12 秒）< 网关下游响应超时 15 秒 < Nginx 读超时 20 秒。这样超时由服务先判定并回滚，而不是网关返回 504 之后服务仍然提交。上传内容在进入用例前已由 Servlet 容器完整接收（超过 1MB 暂存到临时目录），客户端上传慢不占用事务时间。存储较慢时成组调大这几项。

## 组件

| 部分 | 行为 |
|---|---|
| 启用 | `domainforge.files.enabled=true`；未启用时不装配，服务注入 `FileStore` 即启动失败 |
| 表 `domainforge_file` | 服务在自己的迁移中创建（结构见组件 `domain-forge/files/schema.sql`）；业务关联表引用其 `id` |
| 本地存储 | `domainforge.files.storage=local`（默认），根目录 `domainforge.files.local.root`；写入根目录下的 `.incoming`、落盘后原子改名，读者不会看到写了一半的文件 |
| S3 存储 | `domainforge.files.storage=s3`：`s3.endpoint`（空为 AWS）、`s3.region`（默认 `us-east-1`）、`s3.bucket`、`s3.prefix`（默认 `<服务名>/`，多个服务可共用一个桶）、`s3.access-key`／`s3.secret-key`（空时使用 AWS 默认凭据链）、`s3.path-style`（默认 true）。桶由运维创建，组件不自动创建。只在服务要求时计算校验和，避免部分兼容实现不支持新的校验头 |
| 启动检查 | 本地目录不可写或桶不可访问时启动失败 |
| 清理任务 | `file-storage-cleanup`（经[定时任务](scheduling.md)登记，初始每小时一次）：删除标记早于保留期的对象与记录；删除最近 3 天目录中早于保留期且没有记录的对象。手动触发时参数 `days=N` 指定检查最近多少天，恢复较早的备份后使用 |
| 指标 | `domainforge.files.stored`、`domainforge.files.rejected{reason}`、`domainforge.files.purged`、`domainforge.files.orphans` |

## 服务中的写法

```
domain/repository/<关联仓储>          业务对象与文件 ID 的关联（如便笺附件）
application/command/<用例>            先核对业务对象的写范围，再 FileStore.store／delete，与关联一起提交
application/query/<用例>              先核对读范围与关联，再 FileStore.find／open（需要资源授权，不走纯查询短路径）
interfaces/http/<入口>                multipart 上传、FileResponses.attachment 下载；上传接口路径以 /files 结尾
```

- 范围外的业务对象按不存在处理，不泄露其存在；文件不是该对象的附件时同样按不存在处理。
- 只在核对完业务对象之后才保存文件，未授权的请求不会写入存储。
- 上传接口路径以 `/files` 结尾，入口 Nginx 才放宽请求体上限，须与服务的 `spring.servlet.multipart.max-file-size: 20MB` 一致，数值见[部署参考](../../../deploy/README.md#安全要点)。

模板的便笺示例（`--with-example`）提供附件：`POST /api/notes/{id}/files` 上传（字段 `file`，文本、PDF、PNG、JPEG，10MB 以内，每条最多 10 个）、`GET /api/notes/{id}/files` 列出、`GET /api/notes/{id}/files/{fileId}` 下载、`DELETE /api/notes/{id}/files/{fileId}` 删除。

## 部署

命名卷、备份恢复与改用 S3 见[部署参考](../../../deploy/README.md#文件存储)。
