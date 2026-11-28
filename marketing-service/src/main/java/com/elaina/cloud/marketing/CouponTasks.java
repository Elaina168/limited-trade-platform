package com.elaina.cloud.marketing;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class CouponTasks {
    private final JdbcTemplate jdbc;

    CouponTasks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Reservation reserve(long orderId, long productId, long couponId) {
        jdbc.update("""
                INSERT INTO marketing_coupon_task(order_id,product_id,coupon_id,status) VALUES (?, ?, ?, 'NEW')
                ON DUPLICATE KEY UPDATE order_id=marketing_coupon_task.order_id
                """, orderId, productId, couponId);
        Task task = readForUpdate(orderId);
        if ("RELEASED".equals(task.status()) || task.productId() != productId || task.couponId() != couponId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券工作单已释放或参数冲突");
        }
        if ("RESERVED".equals(task.status())) return task.reservation();
        var choices = jdbc.query("""
                SELECT threshold_amount,discount FROM marketing_coupon
                WHERE id=? AND product_id=? AND starts_at<=CURRENT_TIMESTAMP AND ends_at>CURRENT_TIMESTAMP
                  AND remaining_quantity>0 FOR UPDATE
                """, (rs, row) -> new Reservation(rs.getBigDecimal(1), rs.getBigDecimal(2)), couponId, productId);
        if (choices.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券不可领取");
        Reservation terms = choices.getFirst();
        jdbc.update("UPDATE marketing_coupon SET remaining_quantity=remaining_quantity-1 WHERE id=?", couponId);
        jdbc.update("""
                UPDATE marketing_coupon_task SET status='RESERVED', threshold_amount=?, discount=? WHERE order_id=?
                """, terms.threshold(), terms.discount(), orderId);
        return terms;
    }

    @Transactional
    public void release(long orderId) {
        // 先到达的释放请求登记终态，确保同订单的迟到占用不会重新扣券。
        jdbc.update("""
                INSERT INTO marketing_coupon_task(order_id,status) VALUES (?, 'RELEASED')
                ON DUPLICATE KEY UPDATE order_id=marketing_coupon_task.order_id
                """, orderId);
        Task task = readForUpdate(orderId);
        if ("RESERVED".equals(task.status())) {
            jdbc.update("UPDATE marketing_coupon SET remaining_quantity=remaining_quantity+1 WHERE id=?", task.couponId());
            jdbc.update("UPDATE marketing_coupon_task SET status='RELEASED' WHERE order_id=?", orderId);
        }
    }

    private Task readForUpdate(long orderId) {
        return jdbc.queryForObject("""
                SELECT product_id,coupon_id,status,threshold_amount,discount
                FROM marketing_coupon_task WHERE order_id=? FOR UPDATE
                """, (rs, row) -> new Task(rs.getObject(1, Long.class), rs.getObject(2, Long.class), rs.getString(3),
                new Reservation(rs.getBigDecimal(4), rs.getBigDecimal(5))), orderId);
    }

    private record Task(Long productId, Long couponId, String status, Reservation reservation) {}
    record Reservation(BigDecimal threshold, BigDecimal discount) {}
}
