-- 在006之后，对开发库和测试库各执行一次；不回填旧订单、不修改原数据。
CREATE TABLE order_timeout_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '超时事件编号',
    order_id BIGINT NOT NULL COMMENT '关联订单，一单只登记一个超时事件',
    expires_at DATETIME NOT NULL COMMENT '原订单支付截止时间快照',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING待投递、SENT已获Broker接收确认',
    attempt_count BIGINT NOT NULL DEFAULT 0 COMMENT '已提交保存的发送尝试次数',
    next_attempt_at DATETIME NOT NULL COMMENT '下一次允许发送的时间',
    last_error VARCHAR(500) NULL COMMENT '最近一次发送错误摘要',
    created_at DATETIME NOT NULL COMMENT '登记时间',
    sent_at DATETIME NULL COMMENT '标记发送成功的时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_timeout_outbox_order (order_id),
    KEY idx_order_timeout_outbox_pending (status, next_attempt_at, id),
    CONSTRAINT fk_order_timeout_outbox_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT chk_order_timeout_outbox_status CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT chk_order_timeout_outbox_attempt CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单超时消息发送意图';
