-- 在目标商城库已应用 013 后执行。通过名称仅追加扩展演示数据。
-- 同一库重复执行不补库存、名额或续期；已有真实和演示记录都保留。
CREATE TEMPORARY TABLE demo_scale_lock (ok INT NOT NULL CHECK (ok = 1));
INSERT INTO demo_scale_lock VALUES (GET_LOCK(CONCAT(DATABASE(), ':demo-catalog-scale'), 10));
START TRANSACTION;
SET @scale_now = NOW();

CREATE TEMPORARY TABLE demo_scale_family (
    id INT PRIMARY KEY, category_name VARCHAR(100) NOT NULL,
    item_a VARCHAR(50) NOT NULL, item_b VARCHAR(50) NOT NULL, item_c VARCHAR(50) NOT NULL,
    base_price DECIMAL(10,2) NOT NULL
);
INSERT INTO demo_scale_family VALUES
(0,'电脑外设','无线鼠标','机械键盘','桌面麦克风',49),
(1,'数码配件','双口充电器','多口扩展坞','编织数据线',39),
(2,'影音设备','蓝牙耳机','桌面音箱','便携耳放',69),
(3,'桌面办公','护眼台灯','显示器支架','桌面文件架',59),
(4,'文具手账','硬面笔记本','中性笔套装','周计划手账',19),
(5,'居家收纳','折叠收纳箱','抽屉分隔板','桌边置物篮',29),
(6,'烹饪餐厨','玻璃保鲜盒','不锈钢餐具','硅胶烘焙垫',29),
(7,'户外运动','旅行保温壶','折叠坐垫','防水运动包',49),
(8,'日用清洁','衣物清洁刷','多用途抹布','旋转清洁刷',19),
(9,'箱包出行','轻便双肩包','拉链洗漱包','证件收纳夹',39),
(10,'宠物用品','宠物饮水碗','梳理手套','便携拾便袋',19),
(11,'绿植园艺','自吸水花盆','园艺修枝剪','浇花喷壶',19);

CREATE TEMPORARY TABLE demo_scale_product (
    n INT PRIMARY KEY, category_name VARCHAR(100) NOT NULL, product_name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL, stock INT NOT NULL
);
INSERT INTO demo_scale_product
WITH digits AS (
    SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), numbers AS (
    SELECT 100 * a.d + 10 * b.d + c.d AS n FROM digits a CROSS JOIN digits b CROSS JOIN digits c
)
SELECT n, CONCAT('扩展演示·', f.category_name),
       CONCAT('扩展演示·', ELT(1 + MOD(n, 3), f.item_a, f.item_b, f.item_c),
              '｜', ELT(1 + MOD(n DIV 3, 4), '雾蓝', '暖灰', '雅黑', '米白'),
              ' ', LPAD(n + 1, 3, '0')),
       f.base_price + MOD(n * 23, 160) + 0.90,
       60 + MOD(n * 37, 240)
FROM numbers JOIN demo_scale_family f ON f.id = n DIV 60
WHERE n < 720;

INSERT INTO product_category(name)
SELECT DISTINCT d.category_name FROM demo_scale_product d
WHERE NOT EXISTS (SELECT 1 FROM product_category c WHERE c.name = d.category_name);

INSERT INTO product(name,price,stock,status,category_id)
SELECT d.product_name,d.price,d.stock,'ON_SALE',c.id
FROM demo_scale_product d JOIN product_category c ON c.name = d.category_name
WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.name = d.product_name);

INSERT INTO activity(product_id,name,total_quantity,remaining_quantity,starts_at,ends_at)
SELECT p.id,CONCAT('扩展演示·活动 ', LPAD(d.n DIV 6 + 1, 3, '0')),
       20 + MOD(d.n, 24),20 + MOD(d.n, 24),
       CASE MOD(d.n DIV 6, 10)
           WHEN 0 THEN DATE_SUB(@scale_now, INTERVAL 30 DAY)
           WHEN 1 THEN DATE_ADD(@scale_now, INTERVAL 1 DAY)
           ELSE DATE_SUB(@scale_now, INTERVAL 1 HOUR) END,
       CASE MOD(d.n DIV 6, 10)
           WHEN 0 THEN DATE_SUB(@scale_now, INTERVAL 1 HOUR)
           ELSE DATE_ADD(@scale_now, INTERVAL 365 DAY) END
FROM demo_scale_product d JOIN product p ON p.name = d.product_name
WHERE MOD(d.n, 6) = 0 AND NOT EXISTS (
    SELECT 1 FROM activity a WHERE a.name = CONCAT('扩展演示·活动 ', LPAD(d.n DIV 6 + 1, 3, '0')));

INSERT INTO activity_coupon(activity_id,name,threshold_amount,discount_amount,total_quantity,
    remaining_quantity,claim_starts_at,claim_ends_at,use_starts_at,use_ends_at)
SELECT a.id, CONCAT('扩展演示·', tier.label), p.price * tier.minimum_qty,
       GREATEST(1, FLOOR(p.price * tier.discount_ratio)),
       tier.qty,tier.qty,a.starts_at,a.ends_at,a.starts_at,a.ends_at
FROM demo_scale_product d JOIN product p ON p.name = d.product_name
JOIN activity a ON a.product_id = p.id AND a.name = CONCAT('扩展演示·活动 ', LPAD(d.n DIV 6 + 1, 3, '0'))
CROSS JOIN (
    SELECT '单件满减券' AS label, 1 AS minimum_qty, 0.08 AS discount_ratio, 16 AS qty
    UNION ALL SELECT '双件满减券', 2, 0.18, 24
) tier
WHERE MOD(d.n, 6) = 0 AND NOT EXISTS (
    SELECT 1 FROM activity_coupon c WHERE c.activity_id = a.id AND c.name = CONCAT('扩展演示·', tier.label));

COMMIT;
DROP TEMPORARY TABLE demo_scale_product, demo_scale_family, demo_scale_lock;
DO RELEASE_LOCK(CONCAT(DATABASE(), ':demo-catalog-scale'));
