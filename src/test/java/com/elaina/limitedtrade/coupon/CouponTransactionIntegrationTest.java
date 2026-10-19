package com.elaina.limitedtrade.coupon;

import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.elaina.limitedtrade.order.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class CouponTransactionIntegrationTest {
    @Autowired OrderService orders;
    @Autowired CouponService coupons;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    boolean owned;

    @BeforeEach void prepare(){
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id BETWEEN 8301 AND 8320",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=17001",Integer.class));
        owned=true;
        for(int i=0;i<20;i++)jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (?,?,'unused','USER')",8301+i,"coupon_tx_"+i);
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES(17001,'事务活动商品',129,100,'ON_SALE')");
        jdbc.update("INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at) VALUES(17001,17001,'事务限量活动',80,80,DATE_SUB(NOW(),INTERVAL 2 HOUR),DATE_ADD(NOW(),INTERVAL 4 HOUR))");
        jdbc.update("INSERT INTO activity_coupon(id,activity_id,name,threshold_amount,discount_amount,total_quantity,remaining_quantity,claim_starts_at,claim_ends_at,use_starts_at,use_ends_at) VALUES(17011,17001,'满200减20',200,20,3,3,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR),DATE_SUB(NOW(),INTERVAL 30 MINUTE),DATE_ADD(NOW(),INTERVAL 2 HOUR))");
    }
    @AfterEach void cleanup(){
        if(owned){
            jdbc.update("DELETE FROM order_coupon WHERE coupon_id=17011");
            jdbc.update("DELETE FROM user_coupon WHERE coupon_id=17011");
            jdbc.update("DELETE FROM activity_coupon WHERE id=17011");
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN(SELECT id FROM trade_order WHERE user_id BETWEEN 8301 AND 8320)");
            jdbc.update("DELETE FROM trade_order WHERE user_id BETWEEN 8301 AND 8320");
            jdbc.update("DELETE FROM activity WHERE id=17001");
            jdbc.update("DELETE FROM product WHERE id=17001");
            jdbc.update("DELETE FROM app_user WHERE id BETWEEN 8301 AND 8320");
        }
    }
    long claim(){return coupons.claim(8301,17011).id();}
    CreateOrderRequest request(long couponId,String key){return new CreateOrderRequest(17001L,2,key,17001L,couponId);}
    long buy(long couponId,String key){return orders.createOrder(8301,request(couponId,key)).order().getId();}
    String state(){return jdbc.queryForObject("SELECT status FROM user_coupon WHERE user_id=8301",String.class);}
    int stock(){return jdbc.queryForObject("SELECT stock FROM product WHERE id=17001",Integer.class);}
    int quota(){return jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=17001",Integer.class);}
    void reject(String timing,String table,String condition,Runnable operation){
        String name="test_coupon_tx_8301";
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",Integer.class,name));
        jdbc.execute("CREATE TRIGGER "+name+" "+timing+" ON "+table+" FOR EACH ROW BEGIN IF "+condition+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled coupon transaction failure'; END IF; END");
        try{assertThrows(DataAccessException.class,operation::run);}finally{jdbc.execute("DROP TRIGGER "+name);}
    }

    @Test void failedClaimRestoresIndependentIssuanceQuota(){
        reject("BEFORE INSERT","user_coupon","NEW.coupon_id=17011",this::claim);
        assertEquals(3,jdbc.queryForObject("SELECT remaining_quantity FROM activity_coupon WHERE id=17011",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE coupon_id=17011",Integer.class));
        assertEquals(100,stock());assertEquals(80,quota());
        assertNotNull(coupons.claim(8301,17011).id());
    }
    @ParameterizedTest @ValueSource(strings={"snapshot","outbox"})
    void lateOrderFailureRestoresCouponStockAndActivity(String mode){
        long couponId=claim();
        reject("BEFORE INSERT",mode.equals("snapshot")?"order_coupon":"order_timeout_outbox",mode.equals("snapshot")?"NEW.coupon_id=17011":"NEW.order_id>0",()->buy(couponId,"rollback-create"));
        assertEquals(100,stock());assertEquals(80,quota());assertEquals("AVAILABLE",state());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=8301",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_coupon WHERE coupon_id=17011",Integer.class));
        assertNotNull(orders.createOrder(8301,request(couponId,"rollback-create")).order().getCoupon());
    }
    @Test void failedRedemptionRollsBackPaymentAndRetrySucceeds(){
        long id=buy(claim(),"rollback-pay");
        reject("BEFORE UPDATE","user_coupon","NEW.user_id=8301 AND NEW.status='USED'",()->orders.payOrder(8301,id));
        assertEquals("PENDING_PAYMENT",orders.getUserOrder(8301,id).getStatus());assertEquals("RESERVED",state());
        assertNull(orders.getUserOrder(8301,id).getPaidAt());
        orders.payOrder(8301,id);assertEquals("USED",state());
    }
    @ParameterizedTest @ValueSource(strings={"cancel","close"})
    void failedReleaseRollsBackOrderStockAndActivity(String mode){
        long id=buy(claim(),"rollback-release");var cutoff=LocalDateTime.now().plusMinutes(16);
        Runnable release=mode.equals("cancel")?()->orders.cancelOrder(8301,id):()->orders.closeExpiredOrder(id,cutoff);
        reject("BEFORE UPDATE","user_coupon","NEW.user_id=8301 AND NEW.status='AVAILABLE'",release);
        assertEquals("PENDING_PAYMENT",orders.getUserOrder(8301,id).getStatus());assertEquals("RESERVED",state());
        assertEquals(98,stock());assertEquals(78,quota());
        release.run();assertEquals("AVAILABLE",state());assertEquals(100,stock());assertEquals(80,quota());
    }
    @ParameterizedTest @ValueSource(strings={"stock","activity","threshold","expired"})
    void rejectedOrderLeavesAllResourcesUnchanged(String mode){
        long couponId=claim();
        switch(mode){
            case "stock"->jdbc.update("UPDATE product SET stock=1 WHERE id=17001");
            case "activity"->jdbc.update("UPDATE activity SET remaining_quantity=1 WHERE id=17001");
            case "threshold"->jdbc.update("UPDATE product SET price=20 WHERE id=17001");
            case "expired"->jdbc.update("UPDATE activity_coupon SET claim_ends_at=DATE_ADD(claim_starts_at,INTERVAL 1 SECOND),use_ends_at=NOW() WHERE id=17011");
        }
        int beforeStock=stock(),beforeQuota=quota();
        assertEquals(409,assertThrows(ResponseStatusException.class,()->buy(couponId,"rejected")).getStatusCode().value());
        assertEquals(beforeStock,stock());assertEquals(beforeQuota,quota());assertEquals("AVAILABLE",state());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=8301",Integer.class));
    }
    <T> List<T> parallel(List<Callable<T>> work)throws Exception {
        var pool=Executors.newFixedThreadPool(8);var gate=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<T>>();for(var job:work)futures.add(pool.submit(()->{gate.await();return job.call();}));
            gate.countDown();var result=new ArrayList<T>();for(var f:futures)result.add(f.get(15,TimeUnit.SECONDS));return result;
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void twentyBuyersCanOnlyClaimThreeCoupons()throws Exception{
        var jobs=new ArrayList<Callable<Integer>>();
        for(int i=0;i<20;i++){long userId=8301+i;jobs.add(()->{try{coupons.claim(userId,17011);return 1;}catch(ResponseStatusException ex){assertEquals(409,ex.getStatusCode().value());return 0;}});}
        assertEquals(3,parallel(jobs).stream().mapToInt(Integer::intValue).sum());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE coupon_id=17011",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT remaining_quantity FROM activity_coupon WHERE id=17011",Integer.class));
        assertEquals(100,stock());assertEquals(80,quota());
    }
    @Test void concurrentRepeatClaimsReturnOneOwnedCoupon()throws Exception{
        var jobs=new ArrayList<Callable<Long>>();
        for(int i=0;i<12;i++)jobs.add(()->coupons.claim(8301,17011).id());
        assertEquals(1L,parallel(jobs).stream().distinct().count());
        assertEquals(2,jdbc.queryForObject("SELECT remaining_quantity FROM activity_coupon WHERE id=17011",Integer.class));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sameCouponCannotBackTwoOrdersAndSameRequestRetriesReturnOneOrder(boolean sameRequest)throws Exception{
        long couponId=claim();var jobs=new ArrayList<Callable<Long>>();
        for(int i=0;i<12;i++){String key=sameRequest?"same-coupon-request":"coupon-request-"+i;jobs.add(()->{try{return buy(couponId,key);}catch(ResponseStatusException ex){assertEquals(409,ex.getStatusCode().value());return 0L;}});}
        var result=parallel(jobs);assertEquals(sameRequest?12L:1L,result.stream().filter(id->id>0).count());
        assertEquals(1L,result.stream().filter(id->id>0).distinct().count());
        assertEquals(98,stock());assertEquals(78,quota());assertEquals("RESERVED",state());
    }
    @Test void cancelAndTimeoutReturnCouponAndStockOnlyOnce()throws Exception{
        long id=buy(claim(),"release-race");
        parallel(List.of(()->{try{orders.cancelOrder(8301,id);}catch(ResponseStatusException ex){assertEquals(409,ex.getStatusCode().value());}return true;},()->orders.closeExpiredOrder(id,LocalDateTime.now().plusMinutes(16))));
        assertEquals("AVAILABLE",state());assertEquals(100,stock());assertEquals(80,quota());
        long next=buy(jdbc.queryForObject("SELECT id FROM user_coupon WHERE user_id=8301",Long.class),"after-release");
        assertFalse(orders.closeExpiredOrder(id,LocalDateTime.now().plusHours(1)));
        assertEquals(next,jdbc.queryForObject("SELECT order_id FROM user_coupon WHERE user_id=8301",Long.class));
    }
    @Test void couponLockWaitAcrossExpiryCannotCreateOrder()throws Exception{
        long couponId=claim();var deadline=LocalDateTime.now().withNano(0).plusSeconds(4);
        jdbc.update("UPDATE activity_coupon SET claim_ends_at=DATE_ADD(claim_starts_at,INTERVAL 1 SECOND),use_ends_at=? WHERE id=17011",deadline);
        var pool=Executors.newSingleThreadExecutor();
        try(var blocker=dataSource.getConnection()){
            blocker.setAutoCommit(false);
            try(var s=blocker.prepareStatement("SELECT id FROM user_coupon WHERE id=? FOR UPDATE")){s.setLong(1,couponId);s.executeQuery().close();}
            var future=pool.submit(()->assertThrows(ResponseStatusException.class,()->buy(couponId,"expiry-lock")));
            boolean productLocked=false;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!productLocked && System.nanoTime()<limit){
                try(var observer=dataSource.getConnection()){
                    observer.setAutoCommit(false);
                    try(var s=observer.prepareStatement("SELECT id FROM product WHERE id=17001 FOR UPDATE NOWAIT")){s.executeQuery().close();}
                    catch(SQLException ex){if(ex.getErrorCode()!=3572)throw ex;productLocked=true;}
                    finally{observer.rollback();}
                }
                if(!productLocked)Thread.sleep(10);
            }
            assertTrue(productLocked);assertTrue(LocalDateTime.now().isBefore(deadline));
            Thread.sleep(Math.max(1,Duration.between(LocalDateTime.now(),deadline.plusSeconds(1)).toMillis()));
            blocker.commit();assertEquals(409,future.get(10,TimeUnit.SECONDS).getStatusCode().value());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(100,stock());assertEquals(80,quota());assertEquals("AVAILABLE",state());
    }
}
