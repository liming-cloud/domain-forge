-- 请假上下文的私有表：只由本服务访问；account_id、unit_id 是 IAM 的稳定 ID，不建跨库外键。
-- 年假账户是聚合根：余额与本人的申请一起读写，版本用于条件更新；余额不能为负由检查约束兜底。
CREATE TABLE leave_ledger
(
    account_id     bigint PRIMARY KEY CHECK (account_id > 0),
    remaining_days int    NOT NULL CHECK (remaining_days >= 0),
    version        bigint NOT NULL CHECK (version > 0)
);

-- 请假申请属于年假账户，随账户一起读写；起止日期都包含在内。
CREATE TABLE leave_request
(
    id         bigint PRIMARY KEY,
    account_id bigint      NOT NULL REFERENCES leave_ledger (account_id),
    unit_id    bigint      NOT NULL CHECK (unit_id > 0),
    start_date date        NOT NULL,
    end_date   date        NOT NULL,
    status     varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CHECK (end_date >= start_date)
);
CREATE INDEX leave_request_account ON leave_request (account_id, id);
CREATE INDEX leave_request_pending ON leave_request (unit_id, id) WHERE status = 'PENDING';
