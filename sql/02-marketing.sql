CREATE TABLE IF NOT EXISTS marketing_activity (
    id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NOT NULL,
    KEY idx_marketing_activity (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS marketing_coupon (
    id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    threshold_amount DECIMAL(12,2) NOT NULL,
    discount DECIMAL(12,2) NOT NULL,
    remaining_quantity INT NOT NULL CHECK (remaining_quantity >= 0),
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NOT NULL,
    KEY idx_marketing_coupon (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS marketing_coupon_task (
    order_id BIGINT PRIMARY KEY,
    product_id BIGINT NULL,
    coupon_id BIGINT NULL,
    threshold_amount DECIMAL(12,2) NULL,
    discount DECIMAL(12,2) NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('NEW','RESERVED','RELEASED')),
    CHECK (status = 'RELEASED' OR (product_id IS NOT NULL AND coupon_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO marketing_activity(id, product_id, starts_at, ends_at)
VALUES (1, 1, CURRENT_TIMESTAMP - INTERVAL 1 DAY, CURRENT_TIMESTAMP + INTERVAL 30 DAY)
ON DUPLICATE KEY UPDATE id=marketing_activity.id;
INSERT INTO marketing_coupon(id, product_id, name, threshold_amount, discount, remaining_quantity, starts_at, ends_at)
VALUES (1, 1, '满 19 减 5', 19.00, 5.00, 30, CURRENT_TIMESTAMP - INTERVAL 1 DAY, CURRENT_TIMESTAMP + INTERVAL 30 DAY)
ON DUPLICATE KEY UPDATE id=marketing_coupon.id;
