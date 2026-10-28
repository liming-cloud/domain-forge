-- 消息组件的 Outbox 表（结构见 domain-forge-messaging 的 domain-forge/messaging/schema.sql）：
-- 集成事件与业务数据同事务写入，提交后由中继发送；发送成功的记录保留 7 天后清理。
CREATE TABLE domainforge_outbox
(
    id              bigserial PRIMARY KEY,
    event_id        varchar(36)  NOT NULL UNIQUE,
    topic           varchar(127) NOT NULL,
    event_type      varchar(127) NOT NULL,
    event_version   integer      NOT NULL CHECK (event_version > 0),
    event_key       varchar(128) NOT NULL,
    order_key       varchar(128),
    payload         text         NOT NULL,
    occurred_at     timestamptz  NOT NULL DEFAULT now(),
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    last_error      varchar(200)
);
CREATE INDEX domainforge_outbox_due ON domainforge_outbox (next_attempt_at, id) WHERE sent_at IS NULL;
CREATE INDEX domainforge_outbox_order ON domainforge_outbox (order_key, id) WHERE sent_at IS NULL AND order_key IS NOT NULL;
CREATE INDEX domainforge_outbox_sent ON domainforge_outbox (sent_at) WHERE sent_at IS NOT NULL;
