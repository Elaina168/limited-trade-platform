-- 在012之后对开发库、测试库各执行一次，不修改历史订单的应付金额。
CREATE TABLE activity_coupon (
    id BIGINT NOT NULL AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    threshold_amount DECIMAL(10,2) NOT NULL,
    discount_amount DECIMAL(10,2) NOT NULL,
    total_quantity INT NOT NULL,
    remaining_quantity INT NOT NULL,
    claim_starts_at DATETIME NOT NULL,
    claim_ends_at DATETIME NOT NULL,
    use_starts_at DATETIME NOT NULL,
    use_ends_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
    CONSTRAINT chk_coupon_amount CHECK (discount_amount > 0 AND threshold_amount > discount_amount),
    CONSTRAINT chk_coupon_quantity CHECK (total_quantity > 0 AND remaining_quantity BETWEEN 0 AND total_quantity),
    CONSTRAINT chk_coupon_time CHECK (claim_starts_at < claim_ends_at AND use_starts_at < use_ends_at AND claim_ends_at <= use_ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动专用满减券，不支持创建后修改规则';

CREATE TABLE user_coupon (
    id BIGINT NOT NULL AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    claimed_at DATETIME NOT NULL,
    order_id BIGINT NULL COMMENT '当前占用或已核销订单，释放后为空',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_coupon_owner (coupon_id,user_id),
    UNIQUE KEY uk_user_coupon_order (order_id),
    KEY idx_user_coupon_user (user_id,id),
    CONSTRAINT fk_user_coupon_template FOREIGN KEY (coupon_id) REFERENCES activity_coupon(id),
    CONSTRAINT fk_user_coupon_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_user_coupon_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT chk_user_coupon_state CHECK ((status='AVAILABLE' AND order_id IS NULL) OR (status IN ('RESERVED','USED') AND order_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每人每种券限领一张，过期由规则时间判断';

CREATE TABLE order_coupon (
    order_id BIGINT NOT NULL,
    user_coupon_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    threshold_amount DECIMAL(10,2) NOT NULL,
    discount_amount DECIMAL(10,2) NOT NULL,
    use_starts_at DATETIME NOT NULL,
    use_ends_at DATETIME NOT NULL,
    PRIMARY KEY (order_id),
    KEY idx_order_coupon_user_coupon (user_coupon_id),
    CONSTRAINT fk_order_coupon_order FOREIGN KEY (order_id) REFERENCES trade_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_coupon_user_coupon FOREIGN KEY (user_coupon_id) REFERENCES user_coupon(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交优惠规则快照，取消后仍保留';

ALTER TABLE trade_order
    ADD COLUMN discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '成交优惠金额',
    ADD COLUMN original_amount DECIMAL(20,2) GENERATED ALWAYS AS (total_amount + discount_amount) STORED COMMENT '优惠前金额，历史订单优惠为0',
    ADD CONSTRAINT chk_order_discount CHECK (discount_amount >= 0);
