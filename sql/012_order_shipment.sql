-- 在011之后，应用停止时对开发库和测试库各执行一次；只新增空发货表，不补造历史履约记录。
CREATE TABLE order_shipment (
    order_id BIGINT NOT NULL,
    carrier VARCHAR(50) NOT NULL COMMENT '人工登记承运方，不代表已对接物流服务',
    tracking_no VARCHAR(64) NOT NULL COMMENT '人工登记运单号',
    shipped_at DATETIME NOT NULL COMMENT '本系统登记发货时间',
    received_at DATETIME NULL COMMENT '买家确认收货时间',
    PRIMARY KEY (order_id),
    CONSTRAINT fk_order_shipment_order FOREIGN KEY (order_id) REFERENCES trade_order(id) ON DELETE CASCADE,
    CONSTRAINT chk_order_shipment_time CHECK (received_at IS NULL OR received_at >= shipped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='整单发货与收货记录';

ALTER TABLE trade_order
    MODIFY COLUMN status VARCHAR(20) NOT NULL
    COMMENT 'PENDING_PAYMENT、PAID、CANCELED、CLOSED、SHIPPED、COMPLETED';
