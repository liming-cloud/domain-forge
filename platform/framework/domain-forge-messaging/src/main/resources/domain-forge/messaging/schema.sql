-- domain-forge-messaging 的表结构：发布方需要 domainforge_outbox，消费方需要 domainforge_inbox。
-- 服务在自己的 Flyway 迁移中创建这两张表（复制本文件中需要的部分），表属于服务自己的 schema。
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

CREATE TABLE domainforge_inbox
(
    consumer_group varchar(127) NOT NULL,
    event_id       varchar(36)  NOT NULL,
    received_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
CREATE INDEX domainforge_inbox_received ON domainforge_inbox (received_at);
