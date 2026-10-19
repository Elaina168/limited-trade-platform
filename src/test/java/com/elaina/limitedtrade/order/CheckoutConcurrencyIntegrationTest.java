package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import com.elaina.limitedtrade.cart.CartService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CheckoutConcurrencyIntegrationTest {
    @Autowired OrderService orders;
    @Autowired CartService carts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private boolean owned;

    @BeforeEach void prepare(){
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id BETWEEN 8021 AND 8030",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id IN (11021,11022)",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id BETWEEN 12021 AND 12030",Integer.class));
        owned=true;
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (11021,'并发键盘',20,5,'ON_SALE'),(11022,'并发鼠标',7.50,4,'ON_SALE')");
        for(long id=8021;id<=8030;id++){
            jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (?,?,'unused','USER')",id,"checkout_parallel_"+id);
            jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (?,11021,1),(?,11022,1)",id,id);
            jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address) VALUES (?,?,'张三','13800138000','浙江','杭州','西湖','学院路1号')",id+4000,id);
        }
    }
    @AfterEach void cleanup(){
        if(owned){
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id BETWEEN 8021 AND 8030)");
            jdbc.update("DELETE FROM trade_order WHERE user_id BETWEEN 8021 AND 8030");
            jdbc.update("DELETE FROM cart_item WHERE user_id BETWEEN 8021 AND 8030");
            jdbc.update("DELETE FROM user_address WHERE user_id BETWEEN 8021 AND 8030");
            jdbc.update("DELETE FROM product WHERE id IN (11021,11022)");
            jdbc.update("DELETE FROM app_user WHERE id BETWEEN 8021 AND 8030");
        }
    }

    @Test void concurrentSameRequestCreatesOneOrderAndOneOutbox() throws Exception {
        List<Callable<CreateOrderResult>> jobs=new ArrayList<>();
        for(int i=0;i<20;i++){
            List<Long> ids=i%2==0?List.of(11021L,11022L):List.of(11022L,11021L);
            jobs.add(()->orders.checkout(8021,new CheckoutRequest(ids,12021L,"same-checkout")));
        }
        List<CreateOrderResult> results=parallel(jobs);
        assertEquals(1,results.stream().filter(CreateOrderResult::created).count());
        assertEquals(1,results.stream().map(r->r.order().getId()).distinct().count());
        assertEquals(1,count("trade_order"));assertEquals(1,count("order_timeout_outbox"));
        assertEquals(2,count("order_item"));assertEquals(1,count("order_address"));
        assertEquals(4,stock(11021));assertEquals(3,stock(11022));
    }

    @Test void competingUsersWithReverseSelectionsDoNotOversellOrPartiallyCommit() throws Exception {
        List<Callable<Integer>> jobs=new ArrayList<>();
        for(long id=8021;id<=8030;id++){
            long userId=id;List<Long> ids=id%2==0?List.of(11021L,11022L):List.of(11022L,11021L);
            jobs.add(()->{
                try {orders.checkout(userId,new CheckoutRequest(ids,userId+4000,"stock-race"));return 201;}
                catch(ResponseStatusException e){return e.getStatusCode().value();}
            });
        }
        var results=parallel(jobs);
        assertEquals(4,results.stream().filter(s->s==201).count());assertEquals(6,results.stream().filter(s->s==409).count());
        assertEquals(1,stock(11021));assertEquals(0,stock(11022));
        assertEquals(4,count("trade_order"));assertEquals(8,count("order_item"));assertEquals(4,count("order_timeout_outbox"));
        assertEquals(12,count("cart_item"));
    }

    @Test void cartUpdateAfterCheckoutWaitsAndKeepsNewPurchaseIntent() throws Exception {
        var saved=new CountDownLatch(1);var release=new CountDownLatch(1);var writing=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var checkout=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{
                var result=orders.checkout(8021,request("cart-after"));saved.countDown();await(release);return result;
            }));
            assertTrue(saved.await(10,TimeUnit.SECONDS));
            var update=pool.submit(()->{writing.countDown();carts.setQuantity(8021,11021,4);});
            assertTrue(writing.await(10,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->update.get(200,TimeUnit.MILLISECONDS));
            release.countDown();var result=checkout.get(10,TimeUnit.SECONDS);update.get(10,TimeUnit.SECONDS);
            assertEquals(1,result.order().getItems().getFirst().quantity());
            assertEquals(4,jdbc.queryForObject("SELECT quantity FROM cart_item WHERE user_id=8021 AND product_id=11021",Integer.class));
            assertEquals(4,stock(11021));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void cartUpdateBeforeCheckoutSuppliesCommittedQuantity() throws Exception {
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);var entering=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var update=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                carts.setQuantity(8021,11021,4);changed.countDown();await(release);
            }));
            assertTrue(changed.await(10,TimeUnit.SECONDS));
            var checkout=pool.submit(()->{entering.countDown();return orders.checkout(8021,request("cart-before"));});
            assertTrue(entering.await(10,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->checkout.get(200,TimeUnit.MILLISECONDS));
            release.countDown();update.get(10,TimeUnit.SECONDS);var result=checkout.get(10,TimeUnit.SECONDS);
            assertEquals(4,result.order().getItems().getFirst().quantity());assertEquals(1,stock(11021));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=8021",Integer.class));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void waitingForProductLockUsesCommittedPriceAndName() throws Exception {
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);var entering=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var update=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.update("UPDATE product SET price=30,name='锁后价格' WHERE id=11021");changed.countDown();await(release);
            }));
            assertTrue(changed.await(10,TimeUnit.SECONDS));
            var checkout=pool.submit(()->{entering.countDown();return orders.checkout(8021,request("price-wait"));});
            assertTrue(entering.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->checkout.get(200,TimeUnit.MILLISECONDS));
            release.countDown();update.get(10,TimeUnit.SECONDS);var result=checkout.get(10,TimeUnit.SECONDS);
            assertEquals(new BigDecimal("37.50"),result.order().getTotalAmount());
            assertEquals("锁后价格",result.order().getItems().getFirst().productName());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void concurrentCancelAndCloseReturnEveryStockOnlyOnce() throws Exception {
        long id=orders.checkout(8021,request("end-race")).order().getId();
        var cutoff=LocalDateTime.now().withNano(0);jdbc.update("UPDATE trade_order SET expires_at=? WHERE id=?",cutoff,id);
        List<Callable<Integer>> jobs=new ArrayList<>();
        for(int i=0;i<20;i++){
            boolean cancel=i%2==0;
            jobs.add(()->{try {if(cancel)orders.cancelOrder(8021,id);else orders.closeExpiredOrder(id,cutoff);return 200;}
                catch(ResponseStatusException e){return e.getStatusCode().value();}});
        }
        assertTrue(parallel(jobs).stream().allMatch(s->s==200||s==409));
        String status=jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?",String.class,id);
        assertTrue(status.equals("CANCELED")||status.equals("CLOSED"));assertEquals(5,stock(11021));assertEquals(4,stock(11022));
    }

    @Test void paymentAndCancellationHaveOneLegalTerminalState() throws Exception {
        long id=orders.checkout(8021,request("pay-race")).order().getId();
        List<Callable<Integer>> jobs=new ArrayList<>();
        for(int i=0;i<20;i++){
            boolean pay=i%2==0;
            jobs.add(()->{try {if(pay)orders.payOrder(8021,id);else orders.cancelOrder(8021,id);return 200;}
                catch(ResponseStatusException e){return e.getStatusCode().value();}});
        }
        assertTrue(parallel(jobs).stream().allMatch(s->s==200||s==409));
        String status=jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?",String.class,id);
        assertTrue(status.equals("PAID")||status.equals("CANCELED"));
        assertEquals(status.equals("PAID")?4:5,stock(11021));assertEquals(status.equals("PAID")?3:4,stock(11022));
    }

    private CheckoutRequest request(String key){return new CheckoutRequest(List.of(11021L,11022L),12021L,key);}
    private int stock(long id){return jdbc.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,id);}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void await(CountDownLatch latch){try {assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private <T> List<T> parallel(List<Callable<T>> jobs)throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(10);
        try {
            List<Callable<T>> gated=jobs.stream().<Callable<T>>map(job->()->{assertTrue(start.await(10,TimeUnit.SECONDS));return job.call();}).toList();
            var futures=new ArrayList<Future<T>>();for(var job:gated)futures.add(pool.submit(job));start.countDown();
            List<T> results=new ArrayList<>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));return results;
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
