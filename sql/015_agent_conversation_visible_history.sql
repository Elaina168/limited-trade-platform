-- 在 014 之后执行一次。旧模型上下文保留原样。
CREATE TABLE agent_conversation_visible_turn (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    turn_no BIGINT NOT NULL,
    display_json JSON NOT NULL,
    PRIMARY KEY (conversation_id, turn_no),
    CONSTRAINT fk_agent_visible_turn_conversation FOREIGN KEY (conversation_id)
        REFERENCES agent_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本人可见的已提交问答';

-- 只复制仍保留且能确认的首条用户消息和最终回答。旧上下文未保存公开来源。
INSERT INTO agent_conversation_visible_turn(conversation_id, turn_no, display_json)
SELECT conversation_id, turn_no, JSON_OBJECT(
    'question', JSON_UNQUOTE(JSON_EXTRACT(messages, '$[0].text')),
    'answer', JSON_UNQUOTE(JSON_EXTRACT(messages, '$[last].text')),
    'sources', JSON_ARRAY(),
    'knowledgeSources', JSON_ARRAY()
)
FROM (
    SELECT conversation_id, turn_no,
        CASE WHEN JSON_VALID(messages_json) THEN CAST(messages_json AS JSON) END AS messages
    FROM agent_conversation_turn
) AS retained
WHERE JSON_TYPE(messages) = 'ARRAY'
  AND JSON_UNQUOTE(JSON_EXTRACT(messages, '$[0].role')) = 'user'
  AND JSON_UNQUOTE(JSON_EXTRACT(messages, '$[last].role')) = 'assistant'
  AND JSON_TYPE(JSON_EXTRACT(messages, '$[0].text')) = 'STRING'
  AND JSON_TYPE(JSON_EXTRACT(messages, '$[last].text')) = 'STRING'
  AND JSON_LENGTH(JSON_EXTRACT(messages, '$[last].calls')) = 0
  AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(messages, '$[last].text')))) > 0;
