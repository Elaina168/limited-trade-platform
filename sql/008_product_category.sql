-- 在007之后，对开发库和测试库各执行一次；旧商品保留未分类，不回填业务数据。
CREATE TABLE product_category (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类编号',
    name VARCHAR(50) NOT NULL COMMENT '一级分类名称',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一级商品分类';

ALTER TABLE product
    ADD COLUMN category_id BIGINT NULL COMMENT '分类编号，空值表示未分类',
    ADD KEY idx_product_category_status_id (category_id, status, id),
    ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product_category (id);
