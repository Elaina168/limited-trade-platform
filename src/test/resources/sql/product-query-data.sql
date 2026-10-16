-- 故意按非编号顺序插入，检查接口是否明确按编号排序。
INSERT INTO product (id, name, price, stock, status)
VALUES
    (9002, '测试鼠标', 59.90, 20, 'ON_SALE'),
    (9001, '测试键盘', 129.00, 10, 'ON_SALE'),
    (9003, '测试下架商品', 199.00, 5, 'OFF_SALE');
