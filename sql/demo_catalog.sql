-- 开发库演示数据：在001—013结构准备好后手动执行，不放入应用启动流程。
-- 只追加缺失的演示记录，不修改已有数据；重复执行不补库存、名额或延长有效期。
USE limited_trade_platform;
START TRANSACTION;
SET @demo_now = NOW();

INSERT INTO product_category (name)
SELECT '演示·数码外设' WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE name = '演示·数码外设');
INSERT INTO product_category (name)
SELECT '演示·桌面办公' WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE name = '演示·桌面办公');
INSERT INTO product_category (name)
SELECT '演示·生活好物' WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE name = '演示·生活好物');
INSERT INTO product_category (name)
SELECT '演示·文具收纳' WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE name = '演示·文具收纳');

INSERT INTO product (name, price, stock, status, category_id)
SELECT demo.name, demo.price, demo.stock, 'ON_SALE', c.id
FROM (
    SELECT '演示·轻享无线鼠标' AS name, 59.90 AS price, 120 AS stock, '演示·数码外设' AS category_name
    UNION ALL SELECT '演示·静音机械键盘', 199.00, 80, '演示·数码外设'
    UNION ALL SELECT '演示·多口USB扩展坞', 79.00, 100, '演示·数码外设'
    UNION ALL SELECT '演示·铝合金笔记本支架', 89.00, 100, '演示·桌面办公'
    UNION ALL SELECT '演示·护眼桌面台灯', 99.00, 100, '演示·桌面办公'
    UNION ALL SELECT '演示·轻便保温杯', 49.90, 150, '演示·生活好物'
    UNION ALL SELECT '演示·日常帆布包', 39.90, 120, '演示·生活好物'
    UNION ALL SELECT '演示·简约笔记本套装', 19.90, 200, '演示·文具收纳'
) AS demo
JOIN product_category c ON c.name = demo.category_name
WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.name = demo.name);

INSERT INTO activity (product_id, name, total_quantity, remaining_quantity, starts_at, ends_at)
SELECT p.id, demo.name, demo.quantity, demo.quantity,
       DATE_SUB(@demo_now, INTERVAL 1 HOUR), DATE_ADD(@demo_now, INTERVAL 30 DAY)
FROM (
    SELECT '演示·键盘限量专场' AS name, '演示·静音机械键盘' AS product_name, 30 AS quantity
    UNION ALL SELECT '演示·保温杯限量专场', '演示·轻便保温杯', 60
    UNION ALL SELECT '演示·台灯限量专场', '演示·护眼桌面台灯', 40
) AS demo
JOIN product p ON p.name = demo.product_name
WHERE NOT EXISTS (SELECT 1 FROM activity a WHERE a.name = demo.name);

INSERT INTO activity_coupon (activity_id, name, threshold_amount, discount_amount,
                             total_quantity, remaining_quantity,
                             claim_starts_at, claim_ends_at, use_starts_at, use_ends_at)
SELECT a.id, demo.name, demo.threshold_amount, demo.discount_amount,
       demo.quantity, demo.quantity, a.starts_at, a.ends_at, a.starts_at, a.ends_at
FROM (
    SELECT '演示·键盘满199减20' AS name, '演示·键盘限量专场' AS activity_name,
           199.00 AS threshold_amount, 20.00 AS discount_amount, 30 AS quantity
    UNION ALL SELECT '演示·保温杯满49减5', '演示·保温杯限量专场', 49.00, 5.00, 60
    UNION ALL SELECT '演示·台灯满99减10', '演示·台灯限量专场', 99.00, 10.00, 40
) AS demo
JOIN activity a ON a.name = demo.activity_name
WHERE NOT EXISTS (SELECT 1 FROM activity_coupon coupon WHERE coupon.activity_id = a.id AND coupon.name = demo.name);

COMMIT;
