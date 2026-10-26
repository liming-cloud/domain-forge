-- 示例上下文的私有表：只由本服务访问；owner_id、unit_id 是 IAM 的稳定 ID，不建跨库外键。
CREATE TABLE note
(
    id       bigint PRIMARY KEY,
    owner_id bigint        NOT NULL CHECK (owner_id > 0),
    unit_id  bigint CHECK (unit_id > 0),
    title    varchar(100)  NOT NULL CHECK (length(title) > 0),
    content  varchar(2000) NOT NULL,
    version  bigint        NOT NULL CHECK (version > 0)
);
CREATE INDEX note_owner ON note (owner_id, id);
CREATE INDEX note_unit ON note (unit_id, id);
