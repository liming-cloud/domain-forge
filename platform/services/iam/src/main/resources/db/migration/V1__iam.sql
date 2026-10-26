-- IAM 首个切片的数据库基线：一套系统一个 IAM 库，无全局用户或超级权限旁路。
-- 业务 ID 为应用分配的雪花 bigint，写入前确定。

-- 管理守卫：串行化首次初始化与"至少保留一个完整管理者"的检查。
CREATE TABLE iam_administration_guard
(
    id          smallint PRIMARY KEY CHECK (id = 1),
    initialized boolean NOT NULL DEFAULT false
);
INSERT INTO iam_administration_guard (id)
VALUES (1);

CREATE TABLE iam_account
(
    id                 bigint PRIMARY KEY,
    login_name         varchar(64)  NOT NULL UNIQUE CHECK (login_name ~ '^[a-z0-9._-]{3,64}$'),
    credential_digest  varchar(512) NOT NULL CHECK (length(credential_digest) > 0),
    credential_version bigint       NOT NULL CHECK (credential_version > 0),
    version            bigint       NOT NULL CHECK (version > 0),
    enabled            boolean      NOT NULL,
    permissions        varchar(192) NOT NULL DEFAULT '' CHECK (
        permissions = '' OR string_to_array(permissions, ',') <@ ARRAY [
            'iam:invitation:create', 'iam:account:read', 'iam:account:state', 'iam:grant:manage', 'iam:audit:read'
            ]::text[])
);

CREATE TABLE iam_invitation
(
    id            bigint PRIMARY KEY,
    login_name    varchar(64) NOT NULL CHECK (login_name ~ '^[a-z0-9._-]{3,64}$'),
    secret_digest char(64)    NOT NULL UNIQUE CHECK (secret_digest ~ '^[a-f0-9]{64}$'),
    expires_at    timestamptz NOT NULL,
    created_by    bigint      NOT NULL REFERENCES iam_account (id),
    consumed_by   bigint UNIQUE REFERENCES iam_account (id),
    invalidated   boolean     NOT NULL DEFAULT false,
    CHECK (consumed_by IS NULL OR NOT invalidated)
);
CREATE INDEX iam_invitation_expiry ON iam_invitation (expires_at);

-- 关键管理变更的审计，与业务变更同事务写入。actor 为账号 ID 或受控来源标识。
CREATE TABLE iam_audit
(
    id          bigint PRIMARY KEY,
    actor       varchar(128) NOT NULL,
    target_id   bigint       NOT NULL,
    action      varchar(64)  NOT NULL,
    occurred_at timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX iam_audit_time ON iam_audit (occurred_at, id);

-- SQL 会话许可：只有许可未撤销、未过期且凭据版本与账号一致时，原生会话才被接受。
CREATE TABLE iam_session_grant
(
    token_digest       char(64)    PRIMARY KEY CHECK (token_digest ~ '^[a-f0-9]{64}$'),
    account_id         bigint      NOT NULL REFERENCES iam_account (id),
    credential_version bigint      NOT NULL CHECK (credential_version > 0),
    expires_at         timestamptz NOT NULL,
    revoked            boolean     NOT NULL DEFAULT false
);
CREATE INDEX iam_session_expiry ON iam_session_grant (expires_at);
CREATE INDEX iam_session_account ON iam_session_grant (account_id);
