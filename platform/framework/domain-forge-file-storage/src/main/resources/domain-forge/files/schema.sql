-- domain-forge-file-storage 的表结构：服务在自己的 Flyway 迁移中创建（复制本文件），表属于服务自己的 schema。
-- 业务表只保存文件 ID；删除先标记 deleted_at，超过保留期后由清理任务删除对象与本行。
CREATE TABLE domainforge_file
(
    id           varchar(36)  PRIMARY KEY,
    storage_key  varchar(200) NOT NULL UNIQUE,
    name         varchar(255) NOT NULL CHECK (length(name) > 0),
    content_type varchar(127) NOT NULL,
    size         bigint       NOT NULL CHECK (size > 0),
    sha256       char(64)     NOT NULL,
    uploaded_by  bigint,
    uploaded_at  timestamptz  NOT NULL,
    deleted_at   timestamptz
);
CREATE INDEX domainforge_file_deleted ON domainforge_file (deleted_at) WHERE deleted_at IS NOT NULL;
