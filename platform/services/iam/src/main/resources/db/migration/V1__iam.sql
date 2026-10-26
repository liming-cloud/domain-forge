-- 身份首个切片仅有一个本项目数据库；不创建全局用户或超级权限旁路。
CREATE TABLE iam_administration_guard
(
    id          smallint PRIMARY KEY CHECK (id = 1),
    initialized boolean NOT NULL DEFAULT false
);
INSERT INTO iam_administration_guard(id)
VALUES (1);
CREATE TABLE iam_account
(
    id         uuid PRIMARY KEY,
    login_name varchar(64) NOT NULL UNIQUE CHECK (login_name ~ '^[a-z0-9._-]{3,64}$'
) ,
  credential_digest varchar(512) NOT NULL CHECK (length(credential_digest)>0),
  credential_version bigint NOT NULL CHECK (credential_version>0),
  version bigint NOT NULL CHECK (version>0),
  enabled boolean NOT NULL,
  permissions varchar(192) NOT NULL DEFAULT '' CHECK (
    permissions='' OR string_to_array(permissions,',') <@ ARRAY[
      'iam:invitation:create','iam:account:read','iam:account:state','iam:grant:manage','iam:audit:read']::text[])
);
CREATE TABLE iam_invitation
(
    id         uuid PRIMARY KEY,
    login_name varchar(64) NOT NULL CHECK (login_name ~ '^[a-z0-9._-]{3,64}$'
) ,
  secret_digest char(64) NOT NULL UNIQUE CHECK (secret_digest ~ '^[a-f0-9]{64}$'),
  expires_at timestamptz NOT NULL,
  created_by uuid NOT NULL REFERENCES iam_account(id),
  consumed_by uuid UNIQUE REFERENCES iam_account(id),
  invalidated boolean NOT NULL DEFAULT false,
  CHECK (consumed_by IS NULL OR NOT invalidated)
);
CREATE INDEX iam_invitation_expiry ON iam_invitation (expires_at);
CREATE TABLE iam_operation
(
    actor_scope    varchar(128) NOT NULL,
    operation_id   uuid         NOT NULL,
    fingerprint    char(64)     NOT NULL,
    target_id      uuid         NOT NULL,
    result_version bigint       NOT NULL,
    action         varchar(64)  NOT NULL,
    expires_at     timestamptz  NOT NULL DEFAULT (clock_timestamp() + interval '24 hours'),
    PRIMARY KEY (actor_scope, operation_id)
);
CREATE INDEX iam_operation_expiry ON iam_operation (expires_at);

CREATE TABLE iam_audit
(
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id   uuid         NOT NULL,
    actor          varchar(128) NOT NULL,
    target_id      uuid         NOT NULL,
    action         varchar(64)  NOT NULL,
    outcome        varchar(64)  NOT NULL,
    source         varchar(32)  NOT NULL,
    occurred_at    timestamptz           DEFAULT clock_timestamp(),
    recorded_at    timestamptz  NOT NULL DEFAULT clock_timestamp(),
    provenance     varchar(512),
    missing_fields varchar(512),
    CHECK (source!='observed' OR occurred_at IS NOT NULL
) ,
  UNIQUE(operation_id,actor,target_id,action,source)
);
CREATE INDEX iam_audit_time ON iam_audit (occurred_at, id);
CREATE INDEX iam_audit_retention ON iam_audit (recorded_at, id);

CREATE TABLE iam_session_grant
(
    token_digest char(64) PRIMARY KEY CHECK (token_digest ~ '^[a-f0-9]{64}$'
) ,
  account_id uuid NOT NULL REFERENCES iam_account(id),
  credential_version bigint NOT NULL CHECK(credential_version>0),
  expires_at timestamptz NOT NULL,
  revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX iam_session_expiry ON iam_session_grant (expires_at);
CREATE INDEX iam_session_account ON iam_session_grant (account_id);
CREATE TABLE iam_login_protection
(
    id            smallint PRIMARY KEY CHECK (id = 1),
    generation    varchar(160),
    blocked_until timestamptz NOT NULL DEFAULT 'infinity'
);
INSERT INTO iam_login_protection(id)
VALUES (1);
