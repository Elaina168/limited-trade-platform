-- 在005之后，对开发库和测试库各执行一次；历史订单的activity_id保持NULL。
CREATE TABLE activity (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '活动编号',
    product_id BIGINT NOT NULL COMMENT '活动商品编号',
    name VARCHAR(100) NOT NULL COMMENT '活动名称',
    total_quantity INT NOT NULL COMMENT '活动销售数量上限',
    remaining_quantity INT NOT NULL COMMENT '剩余活动名额，不代表预留库存',
    starts_at DATETIME NOT NULL COMMENT '允许下单的开始时间，含边界',
    ends_at DATETIME NOT NULL COMMENT '允许下单的结束时间，不含边界',
    PRIMARY KEY (id),
    KEY idx_activity_product (product_id),
    CONSTRAINT fk_activity_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT chk_activity_quantity CHECK (total_quantity > 0 AND remaining_quantity BETWEEN 0 AND total_quantity),
    CONSTRAINT chk_activity_time CHECK (starts_at < ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='最小限时限量活动';

ALTER TABLE trade_order
    ADD COLUMN activity_id BIGINT NULL COMMENT '活动编号；普通及历史订单为空',
    ADD KEY idx_trade_order_activity (activity_id),
    ADD CONSTRAINT fk_trade_order_activity FOREIGN KEY (activity_id) REFERENCES activity (id);
