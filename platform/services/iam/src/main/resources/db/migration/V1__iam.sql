-- IAM 数据库基线：一套系统一个 IAM 库，无全局用户或超级权限旁路。
-- 业务 ID 为应用分配的雪花 bigint，写入前确定；内置根组织与 SYSTEM_ADMIN 角色使用固定 ID 1。

-- 管理守卫：串行化首次初始化与"至少保留一名启用的系统管理员"的检查。
CREATE TABLE iam_administration_guard
(
    id          smallint PRIMARY KEY CHECK (id = 1),
    initialized boolean NOT NULL DEFAULT false
);
INSERT INTO iam_administration_guard (id)
VALUES (1);

-- 结构协调锁：组织树、菜单树与权限目录各一行，结构变更在事务内先锁对应行。
CREATE TABLE iam_coordination
(
    name varchar(32) PRIMARY KEY
);
INSERT INTO iam_coordination (name)
VALUES ('organization'),
       ('menu'),
       ('catalog');

CREATE TABLE iam_account
(
    id                 bigint PRIMARY KEY,
    login_name         varchar(64)  NOT NULL UNIQUE CHECK (login_name ~ '^[a-z0-9._-]{3,64}$'),
    credential_digest  varchar(512) NOT NULL CHECK (length(credential_digest) > 0),
    credential_version bigint       NOT NULL CHECK (credential_version > 0),
    version            bigint       NOT NULL CHECK (version > 0),
    enabled            boolean      NOT NULL
);

-- 权限目录：服务命名空间内的精确操作码。scoped 表示授予时需要数据范围；system_only 只能授予系统角色。
CREATE TABLE iam_permission
(
    code        varchar(128) PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]{1,31}(:[a-z][a-z0-9-]{0,47}){1,3}$'),
    namespace   varchar(32) NOT NULL CHECK (code LIKE namespace || ':%'),
    name        varchar(64) NOT NULL CHECK (length(name) > 0),
    scoped      boolean     NOT NULL,
    system_only boolean     NOT NULL,
    CHECK (NOT (scoped AND system_only))
);
CREATE INDEX iam_permission_namespace ON iam_permission (namespace);
INSERT INTO iam_permission (code, namespace, name, scoped, system_only)
VALUES ('iam:invitation:create', 'iam', '创建注册邀请', false, false),
       ('iam:account:read', 'iam', '查询账号', false, false),
       ('iam:account:state', 'iam', '启用或禁用账号', false, false),
       ('iam:audit:read', 'iam', '查询审计', false, false),
       ('iam:org:read', 'iam', '查询组织', false, false),
       ('iam:role:read', 'iam', '查询角色', false, false),
       ('iam:menu:read', 'iam', '查询菜单目录', false, false),
       ('iam:catalog:read', 'iam', '查询权限目录', false, false),
       ('iam:org:manage', 'iam', '管理组织', false, true),
       ('iam:member:manage', 'iam', '管理成员归属', false, true),
       ('iam:role:manage', 'iam', '管理角色与授权范围', false, true),
       ('iam:account:role', 'iam', '分配账号角色', false, true),
       ('iam:catalog:manage', 'iam', '安装权限目录', false, true),
       ('iam:menu:manage', 'iam', '管理菜单', false, true);

-- 组织：一套系统一棵树，邻接表；根节点 ID 固定为 1，不能移动、停用或删除。
CREATE TABLE iam_org_unit
(
    id        bigint PRIMARY KEY,
    parent_id bigint REFERENCES iam_org_unit (id),
    name      varchar(64) NOT NULL CHECK (length(name) > 0),
    enabled   boolean     NOT NULL,
    version   bigint      NOT NULL CHECK (version > 0),
    CHECK ((parent_id IS NULL) = (id = 1)),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX iam_org_unit_sibling_name ON iam_org_unit (parent_id, name);
CREATE INDEX iam_org_unit_parent ON iam_org_unit (parent_id);
INSERT INTO iam_org_unit (id, parent_id, name, enabled, version)
VALUES (1, NULL, '根组织', true, 1);

CREATE TABLE iam_membership
(
    account_id bigint  NOT NULL REFERENCES iam_account (id),
    unit_id    bigint  NOT NULL REFERENCES iam_org_unit (id),
    is_primary boolean NOT NULL,
    PRIMARY KEY (account_id, unit_id)
);
CREATE UNIQUE INDEX iam_membership_primary ON iam_membership (account_id) WHERE is_primary;
CREATE INDEX iam_membership_unit ON iam_membership (unit_id);

-- 角色：SYSTEM_ADMIN 为受保护系统角色，固定 ID 1，授予全部 IAM 管理权限且不可修改授权或停用。
CREATE TABLE iam_role
(
    id      bigint PRIMARY KEY,
    code    varchar(64) NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z][A-Za-z0-9_-]{1,63}$'),
    name    varchar(64) NOT NULL CHECK (length(name) > 0),
    enabled boolean     NOT NULL,
    system  boolean     NOT NULL,
    version bigint      NOT NULL CHECK (version > 0),
    CHECK (system = (id = 1))
);
INSERT INTO iam_role (id, code, name, enabled, system, version)
VALUES (1, 'SYSTEM_ADMIN', '系统管理员', true, true, 1);

-- 授予：权限与范围绑定。CUSTOM_DEPARTMENTS 的组织列在 iam_role_grant_unit。
CREATE TABLE iam_role_grant
(
    role_id         bigint       NOT NULL REFERENCES iam_role (id),
    permission_code varchar(128) NOT NULL REFERENCES iam_permission (code),
    scope           varchar(32)  NOT NULL CHECK (scope IN ('NOT_APPLICABLE', 'SELF', 'DEPARTMENT',
                                                           'DEPARTMENT_AND_DESCENDANTS', 'CUSTOM_DEPARTMENTS', 'ALL')),
    PRIMARY KEY (role_id, permission_code)
);
CREATE INDEX iam_role_grant_permission ON iam_role_grant (permission_code);
INSERT INTO iam_role_grant (role_id, permission_code, scope)
SELECT 1, code, 'NOT_APPLICABLE'
FROM iam_permission
WHERE namespace = 'iam';

CREATE TABLE iam_role_grant_unit
(
    role_id         bigint       NOT NULL,
    permission_code varchar(128) NOT NULL,
    unit_id         bigint       NOT NULL REFERENCES iam_org_unit (id),
    PRIMARY KEY (role_id, permission_code, unit_id),
    FOREIGN KEY (role_id, permission_code) REFERENCES iam_role_grant (role_id, permission_code) ON DELETE CASCADE
);
CREATE INDEX iam_role_grant_unit_unit ON iam_role_grant_unit (unit_id);

CREATE TABLE iam_account_role
(
    account_id bigint NOT NULL REFERENCES iam_account (id),
    role_id    bigint NOT NULL REFERENCES iam_role (id),
    PRIMARY KEY (account_id, role_id)
);
CREATE INDEX iam_account_role_role ON iam_account_role (role_id);

-- 菜单：目录／页面／按钮树；按钮不能作父节点，页面和按钮必须关联目录中的权限。
CREATE TABLE iam_menu_item
(
    id              bigint PRIMARY KEY,
    parent_id       bigint REFERENCES iam_menu_item (id),
    type            varchar(16)  NOT NULL CHECK (type IN ('DIRECTORY', 'PAGE', 'BUTTON')),
    code            varchar(64)  NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9:._-]{1,63}$'),
    name            varchar(64)  NOT NULL CHECK (length(name) > 0),
    path            varchar(256),
    sort_order      integer      NOT NULL,
    permission_code varchar(128) REFERENCES iam_permission (code),
    enabled         boolean      NOT NULL,
    version         bigint       NOT NULL CHECK (version > 0),
    CHECK (type = 'DIRECTORY' OR permission_code IS NOT NULL),
    CHECK (type <> 'BUTTON' OR path IS NULL),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX iam_menu_item_parent ON iam_menu_item (parent_id);
CREATE INDEX iam_menu_item_permission ON iam_menu_item (permission_code);

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
    token_digest       char(64) PRIMARY KEY CHECK (token_digest ~ '^[a-f0-9]{64}$'),
    account_id         bigint      NOT NULL REFERENCES iam_account (id),
    credential_version bigint      NOT NULL CHECK (credential_version > 0),
    expires_at         timestamptz NOT NULL,
    revoked            boolean     NOT NULL DEFAULT false
);
CREATE INDEX iam_session_expiry ON iam_session_grant (expires_at);
CREATE INDEX iam_session_account ON iam_session_grant (account_id);
