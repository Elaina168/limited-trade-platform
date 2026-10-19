package com.elaina.limitedtrade.order;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class FulfillmentConcurrencyIntegrationTest {
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    private long id;
    private boolean owned;
    private final ShipOrderRequest shipping=new ShipOrderRequest("测试承运方","PARALLEL-001");

    @BeforeEach void prepare(){
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=8121",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id IN (13021,13022)",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id=15021",Integer.class));
        owned=true;
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8121,'fulfillment_parallel','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (13021,'并发履约键盘',20,5,'ON_SALE'),(13022,'并发履约鼠标',7.50,4,'ON_SALE')");
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (8121,13021,2),(8121,13022,1)");
        jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address) VALUES (15021,8121,'张三','13800138000','浙江','杭州','西湖','学院路1号')");
        id=orders.checkout(8121,new CheckoutRequest(List.of(13021L,13022L),15021L,"parallel-fulfillment")).order().getId();
        orders.payOrder(8121,id);
    }
    @AfterEach void cleanup(){if(owned){
        jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id=8121)");
        jdbc.update("DELETE FROM trade_order WHERE user_id=8121");
        jdbc.update("DELETE FROM cart_item WHERE user_id=8121");
        jdbc.update("DELETE FROM user_address WHERE user_id=8121");
        jdbc.update("DELETE FROM product WHERE id IN (13021,13022)");
        jdbc.update("DELETE FROM app_user WHERE id=8121");
    }}

    @Test void concurrentIdenticalShippingCreatesOneRecordAndPreservesFirstTime()throws Exception {
        List<Callable<OrderShipment>> jobs=new ArrayList<>();
        for(int i=0;i<20;i++)jobs.add(()->orders.shipOrder(id,shipping).getShipment());
        var results=parallel(jobs);assertEquals(1,new HashSet<>(results).size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id=?",Integer.class,id));
        assertEquals("SHIPPED",status());unchangedStock();
    }

    @Test void conflictingShippingPayloadsHaveOneWinnerWithoutOverwritingIt()throws Exception {
        List<Callable<Integer>> jobs=new ArrayList<>();
        for(int i=0;i<20;i++){
            var request=new ShipOrderRequest("测试承运方",i%2==0?"TRACK-A":"TRACK-B");
            jobs.add(()->{try{orders.shipOrder(id,request);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
        }
        var results=parallel(jobs);assertEquals(10,results.stream().filter(x->x==200).count());assertEquals(10,results.stream().filter(x->x==409).count());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id=?",Integer.class,id));
        assertTrue(Set.of("TRACK-A","TRACK-B").contains(jdbc.queryForObject("SELECT tracking_no FROM order_shipment WHERE order_id=?",String.class,id)));unchangedStock();
    }

    @Test void concurrentReceiptRecordsOneCompletionTime()throws Exception {
        orders.shipOrder(id,shipping);
        List<Callable<OrderShipment>> jobs=new ArrayList<>();for(int i=0;i<20;i++)jobs.add(()->orders.receiveOrder(8121,id).getShipment());
        var results=parallel(jobs);assertEquals(1,new HashSet<>(results).size());assertNotNull(results.getFirst().receivedAt());
        assertEquals("COMPLETED",status());unchangedStock();
    }

    @Test void shippingAndReceiptRaceHasOnlyOrderedLegalOutcomes()throws Exception {
        var results=parallel(List.<Callable<Integer>>of(
                ()->{orders.shipOrder(id,shipping);return 200;},
                ()->{try{orders.receiveOrder(8121,id);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
        assertEquals(200,results.getFirst());assertTrue(Set.of(200,409).contains(results.get(1)));
        assertEquals(results.get(1)==200?"COMPLETED":"SHIPPED",status());unchangedStock();
    }

    @Test void oldStateActionsCannotUndoConcurrentShipping()throws Exception {
        var results=parallel(List.<Callable<Integer>>of(
                ()->{orders.shipOrder(id,shipping);return 200;},
                ()->{try{orders.payOrder(8121,id);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}},
                ()->{try{orders.cancelOrder(8121,id);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}},
                ()->orders.closeExpiredOrder(id,java.time.LocalDateTime.now().plusDays(1))?200:409));
        assertEquals(200,results.get(0));assertTrue(Set.of(200,409).contains(results.get(1)));assertEquals(409,results.get(2));assertEquals(409,results.get(3));
        assertEquals("SHIPPED",status());unchangedStock();
    }

    private String status(){return jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?",String.class,id);}
    private void unchangedStock(){assertEquals(3,jdbc.queryForObject("SELECT stock FROM product WHERE id=13021",Integer.class));assertEquals(3,jdbc.queryForObject("SELECT stock FROM product WHERE id=13022",Integer.class));}
    private <T> List<T> parallel(List<Callable<T>> jobs)throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(10);
        try {
            var futures=new ArrayList<Future<T>>();for(var job:jobs)futures.add(pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return job.call();}));
            start.countDown();var result=new ArrayList<T>();for(var f:futures)result.add(f.get(20,TimeUnit.SECONDS));return result;
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
