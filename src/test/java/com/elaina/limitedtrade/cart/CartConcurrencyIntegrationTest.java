package com.elaina.limitedtrade.cart;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CartConcurrencyIntegrationTest {
    @Autowired private CartService carts;
    @Autowired private JdbcTemplate jdbc;
    private boolean owned;

    @BeforeEach
    void prepareCommittedFixtures() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id IN (7911,7912)", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=10111", Integer.class));
        owned=true;
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (7911,'cart_parallel_1','unused','USER'),(7912,'cart_parallel_2','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (10111,'并发购物车商品',20,5,'ON_SALE')");
    }

    @AfterEach
    void cleanupOwnedFixtures() {
        if (owned) {
            jdbc.update("DELETE FROM cart_item WHERE user_id IN (7911,7912)");
            jdbc.update("DELETE FROM product WHERE id=10111");
            jdbc.update("DELETE FROM app_user WHERE id IN (7911,7912)");
        }
    }

    @Test
    void concurrentSameValueCreatesOneRow() throws Exception {
        List<Callable<Void>> tasks=new ArrayList<>();
        for(int i=0;i<20;i++) tasks.add(() -> { carts.setQuantity(7911,10111,3); return null; });
        runConcurrently(tasks);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=7911", Integer.class));
        assertEquals(3, quantity(7911));
        assertEquals(5, jdbc.queryForObject("SELECT stock FROM product WHERE id=10111", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox", Integer.class));
    }

    @Test
    void concurrentDifferentValuesDoNotAccumulate() throws Exception {
        List<Callable<Void>> tasks=new ArrayList<>();
        for(int value=1;value<=20;value++) {
            int requested=value;
            tasks.add(() -> { carts.setQuantity(7911,10111,requested); return null; });
        }
        runConcurrently(tasks);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=7911", Integer.class));
        assertTrue(quantity(7911)>=1 && quantity(7911)<=20);
    }

    @Test
    void settingAndDeletingSameItemDoesNotAffectOtherBuyer() throws Exception {
        carts.setQuantity(7912,10111,7);
        List<Callable<Void>> tasks=new ArrayList<>();
        for(int i=0;i<20;i++) {
            boolean remove=i%2==0;
            tasks.add(() -> {
                if(remove) carts.removeItem(7911,10111);
                else carts.setQuantity(7911,10111,3);
                return null;
            });
        }
        runConcurrently(tasks);
        var values=jdbc.queryForList("SELECT quantity FROM cart_item WHERE user_id=7911 AND product_id=10111",Integer.class);
        assertTrue(values.isEmpty() || values.equals(List.of(3)));
        assertEquals(7,quantity(7912));
    }

    private int quantity(long owner) {
        return jdbc.queryForObject("SELECT quantity FROM cart_item WHERE user_id=? AND product_id=10111",Integer.class,owner);
    }

    private void runConcurrently(List<Callable<Void>> tasks) throws Exception {
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(10);
        try {
            var futures=tasks.stream().map(task -> pool.submit(() -> {
                assertTrue(start.await(5,TimeUnit.SECONDS));
                return task.call();
            })).toList();
            start.countDown();
            for(var future:futures) future.get(15,TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));
        }
    }
}
