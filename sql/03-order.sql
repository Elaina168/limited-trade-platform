CREATE TABLE IF NOT EXISTS lab_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    coupon_id BIGINT NULL,
    total_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lab_order_request (user_id, request_id)
) ENGINE=InnoDB;

-- 反查工作与尚未提交的订单独立提交，不能对订单主表建立外键。
CREATE TABLE IF NOT EXISTS lab_order_outbox (
    order_id BIGINT NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (order_id, event_type),
    KEY idx_lab_order_outbox_dispatch (event_type, status, next_attempt_at, due_at)
) ENGINE=InnoDB;
