INSERT INTO app_user (id,username,password_hash,role) VALUES
    (7901,'cart_api_buyer_1','unused-test-hash','USER'),
    (7902,'cart_api_buyer_2','unused-test-hash','USER');

INSERT INTO product (id,name,price,stock,status) VALUES
    (10101,'购物车测试键盘',129.00,5,'ON_SALE'),
    (10102,'购物车下架商品',20.00,8,'OFF_SALE'),
    (10103,'购物车零库存商品',30.00,0,'ON_SALE');
