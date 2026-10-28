-- 消费示例：便笺统计（读模型）与消息组件的去重表（结构见 domain-forge-messaging 的 domain-forge/messaging/schema.sql）。
CREATE TABLE note_tally
(
    owner_id bigint PRIMARY KEY CHECK (owner_id > 0),
    notes    bigint NOT NULL CHECK (notes > 0)
);

CREATE TABLE domainforge_inbox
(
    consumer_group varchar(127) NOT NULL,
    event_id       varchar(36)  NOT NULL,
    received_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
CREATE INDEX domainforge_inbox_received ON domainforge_inbox (received_at);
