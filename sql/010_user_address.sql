-- 在009之后，对开发库和测试库各执行一次；只创建空地址表。
CREATE TABLE user_address (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '所属用户编号',
    recipient_name VARCHAR(50) NOT NULL COMMENT '收货人',
    phone VARCHAR(11) NOT NULL COMMENT '大陆手机号',
    province VARCHAR(50) NOT NULL,
    city VARCHAR(50) NOT NULL,
    district VARCHAR(50) NOT NULL,
    detail_address VARCHAR(200) NOT NULL,
    is_default TINYINT NOT NULL DEFAULT 0 COMMENT '默认标记：0否，1是',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_address_owner_default (user_id,is_default,id),
    CONSTRAINT fk_user_address_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT chk_user_address_default CHECK (is_default IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收货地址';
