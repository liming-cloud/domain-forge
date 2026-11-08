-- 示例上下文的私有表：只由本服务访问；owner_id、unit_id 是 IAM 的稳定 ID，不建跨库外键。
-- 订单行属于订单聚合，随订单一起读写；金额以分为单位。
CREATE TABLE orders
(
    id       bigint PRIMARY KEY,
    owner_id bigint      NOT NULL CHECK (owner_id > 0),
    unit_id  bigint CHECK (unit_id > 0),
    status   varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'PAID', 'CANCELLED')),
    version  bigint      NOT NULL CHECK (version > 0)
);
CREATE INDEX orders_owner ON orders (owner_id, id);
CREATE INDEX orders_unit ON orders (unit_id, id);

CREATE TABLE order_line
(
    order_id         bigint       NOT NULL REFERENCES orders (id),
    id               bigint       NOT NULL,
    sku              varchar(64)  NOT NULL CHECK (length(sku) > 0),
    quantity         int          NOT NULL CHECK (quantity > 0),
    unit_price_cents bigint       NOT NULL CHECK (unit_price_cents > 0),
    PRIMARY KEY (order_id, id),
    UNIQUE (order_id, sku)
);
