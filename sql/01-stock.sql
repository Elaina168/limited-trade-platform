CREATE TABLE IF NOT EXISTS stock_product (
    id BIGINT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    stock INT NOT NULL CHECK (stock >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stock_lock_task (
    order_id BIGINT PRIMARY KEY,
    product_id BIGINT NULL,
    quantity INT NULL CHECK (quantity > 0),
    status VARCHAR(12) NOT NULL CHECK (status IN ('NEW','LOCKED','RELEASED')),
    next_check_at DATETIME(3) NULL,
    trace_id CHAR(32) NULL,
    CHECK (status = 'RELEASED' OR (product_id IS NOT NULL AND quantity IS NOT NULL AND trace_id IS NOT NULL)),
    KEY idx_stock_check (status, next_check_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO stock_product(id, name, price, stock) VALUES (1, '便携机械键盘', 19.90, 50)
ON DUPLICATE KEY UPDATE id=stock_product.id;
