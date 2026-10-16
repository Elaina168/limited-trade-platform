INSERT INTO app_user (id, username, password_hash, role)
VALUES
    (7101, 'buyer_a', 'unused-test-hash', 'USER'),
    (7102, 'buyer_b', 'unused-test-hash', 'USER');

INSERT INTO product (id, name, price, stock, status)
VALUES
    (9101, '订单测试键盘', 129.00, 5, 'ON_SALE'),
    (9102, '订单测试下架商品', 59.90, 5, 'OFF_SALE');
