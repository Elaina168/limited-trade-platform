-- 在010之后，应用停止时对开发库和测试库各执行一次；先复制历史快照再移除旧列。
CREATE TABLE order_item (
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(100) NOT NULL COMMENT '成交商品名称快照',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '成交单价',
    quantity INT NOT NULL,
    line_amount DECIMAL(20,2) NOT NULL COMMENT '本明细成交金额',
    activity_id BIGINT NULL,
    PRIMARY KEY (order_id,product_id),
    KEY idx_order_item_product (product_id),
    KEY idx_order_item_activity (activity_id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES trade_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT fk_order_item_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
    CONSTRAINT chk_order_item_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品快照';

CREATE TABLE order_address (
    order_id BIGINT NOT NULL,
    source_address_id BIGINT NOT NULL COMMENT '首次请求的地址编号，仅供幂等比较，不依赖地址簿存续',
    recipient_name VARCHAR(50) NOT NULL,
    phone VARCHAR(11) NOT NULL,
    province VARCHAR(50) NOT NULL,
    city VARCHAR(50) NOT NULL,
    district VARCHAR(50) NOT NULL,
    detail_address VARCHAR(200) NOT NULL,
    PRIMARY KEY (order_id),
    CONSTRAINT fk_order_address_order FOREIGN KEY (order_id) REFERENCES trade_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单收货地址快照';

INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount,activity_id)
SELECT id,product_id,product_name,unit_price,quantity,total_amount,activity_id FROM trade_order;

ALTER TABLE trade_order
    ADD COLUMN order_type VARCHAR(10) NOT NULL DEFAULT 'DIRECT' COMMENT 'DIRECT单商品下单/CART购物车结算',
    ADD CONSTRAINT chk_trade_order_type CHECK (order_type IN ('DIRECT','CART')),
    DROP FOREIGN KEY fk_trade_order_product,
    DROP FOREIGN KEY fk_trade_order_activity,
    DROP INDEX idx_trade_order_product,
    DROP INDEX idx_trade_order_activity,
    DROP COLUMN product_id,
    DROP COLUMN product_name,
    DROP COLUMN unit_price,
    DROP COLUMN quantity,
    DROP COLUMN activity_id;
