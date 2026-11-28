package com.elaina.cloud.stock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class StockTasks {
    private final JdbcTemplate jdbc;
    private final long delayMs;
    private final long retryMs;

    StockTasks(JdbcTemplate jdbc, @Value("${lab.stock.check-delay-ms}") long delayMs,
               @Value("${lab.stock.retry-ms}") long retryMs) {
        this.jdbc = jdbc;
        this.delayMs = delayMs;
        this.retryMs = retryMs;
    }

    @Transactional
    public boolean lock(long orderId, long productId, int quantity, String traceId) {
        // 先占有工作单主键，再读状态；两个实例同时受理时只会有一方扣库存。
        jdbc.update("""
                INSERT INTO stock_lock_task(order_id,product_id,quantity,status,trace_id)
                VALUES (?, ?, ?, 'NEW', ?) ON DUPLICATE KEY UPDATE order_id=stock_lock_task.order_id
                """, orderId, productId, quantity, traceId);
        Task task = readForUpdate(orderId);
        if ("RELEASED".equals(task.status()) || task.productId() != productId || task.quantity() != quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "库存工作单已释放或参数冲突");
        }
        if ("LOCKED".equals(task.status())) return false;
        if (jdbc.update("UPDATE stock_product SET stock=stock-? WHERE id=? AND stock>=?", quantity, productId, quantity) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品不存在或库存不足");
        }
        // 数据库兜底比正常 MQ 延时稍晚，覆盖发送前宕机、投递丢失及 MQ 不可用。
        jdbc.update("UPDATE stock_lock_task SET status='LOCKED', next_check_at=? WHERE order_id=?",
                Timestamp.from(Instant.now().plusMillis(delayMs + retryMs)), orderId);
        return true;
    }

    @Transactional
    public void release(long orderId) {
        // 先释放后才到达的超时锁定请求必须失败，用终态工作单记录这个事实。
        jdbc.update("""
                INSERT INTO stock_lock_task(order_id,status) VALUES (?, 'RELEASED')
                ON DUPLICATE KEY UPDATE order_id=stock_lock_task.order_id
                """, orderId);
        Task task = readForUpdate(orderId);
        if ("LOCKED".equals(task.status())) {
            jdbc.update("UPDATE stock_product SET stock=stock+? WHERE id=?", task.quantity(), task.productId());
            jdbc.update("UPDATE stock_lock_task SET status='RELEASED', next_check_at=NULL WHERE order_id=?", orderId);
        }
    }

    private Task readForUpdate(long orderId) {
        return jdbc.queryForObject("""
                SELECT product_id,quantity,status FROM stock_lock_task WHERE order_id=? FOR UPDATE
                """, (rs, row) -> new Task(rs.getObject("product_id", Long.class),
                rs.getObject("quantity", Integer.class), rs.getString("status")), orderId);
    }

    List<Long> dueOrderIds() {
        return jdbc.queryForList("""
                SELECT order_id FROM stock_lock_task
                WHERE status='LOCKED' AND next_check_at<=CURRENT_TIMESTAMP(3)
                ORDER BY next_check_at,order_id LIMIT 30
                """, Long.class);
    }

    List<String> openTrace(long orderId) {
        return jdbc.queryForList("""
                SELECT trace_id FROM stock_lock_task
                WHERE order_id=? AND status='LOCKED' AND next_check_at IS NOT NULL
                """, String.class, orderId);
    }

    void retry(long orderId) {
        jdbc.update("""
                UPDATE stock_lock_task SET next_check_at=?
                WHERE order_id=? AND status='LOCKED' AND next_check_at IS NOT NULL
                """, Timestamp.from(Instant.now().plusMillis(retryMs)), orderId);
    }

    void settlePaid(long orderId) {
        jdbc.update("UPDATE stock_lock_task SET next_check_at=NULL WHERE order_id=? AND status='LOCKED'", orderId);
    }

    record Task(Long productId, Integer quantity, String status) {}
}
