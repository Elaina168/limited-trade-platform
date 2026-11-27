-- 在016之后对开发库和测试库执行；per_user_limit为空表示不额外限制单人件数，正整数表示单人累计有效抢购上限（例如1为一人一单）。
ALTER TABLE activity
    ADD COLUMN per_user_limit INT NULL COMMENT '单人累计有效抢购上限；NULL表示仅受活动总名额约束',
    ADD CONSTRAINT chk_activity_per_user_limit CHECK (per_user_limit IS NULL OR per_user_limit > 0);

UPDATE activity SET per_user_limit = 1 WHERE per_user_limit IS NULL;

CREATE TABLE activity_purchase_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '抢购预约编号',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端请求幂等标识',
    user_id BIGINT NOT NULL COMMENT '抢购用户编号',
    activity_id BIGINT NOT NULL COMMENT '活动编号',
    product_id BIGINT NOT NULL COMMENT '商品编号',
    quantity INT NOT NULL COMMENT '抢购数量',
    address_id BIGINT NULL COMMENT '收货地址编号',
    user_coupon_id BIGINT NULL COMMENT '本人优惠券编号',
    status VARCHAR(20) NOT NULL COMMENT 'QUEUED、CREATED、FAILED、CANCELED',
    order_id BIGINT NULL COMMENT '关联落库订单编号',
    fail_reason VARCHAR(200) NULL COMMENT '建单失败或取消原因',
    reserved_at DATETIME NOT NULL COMMENT '预扣受理时间（秒级）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_seckill_user_request (user_id, request_id),
    KEY idx_seckill_activity_user_status (activity_id, user_id, status),
    KEY idx_seckill_order_id (order_id),
    KEY idx_seckill_status_created (status, created_at),
    CONSTRAINT fk_seckill_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_seckill_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT fk_seckill_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT chk_seckill_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限量活动异步秒杀预约与对账表';
