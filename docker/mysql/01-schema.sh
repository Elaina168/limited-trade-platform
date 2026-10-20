#!/bin/bash
set -euo pipefail

# 全新容器数据卷的初始化；逐个执行，任一SQL文件失败就立即退出。
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"
for script in \
    schema.sql \
    002_app_user.sql \
    003_trade_order.sql \
    004_order_payment_cancel.sql \
    005_order_timeout.sql \
    006_activity.sql \
    007_order_timeout_outbox.sql \
    008_product_category.sql \
    009_cart_item.sql \
    010_user_address.sql \
    011_order_checkout.sql \
    012_order_shipment.sql \
    013_activity_coupon.sql \
    seed.sql \
    demo_catalog.sql
do
    mysql --protocol=socket --user=root --database=limited_trade_platform \
        --default-character-set=utf8mb4 < "/bootstrap/${script}"
done
unset MYSQL_PWD
