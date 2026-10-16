-- 在 004_order_payment_cancel.sql 之后执行；保留已有订单数据。
ALTER TABLE trade_order
    MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT 'PENDING_PAYMENT、PAID、CANCELED、CLOSED',
    ADD INDEX idx_trade_order_status_expires (status, expires_at);
