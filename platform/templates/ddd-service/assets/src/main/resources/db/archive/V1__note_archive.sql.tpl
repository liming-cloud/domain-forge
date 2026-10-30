-- 归档库（附加数据源 archive，独立数据库）：便笺快照，按便笺 ID 覆盖保存。与主库没有外键与共同事务。
CREATE TABLE note_archive
(
    note_id     bigint PRIMARY KEY,
    owner_id    bigint        NOT NULL,
    unit_id     bigint,
    title       varchar(100)  NOT NULL,
    content     varchar(2000) NOT NULL,
    version     bigint        NOT NULL,
    archived_at timestamptz   NOT NULL
);
