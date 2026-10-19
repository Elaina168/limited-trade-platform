INSERT INTO app_user (id, username, password_hash, role)
VALUES
    (7201, 'action_buyer', 'unused-test-hash', 'USER'),
    (7202, 'other_action_buyer', 'unused-test-hash', 'USER');

-- 原有5件，下单购买2件后剩3件。
INSERT INTO product (id, name, price, stock, status)
VALUES (9201, '支付取消测试商品', 20.00, 3, 'ON_SALE');

INSERT INTO trade_order (id, user_id,
                         total_amount, status, request_id, created_at, expires_at)
VALUES (9201, 7201, 40.00, 'PENDING_PAYMENT',
        'action-test', CURRENT_TIMESTAMP, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE));

INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount)
VALUES (9201,9201,'支付取消测试商品',20.00,2,40.00);
