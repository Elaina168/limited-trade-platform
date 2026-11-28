CREATE TABLE product_search_pending (
    product_id BIGINT NOT NULL COMMENT '待同步商品编号；0用作同步任务互斥行',
    change_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '每次商品变更生成的新标识',
    changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id),
    KEY idx_product_search_pending_age (changed_at, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待同步至商品搜索索引的商品';

INSERT INTO product_search_pending (product_id, change_id) VALUES (0, '');
