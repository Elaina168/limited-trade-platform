-- 仅在开发库首次初始化时执行；固定编号可避免重复执行时悄悄插入多份数据。
INSERT INTO product (id, name, price, stock, status)
VALUES
    (1, '机械键盘', 129.00, 10, 'ON_SALE'),
    (2, '无线鼠标', 59.90, 20, 'ON_SALE'),
    (3, '游戏耳机', 199.00, 5, 'OFF_SALE');
