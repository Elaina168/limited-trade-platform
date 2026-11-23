-- 在 015 之后执行一次。单行锁串行项目内的预算决策。
CREATE TABLE agent_budget_gate (
    id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO agent_budget_gate(id) VALUES (1);

CREATE TABLE agent_budget_request (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    quota_day DATE NOT NULL,
    state ENUM('OPEN','CLOSED','EXPIRED') NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    KEY idx_agent_budget_user_day (user_id, quota_day),
    KEY idx_agent_budget_open_lease (state, lease_until)
) ENGINE=InnoDB;

CREATE TABLE agent_budget_call (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    billing_month DATE NOT NULL,
    state ENUM('RESERVED','SETTLED','RELEASED','UNKNOWN') NOT NULL,
    model VARCHAR(100) NOT NULL,
    price_version VARCHAR(100) NOT NULL,
    input_rate INT NOT NULL,
    output_rate INT NOT NULL,
    estimated_input_tokens INT NOT NULL,
    max_output_tokens INT NOT NULL,
    reserved_micros BIGINT NOT NULL,
    actual_input_tokens INT NULL,
    actual_output_tokens INT NULL,
    actual_total_tokens BIGINT NULL,
    charged_micros BIGINT NULL,
    KEY idx_agent_budget_billing_month (billing_month),
    CONSTRAINT fk_agent_budget_request FOREIGN KEY (request_id) REFERENCES agent_budget_request(id)
) ENGINE=InnoDB;
