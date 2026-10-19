INSERT INTO app_user (id, username, password_hash, role)
VALUES (7301, 'admin_query_buyer_one', 'unused-test-hash', 'USER'),
       (7302, 'admin_query_buyer_two', 'unused-test-hash', 'USER');

INSERT INTO product (id, name, price, stock, status)
VALUES (9301, '管理查询测试商品', 20.00, 10, 'ON_SALE');

-- 查询夹具直接覆盖各状态，不通过接口生成交易。
INSERT INTO trade_order (id, user_id,
                         total_amount, status, request_id, created_at, expires_at, paid_at, ended_at)
VALUES
    (9301,7301,40,'PENDING_PAYMENT','admin-query-1','2026-10-16 10:00:00','2026-10-16 10:15:00',NULL,NULL),
    (9302,7302,20,'PAID','admin-query-2','2026-10-16 10:01:00','2026-10-16 10:16:00','2026-10-16 10:02:00',NULL),
    (9303,7301,40,'CANCELED','admin-query-3','2026-10-16 10:02:00','2026-10-16 10:17:00',NULL,'2026-10-16 10:03:00'),
    (9304,7302,20,'CLOSED','admin-query-4','2026-10-16 10:03:00','2026-10-16 10:18:00',NULL,'2026-10-16 10:18:00'),
    (9305,7301,40,'PENDING_PAYMENT','admin-query-5','2026-10-16 10:04:00','2026-10-16 10:19:00',NULL,NULL);

INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount)
SELECT id,9301,'管理查询测试商品',20,CASE WHEN total_amount=40 THEN 2 ELSE 1 END,total_amount FROM trade_order WHERE id BETWEEN 9301 AND 9305;
