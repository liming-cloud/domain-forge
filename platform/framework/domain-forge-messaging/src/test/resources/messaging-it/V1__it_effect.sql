-- 测试处理者的效果表：与去重记录同事务写入，用于判定“只生效一次”。消息组件的表由 schema.sql 预先创建。
CREATE TABLE it_effect
(
    event_id   varchar(36) NOT NULL,
    handled_by varchar(64)
);
