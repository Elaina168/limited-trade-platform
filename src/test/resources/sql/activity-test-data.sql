INSERT INTO app_user (id, username, password_hash, role)
VALUES (7401,'activity_buyer','unused-test-hash','USER');

INSERT INTO product (id,name,price,stock,status)
VALUES (9401,'活动测试键盘',20.00,10,'ON_SALE'),
       (9402,'其他商品',30.00,10,'ON_SALE');

INSERT INTO activity (id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at)
VALUES (9401,9401,'限时限量活动',5,5,
        DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 HOUR),DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 HOUR)),
       (9402,9401,'同商品的另一活动',3,3,
        DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 HOUR),DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 HOUR));
