package com.elaina.cloud.stock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockTasksTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StockTasks tasks = new StockTasks(jdbc, 10_000, 2_000);

    @Test
    void firstLockDeductsOnceReplayDoesNotAndChangedPayloadConflicts() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockTasks.Task>>any(), eq(1L)))
                .thenReturn(new StockTasks.Task(7L, 2, "NEW"), new StockTasks.Task(7L, 2, "LOCKED"));
        when(jdbc.update("UPDATE stock_product SET stock=stock-? WHERE id=? AND stock>=?", 2, 7L, 2)).thenReturn(1);

        assertTrue(tasks.lock(1, 7, 2, "a".repeat(32)));
        assertFalse(tasks.lock(1, 7, 2, "a".repeat(32)));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tasks.lock(1, 8, 2, "a".repeat(32))).getStatusCode().value());
        verify(jdbc).update("UPDATE stock_product SET stock=stock-? WHERE id=? AND stock>=?", 2, 7L, 2);
    }

    @Test
    void releaseReturnsStockOnceAndBlocksLateLock() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockTasks.Task>>any(), eq(1L)))
                .thenReturn(new StockTasks.Task(7L, 2, "LOCKED"), new StockTasks.Task(null, null, "RELEASED"));

        tasks.release(1);
        tasks.release(1);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tasks.lock(1, 7, 2, "a".repeat(32))).getStatusCode().value());
        verify(jdbc).update("UPDATE stock_product SET stock=stock+? WHERE id=?", 2, 7L);
        verify(jdbc, never()).update(eq("UPDATE stock_product SET stock=stock-? WHERE id=? AND stock>=?"),
                anyInt(), anyLong(), anyInt());
    }
}
