-- 在013之后执行一次；只新增Agent会话表，不改动交易数据。
CREATE TABLE agent_conversation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    generation_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    busy_until DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_conversation_owner_expiry (user_id, expires_at),
    CONSTRAINT fk_agent_conversation_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本人Agent会话与生成资格';

CREATE TABLE agent_conversation_turn (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    turn_no INT NOT NULL,
    messages_json MEDIUMTEXT NOT NULL COMMENT '完整成功轮次，不含系统消息',
    PRIMARY KEY (conversation_id, turn_no),
    CONSTRAINT fk_agent_turn_conversation FOREIGN KEY (conversation_id)
        REFERENCES agent_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='最多保留六轮成功历史';
