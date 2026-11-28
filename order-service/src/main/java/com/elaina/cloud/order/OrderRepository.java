package com.elaina.cloud.order;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class OrderRepository {
    static final String PENDING = "PENDING_PAYMENT";
    static final String CANCELED = "CANCELED";
    static final String CLOSED = "CLOSED";
    static final String MISSING = "MISSING";

    private static final String SELECT = """
        SELECT id,user_id,request_id,product_id,quantity,coupon_id,total_amount,status,expires_at FROM lab_order
        """;
    private static final RowMapper<Order> ROW = (rs, row) -> new Order(
        rs.getLong("id"), rs.getLong("user_id"), rs.getString("request_id"), rs.getLong("product_id"),
        rs.getInt("quantity"), rs.getObject("coupon_id", Long.class), rs.getBigDecimal("total_amount"),
        rs.getString("status"), rs.getTimestamp("expires_at").toInstant());
    private final JdbcTemplate jdbc;

    OrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    long insert(long userId, OrderService.Request request, Instant expiresAt) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO lab_order (user_id,request_id,product_id,quantity,coupon_id,status,expires_at)
                VALUES (?,?,?,?,?,?,?)
                """, new String[] {"id"});
            statement.setLong(1, userId);
            statement.setString(2, request.requestId());
            statement.setLong(3, request.productId());
            statement.setInt(4, request.quantity());
            statement.setObject(5, request.couponId());
            statement.setString(6, PENDING);
            statement.setTimestamp(7, Timestamp.from(expiresAt));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    Optional<Order> request(long userId, String requestId, boolean lock) {
        return jdbc.query(SELECT + "WHERE user_id=? AND request_id=?" + (lock ? " FOR UPDATE" : ""), ROW, userId, requestId)
            .stream().findFirst();
    }

    void finish(long id, BigDecimal total, Instant expiresAt) {
        jdbc.update("UPDATE lab_order SET total_amount=?,expires_at=? WHERE id=?", total, Timestamp.from(expiresAt), id);
    }

    Optional<Order> owned(long userId, long id) {
        return jdbc.query(SELECT + "WHERE user_id=? AND id=?", ROW, userId, id).stream().findFirst();
    }

    @Transactional(timeout = 2)
    public String state(long id) {
        // 锁定读取会等待正在提交的订单。网络/锁超时不能当成订单缺失。
        return jdbc.query("SELECT status FROM lab_order WHERE id=? FOR SHARE", (rs, row) -> rs.getString(1), id)
            .stream().findFirst().orElse(MISSING);
    }

    boolean cancel(long id, long userId) {
        return jdbc.update("UPDATE lab_order SET status=? WHERE id=? AND user_id=? AND status=?", CANCELED, id, userId, PENDING) == 1;
    }

    boolean expire(long id, Instant now) {
        return jdbc.update("UPDATE lab_order SET status=? WHERE id=? AND status=? AND expires_at<=?",
            CLOSED, id, PENDING, Timestamp.from(now)) == 1;
    }

    record Order(long id, long userId, String requestId, long productId, int quantity,
                 Long couponId, BigDecimal totalAmount, String status, Instant expiresAt) {}
}
