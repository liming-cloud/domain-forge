-- 文件存储组件的文件信息表（结构见 domain-forge-file-storage 的 domain-forge/files/schema.sql）：
-- 与业务数据同事务写入；删除先标记 deleted_at，超过保留期后由清理任务删除对象与记录。
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

-- 便笺附件：只记录文件 ID，文件信息与内容由文件存储组件管理。
CREATE TABLE note_file
(
    note_id  bigint      NOT NULL REFERENCES note (id),
    file_id  varchar(36) NOT NULL REFERENCES domainforge_file (id),
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (note_id, file_id)
);
