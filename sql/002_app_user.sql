-- 已有项目在开发库和测试库中各执行一次；不覆盖已有表。
CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户编号',
    username VARCHAR(50) NOT NULL COMMENT '登录名',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt密码摘要',
    role VARCHAR(20) NOT NULL COMMENT '角色：USER 或 ADMIN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
