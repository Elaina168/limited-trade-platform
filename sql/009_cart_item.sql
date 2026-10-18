-- 在008之后，对开发库和测试库各执行一次；只创建空购物车表。
CREATE TABLE cart_item (
    user_id BIGINT NOT NULL COMMENT '所属用户编号',
    product_id BIGINT NOT NULL COMMENT '商品编号',
    quantity INT NOT NULL COMMENT '意向购买数量，不占用库存',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次加入时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变更时间',
    PRIMARY KEY (user_id, product_id),
    KEY idx_cart_item_product (product_id),
    CONSTRAINT fk_cart_item_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT chk_cart_item_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户购物车购买意向';
