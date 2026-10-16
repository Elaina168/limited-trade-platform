-- 开发库和测试库分别执行一次；保留成交快照和请求幂等约束。
CREATE TABLE trade_order (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单编号',
    user_id BIGINT NOT NULL COMMENT '购买用户编号',
    product_id BIGINT NOT NULL COMMENT '商品编号',
    product_name VARCHAR(100) NOT NULL COMMENT '下单时的商品名称',
    unit_price DECIMAL(10, 2) NOT NULL COMMENT '成交单价',
    quantity INT NOT NULL COMMENT '购买数量',
    total_amount DECIMAL(20, 2) NOT NULL COMMENT '订单应付金额',
    status VARCHAR(20) NOT NULL COMMENT '当前阶段为PENDING_PAYMENT',
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '下单请求标识',
    created_at DATETIME NOT NULL COMMENT '下单时间',
    expires_at DATETIME NOT NULL COMMENT '支付截止时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_order_user_request (user_id, request_id),
    KEY idx_trade_order_user_id (user_id, id),
    KEY idx_trade_order_product (product_id),
    CONSTRAINT fk_trade_order_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_trade_order_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';
